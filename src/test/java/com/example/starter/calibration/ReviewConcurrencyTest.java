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
 * 复核、修订、证书撤销与重新放行的并发边界测试：
 * 1. 并发复核同一批次（不同 reviewKey）恰好一条成功；同 reviewKey 同参其余重放成功且不重复落库；
 * 2. 并并发创建同一测量后继修订恰好一条成功；
 * 3. 证书撤销与重新放行并发时按事务提交顺序生效，不混用版本；
 * 4. 并发重新放行（不同 requestId）恰好生成一个新批次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM request_idempotency");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM review_item");
        jdbc.update("DELETE FROM batch_review");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, by)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        StringBuilder keyList = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                keyList.append(',');
            }
            keyList.append('"').append(keys[i]).append('"');
        }
        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + keyList + "]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batchId");
    }

    private void reviewSingle(String batchId, String reviewKey, String actor) throws Exception {
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"%s",
                                 "rejections":[{"position":1,"version":1,"reason":"r"}]}
                                """.formatted(reviewKey)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void concurrentReviewsDifferentKeysExactlyOneWins() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "alice");
        String batchId = release("carol", "M-1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/review", batchId)
                                .header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"CRK-%d",
                                         "rejections":[{"position":1,"version":1,"reason":"r"}]}
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
        assertEquals(1, ok.get(), "并发复核必须恰好一条成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_review", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_item", Integer.class));
    }

    @Test
    void concurrentReviewsSameKeyReplayWithoutDuplicates() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "alice");
        String batchId = release("carol", "M-1");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/review", batchId)
                                .header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"SAME-KEY",
                                         "rejections":[{"position":1,"version":1,"reason":"r"}]}
                                        """))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) {
            assertEquals(200, future.get(30, TimeUnit.SECONDS), "同参同键并发必须全部重放成功");
        }
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_review", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_idempotency WHERE request_id = 'SAME-KEY'", Integer.class));
    }

    @Test
    void concurrentRevisionsExactlyOneWins() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "alice");
        String batchId = release("carol", "M-1");
        reviewSingle(batchId, "RK-1", "dave");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/revisions")
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measurementKey":"M-1","reading":"2","note":"fix"}
                                        """))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "单一后继修订：并发创建必须恰好一条成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE revision_of IS NOT NULL", Integer.class));
    }

    @Test
    void revokeAndReReleaseFollowCommitOrder() throws Exception {
        for (int round = 0; round < 12; round++) {
            final int r = round;
            long certId = createCert("INS-R" + r, "1", "0");
            submit("M-" + r, "INS-R" + r, "1", "alice");
            String batchId = release("carol", "M-" + r);
            reviewSingle(batchId, "RK-" + r, "dave");
            mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"measurementKey\":\"M-" + r + "\",\"reading\":\"2\",\"note\":\"fix\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> reReleaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                                .header("X-Actor-Id", "frank")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"RR-%d",
                                         "items":[{"position":1,"measurementKey":"M-%d#r1","version":1}]}
                                        """.formatted(r, r)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int reReleaseStatus = reReleaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(reReleaseStatus == 200 || reReleaseStatus == 409,
                    "重新放行只可能成功或整批冲突: " + reReleaseStatus);

            Integer newBatchCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_lineage WHERE source_batch_id = ?",
                    Integer.class, batchId);
            if (newBatchCount > 0) {
                // 重新放行先提交：新批次存在，证书撤销在其后生效（结果保留但不再可用）
                assertEquals(200, reReleaseStatus);
                assertTrue(jdbc.queryForObject(
                        "SELECT revoked FROM calibration_certificate WHERE id = ?", Boolean.class, certId));
            } else {
                // 撤销先提交：重新放行必须 409 CERTIFICATE_REVOKED，无新批次
                assertEquals(409, reReleaseStatus);
            }
        }
    }

    @Test
    void concurrentReReleaseDifferentRequestsExactlyOneBatch() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "alice");
        submit("M-2", "INS-1", "2", "alice");
        String batchId = release("carol", "M-1", "M-2");
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RK-1",
                                 "rejections":[{"position":1,"version":1,"reason":"r"}]}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-1\",\"reading\":\"1\",\"note\":\"fix\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                                .header("X-Actor-Id", "frank")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"RR-%d",
                                         "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                                                  {"position":2,"measurementKey":"M-2","version":1}]}
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
        assertEquals(1, ok.get(), "并发重新放行必须恰好生成一个新批次");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT new_batch_id) FROM batch_lineage WHERE source_batch_id = ?",
                Integer.class, batchId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_batch WHERE released_by = 'frank'", Integer.class),
                "只应生成一个新批次");
    }

    @Test
    void concurrentReReleaseSameRequestReplays() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "alice");
        String batchId = release("carol", "M-1");
        reviewSingle(batchId, "RK-1", "dave");
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-1\",\"reading\":\"1\",\"note\":\"fix\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                                .header("X-Actor-Id", "frank")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"SAME-RR",
                                         "items":[{"position":1,"measurementKey":"M-1#r1","version":1}]}
                                        """))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        String newBatchId = null;
        for (Future<String> future : results) {
            String body = future.get(30, TimeUnit.SECONDS);
            String id = com.jayway.jsonpath.JsonPath.read(body, "$.newBatchId");
            if (newBatchId == null) {
                newBatchId = id;
            }
            assertEquals(newBatchId, id, "同参同键并发必须重放同一新批次");
        }
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE new_batch_id = ?",
                Integer.class, newBatchId));
    }
}
