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
 * 参考漂移模式测量提交测试：合成主流程（10:00 偏 0、11:00 偏 2、10:30 原值 5 → 4）、
 * 精确有理数保存与 HALF_UP 展示、区间/仪器/撤销 422 分支、超精度 400、幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftMeasurementSubmitTest {

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

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private long createProfile(String requestId, String instrument, String at, String offset) throws Exception {
        String body = mvc.perform(post("/api/drift-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"op","instrumentId":"%s",
                                 "profiledAt":"%s","referenceOffset":"%s"}
                                """.formatted(requestId, instrument, at, offset)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String driftJson(String requestId, String key, String instrument, String at,
                             String reading, String lower, String upper, long t0, long t1) {
        return """
                {"requestId":"%s","operator":"alice","measurementKey":"%s","instrumentId":"%s",
                 "measuredAt":"%s","reading":"%s","lowerLimit":"%s","upperLimit":"%s",
                 "profileT0Id":%d,"profileT1Id":%d}
                """.formatted(requestId, key, instrument, at, reading, lower, upper, t0, t1);
    }

    @Test
    void syntheticScenarioInterpolatesAndSubtracts() throws Exception {
        // 合成场景：证书 a=1、b=0；10:00 偏 0、11:00 偏 2；10:30 原值 5 → 插值偏移 1 → 校正值 4
        createCert("INS-D");
        long t0 = createProfile("REQ-D1", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D2", "INS-D", "2026-06-01T11:00:00Z", "2");

        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D3", "D-1", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", t0, t1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("DRIFT"))
                .andExpect(jsonPath("$.computedValue").value("4"))
                .andExpect(jsonPath("$.displayValue").value("4.0000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.profileT0Id").value(t0))
                .andExpect(jsonPath("$.profileT1Id").value(t1));
    }

    @Test
    void exactRationalPreservedForNonTerminatingInterpolation() throws Exception {
        // 10:00:00 偏 0、10:00:03 偏 1，10:00:01 → 偏移 1/3；原值 1 → 校正值 2/3
        createCert("INS-D");
        long t0 = createProfile("REQ-D4", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D5", "INS-D", "2026-06-01T10:00:03Z", "1");

        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D6", "D-2", "INS-D", "2026-06-01T10:00:01Z",
                                "1", "0", "0.66667", t0, t1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("2/3"))
                .andExpect(jsonPath("$.displayValue").value("0.6667"))
                // 未舍入 2/3 ≈ 0.66667 > 上限 0.66667？不：2/3 = 0.666666… < 0.66667 → 合格
                .andExpect(jsonPath("$.passed").value(true));

        // 上限取 0.66666（小于 2/3）→ 不合格，即使显示值 0.6667 看似相等
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D7", "D-3", "INS-D", "2026-06-01T10:00:01Z",
                                "1", "0", "0.66666", t0, t1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("2/3"))
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void bracketViolationsReturn422() throws Exception {
        createCert("INS-D");
        long t0 = createProfile("REQ-D8", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D9", "INS-D", "2026-06-01T11:00:00Z", "2");
        long far = createProfile("REQ-D10", "INS-D", "2026-06-01T11:00:00.001Z", "2");
        long other = createProfile("REQ-D11", "INS-OTHER", "2026-06-01T10:30:00Z", "1");

        // t 越界（早于 t0）
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D12", "D-4", "INS-D", "2026-06-01T09:59:59Z",
                                "5", "0", "9", t0, t1)))
                .andExpect(status().isUnprocessableEntity());
        // 间隔超过 1 小时
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D13", "D-5", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", t0, far)))
                .andExpect(status().isUnprocessableEntity());
        // t0 不早于 t1（同一档案）
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D14", "D-6", "INS-D", "2026-06-01T10:00:00Z",
                                "5", "0", "9", t0, t0)))
                .andExpect(status().isUnprocessableEntity());
        // 仪器不符
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D15", "D-7", "INS-D", "2026-06-01T10:15:00Z",
                                "5", "0", "9", t0, other)))
                .andExpect(status().isUnprocessableEntity());
        // 档案不存在
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D16", "D-8", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", t0, 999999)))
                .andExpect(status().isUnprocessableEntity());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count, "422 不得产生测量");
    }

    @Test
    void revokedProfileAndMissingCertReturn422() throws Exception {
        createCert("INS-D");
        long t0 = createProfile("REQ-D17", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D18", "INS-D", "2026-06-01T11:00:00Z", "2");

        // 撤销任一参考 → 422，不补查最近档案代替
        mvc.perform(post("/api/drift-profiles/{id}/revoke", t1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-D19\",\"operator\":\"op\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D20", "D-9", "INS-D", "2026-06-01T10:30:00Z",
                                "5", "0", "9", t0, t1)))
                .andExpect(status().isUnprocessableEntity());

        // 无有效证书的仪器 → 422
        long u0 = createProfile("REQ-D21", "INS-NOCERT", "2026-06-01T10:00:00Z", "0");
        long u1 = createProfile("REQ-D22", "INS-NOCERT", "2026-06-01T11:00:00Z", "2");
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D23", "D-10", "INS-NOCERT", "2026-06-01T10:30:00Z",
                                "5", "0", "9", u0, u1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
    }

    @Test
    void overPrecisionInputReturns400AndProducesNothing() throws Exception {
        createCert("INS-D");
        long t0 = createProfile("REQ-D24", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D25", "INS-D", "2026-06-01T11:00:00Z", "2");

        // 读数超 6 位小数
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D26", "D-11", "INS-D", "2026-06-01T10:30:00Z",
                                "5.0000001", "0", "9", t0, t1)))
                .andExpect(status().isBadRequest());
        // 测量时刻超毫秒精度
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D27", "D-12", "INS-D", "2026-06-01T10:30:00.0001Z",
                                "5", "0", "9", t0, t1)))
                .andExpect(status().isBadRequest());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count, "超精度输入不得产生测量");
    }

    @Test
    void idempotentSubmitReplaysAndConflicts() throws Exception {
        createCert("INS-D");
        long t0 = createProfile("REQ-D28", "INS-D", "2026-06-01T10:00:00Z", "0");
        long t1 = createProfile("REQ-D29", "INS-D", "2026-06-01T11:00:00Z", "2");
        String json = driftJson("REQ-D30", "D-13", "INS-D", "2026-06-01T10:30:00Z",
                "5", "0", "9", t0, t1);

        String first = mvc.perform(post("/api/measurements/drift")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同参重放返回首次完整响应，不产生新测量
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.computedValue").value("4"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);

        // 同 requestId 异参 → 409
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content(driftJson("REQ-D30", "D-13", "INS-D", "2026-06-01T10:30:00Z",
                                "6", "0", "9", t0, t1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 明细仍可查
        mvc.perform(get("/api/measurements/{key}", "D-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.computedValue").value("4"));
    }
}
