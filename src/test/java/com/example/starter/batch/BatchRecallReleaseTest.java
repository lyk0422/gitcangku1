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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次召回解除与血缘恢复放行审查测试：申请/复检/批准主流程、422 预校验分支、
 * 召回代次、后代历史召回保留、releaseKey 幂等与并发提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchRecallReleaseTest {

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
        jdbc.update("DELETE FROM recall_release_snapshot");
        jdbc.update("DELETE FROM recall_release");
        jdbc.update("DELETE FROM retest");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyPath_releaseRestoresEligibilityAndWritesImmutableSnapshot() throws Exception {
        String root = "BK-HP-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-HP-C-" + unique();
        String sibling = "BK-HP-S-" + unique();
        split(root, "CK-HP-S1", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}), 201);
        releaseBatch(child, "insp-2");
        submitTest(sibling, "t1", "PASS", "insp-3", 201);

        recall(root, "qa-lead", "原料污染", "CK-HP-R1", 201);
        assertEquals("RECALLED", currentStatus(root));
        assertFalse(availableKeys().contains(child));

        // 闭包内全部批次完成合格复检
        retest(root, "RT-HP-1", "PASS", "qa-1", "CK-RT-1", 201);
        retest(child, "RT-HP-2", "PASS", "qa-2", "CK-RT-2", 201);
        retest(sibling, "RT-HP-3", "PASS", "qa-3", "CK-RT-3", 201);

        // 申请：复检集合乱序且含重复，服务端去重并规范排序
        MvcResult applied = applyRelease(root, "mgr-1", "RK-HP-1", 1, "更换供应商并全检",
                List.of(sibling, root, child, child), "qa-lead", 201);
        JsonNode appliedBody = read(applied);
        assertEquals("PENDING", appliedBody.path("status").asText());
        List<String> sorted = Stream.of(child, root, sibling).sorted().toList();
        assertEquals(sorted, toStringList(appliedBody.path("retestBatches")));

        // 批准：写入不可变评审快照
        MvcResult approved = approveRelease(root, "RK-HP-1", "qa-lead", "CK-HP-A1", 201);
        JsonNode snap = read(approved);
        assertEquals("RK-HP-1", snap.path("releaseKey").asText());
        assertEquals(1, snap.path("recallVersion").asInt());
        assertEquals(sorted, toStringList(snap.path("closureBatches")));
        assertEquals("更换供应商并全检", snap.path("correctiveMeasures").asText());
        assertEquals("qa-lead", snap.path("approver").asText());

        // 批次恢复召回前状态（SPLIT），后代解除封锁、放行资格恢复
        assertEquals("SPLIT", currentStatus(root));
        assertTrue(availableKeys().contains(child));
        assertTrue(availableKeys().contains(sibling));

        // 召回记录不删除：状态置为 RELEASED，记录解除时间
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall WHERE batch_key = ?", Integer.class, root));
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT recall_status FROM recall WHERE batch_key = ?", String.class, root));
        assertNotNull(jdbc.queryForObject(
                "SELECT released_at FROM recall WHERE batch_key = ?", String.class, root));

        // 解除快照可查询
        MvcResult detail = mockMvc.perform(get("/api/batches/" + root + "/recall-releases/RK-HP-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode detailBody = read(detail);
        assertEquals("APPROVED", detailBody.path("release").path("status").asText());
        assertFalse(detailBody.path("release").path("decidedAt").isNull());
        assertEquals(sorted, toStringList(detailBody.path("snapshot").path("closureBatches")));

        // 历史放行与召回记录不重写：批准记录仍 2 条，召回事件仍可见
        MvcResult history = mockMvc.perform(get("/api/batches/" + root + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode historyBody = read(history);
        assertEquals(2, historyBody.path("approvals").size());
        assertFalse(historyBody.path("recall").isNull());
    }

    // ---------- 申请失败分支 ----------

    @Test
    void applyFailures_notRecalledVersionMismatchUnknownBatchAndInvalidParams() throws Exception {
        // 无 ACTIVE 召回记录的批次不可申请 → 409
        String normal = "BK-AF-N-" + unique();
        createBatch(normal, List.of("t1"), 201);
        applyRelease(normal, "mgr", "RK-AF-1", 1, "m", List.of(normal), "qa", 409);

        // 未知批次 → 404
        applyRelease("NO-SUCH-BATCH", "mgr", "RK-AF-2", 1, "m", List.of("NO-SUCH-BATCH"), "qa",
                404);

        // 召回版本不匹配 → 409
        String recalled = "BK-AF-R-" + unique();
        createBatch(recalled, List.of("t1"), 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "r", "CK-AF-R1", 201);
        applyRelease(recalled, "mgr", "RK-AF-3", 2, "m", List.of(recalled), "qa", 409);

        // 参数校验 → 400：空复检集合
        mockMvc.perform(post("/api/batches/" + recalled + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseKey\":\"RK-AF-4\",\"recallVersion\":1,"
                                + "\"correctiveMeasures\":\"m\",\"retestBatches\":[],"
                                + "\"approver\":\"qa\"}"))
                .andExpect(status().isBadRequest());
        // 空纠正措施
        mockMvc.perform(post("/api/batches/" + recalled + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseKey\":\"RK-AF-5\",\"recallVersion\":1,"
                                + "\"correctiveMeasures\":\"\",\"retestBatches\":[\"" + recalled
                                + "\"],\"approver\":\"qa\"}"))
                .andExpect(status().isBadRequest());
        // 缺指定审批人
        mockMvc.perform(post("/api/batches/" + recalled + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseKey\":\"RK-AF-6\",\"recallVersion\":1,"
                                + "\"correctiveMeasures\":\"m\",\"retestBatches\":[\"" + recalled
                                + "\"]}"))
                .andExpect(status().isBadRequest());
        // 版本小于 1
        mockMvc.perform(post("/api/batches/" + recalled + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseKey\":\"RK-AF-7\",\"recallVersion\":0,"
                                + "\"correctiveMeasures\":\"m\",\"retestBatches\":[\"" + recalled
                                + "\"],\"approver\":\"qa\"}"))
                .andExpect(status().isBadRequest());
        // 缺 X-Actor-Id
        mockMvc.perform(post("/api/batches/" + recalled + "/recall-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseKey\":\"RK-AF-8\",\"recallVersion\":1,"
                                + "\"correctiveMeasures\":\"m\",\"retestBatches\":[\"" + recalled
                                + "\"],\"approver\":\"qa\"}"))
                .andExpect(status().isBadRequest());
        // 全部失败不留申请记录
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recall_release", Integer.class));
    }

    // ---------- 批准预校验 422 分支 ----------

    @Test
    void approvePendingQuarantine_returns422AndNoPartialState() throws Exception {
        String root = "BK-PQ-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-PQ-C-" + unique();
        String sibling = "BK-PQ-C2-" + unique();
        split(root, "CK-PQ-S1", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}),
                201);
        // child 保持 QUARANTINED（未决隔离）；sibling 完成检验
        submitTest(sibling, "t1", "PASS", "insp-2", 201);
        recall(root, "u", "r", "CK-PQ-R1", 201);
        retest(root, "RT-PQ-1", "PASS", "qa", "CK-PQ-T1", 201);
        retest(sibling, "RT-PQ-2", "PASS", "qa", "CK-PQ-T2", 201);
        retest(child, "RT-PQ-3", "PASS", "qa", "CK-PQ-T3", 201);

        applyRelease(root, "mgr", "RK-PQ-1", 1, "m", List.of(root, child, sibling), "qa-lead",
                201);
        MvcResult rejected = approveRelease(root, "RK-PQ-1", "qa-lead", "CK-PQ-A1", 422);
        assertTrue(read(rejected).path("message").asText().contains("未决隔离"));

        // 所有召回状态与放行资格保持不变，不留半成品状态
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT recall_status FROM recall WHERE batch_key = ?", String.class, root));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM recall_release WHERE release_key = 'RK-PQ-1'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_release_snapshot", Integer.class));
    }

    @Test
    void approveRetestGap_returns422_failureDoesNotConsumeKeyAndRetrySucceeds() throws Exception {
        String root = "BK-RG-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String sibling = "BK-RG-S-" + unique();
        split(root, "CK-RG-S1", List.of(new String[]{sibling, "L1"},
                new String[]{"BK-RG-S2-" + unique(), "L2"}), 201);
        submitTest(sibling, "t1", "PASS", "insp-2", 201);
        String other = jdbc.queryForObject(
                "SELECT child_key FROM batch_lineage WHERE parent_key = ? AND seq = 2",
                String.class, root);
        submitTest(other, "t1", "PASS", "insp-3", 201);
        recall(root, "u", "r", "CK-RG-R1", 201);

        retest(root, "RT-RG-1", "PASS", "qa", "CK-RG-T1", 201);
        retest(other, "RT-RG-2", "PASS", "qa", "CK-RG-T2", 201);
        // sibling 无复检 → 缺口
        applyRelease(root, "mgr", "RK-RG-1", 1, "m", List.of(root, sibling, other), "qa-lead",
                201);
        MvcResult gap = approveRelease(root, "RK-RG-1", "qa-lead", "CK-RG-A1", 422);
        assertTrue(read(gap).path("message").asText().contains("未完成合格复检"));

        // sibling 最新复检 FAIL → 仍 422
        retest(sibling, "RT-RG-3", "FAIL", "qa", "CK-RG-T3", 201);
        approveRelease(root, "RK-RG-1", "qa-lead", "CK-RG-A1", 422);
        assertEquals("RECALLED", currentStatus(root));

        // 失败不占键：同一 commandKey 在补齐合格复检后成功
        retest(sibling, "RT-RG-4", "PASS", "qa", "CK-RG-T4", 201);
        approveRelease(root, "RK-RG-1", "qa-lead", "CK-RG-A1", 201);
        assertEquals("SPLIT", currentStatus(root));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_release_snapshot", Integer.class));
    }

    @Test
    void approveClosureMismatchAndWrongApprover_return422WithDistinctReasons() throws Exception {
        String root = "BK-CM-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CM-C-" + unique();
        split(root, "CK-CM-S1", List.of(new String[]{child, "L1"},
                new String[]{"BK-CM-C2-" + unique(), "L2"}), 201);
        submitTest(child, "t1", "PASS", "insp-2", 201);
        String other = jdbc.queryForObject(
                "SELECT child_key FROM batch_lineage WHERE parent_key = ? AND seq = 2",
                String.class, root);
        submitTest(other, "t1", "PASS", "insp-3", 201);
        recall(root, "u", "r", "CK-CM-R1", 201);
        retest(root, "RT-CM-1", "PASS", "qa", "CK-CM-T1", 201);
        retest(child, "RT-CM-2", "PASS", "qa", "CK-CM-T2", 201);
        retest(other, "RT-CM-3", "PASS", "qa", "CK-CM-T3", 201);

        // 血缘新增未知来源：声明集合包含闭包外批次 → 422
        applyRelease(root, "mgr", "RK-CM-1", 1, "m",
                List.of(root, child, other, "BK-CM-UNKNOWN-" + unique()), "qa-lead", 201);
        MvcResult unknown = approveRelease(root, "RK-CM-1", "qa-lead", "CK-CM-A1", 422);
        assertTrue(read(unknown).path("message").asText().contains("未知来源"));

        // 声明集合缺少闭包批次 → 422
        applyRelease(root, "mgr", "RK-CM-2", 1, "m", List.of(root, child), "qa-lead", 201);
        MvcResult missing = approveRelease(root, "RK-CM-2", "qa-lead", "CK-CM-A2", 422);
        assertTrue(read(missing).path("message").asText().contains("缺少最终血缘闭包批次"));

        // 审批人必须为申请指定审批人 → 422
        applyRelease(root, "mgr", "RK-CM-3", 1, "m", List.of(root, child, other), "qa-lead", 201);
        MvcResult wrongApprover = approveRelease(root, "RK-CM-3", "qa-other", "CK-CM-A3", 422);
        assertTrue(read(wrongApprover).path("message").asText().contains("指定审批人"));

        // 全部失败后状态不变；指定审批人批准成功
        assertEquals("RECALLED", currentStatus(root));
        approveRelease(root, "RK-CM-3", "qa-lead", "CK-CM-A3", 201);
        assertEquals("SPLIT", currentStatus(root));
    }

    // ---------- 复检规则 ----------

    @Test
    void retestRules_replayConflictAndNotUnderRecall() throws Exception {
        // 未处于召回影响范围的批次不可复检 → 409
        String normal = "BK-TR-N-" + unique();
        createBatch(normal, List.of("t1"), 201);
        retest(normal, "RT-TR-0", "PASS", "qa", "CK-TR-0", 409);

        String recalled = "BK-TR-R-" + unique();
        createBatch(recalled, List.of("t1"), 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "r", "CK-TR-R1", 201);

        // retestKey 幂等：同内容重放 200，不同内容 409
        retest(recalled, "RT-TR-1", "PASS", "qa-1", "CK-TR-1", 201);
        retest(recalled, "RT-TR-1", "PASS", "qa-1", "CK-TR-2", 200);
        retest(recalled, "RT-TR-1", "FAIL", "qa-1", "CK-TR-3", 409);

        // 最新复检决定合格性：FAIL 后再次 PASS 恢复合格
        retest(recalled, "RT-TR-2", "FAIL", "qa-2", "CK-TR-4", 201);
        MvcResult gaps = mockMvc.perform(get("/api/batches/" + recalled + "/retest-gaps"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(read(gaps).path("gaps").toString().contains("FAILED_RETEST"));
        retest(recalled, "RT-TR-3", "PASS", "qa-3", "CK-TR-5", 201);
        MvcResult gapsAfter = mockMvc.perform(get("/api/batches/" + recalled + "/retest-gaps"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, read(gapsAfter).path("gaps").size());

        // 未知批次复检 → 404
        retest("NO-SUCH-BATCH", "RT-TR-9", "PASS", "qa", "CK-TR-9", 404);
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_applyAndApproveReplayAndKeyConflict() throws Exception {
        String root = "BK-ID-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp");
        recall(root, "u", "r", "CK-ID-R1", 201);
        retest(root, "RT-ID-1", "PASS", "qa", "CK-ID-T1", 201);

        // 申请同键同参重放：返回首次结果，不重复落库
        MvcResult first = applyRelease(root, "mgr", "RK-ID-1", 1, "m", List.of(root), "qa-lead",
                201);
        MvcResult replay = applyRelease(root, "mgr", "RK-ID-1", 1, "m", List.of(root), "qa-lead",
                201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall_release", Integer.class));

        // 同键改参 → 409
        applyRelease(root, "mgr", "RK-ID-1", 1, "changed", List.of(root), "qa-lead", 409);

        // 批准同 commandKey 重放：返回首次快照，不重复写快照
        MvcResult approved = approveRelease(root, "RK-ID-1", "qa-lead", "CK-ID-A1", 201);
        MvcResult approvedReplay = approveRelease(root, "RK-ID-1", "qa-lead", "CK-ID-A1", 201);
        assertEquals(approved.getResponse().getContentAsString(),
                approvedReplay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_release_snapshot", Integer.class));

        // 换 commandKey 重复批准 → 409
        approveRelease(root, "RK-ID-1", "qa-lead", "CK-ID-A2", 409);

        // 申请失败不占键：同一 releaseKey 在合格批次上可成功
        String notRecalled = "BK-ID-N-" + unique();
        createBatch(notRecalled, List.of("t1"), 201);
        applyRelease(notRecalled, "mgr", "RK-ID-2", 1, "m", List.of(notRecalled), "qa", 409);
        String recalled2 = "BK-ID-R2-" + unique();
        createBatch(recalled2, List.of("t1"), 201);
        releaseBatch(recalled2, "insp");
        recall(recalled2, "u", "r", "CK-ID-R2", 201);
        applyRelease(recalled2, "mgr", "RK-ID-2", 1, "m", List.of(recalled2), "qa", 201);
    }

    // ---------- 召回代次 ----------

    @Test
    void recallGenerations_onlyCoveredGenerationReleased() throws Exception {
        String leaf = "BK-GN-L-" + unique();
        createBatch(leaf, List.of("t1"), 201);
        releaseBatch(leaf, "insp");

        // 第一代召回 → 解除
        recall(leaf, "u", "第一次召回", "CK-GN-R1", 201);
        retest(leaf, "RT-GN-1", "PASS", "qa", "CK-GN-T1", 201);
        applyRelease(leaf, "mgr", "RK-GN-1", 1, "m1", List.of(leaf), "qa-lead", 201);
        approveRelease(leaf, "RK-GN-1", "qa-lead", "CK-GN-A1", 201);
        assertEquals("RELEASED", currentStatus(leaf));

        // 解除后可再次召回，生成第二代
        recall(leaf, "u", "第二次召回", "CK-GN-R2", 201);
        assertEquals("RECALLED", currentStatus(leaf));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall WHERE batch_key = ?", Integer.class, leaf));

        // 旧代次版本申请 → 409；仅当前 ACTIVE 代次可申请
        applyRelease(leaf, "mgr", "RK-GN-2", 1, "m2", List.of(leaf), "qa-lead", 409);
        applyRelease(leaf, "mgr", "RK-GN-2", 2, "m2", List.of(leaf), "qa-lead", 201);
        approveRelease(leaf, "RK-GN-2", "qa-lead", "CK-GN-A2", 201);

        // 两代召回记录均保留且均已解除，批次恢复放行资格
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall WHERE batch_key = ? AND recall_status = 'RELEASED'",
                Integer.class, leaf));
        assertEquals("RELEASED", currentStatus(leaf));
        assertTrue(availableKeys().contains(leaf));

        // 第一代解除快照仍可查询，不被第二代改写
        MvcResult detail = mockMvc.perform(get("/api/batches/" + leaf + "/recall-releases/RK-GN-1"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, read(detail).path("snapshot").path("recallVersion").asInt());
    }

    // ---------- 后代历史召回 ----------

    @Test
    void descendantOwnRecall_notDeletedAndNeedsOwnRelease() throws Exception {
        String root = "BK-DR-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-DR-C-" + unique();
        String sibling = "BK-DR-C2-" + unique();
        split(root, "CK-DR-S1", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}),
                201);
        releaseBatch(child, "insp-2");
        submitTest(sibling, "t1", "PASS", "insp-3", 201);

        // 后代自身被直接召回，随后根批也被召回
        recall(child, "u", "子批自身问题", "CK-DR-R1", 201);
        recall(root, "u", "根批召回", "CK-DR-R2", 201);

        retest(root, "RT-DR-1", "PASS", "qa", "CK-DR-T1", 201);
        retest(child, "RT-DR-2", "PASS", "qa", "CK-DR-T2", 201);
        retest(sibling, "RT-DR-3", "PASS", "qa", "CK-DR-T3", 201);

        // 根批解除仅覆盖根批召回代次：后代历史召回记录不删除
        applyRelease(root, "mgr", "RK-DR-1", 1, "m", List.of(root, child, sibling), "qa-lead",
                201);
        approveRelease(root, "RK-DR-1", "qa-lead", "CK-DR-A1", 201);
        assertEquals("SPLIT", currentStatus(root));
        assertEquals("RECALLED", currentStatus(child));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT recall_status FROM recall WHERE batch_key = ?", String.class, child));

        // 后代仍需各自满足复检并单独解除
        applyRelease(child, "mgr", "RK-DR-2", 1, "m", List.of(child), "qa-lead", 201);
        approveRelease(child, "RK-DR-2", "qa-lead", "CK-DR-A2", 201);
        assertEquals("RELEASED", currentStatus(child));
        assertTrue(availableKeys().contains(child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall WHERE recall_status = 'RELEASED'", Integer.class));
    }

    // ---------- 查询 ----------

    @Test
    void queries_recallImpactRetestGapsAndReleaseDetail() throws Exception {
        String root = "BK-QR-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-QR-C-" + unique();
        String sibling = "BK-QR-S-" + unique();
        split(root, "CK-QR-S1", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}),
                201);
        // child 保持 QUARANTINED；sibling PENDING_RELEASE
        submitTest(sibling, "t1", "PASS", "insp-2", 201);
        recall(root, "u", "r", "CK-QR-R1", 201);
        retest(root, "RT-QR-1", "PASS", "qa", "CK-QR-T1", 201);
        retest(sibling, "RT-QR-2", "FAIL", "qa", "CK-QR-T2", 201);

        // 复检缺口：child 未决隔离+无复检，sibling 最新复检不合格
        MvcResult gaps = mockMvc.perform(get("/api/batches/" + root + "/retest-gaps"))
                .andExpect(status().isOk()).andReturn();
        JsonNode gapsBody = read(gaps);
        List<String> closure = Stream.of(child, root, sibling).sorted().toList();
        assertEquals(closure, toStringList(gapsBody.path("closureBatches")));
        String gapsText = gapsBody.path("gaps").toString();
        assertTrue(gapsText.contains("PENDING_QUARANTINE"));
        assertTrue(gapsText.contains("MISSING_RETEST"));
        assertTrue(gapsText.contains("FAILED_RETEST"));
        assertEquals(3, gapsBody.path("gaps").size());

        // 血缘影响：各批自身状态、召回代次与复检合格性
        MvcResult impact = mockMvc.perform(get("/api/batches/" + root + "/recall-impact"))
                .andExpect(status().isOk()).andReturn();
        JsonNode impactBody = read(impact);
        assertEquals(3, impactBody.size());
        assertEquals(root, impactBody.get(0).path("batchKey").asText());
        assertEquals("RECALLED", impactBody.get(0).path("status").asText());
        assertEquals(1, impactBody.get(0).path("recallVersion").asInt());
        assertEquals("ACTIVE", impactBody.get(0).path("recallStatus").asText());
        assertTrue(impactBody.get(0).path("retestQualified").asBoolean());
        assertEquals("QUARANTINED", impactBody.get(1).path("status").asText());
        assertTrue(impactBody.get(1).path("recallVersion").isNull());
        assertFalse(impactBody.get(1).path("retestQualified").asBoolean());
        assertEquals("FAIL", impactBody.get(2).path("latestRetest").asText());

        // 解除申请详情：未批准时 snapshot 为 null
        applyRelease(root, "mgr", "RK-QR-1", 1, "m", List.of(root, child, sibling), "qa-lead",
                201);
        MvcResult detail = mockMvc.perform(get("/api/batches/" + root + "/recall-releases/RK-QR-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode detailBody = read(detail);
        assertEquals("PENDING", detailBody.path("release").path("status").asText());
        assertTrue(detailBody.path("snapshot").isNull());

        // 未知键 → 404
        mockMvc.perform(get("/api/batches/" + root + "/recall-releases/NO-SUCH-RK"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/recall-impact"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/retest-gaps"))
                .andExpect(status().isNotFound());
        approveRelease(root, "NO-SUCH-RK", "qa-lead", "CK-QR-A9", 404);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentApproveAndFailingRetest_commitOrderDecides() throws Exception {
        String root = "BK-CC-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp");
        recall(root, "u", "r", "CK-CC-R1", 201);
        retest(root, "RT-CC-1", "PASS", "qa", "CK-CC-T1", 201);
        applyRelease(root, "mgr", "RK-CC-1", 1, "m", List.of(root), "qa-lead", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + root + "/recall-releases/RK-CC-1/approve")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CC-A1\"}")),
                () -> callStatus(post("/api/batches/" + root + "/retests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CC-T2\",\"retestKey\":\"RT-CC-2\","
                                + "\"outcome\":\"FAIL\",\"inspector\":\"qa-2\"}"))
        );
        int approve = results.get(0).get(30, TimeUnit.SECONDS);
        int failRetest = results.get(1).get(30, TimeUnit.SECONDS);
        if (approve == 201) {
            // 批准先提交：召回解除，后续复检因批次已不在召回范围被拒绝
            assertEquals(409, failRetest);
            assertEquals("RELEASED", currentStatus(root));
        } else {
            // 不合格复检先提交：批准预校验失败 422，召回状态不变
            assertEquals(422, approve);
            assertEquals(201, failRetest);
            assertEquals("RECALLED", currentStatus(root));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT recall_status FROM recall WHERE batch_key = ?", String.class, root));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recall_release_snapshot", Integer.class));
        }
    }

    @Test
    void concurrentDuplicateApprove_sameWinnerForBoth() throws Exception {
        String root = "BK-CD-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp");
        recall(root, "u", "r", "CK-CD-R1", 201);
        retest(root, "RT-CD-1", "PASS", "qa", "CK-CD-T1", 201);
        applyRelease(root, "mgr", "RK-CD-1", 1, "m", List.of(root), "qa-lead", 201);

        List<Future<String>> bodies = runConcurrentBodies(
                () -> callBody(post("/api/batches/" + root + "/recall-releases/RK-CD-1/approve")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CD-A1\"}")),
                () -> callBody(post("/api/batches/" + root + "/recall-releases/RK-CD-1/approve")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CD-A1\"}"))
        );
        String first = bodies.get(0).get(30, TimeUnit.SECONDS);
        String second = bodies.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同 commandKey 并发批准均返回首次快照");
        assertTrue(first.contains("RK-CD-1"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_release_snapshot", Integer.class));
        assertEquals("RELEASED", currentStatus(root));
    }

    @Test
    void concurrentApplySameReleaseKey_sameWinnerForBoth() throws Exception {
        String root = "BK-CA-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp");
        recall(root, "u", "r", "CK-CA-R1", 201);
        String body = "{\"releaseKey\":\"RK-CA-1\",\"recallVersion\":1,"
                + "\"correctiveMeasures\":\"m\",\"retestBatches\":[\"" + root
                + "\"],\"approver\":\"qa-lead\"}";

        List<Future<String>> bodies = runConcurrentBodies(
                () -> callBody(post("/api/batches/" + root + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/batches/" + root + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        String first = bodies.get(0).get(30, TimeUnit.SECONDS);
        String second = bodies.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同 releaseKey 同参并发申请均返回首次结果");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall_release", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> toStringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode item : read(history).path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-T-" + unique(), "TK-" + unique(), item, result,
                                        inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
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

    private void split(String parentKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.append("]}").toString()))
                .andExpect(status().is(expected));
    }

    private MvcResult retest(String batchKey, String retestKey, String outcome, String inspector,
                             String commandKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/retests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RetestCmd(commandKey, retestKey, outcome, inspector))))
                .andExpect(status().is(expected)).andReturn();
    }

    private record RetestCmd(String commandKey, String retestKey, String outcome,
                             String inspector) {
    }

    private MvcResult applyRelease(String batchKey, String actor, String releaseKey, int version,
                                   String measures, List<String> retestBatches, String approver,
                                   int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recall-releases")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplyCmd(releaseKey, version, measures, retestBatches,
                                        approver))))
                .andExpect(status().is(expected)).andReturn();
    }

    private record ApplyCmd(String releaseKey, int recallVersion, String correctiveMeasures,
                            List<String> retestBatches, String approver) {
    }

    private MvcResult approveRelease(String batchKey, String releaseKey, String actor,
                                     String commandKey, int expected) throws Exception {
        return mockMvc.perform(post(
                        "/api/batches/" + batchKey + "/recall-releases/" + releaseKey + "/approve")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = read(result);
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callBody(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            return result.getResponse().getStatus() + "|"
                    + result.getResponse().getContentAsString();
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

    @SafeVarargs
    private List<Future<String>> runConcurrentBodies(Callable<String>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
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
