package com.example.starter.frame;

import com.example.starter.observation.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 带版本坐标基准转换的并发与幂等边界测试（真实 H2 内存库 + 真实线程）：
 * 规则发布与观测写入竞争时按提交时有效版本整体裁决，X/Y 不得半旧半新；
 * 同 requestId 并发重放只产生一条结果；重复业务标识并发只入库一条。
 */
@SpringBootTest
class CoordinateFrameConcurrencyTest {

    private static final String V1_FROM = "2026-09-01T00:00:00Z";
    private static final String V2_FROM = "2026-09-20T00:00:00Z";

    @Autowired
    private CoordinateFrameService coordinateFrameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM coordinate_observation");
        jdbcTemplate.update("DELETE FROM coordinate_frame_rule");
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

    private Map<String, Object> observationRow(String observationId) {
        return jdbcTemplate.queryForMap(
                "SELECT target_x, target_y, rule_version, review_status FROM coordinate_observation "
                        + "WHERE coordinate_observation_id = ?", observationId);
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coordinate_observation", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 核心竞争：V2 发布与观测写入同时开始，观测时刻恰为 V2 起始时刻（左闭右开边界）。
     * 观测先提交则整体使用 V1（(10,20) 站内自动合并）；发布先提交则整体使用 V2
     * （(10.5,19.5) 站外待复核）。两种结局都合法，但目标 X/Y、规则版本、复核状态
     * 必须来自同一条规则，绝不允许 X 按一版、Y 按另一版的半旧半新结果。
     * 重复 6 轮在真实线程上验证串行化；两种顺序各自的业务结果另由 API 测试确定性覆盖。
     */
    @Test
    void rulePublishRacingObservationIsDecidedAtCommitTimeAtomically() throws Exception {
        for (int i = 0; i < 6; i++) {
            final int round = i;
            // 每轮独立环境：避免上一轮已发布的规则链与本轮规则在同一观测时刻同时生效（那会正确地触发 422）
            jdbcTemplate.update("DELETE FROM coordinate_observation");
            jdbcTemplate.update("DELETE FROM coordinate_frame_rule");
            jdbcTemplate.update("DELETE FROM request_log");
            String ruleId = "RACE-R-" + round;
            String observationId = "RACE-O-" + round;
            coordinateFrameService.publish(new PublishFrameRuleRequest(
                    "race-v1-" + round, ruleId, "CGCS2000", "0", "0", V1_FROM));

            List<Callable<String>> tasks = List.of(
                    () -> {
                        coordinateFrameService.publish(new PublishFrameRuleRequest(
                                "race-v2-" + round, ruleId, "CGCS2000-2", "0.5", "-0.5", V2_FROM));
                        return "published";
                    },
                    () -> {
                        coordinateFrameService.observe(new CoordinateObservationRequest(
                                "race-obs-" + round, observationId, "WGS84", "10", "20", V2_FROM));
                        return "observed";
                    });
            runConcurrently(tasks);

            Map<String, Object> row = observationRow(observationId);
            int ruleVersion = (Integer) row.get("rule_version");
            BigDecimal targetX = (BigDecimal) row.get("target_x");
            BigDecimal targetY = (BigDecimal) row.get("target_y");
            String reviewStatus = (String) row.get("review_status");

            if (ruleVersion == 1) {
                // 观测先提交：V1 整体生效
                assertThat(targetX).isEqualByComparingTo("10.000000");
                assertThat(targetY).isEqualByComparingTo("20.000000");
                assertThat(reviewStatus).isEqualTo("AUTO_MERGED");
            } else {
                assertThat(ruleVersion).isEqualTo(2);
                // 发布先提交：V2 整体生效，X/Y 同时应用 V2 偏移
                assertThat(targetX).isEqualByComparingTo("10.500000");
                assertThat(targetY).isEqualByComparingTo("19.500000");
                assertThat(reviewStatus).isEqualTo("PENDING_REVIEW");
            }

            // 规则链始终完整：两版本各一行，V1 恰好在 V2 起始时刻闭合，V2 开放
            List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                    "SELECT version, effective_to_utc FROM coordinate_frame_rule WHERE rule_id = ? ORDER BY version",
                    ruleId);
            assertThat(rules).hasSize(2);
            assertThat(rules.get(0).get("effective_to_utc")).as("V1 closed at V2 start (left-closed right-open)")
                    .isNotNull();
            assertThat(rules.get(1).get("effective_to_utc")).isNull();
        }
    }

    /**
     * 同一 requestId、相同参数并发提交 4 次：全部重放同一结果，只入库一条观测、占用一个幂等键。
     */
    @Test
    void concurrentSameRequestIdReplaysSingleObservation() throws Exception {
        coordinateFrameService.publish(new PublishFrameRuleRequest(
                "idem-rule", "IDEM-R", "CGCS2000", "0.5", "-0.5", V1_FROM));

        List<Callable<CoordinateFrameService.ObserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> coordinateFrameService.observe(new CoordinateObservationRequest(
                    "idem-obs", "IDEM-O", "WGS84", "10", "20", "2026-09-10T00:00:00Z")));
        }
        List<CoordinateFrameService.ObserveOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().ruleVersion() == 1
                        && outcome.body().reviewStatus() == ReviewStatus.PENDING_REVIEW
                        && outcome.body().targetX().compareTo(new BigDecimal("10.500000")) == 0
                        && outcome.body().targetY().compareTo(new BigDecimal("19.500000")) == 0);
        assertThat(observationCount()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'idem-obs'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    /**
     * 不同 requestId、不同观测标识的观测并发提交，全部成功且各自结果独立。
     */
    @Test
    void concurrentDistinctObservationsAllPersist() throws Exception {
        coordinateFrameService.publish(new PublishFrameRuleRequest(
                "many-rule", "MANY-R", "CGCS2000", "0", "0", V1_FROM));

        List<Callable<CoordinateFrameService.ObserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int index = i;
            tasks.add(() -> coordinateFrameService.observe(new CoordinateObservationRequest(
                    "many-obs-" + index, "MANY-O-" + index, "WGS84",
                    String.valueOf(index + 1), "10", "2026-09-10T00:00:00Z")));
        }
        List<CoordinateFrameService.ObserveOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(observationCount()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            Map<String, Object> row = observationRow("MANY-O-" + i);
            assertThat((BigDecimal) row.get("target_x"))
                    .isEqualByComparingTo(BigDecimal.valueOf(i + 1).setScale(6));
        }
    }

    /**
     * 同一观测标识并发创建（不同 requestId）：主键串行化，恰好一条成功，其余 409，
     * 不出现重复行；成功结果属于先提交者。
     */
    @Test
    void concurrentCreatesOnSameObservationIdYieldSingleRecord() throws Exception {
        coordinateFrameService.publish(new PublishFrameRuleRequest(
                "dup-rule", "DUP-R", "CGCS2000", "0", "0", V1_FROM));

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    coordinateFrameService.observe(new CoordinateObservationRequest(
                            "dup-obs-" + index, "DUP-O", "WGS84",
                            String.valueOf(index + 1), "10", "2026-09-10T00:00:00Z"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(3);
        assertThat(observationCount()).isEqualTo(1);
    }
}
