package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离群复核主流程与失败分支：离群检测阻断放行、两人一致裁决、结论相反待仲裁、
 * REJECT 排除读数并重算统计、复核人校验、STALE 版本与 410、ACCEPT 理由必填。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierReviewApiTest {

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

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void createBatch(String key, String instrument, String target, String tolerance)
            throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s","targetValue":"%s","tolerance":"%s"}
                                """.formatted(key, instrument, target, tolerance)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
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

    private ResultActions review(long outlierId, String reviewer, String decision, String reason,
                                 int batchRevision, int certificateVersion) throws Exception {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reviewer":"%s","decision":"%s"%s,
                         "batchRevision":%d,"certificateVersion":%d}
                        """.formatted(reviewer, decision, reasonPart, batchRevision, certificateVersion)));
    }

    private ResultActions release(String batchKey, String actor) throws Exception {
        return mvc.perform(post("/api/batches/{key}/release", batchKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void outlierDetectedAndBlocksReleaseUntilAcceptedByQuorum() throws Exception {
        createCert("INS-B1");
        createBatch("B-1", "INS-B1", "10", "0.5");
        // 范围内读数：不产生离群记录
        submitReading("B-1", "RD-1", "10.2", "alice");
        // 离群读数：实际偏差 0.9 > 允许偏差 0.5
        String outlierBody = submitReading("B-1", "RD-2", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(outlierBody, "$.outlierId"))
                .longValue();
        org.junit.jupiter.api.Assertions.assertEquals("0.9",
                com.jayway.jsonpath.JsonPath.read(outlierBody, "$.deviation"));

        // 未裁决离群值阻断放行：422 且返回实际/要求数量
        release("B-1", "carol").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSTANDING_OUTLIERS"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("实际 1 条，要求 0 条")));

        // 仅一名复核：仍待裁决，放行继续 422
        review(outlierId, "bob", "ACCEPT", "复核确认可接受", 1, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        release("B-1", "carol").andExpect(status().isUnprocessableEntity());

        // 第二名复核人一致 ACCEPT：形成裁决，放行成功
        review(outlierId, "dave", "ACCEPT", "同意保留", 1, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.adjudicationDecision").value("ACCEPT"));
        release("B-1", "carol").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.readingCount").value(2))
                .andExpect(jsonPath("$.excludedCount").value(0));

        // 裁决记录与放行快照已写入
        Integer adjudications = jdbc.queryForObject("SELECT COUNT(*) FROM adjudication_record",
                Integer.class);
        Integer snapshots = jdbc.queryForObject("SELECT COUNT(*) FROM batch_release_snapshot",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, adjudications);
        org.junit.jupiter.api.Assertions.assertEquals(1, snapshots);
    }
    @Test
    void conflictingReviewsStayPendingUntilThirdReviewBreaksTie() throws Exception {
        createCert("INS-B2");
        createBatch("B-2", "INS-B2", "10", "0.5");
        String body = submitReading("B-2", "RD-1", "11.0", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 结论相反：保持待仲裁，批次继续阻断
        review(outlierId, "bob", "ACCEPT", "可接受", 1, 1).andExpect(status().isCreated());
        review(outlierId, "carol", "REJECT", null, 1, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        release("B-2", "dave").andExpect(status().isUnprocessableEntity());

        // 第三名复核人 ACCEPT：两票 ACCEPT 形成裁决
        review(outlierId, "erin", "ACCEPT", "同样可接受", 1, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.adjudicationDecision").value("ACCEPT"));
        release("B-2", "dave").andExpect(status().isOk());
    }

    @Test
    void rejectAdjudicationExcludesReadingAndRecomputesStatsInSameTransaction() throws Exception {
        createCert("INS-B3");
        createBatch("B-3", "INS-B3", "10", "0.5");
        submitReading("B-3", "RD-1", "10.1", "alice");
        String body = submitReading("B-3", "RD-2", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        review(outlierId, "bob", "REJECT", null, 1, 1).andExpect(status().isCreated());
        review(outlierId, "carol", "REJECT", null, 1, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("REJECTED"))
                .andExpect(jsonPath("$.adjudicationDecision").value("REJECT"));

        // 读数被排除、修订版本递增、统计已重算（仅剩 10.1 一条）
        java.util.Map<String, Object> reading = jdbc.queryForMap(
                "SELECT * FROM batch_reading WHERE reading_key = 'RD-2'");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, reading.get("excluded"));
        java.util.Map<String, Object> stats = jdbc.queryForMap(
                "SELECT * FROM batch_stats_record WHERE trigger_action = 'ADJUDICATION'");
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) stats.get("revision")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) stats.get("reading_count")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) stats.get("excluded_count")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(new java.math.BigDecimal("10.100000000000"),
                ((java.math.BigDecimal) stats.get("mean_value")).stripTrailingZeros()
                        .setScale(12, java.math.RoundingMode.HALF_UP));

        // 重算后无未裁决离群值：放行成功，快照记录修订版本 2
        release("B-3", "dave").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.readingCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10.1"));
    }

    @Test
    void reviewerMustDifferFromSubmitterAndCannotReviewTwice() throws Exception {
        createCert("INS-B4");
        createBatch("B-4", "INS-B4", "10", "0.5");
        String body = submitReading("B-4", "RD-1", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 复核人 = 读数提交人 → 409
        review(outlierId, "alice", "ACCEPT", "自己复核", 1, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));

        review(outlierId, "bob", "ACCEPT", "可接受", 1, 1).andExpect(status().isCreated());
        // 同一复核人重复有效复核 → 409
        review(outlierId, "bob", "ACCEPT", "再次复核", 1, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));

        review(outlierId, "carol", "ACCEPT", "同意", 1, 1).andExpect(status().isCreated());
        // 已裁决后再复核 → 409
        review(outlierId, "dave", "ACCEPT", "迟到", 1, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLIER_NOT_PENDING"));
    }

    @Test
    void acceptReviewRequiresReasonAndDecisionMustBeValid() throws Exception {
        createCert("INS-B5");
        createBatch("B-5", "INS-B5", "10", "0.5");
        String body = submitReading("B-5", "RD-1", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // ACCEPT 缺少理由 → 400
        review(outlierId, "bob", "ACCEPT", null, 1, 1).andExpect(status().isBadRequest());
        // 非法结论 → 400
        review(outlierId, "bob", "MAYBE", "x", 1, 1).andExpect(status().isBadRequest());
        // 不存在的离群记录 → 404
        review(999999L, "bob", "REJECT", null, 1, 1).andExpect(status().isNotFound());
    }
    @Test
    void staleVersionOrRevokedCertificateReturns410AndMarksReviewStale() throws Exception {
        long certId = createCert("INS-B6");
        createBatch("B-6", "INS-B6", "10", "0.5");
        String body = submitReading("B-6", "RD-1", "10.9", "alice");
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 引用的测量修订版本已变更 → 410，复核标记 STALE，返回实际与引用版本
        review(outlierId, "bob", "ACCEPT", "旧版本复核", 99, 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("引用 99，当前 1")));

        // 撤销证书（证书版本递增且失效）→ 410
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        review(outlierId, "carol", "ACCEPT", "撤销后复核", 1, 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // 诊断中可查到两条 STALE 复核，且不计入裁决：离群记录仍待裁决
        mvc.perform(get("/api/batches/{key}/diagnostics", "B-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outliers[0].status").value("PENDING"))
                .andExpect(jsonPath("$.outliers[0].reviews[0].status").value("STALE"))
                .andExpect(jsonPath("$.outliers[0].reviews[1].status").value("STALE"))
                .andExpect(jsonPath("$.outliers[0].reviews.length()").value(2));
        Integer validCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_record WHERE status = 'VALID'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, validCount);
    }

    @Test
    void releaseFailureLeavesNoPartialState() throws Exception {
        createCert("INS-B7");
        createBatch("B-7", "INS-B7", "10", "0.5");
        submitReading("B-7", "RD-1", "10.9", "alice");

        // 放行被 422 拒绝：批次仍 OPEN，无快照、无 RELEASE 统计记录
        release("B-7", "carol").andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/batches/{key}", "B-7"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pendingOutliers").value(1));
        Integer snapshots = jdbc.queryForObject("SELECT COUNT(*) FROM batch_release_snapshot",
                Integer.class);
        Integer releaseStats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_stats_record WHERE trigger_action = 'RELEASE'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, snapshots);
        org.junit.jupiter.api.Assertions.assertEquals(0, releaseStats);
    }
}