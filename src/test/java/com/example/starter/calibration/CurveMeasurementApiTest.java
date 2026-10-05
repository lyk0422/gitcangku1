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
 * 曲线测量测试：节点命中直接取值、相邻节点线性插值、既约分数精确判定（不先转有限小数）、
 * 越界 422 禁止外推、节点闭区间边界、冻结节点区间不随新证书改变、放行与撤销语义。
 * 合成节点 (0,0)、(3,1)、(6,4)：读数 1 的精确值为 1/3，上限 0.333333 必须判不合格。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurveMeasurementApiTest {

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

    private long createCurveCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","type":"CURVE",
                                 "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                                          {"raw":"6","corrected":"4"}]}
                                """.formatted(instrument)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String measurementJson(String key, String instrument, String reading,
                                   String lower, String upper, String by) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(key, instrument, reading, lower, upper, by);
    }

    @Test
    void readingOnNodeTakesNodeValueDirectly() throws Exception {
        createCurveCert("INS-N1");
        // 落在节点 (3,1) 上：直接取节点值 1
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CN-1", "INS-N1", "3", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certType").value("CURVE"))
                .andExpect(jsonPath("$.computedValue").value("1/1"))
                .andExpect(jsonPath("$.displayValue").value("1.0000"))
                .andExpect(jsonPath("$.interval.loRaw").value("3"))
                .andExpect(jsonPath("$.interval.hiRaw").value("3"))
                .andExpect(jsonPath("$.interval.loCorrected").value("1"))
                .andExpect(jsonPath("$.passed").value(true));
        // 区间端点（闭区间）：读数 0 → 0，读数 6 → 4
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CN-2", "INS-N1", "0", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("0/1"))
                .andExpect(jsonPath("$.passed").value(true));
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CN-3", "INS-N1", "6", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("4/1"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void interpolationUsesExactFractionNotRoundedDecimal() throws Exception {
        createCurveCert("INS-N2");
        // 读数 1 → 精确值 1/3；上限 0.333333 < 1/3 → 不合格（不得舍入成 0.3333 通过）
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CF-1", "INS-N2", "1", "0", "0.333333", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.displayValue").value("0.3333"))
                .andExpect(jsonPath("$.interval.loRaw").value("0"))
                .andExpect(jsonPath("$.interval.hiRaw").value("3"))
                .andExpect(jsonPath("$.passed").value(false));
        // 上限 0.333334 > 1/3 → 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CF-2", "INS-N2", "1", "0", "0.333334", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.passed").value(true));
        // 下限 0.333334 > 1/3 → 不合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CF-3", "INS-N2", "1", "0.333334", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void interpolationBetweenNodesIsExact() throws Exception {
        createCurveCert("INS-N3");
        // 读数 4.5 落在 (3,1)-(6,4)：1 + 1.5×(4-1)/(6-3) = 5/2
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CI-1", "INS-N3", "4.5", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("5/2"))
                .andExpect(jsonPath("$.displayValue").value("2.5000"))
                .andExpect(jsonPath("$.interval.loRaw").value("3"))
                .andExpect(jsonPath("$.interval.loCorrected").value("1"))
                .andExpect(jsonPath("$.interval.hiRaw").value("6"))
                .andExpect(jsonPath("$.interval.hiCorrected").value("4"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void readingOutsideNodeRangeReturns422NoExtrapolation() throws Exception {
        createCurveCert("INS-N4");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CO-1", "INS-N4", "-0.000001", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_RANGE"));
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CO-2", "INS-N4", "6.000001", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_RANGE"));
        // 越界失败不产生测量记录
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void measurementFreezesNodesAndIntervalAcrossCertificateChange() throws Exception {
        long certId = createCurveCert("INS-N5");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CZ-1", "INS-N5", "1", "0", "9", "alice")))
                .andExpect(status().isCreated());

        // 撤销旧证书并创建同区间新曲线证书（节点不同）
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-N5","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","type":"CURVE",
                                 "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"10"},
                                          {"raw":"6","corrected":"40"}]}
                                """))
                .andExpect(status().isCreated());

        // 历史明细仍冻结原节点区间与精确值，不得改用新证书
        mvc.perform(get("/api/measurements/{key}", "CZ-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certId))
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.displayValue").value("0.3333"))
                .andExpect(jsonPath("$.interval.loRaw").value("0"))
                .andExpect(jsonPath("$.interval.hiRaw").value("3"))
                .andExpect(jsonPath("$.interval.hiCorrected").value("1"));
    }

    @Test
    void curveMeasurementReleaseAndRevokeSemantics() throws Exception {
        long certId = createCurveCert("INS-N6");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CR-1", "INS-N6", "4.5", "0", "9", "alice")))
                .andExpect(status().isCreated());

        // 放行人不同于提交人 → 放行成功
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"CR-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-N6"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].computedValue").value("5/2"));

        // 撤销曲线证书：失去当前可用资格，但放行历史保留
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-N6"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/measurements/{key}", "CR-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }
}
