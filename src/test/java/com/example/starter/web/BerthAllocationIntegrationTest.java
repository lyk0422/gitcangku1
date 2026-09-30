package com.example.starter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 H2 内存库（MODE=MySQL）的端到端测试：
 * 主流程、422 失败分支、可区分拒绝原因、潮高版本发布、待复核释放占用、
 * 已开始记录快照保留、人工复核与 requestId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BerthAllocationIntegrationTest {

    private static final String APPLY_URL = "/api/berths/allocations";

    private static final String PUBLISH_URL = "/api/berths/tide-versions";

    private static final String REVIEW_URL = "/api/berths/allocations/review";

    /** 固定业务时钟：2026-09-30 12:00，此时 10-01 08:00 的靠泊尚未开始。 */
    private static final LocalDateTime CLOCK_BEFORE = LocalDateTime.of(2026, 9, 30, 12, 0);

    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        setClock(CLOCK_BEFORE);
        resetTideBaseline();
    }

    @AfterEach
    void tearDown() {
        com.example.starter.support.BusinessTime.resetClock();
        jdbcTemplate.update("DELETE FROM berth_allocation");
    }

    /**
     * 将潮高数据恢复为合成初始版本 1（08:00-12:00 潮高 0.7 米），保证用例与测试类间隔离。
     */
    private void resetTideBaseline() {
        jdbcTemplate.update("DELETE FROM berth_allocation");
        jdbcTemplate.update("DELETE FROM tide_window");
        jdbcTemplate.update("DELETE FROM tide_version");
        jdbcTemplate.update("INSERT INTO tide_version (id, version_no, published_at, remark) "
                + "VALUES (1, 1, ?, '初始版本')", java.sql.Timestamp.valueOf(CLOCK_BEFORE));
        jdbcTemplate.execute("ALTER TABLE tide_version ALTER COLUMN id RESTART WITH 2");
        jdbcTemplate.batchUpdate(
                "INSERT INTO tide_window (version_id, start_time, end_time, tide_height_m) "
                        + "VALUES (1, ?, ?, ?)",
                List.of(
                        new Object[]{LocalDateTime.of(2026, 10, 1, 0, 0), T08, new java.math.BigDecimal("1.20")},
                        new Object[]{T08, T10, new java.math.BigDecimal("0.70")},
                        new Object[]{T10, LocalDateTime.of(2026, 10, 1, 12, 0),
                                new java.math.BigDecimal("0.70")},
                        new Object[]{LocalDateTime.of(2026, 10, 1, 12, 0),
                                LocalDateTime.of(2026, 10, 2, 0, 0),
                                new java.math.BigDecimal("1.00")}));
    }

    // ---------- 主流程 ----------

    @Test
    void allows92mDraftWith07mTideAndRecordsSnapshot() throws Exception {
        Map<String, Object> body = applyBody("req-ok", "合成船-92", "9.20", "0.20", T08, T10);

        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                // 可用深度 8.8 + 0.7 = 9.5；需要 9.2 + 0.2 = 9.4；余量 0.1。
                .andExpect(jsonPath("$.minTideHeightM").value(0.70))
                .andExpect(jsonPath("$.availableDepthM").value(9.50))
                .andExpect(jsonPath("$.requiredDepthM").value(9.40))
                .andExpect(jsonPath("$.clearanceM").value(0.10))
                .andExpect(jsonPath("$.tideVersionId").value(1))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
    }

    @Test
    void detailAndTimelineShowVersionTideClearanceAndAllocation() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                .content(objectMapper.writeValueAsString(
                        applyBody("req-view", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());

        mockMvc.perform(get(APPLY_URL + "/req-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.berthCode").value("B-01"))
                .andExpect(jsonPath("$.staticDepthM").value(8.80))
                .andExpect(jsonPath("$.tideVersionId").value(1));

        mockMvc.perform(get("/api/berths/B-01/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staticDepthM").value(8.80))
                .andExpect(jsonPath("$.activeTideVersionNo").value(1))
                .andExpect(jsonPath("$.allocations[0].requestId").value("req-view"))
                .andExpect(jsonPath("$.allocations[0].clearanceM").value(0.10));
    }

    // ---------- 422 失败分支 ----------

    @Test
    void returns422ForNonPositiveInterval() throws Exception {
        Map<String, Object> body = applyBody("req-bad-interval", "合成船", "9.20", "0.20", T08, T08);

        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NON_POSITIVE_INTERVAL"));
        assertThat(allocationCount("req-bad-interval")).isZero();
    }

    @Test
    void returns422ForIllegalDraft() throws Exception {
        Map<String, Object> body = applyBody("req-bad-draft", "合成船", "0", "0.20", T08, T10);

        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        assertThat(allocationCount("req-bad-draft")).isZero();
    }

    @Test
    void returns422WithDistinctReasonForTideGapAndLeavesNoRecord() throws Exception {
        // 2026-10-02 08:00-10:00 在版本 1 中没有任何潮高区间覆盖。
        Map<String, Object> body = applyBody("req-gap", "合成船", "9.20", "0.20",
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 10, 0));

        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_GAP"));

        // 失败请求不留下半条分配或幂等记录。
        mockMvc.perform(get(APPLY_URL + "/req-gap")).andExpect(status().isNotFound());
        assertThat(allocationCount("req-gap")).isZero();
    }

    @Test
    void returns422WhenPublishingWindowsWithGap() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionNo", 9);
        body.put("windows", List.of(
                window(LocalDateTime.of(2026, 10, 1, 0, 0), T08, "1.00"),
                window(LocalDateTime.of(2026, 10, 1, 9, 0),
                        LocalDateTime.of(2026, 10, 2, 0, 0), "1.00")));

        mockMvc.perform(post(PUBLISH_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_GAP"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM tide_version WHERE version_no = 9", Integer.class)).isZero();
    }

    // ---------- 深度不足与泊位冲突，均不占泊位 ----------

    @Test
    void rejectsForInsufficientDepthWithDistinctReasonAndBerthStaysFree() throws Exception {
        // 需要深度 9.5 + 0.2 = 9.7 > 9.5。
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-deep", "合成船-95", "9.50", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("DEPTH_INSUFFICIENT"))
                .andExpect(jsonPath("$.clearanceM").value(-0.20));

        // 被拒记录不占泊位：另一艘满足深度的船同窗口可确认。
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-next", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void rejectsForBerthConflictAndKeepsFirstOccupancy() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-first", "合成船-A", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-second", "合成船-B", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("BERTH_CONFLICT"));

        // 端点相接不算重叠：10:00-12:00 仍可确认。
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-touch", "合成船-C", "9.20", "0.20",
                                        T10, LocalDateTime.of(2026, 10, 1, 12, 0)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ---------- 潮高修订、待复核、释放占用、快照保留 ----------

    @Test
    void tideRevisionMarksUnstartedConfirmedPendingAndReleasesSlot() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-revise", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        publishVersion(2, "0.50");

        mockMvc.perform(get(APPLY_URL + "/req-revise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.rejectReason").value("DEPTH_INSUFFICIENT"))
                // 修订后校核结果。
                .andExpect(jsonPath("$.reviewTideVersionId").isNumber())
                .andExpect(jsonPath("$.reviewMinTideHeightM").value(0.50))
                .andExpect(jsonPath("$.reviewClearanceM").value(-0.10))
                // 原决策快照保留，不被自动改写。
                .andExpect(jsonPath("$.tideVersionId").value(1))
                .andExpect(jsonPath("$.minTideHeightM").value(0.70))
                .andExpect(jsonPath("$.clearanceM").value(0.10));

        // 已释放占用：另一艘在新版本下恰好合格的船（需要 9.3）可使用原窗口。
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-takeover", "合成船-91", "9.10", "0.20", T08, T10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void startedAllocationKeepsDecisionSnapshotAfterRevision() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-started", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());

        // 时钟推进到 09:00：靠泊已开始。
        setClock(LocalDateTime.of(2026, 10, 1, 9, 0));
        publishVersion(2, "0.50");

        mockMvc.perform(get(APPLY_URL + "/req-started"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tideVersionId").value(1))
                .andExpect(jsonPath("$.clearanceM").value(0.10))
                .andExpect(jsonPath("$.reviewTideVersionId").doesNotExist());
    }

    // ---------- 人工复核 ----------

    @Test
    void reviewApproveRechecksLatestVersionAndConfirms() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-review-ok", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());
        publishVersion(2, "0.50");
        // 版本 3 潮高恢复 0.7。
        publishVersion(3, "0.70");

        mockMvc.perform(post(REVIEW_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(reviewBody("req-review-ok", "APPROVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tideVersionId").value(3))
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
    }

    @Test
    void reviewApproveFailsWhenDepthStillInsufficient() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-review-fail", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());
        publishVersion(2, "0.50");

        mockMvc.perform(post(REVIEW_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(reviewBody("req-review-fail", "APPROVE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPTH_INSUFFICIENT"));

        mockMvc.perform(get(APPLY_URL + "/req-review-fail"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    void reviewRejectSetsRejectedState() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-review-rej", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());
        publishVersion(2, "0.50");

        mockMvc.perform(post(REVIEW_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(reviewBody("req-review-rej", "REJECT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.reviewCount").value(1));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysFirstResultAndDifferentParamsConflict() throws Exception {
        String json = objectMapper.writeValueAsString(
                applyBody("req-idem", "合成船-92", "9.20", "0.20", T08, T10));

        MvcResult first = mockMvc.perform(post(APPLY_URL).contentType("application/json").content(json))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post(APPLY_URL).contentType("application/json").content(json))
                .andExpect(status().isCreated())
                .andReturn();

        Map<?, ?> firstBody = objectMapper.readValue(first.getResponse().getContentAsString(), Map.class);
        Map<?, ?> secondBody = objectMapper.readValue(second.getResponse().getContentAsString(), Map.class);
        assertThat(secondBody.get("id")).isEqualTo(firstBody.get("id"));
        assertThat(allocationCount("req-idem")).isEqualTo(1);

        Map<String, Object> changed = applyBody("req-idem", "合成船-92", "9.30", "0.20", T08, T10);
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));
        assertThat(allocationCount("req-idem")).isEqualTo(1);
    }

    @Test
    void sameReviewRequestReplaysFirstDecision() throws Exception {
        mockMvc.perform(post(APPLY_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                applyBody("req-idem-review", "合成船-92", "9.20", "0.20", T08, T10))))
                .andExpect(status().isCreated());
        publishVersion(2, "0.50");

        String rejectJson = objectMapper.writeValueAsString(reviewBody("req-idem-review", "REJECT"));
        mockMvc.perform(post(REVIEW_URL).contentType("application/json").content(rejectJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(post(REVIEW_URL).contentType("application/json").content(rejectJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewCount").value(1));
    }

    // ---------- 辅助方法 ----------

    private void publishVersion(int versionNo, String tideHeight) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionNo", versionNo);
        body.put("windows", List.of(window(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 2, 0, 0), tideHeight)));
        mockMvc.perform(post(PUBLISH_URL).contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$").value(versionNo));
    }

    private Map<String, Object> applyBody(String requestId, String vessel, String draft,
                                          String margin, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("vesselName", vessel);
        body.put("draftM", new java.math.BigDecimal(draft));
        body.put("safetyMarginM", new java.math.BigDecimal(margin));
        body.put("berthCode", "B-01");
        body.put("startTime", fmt(start));
        body.put("endTime", fmt(end));
        return body;
    }

    private String fmt(LocalDateTime time) {
        return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private Map<String, Object> window(LocalDateTime start, LocalDateTime end, String tideHeight) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startTime", fmt(start));
        window.put("endTime", fmt(end));
        window.put("tideHeightM", new java.math.BigDecimal(tideHeight));
        return window;
    }

    private Map<String, Object> reviewBody(String requestId, String action) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("action", action);
        return body;
    }

    private int allocationCount(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM berth_allocation WHERE request_id = ?",
                Integer.class, requestId);
    }

    private void setClock(LocalDateTime time) {
        com.example.starter.support.BusinessTime.setClock(
                Clock.fixed(Instant.ofEpochMilli(
                        time.toInstant(ZoneOffset.ofHours(8)).toEpochMilli()),
                        com.example.starter.support.BusinessTime.ZONE));
    }
}
