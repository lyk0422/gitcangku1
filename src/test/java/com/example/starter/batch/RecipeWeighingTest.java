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
 * 配方称量端到端测试（真实 H2 事务）：配方校验、称量累加与更正、放行配方门禁、
 * 拆分守恒继承、requestId 幂等与并发边界。
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
        jdbc.update("DELETE FROM recipe_ingredient");
        jdbc.update("DELETE FROM recipe_version");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 配方版本校验 ----------

    @Test
    void recipeValidation_rulesEnforced() throws Exception {
        String key = "R-VAL-" + unique();
        // 仅1种成分 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-1", key,
                "[{\"name\":\"A\",\"percentage\":100}]"), 400);
        // 7种成分 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-2", key,
                "[{\"name\":\"A\",\"percentage\":15},{\"name\":\"B\",\"percentage\":15},"
                        + "{\"name\":\"C\",\"percentage\":15},{\"name\":\"D\",\"percentage\":15},"
                        + "{\"name\":\"E\",\"percentage\":15},{\"name\":\"F\",\"percentage\":15},"
                        + "{\"name\":\"G\",\"percentage\":10}]"), 400);
        // 合计不等于100 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-3", key,
                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"B\",\"percentage\":49.9999}]"), 400);
        // 百分比为0或负 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-4", key,
                "[{\"name\":\"A\",\"percentage\":0},{\"name\":\"B\",\"percentage\":100}]"), 400);
        postJson("/api/recipes", "ops", recipeBody("RQ-5", key,
                "[{\"name\":\"A\",\"percentage\":-1},{\"name\":\"B\",\"percentage\":101}]"), 400);
        // 百分比超过4位小数 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-6", key,
                "[{\"name\":\"A\",\"percentage\":50.00001},{\"name\":\"B\",\"percentage\":49.99999}]"), 400);
        // 成分名重复 -> 400
        postJson("/api/recipes", "ops", recipeBody("RQ-7", key,
                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"A\",\"percentage\":50}]"), 400);
        // 缺操作人 -> 400
        mockMvc.perform(post("/api/recipes").contentType(MediaType.APPLICATION_JSON)
                        .content(recipeBody("RQ-8", key,
                                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"B\",\"percentage\":50}]")))
                .andExpect(status().isBadRequest());
        // 失败不占键：RQ-6 失败后换合法参数复用同一 requestId 成功
        postJson("/api/recipes", "ops", recipeBody("RQ-6", key,
                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"B\",\"percentage\":50}]"), 201);
        // 重复 recipeKey -> 409
        postJson("/api/recipes", "ops", recipeBody("RQ-9", key,
                "[{\"name\":\"A\",\"percentage\":60},{\"name\":\"B\",\"percentage\":40}]"), 409);
        // 同 requestId 同参重放首次响应；同键异参 -> 409
        String body = recipeBody("RQ-10", "R-VAL2-" + unique(),
                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"B\",\"percentage\":50}]");
        MvcResult first = postJson("/api/recipes", "ops", body, 201);
        MvcResult replay = postJson("/api/recipes", "ops", body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        postJson("/api/recipes", "ops", recipeBody("RQ-10", "R-VAL3-" + unique(),
                "[{\"name\":\"A\",\"percentage\":70},{\"name\":\"B\",\"percentage\":30}]"), 409);
    }

    // ---------- 主流程：称量累加 + 放行 ----------

    @Test
    void happyFlow_weighAccumulateThenRelease() throws Exception {
        String recipe = "R-HAPPY-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-HAPPY-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        // 同成分多次称量累加：A 30+20=50，B 50
        weigh(batch, "scale-1", "W-1", "A", "30", 0, 201);
        weigh(batch, "scale-1", "W-2", "A", "20", 1, 201);
        weigh(batch, "scale-2", "W-3", "B", "50", 2, 201);

        JsonNode chain = weighingChain(batch);
        assertEquals(3, chain.path("weighingVersion").asInt());
        assertEquals(3, chain.path("weighings").size());
        assertDecimal("50", totalOf(chain, "A"));
        assertDecimal("50", totalOf(chain, "B"));

        submitPass(batch, "t1");
        approve(batch, "qa-1", "QUALITY", 201);
        approve(batch, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 落库核对：3条称量、命令日志含 CREATE_RECIPE 与 3 条 WEIGH
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batch));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CREATE_RECIPE'",
                Integer.class));
    }
    // ---------- 配方门禁：超差 / 缺料 / 总量不符 ----------

    @Test
    void outOfTolerance_rejectedThenCorrectedAndReleased() throws Exception {
        String recipe = "R-TOL-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-TOL-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        // 合成场景：50.100001 与 49.899999，占比偏差 0.100001 > 0.1，判定不提前舍入
        long wa = weighingId(weigh(batch, "scale-1", "W-1", "A", "50.100001", 0, 201));
        long wb = weighingId(weigh(batch, "scale-1", "W-2", "B", "49.899999", 1, 201));

        submitPass(batch, "t1");
        approve(batch, "qa-1", "QUALITY", 201);
        // 最终批准触发配方门禁：422 且给出各成分差额，批次停留在 RELEASE_REVIEW
        MvcResult rejected = approveRaw(batch, "ops-1", "OPERATIONS", 422);
        JsonNode error = readTree(rejected);
        assertEquals("RECIPE_NOT_MET", error.path("code").asText());
        JsonNode diffs = error.path("differences");
        assertEquals(2, diffs.size());
        JsonNode diffA = diffs.get(0);
        assertEquals("A", diffA.path("ingredient").asText());
        assertDecimal("50", diffA.path("expectedMassGrams").asText());
        assertDecimal("50.100001", diffA.path("actualMassGrams").asText());
        assertDecimal("0.100001", diffA.path("massDifferenceGrams").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 称量更正：指定最近有效称量ID，以替代量重算而不是重复累加
        correct(batch, "scale-1", "C-1", wa, "50", 2, 201);
        correct(batch, "scale-1", "C-2", wb, "50", 3, 201);
        JsonNode chain = weighingChain(batch);
        assertEquals(2, chain.path("weighings").size(), "被更正行失效后有效链仅2条");
        assertDecimal("50", totalOf(chain, "A"));
        assertDecimal("50", totalOf(chain, "B"));

        approve(batch, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batch));
        // 放行冻结有效称量链
        weigh(batch, "scale-1", "W-9", "A", "1", 4, 409);
        correct(batch, "scale-1", "C-9", wa, "49", 4, 409);
    }

    @Test
    void missingIngredientAndTotalMismatch_rejected422() throws Exception {
        String recipe = "R-MISS-" + unique();
        createRecipe5050(recipe);
        // 只称一种成分：成分不齐全 -> 422
        String b1 = "BK-W-MISS-" + unique();
        createBatchWithRecipe(b1, recipe, "100", List.of("t1"), 201);
        weigh(b1, "scale-1", "W-1", "A", "100", 0, 201);
        submitPass(b1, "t1");
        approve(b1, "qa-1", "QUALITY", 201);
        MvcResult r1 = approveRaw(b1, "ops-1", "OPERATIONS", 422);
        JsonNode diffs = readTree(r1).path("differences");
        assertEquals(2, diffs.size());
        assertDecimal("0", diffs.get(1).path("actualMassGrams").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(b1));

        // 总量与目标不相等（50+49=99 != 100）-> 422
        String b2 = "BK-W-SUM-" + unique();
        createBatchWithRecipe(b2, recipe, "100", List.of("t1"), 201);
        weigh(b2, "scale-1", "W-3", "A", "50", 0, 201);
        weigh(b2, "scale-1", "W-4", "B", "49", 1, 201);
        submitPass(b2, "t1");
        approve(b2, "qa-1", "QUALITY", 201);
        approve(b2, "ops-1", "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b2));
    }

    // ---------- 称量/更正输入校验 ----------

    @Test
    void weighingInputValidation_400() throws Exception {
        String recipe = "R-IN-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-IN-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        // 负量、零量 -> 400
        weigh(batch, "scale-1", "W-N1", "A", "-1", 0, 400);
        weigh(batch, "scale-1", "W-N2", "A", "0", 0, 400);
        // 超精度（7位小数）-> 400
        weigh(batch, "scale-1", "W-N3", "A", "1.0000001", 0, 400);
        // 未知成分 -> 400
        weigh(batch, "scale-1", "W-N4", "X", "1", 0, 400);
        // 缺操作人 -> 400
        mockMvc.perform(post("/api/batches/" + batch + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("W-N5", "A", "1", 0)))
                .andExpect(status().isBadRequest());
        // 失败不占键：W-N4 失败后同 requestId 换合法成分成功
        weigh(batch, "scale-1", "W-N4", "A", "1", 0, 201);
        // 期望版本不一致 -> 409
        weigh(batch, "scale-1", "W-N6", "A", "1", 0, 409);
        // 未绑定配方的批次不能称量 -> 422
        String plain = "BK-W-PLAIN-" + unique();
        createPlainBatch(plain, List.of("t1"), 201);
        weigh(plain, "scale-1", "W-P1", "A", "1", 0, 422);
        // 不存在的批次 -> 404
        weigh("BK-NOPE-" + unique(), "scale-1", "W-P2", "A", "1", 0, 404);
    }

    @Test
    void correctionRules_replacesNotAccumulates() throws Exception {
        String recipe = "R-COR-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-COR-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        long wa = weighingId(weigh(batch, "scale-1", "W-1", "A", "60", 0, 201));
        // 更正为50：替代量重算，总量是50而不是110
        long corr = weighingId(correct(batch, "scale-1", "C-1", wa, "50", 1, 201));
        JsonNode chain = weighingChain(batch);
        assertEquals(1, chain.path("weighings").size());
        assertDecimal("50", totalOf(chain, "A"));
        assertEquals(2, chain.path("weighingVersion").asInt());
        // 已失效的称量不能再次更正 -> 409
        correct(batch, "scale-1", "C-2", wa, "45", 2, 409);
        // 不存在的称量 -> 404；其他批次的称量 -> 409
        correct(batch, "scale-1", "C-3", 999999L, "45", 2, 404);
        String other = "BK-W-COR2-" + unique();
        createBatchWithRecipe(other, recipe, "100", List.of("t1"), 201);
        correct(other, "scale-1", "C-4", corr, "45", 0, 409);
        // 更正输入校验：负量/超精度 -> 400
        correct(batch, "scale-1", "C-5", corr, "-1", 2, 400);
        correct(batch, "scale-1", "C-6", corr, "1.0000001", 2, 400);
        // 落库核对：旧行已被替代，新行 replaces_id 指向旧行
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE id = ? AND superseded_by = ?",
                Integer.class, wa, corr));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE id = ? AND replaces_id = ?",
                Integer.class, corr, wa));
    }
    // ---------- 幂等 ----------

    @Test
    void idempotency_weighAndCorrectReplayAndConflict() throws Exception {
        String recipe = "R-IDEM-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-IDEM-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        // 同 requestId 同参重放首次完整响应
        String body = weighBody("W-1", "A", "50", 0);
        MvcResult first = postJson("/api/batches/" + batch + "/weighings", "scale-1", body, 201);
        MvcResult replay = postJson("/api/batches/" + batch + "/weighings", "scale-1", body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键异参 -> 409（金额不同、操作人不同都算异参）
        postJson("/api/batches/" + batch + "/weighings", "scale-1",
                weighBody("W-1", "A", "60", 0), 409);
        postJson("/api/batches/" + batch + "/weighings", "scale-2", body, 409);
        // 重放不产生新称量行
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batch));

        // 更正幂等：同键同参重放，同键异参 409
        long wa = weighingId(first);
        String corrBody = correctBody("C-1", wa, "45", 1);
        MvcResult c1 = postJson("/api/batches/" + batch + "/weighings/corrections", "scale-1",
                corrBody, 201);
        MvcResult c2 = postJson("/api/batches/" + batch + "/weighings/corrections", "scale-1",
                corrBody, 201);
        assertEquals(c1.getResponse().getContentAsString(),
                c2.getResponse().getContentAsString());
        postJson("/api/batches/" + batch + "/weighings/corrections", "scale-1",
                correctBody("C-1", wa, "46", 1), 409);
        // 更正只生效一次：有效链1条，总量45
        JsonNode chain = weighingChain(batch);
        assertEquals(1, chain.path("weighings").size());
        assertDecimal("45", totalOf(chain, "A"));
    }

    // ---------- 拆分守恒继承 ----------

    @Test
    void splitInheritance_conservingSplitAndChildRelease() throws Exception {
        String recipe = "R-SP-" + unique();
        createRecipe5050(recipe);
        String parent = "BK-W-SP-" + unique();
        createBatchWithRecipe(parent, recipe, "100", List.of("t1"), 201);
        weigh(parent, "scale-1", "W-1", "A", "50", 0, 201);
        weigh(parent, "scale-1", "W-2", "B", "50", 1, 201);
        submitPass(parent, "t1");
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(parent));

        // 按子批质量比例精确继承：60/40 -> A、B 各 30/30 与 20/20
        String c1 = "BK-W-SPC1-" + unique();
        String c2 = "BK-W-SPC2-" + unique();
        postJson("/api/batches/" + parent + "/split",
                splitBody("SP-1", parent, c1, "60", c2, "40"), 201);
        assertEquals("SPLIT", currentStatus(parent));

        JsonNode chain1 = weighingChain(c1);
        assertDecimal("60", chain1.path("targetMassGrams").asText());
        assertDecimal("30", totalOf(chain1, "A"));
        assertDecimal("30", totalOf(chain1, "B"));
        assertEquals(2, chain1.path("weighingVersion").asInt());
        JsonNode chain2 = weighingChain(c2);
        assertDecimal("20", totalOf(chain2, "A"));
        assertDecimal("20", totalOf(chain2, "B"));
        // 成分质量守恒：子批合计等于父批称量
        assertDecimal("50", jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM weighing WHERE batch_key IN (?,?)"
                        + " AND ingredient = 'A' AND superseded_by IS NULL",
                BigDecimal.class, c1, c2).toPlainString());

        // 子批自身满足配方，可走完整检验与放行
        submitPass(c1, "t1");
        approve(c1, "qa-1", "QUALITY", 201);
        approve(c1, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(c1));

        // 已拆父批不能再更正（拆分与更正互斥）
        long parentWeighing = jdbc.queryForObject(
                "SELECT id FROM weighing WHERE batch_key = ? ORDER BY id LIMIT 1",
                Long.class, parent);
        correct(parent, "scale-1", "C-SP", parentWeighing, "49", 2, 409);
    }

    @Test
    void splitInheritance_nonConservingRejected422() throws Exception {
        String recipe = "R-SPN-" + unique();
        createRecipe5050(recipe);
        String parent = "BK-W-SPN-" + unique();
        createBatchWithRecipe(parent, recipe, "100", List.of("t1"), 201);
        weigh(parent, "scale-1", "W-1", "A", "50", 0, 201);
        weigh(parent, "scale-1", "W-2", "B", "50", 1, 201);
        submitPass(parent, "t1");
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        // 50 * 33.333333 / 100 = 16.6666665，无法在6位小数内精确继承 -> 422
        postJson("/api/batches/" + parent + "/split",
                splitBody("SP-2", parent, "BK-W-SPN1-" + unique(), "33.333333",
                        "BK-W-SPN2-" + unique(), "66.666667"), 422);
        // 子批目标质量之和 != 父批目标质量 -> 422
        postJson("/api/batches/" + parent + "/split",
                splitBody("SP-3", parent, "BK-W-SPN3-" + unique(), "50",
                        "BK-W-SPN4-" + unique(), "49"), 422);
        // 绑定配方的父批拆分时子批缺 targetMassGrams -> 400
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"SP-4\",\"children\":["
                                + "{\"batchKey\":\"BK-X1-" + unique() + "\",\"batchNo\":\"N1\"},"
                                + "{\"batchKey\":\"BK-X2-" + unique() + "\",\"batchNo\":\"N2\"}]}"))
                .andExpect(status().isBadRequest());
        // 整次失败回滚：父批仍 RELEASED，无子批、无继承称量
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?",
                Integer.class, parent));
    }

    @Test
    void ancestorRecallBlocksWeighing() throws Exception {
        String recipe = "R-RC-" + unique();
        createRecipe5050(recipe);
        String parent = "BK-W-RC-" + unique();
        createBatchWithRecipe(parent, recipe, "100", List.of("t1"), 201);
        weigh(parent, "scale-1", "W-1", "A", "50", 0, 201);
        weigh(parent, "scale-1", "W-2", "B", "50", 1, 201);
        submitPass(parent, "t1");
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);
        String child = "BK-W-RCC-" + unique();
        postJson("/api/batches/" + parent + "/split",
                splitBody("SP-RC", parent, child, "60", "BK-W-RCD-" + unique(), "40"), 201);

        // 召回父批后，子批称量被祖先召回门禁拦截 -> 422
        postJson("/api/batches/" + parent + "/recall", "qa-lead",
                "{\"commandKey\":\"RC-1\",\"reason\":\"质量调查\"}", 201);
        weigh(child, "scale-1", "W-RC", "A", "1", 2, 422);
    }
    // ---------- 并发 ----------

    @Test
    void concurrentWeighings_sameExpectedVersion_exactlyOneWins() throws Exception {
        String recipe = "R-CC1-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-CC1-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .header("X-Actor-Id", "scale-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("W-C1", "A", "50", 0))),
                () -> callStatus(post("/api/batches/" + batch + "/weighings")
                        .header("X-Actor-Id", "scale-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("W-C2", "B", "50", 0))));
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertTrue(codes.contains(201) && codes.contains(409),
                "同一期望版本并发称量必须恰好一个成功: " + codes);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT weighing_version FROM batch WHERE batch_key = ?",
                Integer.class, batch));
    }

    @Test
    void concurrentSameRequestId_replaysSameResponseWithoutUniqueKeyLeak() throws Exception {
        String recipe = "R-CC2-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-CC2-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);

        String body = weighBody("W-SAME", "A", "50", 0);
        List<Future<String>> results = runConcurrent(
                () -> callStatusAndBody(post("/api/batches/" + batch + "/weighings")
                        .header("X-Actor-Id", "scale-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatusAndBody(post("/api/batches/" + batch + "/weighings")
                        .header("X-Actor-Id", "scale-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue(first.startsWith("201|"), "并发同键同参均应重放首次响应: " + first);
        assertEquals(first, second);
        // 竞争不泄露唯一键异常：只落一条称量、一条命令日志
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'"
                        + " AND command_key = 'W-SAME'", Integer.class));
    }

    @Test
    void concurrentCorrections_sameTarget_exactlyOneWins() throws Exception {
        String recipe = "R-CC3-" + unique();
        createRecipe5050(recipe);
        String batch = "BK-W-CC3-" + unique();
        createBatchWithRecipe(batch, recipe, "100", List.of("t1"), 201);
        long wa = weighingId(weigh(batch, "scale-1", "W-1", "A", "60", 0, 201));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/weighings/corrections")
                        .header("X-Actor-Id", "scale-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctBody("C-A", wa, "50", 1))),
                () -> callStatus(post("/api/batches/" + batch + "/weighings/corrections")
                        .header("X-Actor-Id", "scale-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctBody("C-B", wa, "55", 1))));
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertTrue(codes.contains(201) && codes.contains(409),
                "对同一称量的并发更正必须恰好一个成功: " + codes);
        // 最终数据核对：有效链1条，总量为胜出替代量，版本只推进一次
        JsonNode chain = weighingChain(batch);
        assertEquals(1, chain.path("weighings").size());
        assertEquals(2, chain.path("weighingVersion").asInt());
        String total = totalOf(chain, "A");
        assertTrue(new BigDecimal("50").compareTo(new BigDecimal(total)) == 0
                || new BigDecimal("55").compareTo(new BigDecimal(total)) == 0,
                "有效总量必须是某个胜出替代量: " + total);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batch));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createRecipe5050(String recipeKey) throws Exception {
        postJson("/api/recipes", "ops", recipeBody("RQ-" + unique(), recipeKey,
                "[{\"name\":\"A\",\"percentage\":50},{\"name\":\"B\",\"percentage\":50}]"), 201);
    }

    private String recipeBody(String requestId, String recipeKey, String ingredientsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"ingredients\":" + ingredientsJson + "}";
    }

    private void createBatchWithRecipe(String batchKey, String recipeKey, String targetMass,
                                       List<String> tests, int expected) throws Exception {
        postJson("/api/batches",
                batchBody("CK-B-" + unique(), batchKey, recipeKey, targetMass, tests), expected);
    }

    private void createPlainBatch(String batchKey, List<String> tests, int expected)
            throws Exception {
        postJson("/api/batches", batchBody("CK-B-" + unique(), batchKey, null, null, tests),
                expected);
    }

    private String batchBody(String commandKey, String batchKey, String recipeKey,
                             String targetMass, List<String> tests) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"commandKey\":\"").append(commandKey).append("\",\"batchKey\":\"")
                .append(batchKey)
                .append("\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                        + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[");
        for (int i = 0; i < tests.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(tests.get(i)).append('"');
        }
        sb.append(']');
        if (recipeKey != null) {
            sb.append(",\"recipeKey\":\"").append(recipeKey)
                    .append("\",\"targetMassGrams\":").append(targetMass);
        }
        sb.append('}');
        return sb.toString();
    }

    private String weighBody(String requestId, String ingredient, String amount,
                             int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"amountGrams\":" + amount + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private String correctBody(String requestId, long weighingId, String amount,
                               int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"weighingId\":" + weighingId
                + ",\"amountGrams\":" + amount + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private String splitBody(String commandKey, String parent, String child1, String target1,
                             String child2, String target2) {
        return "{\"commandKey\":\"" + commandKey + "\",\"children\":["
                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"N1\",\"targetMassGrams\":"
                + target1 + "},"
                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"N2\",\"targetMassGrams\":"
                + target2 + "}]}";
    }

    private MvcResult postJson(String url, String body, int expected) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult postJson(String url, String actor, String body, int expected)
            throws Exception {
        return mockMvc.perform(post(url).header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult weigh(String batchKey, String actor, String requestId, String ingredient,
                            String amount, int expectedVersion, int expected) throws Exception {
        return postJson("/api/batches/" + batchKey + "/weighings", actor,
                weighBody(requestId, ingredient, amount, expectedVersion), expected);
    }

    private MvcResult correct(String batchKey, String actor, String requestId, long weighingId,
                              String amount, int expectedVersion, int expected) throws Exception {
        return postJson("/api/batches/" + batchKey + "/weighings/corrections", actor,
                correctBody(requestId, weighingId, amount, expectedVersion), expected);
    }

    private long weighingId(MvcResult result) throws Exception {
        return readTree(result).path("weighingId").asLong();
    }

    private JsonNode weighingChain(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/weighings"))
                .andExpect(status().isOk()).andReturn();
        return readTree(result);
    }

    private String totalOf(JsonNode chain, String ingredient) {
        for (JsonNode total : chain.path("totals")) {
            if (ingredient.equals(total.path("ingredient").asText())) {
                return total.path("totalGrams").asText();
            }
        }
        fail("称量链缺少成分汇总: " + ingredient);
        return null;
    }

    private void assertDecimal(String expected, String actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                "期望 " + expected + " 实际 " + actual);
    }

    private void submitPass(String batchKey, String item) throws Exception {
        postJson("/api/batches/" + batchKey + "/tests",
                "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                        + "\",\"testItem\":\"" + item
                        + "\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}", 201);
    }

    private void approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        approveRaw(batchKey, actor, role, expected);
    }

    private MvcResult approveRaw(String batchKey, String actor, String role, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return readTree(result).path("batch").path("status").asText();
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callStatusAndBody(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            return result.getResponse().getStatus() + "|"
                    + result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private <T> List<Future<T>> runConcurrent(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
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