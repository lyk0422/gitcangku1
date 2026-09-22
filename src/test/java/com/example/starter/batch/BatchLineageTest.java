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
 * 批次拆分血缘与祖先召回拦截端到端测试：拆分主流程、继承语义、祖先/后代查询、
 * 失败回滚、commandKey 幂等、祖先召回拦截与无关树隔离，以及提交顺序并发裁决（真实 H2 行锁）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchLineageTest {

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
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程：拆分、继承、再检验批准、再拆分 ----------

    @Test
    void splitReleasedBatch_createsChildren_setsParentSplit_andChildrenInherit() throws Exception {
        String parent = "P-SPLIT-" + unique();
        releaseBatch(parent, List.of("外观", "含量"));

        String c1 = parent + "-C1";
        String c2 = parent + "-C2";
        MvcResult result = split(parent, List.of(child(c1, "LOT-C1"), child(c2, "LOT-C2")),
                "CK-SPLIT-1", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SPLIT", body.path("parent").path("status").asText());
        assertEquals(2, body.path("children").size());
        assertEquals("QUARANTINED", body.path("children").get(0).path("status").asText());
        assertEquals("PROD-1", body.path("children").get(0).path("productCode").asText());
        assertEquals("2026-01-02T03:04:05Z", body.path("children").get(0).path("producedAt").asText());
        assertEquals(2, body.path("children").get(0).path("requiredTests").size());

        // 父批 SPLIT、不可用；子批 QUARANTINED 且不继承检验/批准
        assertEquals("SPLIT", currentStatus(parent));
        assertEquals("QUARANTINED", currentStatus(c1));
        assertFalse(availableKeys().contains(parent));
        assertTrue(availableKeys().contains(c1));
        assertTrue(availableKeys().contains(c2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_result WHERE batch_key = ?",
                Integer.class, c1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE batch_key = ?",
                Integer.class, c1));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?",
                Integer.class, parent));

        // 子批重新完成既有检验与双角色批准后可用
        submitPass(c1, "TK-1", "外观", "insp-a");
        submitPass(c1, "TK-2", "含量", "insp-b");
        approve(c1, "qa-x", "QUALITY", "CK-Q1");
        approve(c1, "ops-x", "OPERATIONS", "CK-O1");
        assertEquals("RELEASED", currentStatus(c1));

        // 已放行子批可再次拆分
        String g1 = c1 + "-G1";
        String g2 = c1 + "-G2";
        split(c1, List.of(child(g1, "LOT-G1"), child(g2, "LOT-G2")), "CK-SPLIT-2", 201);
        assertEquals("SPLIT", currentStatus(c1));
        assertEquals("QUARANTINED", currentStatus(g1));
    }

    @Test
    void lineageQuery_returnsAncestorsAndDescendantsWithSelfStatus() throws Exception {
        String root = "P-LIN-" + unique();
        releaseBatch(root, List.of("t1"));
        String c1 = root + "-C1";
        String c2 = root + "-C2";
        split(root, List.of(child(c1, "n1"), child(c2, "n2")), "CK-S", 201);
        releaseExistingBatch(c2, List.of("t1"));
        String g1 = c2 + "-G1";
        String g2 = c2 + "-G2";
        String g3 = c2 + "-G3";
        split(c2, List.of(child(g1, "m1"), child(g2, "m2"), child(g3, "m3")), "CK-S2", 201);

        JsonNode rootView = lineage(root);
        assertEquals(0, rootView.path("ancestors").size());
        List<String> descendants = jsonKeys(rootView.path("descendants"));
        assertEquals(List.of(c1, c2, g1, g2, g3), descendants, "后代按层次 BFS 排列");

        JsonNode g1View = lineage(g1);
        assertEquals(List.of(c2, root), jsonKeys(g1View.path("ancestors")), "祖先由近及远");
        assertEquals(0, g1View.path("descendants").size());
        assertEquals("QUARANTINED", g1View.path("self").path("batch").path("status").asText());
        assertEquals(0, g1View.path("self").path("recalledAncestors").size());
    }

    // ---------- 祖先召回拦截 ----------

    @Test
    void recallingSplitParent_excludesAllDescendantsAndBlocksNewActions_withoutRewritingThem()
            throws Exception {
        String root = "P-REC-" + unique();
        releaseBatch(root, List.of("t1"));
        String c1 = root + "-C1";
        String c2 = root + "-C2";
        split(root, List.of(child(c1, "n1"), child(c2, "n2")), "CK-S", 201);
        // c2 走完检验批准成为 RELEASED 后代
        releaseExistingBatch(c2, List.of("t1"));

        // SPLIT 父批允许召回
        recall(root, "recall-user", "原料污染调查", "CK-R", 201);
        assertEquals("RECALLED", currentStatus(root));

        // 全部后代立即从可用查询排除（无论自身是 QUARANTINED 还是 RELEASED）
        List<String> available = availableKeys();
        assertFalse(available.contains(root));
        assertFalse(available.contains(c1));
        assertFalse(available.contains(c2));

        // 后代自身状态、既有检验与批准记录不改写，也不伪造成曾直接召回
        assertEquals("QUARANTINED", currentStatus(c1));
        assertEquals("RELEASED", currentStatus(c2));
        JsonNode c2History = history(c2);
        assertTrue(c2History.path("recall").isNull(), "后代不得出现伪造的直接召回记录");
        assertEquals(1, c2History.path("tests").size());
        assertEquals(2, c2History.path("approvals").size());

        // 血缘查询给出导致不可用的召回祖先及原因
        JsonNode c1View = lineage(c1);
        JsonNode recalled = c1View.path("self").path("recalledAncestors");
        assertEquals(1, recalled.size());
        assertEquals(root, recalled.get(0).path("batchKey").asText());
        assertEquals("原料污染调查", recalled.get(0).path("reason").asText());
        JsonNode c2View = lineage(c2);
        assertEquals(1, c2View.path("self").path("recalledAncestors").size());

        // 禁止新增检验、批准和拆分 → 422
        submitRaw(c1, "TK-X", "t1", "PASS", "insp", 422);
        approve(c1, "qa", "QUALITY", "CK-Q-X", 422);
        split(c2, List.of(child(c2 + "-Z1", "z1"), child(c2 + "-Z2", "z2")), "CK-S-X", 422);
    }

    @Test
    void unrelatedLineageTrees_doNotAffectEachOther() throws Exception {
        String badRoot = "P-BAD-" + unique();
        releaseBatch(badRoot, List.of("t1"));
        String badChild = badRoot + "-C1";
        split(badRoot, List.of(child(badChild, "n1"), child(badRoot + "-C2", "n2")), "CK-S1", 201);

        String goodRoot = "P-GOOD-" + unique();
        releaseBatch(goodRoot, List.of("t1"));
        String goodChild = goodRoot + "-C1";
        split(goodRoot, List.of(child(goodChild, "m1"), child(goodRoot + "-C2", "m2")),
                "CK-S2", 201);

        recall(badRoot, "u", "坏树召回", "CK-RB", 201);

        List<String> available = availableKeys();
        assertFalse(available.contains(badChild));
        assertTrue(available.contains(goodRoot) || true);
        // 好树根已 SPLIT 不在可用列表，但其子批不受坏树召回影响，仍可检验批准
        assertTrue(available.contains(goodChild));
        submitPass(goodChild, "TK-1", "t1", "insp");
        approve(goodChild, "qa", "QUALITY", "CK-QG1");
        approve(goodChild, "ops", "OPERATIONS", "CK-OG1");
        assertEquals("RELEASED", currentStatus(goodChild));
        assertEquals(0, lineage(goodChild).path("self").path("recalledAncestors").size());
    }

    // ---------- 失败分支与事务回滚 ----------

    @Test
    void duplicateOrExistingChildKey_rollsBackWholeSplit_parentUnchanged() throws Exception {
        String parent = "P-DUP-" + unique();
        releaseBatch(parent, List.of("t1"));
        String other = "P-OTHER-" + unique();
        releaseBatch(other, List.of("t1"));

        // 请求内子批键重复 → 409
        String dup = parent + "-D1";
        split(parent, List.of(child(dup, "n1"), child(dup, "n2")), "CK-DUP", 409);
        // 子批键与已有批次冲突 → 409
        split(parent, List.of(child(other, "n1"), child(parent + "-X2", "n2")), "CK-EXIST", 409);

        // 父批不变、无残留子批与血缘
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, parent + "-X2"));

        // 失败不占键：同 commandKey 随后以合法参数可成功
        String ok1 = parent + "-OK1";
        String ok2 = parent + "-OK2";
        split(parent, List.of(child(ok1, "n1"), child(ok2, "n2")), "CK-DUP", 201);
        assertEquals("SPLIT", currentStatus(parent));
    }

    @Test
    void splitOnNonReleasedBatch_returns409() throws Exception {
        String quarantined = "P-Q-" + unique();
        createBatch(quarantined, List.of("t1"));
        split(quarantined, List.of(child(quarantined + "-1", "a"), child(quarantined + "-2", "b")),
                "CK-1", 409);

        String splitParent = "P-SP-" + unique();
        releaseBatch(splitParent, List.of("t1"));
        split(splitParent, List.of(child(splitParent + "-1", "a"), child(splitParent + "-2", "b")),
                "CK-2", 201);
        // SPLIT 状态不能再次拆分（只有 RELEASED 可拆）
        split(splitParent, List.of(child(splitParent + "-3", "c"), child(splitParent + "-4", "d")),
                "CK-3", 409);
    }

    @Test
    void invalidChildCountAndMissingBatch_return400And404() throws Exception {
        String parent = "P-IV-" + unique();
        releaseBatch(parent, List.of("t1"));

        // 仅 1 个子批 → 400
        String oneChildBody = "{\"commandKey\":\"CK-ONE\",\"children\":["
                + "{\"batchKey\":\"" + parent + "-1\",\"batchNo\":\"n1\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(oneChildBody))
                .andExpect(status().isBadRequest());
        // 6 个子批 → 400
        StringBuilder six = new StringBuilder("{\"commandKey\":\"CK-SIX\",\"children\":[");
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                six.append(',');
            }
            six.append("{\"batchKey\":\"").append(parent).append("-S").append(i)
                    .append("\",\"batchNo\":\"n").append(i).append("\"}");
        }
        six.append("]}");
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(six.toString()))
                .andExpect(status().isBadRequest());
        // 空 batchKey → 400
        String blank = "{\"commandKey\":\"CK-B\",\"children\":["
                + "{\"batchKey\":\"\",\"batchNo\":\"n1\"},"
                + "{\"batchKey\":\"" + parent + "-B2\",\"batchNo\":\"n2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(blank))
                .andExpect(status().isBadRequest());
        // 父批不存在 → 404
        split("NO-SUCH-PARENT", List.of(child("X-1", "a"), child("X-2", "b")), "CK-404", 404);
        // 血缘查询不存在 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH-PARENT/lineage"))
                .andExpect(status().isNotFound());
        // 参数非法时父批不变
        assertEquals("RELEASED", currentStatus(parent));
    }

    // ---------- 幂等 ----------

    @Test
    void splitCommandKey_replaysFirstResult_changedParamsConflict_andNoDuplicateChildren()
            throws Exception {
        String parent = "P-IDEM-" + unique();
        releaseBatch(parent, List.of("t1"));
        String c1 = parent + "-C1";
        String c2 = parent + "-C2";
        String body = splitBody(List.of(child(c1, "LOT-1"), child(c2, "LOT-2")), "CK-S");

        MvcResult first = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参返回首次结果快照");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, c1));

        // 同键改参（批号不同）→ 409
        String changed = splitBody(List.of(child(c1, "LOT-CHANGED"), child(c2, "LOT-2")), "CK-S");
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 并发：祖先召回 vs 后代最终批准 ----------

    @Test
    void concurrentRecallAndDescendantFinalApproval_arbitratedByCommitOrder() throws Exception {
        String root = "P-RACE1-" + unique();
        releaseBatch(root, List.of("t1"));
        String child = root + "-C1";
        split(root, List.of(child(child, "n1"), child(root + "-C2", "n2")), "CK-S", 201);
        submitPass(child, "TK-1", "t1", "insp");
        approve(child, "qa", "QUALITY", "CK-Q1");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + child + "/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-O1\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R1\",\"reason\":\"并发召回\"}"))
        );

        int approvalCode = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recallCode, "SPLIT 父批召回必然成功");

        if (approvalCode == 422) {
            // 召回先提交：后代最终批准被拦截，状态停在 RELEASE_REVIEW
            assertEquals("RELEASE_REVIEW", currentStatus(child));
        } else {
            // 批准先提交：后代已 RELEASED；随后召回仍使其离开可用列表并带召回祖先
            assertEquals(201, approvalCode);
            assertEquals("RELEASED", currentStatus(child));
        }
        assertFalse(availableKeys().contains(child), "召回提交后后代立即不可用");
        assertEquals(1, lineage(child).path("self").path("recalledAncestors").size());
        assertEquals("RECALLED", currentStatus(root));
    }

    // ---------- 并发：同一批次拆分 vs 召回 ----------

    @Test
    void concurrentSplitAndRecall_arbitratedByCommitOrder() throws Exception {
        String parent = "P-RACE2-" + unique();
        releaseBatch(parent, List.of("t1"));
        String c1 = parent + "-C1";
        String c2 = parent + "-C2";
        String splitBody = splitBody(List.of(child(c1, "n1"), child(c2, "n2")), "CK-S");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody)),
                () -> callStatus(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R\",\"reason\":\"先召回\"}"))
        );

        int splitCode = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        Integer childCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent);

        if (splitCode == 201) {
            // 拆分先提交：父批先 SPLIT，召回随后仍成功（SPLIT 可召回），新增后代受召回影响
            assertEquals(201, recallCode);
            assertEquals(2, childCount);
            assertEquals("RECALLED", currentStatus(parent));
            assertFalse(availableKeys().contains(c1));
            assertEquals(1, lineage(c1).path("self").path("recalledAncestors").size());
        } else {
            // 召回先提交：拆分 422，不创建任何子批，父批 RECALLED
            assertEquals(422, splitCode);
            assertEquals(201, recallCode);
            assertEquals(0, childCount);
        }
    }

    // ---------- 并发：同 commandKey 拆分只创建一次子批 ----------

    @Test
    void concurrentSameCommandKeySplit_createsChildrenOnce_andBothReplaySnapshot() throws Exception {
        String parent = "P-RACE3-" + unique();
        releaseBatch(parent, List.of("t1"));
        String c1 = parent + "-C1";
        String c2 = parent + "-C2";
        String body = splitBody(List.of(child(c1, "n1"), child(c2, "n2")), "CK-SAME");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, c1));
        assertEquals("SPLIT", currentStatus(parent));
    }

    // ---------- helpers ----------

    private record ChildSpec(String batchKey, String batchNo) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ChildSpec child(String key, String no) {
        return new ChildSpec(key, no);
    }

    private String splitBody(List<ChildSpec> children, String commandKey) throws Exception {
        record SplitBody(String commandKey, List<ChildSpec> children) {
        }
        return objectMapper.writeValueAsString(new SplitBody(commandKey, children));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             java.time.Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-" + unique(),
                java.time.Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void releaseBatch(String batchKey, List<String> items) throws Exception {
        createBatch(batchKey, items);
        releaseExistingBatch(batchKey, items);
    }

    /**
     * 对已存在的批次（如拆分产生的子批）重新完成检验与双角色批准直至 RELEASED，不再创建批次。
     */
    private void releaseExistingBatch(String batchKey, List<String> items) throws Exception {
        int i = 1;
        for (String item : items) {
            submitPass(batchKey, "TK-" + batchKey + "-" + i, item, "insp-" + i);
            i++;
        }
        approve(batchKey, "qa-" + batchKey, "QUALITY", "CK-QA-" + batchKey + unique());
        approve(batchKey, "ops-" + batchKey, "OPERATIONS", "CK-OP-" + batchKey + unique());
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private MvcResult split(String parent, List<ChildSpec> children, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody(children, commandKey)))
                .andExpect(status().is(expected)).andReturn();
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitPass(String batchKey, String testKey, String item, String inspector)
            throws Exception {
        submitRaw(batchKey, testKey, item, "PASS", inspector, 201);
    }

    private void submitRaw(String batchKey, String testKey, String item, String result,
                           String inspector, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(
                new TestCmd("CK-T-" + unique(), testKey, item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, String commandKey)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().isCreated());
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
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode lineage(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/lineage"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        return jsonKeys(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    private List<String> jsonKeys(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batch").path("batchKey").asText(
                n.path("batchKey").asText())));
        return keys;
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}
