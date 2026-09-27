package com.example.starter.observation.geofence;

import com.example.starter.observation.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 地理围栏并发与幂等边界测试：真实并发提交，验证同站点围栏串行化、
 * 批量提交唯一键兜底、同键并发重放与并发裁决只占一键。
 */
@SpringBootTest
class GeofenceConcurrencyTest {

    @Autowired
    private GeofenceService geofenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM geofence_adjudication");
        jdbcTemplate.update("DELETE FROM geofence_observation");
        jdbcTemplate.update("DELETE FROM site_geofence");
        jdbcTemplate.update("DELETE FROM geofence_site");
        jdbcTemplate.update("DELETE FROM geofence_request_log");
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

    private CreateFenceRequest fenceRequest(String requestId, String fenceId, String siteId,
                                            String from, String to) {
        return new CreateFenceRequest(requestId, fenceId, siteId,
                new BigDecimal("30"), new BigDecimal("31"), new BigDecimal("120"), new BigDecimal("121"),
                from, to, "op-admin");
    }

    private BatchObservationRequest batchRequest(String requestId, String observationId, String latitude) {
        ObservationItemRequest item = new ObservationItemRequest(observationId, "site-1",
                new BigDecimal(latitude), new BigDecimal("120.5"), "2026-09-28T11:00:00Z", null);
        return new BatchObservationRequest(requestId, "op-field", List.of(item));
    }

    @Test
    void concurrentOverlappingFenceCreatesYieldSingleFence() throws Exception {
        // 两个并发围栏配置：同站点、生效区间重叠，串行化后恰好一个成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geofenceService.createFence(fenceRequest("req-gc1", "fence-c1", "site-1",
                                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z"));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        geofenceService.createFence(fenceRequest("req-gc2", "fence-c2", "site-1",
                                "2026-09-28T11:00:00Z", "2026-09-28T13:00:00Z"));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("created", "409");
        Integer fenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_geofence", Integer.class);
        assertThat(fenceRows).isEqualTo(1);
    }

    @Test
    void concurrentBatchesWithSameObservationIdYieldSingleBatch() throws Exception {
        // 两个并发批量提交包含同一 observationId：主键兜底，恰好一个整批成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geofenceService.submitBatch(batchRequest("req-bc1", "obs-shared", "30.5"));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        geofenceService.submitBatch(batchRequest("req-bc2", "obs-shared", "30.6"));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("created", "409");
        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_observation WHERE observation_id = 'obs-shared'", Integer.class);
        assertThat(observationRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdBatchReplaysSingleResult() throws Exception {
        // 同一 requestId、相同参数并发提交 4 次：全部返回同一快照，只落库一批
        List<Callable<GeofenceService.GeofenceOutcome<BatchObservationResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> geofenceService.submitBatch(batchRequest("req-bc3", "obs-replay", "30.5")));
        }
        List<GeofenceService.GeofenceOutcome<BatchObservationResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().submittedAtUtc().equals(outcomes.get(0).body().submittedAtUtc()));
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_request_log WHERE request_id = 'req-bc3'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_observation WHERE observation_id = 'obs-replay'", Integer.class);
        assertThat(observationRows).isEqualTo(1);
    }

    @Test
    void concurrentAdjudicationsOnSameObservationYieldSingleRecord() throws Exception {
        // 先落库一条被拒观测
        geofenceService.createFence(fenceRequest("req-ac0", "fence-a1", "site-1",
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z"));
        geofenceService.submitBatch(batchRequest("req-ac1", "obs-rej", "32.0"));

        // 两个并发裁决（不同 adjudicationId）：恰好一个生效，观测状态只翻转一次
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geofenceService.adjudicate("obs-rej",
                                new AdjudicateRequest("req-ac2", "adj-c1", "op-review", "理由一"));
                        return "accepted";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        geofenceService.adjudicate("obs-rej",
                                new AdjudicateRequest("req-ac3", "adj-c2", "op-review", "理由二"));
                        return "accepted";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("accepted", "409");
        Integer adjudicationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_adjudication WHERE observation_id = 'obs-rej'", Integer.class);
        assertThat(adjudicationRows).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM geofence_observation WHERE observation_id = 'obs-rej'", String.class);
        assertThat(status).isEqualTo("ACCEPTED");
    }
}
