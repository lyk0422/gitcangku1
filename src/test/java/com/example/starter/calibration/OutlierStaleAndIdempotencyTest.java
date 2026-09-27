package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复核版本失效（410/STALE 留痕）与 requestId 幂等测试：
 * 同参重放首次结果、异参 409、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierStaleAndIdempotencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM outlier_verdict");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void createBatch(String batchKey, String instrument) throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s",
                                 "targetValue":"10","allowedDeviation":"1"}
                                """.formatted(batchKey, instrument)))
                .andExpect(status().isCreated());
    }

    private long submitOutlier(String batchKey, String requestId, String reading) throws Exception {
        String body = mvc.perform(post("/api/batches/{batchKey}/readings", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"%s","submittedBy":"alice"}
                                """.formatted(requestId, reading)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();
    }

    private String reviewJson(String requestId, String reviewer, String decision,
                              int revision, long certId) {
        return """
                {"requestId":"%s","reviewer":"%s","decision":"%s",
                 "reason":"%s 的理由","batchRevision":%d,"certificateId":%d}
                """.formatted(requestId, reviewer, decision, reviewer, revision, certId);
    }

    @Test
    void staleReviewReturns410AndPersistsStaleRecordWithoutOccupyingKey() throws Exception {
        long certId = createCert("INS-S1");
        createBatch("B-S1", "INS-S1");
        long outlierId = submitOutlier("B-S1", "rd-s1", "20");

        // 引用错误的测量修订版本 → 410，留痕 STALE，失败不占键
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-s1", "rev1", "ACCEPT", 99, certId)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW"));
        mvc.perform(get("/api/outliers/{outlierId}", outlierId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].stale").value(true))
                .andExpect(jsonPath("$.reviews[0].batchRevision").value(99));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'rv-s1'", Integer.class);
        assertEquals(0, idemCount, "410 失败不得占用幂等键");

        // 同一 requestId 修正版本后重试 → 201（失败未占键）
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-s1", "rev1", "ACCEPT", 1, certId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));

        // 测量修订后引用旧版本 → 410 留痕
        mvc.perform(post("/api/batches/{batchKey}/revisions", "B-S1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetValue\":\"10\",\"allowedDeviation\":\"2\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-s2", "rev2", "ACCEPT", 1, certId)))
                .andExpect(status().isGone());

        // 证书撤销后引用该证书 → 410 留痕
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-s3", "rev2", "ACCEPT", 2, certId)))
                .andExpect(status().isGone());

        // 全部留痕可追溯：1 条有效 + 3 条 STALE，离群记录仍未裁决
        mvc.perform(get("/api/outliers/{outlierId}", outlierId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviews.length()").value(4));
        Integer staleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE outlier_id = ? AND stale = TRUE",
                Integer.class, outlierId);
        assertEquals(3, staleCount);
    }

    @Test
    void readingSubmitIdempotencyReplayConflictAndFailureNotOccupying() throws Exception {
        createCert("INS-I1");
        createBatch("B-I1", "INS-I1");

        // 首次提交 201；同参重放返回首次结果（同一读数 ID）
        String first = mvc.perform(post("/api/batches/{batchKey}/readings", "B-I1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-i1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/batches/{batchKey}/readings", "B-I1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-i1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Object firstReadingId = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        Object replayReadingId = com.jayway.jsonpath.JsonPath.read(replay, "$.id");
        assertEquals(firstReadingId, replayReadingId, "同参重放必须返回首次结果");
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_reading WHERE request_id = 'rd-i1'", Integer.class);
        assertEquals(1, readingCount, "重放不得产生重复读数");

        // 异参同键 → 409
        mvc.perform(post("/api/batches/{batchKey}/readings", "B-I1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-i1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"11","submittedBy":"alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 失败不占键：无证书时刻提交 422 后，同一 requestId 可重新成功提交
        mvc.perform(post("/api/batches/{batchKey}/readings", "B-I1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-i2","measuredAt":"2030-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/batches/{batchKey}/readings", "B-I1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rd-i2","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10","submittedBy":"alice"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void reviewIdempotencyReplayAndConflict() throws Exception {
        long certId = createCert("INS-I2");
        createBatch("B-I2", "INS-I2");
        long outlierId = submitOutlier("B-I2", "rd-i9", "20");

        String first = mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-i1", "rev1", "ACCEPT", 1, certId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-i1", "rev1", "ACCEPT", 1, certId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Object firstReviewId = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        Object replayReviewId = com.jayway.jsonpath.JsonPath.read(replay, "$.id");
        assertEquals(firstReviewId, replayReviewId);
        Integer reviewCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE request_id = 'rv-i1'", Integer.class);
        assertEquals(1, reviewCount, "重放不得产生重复复核记录");

        // 异参同键 → 409
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-i1", "rev1", "REJECT", 1, certId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 不同 requestId 的复核正常推进裁决
        mvc.perform(post("/api/outliers/{outlierId}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("rv-i2", "rev2", "ACCEPT", 1, certId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));
        Integer verdictCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_verdict WHERE outlier_id = ?", Integer.class, outlierId);
        assertEquals(1, verdictCount);
    }
}
