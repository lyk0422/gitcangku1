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
import static com.example.starter.calibration.SessionTestSupport.createSession;
import static com.example.starter.calibration.SessionTestSupport.releaseSessions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话批量放行测试：只能引用已关闭完整会话，任一成员不合格或证书撤销整批 422；
 * 放行人不得是任何成员提交人；旧单测量放行遇会话成员必须拒绝；历史方向不受新会话影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionReleaseApiTest {

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

    /** 创建并关闭一个含两条合格成员的会话（提交人 alice）。 */
    private long closedSession(String key, String instrument, String suffix) throws Exception {
        long certId = createHysteresisCert(mvc, instrument, "1", "1", "-1");
        createSession(mvc, "REQ-C" + suffix, key, instrument, certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1-" + suffix, key, 0, "10", "UP", "alice").andExpect(status().isCreated());
        append(mvc, "REQ-A2-" + suffix, key, 1, "12", null, "alice").andExpect(status().isCreated());
        close(mvc, "REQ-CL" + suffix, key, 2, "alice").andExpect(status().isOk());
        return certId;
    }

    @Test
    void releaseRejectsOpenSessionAndUnknownSession() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-OPEN", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1", "S-OPEN", 0, "10", "UP", "alice").andExpect(status().isCreated());

        releaseSessions(mvc, "REQ-R1", "carol", "S-OPEN")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_NOT_CLOSED"));
        releaseSessions(mvc, "REQ-R2", "carol", "S-MISSING")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_NOT_FOUND"));
    }

    @Test
    void releaseRejectsBatchWhenAnyMemberNotPassed() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-FAIL", "INS-H", certId, "0", "11", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1", "S-FAIL", 0, "10", "UP", "alice").andExpect(status().isCreated());
        // 校正值 13 超出上限 11 → 不合格
        append(mvc, "REQ-A2", "S-FAIL", 1, "12", null, "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false));
        close(mvc, "REQ-CL1", "S-FAIL", 2, "alice").andExpect(status().isOk());
        closedSession("S-OK", "INS-H2", "ok");

        // 同一批中 S-FAIL 不合格 → 整批 422，S-OK 不被部分放行
        releaseSessions(mvc, "REQ-R1", "carol", "S-FAIL", "S-OK")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='S-FAIL')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures.length()").value(1));
        mvc.perform(get("/api/sessions/{key}", "S-OK"))
                .andExpect(jsonPath("$.entries[0].status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, releaseCount);
    }

    @Test
    void releaseRejectsBatchWhenCertificateRevoked() throws Exception {
        long certId = closedSession("S-1", "INS-H", "1");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        releaseSessions(mvc, "REQ-R1", "carol", "S-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));
        mvc.perform(get("/api/sessions/{key}", "S-1"))
                .andExpect(jsonPath("$.entries[0].status").value("PENDING"));
    }

    @Test
    void releaserMustDifferFromEveryMemberSubmitter() throws Exception {
        closedSession("S-1", "INS-H", "1");
        // 放行人等于成员提交人 alice → 422
        releaseSessions(mvc, "REQ-R1", "alice", "S-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SAME_ACTOR"));
        // 其他放行人可放行
        releaseSessions(mvc, "REQ-R2", "carol", "S-1").andExpect(status().isOk());
        // 重复放行 → 422 NOT_PENDING
        releaseSessions(mvc, "REQ-R3", "carol", "S-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PENDING"));
    }

    @Test
    void legacyMeasurementReleaseRejectsSessionMembers() throws Exception {
        closedSession("S-1", "INS-H", "1");
        // 旧单测量放行端点遇会话成员必须拒绝，防止跳过方向链
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"S-1#1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_MEMBER"));
        mvc.perform(get("/api/sessions/{key}", "S-1"))
                .andExpect(jsonPath("$.entries[0].status").value("PENDING"));
    }

    @Test
    void historicalDirectionsNotAffectedByNewSessionFirstValue() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-OLD", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1", "S-OLD", 0, "10", "UP", "alice").andExpect(status().isCreated());
        append(mvc, "REQ-A2", "S-OLD", 1, "9", null, "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DOWN"));
        close(mvc, "REQ-CL1", "S-OLD", 2, "alice").andExpect(status().isOk());

        // 新会话首条显式 DOWN，且原值序列不同
        createSession(mvc, "REQ-C2", "S-NEW", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A3", "S-NEW", 0, "10", "DOWN", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DOWN"))
                .andExpect(jsonPath("$.correctedValue").value("9"));
        append(mvc, "REQ-A4", "S-NEW", 1, "9", null, "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DOWN"));

        // 历史会话方向与精确结果不变
        mvc.perform(get("/api/sessions/{key}", "S-OLD"))
                .andExpect(jsonPath("$.entries[0].direction").value("UP"))
                .andExpect(jsonPath("$.entries[0].correctedValue").value("11"))
                .andExpect(jsonPath("$.entries[1].direction").value("DOWN"))
                .andExpect(jsonPath("$.entries[1].correctedValue").value("8"));
    }
}
