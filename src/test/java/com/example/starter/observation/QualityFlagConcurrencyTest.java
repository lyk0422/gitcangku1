package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 质量标记并发与幂等边界测试：真实并发提交，验证标记创建唯一性、
 * 复核与合并按事务提交顺序裁决、同键并发重放只占一键且置信度只扣减一次。
 */
@SpringBootTest
class QualityFlagConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private QualityFlagService qualityFlagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM quality_flag_review");
        jdbcTemplate.update("DELETE FROM confidence_deduction");
        jdbcTemplate.update("DELETE FROM quality_flag");
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

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    private QualityFlagService.FlagOutcome createFlag(String requestId, String observationId, String flagKey,
                                                      QualityCategory category, String role) {
        return qualityFlagService.createFlag(observationId,
                new CreateQualityFlagRequest(requestId, flagKey, category, "质量说明-" + flagKey, role));
    }

    private QualityFlagService.FlagOutcome review(String requestId, String observationId, String flagKey,
                                                  ReviewConclusion conclusion, String role) {
        return qualityFlagService.review(observationId,
                new ReviewQualityFlagRequest(requestId, flagKey, conclusion, "复核理由-" + requestId, role));
    }

    @Test
    void concurrentFlagsSameCategoryYieldSinglePending() throws Exception {
        createObservation("obs-q1", "req-q0");

        // 同一类别并发提交两个不同 flagKey：串行化后恰一个成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        createFlag("req-qa", "obs-q1", "flag-a", QualityCategory.SENSOR_FAULT, "OBSERVER");
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        createFlag("req-qb", "obs-q1", "flag-b", QualityCategory.SENSOR_FAULT, "OPERATOR");
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer pendingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE observation_id = 'obs-q1' AND status = 'PENDING_REVIEW'",
                Integer.class);
        assertThat(pendingRows).isEqualTo(1);
    }

    @Test
    void concurrentReviewAndMergeResolvedByCommitOrder() throws Exception {
        createObservation("obs-q2", "req-q0");
        createFlag("req-qf", "obs-q2", "flag-1", QualityCategory.SENSOR_FAULT, "OBSERVER");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        QualityFlagService.FlagOutcome outcome =
                                review("req-qr", "obs-q2", "flag-1", ReviewConclusion.CONFIRMED, "REVIEWER");
                        return "review-" + outcome.status();
                    } catch (ApiException e) {
                        return "review-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-q2",
                                new MergeObservationRequest("req-qm", 1, "站点B", "1.0", "初始备注"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 按事务提交顺序裁决：复核先提交则 200 且置信度扣减带入新版本；
        // 合并先提交则复核 410 且标记转 STALE，置信度不变。两种结局必居其一。
        assertThat(results).contains("merge-ok");
        ObservationResponse current = observationService.getCurrent("obs-q2");
        assertThat(current.version()).isEqualTo(2);
        String flagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM quality_flag WHERE observation_id = 'obs-q2' AND flag_key = 'flag-1'",
                String.class);
        if (results.contains("review-200")) {
            assertThat(current.confidence()).isEqualTo(80);
            assertThat(flagStatus).isEqualTo("CONFIRMED");
            // 复核记录固化版本一致性
            Integer consistent = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM quality_flag_review WHERE observation_id = 'obs-q2' "
                            + "AND flag_key = 'flag-1' AND flagged_version = 1 AND review_version = 1 "
                            + "AND version_consistent = TRUE",
                    Integer.class);
            assertThat(consistent).isEqualTo(1);
        } else {
            assertThat(results).contains("review-410");
            assertThat(current.confidence()).isEqualTo(100);
            assertThat(flagStatus).isEqualTo("STALE");
            Integer reviewRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM quality_flag_review WHERE observation_id = 'obs-q2'",
                    Integer.class);
            assertThat(reviewRows).isZero();
        }
    }

    @Test
    void concurrentReviewsSameFlagDeductConfidenceOnce() throws Exception {
        createObservation("obs-q3", "req-q0");
        createFlag("req-qf", "obs-q3", "flag-1", QualityCategory.SENSOR_FAULT, "OBSERVER");

        // 两个不同复核人并发复核同一标记：恰一个成功，置信度只扣减一次
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        QualityFlagService.FlagOutcome outcome =
                                review("req-qr1", "obs-q3", "flag-1", ReviewConclusion.CONFIRMED, "REVIEWER");
                        return String.valueOf(outcome.status());
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        QualityFlagService.FlagOutcome outcome =
                                review("req-qr2", "obs-q3", "flag-1", ReviewConclusion.CONFIRMED, "AUDITOR");
                        return String.valueOf(outcome.status());
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("200", "409");
        ObservationResponse current = observationService.getCurrent("obs-q3");
        assertThat(current.confidence()).isEqualTo(80);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE observation_id = 'obs-q3'", Integer.class);
        assertThat(reviewRows).isEqualTo(1);
        Integer deductionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM confidence_deduction WHERE observation_id = 'obs-q3'", Integer.class);
        assertThat(deductionRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdReviewReplaysSingleResult() throws Exception {
        createObservation("obs-q4", "req-q0");
        createFlag("req-qf", "obs-q4", "flag-1", QualityCategory.SENSOR_FAULT, "OBSERVER");

        // 同一 requestId、相同参数并发复核 4 次：全部返回同一结果，只写一条复核记录，只扣减一次
        List<Callable<QualityFlagService.FlagOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> review("req-qr", "obs-q4", "flag-1", ReviewConclusion.CONFIRMED, "REVIEWER"));
        }
        List<QualityFlagService.FlagOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        ObservationResponse current = observationService.getCurrent("obs-q4");
        assertThat(current.confidence()).isEqualTo(80);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE observation_id = 'obs-q4'", Integer.class);
        assertThat(reviewRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-qr'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdFlagCreationReplaysSingleResult() throws Exception {
        createObservation("obs-q5", "req-q0");

        // 同一 requestId、相同参数并发创建标记 3 次：全部返回首次结果，只落一条标记
        List<Callable<QualityFlagService.FlagOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> createFlag("req-qf", "obs-q5", "flag-1", QualityCategory.HUMAN_ERROR, "OBSERVER"));
        }
        List<QualityFlagService.FlagOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        Integer flagRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE observation_id = 'obs-q5'", Integer.class);
        assertThat(flagRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-qf'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }
}
