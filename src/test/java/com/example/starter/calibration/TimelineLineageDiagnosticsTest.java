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
 * 查询接口测试：证书时间线、测量血缘、放行诊断。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimelineLineageDiagnosticsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String from, String to, String certVersion,
                            boolean singleBatchOnly) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"1","b":"0",
                                 "certVersion":"%s","compensationCoeff":"0.01",
                                 "uncertaintyVersion":"UV-1","singleBatchOnly":%s}
                                """.formatted(instrument, from, to, certVersion, singleBatchOnly)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"10",
                                 "lowerLimit":"0","upperLimit":"100","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    @Test
    void certificateTimelineOrderedAndIncludesRevoked() throws Exception {
        long first = createCert("INS-T", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z", "CV-1", false);
        createCert("INS-T", "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z", "CV-2", false);
        createCert("INS-OTHER", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CV-9", false);
        mvc.perform(post("/api/certificates/{id}/revoke", first)).andExpect(status().isOk());

        mvc.perform(get("/api/certificates/timeline").param("instrumentId", "INS-T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].certVersion").value("CV-1"))
                .andExpect(jsonPath("$[0].revoked").value(true))
                .andExpect(jsonPath("$[1].certVersion").value("CV-2"))
                .andExpect(jsonPath("$[1].revoked").value(false));

        // 其他仪器互不影响；无证书仪器返回空列表
        mvc.perform(get("/api/certificates/timeline").param("instrumentId", "INS-NONE"))
                .andExpect(jsonPath("$.length()").value(0));
        // 缺少 instrumentId → 400
        mvc.perform(get("/api/certificates/timeline"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lineageUnknownMeasurementReturns404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/lineage", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseDiagnosticsTracesStandardCompensationAndUncertaintyVersion() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "CV-1", false);
        submit("D-1", "INS-1");

        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-1\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(body, "$.batchId");

        mvc.perform(get("/api/releases/{batchId}/diagnostics", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].measurementKey").value("D-1"))
                .andExpect(jsonPath("$.items[0].certVersion").value("CV-1"))
                .andExpect(jsonPath("$.items[0].compensationCoeff").value("0.01"))
                .andExpect(jsonPath("$.items[0].uncertaintyVersion").value("UV-1"))
                .andExpect(jsonPath("$.items[0].singleBatchOnly").value(false));

        mvc.perform(get("/api/releases/{batchId}/diagnostics", "no-such-batch"))
                .andExpect(status().isNotFound());
    }

    @Test
    void diagnosticsShowsBoundBatchForSingleBatchOnlyCertificate() throws Exception {
        long certId = createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z",
                "CV-1", true);
        submit("D-2", "INS-1");

        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-2\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(body, "$.batchId");

        mvc.perform(get("/api/releases/{batchId}/diagnostics", batchId))
                .andExpect(jsonPath("$.items[0].singleBatchOnly").value(true))
                .andExpect(jsonPath("$.items[0].boundBatchId").value(batchId));
        mvc.perform(get("/api/certificates/{id}", certId))
                .andExpect(jsonPath("$.boundBatchId").value(batchId));
    }
}
