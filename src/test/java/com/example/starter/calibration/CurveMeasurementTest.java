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
 * 曲线测量测试：节点 (0,0)、(3,1)、(6,4) 的插值、节点命中、越界 422、
 * 精确分数判定（1/3 不得舍入成 0.3333 通过）、冻结语义与曲线放行/撤销流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurveMeasurementTest {

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

    private long createCurve(String instrument, String nodes) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","nodes":%s}
                                """.formatted(instrument, nodes)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String measurementJson(String key, String instrument, String reading,
                                   String lower, String upper, String by) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(key, instrument, reading, lower, upper, by);
    }

    private static final String NODES =
            "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
            + "{\"raw\":\"6\",\"corrected\":\"4\"}]";

    @Test
    void exactFractionOneThirdFailsAgainstUpperLimitWithoutRounding() throws Exception {
        createCurve("INS-M1", NODES);
        // 读数 1 的精确插值为 1/3；上限 0.333333 < 1/3 → 不合格，不得舍入成 0.3333 通过
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-1", "INS-M1", "1", "0", "0.333333", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.displayValue").value("0.3333"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.nodeLoIndex").value(0))
                .andExpect(jsonPath("$.nodeHiIndex").value(1));
    }

    @Test
    void exactFractionOneThirdPassesAgainstLowerLimit() throws Exception {
        createCurve("INS-M2", NODES);
        // 1/3 > 0.333333 → 满足下限
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-2", "INS-M2", "1", "0.333333", "1", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void nodeHitTakesNodeValueDirectly() throws Exception {
        createCurve("INS-M3", NODES);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-3", "INS-M3", "3", "1", "1", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.displayValue").value("1.0000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.nodeLoIndex").value(1))
                .andExpect(jsonPath("$.nodeHiIndex").value(1));
        // 末节点（闭区间右端）命中
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-4", "INS-M3", "6", "4", "4", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("4"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void interpolationOnSecondSegmentIsExact() throws Exception {
        createCurve("INS-M4", NODES);
        // 读数 4.5：1 + (4-1)×(4.5-3)/(6-3) = 2.5
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-5", "INS-M4", "4.5", "2.5", "2.5", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("2.5"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.nodeLoIndex").value(1))
                .andExpect(jsonPath("$.nodeHiIndex").value(2));
    }

    @Test
    void outOfRangeReadingRejected422NoExtrapolation() throws Exception {
        createCurve("INS-M5", NODES);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-6", "INS-M5", "-0.000001", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_RANGE"));
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-7", "INS-M5", "6.000001", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_RANGE"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count, "越界测量不得落库");
    }

    @Test
    void measurementFrozenAfterCertificateRevokedAndRecreated() throws Exception {
        long certId = createCurve("INS-M6", NODES);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-8", "INS-M6", "1", "0", "9", "alice")))
                .andExpect(status().isCreated());

        // 撤销旧曲线证书并以不同节点重建同区间证书
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        createCurve("INS-M6",
                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"2\"},"
                + "{\"raw\":\"6\",\"corrected\":\"8\"}]");

        // 历史明细仍使用提交时冻结的节点与分数，不改用新证书
        mvc.perform(get("/api/measurements/{key}", "CM-8"))
                .andExpect(jsonPath("$.certificateId").value(certId))
                .andExpect(jsonPath("$.computedValue").value("1/3"))
                .andExpect(jsonPath("$.displayValue").value("0.3333"))
                .andExpect(jsonPath("$.nodeLoIndex").value(0))
                .andExpect(jsonPath("$.nodeHiIndex").value(1));
    }

    @Test
    void curveReleaseThenRevokeDropsUsabilityButKeepsHistory() throws Exception {
        long certId = createCurve("INS-M7", NODES);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("CM-9", "INS-M7", "4.5", "0", "9", "alice")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"CM-9\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-M7"))
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-M7"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/measurements/{key}", "CM-9"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("2.5"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }
}
