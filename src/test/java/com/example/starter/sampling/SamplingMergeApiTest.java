package com.example.starter.sampling;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
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
 * 批量离线合并与冲突簇裁决测试：预校验（完整最终计数、坐标边界、冲突簇）、
 * 整体回滚、批次幂等与人工裁决（真实 H2 内存库，MySQL 兼容模式；时钟可控）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplingMergeApiTest {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-10-01T00:00:00Z";
    private static final String T = "2026-09-15T00:00:00Z";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-28T09:00:00Z");

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
        jdbcTemplate.update("DELETE FROM sampling_observation_snapshot");
        jdbcTemplate.update("DELETE FROM sampling_rejection");
        jdbcTemplate.update("DELETE FROM sampling_observation");
        jdbcTemplate.update("DELETE FROM sampling_conflict_cluster");
        jdbcTemplate.update("DELETE FROM sampling_merge_batch");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
    }

    private void createPermit(String permitId, String deviceId, int minX, int maxX, int minY, int maxY,
                              int maxObservations, int permitVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("gridMinX", minX);
        body.put("gridMaxX", maxX);
        body.put("gridMinY", minY);
        body.put("gridMaxY", maxY);
        body.put("validFromUtc", FROM);
        body.put("validToUtc", TO);
        body.put("maxObservations", maxObservations);
        body.put("permitVersion", permitVersion);
        mockMvc.perform(post("/api/sampling/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> item(String deviceId, int permitVersion, int gridX, int gridY, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceId", deviceId);
        item.put("permitVersion", permitVersion);
        item.put("gridX", gridX);
        item.put("gridY", gridY);
        item.put("collectedAtUtc", T);
        item.put("content", content);
        return item;
    }

    private ResultActions mergeBatch(String batchId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("observations", items);
        return mockMvc.perform(post("/api/sampling/merges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submit(String deviceId, int permitVersion, int gridX, int gridY, String content)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", deviceId);
        body.put("permitVersion", permitVersion);
        body.put("gridX", gridX);
        body.put("gridY", gridY);
        body.put("collectedAtUtc", T);
        body.put("content", content);
        return mockMvc.perform(post("/api/sampling/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String clusterId, String operator, String winningContent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", operator);
        body.put("winningContent", winningContent);
        return mockMvc.perform(post("/api/sampling/clusters/{id}/adjudicate", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCluster(String clusterId) throws Exception {
        return mockMvc.perform(get("/api/sampling/clusters/{id}", clusterId));
    }

    private ResultActions listPermitObservations(String permitId) throws Exception {
        return mockMvc.perform(get("/api/sampling/permits/{id}/observations", permitId));
    }

    private int permitUsedCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? -1 : count;
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sampling_observation", Integer.class);
        return count == null ? 0 : count;
    }

    private int clusterCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sampling_conflict_cluster", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 查询许可下全部观测，返回 观测内容 → 观测状态 的映射，便于按内容断言状态。
     */
    private Map<String, String> statusByContent(String permitId) throws Exception {
        MvcResult result = listPermitObservations(permitId)
                .andExpect(status().isOk())
                .andReturn();
        Map<String, String> statusByContent = new LinkedHashMap<>();
        for (var node : objectMapper.readTree(result.getResponse().getContentAsString())) {
            statusByContent.put(node.get("content").asText(), node.get("status").asText());
        }
        return statusByContent;
    }

    // ---------- 批量合并主流程 ----------

    @Test
    void batchMergeSuccessMergesAndUpdatesCounts() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "读数A"),
                        item("dev-1", 1, 2, 2, "读数B"),
                        item("dev-1", 1, 3, 3, "读数C")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.replayedCount").value(0))
                .andExpect(jsonPath("$.mergedCount").value(3))
                .andExpect(jsonPath("$.conflictClusterIds.length()").value(0))
                .andExpect(jsonPath("$.permitCounts['permit-1']").value(3));

        assertThat(permitUsedCount("permit-1")).isEqualTo(3);
        listPermitObservations("permit-1")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("MERGED"))
                .andExpect(jsonPath("$[0].batchId").value("batch-1"));
    }

    @Test
    void batchConflictClusterStaysPendingUntilAdjudicated() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        // 同设备同网格单元、内容不一致：形成冲突簇
        MvcResult result = mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 5, 5, "内容甲"),
                        item("dev-1", 1, 5, 5, "内容乙"),
                        item("dev-1", 1, 6, 6, "无冲突")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.mergedCount").value(1))
                .andExpect(jsonPath("$.conflictClusterIds.length()").value(1))
                .andReturn();
        String clusterId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("conflictClusterIds").get(0).asText();

        // 簇待裁决，成员观测未合并（无冲突条目已合并）
        getCluster(clusterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.batchId").value("batch-1"));
        assertThat(statusByContent("permit-1"))
                .containsEntry("内容甲", "RECEIVED")
                .containsEntry("内容乙", "RECEIVED")
                .containsEntry("无冲突", "MERGED");

        // 人工裁决：簇置为已裁决，成员观测统一合并，裁决时刻来自可控时钟
        adjudicate(clusterId, "operator-1", "内容甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADJUDICATED"))
                .andExpect(jsonPath("$.winningContent").value("内容甲"))
                .andExpect(jsonPath("$.adjudicatedBy").value("operator-1"))
                .andExpect(jsonPath("$.adjudicatedAtUtc").value("2026-09-28T09:00:00Z"));
        assertThat(statusByContent("permit-1"))
                .containsEntry("内容甲", "MERGED")
                .containsEntry("内容乙", "MERGED");

        // 同参重放返回原结果；异参 409
        adjudicate(clusterId, "operator-1", "内容甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADJUDICATED"));
        adjudicate(clusterId, "operator-2", "内容乙")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CLUSTER_NOT_PENDING"));
    }

    @Test
    void adjudicatedClusterIsNotRecomputedOnRevoke() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        MvcResult result = mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 5, 5, "内容甲"),
                        item("dev-1", 1, 5, 5, "内容乙")))
                .andExpect(status().isOk())
                .andReturn();
        String clusterId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("conflictClusterIds").get(0).asText();
        adjudicate(clusterId, "operator-1", "内容甲").andExpect(status().isOk());

        // 撤销许可：已人工裁决簇的成员已合并，不标记复核、不重算
        mockMvc.perform(post("/api/sampling/permits/{id}/revoke", "permit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        listPermitObservations("permit-1")
                .andExpect(jsonPath("$[0].status").value("MERGED"))
                .andExpect(jsonPath("$[0].reviewFlag").value(false))
                .andExpect(jsonPath("$[1].status").value("MERGED"));
        getCluster(clusterId)
                .andExpect(jsonPath("$.status").value("ADJUDICATED"));
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation_snapshot", Integer.class);
        assertThat(snapshots).isZero();
    }

    // ---------- 预校验与整体回滚 ----------

    @Test
    void batchWithMissingPermitRollsBackEverything() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        // 第二条无许可：整批 422，全部观测与簇变化回滚
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "有许可"),
                        item("dev-9", 1, 1, 1, "无许可")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        assertThat(observationCount()).isZero();
        assertThat(clusterCount()).isZero();
        assertThat(permitUsedCount("permit-1")).isZero();
        // 批次失败不占键：修正后同一 batchId 可重新提交
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "有许可"),
                        item("dev-1", 1, 2, 2, "修正后")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2));
        assertThat(permitUsedCount("permit-1")).isEqualTo(2);
        // 失败原因已留痕
        mockMvc.perform(get("/api/sampling/rejections").param("requestKey", "batch-1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scope").value("BATCH"))
                .andExpect(jsonPath("$[0].reason").value("NO_PERMIT"));
    }

    @Test
    void batchExceedingFinalCountRollsBack() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 2, 1);
        submit("dev-1", 1, 1, 1, "已占一席").andExpect(status().isCreated());
        // 批次两条新观测：完整最终计数 1 + 2 = 3 超过上限 2
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 2, 2, "批次一"),
                        item("dev-1", 1, 3, 3, "批次二")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_LIMIT_EXCEEDED"));
        assertThat(permitUsedCount("permit-1")).isEqualTo(1);
        assertThat(observationCount()).isEqualTo(1);
        assertThat(clusterCount()).isZero();
    }

    @Test
    void batchWithOutOfBoundsItemReturns422() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "正常"),
                        item("dev-1", 1, -5, 1, "越界")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("GRID_OUT_OF_BOUNDS"));
        assertThat(observationCount()).isZero();
    }

    @Test
    void batchWithMismatchedPermitVersionReturns422() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 3);
        mergeBatch("batch-1", List.of(item("dev-1", 1, 1, 1, "版本不一致")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_VERSION_MISMATCH"));
        assertThat(observationCount()).isZero();
    }

    // ---------- 批次幂等与去重 ----------

    @Test
    void batchReplayReturnsSameResultWithoutDoubleCount() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        List<Map<String, Object>> items = List.of(
                item("dev-1", 1, 1, 1, "读数A"),
                item("dev-1", 1, 2, 2, "读数B"));
        mergeBatch("batch-1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2));
        // 同键同参重放：返回原结果，计数不重复
        mergeBatch("batch-1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.permitCounts['permit-1']").value(2));
        assertThat(permitUsedCount("permit-1")).isEqualTo(2);
        assertThat(observationCount()).isEqualTo(2);
        // 同键异参：409
        mergeBatch("batch-1", List.of(item("dev-1", 1, 9, 9, "不同内容")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("BATCH_REUSED"));
    }

    @Test
    void duplicateItemsWithinBatchCountedOnce() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "重复条目"),
                        item("dev-1", 1, 1, 1, "重复条目")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.replayedCount").value(1));
        assertThat(permitUsedCount("permit-1")).isEqualTo(1);
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void alreadySubmittedItemIsReplayedWithinBatch() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        submit("dev-1", 1, 1, 1, "单次已提交").andExpect(status().isCreated());
        // 批次包含已提交条目（同键重放，不重复计数）与一条新条目
        mergeBatch("batch-1", List.of(
                        item("dev-1", 1, 1, 1, "单次已提交"),
                        item("dev-1", 1, 2, 2, "批次新增")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.replayedCount").value(1));
        assertThat(permitUsedCount("permit-1")).isEqualTo(2);
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void getMissingClusterReturns404() throws Exception {
        getCluster("no-such-cluster").andExpect(status().isNotFound());
        adjudicate("no-such-cluster", "operator-1", "内容").andExpect(status().isNotFound());
    }

    @Test
    void batchAcrossMultiplePermitsCountsSeparately() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, 10, 1);
        createPermit("permit-2", "dev-2", 0, 10, 0, 10, 10, 1);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("dev-1", 1, 1, 1, "设备一A"));
        items.add(item("dev-1", 1, 2, 2, "设备一B"));
        items.add(item("dev-2", 1, 1, 1, "设备二A"));
        mergeBatch("batch-1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.permitCounts['permit-1']").value(2))
                .andExpect(jsonPath("$.permitCounts['permit-2']").value(1));
        assertThat(permitUsedCount("permit-1")).isEqualTo(2);
        assertThat(permitUsedCount("permit-2")).isEqualTo(1);
    }
}
