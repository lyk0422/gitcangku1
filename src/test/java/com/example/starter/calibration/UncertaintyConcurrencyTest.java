package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
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
 * 不确定度预算并发裁决 H2 测试：
 * 1. 预算修改与放行并发时按事务提交顺序裁决，放行读取一致预算，不出现部分结果；
 * 2. 同一 requestId 同参并发时业务恰好执行一次（预算只追加一个版本），其余返回首次快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UncertaintyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_snapshot_component");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_budget");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM uncertainty_threshold");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotent_request");
    }

    private void setup(String instrument, String key, String threshold) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2030-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "threshold":"%s"}""".formatted(instrument, threshold)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""
                                .formatted(key, instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private void budget(String key, String magnitude) throws Exception {
        mvc.perform(post("/api/measurements/" + key + "/budget").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","components":[{"name":"a","magnitude":"%s"}]}"""
                                .formatted(key, magnitude)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void budgetChangeAndReleaseFollowCommitOrderWithConsistentBudget() throws Exception {
        // 阈值 4.9999：旧预算 5.0000 超限；新预算 1.0000 满足。裁决结果取决于提交顺序。
        for (int round = 0; round < 12; round++) {
            String instrument = "INS-C" + round;
            String key = "M-C" + round;
            setup(instrument, key, "4.9999");
            budget(key, "5"); // v1 = 5.0000 超阈值

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> budgetFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/" + key + "/budget").header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measurementKey":"%s","components":[{"name":"a","magnitude":"1"}]}"""
                                        .formatted(key)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            int budgetStatus = budgetFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, budgetStatus, "预算修改本身应成功");
            assertTrue(releaseStatus == 200 || releaseStatus == 409,
                    "放行只能成功或整批拒绝，实际: " + releaseStatus);

            String measurementStatus = (String) jdbc.queryForMap(
                    "SELECT status FROM measurement WHERE measurement_key = ?", key).get("status");
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, key);
            Integer budgetVersions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM uncertainty_budget b JOIN measurement m ON m.id = b.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, key);

            if (releaseStatus == 200) {
                // 预算修改先提交：放行必须读到新预算 v2=1.0000，快照固化 v2
                assertEquals("RELEASED", measurementStatus);
                assertEquals(1, releaseCount);
                String snapshotCombined = jdbc.queryForObject(
                        "SELECT s.combined_uncertainty FROM release_snapshot s "
                                + "JOIN measurement m ON m.id = s.measurement_id "
                                + "WHERE m.measurement_key = ?", String.class, key);
                assertEquals("1.0000", snapshotCombined, "放行快照必须固化提交顺序上的一致新预算");
                assertEquals(2, budgetVersions);
            } else {
                // 放行先提交：读到旧预算 5.0000 超限整批拒绝，测量保持待放行，随后预算改为 v2
                assertEquals("PENDING", measurementStatus);
                assertEquals(0, releaseCount, "拒绝不得写入放行历史");
                assertEquals(2, budgetVersions);
            }
        }
    }

    @Test
    void concurrentSameRequestIdAppliesBudgetExactlyOnce() throws Exception {
        setup("INS-I", "M-I", "9");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/M-I/budget")
                                .header("X-Actor-Id", "dave").header("X-Request-Id", "REQ-ONCE")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measurementKey":"M-I","components":[{"name":"a","magnitude":"2"}]}"""))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) {
            assertEquals(200, future.get(30, TimeUnit.SECONDS), "同参并发均应返回 200（首次或重放）");
        }
        pool.shutdown();
        // 业务恰好执行一次：预算仅一个版本、一组分量、一条幂等记录
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_budget", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM uncertainty_component", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class));
    }
}
