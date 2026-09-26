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
 * 批次包装标签核销端到端测试（真实 H2 库）：计划登记、批量封箱、作废、放行门禁与快照、
 * 明细/历史/诊断查询、commandKey 幂等、事务回滚与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchLabelReconciliationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM box_seal");
        jdbc.update("DELETE FROM packaging_plan");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_planSealRelease_snapshotFrozenAndDiagnosticsClean() throws Exception {
        String batchKey = "BK-LBL-HAPPY-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 10, 1000, 1009, "CK-PLAN-1", 201);

        MvcResult sealResult = seal(batchKey, "CK-SEAL-1", 201,
                spec("S1", 1000, 4), spec("S2", 1001, 3), spec("S3", 1002, 3));
        JsonNode sealBody = read(sealResult);
        assertEquals(3, sealBody.path("seals").size());
        assertEquals(10, sealBody.path("summary").path("plannedQuantity").asInt());
        assertEquals(10, sealBody.path("summary").path("sealedQuantity").asInt());
        assertEquals(0, sealBody.path("summary").path("remainingQuantity").asInt());
        assertEquals(3, sealBody.path("summary").path("usedLabelCount").asInt());

        // 明细：3 条活跃封箱
        JsonNode detail = getJson("/api/batches/" + batchKey + "/labels");
        assertEquals(3, detail.path("seals").size());
        assertEquals(1000, detail.path("plan").path("labelStart").asLong());
        assertEquals(1009, detail.path("plan").path("labelEnd").asLong());

        submitPassAndFirstApprove(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行快照固化：计划/实际/标签摘要/每个封箱版本
        JsonNode diagnostics = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        assertTrue(diagnostics.path("released").asBoolean());
        assertTrue(diagnostics.path("digestMatchesSnapshot").asBoolean());
        assertEquals(0, diagnostics.path("discrepancies").size());
        JsonNode snapshot = diagnostics.path("snapshot");
        assertEquals(10, snapshot.path("plannedQuantity").asInt());
        assertEquals(10, snapshot.path("sealedQuantity").asInt());
        assertEquals(3, snapshot.path("labelCount").asInt());
        assertEquals(3, snapshot.path("seals").size());
        // 快照封箱按标签号升序，版本为放行时的 1
        assertEquals(1000, snapshot.path("seals").get(0).path("labelNo").asLong());
        assertEquals(1, snapshot.path("seals").get(0).path("version").asInt());
        assertEquals(1002, snapshot.path("seals").get(2).path("labelNo").asLong());
        assertEquals(snapshot.path("labelDigest").asText(),
                diagnostics.path("currentLabelDigest").asText());

        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(1, snapshotRows);
    }

    // ---------- 失败分支：封箱校验 ----------

    @Test
    void sealWithoutPlan_returns422() throws Exception {
        String batchKey = "BK-LBL-NOPLAN-" + unique();
        createBatch(batchKey, List.of("t1"));
        seal(batchKey, "CK-S", 422, spec("S1", 1000, 1));
        assertEquals(0, sealCount(batchKey));
    }

    @Test
    void labelOutOfRange_returns422_andWholeBatchRollsBack() throws Exception {
        String batchKey = "BK-LBL-RANGE-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 10, 3000, 3009, "CK-P", 201);

        // 一条合法 + 一条越界：整次 422，不留下部分占用
        MvcResult result = seal(batchKey, "CK-S", 422,
                spec("S1", 3000, 1), spec("S2", 3999, 1));
        assertTrue(read(result).path("message").asText().contains("3999"));
        assertEquals(0, sealCount(batchKey), "任一标签失败整次回滚");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-S'", Integer.class),
                "失败命令不占键");
    }

    @Test
    void duplicateLabelInRequest_returns409_andRollsBack() throws Exception {
        String batchKey = "BK-LBL-DUP-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 10, 3000, 3009, "CK-P", 201);
        seal(batchKey, "CK-S", 409, spec("S1", 3000, 1), spec("S2", 3000, 1));
        assertEquals(0, sealCount(batchKey));
    }

    @Test
    void labelUsedByOtherBatch_returns409() throws Exception {
        String batchA = "BK-LBL-A-" + unique();
        String batchB = "BK-LBL-B-" + unique();
        createBatch(batchA, List.of("t1"));
        createBatch(batchB, List.of("t1"));
        // 两个批次号段重叠：标签全局只能被活跃占用一次
        registerPlan(batchA, 10, 4000, 4009, "CK-PA", 201);
        registerPlan(batchB, 10, 4000, 4009, "CK-PB", 201);

        seal(batchA, "CK-SA", 201, spec("SA1", 4000, 1));
        MvcResult conflict = seal(batchB, "CK-SB", 409, spec("SB1", 4000, 1));
        assertTrue(read(conflict).path("message").asText().contains("4000"));
        // 换未占用标签可封箱
        seal(batchB, "CK-SB2", 201, spec("SB1", 4001, 1));
    }

    @Test
    void quantityExceedsPlan_returns422_withActualRequiredAndDiff() throws Exception {
        String batchKey = "BK-LBL-QTY-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 5, 5000, 5009, "CK-P", 201);

        // 单笔即超计划
        MvcResult over = seal(batchKey, "CK-S1", 422, spec("S1", 5000, 6));
        String message = read(over).path("message").asText();
        assertTrue(message.contains("实际 6") && message.contains("计划 5") && message.contains("超出 1"),
                "应返回实际值/要求值/差额: " + message);

        seal(batchKey, "CK-S2", 201, spec("S1", 5000, 3));
        // 累计超计划
        seal(batchKey, "CK-S3", 422, spec("S2", 5001, 3));
        assertEquals(1, sealCount(batchKey));
    }

    @Test
    void sealOnTerminalBatches_returns409() throws Exception {
        // 已拒绝
        String rejected = "BK-LBL-REJ-" + unique();
        createBatch(rejected, List.of("t1"));
        registerPlan(rejected, 5, 6000, 6009, "CK-P", 201);
        submitTest(rejected, "TK-F", "t1", "FAIL", "insp", 201);
        seal(rejected, "CK-S", 409, spec("S1", 6000, 1));

        // 已放行（无计划批次可放行后再登记计划也被拒）
        String released = "BK-LBL-REL-" + unique();
        createBatch(released, List.of("t1"));
        registerPlan(released, 1, 6000, 6000, "CK-P2", 201);
        seal(released, "CK-S0", 201, spec("S0", 6000, 1));
        submitPassAndFirstApprove(released);
        approve(released, "ops", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(released));
        seal(released, "CK-S1", 409, spec("S1", 6000, 1));
        registerPlan(released, 1, 6000, 6000, "CK-P3", 409);

        // 已召回
        recall(released, "u", "上市后异常", "CK-R", 201);
        seal(released, "CK-S2", 409, spec("S2", 6000, 1));
    }

    @Test
    void unknownBatch_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(new PlanCmd("CK-P", 5, 1000, 1009));
        mockMvc.perform(post("/api/batches/NO-SUCH/packaging-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        seal("NO-SUCH", "CK-S", 404, spec("S1", 1000, 1));
        voidSeal("NO-SUCH", "S1", "r", "CK-V", 404);
        mockMvc.perform(get("/api/batches/NO-SUCH/labels")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/labels/history")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/labels/diagnostics")).andExpect(status().isNotFound());
    }

    @Test
    void invalidPlanRequests_return400() throws Exception {
        String batchKey = "BK-LBL-BADPLAN-" + unique();
        createBatch(batchKey, List.of("t1"));
        // labelStart > labelEnd
        registerPlan(batchKey, 5, 1010, 1000, "CK-P1", 400);
        // plannedQuantity 非正
        registerPlan(batchKey, 0, 1000, 1009, "CK-P2", 400);
        // 缺 labelEnd
        mockMvc.perform(post("/api/batches/" + batchKey + "/packaging-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P3\",\"plannedQuantity\":5,\"labelStart\":1000}"))
                .andExpect(status().isBadRequest());
        // quantity 非正 / 空 seals
        registerPlan(batchKey, 5, 1000, 1009, "CK-P4", 201);
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-S0\",\"seals\":[]}"))
                .andExpect(status().isBadRequest());
        seal(batchKey, "CK-S1", 400, spec("S1", 1000, 0));
        // 重复登记计划
        registerPlan(batchKey, 5, 1000, 1009, "CK-P5", 409);
    }

    // ---------- 作废 ----------

    @Test
    void voidSeal_releasesQuantityAndLabel_andHistoryKeepsEvidence() throws Exception {
        String batchKey = "BK-LBL-VOID-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 5, 7000, 7009, "CK-P", 201);
        seal(batchKey, "CK-S1", 201, spec("S1", 7000, 2), spec("S2", 7001, 3));

        MvcResult voided = voidSeal(batchKey, "S1", "贴标错位", "CK-V1", 200);
        JsonNode voidBody = read(voided);
        assertEquals("VOIDED", voidBody.path("status").asText());
        assertEquals(2, voidBody.path("version").asInt());
        assertEquals("贴标错位", voidBody.path("voidReason").asText());
        assertFalse(voidBody.path("voidedAt").isNull());

        // 明细：仅活跃封箱；汇总数量与标签占用已释放
        JsonNode detail = getJson("/api/batches/" + batchKey + "/labels");
        assertEquals(1, detail.path("seals").size());
        assertEquals("S2", detail.path("seals").get(0).path("sealKey").asText());
        assertEquals(3, detail.path("summary").path("sealedQuantity").asInt());
        assertEquals(2, detail.path("summary").path("remainingQuantity").asInt());

        // 历史：两条都在，作废行保留原因与版本
        JsonNode history = getJson("/api/batches/" + batchKey + "/labels/history");
        assertEquals(2, history.path("seals").size());
        JsonNode voidedRow = history.path("seals").get(0);
        assertEquals("VOIDED", voidedRow.path("status").asText());
        assertEquals("贴标错位", voidedRow.path("voidReason").asText());

        // 标签占用已释放：同标签可重新封箱
        seal(batchKey, "CK-S2", 201, spec("S3", 7000, 2));
        JsonNode detailAfter = getJson("/api/batches/" + batchKey + "/labels");
        assertEquals(5, detailAfter.path("summary").path("sealedQuantity").asInt());

        // 重复作废 409；未知封箱 404
        voidSeal(batchKey, "S1", "再次作废", "CK-V2", 409);
        voidSeal(batchKey, "NO-SUCH-SEAL", "r", "CK-V3", 404);
    }

    @Test
    void voidOnReleasedBatch_returns409_andSnapshotUntouched() throws Exception {
        String batchKey = "BK-LBL-VOIDREL-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 2, 7100, 7109, "CK-P", 201);
        seal(batchKey, "CK-S", 201, spec("S1", 7100, 2));
        submitPassAndFirstApprove(batchKey);
        approve(batchKey, "ops", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode before = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        voidSeal(batchKey, "S1", "放行后申请作废", "CK-V", 409);
        JsonNode after = getJson("/api/batches/" + batchKey + "/labels/diagnostics");

        // 快照与当前状态均未改写
        assertEquals(before.path("snapshot").path("labelDigest").asText(),
                after.path("snapshot").path("labelDigest").asText());
        assertTrue(after.path("digestMatchesSnapshot").asBoolean());
        assertEquals(0, after.path("discrepancies").size());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM box_seal WHERE batch_key = ? AND seal_key = 'S1'",
                String.class, batchKey));
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseGate_incompleteSealingBlocksFinalApproval_untilComplete() throws Exception {
        String batchKey = "BK-LBL-GATE-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 5, 8000, 8009, "CK-P", 201);
        seal(batchKey, "CK-S1", 201, spec("S1", 8000, 3));
        submitPassAndFirstApprove(batchKey);

        // 封箱 3/5，终审 422 且携带差额
        MvcResult denied = approve(batchKey, "ops", "OPERATIONS", "CK-A2", 422);
        String message = read(denied).path("message").asText();
        assertTrue(message.contains("实际 3") && message.contains("计划 5") && message.contains("差额 2"),
                "应返回实际值/要求值/差额: " + message);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 作废部分封箱后补齐，终审放行
        voidSeal(batchKey, "S1", "数量登记错误", "CK-V1", 200);
        seal(batchKey, "CK-S2", 201, spec("S2", 8001, 2), spec("S3", 8002, 3));
        approve(batchKey, "ops", "OPERATIONS", "CK-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 快照记录的是放行时的封箱集合（S1 已作废不在快照中）
        JsonNode diagnostics = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        assertEquals(2, diagnostics.path("snapshot").path("labelCount").asInt());
        assertEquals(5, diagnostics.path("snapshot").path("sealedQuantity").asInt());
        assertTrue(diagnostics.path("digestMatchesSnapshot").asBoolean());
    }

    @Test
    void batchWithoutPlan_releasesWithoutGate() throws Exception {
        String batchKey = "BK-LBL-NOPLANREL-" + unique();
        createBatch(batchKey, List.of("t1"));
        submitPassAndFirstApprove(batchKey);
        approve(batchKey, "ops", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 无计划：诊断中计划为 null，无快照
        JsonNode diagnostics = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        assertTrue(diagnostics.path("plannedQuantity").isNull());
        assertTrue(diagnostics.path("snapshot").isNull());
        assertTrue(diagnostics.path("digestMatchesSnapshot").isNull());
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sealReplayAndChangedParamsAndFailureNotOccupying() throws Exception {
        String batchKey = "BK-LBL-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 10, 9000, 9009, "CK-P", 201);

        String body = sealBody("CK-S", spec("S1", 9000, 2));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次结果");
        assertEquals(1, sealCount(batchKey), "重放不新增封箱");

        // 同键异参 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-S", spec("S1", 9001, 2))))
                .andExpect(status().isConflict());

        // 失败不占键：越界 422 后同键改正参数成功
        seal(batchKey, "CK-F", 422, spec("SF", 9999, 1));
        seal(batchKey, "CK-F", 201, spec("SF", 9001, 1));

        // 不同 commandKey、同 sealKey 同内容 → 记录级重放，整体 200 不新增
        MvcResult recordReplay = seal(batchKey, "CK-S9", 200, spec("S1", 9000, 2));
        assertEquals(2, sealCount(batchKey));
        assertEquals(2, read(recordReplay).path("summary").path("usedLabelCount").asInt());
        // 同 sealKey 异内容 → 409
        seal(batchKey, "CK-S10", 409, spec("S1", 9002, 2));
    }

    @Test
    void commandKey_planAndVoidReplay() throws Exception {
        String batchKey = "BK-LBL-IDEM2-" + unique();
        createBatch(batchKey, List.of("t1"));
        String planBody = objectMapper.writeValueAsString(new PlanCmd("CK-P", 5, 9100, 9109));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/packaging-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/packaging-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键异参 → 409
        registerPlan(batchKey, 6, 9100, 9109, "CK-P", 409);

        seal(batchKey, "CK-S", 201, spec("S1", 9100, 1));
        String voidBody = "{\"commandKey\":\"CK-V\",\"reason\":\"r1\"}";
        MvcResult voidFirst = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult voidReplay = mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isOk()).andReturn();
        assertEquals(voidFirst.getResponse().getContentAsString(),
                voidReplay.getResponse().getContentAsString());
        // 同键异参（不同原因）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/seals/S1/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V\",\"reason\":\"r2\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameLabelAcrossBatches_exactlyOneActiveOccupancy() throws Exception {
        String batchA = "BK-LBL-RA-" + unique();
        String batchB = "BK-LBL-RB-" + unique();
        createBatch(batchA, List.of("t1"));
        createBatch(batchB, List.of("t1"));
        registerPlan(batchA, 10, 9500, 9509, "CK-PA", 201);
        registerPlan(batchB, 10, 9500, 9509, "CK-PB", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchA + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SA", spec("SA", 9500, 1)))),
                () -> callStatus(post("/api/batches/" + batchB + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SB", spec("SB", 9500, 1))))
        );
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created, "并发同标签最多一次成功");
        assertEquals(1, conflict);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM box_seal WHERE active_label_no = 9500", Integer.class);
        assertEquals(1, active, "数据库唯一约束保证同标签仅一条活跃占用");
    }

    @Test
    void concurrentSealsSameBatch_quantityNeverExceedsPlan() throws Exception {
        String batchKey = "BK-LBL-RSUM-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 5, 9600, 9609, "CK-P", 201);

        // 两笔各 3，合计 6 超计划 5：行锁串行后一笔 201、一笔 422
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-S1", spec("S1", 9600, 3)))),
                () -> callStatus(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-S2", spec("S2", 9601, 3))))
        );
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertTrue(codes.contains(201) && codes.contains(422), "应一笔成功一笔超计划: " + codes);
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM box_seal WHERE batch_key = ? AND status = 'ACTIVE'",
                Integer.class, batchKey);
        assertEquals(3, sum, "活跃封箱数量之和不得超过计划");
    }

    @Test
    void concurrentSameCommandKeySeal_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-LBL-RCK-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 10, 9700, 9709, "CK-P", 201);
        String body = sealBody("CK-S", spec("S1", 9700, 2), spec("S2", 9701, 3));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(2, sealCount(batchKey), "并发同键只落一次封箱");
    }

    // ---------- 只读查询 ----------

    @Test
    void queriesAreReadOnly_andStableAcrossRepeats() throws Exception {
        String batchKey = "BK-LBL-RO-" + unique();
        createBatch(batchKey, List.of("t1"));
        registerPlan(batchKey, 4, 9800, 9809, "CK-P", 201);
        seal(batchKey, "CK-S", 201, spec("S1", 9800, 2), spec("S2", 9801, 2));
        voidSeal(batchKey, "S2", "外观破损", "CK-V", 200);

        JsonNode detail1 = getJson("/api/batches/" + batchKey + "/labels");
        JsonNode detail2 = getJson("/api/batches/" + batchKey + "/labels");
        JsonNode history1 = getJson("/api/batches/" + batchKey + "/labels/history");
        JsonNode diag1 = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        JsonNode diag2 = getJson("/api/batches/" + batchKey + "/labels/diagnostics");
        assertEquals(detail1.toString(), detail2.toString());
        assertEquals(diag1.toString(), diag2.toString());
        assertEquals(1, detail1.path("seals").size(), "明细仅活跃封箱");
        assertEquals(2, history1.path("seals").size(), "历史含已作废封箱");
        assertFalse(diag1.path("released").asBoolean());
        assertTrue(diag1.path("labelCountMatchesSealCount").asBoolean());
        // 读取不改变状态
        assertEquals(2, sealCount(batchKey));
        assertEquals("QUARANTINED", currentStatus(batchKey));
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private record PlanCmd(String commandKey, int plannedQuantity, long labelStart, long labelEnd) {
    }

    private record Spec(String sealKey, long labelNo, int quantity) {
    }

    private record SealCmd(String commandKey, List<Spec> seals) {
    }

    private Spec spec(String sealKey, long labelNo, int quantity) {
        return new Spec(sealKey, labelNo, quantity);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void registerPlan(String batchKey, int planned, long start, long end,
                              String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/packaging-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlanCmd(commandKey, planned, start, end))))
                .andExpect(status().is(expected));
    }

    private String sealBody(String commandKey, Spec... specs) {
        try {
            return objectMapper.writeValueAsString(new SealCmd(commandKey, List.of(specs)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MvcResult seal(String batchKey, String commandKey, int expected, Spec... specs)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody(commandKey, specs)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult voidSeal(String batchKey, String sealKey, String reason, String commandKey,
                               int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/seals/" + sealKey + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"" + testKey
                                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                                + "\",\"inspector\":\"" + inspector + "\"}"))
                .andExpect(status().is(expected));
    }

    private void submitPassAndFirstApprove(String batchKey) throws Exception {
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-A1", 201);
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/history").path("batch").path("status").asText();
    }

    private int sealCount(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM box_seal WHERE batch_key = ?", Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
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
