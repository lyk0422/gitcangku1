package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离群检测测试：批次目标值与允许偏差配置、读数越限自动生成 OUTLIER 并阻断放行（422）、
 * 边界值（恰好等于偏差不判离群）、统计重算与参数校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierDetectionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outlier_audit");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM calibration_batch");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    void createBatch(String key, String instrument, String target, String deviation) throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-%s","batchKey":"%s","instrumentId":"%s",
                                 "targetValue":"%s","allowedDeviation":"%s"}
                                """.formatted(key, key, instrument, target, deviation)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    String submitReading(String batch, String key, String value, String by) throws Exception {
        return mvc.perform(post("/api/batches/{batch}/readings", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R-%s","readingKey":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"%s","submittedBy":"%s"}
                                """.formatted(key, key, value, by)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void readingWithinDeviationCreatesNoOutlierAndComputesStats() throws Exception {
        createCert("INS-B");
        createBatch("B-1", "INS-B", "10", "0.5");
        submitReading("B-1", "RD-1", "10.2", "alice");

        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.outliers.length()").value(0))
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].excluded").value(false))
                .andExpect(jsonPath("$.latestStats.includedCount").value(1))
                .andExpect(jsonPath("$.latestStats.excludedCount").value(0))
                .andExpect(jsonPath("$.latestStats.meanValue").value("10.2"))
                .andExpect(jsonPath("$.latestStats.minValue").value("10.2"))
                .andExpect(jsonPath("$.latestStats.maxValue").value("10.2"))
                .andExpect(jsonPath("$.latestStats.trigger").value("READING_SUBMITTED"));

        // 无离群：放行成功并封存快照
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.includedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10.2"));
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].releasedBy").value("carol"));
    }

    @Test
    void readingAtExactDeviationIsNotOutlier() throws Exception {
        createCert("INS-B");
        createBatch("B-1", "INS-B", "10", "0.5");
        // 偏差恰好等于允许偏差：不判离群（超出才判）
        String body = submitReading("B-1", "RD-1", "10.5", "alice");
        Object outlierId = com.jayway.jsonpath.JsonPath.read(body, "$.outlierId");
        org.junit.jupiter.api.Assertions.assertNull(outlierId);
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.outliers.length()").value(0));
    }
    @Test
    void readingBeyondDeviationCreatesOutlierAndBlocksRelease() throws Exception {
        createCert("INS-B");
        createBatch("B-1", "INS-B", "10", "0.5");
        String body = submitReading("B-1", "RD-1", "10.6", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 离群记录封存目标值、阈值、读数与实际偏差/超出量
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.outliers.length()").value(1))
                .andExpect(jsonPath("$.outliers[0].id").value(outlierId))
                .andExpect(jsonPath("$.outliers[0].targetValue").value("10"))
                .andExpect(jsonPath("$.outliers[0].allowedDeviation").value("0.5"))
                .andExpect(jsonPath("$.outliers[0].readingValue").value("10.6"))
                .andExpect(jsonPath("$.outliers[0].actualDeviation").value("0.6"))
                .andExpect(jsonPath("$.outliers[0].excess").value("0.1"))
                .andExpect(jsonPath("$.outliers[0].status").value("PENDING"))
                .andExpect(jsonPath("$.outliers[0].decidedAt").value(nullValue()));

        // 离群检测写入不可变审计，封存角色与读数
        mvc.perform(get("/api/batches/{key}/audits", "B-1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("OUTLIER_DETECTED"))
                .andExpect(jsonPath("$[0].actor").value("alice"))
                .andExpect(jsonPath("$[0].readingValue").value("10.6"))
                .andExpect(jsonPath("$[0].batchRevision").value(1));

        // 未裁决离群阻断放行：422 并返回实际值、要求值与差额
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTLIERS_PENDING"))
                .andExpect(jsonPath("$.details.actual").value(1))
                .andExpect(jsonPath("$.details.required").value(0))
                .andExpect(jsonPath("$.details.shortfall").value(1));

        // 阻断不产生半成品：批次仍 OPEN，无放行快照
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(0));
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, snapshots);
    }

    @Test
    void batchValidationAndDuplicates() throws Exception {
        createCert("INS-B");
        // 负偏差 → 400
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-V-1","batchKey":"B-NEG","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"-0.1"}"""))
                .andExpect(status().isBadRequest());
        // 缺少 requestId → 400
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-NORID","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(status().isBadRequest());
        // 目标值小数位超限 → 400
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-V-2","batchKey":"B-BAD","instrumentId":"INS-B",
                                 "targetValue":"10.0000001","allowedDeviation":"0.5"}"""))
                .andExpect(status().isBadRequest());

        createBatch("B-1", "INS-B", "10", "0.5");
        // 不同 requestId 重复批次键 → 409
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-V-3","batchKey":"B-1","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BATCH_KEY"));
    }

    @Test
    void readingSubmitFailures() throws Exception {
        createCert("INS-B");
        createBatch("B-1", "INS-B", "10", "0.5");

        // 批次不存在 → 404
        mvc.perform(post("/api/batches/{batch}/readings", "B-MISSING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-F-1","readingKey":"RD-X",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10","submittedBy":"alice"}"""))
                .andExpect(status().isNotFound());

        // 测量时刻无匹配有效证书 → 422
        mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-F-2","readingKey":"RD-NOCERT",
                                 "measuredAt":"2030-06-01T00:00:00Z","value":"10","submittedBy":"alice"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));

        submitReading("B-1", "RD-1", "10.1", "alice");
        // 不同 requestId 重复读数键 → 409
        mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-F-3","readingKey":"RD-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.1","submittedBy":"alice"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_READING_KEY"));
    }
}