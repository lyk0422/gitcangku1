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
 * 地理围栏并发与幂等边界测试：真实并发提交，验证同键并发重放只占一键、
 * 人工接受串行化、并发批量合并基于最新版本重新判定。
 */
@SpringBootTest
class GeofenceConcurrencyTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant INSIDE_TIME = Instant.parse("2026-06-15T10:00:00Z");

    @Autowired
    private GeofenceService geofenceService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM geofence_submission");
        jdbcTemplate.update("DELETE FROM geofence");
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

    private void createFence() {
        geofenceService.createGeofence(new CreateGeofenceRequest("req-cf", "fence-1", "site-1",
                "ops-1", new BigDecimal("30"), new BigDecimal("40"),
                new BigDecimal("120"), new BigDecimal("130"), FROM, TO));
    }

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "地点A", "1.0", "初始备注"));
    }

    private GeofenceSubmissionItem item(String submissionId, String observationId, int baseVersion,
                                        String location, String reading, String note,
                                        String latitude, String longitude) {
        return new GeofenceSubmissionItem(submissionId, "site-1", observationId, baseVersion,
                location, reading, note, new BigDecimal(latitude), new BigDecimal(longitude), INSIDE_TIME);
    }

    private BatchGeofenceSubmissionRequest batch(String requestId, GeofenceSubmissionItem... items) {
        return new BatchGeofenceSubmissionRequest(requestId, "field-1", List.of(items));
    }

    @Test
    void concurrentSameRequestIdBatchSubmitsOnlyOnce() throws Exception {
        createFence();
        createObservation("obs-c1", "req-co1");
        BatchGeofenceSubmissionRequest request = batch("req-cb1",
                item("sub-c1", "obs-c1", 1, "地点B", "2.0", "并发同键", "31", "121"));

        List<GeofenceService.Outcome<BatchGeofenceSubmissionResponse>> outcomes = runConcurrently(List.of(
                () -> geofenceService.submitBatch(request),
                () -> geofenceService.submitBatch(request),
                () -> geofenceService.submitBatch(request)));

        // 同键同参并发：全部得到首次成功结果，状态只推进一次
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.stream().map(outcome -> outcome.body().items().getFirst().submissionId()))
                .containsOnly("sub-c1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cb1'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_submission", Long.class)).isEqualTo(1);
        assertThat(observationService.getCurrent("obs-c1").version()).isEqualTo(2);
    }

    @Test
    void concurrentManualAcceptsOnlyOneWins() throws Exception {
        createFence();
        createObservation("obs-c2", "req-co2");
        geofenceService.submitBatch(batch("req-cb2",
                item("sub-c2", "obs-c2", 1, "地点C", "3.0", "围栏外", "50", "50")));

        // 行锁串行化：恰好一个成功，另一个抛 409（已人工接受）
        List<Integer> statuses = runConcurrently(List.of(
                () -> acceptStatus("sub-c2", "req-ca1", "sup-1", "复核通过A"),
                () -> acceptStatus("sub-c2", "req-ca2", "sup-2", "复核通过B")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        GeofenceSubmissionResponse accepted = geofenceService.getSubmission("sub-c2");
        assertThat(accepted.status()).isEqualTo(GeofenceSubmission.MANUALLY_ACCEPTED);
        assertThat(observationService.getCurrent("obs-c2").version()).isEqualTo(2);
    }

    /**
     * 执行人工接受并返回 HTTP 状态码：业务失败（ApiException）映射为其携带的状态码。
     */
    private int acceptStatus(String submissionId, String requestId, String operator, String reason) {
        try {
            return geofenceService.accept(submissionId,
                    new AcceptGeofenceSubmissionRequest(requestId, operator, reason)).status();
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    @Test
    void concurrentBatchesOnSameObservationSerialize() throws Exception {
        createFence();
        createObservation("obs-c3", "req-co3");

        // 两个批量都基于版本 1：一个改地点，一个改备注；互不冲突，应都生效
        List<GeofenceService.Outcome<BatchGeofenceSubmissionResponse>> outcomes = runConcurrently(List.of(
                () -> geofenceService.submitBatch(batch("req-cb3",
                        item("sub-c3a", "obs-c3", 1, "地点D", "1.0", "初始备注", "31", "121"))),
                () -> geofenceService.submitBatch(batch("req-cb4",
                        item("sub-c3b", "obs-c3", 1, "地点A", "1.0", "并发备注", "32", "122")))));

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        ObservationResponse current = observationService.getCurrent("obs-c3");
        // 后到者基于提交时的最新版本重新判定：两边修改都保留，版本只加两次
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("地点D");
        assertThat(current.note()).isEqualTo("并发备注");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_submission WHERE status = 'ACCEPTED'", Long.class)).isEqualTo(2);
    }
}
