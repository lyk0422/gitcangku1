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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等测试：同键同参重放首次完整响应；同键异参 409；失败不占键；
 * 业务与去重原子提交（失败回滚后 requestId 可复用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionIdempotencyTest {

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

    private long createHysteresisCert(String requestId, String instrument, String slope) throws Exception {
        String body = mvc.perform(post("/api/certificates/hysteresis").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","slope":"%s","upOffset":"1","downOffset":"-1"}
                                """.formatted(requestId, instrument, slope)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String sessionJson(String requestId, String instrument, long certId) {
        return """
                {"requestId":"%s","instrumentId":"%s","certificateId":%d}
                """.formatted(requestId, instrument, certId);
    }

    private String appendJson(String requestId, long version, String raw, String direction) {
        String dir = direction == null ? "" : "\"direction\":\"" + direction + "\",";
        return """
                {"requestId":"%s","expectedSessionVersion":%d,"rawValue":"%s",%s
                 "lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}
                """.formatted(requestId, version, raw, dir);
    }

    @Test
    void sameKeySameParamsReplaysFirstResponse() throws Exception {
        long certId = createHysteresisCert(UUID.randomUUID().toString(), "INS-I", "1");
        String requestId = UUID.randomUUID().toString();
        String json = sessionJson(requestId, "INS-I", certId);

        String first = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        Object firstId = JsonPath.read(first, "$.id");
        Object replayId = JsonPath.read(replay, "$.id");
        assertEquals(firstId, replayId, "重放必须返回首次的会话 ID");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM calibration_session", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        long certId = createHysteresisCert(UUID.randomUUID().toString(), "INS-I", "1");
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(requestId, "INS-I", certId)))
                .andExpect(status().isCreated());
        // 同键异参（仪器不同）→ 409；结构化身份比较，不受分隔符碰撞影响
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(requestId, "INS-I-X", certId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM calibration_session", Integer.class));
    }

    @Test
    void failureDoesNotOccupyKey() throws Exception {
        long certId = createHysteresisCert(UUID.randomUUID().toString(), "INS-I", "1");
        String sessionBody = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(UUID.randomUUID().toString(), "INS-I", certId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long sessionId = ((Number) JsonPath.read(sessionBody, "$.id")).longValue();

        // 版本冲突失败 → 不占键
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(requestId, 7, "10", "UP")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Integer.class, requestId));

        // 同一 requestId 以正确版本重试 → 成功
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(requestId, 0, "10", "UP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void appendReplayIncrementsVersionOnlyOnce() throws Exception {
        long certId = createHysteresisCert(UUID.randomUUID().toString(), "INS-I", "1");
        String sessionBody = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(UUID.randomUUID().toString(), "INS-I", certId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long sessionId = ((Number) JsonPath.read(sessionBody, "$.id")).longValue();

        String requestId = UUID.randomUUID().toString();
        String json = appendJson(requestId, 0, "10", "UP");
        String first = mvc.perform(post("/api/sessions/{id}/entries", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/sessions/{id}/entries", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Object firstVersion = JsonPath.read(first, "$.version");
        Object replayVersion = JsonPath.read(replay, "$.version");
        assertEquals(firstVersion, replayVersion);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM session_entry", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM calibration_session WHERE id = ?", Integer.class, sessionId));
    }

    @Test
    void releaseReplayReturnsSameBatch() throws Exception {
        long certId = createHysteresisCert(UUID.randomUUID().toString(), "INS-I", "1");
        String sessionBody = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(UUID.randomUUID().toString(), "INS-I", certId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long sessionId = ((Number) JsonPath.read(sessionBody, "$.id")).longValue();
        mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content(appendJson(UUID.randomUUID().toString(), 0, "10", "UP")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedSessionVersion\":1}"))
                .andExpect(status().isOk());

        String requestId = UUID.randomUUID().toString();
        String releaseJson = "{\"requestId\":\"" + requestId + "\",\"sessionIds\":[" + sessionId + "]}";
        String first = mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Object firstBatch = JsonPath.read(first, "$.batchId");
        Object replayBatch = JsonPath.read(replay, "$.batchId");
        assertEquals(firstBatch, replayBatch, "重放必须返回首次批次");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 同键但放行人不同（身份含操作者）→ 409
        mvc.perform(post("/api/sessions/release").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void hysteresisCertificateCreateIsIdempotent() throws Exception {
        String requestId = UUID.randomUUID().toString();
        long certId = createHysteresisCert(requestId, "INS-I", "1");
        // 同键同参重放 → 同一证书
        long replayed = createHysteresisCert(requestId, "INS-I", "1");
        assertEquals(certId, replayed);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM calibration_certificate", Integer.class));
        // 同键异参 → 409
        mvc.perform(post("/api/certificates/hysteresis").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"INS-I","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","slope":"2","upOffset":"1","downOffset":"-1"}
                                """.formatted(requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
