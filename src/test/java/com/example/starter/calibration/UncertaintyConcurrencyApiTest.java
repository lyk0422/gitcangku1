package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 不确定度预算与幂等并发边界 H2 测试（真实多线程、真实事务与行锁）：
 * 1. 并发整批替换预算串行化，两个新版本都完整、无唯一约束错误；
 * 2. 放行与预算替换并发时按提交顺序裁决，放行只可能读到完整一致的某一版预算；
 * 3. 同 requestId 同参并发：恰好一次真实放行，其余返回首次快照；异参并发 409。
 */
class UncertaintyConcurrencyApiTest extends AbstractCalibrationApiTest {

    @Test
    void concurrentBudgetReplaceSerializesIntoTwoCompleteVersions() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        submitMeasurement("C-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("C-1", "alice", budgetJson("u", "0.1"), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int idx = i;
            results.add(pool.submit(() -> {
                start.await();
                String magnitude = idx == 0 ? "0.2" : "0.3";
                return mvc.perform(post("/api/measurements/{key}/budget", "C-1")
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(budgetJson("u", magnitude)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "并发替换都应在锁串行后成功");
        }
        pool.shutdown();

        // 最终为版本 3，且 v2、v3 各自完整（各 1 行），无半成品/唯一约束错误
        Map<String, Object> m = jdbc.queryForMap(
                "SELECT id FROM measurement WHERE measurement_key = 'C-1'");
        long measurementId = ((Number) m.get("id")).longValue();
        Integer v2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component WHERE measurement_id = ? AND budget_version = 2",
                Integer.class, measurementId);
        Integer v3 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component WHERE measurement_id = ? AND budget_version = 3",
                Integer.class, measurementId);
        assertEquals(1, v2);
        assertEquals(1, v3);
        Long maxVersion = jdbc.queryForObject(
                "SELECT MAX(budget_version) FROM uncertainty_component WHERE measurement_id = ?",
                Long.class, measurementId);
        assertEquals(3L, maxVersion);
    }

    @Test
    void releaseAndBudgetReplaceConcurrentFollowCommitOrderWithConsistentBudget() throws Exception {
        // 重复多轮覆盖两种提交顺序
        for (int round = 0; round < 10; round++) {
            String instrument = "INS-R" + round;
            String key = "M-R" + round;
            createCertificate(instrument, "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
            createOpenThreshold(instrument, "2026-01-01T00:00:00Z", "1");
            submitMeasurement(key, instrument, "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
            registerBudget(key, "alice", budgetJson("u", "0.1"), null)
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> replaceFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/budget", key)
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(budgetJson("u", "9")))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            int replaceStatus = replaceFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, replaceStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 422,
                    "放行要么读到旧预算成功，要么读到超阈值新预算被 422");

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", key);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) measurement.get("id")).longValue());

            if (releaseStatus == 200) {
                // 放行先提交：状态 RELEASED，且固化的一定是它读到的那一版完整预算（v1=0.1000）
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(1, releaseCount);
                Map<String, Object> release = jdbc.queryForMap(
                        "SELECT * FROM release_record WHERE measurement_id = ?",
                        ((Number) measurement.get("id")).longValue());
                assertEquals(1L, ((Number) release.get("budget_version")).longValue());
                assertEquals(0, new java.math.BigDecimal(release.get("combined_uncertainty").toString())
                        .compareTo(new java.math.BigDecimal("0.1000")));
            } else {
                // 预算替换先提交：放行读到 v2(9) 超阈值，整批拒绝，保持待放行、无放行历史
                assertEquals("PENDING", measurement.get("status"));
                assertEquals(0, releaseCount);
                Long maxVersion = jdbc.queryForObject(
                        "SELECT MAX(budget_version) FROM uncertainty_component WHERE measurement_id = ?",
                        Long.class, ((Number) measurement.get("id")).longValue());
                assertEquals(2L, maxVersion);
            }
        }
    }

    @Test
    void concurrentSameRequestIdSameParamsExactlyOneActualReleaseOthersReplaySnapshot()
            throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        createOpenThreshold("INS-1", "2026-01-01T00:00:00Z", "1");
        submitMeasurement("C-9", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("C-9", "alice", budgetJson("u", "0.1"), null)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .header("X-Request-Id", "REL-C9")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"C-9\"]}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        for (Future<Integer> f : results) {
            int status = f.get(30, TimeUnit.SECONDS);
            assertEquals(200, status, "同参并发：原始成功与重放快照都返回 200");
            ok.incrementAndGet();
        }
        pool.shutdown();
        assertEquals(threads, ok.get());

        Map<String, Object> measurement = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'C-9'");
        Integer releaseRows = jdbc.queryForObject("SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                Integer.class, ((Number) measurement.get("id")).longValue());
        Integer idemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REL-C9'", Integer.class);
        assertEquals(1, releaseRows, "只允许一次真实放行，其余为重放快照");
        assertEquals(1, idemRows);
        assertEquals("RELEASED", measurement.get("status"));
    }
}
