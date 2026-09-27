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
 * 维护窗口业务并发边界测试（真实 H2 内存库 + 真实线程并发，协调启动并设超时）：
 * 重叠窗口登记串行化、同簇并发合并序号不重复、同快照并发裁决只成功一次、同 requestId 并发只写一批。
 */
@SpringBootTest
class MaintenanceConcurrencyTest {

    private static final Instant ENABLED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T = Instant.parse("2026-09-10T00:00:00Z");

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_adjudication");
        jdbcTemplate.update("DELETE FROM cluster_snapshot_window");
        jdbcTemplate.update("DELETE FROM cluster_snapshot_evidence");
        jdbcTemplate.update("DELETE FROM cluster_snapshot");
        jdbcTemplate.update("DELETE FROM station_observation");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM station");
        jdbcTemplate.update("DELETE FROM maintenance_request_log");
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

    private void registerStation(String requestId, String stationId) {
        maintenanceService.registerStation(new RegisterStationRequest(requestId, stationId, ENABLED));
    }

    private RegisterWindowRequest windowRequest(String requestId, String windowId,
                                                Instant start, Instant end) {
        return new RegisterWindowRequest(requestId, windowId, start, end);
    }

    private ObservationItem item(String id, String stationId, String clusterId,
                                 Instant submittedAt, String payload) {
        return new ObservationItem(id, stationId, clusterId, submittedAt, payload);
    }

    @Test
    void concurrentOverlappingWindowsRegisterExactlyOne() throws Exception {
        registerStation("req-cs-0", "st-1");
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        maintenanceService.registerWindow("st-1",
                                windowRequest("req-cw-1", "win-a", T, T.plusSeconds(3600)));
                        return "ok";
                    } catch (MaintenanceApiException e) {
                        return e.errorCode();
                    }
                },
                () -> {
                    try {
                        maintenanceService.registerWindow("st-1",
                                windowRequest("req-cw-2", "win-b",
                                        T.plusSeconds(1800), T.plusSeconds(5400)));
                        return "ok";
                    } catch (MaintenanceApiException e) {
                        return e.errorCode();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "WINDOW_OVERLAP");
        Integer windowRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_window WHERE station_id = 'st-1'", Integer.class);
        assertThat(windowRows).isEqualTo(1);
    }

    @Test
    void concurrentNonOverlappingWindowsBothRegisterWithDistinctVersions() throws Exception {
        registerStation("req-cs-1", "st-2");
        List<Callable<String>> tasks = List.of(
                () -> {
                    maintenanceService.registerWindow("st-2",
                            windowRequest("req-cw-3", "win-a", T, T.plusSeconds(3600)));
                    return "ok";
                },
                () -> {
                    maintenanceService.registerWindow("st-2",
                            windowRequest("req-cw-4", "win-b",
                                    T.plusSeconds(3600), T.plusSeconds(7200)));
                    return "ok";
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactly("ok", "ok");
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT maintenance_version FROM maintenance_window WHERE station_id = 'st-2' "
                        + "ORDER BY maintenance_version", Integer.class);
        // 站点行锁串行化版本号分配：版本恰好为 1、2，无重复无跳号冲突
        assertThat(versions).containsExactly(1, 2);
    }

    @Test
    void concurrentMergesOnSameClusterProduceDistinctSequences() throws Exception {
        registerStation("req-cs-2", "st-3");
        maintenanceService.submitObservations(new SubmitObservationsRequest("req-cb-0", List.of(
                item("obs-a", "st-3", "cl-1", T, "a"),
                item("obs-b", "st-3", "cl-1", T.plusSeconds(60), "b"))));

        List<Callable<Integer>> tasks = List.of(
                () -> maintenanceService.mergeCluster("cl-1", new MergeClusterRequest("req-cm-1")).status(),
                () -> maintenanceService.mergeCluster("cl-1", new MergeClusterRequest("req-cm-2")).status());
        List<Integer> statuses = runConcurrently(tasks);

        // 站点行锁串行化：两次合并都成功并追加序号 1、2，唯一键不允许出现重复序号
        assertThat(statuses).containsExactly(200, 200);
        List<Integer> sequences = jdbcTemplate.queryForList(
                "SELECT sequence_no FROM cluster_snapshot WHERE cluster_id = 'cl-1' ORDER BY sequence_no",
                Integer.class);
        assertThat(sequences).containsExactly(1, 2);
        Integer evidenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_snapshot_evidence", Integer.class);
        assertThat(evidenceRows).isEqualTo(4);
    }

    @Test
    void concurrentAdjudicationsOfSamePendingSnapshotSucceedOnce() throws Exception {
        registerStation("req-cs-3", "st-4");
        maintenanceService.registerWindow("st-4", windowRequest(
                "req-cw-5", "win-1", T, T.plusSeconds(7200)));
        maintenanceService.submitObservations(new SubmitObservationsRequest("req-cb-1", List.of(
                item("obs-a", "st-4", "cl-2", T.plusSeconds(60), "受影响A"),
                item("obs-b", "st-4", "cl-2", T.plusSeconds(120), "受影响B"))));
        maintenanceService.mergeCluster("cl-2", new MergeClusterRequest("req-cm-3"));

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        maintenanceService.adjudicateCluster("cl-2", new AdjudicateClusterRequest(
                                "req-ca-1", "adj-1", "obs-a", "选择A", "op-1"));
                        return "ok-a";
                    } catch (MaintenanceApiException e) {
                        return e.errorCode();
                    }
                },
                () -> {
                    try {
                        maintenanceService.adjudicateCluster("cl-2", new AdjudicateClusterRequest(
                                "req-ca-2", "adj-2", "obs-b", "选择B", "op-2"));
                        return "ok-b";
                    } catch (MaintenanceApiException e) {
                        return e.errorCode();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 同一待裁决快照只允许裁决一次：恰好一个成功，另一个冲突
        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(r -> r.startsWith("ok")).count()).isEqualTo(1);
        assertThat(results).contains("ADJUDICATION_CONFLICT");
        Integer adjudicationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_adjudication WHERE cluster_id = 'cl-2'", Integer.class);
        assertThat(adjudicationRows).isEqualTo(1);
        // 失败事务回滚，不占 requestId
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_request_log WHERE request_id IN ('req-ca-1','req-ca-2')",
                Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdBatchSubmitWritesSingleBatch() throws Exception {
        registerStation("req-cs-4", "st-5");
        SubmitObservationsRequest request = new SubmitObservationsRequest("req-cbi-1", List.of(
                item("obs-a", "st-5", null, T, "a"),
                item("obs-b", "st-5", null, T.plusSeconds(60), "b")));
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> maintenanceService.submitObservations(request).status());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsExactly(201, 201, 201, 201);
        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_observation", Integer.class);
        assertThat(observationRows).isEqualTo(2);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-cbi-1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }
}
