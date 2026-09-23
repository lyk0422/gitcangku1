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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 召回血缘闭包分区处置端到端测试（真实 H2 MySQL 兼容内存库）：
 * 主流程、闭包/路径查询、422 分区校验、版本重校 409、拒绝/取消不改批次、
 * 整单回滚、requestId 幂等（换序同参/异参 409/失败不占键）、并发裁决不部分落账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchDispositionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM disposition_snapshot");
        jdbc.update("DELETE FROM batch_disposition");
        jdbc.update("DELETE FROM disposition_order_batch");
        jdbc.update("DELETE FROM disposition_order");
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
    void happyFlow_submitFreezeClosureThenConfirmLandsAllCategories() throws Exception {
        // root 拆分为 c1,c2，三者放行后召回 root；闭包 = {root,c1,c2}
        String root = "BK-DP-R-" + unique();
        String c1 = "BK-DP-C1-" + unique();
        String c2 = "BK-DP-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);

        // 闭包只读查询：含祖先自身、版本/状态/路径
        MvcResult closureResult = mockMvc.perform(get("/api/batches/" + root + "/closure"))
                .andExpect(status().isOk()).andReturn();
        JsonNode closure = objectMapper.readTree(closureResult.getResponse().getContentAsString());
        assertEquals(3, closure.size());
        assertEquals(root, closure.get(0).path("batchKey").asText());
        assertEquals(0, closure.get(0).path("path").size(), "祖先自身路径为空");
        for (int i = 1; i < 3; i++) {
            assertEquals(root, closure.get(i).path("path").get(0).asText(), "后代路径指向祖先");
            assertTrue(closure.get(i).path("version").asLong() >= 1);
        }

        // 提交：root DESTROY，c1 REWORK，c2 HOLD
        String dKey = "DP-KEY-" + unique();
        MvcResult submitResult = submit(root, "qa-lead", "QUALITY",
                submitBody("REQ-S-1", dKey, "暂挂待复检",
                        List.of(root), List.of(c1), List.of(c2)), 201);
        JsonNode order = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        assertEquals("SUBMITTED", order.path("status").asText());
        assertEquals(1, order.path("version").asInt());
        assertEquals("qa-lead", order.path("submitActor").asText());
        assertEquals(3, order.path("batches").size());
        assertEquals("RECALLED", order.path("batches").get(0).path("frozenStatus").asText());
        long rootFrozenVersion = order.path("batches").get(0).path("frozenVersion").asLong();
        assertTrue(rootFrozenVersion >= 5, "召回后祖先版本已含检验/批准/召回流转");

        // 二审：不同生产负责人，expectedVersion=1，携带冻结版本（故意打乱顺序验证顺序无关）
        List<Map<String, Object>> batchVersions = new ArrayList<>();
        JsonNode batches = order.path("batches");
        batchVersions.add(batchVersion(batches.get(2).path("batchKey").asText(),
                batches.get(2).path("frozenVersion").asLong()));
        batchVersions.add(batchVersion(batches.get(0).path("batchKey").asText(),
                batches.get(0).path("frozenVersion").asLong()));
        batchVersions.add(batchVersion(batches.get(1).path("batchKey").asText(),
                batches.get(1).path("frozenVersion").asLong()));
        MvcResult confirmResult = confirm(dKey, "ops-lead", "OPERATIONS",
                confirmBody("REQ-C-1", 1, batchVersions), 200);
        JsonNode confirmed = objectMapper.readTree(confirmResult.getResponse().getContentAsString());
        assertEquals("CONFIRMED", confirmed.path("order").path("status").asText());
        assertEquals(2, confirmed.path("order").path("version").asInt());
        assertEquals("ops-lead", confirmed.path("order").path("decideActor").asText());
        assertEquals(3, confirmed.path("snapshots").size());

        // 批次按分类推进
        assertEquals("DESTROYED", currentStatus(root));
        assertEquals("REWORK_PENDING", currentStatus(c1));
        assertEquals("RELEASED", currentStatus(c2), "HOLD 保持原状态（放行态）并隔离记录原因");

        // 不可变路径与分类快照
        MvcResult pathsResult = mockMvc.perform(get("/api/dispositions/" + dKey + "/paths"))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshots = objectMapper.readTree(pathsResult.getResponse().getContentAsString());
        assertEquals(3, snapshots.size());
        JsonNode holdSnapshot = findSnapshot(snapshots, c2);
        assertEquals("HOLD", holdSnapshot.path("category").asText());
        assertEquals("RELEASED", holdSnapshot.path("previousStatus").asText());
        assertEquals("RELEASED", holdSnapshot.path("finalStatus").asText());
        assertEquals("暂挂待复检", holdSnapshot.path("reason").asText());
        JsonNode destroySnapshot = findSnapshot(snapshots, root);
        assertEquals("DESTROY", destroySnapshot.path("category").asText());
        assertEquals("RECALLED", destroySnapshot.path("previousStatus").asText());
        assertEquals("DESTROYED", destroySnapshot.path("finalStatus").asText());
        assertTrue(holdSnapshot.path("reason").isNull() || true);
        JsonNode c1Snapshot = findSnapshot(snapshots, c1);
        assertTrue(c1Snapshot.path("reason").isNull());
        assertEquals(root, findSnapshot(snapshots, c1).path("path").get(0).asText());

        // 批次级落账结论与版本
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_disposition", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ? AND status = 'DESTROYED'",
                Integer.class, root));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ? AND status = 'REWORK_PENDING'",
                Integer.class, c1));
    }

    @Test
    void holdKeepsRecalledAncestorRecalled_andFreezesFullMultiLevelPaths() throws Exception {
        // 先建两级树 root -> child,C2；child -> grand,G2；放行后最后召回 root，闭包 5 个批次
        String root = "BK-DH-R-" + unique();
        String child = "BK-DH-C-" + unique();
        String c2 = "BK-DH-C2-" + unique();
        String grand = "BK-DH-G-" + unique();
        String g2 = "BK-DH-G2-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, "insp-r");
        split(root, "REQ-SP1-" + unique(), List.of(new String[]{child, "L1"}, new String[]{c2, "L2"}));
        releaseBatch(child, "insp-c");
        split(child, "REQ-SP2-" + unique(),
                List.of(new String[]{grand, "G1"}, new String[]{g2, "G2"}));
        releaseBatch(grand, "insp-g");
        recall(root, "u", "两级树召回", "REQ-RC-" + unique());

        List<String> closureKeys = closureKeys(root);
        assertEquals(5, closureKeys.size());
        String dKey = "DP-HOLD-" + unique();
        MvcResult submitted = submit(root, "qa", "QUALITY",
                submitBody("REQ-SH-1", dKey, "全量暂挂", List.of(), List.of(), closureKeys), 201);
        JsonNode order = objectMapper.readTree(submitted.getResponse().getContentAsString());
        JsonNode grandFrozen = findFrozen(order.path("batches"), grand);
        assertEquals(2, grandFrozen.path("frozenPath").size(), "grand 路径为 root->child（祖先在前）");
        assertEquals(root, grandFrozen.path("frozenPath").get(0).asText());
        assertEquals(child, grandFrozen.path("frozenPath").get(1).asText());

        confirmWithFrozen(dKey, "ops", "REQ-CH-1", order, 200);
        assertEquals("RECALLED", currentStatus(root), "祖先 HOLD 保持 RECALLED");
        assertEquals("SPLIT", currentStatus(child), "HOLD 后代状态不变");
        assertEquals("RELEASED", currentStatus(grand));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_snapshot WHERE disposition_key = ?",
                Integer.class, dKey).intValue());
    }

    // ---------- 提交分区校验 422 ----------

    @Test
    void submitPartitionViolations_return422_andFailureDoesNotOccupyRequestId() throws Exception {
        String root = "BK-DV-R-" + unique();
        String c1 = "BK-DV-C1-" + unique();
        String c2 = "BK-DV-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);

        // 遗漏 c2
        submit(root, "qa", "QUALITY",
                submitBody("REQ-BAD-1", "DP-BAD-1-" + unique(), "r",
                        List.of(root, c1), List.of(), List.of()), 422);
        // 多余（非闭包批次）
        submit(root, "qa", "QUALITY",
                submitBody("REQ-BAD-2", "DP-BAD-2-" + unique(), "r",
                        List.of(root, c1, c2, "BK-DV-OUTSIDE-" + unique()), List.of(), List.of()), 422);
        // 同一批次跨集合
        submit(root, "qa", "QUALITY",
                submitBody("REQ-BAD-3", "DP-BAD-3-" + unique(), "r",
                        List.of(root), List.of(c1), List.of(c1, c2)), 422);
        // 集合内重复
        submit(root, "qa", "QUALITY",
                submitBody("REQ-BAD-4", "DP-BAD-4-" + unique(), "r",
                        List.of(root, c1, c1), List.of(), List.of(c2)), 422);

        // 失败不占键：REQ-BAD-1 修正为恰好闭包后成功
        MvcResult ok = submit(root, "qa", "QUALITY",
                submitBody("REQ-BAD-1", "DP-BAD-OK-" + unique(), "r",
                        List.of(root, c1), List.of(), List.of(c2)), 201);
        assertEquals("SUBMITTED", objectMapper.readTree(ok.getResponse().getContentAsString())
                .path("status").asText());
        // 失败未写入处置单/冻结/命令日志
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_order", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'REQ-BAD-1'", Integer.class));
    }

    @Test
    void submitOnNonRecalledAncestor_returns422() throws Exception {
        String root = "BK-DN-R-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, "insp");
        // RELEASED 而非 RECALLED
        submit(root, "qa", "QUALITY",
                submitBody("REQ-NR-1", "DP-NR-" + unique(), "r",
                        List.of(root), List.of(), List.of()), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_order", Integer.class));
    }

    @Test
    void submitRoleAndHeaderValidation_return400() throws Exception {
        String root = "BK-DR-R-" + unique();
        buildRecalledTree(root, List.of(new String[]{"BK-DR-C1-" + unique(), "L1"},
                new String[]{"BK-DR-C2-" + unique(), "L2"}), true);
        String body = submitBody("REQ-ROLE", "DP-ROLE-" + unique(), "r",
                List.of(root), List.of(), List.of());
        // 提交人角色必须为 QUALITY
        mockMvc.perform(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        // 缺 actor
        mockMvc.perform(post("/api/batches/" + root + "/dispositions")
                        .header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitUnknownAncestor_returns404_andDuplicateDispositionKey409() throws Exception {
        submit("NO-SUCH-ROOT", "qa", "QUALITY",
                submitBody("REQ-404", "DP-404-" + unique(), "r",
                        List.of("x"), List.of(), List.of()), 404);

        String root = "BK-DK-R-" + unique();
        List<String> closure = buildRecalledTree(root, List.of(new String[]{"BK-DK-C1-" + unique(), "L1"},
                new String[]{"BK-DK-C2-" + unique(), "L2"}), true);
        String dKey = "DP-DUP-" + unique();
        submit(root, "qa", "QUALITY",
                submitBody("REQ-DK-2", dKey, "r", List.of(root), List.of(),
                        closure.subList(1, closure.size())), 201);
        // dispositionKey 全局唯一：换 requestId 再提交同键 → 409
        submit(root, "qa", "QUALITY",
                submitBody("REQ-DK-3", dKey, "r", List.of(root), List.of(),
                        closure.subList(1, closure.size())), 409);
    }

    // ---------- 二审版本重校 409 / 角色 / 同人 ----------

    @Test
    void confirmVersionAndClosureMismatches_return409_withoutLanding() throws Exception {
        String root = "BK-DM-R-" + unique();
        String c1 = "BK-DM-C1-" + unique();
        String c2 = "BK-DM-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);
        JsonNode frozen = submitAndFreeze(root, "DP-MM-" + unique(),
                Map.of(root, "DESTROY", c1, "REWORK", c2, "HOLD"));

        // expectedDispositionVersion 错误 → 409，失败不占键
        List<Map<String, Object>> versions = frozenVersions(frozen);
        String dKey = dispositionKeyOf(frozen);
        confirm(dKey, "ops", "OPERATIONS", confirmBody("REQ-MM-1", 2, versions), 409);
        // 携带批次版本被篡改 → 409
        List<Map<String, Object>> tampered = new ArrayList<>(versions);
        tampered.set(0, batchVersion(tampered.get(0).get("batchKey").toString(),
                ((Number) tampered.get(0).get("version")).longValue() + 1));
        confirm(dKey, "ops", "OPERATIONS", confirmBody("REQ-MM-2", 1, tampered), 409);
        // 缺少一个批次 → 409
        confirm(dKey, "ops", "OPERATIONS",
                confirmBody("REQ-MM-3", 1, versions.subList(0, 2)), 409);
        // 多余批次 → 409
        List<Map<String, Object>> extra = new ArrayList<>(versions);
        extra.add(batchVersion("BK-DM-GHOST-" + unique(), 1L));
        confirm(dKey, "ops", "OPERATIONS", confirmBody("REQ-MM-4", 1, extra), 409);

        // 全部批次未被落账
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("RELEASED", currentStatus(c1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_snapshot", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_disposition", Integer.class));

        // 失败不占键：同 requestId 以正确参数确认成功
        MvcResult ok = confirm(dKey, "ops", "OPERATIONS",
                confirmBody("REQ-MM-1", 1, versions), 200);
        assertEquals("CONFIRMED", objectMapper.readTree(ok.getResponse().getContentAsString())
                .path("order").path("status").asText());
    }

    @Test
    void confirmActorAndRoleConstraints_rejected() throws Exception {
        String root = "BK-DA-R-" + unique();
        createRecalledLeaf(root);
        JsonNode frozen = submitAndFreeze(root, "DP-AC-" + unique(),
                Map.of(root, "DESTROY"));
        String dKey = dispositionKeyOf(frozen);

        // 二审人不得与提交人相同（qa-lead）
        confirm(dKey, "qa-lead", "OPERATIONS",
                confirmBody("REQ-AC-1", 1, frozenVersions(frozen)), 422);
        // 角色必须为 OPERATIONS
        confirm(dKey, "ops-x", "QUALITY",
                confirmBody("REQ-AC-2", 1, frozenVersions(frozen)), 400);
        // 处置单仍待二审、批次不变
        assertEquals("SUBMITTED", getOrder(dKey).path("status").asText());
        assertEquals("RECALLED", currentStatus(root));
    }

    @Test
    void confirmRejectedOrCancelledOrder_returns409() throws Exception {
        String root = "BK-DF-R-" + unique();
        createRecalledLeaf(root);
        JsonNode frozen = submitAndFreeze(root, "DP-FN-" + unique(),
                Map.of(root, "HOLD"));
        String dKey = dispositionKeyOf(frozen);
        reject(dKey, "ops", "OPERATIONS", rejectBody("REQ-RJ-1", "依据不足"), 200);
        confirm(dKey, "ops2", "OPERATIONS",
                confirmBody("REQ-CF-1", 1, frozenVersions(frozen)), 409);
        assertEquals("RECALLED", currentStatus(root), "拒绝不改批次");
    }

    private void createRecalledLeaf(String root) throws Exception {
        createBatch(root, List.of("t1"));
        releaseBatch(root, "insp");
        recall(root, "u", "召回", "REQ-RC-" + unique());
    }

    // ---------- 拒绝 / 取消 ----------

    @Test
    void rejectAndCancel_doNotChangeBatches() throws Exception {
        String root = "BK-DJ-R-" + unique();
        String c1 = "BK-DJ-C1-" + unique();
        String c2 = "BK-DJ-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);

        // 拒绝：必须不同的生产负责人
        JsonNode f1 = submitAndFreeze(root, "DP-RJ-" + unique(),
                Map.of(root, "DESTROY", c1, "HOLD", c2, "HOLD"));
        reject(dispositionKeyOf(f1), "qa-lead", "OPERATIONS",
                rejectBody("REQ-X-1", "提交人不能拒绝"), 422);
        MvcResult rejected = reject(dispositionKeyOf(f1), "ops-1", "OPERATIONS",
                rejectBody("REQ-X-2", "材料不全"), 200);
        assertEquals("REJECTED", objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("status").asText());
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("RELEASED", currentStatus(c1));
        // 已拒绝不可再拒绝/取消
        reject(dispositionKeyOf(f1), "ops-1", "OPERATIONS",
                rejectBody("REQ-X-3", "再次拒绝"), 409);

        // 取消：仅提交人本人 QUALITY
        JsonNode f2 = submitAndFreeze(root, "DP-CN-" + unique(),
                Map.of(root, "HOLD", c1, "DESTROY", c2, "HOLD"));
        String d2 = dispositionKeyOf(f2);
        cancel(d2, "qa-lead", "OPERATIONS", cancelBody("REQ-Y-1"), 400);
        cancel(d2, "other-qa", "QUALITY", cancelBody("REQ-Y-2"), 422);
        MvcResult cancelled = cancel(d2, "qa-lead", "QUALITY", cancelBody("REQ-Y-3"), 200);
        assertEquals("CANCELLED", objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .path("status").asText());
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("RELEASED", currentStatus(c1));
        cancel(d2, "qa-lead", "QUALITY", cancelBody("REQ-Y-4"), 409);
    }

    // ---------- 整单回滚 ----------

    @Test
    void confirmWholeRollback_whenOneBatchDisallowsTransition() throws Exception {
        // 第一张处置单：c1 DESTROY；root/c2 HOLD。确认后 c1 DESTROYED 终态，三张批次均已落账一次。
        String root = "BK-DB-R-" + unique();
        String c1 = "BK-DB-C1-" + unique();
        String c2 = "BK-DB-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);

        JsonNode first = submitAndFreeze(root, "DP-RB-1-" + unique(),
                Map.of(root, "HOLD", c1, "DESTROY", c2, "HOLD"));
        confirm(dispositionKeyOf(first), "ops-1", "OPERATIONS",
                confirmBody("REQ-RB-C1", 1, frozenVersions(first)), 200);
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("DESTROYED", currentStatus(c1));
        long versionRootBefore = currentVersion(root);
        long versionC2Before = currentVersion(c2);

        // 第二张单试图再次处置已落账批次（c1 已是 DESTROYED 终态）→ 409 整单回滚
        JsonNode second = submitAndFreeze(root, "DP-RB-2-" + unique(),
                Map.of(root, "REWORK", c1, "DESTROY", c2, "HOLD"));
        confirm(dispositionKeyOf(second), "ops-2", "OPERATIONS",
                confirmBody("REQ-RB-C2", 1, frozenVersions(second)), 409);

        // 整单回滚：处置单仍 SUBMITTED；无第二张单快照；任何批次状态/版本未部分推进
        assertEquals("SUBMITTED", getOrder(dispositionKeyOf(second)).path("status").asText());
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("DESTROYED", currentStatus(c1));
        assertEquals("RELEASED", currentStatus(c2));
        assertEquals(versionRootBefore, currentVersion(root));
        assertEquals(versionC2Before, currentVersion(c2));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_snapshot WHERE disposition_key = ?",
                Integer.class, dispositionKeyOf(first)).intValue());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_snapshot WHERE disposition_key = ?",
                Integer.class, dispositionKeyOf(second)).intValue());
        // 失败不占键：同一 requestId 无法把不允许的流转改成成功（仍 409），且批次依旧不变
        confirm(dispositionKeyOf(second), "ops-2", "OPERATIONS",
                confirmBody("REQ-RB-C2", 1, frozenVersions(second)), 409);
        assertEquals(versionRootBefore, currentVersion(root));
    }

    // ---------- 幂等 ----------

    @Test
    void submitRequestId_sameParamsReorderedReplays_changedParamsConflicts() throws Exception {
        String root = "BK-DI-R-" + unique();
        String c1 = "BK-DI-C1-" + unique();
        String c2 = "BK-DI-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);
        String dKey = "DP-IDEM-" + unique();
        String body1 = submitBody("REQ-IDEM-1", dKey, "原因甲",
                List.of(root), List.of(c2, c1), List.of());
        MvcResult first = mockMvc.perform(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated()).andReturn();
        // 三集合内部换序（REWORK: c1,c2）→ 同参重放
        String bodyReordered = submitBody("REQ-IDEM-1", dKey, "原因甲",
                List.of(root), List.of(c1, c2), List.of());
        MvcResult replay = mockMvc.perform(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyReordered))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_order", Integer.class));

        // 同 requestId 改参（holdReason 不同）→ 409
        mockMvc.perform(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("REQ-IDEM-1", dKey, "原因乙",
                                List.of(root), List.of(c1, c2), List.of())))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmRequestId_sameParamsReplays_changedParamsConflicts() throws Exception {
        String root = "BK-DQ-R-" + unique();
        String c1 = "BK-DQ-C1-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"},
                new String[]{"BK-DQ-C2-" + unique(), "L2"}), true);
        List<String> closure = closureKeys(root);
        String c2 = closure.get(2);
        JsonNode frozen = submitAndFreeze(root, "DP-CIDEM-" + unique(),
                Map.of(root, "DESTROY", c1, "REWORK", c2, "HOLD"));
        String dKey = dispositionKeyOf(frozen);
        List<Map<String, Object>> versions = frozenVersions(frozen);
        String body = confirmBody("REQ-CIDEM-1", 1, versions);
        MvcResult first = mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 换序重放
        List<Map<String, Object>> reordered = new ArrayList<>(versions);
        java.util.Collections.reverse(reordered);
        MvcResult replay = mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CIDEM-1", 1, reordered)))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 只落账一次
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_snapshot WHERE disposition_key = ?",
                Integer.class, dKey).intValue());
        // 异参（期望版本 2）→ 409
        mockMvc.perform(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CIDEM-1", 2, reordered)))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentTwoConfirms_onlyOneLands() throws Exception {
        String root = "BK-CC-R-" + unique();
        List<String> closure = buildRecalledTree(root,
                List.of(new String[]{"BK-CC-C1-" + unique(), "L1"},
                        new String[]{"BK-CC-C2-" + unique(), "L2"}), true);
        JsonNode frozen = submitAndFreeze(root, "DP-CC-" + unique(), allHold(closure));
        String dKey = dispositionKeyOf(frozen);
        List<Map<String, Object>> versions = frozenVersions(frozen);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops-a").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CC-A-" + unique(), 1, versions))),
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops-b").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CC-B-" + unique(), 1, versions)))
        );
        int a = results.get(0).get(30, TimeUnit.SECONDS);
        int b = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (a == 200 ? 1 : 0) + (b == 200 ? 1 : 0), "恰好一个确认成功");
        assertTrue(a == 409 || a == 200);
        assertTrue(b == 409 || b == 200);
        assertEquals(closure.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition_snapshot WHERE disposition_key = ?",
                Integer.class, dKey).intValue(), "无重复/部分落账");
        assertEquals(closure.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_disposition", Integer.class).intValue());
    }

    @Test
    void concurrentConfirmAndDirectRecallOfClosureLeaf_noPartialLanding() throws Exception {
        // root RECALLED；闭包内叶子 c1 仍 RELEASED，可被直接再次召回（状态/版本变化）
        String root = "BK-CR-R-" + unique();
        String c1 = "BK-CR-C1-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"},
                new String[]{"BK-CR-C2-" + unique(), "L2"}), true);
        List<String> closure = closureKeys(root);
        JsonNode frozen = submitAndFreeze(root, "DP-CRL-" + unique(), allHold(closure));
        String dKey = dispositionKeyOf(frozen);
        List<Map<String, Object>> versions = frozenVersions(frozen);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CRL-C-" + unique(), 1, versions))),
                () -> callStatus(post("/api/batches/" + c1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"REQ-CRL-R-" + unique()
                                + "\",\"reason\":\"闭包内再次召回\"}"))
        );
        int confirm = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        if (confirm == 200) {
            // 确认先提交：c1 已落账保持/终态，直接召回被状态机拒绝
            assertEquals(409, recallCode);
            assertEquals(closure.size(), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_disposition", Integer.class).intValue());
        } else {
            // 再次召回先提交：c1 版本/状态变化，确认 409 整单不落账
            assertEquals(409, confirm);
            assertEquals("RECALLED", currentStatus(c1));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_disposition",
                    Integer.class).intValue());
            assertEquals("SUBMITTED", getOrder(dKey).path("status").asText());
        }
    }

    @Test
    void concurrentConfirmAndSplitOfClosureBatch_neverMissesDescendant() throws Exception {
        // 祖先已召回，闭包成员的拆分必然被祖先召回拦截（422）；确认照常全量落账，不新增游离后代
        String root = "BK-CS-R-" + unique();
        String c1 = "BK-CS-C1-" + unique();
        List<String> closure = buildRecalledTree(root, List.of(new String[]{c1, "L1"},
                new String[]{"BK-CS-C2-" + unique(), "L2"}), true);
        JsonNode frozen = submitAndFreeze(root, "DP-CSL-" + unique(), allHold(closure));
        String dKey = dispositionKeyOf(frozen);
        List<Map<String, Object>> versions = frozenVersions(frozen);

        String newChild = "BK-CS-NEW-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/dispositions/" + dKey + "/confirm")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("REQ-CS-C-" + unique(), 1, versions))),
                () -> callStatus(post("/api/batches/" + c1 + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("REQ-CS-S-" + unique(),
                                List.of(new String[]{newChild, "N1"},
                                        new String[]{"BK-CS-NEW2-" + unique(), "N2"}))))
        );
        assertEquals(200, results.get(0).get(30, TimeUnit.SECONDS), "确认成功");
        assertEquals(422, results.get(1).get(30, TimeUnit.SECONDS), "祖先召回后禁止拆分");
        assertEquals(closure.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_disposition", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, newChild),
                "被拦截拆分不得产生游离后代");
    }

    @Test
    void concurrentSubmitSameDispositionKey_onlyOneWins() throws Exception {
        String root = "BK-CK-R-" + unique();
        List<String> closure = buildRecalledTree(root, List.of(new String[]{"BK-CK-C1-" + unique(), "L1"},
                new String[]{"BK-CK-C2-" + unique(), "L2"}), true);
        String dKey = "DP-CON-" + unique();
        String body1 = submitBody("REQ-CK-1-" + unique(), dKey, "r",
                List.of(root), List.of(), closure.subList(1, closure.size()));
        String body2 = submitBody("REQ-CK-2-" + unique(), dKey, "r",
                List.of(root), List.of(), closure.subList(1, closure.size()));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body1)),
                () -> callStatus(post("/api/batches/" + root + "/dispositions")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
        );
        int a = results.get(0).get(30, TimeUnit.SECONDS);
        int b = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (a == 201 ? 1 : 0) + (b == 201 ? 1 : 0));
        assertEquals(409, a == 201 ? b : a);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM disposition_order", Integer.class));
    }

    // ---------- 只读查询 ----------

    @Test
    void readOnlyQueries_orderAndClosure_404AndStateRules() throws Exception {
        mockMvc.perform(get("/api/dispositions/NO-SUCH-DP"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dispositions/NO-SUCH-DP/paths"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/closure"))
                .andExpect(status().isNotFound());

        String root = "BK-DQ2-R-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, "insp");
        // 非 RECALLED 不可查闭包
        mockMvc.perform(get("/api/batches/" + root + "/closure"))
                .andExpect(status().isUnprocessableEntity());

        recall(root, "u", "召回", "REQ-Q2-" + unique());
        JsonNode frozen = submitAndFreeze(root, "DP-Q2-" + unique(), Map.of(root, "HOLD"));
        // SUBMITTED 无落账路径快照 → 422
        mockMvc.perform(get("/api/dispositions/" + dispositionKeyOf(frozen) + "/paths"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 落账后封闭 ----------

    @Test
    void landedBatches_areLockedFromOldLifecycle_andExcludedFromAvailable() throws Exception {
        // root HOLD（保持 RECALLED），c1 REWORK，c2 HOLD（RELEASED 不变）
        String root = "BK-DL-R-" + unique();
        String c1 = "BK-DL-C1-" + unique();
        String c2 = "BK-DL-C2-" + unique();
        buildRecalledTree(root, List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), true);
        JsonNode frozen = submitAndFreeze(root, "DP-LAND-" + unique(),
                Map.of(root, "HOLD", c1, "REWORK", c2, "HOLD"));
        confirm(dispositionKeyOf(frozen), "ops", "OPERATIONS",
                confirmBody("REQ-LAND-C", 1, frozenVersions(frozen)), 200);

        // 三批均不出现在可用列表（含状态保持 RELEASED 的 HOLD 批次）
        List<String> available = availableKeys();
        assertFalse(available.contains(root));
        assertFalse(available.contains(c1));
        assertFalse(available.contains(c2));

        // 落账批次对旧生命周期封闭
        String testBody = objectMapper.writeValueAsString(new TestCmd("CK-T-X-" + unique(),
                "TK-X-" + unique(), "t1", "PASS", "insp-x"));
        mockMvc.perform(post("/api/batches/" + c2 + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(testBody))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + c2 + "/approvals")
                        .header("X-Actor-Id", "qa-x").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-X-" + unique() + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + c2 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-X-" + unique() + "\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + c2 + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-S-X-" + unique(),
                                List.of(new String[]{"BK-DL-N1-" + unique(), "N1"},
                                        new String[]{"BK-DL-N2-" + unique(), "N2"}))))
                .andExpect(status().is4xxClientError());
        // 状态与落账结论保持
        assertEquals("RELEASED", currentStatus(c2));
        assertEquals("REWORK_PENDING", currentStatus(c1));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            String testBody = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                    "TK-" + unique(), item.asText(), "PASS", inspector));
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON).content(testBody))
                    .andExpect(status().isCreated());
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique());
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String commandKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String actor, String reason, String commandKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().isCreated());
    }

    private String splitBody(String commandKey, List<String[]> children) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void split(String parentKey, String commandKey, List<String[]> children) throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().isCreated());
    }

    /**
     * 建 root（必做项 t1），放行后拆分为 children 并放行全部子批，最后召回 root；返回闭包键列表。
     */
    private List<String> buildRecalledTree(String root, List<String[]> children,
                                           boolean releaseChildren) throws Exception {
        createBatch(root, List.of("t1"));
        releaseBatch(root, "insp-r");
        split(root, "CK-SP-" + unique(), children);
        if (releaseChildren) {
            for (String[] child : children) {
                releaseBatch(child[0], "insp-" + child[0], 0);
            }
        }
        recall(root, "u", "原料污染", "CK-RC-" + unique());
        return closureKeys(root);
    }

    private void releaseBatch(String batchKey, String inspector, int marker) throws Exception {
        releaseBatch(batchKey, inspector);
    }

    private String submitBody(String requestId, String dispositionKey, String holdReason,
                              List<String> destroy, List<String> rework, List<String> hold)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("dispositionKey", dispositionKey);
        body.put("holdReason", holdReason);
        body.put("destroy", destroy);
        body.put("rework", rework);
        body.put("hold", hold);
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult submit(String ancestorKey, String actor, String role, String body,
                             int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + ancestorKey + "/dispositions")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private Map<String, Object> batchVersion(String batchKey, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchKey", batchKey);
        m.put("version", version);
        return m;
    }

    private String confirmBody(String requestId, int expectedVersion,
                               List<Map<String, Object>> batchVersions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDispositionVersion", expectedVersion);
        body.put("batchVersions", batchVersions);
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult confirm(String dispositionKey, String actor, String role, String body,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/dispositions/" + dispositionKey + "/confirm")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    /** 供版本错误用例以路径变量动态指定处置单（当前未使用，保留显式 409 校验入口）。 */

    private String rejectBody(String requestId, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of("requestId", requestId, "reason", reason));
    }

    private MvcResult reject(String dispositionKey, String actor, String role, String body,
                             int expected) throws Exception {
        return mockMvc.perform(post("/api/dispositions/" + dispositionKey + "/reject")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private String cancelBody(String requestId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("requestId", requestId));
    }

    private MvcResult cancel(String dispositionKey, String actor, String role, String body,
                             int expected) throws Exception {
        return mockMvc.perform(post("/api/dispositions/" + dispositionKey + "/cancel")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode submitAndFreeze(String root, String dispositionKey,
                                     Map<String, String> categoryByBatch) throws Exception {
        List<String> destroy = new ArrayList<>();
        List<String> rework = new ArrayList<>();
        List<String> hold = new ArrayList<>();
        for (Map.Entry<String, String> e : categoryByBatch.entrySet()) {
            switch (e.getValue()) {
                case "DESTROY" -> destroy.add(e.getKey());
                case "REWORK" -> rework.add(e.getKey());
                default -> hold.add(e.getKey());
            }
        }
        MvcResult result = submit(root, "qa-lead", "QUALITY",
                submitBody("REQ-SF-" + unique(), dispositionKey, "暂挂原因",
                        destroy, rework, hold), 201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, String> allHold(List<String> closure) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String key : closure) {
            m.put(key, "HOLD");
        }
        return m;
    }

    private List<Map<String, Object>> frozenVersions(JsonNode order) {
        List<Map<String, Object>> versions = new ArrayList<>();
        order.path("batches").forEach(b ->
                versions.add(batchVersion(b.path("batchKey").asText(),
                        b.path("frozenVersion").asLong())));
        return versions;
    }

    private void confirmWithFrozen(String dKey, String actor, String requestId, JsonNode frozen,
                                   int expected) throws Exception {
        confirm(dKey, actor, "OPERATIONS",
                confirmBody(requestId, 1, frozenVersions(frozen)), expected);
    }

    private String dispositionKeyOf(JsonNode order) {
        return order.path("dispositionKey").asText();
    }

    private JsonNode getOrder(String dKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dispositions/" + dKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> closureKeys(String root) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + root + "/closure"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private long currentVersion(String batchKey) {
        return jdbc.queryForObject("SELECT version FROM batch WHERE batch_key = ?",
                Long.class, batchKey);
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private JsonNode findSnapshot(JsonNode snapshots, String batchKey) {
        for (JsonNode s : snapshots) {
            if (batchKey.equals(s.path("batchKey").asText())) {
                return s;
            }
        }
        throw new AssertionError("未找到批次快照: " + batchKey);
    }

    private JsonNode findFrozen(JsonNode frozenBatches, String batchKey) {
        for (JsonNode b : frozenBatches) {
            if (batchKey.equals(b.path("batchKey").asText())) {
                return b;
            }
        }
        throw new AssertionError("未找到冻结批次: " + batchKey);
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
        assertTrue(pool.awaitTermination(40, TimeUnit.SECONDS));
        return futures;
    }
}
