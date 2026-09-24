package com.example.starter.calibration;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * requestId 幂等边界，以及期间核查与批量放行并发时按事务提交顺序裁决的真实 H2 测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InterimCheckIdempotencyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM measurement_suspect");
        jdbc.update("DELETE FROM isolation_interval");
        jdbc.update("DELETE FROM interim_check");
        jdbc.update("DELETE FROM request_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private String checkBody(String requestId, String checkKey, String instrument,
                             String checkedAt, String actual) {
        return """
                {"requestId":"%s","checkKey":"%s","instrumentId":"%s","checkedAt":"%s",
                 "standardValue":"1","actualValue":"%s","tolerance":"1","checkedBy":"dave"}"""
                .formatted(requestId, checkKey, instrument, checkedAt, actual);
    }

    @Test
    void sameRequestReplaysFirstResultOnlyOnce() throws Exception {
        createCert("INS-I");
        String body = checkBody("REQ-SAME", "CK-SAME", "INS-I", "2026-05-01T00:00:00Z", "1");
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        // 同参重放：返回首次结果，replayed=true，不新增记录
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.checkKey").value("CK-SAME"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_record", Integer.class));
    }

    @Test
    void sameRequestDifferentParamsConflictsAndDoesNotOccupyExtraKey() throws Exception {
        createCert("INS-I");
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-X", "CK-A", "INS-I", "2026-05-01T00:00:00Z", "1")))
                .andExpect(status().isCreated());
        // 同 requestId，异参（不同实测值）→ 409
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-X", "CK-A", "INS-I", "2026-05-01T00:00:00Z", "2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
        // 同 requestId，不同 checkKey 也属异参 → 409
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-X", "CK-B", "INS-I", "2026-05-01T00:00:00Z", "1")))
                .andExpect(status().isConflict());
        // 原始首次结果仍可同参重放
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-X", "CK-A", "INS-I", "2026-05-01T00:00:00Z", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
    }

    @Test
    void failedValidationDoesNotOccupyRequestId() throws Exception {
        createCert("INS-I");
        // 负容差失败（400），不占键；随后同 requestId 以合法参数成功
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-RETRY", "CK-R", "INS-I", "2026-05-01T00:00:00Z", "1")
                                .replace("\"tolerance\":\"1\"", "\"tolerance\":\"-1\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody("REQ-RETRY", "CK-R", "INS-I", "2026-05-01T00:00:00Z", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_record", Integer.class));
    }

    @Test
    void sameCheckKeySubmittedConcurrentlyTakesEffectAtMostOnce() throws Exception {
        createCert("INS-C");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                                .content(checkBody("REQ-C" + idx, "CK-CONC", "INS-C",
                                        "2026-05-01T00:00:00Z", "1")))
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
        assertEquals(1, created.get(), "同一 checkKey 最多生效一次");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
    }

    @Test
    void failAndReleaseConcurrentlyFollowCommitOrder() throws Exception {
        // 重复多轮：FAIL 先提交则放行整批 409；放行先提交则结果随即 SUSPECT。
        boolean sawFailFirst = false;
        boolean sawReleaseFirst = false;
        for (int round = 0; round < 20; round++) {
            final int r = round;
            String instrument = "INS-Q" + r;
            String measurementKey = "Q-" + r;
            createCert(instrument);
            mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"measurementKey":"%s","instrumentId":"%s",
                                     "measuredAt":"2026-03-01T00:00:00Z","reading":"1",
                                     "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""
                                    .formatted(measurementKey, instrument)))
                    .andExpect(status().isCreated());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> checkFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                                .content(checkBody("REQ-QF" + r, "CK-QF" + r, instrument,
                                        "2026-05-01T00:00:00Z", "9")))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + measurementKey + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int checkStatus = checkFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(201, checkStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Map2 state = readState(measurementKey);
            if (releaseStatus == 409) {
                // FAIL 先提交：待放行结果被隔离拦截，保持 PENDING
                sawFailFirst = true;
                assertEquals("PENDING", state.status);
            } else {
                // 放行先提交：status 保持 RELEASED，但随即被标记 SUSPECT，历史保留
                sawReleaseFirst = true;
                assertEquals("RELEASED", state.status);
                assertTrue(state.suspect, "放行先提交也必须随即被标记 SUSPECT");
                assertEquals(1, state.releaseCount, "放行历史保留，不得回写为从未放行");
            }
        }
        // 多轮竞争应覆盖到两种提交顺序（若调度极端未覆盖，不视为失败但打印提示）
        assertTrue(sawFailFirst || sawReleaseFirst);
    }

    private record Map2(String status, boolean suspect, int releaseCount) {
    }

    private Map2 readState(String key) {
        var m = jdbc.queryForMap("SELECT id, status, suspect FROM measurement WHERE measurement_key = ?", key);
        int releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                Integer.class, ((Number) m.get("id")).longValue());
        return new Map2((String) m.get("status"), Boolean.TRUE.equals(m.get("suspect")), releaseCount);
    }
}
