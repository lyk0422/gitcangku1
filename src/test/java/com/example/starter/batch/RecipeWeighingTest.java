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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配方称量测试：配方版本校验、批次绑定、称量累加、更正替代、放行配方门禁、
 * requestId 幂等与状态门禁，全部在真实 H2（MODE=MySQL）上验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecipeWeighingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM batch_recipe_binding");
        jdbc.update("DELETE FROM recipe_ingredient");
        jdbc.update("DELETE FROM recipe");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 配方版本 ----------

    @Test
    void createRecipe_validationAndImmutability() throws Exception {
        // 成分数越界：1 种与 7 种均 400
        createRecipe("RID-1", "RCP-1", new Object[][]{{"A", "100"}}, 400);
        createRecipe("RID-2", "RCP-2", new Object[][]{{"A", "20"}, {"B", "20"}, {"C", "20"},
                {"D", "20"}, {"E", "10"}, {"F", "5"}, {"G", "5"}}, 400);
        // 成分名重复 400
        createRecipe("RID-3", "RCP-3", new Object[][]{{"A", "50"}, {"A", "50"}}, 400);
        // 负值与零 400
        createRecipe("RID-4", "RCP-4", new Object[][]{{"A", "150"}, {"B", "-50"}}, 400);
        createRecipe("RID-5", "RCP-5", new Object[][]{{"A", "100"}, {"B", "0"}}, 400);
        // 百分比超 4 位小数 400
        createRecipe("RID-6", "RCP-6", new Object[][]{{"A", "33.33333"}, {"B", "66.66667"}}, 400);
        // 合计不等于 100 400
        createRecipe("RID-7", "RCP-7", new Object[][]{{"A", "50.0001"}, {"B", "50"}}, 400);
        // 合法：4 位小数合计精确 100
        createRecipe("RID-8", "RCP-8", new Object[][]{{"A", "33.3333"}, {"B", "66.6667"}}, 201);
        // 不可变：同 recipeKey 不同内容 409
        createRecipe("RID-9", "RCP-8", new Object[][]{{"A", "50"}, {"B", "50"}}, 409);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recipe WHERE recipe_key = 'RCP-8'",
                Integer.class));
    }

    @Test
    void createRecipe_requestIdReplayAndConflict() throws Exception {
        MvcResult first = createRecipe("RID-R", "RCP-R", new Object[][]{{"A", "50"}, {"B", "50"}}, 201);
        MvcResult replay = createRecipe("RID-R", "RCP-R", new Object[][]{{"A", "50"}, {"B", "50"}}, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次完整响应");
        // 同 requestId 异参 409
        createRecipe("RID-R", "RCP-R", new Object[][]{{"A", "60"}, {"B", "40"}}, 409);
        // 失败不占键：非法参数失败后，同 requestId 换合法参数可成功
        createRecipe("RID-F", "RCP-F", new Object[][]{{"A", "150"}, {"B", "-50"}}, 400);
        createRecipe("RID-F", "RCP-F", new Object[][]{{"A", "50"}, {"B", "50"}}, 201);
    }

    // ---------- 批次绑定 ----------

    @Test
    void bindRecipe_successAndFailures() throws Exception {
        createRecipe("RID-B0", "RCP-B", new Object[][]{{"A", "50"}, {"B", "50"}}, 201);
        String batchKey = "BK-BIND-" + unique();
        createBatch(batchKey);
        // 未知批次 404、未知配方 404
        bind("NO-SUCH", "RID-B1", "RCP-B", "100", 404);
        bind(batchKey, "RID-B2", "NO-SUCH-RECIPE", "100", 404);
        // 目标质量非正值、超 6 位小数 400
        bind(batchKey, "RID-B3", "RCP-B", "0", 400);
        bind(batchKey, "RID-B4", "RCP-B", "-5", 400);
        bind(batchKey, "RID-B5", "RCP-B", "0.0000001", 400);
        // 成功绑定，重放返回首次响应
        MvcResult first = bind(batchKey, "RID-B6", "RCP-B", "100.000001", 201);
        MvcResult replay = bind(batchKey, "RID-B6", "RCP-B", "100.000001", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 异参 409；重复绑定 409
        bind(batchKey, "RID-B6", "RCP-B", "50", 409);
        bind(batchKey, "RID-B7", "RCP-B", "50", 409);
    }
    // ---------- 称量累加与输入校验 ----------

    @Test
    void weighing_accumulatesAndValidatesInput() throws Exception {
        String batchKey = prepareBoundBatch("RCP-W", "100");
        // 同成分多次称量累加
        MvcResult w1 = weigh(batchKey, "RID-W1", "A", "20", 0, 201);
        assertTotals(w1, "20", "0");
        MvcResult w2 = weigh(batchKey, "RID-W2", "A", "30", 1, 201);
        assertTotals(w2, "50", "0");
        MvcResult w3 = weigh(batchKey, "RID-W3", "B", "50", 2, 201);
        assertTotals(w3, "50", "50");
        assertEquals(3, json(w3).path("chainVersion").asInt());
        // 负量、零、未知成分、超精度 400
        weigh(batchKey, "RID-W4", "A", "-1", 3, 400);
        weigh(batchKey, "RID-W5", "A", "0", 3, 400);
        weigh(batchKey, "RID-W6", "未知成分", "1", 3, 400);
        weigh(batchKey, "RID-W7", "A", "0.0000001", 3, 400);
        // 期望版本不一致 409
        weigh(batchKey, "RID-W8", "A", "1", 99, 409);
        // 缺操作人 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-W9\",\"ingredient\":\"A\",\"massGrams\":1,"
                                + "\"expectedVersion\":3}"))
                .andExpect(status().isBadRequest());
        // 未绑定配方的批次称量 422
        String unbound = "BK-UNBOUND-" + unique();
        createBatch(unbound);
        weigh(unbound, "RID-W10", "A", "1", 0, 422);
        // 失败请求均未改变链版本
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void correction_replacesInsteadOfAccumulating() throws Exception {
        String batchKey = prepareBoundBatch("RCP-C", "100");
        long id1 = weighingId(weigh(batchKey, "RID-C1", "A", "30", 0, 201));
        long id2 = weighingId(weigh(batchKey, "RID-C2", "A", "25", 1, 201));
        // 只能更正该成分最近有效称量：更正 id1 409
        correct(batchKey, "RID-C3", id1, "A", "10", 2, 409);
        // 更正 id2 为 20：总量按替代量重算为 30+20=50，而不是重复累加
        MvcResult fixed = correct(batchKey, "RID-C4", id2, "A", "20", 2, 201);
        assertTotals(fixed, "50", "0");
        assertEquals(id2, json(fixed).path("replacesWeighingId").asLong());
        // 已作废记录不能再更正
        correct(batchKey, "RID-C5", id2, "A", "10", 3, 409);
        // 未知称量ID 404；成分与被更正记录不一致 409；未知成分 400；负量 400；超精度 400
        correct(batchKey, "RID-C6", 999999L, "A", "10", 3, 404);
        long id3 = json(fixed).path("weighingId").asLong();
        correct(batchKey, "RID-C7", id3, "B", "10", 3, 409);
        correct(batchKey, "RID-C8", id3, "未知成分", "10", 3, 400);
        correct(batchKey, "RID-C9", id3, "A", "-5", 3, 400);
        correct(batchKey, "RID-C10", id3, "A", "1.0000001", 3, 400);
        // 数据库核对：id2 已被 id3 作废，有效记录为 id1(30) 与 id3(20)
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE id = ? AND superseded_by = ?",
                Integer.class, id2, id3));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NULL",
                Integer.class, batchKey));
    }

    @Test
    void weighAndCorrect_requestIdIdempotency() throws Exception {
        String batchKey = prepareBoundBatch("RCP-I", "100");
        // 失败不占键：负量 400 后同 requestId 合法参数成功
        weigh(batchKey, "RID-I1", "A", "-1", 0, 400);
        MvcResult first = weigh(batchKey, "RID-I1", "A", "50", 0, 201);
        MvcResult replay = weigh(batchKey, "RID-I1", "A", "50", 0, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 异参 409
        weigh(batchKey, "RID-I1", "A", "60", 0, 409);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey));
        // 更正幂等：同 requestId 重放返回首次响应且不新增记录
        long id1 = weighingId(first);
        MvcResult c1 = correct(batchKey, "RID-I2", id1, "A", "45", 1, 201);
        MvcResult c2 = correct(batchKey, "RID-I2", id1, "A", "45", 1, 201);
        assertEquals(c1.getResponse().getContentAsString(), c2.getResponse().getContentAsString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey));
        correct(batchKey, "RID-I2", id1, "A", "46", 1, 409);
    }
    // ---------- 放行配方门禁 ----------

    @Test
    void releaseGate_50plus50of100g_releases() throws Exception {
        String batchKey = prepareBoundBatch("RCP-G1", "100");
        weigh(batchKey, "RID-G1", "A", "50", 0, 201);
        weigh(batchKey, "RID-G2", "B", "50", 1, 201);
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseGate_outOfTolerance_rejectedWithDeviations_thenCorrected() throws Exception {
        String batchKey = prepareBoundBatch("RCP-G2", "100");
        // 50.100001 + 49.899999 = 100，但各自占比偏差 0.100001 > 0.1，判定不提前舍入
        long id1 = weighingId(weigh(batchKey, "RID-G3", "A", "50.100001", 0, 201));
        weigh(batchKey, "RID-G4", "B", "49.899999", 1, 201);
        submitPass(batchKey);
        MvcResult denied = approve(batchKey, "qa-1", "QUALITY", 422);
        JsonNode deviations = json(denied).path("deviations");
        assertEquals(2, deviations.size(), "各成分差额全部给出");
        assertDeviation(deviations.get(0), "A", "50.100001", "0.100001");
        assertDeviation(deviations.get(1), "B", "49.899999", "-0.100001");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey), "不达配方不得批准");
        // 两项都更正为精确 50 后可放行
        correct(batchKey, "RID-G5", id1, "A", "50", 2, 201);
        correct(batchKey, "RID-G6", latestValidWeighingId(batchKey, "B"), "B", "50", 3, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseGate_missingIngredient_totalMismatch_andNoPartialApproval() throws Exception {
        // 三成分配方 50/30/20：只称 A 时成分不齐全 422
        createRecipe("RID-G6", "RCP-G3", new Object[][]{{"A", "50"}, {"B", "30"}, {"C", "20"}}, 201);
        String batchKey = "BK-G3-" + unique();
        createBatch(batchKey);
        bind(batchKey, "RID-G7", "RCP-G3", "100", 201);
        weigh(batchKey, "RID-G8", "A", "100", 0, 201);
        submitPass(batchKey);
        MvcResult missing = approve(batchKey, "qa-1", "QUALITY", 422);
        assertEquals(3, json(missing).path("deviations").size());
        // 总质量不等于目标 422：A 50 + B 30 + C 19 = 99
        weigh(batchKey, "RID-G9", "B", "30", 1, 201);
        weigh(batchKey, "RID-G10", "C", "19", 2, 201);
        // 当前 A=100,B=30,C=19，总量 149 不等于目标且占比全偏
        approve(batchKey, "qa-1", "QUALITY", 422);
        // 修正 A 为 50、补 C 到 20：A 合格但 B 超差时仍不得放行（不允许只批准合格成分）
        long idA = latestValidWeighingId(batchKey, "A");
        correct(batchKey, "RID-G11", idA, "A", "50", 3, 201);
        long idC = latestValidWeighingId(batchKey, "C");
        correct(batchKey, "RID-G12", idC, "C", "19.95", 4, 201);
        // A=50(偏差0) B=30(偏差0) C=19.95(偏差0.05) 全部达标但总量 99.95 ≠ 100 → 422
        MvcResult totalMismatch = approve(batchKey, "qa-1", "QUALITY", 422);
        assertEquals(3, json(totalMismatch).path("deviations").size());
        // B 超差场景：A、C 合格也不能放行（不允许只批准合格成分）
        correct(batchKey, "RID-G13", latestValidWeighingId(batchKey, "A"), "A", "49.9", 5, 201);
        correct(batchKey, "RID-G14", latestValidWeighingId(batchKey, "B"), "B", "30.2", 6, 201);
        correct(batchKey, "RID-G15", latestValidWeighingId(batchKey, "C"), "C", "19.9", 7, 201);
        // A=49.9(偏差0.1) B=30.2(偏差0.2) C=19.9(偏差0.1) 总量恰为 100，仅 B 超差
        MvcResult partial = approve(batchKey, "qa-1", "QUALITY", 422);
        JsonNode deviations = json(partial).path("deviations");
        assertEquals(3, deviations.size());
        assertDeviation(deviations.get(1), "B", "30.2", "0.2");
        // 全部修正后放行
        correct(batchKey, "RID-G16", latestValidWeighingId(batchKey, "A"), "A", "50", 8, 201);
        correct(batchKey, "RID-G17", latestValidWeighingId(batchKey, "B"), "B", "30", 9, 201);
        correct(batchKey, "RID-G18", latestValidWeighingId(batchKey, "C"), "C", "20", 10, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseGate_unboundBatchNotAffected() throws Exception {
        String batchKey = "BK-NOBIND-" + unique();
        createBatch(batchKey);
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }
    // ---------- 状态门禁与历史 ----------

    @Test
    void statusGates_releasedFrozen_splitParentLocked_rejectedLocked() throws Exception {
        // 放行冻结称量链
        String released = prepareBoundBatch("RCP-S1", "100");
        weigh(released, "RID-S1", "A", "50", 0, 201);
        weigh(released, "RID-S2", "B", "50", 1, 201);
        submitPass(released);
        approve(released, "qa-1", "QUALITY", 201);
        approve(released, "ops-1", "OPERATIONS", 201);
        weigh(released, "RID-S3", "A", "1", 2, 409);
        correct(released, "RID-S4", latestValidWeighingId(released, "A"), "A", "49", 2, 409);
        // 已拒绝批次不可称量
        String rejected = prepareBoundBatch("RCP-S2", "100");
        submitTest(rejected, "FAIL", 201);
        weigh(rejected, "RID-S5", "A", "1", 0, 409);
        // 已拆父批不能再更正
        String parent = prepareBoundBatch("RCP-S3", "100");
        weigh(parent, "RID-S6", "A", "50", 0, 201);
        weigh(parent, "RID-S7", "B", "50", 1, 201);
        submitPass(parent);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);
        String child1 = "BK-SP1-" + unique();
        String child2 = "BK-SP2-" + unique();
        split(parent, "CK-SP-" + unique(), new String[][]{{child1, "L1", "60"}, {child2, "L2", "40"}}, 201);
        weigh(parent, "RID-S8", "A", "1", 2, 409);
        correct(parent, "RID-S9", latestValidWeighingId(parent, "A"), "A", "49", 2, 409);
    }

    @Test
    void ancestorRecall_blocksChildWeighing() throws Exception {
        String parent = prepareBoundBatch("RCP-RC", "100");
        weigh(parent, "RID-RC1", "A", "50", 0, 201);
        weigh(parent, "RID-RC2", "B", "50", 1, 201);
        submitPass(parent);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);
        String child = "BK-RC-C-" + unique();
        split(parent, "CK-RC-" + unique(),
                new String[][]{{child, "L1", "40"}, {"BK-RC-D-" + unique(), "L2", "60"}}, 201);
        // 子批继承配方绑定与成分质量，可继续称量
        weigh(child, "RID-RC3", "A", "1", 2, 201);
        // 召回父批后，后代禁止称量与更正
        recall(parent, 201);
        weigh(child, "RID-RC4", "A", "1", 3, 422);
        correct(child, "RID-RC5", latestValidWeighingId(child, "A"), "A", "2", 3, 422);
    }

    @Test
    void history_containsBindingAndWeighingChain() throws Exception {
        String batchKey = prepareBoundBatch("RCP-H", "100");
        long id1 = weighingId(weigh(batchKey, "RID-H1", "A", "60", 0, 201));
        weigh(batchKey, "RID-H2", "B", "40", 1, 201);
        correct(batchKey, "RID-H3", id1, "A", "50", 2, 201);
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = json(history);
        assertEquals("RCP-H", node.path("recipeBinding").path("recipeKey").asText());
        assertEquals(0, new BigDecimal("100").compareTo(
                new BigDecimal(node.path("recipeBinding").path("targetMassGrams").asText())));
        JsonNode weighings = node.path("weighings");
        assertEquals(3, weighings.size());
        assertEquals("WEIGH", weighings.get(0).path("kind").asText());
        assertEquals(id1, weighings.get(0).path("weighingId").asLong());
        assertEquals("CORRECTION", weighings.get(2).path("kind").asText());
        assertEquals(id1, weighings.get(2).path("replacesWeighingId").asLong());
        assertEquals(weighings.get(2).path("weighingId").asLong(),
                weighings.get(0).path("supersededByWeighingId").asLong());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long weighingId(MvcResult result) throws Exception {
        return json(result).path("weighingId").asLong();
    }

    private void assertTotals(MvcResult result, String expectedA, String expectedB) throws Exception {
        JsonNode totals = json(result).path("totals");
        assertEquals(0, new BigDecimal(expectedA).compareTo(
                new BigDecimal(totals.path("A").asText())), "成分 A 累计量");
        assertEquals(0, new BigDecimal(expectedB).compareTo(
                new BigDecimal(totals.path("B").asText())), "成分 B 累计量");
    }

    private void assertDeviation(JsonNode deviation, String ingredient, String actualMass,
                                 String difference) {
        assertEquals(ingredient, deviation.path("ingredient").asText());
        assertEquals(0, new BigDecimal(actualMass).compareTo(
                new BigDecimal(deviation.path("actualMassGrams").asText())), "实际量");
        assertEquals(0, new BigDecimal(difference).compareTo(
                new BigDecimal(deviation.path("differenceGrams").asText())), "差额");
    }
    private long latestValidWeighingId(String batchKey, String ingredient) {
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM weighing WHERE batch_key = ? AND ingredient = ?"
                        + " AND superseded_by IS NULL",
                Long.class, batchKey, ingredient);
        assertNotNull(id, "应存在有效称量记录");
        return id;
    }

    /**
     * 创建 50/50 配方、批次并绑定目标质量。
     */
    private String prepareBoundBatch(String recipeKey, String targetMass) throws Exception {
        createRecipe("RID-P-" + unique(), recipeKey, new Object[][]{{"A", "50"}, {"B", "50"}}, 201);
        String batchKey = "BK-" + recipeKey + "-" + unique();
        createBatch(batchKey);
        bind(batchKey, "RID-PB-" + unique(), recipeKey, targetMass, 201);
        return batchKey;
    }

    private MvcResult createRecipe(String requestId, String recipeKey, Object[][] ingredients,
                                   int expected) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] ingredient : ingredients) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", ingredient[0]);
            entry.put("percentage", new BigDecimal((String) ingredient[1]));
            list.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("recipeKey", recipeKey);
        body.put("ingredients", list);
        return mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBatch(String batchKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-B-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", List.of("t1"));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private MvcResult bind(String batchKey, String requestId, String recipeKey, String targetMass,
                           int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"targetMassGrams\":" + targetMass + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recipe-binding")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult weigh(String batchKey, String requestId, String ingredient, String mass,
                            int expectedVersion, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"massGrams\":" + mass + ",\"expectedVersion\":" + expectedVersion + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult correct(String batchKey, String requestId, long replacesId, String ingredient,
                              String mass, int expectedVersion, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"replacesWeighingId\":" + replacesId
                + ",\"ingredient\":\"" + ingredient + "\",\"massGrams\":" + mass
                + ",\"expectedVersion\":" + expectedVersion + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighing-corrections")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }
    private void submitPass(String batchKey) throws Exception {
        submitTest(batchKey, "PASS", 201);
    }

    private void submitTest(String batchKey, String result, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                + "\",\"testItem\":\"t1\",\"result\":\"" + result + "\",\"inspector\":\"insp-1\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void recall(String batchKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-" + unique() + "\",\"reason\":\"质量风险\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 拆分：children 每行为 {batchKey, batchNo, targetMassGrams}，质量为 null 时不携带该字段。
     */
    protected MvcResult split(String parentKey, String commandKey, String[][] children, int expected)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.length; i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"batchKey\":\"").append(children[i][0]).append("\",\"batchNo\":\"")
                    .append(children[i][1]).append("\"");
            if (children[i][2] != null) {
                body.append(",\"targetMassGrams\":").append(children[i][2]);
            }
            body.append("}");
        }
        body.append("]}");
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("batch").path("status").asText();
    }
}