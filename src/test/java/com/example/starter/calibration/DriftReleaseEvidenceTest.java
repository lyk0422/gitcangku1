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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 漂移模式放行与证据核对测试：放行原子核对两档案与证书；
 * 撤销后当前可用失效但历史保留；撤销与放行并发按提交顺序生效；并发同键幂等提交。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftReleaseEvidenceTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM drift_profile");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long[] setupEvidence(String tag) throws Exception {
        return setupEvidence(tag, "INS-E");
    }

    private long[] setupEvidence(String tag, String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(status().isCreated());
        long t0 = createProfile("REQ-" + tag + "-P0", instrument, "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-" + tag + "-P1", instrument, "2026-06-01T11:00:00Z", "2");
        return new long[]{t0, t1};
    }

    private long createProfile(String requestId, String instrument, String at, String offset) throws Exception {
        String body = mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"op","instrumentId":"%s",
                                 "profiledAt":"%s","referenceOffset":"%s"}
                                """.formatted(requestId, instrument, at, offset)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submitDrift(String requestId, String key, long t0, long t1) throws Exception {
        submitDrift(requestId, key, "INS-E", t0, t1);
    }

    private void submitDrift(String requestId, String key, String instrument,
                             long t0, long t1) throws Exception {
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"alice","measurementKey":"%s",
                                 "instrumentId":"%s","measuredAt":"2026-06-01T10:30:00Z",
                                 "reading":"5","lowerLimit":"0","upperLimit":"9",
                                 "profileT0Id":%d,"profileT1Id":%d}
                                """.formatted(requestId, key, instrument, t0, t1)))
                .andExpect(status().isCreated());
    }

    private void revokeProfile(String requestId, long id) throws Exception {
        mvc.perform(post("/api/drift-profiles/{id}/revoke", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"operator\":\"op\"}".formatted(requestId)))
                .andExpect(status().isOk());
    }

    @Test
    void releasedDriftResultUsableUntilEvidenceRevoked() throws Exception {
        long[] p = setupEvidence("E1");
        submitDrift("REQ-E1-S", "E-1", p[0], p[1]);

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"E-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-E"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].computedValue").value("4"));

        // 撤销任一参考：当前可用失效，历史明细保留且校正值仍为 4
        submitDrift("REQ-E1-S2", "E-2", p[0], p[1]);
        revokeProfile("REQ-E1-R", p[1]);
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-E"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/measurements/{key}", "E-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("4"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 撤销后引用该证据的待放行测量不可新放行
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"E-2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("DRIFT_PROFILE_REVOKED"));
        mvc.perform(get("/api/measurements/{key}", "E-2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void revokedCertificateAlsoBlocksDriftRelease() throws Exception {
        long[] p = setupEvidence("E2");
        submitDrift("REQ-E2-S", "E-3", p[0], p[1]);
        Long certId = jdbc.queryForObject(
                "SELECT certificate_id FROM measurement WHERE measurement_key = 'E-3'", Long.class);
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"E-3\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void profileRevokeAndReleaseConcurrentFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int r = round;
            long[] p = setupEvidence("C" + r, "INS-C" + r);
            String key = "C-" + r;
            submitDrift("REQ-C" + r + "-S", key, "INS-C" + r, p[0], p[1]);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/drift-profiles/{id}/revoke", p[0])
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"REQ-C" + r + "-R\",\"operator\":\"op\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", key);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) measurement.get("id")).longValue());
            if (releaseCount > 0) {
                // 放行先提交：历史保留，撤销在其后生效
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(200, releaseStatus);
            } else {
                // 撤销先提交：放行整批拒绝，测量保持待放行
                assertEquals(409, releaseStatus);
                assertEquals("PENDING", measurement.get("status"));
            }
        }
    }

    @Test
    void concurrentSameRequestIdSubmitsExactlyOnce() throws Exception {
        long[] p = setupEvidence("K");
        String json = """
                {"requestId":"REQ-K-S","operator":"alice","measurementKey":"K-1",
                 "instrumentId":"INS-E","measuredAt":"2026-06-01T10:30:00Z",
                 "reading":"5","lowerLimit":"0","upperLimit":"9",
                 "profileT0Id":%d,"profileT1Id":%d}
                """.formatted(p[0], p[1]);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/drift")
                                .contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn().getResponse().getStatus();
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
        assertEquals(threads, created.get(), "同参并发应全部重放首次成功响应");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'K-1'", Integer.class);
        assertEquals(1, count, "并发同键只产生一条测量");
    }
}
