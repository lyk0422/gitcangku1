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
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 并发与幂等边界测试：
 * 1. 并发创建重叠证书时最多一张成功；
 * 2. 证书撤销与批量放行并发时按事务提交顺序生效，不出现撤销后仍被放行；
 * 3. 并发重复放行同一测量时恰好一批成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyApiTest {

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

    @Test
    void concurrentOverlappingCertificatesAtMostOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                ResultActions action = mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""));
                return action.andReturn().getResponse().getStatus();
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
        assertEquals(1, created.get(), "重叠证书并发创建必须恰好一张成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为 409 冲突");
    }

    @Test
    void revokeAndReleaseConcurrentFollowCommitOrder() throws Exception {
        // 重复多轮，验证任意调度下都不出现“撤销后仍被放行”
        for (int round = 0; round < 15; round++) {
            final int r = round;
            String certBody = mvc.perform(post("/api/certificates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"INS-R%d","validFrom":"2026-01-01T00:00:00Z",
                                     "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(r)))
                    .andReturn().getResponse().getContentAsString();
            long certId = ((Number) com.jayway.jsonpath.JsonPath.read(certBody, "$.id")).longValue();

            mvc.perform(post("/api/measurements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"measurementKey":"M-%d","instrumentId":"INS-R%d",
                             "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                             "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}""".formatted(r, r)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

            // 放行前需具备有效 PASS 同行复核（复核人 dave 不同于提交人 alice）
            mvc.perform(post("/api/reviews")
                            .header("X-Actor-Id", "dave")
                            .header("X-Request-Id", "REQ-REV-" + r)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"measurementKey":"M-%d","reviewKey":"RV-M-%d","version":1,
                                     "conclusion":"PASS","comment":"ok"}""".formatted(r, r)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"M-" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Map<String, Object> cert = jdbc.queryForMap("SELECT * FROM calibration_certificate WHERE id = ?", certId);
            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-" + r);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) measurement.get("id")).longValue());

            if (releaseCount > 0) {
                // 放行先提交：历史必须保留，状态为 RELEASED；撤销在其后生效
                assertEquals("RELEASED", measurement.get("status"));
                java.sql.Timestamp revokedAt = (java.sql.Timestamp) cert.get("revoked_at");
                Map<String, Object> release = jdbc.queryForMap(
                        "SELECT * FROM release_record WHERE measurement_id = ?",
                        ((Number) measurement.get("id")).longValue());
                assertFalse(((java.sql.Timestamp) release.get("released_at"))
                        .after(revokedAt), "撤销提交后不得再放行该证书下的结果");
            } else {
                // 撤销先提交：放行必须 409，测量保持待放行
                assertEquals(409, releaseStatus);
                assertEquals("PENDING", measurement.get("status"));
            }
        }
    }

    @Test
    void concurrentReleaseSameMeasurementExactlyOneWins() throws Exception {
        String certBody = mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-D","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/measurements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"M-D","instrumentId":"INS-D",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .header("X-Request-Id", "REQ-REV-D")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-D","reviewKey":"RV-M-D","version":1,
                                 "conclusion":"PASS","comment":"ok"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"M-D\"]}"))
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
        assertEquals(1, ok.get(), "并发重复放行必须恰好一批成功");
        assertEquals(threads - 1, conflict.get());
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'M-D'", Integer.class);
        assertEquals(1, releaseCount, "只允许写入一条放行历史");
    }
}
