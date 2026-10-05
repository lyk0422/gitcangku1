package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等去重测试：同键同参重放首次完整响应、异参 409、失败不占键、
 * 期望版本参与指纹、创建/提交/放行三类写入均生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest {

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

    private void createLinearCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private String submitJson(String key, String instrument, String reading, String requestId) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                 "requestId":"%s"}
                """.formatted(key, instrument, reading, requestId);
    }

    @Test
    void submitSameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        createLinearCert("INS-I1");
        String json = submitJson("MI-1", "INS-I1", "2", "REQ-S-1");
        String first = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同键同参重放：返回首次完整响应，不产生新记录
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.measurementKey").value("MI-1"))
                .andExpect(jsonPath("$.computedValue").value("2"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void submitSameRequestIdDifferentParamsReturns409() throws Exception {
        createLinearCert("INS-I2");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-2", "INS-I2", "2", "REQ-S-2")))
                .andExpect(status().isCreated());
        // 同键异参 → 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-2", "INS-I2", "5", "REQ-S-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 异参请求未生效
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"MI-2X","instrumentId":"INS-I2",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"2",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "requestId":"REQ-S-2","expectedVersion":"2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        // 无有效证书 → 422，失败不占键
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-3", "INS-I3", "2", "REQ-S-3")))
                .andExpect(status().isUnprocessableEntity());
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(0, idemCount);

        // 补齐证书后同键同参可成功
        createLinearCert("INS-I3");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-3", "INS-I3", "2", "REQ-S-3")))
                .andExpect(status().isCreated());

        // 参数非法 400 同样不占键
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"MI-4","instrumentId":"INS-I3",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1.0000001",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "requestId":"REQ-S-4"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-4", "INS-I3", "1", "REQ-S-4")))
                .andExpect(status().isCreated());
    }

    @Test
    void certificateCreateReplayAndConflict() throws Exception {
        String json = """
                {"instrumentId":"INS-I5","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","type":"CURVE",
                 "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                          {"raw":"6","corrected":"4"}],
                 "requestId":"REQ-C-1"}
                """;
        String first = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同键同参重放：同一证书 ID，节点不重复插入
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.nodes.length()").value(3));
        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-I5'", Integer.class);
        assertEquals(1, certCount);
        Integer nodeCount = jdbc.queryForObject("SELECT COUNT(*) FROM certificate_node", Integer.class);
        assertEquals(3, nodeCount);

        // 同键异参 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I5","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-06-01T00:00:00Z","a":"1","b":"0",
                                 "requestId":"REQ-C-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void releaseReplayReturnsSameBatchAndSingleHistory() throws Exception {
        createLinearCert("INS-I6");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("MI-6", "INS-I6", "2", "REQ-S-6")))
                .andExpect(status().isCreated());

        String releaseJson = "{\"keys\":[\"MI-6\"],\"requestId\":\"REQ-R-1\"}";
        String first = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");

        // 同键同参重放：同一批次，放行历史不重复
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(1, releaseCount);

        // 同键异参（不同测量键）→ 409
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"MI-OTHER\"],\"requestId\":\"REQ-R-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void revokeReplayReturnsFirstResponse() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-I7","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        // 携带 requestId 撤销：重放返回首次响应而非 409
        mvc.perform(post("/api/certificates/{id}/revoke", certId).param("requestId", "REQ-V-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(post("/api/certificates/{id}/revoke", certId).param("requestId", "REQ-V-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        // 不带 requestId 的重复撤销仍为 409
        mvc.perform(post("/api/certificates/{id}/revoke", certId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));
    }
}
