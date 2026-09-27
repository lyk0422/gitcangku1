package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校准离群复核法定人数与放行门禁测试：
 * 主流程（检测→复核→裁决→放行）、失败分支（409/410/422/400）、
 * 幂等（同参重放、异参 409、失败不占键）、修订版本与快照、并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierQuorumApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM batch_stats_history");
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

    private void createBatch(String batchKey, String instrument, long certId,
                             String target, String deviation) throws Exception {
        mvc.perform(post("/api/outlier-batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"CB-%s","batchKey":"%s","instrumentId":"%s",
                                 "certificateId":%d,"targetValue":"%s","allowedDeviation":"%s"}
                                """.formatted(batchKey, batchKey, instrument, certId, target, deviation)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchKey").value(batchKey))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private ResultActions submitReading(String batchKey, String requestId, String readingKey,
                                        String value, String by) throws Exception {
        return mvc.perform(post("/api/outlier-batches/{key}/readings", batchKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","readingKey":"%s","value":"%s","submittedBy":"%s"}
                        """.formatted(requestId, readingKey, value, by)));
    }

    private ResultActions review(long outlierId, String requestId, String reviewer, String decision,
                                 String reason, int revision, long certId) throws Exception {
        String reasonPart = reason == null ? "" : "\"reason\":\"%s\",".formatted(reason);
        return mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","reviewer":"%s","decision":"%s",%s
                         "batchRevision":%d,"certificateId":%d}
                        """.formatted(requestId, reviewer, decision, reasonPart, revision, certId)));
    }

    private ResultActions release(String batchKey, String requestId, String actor) throws Exception {
        return mvc.perform(post("/api/outlier-batches/{key}/release", batchKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"%s\"}".formatted(requestId)));
    }

    @Test
    void acceptQuorumMainFlowReleasesBatch() throws Exception {
        long certId = createCert("INS-Q1");
        createBatch("B-Q1", "INS-Q1", certId, "10", "0.5");

        // 正常读数：偏差 0.2 ≤ 0.5，不离群
        submitReading("B-Q1", "RD-1", "R-1", "10.2", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlier").value(false))
                .andExpect(jsonPath("$.outlierId").value(nullValue()))
                .andExpect(jsonPath("$.deviation").value("0.2"));

        // 离群读数：偏差 0.8 > 0.5，自动生成 OUTLIER 记录并封存证据
        String body = submitReading("B-Q1", "RD-2", "R-2", "10.8", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlier").value(true))
                .andExpect(jsonPath("$.deviation").value("0.8"))
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 离群明细：封存目标值、阈值、读数与角色
        mvc.perform(get("/api/outliers/{id}", outlierId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchKey").value("B-Q1"))
                .andExpect(jsonPath("$.targetValue").value("10"))
                .andExpect(jsonPath("$.allowedDeviation").value("0.5"))
                .andExpect(jsonPath("$.readingValue").value("10.8"))
                .andExpect(jsonPath("$.deviation").value("0.8"))
                .andExpect(jsonPath("$.submittedBy").value("alice"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.verdict").value(nullValue()))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.requiredQuorum").value(2))
                .andExpect(jsonPath("$.reviews.length()").value(0));

        // 未裁决离群值阻断放行：422 携带实际值/要求值/差额
        release("B-Q1", "REL-BLOCK", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTLIERS_PENDING"))
                .andExpect(jsonPath("$.pendingOutliers").value(1))
                .andExpect(jsonPath("$.requiredPending").value(0))
                .andExpect(jsonPath("$.remaining").value(1));

        // 第一票 ACCEPT：未达法定人数，仍待裁决
        review(outlierId, "RV-1", "bob", "ACCEPT", "复核通过", 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.acceptCount").value(1))
                .andExpect(jsonPath("$.requiredQuorum").value(2))
                .andExpect(jsonPath("$.verdict").value(nullValue()))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));

        // 第二票 ACCEPT：形成有效裁决，保留读数
        review(outlierId, "RV-2", "carol", "ACCEPT", "同意保留", 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptCount").value(2))
                .andExpect(jsonPath("$.verdict").value("ACCEPT"))
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));

        // 裁决后放行成功，快照封存修订版本与统计
        release("B-Q1", "REL-1", "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.includedCount").value(2))
                .andExpect(jsonPath("$.meanValue").value("10.5"));

        mvc.perform(get("/api/outlier-batches/{key}/history", "B-Q1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].revision").value(1))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("dave"))
                .andExpect(jsonPath("$.releases[0].includedCount").value(2));
    }

    @Test
    void rejectQuorumExcludesReadingAndRecomputesStats() throws Exception {
        long certId = createCert("INS-Q2");
        createBatch("B-Q2", "INS-Q2", certId, "10", "0.5");
        submitReading("B-Q2", "RD-1", "R-1", "10", "alice").andExpect(status().isCreated());
        String body = submitReading("B-Q2", "RD-2", "R-2", "11", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlier").value(true))
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        review(outlierId, "RV-1", "bob", "REJECT", null, 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rejectCount").value(1))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        review(outlierId, "RV-2", "carol", "REJECT", null, 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rejectCount").value(2))
                .andExpect(jsonPath("$.verdict").value("REJECT"))
                .andExpect(jsonPath("$.outlierStatus").value("REJECTED"));

        // 同事务排除读数并重算：均值只剩 10，统计历史含 EXCLUDE 记录
        mvc.perform(get("/api/outlier-batches/{key}", "B-Q2"))
                .andExpect(jsonPath("$.readingCount").value(2))
                .andExpect(jsonPath("$.includedCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10"))
                .andExpect(jsonPath("$.pendingOutliers").value(0));
        mvc.perform(get("/api/outlier-batches/{key}/history", "B-Q2"))
                .andExpect(jsonPath("$.stats[?(@.reason=='EXCLUDE')].includedCount").value(
                        org.hamcrest.Matchers.contains(1)));

        // 裁决后无未裁决离群值，放行成功且快照只含计入读数
        release("B-Q2", "REL-1", "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10"));
    }

    @Test
    void conflictingReviewsStayPendingUntilTieBreak() throws Exception {
        long certId = createCert("INS-Q3");
        createBatch("B-Q3", "INS-Q3", certId, "10", "0.5");
        String body = submitReading("B-Q3", "RD-1", "R-1", "11", "alice")
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        review(outlierId, "RV-1", "bob", "ACCEPT", "保留", 1, certId).andExpect(status().isCreated());
        // 结论相反：保持待仲裁，批次继续阻断
        review(outlierId, "RV-2", "carol", "REJECT", null, 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptCount").value(1))
                .andExpect(jsonPath("$.rejectCount").value(1))
                .andExpect(jsonPath("$.verdict").value(nullValue()))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));
        release("B-Q3", "REL-BLOCK", "dave")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.pendingOutliers").value(1));

        // 第三票打破平局：两票 ACCEPT 形成有效裁决
        review(outlierId, "RV-3", "dave", "ACCEPT", "确认保留", 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("ACCEPT"))
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));
        release("B-Q3", "REL-1", "erin").andExpect(status().isOk());
    }

    @Test
    void reviewValidationFailures() throws Exception {
        long certId = createCert("INS-Q4");
        createBatch("B-Q4", "INS-Q4", certId, "10", "0.5");
        String body = submitReading("B-Q4", "RD-1", "R-1", "11", "alice")
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 复核人 = 读数提交人 → 409
        review(outlierId, "RV-SAME", "alice", "ACCEPT", "自审", 1, certId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));

        // ACCEPT 缺少理由 → 400
        review(outlierId, "RV-NOREASON", "bob", "ACCEPT", null, 1, certId)
                .andExpect(status().isBadRequest());

        // 非法结论 → 400
        review(outlierId, "RV-BADDEC", "bob", "MAYBE", null, 1, certId)
                .andExpect(status().isBadRequest());

        // 不存在的离群记录 → 404
        review(999999L, "RV-404", "bob", "ACCEPT", "x", 1, certId)
                .andExpect(status().isNotFound());

        // 有效复核后同一复核人再次复核 → 409
        review(outlierId, "RV-1", "bob", "ACCEPT", "保留", 1, certId).andExpect(status().isCreated());
        review(outlierId, "RV-2", "bob", "ACCEPT", "再次", 1, certId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));

        // 裁决后再次复核 → 409
        review(outlierId, "RV-3", "carol", "ACCEPT", "同意", 1, certId).andExpect(status().isCreated());
        review(outlierId, "RV-4", "dave", "ACCEPT", "迟到", 1, certId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLIER_RESOLVED"));

        // 失败复核不写入复核记录：仅 bob/carol 两条有效复核
        mvc.perform(get("/api/outliers/{id}", outlierId))
                .andExpect(jsonPath("$.reviews.length()").value(2));
    }

    @Test
    void staleReviewOnVersionChangeOrRevokedCertificateReturns410() throws Exception {
        long certId = createCert("INS-Q5");
        createBatch("B-Q5", "INS-Q5", certId, "10", "0.5");
        String body = submitReading("B-Q5", "RD-1", "R-1", "11", "alice")
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 引用错误的测量修订版本 → 410，复核标记 STALE，不计入法定人数
        review(outlierId, "RV-STALE-1", "bob", "ACCEPT", "旧版本", 99, certId)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.acceptCount").value(0))
                .andExpect(jsonPath("$.outlierStatus").value("PENDING"));

        // STALE 后同一复核人可用当前版本重新复核
        review(outlierId, "RV-1", "bob", "ACCEPT", "当前版本", 1, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.acceptCount").value(1));

        // 证书撤销（证书失效）→ 410 STALE
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        review(outlierId, "RV-STALE-2", "carol", "ACCEPT", "证书已撤销", 1, certId)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("STALE"));

        // 诊断查询：两条 STALE + 一条 EFFECTIVE，全部保留为不可变证据
        mvc.perform(get("/api/outliers/{id}", outlierId))
                .andExpect(jsonPath("$.reviews.length()").value(3))
                .andExpect(jsonPath("$.reviews[0].status").value("STALE"))
                .andExpect(jsonPath("$.reviews[1].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.reviews[2].status").value("STALE"))
                .andExpect(jsonPath("$.acceptCount").value(1));
    }

    @Test
    void idempotencyReplayConflictAndFailureNotOccupying() throws Exception {
        long certId = createCert("INS-Q6");
        createBatch("B-Q6", "INS-Q6", certId, "10", "0.5");

        // 同 requestId 同参重放：返回首次结果，不产生第二条读数
        String first = submitReading("B-Q6", "RD-IDEM", "R-1", "10.1", "alice")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = submitReading("B-Q6", "RD-IDEM", "R-1", "10.1", "alice")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "同参重放必须返回首次结果");
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_reading", Integer.class);
        assertEquals(1, readingCount);

        // 同 requestId 异参 → 409
        submitReading("B-Q6", "RD-IDEM", "R-2", "10.2", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 失败不占键：重复读数键 409 后，同一 requestId 可正常执行新请求
        submitReading("B-Q6", "RD-FAIL", "R-1", "10.3", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_READING_KEY"));
        submitReading("B-Q6", "RD-FAIL", "R-3", "10.3", "alice")
                .andExpect(status().isCreated());

        // 批次创建幂等：同键重复创建 409 不占 requestId
        mvc.perform(post("/api/outlier-batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"CB-DUP","batchKey":"B-Q6","instrumentId":"INS-Q6",
                                 "certificateId":%d,"targetValue":"10","allowedDeviation":"0.5"}
                                """.formatted(certId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BATCH_KEY"));
        mvc.perform(post("/api/outlier-batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"CB-DUP","batchKey":"B-Q6-NEW","instrumentId":"INS-Q6",
                                 "certificateId":%d,"targetValue":"10","allowedDeviation":"0.5"}
                                """.formatted(certId)))
                .andExpect(status().isCreated());
    }

    @Test
    void releasedBatchNewReadingCreatesNextRevisionAndKeepsSnapshot() throws Exception {
        long certId = createCert("INS-Q7");
        createBatch("B-Q7", "INS-Q7", certId, "10", "0.5");
        submitReading("B-Q7", "RD-1", "R-1", "10", "alice").andExpect(status().isCreated());
        release("B-Q7", "REL-1", "carol").andExpect(status().isOk());

        // 重复放行 → 409
        release("B-Q7", "REL-2", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));

        // 已放行批次新增离群事实：创建后续修订版本，批次回到 OPEN 并继续阻断
        submitReading("B-Q7", "RD-2", "R-2", "12", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchRevision").value(2))
                .andExpect(jsonPath("$.outlier").value(true));
        mvc.perform(get("/api/outlier-batches/{key}", "B-Q7"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pendingOutliers").value(1));

        // 旧放行快照不改写：仍是修订 1、均值 10
        mvc.perform(get("/api/outlier-batches/{key}/history", "B-Q7"))
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].revision").value(1))
                .andExpect(jsonPath("$.releases[0].meanValue").value("10"));

        // 引用旧修订版本的复核 → 410 STALE
        String outliers = mvc.perform(get("/api/outlier-batches/{key}/outliers", "B-Q7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(outliers, "$[0].id")).longValue();
        review(outlierId, "RV-OLD", "bob", "ACCEPT", "旧修订", 1, certId)
                .andExpect(status().isGone());
        review(outlierId, "RV-1", "bob", "ACCEPT", "保留", 2, certId).andExpect(status().isCreated());
        review(outlierId, "RV-2", "carol", "ACCEPT", "保留", 2, certId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierStatus").value("ACCEPTED"));
        release("B-Q7", "REL-3", "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void boundaryDeviationEqualToThresholdIsNotOutlier() throws Exception {
        long certId = createCert("INS-Q8");
        createBatch("B-Q8", "INS-Q8", certId, "10", "0.5");
        // 偏差恰好等于允许偏差（端点含内）：不离群
        submitReading("B-Q8", "RD-1", "R-1", "10.5", "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlier").value(false))
                .andExpect(jsonPath("$.deviation").value("0.5"));
        release("B-Q8", "REL-1", "carol").andExpect(status().isOk());
    }

    @Test
    void releaseUnderRevokedCertificateRejected() throws Exception {
        long certId = createCert("INS-Q9");
        createBatch("B-Q9", "INS-Q9", certId, "10", "0.5");
        submitReading("B-Q9", "RD-1", "R-1", "10", "alice").andExpect(status().isCreated());
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        release("B-Q9", "REL-1", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
        // 证书撤销后提交读数 → 409
        submitReading("B-Q9", "RD-2", "R-2", "10.1", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void unknownResourcesReturn404() throws Exception {
        mvc.perform(get("/api/outlier-batches/{key}", "NO-SUCH")).andExpect(status().isNotFound());
        mvc.perform(get("/api/outlier-batches/{key}/history", "NO-SUCH")).andExpect(status().isNotFound());
        mvc.perform(get("/api/outlier-batches/{key}/outliers", "NO-SUCH")).andExpect(status().isNotFound());
        mvc.perform(get("/api/outliers/{id}", 424242L)).andExpect(status().isNotFound());
        submitReading("NO-SUCH", "RD-X", "R-1", "1", "alice").andExpect(status().isNotFound());
        release("NO-SUCH", "REL-X", "carol").andExpect(status().isNotFound());
    }

    @Test
    void concurrentReviewsAndReleaseAreSerialized() throws Exception {
        long certId = createCert("INS-Q10");
        createBatch("B-Q10", "INS-Q10", certId, "10", "0.5");
        String body = submitReading("B-Q10", "RD-1", "R-1", "11", "alice")
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        // 两名复核人并发 ACCEPT：均成功且裁决恰好形成一次
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> r1 = pool.submit(() -> {
            start.await();
            return review(outlierId, "RV-C1", "bob", "ACCEPT", "保留", 1, certId)
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> r2 = pool.submit(() -> {
            start.await();
            return review(outlierId, "RV-C2", "carol", "ACCEPT", "保留", 1, certId)
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertEquals(201, r1.get(30, TimeUnit.SECONDS));
        assertEquals(201, r2.get(30, TimeUnit.SECONDS));

        mvc.perform(get("/api/outliers/{id}", outlierId))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptCount").value(2))
                .andExpect(jsonPath("$.reviews.length()").value(2));

        // 裁决后并发放行：恰好一次成功
        int threads = 4;
        CountDownLatch start2 = new CountDownLatch(1);
        List<Future<Integer>> releases = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            releases.add(pool.submit(() -> {
                start2.await();
                return release("B-Q10", "REL-C" + index, "dave")
                        .andReturn().getResponse().getStatus();
            }));
        }
        start2.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : releases) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发放行必须恰好一次成功");
        assertEquals(threads - 1, conflict.get());
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        assertEquals(1, snapshots, "只允许写入一条放行快照");
    }

    @Test
    void concurrentSameReviewerOnlyOneEffectiveReview() throws Exception {
        long certId = createCert("INS-Q11");
        createBatch("B-Q11", "INS-Q11", certId, "10", "0.5");
        String body = submitReading("B-Q11", "RD-1", "R-1", "11", "alice")
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return review(outlierId, "RV-DUP-" + index, "bob", "ACCEPT", "保留", 1, certId)
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "同一复核人并发复核必须恰好一条有效");
        assertEquals(threads - 1, conflict.get());
        Integer effective = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE status = 'EFFECTIVE'", Integer.class);
        assertEquals(1, effective);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM outlier_review", Integer.class) == 1,
                "失败复核不得写入复核记录");
    }
}
