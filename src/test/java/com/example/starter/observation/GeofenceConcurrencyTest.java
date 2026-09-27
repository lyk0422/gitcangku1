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
 * 地理围栏并发边界测试：真实并发提交，验证同站点重叠围栏只落一个、
 * 同键批量重放只占一键、同一提交并发裁决只成功一次（真实 H2 内存库）。
 */
@SpringBootTest
class GeofenceConcurrencyTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant COLLECTED = Instant.parse("2026-06-01T00:00:00Z");

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
        jdbcTemplate.update("DELETE FROM geofence");
        jdbcTemplate.update("DELETE FROM geofence_site_lock");
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

    private CreateGeofenceRequest fenceRequest(String requestId, String fenceId, String siteId,
                                               String minLat, String maxLat) {
        return new CreateGeofenceRequest(requestId, fenceId, siteId,
                new BigDecimal(minLat), new BigDecimal(maxLat),
                new BigDecimal("120"), new BigDecimal("130"), FROM, TO, "tester");
    }

    private SubmissionItem item(String submissionId, String siteId, String observationId,
                                String latitude) {
        return new SubmissionItem(submissionId, siteId, observationId, 1, "站点A东", "2.0", "并发",
                new BigDecimal(latitude), new BigDecimal("121"), COLLECTED);
    }

    private void createSiteAFence() {
        geofenceService.create(fenceRequest("req-f0", "fence-1", "site-A", "30", "40"));
    }

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    @Test
    void concurrentOverlappingFenceCreatesYieldSingleFence() throws Exception {
        // 同站点、生效区间重叠的两个围栏并发创建：站点锁串行化后恰好一个成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geofenceService.create(fenceRequest("req-cf1", "fence-a", "site-A", "30", "40"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        geofenceService.create(fenceRequest("req-cf2", "fence-b", "site-A", "35", "45"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer fenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence WHERE site_id = 'site-A'", Integer.class);
        assertThat(fenceRows).isEqualTo(1);
    }

    @Test
    void concurrentSameFenceIdCreatesYieldSingleFence() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    geofenceService.create(fenceRequest("req-ci" + index, "fence-1", "site-A", "30", "40"));
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
                "SELECT COUNT(*) FROM geofence WHERE fence_id = 'fence-1'", Integer.class);
        assertThat(fenceRows).isEqualTo(1);
    }

    @Test
    void concurrentFenceUpdatesOnlyVersionMatcherSucceeds() throws Exception {
        createSiteAFence();

        // 两个变更都期望当前版本 1：先到者推进到版本 2，后到者版本不匹配 409
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geofenceService.update("fence-1", new UpdateGeofenceRequest("req-cu1", 1,
                                new BigDecimal("31"), new BigDecimal("39"),
                                new BigDecimal("120"), new BigDecimal("130"), FROM, TO, "tester"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        geofenceService.update("fence-1", new UpdateGeofenceRequest("req-cu2", 1,
                                new BigDecimal("32"), new BigDecimal("38"),
                                new BigDecimal("120"), new BigDecimal("130"), FROM, TO, "tester"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        assertThat(geofenceService.getCurrent("fence-1").fenceVersion()).isEqualTo(2);
        Integer fenceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence WHERE fence_id = 'fence-1'", Integer.class);
        assertThat(fenceRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdBatchReplaysSingleResult() throws Exception {
        createSiteAFence();
        createObservation("obs-1", "req-o1");

        // 同一 requestId、相同批量并发提交 4 次：全部返回同一结果，只产生一条提交记录、一次合并
        List<Callable<SubmissionService.BatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> submissionService.submitBatch(new BatchSubmissionRequest(
                    "req-cb1", "tester", List.of(item("sub-1", "site-A", "obs-1", "31.5")))));
        }
        List<SubmissionService.BatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().acceptedCount() == 1);
        assertThat(outcomes).allMatch(outcome -> outcome.body().items().get(0).mergedVersion() == 2);
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission", Integer.class);
        assertThat(submissionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cb1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
    }

    @Test
    void concurrentAdjudicationsOnSameSubmissionYieldSingleRecord() throws Exception {
        createSiteAFence();
        createObservation("obs-1", "req-o1");
        // 围栏外提交：GEOFENCE_REJECTED
        submissionService.submitBatch(new BatchSubmissionRequest("req-b1", "tester",
                List.of(item("sub-1", "site-A", "obs-1", "50"))));
        assertThat(submissionService.getSubmission("sub-1").status())
                .isEqualTo(SubmissionRecord.STATUS_GEOFENCE_REJECTED);

        // 两个不同 adjudicationId 的裁决并发：行锁串行化后恰好一个成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        submissionService.adjudicate("sub-1", new AdjudicateSubmissionRequest(
                                "req-ca1", "adj-1", "ACCEPT", "核实有效", "reviewer"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        submissionService.adjudicate("sub-1", new AdjudicateSubmissionRequest(
                                "req-ca2", "adj-2", "ACCEPT", "复核有效", "reviewer"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer adjudicationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_adjudication", Integer.class);
        assertThat(adjudicationRows).isEqualTo(1);
        assertThat(submissionService.getSubmission("sub-1").status())
                .isEqualTo(SubmissionRecord.STATUS_MANUAL_ACCEPTED);
        // 只合并一次：观测记录推进到版本 2
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
    }

    @Test
    void concurrentBatchesOnSameObservationSerializeMerges() throws Exception {
        createSiteAFence();
        createObservation("obs-1", "req-o1");

        // 两个批量都基于版本 1 改同一字段：串行化后一个合并成功，另一个三方冲突 409，不留部分结果
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        submissionService.submitBatch(new BatchSubmissionRequest("req-cm1", "tester",
                                List.of(new SubmissionItem("sub-1", "site-A", "obs-1", 1,
                                        "站点B", "1.0", "初始备注", new BigDecimal("31.5"),
                                        new BigDecimal("121"), COLLECTED))));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        submissionService.submitBatch(new BatchSubmissionRequest("req-cm2", "tester",
                                List.of(new SubmissionItem("sub-2", "site-A", "obs-1", 1,
                                        "站点C", "1.0", "初始备注", new BigDecimal("32"),
                                        new BigDecimal("121"), COLLECTED))));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        // 失败者整批回滚：只有胜者的提交记录
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission", Integer.class);
        assertThat(submissionRows).isEqualTo(1);
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
    }
}
