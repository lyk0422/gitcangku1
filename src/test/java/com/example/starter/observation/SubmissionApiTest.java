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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线观测提交（地理围栏匹配 + 自动合并）与人工裁决 API 的
 * 主流程、失败分支、事务回滚与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionApiTest {

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
        jdbcTemplate.update("DELETE FROM geofence_version");
        jdbcTemplate.update("DELETE FROM geofence_fence");
        jdbcTemplate.update("DELETE FROM geofence_site");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private void createObservation(String requestId, String observationId,
                                   String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void createFence(String requestId, String fenceId, String siteId,
                             String minLat, String maxLat, String minLon, String maxLon,
                             String fromUtc, String toUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "op-admin");
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("minLat", minLat);
        body.put("maxLat", maxLat);
        body.put("minLon", minLon);
        body.put("maxLon", maxLon);
        body.put("effectiveFromUtc", fromUtc);
        body.put("effectiveToUtc", toUtc);
        mockMvc.perform(post("/api/geofences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> item(String submissionId, String observationId, String siteId,
                                     int baseVersion, String location, String reading, String note,
                                     Object latitude, Object longitude, String capturedAtUtc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("submissionId", submissionId);
        item.put("observationId", observationId);
        item.put("siteId", siteId);
        item.put("baseVersion", baseVersion);
        item.put("location", location);
        item.put("reading", reading);
        item.put("note", note);
        item.put("latitude", latitude);
        item.put("longitude", longitude);
        item.put("capturedAtUtc", capturedAtUtc);
        return item;
    }

    private ResultActions submitBatch(String requestId, String operator,
                                      List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/submissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String submissionId, String requestId, String operator,
                                     String decision, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("decision", decision);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/submissions/{id}/adjudicate", submissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int submissionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：接受与拒绝 ----------

    @Test
    void acceptedSubmissionInsideFenceMergesIntoObservation() throws Exception {
        createObservation("req-o1", "obs-1", "站点A", "1.0", "初始备注");
        createFence("req-f1", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        submitBatch("req-b1", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点B", "2.5", "现场复核",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-b1"))
                .andExpect(jsonPath("$.operator").value("op-field"))
                .andExpect(jsonPath("$.submittedAtUtc").exists())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.results[0].matchedFenceVersion").value(1))
                .andExpect(jsonPath("$.results[0].observationVersion").value(2))
                .andExpect(jsonPath("$.results[0].latitude").value(31.5))
                .andExpect(jsonPath("$.results[0].capturedAtUtc").value("2026-09-15T08:00:00Z"));

        // 自动合并生效：观测记录版本前进
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.5"));

        // 明细查询：证据字段完整
        mockMvc.perform(get("/api/submissions/{id}", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.observationVersion").value(2));
    }

    @Test
    void rejectedSubmissionOutsideFenceKeepsEvidenceWithoutMerge() throws Exception {
        createObservation("req-o2", "obs-1", "站点A", "1.0", "初始备注");
        createFence("req-f2", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        submitBatch("req-b2", "op-field", List.of(
                item("sub-2", "obs-1", "site-1", 1, "站点B", "2.5", "围栏外采集",
                        35.0, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.results[0].matchedFenceId").doesNotExist())
                .andExpect(jsonPath("$.results[0].matchedFenceVersion").doesNotExist())
                .andExpect(jsonPath("$.results[0].observationVersion").doesNotExist());

        // 不参与自动合并：观测记录保持版本 1
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));

        // 原始证据保留：坐标与采集时刻不可变地落库
        mockMvc.perform(get("/api/submissions/{id}", "sub-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.latitude").value(35.0))
                .andExpect(jsonPath("$.longitude").value(121.5))
                .andExpect(jsonPath("$.capturedAtUtc").value("2026-09-15T08:00:00Z"))
                .andExpect(jsonPath("$.submittedAtUtc").exists());
    }

    @Test
    void capturedAtIntervalBoundariesAreLeftClosedRightOpen() throws Exception {
        createObservation("req-o3", "obs-a", "站点A", "1.0", "备注");
        createObservation("req-o4", "obs-b", "站点B", "2.0", "备注");
        createFence("req-f3", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        submitBatch("req-b3", "op-field", List.of(
                item("sub-from", "obs-a", "site-1", 1, "站点A2", "1.0", "备注",
                        31.5, 121.5, "2026-09-01T00:00:00Z"),
                item("sub-to", "obs-b", "site-1", 1, "站点B2", "2.0", "备注",
                        31.5, 121.5, "2026-10-01T00:00:00Z")))
                .andExpect(status().isOk())
                // 区间起点（左闭）：落围栏内
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"))
                // 区间终点（右开）：落围栏外
                .andExpect(jsonPath("$.results[1].matchStatus").value("GEOFENCE_REJECTED"));
    }

    @Test
    void mixedSitesBatchIsProcessedInSubmissionOrder() throws Exception {
        createObservation("req-o5", "obs-1", "站点A", "1.0", "备注");
        createObservation("req-o6", "obs-2", "站点B", "2.0", "备注");
        createFence("req-f4", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        // 混合站点：site-1 有围栏且落内，site-2 无围栏
        submitBatch("req-b4", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z"),
                item("sub-2", "obs-2", "site-2", 1, "站点B2", "2.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].submissionId").value("sub-2"))
                .andExpect(jsonPath("$.results[1].matchStatus").value("GEOFENCE_REJECTED"));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点A2"));
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 失败分支：整批回滚 ----------

    @Test
    void invalidCoordinateInAnyItemRollsBackWholeBatch() throws Exception {
        createObservation("req-o7", "obs-1", "站点A", "1.0", "备注");
        createObservation("req-o8", "obs-2", "站点B", "2.0", "备注");
        createFence("req-f5", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                31.5, 121.5, "2026-09-15T08:00:00Z"));
        items.add(item("sub-2", "obs-2", "site-1", 1, "站点B2", "2.5", "备注",
                91.0, 121.5, "2026-09-15T08:00:00Z"));
        submitBatch("req-b5", "op-field", items)
                .andExpect(status().isUnprocessableEntity());

        // 整批回滚：无提交记录、批量请求不占幂等键、观测记录未被合并
        assertThat(submissionCount()).isZero();
        Integer batchLogRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-b5'", Integer.class);
        assertThat(batchLogRows).isZero();
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/submissions/{id}", "sub-1"))
                .andExpect(status().isNotFound());

        // 失败不占键：修正坐标后同一 requestId 可成功
        items.set(1, item("sub-2", "obs-2", "site-1", 1, "站点B2", "2.5", "备注",
                31.6, 121.5, "2026-09-15T08:00:00Z"));
        submitBatch("req-b5", "op-field", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].matchStatus").value("ACCEPTED"));
    }

    @Test
    void mergeConflictInBatchReturns409AndRollsBack() throws Exception {
        createObservation("req-o9", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f6", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        // 服务端当前版本前进到 2（地点改为 站点B）
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

        // 离线端基于版本 1 把地点改为第三个值：三方冲突，整批 409
        submitBatch("req-b6", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点C", "1.0", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        assertThat(submissionCount()).isZero();
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
    }

    @Test
    void submissionToMissingOrDeletedObservationFailsBatch() throws Exception {
        createObservation("req-o10", "obs-1", "站点A", "1.0", "备注");
        createObservation("req-o10b", "obs-2", "站点B", "2.0", "备注");
        createFence("req-f7", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        // 目标观测记录不存在：404，整批回滚
        submitBatch("req-b7", "op-field", List.of(
                item("sub-1", "obs-missing", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isNotFound());
        assertThat(submissionCount()).isZero();

        // 基线版本不存在：404
        submitBatch("req-b8", "op-field", List.of(
                item("sub-2", "obs-1", "site-1", 9, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isNotFound());
        assertThat(submissionCount()).isZero();

        // 目标观测记录已删除：410
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d1");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        submitBatch("req-b8b", "op-field", List.of(
                item("sub-3", "obs-2", "site-1", 1, "站点B2", "2.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isGone());
        assertThat(submissionCount()).isZero();
    }

    // ---------- 人工裁决 ----------

    private void submitRejected(String batchRequestId, String submissionId, String observationId)
            throws Exception {
        submitBatch(batchRequestId, "op-field", List.of(
                item(submissionId, observationId, "site-1", 1, "站点B", "2.5", "围栏外采集",
                        35.0, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("GEOFENCE_REJECTED"));
    }

    @Test
    void adjudicateRejectedSubmissionAcceptsAndMerges() throws Exception {
        createObservation("req-o11", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f8", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitRejected("req-b9", "sub-1", "obs-1");

        adjudicate("sub-1", "req-a1", "op-review", "ACCEPT", "现场核实，坐标漂移但数据有效")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value("sub-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.reason").value("现场核实，坐标漂移但数据有效"))
                .andExpect(jsonPath("$.operator").value("op-review"))
                .andExpect(jsonPath("$.observationVersion").value(2))
                .andExpect(jsonPath("$.decidedAtUtc").exists());

        // 裁决后候选值合并进观测记录
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.5"));

        // 原始匹配结果不可变：明细仍是 GEOFENCE_REJECTED 证据
        mockMvc.perform(get("/api/submissions/{id}", "sub-1"))
                .andExpect(jsonPath("$.matchStatus").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.latitude").value(35.0));

        // 重复裁决：409
        adjudicate("sub-1", "req-a2", "op-review", "ACCEPT", "再次裁决")
                .andExpect(status().isConflict());
    }

    @Test
    void adjudicateValidationFailures() throws Exception {
        createObservation("req-o12", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f9", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitRejected("req-b10", "sub-1", "obs-1");

        // 理由必填：空白理由 400
        adjudicate("sub-1", "req-a3", "op-review", "ACCEPT", " ")
                .andExpect(status().isBadRequest());
        // 非法裁决类型：400
        adjudicate("sub-1", "req-a4", "op-review", "REJECT", "理由")
                .andExpect(status().isBadRequest());
        // 提交不存在：404
        adjudicate("sub-missing", "req-a5", "op-review", "ACCEPT", "理由")
                .andExpect(status().isNotFound());

        // 失败不占键：修正后可成功
        adjudicate("sub-1", "req-a3", "op-review", "ACCEPT", "补充理由后接受")
                .andExpect(status().isOk());
    }

    @Test
    void adjudicateAcceptedSubmissionReturns409() throws Exception {
        createObservation("req-o13", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f10", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitBatch("req-b11", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"));

        adjudicate("sub-1", "req-a6", "op-review", "ACCEPT", "试图重复接受")
                .andExpect(status().isConflict());
    }

    @Test
    void adjudicateRequestIdReplayAndDifferentParamsReturn409() throws Exception {
        createObservation("req-o14", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f11", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitRejected("req-b12", "sub-1", "obs-1");

        String firstBody = adjudicate("sub-1", "req-a7", "op-review", "ACCEPT", "理由一")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次完整响应快照
        String replayBody = adjudicate("sub-1", "req-a7", "op-review", "ACCEPT", "理由一")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);
        // 异参复用：409
        adjudicate("sub-1", "req-a7", "op-review", "ACCEPT", "理由二")
                .andExpect(status().isConflict());
        // 重放不产生重复裁决记录
        Integer adjudications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_adjudication WHERE submission_id = 'sub-1'",
                Integer.class);
        assertThat(adjudications).isEqualTo(1);
    }

    // ---------- 历史稳定性与诊断 ----------

    @Test
    void fenceRevisionDoesNotRewriteHistoricalMatchResults() throws Exception {
        createObservation("req-o15", "obs-1", "站点A", "1.0", "备注");
        createObservation("req-o16", "obs-2", "站点B", "2.0", "备注");
        createFence("req-f12", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitBatch("req-b13", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("ACCEPTED"));

        // 围栏改版：同一坐标落在新围栏外
        Map<String, Object> reviseBody = new LinkedHashMap<>();
        reviseBody.put("requestId", "req-f13");
        reviseBody.put("operator", "op-admin");
        reviseBody.put("minLat", "40.0");
        reviseBody.put("maxLat", "41.0");
        reviseBody.put("minLon", "121.0");
        reviseBody.put("maxLon", "122.0");
        reviseBody.put("effectiveFromUtc", "2026-09-01T00:00:00Z");
        reviseBody.put("effectiveToUtc", "2026-10-01T00:00:00Z");
        mockMvc.perform(post("/api/geofences/{id}/revisions", "fence-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviseBody)))
                .andExpect(status().isOk());

        // 历史接受结果不重算：仍是 ACCEPTED，匹配版本仍是 1
        mockMvc.perform(get("/api/submissions/{id}", "sub-1"))
                .andExpect(jsonPath("$.matchStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceVersion").value(1));

        // 新提交按改版后的当前围栏匹配：同一坐标被拒
        submitBatch("req-b14", "op-field", List.of(
                item("sub-2", "obs-2", "site-1", 1, "站点B2", "2.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].matchStatus").value("GEOFENCE_REJECTED"));
    }

    @Test
    void diagnosticsReturnActualCounts() throws Exception {
        createObservation("req-o17", "obs-1", "站点A", "1.0", "备注");
        createObservation("req-o18", "obs-2", "站点B", "2.0", "备注");
        createObservation("req-o19", "obs-3", "站点C", "3.0", "备注");
        createFence("req-f14", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        submitBatch("req-b15", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z"),
                item("sub-2", "obs-2", "site-1", 1, "站点B2", "2.5", "备注",
                        35.0, 121.5, "2026-09-15T08:00:00Z"),
                item("sub-3", "obs-3", "site-1", 1, "站点C2", "3.5", "备注",
                        36.0, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk());
        adjudicate("sub-2", "req-a8", "op-review", "ACCEPT", "现场核实")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sites/{id}/submissions/diagnostics", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.totalSubmissions").value(3))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.geofenceRejectedCount").value(2))
                .andExpect(jsonPath("$.adjudicatedAcceptedCount").value(1))
                .andExpect(jsonPath("$.pendingRejectedCount").value(1));
    }

    @Test
    void submissionHistoryHasStableOrdering() throws Exception {
        createObservation("req-o20", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f15", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitBatch("req-b16", "op-field", List.of(
                item("sub-3", "obs-1", "site-1", 1, "站点A", "1.0", "备注",
                        35.0, 121.5, "2026-09-15T08:00:00Z"),
                item("sub-1", "obs-1", "site-1", 1, "站点A", "1.0", "备注",
                        35.1, 121.5, "2026-09-15T08:00:00Z"),
                item("sub-2", "obs-1", "site-1", 1, "站点A", "1.0", "备注",
                        35.2, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk());

        // 同一批次提交时刻相同：按提交标识升序，排序稳定
        mockMvc.perform(get("/api/sites/{id}/submissions", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$[1].submissionId").value("sub-2"))
                .andExpect(jsonPath("$[2].submissionId").value("sub-3"));

        mockMvc.perform(get("/api/observations/{id}/submissions", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].submissionId").value("sub-1"));
    }

    @Test
    void batchRequestIdReplayReturnsSnapshotAndDifferentParamsReturn409() throws Exception {
        createObservation("req-o21", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f16", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        List<Map<String, Object>> items = List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z"));

        String firstBody = submitBatch("req-b17", "op-field", items)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次完整响应快照，不新增提交记录与观测版本
        String replayBody = submitBatch("req-b17", "op-field", items)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);
        assertThat(submissionCount()).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2));

        // 异参复用同一 requestId：409
        submitBatch("req-b17", "op-field", List.of(
                item("sub-9", "obs-1", "site-1", 1, "站点A3", "1.6", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isConflict());
        assertThat(submissionCount()).isEqualTo(1);
    }

    @Test
    void duplicateSubmissionIdAcrossBatchesReturns409() throws Exception {
        createObservation("req-o22", "obs-1", "站点A", "1.0", "备注");
        createFence("req-f17", "fence-1", "site-1", "31.0", "32.0", "121.0", "122.0",
                "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");
        submitBatch("req-b18", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A2", "1.5", "备注",
                        31.5, 121.5, "2026-09-15T08:00:00Z")))
                .andExpect(status().isOk());

        // 不同批次复用同一 submissionId：409，且整批回滚
        submitBatch("req-b19", "op-field", List.of(
                item("sub-1", "obs-1", "site-1", 1, "站点A3", "1.6", "备注",
                        31.6, 121.6, "2026-09-15T08:00:00Z")))
                .andExpect(status().isConflict());
        assertThat(submissionCount()).isEqualTo(1);
    }
}
