package com.example.starter.maintenance;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测站维护窗口与离线合并证据标记 API 的主流程、失败分支与幂等边界测试
 * （真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceApiTest {

    /** 覆盖测试执行时刻的维护窗口区间（UTC）。 */
    private static final String WINDOW_START = "2020-01-01T00:00:00Z";
    private static final String WINDOW_END = "2030-01-01T00:00:00Z";
    /** 不覆盖测试执行时刻的窗口区间（UTC）。 */
    private static final String PAST_START = "2001-01-01T00:00:00Z";
    private static final String PAST_END = "2001-01-02T00:00:00Z";
    private static final String ACTIVATED_AT = "2000-01-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_adjudication");
        jdbcTemplate.update("DELETE FROM cluster_merge_snapshot");
        jdbcTemplate.update("DELETE FROM station_observation");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM observation_station");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    // ---------- 请求辅助 ----------

    private ResultActions registerStation(String requestId, String stationId, String activatedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stationId", stationId);
        body.put("activatedAtUtc", activatedAt);
        return mockMvc.perform(post("/api/stations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerWindow(String requestId, String stationId, String windowId,
                                         String start, String end, int version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("windowId", windowId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("windowVersion", version);
        return mockMvc.perform(post("/api/stations/{stationId}/windows", stationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions correctWindow(String requestId, String windowId, String correctionWindowId,
                                        String start, String end, int version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("correctionWindowId", correctionWindowId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("windowVersion", version);
        return mockMvc.perform(post("/api/stations/windows/{windowId}/corrections", windowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions withdrawWindow(String requestId, String windowId) throws Exception {
        return mockMvc.perform(post("/api/stations/windows/{windowId}/withdraw", windowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))));
    }

    private Map<String, Object> item(String submissionId, String clusterId, String stationId,
                                     String observedAt, String reading) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("submissionId", submissionId);
        item.put("clusterId", clusterId);
        item.put("stationId", stationId);
        item.put("observedAtUtc", observedAt);
        item.put("location", "场地-" + submissionId);
        item.put("reading", reading);
        item.put("note", "备注-" + submissionId);
        return item;
    }

    private ResultActions submitBatch(String requestId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", items);
        return mockMvc.perform(post("/api/stations/observations/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeCluster(String requestId, String clusterId) throws Exception {
        return mockMvc.perform(post("/api/stations/clusters/{clusterId}/merge", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))));
    }

    private ResultActions adjudicate(String requestId, String clusterId, String adjudicationId,
                                     String chosenSubmissionId, String reason, String adjudicator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("chosenSubmissionId", chosenSubmissionId);
        body.put("reason", reason);
        body.put("adjudicator", adjudicator);
        return mockMvc.perform(post("/api/stations/clusters/{clusterId}/adjudicate", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM station_observation", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 站点与窗口登记 ----------

    @Test
    void registerStationAndQueryDetail() throws Exception {
        registerStation("req-s1", "st-1", ACTIVATED_AT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("st-1"))
                .andExpect(jsonPath("$.activatedAtUtc").value(ACTIVATED_AT));

        mockMvc.perform(get("/api/stations/{stationId}", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("st-1"));
    }

    @Test
    void registerStationDuplicateReturns409AndUnknownStationReturns404() throws Exception {
        registerStation("req-s2", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerStation("req-s3", "st-1", ACTIVATED_AT).andExpect(status().isConflict());
        mockMvc.perform(get("/api/stations/{stationId}", "st-x")).andExpect(status().isNotFound());
    }

    @Test
    void registerWindowSuccessAndOverlapRejected() throws Exception {
        registerStation("req-w0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-w1", "st-1", "win-1", WINDOW_START, WINDOW_END, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.windowVersion").value(1))
                .andExpect(jsonPath("$.sealed").value(false));

        // 相邻区间（左闭右开，end == start 不重叠）允许登记
        registerWindow("req-w2", "st-1", "win-2", "2000-06-01T00:00:00Z", "2001-06-01T00:00:00Z", 1)
                .andExpect(status().isCreated());

        // 与 win-1 重叠：拒绝并返回冲突窗口明细
        registerWindow("req-w3", "st-1", "win-3", "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.overlappingWindowId").value("win-1"));
    }

    @Test
    void registerWindowBeforeActivationReturns400WithDeficit() throws Exception {
        registerStation("req-w4", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-w5", "st-1", "win-1", "1999-12-31T00:00:00Z", WINDOW_END, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.activatedAtUtc").value(ACTIVATED_AT))
                .andExpect(jsonPath("$.details.requestedStartUtc").value("1999-12-31T00:00:00Z"))
                .andExpect(jsonPath("$.details.deficitMillis").value(86400000));
    }

    @Test
    void registerWindowInvalidIntervalReturns400() throws Exception {
        registerStation("req-w6", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-w7", "st-1", "win-1", WINDOW_END, WINDOW_START, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.requirement").value("startUtc < endUtc"));
    }

    @Test
    void windowHistoryAndDiagnosticsAreStable() throws Exception {
        registerStation("req-d0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-d1", "st-1", "win-1", WINDOW_START, WINDOW_END, 1).andExpect(status().isCreated());
        registerWindow("req-d2", "st-1", "win-2", PAST_START, PAST_END, 2).andExpect(status().isCreated());

        mockMvc.perform(get("/api/stations/{stationId}/windows", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].windowId").value("win-2"))
                .andExpect(jsonPath("$[1].windowId").value("win-1"));

        mockMvc.perform(get("/api/stations/{stationId}/windows/diagnostics", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWindows").value(2))
                .andExpect(jsonPath("$.activeWindows").value(2))
                .andExpect(jsonPath("$.sealedWindows").value(0))
                .andExpect(jsonPath("$.withdrawnWindows").value(0));
    }

    // ---------- 批量提交与维护标记 ----------

    @Test
    void batchSubmissionMarksMaintenanceAffectedPerItem() throws Exception {
        registerStation("req-b0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerStation("req-b1", "st-2", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-b2", "st-1", "win-1", WINDOW_START, WINDOW_END, 3).andExpect(status().isCreated());

        submitBatch("req-b3", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "10.5"),
                item("sub-2", "cl-1", "st-2", "2026-09-01T01:00:00Z", "11.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$.items[0].maintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.items[0].windowId").value("win-1"))
                .andExpect(jsonPath("$.items[0].windowVersion").value(3))
                .andExpect(jsonPath("$.items[1].maintenanceFlag").value("NONE"))
                .andExpect(jsonPath("$.items[1].windowId").doesNotExist());

        mockMvc.perform(get("/api/stations/observations/{submissionId}", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.windowVersion").value(3));
    }

    @Test
    void batchSubmissionReplayReturnsSameResult() throws Exception {
        registerStation("req-r0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-r1", "st-1", "win-1", WINDOW_START, WINDOW_END, 1).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "1.0"));

        submitBatch("req-r2", items).andExpect(status().isCreated());
        // 同参重放：返回首次结果，不重复落库
        submitBatch("req-r2", items)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.items[0].maintenanceFlag").value("MAINTENANCE_AFFECTED"));
        assertThat(observationCount()).isEqualTo(1);

        // 同键异参：409
        submitBatch("req-r2", List.of(item("sub-9", "cl-1", "st-1", "2026-09-01T00:00:00Z", "2.0")))
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void batchSubmissionUnknownStationRollsBackEntireBatch() throws Exception {
        registerStation("req-b4", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        submitBatch("req-b5", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "1.0"),
                item("sub-2", "cl-1", "st-missing", "2026-09-01T00:00:00Z", "2.0")))
                .andExpect(status().isNotFound());
        // 整批回滚：查不到半成品
        assertThat(observationCount()).isZero();
        mockMvc.perform(get("/api/stations/observations/{submissionId}", "sub-1"))
                .andExpect(status().isNotFound());

        // 失败不占幂等键：修正后同 requestId 可重新提交成功
        submitBatch("req-b5", List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "1.0")))
                .andExpect(status().isCreated());
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void batchSubmissionInvalidFormatRollsBackEntireBatch() throws Exception {
        registerStation("req-b6", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        Map<String, Object> bad = item("sub-2", "cl-1", "st-1", "2026-09-01T00:00:00Z", "not-a-number");
        submitBatch("req-b7", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "1.0"), bad))
                .andExpect(status().isBadRequest());
        assertThat(observationCount()).isZero();
    }

    @Test
    void batchSubmissionDuplicateSubmissionIdRejected() throws Exception {
        registerStation("req-b8", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        submitBatch("req-b9", List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T00:00:00Z", "1.0")))
                .andExpect(status().isCreated());
        submitBatch("req-b10", List.of(item("sub-1", "cl-2", "st-1", "2026-09-02T00:00:00Z", "2.0")))
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    // ---------- 合并与维护证据 ----------

    @Test
    void mergeSkipsAffectedAndPicksLatestEligible() throws Exception {
        registerStation("req-m0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-m1", "st-1", "win-1", WINDOW_START, WINDOW_END, 2).andExpect(status().isCreated());
        // 提交时刻命中窗口：sub-1/sub-2 受影响；先撤回窗口再提交 sub-3（未受影响）
        submitBatch("req-m2", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0"),
                item("sub-2", "cl-1", "st-1", "2026-09-01T04:00:00Z", "2.0")))
                .andExpect(status().isCreated());
        withdrawWindow("req-m3", "win-1").andExpect(status().isOk());
        submitBatch("req-m4", List.of(item("sub-3", "cl-1", "st-1", "2026-09-01T02:00:00Z", "3.0")))
                .andExpect(status().isCreated());

        // sub-3 观测时刻虽早于受影响的 sub-2，但它是唯一未受影响者，必须胜出
        mergeCluster("req-m5", "cl-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.winnerSubmissionId").value("sub-3"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(2))
                .andExpect(jsonPath("$.eligibleCount").value(1))
                .andExpect(jsonPath("$.marks[0].maintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.marks[0].windowVersion").value(2));

        mockMvc.perform(get("/api/stations/clusters/{clusterId}", "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.winnerSubmissionId").value("sub-3"));
    }

    @Test
    void mergeWithOnlyAffectedObservationsBecomesPendingManual() throws Exception {
        registerStation("req-p0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-p1", "st-1", "win-1", WINDOW_START, WINDOW_END, 1).andExpect(status().isCreated());
        submitBatch("req-p2", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0"),
                item("sub-2", "cl-1", "st-1", "2026-09-01T04:00:00Z", "2.0")))
                .andExpect(status().isCreated());

        // 冲突簇只含受影响观测：待人工裁决，不任意选择胜出
        mergeCluster("req-p3", "cl-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.winnerSubmissionId").doesNotExist())
                .andExpect(jsonPath("$.affectedCount").value(2))
                .andExpect(jsonPath("$.eligibleCount").value(0));

        mockMvc.perform(get("/api/stations/clusters/{clusterId}", "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.winnerSubmissionId").doesNotExist());

        // 重复合并（不同 requestId）：已合并，409
        mergeCluster("req-p4", "cl-1").andExpect(status().isConflict());
        // 同 requestId 重放：返回首次结果
        mergeCluster("req-p3", "cl-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"));
    }

    @Test
    void adjudicationCanChooseAffectedWithReasonAndEvidenceShowsMarks() throws Exception {
        registerStation("req-a0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-a1", "st-1", "win-1", WINDOW_START, WINDOW_END, 7).andExpect(status().isCreated());
        submitBatch("req-a2", List.of(
                item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0"),
                item("sub-2", "cl-1", "st-1", "2026-09-01T04:00:00Z", "2.0")))
                .andExpect(status().isCreated());
        mergeCluster("req-a3", "cl-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"));

        // 人工裁决选择受影响观测，必须写入理由和裁决人
        adjudicate("req-a4", "cl-1", "adj-1", "sub-2", "现场复核确认 sub-2 仪器已校准", "operator-zhang")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chosenSubmissionId").value("sub-2"))
                .andExpect(jsonPath("$.chosenMaintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.reason").value("现场复核确认 sub-2 仪器已校准"))
                .andExpect(jsonPath("$.adjudicator").value("operator-zhang"))
                .andExpect(jsonPath("$.clusterStatus").value("MERGED"));

        mockMvc.perform(get("/api/stations/clusters/{clusterId}", "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.winnerSubmissionId").value("sub-2"))
                .andExpect(jsonPath("$.adjudicationCount").value(1));

        // 证据查询同时显示维护标记、不可变快照与裁决记录
        mockMvc.perform(get("/api/stations/clusters/{clusterId}/evidence", "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.observations[0].maintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.snapshot.status").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.snapshot.marks[1].windowVersion").value(7))
                .andExpect(jsonPath("$.adjudications[0].adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.adjudications[0].chosenMaintenanceFlag").value("MAINTENANCE_AFFECTED"));

        mockMvc.perform(get("/api/stations/adjudications/{adjudicationId}", "adj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicator").value("operator-zhang"));
    }

    @Test
    void adjudicationValidationAndIdReuse() throws Exception {
        registerStation("req-v0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        submitBatch("req-v1", List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0")))
                .andExpect(status().isCreated());

        // 选择不属于该簇的提交：400
        adjudicate("req-v2", "cl-1", "adj-1", "sub-x", "理由", "op")
                .andExpect(status().isBadRequest());
        // 缺理由：400（Bean 校验）
        adjudicate("req-v3", "cl-1", "adj-1", "sub-1", "", "op")
                .andExpect(status().isBadRequest());

        adjudicate("req-v4", "cl-1", "adj-1", "sub-1", "确认无误", "op")
                .andExpect(status().isCreated());
        // adjudicationId 同参重放（新 requestId）：返回原记录，200
        adjudicate("req-v5", "cl-1", "adj-1", "sub-1", "确认无误", "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"));
        // adjudicationId 异参复用：409
        adjudicate("req-v6", "cl-1", "adj-1", "sub-1", "改理由", "op")
                .andExpect(status().isConflict());
        // 不存在的簇：404
        adjudicate("req-v7", "cl-x", "adj-9", "sub-1", "理由", "op")
                .andExpect(status().isNotFound());
    }

    // ---------- 封存快照与窗口更正 ----------

    @Test
    void sealedWindowCannotBeWithdrawnButCanBeCorrected() throws Exception {
        registerStation("req-c0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-c1", "st-1", "win-1", WINDOW_START, WINDOW_END, 1).andExpect(status().isCreated());
        submitBatch("req-c2", List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0")))
                .andExpect(status().isCreated());
        mergeCluster("req-c3", "cl-1").andExpect(status().isOk());

        // 窗口已被合并快照覆盖（封存）：不可撤回
        mockMvc.perform(get("/api/stations/windows/{windowId}", "win-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true));
        withdrawWindow("req-c4", "win-1").andExpect(status().isConflict());

        // 只能追加更正窗口：版本必须大于原版本
        correctWindow("req-c5", "win-1", "win-2", WINDOW_START, "2028-01-01T00:00:00Z", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.requiredMinWindowVersion").value(2))
                .andExpect(jsonPath("$.details.actualWindowVersion").value(1));
        correctWindow("req-c6", "win-1", "win-2", WINDOW_START, "2028-01-01T00:00:00Z", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.correctsWindowId").value("win-1"))
                .andExpect(jsonPath("$.windowVersion").value(2));

        mockMvc.perform(get("/api/stations/windows/{windowId}", "win-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));

        // 历史快照不改写：证据仍显示提交时的窗口版本 1
        mockMvc.perform(get("/api/stations/clusters/{clusterId}/evidence", "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations[0].windowId").value("win-1"))
                .andExpect(jsonPath("$.observations[0].windowVersion").value(1))
                .andExpect(jsonPath("$.snapshot.marks[0].windowVersion").value(1));

        // 诊断反映最新状态
        mockMvc.perform(get("/api/stations/{stationId}/windows/diagnostics", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWindows").value(2))
                .andExpect(jsonPath("$.activeWindows").value(1))
                .andExpect(jsonPath("$.supersededWindows").value(1))
                .andExpect(jsonPath("$.sealedWindows").value(1));
    }

    @Test
    void unsealedWindowCanBeWithdrawn() throws Exception {
        registerStation("req-u0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        registerWindow("req-u1", "st-1", "win-1", PAST_START, PAST_END, 1).andExpect(status().isCreated());
        withdrawWindow("req-u2", "win-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        // 已撤回窗口不能再次撤回
        withdrawWindow("req-u3", "win-1").andExpect(status().isConflict());
        // 同 requestId 重放首次撤回结果
        withdrawWindow("req-u2", "win-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void windowChangeOrderingDeterminesMarking() throws Exception {
        registerStation("req-o0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        // 观测先提交：提交时无窗口，标记 NONE
        submitBatch("req-o1", List.of(item("sub-1", "cl-1", "st-1", "2026-09-01T03:00:00Z", "1.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].maintenanceFlag").value("NONE"));
        // 窗口变更后提交：按新窗口标记
        registerWindow("req-o2", "st-1", "win-1", WINDOW_START, WINDOW_END, 5).andExpect(status().isCreated());
        submitBatch("req-o3", List.of(item("sub-2", "cl-1", "st-1", "2026-09-01T04:00:00Z", "2.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].maintenanceFlag").value("MAINTENANCE_AFFECTED"))
                .andExpect(jsonPath("$.items[0].windowVersion").value(5));
        // 先提交的观测保留提交时标记，不被后续窗口变更改写
        mockMvc.perform(get("/api/stations/observations/{submissionId}", "sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceFlag").value("NONE"))
                .andExpect(jsonPath("$.windowId").doesNotExist());
    }

    @Test
    void requestIdReuseAcrossOperationsReturns409() throws Exception {
        registerStation("req-x0", "st-1", ACTIVATED_AT).andExpect(status().isCreated());
        // 同一 requestId 用于不同操作（异参）：409
        registerWindow("req-x0", "st-1", "win-1", WINDOW_START, WINDOW_END, 1)
                .andExpect(status().isConflict());
    }

    @Test
    void mergeOnUnknownClusterReturns404() throws Exception {
        mergeCluster("req-z1", "cl-x").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stations/clusters/{clusterId}", "cl-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stations/clusters/{clusterId}/evidence", "cl-x"))
                .andExpect(status().isNotFound());
    }
}
