package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离群复核并发边界测试：
 * 1. 并发复核恰好形成一条裁决，离群状态与裁决一致；
 * 2. 并发放行同一批次恰好一次成功；
 * 3. 并发同 requestId 提交读数只产生一条读数，重放结果一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM outlier_verdict");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void createBatch(String batchKey, String instrument) throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s",
                                 "targetValue":"10","allowedDeviation":"1"}
                                """.formatted(batchKey, instrument)))
                .andExpect(status().isCreated());
    }

    private long submitReading(String batchKey, String requestId, String reading) throws Exception {
        String body = mvc.perform(post("/api/batches/{batchKey}/readings", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"%s","submittedBy":"alice"}
                                """.formatted(requestId, reading)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Object outlierId = com.jayway.jsonpath.JsonPath.read(body, "$.outlierId");
        return outlierId == null ? -1L : ((Number) outlierId).longValue();
    }

    @Test
    void concurrentReviewsFormExactlyOneConsistentVerdict() throws Exception {
        long certId = createCert("INS-C1");
        createBatch("B-C1", "INS-C1");
        long outlierId = submitReading("B-C1", "rd-c1", "20");

        // 4 名复核人并发：2 ACCEPT + 2 REJECT，先达到法定人数者形成裁决
        String[] reviewers = {"rev-a1", "rev-a2", "rev-r1", "rev-r2"};
        String[] decisions = {"ACCEPT", "ACCEPT", "REJECT", "REJECT"};
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < reviewers.length; i++) {
            final int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"rv-c-%d","reviewer":"%s","decision":"%s",
                                         "reason":"并发复核","batchRevision":1,"certificateId":%d}
                                        """.formatted(index, reviewers[index], decisions[index], certId)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();

        // 两种合法调度：一方连续两条先到（2×201+2×409），或 1:1 后第三条形成裁决（3×201+1×409）
        assertTrue(created.get() == 2 || created.get() == 3,
                "成功复核数必须为 2 或 3，实际: " + created.get());
        assertEquals(4 - created.get(), conflicts.get(), "裁决形成后其余复核必须为 409");

        Integer verdictCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_verdict WHERE outlier_id = ?", Integer.class, outlierId);
        assertEquals(1, verdictCount, "并发下必须恰好形成一条裁决");
        String verdict = jdbc.queryForObject(
                "SELECT decision FROM outlier_verdict WHERE outlier_id = ?", String.class, outlierId);
        String outlierStatus = jdbc.queryForObject(
                "SELECT status FROM outlier_record WHERE id = ?", String.class, outlierId);
        assertEquals(verdict + "ED", outlierStatus, "离群状态必须与裁决一致");
        Integer validReviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE outlier_id = ? AND stale = FALSE",
                Integer.class, outlierId);
        assertEquals(created.get(), validReviews, "有效复核数必须与成功响应数一致");
        Boolean excluded = jdbc.queryForObject(
                "SELECT r.excluded FROM batch_reading r JOIN outlier_record o ON o.reading_id = r.id "
                        + "WHERE o.id = ?", Boolean.class, outlierId);
        assertEquals("REJECT".equals(verdict), excluded, "REJECT 裁决必须同事务排除读数");
    }

    @Test
    void concurrentReleaseSameBatchExactlyOneWins() throws Exception {
        createCert("INS-C2");
        createBatch("B-C2", "INS-C2");
        submitReading("B-C2", "rd-c2", "10");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchKey}/release", "B-C2")
                                .header("X-Actor-Id", "carol"))
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
        assertEquals(1, ok.get(), "并发放行必须恰好一次成功");
        assertEquals(threads - 1, conflict.get());
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release br JOIN measurement_batch b ON b.id = br.batch_id "
                        + "WHERE b.batch_key = 'B-C2'", Integer.class);
        assertEquals(1, releaseCount, "只允许写入一条放行快照");
    }

    @Test
    void concurrentSameRequestIdReadingSubmitReplaysSingleResult() throws Exception {
        createCert("INS-C3");
        createBatch("B-C3", "INS-C3");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchKey}/readings", "B-C3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"rd-c3","measuredAt":"2026-06-01T00:00:00Z",
                                         "reading":"10","submittedBy":"alice"}
                                        """))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        List<Long> readingIds = new ArrayList<>();
        for (Future<String> future : results) {
            String body = future.get(30, TimeUnit.SECONDS);
            readingIds.add(((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue());
        }
        pool.shutdown();
        assertEquals(1, readingIds.stream().distinct().count(), "同参并发重放必须返回同一读数");
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_reading WHERE request_id = 'rd-c3'", Integer.class);
        assertEquals(1, readingCount, "并发同键只能产生一条读数");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'rd-c3'", Integer.class);
        assertEquals(1, idemCount);
        Integer outlierCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record", Integer.class);
        assertEquals(0, outlierCount, "在区间内的读数不得产生离群记录");
        assertTrue(readingIds.get(0) > 0);
    }
}
