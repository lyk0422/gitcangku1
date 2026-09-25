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
 * 质量标记并发与幂等边界测试：真实并发提交，验证数据库唯一约束裁决、
 * 复核与合并/删除按事务提交顺序串行、版本变化不得生效 CONFIRMED、同键重放只占一键。
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
        jdbcTemplate.update("DELETE FROM quality_flag");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
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

    private String codeOf(Runnable action) {
        try {
            action.run();
            return "ok";
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        }
    }

    @Test
    void concurrentFlagsOfSameCategoryYieldSinglePending() throws Exception {
        createObservation("obs-1", "req-0");

        // 同观测同类别并发提交 3 条不同 flagKey：唯一去重索引裁决，恰好一条成功
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> codeOf(() -> qualityFlagService.createFlag("obs-1",
                    new CreateQualityFlagRequest("req-f" + index, "flag-" + index,
                            QualityCategory.SENSOR_ANOMALY, "异常" + index, "role-a"))));
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE observation_id = 'obs-1' AND status = 'PENDING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCreateWithSameFlagKeyYieldsSingleFlag() throws Exception {
        createObservation("obs-2", "req-0");

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> codeOf(() -> qualityFlagService.createFlag("obs-2",
                    new CreateQualityFlagRequest("req-k" + index, "dup-key",
                            QualityCategory.HUMAN_MISOPERATION, "误操作", "role-a"))));
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE flag_key = 'dup-key'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentReviewAndMergeSerializesAndStaleFlagNeverDeducts() throws Exception {
        createObservation("obs-3", "req-0");
        qualityFlagService.createFlag("obs-3", new CreateQualityFlagRequest("req-flag", "flag-1",
                QualityCategory.SENSOR_ANOMALY, "异常", "role-a"));

        // 复核 CONFIRMED 与产生新版本的合并真实并发
        List<Callable<String>> tasks = List.of(
                () -> codeOf(() -> qualityFlagService.reviewFlag("flag-1",
                        new ReviewQualityFlagRequest("req-review", ReviewConclusion.CONFIRMED,
                                "确认", "role-q"))),
                () -> codeOf(() -> observationService.merge("obs-3",
                        new MergeObservationRequest("req-merge", 1, "站点A", "1.0", "并发备注"))));
        List<String> results = runConcurrently(tasks);

        ObservationSnapshot current = jdbcTemplate.queryForObject(
                "SELECT observation_id, version, location, reading, note, confidence, deleted "
                        + "FROM observation_current WHERE observation_id = 'obs-3'",
                (rs, n) -> new ObservationSnapshot(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBoolean(7)));
        String flagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM quality_flag WHERE flag_key = 'flag-1'", String.class);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE flag_key = 'flag-1'", Integer.class);

        if (results.get(0).equals("ok") && results.get(1).equals("ok")) {
            // 两者都成功只可能发生在：复核先生效（v1→80），合并随后产生 v2 并把标记转 STALE 之外——
            // 但标记已 CONFIRMED，合并不再改动它；最终 v2 继承 80，复核记录存在。
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.confidence()).isEqualTo(80);
            assertThat(flagStatus).isEqualTo("CONFIRMED");
            assertThat(reviewRows).isEqualTo(1);
            assertThat(results).containsExactly("ok", "ok");
        } else {
            // 合并先生效：v2 产生并把 PENDING 标记转 STALE，复核随后必须 410，绝不能扣减
            assertThat(results).contains("410");
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.note()).isEqualTo("并发备注");
            assertThat(current.confidence()).isEqualTo(100);
            assertThat(flagStatus).isEqualTo("STALE");
            assertThat(reviewRows).isZero();
        }

        // 无论裁决顺序，CONFIRMED 绝不可能在版本已变化后生效：v2 置信度只可能是 100 或继承的 80
        assertThat(current.confidence()).isIn(100, 80);
        Integer v2Confidence = jdbcTemplate.queryForObject(
                "SELECT confidence FROM observation_version WHERE observation_id = 'obs-3' AND version = 2",
                Integer.class);
        assertThat(v2Confidence).isEqualTo(current.confidence());
    }

    @Test
    void concurrentReviewAndDeleteSerializesAndStaleFlagNeverDeducts() throws Exception {
        createObservation("obs-4", "req-0");
        qualityFlagService.createFlag("obs-4", new CreateQualityFlagRequest("req-flag", "flag-1",
                QualityCategory.SENSOR_ANOMALY, "异常", "role-a"));

        List<Callable<String>> tasks = List.of(
                () -> codeOf(() -> qualityFlagService.reviewFlag("flag-1",
                        new ReviewQualityFlagRequest("req-review", ReviewConclusion.CONFIRMED,
                                "确认", "role-q"))),
                () -> codeOf(() -> observationService.delete("obs-4",
                        new DeleteObservationRequest("req-delete", 1))));
        List<String> results = runConcurrently(tasks);

        ObservationSnapshot current = jdbcTemplate.queryForObject(
                "SELECT observation_id, version, location, reading, note, confidence, deleted "
                        + "FROM observation_current WHERE observation_id = 'obs-4'",
                (rs, n) -> new ObservationSnapshot(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBoolean(7)));
        String flagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM quality_flag WHERE flag_key = 'flag-1'", String.class);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE flag_key = 'flag-1'", Integer.class);

        if (results.contains("410")) {
            // 删除先生效：墓碑 v2，标记 STALE，复核 410，无扣减、无复核记录
            assertThat(current.deleted()).isTrue();
            assertThat(current.version()).isEqualTo(2);
            assertThat(flagStatus).isEqualTo("STALE");
            assertThat(reviewRows).isZero();
        } else {
            // 复核先生效：v1 扣到 80，删除随后产生墓碑 v2（继承 80），标记保持 CONFIRMED
            assertThat(results).containsExactly("ok", "ok");
            assertThat(current.deleted()).isTrue();
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.confidence()).isEqualTo(80);
            assertThat(flagStatus).isEqualTo("CONFIRMED");
            assertThat(reviewRows).isEqualTo(1);
        }
    }

    @Test
    void concurrentSameReviewRequestIdReplaysSingleResultAndDoesNotDoubleDeduct() throws Exception {
        createObservation("obs-5", "req-0");
        qualityFlagService.createFlag("obs-5", new CreateQualityFlagRequest("req-flag", "flag-1",
                QualityCategory.SENSOR_ANOMALY, "异常", "role-a"));

        // 同键同参并发复核 4 次：全部重放首次成功结果，只落一条复核记录，只扣减一次
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> codeOf(() -> qualityFlagService.reviewFlag("flag-1",
                    new ReviewQualityFlagRequest("req-review", ReviewConclusion.CONFIRMED, "确认", "role-q"))));
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE flag_key = 'flag-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confidence FROM observation_current WHERE observation_id = 'obs-5'", Integer.class))
                .isEqualTo(80);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-review'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedReviewDoesNotOccupyRequestId() throws Exception {
        createObservation("obs-6", "req-0");
        qualityFlagService.createFlag("obs-6", new CreateQualityFlagRequest("req-flag", "flag-1",
                QualityCategory.SENSOR_ANOMALY, "异常", "role-a"));

        // 版本前进后同键复核失败（410，标记转 STALE，不占键）
        observationService.merge("obs-6",
                new MergeObservationRequest("req-merge", 1, "站点A", "1.0", "v2备注"));
        String first = codeOf(() -> qualityFlagService.reviewFlag("flag-1",
                new ReviewQualityFlagRequest("req-review", ReviewConclusion.CONFIRMED, "确认", "role-q")));
        assertThat(first).isEqualTo("410");

        // requestId 未被占用：可用于另一标记的合法复核
        qualityFlagService.createFlag("obs-6", new CreateQualityFlagRequest("req-flag2", "flag-2",
                QualityCategory.HUMAN_MISOPERATION, "误操作", "role-a"));
        String second = codeOf(() -> qualityFlagService.reviewFlag("flag-2",
                new ReviewQualityFlagRequest("req-review", ReviewConclusion.CONFIRMED, "确认", "role-q")));
        assertThat(second).isEqualTo("ok");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confidence FROM observation_current WHERE observation_id = 'obs-6'", Integer.class))
                .isEqualTo(80);
    }
}
