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

/**
 * 离群复核并发边界：并发复核恰好形成一次裁决；放行与复核按事务提交顺序生效；
 * 并发重复放行恰好一次成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM adjudication_record");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private void createBatch(String key, String instrument) throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s","targetValue":"10",
                                 "tolerance":"0.5"}""".formatted(key, instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private long submitOutlierReading(String batchKey, String readingKey) throws Exception {
        String body = mvc.perform(post("/api/batches/{key}/readings", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10.9","submittedBy":"alice"}""".formatted(readingKey)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();
    }

    private int review(long outlierId, String reviewer, int batchRevision) throws Exception {
        return mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewer":"%s","decision":"ACCEPT","reason":"并发复核",
                                 "batchRevision":%d,"certificateVersion":1}
                                """.formatted(reviewer, batchRevision)))
                .andReturn().getResponse().getStatus();
    }

    private int release(String batchKey, String actor) throws Exception {
        return mvc.perform(post("/api/batches/{key}/release", batchKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void concurrentReviewsProduceExactlyOneAdjudication() throws Exception {
        createCert("INS-C1");
        createBatch("B-C1", "INS-C1");
        long outlierId = submitOutlierReading("B-C1", "RD-C1");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String[] reviewers = {"bob", "carol"};
        for (String reviewer : reviewers) {
            results.add(pool.submit(() -> {
                start.await();
                return review(outlierId, reviewer, 1);
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        for (Future<Integer> future : results) {
            if (future.get(30, TimeUnit.SECONDS) == 201) {
                created.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(2, created.get(), "两名复核人均应提交成功");
        Integer validReviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_record WHERE status = 'VALID'", Integer.class);
        Integer adjudications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adjudication_record", Integer.class);
        assertEquals(2, validReviews);
        assertEquals(1, adjudications, "并发复核必须恰好形成一次裁决");
        assertEquals("ACCEPTED", jdbc.queryForObject(
                "SELECT status FROM outlier_record WHERE id = ?", String.class, outlierId));
    }

    @Test
    void releaseAndFinalReviewFollowCommitOrder() throws Exception {
        // 重复多轮：放行读取的离群集合必须与复核提交顺序一致
        for (int round = 0; round < 10; round++) {
            String key = "B-C2-" + round;
            createCert("INS-C2-" + round);
            createBatch(key, "INS-C2-" + round);
            long outlierId = submitOutlierReading(key, "RD-C2-" + round);
            assertEquals(201, review(outlierId, "bob", 1));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> reviewFuture = pool.submit(() -> {
                start.await();
                return review(outlierId, "carol", 1);
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return release(key, "dave");
            });
            start.countDown();
            int reviewStatus = reviewFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(201, reviewStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 422,
                    "放行只能成功或因未裁决离群值被 422 阻断，实际: " + releaseStatus);

            String batchStatus = jdbc.queryForObject(
                    "SELECT status FROM measurement_batch WHERE batch_key = ?", String.class, key);
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outlier_record o JOIN measurement_batch b "
                            + "ON b.id = o.batch_id WHERE b.batch_key = ? AND o.status = 'PENDING'",
                    Integer.class, key);
            if (releaseStatus == 200) {
                // 放行先读取到已裁决集合：批次已放行且不存在未裁决离群值
                assertEquals("RELEASED", batchStatus);
                assertEquals(0, pending, "放行后不得存在未裁决离群值");
            } else {
                // 复核后放行仍须重试：批次保持 OPEN，离群已裁决
                assertEquals("OPEN", batchStatus);
                assertEquals(0, pending);
                assertEquals(200, release(key, "dave"), "裁决完成后重试放行必须成功");
            }
        }
    }

    @Test
    void concurrentReleaseSameBatchExactlyOneWins() throws Exception {
        createCert("INS-C3");
        createBatch("B-C3", "INS-C3");
        mvc.perform(post("/api/batches/{key}/readings", "B-C3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"readingKey":"RD-C3","measuredAt":"2026-06-01T00:00:00Z",
                         "reading":"10.1","submittedBy":"alice"}"""));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return release("B-C3", "carol");
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
        assertEquals(1, ok.get(), "并发重复放行必须恰好一次成功");
        assertEquals(threads - 1, conflict.get());
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        assertEquals(1, snapshots, "只允许写入一条放行快照");
    }
}