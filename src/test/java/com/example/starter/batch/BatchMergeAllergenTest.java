package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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
 * 过敏原隔离合批测试（真实 H2 MODE=MySQL 内存库）：
 * 合批成功、过敏原/代次/隔离三类 422 拒绝与回滚、创建规范化、commandKey 幂等换序/换集合、
 * 清洁代次推进、合批与清洁并发裁决（不产生混合版本子批）、冻结依据不改写、查询展示、唯一约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeAllergenTest {

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
        jdbc.update("DELETE FROM batch_merge_parent");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM batch_allergen");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyMerge_parentsBothMilkGen3_childFreezesSnapshot_parentsMerged() throws Exception {
        String a = "BK-MG-A-" + unique();
        String b = "BK-MG-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");

        String child = "BK-MG-C-" + unique();
        MvcResult result = merge(List.of(a, b), child, "LOT-C", 3, "CK-MG-1", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("QUARANTINED", body.path("childStatus").asText());
        assertEquals(3, body.path("frozenCleanGeneration").asInt());
        assertEquals(List.of("乳"), toStrings(body.path("frozenAllergens")));
        assertEquals(List.of(a, b), toStrings(body.path("parentBatchKeys")));
        assertEquals(2, body.path("parents").size());
        for (JsonNode parent : body.path("parents")) {
            assertEquals(List.of("乳"), toStrings(parent.path("allergens")));
            assertEquals(3, parent.path("cleanGeneration").asInt());
            assertEquals("RELEASED", parent.path("status").asText(), "父批快照记录合批落定前的 RELEASED 状态");
        }

        // 父批置 MERGED、数量不变（仍是两条父批记录），子批 QUARANTINED
        assertEquals("MERGED", currentStatus(a));
        assertEquals("MERGED", currentStatus(b));
        assertEquals("QUARANTINED", currentStatus(child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?) AND status = 'MERGED'",
                Integer.class, a, b));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ? AND status = 'QUARANTINED'",
                Integer.class, child));

        // 子批过敏原集合与代次固化落库
        assertEquals(List.of("乳"), jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                String.class, child));
        assertEquals(3, jdbc.queryForObject(
                "SELECT clean_generation FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'MERGE' AND child_key = ?",
                Integer.class, child));
        assertEquals(3, jdbc.queryForObject(
                "SELECT frozen_clean_generation FROM batch_merge WHERE child_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent mp JOIN batch_merge m ON mp.merge_id = m.id"
                        + " WHERE m.child_key = ? AND mp.clean_generation = 3", Integer.class, child));

        // 可用查询：父批离开，子批出现
        List<String> available = availableKeys();
        assertFalse(available.contains(a));
        assertFalse(available.contains(b));
        assertTrue(available.contains(child));
    }

    // ---------- 422 失败分支与回滚 ----------

    @Test
    void allergenSetMismatch_bAddsPeanut_returns422_noChildAndParentsUnchanged() throws Exception {
        String a = "BK-AM-A-" + unique();
        String b = "BK-AM-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳", "花生"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");

        String child = "BK-AM-C-" + unique();
        MvcResult result = merge(List.of(a, b), child, "LOT-C", 3, "CK-AM-1", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", error.path("code").asText());
        assertTrue(error.path("message").asText().contains("过敏原"),
                "拒绝原因须具体说明过敏原集合不兼容，实际: " + error.path("message").asText());
        assertTrue(error.path("message").asText().contains(b),
                "拒绝原因须指出具体父批，实际: " + error.path("message").asText());

        assertMergeDidNotHappen(child, List.of(a, b));
    }

    @Test
    void generationMismatch_bMovedToGen4_returns422_noChildAndParentsUnchanged() throws Exception {
        String a = "BK-GM-A-" + unique();
        String b = "BK-GM-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 4);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");

        String child = "BK-GM-C-" + unique();
        // 请求声明代次 3：B 当前为 4（代次过期视角下不兼容）；反之声明 4 时 A 不兼容
        MvcResult result = merge(List.of(a, b), child, "LOT-C", 3, "CK-GM-1", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(error.path("message").asText().contains("清洁代次"),
                "拒绝原因须具体说明清洁代次不一致，实际: " + error.path("message").asText());
        assertTrue(error.path("message").asText().contains(b));
        assertMergeDidNotHappen(child, List.of(a, b));

        // 整单语义：声明代次 4 同样 422（A 停留在 3），不会出现"A 跟 3、B 跟 4"的部分成功
        String child2 = "BK-GM-C2-" + unique();
        merge(List.of(a, b), child2, "LOT-C2", 4, "CK-GM-2", 422);
        assertMergeDidNotHappen(child2, List.of(a, b));
    }

    @Test
    void quarantinedOrRecalledAncestorParent_returns422() throws Exception {
        // 任一父批仍隔离（未放行）→ 422
        String a = "BK-QR-A-" + unique();
        String b = "BK-QR-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        // b 保持 QUARANTINED
        String child = "BK-QR-C-" + unique();
        MvcResult quarantined = merge(List.of(a, b), child, "LOT-C", 3, "CK-QR-1", 422);
        JsonNode error = objectMapper.readTree(quarantined.getResponse().getContentAsString());
        assertTrue(error.path("message").asText().contains("QUARANTINED"));
        assertNoMergeArtifacts(child);
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("QUARANTINED", currentStatus(b), "422 不改变仍隔离父批的状态");

        // 放行 b；另造一棵树：root 拆分出 leaf，leaf 放行后召回 root，则 leaf 有召回祖先不可合批
        releaseBatch(b, "insp-b");
        String root2 = "BK-QR-R2-" + unique();
        String leaf = "BK-QR-LF-" + unique();
        createBatch(root2, List.of("乳"), 3);
        releaseBatch(root2, "insp-r2");
        split(root2, "CK-QR-S2", List.of(new String[]{leaf, "LF1"},
                new String[]{"BK-QR-LF2-" + unique(), "LF2"}), 201);
        releaseBatch(leaf, "insp-lf");
        recall(root2, "u", "上游召回", "CK-QR-RC", 201);

        String child2 = "BK-QR-C2-" + unique();
        MvcResult blocked = merge(List.of(leaf, b), child2, "LOT-C2", 3, "CK-QR-2", 422);
        JsonNode blockedError = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertTrue(blockedError.path("message").asText().contains(root2),
                "拒绝原因须指出召回祖先，实际: " + blockedError.path("message").asText());
        assertNoMergeArtifacts(child2);
        assertEquals("RELEASED", currentStatus(b), "未参与成功合批的父批数量与状态不变");
    }

    @Test
    void mergeUnknownParent_returns404() throws Exception {
        String a = "BK-404-A-" + unique();
        createBatch(a, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        merge(List.of(a, "BK-404-MISSING"), "BK-404-C-" + unique(), "L", 3, "CK-404", 404);
    }

    @Test
    void invalidMergeBodies_return400_andDuplicateParentKeys409() throws Exception {
        String a = "BK-INV-A-" + unique();
        createBatch(a, List.of("乳"), 3);

        // 仅 1 个父批
        mergeRaw(mergeBody("CK-INV-1", List.of(a), "BK-INV-C1-" + unique(), "L", 3), 400);
        // 6 个父批
        List<String> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add("BK-INV-P" + i + "-" + unique());
        }
        mergeRaw(mergeBody("CK-INV-2", six, "BK-INV-C2-" + unique(), "L", 3), 400);
        // 子批键空白
        mergeRaw(mergeBody("CK-INV-3", List.of(a, "BK-INV-X-" + unique()), "  ", "L", 3), 400);
        // 清洁代次非正数
        mergeRaw(mergeBody("CK-INV-4", List.of(a, "BK-INV-Y-" + unique()),
                "BK-INV-C4-" + unique(), "L", 0), 400);

        // 父批键请求内重复（集合语义，去重后不足 2 个不同父批）→ 409
        mergeRaw(mergeBody("CK-INV-5", List.of(a, a), "BK-INV-C5-" + unique(), "L", 3), 409);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'MERGE'", Integer.class));
    }

    // ---------- 规范化 ----------

    @Test
    void createBatch_normalizesAllergens_trimDedupeAndStableSort() throws Exception {
        String key = "BK-NORM-" + unique();
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-NORM", key, "PROD-1", "LOT-1",
                Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                List.of("花生", " 乳 ", "乳", "花生", "蛋 "), 3));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        List<String> stored = jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                String.class, key);
        assertEquals(List.of("乳", "花生", "蛋"), stored, "须 trim、去重并按 Unicode 字典序稳定排序");
        MvcResult history = mockMvc.perform(get("/api/batches/" + key + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(List.of("乳", "花生", "蛋"), toStrings(node.path("batch").path("allergens")));
        assertEquals(3, node.path("batch").path("cleanGeneration").asInt());
    }

    @Test
    void missingAllergensAndGeneration_defaultToEmptySetAndGen1_andMerges() throws Exception {
        String a = "BK-DEF-A-" + unique();
        String b = "BK-DEF-B-" + unique();
        createBatchDefault(a);
        createBatchDefault(b);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");

        String child = "BK-DEF-C-" + unique();
        MvcResult result = merge(List.of(a, b), child, "LOT-C", 1, "CK-DEF-1", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, body.path("frozenAllergens").size(), "无过敏原父批合批，固化空集合");
        assertEquals(1, body.path("frozenCleanGeneration").asInt());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_allergen WHERE batch_key = ?", Integer.class, child));
    }

    // ---------- 幂等 ----------

    @Test
    void mergeCommandKey_reorderedParentsReplay_changedSetConflicts() throws Exception {
        String a = "BK-IDM-A-" + unique();
        String b = "BK-IDM-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");
        String child = "BK-IDM-C-" + unique();

        String firstBody = mergeBody("CK-IDM", List.of(a, b), child, "LOT-C", 3);
        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated()).andReturn();

        // 父批换序 = 同参：重放首次子批与过敏原快照
        String reordered = mergeBody("CK-IDM", List.of(b, a), child, "LOT-C", 3);
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'MERGE' AND child_key = ?",
                Integer.class, child));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));

        // 同键换请求代次 → 409
        mergeRaw(mergeBody("CK-IDM", List.of(a, b), child, "LOT-C", 4), 409);

        // 同键换子批号 → 409
        mergeRaw(mergeBody("CK-IDM", List.of(a, b), "BK-IDM-CX-" + unique(), "LOT-X", 3), 409);

        // 同键换父批集合（B 换成 C）→ 409
        String c = "BK-IDM-CP-" + unique();
        createBatch(c, List.of("乳"), 3);
        releaseBatch(c, "insp-c");
        mergeRaw(mergeBody("CK-IDM", List.of(a, c), "BK-IDM-C2-" + unique(), "LOT-C2", 3), 409);
    }

    // ---------- 清洁代次推进 ----------

    @Test
    void advanceCleaning_onlyIncreases_andIdempotent_andFreezeStays() throws Exception {
        String a = "BK-CL-A-" + unique();
        String b = "BK-CL-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");
        String child = "BK-CL-C-" + unique();
        merge(List.of(a, b), child, "LOT-C", 3, "CK-CL-M", 201);

        // 代次只增不减：停留/回退 422
        String stay = "{\"commandKey\":\"CK-CL-0\",\"newGeneration\":3}";
        mockMvc.perform(post("/api/batches/" + b + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON).content(stay))
                .andExpect(status().isUnprocessableEntity());
        String back = "{\"commandKey\":\"CK-CL-0B\",\"newGeneration\":2}";
        mockMvc.perform(post("/api/batches/" + b + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON).content(back))
                .andExpect(status().isUnprocessableEntity());

        // 推进到 4：父批当前代次变化
        String body = "{\"commandKey\":\"CK-CL-1\",\"newGeneration\":4}";
        MvcResult ok = mockMvc.perform(post("/api/batches/" + b + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode okNode = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertEquals(3, okNode.path("previousCleanGeneration").asInt());
        assertEquals(4, okNode.path("cleanGeneration").asInt());
        // 同键同参重放
        mockMvc.perform(post("/api/batches/" + b + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同键改参 → 409
        mockMvc.perform(post("/api/batches/" + b + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL-1\",\"newGeneration\":5}"))
                .andExpect(status().isConflict());

        // 子批自身也推进到 4，但其合批冻结依据仍是 3，父批快照仍是 3
        mockMvc.perform(post("/api/batches/" + child + "/cleaning")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL-2\",\"newGeneration\":4}"))
                .andExpect(status().isOk());
        MvcResult history = mockMvc.perform(get("/api/batches/" + child + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(4, node.path("batch").path("cleanGeneration").asInt(), "当前代次已推进到 4");
        assertEquals(3, node.path("mergeBasis").path("frozenCleanGeneration").asInt(),
                "后续清洁不改写已合批的历史代次依据");
        assertEquals(List.of("乳"), toStrings(node.path("mergeBasis").path("frozenAllergens")));
        for (JsonNode parent : node.path("mergeBasis").path("parents")) {
            assertEquals(3, parent.path("cleanGeneration").asInt(), "父批合批快照代次冻结为 3");
        }
        assertEquals(3, jdbc.queryForObject(
                "SELECT frozen_clean_generation FROM batch_merge WHERE child_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE clean_generation <> 3", Integer.class));

        // 父批合批去向后可查
        MvcResult parentHistory = mockMvc.perform(get("/api/batches/" + b + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode parentNode = objectMapper.readTree(parentHistory.getResponse().getContentAsString());
        assertEquals(child, parentNode.path("mergedIntoChildKey").asText());
        assertEquals(4, parentNode.path("batch").path("cleanGeneration").asInt());

        // 未知批次 404
        mockMvc.perform(post("/api/batches/NO-SUCH/cleaning")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL-X\",\"newGeneration\":9}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergeAndCleaning_neverProducesMixedVersionChild() throws Exception {
        // 多轮独立数据重复同一并发交错：每轮都必须满足"不产生混合版本子批"，
        // 且两种提交顺序（合批先/清洁先）的业务结果都在本测试的断言中被核对。
        int rounds = 8;
        for (int round = 0; round < rounds; round++) {
            String a = "BK-CC-A" + round + "-" + unique();
            String b = "BK-CC-B" + round + "-" + unique();
            createBatch(a, List.of("乳"), 3);
            createBatch(b, List.of("乳"), 3);
            releaseBatch(a, "insp-a" + round);
            releaseBatch(b, "insp-b" + round);
            String child = "BK-CC-C" + round + "-" + unique();
            String mergeCommandKey = "CK-CC-M" + round + "-" + unique();
            String cleaningCommandKey = "CK-CC-G" + round + "-" + unique();

            List<Future<Integer>> results = runConcurrent(
                    () -> callStatus(post("/api/batches/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mergeBody(mergeCommandKey, List.of(a, b), child, "LOT-C", 3))),
                    () -> callStatus(post("/api/batches/" + b + "/cleaning")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + cleaningCommandKey
                                    + "\",\"newGeneration\":4}"))
            );

            int mergeStatus = results.get(0).get(30, TimeUnit.SECONDS);
            int cleaningStatus = results.get(1).get(30, TimeUnit.SECONDS);
            Integer childCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, child);
            if (mergeStatus == 201) {
                // 合批先获锁提交：清洁随后生效，两者各自成功；子批冻结依据必须全部为代次 3
                assertEquals(1, childCount, "round " + round);
                assertEquals(200, cleaningStatus, "round " + round);
                assertEquals(3, jdbc.queryForObject(
                        "SELECT frozen_clean_generation FROM batch_merge WHERE child_key = ?",
                        Integer.class, child), "round " + round);
                Integer mixed = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM batch_merge_parent mp JOIN batch_merge m"
                                + " ON mp.merge_id = m.id WHERE m.child_key = ?"
                                + " AND mp.clean_generation <> 3", Integer.class, child);
                assertEquals(0, mixed, "合批父批快照不得出现 3/4 混合版本，round " + round);
                assertEquals(4, jdbc.queryForObject(
                        "SELECT clean_generation FROM batch WHERE batch_key = ?", Integer.class, b),
                        "round " + round);
            } else {
                // 清洁先提交：B 已到代次 4，合批必须整单 422，不生成任何子批与血缘
                assertEquals(422, mergeStatus, "round " + round);
                assertEquals(200, cleaningStatus, "round " + round);
                assertEquals(0, childCount, "round " + round);
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child),
                        "round " + round);
                assertEquals("RELEASED", currentStatus(a), "round " + round);
                assertEquals("RELEASED", currentStatus(b),
                        "清洁推进不改变父批状态，父批仍可另行合批，round " + round);
            }
        }
    }

    @Test
    void concurrentSameMergeCommandKey_singleChildBothReplay() throws Exception {
        String a = "BK-CK-A-" + unique();
        String b = "BK-CK-B-" + unique();
        createBatch(a, List.of("乳"), 3);
        createBatch(b, List.of("乳"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");
        String child = "BK-CK-C-" + unique();
        String body = mergeBody("CK-CK-M", List.of(a, b), child, "LOT-C", 3);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发合批均重放首次结果");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));
    }

    // ---------- 查询展示与拆分继承 ----------

    @Test
    void lineageQueriesShowAllergensGenerationRelationType_andSplitInherits() throws Exception {
        String a = "BK-LQ-A-" + unique();
        String b = "BK-LQ-B-" + unique();
        createBatch(a, List.of("乳", "花生"), 3);
        createBatch(b, List.of("乳", "花生"), 3);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");
        String child = "BK-LQ-C-" + unique();
        merge(List.of(a, b), child, "LOT-C", 3, "CK-LQ-M", 201);

        // 祖先：两个直接父批，关系类型 MERGE，带过敏原与代次
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        assertEquals(List.of(a, b), List.of(
                ancestorNodes.get(0).path("batchKey").asText(),
                ancestorNodes.get(1).path("batchKey").asText()));
        for (JsonNode node : ancestorNodes) {
            assertEquals("MERGED", node.path("status").asText());
            assertEquals("MERGE", node.path("relationType").asText());
            assertEquals(List.of("乳", "花生"), toStrings(node.path("allergens")));
            assertEquals(3, node.path("cleanGeneration").asInt());
            assertTrue(node.path("unavailableDueToRecalledAncestor").isNull());
        }

        // 后代：A/B 的后代都含合批子批
        for (String parent : List.of(a, b)) {
            MvcResult descendants = mockMvc.perform(get("/api/batches/" + parent + "/descendants"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode nodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
            assertEquals(1, nodes.size());
            assertEquals(child, nodes.get(0).path("batchKey").asText());
            assertEquals("MERGE", nodes.get(0).path("relationType").asText());
        }

        // 拆分继承过敏原与当前清洁代次
        String splitParent = "BK-LQ-SP-" + unique();
        createBatch(splitParent, List.of("花生", "乳"), 3);
        releaseBatch(splitParent, "insp-sp");
        String sc1 = "BK-LQ-S1-" + unique();
        split(splitParent, "CK-LQ-S", List.of(new String[]{sc1, "S1"},
                new String[]{"BK-LQ-S2-" + unique(), "S2"}), 201);
        MvcResult splitHistory = mockMvc.perform(get("/api/batches/" + sc1 + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode splitNode = objectMapper.readTree(splitHistory.getResponse().getContentAsString());
        assertEquals(List.of("乳", "花生"), toStrings(splitNode.path("batch").path("allergens")));
        assertEquals(3, splitNode.path("batch").path("cleanGeneration").asInt());
    }

    @Test
    void allergenUniqueConstraint_isEnforcedByDatabase() {
        String key = "BK-UK-" + unique();
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                + " clean_generation, created_at) VALUES (?, 'P', 'N', '2026-01-01T00:00:00Z',"
                + " 'QUARANTINED', 1, '2026-01-01T00:00:00Z')", key);
        jdbc.update("INSERT INTO batch_allergen (batch_key, allergen, seq) VALUES (?, '乳', 1)", key);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO batch_allergen (batch_key, allergen, seq) VALUES (?, '乳', 2)", key),
                "uk_batch_allergen 唯一约束须在 H2 上实际拦截重复过敏原行");
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, List<String> allergens,
                             Integer cleanGeneration) {
    }

    private record MergeCmd(String commandKey, List<String> parentBatchKeys, String childBatchKey,
                            String childBatchNo, Integer cleanGeneration) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> allergens, int cleanGeneration)
            throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                allergens, cleanGeneration));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void createBatchDefault(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                null, null));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                                "TK-" + unique(), "t1", "PASS", inspector))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-" + unique()).header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AQ-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-" + unique()).header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-AO-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private String mergeBody(String commandKey, List<String> parents, String childKey,
                             String childBatchNo, int generation) throws Exception {
        return objectMapper.writeValueAsString(new MergeCmd(commandKey, parents, childKey,
                childBatchNo, generation));
    }

    private MvcResult merge(List<String> parents, String childKey, String childBatchNo,
                            int generation, String commandKey, int expectedStatus) throws Exception {
        return mergeRaw(mergeBody(commandKey, parents, childKey, childBatchNo, generation),
                expectedStatus);
    }

    private MvcResult mergeRaw(String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
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

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 断言失败合批整单回滚：无子批/合批事件/父批快照/MERGE 血缘写入。
     */
    private void assertNoMergeArtifacts(String childKey) {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, childKey));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation_type = 'MERGE'", Integer.class));
    }

    /**
     * 断言失败合批整单回滚且参与的 RELEASED 父批状态与数量不变。
     */
    private void assertMergeDidNotHappen(String childKey, List<String> releasedParents) {
        assertNoMergeArtifacts(childKey);
        for (String parent : releasedParents) {
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key = ? AND status = 'RELEASED'",
                    Integer.class, parent), "422 失败不得改变父批状态与数量: " + parent);
        }
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

    private List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
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
