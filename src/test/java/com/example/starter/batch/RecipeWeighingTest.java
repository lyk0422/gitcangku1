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
 * 配方称量端到端测试（真实 H2 事务）：配方版本校验、称量累加与更正、放行配方门禁、
 * 拆批质量守恒继承、requestId 幂等与并发边界。题干合成用例：50%/50% 的 100 克配方，
 * 50+50 可放行；50.100001+49.899999 超差拒绝。
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
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM batch_recipe");
        jdbc.update("DELETE FROM recipe_ingredient");
        jdbc.update("DELETE FROM recipe_version");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_weighAccumulatesThenReleases() throws Exception {
        String recipe = "RC-HAPPY-" + unique();
        createRecipe(recipe, List.of(ing("成分甲", "50"), ing("成分乙", "50")), 201);
        String batch = "BK-RW-HAPPY-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        // 同成分多次称量累加：30 + 20 = 50
        weigh(batch, "成分甲", "30", null, 201);
        weigh(batch, "成分甲", "20", null, 201);
        weigh(batch, "成分乙", "50", null, 201);

        JsonNode chain = chain(batch);
        assertEquals(3, chain.path("version").asLong());
        assertEquals(3, chain.path("records").size());
        assertTrue(chain.path("recipeSatisfied").asBoolean());
        assertEquals("100", chain.path("targetMassG").asText());

        submitPass(batch);
        approve(batch, "qa-1", "QUALITY", "CK-A1-" + unique(), 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-A2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batch));
    }

    @Test
    void synthetic5050_exactPasses_andDeviationRejected() throws Exception {
        // 题干合成：50% 与 50% 的 100 克配方
        String recipe = "RC-SYN-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);

        // 50 和 50 可放行
        String ok = "BK-SYN-OK-" + unique();
        createBoundBatch(ok, recipe, "100", 201);
        weigh(ok, "A", "50", null, 201);
        weigh(ok, "B", "50", null, 201);
        submitPass(ok);
        approve(ok, "qa-1", "QUALITY", "CK-Q-" + unique(), 201);
        approve(ok, "ops-1", "OPERATIONS", "CK-O-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(ok));

        // 50.100001 与 49.899999：总量恰为 100，但 A 占比 50.100001% 超差 0.1 个百分点
        String bad = "BK-SYN-BAD-" + unique();
        createBoundBatch(bad, recipe, "100", 201);
        weigh(bad, "A", "50.100001", null, 201);
        weigh(bad, "B", "49.899999", null, 201);
        submitPass(bad);
        MvcResult rejected = approveRaw(bad, "qa-9", "QUALITY", "CK-QB-" + unique(), 422);
        JsonNode body = read(rejected);
        assertEquals("RECIPE_NOT_SATISFIED", body.path("code").asText());
        JsonNode ingredients = body.path("ingredients");
        assertEquals(2, ingredients.size(), "须给出全部成分差额，不允许只批准合格成分");
        JsonNode a = ingredients.get(0);
        assertEquals("A", a.path("ingredient").asText());
        assertEquals("50.100001", a.path("actualMassG").asText());
        assertEquals("50", a.path("expectedMassG").asText());
        assertEquals("0.100001", a.path("differenceMassG").asText());
        assertFalse(a.path("withinTolerance").asBoolean(), "0.100001 偏差不得提前舍入为 0.1");
        JsonNode b = ingredients.get(1);
        assertEquals("B", b.path("ingredient").asText());
        assertEquals("-0.100001", b.path("differenceMassG").asText());
        assertFalse(b.path("withinTolerance").asBoolean());
        // 门禁拒绝后批次保持待放行，不留下任何批准记录
        assertEquals("PENDING_RELEASE", currentStatus(bad));
        assertEquals(0, countRows("approval", bad));
    }

    @Test
    void gateFailsWhenTotalNotOnTarget_evenIfOneIngredientQualified() throws Exception {
        String recipe = "RC-PART-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-PART-" + unique();
        createBoundBatch(batch, recipe, "100", 201);
        // A 精确 50，B 只有 49.9：总量 99.9 不达目标；A 占比在容差内也不许部分放行
        weigh(batch, "A", "50", null, 201);
        weigh(batch, "B", "49.9", null, 201);
        submitPass(batch);
        MvcResult rejected = approveRaw(batch, "qa-1", "QUALITY", "CK-P-" + unique(), 422);
        JsonNode ingredients = read(rejected).path("ingredients");
        assertEquals(2, ingredients.size());
        assertTrue(ingredients.get(0).path("withinTolerance").asBoolean(),
                "A 占比 50/99.9 偏差约 0.05 个百分点，在容差内");
        assertEquals("PENDING_RELEASE", currentStatus(batch));
        assertEquals(0, countRows("approval", batch));
    }

    // ---------- 更正 ----------

    @Test
    void correction_replacesInsteadOfAccumulating_andReleaseFreezesChain() throws Exception {
        String recipe = "RC-COR-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-COR-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        long w1 = weigh(batch, "A", "40", null, 201);
        // 更正为 50：替代量重算；若错误累加则为 90 不达配方
        long w2 = correct(batch, w1, "50", null, 201);
        weigh(batch, "B", "50", null, 201);

        JsonNode chain = chain(batch);
        assertEquals(3, chain.path("version").asLong());
        assertFalse(chain.path("records").get(0).path("valid").asBoolean(), "被更正记录失效保留");
        assertTrue(chain.path("records").get(1).path("valid").asBoolean());
        assertEquals(w1, chain.path("records").get(1).path("supersedesId").asLong());
        assertTrue(chain.path("recipeSatisfied").asBoolean());
        // 有效合计：A=50（替代而非 40+50）
        assertEquals("50", chain.path("ingredients").get(0).path("actualMassG").asText());

        submitPass(batch);
        approve(batch, "qa-1", "QUALITY", "CK-C1-" + unique(), 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-C2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 放行冻结有效称量链：新称量与更正均 409
        weigh(batch, "A", "1", null, 409);
        correct(batch, w2, "49", null, 409);
        assertEquals(3, countRows("weighing", batch), "冻结后不得新增称量记录");
    }

    @Test
    void correction_requiresLatestValidWeighing() throws Exception {
        String recipe = "RC-LAT-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-LAT-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        long w1 = weigh(batch, "A", "10", null, 201);
        long w2 = weigh(batch, "A", "20", null, 201);
        // w1 不是该成分最近有效称量 → 409
        correct(batch, w1, "15", null, 409);
        // 最近有效 w2 可更正
        long w3 = correct(batch, w2, "25", null, 201);
        assertTrue(w3 > w2);
        // w2 已被替代，再次更正 → 409
        correct(batch, w2, "26", null, 409);
        // 不存在的称量ID → 404
        correct(batch, 999999999L, "1", null, 404);
        // 其他批次的称量ID → 404
        String other = "BK-LAT2-" + unique();
        createBoundBatch(other, recipe, "100", 201);
        long wo = weigh(other, "A", "1", null, 201);
        correct(batch, wo, "2", null, 404);

        // 有效合计：A = 10 + 25 = 35
        JsonNode chain = chain(batch);
        assertEquals("35", chain.path("ingredients").get(0).path("actualMassG").asText());
        assertEquals(3, chain.path("records").size());
    }

    // ---------- 校验分支 ----------

    @Test
    void recipeValidation_returns400() throws Exception {
        // 成分只有 1 种 / 7 种 → 400（Bean 校验）
        createRecipe("RC-ONE-" + unique(), List.of(ing("A", "100")), 400);
        createRecipe("RC-SEVEN-" + unique(), List.of(ing("a", "14.3"), ing("b", "14.3"),
                ing("c", "14.3"), ing("d", "14.3"), ing("e", "14.3"), ing("f", "14.3"),
                ing("g", "14.2")), 400);
        // 百分比超过 4 位小数 → 400
        createRecipe("RC-SCALE-" + unique(), List.of(ing("A", "50.00001"), ing("B", "49.99999")), 400);
        // 百分比为零或为负 → 400
        createRecipe("RC-ZERO-" + unique(), List.of(ing("A", "0"), ing("B", "100")), 400);
        createRecipe("RC-NEG-" + unique(), List.of(ing("A", "-1"), ing("B", "101")), 400);
        // 合计不精确等于 100 → 400
        createRecipe("RC-SUM-" + unique(), List.of(ing("A", "50"), ing("B", "50.0001")), 400);
        // 成分名重复 → 400
        createRecipe("RC-DUP-" + unique(), List.of(ing("A", "50"), ing("A", "50")), 400);
        // 全部失败不占键：recipe_version 表为空
        Integer recipes = jdbc.queryForObject("SELECT COUNT(*) FROM recipe_version", Integer.class);
        assertEquals(0, recipes);
    }

    @Test
    void duplicateRecipeKey_returns409_andRecipeImmutable() throws Exception {
        String recipe = "RC-DUPKEY-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        // 同键不同内容 → 409；同键同内容不同 requestId 也 → 409（配方不可变）
        createRecipe(recipe, List.of(ing("A", "60"), ing("B", "40")), 409);
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 409);
        // 原配方内容未被改写
        MvcResult result = mockMvc.perform(get("/api/recipes/" + recipe))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertEquals("50", body.path("ingredients").get(0).path("percentage").asText());
        mockMvc.perform(get("/api/recipes/NO-SUCH-RECIPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchBindingValidation_returns400or422() throws Exception {
        String recipe = "RC-BIND-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        // 只给 recipeKey / 只给 targetMassG → 400
        createBatchRaw(batchBody("BK-B1-" + unique(), recipe, null), 400);
        createBatchRaw(batchBody("BK-B2-" + unique(), null, "100"), 400);
        // 未知配方 → 422
        createBatchRaw(batchBody("BK-B3-" + unique(), "NO-SUCH-RECIPE", "100"), 422);
        // targetMassG 超 6 位小数 / 非正值 → 400
        createBatchRaw(batchBody("BK-B4-" + unique(), recipe, "1.0000001"), 400);
        createBatchRaw(batchBody("BK-B5-" + unique(), recipe, "0"), 400);
        createBatchRaw(batchBody("BK-B6-" + unique(), recipe, "-5"), 400);
        // 合法绑定 → 201，响应携带绑定信息
        String batch = "BK-B7-" + unique();
        MvcResult created = createBatchRaw(batchBody(batch, recipe, "100"), 201);
        JsonNode body = read(created);
        assertEquals(recipe, body.path("recipeKey").asText());
        assertEquals("100", body.path("targetMassG").asText());
    }

    @Test
    void weighValidation_returns400or422or404() throws Exception {
        String recipe = "RC-WV-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-WV-" + unique();
        createBoundBatch(batch, recipe, "100", 201);
        // 未知成分 → 400
        weigh(batch, "不存在的成分", "1", null, 400);
        // 负量 → 400
        weigh(batch, "A", "-0.5", null, 400);
        // 超 6 位精度 → 400
        weigh(batch, "A", "1.0000001", null, 400);
        // 未绑定配方的批次称量 → 422
        String unbound = "BK-WV-UNB-" + unique();
        createBatchRaw(batchBody(unbound, null, null), 201);
        weigh(unbound, "A", "1", null, 422);
        // 不存在的批次 → 404
        weigh("NO-SUCH-BATCH", "A", "1", null, 404);
        // 全部失败不留称量记录
        assertEquals(0, countRows("weighing", batch));
        // 更正的负量与超精度同样 400
        long w1 = weigh(batch, "A", "10", null, 201);
        correct(batch, w1, "-1", null, 400);
        correctRaw(batch, correctBody("RID-X-" + unique(), "op", w1, "1.0000001", null), 400);
        assertEquals(1, countRows("weighing", batch));
    }

    @Test
    void expectedVersion_optimisticConcurrency() throws Exception {
        String recipe = "RC-VER-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-VER-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        long w1 = weigh(batch, "A", "10", null, 201);
        // 期望版本不匹配 → 409
        weigh(batch, "A", "5", 5L, 409);
        // 期望版本匹配（当前版本 1）→ 201
        long w2 = weigh(batch, "A", "5", 1L, 201);
        // 更正也校验期望版本（当前版本 2）；只能更正该成分最近有效称量 w2
        correct(batch, w2, "12", 99L, 409);
        correct(batch, w2, "12", 2L, 201);
        JsonNode chain = chain(batch);
        assertEquals(3, chain.path("version").asLong());
        // A 有效合计 = 10 + 12 = 22
        assertEquals("22", chain.path("ingredients").get(0).path("actualMassG").asText());
    }

    // ---------- 幂等 ----------

    @Test
    void requestId_replayConflict_andFailureNotOccupying() throws Exception {
        String recipe = "RC-IDEM-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-IDEM-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        // 同 requestId 同参数重放 → 返回首次完整响应
        String requestId = "RID-REPLAY-" + unique();
        String body = weighBody(requestId, "op-1", "A", "10", null);
        MvcResult first = weighRaw(batch, body, 201);
        MvcResult replay = weighRaw(batch, body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 不同参数 → 409
        weighRaw(batch, weighBody(requestId, "op-1", "A", "20", null), 409);
        weighRaw(batch, weighBody(requestId, "op-2", "A", "10", null), 409);
        // 失败不占键：未知成分 400 后，同 requestId 合法参数可成功
        String failThenOk = "RID-FAIL-" + unique();
        weighRaw(batch, weighBody(failThenOk, "op-1", "未知成分", "5", null), 400);
        weighRaw(batch, weighBody(failThenOk, "op-1", "A", "5", null), 201);
        // 称量记录恰好 2 条（重放与失败均不产生新记录）
        assertEquals(2, countRows("weighing", batch));
        Integer commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'", Integer.class);
        assertEquals(2, commands, "仅成功命令占键");
    }

    @Test
    void recipeRequestId_replayAndConflict() throws Exception {
        String recipe = "RC-RID-" + unique();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "RID-RC-" + unique());
        body.put("recipeKey", recipe);
        body.put("operator", "op-1");
        body.put("ingredients", List.of(ing("A", "50"), ing("B", "50")));
        String json = objectMapper.writeValueAsString(body);
        MvcResult first = mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 改参 → 409
        body.put("operator", "op-2");
        mockMvc.perform(post("/api/recipes").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_version WHERE recipe_key = ?", Integer.class, recipe));
    }

    // ---------- 拆批守恒 ----------

    @Test
    void split_inheritsIngredientMassesByExactProportion() throws Exception {
        String recipe = "RC-SP-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String parent = "BK-SP-P-" + unique();
        createBoundBatch(parent, recipe, "100", 201);
        weigh(parent, "A", "50", null, 201);
        weigh(parent, "B", "50", null, 201);
        submitPass(parent);
        approve(parent, "qa-1", "QUALITY", "CK-SQ-" + unique(), 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(parent));

        String c1 = "BK-SP-C1-" + unique();
        String c2 = "BK-SP-C2-" + unique();
        split(parent, "CK-SP-" + unique(), List.of(child(c1, "L1", "60"), child(c2, "L2", "40")), 201);

        // 子批按质量比例精确继承：c1 A=30/B=30，c2 A=20/B=20
        JsonNode chain1 = chain(c1);
        assertEquals("60", chain1.path("targetMassG").asText());
        assertEquals(2, chain1.path("records").size());
        assertTrue(chain1.path("recipeSatisfied").asBoolean());
        assertEquals("30.000000", chain1.path("ingredients").get(0).path("actualMassG").asText());
        JsonNode chain2 = chain(c2);
        assertEquals("20.000000", chain2.path("ingredients").get(0).path("actualMassG").asText());
        assertTrue(chain2.path("recipeSatisfied").asBoolean());

        // 数据库层面守恒：子批各成分有效称量合计等于父批各成分质量
        java.math.BigDecimal childSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CAST(w.mass_g AS DECIMAL(20,6))), 0) FROM weighing w"
                        + " WHERE w.ingredient = 'A' AND w.superseded_by IS NULL"
                        + " AND w.batch_key IN (?, ?)",
                java.math.BigDecimal.class, c1, c2);
        assertEquals(0, new java.math.BigDecimal("50.000000").compareTo(childSum));

        // 已拆父批称量链冻结：称量与更正均 409
        weigh(parent, "A", "1", null, 409);
        long parentWeighing = jdbc.queryForObject(
                "SELECT MIN(id) FROM weighing WHERE batch_key = ?", Long.class, parent);
        correct(parent, parentWeighing, "49", null, 409);
        assertEquals(2, countRows("weighing", parent), "父批称量链冻结不变");

        // 子批继承称量已满足配方，走完检验与双批准可放行
        submitPass(c1);
        approve(c1, "qa-2", "QUALITY", "CK-C1Q-" + unique(), 201);
        approve(c1, "ops-2", "OPERATIONS", "CK-C1O-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(c1));
    }

    @Test
    void split_nonConserving_returns422_andParentUnchanged() throws Exception {
        String recipe = "RC-NC-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String parent = "BK-NC-P-" + unique();
        createBoundBatch(parent, recipe, "100", 201);
        weigh(parent, "A", "50", null, 201);
        weigh(parent, "B", "50", null, 201);
        submitPass(parent);
        approve(parent, "qa-1", "QUALITY", "CK-NQ-" + unique(), 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-NO-" + unique(), 201);

        // 子批合计不等于父批目标 → 422
        split(parent, "CK-NC1-" + unique(), List.of(
                child("BK-NC-X1-" + unique(), "L1", "50"),
                child("BK-NC-X2-" + unique(), "L2", "40")), 422);
        // 合计等于 100 但成分分摊 50×33.333333/100=16.6666665 超 6 位小数 → 422
        split(parent, "CK-NC2-" + unique(), List.of(
                child("BK-NC-Y1-" + unique(), "L1", "33.333333"),
                child("BK-NC-Y2-" + unique(), "L2", "66.666667")), 422);
        // 绑定配方的父批，子批缺 targetMassG → 400
        split(parent, "CK-NC3-" + unique(), List.of(
                child("BK-NC-Z1-" + unique(), "L1", null),
                child("BK-NC-Z2-" + unique(), "L2", null)), 400);
        // 子批 targetMassG 超精度 → 400
        split(parent, "CK-NC4-" + unique(), List.of(
                child("BK-NC-W1-" + unique(), "L1", "60.0000001"),
                child("BK-NC-W2-" + unique(), "L2", "39.9999999")), 400);
        // 全部失败：父批保持 RELEASED，无子批与继承称量落库
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(2, countRows("weighing", parent));
        Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent);
        assertEquals(0, children);

        // 未绑定配方的父批，子批不得提供 targetMassG → 400
        String unbound = "BK-NC-UNB-" + unique();
        createBatchRaw(batchBody(unbound, null, null), 201);
        submitPass(unbound);
        approve(unbound, "qa-1", "QUALITY", "CK-UQ-" + unique(), 201);
        approve(unbound, "ops-1", "OPERATIONS", "CK-UO-" + unique(), 201);
        split(unbound, "CK-NC5-" + unique(), List.of(
                child("BK-NC-U1-" + unique(), "L1", "50"),
                child("BK-NC-U2-" + unique(), "L2", "50")), 400);
        assertEquals("RELEASED", currentStatus(unbound));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameRequestId_weigh_replaysSingleRecord() throws Exception {
        String recipe = "RC-CID-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-CID-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        String body = weighBody("RID-CONC-" + unique(), "op-1", "A", "10", null);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, countRows("weighing", batch), "并发同键只落一条称量记录");
    }

    @Test
    void concurrentSameRequestIdDifferentParams_exactlyOneWins() throws Exception {
        String recipe = "RC-CDP-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-CDP-" + unique();
        createBoundBatch(batch, recipe, "100", 201);

        String requestId = "RID-RACE-" + unique();
        String body1 = weighBody(requestId, "op-1", "A", "10", null);
        String body2 = weighBody(requestId, "op-1", "A", "20", null);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body1)),
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body2)));
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("并发不得泄露唯一键异常，意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, conflict);
        assertEquals(1, countRows("weighing", batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'", Integer.class));
    }

    @Test
    void concurrentWeighAndApprove_recipeGateSerialized() throws Exception {
        String recipe = "RC-CGA-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-CGA-" + unique();
        createBoundBatch(batch, recipe, "100", 201);
        weigh(batch, "A", "50", null, 201);
        submitPass(batch);

        // 并发：补齐 B 的称量 与 首次批准竞争同一批次行锁
        String approveKey = "CK-CGA-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("RID-CGA-" + unique(), "op-1", "B", "50", null))),
                () -> callStatus(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + approveKey + "\"}")));
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        int firstApprove = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue(firstApprove == 201 || firstApprove == 422,
                "批准要么看到已补齐的称量放行，要么被配方门禁拒绝: " + firstApprove);
        if (firstApprove == 422) {
            // 失败不占键：称量落定后同 commandKey 重试成功
            approve(batch, "qa-1", "QUALITY", approveKey, 201);
        }
        approve(batch, "ops-1", "OPERATIONS", "CK-CGA2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batch));
        assertEquals(2, countRows("weighing", batch));
    }

    // ---------- 持久化 ----------

    @Test
    void dataPersisted_recipeBindingAndWeighingTables() throws Exception {
        String recipe = "RC-PER-" + unique();
        createRecipe(recipe, List.of(ing("A", "50"), ing("B", "50")), 201);
        String batch = "BK-PER-" + unique();
        createBoundBatch(batch, recipe, "100", 201);
        long w1 = weigh(batch, "A", "40", null, 201);
        correct(batch, w1, "50", null, 201);
        weigh(batch, "B", "50", null, 201);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_version WHERE recipe_key = ?", Integer.class, recipe));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_ingredient WHERE recipe_key = ?", Integer.class, recipe));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_recipe WHERE batch_key = ? AND target_mass_g = '100'",
                Integer.class, batch));
        assertEquals(3, countRows("weighing", batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NOT NULL",
                Integer.class, batch));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NULL",
                Integer.class, batch));
        // 业务与去重原子提交：CREATE_RECIPE/CREATE_BATCH/WEIGH/CORRECT_WEIGH 各就各位
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CREATE_RECIPE'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CORRECT_WEIGH'",
                Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> ing(String name, String percentage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("percentage", percentage);
        return map;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countRows(String table, String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE batch_key = ?", Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    private MvcResult createRecipe(String recipeKey, List<Map<String, Object>> ingredients,
                                   int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "RID-RC-" + unique());
        body.put("recipeKey", recipeKey);
        body.put("operator", "op-1");
        body.put("ingredients", ingredients);
        MvcResult result = mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
        return result;
    }

    private Map<String, Object> batchBody(String batchKey, String recipeKey, String targetMassG) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-B-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", List.of("t1"));
        if (recipeKey != null) {
            body.put("recipeKey", recipeKey);
        }
        if (targetMassG != null) {
            body.put("targetMassG", targetMassG);
        }
        return body;
    }

    private MvcResult createBatchRaw(Map<String, Object> body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBoundBatch(String batchKey, String recipeKey, String targetMassG,
                                  int expected) throws Exception {
        createBatchRaw(batchBody(batchKey, recipeKey, targetMassG), expected);
    }

    private String weighBody(String requestId, String operator, String ingredient, String massG,
                             Long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("ingredient", ingredient);
        body.put("massG", massG);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult weighRaw(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private long weigh(String batchKey, String ingredient, String massG, Long expectedVersion,
                       int expected) throws Exception {
        MvcResult result = weighRaw(batchKey,
                weighBody("RID-W-" + unique(), "weigher-1", ingredient, massG, expectedVersion),
                expected);
        if (expected != 201) {
            return -1L;
        }
        return read(result).path("weighingId").asLong();
    }

    private String correctBody(String requestId, String operator, long weighingId, String newMassG,
                               Long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("weighingId", weighingId);
        body.put("newMassG", newMassG);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult correctRaw(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighing-corrections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private long correct(String batchKey, long weighingId, String newMassG, Long expectedVersion,
                         int expected) throws Exception {
        MvcResult result = correctRaw(batchKey,
                correctBody("RID-C-" + unique(), "weigher-1", weighingId, newMassG, expectedVersion),
                expected);
        if (expected != 201) {
            return -1L;
        }
        return read(result).path("weighingId").asLong();
    }

    private void submitPass(String batchKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", "TK-" + unique());
        body.put("testItem", "t1");
        body.put("result", "PASS");
        body.put("inspector", "insp-1");
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private MvcResult approveRaw(String batchKey, String actor, String role, String commandKey,
                                 int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        approveRaw(batchKey, actor, role, commandKey, expected);
    }

    private Map<String, Object> child(String batchKey, String batchNo, String targetMassG) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("batchKey", batchKey);
        map.put("batchNo", batchNo);
        if (targetMassG != null) {
            map.put("targetMassG", targetMassG);
        }
        return map;
    }

    private MvcResult split(String parentKey, String commandKey, List<Map<String, Object>> children,
                            int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("children", children);
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode chain(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/weighings"))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
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
