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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同行复核并发与幂等边界（真实 H2 + 行锁）：
 * 1. 并发同类 PASS 复核恰好一条有效，其余 409；
 * 2. 同一 requestId 同参并发重放均返回首次结果，仅落一条复核；
 * 3. 修订与针对旧版本的复核并发，按事务提交顺序裁决（旧复核 VALID 仅留历史或标 STALE），
 *    任何调度下新版本都不会继承旧 PASS。
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
        jdbc.update("DELETE FROM peer_review");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_head");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void prepare(String key) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key)))
                .andExpect(status().isCreated());
    }

    private void submitOnly(String key) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key)))
                .andExpect(status().isCreated());
    }

    private void returnReview(String key, String reviewKey, String reqId) throws Exception {
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "bob").header("X-Request-Id", reqId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","reviewKey":"%s","version":1,
                                 "conclusion":"RETURN","comment":"fix"}""".formatted(key, reviewKey)))
                .andExpect(status().isCreated());
    }

    private String passBody(String key, String reviewKey, int version) {
        return """
                {"measurementKey":"%s","reviewKey":"%s","version":%d,
                 "conclusion":"PASS","comment":"ok"}
                """.formatted(key, reviewKey, version);
    }

    @Test
    void concurrentSameConclusionAtMostOneValid() throws Exception {
        prepare("C-1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/reviews")
                                .header("X-Actor-Id", "reviewer-" + index)
                                .header("X-Request-Id", "REQ-C1-" + index)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(passBody("C-1", "RV-C1-" + index, 1)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int s = future.get(30, TimeUnit.SECONDS);
            if (s == 201) {
                created.incrementAndGet();
            } else if (s == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "同一版本并发 PASS 必须恰好一条有效");
        assertEquals(threads - 1, conflict.get(), "其余必须 409");
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM peer_review WHERE measurement_key='C-1' AND state='VALID' "
                        + "AND conclusion='PASS'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdSameParamsReplaysSingleResult() throws Exception {
        prepare("C-2");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/reviews")
                                .header("X-Actor-Id", "bob")
                                .header("X-Request-Id", "REQ-C2-SAME")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(passBody("C-2", "RV-C2", 1)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) {
            assertEquals(201, future.get(30, TimeUnit.SECONDS), "同键同参并发必须都重放首次 201");
        }
        pool.shutdown();
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM peer_review WHERE measurement_key='C-2'", Integer.class),
                "幂等重放不得产生多条复核");
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_request WHERE request_id='REQ-C2-SAME'", Integer.class));
    }

    @Test
    void revisionAndStaleReviewConcurrentFollowCommitOrderWithoutMigratingGate() throws Exception {
        // 一张有效证书覆盖全部轮次；每轮使用独立测量键避免上一轮状态干扰
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());

        // 重复多轮以覆盖不同调度顺序
        for (int round = 0; round < 10; round++) {
            final int r = round;
            String key = "C-3-" + r;
            submitOnly(key);
            returnReview(key, "RV-RET-" + r, "REQ-RET-" + r);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revise = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revise", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reading":"2","lowerLimit":"0","upperLimit":"9"}"""))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> reviewV1 = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/reviews")
                                .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-PASS-" + r)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(passBody(key, "RV-PASS-" + r, 1)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int reviseStatus = revise.get(30, TimeUnit.SECONDS);
            int reviewStatus = reviewV1.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(200, reviseStatus);
            org.junit.jupiter.api.Assertions.assertTrue(reviewStatus == 201 || reviewStatus == 410,
                    "旧版本复核只能在修订前有效（201）或修订后失效（410）");

            // 不变量：当前版本恒为 v2 且无有效 PASS（旧复核不迁移）
            Map2 current = currentVersion(key);
            assertEquals(2, current.version());
            Integer validPassOnV2 = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM peer_review WHERE measurement_key=? AND version=2 AND state='VALID'",
                    Integer.class, key);
            assertEquals(0, validPassOnV2, "新版本不得继承旧版本 PASS 复核");

            // v2 放行必须被门禁拦截
            mvc.perform(post("/api/measurements/{key}/release", key).header("X-Actor-Id", "dave"))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    private Map2 currentVersion(String key) {
        var row = jdbc.queryForMap(
                "SELECT h.current_version version, m.status status FROM measurement_head h "
                        + "JOIN measurement m ON m.id = h.current_id WHERE h.measurement_key = ?", key);
        return new Map2(((Number) row.get("version")).intValue(), String.valueOf(row.get("status")));
    }

    private record Map2(int version, String status) {
    }
}
