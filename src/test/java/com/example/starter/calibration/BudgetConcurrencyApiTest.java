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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 不确定度预算并发裁决真实 H2 测试：
 * 1. 预算修订与放行并发时按事务提交顺序裁决，放行只读取一致预算，不出现越闸放行；
 * 2. 同 requestId 同参并发恰好一次生效，其余返回首次响应快照；
 * 3. 同 expectedVersion 并发修订恰好一笔成功，过期方 410，无部分结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClockConfig.class)
class BudgetConcurrencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_budget");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM uncertainty_threshold");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void setupInstrumentAndMeasurement(String instrument, String key) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","maxUncertainty":"5"}""".formatted(instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"%s","instrumentId":"%s",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}""".formatted(key, instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        // 初始预算 v1：[3,4] → 5，恰好满足阈值 5
        mvc.perform(put("/api/measurements/{key}/budget", key).header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"3"},{"name":"b","value":"4"}]}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void revisionAndReleaseFollowCommitOrderInEitherOrder() throws Exception {
        for (int round = 0; round < 12; round++) {
            String instrument = "INS-X" + round;
            String key = "M-X" + round;
            setupInstrumentAndMeasurement(instrument, key);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            // 修订为 [3,4.1] → sqrt(25.81)≈5.08 > 5：修订先提交则放行必须被拒
            Future<MvcResult> reviseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/budget", key)
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"components":[{"name":"a","value":"3"},{"name":"b","value":"4.1"}],
                                         "expectedVersion":2}"""))
                        .andReturn();
            });
            Future<MvcResult> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn();
            });
            start.countDown();
            int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            pool.shutdown();

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", key);
            long measurementId = ((Number) measurement.get("id")).longValue();
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, measurementId);
            Integer budgetCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM uncertainty_budget WHERE measurement_id = ?",
                    Integer.class, measurementId);

            if (releaseCount > 0) {
                // 放行先提交（读到 v1，合成 5 ≤ 5）：修订必须 409/410，预算不得新增，状态 RELEASED
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(1, releaseCount, "只允许一条放行历史");
                assertEquals(1, budgetCount, "放行先提交时不得再写入预算新版本");
                assertTrue(reviseStatus == 409 || reviseStatus == 410,
                        "放行先提交后修订必须被拒绝，实际状态码=" + reviseStatus);
                assertEquals(200, releaseStatus);
            } else {
                // 修订先提交（v2 超阈值）：放行必须 409，测量保持 PENDING，预算恰好两版
                assertEquals(409, releaseStatus, "修订先提交且超阈值时放行必须整批拒绝");
                assertEquals("PENDING", measurement.get("status"));
                assertEquals(2, budgetCount, "修订只产生一个新版本，无部分分量");
                assertEquals(200, reviseStatus);
                Integer v2Components = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM uncertainty_component c "
                                + "JOIN uncertainty_budget b ON b.id = c.budget_id "
                                + "WHERE b.measurement_id = ? AND b.budget_version = 2",
                        Integer.class, measurementId);
                assertEquals(2, v2Components, "新版本分量必须完整写入");
            }
        }
    }

    @Test
    void concurrentSameRequestIdAppliesOnceAndOthersReplaySnapshot() throws Exception {
        setupInstrumentAndMeasurement("INS-I", "M-I");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        String body = """
                {"components":[{"name":"a","value":"2"},{"name":"b","value":"2.5"}]}""";
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/budget", "M-I")
                                .header("X-Actor-Id", "carol")
                                .header("X-Request-Id", "REQ-CONCURRENT")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            }));
        }
        start.countDown();

        AtomicInteger ok = new AtomicInteger();
        String snapshot = null;
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(200, result.getResponse().getStatus(), "同参并发必须全部返回 200");
            ok.incrementAndGet();
            String content = result.getResponse().getContentAsString();
            if (snapshot == null) {
                snapshot = content;
            } else {
                assertEquals(snapshot, content, "并发重放必须返回首次完整响应快照");
            }
        }
        pool.shutdown();
        assertEquals(threads, ok.get());

        // 只生效一次：恰好多一个预算版本（v2），测量版本只推进一格
        Map<String, Object> measurement = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = ?", "M-I");
        assertEquals(3L, ((Number) measurement.get("version")).longValue());
        Integer budgetCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget WHERE measurement_id = ?",
                Integer.class, ((Number) measurement.get("id")).longValue());
        assertEquals(2, budgetCount, "同 requestId 并发只能产生一个预算新版本");
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REQ-CONCURRENT'",
                Integer.class);
        assertEquals(1, idempotentRows);
    }

    @Test
    void concurrentRevisionsWithSameExpectedVersionExactlyOneSucceeds() throws Exception {
        setupInstrumentAndMeasurement("INS-V", "M-V");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String body = """
                {"components":[{"name":"a","value":"1.5"}],"expectedVersion":2}""";
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/budget", "M-V")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                success.incrementAndGet();
            } else if (status == 410) {
                gone.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, success.get(), "持相同 expectedVersion 的并发修订必须恰好一笔成功");
        assertEquals(threads - 1, gone.get(), "其余必须 410 版本过期");

        Map<String, Object> measurement = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = ?", "M-V");
        assertEquals(3L, ((Number) measurement.get("version")).longValue(),
                "失败修订不得推进测量版本");
        Integer budgetCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget WHERE measurement_id = ?",
                Integer.class, ((Number) measurement.get("id")).longValue());
        assertEquals(2, budgetCount, "失败修订不得留下部分预算版本");
    }

    @Test
    void concurrentSameRequestIdWhereBusinessFailsAfterAcquireReleasesKey() throws Exception {
        // 仅建证书与测量、不发布阈值：占键成功后才在事务内发现阈值缺失（422），真正覆盖占位回滚路径。
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"INS-F","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"M-F","instrumentId":"INS-F",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String body = """
                {"components":[{"name":"x","value":"1"}]}""";
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/budget", "M-F")
                                .header("X-Actor-Id", "carol")
                                .header("X-Request-Id", "REQ-FAIL")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) {
            assertEquals(422, future.get(30, TimeUnit.SECONDS),
                    "占键后业务失败必须回滚，等待方不得被失败占位阻塞");
        }
        pool.shutdown();

        // 发布阈值后，同一 requestId 可被合法请求成功占用（失败不占键）
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-F","maxUncertainty":"5"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(put("/api/measurements/{key}/budget", "M-F")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REQ-FAIL'", Integer.class);
        assertEquals(1, idempotentRows, "最终只保留一条成功幂等记录");
    }
}
