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
 * 复核、修订与重新放行的并发与幂等边界：
 * 同批次并发复核/重新放行按提交顺序恰好一个生效；同幂等键并发同参重放返回同一结果；
 * 并发创建后继修订恰好一个成功；重新放行与证书撤销按提交顺序生效。
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
        jdbc.update("DELETE FROM release_lineage");
        jdbc.update("DELETE FROM batch_snapshot");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM release_batch");
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

    private void submit(String key, String instrument, String reading, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, by)))
                .andExpect(status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        StringBuilder keyJson = new StringBuilder();
        for (String key : keys) {
            if (keyJson.length() > 0) {
                keyJson.append(',');
            }
            keyJson.append('"').append(key).append('"');
        }
        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + keyJson + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batchId");
    }

    private void review(String reviewKey, String batchId, String key) throws Exception {
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"%s","batchId":"%s",
                                 "items":[{"measurementKey":"%s","version":0,"reason":"bad"}]}
                                """.formatted(reviewKey, batchId, key)))
                .andExpect(status().isOk());
    }

    private void revise(String key, String reading) throws Exception {
        mvc.perform(post("/api/measurements/{key}/revisions", key)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"" + reading + "\"}"))
                .andExpect(status().isCreated());
    }

    private String reReleaseBody(String requestId, String key, int version) {
        return """
                {"requestId":"%s",
                 "items":[{"measurementKey":"%s","version":%d}]}
                """.formatted(requestId, key, version);
    }

    @Test
    void concurrentReviewSameBatchExactlyOneWins() throws Exception {
        createCert("INS-C1");
        submit("M-C1", "INS-C1", "1", "alice");
        String batchId = release("carol", "M-C1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/reviews")
                                .header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"RV-C-%d","batchId":"%s",
                                         "items":[{"measurementKey":"M-C1","version":0,"reason":"bad"}]}
                                        """.formatted(index, batchId)))
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
        assertEquals(1, ok.get(), "同批次并发复核必须恰好一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_record", Integer.class));
        assertEquals("REVIEW_REQUIRED",
                jdbc.queryForObject("SELECT status FROM release_batch WHERE batch_id = ?",
                        String.class, batchId));
    }

    @Test
    void concurrentSameReviewKeyReplayReturnsSameResult() throws Exception {
        createCert("INS-C2");
        submit("M-C2", "INS-C2", "1", "alice");
        String batchId = release("carol", "M-C2");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/reviews")
                                .header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"RV-SAME","batchId":"%s",
                                         "items":[{"measurementKey":"M-C2","version":0,"reason":"bad"}]}
                                        """.formatted(batchId)))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        for (Future<String> future : results) {
            assertEquals(first, future.get(30, TimeUnit.SECONDS), "同键同参并发重放必须返回同一结果");
        }
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_record", Integer.class));
    }

    @Test
    void concurrentRevisionCreationExactlyOneWins() throws Exception {
        createCert("INS-C3");
        submit("M-C3", "INS-C3", "1", "alice");
        String batchId = release("carol", "M-C3");
        review("RV-C3", batchId, "M-C3");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revisions", "M-C3")
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"reading\":\"2\"}"))
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
        assertEquals(1, created.get(), "并发创建后继修订必须恰好一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-C3'", Integer.class));
    }

    @Test
    void concurrentReReleaseSameBatchExactlyOneWins() throws Exception {
        createCert("INS-C4");
        submit("M-C4", "INS-C4", "1", "alice");
        String batchId = release("carol", "M-C4");
        review("RV-C4", batchId, "M-C4");
        revise("M-C4", "2");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                                .header("X-Actor-Id", "erin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reReleaseBody("RR-C-" + index, "M-C4", 1)))
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
        assertEquals(1, ok.get(), "同批次并发重新放行必须恰好一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
        assertEquals("SUPERSEDED",
                jdbc.queryForObject("SELECT status FROM release_batch WHERE batch_id = ?",
                        String.class, batchId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_batch WHERE status = 'RELEASED'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdReReleaseReturnsSameBatch() throws Exception {
        createCert("INS-C5");
        submit("M-C5", "INS-C5", "1", "alice");
        String batchId = release("carol", "M-C5");
        review("RV-C5", batchId, "M-C5");
        revise("M-C5", "2");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                                .header("X-Actor-Id", "erin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reReleaseBody("RR-SAME", "M-C5", 1)))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        for (Future<String> future : results) {
            assertEquals(first, future.get(30, TimeUnit.SECONDS),
                    "同 requestId 同参并发重放必须返回同一批次");
        }
        pool.shutdown();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_batch WHERE request_id = 'RR-SAME'", Integer.class));
    }

    @Test
    void reReleaseAndRevokeConcurrentFollowCommitOrder() throws Exception {
        // 重复多轮，验证任意调度下都不出现“证书已撤销但重新放行仍生效”
        for (int round = 0; round < 10; round++) {
            final int r = round;
            long certId = createCert("INS-CR" + r);
            submit("M-CR" + r, "INS-CR" + r, "1", "alice");
            String batchId = release("carol", "M-CR" + r);
            review("RV-CR" + r, batchId, "M-CR" + r);
            revise("M-CR" + r, "2");

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
                                .header("X-Actor-Id", "erin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reReleaseBody("RR-CR" + r, "M-CR" + r, 1)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int reReleaseStatus = reReleaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(reReleaseStatus == 200 || reReleaseStatus == 409);

            String oldBatchStatus = jdbc.queryForObject(
                    "SELECT status FROM release_batch WHERE batch_id = ?", String.class, batchId);
            Integer usableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurement m "
                            + "JOIN release_record rr ON rr.measurement_id = m.id "
                            + "JOIN release_batch b ON b.batch_id = rr.batch_id "
                            + "WHERE m.measurement_key = ? AND b.status = 'RELEASED'",
                    Integer.class, "M-CR" + r);
            if (reReleaseStatus == 200) {
                // 重新放行先提交：旧批次被取代，新批次生效（随后证书被撤销，结果失去可用资格）
                assertEquals("SUPERSEDED", oldBatchStatus);
            } else {
                // 撤销先提交：重新放行整批失败，批次保持复核状态，修订仍待放行
                assertEquals("REVIEW_REQUIRED", oldBatchStatus);
                assertEquals(0, usableCount);
                assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM measurement WHERE measurement_key = ? AND version = 1",
                        String.class, "M-CR" + r));
            }
        }
    }
}
