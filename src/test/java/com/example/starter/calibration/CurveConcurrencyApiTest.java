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
 * 曲线与幂等的并发边界测试：
 * 1. 线性与曲线证书混合并发创建重叠区间时最多一张成功，最终集合不重叠；
 * 2. 同 requestId 同参数并发提交时全部重放首次响应，仅落一条业务记录，不泄露唯一键异常。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurveConcurrencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM certificate_node");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void concurrentMixedTypeCertificatesAtMostOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean curve = i % 2 == 1;
            results.add(pool.submit(() -> {
                start.await();
                String body = curve
                        ? """
                          {"instrumentId":"INS-MIX","validFrom":"2026-01-01T00:00:00Z",
                           "validTo":"2027-01-01T00:00:00Z","type":"CURVE",
                           "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                                    {"raw":"6","corrected":"4"}]}
                          """
                        : """
                          {"instrumentId":"INS-MIX","validFrom":"2026-01-01T00:00:00Z",
                           "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}
                          """;
                return mvc.perform(post("/api/certificates")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
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
        assertEquals(1, created.get(), "线性与曲线混合并发创建重叠区间必须恰好一张成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为 409 冲突");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-MIX' AND revoked = FALSE",
                Integer.class);
        assertEquals(1, active, "最终未撤销证书集合不得重叠");
    }

    @Test
    void concurrentSameRequestIdSubmitReplaysSingleResponse() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-IDEM","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        String json = """
                {"measurementKey":"M-IDEM","instrumentId":"INS-IDEM",
                 "measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice","requestId":"REQ-X-1"}
                """;
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                var response = mvc.perform(post("/api/measurements")
                                .contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn().getResponse();
                return response.getStatus() + "|" + response.getContentAsString();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        List<String> ids = new ArrayList<>();
        for (Future<String> future : results) {
            String outcome = future.get(30, TimeUnit.SECONDS);
            int status = Integer.parseInt(outcome.substring(0, outcome.indexOf('|')));
            assertEquals(201, status, "同键同参并发必须全部重放首次响应，不得泄露唯一键异常: " + outcome);
            created.incrementAndGet();
            ids.add(com.jayway.jsonpath.JsonPath.read(outcome.substring(outcome.indexOf('|') + 1), "$.id")
                    .toString());
        }
        pool.shutdown();
        assertEquals(threads, created.get());
        assertEquals(1, ids.stream().distinct().count(), "全部并发请求必须拿到同一测量 ID");
        Integer measurementCount = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, measurementCount, "仅允许落一条测量记录");
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idemCount, "仅允许落一条幂等记录");
    }

    @Test
    void concurrentCurveRevokeAndReleaseFollowCommitOrder() throws Exception {
        // 曲线证书撤销与批量放行并发：按事务提交顺序生效，不出现撤销后仍被放行
        for (int round = 0; round < 10; round++) {
            final int r = round;
            String certBody = mvc.perform(post("/api/certificates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"INS-CR%d","validFrom":"2026-01-01T00:00:00Z",
                                     "validTo":"2028-01-01T00:00:00Z","type":"CURVE",
                                     "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                                              {"raw":"6","corrected":"4"}]}
                                    """.formatted(r)))
                    .andReturn().getResponse().getContentAsString();
            long certId = ((Number) com.jayway.jsonpath.JsonPath.read(certBody, "$.id")).longValue();
            mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"measurementKey":"MC-%d","instrumentId":"INS-CR%d",
                             "measuredAt":"2026-06-01T00:00:00Z","reading":"4.5",
                             "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}""".formatted(r, r)))
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
                                .content("{\"keys\":[\"MC-" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            var measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "MC-" + r);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) measurement.get("id")).longValue());
            if (releaseCount > 0) {
                // 放行先提交：历史保留，状态 RELEASED；撤销在其后生效
                assertEquals("RELEASED", measurement.get("status"));
            } else {
                // 撤销先提交：放行整批拒绝，测量保持待放行
                assertEquals(409, releaseStatus);
                assertEquals("PENDING", measurement.get("status"));
            }
        }
    }
}
