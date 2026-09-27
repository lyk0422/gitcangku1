package com.example.starter.permit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
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
 * 观测采样许可 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 * 使用固定 UTC 时钟 T0：新许可 validFrom 不得早于 T0，采样时刻不得晚于 T0。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermitApiTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM permit_cluster_resolution");
        jdbcTemplate.update("DELETE FROM permit_observation_version");
        jdbcTemplate.update("DELETE FROM permit_observation");
        jdbcTemplate.update("DELETE FROM permit_rejection_log");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM sampling_device");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    // ---------- 辅助 ----------

    private ResultActions createPermit(String requestId, String permitId, String deviceId, int version,
                                       String minLon, String minLat, String maxLon, String maxLat,
                                       Instant from, Instant to, int maxObs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("permitVersion", version);
        body.put("gridMinLon", new BigDecimal(minLon));
        body.put("gridMinLat", new BigDecimal(minLat));
        body.put("gridMaxLon", new BigDecimal(maxLon));
        body.put("gridMaxLat", new BigDecimal(maxLat));
        body.put("validFrom", from);
        body.put("validTo", to);
        body.put("maxObservations", maxObs);
        return mockMvc.perform(post("/api/sampling/permits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submit(String requestId, String deviceId, String lon, String lat,
                                 Instant sampledAt, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("deviceId", deviceId);
        body.put("gridLon", new BigDecimal(lon));
        body.put("gridLat", new BigDecimal(lat));
        body.put("sampledAt", sampledAt);
        body.put("content", content);
        return mockMvc.perform(post("/api/sampling/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions batchMerge(String requestId, List<Map<String, Object>> items,
                                     List<Map<String, Object>> clusters) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", items);
        body.put("clusters", clusters);
        return mockMvc.perform(post("/api/sampling/observations/batch-merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String deviceId, String lon, String lat, Instant sampledAt, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceId", deviceId);
        item.put("gridLon", new BigDecimal(lon));
        item.put("gridLat", new BigDecimal(lat));
        item.put("sampledAt", sampledAt);
        item.put("content", content);
        return item;
    }

    private String key(String deviceId, String lon, String lat, Instant sampledAt) {
        return String.join("|", deviceId, sampledAt.toString(),
                new BigDecimal(lon).stripTrailingZeros().toPlainString(),
                new BigDecimal(lat).stripTrailingZeros().toPlainString());
    }

    private Integer permitCount(String permitId) {
        return jdbcTemplate.queryForObject(
                "SELECT accepted_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
    }

    private Integer observationRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permit_observation", Integer.class);
    }

    // ---------- 许可签发与范围冲突 ----------

    @Test
    void createPermitReturnsPermitWithQuota() throws Exception {
        createPermit("req-p1", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-01T01:00:00Z"), 5)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.remaining").value(5));

        mockMvc.perform(get("/api/sampling/permits/permit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(5));
    }

    @Test
    void overlappingPermitsInGridAndTimeReturn409() throws Exception {
        createPermit("req-p2", "permit-a", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-01T02:00:00Z"), 10)
                .andExpect(status().isCreated());
        // 网格相交且时间相交：409 PERMIT_OVERLAP
        createPermit("req-p3", "permit-b", "dev-1", 2,
                "100.5", "30.5", "101.5", "31.5", T0, Instant.parse("2026-01-01T02:00:00Z"), 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("PERMIT_OVERLAP"));
    }

    @Test
    void permitsTouchingAtTimeBoundaryOrOutsideGridDoNotConflict() throws Exception {
        createPermit("req-p4", "permit-a", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-01T01:00:00Z"), 10)
                .andExpect(status().isCreated());
        // 时间仅首尾相接（左闭右开，不构成交集）：即使网格重叠也允许
        createPermit("req-p5", "permit-b", "dev-1", 2,
                "100.0", "30.0", "101.0", "31.0",
                Instant.parse("2026-01-01T01:00:00Z"), Instant.parse("2026-01-01T02:00:00Z"), 10)
                .andExpect(status().isCreated());
        // 同一时段但网格不相交：允许
        createPermit("req-p6", "permit-c", "dev-1", 3,
                "102.0", "30.0", "103.0", "31.0", T0, Instant.parse("2026-01-01T02:00:00Z"), 10)
                .andExpect(status().isCreated());
    }

    @Test
    void duplicatePermitIdReturns409() throws Exception {
        createPermit("req-p7", "permit-x", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isCreated());
        createPermit("req-p8", "permit-x", "dev-2", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("PERMIT_ID_DUPLICATE"));
    }

    @Test
    void nonSequentialPermitVersionReturns409() throws Exception {
        createPermit("req-p9", "permit-a", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isCreated());
        createPermit("req-p10", "permit-b", "dev-1", 3,
                "102.0", "30.0", "103.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("PERMIT_VERSION_DUPLICATE"));
    }

    @Test
    void retroactivePermitIsRejected400() throws Exception {
        createPermit("req-p11", "permit-a", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0",
                Instant.parse("2025-12-31T23:59:59Z"), Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sampling_permit", Integer.class)).isZero();
    }

    // ---------- 单条提交：唯一命中与配额 ----------

    @Test
    void submitHitsUniquePermitAndDecrementsCount() throws Exception {
        createPermit("req-s1", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 3)
                .andExpect(status().isCreated());
        submit("req-s2", "dev-1", "100.5", "30.5", T0, "温度25.0")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationKey").exists())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.permitReview").value(false))
                .andExpect(jsonPath("$.rejectReasonCode").doesNotExist());
        assertThat(permitCount("permit-1")).isEqualTo(1);
        // 网格闭矩形边界点可命中
        submit("req-s3", "dev-1", "100.0", "30.0", T0, "边界点").andExpect(status().isCreated());
        assertThat(permitCount("permit-1")).isEqualTo(2);
    }

    @Test
    void submitAtRightOpenEndInstantReturns422() throws Exception {
        createPermit("req-s4", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-01T01:00:00Z"), 3)
                .andExpect(status().isCreated());
        // 提前结束时刻恰为 T0（右开）：T0 采样不在许可窗口内
        Map<String, Object> endBody = new LinkedHashMap<>();
        endBody.put("requestId", "req-s4-end");
        endBody.put("endAt", T0);
        mockMvc.perform(post("/api/sampling/permits/permit-1/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(endBody)))
                .andExpect(status().isOk());
        String key = key("dev-1", "100.5", "30.5", T0);
        submit("req-s5", "dev-1", "100.5", "30.5", T0, "右端点")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        // 拒绝原因可按观测坐标查询，且不留观测半成品
        mockMvc.perform(get("/api/sampling/observations/{key}/rejections", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reasonCode").value("NO_TIME_VALID_PERMIT"));
        assertThat(observationRows()).isZero();
        assertThat(permitCount("permit-1")).isZero();
    }

    @Test
    void submitOutsideGridReturns422() throws Exception {
        createPermit("req-s6", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 3)
                .andExpect(status().isCreated());
        submit("req-s7", "dev-1", "101.5", "30.5", T0, "越界点")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("GRID_OUT_OF_BOUNDS"));
        assertThat(observationRows()).isZero();
        assertThat(permitCount("permit-1")).isZero();
    }

    @Test
    void submitExceedingQuotaReturns422AndLeavesNothing() throws Exception {
        createPermit("req-s8", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 2)
                .andExpect(status().isCreated());
        submit("req-s9", "dev-1", "100.1", "30.1", T0, "一").andExpect(status().isCreated());
        submit("req-s10", "dev-1", "100.2", "30.2", T0, "二").andExpect(status().isCreated());
        submit("req-s11", "dev-1", "100.3", "30.3", T0, "三")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("QUOTA_EXCEEDED"));
        assertThat(permitCount("permit-1")).isEqualTo(2);
        assertThat(observationRows()).isEqualTo(2);
    }

    @Test
    void futureSampledAtReturns422() throws Exception {
        createPermit("req-s12", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 3)
                .andExpect(status().isCreated());
        submit("req-s13", "dev-1", "100.5", "30.5",
                Instant.parse("2026-01-01T00:00:01Z"), "未来采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("SAMPLED_AT_IN_FUTURE"));
    }

    @Test
    void multipleCoveringPermitsReturn422NotUnique() throws Exception {
        // 经服务 API 同设备许可不可能相交；此处直接构造两条相交许可验证防御性唯一命中判定与真实 SQL 行为。
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, permit_version, grid_min_lon, grid_min_lat, "
                        + "grid_max_lon, grid_max_lat, valid_from_utc, valid_to_utc, effective_end_utc, "
                        + "max_observations, accepted_count, status, revoke_snapshot, revoked_at_utc, created_at) "
                        + "VALUES ('permit-a','dev-9',1,100,30,101,31,?, ?,NULL,5,0,'ACTIVE',NULL,NULL,CURRENT_TIMESTAMP)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, permit_version, grid_min_lon, grid_min_lat, "
                        + "grid_max_lon, grid_max_lat, valid_from_utc, valid_to_utc, effective_end_utc, "
                        + "max_observations, accepted_count, status, revoke_snapshot, revoked_at_utc, created_at) "
                        + "VALUES ('permit-b','dev-9',2,100,30,101,31,?, ?,NULL,5,0,'ACTIVE',NULL,NULL,CURRENT_TIMESTAMP)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        submit("req-s14", "dev-9", "100.5", "30.5", T0, "多许可命中")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("PERMIT_NOT_UNIQUE"));
    }

    // ---------- 提前结束与撤销 ----------

    @Test
    void endingPermitEarlyOnlyAffectsLaterSampling() throws Exception {
        createPermit("req-e1", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 5)
                .andExpect(status().isCreated());
        submit("req-e2", "dev-1", "100.5", "30.5", T0, "结束前采样").andExpect(status().isCreated());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-e3");
        body.put("endAt", Instant.parse("2026-01-01T00:00:00Z"));
        mockMvc.perform(post("/api/sampling/permits/permit-1/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.acceptedCount").value(1));

        // 结束时刻右开：恰为结束时刻的采样不命中
        submit("req-e4", "dev-1", "100.6", "30.6", T0, "结束后采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        // 已接收观测保留且仍可查
        mockMvc.perform(get("/api/sampling/observations/{key}",
                        key("dev-1", "100.5", "30.5", T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("RECEIVED"));
    }

    @Test
    void revokeMarksUnmergedObservationsForReviewAndSnapshots() throws Exception {
        createPermit("req-r1", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 10)
                .andExpect(status().isCreated());
        submit("req-r2", "dev-1", "100.1", "30.1", T0, "未合并观测").andExpect(status().isCreated());

        // 一条已合并观测（经批量合并）
        String mergedKey = key("dev-1", "101.0", "31.0", T0);
        batchMerge("req-r3",
                List.of(item("dev-1", "101.0", "31.0", T0, "已合并观测")),
                List.of()).andExpect(status().isOk());

        Map<String, Object> revokeBody = new LinkedHashMap<>();
        revokeBody.put("requestId", "req-r4");
        mockMvc.perform(post("/api/sampling/permits/permit-1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.acceptedCount").value(2));

        // 未合并观测进入复核
        mockMvc.perform(get("/api/sampling/observations/{key}", key("dev-1", "100.1", "30.1", T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PERMIT_REVIEW"))
                .andExpect(jsonPath("$.permitReview").value(true));
        // 已合并观测不重算
        mockMvc.perform(get("/api/sampling/observations/{key}", mergedKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("MERGED"));
        // 撤销快照已写入许可且含复核观测
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT revoke_snapshot FROM sampling_permit WHERE permit_id = 'permit-1'", String.class);
        assertThat(snapshot).contains("PERMIT_REVIEW").contains("未合并观测");
        Integer reviewVersions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permit_observation_version WHERE review_status = 'PERMIT_REVIEW'",
                Integer.class);
        assertThat(reviewVersions).isEqualTo(1);
        // 撤销后采样一律拒绝
        submit("req-r5", "dev-1", "100.2", "30.2", T0, "撤销后采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        // 撤销为强失效：换用新 requestId 回填撤销窗口内的旧采样同样被拒
        submit("req-r6", "dev-1", "100.3", "30.3", T0, "撤销后回填旧采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        assertThat(observationRows()).isEqualTo(2);
    }

    @Test
    void newPermitCannotRetroactivelyRescuePastRejection() throws Exception {
        Instant pastSampledAt = Instant.parse("2025-12-31T23:59:00Z");
        // 无许可时，严格早于签发时刻的采样被拒
        submit("req-n1", "dev-1", "100.5", "30.5", pastSampledAt, "过去采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        // 新许可只能从签发时刻 T0 起生效，无法覆盖 T0 之前的拒绝
        createPermit("req-n2", "permit-1", "dev-1", 1,
                "100.0", "30.0", "101.0", "31.0", T0, Instant.parse("2026-01-02T00:00:00Z"), 5)
                .andExpect(status().isCreated());
        // 同坐标同参重试：失败不占键可重试，但新许可窗口不覆盖过去时刻，仍 422，未变成成功
        submit("req-n1", "dev-1", "100.5", "30.5", pastSampledAt, "过去采样")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        assertThat(observationRows()).isZero();
    }
}
