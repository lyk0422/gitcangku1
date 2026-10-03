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
 * requestId 幂等：同参重放返回首次结果，异参 409，失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchIdempotencyTest {

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

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() throws Exception {
        createCert("INS-I1");
        String body = """
                {"batchKey":"B-I1","instrumentId":"INS-I1","targetValue":"10",
                 "tolerance":"0.5","requestId":"req-create-1"}""";
        String first = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchKey").value("B-I1"))
                .andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次结果，不重复建批
        String replay = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(first, replay);
        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_batch WHERE batch_key = 'B-I1'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, batchCount);

        // 异参重放 → 409
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-I1","instrumentId":"INS-I1","targetValue":"20",
                                 "tolerance":"0.5","requestId":"req-create-1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void readingSubmitReplaysWithoutDuplicateRow() throws Exception {
        createCert("INS-I2");
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-I2","instrumentId":"INS-I2","targetValue":"10",
                                 "tolerance":"0.5","requestId":"req-b2"}"""))
                .andExpect(status().isCreated());
        String reading = """
                {"readingKey":"RD-I2","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"10.9","submittedBy":"alice","requestId":"req-read-1"}""";
        String first = mvc.perform(post("/api/batches/{key}/readings", "B-I2")
                        .contentType(MediaType.APPLICATION_JSON).content(reading))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierId").isNumber())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/batches/{key}/readings", "B-I2")
                        .contentType(MediaType.APPLICATION_JSON).content(reading))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(first, replay);
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_reading WHERE reading_key = 'RD-I2'", Integer.class);
        Integer outlierCount = jdbc.queryForObject("SELECT COUNT(*) FROM outlier_record",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, readingCount);
        org.junit.jupiter.api.Assertions.assertEquals(1, outlierCount, "重放不得重复生成离群记录");
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        createCert("INS-I3");
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-I3","instrumentId":"INS-I3","targetValue":"10",
                                 "tolerance":"0.5"}"""))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/batches/{key}/readings", "B-I3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"RD-I3","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10.9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId"))
                .longValue();

        // 放行被 422 拒绝：失败不占键
        mvc.perform(post("/api/batches/{key}/release", "B-I3")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-release-1\"}"))
                .andExpect(status().isUnprocessableEntity());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-release-1'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, idemCount);

        // 裁决完成后，同一 requestId 可成功放行
        String review = """
                {"reviewer":"%s","decision":"ACCEPT","reason":"确认",
                 "batchRevision":1,"certificateVersion":1}""";
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review.formatted("bob")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON).content(review.formatted("dave")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/{key}/release", "B-I3")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-release-1\"}"))
                .andExpect(status().isOk());
        // 成功结果同参重放：返回首次放行结果，不重复放行
        mvc.perform(post("/api/batches/{key}/release", "B-I3")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-release-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchKey").value("B-I3"));
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, snapshots);
    }

    @Test
    void staleReviewDoesNotOccupyRequestId() throws Exception {
        createCert("INS-I4");
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-I4","instrumentId":"INS-I4","targetValue":"10",
                                 "tolerance":"0.5"}"""))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/batches/{key}/readings", "B-I4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readingKey":"RD-I4","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"10.9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId"))
                .longValue();

        // 410 STALE 为失败响应：不占键，修正版本后同一 requestId 可成功
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewer":"bob","decision":"ACCEPT","reason":"旧版本",
                                 "batchRevision":99,"certificateVersion":1,"requestId":"req-rev-1"}"""))
                .andExpect(status().isGone());
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewer":"bob","decision":"ACCEPT","reason":"修正后",
                                 "batchRevision":1,"certificateVersion":1,"requestId":"req-rev-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"));
    }
}