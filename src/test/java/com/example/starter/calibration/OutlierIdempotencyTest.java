package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等测试：requestId 同参重放首次结果、异参 409、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierIdempotencyTest {

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

    private void createCert() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-B","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
    }

    private String batchJson(String requestId, String key, String target) {
        return """
                {"requestId":"%s","batchKey":"%s","instrumentId":"INS-B",
                 "targetValue":"%s","allowedDeviation":"0.5"}
                """.formatted(requestId, key, target);
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() throws Exception {
        createCert();
        String first = mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-1", "B-1", "10")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同参重放：返回首次结果（同一批次 ID），不产生新记录
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-1", "B-1", "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.batchKey").value("B-1"));
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_batch", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, batches);
        Integer keys = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, keys);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        createCert();
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-2", "B-1", "10")))
                .andExpect(status().isCreated());
        // 同键异参 → 409
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-2", "B-2", "20")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_batch", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, batches);
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        // 无证书：提交读数失败 422，不占幂等键
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-3", "B-1", "10")))
                .andExpect(status().isCreated());
        String reading = """
                {"requestId":"REQ-ID-4","readingKey":"RD-1",
                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.1","submittedBy":"alice"}""";
        mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON).content(reading))
                .andExpect(status().isUnprocessableEntity());
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'REQ-ID-4'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, keys);

        // 补齐证书后同键同参可正常成功
        createCert();
        mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON).content(reading))
                .andExpect(status().isCreated());
    }

    @Test
    void reviewAndReleaseReplayReturnFirstResult() throws Exception {
        createCert();
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("REQ-ID-5", "B-1", "10")))
                .andExpect(status().isCreated());
        String readingBody = mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-ID-6","readingKey":"RD-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.6","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(readingBody, "$.outlierId")).longValue();

        // 复核重放：同一复核 ID，不产生第二条复核
        String review = """
                {"requestId":"REQ-ID-7","reviewer":"bob","decision":"ACCEPT","reason":"可接受",
                 "batchRevision":1,"certificateVersion":1}""";
        String first = mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reviewId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reviewId));
        Integer reviews = jdbc.queryForObject("SELECT COUNT(*) FROM outlier_review", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, reviews);

        // 裁决后放行，重放返回首次结果且不产生第二个快照
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-ID-8","reviewer":"carol","decision":"ACCEPT",
                                 "reason":"同意","batchRevision":1,"certificateVersion":1}"""))
                .andExpect(status().isCreated());
        String release = "{\"requestId\":\"REQ-ID-9\"}";
        String released = mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String releasedAt = com.jayway.jsonpath.JsonPath.read(released, "$.releasedAt");
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").value(releasedAt));
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, snapshots);
    }
}