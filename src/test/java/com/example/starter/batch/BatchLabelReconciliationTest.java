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
 * 批次包装标签核销测试：计划登记、批量封箱主流程与原子回滚、作废释放、
 * 放行门禁与快照固化、快照不可改写、commandKey 幂等、并发同标签裁决、诊断查询。
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
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_label_snapshot");
        jdbc.update("DELETE FROM label_usage");
        jdbc.update("DELETE FROM pack_case");
        jdbc.update("DELETE FROM label_plan");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyPath_planSealRelease_snapshotFrozenAndQueriesConsistent() throws Exception {
        String batch = "BK-LR-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-1", 3, 1000, 1010, 201);

        MvcResult seal = seal(batch, "CK-SEAL-1",
                List.<Object[]>of(new Object[]{"CASE-A", 1000L, 1}, new Object[]{"CASE-B", 1001L, 2}), 201);
        JsonNode sealBody = objectMapper.readTree(seal.getResponse().getContentAsString());
        assertEquals(3, sealBody.path("totalSealedQuantity").asInt());
        assertEquals(3, sealBody.path("plannedQuantity").asInt());
        assertEquals(0, sealBody.path("remainingQuantity").asInt());
        assertEquals(2, sealBody.path("cases").size());

        // 汇总：已封 3、差额 0、已用标签规范排序、未放行无快照
        JsonNode summary = summary(batch);
        assertEquals(3, summary.path("sealedQuantity").asInt());
        assertEquals(3, summary.path("plannedQuantity").asInt());
        assertEquals(0, summary.path("remainingQuantity").asInt());
        assertEquals(2, summary.path("activeCaseCount").asInt());
        assertEquals(1000L, summary.path("usedLabels").get(0).asLong());
        assertEquals(1001L, summary.path("usedLabels").get(1).asLong());
        assertTrue(summary.path("snapshot").isNull());

        releaseBatch(batch);

        // 放行快照固化：计划数量、实际封箱数量、封箱数、摘要与每个封箱版本
        JsonNode after = summary(batch);
        JsonNode snapshot = after.path("snapshot");
        assertFalse(snapshot.isNull());
        assertEquals(3, snapshot.path("plannedQuantity").asInt());
        assertEquals(3, snapshot.path("sealedQuantity").asInt());
        assertEquals(2, snapshot.path("caseCount").asInt());
        assertEquals(64, snapshot.path("labelDigest").asText().length());
        assertTrue(snapshot.path("caseVersions").asText().contains("CASE-A:1"));
        assertTrue(snapshot.path("caseVersions").asText().contains("CASE-B:1"));

        // 诊断：已放行且无差异
        JsonNode diag = reconciliation(batch);
        assertTrue(diag.path("released").asBoolean());
        assertEquals(0, diag.path("quantityDifference").asInt());
        assertEquals(0, diag.path("discrepancies").size());

        // 明细：两条 ACTIVE 记录，版本 1，未作废字段为 null
        JsonNode cases = cases(batch);
        assertEquals(2, cases.size());
        assertEquals("ACTIVE", cases.get(0).path("status").asText());
        assertEquals(1, cases.get(0).path("version").asInt());
        assertTrue(cases.get(0).path("voidedAt").isNull());
        assertTrue(cases.get(0).path("voidReason").isNull());
    }

    @Test
    void releaseBlocked_whenSealingIncomplete_returns422WithDifference() throws Exception {
        String batch = "BK-GATE-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-G", 2, 2000, 2010, 201);
        seal(batch, "CK-SEAL-G1", List.<Object[]>of(new Object[]{"CASE-1", 2000L, 1}), 201);

        submitTestPass(batch);
        approve(batch, "qa-1", "QUALITY", "CK-AG-1", 201);
        // 第二次批准触发放行门禁：已封 1、计划 2、差额 1
        MvcResult blocked = mockMvc.perform(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AG-2\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = objectMapper.readTree(blocked.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains("1") && message.contains("2"), message);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 诊断报告差额；失败不占键，补足数量后同一 commandKey 放行成功
        JsonNode diag = reconciliation(batch);
        assertEquals(1, diag.path("quantityDifference").asInt());
        assertEquals(1, diag.path("discrepancies").size());
        seal(batch, "CK-SEAL-G2", List.<Object[]>of(new Object[]{"CASE-2", 2001L, 1}), 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-AG-2", 201);
        assertEquals("RELEASED", currentStatus(batch));
    }

    // ---------- 封箱失败分支与原子性 ----------

    @Test
    void sealLabelOutOfRange_returns422_andWholeBatchRolledBack() throws Exception {
        String batch = "BK-OOR-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-O", 5, 100, 105, 201);

        // 右端点 105 为开区间，越界；同事务内合法标签 100 也不得留下占用
        seal(batch, "CK-SEAL-O", List.<Object[]>of(new Object[]{"CASE-OK", 100L, 1},
                new Object[]{"CASE-OUT", 105L, 1}), 422);
        assertEquals(0, countRows("label_usage"));
        assertEquals(0, countRows("pack_case"));

        // 低于左端点同样越界
        seal(batch, "CK-SEAL-O2", List.<Object[]>of(new Object[]{"CASE-LOW", 99L, 1}), 422);
        assertEquals(0, countRows("label_usage"));

        // 失败不占键：修正后同一 commandKey 成功
        seal(batch, "CK-SEAL-O", List.<Object[]>of(new Object[]{"CASE-OK", 100L, 1}), 201);
        assertEquals(1, countRows("label_usage"));
    }

    @Test
    void sealDuplicateLabel_returns409_andNoPartialOccupation() throws Exception {
        String batch = "BK-DUP-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-D", 5, 300, 310, 201);
        seal(batch, "CK-SEAL-D1", List.<Object[]>of(new Object[]{"CASE-1", 300L, 1}), 201);

        // 与已占用标签冲突
        seal(batch, "CK-SEAL-D2", List.<Object[]>of(new Object[]{"CASE-2", 300L, 1}), 409);
        // 请求内标签重复
        seal(batch, "CK-SEAL-D3", List.<Object[]>of(new Object[]{"CASE-3", 301L, 1},
                new Object[]{"CASE-4", 301L, 1}), 409);
        // 请求内 caseKey 重复
        seal(batch, "CK-SEAL-D4", List.<Object[]>of(new Object[]{"CASE-5", 302L, 1},
                new Object[]{"CASE-5", 303L, 1}), 409);
        // 混合合法与冲突标签：整次回滚
        seal(batch, "CK-SEAL-D5", List.<Object[]>of(new Object[]{"CASE-6", 304L, 1},
                new Object[]{"CASE-7", 300L, 1}), 409);
        assertEquals(1, countRows("label_usage"));
        assertEquals(1, countRows("pack_case"));
    }

    @Test
    void sealQuantityExceedsPlan_returns422WithActualRequiredAndExcess() throws Exception {
        String batch = "BK-QTY-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-Q", 2, 400, 410, 201);
        seal(batch, "CK-SEAL-Q1", List.<Object[]>of(new Object[]{"CASE-1", 400L, 2}), 201);

        MvcResult over = mockMvc.perform(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SEAL-Q2", List.<Object[]>of(new Object[]{"CASE-2", 401L, 1}))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = objectMapper.readTree(over.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains("已封箱 2") && message.contains("本次 1")
                && message.contains("计划 2") && message.contains("超出 1"), message);
        assertEquals(1, countRows("pack_case"));
    }

    @Test
    void sealStateConflicts_return409or422() throws Exception {
        // 未登记计划 → 422
        String noPlan = "BK-NP-" + unique();
        createBatch(noPlan);
        seal(noPlan, "CK-SEAL-NP", List.<Object[]>of(new Object[]{"CASE-1", 500L, 1}), 422);

        // 已放行 → 409
        String released = "BK-REL-" + unique();
        createBatch(released);
        registerPlan(released, "CK-LP-R", 1, 500, 510, 201);
        seal(released, "CK-SEAL-R1", List.<Object[]>of(new Object[]{"CASE-1", 500L, 1}), 201);
        releaseBatch(released);
        seal(released, "CK-SEAL-R2", List.<Object[]>of(new Object[]{"CASE-2", 501L, 1}), 409);

        // 已召回 → 409
        recall(released, "qa-lead", "放行后发现标签差异", "CK-RC-1", 201);
        seal(released, "CK-SEAL-R3", List.<Object[]>of(new Object[]{"CASE-3", 502L, 1}), 409);

        // 已拒收 → 409
        String rejected = "BK-REJ-" + unique();
        createBatch(rejected);
        registerPlan(rejected, "CK-LP-RJ", 1, 600, 610, 201);
        submitTest(rejected, "FAIL", "insp-1", 201);
        seal(rejected, "CK-SEAL-RJ", List.<Object[]>of(new Object[]{"CASE-1", 600L, 1}), 409);

        // 批次不存在 → 404
        seal("NO-SUCH-BATCH", "CK-SEAL-404", List.<Object[]>of(new Object[]{"CASE-1", 1L, 1}), 404);
    }

    // ---------- 计划登记 ----------

    @Test
    void registerPlan_validationAndConflicts() throws Exception {
        String batch = "BK-LPV-" + unique();
        createBatch(batch);

        // 号段右端点不大于左端点 → 400（左闭右开）
        registerPlan(batch, "CK-LP-V1", 1, 100, 100, 400);
        registerPlan(batch, "CK-LP-V2", 1, 100, 99, 400);
        // 计划数量非正 → 400
        registerPlan(batch, "CK-LP-V3", 0, 100, 200, 400);
        // 批次不存在 → 404
        registerPlan("NO-SUCH-BATCH", "CK-LP-V4", 1, 100, 200, 404);

        registerPlan(batch, "CK-LP-V5", 2, 100, 200, 201);
        // 重复登记（不同 commandKey）→ 409
        registerPlan(batch, "CK-LP-V6", 2, 100, 200, 409);
        // 同 commandKey 同参重放 → 首次结果
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batch + "/label-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("CK-LP-V5", 2, 100, 200)))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(2, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("plannedQuantity").asInt());
        // 同 commandKey 改参 → 409
        registerPlan(batch, "CK-LP-V5", 3, 100, 200, 409);
        assertEquals(1, countRows("label_plan"));

        // 已放行批次登记 → 409
        String released = "BK-LPR-" + unique();
        createBatch(released);
        releaseBatch(released);
        registerPlan(released, "CK-LP-V7", 1, 100, 200, 409);
    }

    // ---------- 作废 ----------

    @Test
    void voidCase_releasesLabelAndQuantity_keepsHistory() throws Exception {
        String batch = "BK-VOID-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-VO", 3, 700, 710, 201);
        seal(batch, "CK-SEAL-V1", List.<Object[]>of(new Object[]{"CASE-A", 700L, 2},
                new Object[]{"CASE-B", 701L, 1}), 201);

        MvcResult voided = voidCase(batch, "CASE-A", "CK-VOID-1", "贴标错误", 200);
        JsonNode voidBody = objectMapper.readTree(voided.getResponse().getContentAsString());
        assertEquals("VOIDED", voidBody.path("status").asText());
        assertEquals(2, voidBody.path("releasedQuantity").asInt());
        assertEquals(1, voidBody.path("activeSealedQuantity").asInt());
        assertEquals(3, voidBody.path("plannedQuantity").asInt());
        assertEquals(2, voidBody.path("remainingQuantity").asInt());
        assertEquals(2, voidBody.path("version").asInt());

        // 标签占用释放：label_usage 只剩 CASE-B；原封箱记录保留原因与时间
        assertEquals(1, countRows("label_usage"));
        JsonNode cases = cases(batch);
        assertEquals(2, cases.size());
        JsonNode voidedCase = cases.get(0);
        assertEquals("VOIDED", voidedCase.path("status").asText());
        assertEquals("贴标错误", voidedCase.path("voidReason").asText());
        assertFalse(voidedCase.path("voidedAt").isNull());

        // 释放后的标签号可复用
        seal(batch, "CK-SEAL-V2", List.<Object[]>of(new Object[]{"CASE-C", 700L, 2}), 201);
        JsonNode summary = summary(batch);
        assertEquals(3, summary.path("sealedQuantity").asInt());
        assertEquals(0, summary.path("remainingQuantity").asInt());

        // 重复作废 → 409；作废未知封箱 → 404
        voidCase(batch, "CASE-A", "CK-VOID-2", "再次作废", 409);
        voidCase(batch, "NO-SUCH-CASE", "CK-VOID-3", "不存在", 404);

        // 作废幂等：同 commandKey 同参重放首次结果，改参 409
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batch + "/cases/CASE-B/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-VOID-B\",\"reason\":\"复检不合格\"}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay2 = mockMvc.perform(post("/api/batches/" + batch + "/cases/CASE-B/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-VOID-B\",\"reason\":\"复检不合格\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(replay.getResponse().getContentAsString(),
                replay2.getResponse().getContentAsString());
        mockMvc.perform(post("/api/batches/" + batch + "/cases/CASE-B/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-VOID-B\",\"reason\":\"改参\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void voidAfterRelease_rejected409_andSnapshotUntouched() throws Exception {
        String batch = "BK-VREL-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-VR", 1, 800, 810, 201);
        seal(batch, "CK-SEAL-VR", List.<Object[]>of(new Object[]{"CASE-A", 800L, 1}), 201);
        releaseBatch(batch);

        String snapshotBefore = summary(batch).path("snapshot").toString();
        // 已放行批次作废申请 → 409，且不得改写放行快照
        voidCase(batch, "CASE-A", "CK-VOID-VR", "放行后作废", 409);
        assertEquals(snapshotBefore, summary(batch).path("snapshot").toString());
        // 封箱记录与标签占用保持不变
        assertEquals("ACTIVE", cases(batch).get(0).path("status").asText());
        assertEquals(1, countRows("label_usage"));
        // 诊断仍无差异
        assertEquals(0, reconciliation(batch).path("discrepancies").size());
    }

    // ---------- 封箱幂等 ----------

    @Test
    void sealCommandKey_sameParamsReplay_changedParamsConflict() throws Exception {
        String batch = "BK-SID-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-SI", 5, 900, 910, 201);
        String body = sealBody("CK-SEAL-SI", List.<Object[]>of(new Object[]{"CASE-1", 900L, 1},
                new Object[]{"CASE-2", 901L, 2}));

        MvcResult first = mockMvc.perform(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复占用
        assertEquals(2, countRows("pack_case"));
        assertEquals(2, countRows("label_usage"));

        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SEAL-SI", List.<Object[]>of(new Object[]{"CASE-1", 900L, 2}))))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSealSameLabel_exactlyOneSucceeds() throws Exception {
        String batch = "BK-CON-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-C", 5, 1000, 1010, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-CON-1", List.<Object[]>of(new Object[]{"CASE-T1", 1000L, 1})))),
                () -> callStatus(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-CON-2", List.<Object[]>of(new Object[]{"CASE-T2", 1000L, 1})))),
                () -> callStatus(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-CON-3", List.<Object[]>of(new Object[]{"CASE-T3", 1000L, 1})))),
                () -> callStatus(post("/api/batches/" + batch + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-CON-4", List.<Object[]>of(new Object[]{"CASE-T4", 1000L, 1}))))
        );

        int created = 0;
        int conflicted = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflicted++;
            }
        }
        assertEquals(1, created, "并发同标签最多一次成功");
        assertEquals(3, conflicted);
        // 最终数据：恰好一条有效封箱与一条标签占用
        assertEquals(1, countRows("label_usage"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pack_case WHERE status = 'ACTIVE'", Integer.class));
    }

    // ---------- 诊断与只读 ----------

    @Test
    void reconciliationAndSummary_areReadOnlyAndStable() throws Exception {
        String batch = "BK-RO-" + unique();
        createBatch(batch);
        registerPlan(batch, "CK-LP-RO", 2, 1100, 1110, 201);
        seal(batch, "CK-SEAL-RO", List.<Object[]>of(new Object[]{"CASE-1", 1100L, 1}), 201);

        JsonNode summary1 = summary(batch);
        JsonNode diag1 = reconciliation(batch);
        JsonNode summary2 = summary(batch);
        JsonNode diag2 = reconciliation(batch);
        assertEquals(summary1.toString(), summary2.toString());
        assertEquals(diag1.toString(), diag2.toString());
        // 读取不改变状态
        assertEquals("QUARANTINED", currentStatus(batch));
        assertEquals(1, countRows("label_usage"));
        // 未放行且未封满：诊断报告差额
        assertEquals(1, diag1.path("quantityDifference").asInt());
        assertFalse(diag1.path("discrepancies").isEmpty());

        // 未知批次查询 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH/cases")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/label-summary"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/label-reconciliation"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void createBatch(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCmd("CK-C-" + unique(), batchKey, "PROD-1", "LOT-1",
                                        Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")))))
                .andExpect(status().isCreated());
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode,
                             String batchNo, Instant producedAt, List<String> requiredTests) {
    }

    private String planBody(String commandKey, int plannedQuantity, long start, long end) {
        return "{\"commandKey\":\"" + commandKey + "\",\"plannedQuantity\":" + plannedQuantity
                + ",\"labelStart\":" + start + ",\"labelEnd\":" + end + "}";
    }

    private void registerPlan(String batchKey, String commandKey, int plannedQuantity,
                              long start, long end, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/label-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(commandKey, plannedQuantity, start, end)))
                .andExpect(status().is(expected));
    }

    private String sealBody(String commandKey, List<Object[]> cases) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"cases\":[");
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"caseKey\":\"").append(cases.get(i)[0])
                    .append("\",\"labelNo\":").append(cases.get(i)[1])
                    .append(",\"quantity\":").append(cases.get(i)[2]).append('}');
        }
        return sb.append("]}").toString();
    }

    private MvcResult seal(String batchKey, String commandKey, List<Object[]> cases, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody(commandKey, cases)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult voidCase(String batchKey, String caseKey, String commandKey, String reason,
                               int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/cases/" + caseKey + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String result, String inspector, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"t1\",\"result\":\"" + result
                                + "\",\"inspector\":\"" + inspector + "\"}"))
                .andExpect(status().is(expected));
    }

    private void submitTestPass(String batchKey) throws Exception {
        submitTest(batchKey, "PASS", "insp-1", 201);
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED（含放行标签门禁与快照固化）。
     */
    private void releaseBatch(String batchKey) throws Exception {
        submitTestPass(batchKey);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode summary(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/label-summary"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode reconciliation(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/batches/" + batchKey + "/label-reconciliation"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode cases(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/cases"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
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
