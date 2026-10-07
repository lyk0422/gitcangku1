package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 拟合并发与重试测试：
 * 1. 并发同 requestId 同参提交，全部重放首次响应，只落一条拟合与一条去重记录；
 * 2. 并发批准同一拟合，恰好一次创建证书，其余重放首次证书。
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reference_fit");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    @Test
    void concurrentSubmitSameRequestIdReplaysSingleRecord() throws Exception {
        String body = FitApiTest.fitJson("REQ-C1", "INS-C1", "0",
                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice");
        int threads = 8;
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
        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同参提交必须全部重放首次响应");
            bodies.add(result.getResponse().getContentAsString());
        }
        pool.shutdown();
        assertEquals(1, bodies.size(), "所有并发响应必须一致（首次完整响应）");
        assertEquals(1, count("reference_fit"), "并发同键提交只能落一条拟合记录");
        assertEquals(1, count("idempotency_record"), "并发同键提交只能落一条去重记录");
    }

    @Test
    void concurrentApproveSameFitIssuesExactlyOneCertificate() throws Exception {
        String fitBody = mvc.perform(post("/api/fits").contentType(MediaType.APPLICATION_JSON)
                        .content(FitApiTest.fitJson("REQ-C2", "INS-C2", "0",
                                "{\"x\":\"0\",\"y\":\"1\"},{\"x\":\"1\",\"y\":\"3\"},{\"x\":\"2\",\"y\":\"5\"}", "alice")))
                .andReturn().getResponse().getContentAsString();
        long fitId = ((Number) com.jayway.jsonpath.JsonPath.read(fitBody, "$.id")).longValue();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/fits/{id}/approve", fitId)
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"REQ-C2-AP-" + index + "\"}"))
                        .andReturn();
            }));
        }
        start.countDown();
        int created = 0;
        int replayed = 0;
        Set<Long> certificateIds = new HashSet<>();
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            int status = result.getResponse().getStatus();
            assertTrue(status == 201 || status == 200, "并发批准只能为 201 或重放 200，实际: " + status);
            if (status == 201) {
                created++;
            } else {
                replayed++;
            }
            long certId = ((Number) com.jayway.jsonpath.JsonPath
                    .read(result.getResponse().getContentAsString(), "$.certificateId")).longValue();
            certificateIds.add(certId);
        }
        pool.shutdown();
        assertEquals(1, created, "并发批准必须恰好一次创建证书");
        assertEquals(threads - 1, replayed, "其余批准必须重放首次证书");
        assertEquals(1, certificateIds.size(), "所有批准响应必须指向同一张证书");
        assertEquals(1, count("calibration_certificate"), "并发批准只能签发一张证书");
        Integer approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_fit WHERE id = ? AND status = 'APPROVED'",
                Integer.class, fitId);
        assertEquals(1, approved);
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
