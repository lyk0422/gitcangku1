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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分辨率配置与测量/放行并发测试：
 * 1. 并发创建配置最多一个成功，版本只递增一次；
 * 2. 同 requestId 并发重放返回首次响应，不泄露唯一键异常；
 * 3. 并发更新按 expectedVersion 串行，恰好一个成功；
 * 4. 提交与配置换版并发时，每条测量冻结的版本与量化值自洽；
 * 5. 放行与配置换版并发时，放行核验冻结值，不受换版影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolutionConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM resolution_config");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String configJson(String requestId, String step, String origin, int expectedVersion) {
        return """
                {"requestId":"%s","step":"%s","origin":"%s","expectedVersion":%d}
                """.formatted(requestId, step, origin, expectedVersion);
    }

    private MvcResult putConfig(String instrument, String requestId, String step,
                                int expectedVersion) throws Exception {
        return mvc.perform(put("/api/instruments/{id}/resolution", instrument)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(requestId, step, "0", expectedVersion)))
                .andReturn();
    }

    private List<Future<MvcResult>> runConcurrent(int threads, java.util.concurrent.Callable<MvcResult> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务必须在超时内完成");
        return futures;
    }

    @Test
    void concurrentConfigCreatesAtMostOneSucceeds() throws Exception {
        int threads = 8;
        List<Future<MvcResult>> futures = runConcurrent(threads,
                () -> putConfig("INS-P1", "REQ-" + Thread.currentThread().getId(), "0.2", 0));
        int created = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        assertEquals(1, created, "并发创建配置必须恰好一个成功");
        assertEquals(threads - 1, conflict, "其余必须为 409 版本冲突");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM resolution_config WHERE instrument_id = 'INS-P1'", Integer.class);
        assertEquals(1, version);
    }

    @Test
    void concurrentSameRequestIdReplaysWithoutUniqueKeyLeak() throws Exception {
        int threads = 8;
        List<Future<MvcResult>> futures = runConcurrent(threads,
                () -> putConfig("INS-P2", "REQ-SAME", "0.2", 0));
        String firstBody = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(),
                    "同键同参并发必须全部重放首响 201，不得泄露唯一键异常: "
                            + result.getResponse().getContentAsString());
            if (firstBody == null) {
                firstBody = result.getResponse().getContentAsString();
            } else {
                assertEquals(firstBody, result.getResponse().getContentAsString(),
                        "重放必须返回首次完整响应");
            }
        }
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-SAME'", Integer.class);
        assertEquals(1, records);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM resolution_config WHERE instrument_id = 'INS-P2'", Integer.class);
        assertEquals(1, version, "重放不得产生新版本");
    }

    @Test
    void concurrentUpdatesExactlyOneAdvancesVersion() throws Exception {
        putConfig("INS-P3", "REQ-BASE", "0.2", 0).getResponse().getStatus();
        int threads = 8;
        List<Future<MvcResult>> futures = runConcurrent(threads,
                () -> putConfig("INS-P3", "REQ-U-" + Thread.currentThread().getId(), "0.5", 1));
        int ok = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        assertEquals(1, ok, "同版本并发更新必须恰好一个成功");
        assertEquals(threads - 1, conflict);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM resolution_config WHERE instrument_id = 'INS-P3'", Integer.class);
        assertEquals(2, version, "版本只递增一次");
    }

    @Test
    void concurrentSubmitAndConfigChangeFreezeConsistentValues() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-P4","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        putConfig("INS-P4", "REQ-V1", "0.2", 0);

        // 一个线程换版（s=0.5 → x=1.1 量化 1.0），八个线程并发提交 x=1.1
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> update = pool.submit(() -> {
            start.await();
            return putConfig("INS-P4", "REQ-V2", "0.5", 1).getResponse().getStatus();
        });
        List<Future<MvcResult>> submits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            submits.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measurementKey":"P4-%d","instrumentId":"INS-P4",
                                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1.1",
                                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                        """.formatted(index)))
                        .andReturn();
            }));
        }
        start.countDown();
        assertEquals(200, update.get(30, TimeUnit.SECONDS));
        for (Future<MvcResult> future : submits) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus());
            String body = result.getResponse().getContentAsString();
            Number version = com.jayway.jsonpath.JsonPath.read(body, "$.resolutionVersion");
            String quantized = com.jayway.jsonpath.JsonPath.read(body, "$.quantizedValue");
            String key = com.jayway.jsonpath.JsonPath.read(body, "$.measurementKey");
            // 冻结值必须与冻结版本自洽：v1 → 1.2，v2 → 1.0
            if (version.intValue() == 1) {
                assertEquals("1.2", quantized, key + " 冻结 v1 必须量化 1.2");
            } else {
                assertEquals(2, version.intValue());
                assertEquals("1", quantized, key + " 冻结 v2 必须量化 1.0");
            }
            // 数据库落库值与响应一致（真实冻结）
            String stored = jdbc.queryForObject(
                    "SELECT quantized_value FROM measurement WHERE measurement_key = ?",
                    String.class, key);
            assertEquals(quantized, new java.math.BigDecimal(stored).stripTrailingZeros().toPlainString());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
    }

    @Test
    void concurrentReleaseAndConfigChangeReleaseUsesFrozenValues() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-P5","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        putConfig("INS-P5", "REQ-R1", "0.2", 0);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"P5-1","instrumentId":"INS-P5",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1.1",
                                 "lowerLimit":"0","upperLimit":"1.2","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());

        // 换版（若回算会量化 1.25 越限）与放行并发：放行必须按冻结值成功
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> update = pool.submit(() -> {
            start.await();
            return mvc.perform(put("/api/instruments/{id}/resolution", "INS-P5")
                            .header("X-Actor-Id", "dave")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(configJson("REQ-R2", "0.3", "0.05", 1)))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> release = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/measurements/release")
                            .header("X-Actor-Id", "carol")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keys\":[\"P5-1\"]}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertEquals(200, update.get(30, TimeUnit.SECONDS));
        assertEquals(200, release.get(30, TimeUnit.SECONDS), "放行核验冻结值，不受换版影响");
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        String status = jdbc.queryForObject(
                "SELECT status FROM measurement WHERE measurement_key = 'P5-1'", String.class);
        assertEquals("RELEASED", status);
        String quantized = jdbc.queryForObject(
                "SELECT quantized_value FROM measurement WHERE measurement_key = 'P5-1'", String.class);
        assertEquals("1.2", new java.math.BigDecimal(quantized).stripTrailingZeros().toPlainString());
    }
}
