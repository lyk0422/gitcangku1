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
 * 测量修订与放行快照：已放行批次新增离群事实只能创建后续修订版本；
 * 后续修订不改写旧放行快照；诊断与明细读取不改变状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchRevisionSnapshotTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM adjudication_record");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
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

    private void createBatch(String key, String instrument) throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s","targetValue":"10","tolerance":"0.5"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    private String submitReading(String batchKey, String readingKey, String reading, String by)
            throws Exception {
        return mvc.perform(post("/api/batches/{key}/readings", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"%s","submittedBy":"%s"}
                                """.formatted(readingKey, reading, by)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void newOutlierFactOnReleasedBatchCreatesSubsequentRevisionOnly() throws Exception {
        createCert("INS-R1");
        createBatch("B-R1", "INS-R1");
        submitReading("B-R1", "RD-1", "10.1", "alice");
        mvc.perform(post("/api/batches/{key}/release", "B-R1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        // 已放行批次新增离群读数：只能创建后续修订版本，批次重新打开
        String body = submitReading("B-R1", "RD-2", "10.9", "alice");
        org.junit.jupiter.api.Assertions.assertEquals(2,
                (int) com.jayway.jsonpath.JsonPath.read(body, "$.batchRevision"));
        mvc.perform(get("/api/batches/{key}", "B-R1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.pendingOutliers").value(1));

        // 旧放行快照不被后续修订改写：仍记录修订版本 1、1 条读数
        mvc.perform(get("/api/batches/{key}/diagnostics", "B-R1"))
                .andExpect(jsonPath("$.releaseSnapshots.length()").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].revision").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].readingCount").value(1))
                .andExpect(jsonPath("$.releaseSnapshots[0].releasedBy").value("carol"));
        java.util.Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT * FROM batch_release_snapshot");
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) snapshot.get("revision")).intValue());

        // 新修订下的离群值未裁决：放行继续 422
        mvc.perform(post("/api/batches/{key}/release", "B-R1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void diagnosticsAndDetailReadsDoNotChangeState() throws Exception {
        createCert("INS-R2");
        createBatch("B-R2", "INS-R2");
        submitReading("B-R2", "RD-1", "10.9", "alice");

        String before = mvc.perform(get("/api/batches/{key}/diagnostics", "B-R2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.batchKey").value("B-R2"))
                .andExpect(jsonPath("$.outliers[0].targetValue").value("10"))
                .andExpect(jsonPath("$.outliers[0].tolerance").value("0.5"))
                .andExpect(jsonPath("$.outliers[0].readingValue").value("10.9"))
                .andExpect(jsonPath("$.outliers[0].deviation").value("0.9"))
                .andExpect(jsonPath("$.outliers[0].status").value("PENDING"))
                .andExpect(jsonPath("$.statsHistory[0].triggerAction").value("SUBMIT"))
                .andReturn().getResponse().getContentAsString();
        String after = mvc.perform(get("/api/batches/{key}/diagnostics", "B-R2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(before, after, "重复读取诊断不得改变状态");
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM review_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, reviewCount, "读取不得产生复核记录");
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mvc.perform(get("/api/batches/{key}", "NO-SUCH")).andExpect(status().isNotFound());
        mvc.perform(get("/api/batches/{key}/diagnostics", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/batches/{key}/readings", "NO-SUCH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"RD-X","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"1","submittedBy":"alice"}"""))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/batches/{key}/release", "NO-SUCH")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchValidationAndDuplicateKey() throws Exception {
        createCert("INS-R3");
        createBatch("B-R3", "INS-R3");
        // 批次键重复 → 409
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-R3","instrumentId":"INS-R3",
                                 "targetValue":"10","tolerance":"0.5"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BATCH_KEY"));
        // 负偏差 → 400
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-R4","instrumentId":"INS-R3",
                                 "targetValue":"10","tolerance":"-0.1"}"""))
                .andExpect(status().isBadRequest());
        // 读数键重复 → 409
        submitReading("B-R3", "RD-1", "10.1", "alice");
        mvc.perform(post("/api/batches/{key}/readings", "B-R3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"RD-1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10.2","submittedBy":"bob"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_READING_KEY"));
        // 重复放行 → 409
        mvc.perform(post("/api/batches/{key}/release", "B-R3")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/batches/{key}/release", "B-R3")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }
}