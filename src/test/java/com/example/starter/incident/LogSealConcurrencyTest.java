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
import com.example.starter.incident.dto.Requests.SealRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.SealView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 日志封存并发边界测试（真实 H2 行锁）：
 * 并发封存版本严格递增；封存与同刻指令写入按事务提交顺序裁决；
 * 同一 commandKey 并发封存只产生一个包。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LogSealConcurrencyTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private LogSealService sealService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_corrections");
        jdbc.update("DELETE FROM incident_log_seal_items");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-22T00:00:00Z"));
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void advance(long seconds) {
        ((ControllableClock) clock).advanceSeconds(seconds);
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
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

    private String setup(String ik) {
        incidentService.report(new ReportRequest(ik, "S2", "并发封存", "r"));
        advance(5);
        incidentService.takeover(ik, "alice", new TakeoverRequest(key()));
        advance(30);
        return ik;
    }

    @Test
    void concurrentSeals_versionsIncrementWithoutGapOrDuplicate() throws Exception {
        String ik = setup("INC-SEAL-C1");
        Instant cutoff = clock.instant();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> sealService.seal(ik, "alice", new SealRequest(key(), cutoff)));
        }
        List<Object> results = runConcurrently(tasks);

        List<SealView> seals = results.stream().filter(SealView.class::isInstance)
                .map(SealView.class::cast).toList();
        assertThat(seals).hasSize(3);
        assertThat(seals).extracting(SealView::version)
                .containsExactlyInAnyOrder(1, 2, 3);
        // 同一切截至时刻的三个包内容必须完全一致（条目数与摘要相同）
        assertThat(seals).allSatisfy(s -> {
            assertThat(s.recordCount()).isEqualTo(seals.get(0).recordCount());
            assertThat(s.contentHash()).isEqualTo(seals.get(0).contentHash());
        });
        assertThat(sealService.listSeals(ik).seals()).hasSize(3);
    }

    @Test
    void concurrentSealAndSameInstantWrite_commitOrderArbitrates() throws Exception {
        String ik = setup("INC-SEAL-C2");
        Instant boundary = clock.instant();

        List<Object> results = runConcurrently(List.of(
                () -> incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-RACE",
                        "NOTE", "同刻写入", boundary)),
                () -> sealService.seal(ik, "alice", new SealRequest(key(), boundary))));

        Object writeResult = results.get(0);
        Object sealResult = results.get(1);
        // 写入必然成功
        assertThat(writeResult).isNotInstanceOf(Exception.class);

        if (sealResult instanceof SealView seal) {
            // 封存先提交：快照边界内不含同刻动作
            assertThat(seal.items())
                    .filteredOn(i -> "ACTION".equals(i.recordType()) && "ACT-RACE".equals(i.recordKey()))
                    .isEmpty();
        } else {
            // 写入先提交：封存必须以可区分原因 409，且不留包
            assertThat(sealResult).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status().value()).isEqualTo(409);
                assertThat(e.code()).isEqualTo("SEAL_BOUNDARY_WRITE");
            });
            assertThat(sealService.listSeals(ik).seals()).isEmpty();
        }

        // 无论谁先提交，把截至时刻推后后再封存，动作必然进入新包
        advance(5);
        SealView later = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        assertThat(later.items())
                .filteredOn(i -> "ACTION".equals(i.recordType()) && "ACT-RACE".equals(i.recordKey()))
                .hasSize(1);
    }

    @Test
    void concurrentSameCommandKey_sealCreatesExactlyOnePackage() throws Exception {
        String ik = setup("INC-SEAL-C3");
        Instant cutoff = clock.instant();
        String commandKey = key();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> sealService.seal(ik, "alice", new SealRequest(commandKey, cutoff)));
        }
        List<Object> results = runConcurrently(tasks);

        List<SealView> seals = results.stream().filter(SealView.class::isInstance)
                .map(SealView.class::cast).toList();
        assertThat(seals).hasSize(3);
        assertThat(seals).allSatisfy(s -> {
            assertThat(s.version()).isEqualTo(1);
            assertThat(s).isEqualTo(seals.get(0));
        });
        assertThat(sealService.listSeals(ik).seals()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
