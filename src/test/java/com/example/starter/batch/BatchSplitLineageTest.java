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
 * 批次拆分血缘与祖先召回拦截测试：拆分主流程、失败回滚、commandKey 幂等、
 * 祖先/后代查询、祖先召回对后代的拦截与可用性排除、无关血缘树隔离、并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchSplitLineageTest {

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
    void happySplit_parentBecomesSplit_childrenInheritAndStartQuarantined() throws Exception {
        String parent = "BK-SP-P-" + unique();
        createBatch(parent, List.of("外观", "含量"), 201);
        releaseBatch(parent, "insp-1");

        List<String> childKeys = List.of("BK-SP-C1-" + unique(), "BK-SP-C2-" + unique(),
                "BK-SP-C3-" + unique());
        MvcResult result = split(parent, "CK-SPLIT-1",
                childKeys.stream().map(k -> new String[]{k, "LOT-" + k.substring(k.length() - 4)})
                        .toList(), 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(parent, body.path("parentBatchKey").asText());
        assertEquals("SPLIT", body.path("parentStatus").asText());
        assertEquals(3, body.path("children").size());
        for (int i = 0; i < 3; i++) {
            JsonNode child = body.path("children").get(i);
            assertEquals(childKeys.get(i), child.path("batchKey").asText());
            assertEquals("QUARANTINED", child.path("status").asText());
            assertEquals("PROD-1", child.path("productCode").asText());
            assertEquals("2026-01-02T03:04:05Z", child.path("producedAt").asText());
            assertEquals(List.of("外观", "含量"), objectMapper.convertValue(
                    child.path("requiredTests"), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class)));
        }

        // 父批 SPLIT 不再可用；子批初始隔离、出现在可用列表
        assertEquals("SPLIT", currentStatus(parent));
        List<String> available = availableKeys();
        assertFalse(available.contains(parent));
        assertTrue(available.containsAll(childKeys));

        // 子批不继承检验或批准记录
        for (String childKey : childKeys) {
            MvcResult history = mockMvc.perform(get("/api/batches/" + childKey + "/history"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
            assertEquals(0, node.path("tests").size());
            assertEquals(0, node.path("approvals").size());
            assertEquals("QUARANTINED", node.path("batch").path("status").asText());
        }
        // 父批历史完整保留
        MvcResult parentHistory = mockMvc.perform(get("/api/batches/" + parent + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode parentNode = objectMapper.readTree(parentHistory.getResponse().getContentAsString());
        assertEquals(2, parentNode.path("tests").size());
        assertEquals(2, parentNode.path("approvals").size());

        // 血缘关系落库
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
    }

    @Test
    void childBatch_canBeReleasedAndSplitAgain() throws Exception {
        String parent = "BK-ML-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String child = "BK-ML-C-" + unique();
        split(parent, "CK-S1", List.of(new String[]{child, "L1"},
                new String[]{"BK-ML-C2-" + unique(), "L2"}), 201);

        // 子批重新完成检验与双角色批准后可用
        releaseBatch(child, "insp-2");
        assertEquals("RELEASED", currentStatus(child));
        assertTrue(availableKeys().contains(child));

        // 子批可再次拆分
        String grandChild = "BK-ML-G-" + unique();
        split(child, "CK-S2", List.of(new String[]{grandChild, "G1"},
                new String[]{"BK-ML-G2-" + unique(), "G2"}), 201);
        assertEquals("SPLIT", currentStatus(child));
        assertEquals("QUARANTINED", currentStatus(grandChild));

        // 祖先链：grandChild -> child -> parent
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + grandChild + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        assertEquals(child, ancestorNodes.get(0).path("batchKey").asText());
        assertEquals(parent, ancestorNodes.get(1).path("batchKey").asText());
        assertEquals("SPLIT", ancestorNodes.get(0).path("status").asText());

        // 后代链：parent -> 2 子批 -> child 的 2 子批
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + parent + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descendantNodes = objectMapper.readTree(
                descendants.getResponse().getContentAsString());
        assertEquals(4, descendantNodes.size());
    }

    // ---------- 拆分失败分支 ----------

    @Test
    void splitNonReleasedParent_returns409() throws Exception {
        // QUARANTINED
        String quarantined = "BK-SNP-Q-" + unique();
        createBatch(quarantined, List.of("t1"), 201);
        split(quarantined, "CK-SQ", twoChildren(), 409);

        // PENDING_RELEASE
        String pending = "BK-SNP-P-" + unique();
        createBatch(pending, List.of("t1"), 201);
        submitTest(pending, "t1", "PASS", "insp", 201);
        split(pending, "CK-SP", twoChildren(), 409);

        // REJECTED
        String rejected = "BK-SNP-R-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        split(rejected, "CK-SR", twoChildren(), 409);

        // RECALLED
        String recalled = "BK-SNP-RC-" + unique();
        createBatch(recalled, List.of("t1"), 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "质量问题", "CK-RC", 201);
        split(recalled, "CK-SRC", twoChildren(), 409);

        // 已 SPLIT 的父批不能再次拆分
        String splitParent = "BK-SNP-SP-" + unique();
        createBatch(splitParent, List.of("t1"), 201);
        releaseBatch(splitParent, "insp");
        split(splitParent, "CK-SSP-1", twoChildren(), 201);
        split(splitParent, "CK-SSP-2", twoChildren(), 409);
        assertEquals("SPLIT", currentStatus(splitParent));
    }

    @Test
    void splitChildKeyConflict_returns409_parentUnchanged_failureDoesNotOccupyKey() throws Exception {
        String parent = "BK-SCK-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp");
        String existing = "BK-SCK-EXIST-" + unique();
        createBatch(existing, List.of("t1"), 201);

        // 子批键已存在 → 整次 409，父批不变，不产生任何子批与血缘
        split(parent, "CK-SCK-1",
                List.of(new String[]{existing, "L1"}, new String[]{"BK-SCK-NEW-" + unique(), "L2"}),
                409);
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));

        // 请求内子批键重复 → 409
        String dup = "BK-SCK-DUP-" + unique();
        split(parent, "CK-SCK-2", List.of(new String[]{dup, "L1"}, new String[]{dup, "L2"}), 409);
        assertEquals("RELEASED", currentStatus(parent));

        // 失败不占键：同一 commandKey 修正参数后成功
        String c1 = "BK-SCK-OK1-" + unique();
        String c2 = "BK-SCK-OK2-" + unique();
        split(parent, "CK-SCK-1", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        assertEquals("SPLIT", currentStatus(parent));
        assertEquals("QUARANTINED", currentStatus(c1));
        assertEquals("QUARANTINED", currentStatus(c2));
    }

    @Test
    void splitInvalidParams_return400() throws Exception {
        String parent = "BK-S400-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp");

        // 只有 1 个子批
        split(parent, "CK-400-1", List.<String[]>of(new String[]{"BK-400-A-" + unique(), "L1"}), 400);
        // 6 个子批
        List<String[]> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new String[]{"BK-400-S" + i + "-" + unique(), "L" + i});
        }
        split(parent, "CK-400-2", six, 400);
        // 子批批号为空
        split(parent, "CK-400-3", List.of(new String[]{"BK-400-B1-" + unique(), ""},
                new String[]{"BK-400-B2-" + unique(), "L2"}), 400);
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"children\":[{\"batchKey\":\"BK-400-C1-" + unique()
                                + "\",\"batchNo\":\"L1\"},{\"batchKey\":\"BK-400-C2-" + unique()
                                + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().isBadRequest());
        assertEquals("RELEASED", currentStatus(parent));
    }

    @Test
    void splitUnknownParent_returns404_andLineageQueryUnknownReturns404() throws Exception {
        split("NO-SUCH-PARENT", "CK-404", twoChildren(), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/ancestors"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/descendants"))
                .andExpect(status().isNotFound());
    }

    // ---------- 拆分幂等 ----------

    @Test
    void splitCommandKey_sameParamsReplaysFirstResult_changedParamsConflicts() throws Exception {
        String parent = "BK-SIDEM-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp");
        String c1 = "BK-SIDEM-C1-" + unique();
        String c2 = "BK-SIDEM-C2-" + unique();
        String body = splitBody("CK-SIDEM", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}));

        MvcResult first = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不得再次创建子批
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, c1));

        // 同 commandKey 改参 → 409
        String changed = splitBody("CK-SIDEM",
                List.of(new String[]{c1, "L1"}, new String[]{"BK-SIDEM-C3-" + unique(), "L3"}));
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 祖先召回 ----------

    @Test
    void recallSplitParent_recallSucceeds_andDescendantsBlockedButNotRewritten() throws Exception {
        // 两级树：root(SPLIT) -> child(SPLIT) -> grandChild(PENDING_RELEASE)
        //                  \-> sibling(RELEASED)
        String root = "BK-AR-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-AR-C-" + unique();
        String sibling = "BK-AR-C2-" + unique();
        split(root, "CK-AR-S1", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}),
                201);
        releaseBatch(child, "insp-2");
        String grandChild = "BK-AR-G-" + unique();
        String grandSibling = "BK-AR-G2-" + unique();
        split(child, "CK-AR-S2",
                List.of(new String[]{grandChild, "G1"}, new String[]{grandSibling, "G2"}), 201);
        releaseBatch(sibling, "insp-3");
        submitTest(grandChild, "t1", "PASS", "insp-4", 201);
        assertEquals("PENDING_RELEASE", currentStatus(grandChild));

        // 召回 SPLIT 父批（根）
        recall(root, "qa-lead", "上游原料污染", "CK-AR-R1", 201);
        assertEquals("RECALLED", currentStatus(root));

        // 全部后代立即从可用查询排除
        List<String> available = availableKeys();
        assertFalse(available.contains(child));
        assertFalse(available.contains(sibling));
        assertFalse(available.contains(grandChild));
        assertFalse(available.contains(grandSibling));

        // 后代自身状态：已放行后代在召回同事务内标记为 PENDING_DISPOSAL，
        // 其余后代状态不改写，也不伪造成曾直接召回
        assertEquals("SPLIT", currentStatus(child));
        assertEquals("PENDING_DISPOSAL", currentStatus(sibling));
        assertEquals("PENDING_RELEASE", currentStatus(grandChild));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
        MvcResult childHistory = mockMvc.perform(get("/api/batches/" + child + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(objectMapper.readTree(childHistory.getResponse().getContentAsString())
                .path("recall").isNull());
        MvcResult siblingHistory = mockMvc.perform(get("/api/batches/" + sibling + "/history"))
                .andExpect(status().isOk()).andReturn();
        // 待处置后代自身没有召回记录，血缘与历史不被删除或改写
        assertTrue(objectMapper.readTree(siblingHistory.getResponse().getContentAsString())
                .path("recall").isNull());
        assertEquals(2, objectMapper.readTree(siblingHistory.getResponse().getContentAsString())
                .path("approvals").size());

        // 禁止后代新增检验、批准和拆分 → 422
        submitTest(grandChild, "t1", "PASS", "insp-5", 422);
        approve(grandChild, "qa-x", "QUALITY", "CK-AR-A1", 422);
        split(sibling, "CK-AR-S3", twoChildren(), 422);

        // 后代查询标注导致不可用的召回祖先
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + root + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertEquals(4, nodes.size());
        for (JsonNode node : nodes) {
            assertEquals(root, node.path("unavailableDueToRecalledAncestor").asText());
        }
        // 祖先查询：grandChild 的祖先 child 被根召回阻断，root 自身是直接召回不算祖先召回
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + grandChild + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(child, ancestorNodes.get(0).path("batchKey").asText());
        assertEquals(root, ancestorNodes.get(0).path("unavailableDueToRecalledAncestor").asText());
        assertEquals(root, ancestorNodes.get(1).path("batchKey").asText());
        assertEquals("RECALLED", ancestorNodes.get(1).path("status").asText());
        assertTrue(ancestorNodes.get(1).path("unavailableDueToRecalledAncestor").isNull());
    }

    @Test
    void unrelatedLineageTrees_doNotAffectEachOther() throws Exception {
        // 树 A
        String rootA = "BK-UT-RA-" + unique();
        createBatch(rootA, List.of("t1"), 201);
        releaseBatch(rootA, "insp-a");
        String childA = "BK-UT-CA-" + unique();
        split(rootA, "CK-UT-SA",
                List.of(new String[]{childA, "L1"}, new String[]{"BK-UT-CA2-" + unique(), "L2"}),
                201);
        // 树 B
        String rootB = "BK-UT-RB-" + unique();
        createBatch(rootB, List.of("t1"), 201);
        releaseBatch(rootB, "insp-b");
        String childB = "BK-UT-CB-" + unique();
        split(rootB, "CK-UT-SB",
                List.of(new String[]{childB, "L1"}, new String[]{"BK-UT-CB2-" + unique(), "L2"}),
                201);

        recall(rootA, "u", "树A召回", "CK-UT-RA", 201);

        // 树 B 完全不受影响：可用、可检验、可批准、可拆分
        List<String> available = availableKeys();
        assertFalse(available.contains(childA));
        assertTrue(available.contains(childB));
        submitTest(childB, "t1", "PASS", "insp-b2", 201);
        approve(childB, "qa-b", "QUALITY", "CK-UT-AB1", 201);
        approve(childB, "ops-b", "OPERATIONS", "CK-UT-AB2", 201);
        assertEquals("RELEASED", currentStatus(childB));
        split(childB, "CK-UT-SB2", twoChildren(), 201);
    }

    @Test
    void recallReleasedLeafStillWorks_andRecallNonTerminal409() throws Exception {
        // RELEASED 无后代批次召回行为不变
        String leaf = "BK-RL-L-" + unique();
        createBatch(leaf, List.of("t1"), 201);
        releaseBatch(leaf, "insp");
        recall(leaf, "u", "常规召回", "CK-RL-1", 201);
        assertEquals("RECALLED", currentStatus(leaf));
        assertFalse(availableKeys().contains(leaf));

        // QUARANTINED 子批不可直接召回
        String parent = "BK-RL-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp");
        String child = "BK-RL-C-" + unique();
        split(parent, "CK-RL-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RL-C2-" + unique(), "L2"}), 201);
        recall(child, "u", "提前召回", "CK-RL-2", 409);
        assertEquals("QUARANTINED", currentStatus(child));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentAncestorRecallAndDescendantFinalApproval_commitOrderDecides() throws Exception {
        String root = "BK-CRA-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CRA-C-" + unique();
        split(root, "CK-CRA-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-CRA-C2-" + unique(), "L2"}), 201);
        submitTest(child, "t1", "PASS", "insp-2", 201);
        approve(child, "qa-1", "QUALITY", "CK-CRA-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + child + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRA-A2\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRA-R\",\"reason\":\"根批召回\"}"))
        );

        int finalApproval = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        if (finalApproval == 201) {
            // 批准先提交：子批曾 RELEASED，随后召回在同一事务内将其标记为待处置，不再可用
            assertEquals("PENDING_DISPOSAL", currentStatus(child));
            assertFalse(availableKeys().contains(child));
        } else {
            // 召回先提交：后代新增批准被拦截 422，子批停留 RELEASE_REVIEW
            assertEquals(422, finalApproval);
            assertEquals("RELEASE_REVIEW", currentStatus(child));
        }
        // 无论哪种顺序，都不会出现"批准成功且子批仍可用"
        assertFalse(finalApproval == 201 && availableKeys().contains(child));
    }

    @Test
    void concurrentAncestorRecallAndDescendantSplit_commitOrderDecides() throws Exception {
        String root = "BK-CRS-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CRS-C-" + unique();
        split(root, "CK-CRS-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-CRS-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-2");

        String g1 = "BK-CRS-G1-" + unique();
        String g2 = "BK-CRS-G2-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + child + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-CRS-S2",
                                List.of(new String[]{g1, "G1"}, new String[]{g2, "G2"})))),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRS-R\",\"reason\":\"根批召回\"}"))
        );

        int split = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall);
        if (split == 201) {
            // 拆分先提交：子批 SPLIT，新增后代同样受随后召回影响
            assertEquals("SPLIT", currentStatus(child));
            assertEquals("QUARANTINED", currentStatus(g1));
            List<String> available = availableKeys();
            assertFalse(available.contains(g1));
            assertFalse(available.contains(g2));
            submitTest(g1, "t1", "PASS", "insp-3", 422);
        } else {
            // 召回先提交：拆分被拦截 422，子批停留 RELEASED 但不可用，无新子批
            assertEquals(422, split);
            assertEquals("RELEASED", currentStatus(child));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class, g1, g2));
        }
    }

    @Test
    void concurrentSplitSameCommandKey_sameWinnerForBoth() throws Exception {
        String parent = "BK-CSC-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp");
        String c1 = "BK-CSC-C1-" + unique();
        String c2 = "BK-CSC-C2-" + unique();
        String body = splitBody("CK-CSC", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals("SPLIT", currentStatus(parent));
    }

    @Test
    void recalledAncestor_doesNotBlockReplayOfExistingTestResult() throws Exception {
        String root = "BK-RP-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-RP-C-" + unique();
        split(root, "CK-RP-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RP-C2-" + unique(), "L2"}), 201);

        // 祖先召回前子批已有一条检验结果
        String testKey = "TK-RP-" + unique();
        String body = objectMapper.writeValueAsString(
                new TestCmd("CK-RP-T1", testKey, "t1", "PASS", "insp-2"));
        mockMvc.perform(post("/api/batches/" + child + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        recall(root, "u", "根批召回", "CK-RP-R1", 201);

        // 同 testKey 同内容重放是读取既有结果而非新增检验，不返回 422
        MvcResult replay = mockMvc.perform(post("/api/batches/" + child + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-RP-T2", testKey, "t1", "PASS", "insp-2"))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("PENDING_RELEASE", objectMapper
                .readTree(replay.getResponse().getContentAsString()).path("batchStatus").asText());
        // 但新增检验仍被拦截
        submitTest(child, "t1", "PASS", "insp-3", 422);
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String batchKey, List<String> items) throws Exception {
        return objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey, "PROD-1",
                "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(batchKey, items)))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
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

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private List<String[]> twoChildren() {
        return List.of(new String[]{"BK-CH1-" + unique(), "L1"},
                new String[]{"BK-CH2-" + unique(), "L2"});
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

    private MvcResult split(String parentKey, String commandKey, List<String[]> children,
                            int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
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
