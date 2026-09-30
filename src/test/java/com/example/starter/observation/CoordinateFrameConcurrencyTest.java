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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 坐标基准转换的并发与幂等边界测试（真实 H2 + 真实线程）：
 * 规则发布与观测写入竞争时按提交时有效版本串行裁决，同 requestId 并发只落一行，
 * 同版本并发发布只有一个胜者；所有断言核对最终落库数据，不依赖打印或睡眠。
 */
@SpringBootTest
class CoordinateFrameConcurrencyTest {

    private static final Instant V1_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant V2_FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant IN_OVERLAP = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant IN_V1_ONLY = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private CoordinateFrameService coordinateFrameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM coordinate_observation");
        jdbcTemplate.update("DELETE FROM coordinate_rule");
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("workers become ready in time").isTrue();
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

    private PublishRuleRequest rule(int version, String frame, String ox, String oy, Instant from) {
        return new PublishRuleRequest(version, frame, new BigDecimal(ox), new BigDecimal(oy), from, null);
    }

    private CoordinateObservationRequest obs(String requestId, String observationId,
                                             String x, String y, Instant at) {
        return new CoordinateObservationRequest(
                requestId, observationId, "FIELD-A", new BigDecimal(x), new BigDecimal(y), at);
    }

    /**
     * 线程返回：HTTP 状态码（ApiException 或成功 201），成功时附带响应体。
     */
    private record Outcome(int status, CoordinateObservationResponse body) {
    }

    private Outcome callIngest(CoordinateObservationRequest request) {
        try {
            return new Outcome(201, coordinateFrameService.ingest(request));
        } catch (ApiException e) {
            return new Outcome(e.status().value(), null);
        }
    }

    @Test
    void concurrentPublishAndIngestAdjudicateByCommittedVersion() throws Exception {
        // V1 已提交且开放区间；发布中的 V2 同样开放区间，一旦提交则 2026-07 时刻同时命中两条规则
        coordinateFrameService.publishRule(rule(1, "FRAME-1", "0", "0", V1_FROM));

        // 1 个发布线程与 3 个观测线程同时冲过起点（连接池上限 5，线程数 4）
        List<Callable<Outcome>> tasks = List.of(
                () -> {
                    try {
                        coordinateFrameService.publishRule(rule(2, "FRAME-2", "6", "0", V2_FROM));
                        return new Outcome(201, null);
                    } catch (ApiException e) {
                        return new Outcome(e.status().value(), null);
                    }
                },
                () -> callIngest(obs("req-p1", "obs-p1", "5", "20", IN_OVERLAP)),
                () -> callIngest(obs("req-p2", "obs-p2", "5", "20", IN_OVERLAP)),
                () -> callIngest(obs("req-p3", "obs-p3", "5", "20", IN_OVERLAP)));
        List<Outcome> results = runConcurrently(tasks);

        // 发布必定成功；观测只有两种串行化结局：V2 提交前拿锁 → 201 且只能按 V1 转换；
        // V2 提交后拿锁 → 命中两条规则 422。绝不允许“看到 V2 却没看到 V1”之类半旧半新的成功。
        Outcome publishOutcome = results.get(0);
        assertThat(publishOutcome.status()).isEqualTo(201);
        List<Outcome> ingestOutcomes = results.subList(1, results.size());
        for (Outcome outcome : ingestOutcomes) {
            assertThat(outcome.status()).isIn(201, 422);
            if (outcome.status() == 201) {
                assertThat(outcome.body().ruleVersion()).isEqualTo(1);
                assertThat(outcome.body().targetX()).isEqualByComparingTo("5.000000");
                assertThat(outcome.body().targetY()).isEqualByComparingTo("20.000000");
                assertThat(outcome.body().reviewStatus()).isEqualTo("AUTO_MERGED");
                assertThat(outcome.body().autoMerged()).isTrue();
            }
        }
        long successCount = ingestOutcomes.stream().filter(o -> o.status() == 201).count();

        // 最终数据核对：成功观测数等于落库行数；每行坐标与其冻结规则版本的偏移严格一致
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation", Integer.class);
        assertThat(rowCount).isEqualTo((int) successCount);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT observation_id, target_x, target_y, rule_version, review_status, auto_merged "
                        + "FROM coordinate_observation");
        for (Map<String, Object> row : rows) {
            int ruleVersion = ((Number) row.get("rule_version")).intValue();
            assertThat(ruleVersion).isEqualTo(1);
            Map<String, Object> ruleRow = jdbcTemplate.queryForMap(
                    "SELECT offset_x, offset_y FROM coordinate_rule WHERE rule_version = ?", ruleVersion);
            BigDecimal expectedX = new BigDecimal("5").add((BigDecimal) ruleRow.get("offset_x"))
                    .setScale(6, java.math.RoundingMode.HALF_UP);
            BigDecimal expectedY = new BigDecimal("20").add((BigDecimal) ruleRow.get("offset_y"))
                    .setScale(6, java.math.RoundingMode.HALF_UP);
            assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo(expectedX);
            assertThat((BigDecimal) row.get("target_y")).isEqualByComparingTo(expectedY);
            assertThat((String) row.get("review_status")).isEqualTo("AUTO_MERGED");
            assertThat(row.get("auto_merged")).isEqualTo(Boolean.TRUE);
        }
        Integer ruleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_rule WHERE rule_version IN (1, 2)", Integer.class);
        assertThat(ruleCount).isEqualTo(2);

        // 收尾：V2 提交后再来的同刻观测必须 422（提交时有效版本裁决，状态已稳定）
        assertThat(callIngest(obs("req-after", "obs-after", "5", "20", IN_OVERLAP)).status()).isEqualTo(422);
        Integer afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation WHERE observation_id = 'obs-after'", Integer.class);
        assertThat(afterCount).isZero();
    }

    @Test
    void concurrentSameRequestIdIngestReplaysSingleResult() throws Exception {
        coordinateFrameService.publishRule(rule(1, "FRAME-1", "0.5", "-0.5", V1_FROM));

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> callIngest(obs("req-same", "obs-same", "10", "20",
                    Instant.parse("2026-03-15T00:00:00Z"))));
        }
        List<Outcome> results = runConcurrently(tasks);

        assertThat(results).allMatch(o -> o.status() == 201);
        assertThat(results).allSatisfy(o -> {
            assertThat(o.body().ruleVersion()).isEqualTo(1);
            assertThat(o.body().targetX()).isEqualByComparingTo("10.500000");
            assertThat(o.body().targetY()).isEqualByComparingTo("19.500000");
            assertThat(o.body().reviewStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(o.body().autoMerged()).isFalse();
        });
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation WHERE request_id = 'req-same'", Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void concurrentPublishOfSameVersionHasSingleWinner() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                try {
                    coordinateFrameService.publishRule(rule(1, "FRAME-1", "0", "0", V1_FROM));
                    return 201;
                } catch (ApiException e) {
                    return e.status().value();
                }
            });
        }
        List<Integer> results = runConcurrently(tasks);

        assertThat(results).filteredOn(code -> code == 201).hasSize(1);
        assertThat(results).filteredOn(code -> code == 409).hasSize(2);
        Integer ruleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_rule WHERE rule_version = 1", Integer.class);
        assertThat(ruleCount).isEqualTo(1);
    }

    @Test
    void concurrentDistinctObservationsAllCommitWithFrozenRule() throws Exception {
        coordinateFrameService.publishRule(rule(1, "FRAME-1", "0", "0", V1_FROM));

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final int index = i;
            // 原坐标取 (i, i*5)：1..4 与 5..20 均在站点矩形 X∈[0,10]、Y∈[0,30] 内
            tasks.add(() -> callIngest(obs("req-d" + index, "obs-d" + index,
                    String.valueOf(index), String.valueOf(index * 5), IN_V1_ONLY)));
        }
        List<Outcome> results = runConcurrently(tasks);

        assertThat(results).allMatch(o -> o.status() == 201);
        assertThat(results).allSatisfy(o -> {
            assertThat(o.body().ruleVersion()).isEqualTo(1);
            assertThat(o.body().reviewStatus()).isEqualTo("AUTO_MERGED");
        });
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation", Integer.class);
        assertThat(rowCount).isEqualTo(4);
        // 每个 id 的原坐标冻结且与响应一致
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT original_x, original_y, target_x, target_y FROM coordinate_observation "
                            + "WHERE observation_id = ?", "obs-d" + i);
            assertThat((BigDecimal) row.get("original_x")).isEqualByComparingTo(String.valueOf(i));
            assertThat((BigDecimal) row.get("original_y")).isEqualByComparingTo(String.valueOf(i * 5));
            assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo(String.valueOf(i));
            assertThat((BigDecimal) row.get("target_y")).isEqualByComparingTo(String.valueOf(i * 5));
        }
    }
}
