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
 * 包装标签核销端到端测试（真实 H2 MySQL 兼容内存库）：
 * 主流程、422/409 失败分支、批量整次回滚、作废释放、放行闸门与快照固化、
 * commandKey 幂等（含失败不占键）、并发同标签/同批次守恒、明细/历史/快照/诊断只读语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LabelReconciliationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_snapshot_seal");
        jdbc.update("DELETE FROM release_label_snapshot");
        jdbc.update("DELETE FROM label_occupancy");
        jdbc.update("DELETE FROM package_seal");
        jdbc.update("DELETE FROM label_plan");
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
    void happyFlow_registerSealReconcileRelease_snapshotFrozenAndImmutable() throws Exception {
        String batchKey = "BK-LBL-HAPPY-" + unique();
        preparePendingReleaseBatch(batchKey);

        // 登记计划：数量 10，号段 100-102
        registerPlan(batchKey, 10, 100, 102, 201);

        // 分两次封箱：4 + 6，覆盖全部标签，数量封足
        JsonNode r1 = sealBoxes(batchKey, List.of(seal(100, 4), seal(101, 4)), "CK-SEAL-1", 201);
        assertEquals(8, r1.path("sealedQuantity").asInt());
        assertEquals(10, r1.path("plannedQuantity").asInt());
        assertEquals(2, r1.path("remainingQuantity").asInt());
        assertEquals(2, r1.path("activeLabelCount").asInt());
        assertEquals(2, r1.path("activeSealCount").asInt());
        assertEquals(3, r1.path("rangeLabelCount").asInt());
        assertEquals(1, r1.path("remainingLabels").asInt());
        assertFalse(r1.path("reconciled").asBoolean());

        JsonNode r2 = sealBoxes(batchKey, List.of(seal(102, 2)), "CK-SEAL-2", 201);
        assertEquals(10, r2.path("sealedQuantity").asInt());
        assertEquals(0, r2.path("remainingQuantity").asInt());
        assertEquals(3, r2.path("activeLabelCount").asInt());
        assertTrue(r2.path("reconciled").asBoolean());

        // 未封足时放行被 422 已在数量阶段验证；此处已封足，两步放行成功
        approve(batchKey, "qa-1", "QUALITY", "CK-AP-1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行快照固化：计划数量、实际数量、规范排序摘要、每个封箱版本
        JsonNode snap = snapshot(batchKey);
        assertEquals(10, snap.path("plannedQuantity").asInt());
        assertEquals(10, snap.path("sealedQuantity").asInt());
        assertEquals("100,101,102", snap.path("usedLabels").asText());
        assertEquals("100-102", snap.path("rangeSummary").asText());
        assertEquals(3, snap.path("usedLabelCount").asInt());
        assertEquals(3, snap.path("sealVersionCount").asInt());
        assertEquals(100, snap.path("seals").get(0).path("labelNo").asLong());
        assertEquals(4, snap.path("seals").get(0).path("quantity").asInt());
        assertEquals(1, snap.path("seals").get(0).path("version").asInt());
        assertEquals(1, snap.path("seals").get(0).path("seq").asInt());
        assertEquals(102, snap.path("seals").get(2).path("labelNo").asLong());
        assertEquals(2, snap.path("seals").get(2).path("quantity").asInt());

        // 已放行：封箱/作废均 409
        sealBoxesRaw(batchKey, List.of(seal(100, 1)), "CK-SEAL-AFTER", 409);
        voidSeals(batchKey, List.of(voidItem(100, "放行后作废")), "CK-VOID-AFTER", 409);

        // 快照与诊断不随后续操作改变（读取不改状态），诊断无差异
        JsonNode snapAgain = snapshot(batchKey);
        assertEquals(snap.toString(), snapAgain.toString());
        JsonNode diag = diagnosis(batchKey);
        assertTrue(diag.path("reconciled").asBoolean());
        assertEquals(0, diag.path("labelSealCountDifference").asInt());
        assertEquals(0, diag.path("snapshotDiff").path("labelsOnlyInSnapshot").size());
        assertEquals(0, diag.path("snapshotDiff").path("labelsOnlyInCurrent").size());
        assertEquals(0, diag.path("snapshotDiff").path("sealedQuantityDelta").asInt());
        assertTrue(diag.path("snapshotDiff").path("snapshotImmutable").asBoolean());

        // 已放行批次差异只能走召回；召回后快照仍可查且不变
        recall(batchKey, "u", "标签差异核查", "CK-RC-1", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
        assertEquals(snap.toString(), snapshot(batchKey).toString());
        voidSeals(batchKey, List.of(voidItem(100, "召回后作废")), "CK-VOID-RECALLED", 409);
    }

    // ---------- 批量整次回滚 / 失败分支 ----------

    @Test
    void outOfRangeLabel_batchRollback_noPartialOccupancy() throws Exception {
        String batchKey = "BK-LBL-RANGE-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 100, 100, 110, 201);

        // 第一条合法、第二条越界：整次回滚，不留半成品
        sealBoxesRaw(batchKey, List.of(seal(100, 5), seal(200, 5)), "CK-SEAL-BAD", 422);
        assertEquals(0, countSeals(batchKey));
        assertEquals(0, countOccupancy(batchKey));
        // 失败不占键：同一 commandKey 随后可成功使用
        JsonNode ok = sealBoxes(batchKey, List.of(seal(100, 5)), "CK-SEAL-BAD", 201);
        assertEquals(5, ok.path("sealedQuantity").asInt());
    }

    @Test
    void duplicateLabelWithinRequest_rollsBack() throws Exception {
        String batchKey = "BK-LBL-DUPREQ-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 100, 100, 110, 201);
        sealBoxesRaw(batchKey, List.of(seal(100, 1), seal(100, 2)), "CK-DUPREQ", 422);
        assertEquals(0, countSeals(batchKey));
    }

    @Test
    void quantityExceedingPlan_returns422_withActualRequiredAndDelta() throws Exception {
        String batchKey = "BK-LBL-OVER-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 110, 201);
        sealBoxes(batchKey, List.of(seal(100, 6)), "CK-OK", 201);
        // 再封 5 将达 11，超出计划 1
        MvcResult result = mockMvc.perform(postSeals(batchKey, List.of(seal(101, 5)), "CK-OVER"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode err = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", err.path("code").asText());
        String msg = err.path("message").asText();
        assertTrue(msg.contains("11") && msg.contains("10"), "响应须含实际值/要求值: " + msg);
        // 整次回滚：数量仍为 6，标签 101 未占用
        JsonNode detail = detail(batchKey);
        assertEquals(6, detail.path("sealedQuantity").asInt());
        assertEquals(4, detail.path("remainingQuantity").asInt());
        assertEquals(1, detail.path("activeSealCount").asInt());
    }

    @Test
    void duplicateActiveSealAndCrossBatchLabel_return409() throws Exception {
        String batchA = "BK-LBL-A-" + unique();
        String batchB = "BK-LBL-B-" + unique();
        preparePendingReleaseBatch(batchA);
        preparePendingReleaseBatch(batchB);
        registerPlan(batchA, 100, 100, 110, 201);
        registerPlan(batchB, 100, 100, 110, 201);

        sealBoxes(batchA, List.of(seal(105, 3)), "CK-A1", 201);
        // 同批次同标签重复封箱
        sealBoxesRaw(batchA, List.of(seal(105, 1)), "CK-A2", 409);
        // 同标签用于其他批次：标签全局只能关联一个批次
        sealBoxesRaw(batchB, List.of(seal(105, 1)), "CK-B1", 409);
        assertEquals(0, countSeals(batchB));
    }

    @Test
    void voidSeal_releasesQuantityAndLabel_thenResealIncrementsVersion() throws Exception {
        String batchKey = "BK-LBL-VOID-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 102, 201);
        sealBoxes(batchKey, List.of(seal(100, 6), seal(101, 4)), "CK-S1", 201);

        // 作废 100：释放数量 6 与标签占用，写入原因
        JsonNode vr = voidSeals(batchKey, List.of(voidItem(100, "装箱破损")), "CK-V1", 201);
        assertEquals(6, vr.path("releasedQuantity").asInt());
        assertEquals(4, vr.path("sealedQuantity").asInt());
        assertEquals(6, vr.path("remainingQuantity").asInt());
        assertEquals(1, vr.path("activeLabelCount").asInt());
        assertEquals(1, vr.path("activeSealCount").asInt());
        assertEquals("装箱破损", vr.path("voidedSeals").get(0).path("voidReason").asText());
        assertNotNull(vr.path("voidedSeals").get(0).path("voidedAt").asText(null));

        // 标签释放后可在同批次重新封箱，版本号递增为 2
        JsonNode rs = sealBoxes(batchKey, List.of(seal(100, 6)), "CK-S2", 201);
        assertEquals(10, rs.path("sealedQuantity").asInt());
        assertEquals(2, rs.path("acceptedSeals").get(0).path("version").asInt());

        // 历史保留两个版本：v1 VOIDED（含原因）、v2 ACTIVE（作废字段为 null）
        JsonNode hist = history(batchKey);
        JsonNode seals = hist.path("seals");
        assertEquals(3, seals.size());
        JsonNode v1 = seals.get(0);
        assertEquals("VOIDED", v1.path("status").asText());
        assertEquals(1, v1.path("version").asInt());
        assertEquals("装箱破损", v1.path("voidReason").asText());
        assertTrue(v1.path("voidedAt").asText().length() > 0);
        JsonNode v2 = seals.get(2);
        assertEquals("ACTIVE", v2.path("status").asText());
        assertEquals(2, v2.path("version").asInt());
        assertTrue(v2.path("voidReason").isNull());
        assertTrue(v2.path("voidedAt").isNull());
        assertTrue(v2.path("voidCommandKey").isNull());
    }

    @Test
    void voidPartialBatchRollbackAndInvalidTargets() throws Exception {
        String batchKey = "BK-LBL-VOIDRB-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 103, 201);
        sealBoxes(batchKey, List.of(seal(100, 3), seal(101, 3)), "CK-S", 201);

        // 批量作废中含一个无生效封箱的标签：整次回滚，100 不被作废
        voidSeals(batchKey, List.of(voidItem(100, "ok"), voidItem(102, "missing")), "CK-VB", 409);
        JsonNode detail = detail(batchKey);
        assertEquals(6, detail.path("sealedQuantity").asInt());
        assertEquals(2, detail.path("activeSealCount").asInt());

        // 作废请求内重复标签 → 422
        voidSeals(batchKey, List.of(voidItem(100, "a"), voidItem(100, "b")), "CK-VD", 422);

        // 未登记计划不能封箱/作废
        String noPlan = "BK-LBL-NOPLAN-" + unique();
        preparePendingReleaseBatch(noPlan);
        sealBoxesRaw(noPlan, List.of(seal(100, 1)), "CK-NP1", 422);
        voidSeals(noPlan, List.of(voidItem(100, "x")), "CK-NP2", 422);
    }

    // ---------- 放行闸门 ----------

    @Test
    void releaseBlockedUntilFullySealed_thenSnapshotCaptured() throws Exception {
        String batchKey = "BK-LBL-GATE-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 104, 201);
        sealBoxes(batchKey, List.of(seal(100, 4), seal(101, 4)), "CK-S1", 201);

        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        // 第二步放行时数量未封足 → 422，仍停留在 RELEASE_REVIEW，无第二批准
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(1, approvalsCount(batchKey));
        // 放行未发生：无快照
        mockMvc.perform(get("/api/batches/" + batchKey + "/labels/snapshot"))
                .andExpect(status().isNotFound());

        // 在 RELEASE_REVIEW 阶段可继续封箱；补齐后放行成功
        sealBoxes(batchKey, List.of(seal(102, 2)), "CK-S2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        JsonNode snap = snapshot(batchKey);
        assertEquals(10, snap.path("sealedQuantity").asInt());
        assertEquals("100,101,102", snap.path("usedLabels").asText());
        assertEquals("100-102", snap.path("rangeSummary").asText());
    }

    @Test
    void labelCountMustEqualSealCountAndBatchWithoutPlanReleasesNormally() throws Exception {
        // 未登记标签计划的批次：既有放行流程不受影响，且无快照
        String batchKey = "BK-LBL-NOSNAP-" + unique();
        preparePendingReleaseBatch(batchKey);
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        mockMvc.perform(get("/api/batches/" + batchKey + "/labels/snapshot"))
                .andExpect(status().isNotFound());
        JsonNode diag = diagnosis(batchKey);
        assertFalse(diag.path("planRegistered").asBoolean());
        assertTrue(diag.path("snapshot").isNull());
        assertTrue(diag.path("snapshotDiff").isNull());
    }

    // ---------- 计划登记 / 状态边界 ----------

    @Test
    void registerPlan_conflictsAndStatusGuards() throws Exception {
        String batchKey = "BK-LBL-PLAN-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 1, 3, 201);
        // 重复登记 → 409（计划不可修改）
        registerPlan(batchKey, 12, 1, 4, 201, "CK-PLAN-AGAIN", 409);
        // 起点大于终点 → 400
        registerPlan(batchKey, 10, 9, 1, 201, "CK-PLAN-BADRANGE", 400);

        // REJECTED 从未放行、无快照，仍属“未放行批次”，允许登记/封箱/作废（仅 RELEASED/SPLIT/RECALLED 拒绝）
        String rejected = "BK-LBL-REJ-" + unique();
        createBatch(rejected, List.of("t1"));
        submitTest(rejected, "TK-F", "t1", "FAIL", "insp");
        assertEquals("REJECTED", currentStatus(rejected));
        registerPlan(rejected, 10, 1, 3, 201, "CK-PLAN-REJ", 201);
        sealBoxes(rejected, List.of(seal(1, 4)), "CK-SEAL-REJ", 201);
        voidSeals(rejected, List.of(voidItem(1, "拒绝批次仍可作废")), "CK-VOID-REJ", 201);

        // 不存在的批次 → 404
        registerPlan("NO-SUCH", 10, 1, 3, 201, "CK-PLAN-404", 404);
    }

    @Test
    void splitBatch_blocksSealAndVoidButKeepsSnapshot() throws Exception {
        // 已放行父批拆分后为 SPLIT：标签写操作仍 409，放行快照保持不变
        String parent = "BK-LBL-SPLIT-" + unique();
        preparePendingReleaseBatch(parent);
        registerPlan(parent, 5, 100, 100, 201);
        sealBoxes(parent, List.of(seal(100, 5)), "CK-S", 201);
        approve(parent, "qa-1", "QUALITY", "CK-Q", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child1 = "BK-LBL-CHILD1-" + unique();
        String child2 = "BK-LBL-CHILD2-" + unique();
        String splitBody = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-SPLIT",
                "children", List.of(
                        java.util.Map.of("batchKey", child1, "batchNo", "LOT-CHILD1"),
                        java.util.Map.of("batchKey", child2, "batchNo", "LOT-CHILD2"))));
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());
        assertEquals("SPLIT", currentStatus(parent));

        sealBoxesRaw(parent, List.of(seal(100, 1)), "CK-SEAL-SPLIT", 409);
        voidSeals(parent, List.of(voidItem(100, "拆分后作废")), "CK-VOID-SPLIT", 409);
        JsonNode snap = snapshot(parent);
        assertEquals(5, snap.path("sealedQuantity").asInt());
        assertEquals("100", snap.path("usedLabels").asText());
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_sameParamsReplay_changedParamsConflict_failureNotOccupying() throws Exception {
        String batchKey = "BK-LBL-IDEM-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 100, 100, 110, 201);

        String body = sealsBody(List.of(seal(100, 5)), "CK-IDEM-1");
        MvcResult first = mockMvc.perform(postSealsJson(batchKey, body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(postSealsJson(batchKey, body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不新增封箱
        assertEquals(1, countSeals(batchKey));

        // 同 commandKey 改数量 → 409
        mockMvc.perform(postSeals(batchKey, List.of(seal(100, 6)), "CK-IDEM-1"))
                .andExpect(status().isConflict());
        // 同 commandKey 改标签 → 409
        mockMvc.perform(postSeals(batchKey, List.of(seal(101, 5)), "CK-IDEM-1"))
                .andExpect(status().isConflict());

        // 失败命令不占键：CK-FAIL 先因越界失败，随后同键合法请求成功
        sealBoxesRaw(batchKey, List.of(seal(999, 1)), "CK-FAIL", 422);
        sealBoxes(batchKey, List.of(seal(101, 5)), "CK-FAIL", 201);

        // 作废命令同键重放返回首次结果，改原因 409
        String voidBody = voidsBody(List.of(voidItem(101, "原因A")), "CK-VIDEM");
        MvcResult vf = mockMvc.perform(postVoidsJson(batchKey, voidBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult vr = mockMvc.perform(postVoidsJson(batchKey, voidBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(vf.getResponse().getContentAsString(), vr.getResponse().getContentAsString());
        mockMvc.perform(postVoids(batchKey, List.of(voidItem(100, "原因B")), "CK-VIDEM"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发守恒 ----------

    @Test
    void concurrentSameLabelAcrossBatches_onlyOneSucceeds() throws Exception {
        String a = "BK-LBL-CX-A-" + unique();
        String b = "BK-LBL-CX-B-" + unique();
        preparePendingReleaseBatch(a);
        preparePendingReleaseBatch(b);
        registerPlan(a, 100, 100, 110, 201);
        registerPlan(b, 100, 100, 110, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(postSeals(a, List.of(seal(100, 1)), "CK-CX-A")),
                () -> callStatus(postSeals(b, List.of(seal(100, 1)), "CK-CX-B"))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else {
                assertEquals(409, code);
                conflict++;
            }
        }
        assertEquals(1, ok, "同标签并发只允许一次成功");
        assertEquals(1, conflict);
        // 全局唯一占用
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE label_no = 100", Integer.class);
        assertEquals(1, occupancy);
        Integer sealRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_seal WHERE label_no = 100 AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, sealRows);
    }

    @Test
    void concurrentSealsSameBatch_neverExceedPlan() throws Exception {
        String batchKey = "BK-LBL-CX-PLAN-" + unique();
        preparePendingReleaseBatch(batchKey);
        // 号段充足，但计划数量仅 10：两笔各 6，恰好一笔会使合计超计划
        registerPlan(batchKey, 10, 100, 120, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(postSeals(batchKey, List.of(seal(100, 6)), "CK-CX-1")),
                () -> callStatus(postSeals(batchKey, List.of(seal(101, 6)), "CK-CX-2"))
        );
        int ok = 0;
        int fail = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else {
                assertEquals(422, code);
                fail++;
            }
        }
        assertEquals(1, ok);
        assertEquals(1, fail);
        Integer total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM package_seal WHERE batch_key = ? AND status='ACTIVE'",
                Integer.class, batchKey);
        assertEquals(6, total, "生效封箱数量合计绝不能超过计划 10");
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(1, occupancy, "失败整次回滚，不留部分占用");
    }

    @Test
    void concurrentSealAndFinalApproval_neverReleasesUnderSealed() throws Exception {
        String batchKey = "BK-LBL-CX-REL-" + unique();
        preparePendingReleaseBatch(batchKey);
        // 计划 12，号段 2 标签；先封 6，剩余 6 与最终放行并发
        registerPlan(batchKey, 12, 100, 101, 201);
        sealBoxes(batchKey, List.of(seal(100, 6)), "CK-PRE", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(postSeals(batchKey, List.of(seal(101, 6)), "CK-FINAL-SEAL")),
                () -> callStatus(postApprove(batchKey, "ops-1", "OPERATIONS", "CK-FINAL-AP"))
        );
        int sealCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approveCode = results.get(1).get(30, TimeUnit.SECONDS);
        String status = currentStatus(batchKey);

        if ("RELEASED".equals(status)) {
            // 封箱先提交：放行看到数量封足，二者皆成功，快照=12
            assertEquals(201, sealCode);
            assertEquals(201, approveCode);
            assertEquals(12, snapshot(batchKey).path("sealedQuantity").asInt());
        } else {
            // 放行先提交：核销未完成 422，封箱成功，批次仍 RELEASE_REVIEW
            assertEquals(201, sealCode);
            assertEquals(422, approveCode);
            assertEquals("RELEASE_REVIEW", status);
            // 补齐后可放行
            approve(batchKey, "ops-1", "OPERATIONS", "CK-FINAL-AP-2", 201);
            assertEquals("RELEASED", currentStatus(batchKey));
            assertEquals(12, snapshot(batchKey).path("sealedQuantity").asInt());
        }
        // 任何裁决顺序下都不可能出现“未封足却已放行”
        if ("RELEASED".equals(status)) {
            assertEquals(12, snapshot(batchKey).path("sealedQuantity").asInt());
        }
    }

    // ---------- 只读查询 / null 语义 / 排序 ----------

    @Test
    void detailHistoryQueries_areReadOnlyAndStable() throws Exception {
        String batchKey = "BK-LBL-RO-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 102, 201);
        sealBoxes(batchKey, List.of(seal(102, 5), seal(100, 3)), "CK-S", 201);

        JsonNode before = detail(batchKey);
        assertEquals(8, before.path("sealedQuantity").asInt());
        // 生效封箱按标签号升序
        assertEquals(100, before.path("activeSeals").get(0).path("labelNo").asLong());
        assertEquals(102, before.path("activeSeals").get(1).path("labelNo").asLong());

        // 连续读取不改状态：纯只读投影，同状态下结果完全一致
        assertEquals(before.toString(), detail(batchKey).toString());
        diagnosis(batchKey);
        history(batchKey);
        assertEquals(8, detail(batchKey).path("sealedQuantity").asInt());

        // 明细中生效封箱作废字段为 null
        assertTrue(before.path("activeSeals").get(0).path("voidedAt").isNull());
        assertTrue(before.path("activeSeals").get(0).path("voidReason").isNull());

        // 历史按提交顺序（id）：102 先、100 后
        JsonNode hist = history(batchKey);
        assertEquals(102, hist.path("seals").get(0).path("labelNo").asLong());
        assertEquals(100, hist.path("seals").get(1).path("labelNo").asLong());
        assertEquals(10, hist.path("plan").path("plannedQuantity").asInt());

        // 未知批次查询 404
        mockMvc.perform(get("/api/batches/NO-SUCH/labels")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/labels/history")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/labels/diagnosis")).andExpect(status().isNotFound());
    }

    @Test
    void rangeSummary_compressesNonContiguousLabels() throws Exception {
        String batchKey = "BK-LBL-RANGESUM-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 12, 1, 10, 201);
        sealBoxes(batchKey, List.of(seal(1, 3), seal(2, 3), seal(3, 3), seal(7, 3)), "CK-S", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        JsonNode snap = snapshot(batchKey);
        assertEquals("1,2,3,7", snap.path("usedLabels").asText());
        assertEquals("1-3,7", snap.path("rangeSummary").asText());
    }

    @Test
    void diagnosis_detectsForcedSnapshotDiscrepancy_readOnly_andRecallRemediation() throws Exception {
        String batchKey = "BK-LBL-DIAG-" + unique();
        preparePendingReleaseBatch(batchKey);
        registerPlan(batchKey, 10, 100, 101, 201);
        sealBoxes(batchKey, List.of(seal(100, 5), seal(101, 5)), "CK-S", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-Q", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 模拟放行后出现标签差异（旁路异常：直接释放标签 100 占用并将该封箱置作废，
        // 正常 API 不允许此操作）。诊断须在真实 H2 数据上检出差异，且读取本身不改状态。
        jdbc.update("DELETE FROM label_occupancy WHERE label_no = 100");
        jdbc.update("UPDATE package_seal SET status='VOIDED', voided_at=?, void_reason='旁路异常',"
                        + " void_command_key='ANOMALY' WHERE batch_key=? AND label_no=100",
                Instant.now().toString(), batchKey);

        JsonNode diag = diagnosis(batchKey);
        assertEquals(5, diag.path("sealedQuantity").asInt());
        assertEquals(5, diag.path("remainingQuantity").asInt());
        assertEquals(1, diag.path("activeLabelCount").asInt());
        assertEquals(1, diag.path("activeSealCount").asInt());
        assertFalse(diag.path("reconciled").asBoolean(), "差异后当前状态不再守恒");
        JsonNode diff = diag.path("snapshotDiff");
        assertEquals(100, diff.path("labelsOnlyInSnapshot").get(0).asLong());
        assertEquals(0, diff.path("labelsOnlyInCurrent").size());
        assertEquals(5, diff.path("currentSealedQuantity").asInt());
        assertEquals(10, diff.path("snapshotSealedQuantity").asInt());
        assertEquals(-5, diff.path("sealedQuantityDelta").asInt());
        assertTrue(diff.path("snapshotImmutable").asBoolean());

        // 快照固化内容保持放行时刻，未被诊断或旁路差异改写
        JsonNode snap = snapshot(batchKey);
        assertEquals(10, snap.path("sealedQuantity").asInt());
        assertEquals("100,101", snap.path("usedLabels").asText());
        Integer snapshotSealRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot_seal WHERE batch_key=?", Integer.class, batchKey);
        assertEquals(2, snapshotSealRows);

        // 差异只能走既有召回流程处置
        recall(batchKey, "u", "放行后标签差异，召回处置", "CK-RC", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
        // 召回后快照与差异证据仍可查
        assertEquals("100,101", snapshot(batchKey).path("usedLabels").asText());
    }

    @Test
    void recalledAncestor_blocksDescendantLabelOps() throws Exception {
        // 父批放行后拆分，召回父批；后代（未放行）的标签登记/封箱/作废被 422 拦截
        String parent = "BK-LBL-RA-P-" + unique();
        preparePendingReleaseBatch(parent);
        approve(parent, "qa-1", "QUALITY", "CK-Q", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-O", 201);

        String c1 = "BK-LBL-RA-C1-" + unique();
        String c2 = "BK-LBL-RA-C2-" + unique();
        String splitBody = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-SPLIT",
                "children", List.of(
                        java.util.Map.of("batchKey", c1, "batchNo", "LOT-C1"),
                        java.util.Map.of("batchKey", c2, "batchNo", "LOT-C2"))));
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        // 召回前后代可登记与封箱
        registerPlan(c1, 10, 1, 5, 201, "CK-PLAN-C1", 201);
        sealBoxes(c1, List.of(seal(1, 4)), "CK-SEAL-C1", 201);

        recall(parent, "u", "父批召回", "CK-RC-P", 201);
        assertEquals("RECALLED", currentStatus(parent));

        // 后代自身状态不变（仍 QUARANTINED），但标签写操作被祖先召回拦截
        assertEquals("QUARANTINED", currentStatus(c1));
        registerPlan(c2, 10, 1, 5, 201, "CK-PLAN-C2", 422);
        sealBoxesRaw(c1, List.of(seal(2, 1)), "CK-SEAL-C1-AFTER", 422);
        voidSeals(c1, List.of(voidItem(1, "祖先后作废")), "CK-VOID-C1", 422);
        // 既有封箱与明细仍可只读查询，未被改写
        assertEquals(4, detail(c1).path("sealedQuantity").asInt());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record SealItem(long labelNo, int quantity) {
    }

    private record VoidItem(long labelNo, String reason) {
    }

    private SealItem seal(long labelNo, int quantity) {
        return new SealItem(labelNo, quantity);
    }

    private VoidItem voidItem(long labelNo, String reason) {
        return new VoidItem(labelNo, reason);
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-CREATE-" + unique(),
                "batchKey", batchKey,
                "productCode", "PROD-1",
                "batchNo", "LOT-" + batchKey,
                "producedAt", "2026-01-02T03:04:05Z",
                "requiredTests", items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void preparePendingReleaseBatch(String batchKey) throws Exception {
        createBatch(batchKey, List.of("t1"));
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-" + unique());
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    private void submitTest(String batchKey, String testKey, String item, String result, String inspector)
            throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", "CK-TEST-" + unique(),
                "testKey", testKey,
                "testItem", item,
                "result", result,
                "inspector", inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void registerPlan(String batchKey, int planned, long start, long end, int commandKeyIgnored)
            throws Exception {
        registerPlan(batchKey, planned, start, end, 201, "CK-PLAN-" + unique(), 201);
    }

    private void registerPlan(String batchKey, int planned, long start, long end,
                              int ignored, String commandKey, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "commandKey", commandKey,
                "plannedQuantity", planned,
                "labelRangeStart", start,
                "labelRangeEnd", end));
        mockMvc.perform(post("/api/batches/" + batchKey + "/label-plan")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String sealsBody(List<SealItem> items, String commandKey) throws Exception {
        List<java.util.Map<String, Object>> seals = items.stream()
                .map(i -> java.util.Map.<String, Object>of("labelNo", i.labelNo(), "quantity", i.quantity()))
                .toList();
        return objectMapper.writeValueAsString(java.util.Map.of("commandKey", commandKey, "seals", seals));
    }

    private String voidsBody(List<VoidItem> items, String commandKey) throws Exception {
        List<java.util.Map<String, Object>> voids = items.stream()
                .map(i -> java.util.Map.<String, Object>of("labelNo", i.labelNo(), "reason", i.reason()))
                .toList();
        return objectMapper.writeValueAsString(java.util.Map.of("commandKey", commandKey, "voids", voids));
    }

    private MockHttpServletRequestBuilder postSeals(String batchKey, List<SealItem> items, String ck)
            throws Exception {
        return post("/api/batches/" + batchKey + "/seals")
                .contentType(MediaType.APPLICATION_JSON).content(sealsBody(items, ck));
    }

    private MockHttpServletRequestBuilder postSealsJson(String batchKey, String json) {
        return post("/api/batches/" + batchKey + "/seals")
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder postVoids(String batchKey, List<VoidItem> items, String ck)
            throws Exception {
        return post("/api/batches/" + batchKey + "/seals/void")
                .contentType(MediaType.APPLICATION_JSON).content(voidsBody(items, ck));
    }

    private MockHttpServletRequestBuilder postVoidsJson(String batchKey, String json) {
        return post("/api/batches/" + batchKey + "/seals/void")
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private JsonNode sealBoxes(String batchKey, List<SealItem> items, String ck, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(postSeals(batchKey, items, ck))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void sealBoxesRaw(String batchKey, List<SealItem> items, String ck, int expected)
            throws Exception {
        mockMvc.perform(postSeals(batchKey, items, ck)).andExpect(status().is(expected));
    }

    private JsonNode voidSeals(String batchKey, List<VoidItem> items, String ck, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(postVoids(batchKey, items, ck))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder postApprove(String batchKey, String actor, String role, String ck) {
        return post("/api/batches/" + batchKey + "/approvals")
                .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"" + ck + "\"}");
    }

    private void approve(String batchKey, String actor, String role, String ck, int expected)
            throws Exception {
        mockMvc.perform(postApprove(batchKey, actor, role, ck)).andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String ck, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode detail(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/labels"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/labels/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode snapshot(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/labels/snapshot"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode diagnosis(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/labels/diagnosis"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int countSeals(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_seal WHERE batch_key = ?", Integer.class, batchKey);
    }

    private int countOccupancy(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE batch_key = ?", Integer.class, batchKey);
    }

    private int approvalsCount(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
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
        assertTrue(pool.awaitTermination(40, TimeUnit.SECONDS));
        return futures;
    }
}
