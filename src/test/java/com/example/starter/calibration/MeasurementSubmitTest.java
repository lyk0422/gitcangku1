package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测量提交接口测试：BigDecimal 精确计算、显示值 HALF_UP 4 位、
 * 合格判定使用未舍入值且含端点、证书匹配的左闭右开、400/409/422 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeasurementSubmitTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_lineage");
        jdbc.update("DELETE FROM batch_snapshot");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument, String from, String to, String a, String b) throws Exception {
        String json = """
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s"}
                """.formatted(instrument, from, to, a, b);
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private String measurementJson(String key, String instrument, String at, String reading,
                                   String lower, String upper, String by) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"%s",
                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(key, instrument, at, reading, lower, upper, by);
    }

    @Test
    void submitComputesExactValueAndHalfUpDisplayAndPendingStatus() throws Exception {
        // 1.5 × 2.000001 + 0.123456 = 3.1234575（精确）；显示值 HALF_UP 4 位 = 3.1235
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1.5", "0.123456");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-1", "INS-1", "2026-06-01T00:00:00Z",
                                "2.000001", "0", "3.123458", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("3.1234575"))
                .andExpect(jsonPath("$.displayValue").value("3.1235"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.certificateId").isNumber())
                .andExpect(jsonPath("$.releases").isArray());
    }

    @Test
    void passJudgmentUsesUnroundedValueEvenWhenDisplayLooksInside() throws Exception {
        // 未舍入值 1.00004 > 上限 1.00003 → 不合格；但显示值 1.0000 看似落在区间内
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-2", "INS-1", "2026-06-01T00:00:00Z",
                                "1.00004", "0", "1.00003", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.00004"))
                .andExpect(jsonPath("$.displayValue").value("1.0000"))
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void passJudgmentIncludesBothEndpoints() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        // 计算值恰好等于下限 → 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-3", "INS-1", "2026-06-01T00:00:00Z",
                                "1.00001", "1.00001", "2", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));
        // 计算值恰好等于上限 → 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-4", "INS-1", "2026-06-01T00:00:00Z",
                                "1.00001", "0", "1.00001", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void certificateIntervalIsLeftClosedRightOpen() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        // 恰好起点 → 匹配成功
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-5", "INS-1", "2026-01-01T00:00:00Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isCreated());
        // 恰好终点（右开）→ 无匹配 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-6", "INS-1", "2027-01-01T00:00:00Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        // 区间外 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-7", "INS-9", "2026-06-01T00:00:00Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void revokedCertificateCannotMatch() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long certId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-8", "INS-1", "2026-06-01T00:00:00Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duplicateKeyReturns409AndInvalidInputReturns400() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        String json = measurementJson("M-9", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEASUREMENT_KEY"));

        // 下限大于上限
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-10", "INS-1", "2026-06-01T00:00:00Z",
                                "1", "9", "0", "alice")))
                .andExpect(status().isBadRequest());
        // 小数位超过 6 位
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-11", "INS-1", "2026-06-01T00:00:00Z",
                                "1.0000001", "0", "9", "alice")))
                .andExpect(status().isBadRequest());
        // 缺少提交人
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-12","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":""}"""))
                .andExpect(status().isBadRequest());
    }
}
