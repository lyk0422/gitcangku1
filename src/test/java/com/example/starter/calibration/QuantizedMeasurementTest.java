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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 格点量化测量测试：精确值先算、半格取偶量化、精确值与量化值均须落在原合格闭区间、
 * 未配置仪器沿用原规则、配置换版不回算旧测量、越界待审放行 422。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuantizedMeasurementTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM resolution_config");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument, String a, String b) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated());
    }

    private void createConfig(String instrument, String requestId, String step, String origin)
            throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", instrument)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","step":"%s","origin":"%s","expectedVersion":0}
                                """.formatted(requestId, step, origin)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String key, String instrument, String reading, String lower, String upper)
            throws Exception {
        return mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"%s","instrumentId":"%s",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                         "lowerLimit":"%s","upperLimit":"%s","submittedBy":"alice"}
                        """.formatted(key, instrument, reading, lower, upper)));
    }

    @Test
    void specSyntheticCaseHalfGridToEvenAndDualInterval() throws Exception {
        createCert("INS-Q1", "1", "0");
        createConfig("INS-Q1", "REQ-Q1", "0.2", "0");
        // x=1.1 → (1.1-0)/0.2=5.5 半格取偶 k=6 → 量化 1.2；上限 1.25：精确值与量化值均在区间 → 合格
        submit("Q-1", "INS-Q1", "1.1", "0", "1.25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.1"))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.gridK").value("6"))
                .andExpect(jsonPath("$.resolutionVersion").value(1))
                .andExpect(jsonPath("$.passed").value(true));
        // x=1.3 → 6.5 半格取偶 k=6 → 量化 1.2；但精确值 1.3 > 1.25 → 不通过（不能先舍入再判断）
        submit("Q-2", "INS-Q1", "1.3", "0", "1.25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.3"))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.passed").value(false));
        // 精确值在区间、量化值越限（1.2 > 1.15）→ 不通过
        submit("Q-3", "INS-Q1", "1.1", "0", "1.15")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void halfEvenTiesAndNegativeValuesAndOrigin() throws Exception {
        createCert("INS-Q2", "1", "0");
        createConfig("INS-Q2", "REQ-Q2", "0.2", "0");
        submit("Q-10", "INS-Q2", "0.5", "-9", "9").andExpect(jsonPath("$.gridK").value("2"))
                .andExpect(jsonPath("$.quantizedValue").value("0.4"));
        submit("Q-11", "INS-Q2", "1.5", "-9", "9").andExpect(jsonPath("$.gridK").value("8"))
                .andExpect(jsonPath("$.quantizedValue").value("1.6"));
        submit("Q-12", "INS-Q2", "2.5", "-9", "9").andExpect(jsonPath("$.gridK").value("12"))
                .andExpect(jsonPath("$.quantizedValue").value("2.4"));
        submit("Q-13", "INS-Q2", "-0.5", "-9", "9").andExpect(jsonPath("$.gridK").value("-2"))
                .andExpect(jsonPath("$.quantizedValue").value("-0.4"));
        submit("Q-14", "INS-Q2", "1", "-9", "9").andExpect(jsonPath("$.gridK").value("5"))
                .andExpect(jsonPath("$.quantizedValue").value("1"));
        // 负原点：o=-0.1, s=0.3；x=0.5 → (0.5+0.1)/0.3=2 → 量化 0.5
        createCert("INS-Q3", "1", "0");
        createConfig("INS-Q3", "REQ-Q3", "0.3", "-0.1");
        submit("Q-15", "INS-Q3", "0.5", "-9", "9")
                .andExpect(jsonPath("$.gridK").value("2"))
                .andExpect(jsonPath("$.quantizedValue").value("0.5"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void unconfiguredInstrumentKeepsOriginalRule() throws Exception {
        createCert("INS-Q4", "1", "0");
        submit("Q-20", "INS-Q4", "1.00004", "0", "1.00003")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.00004"))
                .andExpect(jsonPath("$.quantizedValue").doesNotExist())
                .andExpect(jsonPath("$.resolutionVersion").doesNotExist())
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void configUpdateDoesNotRecomputeOldMeasurements() throws Exception {
        createCert("INS-Q5", "1", "0");
        createConfig("INS-Q5", "REQ-Q5", "0.2", "0");
        submit("Q-30", "INS-Q5", "1.1", "0", "9")
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.resolutionVersion").value(1));
        // 换版：s=0.5 → 新测量 x=1.1 → 1.1/0.5=2.2 → k=2 → 量化 1.0
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q5")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-Q5B\",\"step\":\"0.5\",\"origin\":\"0\","
                                + "\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        // 旧测量冻结值不变
        mvc.perform(get("/api/measurements/{key}", "Q-30"))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.resolutionVersion").value(1))
                .andExpect(jsonPath("$.passed").value(true));
        submit("Q-31", "INS-Q5", "1.1", "0", "9")
                .andExpect(jsonPath("$.quantizedValue").value("1"))
                .andExpect(jsonPath("$.resolutionVersion").value(2));
    }

    @Test
    void releaseUsesFrozenValuesAfterConfigChange() throws Exception {
        createCert("INS-Q6", "1", "0");
        createConfig("INS-Q6", "REQ-Q6", "0.2", "0");
        // v1：x=1.1 → 量化 1.2，上限 1.2 → 合格
        submit("Q-40", "INS-Q6", "1.1", "0", "1.2")
                .andExpect(jsonPath("$.passed").value(true));
        // 换版 o=0.05, s=0.3：若回算 (1.1-0.05)/0.3=3.5 → k=4 → 1.25 > 1.2 会不合格
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-Q6")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-Q6B\",\"step\":\"0.3\",\"origin\":\"0.05\","
                                + "\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        // 放行核验冻结值，不受换版影响 → 成功
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"Q-40\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "Q-40"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"));
    }

    @Test
    void outOfRangeSavedAsPendingAndReleaseReturns422() throws Exception {
        String big = "999999999999999999999999999999"; // 30 位，乘积超 DECIMAL(38,12) 精度
        createCert("INS-Q7", big, "0");
        submit("Q-50", "INS-Q7", big, "0", "9")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").doesNotExist())
                .andExpect(jsonPath("$.quantizedValue").doesNotExist())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"Q-50\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("Q-50"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("OUT_OF_RANGE"));
        // 整批无放行记录，测量保持待审
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, releaseCount);
        mvc.perform(get("/api/measurements/{key}", "Q-50"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
