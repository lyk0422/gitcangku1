package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.example.starter.calibration.SessionTestSupport.append;
import static com.example.starter.calibration.SessionTestSupport.close;
import static com.example.starter.calibration.SessionTestSupport.createHysteresisCert;
import static com.example.starter.calibration.SessionTestSupport.createLinearCert;
import static com.example.starter.calibration.SessionTestSupport.createSession;
import static com.example.starter.calibration.SessionTestSupport.releaseSessions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迟滞校准会话主流程与追加/关闭规则测试：合成斜率 1、UP 偏移 1、DOWN 偏移 -1，
 * 原值 10、12、12、11 → 方向 UP、UP、UP、DOWN，校正 11、13、13、10。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_session");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void hysteresisSessionFullFlowWithSyntheticData() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0));

        append(mvc, "REQ-A1", "S-1", 0, "10", "UP", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.direction").value("UP"))
                .andExpect(jsonPath("$.correctedValue").value("11"))
                .andExpect(jsonPath("$.displayValue").value("11.0000"))
                .andExpect(jsonPath("$.passed").value(true));
        append(mvc, "REQ-A2", "S-1", 1, "12", null, "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("UP"))
                .andExpect(jsonPath("$.correctedValue").value("13"));
        // 原值相同 → 继承前方向 UP
        append(mvc, "REQ-A3", "S-1", 2, "12", null, "bob")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("UP"))
                .andExpect(jsonPath("$.correctedValue").value("13"));
        append(mvc, "REQ-A4", "S-1", 3, "11", null, "bob")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DOWN"))
                .andExpect(jsonPath("$.correctedValue").value("10"));

        close(mvc, "REQ-CL1", "S-1", 4, "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[3].direction").value("DOWN"))
                .andExpect(jsonPath("$.closedAt").isString());

        releaseSessions(mvc, "REQ-R1", "carol", "S-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedSessions[0]").value("S-1"))
                .andExpect(jsonPath("$.releasedMeasurements.length()").value(4));

        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-H"))
                .andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/sessions/{key}", "S-1"))
                .andExpect(jsonPath("$.entries[0].status").value("RELEASED"));
    }

    @Test
    void firstEntryRequiresExplicitDirectionAndLaterEntriesForbidIt() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());

        // 首条缺 direction → 400
        append(mvc, "REQ-A1", "S-1", 0, "10", null, "alice").andExpect(status().isBadRequest());
        // 非法 direction → 400
        append(mvc, "REQ-A2", "S-1", 0, "10", "SIDEWAYS", "alice").andExpect(status().isBadRequest());
        // 首条显式 DOWN 成功
        append(mvc, "REQ-A3", "S-1", 0, "10", "DOWN", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DOWN"))
                .andExpect(jsonPath("$.correctedValue").value("9"));
        // 后续条目携带 direction → 400
        append(mvc, "REQ-A4", "S-1", 1, "11", "UP", "alice").andExpect(status().isBadRequest());
    }

    @Test
    void overPrecision400AndVersionConflict409() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());

        // 超过 6 位小数 → 400
        append(mvc, "REQ-A1", "S-1", 0, "1.0000001", "UP", "alice").andExpect(status().isBadRequest());
        // 期望版本不符 → 409
        append(mvc, "REQ-A2", "S-1", 5, "10", "UP", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 失败不占版本：正确版本仍可追加
        append(mvc, "REQ-A3", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());
    }

    @Test
    void closedSessionRejectsAppendAndDoubleCloseAndEmptyClose() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        // 空会话关闭 → 422
        close(mvc, "REQ-CL0", "S-1", 0, "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_EMPTY"));

        append(mvc, "REQ-A1", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());
        close(mvc, "REQ-CL1", "S-1", 1, "alice").andExpect(status().isOk());
        // 关闭后追加 → 409
        append(mvc, "REQ-A2", "S-1", 1, "11", null, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CLOSED"));
        // 重复关闭 → 409
        close(mvc, "REQ-CL2", "S-1", 1, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CLOSED"));
        // 关闭冻结方向与精确结果
        mvc.perform(get("/api/sessions/{key}", "S-1"))
                .andExpect(jsonPath("$.entries[0].direction").value("UP"))
                .andExpect(jsonPath("$.entries[0].correctedValue").value("11"));
    }

    @Test
    void sessionEntryLimitIs20() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "1000", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A0", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());
        for (int i = 1; i < 20; i++) {
            append(mvc, "REQ-A" + i, "S-1", i, "1" + (i % 10), null, "alice")
                    .andExpect(status().isCreated());
        }
        // 第 21 条 → 422
        append(mvc, "REQ-A20", "S-1", 20, "15", null, "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_FULL"));
    }

    @Test
    void sessionRequiresHysteresisCertificateAndRejectsLinearOrRevoked() throws Exception {
        long linearId = createLinearCert(mvc, "INS-L", "1", "0");
        // 旧线性证书不走新模式 → 422
        createSession(mvc, "REQ-C1", "S-L", "INS-L", linearId, "0", "100", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_NOT_HYSTERESIS"));
        // 证书不存在 → 404
        createSession(mvc, "REQ-C2", "S-M", "INS-L", 999999L, "0", "100", "alice")
                .andExpect(status().isNotFound());
        // 已撤销的迟滞证书 → 422
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        createSession(mvc, "REQ-C3", "S-R", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
        // 会话键重复（不同 requestId）→ 409
        long certId2 = createHysteresisCert(mvc, "INS-H2", "1", "1", "-1");
        createSession(mvc, "REQ-C4", "S-DUP", "INS-H2", certId2, "0", "100", "alice")
                .andExpect(status().isCreated());
        createSession(mvc, "REQ-C5", "S-DUP", "INS-H2", certId2, "0", "100", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SESSION_KEY"));
    }

    @Test
    void oldLinearSubmitDoesNotMatchHysteresisCertificate() throws Exception {
        createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        // 只有迟滞证书时，旧线性提交无匹配证书 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-H","instrumentId":"INS-H",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
    }

    @Test
    void judgmentUsesUnroundedValueWhileDisplayRoundsHalfUp() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "0.00004", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "1.00003", "alice")
                .andExpect(status().isCreated());
        // 校正值 1.00004 > 上限 1.00003 → 不合格；显示值 1.0000 看似落在区间内
        append(mvc, "REQ-A1", "S-1", 0, "1", "UP", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedValue").value("1.00004"))
                .andExpect(jsonPath("$.displayValue").value("1.0000"))
                .andExpect(jsonPath("$.passed").value(false));
    }
}
