package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 日志封存并发边界测试：验证事件行锁与依赖图全局锁下，
 * 并发封存版本唯一递增、封存与指令写入按事务提交顺序裁决（无部分包）、
 * 同 commandKey 并发封存只生效一次。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class IncidentSealConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant CUTOFF = T0.plusSeconds(1000);

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentSealService sealService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_appendices");
        jdbc.update("DELETE FROM incident_log_seal_edges");
        jdbc.update("DELETE FROM incident_log_seal_records");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S1", "并发封存", "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(CUTOFF);
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常）。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSeals_versionsUniqueAndIncreasing() throws Exception {
        commanding("INC-700", "alice");
        List<Callable<SealView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> sealService.createSeal("INC-700", "alice",
                    new SealCreateRequest(key(), CUTOFF)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(SealView.class));
        Set<Integer> versions = new HashSet<>();
        for (Object r : results) {
            versions.add(((SealView) r).version());
        }
        // 版本唯一且连续递增
        assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(sealService.listSeals("INC-700").seals()).hasSize(4);
    }

    @Test
    void concurrentSealAndActionWrite_commitOrderConsistentNoPartial() throws Exception {
        commanding("INC-701", "alice");
        incidentService.addAction("INC-701", "alice", new ActionRequest(key(), "ACT-0",
                "NOTE", "封存前记录", T0.plusSeconds(5)));

        List<Object> results = runConcurrently(List.of(
                () -> sealService.createSeal("INC-701", "alice",
                        new SealCreateRequest(key(), CUTOFF)),
                () -> incidentService.addAction("INC-701", "alice", new ActionRequest(key(),
                        "ACT-RACE", "NOTE", "竞态记录", T0.plusSeconds(10)))));

        // 两个写操作都成功（行锁串行化，按提交顺序生效）
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        // 指令最终落库
        assertThat(incidentService.history("INC-701").actions()).hasSize(2);
        // 无部分包：包内记录数与包头计数一致，竞态指令要么完整入包要么完整不入
        SealDetailView detail = sealService.getSeal("INC-701", 1);
        boolean inPackage = detail.records().stream()
                .anyMatch(r -> r.recordKey().equals("ACT-RACE"));
        int expected = inPackage ? 2 : 1;
        assertThat(detail.records()).hasSize(expected);
        assertThat(detail.seal().recordCount()).isEqualTo(expected);
    }

    @Test
    void concurrentSameCommandKeySeal_singlePackage() throws Exception {
        commanding("INC-702", "alice");
        String commandKey = key();
        List<Callable<SealView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> sealService.createSeal("INC-702", "alice",
                    new SealCreateRequest(commandKey, CUTOFF)));
        }
        List<Object> results = runConcurrently(tasks);

        List<SealView> successes = results.stream().filter(SealView.class::isInstance)
                .map(SealView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且只生成一个封存包
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(sealService.listSeals("INC-702").seals()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
