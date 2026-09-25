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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复核并发与幂等边界测试：
 * 1. 并发同类复核同一版本时恰好一条成功，其余 409；
 * 2. 同 requestId 同参并发提交时重放首次结果，仅产生一条复核记录；
 * 3. 修订与放行并发按事务提交顺序裁决，结果必居其一且状态一致。
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
        jdbc.update("DELETE FROM review_request");
        jdbc.update("DELETE FROM measurement_review");
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
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    private void passReview(String key, String reviewKey, String requestId) throws Exception {
        mvc.perform(post("/api/measurements/{key}/reviews", key)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"%s","requestId":"%s","revision":1,
                                 "conclusion":"PASS","comment":"复核通过"}
                                """.formatted(reviewKey, requestId)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentSameConclusionReviewsExactlyOneSucceeds() throws Exception {
        createCert("INS-C1");
        submit("M-C1", "INS-C1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/reviews", "M-C1")
                                .header("X-Actor-Id", "reviewer-" + index)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"RV-C1-%d","requestId":"REQ-C1-%d","revision":1,
                                         "conclusion":"PASS","comment":"并发复核"}
                                        """.formatted(index, index)))
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
        assertEquals(1, created.get(), "同一版本同类有效复核必须恰好一条成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为 409 冲突");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_review WHERE conclusion = 'PASS' AND status = 'VALID'",
                Integer.class);
        assertEquals(1, count, "只允许写入一条有效 PASS 复核");
    }

    @Test
    void concurrentSameRequestIdReplaysFirstResult() throws Exception {
        createCert("INS-C2");
        submit("M-C2", "INS-C2");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                String body = mvc.perform(post("/api/measurements/{key}/reviews", "M-C2")
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"RV-C2","requestId":"REQ-C2","revision":1,
                                         "conclusion":"PASS","comment":"同键同参"}
                                        """))
                        .andReturn().getResponse().getContentAsString();
                return com.jayway.jsonpath.JsonPath.read(body, "$.reviewKey") != null ? 201 : 0;
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        for (Future<Integer> future : results) {
            if (future.get(30, TimeUnit.SECONDS) == 201) {
                ok.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(threads, ok.get(), "同键同参并发必须全部重放首次结果");
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM measurement_review", Integer.class);
        assertEquals(1, reviewCount, "同键同参并发只允许产生一条复核记录");
        Integer requestCount = jdbc.queryForObject("SELECT COUNT(*) FROM review_request", Integer.class);
        assertEquals(1, requestCount, "requestId 只被占用一次");
    }

    @Test
    void reviseAndReleaseConcurrentFollowCommitOrder() throws Exception {
        // 重复多轮，验证任意调度下结果一致：放行先成功则修订 409；修订先提交则放行 422
        for (int round = 0; round < 10; round++) {
            final int r = round;
            createCert("INS-RR" + r);
            submit("M-RR" + r, "INS-RR" + r);
            passReview("M-RR" + r, "RV-RR" + r, "REQ-RR" + r);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> reviseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revisions", "M-RR" + r)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"M-RR" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-RR" + r);
            if (releaseStatus == 200) {
                // 放行先提交：修订不得再生效，状态保持已放行
                assertEquals(409, reviseStatus, "放行成功后修订必须 409");
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(1, ((Number) measurement.get("revision")).intValue());
            } else {
                // 修订先提交：旧复核失效，放行 422，测量回到待放行新版本
                assertEquals(422, releaseStatus, "修订先提交后放行必须 422");
                assertEquals(200, reviseStatus);
                assertEquals("PENDING", measurement.get("status"));
                assertEquals(2, ((Number) measurement.get("revision")).intValue());
            }
        }
    }

    @Test
    void reviewAndReviseConcurrentReviewEitherValidOrStale() throws Exception {
        // 复核与修订并发：复核先提交则 201（随后被修订失效），修订先提交则复核 410 STALE
        for (int round = 0; round < 10; round++) {
            final int r = round;
            createCert("INS-RV" + r);
            submit("M-RV" + r, "INS-RV" + r);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> reviewFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/reviews", "M-RV" + r)
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reviewKey":"RV-RV-%d","requestId":"REQ-RV-%d","revision":1,
                                         "conclusion":"PASS","comment":"与修订并发"}
                                        """.formatted(r, r)))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> reviseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revisions", "M-RV" + r)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int reviewStatus = reviewFuture.get(30, TimeUnit.SECONDS);
            int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(200, reviseStatus, "未放行测量的修订必须成功");
            assertTrue(reviewStatus == 201 || reviewStatus == 410,
                    "并发复核必须成功（201）或被判为过期（410），实际: " + reviewStatus);

            Map<String, Object> review = jdbc.queryForMap(
                    "SELECT * FROM measurement_review WHERE review_key = ?", "RV-RV-" + r);
            if (reviewStatus == 410) {
                assertEquals("STALE", review.get("status"), "修订先提交时复核必须记为 STALE");
            } else {
                assertEquals("VALID", review.get("status"));
            }
            // 无论哪种顺序，旧版本复核都不得用于新版本放行
            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-RV" + r);
            assertEquals(2, ((Number) measurement.get("revision")).intValue());
            Integer effectivePass = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurement_review r JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.measurement_key = ? AND r.conclusion = 'PASS' "
                            + "AND r.status = 'VALID' AND r.measurement_revision = m.revision",
                    Integer.class, "M-RV" + r);
            assertEquals(0, effectivePass, "修订后旧版本复核不得计入放行门禁");
        }
    }
}
