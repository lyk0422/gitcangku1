package com.example.starter.calibration;

import java.util.Map;

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
 * 期间核查判定、追溯区间、隔离与解除、可用结果排除、放行拦截的真实 H2 数据库测试。
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

    private void submit(String key, String instrument, String measuredAt) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""
                                .formatted(key, instrument, measuredAt)))
                .andExpect(status().isCreated());
    }

    private void release(String... keys) throws Exception {
        String array = String.join(",", java.util.Arrays.stream(keys).map(k -> "\"" + k + "\"").toList());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + array + "]}"))
                .andExpect(status().isOk());
    }

    private ResultActions check(String requestId, String checkKey, String instrument, String checkedAt,
                                String standard, String actual, String tolerance) throws Exception {
        return mvc.perform(post("/api/checks").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","checkKey":"%s","instrumentId":"%s","checkedAt":"%s",
                         "standardValue":"%s","actualValue":"%s","tolerance":"%s","checkedBy":"dave"}"""
                        .formatted(requestId, checkKey, instrument, checkedAt, standard, actual, tolerance)));
    }

    private boolean suspect(String key) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT suspect, status FROM measurement WHERE measurement_key = ?", key);
        return Boolean.TRUE.equals(row.get("suspect"));
    }

    private String statusOf(String key) {
        return (String) jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key = ?", key).get("status");
    }

    @Test
    void passWhenDeviationWithinToleranceIncludingBoundary() throws Exception {
        createCert("INS-P");
        // |1.000001 - 1| = 0.000001 == 容差，边界判 PASS
        check("REQ-P1", "CK-P1", "INS-P", "2026-03-01T00:00:00Z", "1", "1.000001", "0.000001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("PASS"))
                .andExpect(jsonPath("$.deviation").value("0.000001"))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void failMarksTraceIntervalAndExcludesUsableButKeepsHistory() throws Exception {
        createCert("INS-F");
        submit("F-1", "INS-F", "2026-03-01T00:00:00Z"); // 区间起点（上一条 PASS 时刻，含）
        submit("F-2", "INS-F", "2026-04-01T00:00:00Z"); // 区间内
        submit("F-3", "INS-F", "2026-05-02T00:00:00Z"); // FAIL 时刻之后，区间外
        release("F-1", "F-2", "F-3");

        // 先有一条 PASS（2026-03-01），FAIL 于 2026-05-01
        check("REQ-PASS", "CK-PASS", "INS-F", "2026-03-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("PASS"));

        check("REQ-FAIL", "CK-FAIL", "INS-F", "2026-05-01T00:00:00Z", "1", "3", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.deviation").value("2"))
                .andExpect(jsonPath("$.isolationRangeFrom").value("2026-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.isolationRangeTo").value("2026-05-01T00:00:00Z"))
                .andExpect(jsonPath("$.affectedKeys.length()").value(2))
                .andExpect(jsonPath("$.affectedKeys[0]").value("F-1"))
                .andExpect(jsonPath("$.affectedKeys[1]").value("F-2"));

        // 状态保持 RELEASED，放行历史保留，但 suspect=TRUE
        assertTrue(suspect("F-1"));
        assertTrue(suspect("F-2"));
        assertFalse(suspect("F-3"));
        assertEquals("RELEASED", statusOf("F-1"));
        Integer releaseHistory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key IN ('F-1','F-2','F-3')", Integer.class);
        assertEquals(3, releaseHistory, "放行历史不得删除或改写");

        // 当前可用结果只剩区间外的 F-3
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-F"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementKey").value("F-3"));

        // 明细视图：suspect=true、usable=false、status=RELEASED
        mvc.perform(get("/api/measurements/F-1"))
                .andExpect(jsonPath("$.suspect").value(true))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases.length()").value(1));
    }

    @Test
    void traceStartsAtEarliestMeasurementWhenNoPriorPass() throws Exception {
        createCert("INS-E");
        submit("E-1", "INS-E", "2026-02-10T08:00:00Z");
        release("E-1");

        check("REQ-EF", "CK-EF", "INS-E", "2026-04-01T00:00:00Z", "0", "5", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.isolationRangeFrom").value("2026-02-10T08:00:00Z"))
                .andExpect(jsonPath("$.isolationRangeTo").value("2026-04-01T00:00:00Z"));
        assertTrue(suspect("E-1"));
    }

    @Test
    void pendingInsideIntervalIsBlockedWithCheckKeyAndBatchFailsWhole() throws Exception {
        createCert("INS-B");
        submit("B-IN", "INS-B", "2026-04-15T00:00:00Z");   // 区间内待放行
        submit("B-OUT", "INS-B", "2026-05-02T00:00:00Z");  // 区间外待放行
        check("REQ-BF", "CK-BF", "INS-B", "2026-05-01T00:00:00Z", "0", "9", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockedPendingKeys[0]").value("B-IN"));

        // 整批失败：区间内项指明 checkKey，区间外项也不得放行
        String array = "[\"B-IN\",\"B-OUT\"]";
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":" + array + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='B-IN')].reasons[0]")
                        .value("ISOLATED_BY_CHECK:CK-BF"));
        assertEquals("PENDING", statusOf("B-IN"));
        assertEquals("PENDING", statusOf("B-OUT"), "整批原子失败，区间外项也不得放行");

        // 区间外单项可正常放行，区间内仍 409
        release("B-OUT");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"B-IN\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]")
                        .value("ISOLATED_BY_CHECK:CK-BF"));
    }

    @Test
    void laterPassClearsSuspectAndRestoresUsable() throws Exception {
        createCert("INS-R");
        submit("R-1", "INS-R", "2026-03-01T00:00:00Z");
        release("R-1");
        check("REQ-RF", "CK-RF", "INS-R", "2026-05-01T00:00:00Z", "0", "9", "1")
                .andExpect(jsonPath("$.result").value("FAIL"));
        assertTrue(suspect("R-1"));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-R"))
                .andExpect(jsonPath("$.length()").value(0));

        check("REQ-RP", "CK-RP", "INS-R", "2026-06-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("PASS"))
                .andExpect(jsonPath("$.resolvedIntervals[0]").value("CK-RF"))
                .andExpect(jsonPath("$.restoredKeys[0]").value("R-1"));

        assertFalse(suspect("R-1"));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-R"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementKey").value("R-1"));

        // 解除历史保留：区间 resolved=TRUE 且记录解除它的 PASS
        Map<String, Object> interval = jdbc.queryForMap(
                "SELECT * FROM isolation_interval WHERE check_key = 'CK-RF'");
        assertEquals(Boolean.TRUE, interval.get("resolved"));
        assertEquals("CK-RP", interval.get("resolved_by_check_key"));
    }

    @Test
    void passDoesNotRestoreRevokedCertificateOrResultsStillCoveredByOtherFail() throws Exception {
        // 证书已撤销：解除隔离不恢复可用
        createCert("INS-V");
        submit("V-1", "INS-V", "2026-03-01T00:00:00Z");
        release("V-1");
        long certId = ((Number) jdbc.queryForMap(
                "SELECT id FROM calibration_certificate WHERE instrument_id = 'INS-V'").get("id")).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        check("REQ-VF", "CK-VF", "INS-V", "2026-05-01T00:00:00Z", "0", "9", "1")
                .andExpect(jsonPath("$.result").value("FAIL"));
        check("REQ-VP", "CK-VP", "INS-V", "2026-06-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restoredKeys.length()").value(0));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-V"))
                .andExpect(jsonPath("$.length()").value(0));

        // 重叠 FAIL：先解除 FAIL1，但结果仍被 FAIL2 覆盖，不恢复
        createCert("INS-O");
        submit("O-1", "INS-O", "2026-02-01T00:00:00Z");
        release("O-1");
        check("REQ-OF1", "CK-OF1", "INS-O", "2026-05-01T00:00:00Z", "0", "9", "1")
                .andExpect(jsonPath("$.result").value("FAIL"));
        check("REQ-OF2", "CK-OF2", "INS-O", "2026-06-01T00:00:00Z", "0", "9", "1")
                .andExpect(jsonPath("$.result").value("FAIL"));
        assertTrue(suspect("O-1"));

        // PASS 介于 FAIL1(05-01) 与 FAIL2(06-01) 之间：只解除 FAIL1
        check("REQ-OP1", "CK-OP1", "INS-O", "2026-05-15T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolvedIntervals.length()").value(1))
                .andExpect(jsonPath("$.restoredKeys.length()").value(0));
        assertTrue(suspect("O-1"), "仍被未解除 FAIL2 覆盖");

        // 更晚 PASS 解除 FAIL2 后恢复
        check("REQ-OP2", "CK-OP2", "INS-O", "2026-07-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolvedIntervals[0]").value("CK-OF2"))
                .andExpect(jsonPath("$.restoredKeys[0]").value("O-1"));
        assertFalse(suspect("O-1"));
    }

    @Test
    void historyAndIntervalQueriesWork() throws Exception {
        createCert("INS-H");
        check("REQ-H1", "CK-H1", "INS-H", "2026-03-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated());
        check("REQ-H2", "CK-H2", "INS-H", "2026-05-01T00:00:00Z", "0", "9", "1")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/checks").param("instrumentId", "INS-H"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].checkKey").value("CK-H1"))
                .andExpect(jsonPath("$[1].result").value("FAIL"));

        mvc.perform(get("/api/checks/CK-H2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkKey").value("CK-H2"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.isolationRangeFrom").exists());

        mvc.perform(get("/api/checks/isolation-intervals").param("instrumentId", "INS-H"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].checkKey").value("CK-H2"))
                .andExpect(jsonPath("$[0].resolved").value(false));

        mvc.perform(get("/api/checks/NO-SUCH")).andExpect(status().isNotFound());
    }

    @Test
    void duplicateCheckKeyAndSameInstrumentTimeRejected() throws Exception {
        createCert("INS-D");
        check("REQ-D1", "CK-D", "INS-D", "2026-03-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isCreated());
        // 不同 requestId 复用 checkKey
        check("REQ-D2", "CK-D", "INS-D", "2026-04-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHECK"));
        // 同一仪器同一核查时刻，不同 checkKey
        check("REQ-D3", "CK-D-OTHER", "INS-D", "2026-03-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHECK"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_record", Integer.class),
                "失败（409）不占 requestId；仅 REQ-D1 成功占键");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
    }

    @Test
    void invalidInputsRejected() throws Exception {
        createCert("INS-X");
        // 负容差 400
        check("REQ-X1", "CK-X1", "INS-X", "2026-03-01T00:00:00Z", "1", "2", "-1")
                .andExpect(status().isBadRequest());
        // 超过 6 位小数 400
        check("REQ-X2", "CK-X2", "INS-X", "2026-03-01T00:00:00Z", "1", "1.0000001", "1")
                .andExpect(status().isBadRequest());
        // 缺少 requestId 400
        check("", "CK-X3", "INS-X", "2026-03-01T00:00:00Z", "1", "1", "1")
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM interim_check", Integer.class));
    }
}
