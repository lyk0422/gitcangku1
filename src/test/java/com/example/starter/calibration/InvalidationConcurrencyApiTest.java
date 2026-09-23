package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 失效相关并发边界（真实 H2 数据库、真实并发线程，带超时断言）：
 * 1. 失效激活与审核放行并发时按事务提交顺序生效，失效提交后不得再放行受影响结果；
 * 2. 两名确认人并发确认同一失效单时恰好激活一次，只生成单一 impactVersion；
 * 3. 两个失效单并发激活重叠闭包时最多一个成功，另一个整单 409 且不发生部分冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvalidationConcurrencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM impact_path");
        jdbc.update("DELETE FROM invalidation_confirmation");
        jdbc.update("DELETE FROM invalidation_snapshot");
        jdbc.update("DELETE FROM invalidation_order");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM standard_version");
        jdbc.update("DELETE FROM measurement_standard");
    }

    private void buildScenario() throws Exception {
        mvc.perform(post("/api/standards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardId\":\"STD-ROOT\",\"name\":\"root\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"versionKey":"SV-ROOT","standardId":"STD-ROOT","parentVersionKey":null,
                         "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                         "certificateNo":"C-R"}""")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"M-C","instrumentId":"INS-1",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                         "standardVersionKey":"SV-ROOT"}""")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private void createOrder(String key, String requestId, long expectedVersion) throws Exception {
        mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", requestId)
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"%s","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"并发"}
                                """.formatted(key, expectedVersion)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void activationAndReleaseFollowCommitOrderAcrossRounds() throws Exception {
        buildScenario();

        // 重复多轮：每轮一个新失效单（根版本已 INVALID 后，新单子快照状态为 INVALID，闭包状态不再被激活改写）
        // 为保证每轮独立，这里用单独一轮验证放行与激活的提交顺序互斥。
        long version = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-C1", "REQ-C1", version);
        mvc.perform(post("/api/invalidations/INV-C1/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> activateFuture = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/invalidations/INV-C1/confirmations").header("X-Actor-Id", "qm-b"))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> releaseFuture = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/measurements/release")
                            .header("X-Actor-Id", "reviewer1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-C\"]}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        int activateStatus = activateFuture.get(30, TimeUnit.SECONDS);
        int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        Map<String, Object> measurement =
                jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-C'");
        String finalStatus = (String) measurement.get("status");
        Long releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                Long.class, ((Number) measurement.get("id")).longValue());

        if ("BLOCKED".equals(finalStatus)) {
            // 失效激活先提交：放行必须 409（NOT_PENDING），记录冻结
            assertEquals(200, activateStatus);
            assertEquals(409, releaseStatus);
            assertEquals(0L, releaseCount, "失效提交后不得放行受影响结果");
        } else {
            // 放行先提交：激活检测到结果状态 PENDING→RELEASED 变化，整单 409
            assertEquals("RELEASED", finalStatus);
            assertEquals(409, activateStatus, "闭包结果状态变化，激活必须整单 409");
            assertEquals(200, releaseStatus);
            assertEquals(1L, releaseCount);
        }
    }

    @Test
    void concurrentConfirmationsActivateExactlyOnce() throws Exception {
        buildScenario();
        long version = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-C2", "REQ-C2", version);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String actor = "qm-" + (char) ('a' + i);
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/invalidations/INV-C2/confirmations").header("X-Actor-Id", actor))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        // 恰好一次激活成功；其余为 ALREADY_CONFIRMED 或 ALREADY_ACTIVATED
        Map<String, Object> order =
                jdbc.queryForMap("SELECT * FROM invalidation_order WHERE invalidation_key = 'INV-C2'");
        assertEquals("ACTIVATED", order.get("status"));
        assertTrue(ok >= 1, "至少一次确认成功");
        Long distinctImpact = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT impact_version) FROM measurement WHERE impact_version IS NOT NULL",
                Long.class);
        assertEquals(1L, distinctImpact, "只允许生成单一 impactVersion");
        assertEquals(2L,
                jdbc.queryForObject("SELECT COUNT(*) FROM invalidation_confirmation", Long.class),
                "恰好两名确认人");
        assertTrue(ok + conflict == threads);
    }

    @Test
    void twoInvalidationOrdersActivatingOverlappingClosureExactlyOneWins() throws Exception {
        buildScenario();
        long version = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);

        // 两个针对同一失效根的失效单，各自先由一名质量人员确认
        createOrder("INV-D1", "REQ-D1", version);
        createOrder("INV-D2", "REQ-D2", version);
        mvc.perform(post("/api/invalidations/INV-D1/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(post("/api/invalidations/INV-D2/confirmations").header("X-Actor-Id", "qm-c"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // 并发发送各自的第二名（不同）确认，触发并发激活
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/invalidations/INV-D1/confirmations").header("X-Actor-Id", "qm-b"))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> f2 = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/invalidations/INV-D2/confirmations").header("X-Actor-Id", "qm-d"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        java.util.List<Integer> statuses = java.util.List.of(s1, s2);
        assertEquals(1L, statuses.stream().filter(s -> s == 200).count(), "恰好一个失效单激活成功");
        assertEquals(1L, statuses.stream().filter(s -> s == 409).count(),
                "另一个失效单因闭包已被首个失效改变而整单 409");

        // 只生成单一 impactVersion；输家整单回滚仍为 PENDING 且确认插入一并回滚
        Long impactCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT impact_version) FROM invalidation_order WHERE impact_version IS NOT NULL",
                Long.class);
        assertEquals(1L, impactCount);
        assertEquals("PENDING",
                jdbc.queryForObject("SELECT status FROM invalidation_order WHERE invalidation_key = 'INV-D2'",
                        String.class));
        assertEquals(1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM invalidation_confirmation WHERE invalidation_id = "
                                + "(SELECT id FROM invalidation_order WHERE invalidation_key = 'INV-D2')",
                        Long.class),
                "输家事务回滚：第二名确认不得保留");
        // 闭包已被赢家整体冻结
        assertEquals("INVALID",
                jdbc.queryForObject("SELECT status FROM standard_version WHERE version_key = 'SV-ROOT'",
                        String.class));
    }
}
