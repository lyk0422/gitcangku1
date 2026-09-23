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
 * 多父合批与召回路径追溯测试：合批主流程、失败回滚、commandKey 幂等（父批集合顺序无关）、
 * MERGED 父批召回、多父 DAG 祖先/后代查询与召回祖先列表、并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeLineageTest {

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
        jdbc.update("DELETE FROM batch_merge_parent");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 合批主流程 ----------

    @Test
    void happyMerge_parentsBecomeMerged_newBatchQuarantinedWithLatestProducedAt() throws Exception {
        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        String p3 = "BK-MG-P3-" + unique();
        createBatch(p1, List.of("外观", "含量"), "2026-01-02T03:04:05Z", 201);
        createBatch(p2, List.of("外观", "含量"), "2026-03-04T05:06:07Z", 201);
        createBatch(p3, List.of("含量", "外观"), "2026-02-03T04:05:06Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        releaseBatch(p3, "insp-3");

        String merged = "BK-MG-NEW-" + unique();
        // 父批集合乱序传入：顺序不影响同参与结果
        MvcResult result = merge("CK-MG-1", merged, "LOT-M1",
                List.of(p3, p1, p2), 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(merged, body.path("batchKey").asText());
        assertEquals("LOT-M1", body.path("batchNo").asText());
        assertEquals("PROD-1", body.path("productCode").asText());
        // 生产 UTC 时间取父批最晚值
        assertEquals("2026-03-04T05:06:07Z", body.path("producedAt").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals("MERGED", body.path("parentStatus").asText());
        // 父批键按 batchKey 升序返回
        List<String> parents = textList(body.path("parentBatchKeys"));
        assertEquals(List.of(p1, p2, p3), parents);
        // 必做检验项集合相同（p3 顺序不同仍视为同集合）
        List<String> required = textList(body.path("requiredTests"));
        assertEquals(2, required.size());
        assertTrue(required.containsAll(List.of("外观", "含量")));

        // 父批全部 MERGED 并退出可用集合；新批 QUARANTINED 进入可用集合
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        assertEquals("MERGED", currentStatus(p3));
        List<String> available = availableKeys();
        assertFalse(available.contains(p1));
        assertFalse(available.contains(p2));
        assertFalse(available.contains(p3));
        assertTrue(available.contains(merged));

        // 新批不继承检验和批准
        MvcResult history = mockMvc.perform(get("/api/batches/" + merged + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(0, node.path("tests").size());
        assertEquals(0, node.path("approvals").size());
        assertTrue(node.path("recall").isNull());

        // 父批历史完整保留
        MvcResult parentHistory = mockMvc.perform(get("/api/batches/" + p1 + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode parentNode = objectMapper.readTree(parentHistory.getResponse().getContentAsString());
        assertEquals(2, parentNode.path("tests").size());
        assertEquals(2, parentNode.path("approvals").size());

        // 合批血缘落库：3 条父边
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?",
                Integer.class, merged));
    }

    @Test
    void mergedBatch_canBeReleasedAndSplitOrMergedAgain() throws Exception {
        String p1 = "BK-MC-P1-" + unique();
        String p2 = "BK-MC-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-02T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String merged = "BK-MC-M-" + unique();
        merge("CK-MC-1", merged, "L-M", List.of(p1, p2), 201);

        // 新批重新检验、双角色批准后 RELEASED
        releaseBatch(merged, "insp-3");
        assertEquals("RELEASED", currentStatus(merged));
        assertTrue(availableKeys().contains(merged));

        // 合批新批可继续拆分
        String c1 = "BK-MC-C1-" + unique();
        String c2 = "BK-MC-C2-" + unique();
        split(merged, "CK-MC-S1", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        assertEquals("SPLIT", currentStatus(merged));
        assertEquals("QUARANTINED", currentStatus(c1));

        // 拆分子批放行后可再次合批
        String p3 = "BK-MC-P3-" + unique();
        createBatch(p3, List.of("t1"), "2026-01-03T00:00:00Z", 201);
        releaseBatch(p3, "insp-4");
        releaseBatch(c1, "insp-5");
        String merged2 = "BK-MC-M2-" + unique();
        merge("CK-MC-2", merged2, "L-M2", List.of(c1, p3), 201);
        assertEquals("MERGED", currentStatus(c1));
        assertEquals("MERGED", currentStatus(p3));
        assertEquals("QUARANTINED", currentStatus(merged2));

        // 多父 DAG：merged2 的祖先含 c1、p3 及 c1 的全部祖先（merged、p1、p2），按 batchKey 升序去重
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + merged2 + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        List<String> ancestorKeys = textList(objectMapper
                .readTree(ancestors.getResponse().getContentAsString()), "batchKey");
        List<String> expected = List.of(c1, merged, p1, p2, p3).stream().sorted().toList();
        assertEquals(expected, ancestorKeys);
    }

    @Test
    void splitSiblings_canBeMergedBack() throws Exception {
        String root = "BK-SB-R-" + unique();
        createBatch(root, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(root, "insp-1");
        String c1 = "BK-SB-C1-" + unique();
        String c2 = "BK-SB-C2-" + unique();
        split(root, "CK-SB-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        releaseBatch(c1, "insp-2");
        releaseBatch(c2, "insp-3");

        // 拆分后的兄弟批次重新合批
        String merged = "BK-SB-M-" + unique();
        merge("CK-SB-M", merged, "L-M", List.of(c1, c2), 201);
        assertEquals("MERGED", currentStatus(c1));
        assertEquals("MERGED", currentStatus(c2));

        // 祖先链：merged -> {c1, c2} -> root，共享祖先 root 只显示一次
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + merged + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        List<String> ancestorKeys = textList(objectMapper
                .readTree(ancestors.getResponse().getContentAsString()), "batchKey");
        List<String> expected = List.of(c1, c2, root).stream().sorted().toList();
        assertEquals(expected, ancestorKeys);

        // 后代查询：root 的后代含 c1、c2、merged，去重后按 batchKey 升序
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + root + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        List<String> descendantKeys = textList(objectMapper
                .readTree(descendants.getResponse().getContentAsString()), "batchKey");
        List<String> expectedDesc = List.of(c1, c2, merged).stream().sorted().toList();
        assertEquals(expectedDesc, descendantKeys);
    }

    // ---------- 合批失败分支 ----------

    @Test
    void mergeInvalidParams_return400() throws Exception {
        String p1 = "BK-M400-P1-" + unique();
        String p2 = "BK-M400-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        // 只有 1 个父批
        merge("CK-M400-1", "BK-M400-N1-" + unique(), "L1", List.of(p1), 400);
        // 6 个父批
        List<String> six = new ArrayList<>(List.of(p1, p2));
        for (int i = 0; i < 4; i++) {
            six.add("BK-M400-X" + i + "-" + unique());
        }
        merge("CK-M400-2", "BK-M400-N2-" + unique(), "L2", six, 400);
        // 新批批号为空
        merge("CK-M400-3", "BK-M400-N3-" + unique(), "", List.of(p1, p2), 400);
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchKey\":\"BK-M400-N4-" + unique()
                                + "\",\"batchNo\":\"L4\",\"parentBatchKeys\":[\"" + p1
                                + "\",\"" + p2 + "\"]}"))
                .andExpect(status().isBadRequest());
        // 父批键为空
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-M400-5\",\"batchKey\":\"BK-M400-N5-"
                                + unique() + "\",\"batchNo\":\"L5\",\"parentBatchKeys\":[\""
                                + p1 + "\",\"\"]}"))
                .andExpect(status().isBadRequest());
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
    }

    @Test
    void mergeUnknownParent_returns404() throws Exception {
        String p1 = "BK-M404-P1-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        merge("CK-M404", "BK-M404-N-" + unique(), "L1",
                List.of(p1, "NO-SUCH-PARENT"), 404);
        assertEquals("RELEASED", currentStatus(p1));
    }

    @Test
    void mergeNonReleasedParent_returns409_andRollsBackEverything() throws Exception {
        String released = "BK-MNR-R-" + unique();
        String quarantined = "BK-MNR-Q-" + unique();
        createBatch(released, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(quarantined, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(released, "insp-1");

        String newKey = "BK-MNR-N-" + unique();
        merge("CK-MNR-1", newKey, "L1", List.of(released, quarantined), 409);

        // 全部回滚：可用父批状态不变，无新批、无合批边
        assertEquals("RELEASED", currentStatus(released));
        assertEquals("QUARANTINED", currentStatus(quarantined));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, newKey));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                Integer.class));

        // REJECTED 父批同样不可用
        String rejected = "BK-MNR-RJ-" + unique();
        createBatch(rejected, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        merge("CK-MNR-2", "BK-MNR-N2-" + unique(), "L2", List.of(released, rejected), 409);
        assertEquals("RELEASED", currentStatus(released));

        // RECALLED 父批不可用
        String recalled = "BK-MNR-RC-" + unique();
        createBatch(recalled, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "质量问题", "CK-MNR-RC", 201);
        merge("CK-MNR-3", "BK-MNR-N3-" + unique(), "L3", List.of(released, recalled), 409);
        assertEquals("RELEASED", currentStatus(released));
    }

    @Test
    void mergeProductOrRequiredTestsMismatch_returns409() throws Exception {
        String p1 = "BK-MM-P1-" + unique();
        String p2 = "BK-MM-P2-" + unique();
        createBatch(p1, "PROD-1", List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, "PROD-2", List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        // 产品编码不一致
        merge("CK-MM-1", "BK-MM-N1-" + unique(), "L1", List.of(p1, p2), 409);
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));

        // 必做检验项集合不一致
        String p3 = "BK-MM-P3-" + unique();
        createBatch(p3, "PROD-1", List.of("t1", "t2"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p3, "insp-3");
        merge("CK-MM-2", "BK-MM-N2-" + unique(), "L2", List.of(p1, p3), 409);
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p3));
    }

    @Test
    void mergeDuplicateParentOrExistingNewKey_returns409_failureDoesNotOccupyKey() throws Exception {
        String p1 = "BK-MDP-P1-" + unique();
        String p2 = "BK-MDP-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        // 请求内父批重复
        merge("CK-MDP-1", "BK-MDP-N1-" + unique(), "L1", List.of(p1, p1), 409);
        assertEquals("RELEASED", currentStatus(p1));

        // 新批键已存在
        merge("CK-MDP-2", p1, "L2", List.of(p1, p2), 409);
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                Integer.class));

        // 失败不占键：同一 commandKey 修正参数后成功
        String newKey = "BK-MDP-OK-" + unique();
        merge("CK-MDP-2", newKey, "L-OK", List.of(p1, p2), 201);
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        assertEquals("QUARANTINED", currentStatus(newKey));
    }

    @Test
    void mergeParentWithRecalledAncestor_returns422() throws Exception {
        String root = "BK-MRA-R-" + unique();
        createBatch(root, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(root, "insp-1");
        String child = "BK-MRA-C-" + unique();
        split(root, "CK-MRA-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-MRA-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-2");
        String other = "BK-MRA-O-" + unique();
        createBatch(other, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(other, "insp-3");

        recall(root, "u", "根批召回", "CK-MRA-R1", 201);

        // 父批存在已召回祖先：禁止合批，422
        merge("CK-MRA-M", "BK-MRA-N-" + unique(), "L1", List.of(child, other), 422);
        assertEquals("RELEASED", currentStatus(child));
        assertEquals("RELEASED", currentStatus(other));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                Integer.class));
    }

    // ---------- 合批幂等 ----------

    @Test
    void mergeCommandKey_sameParamsAnyOrderReplays_changedParamsConflicts() throws Exception {
        String p1 = "BK-MID-P1-" + unique();
        String p2 = "BK-MID-P2-" + unique();
        String p3 = "BK-MID-P3-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p3, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        releaseBatch(p3, "insp-3");
        String merged = "BK-MID-M-" + unique();

        MvcResult first = merge("CK-MID", merged, "L-M", List.of(p1, p2), 201);
        // 同键同参（父批集合顺序不同）重放首次结果
        MvcResult replay = merge("CK-MID", merged, "L-M", List.of(p2, p1), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不得重复落边
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?",
                Integer.class, merged));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, merged));

        // 同键改参（换父批集合）→ 409
        merge("CK-MID", merged, "L-M", List.of(p1, p3), 409);
        // 同键改参（换新批键）→ 409
        merge("CK-MID", "BK-MID-M2-" + unique(), "L-M", List.of(p1, p2), 409);
    }

    // ---------- MERGED 父批召回与路径追溯 ----------

    @Test
    void recallMergedParent_descendantsBlocked_andAllRecalledAncestorsListed() throws Exception {
        String p1 = "BK-RMP-P1-" + unique();
        String p2 = "BK-RMP-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String merged = "BK-RMP-M-" + unique();
        merge("CK-RMP-1", merged, "L-M", List.of(p1, p2), 201);
        submitTest(merged, "t1", "PASS", "insp-3", 201);
        assertEquals("PENDING_RELEASE", currentStatus(merged));

        // MERGED 父批允许按原接口召回
        recall(p1, "qa-lead", "原料污染", "CK-RMP-R1", 201);
        assertEquals("RECALLED", currentStatus(p1));

        // 后代立即不可用：退出可用集合，禁止新增检验、批准、拆分、合批
        assertFalse(availableKeys().contains(merged));
        submitTest(merged, "t1", "PASS", "insp-4", 422);
        approve(merged, "qa-x", "QUALITY", "CK-RMP-A1", 422);
        split(merged, "CK-RMP-S1", twoChildren(), 422);
        String other = "BK-RMP-O-" + unique();
        createBatch(other, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(other, "insp-5");
        merge("CK-RMP-M2", "BK-RMP-N-" + unique(), "L-N", List.of(merged, other), 422);

        // 不伪造后代自身状态
        assertEquals("PENDING_RELEASE", currentStatus(merged));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));

        // 召回一条路径不能被另一条未召回路径抵消：p2 未召回，merged 仍不可用
        assertFalse(availableKeys().contains(merged));

        // 祖先查询：p1、p2 均为合批父批；自身被直接召回不算祖先召回，
        // 两者自身都没有召回祖先，不可用祖先列表均为空
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + merged + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        List<String> ancestorKeys = textList(ancestorNodes, "batchKey");
        assertEquals(List.of(p1, p2).stream().sorted().toList(), ancestorKeys);
        for (JsonNode node : ancestorNodes) {
            assertTrue(node.path("unavailableDueToRecalledAncestor").isNull());
            assertEquals(0, node.path("unavailableDueToRecalledAncestors").size());
            if (node.path("batchKey").asText().equals(p1)) {
                assertEquals("RECALLED", node.path("status").asText());
            } else {
                assertEquals("MERGED", node.path("status").asText());
            }
        }

        // 再召回 p2：merged 的不可用祖先列表含两个，按 batchKey 升序
        recall(p2, "qa-lead", "追加召回", "CK-RMP-R2", 201);
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + p1 + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descNodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertEquals(1, descNodes.size());
        JsonNode mergedNode = descNodes.get(0);
        assertEquals(merged, mergedNode.path("batchKey").asText());
        List<String> expectedRecalled = List.of(p1, p2).stream().sorted().toList();
        assertEquals(expectedRecalled,
                textList(mergedNode.path("unavailableDueToRecalledAncestors")));
        assertEquals(expectedRecalled.get(0),
                mergedNode.path("unavailableDueToRecalledAncestor").asText());
    }

    @Test
    void sharedAncestorRecalled_allPathsDescendantsUnavailable() throws Exception {
        // 菱形 DAG：root 拆出 c1、c2，c1 与 c2 合批为 merged
        String root = "BK-SAR-R-" + unique();
        createBatch(root, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(root, "insp-1");
        String c1 = "BK-SAR-C1-" + unique();
        String c2 = "BK-SAR-C2-" + unique();
        split(root, "CK-SAR-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        releaseBatch(c1, "insp-2");
        releaseBatch(c2, "insp-3");
        String merged = "BK-SAR-M-" + unique();
        merge("CK-SAR-M", merged, "L-M", List.of(c1, c2), 201);

        // 共享祖先 root 召回：经两条路径传导，merged 不可用
        recall(root, "u", "根批召回", "CK-SAR-R1", 201);
        assertFalse(availableKeys().contains(merged));
        submitTest(merged, "t1", "PASS", "insp-4", 422);

        // merged 的祖先查询：c1、c2、root 各一次（共享祖先不重复），按 batchKey 升序
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + merged + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        List<String> keys = textList(nodes, "batchKey");
        assertEquals(List.of(c1, c2, root).stream().sorted().toList(), keys);
        // c1、c2 的不可用祖先是 root；root 自身被直接召回不算祖先召回
        for (JsonNode node : nodes) {
            if (node.path("batchKey").asText().equals(root)) {
                assertEquals(0, node.path("unavailableDueToRecalledAncestors").size());
            } else {
                assertEquals(List.of(root),
                        textList(node.path("unavailableDueToRecalledAncestors")));
            }
        }
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergesSharingParent_onlyOneConsumesParent() throws Exception {
        String shared = "BK-CMS-S-" + unique();
        String other1 = "BK-CMS-O1-" + unique();
        String other2 = "BK-CMS-O2-" + unique();
        createBatch(shared, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(other1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(other2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(shared, "insp-1");
        releaseBatch(other1, "insp-2");
        releaseBatch(other2, "insp-3");
        String m1 = "BK-CMS-M1-" + unique();
        String m2 = "BK-CMS-M2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> mergeStatus("CK-CMS-1", m1, "L1", List.of(shared, other1)),
                () -> mergeStatus("CK-CMS-2", m2, "L2", List.of(shared, other2))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);

        // 父批只能被消费一次：恰一个 201，另一个 409
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "共享父批的两次合批只能成功一次");
        assertEquals(1, (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0));
        assertEquals("MERGED", currentStatus(shared));

        // 失败不残留子批或部分边
        String loserChild = first == 201 ? m2 : m1;
        String winnerChild = first == 201 ? m1 : m2;
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, loserChild));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?",
                Integer.class, winnerChild));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                Integer.class));
    }

    @Test
    void concurrentMergeAndParentRecall_commitOrderDecides() throws Exception {
        String p1 = "BK-CMR-P1-" + unique();
        String p2 = "BK-CMR-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String merged = "BK-CMR-M-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> mergeStatus("CK-CMR-M", merged, "L-M", List.of(p1, p2)),
                () -> callStatus(post("/api/batches/" + p1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CMR-R\",\"reason\":\"召回\"}"))
        );
        int merge = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);

        if (merge == 201) {
            // 合批先提交：父批 MERGED，随后召回 MERGED 父批成功
            assertEquals(201, recall);
            assertEquals("RECALLED", currentStatus(p1));
            assertEquals("MERGED", currentStatus(p2));
            // 新批因召回祖先不可用
            assertFalse(availableKeys().contains(merged));
            submitTest(merged, "t1", "PASS", "insp-9", 422);
        } else {
            // 召回先提交：父批 RECALLED 不可用，合批 409 且无残留
            assertEquals(409, merge);
            assertEquals(201, recall);
            assertEquals("RECALLED", currentStatus(p1));
            assertEquals("RELEASED", currentStatus(p2));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, merged));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                    Integer.class));
        }
    }

    @Test
    void concurrentMergeAndSplitOnSameParent_commitOrderDecides() throws Exception {
        String parent = "BK-CMSP-P-" + unique();
        String other = "BK-CMSP-O-" + unique();
        createBatch(parent, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(other, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(parent, "insp-1");
        releaseBatch(other, "insp-2");
        String merged = "BK-CMSP-M-" + unique();
        String s1 = "BK-CMSP-S1-" + unique();
        String s2 = "BK-CMSP-S2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> mergeStatus("CK-CMSP-M", merged, "L-M", List.of(parent, other)),
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-CMSP-S",
                                List.of(new String[]{s1, "L1"}, new String[]{s2, "L2"}))))
        );
        int merge = results.get(0).get(30, TimeUnit.SECONDS);
        int split = results.get(1).get(30, TimeUnit.SECONDS);

        // 父批只能被消费一次：恰一个成功
        assertEquals(1, (merge == 201 ? 1 : 0) + (split == 201 ? 1 : 0));
        if (merge == 201) {
            assertEquals(409, split);
            assertEquals("MERGED", currentStatus(parent));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)",
                    Integer.class, s1, s2));
        } else {
            assertEquals(409, merge);
            assertEquals(201, split);
            assertEquals("SPLIT", currentStatus(parent));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, merged));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent",
                    Integer.class));
        }
    }

    @Test
    void concurrentMergeSameCommandKey_sameWinnerForBoth() throws Exception {
        String p1 = "BK-CMK-P1-" + unique();
        String p2 = "BK-CMK-P2-" + unique();
        createBatch(p1, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        createBatch(p2, List.of("t1"), "2026-01-01T00:00:00Z", 201);
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String merged = "BK-CMK-M-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> mergeStatus("CK-CMK", merged, "L-M", List.of(p1, p2)),
                () -> mergeStatus("CK-CMK", merged, "L-M", List.of(p2, p1))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?",
                Integer.class, merged));
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, String producedAt, int expected)
            throws Exception {
        createBatch(batchKey, "PROD-1", items, producedAt, expected);
    }

    private void createBatch(String batchKey, String productCode, List<String> items,
                             String producedAt, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCmd("CK-C-" + unique(), batchKey, productCode, "LOT-1",
                                        Instant.parse(producedAt), items))))
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

    private String mergeBody(String commandKey, String batchKey, String batchNo,
                             List<String> parentKeys) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"batchKey\":\"").append(batchKey)
                .append("\",\"batchNo\":\"").append(batchNo)
                .append("\",\"parentBatchKeys\":[");
        for (int i = 0; i < parentKeys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parentKeys.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private MvcResult merge(String commandKey, String batchKey, String batchNo,
                            List<String> parentKeys, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, batchKey, batchNo, parentKeys)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int mergeStatus(String commandKey, String batchKey, String batchNo,
                            List<String> parentKeys) {
        try {
            return mockMvc.perform(post("/api/batches/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mergeBody(commandKey, batchKey, batchNo, parentKeys)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private List<String> textList(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.path(field).asText()));
        return values;
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
