package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 地理围栏、离线提交与人工裁决的并发/幂等边界测试：真实并发操作，
 * 验证站点锁串行化、围栏改版串行化、同 requestId 并发重放与同提交并发裁决的结果与最终数据。
 */
@SpringBootTest
class SubmissionConcurrencyTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-09-15T08:00:00Z");

    @Autowired
    private GeofenceService geofenceService;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM submission_adjudication");
        jdbcTemplate.update("DELETE FROM observation_submission");
        jdbcTemplate.update("DELETE FROM geofence_version");
        jdbcTemplate.update("DELETE FROM geofence_fence");
        jdbcTemplate.update("DELETE FROM geofence_site");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
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

    private CreateGeofenceRequest fenceRequest(String requestId, String fenceId, String siteId) {
        return new CreateGeofenceRequest(requestId, "op-1", fenceId, siteId,
                new BigDecimal("31.0"), new BigDecimal("32.0"),
                new BigDecimal("121.0"), new BigDecimal("122.0"), FROM, TO);
    }

    @Test
    void concurrentOverlappingFencesAtSameSiteYieldSingleWinner() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    geofenceService.create(fenceRequest("req-f" + index, "fence-" + index, "site-1"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        Integer fenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_fence WHERE site_id = 'site-1'", Integer.class);
        assertThat(fenceRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_version", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }

    @Test
    void concurrentRevisionsOnSameFenceSerializeToSequentialVersions() throws Exception {
        geofenceService.create(fenceRequest("req-f0", "fence-1", "site-1"));

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    ReviseGeofenceRequest request = new ReviseGeofenceRequest(
                            "req-r" + index, "op-1",
                            new BigDecimal("31.0"), new BigDecimal("32." + (1 + index)),
                            new BigDecimal("121.0"), new BigDecimal("122.0"), FROM, TO);
                    return geofenceService.revise("fence-1", request).body().version();
                } catch (ApiException e) {
                    return -e.status().value();
                }
            });
        }
        List<Integer> versions = runConcurrently(tasks);

        assertThat(versions).containsExactlyInAnyOrder(2, 3, 4);
        GeofenceResponse current = geofenceService.getCurrent("fence-1");
        assertThat(current.version()).isEqualTo(4);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_version WHERE fence_id = 'fence-1'", Integer.class);
        assertThat(versionRows).isEqualTo(4);
    }

    @Test
    void concurrentSameBatchRequestIdReplaysSingleResult() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-o0", "obs-1", "站点A", "1.0", "备注"));
        geofenceService.create(fenceRequest("req-f0", "fence-1", "site-1"));

        List<Callable<SubmissionService.BatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> submissionService.submitBatch(batchRequest("req-b1", "sub-1")));
        }
        List<SubmissionService.BatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().results().get(0).observationVersion() == 2);
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission", Integer.class);
        assertThat(submissionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-b1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
    }

    @Test
    void concurrentBatchesOnSameObservationSerializeMerges() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-o0", "obs-1", "站点A", "1.0", "备注"));
        geofenceService.create(fenceRequest("req-f0", "fence-1", "site-1"));

        // 各端基于版本 1 改不同字段，围栏内提交：行锁串行化后三方合并应全部保留
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        submissionService.submitBatch(batchRequest("req-b1", "sub-1", "站点B", "1.0"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        submissionService.submitBatch(batchRequest("req-b2", "sub-2", "站点A", "2.0"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactly("ok", "ok");
        ObservationResponse current = observationService.getCurrent("obs-1");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("站点B");
        assertThat(current.reading()).isEqualTo("2.0");
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission", Integer.class);
        assertThat(submissionRows).isEqualTo(2);
    }

    @Test
    void concurrentAdjudicationsOnSameRejectedSubmissionYieldSingleAccept() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-o0", "obs-1", "站点A", "1.0", "备注"));
        geofenceService.create(fenceRequest("req-f0", "fence-1", "site-1"));
        submissionService.submitBatch(rejectedBatchRequest("req-b0", "sub-1"));

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    submissionService.adjudicate("sub-1", new AdjudicateSubmissionRequest(
                            "req-a" + index, "op-review", "ACCEPT", "并发裁决理由"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        Integer adjudicationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_adjudication WHERE submission_id = 'sub-1'",
                Integer.class);
        assertThat(adjudicationRows).isEqualTo(1);
        // 裁决只合并一次：观测版本只前进一次
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
    }

    private BatchSubmitObservationsRequest batchRequest(String requestId, String submissionId) {
        return batchRequest(requestId, submissionId, "站点B", "2.0");
    }

    private BatchSubmitObservationsRequest batchRequest(
            String requestId, String submissionId, String location, String reading) {
        SubmissionItemRequest item = new SubmissionItemRequest(submissionId, "obs-1", "site-1",
                1, location, reading, "备注",
                new BigDecimal("31.5"), new BigDecimal("121.5"), CAPTURED);
        return new BatchSubmitObservationsRequest(requestId, "op-field", List.of(item));
    }

    private BatchSubmitObservationsRequest rejectedBatchRequest(String requestId, String submissionId) {
        SubmissionItemRequest item = new SubmissionItemRequest(submissionId, "obs-1", "site-1",
                1, "站点B", "2.0", "围栏外",
                new BigDecimal("35.0"), new BigDecimal("121.5"), CAPTURED);
        return new BatchSubmitObservationsRequest(requestId, "op-field", List.of(item));
    }
}
