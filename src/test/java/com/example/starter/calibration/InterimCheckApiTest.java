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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 仪器期间核查测试（真实 H2，MODE=MySQL）：
 * PASS/FAIL 判定边界、FAIL 追溯区间、SUSPECT 隔离与可用排除、区间内放行 409、
 * 更晚 PASS 解除（含证书撤销不恢复、重叠 FAIL 不恢复）、幂等与不可改写、查询、并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InterimCheckApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suspect_marking");
        jdbc.update("DELETE FROM isolation_interval");
        jdbc.update("DELETE FROM check_request");
        jdbc.update("DELETE FROM interim_check");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2029-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String measuredAt, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"%s","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, measuredAt, by)))
                .andExpect(status().isCreated());
    }

    private void release(String... keys) throws Exception {
        String array = String.join(",", List.of(keys).stream().map(k -> "\"" + k + "\"").toList());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + array + "]}"))
                .andExpect(status().isOk());
    }

    private ResultActions check(String requestId, String checkKey, String instrument, String checkedAt,
                                String standard, String measured, String tolerance) throws Exception {
        return mvc.perform(post("/api/interim-checks").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","checkKey":"%s","instrumentId":"%s","checkedAt":"%s",
                         "standardValue":"%s","measuredValue":"%s","tolerance":"%s","checkedBy":"dave"}
                        """.formatted(requestId, checkKey, instrument, checkedAt,
                        standard, measured, tolerance)));
    }

    @Test
    void passFailJudgmentUsesBigDecimalBoundaryAndSixDecimals() throws Exception {
        // |5.000001 - 5| = 0.000001 <= 0.000001 → PASS（边界含端点）
        check("REQ-P1", "CK-P1", "INS-J", "2026-03-01T00:00:00Z",
                "5.000001", "5", "0.000001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("PASS"))
                .andExpect(jsonPath("$.deviation").value("0.000001"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 差 0.000002 > 0.000001 → FAIL
        check("REQ-F1", "CK-F1", "INS-J", "2026-04-01T00:00:00Z",
                "5.000002", "5", "0.000001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("FAIL"))
                .andExpect(jsonPath("$.deviation").value("0.000002"));

        // 负数差值同样按绝对值判定
        check("REQ-P2", "CK-P2", "INS-J", "2026-05-01T00:00:00Z",
                "-1.5", "-1.6", "0.1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("PASS"));

        // 超过 6 位小数 → 400
        check("REQ-BAD", "CK-BAD", "INS-J", "2026-06-01T00:00:00Z",
                "1.0000001", "1", "0.1")
                .andExpect(status().isBadRequest());
        // 负容差 → 400
        check("REQ-BAD2", "CK-BAD2", "INS-J", "2026-06-02T00:00:00Z",
                "1", "1", "-0.01")
                .andExpect(status().isBadRequest());
    }

    @Test
    void failMarksTraceabilityRangeAndExcludesSuspectFromUsable() throws Exception {
        createCert("INS-T");
        // PASS 核查在 02-01；测量分布：区间内 02-01（含）、03-01，区间外 01-15 与 04-01（FAIL 时刻不含）
        check("REQ-P", "CK-PASS", "INS-T", "2026-02-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        submit("M-OUT-BEFORE", "INS-T", "2026-01-15T00:00:00Z", "alice");
        submit("M-AT-PASS", "INS-T", "2026-02-01T00:00:00Z", "alice");
        submit("M-IN", "INS-T", "2026-03-01T00:00:00Z", "bob");
        submit("M-AT-FAIL", "INS-T", "2026-04-01T00:00:00Z", "bob");
        release("M-OUT-BEFORE", "M-AT-PASS", "M-IN", "M-AT-FAIL");

        check("REQ-F", "CK-FAIL", "INS-T", "2026-04-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("FAIL"));

        // 隔离区间 [2026-02-01, 2026-04-01)
        mvc.perform(get("/api/interim-checks/intervals").param("instrumentId", "INS-T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].checkKey").value("CK-FAIL"))
                .andExpect(jsonPath("$[0].rangeFrom").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].rangeTo").value("2026-04-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].resolved").value(false));

        // 区间内两条已放行：SUSPECT=true、usable=false、状态仍 RELEASED、放行历史保留
        mvc.perform(get("/api/measurements/{key}", "M-AT-PASS"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.suspect").value(true))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
        mvc.perform(get("/api/measurements/{key}", "M-IN"))
                .andExpect(jsonPath("$.suspect").value(true))
                .andExpect(jsonPath("$.usable").value(false));
        // 区间外不受影响
        mvc.perform(get("/api/measurements/{key}", "M-OUT-BEFORE"))
                .andExpect(jsonPath("$.suspect").value(false))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/measurements/{key}", "M-AT-FAIL"))
                .andExpect(jsonPath("$.suspect").value(false))
                .andExpect(jsonPath("$.usable").value(true));

        // 当前可用结果只剩区间外两条
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-T"))
                .andExpect(jsonPath("$.length()").value(2));

        // 受影响结果查询：区间内两条（FAIL 时刻的不含）
        mvc.perform(get("/api/interim-checks/{key}/affected-results", "CK-FAIL"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.measurementKey=='M-AT-PASS')].suspect[0]").value(true));
    }

    @Test
    void rangeStartsAtEarliestMeasurementWhenNoPriorPass() throws Exception {
        createCert("INS-E");
        submit("E-1", "INS-E", "2026-01-10T00:00:00Z", "alice");
        submit("E-2", "INS-E", "2026-02-10T00:00:00Z", "bob");
        release("E-1", "E-2");

        check("REQ-EF", "CK-EARLY-FAIL", "INS-E", "2026-05-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/interim-checks/intervals").param("instrumentId", "INS-E"))
                .andExpect(jsonPath("$[0].rangeFrom").value("2026-01-10T00:00:00Z"))
                .andExpect(jsonPath("$[0].rangeTo").value("2026-05-01T00:00:00Z"));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-E"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pendingResultInsideRangeRejectedWithBlockingCheckKey() throws Exception {
        createCert("INS-B");
        submit("B-1", "INS-B", "2026-03-01T00:00:00Z", "alice");
        check("REQ-BF", "CK-BLOCK", "INS-B", "2026-04-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"B-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("B-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNDER_INTERIM_ISOLATION"))
                .andExpect(jsonPath("$.failures[0].blockingCheckKey").value("CK-BLOCK"));

        // 区间外测量同批不受影响：与区间内混合时整批失败，但区间外单项可放行
        submit("B-2", "INS-B", "2026-05-01T00:00:00Z", "bob");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"B-2\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void laterPassResolvesIsolationButNotRevokedCertOrOtherFailCoverage() throws Exception {
        // 场景一：证书撤销导致的不可用不因解除隔离而恢复
        long certId = createCert("INS-R");
        submit("RV-1", "INS-R", "2026-03-01T00:00:00Z", "alice");
        release("RV-1");
        check("REQ-RF", "CK-R-FAIL", "INS-R", "2026-04-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        check("REQ-RP", "CK-R-PASS", "INS-R", "2026-05-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/measurements/{key}", "RV-1"))
                .andExpect(jsonPath("$.suspect").value(false))
                .andExpect(jsonPath("$.usable").value(false));
        mvc.perform(get("/api/interim-checks/intervals").param("instrumentId", "INS-R"))
                .andExpect(jsonPath("$[0].resolved").value(true))
                .andExpect(jsonPath("$[0].resolvedByCheckKey").value("CK-R-PASS"));

        // 场景二：多个重叠 FAIL 同时解除；仍被更晚未解除 FAIL 覆盖的结果不恢复
        createCert("INS-O");
        submit("O-1", "INS-O", "2026-03-01T00:00:00Z", "alice");
        submit("O-2", "INS-O", "2026-06-10T00:00:00Z", "bob");
        release("O-1", "O-2");
        // FAIL1 04-01 覆盖 O-1；FAIL2 07-01 覆盖 O-1、O-2
        check("REQ-OF1", "CK-O-FAIL-1", "INS-O", "2026-04-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());
        check("REQ-OF2", "CK-O-FAIL-2", "INS-O", "2026-07-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/measurements/{key}", "O-1"))
                .andExpect(jsonPath("$.suspect").value(true));
        // PASS 08-01 同时解除 FAIL1/FAIL2：O-1、O-2 均恢复
        check("REQ-OP1", "CK-O-PASS-1", "INS-O", "2026-08-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/measurements/{key}", "O-1"))
                .andExpect(jsonPath("$.suspect").value(false))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/measurements/{key}", "O-2"))
                .andExpect(jsonPath("$.suspect").value(false))
                .andExpect(jsonPath("$.usable").value(true));

        // FAIL3 09-01 覆盖 O-2；更早的 PASS 08-15 不能解除它
        check("REQ-OF3", "CK-O-FAIL-3", "INS-O", "2026-09-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());
        check("REQ-OP2", "CK-O-PASS-2", "INS-O", "2026-08-15T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/measurements/{key}", "O-2"))
                .andExpect(jsonPath("$.suspect").value(true))
                .andExpect(jsonPath("$.usable").value(false));
        mvc.perform(get("/api/measurements/{key}", "O-1"))
                .andExpect(jsonPath("$.suspect").value(false));
        mvc.perform(get("/api/interim-checks/intervals")
                        .param("instrumentId", "INS-O").param("resolved", "false"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].checkKey").value("CK-O-FAIL-3"));
    }

    @Test
    void requestIdIdempotencyReplaySameParamsMismatchAndFailureDoesNotConsume() throws Exception {
        createCert("INS-I");
        // 首次生效
        check("REQ-IDEM", "CK-IDEM", "INS-I", "2026-03-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.id").isNumber());

        // 同参重放（含等价十进制写法）：返回首次结果，不再次生效
        check("REQ-IDEM", "CK-IDEM", "INS-I", "2026-03-01T00:00:00Z", "1.0", "1.00", "0.10")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.checkKey").value("CK-IDEM"));
        Integer checkRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interim_check WHERE request_id = 'REQ-IDEM'", Integer.class);
        assertEquals(1, checkRows, "重放不得再写核查记录");

        // 异参 409
        check("REQ-IDEM", "CK-IDEM", "INS-I", "2026-03-01T00:00:00Z", "2", "1", "0.1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 失败不占键：先用冲突参数失败，再以同一 requestId 正常生效
        check("REQ-REUSE", "CK-REUSE", "INS-I", "2026-03-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        check("REQ-REUSE", "CK-OTHER", "INS-I", "2026-03-02T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isConflict());
        Integer requestRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM check_request WHERE request_id = 'REQ-REUSE'", Integer.class);
        assertEquals(1, requestRows, "异参失败不得新增幂等登记");

        // checkKey 全局唯一：不同 requestId 复用 checkKey → 409
        check("REQ-NEW", "CK-IDEM", "INS-I", "2026-03-05T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHECK_KEY"));
        // 同一仪器同一时刻只允许一条
        check("REQ-NEW2", "CK-UNIQUE-TIME", "INS-I", "2026-03-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INSTRUMENT_CHECK_TIME"));
        // 前一个失败不占键：REQ-NEW2 换时刻可正常生效
        check("REQ-NEW2", "CK-UNIQUE-TIME", "INS-I", "2026-03-06T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
    }

    @Test
    void historyAndQueriesFilterByInstrumentAndVerdict() throws Exception {
        check("REQ-H1", "CK-H1", "INS-H1", "2026-03-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());
        check("REQ-H2", "CK-H2", "INS-H2", "2026-03-02T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/interim-checks"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/interim-checks").param("instrumentId", "INS-H1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].checkKey").value("CK-H1"));
        mvc.perform(get("/api/interim-checks/{key}", "CK-H2"))
                .andExpect(jsonPath("$.verdict").value("FAIL"));
        mvc.perform(get("/api/interim-checks/{key}", "NO-SUCH"))
                .andExpect(status().isNotFound());
        // PASS 核查不产生隔离区间
        mvc.perform(get("/api/interim-checks/intervals"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void failAndReleaseConcurrentFollowCommitOrder() throws Exception {
        // 重复多轮：FAIL 先提交则放行 409 且保持 PENDING；放行先提交则结果随即 SUSPECT
        for (int round = 0; round < 15; round++) {
            final int r = round;
            String instrument = "INS-X%d".formatted(r);
            createCert(instrument);
            submit("X-%d".formatted(r), instrument, "2026-03-01T00:00:00Z", "alice");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> checkFuture = pool.submit(() -> {
                start.await();
                return check("REQ-XF%d".formatted(r), "CK-XF%d".formatted(r), instrument,
                        "2026-04-01T00:00:00Z", "9", "1", "0.1")
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"X-" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int checkStatus = checkFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(201, checkStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409,
                    "放行只能成功或整批冲突，round=" + r);

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "X-" + r);
            Integer activeMarkings = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM suspect_marking s JOIN interim_check c ON c.id = s.check_id "
                            + "WHERE c.check_key = ? AND s.cleared_by_check_id IS NULL",
                    Integer.class, "CK-XF%d".formatted(r));
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, "X-" + r);

            if (releaseStatus == 200) {
                // 放行先提交：RELEASED 历史保留，但随即被 SUSPECT 隔离
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(1, releaseCount);
                assertEquals(1, activeMarkings, "放行先提交后必须被标记 SUSPECT，round=" + r);
            } else {
                // FAIL 先提交：整批失败，无放行历史，保持 PENDING
                assertEquals("PENDING", measurement.get("status"));
                assertEquals(0, releaseCount);
                assertEquals(0, activeMarkings, "待放行结果不写 SUSPECT 标记，round=" + r);
            }
        }
    }

    @Test
    void concurrentSameRequestIdAtMostOneTakesEffect() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/interim-checks").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"REQ-CONC","checkKey":"CK-CONC","instrumentId":"INS-C",
                                         "checkedAt":"2026-03-01T00:00:00Z",
                                         "standardValue":"1","measuredValue":"1","tolerance":"0.1",
                                         "checkedBy":"dave"}"""))
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
        assertEquals(1, created.get(), "同一 checkKey/requestId 并发最多一条生效");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM check_request", Integer.class));
    }

    @Test
    void checkHistoryIsImmutableAndResolutionHistoryKept() throws Exception {
        createCert("INS-K");
        submit("K-1", "INS-K", "2026-03-01T00:00:00Z", "alice");
        release("K-1");
        check("REQ-KF", "CK-KF", "INS-K", "2026-04-01T00:00:00Z", "9", "1", "0.1")
                .andExpect(status().isCreated());
        check("REQ-KP", "CK-KP", "INS-K", "2026-05-01T00:00:00Z", "1", "1", "0.1")
                .andExpect(status().isCreated());

        // 解除后区间与标记历史仍可查，标记记录保留清除信息而非删除
        Integer totalMarkings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suspect_marking s JOIN interim_check c ON c.id = s.check_id "
                        + "WHERE c.check_key = 'CK-KF'", Integer.class);
        Integer clearedMarkings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suspect_marking s JOIN interim_check c ON c.id = s.check_id "
                        + "WHERE c.check_key = 'CK-KF' AND s.cleared_by_check_id IS NOT NULL", Integer.class);
        assertEquals(1, totalMarkings, "解除不得删除标记历史");
        assertEquals(1, clearedMarkings);
        assertFalse(jdbc.queryForMap("SELECT * FROM interim_check WHERE check_key = 'CK-KF'")
                .isEmpty());
    }
}
