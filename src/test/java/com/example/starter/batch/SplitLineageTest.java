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
 * 批次拆分血缘与祖先召回拦截测试：拆分主流程、失败回滚、commandKey 幂等、
 * 祖先/后代查询、祖先召回对后代的拦截与可用性排除、无关血缘树隔离，以及
 * 拆分/最终批准/祖先召回并发按事务提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SplitLineageTest {

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
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 拆分主流程 ----------

    @Test
    void splitHappyFlow_childrenInheritAndParentBecomesSplit() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1", "t2"));

        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        String c3 = "BK-C3-" + unique();
        MvcResult result = split(parent, "CK-SP-1", 201,
                child(c1, "NO-1"), child(c2, "NO-2"), child(c3, "NO-3"));

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(parent, body.path("parentBatchKey").asText());
        assertEquals("SPLIT", body.path("parentStatus").asText());
        assertEquals(3, body.path("children").size());
        JsonNode first = body.path("children").get(0);
        assertEquals(c1, first.path("batchKey").asText());
        assertEquals("QUARANTINED", first.path("status").asText());
        assertEquals("PROD-1", first.path("productCode").asText(), "子批继承产品编码");
        assertEquals("NO-1", first.path("batchNo").asText(), "子批批号来自请求");
        assertEquals("2026-01-02T03:04:05Z", first.path("producedAt").asText(), "子批继承生产 UTC 时间");
        assertEquals("t1", first.path("requiredTests").get(0).asText());
        assertEquals("t2", first.path("requiredTests").get(1).asText());

        // 父批 SPLIT 不再可用；子批 QUARANTINED 可用
        assertEquals("SPLIT", currentStatus(parent));
        List<String> available = availableKeys();
        assertFalse(available.contains(parent));
        assertTrue(available.contains(c1));
        assertTrue(available.contains(c2));
        assertTrue(available.contains(c3));

        // 子批不继承检验或批准记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key IN (?, ?, ?)",
                Integer.class, c1, c2, c3));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key IN (?, ?, ?)",
                Integer.class, c1, c2, c3));
        // 必做检验项已继承落库
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_required_test WHERE batch_key = ?",
                Integer.class, c1));
        // 血缘关系落库，每个子批一个父批
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, parent));

        // 子批可重新完成检验与双角色批准后放行，并可再次拆分
        releaseExistingBatch(c1, List.of("t1", "t2"));
        assertEquals("RELEASED", currentStatus(c1));
        String g1 = "BK-G1-" + unique();
        String g2 = "BK-G2-" + unique();
        split(c1, "CK-SP-2", 201, child(g1, "G-1"), child(g2, "G-2"));
        assertEquals("SPLIT", currentStatus(c1));
        assertEquals("QUARANTINED", currentStatus(g1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, c1));
    }

    // ---------- 拆分失败分支与回滚 ----------

    @Test
    void splitWithExistingOrDuplicateChildKey_returns409AndRollsBackEntirely() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1"));
        String existing = "BK-EXIST-" + unique();
        createBatch(existing, List.of("x"));

        String n1 = "BK-N1-" + unique();
        String n2 = "BK-N2-" + unique();
        // 第二个子批键已存在 → 整次 409
        split(parent, "CK-SP-F", 409, child(n1, "N-1"), child(existing, "N-2"), child(n2, "N-3"));
        // 父批不变，仍 RELEASED；其余子批未创建；无血缘关系
        assertEquals("RELEASED", currentStatus(parent));
        assertBatchMissing(n1);
        assertBatchMissing(n2);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, parent));
        // 失败不占键：同 commandKey 换全部全新子批后成功
        String m1 = "BK-M1-" + unique();
        String m2 = "BK-M2-" + unique();
        split(parent, "CK-SP-F", 201, child(m1, "M-1"), child(m2, "M-2"));
        assertEquals("SPLIT", currentStatus(parent));

        // 请求内子批键重复 → 409，父批不变
        String parent2 = "BK-P2-" + unique();
        releaseNewBatch(parent2, List.of("t1"));
        String dup = "BK-DUP-" + unique();
        split(parent2, "CK-SP-D", 409, child(dup, "D-1"), child(dup, "D-2"));
        assertEquals("RELEASED", currentStatus(parent2));
        assertBatchMissing(dup);

        // 子批键与父批相同 → 409（父批已存在）
        String parent3 = "BK-P3-" + unique();
        releaseNewBatch(parent3, List.of("t1"));
        split(parent3, "CK-SP-S", 409, child(parent3, "S-1"), child("BK-OTHER-" + unique(), "S-2"));
        assertEquals("RELEASED", currentStatus(parent3));
    }

    @Test
    void splitValidationErrors_return400() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1"));

        // 1 个子批
        split(parent, "CK-V1", 400, child("BK-A-" + unique(), "N"));
        // 6 个子批
        split(parent, "CK-V2", 400, child("a1", "N"), child("a2", "N"), child("a3", "N"),
                child("a4", "N"), child("a5", "N"), child("a6", "N"));
        // 子批键为空
        split(parent, "CK-V3", 400, child("", "N"), child("BK-B-" + unique(), "N"));
        // 子批批号为空
        split(parent, "CK-V4", 400, child("BK-C-" + unique(), ""), child("BK-D-" + unique(), "N"));
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"children\":[{\"batchKey\":\"k1\",\"batchNo\":\"n\"},"
                                + "{\"batchKey\":\"k2\",\"batchNo\":\"n\"}]}"))
                .andExpect(status().isBadRequest());
        assertEquals("RELEASED", currentStatus(parent));
    }

    @Test
    void splitOnNonReleasedParent_returns409() throws Exception {
        // QUARANTINED
        String quarantined = "BK-Q-" + unique();
        createBatch(quarantined, List.of("t1"));
        split(quarantined, "CK-S1", 409, child("BK-1-" + unique(), "N"), child("BK-2-" + unique(), "N"));

        // PENDING_RELEASE
        String pending = "BK-PD-" + unique();
        createBatch(pending, List.of("t1"));
        submitTest(pending, "t1", "PASS", 201);
        split(pending, "CK-S2", 409, child("BK-3-" + unique(), "N"), child("BK-4-" + unique(), "N"));

        // REJECTED
        String rejected = "BK-RJ-" + unique();
        createBatch(rejected, List.of("t1"));
        submitTest(rejected, "t1", "FAIL", 201);
        split(rejected, "CK-S3", 409, child("BK-5-" + unique(), "N"), child("BK-6-" + unique(), "N"));

        // RECALLED
        String recalled = "BK-RC-" + unique();
        releaseNewBatch(recalled, List.of("t1"));
        recall(recalled, "u", "原因", "CK-R-1", 201);
        split(recalled, "CK-S4", 409, child("BK-7-" + unique(), "N"), child("BK-8-" + unique(), "N"));

        // SPLIT（已拆分过的父批不能再次拆分）
        String splitParent = "BK-SP-" + unique();
        releaseNewBatch(splitParent, List.of("t1"));
        split(splitParent, "CK-S5", 201, child("BK-9-" + unique(), "N"), child("BK-10-" + unique(), "N"));
        split(splitParent, "CK-S6", 409, child("BK-11-" + unique(), "N"), child("BK-12-" + unique(), "N"));
    }

    @Test
    void splitUnknownParent_returns404() throws Exception {
        split("NO-SUCH-BATCH", "CK-S404", 404, child("BK-A-" + unique(), "N"),
                child("BK-B-" + unique(), "N"));
    }

    // ---------- 拆分幂等 ----------

    @Test
    void splitCommandKey_replayReturnsFirstResult_changedParamsConflict() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        String body = splitBody("CK-SP-IDEM", child(c1, "N-1"), child(c2, "N-2"));

        MvcResult first = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放未再次创建子批
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, c1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, parent));

        // 同 commandKey 改参 → 409
        String changed = splitBody("CK-SP-IDEM", child(c1, "N-1"), child("BK-OTHER-" + unique(), "N-9"));
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 祖先召回拦截 ----------

    @Test
    void ancestorRecall_blocksDescendantsAndExcludesFromAvailable_unrelatedTreeUnaffected()
            throws Exception {
        // 三代血缘：P → C1 → G1/G2；P → C2
        String p = "BK-P-" + unique();
        releaseNewBatch(p, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        split(p, "CK-SP-1", 201, child(c1, "C-1"), child(c2, "C-2"));
        releaseExistingBatch(c1, List.of("t1"));
        String g1 = "BK-G1-" + unique();
        String g2 = "BK-G2-" + unique();
        split(c1, "CK-SP-2", 201, child(g1, "G-1"), child(g2, "G-2"));
        releaseExistingBatch(g1, List.of("t1"));
        // G2 推进到 PENDING_RELEASE，用于验证批准被拦截
        submitTest(g2, "t1", "PASS", 201);
        assertEquals("PENDING_RELEASE", currentStatus(g2));

        // 无关血缘树
        String other = "BK-OTHER-" + unique();
        releaseNewBatch(other, List.of("t1"));

        // 召回 SPLIT 状态的祖先 P
        recall(p, "qa-lead", "原料供应商批次污染", "CK-R-P", 201);
        assertEquals("RECALLED", currentStatus(p));

        // 全部后代立即从可用查询排除
        List<String> available = availableKeys();
        for (String key : List.of(p, c1, c2, g1, g2)) {
            assertFalse(available.contains(key), key + " 不应出现在可用列表");
        }
        assertTrue(available.contains(other), "无关血缘树不受影响");

        // 后代自身状态不改写、不伪造成曾直接召回
        assertEquals("SPLIT", currentStatus(c1));
        assertEquals("QUARANTINED", currentStatus(c2));
        assertEquals("RELEASED", currentStatus(g1));
        assertEquals("PENDING_RELEASE", currentStatus(g2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
        MvcResult g1History = mockMvc.perform(get("/api/batches/" + g1 + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode g1Node = objectMapper.readTree(g1History.getResponse().getContentAsString());
        assertEquals("RELEASED", g1Node.path("batch").path("status").asText());
        assertTrue(g1Node.path("recall").isNull(), "后代不得伪造召回记录");
        assertEquals(2, g1Node.path("approvals").size(), "后代既有批准记录保留");

        // 禁止后代新增检验、批准和拆分 → 422
        submitTest(c2, "t1", "PASS", 422);
        approve(g2, "qa-x", "QUALITY", 422);
        split(g1, "CK-SP-G1", 422, child("BK-X1-" + unique(), "N"), child("BK-X2-" + unique(), "N"));

        // 无关树仍可正常拆分
        split(other, "CK-SP-O", 201, child("BK-O1-" + unique(), "N"), child("BK-O2-" + unique(), "N"));
    }

    @Test
    void descendantRecallBlocksOnlyItsOwnSubtree() throws Exception {
        String p = "BK-P-" + unique();
        releaseNewBatch(p, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        split(p, "CK-SP-1", 201, child(c1, "C-1"), child(c2, "C-2"));
        releaseExistingBatch(c1, List.of("t1"));
        String g1 = "BK-G1-" + unique();
        String g2 = "BK-G2-" + unique();
        split(c1, "CK-SP-2", 201, child(g1, "G-1"), child(g2, "G-2"));

        // 召回中间层 C1（SPLIT）：仅其子树被拦截
        recall(c1, "u", "子树质量事件", "CK-R-C1", 201);
        submitTest(g1, "t1", "PASS", 422);
        // 兄弟子树 C2 与祖先 P 不受影响
        submitTest(c2, "t1", "PASS", 201);
        assertEquals("PENDING_RELEASE", currentStatus(c2));
        List<String> available = availableKeys();
        assertFalse(available.contains(g1));
        assertFalse(available.contains(g2));
        assertTrue(available.contains(c2));
    }

    // ---------- 祖先 / 后代查询 ----------

    @Test
    void lineageQueries_returnStatusAndRecalledAncestor() throws Exception {
        String p = "BK-P-" + unique();
        releaseNewBatch(p, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        split(p, "CK-SP-1", 201, child(c1, "C-1"), child(c2, "C-2"));
        releaseExistingBatch(c1, List.of("t1"));
        String g1 = "BK-G1-" + unique();
        split(c1, "CK-SP-2", 201, child(g1, "G-1"), child("BK-G2-" + unique(), "G-2"));

        // 召回前：祖先链无召回祖先
        MvcResult before = mockMvc.perform(get("/api/batches/" + g1 + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorsBefore = objectMapper.readTree(before.getResponse().getContentAsString());
        assertEquals(2, ancestorsBefore.size());
        assertEquals(p, ancestorsBefore.get(0).path("batchKey").asText(), "根在前");
        assertEquals("SPLIT", ancestorsBefore.get(0).path("status").asText());
        assertEquals(c1, ancestorsBefore.get(1).path("batchKey").asText());
        assertEquals("SPLIT", ancestorsBefore.get(1).path("status").asText());
        assertTrue(ancestorsBefore.get(0).path("recalledAncestor").isNull());
        assertTrue(ancestorsBefore.get(1).path("recalledAncestor").isNull());

        // 召回 P 后：祖先与后代查询均带召回祖先
        recall(p, "u", "祖先召回", "CK-R-P", 201);
        MvcResult after = mockMvc.perform(get("/api/batches/" + g1 + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorsAfter = objectMapper.readTree(after.getResponse().getContentAsString());
        assertEquals("RECALLED", ancestorsAfter.get(0).path("status").asText());
        assertTrue(ancestorsAfter.get(0).path("recalledAncestor").isNull(),
                "自身被直接召回的批次不回填召回祖先");
        assertEquals(p, ancestorsAfter.get(1).path("recalledAncestor").asText());

        MvcResult desc = mockMvc.perform(get("/api/batches/" + p + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descendants = objectMapper.readTree(desc.getResponse().getContentAsString());
        assertEquals(4, descendants.size());
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        descendants.forEach(n -> byKey.put(n.path("batchKey").asText(), n));
        assertEquals(p, byKey.get(c1).path("recalledAncestor").asText());
        assertEquals(p, byKey.get(c2).path("recalledAncestor").asText());
        assertEquals(p, byKey.get(g1).path("recalledAncestor").asText());
        assertEquals("QUARANTINED", byKey.get(g1).path("status").asText());

        // 根批次无祖先
        MvcResult rootAncestors = mockMvc.perform(get("/api/batches/" + p + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(rootAncestors.getResponse().getContentAsString()).size());

        // 不存在批次 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH/ancestors")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/descendants")).andExpect(status().isNotFound());
    }

    // ---------- 并发：拆分 / 最终批准 / 祖先召回按提交顺序裁决 ----------

    @Test
    void concurrentSplitAndAncestorRecall_commitOrderAdjudicates() throws Exception {
        String p = "BK-P-" + unique();
        releaseNewBatch(p, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        split(p, "CK-SP-1", 201, child(c1, "C-1"), child("BK-C2-" + unique(), "C-2"));
        releaseExistingBatch(c1, List.of("t1"));

        String x1 = "BK-X1-" + unique();
        String x2 = "BK-X2-" + unique();
        String splitBody = splitBody("CK-SP-X", child(x1, "X-1"), child(x2, "X-2"));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + c1 + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody)),
                () -> callStatus(post("/api/batches/" + p + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-P\",\"reason\":\"祖先召回\"}"))
        );
        int splitStatus = results.get(0).get(30, TimeUnit.SECONDS);
        int recallStatus = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recallStatus, "SPLIT 祖先始终可召回");

        if (splitStatus == 422) {
            // 召回先提交：拆分被拦截，子批不存在，C1 仍为 RELEASED 但不可用
            assertEquals("RELEASED", currentStatus(c1));
            assertBatchMissing(x1);
            assertBatchMissing(x2);
        } else {
            // 拆分先提交：新子批已创建，但随后召回使其立即不可用且禁止新增检验
            assertEquals(201, splitStatus);
            assertEquals("SPLIT", currentStatus(c1));
            assertEquals("QUARANTINED", currentStatus(x1));
            submitTest(x1, "t1", "PASS", 422);
        }
        assertFalse(availableKeys().contains(c1));
        assertFalse(availableKeys().contains(x1));
    }

    @Test
    void concurrentFinalApprovalAndAncestorRecall_commitOrderAdjudicates() throws Exception {
        String p = "BK-P-" + unique();
        releaseNewBatch(p, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        split(p, "CK-SP-1", 201, child(c1, "C-1"), child("BK-C2-" + unique(), "C-2"));
        submitTest(c1, "t1", "PASS", 201);
        approve(c1, "qa-1", "QUALITY", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(c1));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + c1 + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A-O\"}")),
                () -> callStatus(post("/api/batches/" + p + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-P\",\"reason\":\"祖先召回\"}"))
        );
        int approvalStatus = results.get(0).get(30, TimeUnit.SECONDS);
        int recallStatus = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recallStatus);

        if (approvalStatus == 422) {
            // 召回先提交：最终批准被拦截，批次停留在 RELEASE_REVIEW
            assertEquals("RELEASE_REVIEW", currentStatus(c1));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, c1));
        } else {
            // 批准先提交：批次 RELEASED，随后召回使其不可用但状态不改写
            assertEquals(201, approvalStatus);
            assertEquals("RELEASED", currentStatus(c1));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, c1));
        }
        assertFalse(availableKeys().contains(c1));
    }

    @Test
    void concurrentSplitsOfSameParent_onlyOneSucceeds() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1"));
        String a1 = "BK-A1-" + unique();
        String a2 = "BK-A2-" + unique();
        String b1 = "BK-B1-" + unique();
        String b2 = "BK-B2-" + unique();
        String bodyA = splitBody("CK-SP-A", child(a1, "A-1"), child(a2, "A-2"));
        String bodyB = splitBody("CK-SP-B", child(b1, "B-1"), child(b2, "B-2"));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "同一父批并发拆分只能成功一次");
        assertTrue(first == 409 || second == 409);
        assertEquals("SPLIT", currentStatus(parent));
        // 只有胜者的子批与血缘落库
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, parent));
        if (first == 201) {
            assertBatchMissing(b1);
            assertBatchMissing(b2);
        } else {
            assertBatchMissing(a1);
            assertBatchMissing(a2);
        }
    }

    @Test
    void concurrentSameSplitCommand_replaysSingleCreation() throws Exception {
        String parent = "BK-P-" + unique();
        releaseNewBatch(parent, List.of("t1"));
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        String body = splitBody("CK-SP-SAME", child(c1, "N-1"), child(c2, "N-2"));

        List<Future<String>> results = runConcurrentBodies(
                () -> callBody(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放返回相同快照");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, c1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, c2));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_batch_key = ?",
                Integer.class, parent));
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String[] child(String batchKey, String batchNo) {
        return new String[]{batchKey, batchNo};
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(),
                                batchKey, "PROD-1", "LOT-1",
                                Instant.parse("2026-01-02T03:04:05Z"), items))))
                .andExpect(status().isCreated());
    }

    private void submitTest(String batchKey, String item, String result, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "commandKey", "CK-T-" + unique(),
                                "testKey", "TK-" + unique(),
                                "testItem", item,
                                "result", result,
                                "inspector", "insp-1"))))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
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

    /**
     * 新建批次并推进到 RELEASED。
     */
    private void releaseNewBatch(String batchKey, List<String> items) throws Exception {
        createBatch(batchKey, items);
        releaseExistingBatch(batchKey, items);
    }

    /**
     * 对已存在批次完成全部必做检验与双角色批准，使其 RELEASED。
     */
    private void releaseExistingBatch(String batchKey, List<String> items) throws Exception {
        for (String item : items) {
            submitTest(batchKey, item, "PASS", 201);
        }
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
    }

    private String splitBody(String commandKey, String[]... children) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        for (String[] c : children) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("batchKey", c[0]);
            entry.put("batchNo", c[1]);
            list.add(entry);
        }
        return objectMapper.writeValueAsString(Map.of("commandKey", commandKey, "children", list));
    }

    private MvcResult split(String parent, String commandKey, int expected, String[]... children)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected))
                .andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private void assertBatchMissing(String batchKey) throws Exception {
        mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isNotFound());
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            return result.getResponse().getStatus() + "|" + result.getResponse().getContentAsString();
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
