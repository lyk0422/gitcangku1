package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参考漂移档案接口测试：创建/撤销/查询主流程、超精度 400、
 * requestId 幂等（同参重放、异参 409、失败可复用键）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftProfileApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM drift_profile");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String profileJson(String requestId, String instrument, String at, String offset) {
        return """
                {"requestId":"%s","operator":"op","instrumentId":"%s",
                 "profiledAt":"%s","referenceOffset":"%s"}
                """.formatted(requestId, instrument, at, offset);
    }

    @Test
    void createAndGetAndRevokeFlow() throws Exception {
        String body = mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("REQ-P1", "INS-P", "2026-06-01T10:00:00Z", "0.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.instrumentId").value("INS-P"))
                .andExpect(jsonPath("$.referenceOffset").value("0.5"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(get("/api/drift-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiledAt").value("2026-06-01T10:00:00Z"));

        mvc.perform(post("/api/drift-profiles/{id}/revoke", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-P2\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAt").isString());

        // 重复撤销 409
        mvc.perform(post("/api/drift-profiles/{id}/revoke", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-P3\",\"operator\":\"op\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));
    }

    @Test
    void createRejectsOverPrecisionInputs() throws Exception {
        // 偏移超过 6 位小数
        mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("REQ-P4", "INS-P", "2026-06-01T10:00:00Z", "0.1234567")))
                .andExpect(status().isBadRequest());
        // 时刻超出毫秒精度
        mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("REQ-P5", "INS-P", "2026-06-01T10:00:00.0001Z", "0")))
                .andExpect(status().isBadRequest());
        // 缺少操作者
        mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-P6","operator":"","instrumentId":"INS-P",
                                 "profiledAt":"2026-06-01T10:00:00Z","referenceOffset":"0"}"""))
                .andExpect(status().isBadRequest());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drift_profile", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count, "超精度输入不得产生档案");
    }

    @Test
    void idempotentCreateReplaysAndConflicts() throws Exception {
        String json = profileJson("REQ-P7", "INS-P", "2026-06-01T10:00:00Z", "1");
        String first = mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次完整响应，不新增档案
        mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(((Number) com.jayway.jsonpath.JsonPath
                        .read(first, "$.id")).longValue()));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drift_profile", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);

        // 同键异参 → 409
        mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("REQ-P7", "INS-P", "2026-06-01T10:00:00Z", "2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestKeyIsReusable() throws Exception {
        // 业务失败（撤销不存在档案 404）后，同一 requestId 可复用
        mvc.perform(post("/api/drift-profiles/{id}/revoke", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-P8\",\"operator\":\"op\"}"))
                .andExpect(status().isNotFound());

        String body = mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("REQ-P9", "INS-P", "2026-06-01T10:00:00Z", "0")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/drift-profiles/{id}/revoke", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-P8\",\"operator\":\"op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }

    @Test
    void getUnknownProfileReturns404() throws Exception {
        mvc.perform(get("/api/drift-profiles/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}
