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
 * 批量离线合并、冲突簇裁决与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermitBatchMergeTest {

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

    private ResultActions createPermit(String requestId, String permitId, String deviceId, int version,
                                       String minLon, String minLat, String maxLon, String maxLat, int maxObs)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("permitVersion", version);
        body.put("gridMinLon", new BigDecimal(minLon));
        body.put("gridMinLat", new BigDecimal(minLat));
        body.put("gridMaxLon", new BigDecimal(maxLon));
        body.put("gridMaxLat", new BigDecimal(maxLat));
        body.put("validFrom", T0);
        body.put("validTo", Instant.parse("2026-01-02T00:00:00Z"));
        body.put("maxObservations", maxObs);
        return mockMvc.perform(post("/api/sampling/permits")
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

    private Map<String, Object> cluster(String clusterId, List<String> keys) {
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("clusterId", clusterId);
        cluster.put("observationKeys", keys);
        return cluster;
    }

    private String key(String deviceId, String lon, String lat, Instant sampledAt) {
        return String.join("|", deviceId, sampledAt.toString(),
                new BigDecimal(lon).stripTrailingZeros().toPlainString(),
                new BigDecimal(lat).stripTrailingZeros().toPlainString());
    }

    private Integer count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------- 批量合并主流程 ----------

    @Test
    void batchMergeAcceptsAllAndComputesFinalCounts() throws Exception {
        createPermit("req-b0", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "观测一"),
                item("dev-1", "100.2", "30.2", T0, "观测二"));
        batchMerge("req-b1", items, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.observations[0].reviewStatus").value("MERGED"))
                .andExpect(jsonPath("$.observations[0].permitVersion").value(1))
                .andExpect(jsonPath("$.quotaAfterMergeByPermit['permit-1']").value(2));
        assertThat(count("SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM permit_observation_version")).isEqualTo(2);
    }

    @Test
    void batchMergeRollsBackWhenOneItemHasNoPermit() throws Exception {
        createPermit("req-b2", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "许可内"),
                // dev-2 无任何许可
                item("dev-2", "100.1", "30.1", T0, "无许可"));
        batchMerge("req-b3", items, List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("NO_TIME_VALID_PERMIT"));
        // 整批回滚：无观测、无计数变化、无半成品
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isZero();
        assertThat(count("SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'")).isZero();
        // 失败不占键：同一 requestId 修正批次后可成功
        List<Map<String, Object>> fixed = List.of(item("dev-1", "100.1", "30.1", T0, "许可内"));
        batchMerge("req-b3", fixed, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1));
    }

    @Test
    void batchMergeRollsBackWhenGridOutsidePermit() throws Exception {
        createPermit("req-b4", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "许可内"),
                item("dev-1", "109.9", "30.1", T0, "越界"));
        batchMerge("req-b5", items, List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("GRID_OUT_OF_BOUNDS"));
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isZero();
    }

    @Test
    void batchMergeRollsBackWhenFinalCountExceedsQuota() throws Exception {
        createPermit("req-b6", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 2).andExpect(status().isCreated());
        // 先占用 1 个配额
        Map<String, Object> submitBody = new LinkedHashMap<>();
        submitBody.put("requestId", "req-b6s");
        submitBody.put("deviceId", "dev-1");
        submitBody.put("gridLon", new BigDecimal("100.0"));
        submitBody.put("gridLat", new BigDecimal("30.0"));
        submitBody.put("sampledAt", T0);
        submitBody.put("content", "已有观测");
        mockMvc.perform(post("/api/sampling/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody)))
                .andExpect(status().isCreated());
        // 批次再提交 2 条：完整最终计数 3 > 上限 2
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "批次一"),
                item("dev-1", "100.2", "30.2", T0, "批次二"));
        batchMerge("req-b7", items, List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("QUOTA_EXCEEDED"));
        // 整批回滚：只有原有 1 条
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isEqualTo(1);
        assertThat(count("SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'")).isEqualTo(1);
    }

    @Test
    void duplicateItemInBatchReturns422AndRollsBack() throws Exception {
        createPermit("req-b8", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "观测一"),
                item("dev-1", "100.1", "30.1", T0, "观测一重复坐标"));
        batchMerge("req-b9", items, List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("DUPLICATE_IN_BATCH"));
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isZero();
    }

    // ---------- 冲突簇 ----------

    @Test
    void batchMergeWithClustersMarksMembersAndAllowsResolution() throws Exception {
        createPermit("req-c0", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        String k1 = key("dev-1", "100.1", "30.1", T0);
        String k2 = key("dev-1", "100.2", "30.2", T0);
        List<Map<String, Object>> items = List.of(
                item("dev-1", "100.1", "30.1", T0, "候选A"),
                item("dev-1", "100.2", "30.2", T0, "候选B"));
        batchMerge("req-c1", items, List.of(cluster("cluster-1", List.of(k1, k2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingClusterIds[0]").value("cluster-1"));
        mockMvc.perform(get("/api/sampling/observations/{key}", k1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("MERGED"))
                .andExpect(jsonPath("$.mergedClusterId").value("cluster-1"));

        // 人工裁决选 k2
        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-c2");
        resolveBody.put("chosenObservationKey", k2);
        resolveBody.put("operator", "reviewer-1");
        mockMvc.perform(post("/api/sampling/clusters/cluster-1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value("cluster-1"))
                .andExpect(jsonPath("$.chosenObservationKey").value(k2))
                .andExpect(jsonPath("$.chosenContent").value("候选B"));

        // 已裁决簇再次裁决返回 409，簇不重算
        Map<String, Object> resolveAgain = new LinkedHashMap<>();
        resolveAgain.put("requestId", "req-c3");
        resolveAgain.put("chosenObservationKey", k1);
        resolveAgain.put("operator", "reviewer-2");
        mockMvc.perform(post("/api/sampling/clusters/cluster-1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveAgain)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("CLUSTER_ALREADY_RESOLVED"));

        // 裁决记录不可变，查询仍指向 k2
        mockMvc.perform(get("/api/sampling/clusters/cluster-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chosenObservationKey").value(k2));
    }

    @Test
    void clusterWithMemberNotInBatchReturns422() throws Exception {
        createPermit("req-c4", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        String k1 = key("dev-1", "100.1", "30.1", T0);
        String ghost = key("dev-1", "100.9", "30.9", T0);
        List<Map<String, Object>> items = List.of(item("dev-1", "100.1", "30.1", T0, "观测一"));
        batchMerge("req-c5", items, List.of(cluster("cluster-x", List.of(k1, ghost))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("WINNER_NOT_IN_CLUSTER"));
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isZero();
    }

    @Test
    void reusedClusterIdInLaterBatchReturns422() throws Exception {
        createPermit("req-c6", "permit-1", "dev-1", 1,
                "100.0", "30.0", "103.0", "33.0", 10).andExpect(status().isCreated());
        String k1 = key("dev-1", "100.1", "30.1", T0);
        String k2 = key("dev-1", "100.2", "30.2", T0);
        batchMerge("req-c7", List.of(
                item("dev-1", "100.1", "30.1", T0, "A"),
                item("dev-1", "100.2", "30.2", T0, "B")),
                List.of(cluster("cluster-dup", List.of(k1, k2))))
                .andExpect(status().isOk());
        String k3 = key("dev-1", "100.3", "30.3", T0);
        String k4 = key("dev-1", "100.4", "30.4", T0);
        batchMerge("req-c8", List.of(
                item("dev-1", "100.3", "30.3", T0, "C"),
                item("dev-1", "100.4", "30.4", T0, "D")),
                List.of(cluster("cluster-dup", List.of(k3, k4))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("CLUSTER_ALREADY_MERGED"));
        // 第二个批次整批回滚：只有前两条
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isEqualTo(2);
    }

    @Test
    void resolveWithWinnerOutsideClusterReturns422() throws Exception {
        createPermit("req-c9", "permit-1", "dev-1", 1,
                "100.0", "30.0", "103.0", "33.0", 10).andExpect(status().isCreated());
        String k1 = key("dev-1", "100.1", "30.1", T0);
        String k2 = key("dev-1", "100.2", "30.2", T0);
        String outsider = key("dev-1", "100.3", "30.3", T0);
        batchMerge("req-c10", List.of(
                item("dev-1", "100.1", "30.1", T0, "A"),
                item("dev-1", "100.2", "30.2", T0, "B"),
                item("dev-1", "100.3", "30.3", T0, "C")),
                List.of(cluster("cluster-1", List.of(k1, k2))))
                .andExpect(status().isOk());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-c11");
        body.put("chosenObservationKey", outsider);
        body.put("operator", "reviewer-1");
        mockMvc.perform(post("/api/sampling/clusters/cluster-1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("WINNER_NOT_IN_CLUSTER"));
        assertThat(count("SELECT COUNT(*) FROM permit_cluster_resolution")).isZero();
    }

    // ---------- 幂等 ----------

    @Test
    void sameBatchRequestIdReplaysWithoutDoubleCounting() throws Exception {
        createPermit("req-i0", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        List<Map<String, Object>> items = List.of(item("dev-1", "100.1", "30.1", T0, "观测一"));
        batchMerge("req-i1", items, List.of()).andExpect(status().isOk());
        batchMerge("req-i1", items, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isEqualTo(1);
        assertThat(count("SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i1'")).isEqualTo(1);
    }

    @Test
    void sameSubmitRequestIdWithDifferentContentReturns409() throws Exception {
        createPermit("req-i2", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("requestId", "req-i3");
        first.put("deviceId", "dev-1");
        first.put("gridLon", new BigDecimal("100.1"));
        first.put("gridLat", new BigDecimal("30.1"));
        first.put("sampledAt", T0);
        first.put("content", "内容A");
        mockMvc.perform(post("/api/sampling/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("content", "内容B");
        mockMvc.perform(post("/api/sampling/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM permit_observation")).isEqualTo(1);
    }

    @Test
    void submitReplayAfterPermitRevokedStillReturnsOriginalResult() throws Exception {
        createPermit("req-i4", "permit-1", "dev-1", 1,
                "100.0", "30.0", "102.0", "32.0", 10).andExpect(status().isCreated());
        Map<String, Object> submitBody = new LinkedHashMap<>();
        submitBody.put("requestId", "req-i5");
        submitBody.put("deviceId", "dev-1");
        submitBody.put("gridLon", new BigDecimal("100.1"));
        submitBody.put("gridLat", new BigDecimal("30.1"));
        submitBody.put("sampledAt", T0);
        submitBody.put("content", "撤销前观测");
        mockMvc.perform(post("/api/sampling/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody)))
                .andExpect(status().isCreated());
        // 撤销许可
        mockMvc.perform(post("/api/sampling/permits/permit-1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-i6\"}"))
                .andExpect(status().isOk());
        // 同键同参重放：不重新做许可判定，仍返回原创建结果（观测因撤销进入复核）
        mockMvc.perform(post("/api/sampling/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("RECEIVED"));
        // 计数不重复扣减
        assertThat(count("SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'")).isEqualTo(1);
    }
}
