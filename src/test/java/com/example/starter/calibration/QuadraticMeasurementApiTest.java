package com.example.starter.calibration;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 二次模式测量提交测试（合成样例 y=x²，分支 [1,3]）：
 * 信号 4 反解 2 且合格域 [2,2] 允许；信号 4.000001 不合格（不得显示 2.0000 后放行）；
 * 信号越界 422、合格区间越出输入域 400、递减分支、夹逼区间宽度、冻结数据不随撤证重算。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuadraticMeasurementApiTest {

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

    private void createQuadCert(String instrument, String a, String b, String c,
                                String lo, String hi) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"QUADRATIC",
                                 "a":"%s","b":"%s","c":"%s","domainLo":"%s","domainHi":"%s"}
                                """.formatted(instrument, a, b, c, lo, hi)))
                .andExpect(status().isCreated());
    }

    private String measurementJson(String key, String instrument, String signal,
                                   String lo, String hi, String by) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(key, instrument, signal, lo, hi, by);
    }

    @Test
    void syntheticSampleSignal4SolvesTo2AndPasses() throws Exception {
        createQuadCert("INS-Q", "1", "0", "0", "1", "3");
        String body = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-1", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("QUADRATIC"))
                .andExpect(jsonPath("$.branch").value("INCREASING"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.displayValue").value("2.0000"))
                .andExpect(jsonPath("$.evalLower").value("4"))
                .andExpect(jsonPath("$.evalUpper").value("4"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        // 夹逼区间含精确根 2 且宽度不大于 0.000001
        BigDecimal rootLo = new BigDecimal(com.jayway.jsonpath.JsonPath.<String>read(body, "$.rootLower"));
        BigDecimal rootHi = new BigDecimal(com.jayway.jsonpath.JsonPath.<String>read(body, "$.rootUpper"));
        assertTrue(rootLo.compareTo(new BigDecimal("2")) <= 0 && rootHi.compareTo(new BigDecimal("2")) >= 0,
                "夹逼区间必须含根 2");
        assertTrue(rootHi.subtract(rootLo).compareTo(new BigDecimal("0.000001")) <= 0,
                "夹逼区间宽度必须不大于 0.000001");
    }

    @Test
    void signal4000001FailsEvenThoughDisplayRoundsTo2() throws Exception {
        createQuadCert("INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-2", "INS-Q", "4.000001", "2", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.displayValue").value("2.0000"));
    }

    @Test
    void signalOutsideEndpointValuesReturns422() throws Exception {
        createQuadCert("INS-Q", "1", "0", "0", "1", "3");
        // 低于 f(1)=1 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-3", "INS-Q", "0.5", "1", "3", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIGNAL_OUT_OF_RANGE"));
        // 高于 f(3)=9 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-4", "INS-Q", "9.000001", "1", "3", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIGNAL_OUT_OF_RANGE"));
        // 恰好等于端点函数值 f(3)=9 → 201，根为 3
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-5", "INS-Q", "9", "3", "3", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.displayValue").value("3.0000"));
    }

    @Test
    void acceptanceIntervalOutsideDomainReturns400() throws Exception {
        createQuadCert("INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-6", "INS-Q", "4", "0.5", "2", "alice")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-7", "INS-Q", "4", "2", "3.5", "alice")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decreasingBranchSolvesUniqueRoot() throws Exception {
        // y=-x²+10 在 [1,3] 严格递减：f(1)=9，f(3)=1；信号 4 的根为 √6≈2.4495
        createQuadCert("INS-D", "-1", "0", "10", "1", "3");
        String body = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-8", "INS-D", "4", "2", "3", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branch").value("DECREASING"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.evalLower").value("6"))
                .andExpect(jsonPath("$.evalUpper").value("1"))
                .andExpect(jsonPath("$.displayValue").value("2.4495"))
                .andReturn().getResponse().getContentAsString();
        BigDecimal rootLo = new BigDecimal(com.jayway.jsonpath.JsonPath.<String>read(body, "$.rootLower"));
        BigDecimal rootHi = new BigDecimal(com.jayway.jsonpath.JsonPath.<String>read(body, "$.rootUpper"));
        BigDecimal sqrt6 = new BigDecimal("2.449490");
        assertTrue(rootLo.compareTo(sqrt6) <= 0 && rootHi.compareTo(sqrt6) >= 0,
                "夹逼区间必须含 √6");
        // 合格域 [2.5,3]：f(2.5)=3.75 < 信号 4，根不在该区间 → 不合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-9", "INS-D", "4", "2.5", "3", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void frozenDetailSurvivesCertificateRevocation() throws Exception {
        createQuadCert("INS-Q", "1", "0", "0", "1", "3");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-10", "INS-Q", "4", "2", "2", "alice")))
                .andExpect(status().isCreated());
        Long certId = jdbc.queryForObject("SELECT certificate_id FROM measurement WHERE measurement_key='Q-10'",
                Long.class);
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        // 历史展示不随撤证重算：分支、夹逼区间、端点函数值与判定保持冻结值
        mvc.perform(get("/api/measurements/{key}", "Q-10"))
                .andExpect(jsonPath("$.mode").value("QUADRATIC"))
                .andExpect(jsonPath("$.branch").value("INCREASING"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.displayValue").value("2.0000"))
                .andExpect(jsonPath("$.evalLower").value("4"))
                .andExpect(jsonPath("$.evalUpper").value("4"))
                .andExpect(jsonPath("$.usable").value(false));
    }
}
