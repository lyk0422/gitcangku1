package com.example.starter.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 维护窗口与合并裁决的并发边界测试：真实并发提交，验证窗口登记串行化、
 * 批量提交同键只占一键、冲突簇合并只成功一次。
 */
@SpringBootTest
class MaintenanceConcurrencyTest {

    private static final String ACTIVATED_AT = "2000-01-01T00:00:00Z";
    private static final String WINDOW_START = "2020-01-01T00:00:00Z";
    private static final String WINDOW_END = "2030-01-01T00:00:00Z";

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_adjudication");
        jdbcTemplate.update("DELETE FROM cluster_merge_snapshot");
        jdbcTemplate.update("DELETE FROM station_observation");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM observation_station");
        jdbcTemplate.update("DELETE FROM request_log");
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

    private void registerStation(String stationId) {
        maintenanceService.registerStation(
                new RegisterStationRequest("req-setup-" + stationId, stationId, Instant.parse(ACTIVATED_AT)));
    }

    private BatchSubmissionRequest batch(String requestId, String submissionId, String clusterId, String stationId) {
        return new BatchSubmissionRequest(requestId, List.of(new BatchSubmissionRequest.Item(
                submissionId, clusterId, stationId, Instant.parse("2026-09-01T03:00:00Z"),
                "场地", "1.0", "备注")));
    }

    @Test
    void concurrentOverlappingWindowRegistrationsOnlyOneSucceeds() throws Exception {
        registerStation("st-1");

        // 冲突异常作为结果收集而非抛出
        List<Callable<Object>> tasks = List.of(
                () -> {
                    try {
                        return maintenanceService.registerWindow("st-1", new RegisterWindowRequest(
                                "req-w1", "win-1", Instant.parse(WINDOW_START), Instant.parse(WINDOW_END), 1));
                    } catch (RuntimeException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return maintenanceService.registerWindow("st-1", new RegisterWindowRequest(
                                "req-w2", "win-2", Instant.parse(WINDOW_START), Instant.parse(WINDOW_END), 1));
                    } catch (RuntimeException e) {
                        return e;
                    }
                });

        List<Object> outcomes = runConcurrently(tasks);
        long successes = outcomes.stream()
                .filter(o -> o instanceof MaintenanceService.Outcome).count();
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof com.example.starter.observation.ApiException)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer windowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_window", Integer.class);
        assertThat(windowCount).isEqualTo(1);
    }

    @Test
    void concurrentBatchWithSameRequestIdOccupiesSingleKey() throws Exception {
        registerStation("st-1");

        List<Callable<MaintenanceService.Outcome<BatchSubmissionResponse>>> tasks = List.of(
                () -> maintenanceService.submitBatch(batch("req-same", "sub-1", "cl-1", "st-1")),
                () -> maintenanceService.submitBatch(batch("req-same", "sub-1", "cl-1", "st-1")),
                () -> maintenanceService.submitBatch(batch("req-same", "sub-1", "cl-1", "st-1")));

        List<MaintenanceService.Outcome<BatchSubmissionResponse>> outcomes = runConcurrently(tasks);
        // 同键同参并发：全部得到首次结果，只落库一条观测
        assertThat(outcomes).allMatch(o -> o.status() == 201);
        assertThat(outcomes).allMatch(o -> o.body().itemCount() == 1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_observation", Integer.class);
        assertThat(count).isEqualTo(1);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class);
        assertThat(logCount).isEqualTo(1);
    }

    @Test
    void concurrentClusterMergesOnlyOneSnapshotCreated() throws Exception {
        registerStation("st-1");
        maintenanceService.submitBatch(batch("req-b1", "sub-1", "cl-1", "st-1"));
        maintenanceService.submitBatch(batch("req-b2", "sub-2", "cl-1", "st-1"));

        List<Callable<Object>> tasks = List.of(
                () -> {
                    try {
                        return maintenanceService.mergeCluster("cl-1", new MergeClusterRequest("req-m1"));
                    } catch (RuntimeException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return maintenanceService.mergeCluster("cl-1", new MergeClusterRequest("req-m2"));
                    } catch (RuntimeException e) {
                        return e;
                    }
                });

        List<Object> outcomes = runConcurrently(tasks);
        long successes = outcomes.stream()
                .filter(o -> o instanceof MaintenanceService.Outcome).count();
        assertThat(successes).isEqualTo(1);
        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_merge_snapshot WHERE cluster_id = 'cl-1'", Integer.class);
        assertThat(snapshotCount).isEqualTo(1);
    }

    @Test
    void concurrentAdjudicationsWithSameAdjudicationIdReplaySingleRecord() throws Exception {
        registerStation("st-1");
        maintenanceService.submitBatch(batch("req-b1", "sub-1", "cl-1", "st-1"));
        maintenanceService.mergeCluster("cl-1", new MergeClusterRequest("req-m1"));

        List<Callable<MaintenanceService.Outcome<AdjudicationResponse>>> tasks = List.of(
                () -> maintenanceService.adjudicate("cl-1",
                        new AdjudicateRequest("req-a1", "adj-1", "sub-1", "理由", "op")),
                () -> maintenanceService.adjudicate("cl-1",
                        new AdjudicateRequest("req-a2", "adj-1", "sub-1", "理由", "op")));

        List<MaintenanceService.Outcome<AdjudicationResponse>> outcomes = runConcurrently(tasks);
        assertThat(outcomes).allMatch(o -> o.body().adjudicationId().equals("adj-1"));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_adjudication WHERE adjudication_id = 'adj-1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
