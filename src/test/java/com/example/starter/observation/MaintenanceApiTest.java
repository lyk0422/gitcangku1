package com.example.starter.observation;

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
 * 观测站维护窗口主流程、失败分支、事务回滚、幂等与历史固化测试（真实 H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceApiTest {

    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String ACTIVATED = "2026-09-01T00:00:00Z";
    private static final String T10 = "2026-09-10T10:00:00Z";
    private static final String T1030 = "2026-09-10T10:30:00Z";
    private static final String T11 = "2026-09-10T11:00:00Z";
    private static final String T1130 = "2026-09-10T11:30:00Z";
    private static final String T12 = "2026-09-10T12:00:00Z";
    private static final String T13 = "2026-09-10T13:00:00Z";
    private static final String T14 = "2026-09-10T14:00:00Z";
    private static final String T15 = "2026-09-10T15:00:00Z";
    private static final String T16 = "2026-09-10T16:00:00Z";
    private static final String T09 = "2026-09-10T09:00:00Z";
    private static final String T0930 = "2026-09-10T09:30:00Z";

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
        jdbcTemplate.update("DELETE FROM cluster_merge");
        jdbcTemplate.update("DELETE FROM observation_submission");
        jdbcTemplate.update("DELETE FROM maintenance_window");
        jdbcTemplate.update("DELETE FROM station");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ---------- 辅助构造 ----------

    private ResultActions registerStation(String requestId, String stationId, String activatedAt) throws Exception {
        return mockMvc.perform(post("/api/stations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "stationId", stationId, "activatedAtUtc", activatedAt))));
    }

    private ResultActions registerWindow(String requestId, String windowId, String stationId,
                                         String start, String end) throws Exception {
        return mockMvc.perform(post("/api/stations/{stationId}/maintenance-windows", stationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "windowId", windowId, "stationId", stationId,
                        "startAtUtc", start, "endAtUtc", end))));
    }

    private ResultActions correctWindow(String requestId, String windowId, String stationId,
                                        String supersedesWindowId, String start, String end,
                                        boolean retract) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("windowId", windowId);
        body.put("stationId", stationId);
        body.put("supersedesWindowId", supersedesWindowId);
        body.put("startAtUtc", start);
        body.put("endAtUtc", end);
        body.put("retract", retract);
        return mockMvc.perform(post("/api/maintenance-windows/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> submission(String id, String stationId, String observationId,
                                           String submittedAt, String location, String reading, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("submissionId", id);
        item.put("stationId", stationId);
        item.put("observationId", observationId);
        item.put("submittedAtUtc", submittedAt);
        item.put("location", location);
        item.put("reading", reading);
        item.put("note", note);
        return item;
    }

    private ResultActions submitBatch(String requestId, List<Map<String, Object>> items) throws Exception {
        return mockMvc.perform(post("/api/observation-submissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId, "submissions", items))));
    }

    private ResultActions mergeCluster(String requestId, String observationId, List<String> ids) throws Exception {
        return mockMvc.perform(post("/api/observations/{observationId}/cluster-merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "observationId", observationId, "submissionIds", ids))));
    }

    private ResultActions decide(String requestId, String mergeId, String winner,
                                 String adjudicator, String reason) throws Exception {
        return mockMvc.perform(post("/api/cluster-merges/{mergeId}/decision", mergeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "mergeId", mergeId, "winningSubmissionId", winner,
                        "adjudicator", adjudicator, "reason", reason))));
    }

    private int tableCount(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    // ---------- 站点登记 ----------

    @Test
    void registerStationReturnsActivation() throws Exception {
        registerStation("req-st-1", "st-1", ACTIVATED)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("st-1"))
                .andExpect(jsonPath("$.activatedAtUtc").value(ACTIVATED));
        mockMvc.perform(get("/api/stations/st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activatedAtUtc").value(ACTIVATED));
    }

    @Test
    void duplicateStationReturns409() throws Exception {
        registerStation("req-st-2", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerStation("req-st-3", "st-1", ACTIVATED).andExpect(status().isConflict());
        assertThat(tableCount("station", "station_id", "st-1")).isEqualTo(1);
    }

    @Test
    void stationRequestIdReplayAndConflict() throws Exception {
        registerStation("req-st-4", "st-1", ACTIVATED).andExpect(status().isCreated());
        // 同参重放
        registerStation("req-st-4", "st-1", ACTIVATED)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("st-1"));
        // 异参 409
        registerStation("req-st-4", "st-2", ACTIVATED).andExpect(status().isConflict());
        assertThat(tableCount("station", "station_id", "st-1")).isEqualTo(1);
        assertThat(tableCount("station", "station_id", "st-2")).isZero();
    }

    // ---------- 窗口登记 ----------

    @Test
    void registerWindowSucceedsWithActualDuration() throws Exception {
        registerStation("req-w-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-w-1", "win-1", "st-1", T10, T12)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.kind").value("REGISTER"))
                .andExpect(jsonPath("$.startAtUtc").value(T10))
                .andExpect(jsonPath("$.endAtUtc").value(T12))
                .andExpect(jsonPath("$.durationSeconds").value(7200))
                .andExpect(jsonPath("$.retracted").value(false))
                .andExpect(jsonPath("$.createdAtUtc").value(NOW.toString()));
    }

    @Test
    void overlappingWindowRejectedAndTouchingAllowed() throws Exception {
        registerStation("req-w-2", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-w-3", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        // 部分重叠：拒绝
        registerWindow("req-w-4", "win-2", "st-1", T11, T13)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("win-1")));
        // 左闭右开端点相接 [10,12) 与 [12,13)：允许
        registerWindow("req-w-5", "win-3", "st-1", T12, T13)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(tableCount("maintenance_window", "station_id", "st-1")).isEqualTo(2);
        // 失败不占键：req-w-4 改参数后成功登记
        registerWindow("req-w-4", "win-4", "st-1", T14, T15)
                .andExpect(status().isCreated());
    }

    @Test
    void windowBeforeActivationAndNonPositiveDurationRejected() throws Exception {
        registerStation("req-w-6", "st-1", ACTIVATED).andExpect(status().isCreated());
        // 开始早于启用时刻：400，错误信息含实际值/要求值/差额
        registerWindow("req-w-7", "win-1", "st-1", "2026-08-31T23:00:00Z", T10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("activation")));
        // 开始等于启用时刻：允许
        registerWindow("req-w-8", "win-2", "st-1", ACTIVATED, "2026-09-01T00:01:00Z")
                .andExpect(status().isCreated());
        // 零时长与负时长：400
        registerWindow("req-w-9", "win-3", "st-1", T10, T10).andExpect(status().isBadRequest());
        registerWindow("req-w-10", "win-4", "st-1", T13, T12).andExpect(status().isBadRequest());
        // 站点不存在：404
        registerWindow("req-w-11", "win-5", "st-x", T10, T12).andExpect(status().isNotFound());
        assertThat(tableCount("maintenance_window", "window_id", "win-1")).isZero();
    }

    @Test
    void windowIdReuseReturns409() throws Exception {
        registerStation("req-w-12", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-w-13", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        registerWindow("req-w-14", "win-1", "st-1", T14, T15).andExpect(status().isConflict());
    }

    @Test
    void windowRegistrationAndCorrectionIdempotency() throws Exception {
        registerStation("req-wi-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-wi-1", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        // 同参重放：返回首次结果，不产生第二版本行
        registerWindow("req-wi-1", "win-1", "st-1", T10, T12)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(tableCount("maintenance_window", "window_id", "win-1")).isEqualTo(1);
        // 异参 409
        registerWindow("req-wi-1", "win-9", "st-1", T14, T15).andExpect(status().isConflict());
        // 更正同参重放
        correctWindow("req-wi-2", "win-2", "st-1", "win-1", T14, T16, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        correctWindow("req-wi-2", "win-2", "st-1", "win-1", T14, T16, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(tableCount("maintenance_window", "station_id", "st-1")).isEqualTo(2);
        // 更正异参（撤回标志不同）：409
        correctWindow("req-wi-2", "win-2", "st-1", "win-1", null, null, true)
                .andExpect(status().isConflict());
        // 窗口登记失败不占键：与有效窗口 win-2 [14,16) 重叠失败的 requestId，换端点相接的合法区间后成功
        registerWindow("req-wi-3", "win-x", "st-1", T15, "2026-09-10T15:30:00Z")
                .andExpect(status().isConflict());
        registerWindow("req-wi-3", "win-3", "st-1", T16, "2026-09-10T17:00:00Z")
                .andExpect(status().isCreated());
        assertThat(tableCount("request_log", "request_id", "req-wi-3")).isEqualTo(1);
    }

    @Test
    void readOnlyQueriesReturn404ForMissingResources() throws Exception {
        mockMvc.perform(get("/api/stations/st-missing")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stations/st-missing/maintenance-windows")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/stations/st-missing/maintenance-windows/effective").param("atUtc", T11))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observation-submissions/sub-missing")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observation-submissions/batches/req-missing")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cluster-merges/merge-missing")).andExpect(status().isNotFound());
    }

    // ---------- 更正/撤回与历史 ----------

    @Test
    void correctionReplacesRangeAndRetractionAppendsOnly() throws Exception {
        registerStation("req-c-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-c-1", "win-1", "st-1", T10, T12).andExpect(status().isCreated());

        // 更正替换为 [14,16)
        correctWindow("req-c-2", "win-2", "st-1", "win-1", T14, T16, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.kind").value("CORRECTION"))
                .andExpect(jsonPath("$.supersedesWindowId").value("win-1"))
                .andExpect(jsonPath("$.durationSeconds").value(7200))
                .andExpect(jsonPath("$.retracted").value(false));
        // 历史窗口行保留，11 点不再命中，15 点命中新窗口
        mockMvc.perform(get("/api/stations/st-1/maintenance-windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].windowId").value("win-1"))
                .andExpect(jsonPath("$[1].windowId").value("win-2"));
        mockMvc.perform(get("/api/stations/st-1/maintenance-windows/effective").param("atUtc", T11))
                // 有效集合为更正后的 win-2（1 条），但业务时刻 11:00 不落在 [14,16)
                .andExpect(jsonPath("$.effectiveCount").value(1))
                .andExpect(jsonPath("$.matchedWindowId").doesNotExist());
        mockMvc.perform(get("/api/stations/st-1/maintenance-windows/effective").param("atUtc", T15))
                .andExpect(jsonPath("$.effectiveCount").value(1))
                .andExpect(jsonPath("$.matchedWindowId").value("win-2"));

        // 撤回 win-2：只追加撤回行，不带区间
        correctWindow("req-c-3", "win-3", "st-1", "win-2", null, null, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.retracted").value(true))
                .andExpect(jsonPath("$.startAtUtc").doesNotExist());
        mockMvc.perform(get("/api/stations/st-1/maintenance-windows/effective").param("atUtc", T15))
                .andExpect(jsonPath("$.effectiveCount").value(0));
        // 历史三行完整保留
        mockMvc.perform(get("/api/stations/st-1/maintenance-windows"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void correctingHistoricalOrUnknownWindowRejected() throws Exception {
        registerStation("req-c-4", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerStation("req-c-5", "st-2", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-c-6", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        correctWindow("req-c-7", "win-2", "st-1", "win-1", T14, T16, false).andExpect(status().isOk());
        // 已被替换的历史窗口不能再改，只能追加更正当前有效窗口
        correctWindow("req-c-8", "win-x", "st-1", "win-1", T09, T10, false)
                .andExpect(status().isBadRequest());
        // 未知窗口：404；跨站引用：404
        correctWindow("req-c-9", "win-y", "st-1", "missing", T09, T10, false)
                .andExpect(status().isNotFound());
        registerWindow("req-c-10", "win-s2", "st-2", T10, T12).andExpect(status().isCreated());
        correctWindow("req-c-11", "win-z", "st-1", "win-s2", T14, T15, false)
                .andExpect(status().isNotFound());
        // 撤回携带时间区间：400；更正不带区间且非撤回：400
        correctWindow("req-c-12", "win-bad1", "st-1", "win-2", T14, T15, true)
                .andExpect(status().isBadRequest());
        correctWindow("req-c-13", "win-bad2", "st-1", "win-2", null, null, false)
                .andExpect(status().isBadRequest());
        assertThat(tableCount("maintenance_window", "station_id", "st-1")).isEqualTo(2);
    }

    // ---------- 批量提交与窗口标记 ----------

    @Test
    void batchSubmitMarksEachItemByStationAndHalfOpenTime() throws Exception {
        registerStation("req-b-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerStation("req-b-1", "st-2", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-b-2", "win-1", "st-1", T10, T12).andExpect(status().isCreated());

        List<Map<String, Object>> items = List.of(
                submission("sub-1", "st-1", "obs-1", T11, "站点A", "1.0", "窗口内受影响"),
                submission("sub-2", "st-1", "obs-1", T12, "站点A", "2.0", "右开边界不受影响"),
                submission("sub-3", "st-2", "obs-2", T11, "站点B", "3.0", "无窗口站点"));
        submitBatch("req-b-3", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.submissions[0].maintenanceAffected").value(true))
                .andExpect(jsonPath("$.submissions[0].windowId").value("win-1"))
                .andExpect(jsonPath("$.submissions[0].windowVersion").value(1))
                .andExpect(jsonPath("$.submissions[0].status").value("PENDING"))
                .andExpect(jsonPath("$.submissions[1].maintenanceAffected").value(false))
                .andExpect(jsonPath("$.submissions[1].windowId").doesNotExist())
                .andExpect(jsonPath("$.submissions[2].maintenanceAffected").value(false));

        // 左闭边界 10:00 命中
        submitBatch("req-b-4", List.of(
                        submission("sub-4", "st-1", "obs-1", T10, "站点A", "4.0", "左闭边界")))
                .andExpect(jsonPath("$.submissions[0].maintenanceAffected").value(true));

        // 证据查询显示维护标记与原始记录
        mockMvc.perform(get("/api/observation-submissions/sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("1.0"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.batchRequestId").value("req-b-3"));
        // 批次明细查询
        mockMvc.perform(get("/api/observation-submissions/batches/req-b-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.submissions.length()").value(3));
    }

    @Test
    void batchRollsBackOnUnknownStationAndInvalidFormat() throws Exception {
        registerStation("req-b-5", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-b-6", "win-1", "st-1", T10, T12).andExpect(status().isCreated());

        // 未知站点：整批回滚，前一条也不留半成品
        submitBatch("req-b-7", List.of(
                        submission("sub-rb1", "st-1", "obs-1", T11, "站点A", "1.0", "本应回滚"),
                        submission("sub-rb2", "st-x", "obs-1", T11, "站点X", "2.0", "未知站点")))
                .andExpect(status().isNotFound());
        assertThat(tableCount("observation_submission", "submission_id", "sub-rb1")).isZero();
        assertThat(tableCount("observation_submission", "submission_id", "sub-rb2")).isZero();

        // 读数格式非法：400，整批回滚
        submitBatch("req-b-8", List.of(
                        submission("sub-rb3", "st-1", "obs-1", T11, "站点A", "1.2345", "四位小数"),
                        submission("sub-rb4", "st-1", "obs-1", T11, "站点A", "abc", "非数字")))
                .andExpect(status().isBadRequest());
        assertThat(tableCount("observation_submission", "submission_id", "sub-rb3")).isZero();

        // 批内提交标识重复：400；跨批重复：409
        submitBatch("req-b-9", List.of(
                        submission("sub-dup", "st-1", "obs-1", T11, "站点A", "1.0", "第一条"),
                        submission("sub-dup", "st-1", "obs-1", T11, "站点A", "2.0", "批内重复")))
                .andExpect(status().isBadRequest());
        submitBatch("req-b-10", List.of(
                submission("sub-uniq", "st-1", "obs-1", T11, "站点A", "1.0", "唯一")))
                .andExpect(status().isOk());
        submitBatch("req-b-11", List.of(
                        submission("sub-uniq", "st-1", "obs-1", T11, "站点A", "9.0", "跨批重复")))
                .andExpect(status().isConflict());
        assertThat(tableCount("observation_submission", "submission_id", "sub-uniq")).isEqualTo(1);
    }

    @Test
    void batchIdempotentReplayConflictAndFailedKeyReuse() throws Exception {
        registerStation("req-b-12", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-b-13", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(
                submission("sub-idem", "st-1", "obs-1", T11, "站点A", "1.0", "备注"));
        submitBatch("req-b-14", items).andExpect(status().isOk());
        // 同参重放：首次结果，不重复落库
        submitBatch("req-b-14", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedCount").value(1));
        assertThat(tableCount("observation_submission", "batch_request_id", "req-b-14")).isEqualTo(1);
        // 异参 409
        submitBatch("req-b-14", List.of(
                        submission("sub-idem2", "st-1", "obs-1", T11, "站点A", "2.0", "异参")))
                .andExpect(status().isConflict());
        // 失败不占键：未知站点失败后，同 requestId 换合法批次成功
        submitBatch("req-b-15", List.of(
                        submission("sub-fail", "st-x", "obs-1", T11, "站点X", "1.0", "失败")))
                .andExpect(status().isNotFound());
        submitBatch("req-b-15", List.of(
                        submission("sub-ok", "st-1", "obs-1", T11, "站点A", "1.0", "重试成功")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions[0].maintenanceAffected").value(true));
        assertThat(tableCount("request_log", "request_id", "req-b-15")).isEqualTo(1);
    }

    @Test
    void submissionBeforeWindowRegistrationKeepsUnmarkedSnapshot() throws Exception {
        registerStation("req-b-16", "st-1", ACTIVATED).andExpect(status().isCreated());
        // 窗口登记前先提交（提交时刻落在后来窗口区间内）：按提交时版本保留为未受影响
        submitBatch("req-b-17", List.of(
                        submission("sub-before", "st-1", "obs-1", T11, "站点A", "1.0", "窗口登记前提交")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions[0].maintenanceAffected").value(false));
        // 窗口变更后提交：按新版本标记受影响
        registerWindow("req-b-18", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        submitBatch("req-b-19", List.of(
                        submission("sub-after", "st-1", "obs-1", T11, "站点A", "2.0", "窗口登记后提交")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions[0].maintenanceAffected").value(true));
        // 早先提交的固化标记不随后续窗口变更改写
        mockMvc.perform(get("/api/observation-submissions/sub-before"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false));
    }

    // ---------- 冲突簇自动合并 ----------

    @Test
    void clusterAutoMergePicksLatestNonAffectedAndNeverAffectedWinner() throws Exception {
        registerStation("req-m-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-m-1", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        submitBatch("req-m-2", List.of(
                submission("sub-a1", "st-1", "obs-1", T1030, "受影响地点", "1.0", "受影响1"),
                submission("sub-a2", "st-1", "obs-1", T1130, "受影响地点", "9.0", "受影响2（时刻最新但受影响）"),
                submission("sub-n1", "st-1", "obs-1", T09, "正常地点", "2.0", "未受影响1"),
                submission("sub-n2", "st-1", "obs-1", T0930, "正常地点", "3.5", "未受影响2（时刻最新）")))
                .andExpect(status().isOk());

        mergeCluster("req-m-3", "obs-1", List.of("sub-a1", "sub-a2", "sub-n1", "sub-n2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTO_MERGED"))
                .andExpect(jsonPath("$.clusterSize").value(4))
                .andExpect(jsonPath("$.affectedCount").value(2))
                .andExpect(jsonPath("$.winningSubmissionId").value("sub-n2"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false))
                .andExpect(jsonPath("$.maintenanceWindowId").doesNotExist())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.submissionIds.length()").value(4));

        // 胜出为最新的未受影响观测，受影响观测不能自动胜出
        mockMvc.perform(get("/api/observations/obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("3.5"))
                .andExpect(jsonPath("$.note").value("未受影响2（时刻最新）"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false));
        mockMvc.perform(get("/api/observations/obs-1/versions/1"))
                .andExpect(jsonPath("$.maintenanceAffected").value(false))
                .andExpect(jsonPath("$.maintenanceWindowId").doesNotExist());
        // 簇内全部提交已消费
        for (String id : List.of("sub-a1", "sub-a2", "sub-n1", "sub-n2")) {
            mockMvc.perform(get("/api/observation-submissions/" + id))
                    .andExpect(jsonPath("$.status").value("CONSUMED"));
        }
        // 无未消费提交时再次合并：409
        mergeCluster("req-m-4", "obs-1", List.of()).andExpect(status().isBadRequest());
        mergeCluster("req-m-5", "obs-1", List.of("sub-n2")).andExpect(status().isConflict());
        // 同一 requestId 同参重放：簇虽已消费，仍返回首次合并结果，不重复加版本
        mergeCluster("req-m-3", "obs-1", List.of("sub-a1", "sub-a2", "sub-n1", "sub-n2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTO_MERGED"))
                .andExpect(jsonPath("$.winningSubmissionId").value("sub-n2"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(tableCount("cluster_merge", "merge_id", "req-m-3")).isEqualTo(1);
        assertThat(tableCount("observation_version", "observation_id", "obs-1")).isEqualTo(1);
        // 同一 requestId 异参：409
        mergeCluster("req-m-3", "obs-1", List.of("sub-a1"))
                .andExpect(status().isConflict());
    }

    @Test
    void clusterMergeRequiresExactlyPendingSet() throws Exception {
        registerStation("req-m-6", "st-1", ACTIVATED).andExpect(status().isCreated());
        submitBatch("req-m-7", List.of(
                submission("sub-p1", "st-1", "obs-2", T09, "地点", "1.0", "备注1"),
                submission("sub-p2", "st-1", "obs-2", T0930, "地点", "2.0", "备注2")))
                .andExpect(status().isOk());
        // 遗漏簇成员：400 且不消费
        mergeCluster("req-m-8", "obs-2", List.of("sub-p1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sub-p2")));
        // 夹带非本簇提交：400
        mergeCluster("req-m-9", "obs-2", List.of("sub-p1", "sub-p2", "sub-ghost"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/observation-submissions/sub-p1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // 完整集合成功；无内容变化时 contentChanged=false、不加版本（先经原 API 建同内容 v1）
        mergeCluster("req-m-10", "obs-2", List.of("sub-p1", "sub-p2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTO_MERGED"))
                .andExpect(jsonPath("$.winningSubmissionId").value("sub-p2"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void clusterMergeContentUnchangedDoesNotAddVersion() throws Exception {
        registerStation("req-m-11", "st-1", ACTIVATED).andExpect(status().isCreated());
        // 先经原创建 API 建立 obs-3 v1，内容与稍后未受影响胜出提交完全一致（读数按数值）
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-m-12", "observationId", "obs-3",
                                "location", "地点", "reading", "1.00", "note", "相同备注"))))
                .andExpect(status().isCreated());
        submitBatch("req-m-13", List.of(
                        submission("sub-same", "st-1", "obs-3", T09, "地点", "1.0", "相同备注")))
                .andExpect(status().isOk());
        mergeCluster("req-m-14", "obs-3", List.of("sub-same"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.baseVersion").value(1));
        assertThat(tableCount("observation_version", "observation_id", "obs-3")).isEqualTo(1);
    }

    // ---------- 全受影响 → 待人工裁决 ----------

    @Test
    void allAffectedClusterGoesPendingManualAndDecisionCanChooseAffected() throws Exception {
        registerStation("req-d-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-d-1", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        submitBatch("req-d-2", List.of(
                submission("sub-f1", "st-1", "obs-9", T1030, "受影响地点A", "1.0", "受影响候选A"),
                submission("sub-f2", "st-1", "obs-9", T11, "受影响地点B", "2.0", "受影响候选B")))
                .andExpect(status().isOk());

        // 全簇受影响：待人工裁决，不任选胜出、不落版本
        mergeCluster("req-d-3", "obs-9", List.of("sub-f1", "sub-f2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.clusterSize").value(2))
                .andExpect(jsonPath("$.affectedCount").value(2))
                .andExpect(jsonPath("$.winningSubmissionId").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.adjudicator").doesNotExist());
        mockMvc.perform(get("/api/observations/obs-9")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observation-submissions/sub-f1"))
                .andExpect(jsonPath("$.status").value("REVIEW"));
        // 待裁定簇不能再次自动合并
        mergeCluster("req-d-4", "obs-9", List.of("sub-f1", "sub-f2"))
                .andExpect(status().isConflict());
        // 但原 requestId 同参重放返回首次的 PENDING_MANUAL 结果
        mergeCluster("req-d-3", "obs-9", List.of("sub-f1", "sub-f2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_MANUAL"))
                .andExpect(jsonPath("$.winningSubmissionId").doesNotExist());
        assertThat(tableCount("cluster_merge", "merge_id", "req-d-3")).isEqualTo(1);
        // 裁决前置校验：理由/裁决人缺失、胜出不在簇内
        decide("req-d-5", "req-d-3", "sub-f2", "", "理由")
                .andExpect(status().isBadRequest());
        decide("req-d-6", "req-d-3", "sub-f2", "op", "")
                .andExpect(status().isBadRequest());
        decide("req-d-7", "req-d-3", "sub-ghost", "op", "理由")
                .andExpect(status().isBadRequest());

        // 人工裁决可选择受影响观测，写入理由和裁决人
        decide("req-d-8", "req-d-3", "sub-f2", "operator-a", "窗口期间读数经人工复核可信")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESOLVED"))
                .andExpect(jsonPath("$.winningSubmissionId").value("sub-f2"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.maintenanceWindowId").value("win-1"))
                .andExpect(jsonPath("$.maintenanceWindowVersion").value(1))
                .andExpect(jsonPath("$.adjudicator").value("operator-a"))
                .andExpect(jsonPath("$.reason").value("窗口期间读数经人工复核可信"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/observations/obs-9"))
                .andExpect(jsonPath("$.reading").value("2.0"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.maintenanceWindowId").value("win-1"))
                .andExpect(jsonPath("$.maintenanceWindowVersion").value(1));
        mockMvc.perform(get("/api/observations/obs-9/versions/1"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.maintenanceWindowVersion").value(1));
        for (String id : List.of("sub-f1", "sub-f2")) {
            mockMvc.perform(get("/api/observation-submissions/" + id))
                    .andExpect(jsonPath("$.status").value("CONSUMED"));
        }
        // 已裁决簇不能再次裁决
        decide("req-d-9", "req-d-3", "sub-f1", "op", "再次裁决")
                .andExpect(status().isConflict());
        // 同参重放裁决：返回首次结果，不产生第二版本
        decide("req-d-8", "req-d-3", "sub-f2", "operator-a", "窗口期间读数经人工复核可信")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(tableCount("cluster_merge", "observation_id", "obs-9")).isEqualTo(1);
        mockMvc.perform(get("/api/cluster-merges/req-d-3"))
                .andExpect(jsonPath("$.state").value("RESOLVED"))
                .andExpect(jsonPath("$.adjudicator").value("operator-a"));
        mockMvc.perform(get("/api/observations/obs-9/cluster-merges"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void laterWindowRetractionDoesNotRewriteFrozenSnapshots() throws Exception {
        registerStation("req-h-0", "st-1", ACTIVATED).andExpect(status().isCreated());
        registerWindow("req-h-1", "win-1", "st-1", T10, T12).andExpect(status().isCreated());
        submitBatch("req-h-2", List.of(
                        submission("sub-z1", "st-1", "obs-8", T11, "受影响地点", "5.0", "受影响"),
                        submission("sub-z2", "st-1", "obs-8", T1130, "受影响地点", "6.0", "受影响二")))
                .andExpect(status().isOk());
        mergeCluster("req-h-3", "obs-8", List.of("sub-z1", "sub-z2")).andExpect(status().isOk());
        decide("req-h-4", "req-h-3", "sub-z2", "op", "采纳").andExpect(status().isOk());
        // 事后撤回窗口：历史快照、当前版本与提交证据的固化标记均不改写
        correctWindow("req-h-5", "win-2", "st-1", "win-1", null, null, true).andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/obs-8/versions/1"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.maintenanceWindowId").value("win-1"))
                .andExpect(jsonPath("$.maintenanceWindowVersion").value(1));
        mockMvc.perform(get("/api/observations/obs-8"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true));
        mockMvc.perform(get("/api/observation-submissions/sub-z2"))
                .andExpect(jsonPath("$.maintenanceAffected").value(true))
                .andExpect(jsonPath("$.windowId").value("win-1"));
        mockMvc.perform(get("/api/cluster-merges/req-h-3"))
                .andExpect(jsonPath("$.maintenanceWindowId").value("win-1"))
                .andExpect(jsonPath("$.state").value("RESOLVED"));
    }
}
