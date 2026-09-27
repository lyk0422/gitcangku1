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
 * 观测站登记、维护窗口登记规则与观测批量提交的 API 测试（真实 H2 内存库，MODE=MySQL）。
 * 覆盖主流程、半开边界、重叠/启用时刻/时长失败分支、整批回滚与 requestId 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    private ResultActions registerStation(String requestId, String stationId, String enabledAtUtc)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stationId", stationId);
        body.put("enabledAtUtc", enabledAtUtc);
        return mockMvc.perform(post("/api/maintenance/stations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteWindow(String requestId, String windowId) throws Exception {
        return mockMvc.perform(post("/api/maintenance/windows/{windowId}/delete", windowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"}"));
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

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------- 站点与窗口主流程 ----------

    @Test
    void registerStationAndWindowAssignsMonotonicVersions() throws Exception {
        registerStation("req-s1", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("st-1"))
                .andExpect(jsonPath("$.enabledAtUtc").value("2026-09-01T00:00:00Z"));
        registerWindow("req-w1", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.maintenanceVersion").value(1))
                .andExpect(jsonPath("$.durationSeconds").value(7200))
                .andExpect(jsonPath("$.windowType").value("REGISTER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        registerWindow("req-w2", "st-1", "win-2", "2026-09-11T00:00:00Z", "2026-09-11T01:30:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceVersion").value(2))
                .andExpect(jsonPath("$.durationSeconds").value(5400));

        mockMvc.perform(get("/api/maintenance/stations/{stationId}/windows", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].windowId").value("win-1"));
        mockMvc.perform(get("/api/maintenance/windows/{windowId}", "win-2"))
                .andExpect(jsonPath("$.maintenanceVersion").value(2));
    }

    @Test
    void duplicateStationAndWindowIdsReturn409() throws Exception {
        registerStation("req-s2", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerStation("req-s3", "st-1", "2026-09-01T00:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STATION_EXISTS"));
        registerWindow("req-w3", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated());
        registerWindow("req-w4", "st-1", "win-1", "2026-09-11T00:00:00Z", "2026-09-11T02:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_ID_EXISTS"));
    }

    @Test
    void windowBeforeStationEnabledIsRejectedWithActualAndRequired() throws Exception {
        registerStation("req-s4", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        // 开始时刻恰好等于启用时刻：左闭允许
        registerWindow("req-w5", "st-1", "win-edge", "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z")
                .andExpect(status().isCreated());
        // 早于启用时刻 3600 秒：409，信息含实际值、要求值与差额
        registerWindow("req-w6", "st-1", "win-early", "2026-08-31T23:00:00Z", "2026-09-01T01:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_BEFORE_ENABLED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("actual=2026-08-31T23:00:00Z")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("required>=2026-09-01T00:00:00Z")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("difference=3600s")));
        assertThat(count("SELECT COUNT(*) FROM maintenance_window WHERE station_id = 'st-1'")).isEqualTo(1);
    }

    @Test
    void windowWithNonPositiveDurationReportsActualRequiredAndDifference() throws Exception {
        registerStation("req-s5", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        // 零时长（起止相同）：400，实际 0s、要求 >0s、差额 1s
        registerWindow("req-w7", "st-1", "win-zero", "2026-09-10T00:00:00Z", "2026-09-10T00:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_WINDOW_RANGE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("actual duration=0s")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("required>0s")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("difference=1s")));
        // 负时长：400
        registerWindow("req-w8", "st-1", "win-negative", "2026-09-10T02:00:00Z", "2026-09-10T00:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_WINDOW_RANGE"));
        assertThat(count("SELECT COUNT(*) FROM maintenance_window")).isZero();
    }

    @Test
    void overlappingWindowsRejectedButTouchingIntervalsAllowed() throws Exception {
        registerStation("req-s6", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerWindow("req-w9", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated());
        // 与 win-1 重叠 1 小时：409，给出实际重叠时长与要求的 0s
        registerWindow("req-w10", "st-1", "win-overlap", "2026-09-10T01:00:00Z", "2026-09-10T03:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_OVERLAP"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overlapping duration=3600s")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("required overlap=0s")));
        // 端点相接（win-1 结束时刻 = 新窗开始时刻）：左闭右开不重叠，允许登记
        registerWindow("req-w11", "st-1", "win-touch", "2026-09-10T02:00:00Z", "2026-09-10T03:00:00Z")
                .andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM maintenance_window WHERE station_id = 'st-1'")).isEqualTo(2);
    }

    @Test
    void windowOperationsOnUnknownStationReturn404() throws Exception {
        registerWindow("req-w12", "st-x", "win-x", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STATION_NOT_FOUND"));
        mockMvc.perform(get("/api/maintenance/stations/{stationId}/windows", "st-x"))
                .andExpect(status().isNotFound());
    }

    // ---------- 批量提交 ----------

    @Test
    void batchSubmitMarksEachObservationByItsOwnSubmitTimeAcrossStations() throws Exception {
        registerStation("req-s7", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerStation("req-s8", "st-2", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerWindow("req-w13", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated());

        submit("req-b1", List.of(
                observation("obs-1", "st-1", null, "2026-09-10T00:00:00Z", "窗口开始时刻，命中"),
                observation("obs-2", "st-1", null, "2026-09-10T02:00:00Z", "窗口结束时刻，不命中"),
                observation("obs-3", "st-2", null, "2026-09-10T01:00:00Z", "他站无窗口，不命中")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptedCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.observations[0].maintenanceAffected").value(true))
                .andExpect(jsonPath("$.observations[0].matchedWindowId").value("win-1"))
                .andExpect(jsonPath("$.observations[0].matchedMaintenanceVersion").value(1))
                .andExpect(jsonPath("$.observations[1].maintenanceAffected").value(false))
                .andExpect(jsonPath("$.observations[1].matchedWindowId").doesNotExist())
                .andExpect(jsonPath("$.observations[2].maintenanceAffected").value(false));

        // 命中窗口的观测原始记录仍完整保存，且带维护标记
        mockMvc.perform(get("/api/maintenance/observations/{observationId}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("窗口开始时刻，命中"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.matchedWindowId").value("win-1"));
        mockMvc.perform(get("/api/maintenance/observations/{observationId}", "obs-2"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false));
    }

    @Test
    void unknownStationReferenceRollsBackEntireBatch() throws Exception {
        registerStation("req-s9", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        submit("req-b2", List.of(
                observation("obs-ok", "st-1", null, "2026-09-10T00:00:00Z", "正常站"),
                observation("obs-bad", "st-x", null, "2026-09-10T00:00:00Z", "不存在站")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STATION_NOT_FOUND"));
        // 整批回滚：两条观测均不存在
        assertThat(count("SELECT COUNT(*) FROM station_observation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-b2'")).isZero();
    }

    @Test
    void duplicateAndExistingObservationIdsRollBackEntireBatch() throws Exception {
        registerStation("req-s10", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        submit("req-b3", List.of(observation("obs-1", "st-1", null, "2026-09-10T00:00:00Z", "首条")))
                .andExpect(status().isCreated());
        // 批内重复
        submit("req-b4", List.of(
                observation("obs-2", "st-1", null, "2026-09-10T00:00:00Z", "a"),
                observation("obs-2", "st-1", null, "2026-09-10T00:01:00Z", "重复")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_DUPLICATE"));
        // 与已落库观测重复
        submit("req-b5", List.of(
                observation("obs-9", "st-1", null, "2026-09-10T00:00:00Z", "新"),
                observation("obs-1", "st-1", null, "2026-09-10T00:00:00Z", "已存在")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_EXISTS"));
        assertThat(count("SELECT COUNT(*) FROM station_observation")).isEqualTo(1);
    }

    @Test
    void clusterMixingStationsIsRejectedAndRolledBack() throws Exception {
        registerStation("req-s11", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerStation("req-s12", "st-2", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        submit("req-b6", List.of(
                observation("obs-1", "st-1", "cl-1", "2026-09-10T00:00:00Z", "站1"),
                observation("obs-2", "st-2", "cl-1", "2026-09-10T00:01:00Z", "站2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CLUSTER_MIXED_STATIONS"));
        assertThat(count("SELECT COUNT(*) FROM station_observation")).isZero();
    }

    @Test
    void laterWindowRegistrationDoesNotRetroactivelyMarkEarlierObservation() throws Exception {
        registerStation("req-s13", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        // 先提交观测（此刻无窗口）
        submit("req-b7", List.of(observation("obs-1", "st-1", null, "2026-09-10T01:00:00Z", "先提交")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observations[0].maintenanceAffected").value(false));
        // 之后才登记覆盖该时刻的窗口：已提交观测按提交时版本保留，不被追溯标记
        registerWindow("req-w14", "st-1", "win-late", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/maintenance/observations/{observationId}", "obs-1"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false))
                .andExpect(jsonPath("$.matchedWindowId").doesNotExist());
        // 窗口登记后新提交的同一时刻观测按新版本标记
        submit("req-b8", List.of(observation("obs-2", "st-1", null, "2026-09-10T01:00:00Z", "后提交")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observations[0].maintenanceAffected").value(true))
                .andExpect(jsonPath("$.observations[0].matchedWindowId").value("win-late"))
                .andExpect(jsonPath("$.observations[0].matchedMaintenanceVersion").value(1));
    }

    @Test
    void deletingWindowKeepsFrozenMarkOnExistingObservation() throws Exception {
        registerStation("req-s14", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerWindow("req-w15", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated());
        submit("req-b9", List.of(observation("obs-1", "st-1", null, "2026-09-10T01:00:00Z", "命中")))
                .andExpect(status().isCreated());
        // 窗口未被快照封存，可删除；删除后新观测不再命中
        deleteWindow("req-w16", "win-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        deleteWindow("req-w17", "win-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WINDOW_NOT_ACTIVE"));
        // 已提交观测的标记与窗口版本固化不变
        mockMvc.perform(get("/api/maintenance/observations/{observationId}", "obs-1"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.matchedWindowId").value("win-1"))
                .andExpect(jsonPath("$.matchedMaintenanceVersion").value(1));
        mockMvc.perform(get("/api/maintenance/stations/{stationId}/windows", "st-1"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 幂等边界 ----------

    @Test
    void submitSameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        registerStation("req-s15", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        List<Map<String, Object>> batch = List.of(
                observation("obs-1", "st-1", null, "2026-09-10T00:00:00Z", "a"));
        submit("req-bi1", batch).andExpect(status().isCreated());
        // 同参重放：返回首次结果，不重复写入
        submit("req-bi1", batch).andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptedCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM station_observation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-bi1'")).isEqualTo(1);
        // 异参：409
        submit("req-bi1", List.of(observation("obs-2", "st-1", null, "2026-09-10T00:00:00Z", "b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedBatchDoesNotOccupyRequestId() throws Exception {
        registerStation("req-s16", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        // 首次因未知站点失败（404），不占键
        submit("req-bf1", List.of(observation("obs-1", "st-x", null, "2026-09-10T00:00:00Z", "x")))
                .andExpect(status().isNotFound());
        // 同 requestId 换成合法批次后成功
        submit("req-bf1", List.of(observation("obs-1", "st-1", null, "2026-09-10T00:00:00Z", "ok")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observations[0].payload").value("ok"));
        assertThat(count("SELECT COUNT(*) FROM maintenance_request_log WHERE request_id = 'req-bf1'")).isEqualTo(1);
    }

    @Test
    void registerWindowSameRequestIdReplaysFirstResult() throws Exception {
        registerStation("req-s17", "st-1", "2026-09-01T00:00:00Z").andExpect(status().isCreated());
        registerWindow("req-wi1", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceVersion").value(1));
        registerWindow("req-wi1", "st-1", "win-1", "2026-09-10T00:00:00Z", "2026-09-10T02:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceVersion").value(1));
        assertThat(count("SELECT COUNT(*) FROM maintenance_window")).isEqualTo(1);
    }
}
