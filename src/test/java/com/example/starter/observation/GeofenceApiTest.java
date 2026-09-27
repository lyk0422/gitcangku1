package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测地理围栏 API 测试：围栏配置校验、批量提交围栏匹配与整批回滚、
 * 人工接受、幂等重放及明细/历史/诊断查询（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeofenceApiTest {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2027-01-01T00:00:00Z";
    private static final String INSIDE_TIME = "2026-06-15T10:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM geofence_submission");
        jdbcTemplate.update("DELETE FROM geofence");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    // ---------- 请求构造辅助 ----------

    private ResultActions createGeofence(String requestId, String fenceId, String siteId,
                                         String minLat, String maxLat, String minLon, String maxLon,
                                         String from, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("operator", "ops-1");
        body.put("minLat", new BigDecimal(minLat));
        body.put("maxLat", new BigDecimal(maxLat));
        body.put("minLon", new BigDecimal(minLon));
        body.put("maxLon", new BigDecimal(maxLon));
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        return mockMvc.perform(post("/api/geofences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createObservation(String requestId, String observationId,
                                            String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String submissionId, String siteId, String observationId,
                                     int baseVersion, String location, String reading, String note,
                                     String latitude, String longitude, String observedAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("submissionId", submissionId);
        item.put("siteId", siteId);
        item.put("observationId", observationId);
        item.put("baseVersion", baseVersion);
        item.put("location", location);
        item.put("reading", reading);
        item.put("note", note);
        item.put("latitude", new BigDecimal(latitude));
        item.put("longitude", new BigDecimal(longitude));
        item.put("observedAtUtc", observedAt);
        return item;
    }

    private ResultActions submitBatch(String requestId, String operator,
                                      List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/geofence-submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions accept(String submissionId, String requestId,
                                 String operator, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/geofence-submissions/{id}/accept", submissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void createStandardFence() throws Exception {
        createGeofence("req-f1", "fence-1", "site-1", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
    }

    private void createStandardObservation() throws Exception {
        createObservation("req-o1", "obs-1", "地点A", "1.0", "初始备注").andExpect(status().isCreated());
    }

    private long submissionCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM geofence_submission", Long.class);
        return count == null ? 0 : count;
    }

    // ---------- 围栏配置 ----------

    @Test
    void createGeofenceReturnsCreatedAndIsQueryable() throws Exception {
        createStandardFence();

        mockMvc.perform(get("/api/geofences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceId").value("fence-1"))
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.minLat").value(30))
                .andExpect(jsonPath("$.maxLat").value(40))
                .andExpect(jsonPath("$.minLon").value(120))
                .andExpect(jsonPath("$.maxLon").value(130))
                .andExpect(jsonPath("$.effectiveFromUtc").value(FROM))
                .andExpect(jsonPath("$.effectiveToUtc").value(TO))
                .andExpect(jsonPath("$.operator").value("ops-1"))
                .andExpect(jsonPath("$.createdAtUtc").exists());

        mockMvc.perform(get("/api/sites/{siteId}/geofences", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fenceId").value("fence-1"));
    }

    @Test
    void createGeofenceWithInvalidCoordinatesReturns422() throws Exception {
        // 纬度越界
        createGeofence("req-g1", "fence-bad-1", "site-1", "-91", "40", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        createGeofence("req-g2", "fence-bad-2", "site-1", "30", "90.5", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        // 经度越界
        createGeofence("req-g3", "fence-bad-3", "site-1", "30", "40", "-180.5", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        createGeofence("req-g4", "fence-bad-4", "site-1", "30", "40", "120", "181", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        // 边界倒置
        createGeofence("req-g5", "fence-bad-5", "site-1", "40", "30", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        createGeofence("req-g6", "fence-bad-6", "site-1", "30", "40", "130", "120", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        // 生效区间非法（左闭右开，起点必须早于终点）
        createGeofence("req-g7", "fence-bad-7", "site-1", "30", "40", "120", "130", TO, FROM)
                .andExpect(status().isUnprocessableEntity());
        createGeofence("req-g8", "fence-bad-8", "site-1", "30", "40", "120", "130", FROM, FROM)
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM geofence", Long.class)).isZero();
    }

    @Test
    void overlappingEffectiveGeofenceReturns409ButAdjacentIsAllowed() throws Exception {
        createStandardFence();
        // 区间重叠（左闭右开语义下 [2026-06-01, 2026-08-01) 与 [2026-01-01, 2027-01-01) 相交）
        createGeofence("req-f2", "fence-2", "site-1", "31", "39", "121", "129",
                "2026-06-01T00:00:00Z", "2026-08-01T00:00:00Z")
                .andExpect(status().isConflict());
        // 相邻区间 [2027-01-01, 2028-01-01)：起点等于既有终点，左闭右开不重叠
        createGeofence("req-f3", "fence-3", "site-1", "31", "39", "121", "129",
                "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z")
                .andExpect(status().isCreated());
        // 其他站点相同区间不冲突
        createGeofence("req-f4", "fence-4", "site-2", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateFenceIdReturns409() throws Exception {
        createStandardFence();
        createGeofence("req-f9", "fence-1", "site-9", "0", "1", "0", "1", FROM, TO)
                .andExpect(status().isConflict());
    }

    @Test
    void geofenceRequestIdReplayAndFailureDoesNotOccupyKey() throws Exception {
        createStandardFence();
        // 同键同参重放：返回首次完整响应快照
        MvcResult first = mockMvc.perform(get("/api/geofences/{id}", "fence-1")).andReturn();
        createGeofence("req-f1", "fence-1", "site-1", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceId").value("fence-1"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM geofence", Long.class)).isEqualTo(1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 同键异参：409
        createGeofence("req-f1", "fence-1", "site-1", "31", "40", "120", "130", FROM, TO)
                .andExpect(status().isConflict());

        // 失败不占键：422 失败后同键换合法参数成功
        createGeofence("req-g10", "fence-bad", "site-1", "-91", "40", "120", "130", FROM, TO)
                .andExpect(status().isUnprocessableEntity());
        createGeofence("req-g10", "fence-ok", "site-9", "0", "1", "0", "1", FROM, TO)
                .andExpect(status().isCreated());
    }

    // ---------- 批量提交与围栏匹配 ----------

    @Test
    void submissionInsideFenceIsAcceptedAndMerged() throws Exception {
        createStandardFence();
        createStandardObservation();

        submitBatch("req-b1", "field-1", List.of(
                item("sub-1", "site-1", "obs-1", 1, "地点B", "2.5", "围栏内提交",
                        "31.2", "121.4", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.geofenceRejected").value(0))
                .andExpect(jsonPath("$.items[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[0].mergeVersion").value(2))
                .andExpect(jsonPath("$.items[0].observedAtUtc").value(INSIDE_TIME))
                .andExpect(jsonPath("$.items[0].submittedAtUtc").exists());

        // 自动合并已生效
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("地点B"))
                .andExpect(jsonPath("$.reading").value("2.5"));
    }

    @Test
    void submissionOutsideFenceIsRejectedButEvidenceKept() throws Exception {
        createStandardFence();
        createStandardObservation();

        submitBatch("req-b2", "field-1", List.of(
                item("sub-2", "site-1", "obs-1", 1, "地点C", "3.0", "围栏外提交",
                        "50", "121.4", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.geofenceRejected").value(1))
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[0].mergeVersion").doesNotExist());

        // 不参与自动合并：观测记录保持原状
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("地点A"));

        // 原始证据完整保留
        mockMvc.perform(get("/api/geofence-submissions/{id}", "sub-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(50))
                .andExpect(jsonPath("$.longitude").value(121.4))
                .andExpect(jsonPath("$.candidateLocation").value("地点C"))
                .andExpect(jsonPath("$.observedAtUtc").value(INSIDE_TIME))
                .andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(jsonPath("$.decisionReason").doesNotExist());
    }

    @Test
    void submissionWithoutEffectiveFenceIsAccepted() throws Exception {
        createStandardFence();
        createStandardObservation();

        // 站点无生效围栏（采集时刻早于围栏生效起点）
        submitBatch("req-b3", "field-1", List.of(
                item("sub-3", "site-1", "obs-1", 1, "地点D", "4.0", "围栏生效前",
                        "50", "50", "2025-12-31T23:59:59Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").doesNotExist());
    }

    @Test
    void effectiveIntervalIsLeftClosedRightOpen() throws Exception {
        createStandardFence();
        createStandardObservation();

        // 采集时刻等于生效起点（闭区间）：围栏生效，点（50, 50）在围栏外 → 拒绝
        submitBatch("req-b4", "field-1", List.of(
                item("sub-4", "site-1", "obs-1", 1, "地点E", "5.0", "起点边界",
                        "50", "50", FROM)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"));

        // 采集时刻等于生效终点（开区间）：围栏不生效 → 接受
        submitBatch("req-b5", "field-1", List.of(
                item("sub-5", "site-1", "obs-1", 1, "地点F", "6.0", "终点边界",
                        "50", "50", TO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").doesNotExist());
    }

    @Test
    void invalidItemCoordinateRollsBackWholeBatch() throws Exception {
        createStandardFence();
        createStandardObservation();

        // 混合站点批量：第二条纬度越界 → 整批 422，第一条也不生效
        submitBatch("req-b6", "field-1", List.of(
                item("sub-6a", "site-1", "obs-1", 1, "地点G", "7.0", "正常", "31", "121", INSIDE_TIME),
                item("sub-6b", "site-2", "obs-1", 1, "地点H", "8.0", "越界", "91", "121", INSIDE_TIME)))
                .andExpect(status().isUnprocessableEntity());

        // 无半成品：观测未变、无提交记录
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(submissionCount()).isZero();

        // 失败不占键：修正参数后同键成功
        submitBatch("req-b6", "field-1", List.of(
                item("sub-6c", "site-1", "obs-1", 1, "地点G", "7.0", "正常", "31", "121", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    void mergeConflictRollsBackWholeBatch() throws Exception {
        createStandardFence();
        createStandardObservation();

        // 两条明细基于同一基线改同一字段：第二条重算冲突 → 整批 409 回滚
        submitBatch("req-b7", "field-1", List.of(
                item("sub-7a", "site-1", "obs-1", 1, "地点I", "1.0", "初始备注", "31", "121", INSIDE_TIME),
                item("sub-7b", "site-1", "obs-1", 1, "地点J", "1.0", "初始备注", "32", "122", INSIDE_TIME)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("地点A"));
        assertThat(submissionCount()).isZero();
    }

    @Test
    void duplicateSubmissionIdInBatchReturns409() throws Exception {
        createStandardFence();
        createStandardObservation();

        submitBatch("req-b8", "field-1", List.of(
                item("sub-8", "site-1", "obs-1", 1, "地点K", "1.0", "备注", "31", "121", INSIDE_TIME),
                item("sub-8", "site-1", "obs-1", 1, "地点L", "1.0", "备注", "32", "122", INSIDE_TIME)))
                .andExpect(status().isConflict());
        assertThat(submissionCount()).isZero();
    }

    @Test
    void batchRequestIdReplayReturnsFirstSnapshot() throws Exception {
        createStandardFence();
        createStandardObservation();

        MvcResult first = submitBatch("req-b9", "field-1", List.of(
                item("sub-9", "site-1", "obs-1", 1, "地点M", "9.0", "备注", "31", "121", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andReturn();
        // 同键同参重放：返回首次完整响应快照，状态不重复推进
        mockMvc.perform(post("/api/geofence-submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LinkedHashMap<>(Map.of(
                        "requestId", "req-b9",
                        "operator", "field-1",
                        "items", List.of(item("sub-9", "site-1", "obs-1", 1, "地点M", "9.0", "备注",
                                "31", "121", INSIDE_TIME)))))))
                .andExpect(status().isCreated())
                .andExpect(content().string(first.getResponse().getContentAsString()));
        assertThat(submissionCount()).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参：409
        submitBatch("req-b9", "field-1", List.of(
                item("sub-9x", "site-1", "obs-1", 1, "地点N", "9.0", "备注", "31", "121", INSIDE_TIME)))
                .andExpect(status().isConflict());
    }

    // ---------- 人工接受 ----------

    @Test
    void manualAcceptRejectedSubmissionMergesWithReason() throws Exception {
        createStandardFence();
        createStandardObservation();
        submitBatch("req-b10", "field-1", List.of(
                item("sub-10", "site-1", "obs-1", 1, "地点O", "10.0", "围栏外", "50", "50", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"));

        accept("sub-10", "req-a1", "supervisor-1", "现场复核确认设备位置无误")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUALLY_ACCEPTED"))
                .andExpect(jsonPath("$.matchResult").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value("supervisor-1"))
                .andExpect(jsonPath("$.decisionReason").value("现场复核确认设备位置无误"))
                .andExpect(jsonPath("$.decidedAtUtc").exists())
                .andExpect(jsonPath("$.mergeVersion").value(2))
                // 原始证据不可变
                .andExpect(jsonPath("$.latitude").value(50))
                .andExpect(jsonPath("$.observedAtUtc").value(INSIDE_TIME));

        // 接受后合并生效
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("地点O"));
    }

    @Test
    void manualAcceptRequiresReasonAndRejectedStatus() throws Exception {
        createStandardFence();
        createStandardObservation();
        // 围栏内明细只改备注、围栏外明细只改地点：后续人工接受时不产生字段冲突
        submitBatch("req-b11", "field-1", List.of(
                item("sub-11a", "site-1", "obs-1", 1, "地点A", "1.0", "围栏内备注", "31", "121", INSIDE_TIME),
                item("sub-11b", "site-1", "obs-1", 1, "地点Q", "1.0", "初始备注", "50", "50", INSIDE_TIME)))
                .andExpect(status().isCreated());

        // 理由缺失：400
        accept("sub-11b", "req-a2", "supervisor-1", "").andExpect(status().isBadRequest());
        // 非被拒提交不可人工接受：409
        accept("sub-11a", "req-a3", "supervisor-1", "重复确认").andExpect(status().isConflict());
        // 不存在的提交：404
        accept("sub-x", "req-a4", "supervisor-1", "理由").andExpect(status().isNotFound());

        // 正常接受后重复接受（异键）：409；同键重放：200
        accept("sub-11b", "req-a5", "supervisor-1", "现场复核").andExpect(status().isOk());
        accept("sub-11b", "req-a6", "supervisor-2", "再次接受").andExpect(status().isConflict());
        accept("sub-11b", "req-a5", "supervisor-1", "现场复核")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUALLY_ACCEPTED"));
    }

    @Test
    void manualAcceptWithMergeConflictReturns409AndKeepsRejected() throws Exception {
        createStandardFence();
        createStandardObservation();
        submitBatch("req-b12", "field-1", List.of(
                item("sub-12", "site-1", "obs-1", 1, "地点R", "1.0", "初始备注", "50", "50", INSIDE_TIME)))
                .andExpect(status().isCreated());
        // 接受前当前版本已被其他提交推进且同字段改动 → 接受时重算冲突
        submitBatch("req-b13", "field-1", List.of(
                item("sub-12x", "site-1", "obs-1", 1, "地点S", "1.0", "初始备注", "31", "121", INSIDE_TIME)))
                .andExpect(status().isCreated());

        accept("sub-12", "req-a7", "supervisor-1", "尝试接受")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        // 冲突失败不改变状态：仍为被拒，可查询
        mockMvc.perform(get("/api/geofence-submissions/{id}", "sub-12"))
                .andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.decidedBy").doesNotExist());
    }

    // ---------- 历史 / 诊断 / 不可变性 ----------

    @Test
    void historyAndDiagnosticsAreStableAndAccurate() throws Exception {
        createStandardFence();
        createStandardObservation();
        // 围栏内明细只改备注、围栏外明细只改地点：人工接受时不产生字段冲突
        submitBatch("req-b14", "field-1", List.of(
                item("sub-14a", "site-1", "obs-1", 1, "地点A", "1.0", "备注T", "31", "121", INSIDE_TIME),
                item("sub-14b", "site-1", "obs-1", 1, "地点U", "1.0", "初始备注", "50", "50", INSIDE_TIME)))
                .andExpect(status().isCreated());
        accept("sub-14b", "req-a8", "supervisor-1", "复核通过").andExpect(status().isOk());

        // 历史：稳定排序（受理时刻相同则按提交标识）
        mockMvc.perform(get("/api/sites/{siteId}/geofence-submissions", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].submissionId").value("sub-14a"))
                .andExpect(jsonPath("$[1].submissionId").value("sub-14b"))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].status").value("MANUALLY_ACCEPTED"));

        // 诊断：数量为实际值
        mockMvc.perform(get("/api/sites/{siteId}/geofence-diagnostics", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.geofenceRejected").value(0))
                .andExpect(jsonPath("$.manuallyAccepted").value(1));
    }

    @Test
    void laterFenceChangesDoNotRecomputeHistoricalSubmissions() throws Exception {
        createStandardObservation();
        // 无围栏时提交被接受
        submitBatch("req-b15", "field-1", List.of(
                item("sub-15", "site-1", "obs-1", 1, "地点V", "1.0", "初始备注", "31", "121", INSIDE_TIME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").doesNotExist());

        // 事后配置覆盖该采集时刻的围栏：历史接受结果与匹配结果不重算
        createStandardFence();
        mockMvc.perform(get("/api/geofence-submissions/{id}", "sub-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceId").doesNotExist());
    }

    @Test
    void getUnknownResourcesReturn404() throws Exception {
        mockMvc.perform(get("/api/geofences/{id}", "fence-x")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofence-submissions/{id}", "sub-x")).andExpect(status().isNotFound());
    }
}
