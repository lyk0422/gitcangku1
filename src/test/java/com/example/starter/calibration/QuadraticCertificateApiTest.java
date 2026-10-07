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
 * 二次证书接口测试：单调分支创建主流程、a 非零、端点导数严格同号（非单调 400）、
 * 定义域与十进制校验、区间重叠门禁、requestId 幂等（重放、改参 409、失败不占键）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuadraticCertificateApiTest {

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

    private String quadJson(String requestId, String operator, String instrument, String a, String b,
                            String c, String lo, String hi) {
        return """
                {"requestId":"%s","operator":"%s","instrumentId":"%s",
                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                 "a":"%s","b":"%s","c":"%s","domainLo":"%s","domainHi":"%s"}
                """.formatted(requestId, operator, instrument, a, b, c, lo, hi);
    }

    @Test
    void createQuadraticCertificateReturns201WithBranch() throws Exception {
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-1", "alice", "INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.certType").value("QUADRATIC"))
                .andExpect(jsonPath("$.a").value("1"))
                .andExpect(jsonPath("$.b").value("0"))
                .andExpect(jsonPath("$.c").value("0"))
                .andExpect(jsonPath("$.domainLo").value("1"))
                .andExpect(jsonPath("$.domainHi").value("3"))
                .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test
    void zeroCoefficientARejected400() throws Exception {
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-2", "alice", "INS-Q", "0", "1", "0", "1", "3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMonotoneCertificateRejected400() throws Exception {
        // 导数 2x 在 [-1,3] 端点异号 → 非单调
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-3", "alice", "INS-Q", "1", "0", "0", "-1", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_MONOTONE_CERTIFICATE"));
        // 导数在端点为零（2x 在 x=0）→ 非严格单调
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-4", "alice", "INS-Q", "1", "0", "0", "0", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_MONOTONE_CERTIFICATE"));
        // 严格递减分支（导数 -2x 在 [1,3] 恒负）→ 合法
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-5", "alice", "INS-Q", "-1", "0", "0", "1", "3")))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidDomainAndDecimalsRejected400() throws Exception {
        // domainLo >= domainHi
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-6", "alice", "INS-Q", "1", "0", "0", "3", "3")))
                .andExpect(status().isBadRequest());
        // 系数超过 6 位小数
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-7", "alice", "INS-Q", "1.0000001", "0", "0", "1", "3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequestIdOrOperatorRejected400() throws Exception {
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("", "alice", "INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-8", "", "INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlapWithLinearCertificateConflict409() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-Q","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-9", "alice", "INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
    }

    @Test
    void replayReturnsFirstResponseAndParamChangeReturns409() throws Exception {
        String body = quadJson("Q-10", "alice", "INS-Q", "1.0", "0", "0", "1", "3");
        String first = mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 原样重放 → 首次完整响应
        String replay = mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "重放必须返回首次完整响应");

        // 十进制同值同参：1.00 与 1.0 视为同参 → 仍为重放
        String sameValue = mvc.perform(post("/api/certificates/quadratic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-10", "alice", "INS-Q", "1.00", "0", "0", "1", "3")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, sameValue, "十进制同值必须视为同参并重放");

        // 改参 → 409
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-10", "alice", "INS-Q", "2", "0", "0", "1", "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAMS_MISMATCH"));

        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-Q'", Integer.class);
        assertEquals(1, certCount, "幂等重放不得重复创建证书");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'Q-10'", Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        // 首次失败（非单调 400）→ 不占键
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-11", "alice", "INS-Q", "1", "0", "0", "-1", "3")))
                .andExpect(status().isBadRequest());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'Q-11'", Integer.class);
        assertEquals(0, idemCount, "失败请求不得占用幂等键");

        // 同一 requestId 改参重试 → 正常创建
        mvc.perform(post("/api/certificates/quadratic").contentType(MediaType.APPLICATION_JSON)
                        .content(quadJson("Q-11", "alice", "INS-Q", "1", "0", "0", "1", "3")))
                .andExpect(status().isCreated());
    }
}
