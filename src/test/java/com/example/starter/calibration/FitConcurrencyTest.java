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

/**
 * 拟合并发与幂等边界测试：
 * 1. 并发同 requestId 同参提交：全部 201 且重放同一首次响应，只落一条拟合；
 * 2. 并发同 requestId 异参提交：恰好一种参数成功，其余 409，不泄漏唯一键异常；
 * 3. 并发批准同一拟合：全部 200 且证书 ID 相同，只创建一张证书。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FitConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM reference_fit_point");
        jdbc.update("DELETE FROM reference_fit");
        jdbc.update("DELETE FROM fit_request");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String fitJson(String requestId, String instrument, String limit) {
        return """
                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","points":[{"x":"0","y":"1"},
                 {"x":"1","y":"3"},{"x":"2","y":"5"}],"residualLimit":"%s",
                 "expectedVersion":1,"submittedBy":"alice"}""".formatted(requestId, instrument, limit);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void concurrentSubmitSameRequestIdSameParamsReplaysSingleFit() throws Exception {
        int threads = 8;
        String body = fitJson("REQ-C1", "INS-C1", "0");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/fits")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            }));
        }
        start.countDown();
        String firstBody = null;
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "同参并发必须全部 201 重放");
            String responseBody = result.getResponse().getContentAsString();
            if (firstBody == null) {
                firstBody = responseBody;
            } else {
                assertEquals(firstBody, responseBody, "所有并发响应必须等于首次完整响应");
            }
        }
        pool.shutdown();
        assertEquals(1, count("reference_fit"), "并发同键同参只落一条拟合");
        assertEquals(1, count("fit_request"));
        assertEquals(3, count("reference_fit_point"));
    }

    @Test
    void concurrentSubmitSameRequestIdDifferentParamsExactlyOneWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            // 两种异参（残差上限不同，均为可签发的合法参数）交替：一种会占键成功，另一种必须 409
            String body = fitJson("REQ-C2", "INS-C2", i % 2 == 0 ? "0" : "1");
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/fits")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            }));
        }
        start.countDown();
        int created = 0;
        int conflict = 0;
        String winnerBody = null;
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();
            if (status == 201) {
                created++;
                if (winnerBody == null) {
                    winnerBody = body;
                } else {
                    assertEquals(winnerBody, body, "同参者必须重放同一首次响应");
                }
            } else if (status == 409) {
                conflict++;
                assertTrue(body.contains("IDEMPOTENCY_CONFLICT"), "异参必须 409 且不泄漏唯一键异常");
            }
        }
        pool.shutdown();
        assertTrue(created >= 1, "与占键者同参的请求必须成功");
        assertEquals(threads - created, conflict);
        assertEquals(1, count("reference_fit"));
        assertEquals(1, count("fit_request"));
    }

    @Test
    void concurrentApproveSameFitCreatesExactlyOneCertificate() throws Exception {
        String fitBody = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(fitJson("REQ-C3", "INS-C3", "0")))
                .andReturn().getResponse().getContentAsString();
        long fitId = ((Number) com.jayway.jsonpath.JsonPath.read(fitBody, "$.id")).longValue();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/fits/{id}/approve", fitId)
                                .header("X-Actor-Id", "bob"))
                        .andReturn();
            }));
        }
        start.countDown();
        Long certId = null;
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(200, result.getResponse().getStatus(), "并发批准必须全部 200（重放首次证书）");
            long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$.certificateId")).longValue();
            if (certId == null) {
                certId = id;
            } else {
                assertEquals(certId, id, "所有并发批准必须返回同一首次证书");
            }
        }
        pool.shutdown();
        assertEquals(1, count("calibration_certificate"), "并发批准只创建一张证书");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM reference_fit WHERE id = ?", String.class, fitId));
    }
}
