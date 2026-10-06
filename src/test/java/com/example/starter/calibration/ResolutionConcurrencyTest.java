package com.example.starter.calibration;

import java.math.BigDecimal;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 分辨率配置并发测试：同期望版本并发更新仅一个成功；同 requestId 并发重放一致；
 * 配置换版与测量提交并发时，测量冻结值与其所见版本一致。
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_resolution");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createResolution(String instrument, String requestId, String step) throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", instrument)
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","origin":"0","step":"%s","expectedVersion":null}
                                """.formatted(requestId, step)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    @Test
    void concurrentUpdatesWithSameExpectedVersionExactlyOneWins() throws Exception {
        createResolution("INS-C", "REQ-C0", "0.2");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/instruments/{id}/resolution", "INS-C")
                                .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"REQ-U%d","origin":"0","step":"0.5","expectedVersion":1}
                                        """.formatted(index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "同期望版本并发更新必须恰好一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM instrument_resolution WHERE instrument_id = 'INS-C'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdAllReplayFirstResponse() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                var response = mvc.perform(put("/api/instruments/{id}/resolution", "INS-R")
                                .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"REQ-SAME","origin":"0","step":"0.2","expectedVersion":null}
                                        """))
                        .andReturn().getResponse();
                return response.getStatus() + "|" + response.getContentAsString();
            }));
        }
        start.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<String> future : results) {
            String[] parts = future.get(30, TimeUnit.SECONDS).split("\\|", 2);
            assertEquals("201", parts[0], "同键同参并发必须全部重放首次 201 响应");
            bodies.add(parts[1]);
        }
        pool.shutdown();
        for (int i = 1; i < bodies.size(); i++) {
            assertEquals(bodies.get(0), bodies.get(i), "重放响应必须与首次完整响应一致");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_resolution WHERE instrument_id = 'INS-R'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-SAME'", Integer.class));
    }

    @Test
    void concurrentSubmitAndConfigUpdateFreezeConsistentValues() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-M","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        createResolution("INS-M", "REQ-M0", "0.2");

        int submits = 8;
        ExecutorService pool = Executors.newFixedThreadPool(submits + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        // 一个线程换版：s 0.2 → 0.5
        results.add(pool.submit(() -> {
            start.await();
            return mvc.perform(put("/api/instruments/{id}/resolution", "INS-M")
                            .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"REQ-M1","origin":"0","step":"0.5","expectedVersion":1}"""))
                    .andReturn().getResponse().getStatus();
        }));
        // 其余线程提交测量 x=1.1
        for (int i = 0; i < submits; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measurementKey":"CM-%d","instrumentId":"INS-M",
                                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1.1",
                                         "lowerLimit":"0","upperLimit":"1.25","submittedBy":"alice"}
                                        """.formatted(index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int updateStatus = results.get(0).get(30, TimeUnit.SECONDS);
        for (int i = 1; i < results.size(); i++) {
            assertEquals(201, results.get(i).get(30, TimeUnit.SECONDS), "测量提交必须全部成功");
        }
        pool.shutdown();
        assertTrue(updateStatus == 200 || updateStatus == 409);

        // 每条测量的冻结值必须与其所见版本一致：v1 → (0.2, k=6, q=1.2)；v2 → (0.5, k=2, q=1.0)
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT resolution_version, grid_origin, grid_step, grid_k, quantized_value "
                        + "FROM measurement WHERE instrument_id = 'INS-M'");
        assertEquals(submits, rows.size());
        for (Map<String, Object> row : rows) {
            int version = ((Number) row.get("resolution_version")).intValue();
            BigDecimal origin = (BigDecimal) row.get("grid_origin");
            BigDecimal step = (BigDecimal) row.get("grid_step");
            BigDecimal k = (BigDecimal) row.get("grid_k");
            BigDecimal quantized = (BigDecimal) row.get("quantized_value");
            assertEquals(0, origin.compareTo(BigDecimal.ZERO));
            if (version == 1) {
                assertEquals(0, step.compareTo(new BigDecimal("0.2")));
                assertEquals(0, k.compareTo(new BigDecimal("6")));
                assertEquals(0, quantized.compareTo(new BigDecimal("1.2")));
            } else {
                assertEquals(2, version);
                assertEquals(0, step.compareTo(new BigDecimal("0.5")));
                assertEquals(0, k.compareTo(new BigDecimal("2")));
                assertEquals(0, quantized.compareTo(new BigDecimal("1.0")));
            }
            // 冻结值内部一致：quantized == origin + k×step
            assertEquals(0, quantized.compareTo(origin.add(k.multiply(step))));
        }
    }
}
