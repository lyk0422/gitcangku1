package com.example.starter.observation.geofence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测地理围栏 API 的主流程、失败分支、幂等与不可变边界测试（真实 H2 内存库，MySQL 兼容模式）。
 * 服务端提交/裁决时刻经可控时钟注入，默认代理为真实 UTC 时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeofenceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        jdbcTemplate.update("DELETE FROM geofence_adjudication");
        jdbcTemplate.update("DELETE FROM geofence_observation");
        jdbcTemplate.update("DELETE FROM site_geofence");
        jdbcTemplate.update("DELETE FROM geofence_site");
        jdbcTemplate.update("DELETE FROM geofence_request_log");
    }

    // ---------- 请求构造辅助 ----------

    private ResultActions createFence(String requestId, String fenceId, String siteId,
                                      Object minLat, Object maxLat, Object minLon, Object maxLon,
                                      String from, String to, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("fenceId", fenceId);
        body.put("siteId", siteId);
        body.put("minLatitude", minLat);
        body.put("maxLatitude", maxLat);
        body.put("minLongitude", minLon);
        body.put("maxLongitude", maxLon);
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geofence/fences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions updateFence(String fenceId, String requestId,
                                      Object minLat, Object maxLat, Object minLon, Object maxLon,
                                      String from, String to, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("minLatitude", minLat);
        body.put("maxLatitude", maxLat);
        body.put("minLongitude", minLon);
        body.put("maxLongitude", maxLon);
        body.put("effectiveFromUtc", from);
        body.put("effectiveToUtc", to);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geofence/fences/{id}/updates", fenceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String observationId, String siteId, Object lat, Object lon,
                                     String observedAtUtc, String payload) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("siteId", siteId);
        item.put("latitude", lat);
        item.put("longitude", lon);
        item.put("observedAtUtc", observedAtUtc);
        if (payload != null) {
            item.put("payload", payload);
        }
        return item;
    }

    private ResultActions submitBatch(String requestId, String operator,
                                      List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/geofence/observations/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String observationId, String requestId, String adjudicationId,
                                     String operator, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("operator", operator);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post("/api/geofence/observations/{id}/adjudicate", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM geofence_observation", Integer.class);
        return count == null ? 0 : count;
    }

    private int fenceVersionCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM site_geofence", Integer.class);
        return count == null ? 0 : count;
    }

    private void createStandardFence(String requestId) throws Exception {
        createFence(requestId, "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated());
    }

    // ---------- 围栏配置 ----------

    @Test
    void createFenceReturnsVersionOne() throws Exception {
        createFence("req-f1", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceId").value("fence-1"))
                .andExpect(jsonPath("$.fenceVersion").value(1))
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.minLatitude").value(30.0))
                .andExpect(jsonPath("$.maxLongitude").value(121.0))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-09-28T10:00:00Z"))
                .andExpect(jsonPath("$.effectiveToUtc").value("2026-09-28T12:00:00Z"))
                .andExpect(jsonPath("$.operator").value("op-admin"));

        mockMvc.perform(get("/api/geofence/fences/{id}", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fenceVersion").value(1));
        mockMvc.perform(get("/api/geofence/fences/{id}/versions", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/geofence/sites/{siteId}/fences", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createFenceWithOutOfRangeCoordinateReturns422WithDifference() throws Exception {
        createFence("req-f2", "fence-1", "site-1", 30, 95, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("maxLatitude"))
                .andExpect(jsonPath("$.details[0].actual").value("95"))
                .andExpect(jsonPath("$.details[0].required").value("[-90, 90]"))
                .andExpect(jsonPath("$.details[0].difference").value("5"));
        createFence("req-f3", "fence-1", "site-1", 30, 31, -181, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("minLongitude"))
                .andExpect(jsonPath("$.details[0].difference").value("-1"));
        // 小数精度超过 7 位同样拒绝
        createFence("req-f4", "fence-1", "site-1", 30.12345678, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity());
        assertThat(fenceVersionCount()).isZero();
    }

    @Test
    void createFenceWithInvalidBoundaryReturns422() throws Exception {
        createFence("req-f5", "fence-1", "site-1", 31, 30, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("latitudeBoundary"));
        createFence("req-f6", "fence-1", "site-1", 30, 31, 121, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("longitudeBoundary"));
        assertThat(fenceVersionCount()).isZero();
    }

    @Test
    void createFenceWithInvalidIntervalReturns422WithActualDuration() throws Exception {
        // 起始晚于结束：返回实际时长（负值）与要求
        createFence("req-f7", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T12:00:00Z", "2026-09-28T10:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("effectiveInterval"))
                .andExpect(jsonPath("$.details[0].actual").value("PT-2H"))
                .andExpect(jsonPath("$.details[0].required").isNotEmpty());
        // 起止相等：零时长同样非法
        createFence("req-f8", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T10:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].actual").value("PT0S"));
        // 时刻格式非法
        createFence("req-f9", "fence-1", "site-1", 30, 31, 120, 121,
                "not-a-time", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("effectiveFromUtc"));
        assertThat(fenceVersionCount()).isZero();
    }

    @Test
    void overlappingFenceOnSameSiteReturns409() throws Exception {
        createFence("req-f10", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        // 同站点生效区间重叠：409
        createFence("req-f11", "fence-2", "site-1", 40, 41, 130, 131,
                "2026-09-28T11:00:00Z", "2026-09-28T13:00:00Z", "op-admin")
                .andExpect(status().isConflict());
        // 左闭右开：紧邻区间 [12:00, 14:00) 不重叠
        createFence("req-f12", "fence-2", "site-1", 40, 41, 130, 131,
                "2026-09-28T12:00:00Z", "2026-09-28T14:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        // 不同站点重叠区间互不影响
        createFence("req-f13", "fence-3", "site-2", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        assertThat(fenceVersionCount()).isEqualTo(3);
    }

    @Test
    void duplicateFenceIdReturns409() throws Exception {
        createFence("req-f14", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        createFence("req-f15", "fence-1", "site-2", 30, 31, 120, 121,
                "2026-09-28T14:00:00Z", "2026-09-28T16:00:00Z", "op-admin")
                .andExpect(status().isConflict());
        assertThat(fenceVersionCount()).isEqualTo(1);
    }

    @Test
    void updateFenceAppendsImmutableVersion() throws Exception {
        createFence("req-f16", "fence-1", "site-1", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        updateFence("fence-1", "req-f17", 32, 33, 122, 123,
                "2026-09-28T12:00:00Z", "2026-09-28T14:00:00Z", "op-admin")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fenceVersion").value(2))
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.minLatitude").value(32.0));
        // 历史版本不可变：版本 1 保持原边界与区间
        mockMvc.perform(get("/api/geofence/fences/{id}/versions", "fence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fenceVersion").value(1))
                .andExpect(jsonPath("$[0].minLatitude").value(30.0))
                .andExpect(jsonPath("$[1].fenceVersion").value(2))
                .andExpect(jsonPath("$[1].minLatitude").value(32.0));
        // 最新版本查询返回版本 2
        mockMvc.perform(get("/api/geofence/fences/{id}", "fence-1"))
                .andExpect(jsonPath("$.fenceVersion").value(2));
        // 与历史版本区间重叠的变更：409
        updateFence("fence-1", "req-f18", 32, 33, 122, 123,
                "2026-09-28T11:00:00Z", "2026-09-28T13:00:00Z", "op-admin")
                .andExpect(status().isConflict());
        assertThat(fenceVersionCount()).isEqualTo(2);
    }

    @Test
    void updateMissingFenceReturns404() throws Exception {
        updateFence("fence-x", "req-f19", 30, 31, 120, 121,
                "2026-09-28T10:00:00Z", "2026-09-28T12:00:00Z", "op-admin")
                .andExpect(status().isNotFound());
    }

    // ---------- 离线观测批量提交 ----------

    @Test
    void batchSubmitMatchesFenceByObservedAt() throws Exception {
        createStandardFence("req-b0");
        List<Map<String, Object>> items = List.of(
                item("obs-a", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", "围栏内"),
                item("obs-b", "site-1", 32.0, 120.5, "2026-09-28T11:00:00Z", "围栏外"),
                item("obs-c", "site-1", 30.5, 120.5, "2026-09-28T13:00:00Z", "无生效围栏"),
                item("obs-d", "site-2", 30.5, 120.5, "2026-09-28T11:00:00Z", null));
        submitBatch("req-b1", "op-field", items)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.acceptedCount").value(3))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.acceptedRatio").value(0.75))
                .andExpect(jsonPath("$.items[0].matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[0].matchedFenceVersion").value(1))
                .andExpect(jsonPath("$.items[1].matchResult").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.items[1].matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.items[2].matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[2].matchedFenceId").doesNotExist())
                .andExpect(jsonPath("$.items[3].matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[3].matchedFenceId").doesNotExist());

        // 证据明细：原始坐标、匹配结果与提交时刻落库
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(32.0))
                .andExpect(jsonPath("$.matchResult").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"))
                .andExpect(jsonPath("$.matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.matchedFenceVersion").value(1))
                .andExpect(jsonPath("$.payload").value("围栏外"));
        // 无生效围栏的接受记录：围栏字段不返回
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceId").doesNotExist())
                .andExpect(jsonPath("$.matchedFenceVersion").doesNotExist());
        assertThat(observationCount()).isEqualTo(4);
    }

    @Test
    void batchWithInvalidItemRollsBackEverything() throws Exception {
        createStandardFence("req-b2");
        List<Map<String, Object>> items = List.of(
                item("obs-ok", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null),
                item("obs-bad", "site-2", 95.0, 120.5, "2026-09-28T11:00:00Z", null));
        submitBatch("req-b3", "op-field", items)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("items[1].latitude"))
                .andExpect(jsonPath("$.details[0].actual").value("95.0"));
        // 整批回滚：合法条目也不落库
        assertThat(observationCount()).isZero();
        // 失败不占键：同一 requestId 修正参数后可正常执行
        List<Map<String, Object>> fixed = List.of(
                item("obs-ok", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null),
                item("obs-bad", "site-2", 30.6, 120.5, "2026-09-28T11:00:00Z", null));
        submitBatch("req-b3", "op-field", fixed)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCount").value(2));
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void batchSizeOutOfLimitReturns422WithActualAndRequired() throws Exception {
        submitBatch("req-b4", "op-field", List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("items"))
                .andExpect(jsonPath("$.details[0].actual").value("0"))
                .andExpect(jsonPath("$.details[0].required").value("1..100"));

        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            oversized.add(item("obs-x" + i, "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null));
        }
        submitBatch("req-b5", "op-field", oversized)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].actual").value("101"));
        assertThat(observationCount()).isZero();
    }

    @Test
    void batchWithDuplicateObservationIdsReturns422() throws Exception {
        List<Map<String, Object>> items = List.of(
                item("obs-dup", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null),
                item("obs-dup", "site-1", 30.6, 120.6, "2026-09-28T11:00:00Z", null));
        submitBatch("req-b6", "op-field", items)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("items[1].observationId"));
        assertThat(observationCount()).isZero();
    }

    @Test
    void batchWithExistingObservationIdReturns409() throws Exception {
        submitBatch("req-b7", "op-field",
                List.of(item("obs-e", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        submitBatch("req-b8", "op-field",
                List.of(item("obs-e", "site-1", 30.6, 120.6, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void batchReplayReturnsFirstSnapshotAndDifferentParamsReturns409() throws Exception {
        List<Map<String, Object>> items = List.of(
                item("obs-r1", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null));
        MvcResult first = submitBatch("req-b9", "op-field", items)
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放：返回首次完整响应快照（含提交时刻），不产生新数据
        MvcResult replay = submitBatch("req-b9", "op-field", items)
                .andExpect(status().isCreated()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(observationCount()).isEqualTo(1);
        // 同键异参：409
        submitBatch("req-b9", "op-field",
                List.of(item("obs-r2", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void fenceChangeDoesNotRecomputeHistoricalMatch() throws Exception {
        createStandardFence("req-b10");
        submitBatch("req-b11", "op-field",
                List.of(item("obs-h1", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].matchResult").value("ACCEPTED"));
        // 变更围栏：新区间边界不再覆盖原坐标
        updateFence("fence-1", "req-b12", 50, 51, 120, 121,
                "2026-09-28T12:00:00Z", "2026-09-28T14:00:00Z", "op-admin")
                .andExpect(status().isCreated());
        // 历史接受结果不重算：匹配结果与围栏版本保持原值
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchedFenceId").value("fence-1"))
                .andExpect(jsonPath("$.matchedFenceVersion").value(1));
        // 同一采集时刻的新提交仍按采集时刻匹配到版本 1（区间左闭右开）
        submitBatch("req-b13", "op-field",
                List.of(item("obs-h2", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].matchedFenceVersion").value(1));
    }

    @Test
    void submittedAtUtcUsesServerClock() throws Exception {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(fixed);
        submitBatch("req-b14", "op-field",
                List.of(item("obs-clock", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submittedAtUtc").value("2026-01-01T00:00:00Z"));
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-clock"))
                .andExpect(jsonPath("$.submittedAtUtc").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void listSiteObservationsHasStableOrderingAndStatusFilter() throws Exception {
        createStandardFence("req-b15");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        submitBatch("req-b16", "op-field",
                List.of(item("obs-s2", "site-1", 32.0, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:01:00Z"));
        submitBatch("req-b17", "op-field",
                List.of(item("obs-s1", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        // 按提交时刻升序：obs-s2 先于 obs-s1（与标识字母序相反）
        mockMvc.perform(get("/api/geofence/sites/{siteId}/observations", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].observationId").value("obs-s2"))
                .andExpect(jsonPath("$[1].observationId").value("obs-s1"));
        // 状态过滤
        mockMvc.perform(get("/api/geofence/sites/{siteId}/observations?status=GEOFENCE_REJECTED", "site-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].observationId").value("obs-s2"));
        mockMvc.perform(get("/api/geofence/sites/{siteId}/observations?status=UNKNOWN", "site-1"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 人工裁决 ----------

    @Test
    void adjudicateRejectedObservationRequiresReasonAndFlipsStatus() throws Exception {
        createStandardFence("req-a0");
        submitBatch("req-a1", "op-field",
                List.of(item("obs-rej", "site-1", 32.0, 120.5, "2026-09-28T11:00:00Z", "证据")))
                .andExpect(status().isCreated());

        adjudicate("obs-rej", "req-a2", "adj-1", "op-review", "现场复核确认坐标有效")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.reason").value("现场复核确认坐标有效"))
                .andExpect(jsonPath("$.observationStatus").value("ACCEPTED"));

        // 当前状态改为 ACCEPTED；原始匹配结果不可变
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-rej"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.matchResult").value("GEOFENCE_REJECTED"));
        // 裁决记录可按标识与按观测查询
        mockMvc.perform(get("/api/geofence/adjudications/{id}", "adj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-rej"))
                .andExpect(jsonPath("$.operator").value("op-review"));
        mockMvc.perform(get("/api/geofence/observations/{id}/adjudications", "obs-rej"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].adjudicationId").value("adj-1"));
    }

    @Test
    void adjudicateWithoutReasonReturns422() throws Exception {
        createStandardFence("req-a3");
        submitBatch("req-a4", "op-field",
                List.of(item("obs-rej", "site-1", 32.0, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        adjudicate("obs-rej", "req-a5", "adj-1", "op-review", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("reason"));
        adjudicate("obs-rej", "req-a6", "adj-1", "op-review", "   ")
                .andExpect(status().isUnprocessableEntity());
        // 状态未被修改，requestId 不占键
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-rej"))
                .andExpect(jsonPath("$.status").value("GEOFENCE_REJECTED"));
        adjudicate("obs-rej", "req-a5", "adj-1", "op-review", "补充理由")
                .andExpect(status().isOk());
    }

    @Test
    void adjudicateAcceptedObservationReturns409() throws Exception {
        createStandardFence("req-a7");
        submitBatch("req-a8", "op-field",
                List.of(item("obs-acc", "site-1", 30.5, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        adjudicate("obs-acc", "req-a9", "adj-1", "op-review", "无需裁决")
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-acc"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_adjudication", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void adjudicationReplayAndReuse() throws Exception {
        createStandardFence("req-a10");
        submitBatch("req-a11", "op-field",
                List.of(item("obs-rej", "site-1", 32.0, 120.5, "2026-09-28T11:00:00Z", null)))
                .andExpect(status().isCreated());
        adjudicate("obs-rej", "req-a12", "adj-1", "op-review", "理由")
                .andExpect(status().isOk());
        // 同 adjudicationId 同参（不同 requestId）：重放原结果
        adjudicate("obs-rej", "req-a13", "adj-1", "op-review", "理由")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"));
        // 同 adjudicationId 异参：409
        adjudicate("obs-rej", "req-a14", "adj-1", "op-review", "另一个理由")
                .andExpect(status().isConflict());
        // 同 requestId 同参：重放首次响应快照
        adjudicate("obs-rej", "req-a12", "adj-1", "op-review", "理由")
                .andExpect(status().isOk());
        // 同 requestId 异参：409
        adjudicate("obs-rej", "req-a12", "adj-2", "op-review", "理由")
                .andExpect(status().isConflict());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_adjudication", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 查询 404 ----------

    @Test
    void queriesOnMissingResourcesReturn404() throws Exception {
        mockMvc.perform(get("/api/geofence/fences/{id}", "fence-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofence/fences/{id}/versions", "fence-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofence/observations/{id}", "obs-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofence/observations/{id}/adjudications", "obs-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/geofence/adjudications/{id}", "adj-x"))
                .andExpect(status().isNotFound());
    }
}
