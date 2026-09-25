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

import java.util.ArrayList;
import java.util.HashMap;
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
 * 批次召回解除测试：申请/复检/批准主流程、召回代次、血缘闭包预校验、放行资格恢复、
 * 历史快照不可变、releaseKey 幂等与并发裁决；失败均断言不留半成品状态。
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
        jdbc.update("DELETE FROM recall_release_snapshot");
        jdbc.update("DELETE FROM recall_release");
        jdbc.update("DELETE FROM reinspection");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyRelease_recallLifted_snapshotWritten_releaseEligibilityRestored() throws Exception {
        String root = "BK-RR-" + unique();
        createBatch(root, List.of("t1", "t2"));
        releaseBatch(root, List.of("t1", "t2"));
        recall(root, "op-1", "污染风险", 201);
        assertEquals("RECALLED", currentStatus(root));
        assertFalse(availableKeys().contains(root));

        reinspect(root, "t1", "PASS", "reinsp-1", 201);
        reinspect(root, "t2", "PASS", "reinsp-1", 201);

        // 复检缺口为空
        JsonNode gaps = read(mockMvc.perform(get("/api/batches/" + root + "/reinspection-gaps"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, gaps.path("recallVersion").asInt());
        assertEquals(1, gaps.path("gaps").size());
        assertEquals(0, gaps.path("gaps").get(0).path("missingItems").size());

        // 申请：复检集合带重复与空白，服务端规范化为去重排序集合
        String releaseKey = "RK-" + unique();
        MvcResult applied = applyRelease(root, "mgr-1", releaseKey, 1, "更换供应商并全项复检",
                List.of("  " + root, root), 201);
        JsonNode application = read(applied);
        assertEquals("PENDING", application.path("status").asText());
        assertEquals(1, application.path("reinspectionBatches").size());
        assertEquals(root, application.path("reinspectionBatches").get(0).asText());
        assertTrue(application.path("approver").isNull());

        // 血缘影响：根批次自身在闭包内且复检已完成
        JsonNode impact = read(mockMvc.perform(get("/api/batches/" + root + "/recall-impact"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("ACTIVE", impact.path("recallStatus").asText());
        assertEquals(1, impact.path("affected").size());
        assertTrue(impact.path("affected").get(0).path("reinspectionComplete").asBoolean());

        // 批准：解除第 1 代召回，恢复召回前状态 RELEASED，写入评审快照
        String approveKey = "CK-AR-" + unique();
        MvcResult approved = approveRelease(root, releaseKey, "qa-lead", approveKey, 201);
        JsonNode snapshot = read(approved);
        assertEquals(releaseKey, snapshot.path("releaseKey").asText());
        assertEquals(1, snapshot.path("recallVersion").asInt());
        assertEquals("qa-lead", snapshot.path("approver").asText());
        assertEquals(List.of(root), jsonStrings(snapshot.path("closureBatches")));
        // 快照记录评审时刻核验的批次状态：批准前根批次处于 RECALLED
        assertEquals("RECALLED", snapshot.path("entries").get(0).path("status").asText());
        assertEquals(List.of("t1", "t2"), jsonStrings(snapshot.path("entries").get(0).path("passedItems")));

        assertEquals("RELEASED", currentStatus(root));
        assertTrue(availableKeys().contains(root), "解除后恢复放行资格，重新可用");
        assertEquals("LIFTED", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ? AND version = 1", String.class, root));

        // 快照查询与批准响应一致；申请状态为 APPROVED
        MvcResult snapshotGet = mockMvc.perform(get("/api/batches/" + root
                        + "/recall-releases/" + releaseKey + "/snapshot"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(approved.getResponse().getContentAsString(),
                snapshotGet.getResponse().getContentAsString());
        JsonNode releases = read(mockMvc.perform(get("/api/batches/" + root + "/recall-releases"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, releases.size());
        assertEquals("APPROVED", releases.get(0).path("status").asText());
        assertEquals("qa-lead", releases.get(0).path("approver").asText());

        // 历史不回写：召回记录保留且标记 LIFTED，批准历史完整
        JsonNode history = read(mockMvc.perform(get("/api/batches/" + root + "/history"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("LIFTED", history.path("recall").path("recallStatus").asText());
        assertEquals(1, history.path("recall").path("version").asInt());
        assertEquals(2, history.path("approvals").size());
        assertEquals("RELEASED", history.path("approvals").get(1).path("batchStatus").asText());
    }

    // ---------- 申请失败分支 ----------

    @Test
    void applyValidation_failures_areDistinguishable_andDoNotConsumeKeys() throws Exception {
        String free = "BK-FREE-" + unique();
        createBatch(free, List.of("t1"));
        releaseBatch(free, List.of("t1"));
        // 未召回（含已销毁等一切非 RECALLED 状态）不可申请
        applyRelease(free, "mgr", "RK-" + unique(), 1, "措施", List.of(free), 409);

        String root = "BK-APV-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        recall(root, "op", "原因", 201);

        // 召回版本不匹配 → 422
        applyRelease(root, "mgr", "RK-" + unique(), 9, "措施", List.of(root), 422);
        // 申报集合与血缘闭包不一致（缺根批次/含未知批次）→ 422
        applyRelease(root, "mgr", "RK-" + unique(), 1, "措施", List.of("NO-SUCH-" + unique()), 422);
        applyRelease(root, "mgr", "RK-" + unique(), 1, "措施",
                List.of(root, "NO-SUCH-" + unique()), 422);
        // 纠正措施为空 → 400
        mockMvc.perform(post("/api/batches/" + root + "/recall-releases")
                        .header("X-Actor-Id", "mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("CK-B-" + unique(), "RK-" + unique(), 1, "", List.of(root))))
                .andExpect(status().isBadRequest());
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/" + root + "/recall-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("CK-B-" + unique(), "RK-" + unique(), 1, "措施", List.of(root))))
                .andExpect(status().isBadRequest());
        // 不存在的批次 → 404
        applyRelease("NO-SUCH-" + unique(), "mgr", "RK-" + unique(), 1, "措施",
                List.of("x"), 404);

        // 失败不占键：失败的 commandKey 未写入 command_log，可修正参数后复用同一键成功
        String commandKey = "CK-RETRY-" + unique();
        String releaseKey = "RK-RETRY-" + unique();
        applyRelease(root, "mgr", releaseKey, 7, "措施", List.of(root), 422, commandKey);
        assertEquals(0, countRows("command_log", commandKey));
        assertEquals(0, countRows("recall_release", releaseKey, "release_key"));
        applyRelease(root, "mgr", releaseKey, 1, "措施", List.of(root), 201, commandKey);
        assertEquals(1, countRows("command_log", commandKey));

        // 同一批次已存在待批准申请 → 新 releaseKey 申请 409
        applyRelease(root, "mgr", "RK-" + unique(), 1, "另一措施", List.of(root), 409);
    }

    // ---------- 复检规则 ----------

    @Test
    void reinspection_rules_replayAndConflict() throws Exception {
        String free = "BK-NORC-" + unique();
        createBatch(free, List.of("t1"));
        // 未处于召回上下文 → 409
        reinspect(free, "t1", "PASS", "i1", 409);

        String root = "BK-RI-" + unique();
        createBatch(root, List.of("t1", "t2"));
        releaseBatch(root, List.of("t1", "t2"));
        recall(root, "op", "原因", 201);

        // 非必做检验项 → 422
        reinspect(root, "不存在的项", "PASS", "i1", 422);

        // 首次提交 201；同内容重放（新 commandKey）200 且结果一致；不同内容 409
        MvcResult first = reinspect(root, "t1", "PASS", "i1", 201);
        MvcResult replay = reinspect(root, "t1", "PASS", "i1", 200);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        JsonNode body = read(first);
        assertEquals(root, body.path("rootKey").asText());
        assertEquals(1, body.path("recallVersion").asInt());
        reinspect(root, "t1", "FAIL", "i1", 409);
        reinspect(root, "t1", "PASS", "other-inspector", 409);
        assertEquals(1, countRows("reinspection", root, "batch_key"));

        // FAIL 复检如实记录但不计入合格复检
        reinspect(root, "t2", "FAIL", "i1", 201);
        JsonNode gaps = read(mockMvc.perform(get("/api/batches/" + root + "/reinspection-gaps"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(List.of("t2"), jsonStrings(gaps.path("gaps").get(0).path("missingItems")));
    }

    // ---------- 批准预校验：血缘闭包 / 未决隔离 / 复检缺口 ----------

    @Test
    void approveValidations_fail422_andLeaveNoPartialState() throws Exception {
        String root = "BK-CLS-" + unique();
        String child1 = "BK-CLS-C1-" + unique();
        String child2 = "BK-CLS-C2-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        split(root, List.of(child1, child2), 201);
        // 子批在召回前完成检验，脱离未决隔离
        submitTest(child1, "t1", "PASS", "insp-c1", 201);
        submitTest(child2, "t1", "PASS", "insp-c2", 201);
        recall(root, "op", "根批次召回", 201);
        assertEquals("RECALLED", currentStatus(root));

        // 申报集合与血缘闭包不一致（漏报 child2）→ 申请即 422
        applyRelease(root, "mgr", "RK-" + unique(), 1, "措施", List.of(root, child1), 422);

        // 只完成根批复检：批准 422（后代复检缺口），且不留半成品状态
        String releaseKey = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey, 1, "措施", List.of(child1, child2, root), 201);
        reinspect(root, "t1", "PASS", "ri", 201);
        String approveKey = "CK-AR-" + unique();
        approveRelease(root, releaseKey, "qa-lead", approveKey, 422);
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ?", String.class, root));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM recall_release WHERE release_key = ?", String.class, releaseKey));
        assertEquals(0, countRows("recall_release_snapshot", releaseKey, "release_key"));
        assertEquals(0, countRows("command_log", approveKey), "失败不占键");

        // 补齐后代复检后，同一 commandKey 重试成功（失败未占键）
        reinspect(child1, "t1", "PASS", "ri", 201);
        reinspect(child2, "t1", "PASS", "ri", 201);
        approveRelease(root, releaseKey, "qa-lead", approveKey, 201);
        // 根批次召回前为 SPLIT，解除后恢复 SPLIT；后代状态与放行资格不被重写
        assertEquals("SPLIT", currentStatus(root));
        assertEquals("PENDING_RELEASE", currentStatus(child1));
        assertEquals("PENDING_RELEASE", currentStatus(child2));
    }

    @Test
    void approveWithQuarantinedDescendant_returns422() throws Exception {
        String root = "BK-QT-" + unique();
        String child1 = "BK-QT-C1-" + unique();
        String child2 = "BK-QT-C2-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        split(root, List.of(child1, child2), 201);
        // 子批从未检验，处于未决隔离 QUARANTINED
        recall(root, "op", "原因", 201);
        // 全部批次复检合格，仍因未决隔离被拒
        List<String> closure = List.of(root, child1, child2);
        for (String key : closure) {
            reinspect(key, "t1", "PASS", "ri", 201);
        }
        String releaseKey = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey, 1, "措施", closure, 201);
        MvcResult result = approveRelease(root, releaseKey, "qa-lead", "CK-AR-" + unique(), 422);
        assertTrue(read(result).path("message").asText().contains("未决隔离"));
        assertEquals("RECALLED", currentStatus(root));
        assertEquals(0, countRows("recall_release_snapshot", releaseKey, "release_key"));
    }

    // ---------- releaseKey 幂等 ----------

    @Test
    void releaseKey_replayAndConflict_snapshotImmutable() throws Exception {
        String root = "BK-IDEM-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        recall(root, "op", "原因", 201);
        reinspect(root, "t1", "PASS", "ri", 201);

        String releaseKey = "RK-" + unique();
        MvcResult applied = applyRelease(root, "mgr", releaseKey, 1, "措施A", List.of(root), 201);
        // 待批准申请无快照 → 409
        mockMvc.perform(get("/api/batches/" + root + "/recall-releases/" + releaseKey + "/snapshot"))
                .andExpect(status().isConflict());
        // 同 releaseKey 同参重放（新 commandKey）→ 200 返回原申请
        MvcResult replay = applyRelease(root, "mgr", releaseKey, 1, "措施A", List.of(root), 200);
        assertEquals(read(applied).path("createdAt").asText(), read(replay).path("createdAt").asText());
        // 同 releaseKey 改参（措施/版本/集合/申请人任一不同）→ 409
        applyRelease(root, "mgr", releaseKey, 1, "措施B", List.of(root), 409);
        applyRelease(root, "mgr2", releaseKey, 1, "措施A", List.of(root), 409);

        // 批准：指纹含审批人；同审批人重放返回首次快照，换审批人 409
        MvcResult approved = approveRelease(root, releaseKey, "qa-lead", "CK-AR-" + unique(), 201);
        MvcResult approvedReplay = approveRelease(root, releaseKey, "qa-lead", "CK-AR-" + unique(), 200);
        assertEquals(approved.getResponse().getContentAsString(),
                approvedReplay.getResponse().getContentAsString());
        approveRelease(root, releaseKey, "other-lead", "CK-AR-" + unique(), 409);
        assertEquals(1, countRows("recall_release_snapshot", releaseKey, "release_key"));

        // 快照不可变：再次查询内容一致；解除后批次不再处于召回上下文，复检 409
        MvcResult snap1 = mockMvc.perform(get("/api/batches/" + root
                        + "/recall-releases/" + releaseKey + "/snapshot"))
                .andExpect(status().isOk()).andReturn();
        MvcResult snap2 = mockMvc.perform(get("/api/batches/" + root
                        + "/recall-releases/" + releaseKey + "/snapshot"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(snap1.getResponse().getContentAsString(),
                snap2.getResponse().getContentAsString());
        reinspect(root, "t1", "PASS", "ri", 409);
    }

    // ---------- 召回代次 ----------

    @Test
    void recallGenerations_reRecallRequiresNewVersionAndNewReinspection() throws Exception {
        String root = "BK-GEN-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        recall(root, "op", "第一次召回", 201);
        reinspect(root, "t1", "PASS", "ri", 201);
        String releaseKey1 = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey1, 1, "措施1", List.of(root), 201);
        approveRelease(root, releaseKey1, "qa-lead", "CK-AR-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(root));

        // 再次召回产生第 2 代；历史第 1 代记录保留为 LIFTED
        recall(root, "op", "第二次召回", 201);
        assertEquals("RECALLED", currentStatus(root));
        JsonNode history = read(mockMvc.perform(get("/api/batches/" + root + "/history"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(2, history.path("recall").path("version").asInt());
        assertEquals("ACTIVE", history.path("recall").path("recallStatus").asText());
        assertEquals(2, countRows("recall", root, "batch_key"));

        // 旧代次申请 → 422；新代次申请 → 201
        applyRelease(root, "mgr", "RK-" + unique(), 1, "措施", List.of(root), 422);
        String releaseKey2 = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey2, 2, "措施2", List.of(root), 201);
        // 第 1 代的合格复检不计入第 2 代 → 批准 422
        approveRelease(root, releaseKey2, "qa-lead", "CK-AR-" + unique(), 422);
        // 重新复检后批准成功
        reinspect(root, "t1", "PASS", "ri", 201);
        approveRelease(root, releaseKey2, "qa-lead", "CK-AR-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(root));
        assertEquals(List.of("LIFTED", "LIFTED"), jdbc.queryForList(
                "SELECT status FROM recall WHERE batch_key = ? ORDER BY version", String.class, root));
    }

    // ---------- 后代自身召回不被根解除覆盖 ----------

    @Test
    void descendantOwnRecall_notLiftedByRootRelease_needsOwnRelease() throws Exception {
        String root = "BK-OWN-P-" + unique();
        String child = "BK-OWN-C-" + unique();
        String child2 = "BK-OWN-C2-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        split(root, List.of(child, child2), 201);
        submitTest(child, "t1", "PASS", "insp-c", 201);
        approve(child, "qa-c", "QUALITY", 201);
        approve(child, "ops-c", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(child));
        // 另一子批完成检验脱离未决隔离，但不放行
        submitTest(child2, "t1", "PASS", "insp-c2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(child2));

        // 根召回后，子批再被直接召回（自身召回记录）
        recall(root, "op", "根召回", 201);
        recall(child, "op", "子批自身召回", 201);
        assertEquals("RECALLED", currentStatus(child));

        // 根解除只覆盖根的第 1 代召回：子批自身召回记录保留且仍为 ACTIVE
        submitTest(child2, "t1", "PASS", "insp-c2", 422); // 祖先召回拦截后代检验
        reinspect(root, "t1", "PASS", "ri", 201);
        reinspect(child, "t1", "PASS", "ri", 201);
        reinspect(child2, "t1", "PASS", "ri", 201);
        String rootRelease = "RK-" + unique();
        applyRelease(root, "mgr", rootRelease, 1, "根纠正措施", List.of(root, child, child2), 201);
        approveRelease(root, rootRelease, "qa-lead", "CK-AR-" + unique(), 201);
        assertEquals("SPLIT", currentStatus(root));
        assertEquals("RECALLED", currentStatus(child), "后代自身召回不被根解除覆盖");
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ?", String.class, child));

        // 后代需各自满足复检并单独解除：根上下文下的复检不计入子批自身召回
        String childRelease = "RK-" + unique();
        applyRelease(child, "mgr", childRelease, 1, "子批纠正措施", List.of(child), 201);
        approveRelease(child, childRelease, "qa-lead", "CK-AR-" + unique(), 422);
        reinspect(child, "t1", "PASS", "ri", 201);
        approveRelease(child, childRelease, "qa-lead", "CK-AR-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(child));
        assertEquals("LIFTED", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ?", String.class, child));
    }

    // ---------- 查询边界 ----------

    @Test
    void impactAndGaps_queryBoundaries() throws Exception {
        String root = "BK-Q-" + unique();
        createBatch(root, List.of("t1", "t2"));
        // 无召回记录：血缘影响 404，复检缺口 409
        mockMvc.perform(get("/api/batches/" + root + "/recall-impact"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/" + root + "/reinspection-gaps"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/batches/NO-SUCH-" + unique() + "/recall-impact"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/" + root + "/recall-releases"))
                .andExpect(status().isOk());

        releaseBatch(root, List.of("t1", "t2"));
        recall(root, "op", "原因", 201);
        reinspect(root, "t1", "PASS", "ri", 201);
        JsonNode impact = read(mockMvc.perform(get("/api/batches/" + root + "/recall-impact"))
                .andExpect(status().isOk()).andReturn());
        assertFalse(impact.path("affected").get(0).path("reinspectionComplete").asBoolean());
        assertEquals(List.of("t2"),
                jsonStrings(impact.path("affected").get(0).path("missingItems")));
        JsonNode gaps = read(mockMvc.perform(get("/api/batches/" + root + "/reinspection-gaps"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(List.of("t2"), jsonStrings(gaps.path("gaps").get(0).path("missingItems")));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentApprove_sameReleaseExactlyOneSnapshot() throws Exception {
        String root = "BK-CAP-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        recall(root, "op", "原因", 201);
        reinspect(root, "t1", "PASS", "ri", 201);
        String releaseKey = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey, 1, "措施", List.of(root), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> approveReleaseStatus(root, releaseKey, "qa-lead", "CK-CA-" + unique()),
                () -> approveReleaseStatus(root, releaseKey, "qa-lead", "CK-CA-" + unique()));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 200) || (first == 200 && second == 201),
                "同 releaseKey 并发批准：一笔创建一笔重放，实际为 " + first + "/" + second);
        assertEquals(1, countRows("recall_release_snapshot", releaseKey, "release_key"));
        assertEquals("LIFTED", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ?", String.class, root));
        assertEquals("RELEASED", currentStatus(root));
    }

    @Test
    void concurrentReinspection_sameItemSameContent_singleRow() throws Exception {
        String root = "BK-CRI-" + unique();
        createBatch(root, List.of("t1"));
        releaseBatch(root, List.of("t1"));
        recall(root, "op", "原因", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> reinspectStatus(root, "t1", "PASS", "ri"),
                () -> reinspectStatus(root, "t1", "PASS", "ri"));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 200) || (first == 200 && second == 201),
                "同项同内容并发复检：一笔创建一笔重放，实际为 " + first + "/" + second);
        assertEquals(1, countRows("reinspection", root, "batch_key"));
    }

    @Test
    void concurrentApproveVsReinspection_commitOrderConsistent() throws Exception {
        String root = "BK-CAI-" + unique();
        createBatch(root, List.of("t1", "t2"));
        releaseBatch(root, List.of("t1", "t2"));
        recall(root, "op", "原因", 201);
        reinspect(root, "t1", "PASS", "ri", 201);
        String releaseKey = "RK-" + unique();
        applyRelease(root, "mgr", releaseKey, 1, "措施", List.of(root), 201);

        // 最后一项复检与批准并发：按提交顺序裁决
        String approveKey = "CK-CAI-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> reinspectStatus(root, "t2", "PASS", "ri"),
                () -> approveReleaseStatus(root, releaseKey, "qa-lead", approveKey));
        int reinspect = results.get(0).get(30, TimeUnit.SECONDS);
        int approve = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, reinspect);
        if (approve == 422) {
            // 批准先裁决：复检未提交故 422 且不占键；补齐后同一 commandKey 重试成功
            assertEquals("RECALLED", currentStatus(root));
            assertEquals(0, countRows("recall_release_snapshot", releaseKey, "release_key"));
            approveRelease(root, releaseKey, "qa-lead", approveKey, 201);
        } else {
            assertEquals(201, approve, "复检先提交则批准通过");
        }
        // 终态一致：召回解除、状态恢复、快照唯一
        assertEquals("RELEASED", currentStatus(root));
        assertEquals("LIFTED", jdbc.queryForObject(
                "SELECT status FROM recall WHERE batch_key = ?", String.class, root));
        assertEquals(1, countRows("recall_release_snapshot", releaseKey, "release_key"));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> jsonStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private int countRows(String table, String value) {
        return countRows(table, value, "command_key");
    }

    private int countRows(String table, String value, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", items);
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void releaseBatch(String batchKey, List<String> items) throws Exception {
        for (String item : items) {
            submitTest(batchKey, item, "PASS", "insp-" + batchKey.substring(batchKey.length() - 4), 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", 201);
    }

    private void submitTest(String batchKey, String item, String outcome, String inspector,
                            int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", "TK-" + unique());
        body.put("testItem", item);
        body.put("result", outcome);
        body.put("inspector", inspector);
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-" + unique() + "\",\"reason\":\""
                                + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private void split(String parent, List<String> childKeys, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", "CK-S-" + unique());
        List<Map<String, String>> children = new ArrayList<>();
        for (String key : childKeys) {
            children.add(Map.of("batchKey", key, "batchNo", "LOT-" + key.substring(key.length() - 4)));
        }
        body.put("children", children);
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    private MvcResult reinspect(String batchKey, String item, String outcome, String inspector,
                                int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/reinspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reinspectionBody(item, outcome, inspector)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int reinspectStatus(String batchKey, String item, String outcome, String inspector) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/reinspections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reinspectionBody(item, outcome, inspector)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String reinspectionBody(String item, String outcome, String inspector) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", "CK-RI-" + unique());
        body.put("testItem", item);
        body.put("outcome", outcome);
        body.put("inspector", inspector);
        return objectMapper.writeValueAsString(body);
    }

    private String applyBody(String commandKey, String releaseKey, int version, String action,
                             List<String> batches) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("releaseKey", releaseKey);
        body.put("recallVersion", version);
        body.put("correctiveAction", action);
        body.put("reinspectionBatches", batches);
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult applyRelease(String batchKey, String actor, String releaseKey, int version,
                                   String action, List<String> batches, int expected)
            throws Exception {
        return applyRelease(batchKey, actor, releaseKey, version, action, batches, expected,
                "CK-RA-" + unique());
    }

    private MvcResult applyRelease(String batchKey, String actor, String releaseKey, int version,
                                   String action, List<String> batches, int expected,
                                   String commandKey) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recall-releases")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(commandKey, releaseKey, version, action, batches)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult approveRelease(String batchKey, String releaseKey, String approver,
                                     String commandKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recall-releases/"
                        + releaseKey + "/approve")
                        .header("X-Actor-Id", approver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private int approveReleaseStatus(String batchKey, String releaseKey, String approver,
                                     String commandKey) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/recall-releases/"
                            + releaseKey + "/approve")
                            .header("X-Actor-Id", approver)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + commandKey + "\"}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        read(result).forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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
