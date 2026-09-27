package com.example.starter.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
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
 * 冲突簇合并、待人工裁决、快照封存与历史不可变的 API 测试（真实 H2 内存库，固定 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceMergeApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_adjudication");
        jdbcTemplate.update("DELETE FROM cluster_snapshot_window");
        jdbcTemplate.update("DELETE FROM cluster_snapshot_evidence");
        jdbcTemplate.update("DELETE FROM cluster_snapshot");
        jdbcTemplate.update("DELETE FROM station_observation");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM station");
        jdbcTemplate.update("DELETE FROM maintenance_request_log");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void registerStation(String requestId, String stationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stationId", stationId);
        body.put("enabledAtUtc", "2026-09-01T00:00:00Z");
        mockMvc.perform(post("/api/maintenance/stations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated());
    }

    private ResultActions registerWindow(String requestId, String stationId, String windowId,
                                String startAtUtc, String endAtUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("windowId", windowId);
        body.put("startAtUtc", startAtUtc);
        body.put("endAtUtc", endAtUtc);
        return mockMvc.perform(post("/api/maintenance/stations/{stationId}/windows", stationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated());
    }

    private ResultActions submit(String requestId, List<Map<String, Object>> observations) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observations", observations);
        return mockMvc.perform(post("/api/maintenance/observations/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> observation(String id, String stationId, String clusterId,
                                            String submittedAtUtc, String payload) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", id);
        item.put("stationId", stationId);
        item.put("clusterId", clusterId);
        item.put("submittedAtUtc", submittedAtUtc);
        item.put("payload", payload);
        return item;
    }

    private ResultActions merge(String requestId, String clusterId) throws Exception {
        return mockMvc.perform(post("/api/maintenance/clusters/{clusterId}/merge", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"}"));
    }

    private ResultActions adjudicate(String requestId, String adjudicationId, String clusterId,
                                     String observationId, String reason, String adjudicator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("observationId", observationId);
        body.put("reason", reason);
        body.put("adjudicator", adjudicator);
        return mockMvc.perform(post("/api/maintenance/clusters/{clusterId}/adjudications", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions correct(String requestId, String windowId, String correctsWindowId,
                                  String startAtUtc, String endAtUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("windowId", windowId);
        body.put("correctsWindowId", correctsWindowId);
        body.put("startAtUtc", startAtUtc);
        body.put("endAtUtc", endAtUtc);
        return mockMvc.perform(post("/api/maintenance/windows/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteWindow(String requestId, String windowId) throws Exception {
        return mockMvc.perform(post("/api/maintenance/windows/{windowId}/delete", windowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"}"));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------- 自动合并 ----------

    @Test
    void mergePicksLatestUnaffectedAndFreezesWindowEvidence() throws Exception {
        registerStation("req-s1", "st-1");
        registerWindow("req-w1", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b1", List.of(
                // 前一天 23:30 未受影响；01:00 命中窗口（提交更晚但不可胜出）；03:00 未受影响且最晚
                observation("obs-a", "st-1", "cl-1", "2026-09-09T23:30:00Z", "早，未受影响"),
                observation("obs-b", "st-1", "cl-1", "2026-09-10T01:00:00Z", "命中维护"),
                observation("obs-c", "st-1", "cl-1", "2026-09-10T03:00:00Z", "晚，未受影响")))
                .andExpect(status().isCreated());

        merge("req-m1", "cl-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value("cl-1"))
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.winnerObservationId").value("obs-c"))
                .andExpect(jsonPath("$.evidenceCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.evidence[1].observationId").value("obs-b"))
                .andExpect(jsonPath("$.evidence[1].maintenanceAffected").value(true))
                .andExpect(jsonPath("$.evidence[1].matchedWindowId").value("win-1"))
                .andExpect(jsonPath("$.evidence[1].matchedMaintenanceVersion").value(1))
                .andExpect(jsonPath("$.windows[0].windowId").value("win-1"))
                .andExpect(jsonPath("$.windows[0].maintenanceVersion").value(1));
    }

    @Test
    void allAffectedClusterBecomesPendingManualAndNeverPicksWinner() throws Exception {
        registerStation("req-s2", "st-1");
        registerWindow("req-w2", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b2", List.of(
                observation("obs-a", "st-1", "cl-2", "2026-09-10T00:30:00Z", "受影响A"),
                observation("obs-b", "st-1", "cl-2", "2026-09-10T01:00:00Z", "受影响B")))
                .andExpect(status().isCreated());

        merge("req-m2", "cl-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.winnerObservationId").doesNotExist())
                .andExpect(jsonPath("$.evidenceCount").value(2))
                .andExpect(jsonPath("$.affectedCount").value(2));
    }

    @Test
    void mergeUnknownClusterReturns404AndReMergeAppendsHistory() throws Exception {
        merge("req-m3", "cl-x").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CLUSTER_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-m3'")).isZero();

        registerStation("req-s3", "st-1");
        submit("req-b3", List.of(
                observation("obs-a", "st-1", "cl-3", "2026-09-10T00:30:00Z", "a")))
                .andExpect(status().isCreated());
        merge("req-m4", "cl-3").andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1));
        // 再次合并：追加序号 2 的新快照，历史快照保留
        merge("req-m5", "cl-3").andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(2));
        mockMvc.perform(get("/api/maintenance/clusters/{clusterId}/snapshots", "cl-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[1].sequence").value(2));
    }

    // ---------- 人工裁决 ----------

    @Test
    void adjudicationCanChooseAffectedObservationAndRecordsReasonAndOperator() throws Exception {
        registerStation("req-s4", "st-1");
        registerWindow("req-w4", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b4", List.of(
                observation("obs-a", "st-1", "cl-4", "2026-09-10T00:30:00Z", "受影响A"),
                observation("obs-b", "st-1", "cl-4", "2026-09-10T01:00:00Z", "受影响B")))
                .andExpect(status().isCreated());
        merge("req-m6", "cl-4").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_MANUAL"));

        adjudicate("req-a1", "adj-1", "cl-4", "obs-b", "维护已结束，数据经人工复核有效", "reviewer-li")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.clusterId").value("cl-4"))
                .andExpect(jsonPath("$.observationId").value("obs-b"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.reason").value("维护已结束，数据经人工复核有效"))
                .andExpect(jsonPath("$.adjudicator").value("reviewer-li"))
                .andExpect(jsonPath("$.adjudicatedAtUtc").value("2026-09-28T08:00:00Z"));

        // 证据查询同时显示维护标记
        mockMvc.perform(get("/api/maintenance/adjudications/{adjudicationId}", "adj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceAffected").value(true));
        mockMvc.perform(get("/api/maintenance/clusters/{clusterId}/adjudications", "cl-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adjudicationId").value("adj-1"));
        assertThat(count("SELECT COUNT(*) FROM cluster_adjudication WHERE cluster_id = 'cl-4'")).isEqualTo(1);
    }

    @Test
    void adjudicationValidationAndStateBoundaries() throws Exception {
        registerStation("req-s5", "st-1");
        registerWindow("req-w5", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        // 存在未受影响观测：自动合并 MERGED，不允许裁决
        submit("req-b5", List.of(
                observation("obs-a", "st-1", "cl-5", "2026-09-10T01:00:00Z", "受影响"),
                observation("obs-b", "st-1", "cl-5", "2026-09-10T03:00:00Z", "未受影响")))
                .andExpect(status().isCreated());
        merge("req-m7", "cl-5").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"));
        adjudicate("req-a2", "adj-2", "cl-5", "obs-b", "理由", "op")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_NOT_PENDING"));
        assertThat(count("SELECT COUNT(*) FROM cluster_adjudication")).isZero();

        // 理由为空：400
        adjudicate("req-a3", "adj-3", "cl-5", "obs-b", "   ", "op")
                .andExpect(status().isBadRequest());
        // 无快照的簇：404
        adjudicate("req-a4", "adj-4", "cl-none", "obs-b", "理由", "op")
                .andExpect(status().isNotFound());

        // 待裁决簇：选择不属于簇的观测 → 404
        registerStation("req-s6", "st-2");
        registerWindow("req-w6", "st-2", "win-9", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b6", List.of(
                observation("obs-c", "st-2", "cl-6", "2026-09-10T01:00:00Z", "受影响")))
                .andExpect(status().isCreated());
        merge("req-m8", "cl-6").andExpect(status().isOk());
        adjudicate("req-a5", "adj-5", "cl-6", "obs-zzz", "理由", "op")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_NOT_IN_CLUSTER"));
        assertThat(count("SELECT COUNT(*) FROM cluster_adjudication")).isZero();
        assertThat(count("SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-a5'")).isZero();
    }

    @Test
    void adjudicationIdempotencyReplaysAndRejectsChangedParams() throws Exception {
        registerStation("req-s7", "st-1");
        registerWindow("req-w7", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b7", List.of(
                observation("obs-a", "st-1", "cl-7", "2026-09-10T01:00:00Z", "受影响")))
                .andExpect(status().isCreated());
        merge("req-m9", "cl-7").andExpect(status().isOk());

        // 同 requestId 同参重放
        adjudicate("req-a6", "adj-7", "cl-7", "obs-a", "理由A", "op1")
                .andExpect(status().isOk());
        adjudicate("req-a6", "adj-7", "cl-7", "obs-a", "理由A", "op1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("理由A"));
        // 同 requestId 异参：409
        adjudicate("req-a6", "adj-7", "cl-7", "obs-a", "理由B", "op1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_ID_CONFLICT"));
        // 新 requestId 但复用 adjudicationId 且改参：409
        adjudicate("req-a7", "adj-7", "cl-7", "obs-a", "理由C", "op2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADJUDICATION_ID_REUSED"));
        // 同 adjudicationId 同参、新 requestId：重放原结果
        adjudicate("req-a8", "adj-7", "cl-7", "obs-a", "理由A", "op1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-a6"));
        assertThat(count("SELECT COUNT(*) FROM cluster_adjudication WHERE adjudication_id = 'adj-7'")).isEqualTo(1);
    }

    // ---------- 封存、更正与历史不可变 ----------

    @Test
    void sealedWindowCannotBeDeletedButCorrectionCanBeAppended() throws Exception {
        registerStation("req-s8", "st-1");
        registerWindow("req-w8", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b8", List.of(
                observation("obs-a", "st-1", "cl-8", "2026-09-10T01:00:00Z", "受影响"),
                observation("obs-b", "st-1", "cl-8", "2026-09-10T03:00:00Z", "未受影响")))
                .andExpect(status().isCreated());
        merge("req-m10", "cl-8").andExpect(status().isOk());

        // 已被快照固化：拒绝删除
        deleteWindow("req-d1", "win-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_SEALED"));
        assertThat(count("SELECT COUNT(*) FROM maintenance_window WHERE window_id = 'win-1' AND status = 'ACTIVE'"))
                .isEqualTo(1);

        // 只能追加更正窗口：允许与原窗口重叠，版本号递增
        correct("req-c1", "win-1-fix", "win-1", "2026-09-10T00:30:00Z", "2026-09-10T01:30:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowType").value("CORRECTION"))
                .andExpect(jsonPath("$.correctsWindowId").value("win-1"))
                .andExpect(jsonPath("$.maintenanceVersion").value(2));
        // 更正窗口与其他生效窗口（非目标）重叠：拒绝
        registerWindow("req-w8b", "st-1", "win-2", "2026-09-10T05:00:00Z", "2026-09-10T06:00:00Z")
                .andExpect(status().isCreated());
        correct("req-c2", "win-1-fix2", "win-1", "2026-09-10T05:30:00Z", "2026-09-10T06:30:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_OVERLAP"));
        // 更正不存在窗口：404
        correct("req-c3", "win-x-fix", "win-x", "2026-09-10T00:30:00Z", "2026-09-10T01:30:00Z")
                .andExpect(status().isNotFound());
    }

    @Test
    void historySnapshotsRemainStableAfterLaterCorrectionAndSupplements() throws Exception {
        registerStation("req-s9", "st-1");
        registerWindow("req-w9", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z");
        submit("req-b9", List.of(
                observation("obs-a", "st-1", "cl-9", "2026-09-10T01:00:00Z", "受影响"),
                observation("obs-b", "st-1", "cl-9", "2026-09-10T03:00:00Z", "未受影响")))
                .andExpect(status().isCreated());
        merge("req-m11", "cl-9").andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerObservationId").value("obs-b"));

        // 追加更正窗口并向簇内补录新观测后再次合并
        correct("req-c4", "win-1-fix", "win-1", "2026-09-10T01:30:00Z", "2026-09-10T02:30:00Z")
                .andExpect(status().isCreated());
        submit("req-b10", List.of(
                observation("obs-c", "st-1", "cl-9", "2026-09-10T04:00:00Z", "补录，未受影响")))
                .andExpect(status().isCreated());
        merge("req-m12", "cl-9").andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(2))
                .andExpect(jsonPath("$.winnerObservationId").value("obs-c"))
                .andExpect(jsonPath("$.evidenceCount").value(3));

        // 历史快照 1 的证据（2 条）、胜出者与固化窗口版本均不随后续操作改写
        mockMvc.perform(get("/api/maintenance/clusters/{clusterId}/snapshots", "cl-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[0].winnerObservationId").value("obs-b"))
                .andExpect(jsonPath("$[0].evidenceCount").value(2))
                .andExpect(jsonPath("$[0].windows.length()").value(1))
                .andExpect(jsonPath("$[0].windows[0].windowId").value("win-1"))
                .andExpect(jsonPath("$[0].windows[0].maintenanceVersion").value(1));
    }

    @Test
    void missingSnapshotsAndAdjudicationsReturn404() throws Exception {
        mockMvc.perform(get("/api/maintenance/snapshots/9999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/maintenance/clusters/cl-x/snapshots")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/maintenance/adjudications/adj-x")).andExpect(status().isNotFound());
    }
}
