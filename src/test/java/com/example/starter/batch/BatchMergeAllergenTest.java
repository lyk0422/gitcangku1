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
 * 过敏原隔离与生产批次合批测试：过敏原规范化、合批成功、集合/代次/隔离/召回祖先等 422 分支、
 * requestId 换序幂等与换集合 409、清洁代次并发不产生混合版本子批、合批冻结依据不被后续清洁改写。
 * 全部基于自动建表的 H2（MODE=MySQL）真实读写。
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
        jdbc.update("DELETE FROM batch_merge_parent_allergen");
        jdbc.update("DELETE FROM batch_merge_parent");
        jdbc.update("DELETE FROM batch_merge_rejection");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM batch_allergen");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 过敏原规范化与清洁代次 ----------

    @Test
    void saveAllergens_trimDeduplicateAndStableSort_emptyClearsSet() throws Exception {
        String key = "BK-ALG-" + unique();
        createBatch(key, "P1", null, null, 201);

        String body = objectMapper.writeValueAsString(new AllergenCmd("CK-ALG-1",
                List.of(" 乳 ", "花生", "乳", "花生", "  蛋  ")));
        MvcResult result = mockMvc.perform(post("/api/batches/" + key + "/allergens")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        // 稳定排序按 Unicode 码位：乳(U+4E73) < 花(U+82B1) < 蛋(U+86CB)
        assertEquals(List.of("乳", "花生", "蛋"),
                objectMapper.convertValue(node.path("allergens"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        // 落库为逐行结构化存储且按字典序
        assertEquals(List.of("乳", "花生", "蛋"), jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                String.class, key));

        // 同 commandKey 同参重放，不重复落库
        mockMvc.perform(post("/api/batches/" + key + "/allergens")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_allergen WHERE batch_key = ?", Integer.class, key));

        // 空集合整体清空，显式表示不含登记过敏原
        String empty = objectMapper.writeValueAsString(new AllergenCmd("CK-ALG-2", List.of()));
        mockMvc.perform(post("/api/batches/" + key + "/allergens")
                        .contentType(MediaType.APPLICATION_JSON).content(empty))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_allergen WHERE batch_key = ?", Integer.class, key));
    }

    @Test
    void cleaningGeneration_advancesOnlyForward_andReplay() throws Exception {
        String key = "BK-GEN-" + unique();
        createBatch(key, "P1", null, null, 201);
        assertEquals(1, currentGeneration(key));

        advanceGeneration(key, 3, "CK-G-1", 200);
        assertEquals(3, currentGeneration(key));
        // 同 commandKey 同参重放
        advanceGeneration(key, 3, "CK-G-1", 200);
        // 不能向后或原地推进
        advanceGeneration(key, 3, "CK-G-2", 422);
        advanceGeneration(key, 2, "CK-G-3", 422);
        assertEquals(3, currentGeneration(key));
    }

    // ---------- 合批主流程 ----------

    @Test
    void happyMerge_parentsBothMilkGen3_childFreezesSetAndGeneration() throws Exception {
        String a = "BK-MG-A-" + unique();
        String b = "BK-MG-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);

        String child = "BK-MG-C-" + unique();
        MvcResult result = merge(child, "LOT-C", "REQ-MG-1", List.of(a, b), 201);
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("REQ-MG-1", node.path("requestId").asText());
        assertEquals(child, node.path("childBatchKey").asText());
        assertEquals(List.of("乳"), objectMapper.convertValue(node.path("allergens"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(3, node.path("cleaningGeneration").asInt());
        assertEquals("QUARANTINED", node.path("childStatus").asText());
        assertEquals(2, node.path("parents").size());
        // 父批按业务键排序固化，各自快照含 乳/gen3/RELEASED
        JsonNode p0 = node.path("parents").get(0);
        assertEquals(a, p0.path("batchKey").asText());
        assertEquals(3, p0.path("cleaningGeneration").asInt());
        assertEquals("RELEASED", p0.path("statusBeforeMerge").asText());
        assertEquals(List.of("乳"), objectMapper.convertValue(p0.path("allergens"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 父批置 MERGED 不再可用，子批初始隔离可用
        assertEquals("MERGED", currentStatus(a));
        assertEquals("MERGED", currentStatus(b));
        List<String> available = availableKeys();
        assertFalse(available.contains(a));
        assertFalse(available.contains(b));
        assertTrue(available.contains(child));
        assertEquals(3, currentGeneration(child));
        assertEquals(List.of("乳"), allergensOf(child));

        // 血缘为 MERGE 且子批多父
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ? AND relation = 'MERGE'",
                Integer.class, child));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE request_id = 'REQ-MG-1'", Integer.class));

        // lookup 返回成功快照；子批祖先查询含两个父批
        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-MG-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode lookupNode = objectMapper.readTree(lookup.getResponse().getContentAsString());
        assertEquals("MERGED", lookupNode.path("outcome").asText());
        assertEquals(child, lookupNode.path("merge").path("childBatchKey").asText());

        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());

        // 子批继承必做检验项，可独立完成检验放行
        submitTest(child, "t1", "PASS", "insp-c", 201);
        approve(child, "qa-c", "QUALITY", "CK-CQ", 201);
        approve(child, "ops-c", "OPERATIONS", "CK-CO", 201);
        assertEquals("RELEASED", currentStatus(child));
    }

    // ---------- 422 失败分支 ----------

    @Test
    void mergeWhenAllergenSetMismatch_returns422_createsNothing_andRecordsReason() throws Exception {
        String a = "BK-MM-A-" + unique();
        String b = "BK-MM-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        // B 增加花生：集合不再完全相同
        saveAllergens(b, List.of("乳", "花生"), "CK-MM-B-PEANUT", 200);

        long batchesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM batch", Long.class);
        String child = "BK-MM-C-" + unique();
        merge(child, "LOT-C", "REQ-MM-1", List.of(a, b), 422);

        // 不生成子批、不写合批/血缘，父批状态与数量不变
        assertEquals(batchesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Long.class));
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("RELEASED", currentStatus(b));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage WHERE relation = 'MERGE'",
                Integer.class));
        assertEquals(List.of("乳"), allergensOf(a));
        assertEquals(List.of("乳", "花生"), allergensOf(b));

        // 具体拒绝原因可按 requestId 查询
        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-MM-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(lookup.getResponse().getContentAsString());
        assertEquals("REJECTED", node.path("outcome").asText());
        assertTrue(node.path("rejections").size() >= 1);
        boolean allergenReason = false;
        for (JsonNode r : node.path("rejections")) {
            if ("ALLERGEN_MISMATCH".equals(r.path("reasonCode").asText())) {
                allergenReason = true;
                assertTrue(r.path("reasonDetail").asText().contains(a)
                        && r.path("reasonDetail").asText().contains(b));
            }
        }
        assertTrue(allergenReason, "必须记录 ALLERGEN_MISMATCH 具体原因");
    }

    @Test
    void mergeWhenGenerationMismatch_returns422_andFrozenSnapshotNotMixed() throws Exception {
        String a = "BK-GM-A-" + unique();
        String b = "BK-GM-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        // B 代次推进到 4
        advanceGeneration(b, 4, "CK-GM-B4", 200);

        merge("BK-GM-C-" + unique(), "LOT-C", "REQ-GM-1", List.of(a, b), 422);
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("RELEASED", currentStatus(b));
        assertEquals(3, currentGeneration(a));
        assertEquals(4, currentGeneration(b));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));

        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-GM-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(lookup.getResponse().getContentAsString());
        assertEquals("GENERATION_MISMATCH",
                node.path("rejections").get(0).path("reasonCode").asText());
    }

    @Test
    void mergeWhenAnyParentStillQuarantined_returns422() throws Exception {
        String a = "BK-QM-A-" + unique();
        String b = "BK-QM-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        // B 仅创建，仍 QUARANTINED
        createBatch(b, "P1", List.of("乳"), 3, 201);

        merge("BK-QM-C-" + unique(), "LOT-C", "REQ-QM-1", List.of(a, b), 422);
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("QUARANTINED", currentStatus(b));

        // B 处于 PENDING_RELEASE 同样拒绝
        submitTest(b, "t1", "PASS", "insp-b", 201);
        merge("BK-QM-C2-" + unique(), "LOT-C2", "REQ-QM-2", List.of(a, b), 422);

        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-QM-1"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("PARENT_QUARANTINED", objectMapper
                .readTree(lookup.getResponse().getContentAsString())
                .path("rejections").get(0).path("reasonCode").asText());
    }

    @Test
    void mergeWithRecalledAncestor_returns422() throws Exception {
        // root 拆分为 childA，root 召回后 childA 即存在召回祖先
        String root = "BK-RC-ROOT-" + unique();
        createBatch(root, "P1", null, null, 201);
        releaseBatch(root, "insp-r");
        String childA = "BK-RC-A-" + unique();
        split(root, "CK-RC-S", List.of(new String[]{childA, "L1"},
                new String[]{"BK-RC-A2-" + unique(), "L2"}), 201);
        releaseBatch(childA, "insp-a");
        saveAllergens(childA, List.of("乳"), "CK-RC-AA", 200);
        advanceGeneration(childA, 3, "CK-RC-AG", 200);

        String b = "BK-RC-B-" + unique();
        prepareReleasedMilkParent(b, 3);

        recall(root, "u", "根批召回", "CK-RC-R", 201);
        merge("BK-RC-C-" + unique(), "LOT-C", "REQ-RC-1", List.of(childA, b), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));

        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-RC-1"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("RECALLED_ANCESTOR", objectMapper
                .readTree(lookup.getResponse().getContentAsString())
                .path("rejections").get(0).path("reasonCode").asText());
    }

    @Test
    void mergeDuplicatedOrMismatchedProductParents_returns422() throws Exception {
        String a = "BK-DP-A-" + unique();
        String b = "BK-DP-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);

        // 请求内父批重复
        merge("BK-DP-C-" + unique(), "LOT-C", "REQ-DP-1", List.of(a, a), 422);
        assertEquals("RELEASED", currentStatus(a));

        // 产品编码不一致
        String c = "BK-DP-C2-" + unique();
        createBatch(c, "P2-DIFFERENT", List.of("乳"), 3, 201);
        releaseBatch(c, "insp-c");
        merge("BK-DP-C3-" + unique(), "LOT-C3", "REQ-DP-2", List.of(a, c), 422);
        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-DP-2"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("PRODUCT_MISMATCH", objectMapper
                .readTree(lookup.getResponse().getContentAsString())
                .path("rejections").get(0).path("reasonCode").asText());
    }

    @Test
    void mergeUnknownParent_returns404() throws Exception {
        String a = "BK-404-A-" + unique();
        prepareReleasedMilkParent(a, 3);
        merge("BK-404-C-" + unique(), "LOT-C", "REQ-404-1",
                List.of(a, "NO-SUCH-PARENT"), 404);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        mockMvc.perform(get("/api/batches/merge/REQ-404-1")).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestId_parentReorderReplaysFirstResult_changedSetConflicts409() throws Exception {
        String a = "BK-ID-A-" + unique();
        String b = "BK-ID-B-" + unique();
        String c = "BK-ID-X-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        prepareReleasedMilkParent(c, 3);
        String child = "BK-ID-C-" + unique();

        MvcResult first = merge(child, "LOT-C", "REQ-ID-1", List.of(a, b), 201);
        // 父批换序：同参，重放首次快照
        MvcResult replay = merge(child, "LOT-C", "REQ-ID-1", List.of(b, a), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 只产生一个子批与一次合批
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));

        // 同 requestId 换父批集合（引入第三个父批）→ 指纹不一致 409
        merge(child, "LOT-C", "REQ-ID-1", List.of(a, c), 409);
    }

    @Test
    void rejectionDoesNotOccupyRequestId_fixParamsThenSameRequestIdSucceeds() throws Exception {
        String a = "BK-RJ-A-" + unique();
        String b = "BK-RJ-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);

        String child = "BK-RJ-C-" + unique();
        // 先因 B 多花生被拒
        saveAllergens(b, List.of("乳", "花生"), "CK-RJ-PEANUT", 200);
        merge(child, "LOT-C", "REQ-RJ-1", List.of(a, b), 422);

        // 修正 B 过敏原后同一 requestId 成功（拒绝记录仍保留可查）
        saveAllergens(b, List.of("乳"), "CK-RJ-FIX", 200);
        merge(child, "LOT-C", "REQ-RJ-1", List.of(a, b), 201);
        assertEquals("MERGED", currentStatus(a));
        assertEquals("MERGED", currentStatus(b));
        assertEquals("QUARANTINED", currentStatus(child));

        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-RJ-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(lookup.getResponse().getContentAsString());
        assertEquals("MERGED", node.path("outcome").asText());
        assertEquals(child, node.path("merge").path("childBatchKey").asText());
        // 历史拒绝原因未被删除
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_rejection WHERE request_id = 'REQ-RJ-1'",
                Integer.class) >= 1);
    }

    @Test
    void mergeChildKeyConflict_returns409_andConsumedParentCannotMergeAgain() throws Exception {
        String a = "BK-CC-A-" + unique();
        String b = "BK-CC-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        String existing = "BK-CC-EXIST-" + unique();
        createBatch(existing, "P1", null, null, 201);

        merge(existing, "LOT-C", "REQ-CC-1", List.of(a, b), 409);
        assertEquals("RELEASED", currentStatus(a));

        // 成功合批后，父批已 MERGED，再次合批整单 422
        String child = "BK-CC-C-" + unique();
        merge(child, "LOT-C2", "REQ-CC-2", List.of(a, b), 201);
        merge("BK-CC-D-" + unique(), "LOT-D", "REQ-CC-3", List.of(a, b), 422);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergeAndGenerationAdvance_neverProducesMixedGenerationChild() throws Exception {
        String a = "BK-CX-A-" + unique();
        String b = "BK-CX-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        String child = "BK-CX-C-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(child, "LOT-C", "REQ-CX-1", List.of(a, b)))),
                () -> callStatus(post("/api/batches/" + b + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenerationCmd("CK-CX-B4", 4))))
        );

        int mergeCode = results.get(0).get(30, TimeUnit.SECONDS);
        int advanceCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, advanceCode);
        Integer mergeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE request_id = 'REQ-CX-1'", Integer.class);
        if (mergeCode == 201) {
            // 合批先提交：子批固化代次必须为 3，两个父批快照也必须都是 3，绝无混合
            assertEquals(1, mergeCount);
            assertEquals(3, jdbc.queryForObject(
                    "SELECT cleaning_generation FROM batch_merge WHERE request_id = 'REQ-CX-1'",
                    Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_merge_parent WHERE request_id = 'REQ-CX-1'"
                            + " AND cleaning_generation = 3", Integer.class));
            assertEquals(3, currentGeneration(child));
            assertEquals("MERGED", currentStatus(a));
            assertEquals("MERGED", currentStatus(b));
        } else {
            // 代次先推进：合批 422，无子批
            assertEquals(422, mergeCode);
            assertEquals(0, mergeCount);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
            assertEquals("RELEASED", currentStatus(a));
            assertEquals("RELEASED", currentStatus(b));
        }
    }

    @Test
    void concurrentTwoMergesConsumingSameParents_exactlyOneSucceeds() throws Exception {
        String a = "BK-C2-A-" + unique();
        String b = "BK-C2-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        String c1 = "BK-C2-C1-" + unique();
        String c2 = "BK-C2-C2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(c1, "L1", "REQ-C2-1", List.of(a, b)))),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(c2, "L2", "REQ-C2-2", List.of(a, b))))
        );
        int code1 = results.get(0).get(30, TimeUnit.SECONDS);
        int code2 = results.get(1).get(30, TimeUnit.SECONDS);
        int ok = (code1 == 201 ? 1 : 0) + (code2 == 201 ? 1 : 0);
        int rejected = (code1 == 422 ? 1 : 0) + (code2 == 422 ? 1 : 0);
        assertEquals(1, ok, "同一组父批只能被成功合批一次");
        assertEquals(1, rejected, "失败者必须是 422 而非脏写");
        assertEquals("MERGED", currentStatus(a));
        assertEquals("MERGED", currentStatus(b));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge", Integer.class));
        // 每个父批仅留下一条指向唯一合批子批的 MERGE 血缘边
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation = 'MERGE' AND parent_key = ?",
                Integer.class, a));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE relation = 'MERGE' AND parent_key = ?",
                Integer.class, b));
    }

    // ---------- 冻结依据不被后续清洁改写 ----------

    @Test
    void laterGenerationAdvanceAndAllergenSave_doNotRewriteMergedBasis() throws Exception {
        String a = "BK-FR-A-" + unique();
        String b = "BK-FR-B-" + unique();
        prepareReleasedMilkParent(a, 3);
        prepareReleasedMilkParent(b, 3);
        String child = "BK-FR-C-" + unique();
        merge(child, "LOT-C", "REQ-FR-1", List.of(a, b), 201);

        // 合批后推进父批代次并改写其过敏原（父批当前值变化）
        advanceGeneration(a, 4, "CK-FR-A4", 200);
        saveAllergens(a, List.of("乳", "花生"), "CK-FR-AP", 200);
        assertEquals(4, currentGeneration(a));
        assertEquals(List.of("乳", "花生"), allergensOf(a));

        // 子批历史中的合批冻结依据仍是 gen3 / 仅 乳
        MvcResult history = mockMvc.perform(get("/api/batches/" + child + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode mergeNode = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("merge");
        assertFalse(mergeNode.isMissingNode());
        assertEquals(3, mergeNode.path("cleaningGeneration").asInt());
        assertEquals(List.of("乳"), objectMapper.convertValue(mergeNode.path("allergens"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        for (JsonNode parent : mergeNode.path("parents")) {
            assertEquals(3, parent.path("cleaningGeneration").asInt());
            assertEquals(List.of("乳"), objectMapper.convertValue(parent.path("allergens"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertEquals("RELEASED", parent.path("statusBeforeMerge").asText());
        }
        // 子批自身固化值不变
        assertEquals(3, currentGeneration(child));
        assertEquals(List.of("乳"), allergensOf(child));

        // lookup 与 history 一致
        MvcResult lookup = mockMvc.perform(get("/api/batches/merge/REQ-FR-1"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(3, objectMapper.readTree(lookup.getResponse().getContentAsString())
                .path("merge").path("cleaningGeneration").asInt());
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, List<String> allergens,
                             Integer cleaningGeneration) {
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private record AllergenCmd(String commandKey, List<String> allergens) {
    }

    private record GenerationCmd(String commandKey, Integer newGeneration) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, String productCode, List<String> allergens,
                             Integer generation, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                productCode == null ? "P1" : productCode, "LOT-" + batchKey,
                Instant.parse("2026-02-02T03:04:05Z"), List.of("t1"), allergens, generation));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    /**
     * 构造含 乳、指定清洁代次的批次并放行到 RELEASED。
     */
    private void prepareReleasedMilkParent(String batchKey, int generation) throws Exception {
        createBatch(batchKey, "P1", List.of("乳"), 1, 201);
        if (generation > 1) {
            advanceGeneration(batchKey, generation, "CK-PG-" + unique(), 200);
        }
        releaseBatch(batchKey, "insp-" + batchKey.substring(batchKey.length() - 4));
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        submitTest(batchKey, "t1", "PASS", inspector, 201);
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

    private void saveAllergens(String batchKey, List<String> allergens, String commandKey,
                               int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/allergens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AllergenCmd(commandKey, allergens))))
                .andExpect(status().is(expected));
    }

    private void advanceGeneration(String batchKey, int generation, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenerationCmd(commandKey, generation))))
                .andExpect(status().is(expected));
    }

    private String mergeBody(String childKey, String childBatchNo, String requestId,
                             List<String> parents) {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"childBatchKey\":\"").append(childKey)
                .append("\",\"childBatchNo\":\"").append(childBatchNo)
                .append("\",\"parentKeys\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parents.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private MvcResult merge(String childKey, String childBatchNo, String requestId,
                            List<String> parents, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(childKey, childBatchNo, requestId, parents)))
                .andExpect(status().is(expected)).andReturn();
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

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int currentGeneration(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("cleaningGeneration").asInt();
    }

    private List<String> allergensOf(String batchKey) {
        return jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                String.class, batchKey);
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
