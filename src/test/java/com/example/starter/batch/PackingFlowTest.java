package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次标签核销端到端测试（真实 H2 内存库 MODE=MySQL）：
 * 登记/封箱/作废/放行快照主流程，422/409/400/404 失败分支与整次回滚，
 * 唯一约束并发裁决，commandKey 幂等，左闭右开时间窗，放行后差异诊断保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackingFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM label_occupancy");
        jdbc.update("DELETE FROM packing_record");
        jdbc.update("DELETE FROM packaging_plan");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_planPackRelease_snapshotFrozenAndStable() throws Exception {
        String batchKey = "BK-PK-HAPPY-" + unique();
        preparePendingBatch(batchKey);

        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        JsonNode pack1 = pack(batchKey, "worker-a", "CK-PACK-1",
                List.of(item(1000L, 4), item(1001L, 6)), 201);
        assertEquals(10, pack1.path("packedQuantity").asInt());
        assertEquals(2, pack1.path("usedLabelCount").asInt());
        assertEquals(0, pack1.path("remainingQuantity").asInt());
        assertEquals(1000L, pack1.path("packed").get(0).path("labelNo").asLong());
        long packingId1 = pack1.path("packed").get(0).path("packingId").asLong();
        long packingId2 = pack1.path("packed").get(1).path("packingId").asLong();
        assertTrue(packingId2 > packingId1);

        // 放行前诊断可放行
        JsonNode diagnosis = diagnose(batchKey);
        assertTrue(diagnosis.path("reconcilable").asBoolean());
        assertEquals(0, diagnosis.path("quantityDifference").asInt());
        assertEquals(0, diagnosis.path("labelCountDifference").asInt());

        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode detail = detail(batchKey);
        assertEquals(10, detail.path("packedQuantity").asInt());
        JsonNode snapshot = detail.path("snapshot");
        assertFalse(snapshot.isMissingNode());
        assertEquals(10, snapshot.path("plannedQuantity").asInt());
        assertEquals(10, snapshot.path("packedQuantity").asInt());
        assertEquals(2, snapshot.path("usedLabelCount").asInt());
        assertEquals("1000,1001", snapshot.path("labelSummary").asText());
        assertEquals(2, snapshot.path("records").size());
        assertEquals(packingId1, snapshot.path("records").get(0).path("packingId").asLong());
        assertEquals(1000L, snapshot.path("records").get(0).path("labelNo").asLong());

        // 放行后作废一律 409，快照不被改写
        voidPacking(batchKey, packingId1, "boss", "CK-V-1", "尝试作废", 409);
        JsonNode snapshotAfter = detail(batchKey).path("snapshot");
        assertEquals(snapshot.toString(), snapshotAfter.toString());
        // 放行后封箱、登记第二份计划均 409
        packRaw(batchKey, "worker-a", "CK-PACK-X", List.of(item(1002L, 1)), 409);
        registerPlan(batchKey, "CK-PLAN-X", 10, 1000L, 1009L, 409);
    }

    @Test
    void voidPacking_releasesQuantityAndLabel_andHistoryKeepsEvidence() throws Exception {
        String batchKey = "BK-PK-VOID-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        JsonNode pack1 = pack(batchKey, "worker-a", "CK-PACK-1",
                List.of(item(1000L, 10)), 201);
        long packingId = pack1.path("packed").get(0).path("packingId").asLong();
        Instant packedAt = Instant.parse(pack1.path("packedAt").asText());

        JsonNode voided = voidPacking(batchKey, packingId, "boss", "CK-V-1", "装箱错误", 200);
        assertEquals(10, voided.path("releasedQuantity").asInt());
        assertEquals(1000L, voided.path("releasedLabel").asLong());
        assertEquals(0, voided.path("packedQuantity").asInt());
        assertEquals(0, voided.path("usedLabelCount").asInt());

        // 释放后同标签可重新封箱
        JsonNode pack2 = pack(batchKey, "worker-b", "CK-PACK-2",
                List.of(item(1000L, 10)), 201);
        assertEquals(10, pack2.path("packedQuantity").asInt());

        // 明细保留全部版本：ACTIVE 1 条、VOID 1 条，作废字段完整
        JsonNode detail = detail(batchKey);
        assertEquals(1, detail.path("activeRecordCount").asInt());
        assertEquals(1, detail.path("voidedRecordCount").asInt());
        assertEquals(2, detail.path("records").size());
        JsonNode first = detail.path("records").get(0);
        assertEquals("VOID", first.path("status").asText());
        assertEquals("boss", first.path("voidedBy").asText());
        assertEquals("装箱错误", first.path("voidReason").asText());
        assertFalse(first.path("voidedAt").isNull());
        JsonNode second = detail.path("records").get(1);
        assertEquals("ACTIVE", second.path("status").asText());
        assertTrue(second.path("voidedBy").isNull());

        // 历史：PACKED/VOIDED/PACKED 三事件，按时间升序
        JsonNode history = history(batchKey, null, null);
        assertEquals(3, history.path("eventCount").asInt());
        assertEquals("PACKED", history.path("events").get(0).path("type").asText());
        assertEquals("VOIDED", history.path("events").get(1).path("type").asText());
        assertEquals("PACKED", history.path("events").get(2).path("type").asText());

        // 左闭右开：[packedAt, +1d) 包含首封与作废；[packedAt+1s, ...) 不含首封
        JsonNode fromPacked = history(batchKey, packedAt, packedAt.plusSeconds(86400));
        assertTrue(fromPacked.path("eventCount").asInt() >= 2);
        assertEquals(86400L, fromPacked.path("elapsedSeconds").asLong());
        JsonNode afterPacked = history(batchKey, packedAt.plusSeconds(1), null);
        for (JsonNode event : afterPacked.path("events")) {
            assertFalse(packingId == event.path("packingId").asLong()
                            && "PACKED".equals(event.path("type").asText()),
                    "左闭右开：起点之后 1 秒的窗口不得包含首封事件");
        }
    }

    @Test
    void releaseGate_partialPackingBlocksSecondApproval_thenCompletes() throws Exception {
        String batchKey = "BK-PK-GATE-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        pack(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 4)), 201);

        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        // 第二批准：数量差 6，422，批准与快照都不落库
        MvcResult blocked = mockMvc.perform(approveRequest(batchKey, "ops", "OPERATIONS", "CK-AP-2"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = objectMapper.readTree(blocked.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains("实际 4") && message.contains("要求 10") && message.contains("差额 6"),
                "失败原因须含实际值/要求值/差额: " + message);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, batchKey));

        pack(batchKey, "w", "CK-PACK-2", List.of(item(1001L, 6)), 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void releaseSnapshot_afterVoidAndRepack_keepsVoidedVersionButCountsEffectiveOnly() throws Exception {
        String batchKey = "BK-PK-REPACK-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        JsonNode first = pack(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 10)), 201);
        long voidedId = first.path("packed").get(0).path("packingId").asLong();
        voidPacking(batchKey, voidedId, "boss", "CK-V-1", "重贴标签", 200);
        // 同标签重新封满
        pack(batchKey, "w", "CK-PACK-2",
                List.of(item(1000L, 4), item(1001L, 6)), 201);

        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AP-2", 201);

        JsonNode snapshot = detail(batchKey).path("snapshot");
        assertEquals(10, snapshot.path("packedQuantity").asInt());
        assertEquals(2, snapshot.path("usedLabelCount").asInt());
        assertEquals("1000,1001", snapshot.path("labelSummary").asText());
        // 每个封箱版本都固化：作废版本 1 条 + 重封 2 条，按版本号升序
        assertEquals(3, snapshot.path("records").size());
        assertEquals(voidedId, snapshot.path("records").get(0).path("packingId").asLong());
        assertEquals(1000L, snapshot.path("records").get(0).path("labelNo").asLong());
        assertEquals(1000L, snapshot.path("records").get(1).path("labelNo").asLong());
        assertEquals(1001L, snapshot.path("records").get(2).path("labelNo").asLong());
    }

    @Test
    void releaseGate_usedLabelCountMustEqualPackingCount() throws Exception {        // 构造数据库层异常状态（占用多于封箱版本），证明门禁以数据为准而非仅数量
        String batchKey = "BK-PK-LABELCNT-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        JsonNode pack1 = pack(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 10)), 201);
        long packingId = pack1.path("packed").get(0).path("packingId").asLong();
        jdbc.update("INSERT INTO label_occupancy (label_no, batch_key, packing_id, occupied_at)"
                + " VALUES (1001, ?, ?, ?)", batchKey, packingId, Instant.now().toString());

        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        MvcResult blocked = mockMvc.perform(approveRequest(batchKey, "ops", "OPERATIONS", "CK-AP-2"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = objectMapper.readTree(blocked.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains("已用标签 2") && message.contains("封箱 1") && message.contains("差额 1"),
                "标签数不符须返回实际值/差额: " + message);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
    }

    // ---------- 失败分支与整次回滚 ----------

    @Test
    void packOutOfRangeOrDuplicateOrOverPlan_rollsBackWholeBatch() throws Exception {
        String batchKey = "BK-PK-ROLLBACK-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);

        // 越界标签与正常标签同批提交 → 422 整次回滚
        packRaw(batchKey, "w", "CK-PACK-BAD1", List.of(item(999L, 1), item(1000L, 1)), 422);
        assertNoPackingState(batchKey);

        // 请求内标签重复 → 422 整次回滚
        packRaw(batchKey, "w", "CK-PACK-BAD2", List.of(item(1000L, 1), item(1000L, 2)), 422);
        assertNoPackingState(batchKey);

        // 先正常封 8 件，再提交超出计划（最终 11）→ 422，本次标签不占用、已封 8 件不变
        pack(batchKey, "w", "CK-PACK-OK", List.of(item(1000L, 8)), 201);
        MvcResult over = mockMvc.perform(packRequest(batchKey, "w", "CK-PACK-OVER",
                        List.of(item(1001L, 2), item(1002L, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = objectMapper.readTree(over.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains("实际 11") && message.contains("超出 1"),
                "超计划须返回实际值与超出量: " + message);
        assertEquals(8, detail(batchKey).path("packedQuantity").asInt());
        assertEquals(1, detail(batchKey).path("activeRecordCount").asInt());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE label_no IN (1001,1002)", Integer.class));

        // 跨批次重复标签 → 409，一个标签只能关联一个批次
        String other = "BK-PK-OTHER-" + unique();
        preparePendingBatch(other);
        registerPlan(other, "CK-PLAN-2", 5, 1000L, 1004L, 201);
        packRaw(other, "w2", "CK-PACK-X", List.of(item(1000L, 1)), 409);
    }

    @Test
    void duplicateLabelAcrossBatches_is409_andOccupancyHasSingleRow() throws Exception {
        String a = "BK-PK-A-" + unique();
        String b = "BK-PK-B-" + unique();
        preparePendingBatch(a);
        preparePendingBatch(b);
        registerPlan(a, "CK-PA", 10, 1000L, 1009L, 201);
        registerPlan(b, "CK-PB", 10, 1000L, 1009L, 201);
        pack(a, "w", "CK-PACK-A", List.of(item(1000L, 5)), 201);
        packRaw(b, "w", "CK-PACK-B", List.of(item(1000L, 5)), 409);
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE label_no = 1000", Integer.class));
        assertEquals(a, jdbc.queryForObject(
                "SELECT batch_key FROM label_occupancy WHERE label_no = 1000", String.class));
    }

    @Test
    void planAndPackStateConflicts_return409Or422() throws Exception {
        String batchKey = "BK-PK-STATE-" + unique();
        preparePendingBatch(batchKey);
        // 号段长度小于计划数量 → 422
        registerPlan(batchKey, "CK-PLAN-BAD", 10, 1000L, 1004L, 422);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        // 重复登记 → 409
        registerPlan(batchKey, "CK-PLAN-2", 10, 1000L, 1009L, 409);
        // 未放行批次正常
        pack(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 10)), 201);
        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AP-2", 201);
        // 已召回 → 封箱 409
        recall(batchKey, "u", "客户投诉", "CK-RC-1", 201);
        packRaw(batchKey, "w", "CK-PACK-2", List.of(item(1001L, 1)), 409);

        // 不存在批次/封箱版本 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/packing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings/999999/void")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V-X\",\"reason\":\"r\"}"))
                .andExpect(status().isNotFound());

        // 缺少 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packBody("CK-PACK-NOACTOR", List.of(item(1001L, 1)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void packingWithoutPlan_is422_andInvalidBodiesAre400() throws Exception {
        String batchKey = "BK-PK-NOPLAN-" + unique();
        preparePendingBatch(batchKey);
        packRaw(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 1)), 422);

        // 空 items、非正数量 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK\",\"items\":[{\"labelNo\":1000,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
        // 计划数量非正 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK\",\"plannedQuantity\":0,\"labelStart\":1,\"labelEnd\":2}"))
                .andExpect(status().isBadRequest());

        // 未登记计划的明细仍可只读：plan=null、计数为 0
        JsonNode node = detail(batchKey);
        assertTrue(node.path("plan").isNull());
        assertEquals(0, node.path("packedQuantity").asInt());
    }

    @Test
    void voidAfterRelease_is409_butRecallStillAvailableForDifferences() throws Exception {
        String batchKey = "BK-PK-DIFF-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        JsonNode pack1 = pack(batchKey, "w", "CK-PACK-1",
                List.of(item(1000L, 4), item(1001L, 6)), 201);
        long packingId = pack1.path("packed").get(0).path("packingId").asLong();
        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AP-2", 201);

        voidPacking(batchKey, packingId, "boss", "CK-V-1", "放行后作废", 409);

        // 放行后发现标签差异（此处模拟占用丢失）：诊断保留差异，快照仍是放行时的固化值
        jdbc.update("DELETE FROM label_occupancy WHERE label_no = 1001");
        JsonNode diagnosis = diagnose(batchKey);
        assertFalse(diagnosis.path("reconcilable").asBoolean());
        assertEquals(1, diagnosis.path("usedLabelCount").asInt());
        assertEquals(2, diagnosis.path("activeRecordCount").asInt());
        assertEquals(-1, diagnosis.path("labelCountDifference").asInt(),
                "差额=已用标签数-封箱数，占用丢失时为 -1");
        assertTrue(diagnosis.path("differences").size() > 0);
        assertEquals(2, diagnosis.path("releaseSnapshot").path("usedLabelCount").asInt());
        assertEquals("1000,1001", diagnosis.path("releaseSnapshot").path("labelSummary").asText());
        // 仍可按既有召回流程处置
        recall(batchKey, "u", "发现标签差异，召回", "CK-RC-1", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sameParamsReplay_changedParamsConflict_failureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-PK-IDEM-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);

        // 封箱同参重放：返回首次结果（含相同版本号），不新增占用
        String body = packBody("CK-PACK-1", List.of(item(1000L, 3)));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, detail(batchKey).path("activeRecordCount").asInt());

        // 同键改参（数量不同）→ 409，不新增占用
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w").contentType(MediaType.APPLICATION_JSON)
                        .content(packBody("CK-PACK-1", List.of(item(1000L, 4)))))
                .andExpect(status().isConflict());
        assertEquals(1, detail(batchKey).path("activeRecordCount").asInt());

        // 失败不占键：先用 CK-PACK-2 提交越界失败，再用同键提交合法请求应成功
        packRaw(batchKey, "w", "CK-PACK-2", List.of(item(9999L, 1)), 422);
        pack(batchKey, "w", "CK-PACK-2", List.of(item(1001L, 3)), 201);

        // 作废同参重放返回首次结果；改原因 409
        long packingId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("packed").get(0).path("packingId").asLong();
        // 该标签仅 3 件，先放行门禁不涉及；直接验作废幂等后再释放剩余
        String voidBody = "{\"commandKey\":\"CK-V-1\",\"reason\":\"原因一\"}";
        MvcResult voidFirst = mockMvc.perform(post("/api/batches/" + batchKey
                                + "/packings/" + packingId + "/void")
                        .header("X-Actor-Id", "boss")
                        .contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult voidReplay = mockMvc.perform(post("/api/batches/" + batchKey
                                + "/packings/" + packingId + "/void")
                        .header("X-Actor-Id", "boss")
                        .contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isOk()).andReturn();
        assertEquals(voidFirst.getResponse().getContentAsString(),
                voidReplay.getResponse().getContentAsString());
        mockMvc.perform(post("/api/batches/" + batchKey + "/packings/" + packingId + "/void")
                        .header("X-Actor-Id", "boss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V-1\",\"reason\":\"原因二\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameLabelAcrossBatches_onlyOneSucceeds() throws Exception {
        String a = "BK-PK-CA-" + unique();
        String b = "BK-PK-CB-" + unique();
        preparePendingBatch(a);
        preparePendingBatch(b);
        registerPlan(a, "CK-PA", 10, 1000L, 1009L, 201);
        registerPlan(b, "CK-PB", 10, 1000L, 1009L, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(packRequest(a, "w", "CK-PACK-A", List.of(item(1005L, 1)))),
                () -> callStatus(packRequest(b, "w", "CK-PACK-B", List.of(item(1005L, 1))))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok, "唯一约束保证同标签最多一次成功");
        assertEquals(1, conflict);
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE label_no = 1005", Integer.class));
    }

    @Test
    void concurrentDifferentLabelsSameBatch_bothSucceed() throws Exception {
        String batchKey = "BK-PK-CSAME-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(packRequest(batchKey, "w", "CK-PACK-A", List.of(item(1002L, 4)))),
                () -> callStatus(packRequest(batchKey, "w", "CK-PACK-B", List.of(item(1003L, 6))))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(10, detail(batchKey).path("packedQuantity").asInt());
        assertEquals(2, detail(batchKey).path("usedLabelCount").asInt());
    }

    @Test
    void concurrentSecondApprovalAndFinalPacking_terminalStateConsistent() throws Exception {
        String batchKey = "BK-PK-RACE-" + unique();
        preparePendingBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 10, 1000L, 1009L, 201);
        pack(batchKey, "w", "CK-PACK-1", List.of(item(1000L, 4)), 201);
        approve(batchKey, "qa", "QUALITY", "CK-AP-1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(packRequest(batchKey, "w", "CK-PACK-2", List.of(item(1001L, 6)))),
                () -> callStatus(approveRequest(batchKey, "ops", "OPERATIONS", "CK-AP-2"))
        );
        int packCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approveCode = results.get(1).get(30, TimeUnit.SECONDS);
        String status = currentStatus(batchKey);
        if ("RELEASED".equals(status)) {
            // 封箱先提交：两操作均成功，快照固化
            assertEquals(201, packCode);
            assertEquals(201, approveCode);
            JsonNode snapshot = detail(batchKey).path("snapshot");
            assertEquals(10, snapshot.path("packedQuantity").asInt());
            assertEquals("1000,1001", snapshot.path("labelSummary").asText());
        } else {
            // 第二批准先获锁：封箱未完成 → 422，批次停在 RELEASE_REVIEW，无半成品
            assertEquals("RELEASE_REVIEW", status);
            assertEquals(422, approveCode);
            assertEquals(201, packCode);
            assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, batchKey));
            // 重试第二批准可放行
            approve(batchKey, "ops", "OPERATIONS", "CK-AP-3", 201);
            assertEquals("RELEASED", currentStatus(batchKey));
        }
    }

    // ---------- helpers ----------

    private record ItemCmd(long labelNo, int quantity) {
    }

    private record PlanCmd(String commandKey, int plannedQuantity, long labelStart, long labelEnd) {
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private ItemCmd item(long labelNo, int quantity) {
        return new ItemCmd(labelNo, quantity);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void preparePendingBatch(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-CREATE-" + unique(),
                                batchKey, "PROD-1", "LOT-1",
                                Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-TEST-" + unique(), "TK-1", "t1", "PASS", "insp"))))
                .andExpect(status().isCreated());
    }

    private String packBody(String commandKey, List<ItemCmd> items) throws Exception {
        return objectMapper.writeValueAsString(new PackBody(commandKey, items));
    }

    private record PackBody(String commandKey, List<ItemCmd> items) {
    }

    private void registerPlan(String batchKey, String commandKey, int planned, long start, long end,
                              int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanCmd(commandKey, planned, start, end))))
                .andExpect(status().is(expected));
    }

    private MockHttpServletRequestBuilder packRequest(String batchKey, String actor, String commandKey,
                                                      List<ItemCmd> items) throws Exception {
        return post("/api/batches/" + batchKey + "/packings")
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PackBody(commandKey, items)));
    }

    private JsonNode pack(String batchKey, String actor, String commandKey, List<ItemCmd> items,
                          int expected) throws Exception {
        MvcResult result = mockMvc.perform(packRequest(batchKey, actor, commandKey, items))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void packRaw(String batchKey, String actor, String commandKey, List<ItemCmd> items,
                         int expected) throws Exception {
        mockMvc.perform(packRequest(batchKey, actor, commandKey, items))
                .andExpect(status().is(expected));
    }

    private MockHttpServletRequestBuilder approveRequest(String batchKey, String actor, String role,
                                                         String commandKey) {
        return post("/api/batches/" + batchKey + "/approvals")
                .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"" + commandKey + "\"}");
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(approveRequest(batchKey, actor, role, commandKey))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode voidPacking(String batchKey, long packingId, String actor, String commandKey,
                                 String reason, int expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey
                                + "/packings/" + packingId + "/void")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode detail(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode diagnose(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing/diagnosis"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey, Instant from, Instant to) throws Exception {
        String url = "/api/batches/" + batchKey + "/packing/history";
        List<String> params = new ArrayList<>();
        if (from != null) {
            params.add("from=" + from);
        }
        if (to != null) {
            params.add("to=" + to);
        }
        if (!params.isEmpty()) {
            url += "?" + String.join("&", params);
        }
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private void assertNoPackingState(String batchKey) throws Exception {
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM packing_record WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(0, detail(batchKey).path("packedQuantity").asInt());
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}
