package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.calibration.service.CalibrationClock;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校准离群复核主流程与失败分支（真实 H2 内存库 MODE=MySQL）：
 * 自动离群检测、目标区间端点、两人同议成裁/相反应待仲裁/第三人打破、
 * ACCEPT 保留/REJECT 同事务排除并重算、放行 422 阻断与一致集合、
 * STALE 410、后续修订、不可变封存与只读语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierFlowH2Test {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalibrationClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_event");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_revision");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        clock.reset();
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void createBatch(String key, String instrument, String target, String tol, String by)
            throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"%s","instrumentId":"%s","targetValue":"%s",
                                 "tolerance":"%s","createdBy":"%s"}
                                """.formatted(key, instrument, target, tol, by)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchKey").value(key))
                .andExpect(jsonPath("$.currentRevision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private String submitReading(String batch, String key, String reading, String by) throws Exception {
        return mvc.perform(post("/api/batches/{batch}/readings", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"%s","submittedBy":"%s"}
                                """.formatted(key, reading, by)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String outlierKey(String batch) throws Exception {
        String body = mvc.perform(get("/api/batches/{batch}", batch)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.outliers[0].outlierKey");
    }

    private String review(String outlier, String reviewer, String decision, String reason) throws Exception {
        return mvc.perform(post("/api/outliers/{outlier}/reviews", outlier)
                        .header("X-Actor-Id", reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"%s","reason":"%s"}
                                """.formatted(decision, reason)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void toleranceRangeIsClosedClosedAndOutlierIsAutoDetectedAndBlocksRelease() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-1", "INS-1", "5", "1", "zoe");

        // 端点包含：[4,6] 内不算离群
        submitReading("B-1", "M-LO", "4.000000", "alice");
        submitReading("B-1", "M-HI", "6.000000", "alice");
        // 超出上限 0.000001 → 离群（差额=2.000001，超出量=1.000001）
        submitReading("B-1", "M-OUT", "7.000001", "alice");
        // 低于下限 → 离群，负差额
        submitReading("B-1", "M-LOW", "3", "alice");

        mvc.perform(get("/api/batches/{batch}", "B-1"))
                .andExpect(jsonPath("$.readings.length()").value(4))
                .andExpect(jsonPath("$.outliers.length()").value(2))
                .andExpect(jsonPath("$.statistics.pendingOutlierCount").value(2))
                .andExpect(jsonPath("$.statistics.requiredPendingOutliers").value(0))
                .andExpect(jsonPath("$.statistics.pendingOutlierGap").value(2))
                .andExpect(jsonPath("$.statistics.releaseBlocked").value(true))
                .andExpect(jsonPath("$.outliers[0].status").value("PENDING"))
                .andExpect(jsonPath("$.outliers[0].decidedAt").doesNotExist());

        // 低于目标的离群：有符号差额与超出量
        String body = mvc.perform(get("/api/batches/{batch}", "B-1")).andReturn().getResponse().getContentAsString();
        String lowOutlier = JsonPath.read(body, "$.outliers[1].outlierKey");
        mvc.perform(get("/api/outliers/{outlier}", lowOutlier))
                .andExpect(jsonPath("$.deviation").value("-2"))
                .andExpect(jsonPath("$.excess").value("1"))
                .andExpect(jsonPath("$.lowerLimit").value("4"))
                .andExpect(jsonPath("$.upperLimit").value("6"));
    }

    @Test
    void releaseBlocked422UntilTwoAcceptsThenReleasedAtomically() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-2", "INS-1", "5", "1", "zoe");
        submitReading("B-2", "M-1", "5", "alice");
        submitReading("B-2", "M-2", "4.5", "alice");
        submitReading("B-2", "M-3", "7", "alice");
        String outlier = outlierKey("B-2");

        // 放行被阻断：422 且无半成品
        mvc.perform(post("/api/batches/{batch}/release", "B-2").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTLIERS_UNRESOLVED"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 第一人 ACCEPT：仍待裁决，放行继续 422
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"within operational margin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.activeAccepts").value(1));
        mvc.perform(post("/api/batches/{batch}/release", "B-2").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());

        // 第二人同议 ACCEPT：裁决形成，读数保留
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"agree\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.activeAccepts").value(2))
                .andExpect(jsonPath("$.decidedAt").isString());

        mvc.perform(get("/api/measurements/{key}", "M-3"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.excluded").value(false));

        // 放行成功：三条读数同一 releaseBatchId 原子放行
        mvc.perform(post("/api/batches/{batch}/release", "B-2").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.released.length()").value(3))
                .andExpect(jsonPath("$.activeCount").value(3));

        mvc.perform(get("/api/batches/{batch}", "B-2"))
                .andExpect(jsonPath("$.batch.status").value("RELEASED"))
                .andExpect(jsonPath("$.revision.releasedAt").isString());
        Integer releaseRows = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT batch_id) FROM release_record", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, releaseRows, "同一批共享一个放行批次 ID");
        org.junit.jupiter.api.Assertions.assertEquals(3,
                jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 事件流水齐全且按序号升序：1 修订 + 1 检测 + 2 复核 + 1 裁决 + 1 放行 = 6 条
        mvc.perform(get("/api/batches/{batch}", "B-2"))
                .andExpect(jsonPath("$.events.length()").value(6))
                .andExpect(jsonPath("$.events[0].eventType").value("REVISION_CREATED"))
                .andExpect(jsonPath("$.events[1].eventType").value("OUTLIER_DETECTED"))
                .andExpect(jsonPath("$.events[?(@.eventType=='REVIEW')]")
                        .value(org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.events[?(@.eventType=='VERDICT')]")
                        .value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.events[5].eventType").value("RELEASE"));

        // 放行后再写读数被拒；已放行批次可创建后续修订
        mvc.perform(post("/api/batches/{b}/readings", "B-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-X\",\"measuredAt\":\"2026-06-01T00:00:00Z\","
                                + "\"reading\":\"5\",\"submittedBy\":\"alice\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_REVISION_RELEASED"));
        mvc.perform(post("/api/batches/{b}/revisions", "B-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetValue\":\"5.2\",\"tolerance\":\"0.5\","
                                + "\"reason\":\"new outlier fact found after release\",\"createdBy\":\"zoe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.reason").value("new outlier fact found after release"));
        mvc.perform(get("/api/batches/{batch}", "B-2"))
                .andExpect(jsonPath("$.batch.status").value("OPEN"))
                .andExpect(jsonPath("$.batch.currentRevision").value(2))
                .andExpect(jsonPath("$.revisions.length()").value(2));

        // 后续修订不改写旧放行快照：旧修订 releasedAt 保留，批次放行历史仍在
        Integer oldReleaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "JOIN measurement_batch b ON b.id = m.batch_id WHERE b.batch_key = 'B-2'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(3, oldReleaseRows);
    }

    @Test
    void twoRejectsExcludeReadingRecomputeAndReleaseRemaining() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-3", "INS-1", "5", "1", "zoe");
        submitReading("B-3", "M-IN", "5", "alice");
        submitReading("B-3", "M-OUT", "8", "alice");
        String outlier = outlierKey("B-3");

        // 唯一离群读数 M-OUT=8；两人同议 REJECT 后在同一事务排除并重算
        review(outlier, "bob", "REJECT", "probe unstable");
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 同事务排除 + 重算：提交 2、排除 1、活动 1，均值=5（保留 M-IN）
        mvc.perform(get("/api/batches/{batch}", "B-3"))
                .andExpect(jsonPath("$.statistics.submittedTotal").value(2))
                .andExpect(jsonPath("$.statistics.excludedCount").value(1))
                .andExpect(jsonPath("$.statistics.activeCount").value(1))
                .andExpect(jsonPath("$.statistics.activeMean").value("5"))
                .andExpect(jsonPath("$.statistics.activeMin").value("5"))
                .andExpect(jsonPath("$.statistics.activeMax").value("5"))
                .andExpect(jsonPath("$.statistics.pendingOutlierCount").value(0));
        mvc.perform(get("/api/measurements/{key}", "M-OUT"))
                .andExpect(jsonPath("$.status").value("EXCLUDED"))
                .andExpect(jsonPath("$.excluded").value(true));
        mvc.perform(get("/api/batches/{batch}", "B-3"))
                .andExpect(jsonPath("$.events[?(@.eventType=='RECOMPUTE')]")
                        .value(org.hamcrest.Matchers.hasSize(1)));

        // 重算后无未裁决离群：仅放行保留的读数 M-IN，已排除的 M-OUT 不能被放行
        mvc.perform(post("/api/batches/{batch}/release", "B-3").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released.length()").value(1))
                .andExpect(jsonPath("$.released[0]").value("M-IN"));
        mvc.perform(get("/api/measurements/{key}", "M-OUT"))
                .andExpect(jsonPath("$.status").value("EXCLUDED"));

        // 已排除读数不能再复核：其离群记录已是 REJECTED
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLIER_ALREADY_DECIDED"));
    }

    @Test
    void splitReviewsKeepPendingArbitrationAndThirdReviewerFormsVerdict() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-4", "INS-1", "5", "1", "zoe");
        submitReading("B-4", "M-1", "5", "alice");
        submitReading("B-4", "M-2", "7", "alice");
        String outlier = outlierKey("B-4");

        review(outlier, "bob", "ACCEPT", "keep");
        review(outlier, "carol", "REJECT", "drop");
        mvc.perform(get("/api/outliers/{o}", outlier))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.activeAccepts").value(1))
                .andExpect(jsonPath("$.activeRejects").value(1));
        // 待仲裁期间批次继续阻断
        mvc.perform(post("/api/batches/{batch}/release", "B-4").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());

        // 第三名不同复核人同议 REJECT → REJECTED 成裁
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"tie break drop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        // 放行人 dave 已复核但不是提交人，仍可放行（复核角色与放行角色不互斥）
        mvc.perform(post("/api/batches/{batch}/release", "B-4").header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value("M-1"));
    }

    @Test
    void staleReferenceRevisionReturns410AndDoesNotCount() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-5", "INS-1", "5", "1", "zoe");
        submitReading("B-5", "M-1", "7", "alice");
        String outlier = outlierKey("B-5");

        // 复核引用一个与当前修订不一致的版本 → 410，复核落盘 STALE
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"stale ref\","
                                + "\"measurementRevision\":99}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.activeAccepts").value(0))
                .andExpect(jsonPath("$.staleReasons[0]").value("MEASUREMENT_REVISION_CHANGED"))
                .andExpect(jsonPath("$.reviews[0].stale").value(true));

        // STALE 不占复核人名额：bob 用当前版本再次复核仍允许
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"current ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews[1].stale").value(false));
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void revokedCertificateMakesReviewStale410() throws Exception {
        long certId = createCert("INS-1", "1", "0");
        createBatch("B-6", "INS-1", "5", "1", "zoe");
        submitReading("B-6", "M-1", "7", "alice");
        String outlier = outlierKey("B-6");

        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"cert gone\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.staleReasons[0]").value("CERTIFICATE_REVOKED_OR_CHANGED"))
                .andExpect(jsonPath("$.reviews[0].stale").value(true));

        // 证书撤销后放行同样被阻断（422），且没有半成品
        mvc.perform(post("/api/batches/{batch}/release", "B-6").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void reviewFailureBranches() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-7", "INS-1", "5", "1", "zoe");
        submitReading("B-7", "M-1", "7", "alice");
        String outlier = outlierKey("B-7");

        // 复核人=提交人 → 409，不写复核
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"self\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_SUBMITTER"));
        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"no actor\"}"))
                .andExpect(status().isBadRequest());
        // 缺少理由 / 非法结论 / 引用错误证书版本 → 400/400/410
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"x\",\"certificateId\":999999}"))
                .andExpect(status().isGone());

        // 落盘的只有一条：引用错误证书版本的 STALE 复核；参数/角色类失败均不产生半成品
        mvc.perform(get("/api/outliers/{o}", outlier))
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].stale").value(true))
                .andExpect(jsonPath("$.activeAccepts").value(0));

        // 离群不存在 → 404
        mvc.perform(post("/api/outliers/NO-SUCH/reviews").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseGuardsAndReadOnlyDetailDoNotMutate() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-8", "INS-1", "5", "1", "zoe");
        submitReading("B-8", "M-1", "5", "alice");

        // 放行人是读数提交人 → 422（无待裁决离群的前提下）
        mvc.perform(post("/api/batches/{batch}/release", "B-8").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASER_IS_SUBMITTER"));

        // 显式键集合与活动集合不一致 → 400
        mvc.perform(post("/api/batches/{batch}/release", "B-8").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"measurementKeys\":[\"M-1\",\"M-X\"]}"))
                .andExpect(status().isBadRequest());

        // 放行成功
        mvc.perform(post("/api/batches/{batch}/release", "B-8").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"measurementKeys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
        // 重复放行 → 409；未放行先修订 → 409（在另一批次验证修订门禁）
        mvc.perform(post("/api/batches/{batch}/release", "B-8").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));

        createBatch("B-8B", "INS-1", "5", "1", "zoe");
        submitReading("B-8B", "M-9", "5", "alice");
        mvc.perform(post("/api/batches/{b}/revisions", "B-8B").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetValue\":\"5\",\"tolerance\":\"1\",\"reason\":\"not released yet\","
                                + "\"createdBy\":\"zoe\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_NOT_RELEASED"));

        // 只读接口不改变状态：连续两次读取事件数一致
        Integer eventsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_event e JOIN measurement_batch b ON b.id = e.batch_id "
                        + "WHERE b.batch_key = 'B-8'", Integer.class);
        mvc.perform(get("/api/batches/{batch}", "B-8")).andExpect(status().isOk());
        mvc.perform(get("/api/batches/{batch}", "B-8")).andExpect(status().isOk());
        Integer eventsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_event e JOIN measurement_batch b ON b.id = e.batch_id "
                        + "WHERE b.batch_key = 'B-8'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(eventsBefore, eventsAfter);
    }

    @Test
    void fixedClockStampsEventsWithControlledUtcInstant() throws Exception {
        Instant fixed = Instant.parse("2026-07-08T09:10:11.123456Z");
        clock.setClock(Clock.fixed(fixed, java.time.ZoneOffset.UTC));
        createCert("INS-1", "1", "0");
        createBatch("B-9", "INS-1", "5", "1", "zoe");
        submitReading("B-9", "M-1", "7", "alice");

        mvc.perform(get("/api/batches/{batch}", "B-9"))
                .andExpect(jsonPath("$.events[0].occurredAt").value("2026-07-08T09:10:11.123456Z"))
                .andExpect(jsonPath("$.events[1].occurredAt").value("2026-07-08T09:10:11.123456Z"))
                .andExpect(jsonPath("$.outliers[0].detectedAt").value("2026-07-08T09:10:11.123456Z"));
    }

    @Test
    void sealedOutlierEvidenceSurvivesLaterRevision() throws Exception {
        createCert("INS-1", "1", "0");
        createBatch("B-10", "INS-1", "5", "1", "zoe");
        submitReading("B-10", "M-1", "5", "alice");
        submitReading("B-10", "M-2", "7", "alice");
        String outlier = outlierKey("B-10");
        review(outlier, "bob", "ACCEPT", "r1");
        review(outlier, "carol", "ACCEPT", "r2");
        mvc.perform(post("/api/batches/{batch}/release", "B-10").header("X-Actor-Id", "dave")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/batches/{b}/revisions", "B-10").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetValue\":\"9\",\"tolerance\":\"0.1\",\"reason\":\"new fact\",\"createdBy\":\"zoe\"}"))
                .andExpect(status().isCreated());

        // 旧离群证据的封存字段不被后续修订改写
        mvc.perform(get("/api/outliers/{o}", outlier))
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.targetValue").value("5"))
                .andExpect(jsonPath("$.tolerance").value("1"))
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.reviews.length()").value(2));
    }
}
