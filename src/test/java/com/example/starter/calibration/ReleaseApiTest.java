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
 * 批量放行测试：原子整批拒绝及各项原因、重复放行 409、放行人校验、
 * 证书撤销后失去当前可用资格但历史保留、批次数量校验、历史明细与可用查询。
 * 放行请求携带 requestId 与环境温湿度；系数版本 v1 在大区间内持续生效，
 * 参考温湿度取 22.5℃/50%RH，本类放行环境取参考值使补偿为 0。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM coefficient_version");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM coefficient_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s",
                                 "tempMin":"20","tempMax":"25","humMin":"30","humMax":"70"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void createCoefficientVersion() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionTag":"v1","referenceTemperature":"22.5","referenceHumidity":"50",
                                 "temperatureCoefficient":"0.01","humidityCoefficient":"0.001",
                                 "effectiveFrom":"2020-01-01T00:00:00Z",
                                 "effectiveTo":"2100-01-01T00:00:00Z"}"""))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String reading, String lower,
                        String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            releaseRequest(String requestId, String body) {
        return post("/api/measurements/release")
                .header("X-Actor-Id", "carol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(requestId));
    }

    @Test
    void validBatchReleasedAtomically() throws Exception {
        createCoefficientVersion();
        createCert("INS-1", "1", "0");
        submit("R-1", "INS-1", "1", "0", "9", "alice");
        submit("R-2", "INS-1", "2", "0", "9", "bob");

        mvc.perform(releaseRequest("REQ-1",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\",\"R-2\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.coefficientVersion").value("v1"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.released[0]").value("R-1"))
                .andExpect(jsonPath("$.released[1]").value("R-2"));

        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.reading").value("1"))
                .andExpect(jsonPath("$.compensationValue").value("0"))
                .andExpect(jsonPath("$.finalValue").value("1.0000"))
                .andExpect(jsonPath("$.temperature").value("22.5"))
                .andExpect(jsonPath("$.humidity").value("50"))
                .andExpect(jsonPath("$.coefficientVersion").value("v1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"))
                .andExpect(jsonPath("$.releases[0].batchId").isString());

        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-1"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/measurements/usable").param("instrumentId", "INS-OTHER"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidBatchRejectsWholeBatchWithItemReasonsAndNoPartialSuccess() throws Exception {
        createCoefficientVersion();
        createCert("INS-1", "1", "0");
        submit("R-OK", "INS-1", "1", "0", "9", "alice");        // 合格但提交人=放行人
        submit("R-FAIL", "INS-1", "100", "0", "9", "bob");      // 不合格
        submit("R-GOOD", "INS-1", "2", "0", "9", "bob");        // 合法可放行

        // 放行人 alice：R-OK 触发 SAME_ACTOR；R-FAIL 触发 NOT_PASSED → 整批 409
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-BAD","keys":["R-OK","R-FAIL","R-GOOD"],
                                 "temperature":"22.5","humidity":"50"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='R-OK')].reasons[0]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures[?(@.key=='R-FAIL')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures.length()").value(2));

        // 整批回滚：R-GOOD 仍为待放行，未被部分放行；失败请求不写快照
        mvc.perform(get("/api/measurements/{key}", "R-GOOD"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, releaseCount);
        Integer snapshotCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, snapshotCount);
    }

    @Test
    void missingMeasurementAndMultipleReasonsReported() throws Exception {
        createCoefficientVersion();
        createCert("INS-1", "1", "0");
        submit("R-FAIL", "INS-1", "100", "0", "9", "alice"); // 不合格且提交人=放行人
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-MISS","keys":["R-FAIL","R-MISSING"],
                                 "temperature":"22.5","humidity":"50"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-FAIL')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures[?(@.key=='R-FAIL')].reasons[1]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures[?(@.key=='R-MISSING')].reasons[0]")
                        .value("MEASUREMENT_NOT_FOUND"));
    }

    @Test
    void replaySameRequestIdReturnsSnapshotAndNewRequestOnReleasedMeasurementConflicts() throws Exception {
        createCoefficientVersion();
        createCert("INS-1", "1", "0");
        submit("R-1", "INS-1", "1", "0", "9", "alice");

        String firstBody = mvc.perform(releaseRequest("REQ-R",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        String firstBatchId = (String) com.jayway.jsonpath.JsonPath.read(firstBody, "$.batchId");

        // 同一 requestId 完全重放 → 200 首次快照，不重新计算、不新增放行历史
        String replayBody = mvc.perform(releaseRequest("REQ-R",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(firstBatchId,
                com.jayway.jsonpath.JsonPath.read(replayBody, "$.batchId"));

        // 新 requestId 放行已放行测量 → 409 ALREADY_RELEASED
        mvc.perform(releaseRequest("REQ-R2",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("R-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));

        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'R-1'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, releaseCount);
    }

    @Test
    void revokingCertificateDropsUsabilityButKeepsHistory() throws Exception {
        createCoefficientVersion();
        long certId = createCert("INS-1", "1", "0");
        submit("R-1", "INS-1", "1", "0", "9", "alice");
        mvc.perform(releaseRequest("REQ-REV",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 撤销证书：立即失去当前可用资格
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 原始测量、补偿快照与放行历史保留，状态仍为 RELEASED，不回写为从未放行
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.finalValue").value("1.0000"))
                .andExpect(jsonPath("$.coefficientVersion").value("v1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
    }

    @Test
    void releasingUnderRevokedCertificateRejectedWith409() throws Exception {
        createCoefficientVersion();
        long certId = createCert("INS-1", "1", "0");
        submit("R-1", "INS-1", "1", "0", "9", "alice");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        mvc.perform(releaseRequest("REQ-RC",
                        "{\"requestId\":\"%s\",\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));

        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void batchSizeAndActorAndEnvironmentValidation() throws Exception {
        createCoefficientVersion();
        createCert("INS-1", "1", "0");
        submit("R-1", "INS-1", "1", "0", "9", "alice");

        // 空批次 → 400
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-E\",\"keys\":[],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isBadRequest());

        // 超过 50 条 → 400
        String big = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "\"K" + i + "\"").toList().toString();
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-E\",\"keys\":" + big
                                + ",\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isBadRequest());

        // 批次内重复键 → 400
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-E","keys":["R-1","R-1"],
                                 "temperature":"22.5","humidity":"50"}"""))
                .andExpect(status().isBadRequest());

        // 缺少 requestId → 400
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"R-1\"],\"temperature\":\"22.5\",\"humidity\":\"50\"}"))
                .andExpect(status().isBadRequest());

        // 缺少温度 → 400
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-E\",\"keys\":[\"R-1\"],\"humidity\":\"50\"}"))
                .andExpect(status().isBadRequest());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/measurements/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-E","keys":["R-1"],
                                 "temperature":"22.5","humidity":"50"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailUnknownMeasurementReturns404() throws Exception {
        mvc.perform(get("/api/measurements/{key}", "NO-SUCH-KEY"))
                .andExpect(status().isNotFound());
    }
}
