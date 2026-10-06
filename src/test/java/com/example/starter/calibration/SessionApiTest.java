package com.example.starter.calibration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迟滞校准会话主流程测试：方向链推导（增大 UP、减小 DOWN、相同继承）、校正值=原值×斜率+方向偏移、
 * 版本控制、关闭冻结、超精度 400、版本冲突 409、线性证书不走会话、历史方向不受新会话影响。
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
        jdbc.update("DELETE FROM session_entry");
        jdbc.update("DELETE FROM calibration_session");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createHysteresisCert(String instrument, String slope, String up, String down) throws Exception {
        String body = mvc.perform(post("/api/certificates/hysteresis").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","slope":"%s","upOffset":"%s","downOffset":"%s"}
                                """.formatted(UUID.randomUUID(), instrument, slope, up, down)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certType").value("HYSTERESIS"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createSession(String instrument, long certId) throws Exception {
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","certificateId":%d}
                                """.formatted(UUID.randomUUID(), instrument, certId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String appendJson(String requestId, long version, String raw, String direction,
                              String lower, String upper, String by) {
        String dir = direction == null ? "" : "\"direction\":\"" + direction + "\",";
        return """
                {"requestId":"%s","expectedSessionVersion":%d,"rawValue":"%s",%s
                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(requestId, version, raw, dir, lower, upper, by);
    }

    private void appendOk(long sessionId, long version, String raw, String direction) throws Exception {
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), version, raw, direction, "0", "100", "alice")))
                .andExpect(status().isOk());
    }

    @Test
    void directionChainAndCorrectedValuesFollowSpec() throws Exception {
        // 合成：斜率 1、UP 偏移 1、DOWN 偏移 -1；原值 10、12、12、11 → 方向 UP、UP、UP、DOWN，校正 11、13、13、10
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        long sessionId = createSession("INS-H", certId);

        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "10", "UP", "0", "100", "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries[0].seq").value(1))
                .andExpect(jsonPath("$.entries[0].direction").value("UP"))
                .andExpect(jsonPath("$.entries[0].correctedValue").value("11"))
                .andExpect(jsonPath("$.entries[0].displayValue").value("11.0000"))
                .andExpect(jsonPath("$.entries[0].passed").value(true));

        appendOk(sessionId, 1, "12", null);   // 增大 → UP，校正 13
        appendOk(sessionId, 2, "12", null);   // 相同 → 继承 UP，校正 13
        appendOk(sessionId, 3, "11", null);   // 减小 → DOWN，校正 10

        mvc.perform(get("/api/sessions/{id}", sessionId))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.entries[1].direction").value("UP"))
                .andExpect(jsonPath("$.entries[1].correctedValue").value("13"))
                .andExpect(jsonPath("$.entries[2].direction").value("UP"))
                .andExpect(jsonPath("$.entries[2].correctedValue").value("13"))
                .andExpect(jsonPath("$.entries[3].direction").value("DOWN"))
                .andExpect(jsonPath("$.entries[3].correctedValue").value("10"))
                .andExpect(jsonPath("$.entries[3].displayValue").value("10.0000"));

        // 关闭后冻结：不再允许追加
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedSessionVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isString());
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 4, "9", null, "0", "100", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CLOSED"));
    }

    @Test
    void appendValidationAndVersionConflict() throws Exception {
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        long sessionId = createSession("INS-H", certId);

        // 首条缺少显式方向 → 400
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "10", null, "0", "100", "alice")))
                .andExpect(status().isBadRequest());
        // 首条方向非法 → 400
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "10", "UPP", "0", "100", "alice")))
                .andExpect(status().isBadRequest());
        // 超精度（7 位小数）→ 400
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "1.0000001", "UP", "0", "100", "alice")))
                .andExpect(status().isBadRequest());
        // 版本冲突 → 409
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 5, "10", "UP", "0", "100", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 下限大于上限 → 400
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "10", "UP", "100", "0", "alice")))
                .andExpect(status().isBadRequest());
        // 失败不占版本：会话仍为空
        mvc.perform(get("/api/sessions/{id}", sessionId)).andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void sessionCapsAtTwentyEntries() throws Exception {
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        long sessionId = createSession("INS-H", certId);
        for (int i = 0; i < 20; i++) {
            appendOk(sessionId, i, String.valueOf(10 + i), i == 0 ? "UP" : null);
        }
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 20, "99", null, "0", "100", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_FULL"));
    }

    @Test
    void createSessionRejectsLinearCertUnknownCertAndMismatch() throws Exception {
        // 线性证书不走新模式 → 422
        String linearBody = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-L","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long linearId = ((Number) JsonPath.read(linearBody, "$.id")).longValue();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"INS-L","certificateId":%d}
                                """.formatted(UUID.randomUUID(), linearId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_TYPE_NOT_SUPPORTED"));

        // 证书不存在 → 404
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"INS-L","certificateId":999999}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // 仪器不一致 → 400
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"INS-OTHER","certificateId":%d}
                                """.formatted(UUID.randomUUID(), certId)))
                .andExpect(status().isBadRequest());

        // 已撤销证书 → 409
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"INS-H","certificateId":%d}
                                """.formatted(UUID.randomUUID(), certId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void closeRequiresMatchingVersion() throws Exception {
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        long sessionId = createSession("INS-H", certId);
        appendOk(sessionId, 0, "10", "UP");
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedSessionVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 重复关闭 → 409
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedSessionVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedSessionVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CLOSED"));
    }

    @Test
    void historicalDirectionsUnaffectedByNewSession() throws Exception {
        long certId = createHysteresisCert("INS-H", "1", "1", "-1");
        long sessionA = createSession("INS-H", certId);
        appendOk(sessionA, 0, "10", "UP");
        appendOk(sessionA, 1, "12", null);   // UP

        // 新会话首条显式 DOWN，后续相同读数继承的是本会话方向，不受会话 A 影响
        long sessionB = createSession("INS-H", certId);
        appendOk(sessionB, 0, "5", "DOWN");
        appendOk(sessionB, 1, "5", null);    // 相同 → 继承本会话 DOWN，校正 4
        appendOk(sessionB, 2, "6", null);    // 增大 → UP，校正 7

        mvc.perform(get("/api/sessions/{id}", sessionB))
                .andExpect(jsonPath("$.entries[1].direction").value("DOWN"))
                .andExpect(jsonPath("$.entries[1].correctedValue").value("4"))
                .andExpect(jsonPath("$.entries[2].direction").value("UP"))
                .andExpect(jsonPath("$.entries[2].correctedValue").value("7"));
        // 会话 A 的历史方向与结果不变
        mvc.perform(get("/api/sessions/{id}", sessionA))
                .andExpect(jsonPath("$.entries[0].direction").value("UP"))
                .andExpect(jsonPath("$.entries[0].correctedValue").value("11"))
                .andExpect(jsonPath("$.entries[1].direction").value("UP"))
                .andExpect(jsonPath("$.entries[1].correctedValue").value("13"));
    }
}
