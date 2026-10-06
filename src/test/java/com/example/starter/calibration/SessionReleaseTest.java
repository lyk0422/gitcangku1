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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话批量放行测试：只能引用已关闭完整会话；成员不合格、证书撤销、放行人冲突整批 422 且原子回滚；
 * 原单测量放行遇会话成员必须拒绝（SESSION_MEMBER），防止跳过方向链。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionReleaseTest {

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

    private long createCertAndSession(String instrument) throws Exception {
        String certBody = mvc.perform(post("/api/certificates/hysteresis").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","slope":"1","upOffset":"1","downOffset":"-1"}
                                """.formatted(UUID.randomUUID(), instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long certId = ((Number) JsonPath.read(certBody, "$.id")).longValue();
        String sessionBody = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","certificateId":%d}
                                """.formatted(UUID.randomUUID(), instrument, certId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(sessionBody, "$.id")).longValue();
    }

    private void append(long sessionId, long version, String raw, String direction,
                        String lower, String upper, String by) throws Exception {
        String dir = direction == null ? "" : "\"direction\":\"" + direction + "\",";
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedSessionVersion":%d,"rawValue":"%s",%s
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(UUID.randomUUID(), version, raw, dir, lower, upper, by)))
                .andExpect(status().isOk());
    }

    private void close(long sessionId, long version) throws Exception {
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"expectedSessionVersion\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private void release(long expectStatus, String actor, long... sessionIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : sessionIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[" + ids + "]}"))
                .andExpect(status().is((int) expectStatus));
    }

    @Test
    void validSessionsReleasedAtomically() throws Exception {
        long s1 = createCertAndSession("INS-S1");
        append(s1, 0, "10", "UP", "0", "100", "alice");
        append(s1, 1, "12", null, "0", "100", "bob");
        close(s1, 2);
        long s2 = createCertAndSession("INS-S2");
        append(s2, 0, "20", "DOWN", "0", "100", "alice");
        close(s2, 1);

        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"sessionIds\":[" + s1 + "," + s2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.releasedSessions[0]").value(s1))
                .andExpect(jsonPath("$.releasedSessions[1]").value(s2))
                .andExpect(jsonPath("$.releasedMeasurements[0]").value("SES-" + s1 + "-1"))
                .andExpect(jsonPath("$.releasedMeasurements[2]").value("SES-" + s2 + "-1"));

        mvc.perform(get("/api/sessions/{id}", s1)).andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/measurements/{key}", "SES-" + s1 + "-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.direction").value("UP"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-S1"))
                .andExpect(jsonPath("$.length()").value(2));

        // 重复放行（新 requestId）→ 422 SESSION_NOT_CLOSED
        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[" + s1 + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_NOT_CLOSED"));
    }

    @Test
    void openOrEmptySessionRejected() throws Exception {
        long open = createCertAndSession("INS-O");
        append(open, 0, "10", "UP", "0", "100", "alice");
        release(422, "carol", open);

        long empty = createCertAndSession("INS-E");
        close(empty, 0);
        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[" + empty + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_EMPTY"));
    }

    @Test
    void failedMemberOrRevokedCertRejectsWholeBatchAtomically() throws Exception {
        long good = createCertAndSession("INS-G");
        append(good, 0, "10", "UP", "0", "100", "alice");
        close(good, 1);

        long bad = createCertAndSession("INS-B");
        append(bad, 0, "200", "UP", "0", "100", "alice");   // 校正 201 超上限 → 不合格
        close(bad, 1);

        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"sessionIds\":[" + good + "," + bad + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value(String.valueOf(bad)))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MEASUREMENT_NOT_PASSED"));

        // 整批回滚：合格会话保持 CLOSED，成员仍待放行，无放行历史
        mvc.perform(get("/api/sessions/{id}", good)).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/measurements/{key}", "SES-" + good + "-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 证书撤销 → 整批 422 CERTIFICATE_REVOKED
        String certBody = mvc.perform(get("/api/sessions/{id}", good)).andReturn().getResponse()
                .getContentAsString();
        long certId = ((Number) JsonPath.read(certBody, "$.certificateId")).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[" + good + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void releaserMustNotBeAnyMemberSubmitter() throws Exception {
        long sessionId = createCertAndSession("INS-A");
        append(sessionId, 0, "10", "UP", "0", "100", "alice");
        append(sessionId, 1, "12", null, "0", "100", "bob");
        close(sessionId, 2);

        mvc.perform(post("/api/sessions/release")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[" + sessionId + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SAME_ACTOR"));
        release(200, "carol", sessionId);
    }

    @Test
    void singleMeasurementReleaseRejectsSessionMember() throws Exception {
        long sessionId = createCertAndSession("INS-M");
        append(sessionId, 0, "10", "UP", "0", "100", "alice");
        close(sessionId, 1);

        // 原单测量放行接口遇会话成员必须拒绝，防止跳过方向链
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"SES-" + sessionId + "-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_MEMBER"));
        mvc.perform(get("/api/measurements/{key}", "SES-" + sessionId + "-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void releaseBatchValidation() throws Exception {
        long sessionId = createCertAndSession("INS-V");
        append(sessionId, 0, "10", "UP", "0", "100", "alice");
        close(sessionId, 1);

        // 空批次 → 400
        mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[]}"))
                .andExpect(status().isBadRequest());
        // 重复会话 → 400
        mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":["
                                + sessionId + "," + sessionId + "]}"))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/sessions/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":["
                                + sessionId + "]}"))
                .andExpect(status().isBadRequest());
        // 不存在的会话 → 422 SESSION_NOT_FOUND
        mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"sessionIds\":[999999]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SESSION_NOT_FOUND"));
    }
}
