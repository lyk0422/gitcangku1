package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * H2 集成测试基类：每个用例前清理全部业务表（独立命名内存库 calibration，MODE=MySQL），
 * 并提供证书、阈值、测量、预算与放行的合成调用夹具。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractCalibrationApiTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanAll() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM measurement_revision");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM instrument_threshold_version");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    protected long createCertificate(String instrument, String from, String to, String a, String b)
            throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s"}
                                """.formatted(instrument, from, to, a, b)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    protected long createOpenThreshold(String instrument, String from, String max) throws Exception {
        return createThreshold(instrument, from, null, max);
    }

    protected long createThreshold(String instrument, String from, String to, String max)
            throws Exception {
        String toField = to == null ? "" : ",\"validTo\":\"" + to + "\"";
        String body = mvc.perform(post("/api/thresholds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s"%s,"maxUncertainty":"%s"}
                                """.formatted(instrument, from, toField, max)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    protected void submitMeasurement(String key, String instrument, String at, String reading,
                                     String lower, String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s",
                                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, at, reading, lower, upper, by)))
                .andReturn();
    }

    protected ResultActions registerBudget(String key, String actor, String bodyJson,
                                           Long expectedVersion) throws Exception {
        String versionField = expectedVersion == null ? ""
                : ",\"expectedBudgetVersion\":" + expectedVersion;
        String body = bodyJson.replaceFirst("\\}$", versionField + "}");
        return mvc.perform(post("/api/measurements/{key}/budget", key)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected ResultActions release(String actor, String requestId, String keysJson) throws Exception {
        var request = post("/api/measurements/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(keysJson);
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return mvc.perform(request.header("X-Actor-Id", actor));
    }

    protected String budgetJson(String... nameMagnitudePairs) {
        StringBuilder sb = new StringBuilder("{\"components\":[");
        for (int i = 0; i < nameMagnitudePairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(nameMagnitudePairs[i])
                    .append("\",\"magnitude\":\"").append(nameMagnitudePairs[i + 1]).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
