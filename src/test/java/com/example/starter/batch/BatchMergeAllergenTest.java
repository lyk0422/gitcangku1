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
 * 合批过敏原隔离真实 H2 测试：集合兼容/不兼容、清洁代次一致/过期、父批隔离、
 * 集合规范化保存、requestId 幂等（换序同参、换集合 409）、冻结依据不被后续清洁改写、
 * 清洁与合批并发不产生混合版本子批、具体拒绝原因与 400/404 分支。
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
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch_merge_parent");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM batch_allergen");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 规范化保存 ----------

    @Test
    void createBatch_normalizesAllergens_andStoresCleaningGeneration() throws Exception {
        String key = "BK-NORM-" + unique();
        createBatch(key, List.of("t1"), List.of("peanut", "milk", "milk", " milk "), 3, 201);

        MvcResult result = mockMvc.perform(get("/api/batches/" + key + "/allergen-profile"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(List.of("milk", "peanut"), toStringList(node.path("allergens")),
                "过敏原保存时必须 trim、去重并稳定排序");
        assertEquals(3, node.path("cleaningGeneration").asInt());
        assertTrue(node.path("frozenBasis").isNull(), "普通批次无合批冻结依据");
        // 落库即规范化顺序
        assertEquals(List.of("milk", "peanut"), jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                String.class, key));
    }

    // ---------- 合批主流程 ----------

    @Test
    void merge_sameMilkSetSameGeneration3_succeeds_andFreezesSnapshot() throws Exception {
        String a = "BK-MG-A-" + unique();
        String b = "BK-MG-B-" + unique();
        createBatch(a, List.of("t1", "t2"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t2", "t3"), List.of("milk"), 3, 201);
        releaseBatch(a, "insp-a");
        releaseBatch(b, "insp-b");

        String child = "BK-MG-C-" + unique();
        MvcResult result = merge("CK-MG-1", child, "LOT-C", "PROD-X", List.of(a, b), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("QUARANTINED", body.path("childStatus").asText());
        assertEquals(List.of("milk"), toStringList(body.path("frozenAllergens")));
        assertEquals(3, body.path("frozenCleaningGeneration").asInt());
        // 父批按业务键排序后展示，与请求顺序无关
        assertEquals(List.of(a, b), toStringList(body.path("parentBatchKeys")));
        // 必做检验项为全部父批去重并集，稳定排序
        assertEquals(List.of("t1", "t2", "t3"), toStringList(body.path("requiredTests")));

        // 子批档案：固化依据完整
        JsonNode profile = profile(child);
        assertEquals(List.of("milk"), toStringList(profile.path("allergens")));
        assertEquals(3, profile.path("cleaningGeneration").asInt());
        JsonNode frozen = profile.path("frozenBasis");
        assertFalse(frozen.isNull());
        assertEquals(List.of("milk"), toStringList(frozen.path("allergens")));
        assertEquals(3, frozen.path("cleaningGeneration").asInt());
        assertEquals(List.of(a, b), toStringList(frozen.path("parentBatchKeys")));

        // 父批状态不变（仍 RELEASED，不被合批消耗）
        assertEquals("RELEASED", statusOf(a));
        assertEquals("RELEASED", statusOf(b));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE child_key = ? AND cleaning_generation = 3",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?", Integer.class, child));
    }

    @Test
    void merge_emptyAllergenSets_areCompatible() throws Exception {
        String a = "BK-EM-A-" + unique();
        String b = "BK-EM-B-" + unique();
        createBatch(a, List.of("t1"), List.of(), 2, 201);
        createBatch(b, List.of("t1"), List.of(), 2, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        String child = "BK-EM-C-" + unique();
        MvcResult result = merge("CK-EM-1", child, "L", "P", List.of(a, b), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, body.path("frozenAllergens").size());
        assertEquals(2, body.path("frozenCleaningGeneration").asInt());
        assertEquals(0, profile(child).path("allergens").size());
    }

    // ---------- 失败分支：422 且不留任何痕迹 ----------

    @Test
    void incompatibleAllergenSet_returns422_createsNothing_andStatesSpecificReason() throws Exception {
        String a = "BK-IA-A-" + unique();
        String b = "BK-IA-B-" + unique();
        // A、B 均含乳可合批的基线先验证一次，随后 B 增加花生必须整单 422
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk", "peanut"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");

        String child = "BK-IA-C-" + unique();
        MvcResult result = merge("CK-IA-1", child, "L", "P", List.of(a, b), 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", error.path("code").asText());
        String message = error.path("message").asText();
        assertTrue(message.contains("不兼容"), "拒绝原因必须具体说明集合不兼容: " + message);
        assertTrue(message.contains(b), "拒绝原因必须点名父批: " + message);
        assertTrue(message.contains("peanut"), "拒绝原因必须包含差异集合内容: " + message);

        assertNoMergeTrace(child, a, b);
        assertEquals("RELEASED", statusOf(a));
        assertEquals("RELEASED", statusOf(b));
    }

    @Test
    void staleCleaningGeneration_returns422_createsNothing_andStatesSpecificReason() throws Exception {
        String a = "BK-GN-A-" + unique();
        String b = "BK-GN-B-" + unique();
        // 均含乳、代次 3 可合批；B 代次推进到 4 后整单 422
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        updateCleaning(b, 4, "CK-GN-UP", 200);
        assertEquals(4, profile(b).path("cleaningGeneration").asInt());

        String child = "BK-GN-C-" + unique();
        MvcResult result = merge("CK-GN-1", child, "L", "P", List.of(a, b), 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        String message = error.path("message").asText();
        assertTrue(message.contains("代次"), "拒绝原因必须具体说明清洁代次不一致: " + message);
        assertTrue(message.contains(b), "拒绝原因必须点名过期父批: " + message);
        assertTrue(message.contains("4"), "拒绝原因必须包含实际代次: " + message);

        assertNoMergeTrace(child, a, b);
        assertEquals(3, profile(a).path("cleaningGeneration").asInt());
        assertEquals(4, profile(b).path("cleaningGeneration").asInt());
    }

    @Test
    void quarantinedParent_returns422_createsNothing() throws Exception {
        String a = "BK-QR-A-" + unique();
        String b = "BK-QR-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        // b 仍处于 QUARANTINED

        String child = "BK-QR-C-" + unique();
        MvcResult result = merge("CK-QR-1", child, "L", "P", List.of(a, b), 422);
        String message = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains(b) && message.contains("QUARANTINED"),
                "拒绝原因必须点名仍隔离的父批及其状态: " + message);
        assertNoMergeTrace(child, a, b);
        assertEquals("QUARANTINED", statusOf(b));
    }

    @Test
    void rejectedAndRecalledParents_alsoReturn422() throws Exception {
        String released = "BK-RR-OK-" + unique();
        String rejected = "BK-RR-REJ-" + unique();
        createBatch(released, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(rejected, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(released, "i1");
        submitTest(rejected, "t1", "FAIL", "i2", 201);
        assertEquals("REJECTED", statusOf(rejected));
        merge("CK-RR-1", "BK-RR-C1-" + unique(), "L", "P", List.of(released, rejected), 422);

        String recalled = "BK-RR-RC-" + unique();
        createBatch(recalled, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(recalled, "i3");
        recall(recalled, "u", "原因", "CK-RR-RC", 201);
        merge("CK-RR-2", "BK-RR-C2-" + unique(), "L", "P", List.of(released, recalled), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    @Test
    void parentWithRecalledAncestor_returns422() throws Exception {
        // root 拆分为 child（RELEASED）与 sibling；root 召回后 child 自身状态仍是 RELEASED 但不可用于合批
        String root = "BK-RA-R-" + unique();
        createBatch(root, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(root, "i0");
        String child = "BK-RA-C-" + unique();
        String sibling = "BK-RA-S-" + unique();
        split(root, "CK-RA-SP", List.of(new String[]{child, "L1"}, new String[]{sibling, "L2"}), 201);
        releaseBatch(child, "i1");
        releaseBatch(sibling, "i2");
        recall(root, "u", "原料问题", "CK-RA-RC", 201);
        assertEquals("RELEASED", statusOf(child), "后代自身状态不改写");

        MvcResult result = merge("CK-RA-1", "BK-RA-D-" + unique(), "L", "P",
                List.of(child, sibling), 422);
        String message = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains(root) && message.contains("已召回"),
                "拒绝原因必须点名召回祖先: " + message);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    // ---------- 幂等 ----------
    @Test
    void mergeReplay_sameRequest_returnsFirstSnapshot_andReorderedParentsAreSameParams() throws Exception {
        String a = "BK-ID-A-" + unique();
        String b = "BK-ID-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        String child = "BK-ID-C-" + unique();

        String body1 = mergeBody("CK-ID-1", child, "LOT-C", "PROD-X", List.of(a, b));
        MvcResult first = mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated()).andReturn();
        // 父批换序：同参，必须重放首次结果
        String reordered = mergeBody("CK-ID-1", child, "LOT-C", "PROD-X", List.of(b, a));
        MvcResult replay = mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 只生成一个子批、一组合批关系
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent", Integer.class));
    }

    @Test
    void sameCommandKey_changedParentSet_returns409() throws Exception {
        String a = "BK-CF-A-" + unique();
        String b = "BK-CF-B-" + unique();
        String c = "BK-CF-C-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(c, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        releaseBatch(c, "i3");

        String child = "BK-CF-D-" + unique();
        merge("CK-CF-1", child, "L", "P", List.of(a, b), 201);
        // 同 commandKey 换父批集合（A+C）→ 409，不新增任何子批
        merge("CK-CF-1", "BK-CF-E-" + unique(), "L2", "P", List.of(a, c), 409);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        // 同 commandKey 改子批批号同样 409
        merge("CK-CF-1", child, "L-CHANGED", "P", List.of(a, b), 409);
    }

    @Test
    void duplicateChildKeyFromDifferentCommand_returns409() throws Exception {
        String a = "BK-DC-A-" + unique();
        String b = "BK-DC-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        String child = "BK-DC-C-" + unique();
        merge("CK-DC-1", child, "L", "P", List.of(a, b), 201);
        merge("CK-DC-2", child, "L", "P", List.of(a, b), 409);
    }

    // ---------- 冻结依据不被后续清洁改写 ----------

    @Test
    void laterCleaning_doesNotRewriteFrozenBasis() throws Exception {
        String a = "BK-FR-A-" + unique();
        String b = "BK-FR-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        String child = "BK-FR-C-" + unique();
        merge("CK-FR-1", child, "L", "P", List.of(a, b), 201);

        // 合批后父批与子批各自推进清洁代次
        updateCleaning(a, 4, "CK-FR-UP-A", 200);
        updateCleaning(b, 5, "CK-FR-UP-B", 200);
        updateCleaning(child, 6, "CK-FR-UP-C", 200);

        JsonNode profile = profile(child);
        assertEquals(6, profile.path("cleaningGeneration").asInt(), "子批当前代次可推进");
        JsonNode frozen = profile.path("frozenBasis");
        assertEquals(3, frozen.path("cleaningGeneration").asInt(),
                "已合批的历史依据代次必须保持 3，不被后续清洁改写");
        assertEquals(List.of("milk"), toStringList(frozen.path("allergens")),
                "冻结过敏原集合不被改写");
        assertEquals(3, jdbc.queryForObject(
                "SELECT cleaning_generation FROM batch_merge WHERE child_key = ?",
                Integer.class, child));
        // 重放合批命令仍返回首次快照（代次 3）
        MvcResult replay = mergeRaw(
                mergeBody("CK-FR-1", child, "L", "P", List.of(b, a)), 201);
        assertEquals(3, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("frozenCleaningGeneration").asInt());
    }

    // ---------- 清洁代次更新约束 ----------

    @Test
    void cleaningGeneration_isMonotonic_andIdempotent() throws Exception {
        String key = "BK-CL-" + unique();
        createBatch(key, List.of("t1"), List.of("milk"), 3, 201);

        String body = cleaningBody("CK-CL-1", 4);
        MvcResult first = mockMvc.perform(post("/api/batches/" + key + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + key + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(4, profile(key).path("cleaningGeneration").asInt());

        // 回退到更小代次 → 422；原地不变 → 409
        updateCleaning(key, 2, "CK-CL-2", 422);
        updateCleaning(key, 4, "CK-CL-3", 409);
        // 同键改参 → 409
        mockMvc.perform(post("/api/batches/" + key + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON).content(cleaningBody("CK-CL-1", 5)))
                .andExpect(status().isConflict());
        assertEquals(4, profile(key).path("cleaningGeneration").asInt());
    }

    // ---------- 并发：清洁与合批不得形成混合版本子批 ----------

    @Test
    void concurrentCleaningAndMerge_neverProducesMixedVersionChild() throws Exception {
        String a = "BK-CC-A-" + unique();
        String b = "BK-CC-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");
        String child = "BK-CC-C-" + unique();

        // 重复若干轮以提高交错概率：每轮使用全新批次
        for (int round = 0; round < 5; round++) {
            String ra = "BK-CCR" + round + "-A-" + unique();
            String rb = "BK-CCR" + round + "-B-" + unique();
            String rc = "BK-CCR" + round + "-C-" + unique();
            createBatch(ra, List.of("t1"), List.of("milk"), 3, 201);
            createBatch(rb, List.of("t1"), List.of("milk"), 3, 201);
            releaseBatch(ra, "i1");
            releaseBatch(rb, "i2");
            String rMerge = mergeBody("CK-CCR" + round, rc, "L", "P", List.of(ra, rb));
            String rClean = cleaningBody("CK-CCR" + round + "-UP", 4);

            List<Future<Integer>> results = runConcurrent(
                    () -> callStatus(post("/api/batches/merges")
                            .contentType(MediaType.APPLICATION_JSON).content(rMerge)),
                    () -> callStatus(post("/api/batches/" + rb + "/cleaning-generation")
                            .contentType(MediaType.APPLICATION_JSON).content(rClean))
            );
            int mergeStatus = results.get(0).get(30, TimeUnit.SECONDS);
            int cleanStatus = results.get(1).get(30, TimeUnit.SECONDS);
            assertEquals(200, cleanStatus, "清洁更新本身应成功（单调递增）");

            Integer childRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, rc);
            Integer mergeRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, rc);
            if (mergeStatus == 201) {
                // 合批先提交：子批必须整体固化代次 3；清洁随后生效只改父批
                assertEquals(1, childRows);
                assertEquals(1, mergeRows);
                JsonNode frozen = profile(rc).path("frozenBasis");
                assertEquals(3, frozen.path("cleaningGeneration").asInt());
                assertEquals(List.of("milk"), toStringList(frozen.path("allergens")));
                assertEquals(4, profile(rb).path("cleaningGeneration").asInt());
                assertEquals(3, profile(ra).path("cleaningGeneration").asInt());
            } else {
                // 清洁先提交：两代次不一致，整单 422，绝无任何子批或合批痕迹
                assertEquals(422, mergeStatus);
                assertEquals(0, childRows, "代次过期时不得生成子批");
                assertEquals(0, mergeRows, "代次过期时不得写入合批依据");
            }
        }
    }

    // ---------- 400 / 404 ----------

    @Test
    void mergeInvalidBodies_return400() throws Exception {
        String a = "BK-400-A-" + unique();
        String b = "BK-400-B-" + unique();
        createBatch(a, List.of("t1"), List.of("milk"), 3, 201);
        createBatch(b, List.of("t1"), List.of("milk"), 3, 201);
        releaseBatch(a, "i1");
        releaseBatch(b, "i2");

        // 只有 1 个父批
        mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-400-1", "BK-400-C-" + unique(), "L", "P", List.of(a))))
                .andExpect(status().isBadRequest());
        // 请求内父批重复
        mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-400-2", "BK-400-D-" + unique(), "L", "P", List.of(a, a))))
                .andExpect(status().isBadRequest());
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childBatchKey\":\"BK-400-E-" + unique()
                                + "\",\"childBatchNo\":\"L\",\"productCode\":\"P\",\"parents\":[\""
                                + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isBadRequest());
        // 清洁代次非法（负数）
        mockMvc.perform(post("/api/batches/" + a + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-400-3\",\"cleaningGeneration\":-1}"))
                .andExpect(status().isBadRequest());
        // 父批均未被动过
        assertEquals("RELEASED", statusOf(a));
        assertEquals("RELEASED", statusOf(b));
    }

    @Test
    void mergeUnknownParent_returns404_profileUnknownReturns404() throws Exception {
        merge("CK-404", "BK-404-C-" + unique(), "L", "P",
                List.of("NO-SUCH-A", "NO-SUCH-B"), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/allergen-profile"))
                .andExpect(status().isNotFound());
        updateCleaning("NO-SUCH-BATCH", 9, "CK-404-UP", 404);
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests,
                             List<String> allergens, Integer cleaningGeneration) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String key, List<String> tests, List<String> allergens,
                             int generation, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), key, "PROD-1",
                "LOT-" + key, Instant.parse("2026-03-01T00:00:00Z"), tests, allergens, generation));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-T-" + unique(), "TK-" + unique(), item, result, inspector))))
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

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        List<String> tests = jdbc.queryForList(
                "SELECT test_item FROM batch_required_test WHERE batch_key = ? ORDER BY seq",
                String.class, batchKey);
        for (String item : tests) {
            submitTest(batchKey, item, "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", statusOf(batchKey));
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

    private String mergeBody(String commandKey, String child, String childNo, String product,
                             List<String> parents) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"childBatchKey\":\"").append(child)
                .append("\",\"childBatchNo\":\"").append(childNo)
                .append("\",\"productCode\":\"").append(product)
                .append("\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parents.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private MvcResult merge(String commandKey, String child, String childNo, String product,
                            List<String> parents, int expected) throws Exception {
        return mergeRaw(mergeBody(commandKey, child, childNo, product, parents), expected);
    }

    private MvcResult mergeRaw(String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private String cleaningBody(String commandKey, int generation) {
        return "{\"commandKey\":\"" + commandKey + "\",\"cleaningGeneration\":" + generation + "}";
    }

    private void updateCleaning(String batchKey, int generation, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/cleaning-generation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cleaningBody(commandKey, generation)))
                .andExpect(status().is(expected));
    }

    private JsonNode profile(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/allergen-profile"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String statusOf(String batchKey) throws Exception {
        return profile(batchKey).path("status").asText();
    }

    private List<String> toStringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    /**
     * 失败合批不得留下任何痕迹：无子批、无合批依据、无父子关系、父批状态与清洁代次不变。
     */
    private void assertNoMergeTrace(String child, String a, String b) {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge WHERE child_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent WHERE child_key = ?",
                Integer.class, child));
        // 失败的命令不写 command_log
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'MERGE'", Integer.class));
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
