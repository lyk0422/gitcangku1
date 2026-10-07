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
 * 幂等去重测试：重放返回首次完整响应、改参 409、失败不占键、
 * 十进制同值同参、文本原样、操作者非空、创建/撤销/放行各写入口。
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createLinearCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private String submitJson(String key, String reading, String by, String requestId) {
        return """
                {"measurementKey":"%s","instrumentId":"INS-1","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"0","upperLimit":"9","submittedBy":"%s","requestId":"%s"}
                """.formatted(key, reading, by, requestId);
    }

    @Test
    void replayReturnsFirstResponseAndSingleRow() throws Exception {
        createLinearCert("INS-1");
        String json = submitJson("M-I1", "1", "alice", "REQ-1");
        String first = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "重放必须返回首次完整响应");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count, "重放不得产生重复业务记录");
    }

    @Test
    void sameRequestIdWithChangedParamsReturns409() throws Exception {
        createLinearCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I2", "1", "alice", "REQ-2")))
                .andExpect(status().isCreated());
        // 改十进制参数 → 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I2", "2", "alice", "REQ-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 文本原样：仅操作者大小写不同也算改参 → 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I2", "1", "Alice", "REQ-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void decimalSameValueTreatedAsSameParams() throws Exception {
        createLinearCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I3", "1.0", "alice", "REQ-3")))
                .andExpect(status().isCreated());
        // "1.00" 与 "1.0" 同值同参 → 重放首次响应而非 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I3", "1.00", "alice", "REQ-3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("M-I3"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void failedRequestDoesNotHoldKey() throws Exception {
        // 无证书 → 422 失败，不占键
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I4", "1", "alice", "REQ-4")))
                .andExpect(status().isUnprocessableEntity());
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(0, idemCount, "失败请求不得留下幂等记录");
        // 同一 requestId 可重试成功
        createLinearCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I4", "1", "alice", "REQ-4")))
                .andExpect(status().isCreated());
    }

    @Test
    void requestIdWithoutOperatorReturns400() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0","requestId":"REQ-5"}"""))
                .andExpect(status().isBadRequest());
        // 提交测量的操作者为 submittedBy，为空同样 400
        createLinearCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("M-I5", "1", "", "REQ-6")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void certificateCreateAndRevokeReplay() throws Exception {
        String createJson = """
                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0","operator":"op","requestId":"REQ-7"}""";
        String first = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        Integer certCount = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_certificate", Integer.class);
        assertEquals(1, certCount, "重放不得重复建证");

        long id = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();
        String revokeJson = "{\"operator\":\"op\",\"requestId\":\"REQ-8\"}";
        String revokeFirst = mvc.perform(post("/api/certificates/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON).content(revokeJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String revokeReplay = mvc.perform(post("/api/certificates/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON).content(revokeJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(revokeFirst, revokeReplay, "撤销重放必须返回首次响应而非 409");
    }

    @Test
    void releaseReplayReturnsSameBatchId() throws Exception {
        createLinearCert("INS-1");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-I6","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
        String releaseJson = "{\"keys\":[\"M-I6\"],\"requestId\":\"REQ-9\"}";
        String first = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(releaseJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(com.jayway.jsonpath.JsonPath.<String>read(first, "$.batchId"),
                com.jayway.jsonpath.JsonPath.<String>read(replay, "$.batchId"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(1, releaseCount, "放行重放不得重复写历史");
    }
}
