package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁运与到期发布并发边界测试：真实并发提交，验证同一簇并发发布只生成一个不可变快照、
 * 同 requestId 并发重放只占一键、并发设置禁运只有一个胜者（真实 H2 内存库，固定 UTC 时钟）。
 */
@SpringBootTest
class EmbargoConcurrencyTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T02:00:00Z");

    @Autowired
    private EmbargoService embargoService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM disclosure_addendum");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM open_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    private void setEmbargo(String observationId, String requestId, Instant until) {
        embargoService.setEmbargoBatch(new SetEmbargoRequest(requestId, "ops",
                List.of(new EmbargoItemRequest(observationId, until, List.of("PUBLIC")))));
    }

    private int rowCount(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentReleaseGeneratesSingleSnapshot() throws Exception {
        createObservation("obs-r1", "req-c0");
        // 截止时刻等于固定时钟时刻：左闭右开，已到期
        setEmbargo("obs-r1", "req-e0", FIXED_NOW);

        List<Callable<EmbargoService.ReleaseOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> embargoService.release("obs-r1",
                    new ReleaseEmbargoRequest("req-r" + index, "ops")));
        }
        List<EmbargoService.ReleaseOutcome> outcomes = runConcurrently(tasks);

        // 全部成功：恰好一个首次发布（201），其余返回同一快照（200）
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200 || outcome.status() == 201);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == 201).hasSize(1);
        assertThat(outcomes).allMatch(outcome -> outcome.body().winningVersion() == 1);
        assertThat(outcomes).allMatch(outcome -> outcome.body().releasedAtUtc().equals(FIXED_NOW));
        assertThat(rowCount("disclosure_snapshot", "obs-r1")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdReleaseReplaysSingleResult() throws Exception {
        createObservation("obs-r2", "req-c0");
        setEmbargo("obs-r2", "req-e0", FIXED_NOW);

        List<Callable<EmbargoService.ReleaseOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> embargoService.release("obs-r2",
                    new ReleaseEmbargoRequest("req-same", "ops")));
        }
        List<EmbargoService.ReleaseOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().winningVersion() == 1);
        assertThat(rowCount("disclosure_snapshot", "obs-r2")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentEmbargoSetOnSameObservationYieldsSingleWinner() throws Exception {
        createObservation("obs-r3", "req-c0");

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    embargoService.setEmbargoBatch(new SetEmbargoRequest("req-e" + index, "ops",
                            List.of(new EmbargoItemRequest("obs-r3",
                                    FIXED_NOW.plusSeconds(3600), List.of("PUBLIC")))));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        // 禁运不可变：恰好一个批次生效，其余 409
        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        assertThat(rowCount("observation_embargo", "obs-r3")).isEqualTo(1);
    }

    @Test
    void concurrentReleaseAndCorrectionSerializeInCommitOrder() throws Exception {
        createObservation("obs-r4", "req-c0");
        setEmbargo("obs-r4", "req-e0", FIXED_NOW);

        // 发布与更正并发：行锁按提交顺序裁决，更正若在发布后生效则必须落入附页
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        embargoService.release("obs-r4", new ReleaseEmbargoRequest("req-r0", "ops"));
                        return "released";
                    } catch (ApiException e) {
                        return "release-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-r4",
                                new MergeObservationRequest("req-m0", 1, "站点B", "2.0", "并发更正"));
                        return "merged";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("released", "merged");
        assertThat(rowCount("disclosure_snapshot", "obs-r4")).isEqualTo(1);
        ObservationResponse current = observationService.getCurrent("obs-r4");
        assertThat(current.version()).isEqualTo(2);
        // 提交顺序决定快照内容：先发布则快照为版本 1 且更正进入附页；先合并则快照为版本 2 且无附页
        DisclosureResponse snapshot = embargoService.getDisclosure("obs-r4");
        List<AddendumResponse> addenda = embargoService.listAddenda("obs-r4");
        if (snapshot.winningVersion() == 1) {
            assertThat(addenda).hasSize(1);
            assertThat(addenda.get(0).version()).isEqualTo(2);
            assertThat(addenda.get(0).location()).isEqualTo("站点B");
        } else {
            assertThat(snapshot.winningVersion()).isEqualTo(2);
            assertThat(snapshot.location()).isEqualTo("站点B");
            assertThat(addenda).isEmpty();
        }
    }
}
