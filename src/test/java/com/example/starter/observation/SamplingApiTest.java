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

import java.sql.Timestamp;
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
 * 采样许可与采样提交 API 的主流程、失败分支、交集冲突、唯一命中、上限、提前结束、撤销与幂等测试。
 * 真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplingApiTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String FROM = "2026-10-01T00:00:00Z";
    private static final String TO = "2026-11-01T00:00:00Z";
    private static final String AT = "2026-10-10T08:00:00Z";

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
        jdbcTemplate.update("DELETE FROM merge_cluster");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM permit_sample_key");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
        Mockito.when(clock.instant()).thenReturn(NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions createPermit(String requestId, String permitId, String deviceId,
                                       int gridMin, int gridMax, String from, String to, int max)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("permitVersion", "v1");
        body.put("gridMin", gridMin);
        body.put("gridMax", gridMax);
        body.put("validFromUtc", from);
        body.put("validToUtc", to);
        body.put("maxObservations", max);
        return mockMvc.perform(post("/api/sampling/permits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions sample(String requestId, String observationId, String deviceId,
                                 Integer gridId, String observedAt, String location,
                                 String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("deviceId", deviceId);
        body.put("gridId", gridId);
        body.put("observedAtUtc", observedAt);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/sampling/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions terminate(String requestId, String permitId, String terminatedAt) throws Exception {
        return mockMvc.perform(post("/api/sampling/permits/{id}/terminate", permitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId,
                        "terminatedAtUtc", terminatedAt))));
    }

    private ResultActions revoke(String requestId, String permitId, String reason) throws Exception {
        return mockMvc.perform(post("/api/sampling/permits/{id}/revoke", permitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId, "reason", reason))));
    }

    private int permitCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observed_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? 0 : count;
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_current", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 绕过交集检查直接插入第二张许可行，用于构造“同一时刻命中两张许可”的 NOT_UNIQUE 数据场景。
     */
    private void insertPermitRow(String permitId, String deviceId, int gridMin, int gridMax,
                                 String from, String to, int max) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, permit_version, grid_min, grid_max, "
                        + "valid_from_utc, valid_to_utc, terminated_at_utc, revoked_at_utc, status, "
                        + "max_observations, observed_count, created_at) "
                        + "VALUES (?, ?, 'v1', ?, ?, ?, ?, NULL, NULL, 'ACTIVE', ?, 0, CURRENT_TIMESTAMP)",
                permitId, deviceId, gridMin, gridMax,
                Timestamp.from(Instant.parse(from)), Timestamp.from(Instant.parse(to)), max);
    }

    // ---------- 许可创建 ----------

    @Test
    void createPermitReturnsCountsAndRange() throws Exception {
        createPermit("req-p1", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.permitVersion").value("v1"))
                .andExpect(jsonPath("$.gridMin").value(10))
                .andExpect(jsonPath("$.gridMax").value(20))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maxObservations").value(5))
                .andExpect(jsonPath("$.observedCount").value(0))
                .andExpect(jsonPath("$.remaining").value(5));

        mockMvc.perform(get("/api/sampling/permits/permit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(5));
    }

    @Test
    void overlappingPermitInGridAndTimeReturns409() throws Exception {
        createPermit("req-p2", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        // 网格与时间均相交
        createPermit("req-p3", "permit-2", "dev-1", 15, 25, FROM, TO, 5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_OVERLAP"));
        // 网格相邻但不共享单元：允许
        createPermit("req-p4", "permit-3", "dev-1", 21, 30, FROM, TO, 5)
                .andExpect(status().isCreated());
        // 时间窗仅在右端点相接（左闭右开）：允许
        createPermit("req-p5", "permit-4", "dev-1", 10, 20, TO, "2026-12-01T00:00:00Z", 5)
                .andExpect(status().isCreated());
        // 不同设备同区域：允许
        createPermit("req-p6", "permit-5", "dev-other", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
    }

    @Test
    void retroactivePermitReturns422() throws Exception {
        createPermit("req-p7", "permit-1", "dev-1", 10, 20,
                "2026-09-01T00:00:00Z", TO, 5)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_PERMIT_RANGE"));
        // 终点不晚于起点
        createPermit("req-p8", "permit-2", "dev-1", 10, 20, TO, FROM, 5)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_PERMIT_RANGE"));
    }

    @Test
    void duplicatePermitIdReturns409() throws Exception {
        createPermit("req-p9", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        createPermit("req-p10", "permit-1", "dev-2", 40, 50, FROM, TO, 5)
                .andExpect(status().isConflict());
    }

    // ---------- 采样提交：唯一命中与计数 ----------

    @Test
    void submitSampleHitsUniquePermitAndDecrementsCount() throws Exception {
        createPermit("req-s0", "permit-1", "dev-1", 10, 20, FROM, TO, 3)
                .andExpect(status().isCreated());
        sample("req-s1", "obs-1", "dev-1", 15, AT, "站点A", "12.5", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observation.observationId").value("obs-1"))
                .andExpect(jsonPath("$.observation.observationStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.observation.permitId").value("permit-1"))
                .andExpect(jsonPath("$.observation.permitVersion").value("v1"))
                .andExpect(jsonPath("$.observation.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.observation.gridId").value(15))
                .andExpect(jsonPath("$.permit.observedCount").value(1))
                .andExpect(jsonPath("$.permit.remaining").value(2))
                .andExpect(jsonPath("$.permitKey").isNotEmpty());
        assertThat(permitCount("permit-1")).isEqualTo(1);

        mockMvc.perform(get("/api/sampling/observations/obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.permitVersion").value("v1"))
                .andExpect(jsonPath("$.permitReview").doesNotExist());
    }

    @Test
    void submitSampleWithoutPermitReturns422AndReason() throws Exception {
        createPermit("req-n0", "permit-1", "dev-1", 10, 20, FROM, TO, 3)
                .andExpect(status().isCreated());

        // 设备无许可
        sample("req-n1", "obs-1", "dev-other", 15, AT, "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 网格越界
        sample("req-n2", "obs-2", "dev-1", 21, AT, "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 时刻早于起点
        sample("req-n3", "obs-3", "dev-1", 15, "2026-09-30T23:59:59Z", "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 时刻恰为终点（左闭右开，不含）
        sample("req-n4", "obs-4", "dev-1", 15, TO, "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 时刻恰为起点（含）：成功
        sample("req-n5", "obs-5", "dev-1", 10, FROM, "站点", "1.0", "备注")
                .andExpect(status().isCreated());
        assertThat(observationCount()).isEqualTo(1);
        assertThat(permitCount("permit-1")).isEqualTo(1);
    }

    @Test
    void submitSampleHittingMultiplePermitsReturns422NotUnique() throws Exception {
        createPermit("req-u0", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        // 直接落一张与 permit-1 在网格和时间上重叠的许可（绕过 API 交集检查），使网格15时刻AT同时命中两张
        insertPermitRow("permit-2", "dev-1", 15, 25, FROM, TO, 5);
        sample("req-u1", "obs-1", "dev-1", 15, AT, "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NOT_UNIQUE"));
        assertThat(observationCount()).isZero();
        assertThat(permitCount("permit-1")).isZero();
    }

    @Test
    void submitSampleOverLimitReturns422AndKeepsCountAtCap() throws Exception {
        createPermit("req-l0", "permit-1", "dev-1", 10, 20, FROM, TO, 2)
                .andExpect(status().isCreated());
        sample("req-l1", "obs-1", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated());
        sample("req-l2", "obs-2", "dev-1", 11, AT, "站点B", "2.0", "备注")
                .andExpect(status().isCreated());
        sample("req-l3", "obs-3", "dev-1", 12, AT, "站点C", "3.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("LIMIT_EXCEEDED"));
        assertThat(permitCount("permit-1")).isEqualTo(2);
        assertThat(observationCount()).isEqualTo(2);
        // 失败不占键：同一 requestId 改为合法余量内仍失败（容量已满），但换设备许可后键可复用
        // 这里验证失败请求未在 request_log 留下成功占位
        Integer logged = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-l3'", Integer.class);
        assertThat(logged).isZero();
    }

    @Test
    void sameRequestIdReplaysSampleWithoutDoubleCounting() throws Exception {
        createPermit("req-i0", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        sample("req-i1", "obs-1", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permit.observedCount").value(1));
        // 同键同参重放：返回原结果（含原 permitKey），计数不再扣增
        sample("req-i1", "obs-1", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permit.observedCount").value(1));
        assertThat(permitCount("permit-1")).isEqualTo(1);
        // 同键改参：409
        sample("req-i1", "obs-1", "dev-1", 11, AT, "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
    }

    // ---------- 提前结束 ----------

    @Test
    void earlyTerminationOnlyAffectsLaterSamples() throws Exception {
        createPermit("req-t0", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        sample("req-t1", "obs-1", "dev-1", 10, "2026-10-15T00:00:00Z", "站点A", "1.0", "备注")
                .andExpect(status().isCreated());
        // 在 10-20 提前结束（不含该时刻）
        terminate("req-t2", "permit-1", "2026-10-20T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminatedAtUtc").value("2026-10-20T00:00:00Z"))
                .andExpect(jsonPath("$.effectiveValidToUtc").value("2026-10-20T00:00:00Z"))
                .andExpect(jsonPath("$.observedCount").value(1));
        // 已接收观测保留
        mockMvc.perform(get("/api/sampling/observations/obs-1"))
                .andExpect(jsonPath("$.observationStatus").value("RECEIVED"));
        // 结束时刻及之后采样被拒
        sample("req-t3", "obs-2", "dev-1", 10, "2026-10-20T00:00:00Z", "站点B", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 结束时刻之前一刻仍可采样
        sample("req-t4", "obs-3", "dev-1", 10, "2026-10-19T23:59:59Z", "站点C", "1.0", "备注")
                .andExpect(status().isCreated());
    }

    @Test
    void terminateInThePastReturns422() throws Exception {
        createPermit("req-t5", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        terminate("req-t6", "permit-1", "2026-09-27T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_PERMIT_RANGE"));
    }

    // ---------- 撤销 ----------

    @Test
    void revokeMarksUnmergedObservationsForReviewAndKeepsMerged() throws Exception {
        createPermit("req-r0", "permit-1", "dev-1", 10, 20, FROM, TO, 10)
                .andExpect(status().isCreated());
        sample("req-r1", "obs-keep", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated());
        sample("req-r2", "obs-merge", "dev-1", 11, AT, "站点B", "2.0", "备注")
                .andExpect(status().isCreated());

        // 将 obs-merge 合并到 v2（MERGED）
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("requestId", "req-r3");
        batch.put("items", List.of(Map.of(
                "observationId", "obs-merge",
                "baseVersion", 1,
                "deviceId", "dev-1",
                "gridId", 11,
                "observedAtUtc", AT,
                "location", "站点B-改",
                "reading", "2.0",
                "note", "备注")));
        mockMvc.perform(post("/api/sampling/batch-merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationStatus").value("MERGED"));

        revoke("req-r4", "permit-1", "设备许可异常撤销")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permit.status").value("REVOKED"))
                .andExpect(jsonPath("$.reason").value("设备许可异常撤销"))
                .andExpect(jsonPath("$.reviewedObservationIds[0]").value("obs-keep"));

        // 未合并观测进入复核并写入快照
        mockMvc.perform(get("/api/sampling/observations/obs-keep"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStatus").value("PERMIT_REVIEW"))
                .andExpect(jsonPath("$.permitReview").value(true))
                .andExpect(jsonPath("$.permitVersion").value("v1"))
                .andExpect(jsonPath("$.reviewReason").value("设备许可异常撤销"));
        // 已合并观测不回退、不进复核
        mockMvc.perform(get("/api/sampling/observations/obs-merge"))
                .andExpect(jsonPath("$.observationStatus").value("MERGED"))
                .andExpect(jsonPath("$.permitReview").doesNotExist())
                .andExpect(jsonPath("$.version").value(2));
        // 撤销后新采样被拒
        sample("req-r5", "obs-new", "dev-1", 12, AT, "站点", "9.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 再次撤销：许可已非 ACTIVE
        revoke("req-r6", "permit-1", "再次撤销")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_ACTIVE"));
        // 已接收观测不被删除
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void samePermitKeyAcrossRequestIdsReplaysWithoutDoubleCounting() throws Exception {
        createPermit("req-k0", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        sample("req-k1", "obs-1", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permit.observedCount").value(1));
        // 同一设备/许可版本/网格/时刻/内容，换 requestId：按 permitKey 同键重放，不重复扣减计数
        sample("req-k2", "obs-1", "dev-1", 10, AT, "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permit.observedCount").value(1));
        assertThat(permitCount("permit-1")).isEqualTo(1);
        Integer obsRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(obsRows).isEqualTo(1);
        // 同键但内容不同（读数变化）：不是同一指纹，按重复观测处理
        sample("req-k3", "obs-1", "dev-1", 10, AT, "站点A", "2.0", "备注")
                .andExpect(status().isConflict());
    }

    // ---------- 新许可不追溯 ----------

    @Test
    void futurePermitCannotRevivePreviouslyRejectedSample() throws Exception {
        createPermit("req-g0", "permit-1", "dev-1", 10, 20, FROM, TO, 5)
                .andExpect(status().isCreated());
        // 窗口开始前（且在服务端当前时刻之前）的采样被拒
        sample("req-g1", "obs-1", "dev-1", 10, "2026-09-27T00:00:00Z", "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 重放同一被拒请求：仍然失败，新许可不能追溯改变过去裁决
        sample("req-g1", "obs-1", "dev-1", 10, "2026-09-27T00:00:00Z", "站点", "1.0", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 想补一张覆盖该过去时刻的许可：起点早于服务端当前时刻，禁止追溯
        createPermit("req-g2", "permit-2", "dev-1", 10, 20,
                "2026-09-27T00:00:00Z", TO, 5)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_PERMIT_RANGE"));
        assertThat(observationCount()).isZero();
    }
}
