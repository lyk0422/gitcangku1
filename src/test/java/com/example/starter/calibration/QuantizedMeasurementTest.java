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
 * 量化测量测试：先算精确校正值再按格点量化（半格取偶），合格要求精确值与量化值
 * 均在原合格闭区间；配置换版不回算旧测量；未配置仪器沿用原规则；不合格量化测量放行 422。
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM instrument_resolution");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void createResolution(String instrument, String requestId, String origin, String step)
            throws Exception {
        mvc.perform(put("/api/instruments/{id}/resolution", instrument)
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","origin":"%s","step":"%s","expectedVersion":null}
                                """.formatted(requestId, origin, step)))
                .andExpect(status().isCreated());
    }

    private String measurementJson(String key, String instrument, String reading,
                                   String lower, String upper) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"alice"}
                """.formatted(key, instrument, reading, lower, upper);
    }

    @Test
    void syntheticGridQuantizesToNearestEvenAndJudgesBothValues() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");

        // x=1.1 → k=6（5.5 半格取偶）→ 量化 1.2；精确值与量化值均在 [0,1.25] → 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-1", "INS-G", "1.1", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.1"))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.gridK").value("6"))
                .andExpect(jsonPath("$.resolutionVersion").value(1))
                .andExpect(jsonPath("$.passed").value(true));

        // x=1.3 → k=6（6.5 半格取偶）→ 量化 1.2；但精确值 1.3 越上限 1.25 → 不合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-2", "INS-G", "1.3", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.3"))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.gridK").value("6"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void quantizedValueOutOfIntervalFailsEvenWhenExactInside() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");
        // x=1.31 在 [0,1.35] 内，但 k=7 → 量化 1.4 越上限 → 不合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-3", "INS-G", "1.31", "0", "1.35")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.31"))
                .andExpect(jsonPath("$.quantizedValue").value("1.4"))
                .andExpect(jsonPath("$.passed").value(false));
        // 上限放宽到 1.4 后两者均在区间内 → 合格
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-4", "INS-G", "1.31", "0", "1.4")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void halfStepRoundsToEvenK() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");
        // x=0.5 → (0.5)/0.2=2.5 半格 → 取偶 k=2 → 量化 0.4
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-5", "INS-G", "0.5", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gridK").value("2"))
                .andExpect(jsonPath("$.quantizedValue").value("0.4"));
        // x=1.5 → 7.5 半格 → 取偶 k=8 → 量化 1.6
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-6", "INS-G", "1.5", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gridK").value("8"))
                .andExpect(jsonPath("$.quantizedValue").value("1.6"));
    }

    @Test
    void configUpdateDoesNotRecomputeOldMeasurements() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-OLD", "INS-G", "1.1", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));

        // 换版：s 从 0.2 改为 0.5，版本 2
        mvc.perform(put("/api/instruments/{id}/resolution", "INS-G")
                        .header("X-Actor-Id", "dave").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-G2","origin":"0","step":"0.5","expectedVersion":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 旧测量冻结值不变：版本 1、量化 1.2、合格结论不变
        mvc.perform(get("/api/measurements/{key}", "Q-OLD"))
                .andExpect(jsonPath("$.resolutionVersion").value(1))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.gridK").value("6"))
                .andExpect(jsonPath("$.passed").value(true));

        // 新测量使用版本 2：x=1.1 → k=2 → 量化 1.0
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-NEW", "INS-G", "1.1", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolutionVersion").value(2))
                .andExpect(jsonPath("$.quantizedValue").value("1"))
                .andExpect(jsonPath("$.gridK").value("2"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void unconfiguredInstrumentKeepsOriginalRule() throws Exception {
        createCert("INS-P");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("P-1", "INS-P", "1.1", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("1.1"))
                .andExpect(jsonPath("$.quantizedValue").doesNotExist())
                .andExpect(jsonPath("$.resolutionVersion").doesNotExist())
                .andExpect(jsonPath("$.gridK").doesNotExist())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void releasingFailedQuantizedMeasurementReturns422() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");
        // 精确值越限 → 不合格但保存待审
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-F", "INS-G", "1.3", "0", "1.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 放行不合格量化测量 → 422，整批无放行记录
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"Q-F\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PASSED"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        mvc.perform(get("/api/measurements/{key}", "Q-F"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void releasePassedQuantizedMeasurementAndRevokeDropsUsability() throws Exception {
        createCert("INS-G");
        createResolution("INS-G", "REQ-G1", "0", "0.2");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("Q-R", "INS-G", "1.1", "0", "1.25")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"Q-R\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-G"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantizedValue").value("1.2"));

        // 撤销证书：失去当前可用性，冻结值与放行历史保留
        long certId = ((Number) jdbc.queryForMap(
                "SELECT certificate_id FROM measurement WHERE measurement_key = 'Q-R'")
                .get("certificate_id")).longValue();
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-G"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/measurements/{key}", "Q-R"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.quantizedValue").value("1.2"))
                .andExpect(jsonPath("$.resolutionVersion").value(1))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }
}
