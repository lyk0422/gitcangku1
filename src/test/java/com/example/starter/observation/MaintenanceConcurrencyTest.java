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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护窗口顺序裁决与并发边界测试（真实 H2 内存库 + 真实并发线程）：
 * 窗口登记与批量提交并发按提交顺序裁决、同站并发窗口重叠只成功一条、全受影响簇并发双裁决只成功一次。
 */
@SpringBootTest
class MaintenanceConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String ACTIVATED = "2026-09-01T00:00:00Z";
    private static final String T10 = "2026-09-10T10:00:00Z";
    private static final String T11 = "2026-09-10T11:00:00Z";
    private static final String T12 = "2026-09-10T12:00:00Z";
    private static final String T13 = "2026-09-10T13:00:00Z";

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_merge");
        jdbcTemplate.update("DELETE FROM observation_submission");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM station");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(NOW);
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

    private String statusOf(Runnable action) {
        try {
            action.run();
            return "ok";
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        }
    }

    private void registerStation(String requestId, String stationId) {
        maintenanceService.registerStation(
                new RegisterStationRequest(requestId, stationId, Instant.parse(ACTIVATED)));
    }

    private SubmitObservationBatchRequest batchItem(String requestId, String submissionId,
                                                    String stationId, String observationId,
                                                    String submittedAt, String note) {
        SubmitObservationBatchRequest.Item item = new SubmitObservationBatchRequest.Item(
                submissionId, stationId, observationId, Instant.parse(submittedAt),
                "地点", "1.0", note);
        return new SubmitObservationBatchRequest(requestId, List.of(item));
    }

    @Test
    void concurrentWindowRegistrationsOverlappingOnlyOneSucceeds() throws Exception {
        registerStation("req-cw-0", "st-1");
        // 同站两个重叠窗口并发登记：恰好一条 201，另一条 409
        List<Callable<String>> tasks = List.of(
                () -> statusOf(() -> maintenanceService.registerWindow(
                        new RegisterMaintenanceWindowRequest("req-cw-1", "win-a", "st-1",
                                Instant.parse(T10), Instant.parse(T12)))),
                () -> statusOf(() -> maintenanceService.registerWindow(
                        new RegisterMaintenanceWindowRequest("req-cw-2", "win-b", "st-1",
                                Instant.parse(T11), Instant.parse(T13)))));
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer windowRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_window WHERE station_id = 'st-1'", Integer.class);
        assertThat(windowRows).isEqualTo(1);
        // 维护版本号只分配到 1，无跳号
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM maintenance_window WHERE station_id = 'st-1'", Integer.class);
        assertThat(maxVersion).isEqualTo(1);
    }

    @Test
    void concurrentWindowAndSubmissionOrderAdjudicatesMarker() throws Exception {
        registerStation("req-ox-0", "st-1");
        // 窗口登记与落在区间内的批量提交并发：按提交顺序裁决，两种顺序的最终标记集合可核对
        List<Callable<String>> tasks = List.of(
                () -> statusOf(() -> maintenanceService.registerWindow(
                        new RegisterMaintenanceWindowRequest("req-ox-1", "win-1", "st-1",
                                Instant.parse(T10), Instant.parse(T12)))),
                () -> statusOf(() -> maintenanceService.submitBatch(
                        batchItem("req-ox-2", "sub-1", "st-1", "obs-1", T11, "并发观测"))));
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsOnly("ok");

        Boolean affected = jdbcTemplate.queryForObject(
                "SELECT maintenance_affected FROM observation_submission WHERE submission_id = 'sub-1'",
                Boolean.class);
        String windowId = jdbcTemplate.queryForObject(
                "SELECT window_id FROM observation_submission WHERE submission_id = 'sub-1'", String.class);
        Integer windowRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_window WHERE station_id = 'st-1'", Integer.class);
        assertThat(windowRows).isEqualTo(1);
        // 窗口先提交则观测按新版本标记受影响并固化 win-1；观测先提交则按提交时版本保留未受影响
        if (affected) {
            assertThat(windowId).isEqualTo("win-1");
        } else {
            assertThat(windowId).isNull();
        }
        // 无论哪种顺序，后续再提交一条区间内观测必然按当前版本标记受影响，且早先标记不改写
        maintenanceService.submitBatch(
                batchItem("req-ox-3", "sub-2", "st-1", "obs-1", T11, "后续观测"));
        Boolean secondAffected = jdbcTemplate.queryForObject(
                "SELECT maintenance_affected FROM observation_submission WHERE submission_id = 'sub-2'",
                Boolean.class);
        assertThat(secondAffected).isTrue();
        Boolean firstAffectedStored = jdbcTemplate.queryForObject(
                "SELECT maintenance_affected FROM observation_submission WHERE submission_id = 'sub-1'",
                Boolean.class);
        assertThat(firstAffectedStored).isEqualTo(affected);
    }

    @Test
    void concurrentDecisionsOnPendingClusterOnlyOneApplies() throws Exception {
        registerStation("req-cd-0", "st-1");
        maintenanceService.registerWindow(new RegisterMaintenanceWindowRequest(
                "req-cd-1", "win-1", "st-1", Instant.parse(T10), Instant.parse(T12)));
        maintenanceService.submitBatch(new SubmitObservationBatchRequest("req-cd-2", List.of(
                new SubmitObservationBatchRequest.Item("sub-d1", "st-1", "obs-1",
                        Instant.parse(T11), "地点A", "1.0", "候选A"),
                new SubmitObservationBatchRequest.Item("sub-d2", "st-1", "obs-1",
                        Instant.parse(T11).plusSeconds(1), "地点B", "2.0", "候选B"))));
        maintenanceService.mergeCluster(new MergeClusterRequest(
                "req-cd-3", "obs-1", List.of("sub-d1", "sub-d2")));
        String state = jdbcTemplate.queryForObject(
                "SELECT state FROM cluster_merge WHERE merge_id = 'req-cd-3'", String.class);
        assertThat(state).isEqualTo("PENDING_MANUAL");

        // 两个不同裁决（不同胜出者/裁决人/理由/requestId）并发：恰好一方成功，另一方 409
        List<Callable<String>> tasks = List.of(
                () -> statusOf(() -> maintenanceService.decide(new ManualDecisionRequest(
                        "req-cd-4", "req-cd-3", "sub-d1", "op-a", "理由A"))),
                () -> statusOf(() -> maintenanceService.decide(new ManualDecisionRequest(
                        "req-cd-5", "req-cd-3", "sub-d2", "op-b", "理由B"))));
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsExactlyInAnyOrder("ok", "409");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, winning_submission_id, adjudicator, new_version FROM cluster_merge "
                        + "WHERE merge_id = 'req-cd-3'");
        assertThat(row.get("state")).isEqualTo("RESOLVED");
        String winner = (String) row.get("winning_submission_id");
        String expectedReading = "sub-d1".equals(winner) ? "1.0" : "2.0";
        String expectedAdjudicator = "sub-d1".equals(winner) ? "op-a" : "op-b";
        assertThat(row.get("adjudicator")).isEqualTo(expectedAdjudicator);
        assertThat(row.get("new_version")).isEqualTo(1);
        // 观测版本只生成一次，内容与成功裁决一致
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
        String reading = jdbcTemplate.queryForObject(
                "SELECT reading FROM observation_current WHERE observation_id = 'obs-1'", String.class);
        assertThat(reading).isEqualTo(expectedReading);
        Boolean affected = jdbcTemplate.queryForObject(
                "SELECT maintenance_affected FROM observation_current WHERE observation_id = 'obs-1'",
                Boolean.class);
        assertThat(affected).isTrue();
        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission WHERE observation_id = 'obs-1' AND status = 'CONSUMED'",
                Integer.class);
        assertThat(consumed).isEqualTo(2);
        // 失败裁决不占键：其 requestId 可用于新的操作
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-cd-4','req-cd-5')",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameBatchRequestIdReplaysSingleBatch() throws Exception {
        registerStation("req-cb-0", "st-1");
        List<Callable<MaintenanceService.MaintenanceOutcome<BatchSubmitResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> maintenanceService.submitBatch(
                    batchItem("req-cb-1", "sub-cb", "st-1", "obs-1", T11, "并发同参")));
        }
        List<MaintenanceService.MaintenanceOutcome<BatchSubmitResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().submittedCount() == 1);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission WHERE submission_id = 'sub-cb'", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cb-1'", Integer.class)).isEqualTo(1);
    }
}
