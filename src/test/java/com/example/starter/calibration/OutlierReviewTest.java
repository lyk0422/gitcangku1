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
 * 离群复核测试：双人一致裁决、结论相反待仲裁、REJECT 同事务排除读数并重算统计、
 * 复核人校验、版本/证书失效返回 410 并落库 STALE。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierReviewTest {

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

    private long certId;

    void createCertAndBatch() throws Exception {
        String certBody = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-B","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        certId = ((Number) com.jayway.jsonpath.JsonPath.read(certBody, "$.id")).longValue();
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-1","batchKey":"B-1","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(status().isCreated());
    }

    long submitOutlierReading(String key, String value, String by) throws Exception {
        String body = mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R-%s","readingKey":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"%s","submittedBy":"%s"}
                                """.formatted(key, key, value, by)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();
    }

    String reviewJson(String requestId, String reviewer, String decision, String reason,
                      int revision, int certVersion) {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return """
                {"requestId":"%s","reviewer":"%s","decision":"%s"%s,
                 "batchRevision":%d,"certificateVersion":%d}
                """.formatted(requestId, reviewer, decision, reasonPart, revision, certVersion);
    }

    @Test
    void twoAcceptsFormVerdictAndReleaseSucceeds() throws Exception {
        createCertAndBatch();
        long outlierId = submitOutlierReading("RD-1", "10.6", "alice");

        // 第一名复核人：法定人数未满，保持待裁决
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-1", "bob", "ACCEPT", "偏差在工艺容忍内", 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.validAcceptCount").value(1))
                .andExpect(jsonPath("$.validRejectCount").value(0))
                .andExpect(jsonPath("$.requiredQuorum").value(2))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));

        // 放行仍被阻断
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-1\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 第二名复核人一致 ACCEPT：形成裁决
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-2", "carol", "ACCEPT", "复核同意保留", 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validAcceptCount").value(2))
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));

        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.outliers[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.outliers[0].decidedAt").isString())
                .andExpect(jsonPath("$.outliers[0].reviews.length()").value(2));

        // 审计轨迹：检测 + 两次复核 + 裁决
        mvc.perform(get("/api/batches/{key}/audits", "B-1"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[1].eventType").value("REVIEW_SUBMITTED"))
                .andExpect(jsonPath("$[2].eventType").value("REVIEW_SUBMITTED"))
                .andExpect(jsonPath("$[3].eventType").value("VERDICT_ACCEPT"))
                .andExpect(jsonPath("$[3].actor").value("carol"));

        // 裁决后放行成功
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedCount").value(1));
    }

    @Test
    void conflictingDecisionsStayPendingUntilTieBreakThenRejectExcludesAndRecomputes() throws Exception {
        createCertAndBatch();
        long outlierId = submitOutlierReading("RD-1", "10.9", "alice");
        // 正常读数两条，用于验证 REJECT 后统计重算
        for (String key : new String[]{"RD-2", "RD-3"}) {
            mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"REQ-R-%s","readingKey":"%s",
                                     "measuredAt":"2026-06-01T00:00:00Z","value":"10.1","submittedBy":"alice"}
                                    """.formatted(key, key)))
                    .andExpect(status().isCreated());
        }

        // 结论相反：保持待仲裁，批次继续阻断
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-1", "bob", "ACCEPT", "可接受", 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-2", "carol", "REJECT", null, 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validAcceptCount").value(1))
                .andExpect(jsonPath("$.validRejectCount").value(1))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.actual").value(1));

        // 第三名复核人打破平局：REJECT 裁决，同事务排除读数并重算统计
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-3", "dave", "REJECT", null, 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validRejectCount").value(2))
                .andExpect(jsonPath("$.outlierStatus").value("REJECTED"));

        // 读数已排除，统计重算：纳入 2 条正常读数，均值 10.1
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.readings[0].excluded").value(true))
                .andExpect(jsonPath("$.latestStats.includedCount").value(2))
                .andExpect(jsonPath("$.latestStats.excludedCount").value(1))
                .andExpect(jsonPath("$.latestStats.meanValue").value("10.1"))
                .andExpect(jsonPath("$.latestStats.trigger").value("VERDICT_REJECT"));

        // 数据库层面核对：排除标记与重算记录真实落库
        Boolean excluded = jdbc.queryForObject(
                "SELECT excluded FROM batch_reading WHERE reading_key = 'RD-1'", Boolean.class);
        org.junit.jupiter.api.Assertions.assertTrue(excluded);
        Integer recomputeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_stats_record WHERE trigger_event = 'VERDICT_REJECT'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, recomputeCount);

        // 无未裁决离群：放行成功，快照封存排除后统计
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedCount").value(2))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10.1"));
    }
    @Test
    void reviewerValidation() throws Exception {
        createCertAndBatch();
        long outlierId = submitOutlierReading("RD-1", "10.6", "alice");

        // ACCEPT 必须填写理由
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-1", "bob", "ACCEPT", null, 1, 1)))
                .andExpect(status().isBadRequest());
        // 复核人不能与读数提交人相同
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-2", "alice", "ACCEPT", "自查", 1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));
        // 非法结论
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-3", "bob", "MAYBE", null, 1, 1)))
                .andExpect(status().isBadRequest());
        // 离群记录不存在
        mvc.perform(post("/api/outliers/{id}/reviews", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-4", "bob", "ACCEPT", "理由", 1, 1)))
                .andExpect(status().isNotFound());

        // 同一复核人重复有效复核 → 409
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-5", "bob", "ACCEPT", "首次", 1, 1)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-6", "bob", "REJECT", null, 1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));

        // 已裁决后再复核 → 409
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-7", "carol", "ACCEPT", "同意", 1, 1)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-8", "dave", "ACCEPT", "迟到", 1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLIER_ALREADY_DECIDED"));
    }

    @Test
    void staleRevisionReturns410AndMarksReviewStale() throws Exception {
        createCertAndBatch();
        long outlierId = submitOutlierReading("RD-1", "10.6", "alice");

        // 引用错误的测量修订版本 → 410，复核落库 STALE
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-1", "bob", "ACCEPT", "旧版本", 99, 1)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW"));

        String status = jdbc.queryForObject(
                "SELECT status FROM outlier_review WHERE reviewer = 'bob'", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("STALE", status);
        mvc.perform(get("/api/batches/{key}/audits", "B-1"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].eventType").value("REVIEW_STALE"))
                .andExpect(jsonPath("$[1].actor").value("bob"));

        // STALE 复核不计入法定人数，也不阻塞同一复核人提交有效复核
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-2", "bob", "ACCEPT", "有效复核", 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validAcceptCount").value(1));
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-3", "carol", "ACCEPT", "同意", 1, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));
    }

    @Test
    void revokedCertificateReturns410AndMarksReviewStale() throws Exception {
        createCertAndBatch();
        long outlierId = submitOutlierReading("RD-1", "10.6", "alice");

        // 撤销证书后复核：证书失效 → 410 且复核落库 STALE
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REQ-RV-1", "bob", "ACCEPT", "证书已撤销", 1, 1)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW"));

        String status = jdbc.queryForObject(
                "SELECT status FROM outlier_review WHERE reviewer = 'bob'", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("STALE", status);
        // 离群仍未裁决，批次继续阻断
        mvc.perform(get("/api/batches/{key}", "B-1"))
                .andExpect(jsonPath("$.outliers[0].status").value("PENDING"));
    }
}