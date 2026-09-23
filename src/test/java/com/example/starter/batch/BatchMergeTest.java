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
 * 多父合批与多路径召回追溯测试：合批主流程、菱形 DAG（兄弟重新合批）、失败回滚、
 * commandKey 幂等（集合顺序无关、异参 409、失败不占键）、多路径召回祖先全部列出且不可抵消、
 * MERGED 父批召回、共享父批并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeTest {

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

    // ---------- 合批主流程 ----------

    @Test
    void happyMerge_parentsMerged_newBatchQuarantinedWithLatestProducedAt() throws Exception {
        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        String p3 = "BK-MG-P3-" + unique();
        createBatch(p1, "PROD-X", "2026-01-01T00:00:00Z", List.of("外观", "含量"));
        createBatch(p2, "PROD-X", "2026-03-01T00:00:00Z", List.of("含量", "外观"));
        createBatch(p3, "PROD-X", "2026-02-01T00:00:00Z", List.of("外观", "含量"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        releaseBatch(p3, "insp-3");

        String newKey = "BK-MG-NEW-" + unique();
        // 请求顺序故意与排序不同，验证集合顺序无关
        MvcResult result = merge(newKey, "LOT-MG", List.of(p3, p1, p2), "CK-MG-1", 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("MERGED", body.path("parentStatus").asText());
        // 响应中的父批键按升序去重排列
        assertEquals(List.of(p1, p2, p3), objectMapper.convertValue(body.path("parentBatchKeys"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        JsonNode child = body.path("child");
        assertEquals(newKey, child.path("batchKey").asText());
        assertEquals("LOT-MG", child.path("batchNo").asText());
        assertEquals("PROD-X", child.path("productCode").asText());
        assertEquals("QUARANTINED", child.path("status").asText());
        // 生产 UTC 时间取父批最晚值
        assertEquals("2026-03-01T00:00:00Z", child.path("producedAt").asText());
        assertEquals(List.of("外观", "含量"), objectMapper.convertValue(child.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 父批全部 MERGED 并退出可用集合，新批出现在可用集合
        for (String p : List.of(p1, p2, p3)) {
            assertEquals("MERGED", currentStatus(p));
            assertFalse(availableKeys().contains(p));
        }
        assertTrue(availableKeys().contains(newKey));

        // 不继承检验和批准
        MvcResult history = mockMvc.perform(get("/api/batches/" + newKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(0, node.path("tests").size());
        assertEquals(0, node.path("approvals").size());

        // 3 条 MERGE 血缘边落库
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ? AND relation_kind = 'MERGE'",
                Integer.class, newKey));
    }

    @Test
    void mergedChild_canBeReTestedApprovedAndMergedAgain() throws Exception {
        String p1 = "BK-RM-P1-" + unique();
        String p2 = "BK-RM-P2-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String child = "BK-RM-C-" + unique();
        merge(child, "L1", List.of(p1, p2), "CK-RM-1", 201);

        // 新批重新检验、批准后 RELEASED
        releaseBatch(child, "insp-3");
        assertEquals("RELEASED", currentStatus(child));

        // 与另一个 RELEASED 批次继续合批
        String p3 = "BK-RM-P3-" + unique();
        createBatch(p3, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p3, "insp-4");
        String grand = "BK-RM-G-" + unique();
        merge(grand, "L2", List.of(child, p3), "CK-RM-2", 201);
        assertEquals("MERGED", currentStatus(child));
        assertEquals("MERGED", currentStatus(p3));
        assertEquals("QUARANTINED", currentStatus(grand));

        // grand 的祖先经多路径去重：child, p1, p2, p3
        List<String> ancestors = ancestorKeys(grand);
        assertEquals(List.of(child, p1, p2, p3).stream().sorted().toList(), ancestors);
    }

    @Test
    void splitSiblings_canMergeBack_intoDiamondDag() throws Exception {
        // root 拆成 a、b，a、b 放行后重新合批为 m：m 经两条路径共享祖先 root
        String root = "BK-DM-R-" + unique();
        createBatch(root, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(root, "insp-1");
        String a = "BK-DM-A-" + unique();
        String b = "BK-DM-B-" + unique();
        split(root, "CK-DM-S", List.of(new String[]{a, "LA"}, new String[]{b, "LB"}), 201);
        releaseBatch(a, "insp-2");
        releaseBatch(b, "insp-3");
        String m = "BK-DM-M-" + unique();
        merge(m, "LM", List.of(a, b), "CK-DM-MG", 201);

        // m 的祖先按 batchKey 排序去重：root 共享只显示一次
        List<String> ancestors = ancestorKeys(m);
        assertEquals(List.of(root, a, b).stream().sorted().toList(), ancestors);
        // root 的后代：a、b、m 去重（不含自身）
        List<String> descendants = descendantKeys(root);
        assertEquals(List.of(a, b, m).stream().sorted().toList(), descendants);

        // 召回 root：两条路径同时被污染，m 不可用，召回祖先在 a/b 条目上只列一次
        recall(root, "u", "根批污染", "CK-DM-R", 201);
        assertFalse(availableKeys().contains(m));
        JsonNode aEntry = lineageEntry("/api/batches/" + m + "/ancestors", a);
        assertEquals(List.of(root), objectMapper.convertValue(
                aEntry.path("recalledAncestors"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(root, aEntry.path("unavailableDueToRecalledAncestor").asText());
        // root 自身是直接召回，其条目不伪造祖先召回
        JsonNode rootEntry = lineageEntry("/api/batches/" + m + "/ancestors", root);
        assertEquals(0, rootEntry.path("recalledAncestors").size());
        assertTrue(rootEntry.path("unavailableDueToRecalledAncestor").isNull());

        // m 禁止新增检验、批准、拆分和合批
        submitTest(m, "t1", "PASS", "insp-4", 422);
        approve(m, "qa-x", "QUALITY", "CK-DM-A", 422);
        split(m, "CK-DM-S2", twoChildren(), 422);
        String other = "BK-DM-O-" + unique();
        createBatch(other, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(other, "insp-5");
        // m 已不可用且仍为 QUARANTINED，作为合批父批应被拦截
        merge("BK-DM-X-" + unique(), "LX", List.of(m, other), "CK-DM-MG2", 422);
        // m 自身状态不被伪造
        assertEquals("QUARANTINED", currentStatus(m));
    }

    // ---------- 失败分支与回滚 ----------

    @Test
    void mergeInvalidParams_return400() throws Exception {
        String p1 = "BK-400-P1-" + unique();
        String p2 = "BK-400-P2-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        // 只有 1 个父批
        mergeRaw("BK-400-N1-" + unique(), "L", List.of(p1), "CK-400-1", 400);
        // 6 个父批
        List<String> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String k = "BK-400-S" + i + "-" + unique();
            createBatch(k, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
            releaseBatch(k, "insp-" + i);
            six.add(k);
        }
        mergeRaw("BK-400-N2-" + unique(), "L", six, "CK-400-2", 400);
        // 重复父批
        mergeRaw("BK-400-N3-" + unique(), "L", List.of(p1, p1), "CK-400-3", 400);
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newBatchKey\":\"BK-400-N4-" + unique()
                                + "\",\"newBatchNo\":\"L\",\"parentBatchKeys\":[\"" + p1 + "\",\""
                                + p2 + "\"]}"))
                .andExpect(status().isBadRequest());
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
    }

    @Test
    void mergeUnknownParent_returns404() throws Exception {
        String p1 = "BK-404-P1-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        merge("BK-404-N-" + unique(), "L", List.of(p1, "NO-SUCH-PARENT"), "CK-404-1", 404);
        assertEquals("RELEASED", currentStatus(p1));
    }

    @Test
    void mergeNonReleasedParents_returns409_andRollsBack() throws Exception {
        String released = "BK-NR-R-" + unique();
        String quarantined = "BK-NR-Q-" + unique();
        createBatch(released, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(quarantined, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(released, "insp-1");

        String newKey = "BK-NR-N-" + unique();
        merge(newKey, "L", List.of(released, quarantined), "CK-NR-1", 409);
        // 整笔回滚：父批状态不变、无新批、无边
        assertEquals("RELEASED", currentStatus(released));
        assertEquals("QUARANTINED", currentStatus(quarantined));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, newKey));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));

        // 已 MERGED 的父批不能再次参与合批
        String p2 = "BK-NR-P2-" + unique();
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p2, "insp-2");
        String m = "BK-NR-M-" + unique();
        merge(m, "L", List.of(released, p2), "CK-NR-2", 201);
        String p3 = "BK-NR-P3-" + unique();
        createBatch(p3, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p3, "insp-3");
        merge("BK-NR-M2-" + unique(), "L", List.of(released, p3), "CK-NR-3", 409);
        assertEquals("MERGED", currentStatus(released));
        assertEquals("RELEASED", currentStatus(p3));

        // 失败不占键：同一 commandKey 换合法参数成功
        String p4 = "BK-NR-P4-" + unique();
        String p5 = "BK-NR-P5-" + unique();
        createBatch(p4, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p5, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p4, "insp-4");
        releaseBatch(p5, "insp-5");
        merge("BK-NR-OK-" + unique(), "L", List.of(p4, p5), "CK-NR-1", 201);
    }

    @Test
    void mergeMismatchedProductOrTests_returns422_andRollsBack() throws Exception {
        String a = "BK-MM-A-" + unique();
        String b = "BK-MM-B-" + unique();
        String c = "BK-MM-C-" + unique();
        createBatch(a, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(b, "PROD-2", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(c, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1", "t2"));
        releaseBatch(a, "insp-1");
        releaseBatch(b, "insp-2");
        releaseBatch(c, "insp-3");

        merge("BK-MM-N1-" + unique(), "L", List.of(a, b), "CK-MM-1", 422);
        // 检验项集合不同（顺序差异不算冲突，数量/内容不同才算）
        merge("BK-MM-N2-" + unique(), "L", List.of(a, c), "CK-MM-2", 422);
        for (String k : List.of(a, b, c)) {
            assertEquals("RELEASED", currentStatus(k));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
    }

    @Test
    void mergeDuplicateNewKey_returns409_andRollsBack() throws Exception {
        String p1 = "BK-DK-P1-" + unique();
        String p2 = "BK-DK-P2-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        merge(p1, "L", List.of(p1, p2), "CK-DK-1", 409);
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
    }

    // ---------- 幂等 ----------

    @Test
    void mergeCommandKey_sameSetDifferentOrderReplays_changedParamsConflict() throws Exception {
        String p1 = "BK-ID-P1-" + unique();
        String p2 = "BK-ID-P2-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String newKey = "BK-ID-N-" + unique();

        MvcResult first = merge(newKey, "L", List.of(p1, p2), "CK-ID-1", 201);
        // 同键同参、父批集合顺序调换：重放首次结果
        MvcResult replay = merge(newKey, "L", List.of(p2, p1), "CK-ID-1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不产生重复边、不重复更新
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, newKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, newKey));

        // 同键改参 → 409
        String p3 = "BK-ID-P3-" + unique();
        createBatch(p3, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p3, "insp-3");
        merge("BK-ID-X-" + unique(), "L", List.of(p1, p3), "CK-ID-1", 409);
    }

    @Test
    void mergeCommandKey_changedParamsConflict_andFailureDoesNotOccupyKey() throws Exception {
        String p1 = "BK-IC-P1-" + unique();
        String p2 = "BK-IC-P2-" + unique();
        String p3 = "BK-IC-P3-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p3, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        releaseBatch(p3, "insp-3");
        String newKey = "BK-IC-N-" + unique();
        merge(newKey, "L", List.of(p1, p2), "CK-IC-1", 201);

        // 同键改参（换父批集合、换新键、换批号均为异参）→ 409
        merge("BK-IC-X-" + unique(), "L", List.of(p1, p3), "CK-IC-1", 409);
        merge("BK-IC-Y-" + unique(), "L", List.of(p1, p2), "CK-IC-1", 409);
        mergeRaw(newKey, "OTHER-LOT", List.of(p1, p2), "CK-IC-1", 409);
        // 历史快照保持不变：仍只有首次的 2 条边
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, newKey));
    }

    // ---------- 多路径召回 ----------

    @Test
    void oneCleanPathCannotOffsetRecalledPath_allRecalledAncestorsListed() throws Exception {
        // 菱形：ra(RECALLED) --> m，rb(RELEASED) --> m；m 只有一条路径被召回也不可用
        String ra = "BK-MP-RA-" + unique();
        String rb = "BK-MP-RB-" + unique();
        createBatch(ra, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(rb, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(ra, "insp-1");
        releaseBatch(rb, "insp-2");
        String m = "BK-MP-M-" + unique();
        merge(m, "LM", List.of(ra, rb), "CK-MP-1", 201);
        releaseBatch(m, "insp-3");

        // 再合一层：m 与干净批次 c 合为 n，使 n 同时有污染路径与干净路径
        String c = "BK-MP-C-" + unique();
        createBatch(c, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(c, "insp-4");
        String n = "BK-MP-N-" + unique();
        merge(n, "LN", List.of(m, c), "CK-MP-2", 201);

        recall(ra, "u", "父批 ra 召回", "CK-MP-R1", 201);
        assertEquals("RECALLED", currentStatus(ra));

        // m、n 均不可用，自身状态不被伪造（m 作为第二层合批父批已为 MERGED）
        assertFalse(availableKeys().contains(m));
        assertFalse(availableKeys().contains(n));
        assertEquals("MERGED", currentStatus(m));
        assertEquals("QUARANTINED", currentStatus(n));

        // m 的祖先条目：ra 自身是直接召回，不伪造为祖先召回；rb 干净
        List<JsonNode> mAncestors = lineageList("/api/batches/" + m + "/ancestors");
        JsonNode raEntry = findEntry(mAncestors, ra);
        assertEquals(0, raEntry.path("recalledAncestors").size());
        assertTrue(raEntry.path("unavailableDueToRecalledAncestor").isNull());
        JsonNode rbEntry = findEntry(mAncestors, rb);
        assertEquals(0, rbEntry.path("recalledAncestors").size());
        assertTrue(rbEntry.path("unavailableDueToRecalledAncestor").isNull());

        // 从 ra 看后代：m、n 条目均标注召回祖先 ra（共享、去重）
        assertEquals(List.of(ra), objectMapper.convertValue(
                lineageEntry("/api/batches/" + ra + "/descendants", m).path("recalledAncestors"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        // 从干净路径 rb 看后代，m、n 同样不可用：一条召回路径不能被另一条抵消
        JsonNode nFromRb = lineageEntry("/api/batches/" + rb + "/descendants", n);
        assertEquals(List.of(ra), objectMapper.convertValue(nFromRb.path("recalledAncestors"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // n 的祖先中 ra 仍只显示一次（m 是中间节点，不伪造为召回祖先）；n 自身条目在 ra 后代中标注
        JsonNode nFromRa = lineageEntry("/api/batches/" + ra + "/descendants", n);
        assertEquals(List.of(ra), objectMapper.convertValue(nFromRa.path("recalledAncestors"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // n 禁止新增检验/批准/拆分/合批；m 已 RELEASED 但不可用，禁止拆分和合批
        submitTest(n, "t1", "PASS", "insp-5", 422);
        approve(n, "qa-x", "QUALITY", "CK-MP-A", 422);
        split(n, "CK-MP-S", twoChildren(), 422);
        split(m, "CK-MP-S2", twoChildren(), 422);
        String d = "BK-MP-D-" + unique();
        createBatch(d, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(d, "insp-6");
        merge("BK-MP-X-" + unique(), "L", List.of(n, d), "CK-MP-MG", 422);
    }

    @Test
    void mergedParent_canBeRecalled_andPropagatesToAllDescendants() throws Exception {
        String p1 = "BK-MR-P1-" + unique();
        String p2 = "BK-MR-P2-" + unique();
        createBatch(p1, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        createBatch(p2, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String m = "BK-MR-M-" + unique();
        merge(m, "LM", List.of(p1, p2), "CK-MR-1", 201);
        releaseBatch(m, "insp-3");
        String c = "BK-MR-C-" + unique();
        split(m, "CK-MR-S", List.of(new String[]{c, "LC"},
                new String[]{"BK-MR-C2-" + unique(), "LC2"}), 201);

        // MERGED 父批按原接口召回
        recall(p1, "u", "合批父批召回", "CK-MR-R", 201);
        assertEquals("RECALLED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        assertEquals("SPLIT", currentStatus(m));
        assertFalse(availableKeys().contains(m));
        assertFalse(availableKeys().contains(c));
        submitTest(c, "t1", "PASS", "insp-4", 422);
    }

    @Test
    void twoRecalledAncestorsOnDifferentPaths_bothListedDeduplicated() throws Exception {
        // m1 的父批 r1 召回；m2 的父批 r2 召回；x 合批 m1、m2 → 经 4 条路径有 2 个召回祖先
        String r1 = "BK-2R-R1-" + unique();
        String s1 = "BK-2R-S1-" + unique();
        String r2 = "BK-2R-R2-" + unique();
        String s2 = "BK-2R-S2-" + unique();
        for (String k : List.of(r1, s1, r2, s2)) {
            createBatch(k, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        }
        releaseBatch(r1, "insp-1");
        releaseBatch(s1, "insp-2");
        releaseBatch(r2, "insp-3");
        releaseBatch(s2, "insp-4");
        String m1 = "BK-2R-M1-" + unique();
        String m2 = "BK-2R-M2-" + unique();
        merge(m1, "L1", List.of(r1, s1), "CK-2R-1", 201);
        merge(m2, "L2", List.of(r2, s2), "CK-2R-2", 201);
        releaseBatch(m1, "insp-5");
        releaseBatch(m2, "insp-6");
        String x = "BK-2R-X-" + unique();
        merge(x, "LX", List.of(m1, m2), "CK-2R-3", 201);

        recall(r1, "u", "召回1", "CK-2R-R1", 201);
        recall(r2, "u", "召回2", "CK-2R-R2", 201);

        // 从 r1 的后代看 x：两个召回祖先全部列出（x 经 m1→r1 与 m2→r2 两条路径），按 batchKey 排序
        JsonNode xEntry = lineageEntry("/api/batches/" + r1 + "/descendants", x);
        List<String> recalled = objectMapper.convertValue(xEntry.path("recalledAncestors"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertEquals(List.of(r1, r2).stream().sorted().toList(), recalled);
        // 兼容字段取排序后第一个
        assertEquals(List.of(r1, r2).stream().sorted().findFirst().orElseThrow(),
                xEntry.path("unavailableDueToRecalledAncestor").asText());
        assertFalse(availableKeys().contains(x));
        submitTest(x, "t1", "PASS", "insp-7", 422);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergesSharingParent_onlyOneWins_loserRollsBack() throws Exception {
        String shared = "BK-CM-S-" + unique();
        String a = "BK-CM-A-" + unique();
        String b = "BK-CM-B-" + unique();
        for (String k : List.of(shared, a, b)) {
            createBatch(k, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        }
        releaseBatch(shared, "insp-1");
        releaseBatch(a, "insp-2");
        releaseBatch(b, "insp-3");
        String n1 = "BK-CM-N1-" + unique();
        String n2 = "BK-CM-N2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(n1, "L1", List.of(shared, a), "CK-CM-1"))),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(n2, "L2", List.of(b, shared), "CK-CM-2")))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "共享父批只能被消费一次，恰有一个合批成功");
        assertTrue(first == 409 || second == 409, "失败方返回 409");

        // 失败方不残留子批或部分边
        assertEquals("MERGED", currentStatus(shared));
        if (first == 201) {
            assertEquals("MERGED", currentStatus(a));
            assertEquals("RELEASED", currentStatus(b));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, n1));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, n2));
        } else {
            assertEquals("RELEASED", currentStatus(a));
            assertEquals("MERGED", currentStatus(b));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, n1));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, n2));
        }
        // shared 只在一条成功的合批中出现
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, shared));
    }

    @Test
    void concurrentMergeAndRecallOfParent_commitOrderDecides() throws Exception {
        String p1 = "BK-CR-P1-" + unique();
        String p2 = "BK-CR-P2-" + unique();
        for (String k : List.of(p1, p2)) {
            createBatch(k, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        }
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String n = "BK-CR-N-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(n, "L", List.of(p1, p2), "CK-CR-M"))),
                () -> callStatus(post("/api/batches/" + p1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-R\",\"reason\":\"父批召回\"}"))
        );
        int merge = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall);
        if (merge == 201) {
            // 合批先提交：p1 落 MERGED 后再被召回为 RECALLED，新批不可用但状态不被伪造
            assertEquals("RECALLED", currentStatus(p1));
            assertEquals("QUARANTINED", currentStatus(n));
            assertFalse(availableKeys().contains(n));
            submitTest(n, "t1", "PASS", "insp-3", 422);
        } else {
            // 召回先提交：合批失败 409，无新批无边，p2 保持 RELEASED
            assertEquals(409, merge);
            assertEquals("RELEASED", currentStatus(p2));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, n));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
        }
    }

    @Test
    void concurrentMergeSameCommandKeySameParams_bothReplayFirstResult() throws Exception {
        String p1 = "BK-CC-P1-" + unique();
        String p2 = "BK-CC-P2-" + unique();
        for (String k : List.of(p1, p2)) {
            createBatch(k, "PROD-1", "2026-01-01T00:00:00Z", List.of("t1"));
        }
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");
        String n = "BK-CC-N-" + unique();
        String body = mergeBody(n, "L", List.of(p1, p2), "CK-CC-1");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, n));
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, String product, String producedAt, List<String> items)
            throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                product, "LOT-" + batchKey.substring(Math.max(0, batchKey.length() - 6)),
                Instant.parse(producedAt), items));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new TestCmd(
                                    "CK-T-" + unique(), "TK-" + unique(), item.asText(), "PASS",
                                    inspector))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-" + unique()).header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AQ-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-" + unique())
                        .header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AO-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-T-" + unique(), "TK-" + unique(), item, result, inspector))))
                .andExpect(status().is(expected));
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
        sb.append("]}");
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected));
    }

    private String mergeBody(String newBatchKey, String newBatchNo, List<String> parents,
                             String commandKey) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"newBatchKey\":\"").append(newBatchKey)
                .append("\",\"newBatchNo\":\"").append(newBatchNo)
                .append("\",\"parentBatchKeys\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parents.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private MvcResult merge(String newBatchKey, String newBatchNo, List<String> parents,
                            String commandKey, int expected) throws Exception {
        return mergeRaw(newBatchKey, newBatchNo, parents, commandKey, expected);
    }

    private MvcResult mergeRaw(String newBatchKey, String newBatchNo, List<String> parents,
                               String commandKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(newBatchKey, newBatchNo, parents, commandKey)))
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

    private List<String> ancestorKeys(String batchKey) throws Exception {
        List<String> keys = new ArrayList<>();
        lineageList("/api/batches/" + batchKey + "/ancestors")
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<String> descendantKeys(String batchKey) throws Exception {
        List<String> keys = new ArrayList<>();
        lineageList("/api/batches/" + batchKey + "/descendants")
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<JsonNode> lineageList(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes;
    }

    private JsonNode lineageEntry(String url, String batchKey) throws Exception {
        return findEntry(lineageList(url), batchKey);
    }

    private JsonNode findEntry(List<JsonNode> nodes, String batchKey) {
        return nodes.stream()
                .filter(n -> batchKey.equals(n.path("batchKey").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("血缘条目中缺少批次: " + batchKey));
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
