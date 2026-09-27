package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealAppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.SealAppendixView;
import com.example.starter.incident.dto.Responses.SealPackageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指挥日志封存并发边界测试：并发封存按事务提交顺序裁决（仅一个版本生效）、
 * 同 commandKey 并发单次生效、并发附录不丢不重、封存与指令写入串行一致。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class SealConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private SealService sealService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_seal_appendices");
        jdbc.update("DELETE FROM incident_seal_edges");
        jdbc.update("DELETE FROM incident_seal_entries");
        jdbc.update("DELETE FROM incident_seal_packages");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发封存", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
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
    void concurrentSeal_sameCutoff_singlePackage() throws Exception {
        commanding("INC-C1", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 并发以相同截至时刻封存：事件行锁串行化，先提交者生成 v1，其余 409
        List<Callable<SealPackageView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> sealService.seal("INC-C1", "alice",
                    new SealCreateRequest(key(), T0.plusSeconds(60))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(SealPackageView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(sealService.sealHistory("INC-C1").packages()).hasSize(1);
    }

    @Test
    void concurrentSeal_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-C2", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        String commandKey = key();
        List<Callable<SealPackageView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> sealService.seal("INC-C2", "alice",
                    new SealCreateRequest(commandKey, T0.plusSeconds(60))));
        }
        List<Object> results = runConcurrently(tasks);

        List<SealPackageView> successes = results.stream()
                .filter(SealPackageView.class::isInstance)
                .map(SealPackageView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且只生成一个包
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(sealService.sealHistory("INC-C2").packages()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentSealAndActionWrite_commitOrderDeterminesSnapshot() throws Exception {
        commanding("INC-C3", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 并发：封存（截至 T0+60）与写入 occurredAt=T0+30 的指令
        List<Object> results = runConcurrently(List.of(
                () -> sealService.seal("INC-C3", "alice",
                        new SealCreateRequest(key(), T0.plusSeconds(60))),
                () -> incidentService.addAction("INC-C3", "alice",
                        new ActionRequest(key(), "A-RACE", "NOTE", "竞争指令",
                                T0.plusSeconds(30)))));

        assertThat(results.get(0)).isInstanceOf(SealPackageView.class);
        // 指令写入与封存按提交顺序裁决：无论谁先提交，最终状态必须一致可解释
        var detail = sealService.sealDetail("INC-C3", 1);
        boolean sealed = detail.entries().stream()
                .anyMatch(e -> e.recordKey().equals("A-RACE"));
        if (!sealed) {
            // 封存先提交：指令落入后续窗口，诊断可见待封存
            var diagnostics = sealService.sealDiagnostics("INC-C3");
            assertThat(diagnostics.pendingActionCount()).isEqualTo(1);
        }
        // 再次封存后指令必然入包（单调推进）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(200));
        sealService.seal("INC-C3", "alice", new SealCreateRequest(key(), T0.plusSeconds(120)));
        var detail2 = sealService.sealDetail("INC-C3", 2);
        boolean sealedInV2 = detail2.entries().stream()
                .anyMatch(e -> e.recordKey().equals("A-RACE"));
        assertThat(sealed || sealedInV2).isTrue();
    }

    @Test
    void concurrentAppendix_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-C4", "alice");
        incidentService.addAction("INC-C4", "alice",
                new ActionRequest(key(), "A1", "NOTE", "原始记录", T0.plusSeconds(10)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        sealService.seal("INC-C4", "alice", new SealCreateRequest(key(), T0.plusSeconds(60)));

        String commandKey = key();
        List<Callable<SealAppendixView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> sealService.appendAppendix("INC-C4", "auditor-1",
                    new SealAppendixRequest(commandKey, 1, "A1", "v1", "r1")));
        }
        List<Object> results = runConcurrently(tasks);

        List<SealAppendixView> successes = results.stream()
                .filter(SealAppendixView.class::isInstance)
                .map(SealAppendixView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(sealService.sealHistory("INC-C4").appendices()).hasSize(1);
    }
}
