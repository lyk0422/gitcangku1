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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线观测批量提交与人工裁决 API 测试：围栏匹配主流程、整批回滚、区间边界、
 * 历史结果不被围栏变更重算、人工裁决与幂等边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionApiTest {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2027-01-01T00:00:00Z";
    private static final String COLLECTED = "2026-06-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM submission_adjudication");
        jdbcTemplate.update("DELETE FROM observation_submission");
        jdbcTemplate.update("DELETE FROM geofence");
        jdbcTemplate.update("DELETE FROM geofence_site_lock");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    // ---------- 请求构造辅助 ----------

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

    private ResultActions createFence(String requestId, String fenceId, String siteId,
                                      String minLat, String maxLat, String minLon, String maxLon,
                                      String from, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("minLatitude", new BigDecimal(minLat));
        body.put("maxLatitude", new BigDecimal(maxLat));
        body.put("minLongitude", new BigDecimal(minLon));
        body.put("maxLongitude", new BigDecimal(maxLon));
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        body.put("operator", "tester");
        return mockMvc.perform(post("/api/geofences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String submissionId, String siteId, String observationId,
                                     int baseVersion, String location, String reading, String note,
                                     String latitude, String longitude, String collectedAt) {
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
        item.put("collectedAtUtc", collectedAt);
        return item;
    }

    private ResultActions submitBatch(String requestId, Map<String, Object>... items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("items", List.of(items));
        return mockMvc.perform(post("/api/observation-submissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String submissionId, String requestId, String adjudicationId,
                                     String decision, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("decision", decision);
        body.put("reason", reason);
        body.put("operator", "reviewer");
        return mockMvc.perform(post("/api/observation-submissions/{id}/adjudications", submissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getSubmission(String submissionId) throws Exception {
        return mockMvc.perform(get("/api/observation-submissions/{id}", submissionId));
    }

    private void createSiteAFence(String requestId) throws Exception {
        createFence(requestId, "fence-1", "site-A", "30", "40", "120", "130", FROM, TO)
                .andExpect(status().isCreated());
    }

    private int submissionCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_submission", Integer.class);
        return count == null ? 0 : count;
    }

    private int adjudicationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submission_adjudication", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：围栏内接受 ----------

    @Test
    void acceptedInsideFenceMergesObservation() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "初始备注").andExpect(status().isCreated());

        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏内", "31.5", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-b1"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(0))
                .andExpect(jsonPath("$.items[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("INSIDE"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[0].matchedFenceVersion").value(1))
                .andExpect(jsonPath("$.items[0].mergedVersion").value(2));

        // 接受的提交参与自动合并：观测记录推进到版本 2
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点A东"))
                .andExpect(jsonPath("$.reading").value("2.5"));

        // 提交详情：原始坐标、采集时刻、匹配结果完整可查
        getSubmission("sub-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchResult").value("INSIDE"))
                .andExpect(jsonPath("$.latitude").value(31.5))
                .andExpect(jsonPath("$.longitude").value(121))
                .andExpect(jsonPath("$.collectedAtUtc").value(COLLECTED))
                .andExpect(jsonPath("$.submittedAtUtc").exists())
                .andExpect(jsonPath("$.matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.mergedVersion").value(2));
    }

    @Test
    void rejectedOutsideFenceKeepsEvidenceWithoutMerge() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "初始备注").andExpect(status().isCreated());

        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("OUTSIDE"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[0].mergedVersion").doesNotExist());

        // 不参与自动合并：观测记录保持版本 1
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));
        // 原始证据保留
        getSubmission("sub-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.latitude").value(50))
                .andExpect(jsonPath("$.note").value("围栏外"))
                .andExpect(jsonPath("$.mergedVersion").doesNotExist());
    }

    @Test
    void rejectedWhenNoEffectiveFence() throws Exception {
        createObservation("req-o1", "obs-1", "站点B", "1.0", "备注").andExpect(status().isCreated());

        submitBatch("req-b1", item("sub-1", "site-B", "obs-1", 1, "站点B", "2.0", "无围栏", "31.5", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("NO_FENCE"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").doesNotExist())
                .andExpect(jsonPath("$.items[0].matchedFenceVersion").doesNotExist());

        getSubmission("sub-1")
                .andExpect(jsonPath("$.matchResult").value("NO_FENCE"))
                .andExpect(jsonPath("$.matchedFenceId").doesNotExist());
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void mixedSitesBatchProcessedInSubmissionOrder() throws Exception {
        createSiteAFence("req-f0");
        createFence("req-f1", "fence-c", "site-C", "0", "10", "0", "10", FROM, TO)
                .andExpect(status().isCreated());
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点C", "3.0", "备注").andExpect(status().isCreated());

        submitBatch("req-b1",
                item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED),
                item("sub-2", "site-C", "obs-2", 1, "站点C", "4.0", "围栏外", "50", "50", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[1].submissionId").value("sub-2"))
                .andExpect(jsonPath("$.items[1].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[1].matchedFenceId").value("fence-c"));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 整批回滚 ----------

    @Test
    void formatErrorRollsBackWholeBatch() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点A", "3.0", "备注").andExpect(status().isCreated());

        // 第二条纬度越界：整批 422 回滚，第一条的合并不生效
        submitBatch("req-b1",
                item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED),
                item("sub-2", "site-A", "obs-2", 1, "站点A西", "4.0", "纬度越界", "91", "121", COLLECTED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("91"))
                .andExpect(jsonPath("$.requiredValue").value("[-90, 90]"));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(submissionCount()).isZero();
        // 失败不占键：request_log 只有围栏与两条观测创建记录
        assertThat(requestLogCount()).isEqualTo(3);

        // 同一 requestId 修正参数后整批成功
        submitBatch("req-b1",
                item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED),
                item("sub-2", "site-A", "obs-2", 1, "站点A西", "4.0", "围栏内", "35", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2));
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void mergeConflictRollsBackWholeBatch() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 服务端前进到版本 2：地点改为站点B
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-m1");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 离线端基于版本 1 把地点改为站点C：三方合并冲突，整批 409 回滚
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点C", "1.0", "备注", "31.5", "121", COLLECTED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        assertThat(submissionCount()).isZero();
        getSubmission("sub-1").andExpect(status().isNotFound());
    }

    @Test
    void duplicateSubmissionIdWithinBatchReturns422() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        submitBatch("req-b1",
                item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED),
                item("sub-1", "site-A", "obs-1", 1, "站点A西", "3.0", "重复标识", "32", "121", COLLECTED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actualValue").value("sub-1"));
        assertThat(submissionCount()).isZero();
    }

    @Test
    void duplicateSubmissionIdAcrossBatchesReturns409() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点A", "3.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED))
                .andExpect(status().isOk());

        // 另一批复用 sub-1：409 且整批回滚，sub-2 不写入
        submitBatch("req-b2",
                item("sub-2", "site-A", "obs-2", 1, "站点A西", "4.0", "围栏内", "35", "121", COLLECTED),
                item("sub-1", "site-A", "obs-1", 2, "站点A", "2.0", "复用标识", "31.5", "121", COLLECTED))
                .andExpect(status().isConflict());
        assertThat(submissionCount()).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 生效区间边界（UTC，左闭右开） ----------

    @Test
    void effectiveIntervalIsLeftClosedRightOpen() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o3", "obs-3", "站点A", "1.0", "备注").andExpect(status().isCreated());

        submitBatch("req-b1",
                // 左闭：采集时刻等于生效起点，命中
                item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "左边界", "31.5", "121", FROM),
                // 右开：采集时刻等于生效终点，不命中
                item("sub-2", "site-A", "obs-2", 1, "站点A西", "3.0", "右边界", "31.5", "121", TO),
                // 生效前一刻，不命中
                item("sub-3", "site-A", "obs-3", 1, "站点A南", "4.0", "生效前", "31.5", "121",
                        "2025-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("INSIDE"))
                .andExpect(jsonPath("$.items[1].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[1].matchResult").value("NO_FENCE"))
                .andExpect(jsonPath("$.items[2].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[2].matchResult").value("NO_FENCE"));
    }

    // ---------- 围栏变更不重算历史 ----------

    @Test
    void fenceUpdateDoesNotRecomputeHistory() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.0", "围栏内", "31.5", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));

        // 变更围栏：新版本矩形不包含原采集点
        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("requestId", "req-f1");
        updateBody.put("expectedVersion", 1);
        updateBody.put("minLatitude", new BigDecimal("50"));
        updateBody.put("maxLatitude", new BigDecimal("60"));
        updateBody.put("minLongitude", new BigDecimal("120"));
        updateBody.put("maxLongitude", new BigDecimal("130"));
        updateBody.put("effectiveFromUtc", FROM);
        updateBody.put("effectiveToUtc", TO);
        updateBody.put("operator", "tester");
        mockMvc.perform(post("/api/geofences/{id}/updates", "fence-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(2));

        // 历史接受结果不重算：状态、匹配围栏版本、原始坐标保持不变
        getSubmission("sub-1")
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceVersion").value(1))
                .andExpect(jsonPath("$.latitude").value(31.5))
                .andExpect(jsonPath("$.mergedVersion").value(2));
        // 新提交按新版本围栏判定：同坐标落在版本 2 矩形外
        submitBatch("req-b2", item("sub-2", "site-A", "obs-2", 1, "站点A", "2.0", "新围栏外", "31.5", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].matchResult").value("OUTSIDE"))
                .andExpect(jsonPath("$.items[0].matchedFenceVersion").value(2));
    }

    // ---------- 人工裁决 ----------

    @Test
    void adjudicateAcceptsRejectedSubmissionWithReason() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"));

        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "现场核实坐标漂移，数据有效")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.submissionId").value("sub-1"))
                .andExpect(jsonPath("$.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.reason").value("现场核实坐标漂移，数据有效"))
                .andExpect(jsonPath("$.operator").value("reviewer"))
                .andExpect(jsonPath("$.status").value("MANUAL_ACCEPTED"))
                .andExpect(jsonPath("$.mergedVersion").value(2))
                .andExpect(jsonPath("$.adjudicatedAtUtc").exists());

        // 裁决接受后内容合并进观测记录
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点A东"));
        // 提交状态推进，但匹配结果与原始坐标不可变
        getSubmission("sub-1")
                .andExpect(jsonPath("$.status").value("MANUAL_ACCEPTED"))
                .andExpect(jsonPath("$.matchResult").value("OUTSIDE"))
                .andExpect(jsonPath("$.latitude").value(50))
                .andExpect(jsonPath("$.mergedVersion").value(2));
        // 裁决历史可查
        mockMvc.perform(get("/api/observation-submissions/{id}/adjudications", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$[0].reason").value("现场核实坐标漂移，数据有效"));
    }

    @Test
    void adjudicateWithoutReasonReturns400() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk());

        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "")
                .andExpect(status().isBadRequest());
        assertThat(adjudicationCount()).isZero();
        getSubmission("sub-1").andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"));
    }

    @Test
    void adjudicateOnNonRejectedSubmissionReturns409() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏内", "31.5", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));

        // 已接受的提交不能再裁决
        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "重复裁决")
                .andExpect(status().isConflict());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void adjudicateOnMissingSubmissionReturns404() throws Exception {
        adjudicate("sub-x", "req-a1", "adj-1", "ACCEPT", "理由")
                .andExpect(status().isNotFound());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void adjudicateReplayAndConflictSemantics() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk());

        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "理由一")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergedVersion").value(2));
        // 同 requestId 同参重放：返回首次结果，不新增裁决记录
        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "理由一")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergedVersion").value(2));
        // 新 requestId 复用同一 adjudicationId 且参数一致：按原记录重放
        adjudicate("sub-1", "req-a2", "adj-1", "ACCEPT", "理由一")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"));
        // 同一 adjudicationId 改参：409
        adjudicate("sub-1", "req-a3", "adj-1", "ACCEPT", "理由二")
                .andExpect(status().isConflict());
        assertThat(adjudicationCount()).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 批量幂等 ----------

    @Test
    void batchReplayReturnsFirstResponseSnapshot() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"));
        // 裁决改变提交当前状态
        adjudicate("sub-1", "req-a1", "adj-1", "ACCEPT", "理由")
                .andExpect(status().isOk());

        // 同键同参重放：返回首次完整响应快照（仍为 GEOFENCE_REJECTED），不产生新记录
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏外", "50", "121", COLLECTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[0].mergedVersion").doesNotExist());
        assertThat(submissionCount()).isEqualTo(1);
        // 当前状态不受重放影响
        getSubmission("sub-1").andExpect(jsonPath("$.status").value("MANUAL_ACCEPTED"));
    }

    @Test
    void batchSameRequestIdWithDifferentParamsReturns409() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1", item("sub-1", "site-A", "obs-1", 1, "站点A东", "2.5", "围栏内", "31.5", "121", COLLECTED))
                .andExpect(status().isOk());
        submitBatch("req-b1", item("sub-2", "site-A", "obs-1", 2, "站点A", "2.5", "改参", "31.5", "121", COLLECTED))
                .andExpect(status().isConflict());
        assertThat(submissionCount()).isEqualTo(1);
    }

    // ---------- 查询 ----------

    @Test
    void submissionsListedInStableOrder() throws Exception {
        createSiteAFence("req-f0");
        createObservation("req-o1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-2", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o3", "obs-3", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitBatch("req-b1",
                item("sub-2", "site-A", "obs-2", 1, "站点A", "2.0", "第二", "31.5", "121", COLLECTED),
                item("sub-1", "site-A", "obs-1", 1, "站点A", "2.0", "第一", "31.5", "121", COLLECTED))
                .andExpect(status().isOk());
        submitBatch("req-b2", item("sub-3", "site-A", "obs-3", 1, "站点A", "2.0", "第三", "31.5", "121", COLLECTED))
                .andExpect(status().isOk());

        // 按提交时刻、批内顺序稳定排序：先批内顺序（sub-2, sub-1），后下一批（sub-3）
        mockMvc.perform(get("/api/sites/{siteId}/observation-submissions", "site-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].submissionId").value("sub-2"))
                .andExpect(jsonPath("$[1].submissionId").value("sub-1"))
                .andExpect(jsonPath("$[2].submissionId").value("sub-3"));
    }

    @Test
    void getMissingSubmissionReturns404() throws Exception {
        getSubmission("sub-x").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observation-submissions/{id}/adjudications", "sub-x"))
                .andExpect(status().isNotFound());
    }
}
