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
 * 配方称量端到端测试（真实 H2 事务）：配方版本校验、绑定、称量累加与更正、
 * 放行配方门禁（含各成分差额）、requestId 幂等与并发、拆分质量守恒继承。
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
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM batch_recipe_binding");
        jdbc.update("DELETE FROM recipe_ingredient");
        jdbc.update("DELETE FROM recipe_version");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 配方版本 ----------

    @Test
    void recipeValidation_rules() throws Exception {
        // 成分数量越界
        createRecipe("R-1-" + unique(), "RID-1", ingredientsJson(new String[][]{{"A", "100"}}), 400);
        createRecipe("R-7-" + unique(), "RID-7", ingredientsJson(new String[][]{
                {"A", "10"}, {"B", "10"}, {"C", "10"}, {"D", "10"},
                {"E", "10"}, {"F", "10"}, {"G", "40"}}), 400);
        // 合计不精确等于 100
        createRecipe("R-S-" + unique(), "RID-S",
                ingredientsJson(new String[][]{{"A", "50.0001"}, {"B", "50"}}), 400);
        // 超精度（5 位小数）、零值、负值
        createRecipe("R-P1-" + unique(), "RID-P1",
                ingredientsJson(new String[][]{{"A", "50.00001"}, {"B", "49.99999"}}), 400);
        createRecipe("R-P2-" + unique(), "RID-P2",
                ingredientsJson(new String[][]{{"A", "0"}, {"B", "100"}}), 400);
        createRecipe("R-P3-" + unique(), "RID-P3",
                ingredientsJson(new String[][]{{"A", "-1"}, {"B", "101"}}), 400);
        // 重复成分名
        createRecipe("R-D-" + unique(), "RID-D",
                ingredientsJson(new String[][]{{"A", "50"}, {"A", "50"}}), 400);

        // 合法配方：201 且可查询
        String recipeKey = "R-OK-" + unique();
        MvcResult created = createRecipe(recipeKey, "RID-OK",
                ingredientsJson(new String[][]{{"A", "50"}, {"B", "50"}}), 201);
        MvcResult queried = mockMvc.perform(get("/api/recipes/" + recipeKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode recipe = objectMapper.readTree(queried.getResponse().getContentAsString());
        assertEquals(2, recipe.path("ingredients").size());
        assertEquals(0, new BigDecimal("50").compareTo(
                recipe.path("ingredients").get(0).path("percentage").decimalValue()));

        // 同 requestId 同参重放：返回首次完整响应
        MvcResult replay = createRecipe(recipeKey, "RID-OK",
                ingredientsJson(new String[][]{{"A", "50"}, {"B", "50"}}), 201);
        assertEquals(created.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 改参：409
        createRecipe(recipeKey, "RID-OK",
                ingredientsJson(new String[][]{{"A", "60"}, {"B", "40"}}), 409);
        // 同 recipeKey 不同 requestId：409（配方版本不可变）
        createRecipe(recipeKey, "RID-OTHER",
                ingredientsJson(new String[][]{{"A", "50"}, {"B", "50"}}), 409);
    }

    // ---------- 绑定 ----------

    @Test
    void bindRecipe_rules() throws Exception {
        String recipeKey = "R-B-" + unique();
        createRecipe(recipeKey, "RID-B", ingredientsJson(new String[][]{{"A", "50"}, {"B", "50"}}), 201);
        String batchKey = "BK-B-" + unique();
        createBatch(batchKey, List.of("t1"));

        // 未知配方 404；未知批次 404；超精度/零目标质量 400；缺少操作人 400
        bind(batchKey, "R-NONE", "100", "RID-BN", 404);
        bind("BK-NONE", recipeKey, "100", "RID-BB", 404);
        bind(batchKey, recipeKey, "100.0000001", "RID-BP", 400);
        bind(batchKey, recipeKey, "0", "RID-BZ", 400);
        bindNoOperator(batchKey, recipeKey, "100", "RID-BO", 400);

        MvcResult bound = bind(batchKey, recipeKey, "100", "RID-B1", 201);
        JsonNode body = objectMapper.readTree(bound.getResponse().getContentAsString());
        assertEquals(0, body.path("weighingVersion").asLong());
        // 重放返回首次响应；重复绑定 409
        MvcResult replay = bind(batchKey, recipeKey, "100", "RID-B1", 201);
        assertEquals(bound.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        bind(batchKey, recipeKey, "200", "RID-B2", 409);
    }
    // ---------- 称量与放行门禁 ----------

    @Test
    void weighAccumulate_releaseHappyPath() throws Exception {
        String batchKey = "BK-H-" + unique();
        String recipeKey = boundBatch(batchKey, "100");

        // 同成分多次称量累加：A 25+25，B 50
        weigh(batchKey, "A", "25", 0, "RID-W1", 201);
        weigh(batchKey, "A", "25", 1, "RID-W2", 201);
        weigh(batchKey, "B", "50", 2, "RID-W3", 201);

        JsonNode status = weighingStatus(batchKey);
        assertTrue(status.path("satisfied").asBoolean());
        assertEquals(3, status.path("weighingVersion").asLong());
        assertEquals(0, new BigDecimal("100").compareTo(status.path("totalMass").decimalValue()));

        passAllTests(batchKey, List.of("t1"));
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void deviationRejected_exactBoundary_thenCorrectThenRelease() throws Exception {
        String batchKey = "BK-D-" + unique();
        boundBatch(batchKey, "100");

        // 题干合成用例：50.100001 与 49.899999，偏差 0.100001 个百分点，超差
        MvcResult w1 = weigh(batchKey, "A", "50.100001", 0, "RID-D1", 201);
        MvcResult w2 = weigh(batchKey, "B", "49.899999", 1, "RID-D2", 201);
        JsonNode status = weighingStatus(batchKey);
        assertFalse(status.path("satisfied").asBoolean());

        passAllTests(batchKey, List.of("t1"));
        approve(batchKey, "qa-1", "QUALITY", "CK-D1", 201);
        // 不达配方：第二批准 422，给出各成分差额，不提前舍入
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", "CK-D2", 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", error.path("code").asText());
        JsonNode details = error.path("details");
        assertEquals(2, details.size());
        JsonNode a = details.get(0);
        assertEquals("A", a.path("ingredient").asText());
        assertEquals(0, new BigDecimal("0.100001").compareTo(a.path("difference").decimalValue()));
        assertFalse(a.path("withinTolerance").asBoolean());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 两种成分分别更正为 50：以替代量重算；失败的批准不占 commandKey，可原键重试
        long idA = objectMapper.readTree(w1.getResponse().getContentAsString())
                .path("weighingId").asLong();
        long idB = objectMapper.readTree(w2.getResponse().getContentAsString())
                .path("weighingId").asLong();
        correct(batchKey, idA, "50", 2, "RID-DC1", 201);
        correct(batchKey, idB, "50", 3, "RID-DC2", 201);
        assertTrue(weighingStatus(batchKey).path("satisfied").asBoolean());
        approve(batchKey, "ops-1", "OPERATIONS", "CK-D2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseGate_listsAllIngredients_notPartialApproval() throws Exception {
        String batchKey = "BK-ALL-" + unique();
        String recipeKey = "R-ALL-" + unique();
        createRecipe(recipeKey, "RID-ALL",
                ingredientsJson(new String[][]{{"A", "50"}, {"B", "30"}, {"C", "20"}}), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, recipeKey, "100", "RID-ALLB", 201);

        // A、C 超差，B 合格：不允许只批准合格成分
        weigh(batchKey, "A", "50.15", 0, "RID-A1", 201);
        weigh(batchKey, "B", "30", 1, "RID-A2", 201);
        weigh(batchKey, "C", "19.85", 2, "RID-A3", 201);
        passAllTests(batchKey, List.of("t1"));
        approve(batchKey, "qa-1", "QUALITY", "CK-AL1", 201);
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", "CK-AL2", 422);
        JsonNode details = objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("details");
        assertEquals(3, details.size());
        assertFalse(details.get(0).path("withinTolerance").asBoolean());
        assertTrue(details.get(1).path("withinTolerance").asBoolean());
        assertFalse(details.get(2).path("withinTolerance").asBoolean());
        assertEquals(0, new BigDecimal("-0.15").compareTo(
                details.get(2).path("difference").decimalValue()));
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
    }

    @Test
    void weighValidation_branches() throws Exception {
        String batchKey = "BK-V-" + unique();
        boundBatch(batchKey, "100");

        // 负量、零量、超精度、未知成分 400
        weigh(batchKey, "A", "-1", 0, "RID-V1", 400);
        weigh(batchKey, "A", "0", 0, "RID-V2", 400);
        weigh(batchKey, "A", "1.0000001", 0, "RID-V3", 400);
        weigh(batchKey, "UNKNOWN", "10", 0, "RID-V4", 400);
        // 期望版本不一致 409；缺少操作人 400；未知批次 404
        weigh(batchKey, "A", "10", 5, "RID-V5", 409);
        weighNoOperator(batchKey, "A", "10", 0, "RID-V6", 400);
        weigh("BK-NONE", "A", "10", 0, "RID-V7", 404);
        // 未绑定配方 422
        String unbound = "BK-VU-" + unique();
        createBatch(unbound, List.of("t1"));
        weigh(unbound, "A", "10", 0, "RID-V8", 422);

        // 失败不占键：上述 requestId 可重新用于合法请求
        weigh(batchKey, "A", "10", 0, "RID-V3", 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Long.class, batchKey));
    }
    // ---------- 更正 ----------

    @Test
    void correction_rules() throws Exception {
        String batchKey = "BK-C-" + unique();
        boundBatch(batchKey, "100");

        MvcResult w1 = weigh(batchKey, "A", "60", 0, "RID-C1", 201);
        long id1 = objectMapper.readTree(w1.getResponse().getContentAsString())
                .path("weighingId").asLong();
        weigh(batchKey, "B", "50", 1, "RID-C2", 201);

        // 更正最近有效称量：60 替代为 50，配方达标
        correct(batchKey, id1, "50", 2, "RID-C3", 201);
        JsonNode status = weighingStatus(batchKey);
        assertTrue(status.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal("50").compareTo(
                status.path("ingredients").get(0).path("actualMass").decimalValue()));

        // 已失效记录不能再次更正 409；未知称量 404；负量/超精度 400
        correct(batchKey, id1, "50", 3, "RID-C4", 409);
        correct(batchKey, 999999L, "50", 3, "RID-C5", 404);
        correct(batchKey, id1, "-5", 3, "RID-C6", 400);
        correct(batchKey, id1, "1.0000001", 3, "RID-C7", 400);
    }

    @Test
    void correction_mustTargetLatestEffective() throws Exception {
        String batchKey = "BK-CL-" + unique();
        boundBatch(batchKey, "200");

        MvcResult w1 = weigh(batchKey, "A", "30", 0, "RID-L1", 201);
        long id1 = objectMapper.readTree(w1.getResponse().getContentAsString())
                .path("weighingId").asLong();
        MvcResult w2 = weigh(batchKey, "A", "20", 1, "RID-L2", 201);
        long id2 = objectMapper.readTree(w2.getResponse().getContentAsString())
                .path("weighingId").asLong();

        // id1 不是该成分最近有效称量：409；id2 可以更正
        correct(batchKey, id1, "35", 2, "RID-L3", 409);
        correct(batchKey, id2, "70", 2, "RID-L4", 201);
        JsonNode status = weighingStatus(batchKey);
        // A 有效累计 = 30 + 70 = 100
        assertEquals(0, new BigDecimal("100").compareTo(
                status.path("ingredients").get(0).path("actualMass").decimalValue()));
    }

    // ---------- 幂等与并发 ----------

    @Test
    void idempotency_replayAndConflict() throws Exception {
        String batchKey = "BK-I-" + unique();
        boundBatch(batchKey, "100");

        MvcResult first = weigh(batchKey, "A", "50", 0, "RID-I1", 201);
        // 同键同参重放：完整响应一致，不产生新记录
        MvcResult replay = weigh(batchKey, "A", "50", 0, "RID-I1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Long.class, batchKey));
        // 同键改参 409；身份含资源，同一 requestId 用于其他批次同样视为改参 409
        weigh(batchKey, "A", "51", 0, "RID-I1", 409);
        String other = "BK-I2-" + unique();
        boundBatch(other, "100");
        weigh(other, "A", "50", 0, "RID-I1", 409);
    }

    @Test
    void concurrentSameRequestId_weighRecordedOnce() throws Exception {
        String batchKey = "BK-CC-" + unique();
        boundBatch(batchKey, "1000");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return weighRaw(batchKey, "A", "10", 0, "RID-CONC");
            }));
        }
        List<String> bodies = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus());
            bodies.add(result.getResponse().getContentAsString());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        // 全部返回首次响应快照；称量记录与命令日志各一行
        assertEquals(1, bodies.stream().distinct().count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Long.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'",
                Long.class));
    }

    @Test
    void concurrentCorrections_exactlyOneWins() throws Exception {
        String batchKey = "BK-CR-" + unique();
        boundBatch(batchKey, "100");
        MvcResult w1 = weigh(batchKey, "A", "60", 0, "RID-R1", 201);
        long id1 = objectMapper.readTree(w1.getResponse().getContentAsString())
                .path("weighingId").asLong();
        weigh(batchKey, "B", "50", 1, "RID-R2", 201);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<MvcResult> f1 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return correctRaw(batchKey, id1, "50", 2, "RID-RC1");
        });
        Future<MvcResult> f2 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return correctRaw(batchKey, id1, "45", 2, "RID-RC2");
        });
        MvcResult r1 = f1.get(30, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 恰好一个更正成功；最终有效量等于胜者的替代量
        List<Integer> statuses = List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 201).count());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        JsonNode status = weighingStatus(batchKey);
        BigDecimal actualA = status.path("ingredients").get(0).path("actualMass").decimalValue();
        BigDecimal winner = statuses.get(0) == 201 ? new BigDecimal("50") : new BigDecimal("45");
        assertEquals(0, winner.compareTo(actualA));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Long.class, batchKey));
    }
    // ---------- 拆分质量守恒继承 ----------

    @Test
    void splitInheritance_proportionalExact() throws Exception {
        String parent = "BK-SP-" + unique();
        boundBatch(parent, "100");
        weigh(parent, "A", "50", 0, "RID-S1", 201);
        weigh(parent, "B", "50", 1, "RID-S2", 201);
        passAllTests(parent, List.of("t1"));
        approve(parent, "qa-1", "QUALITY", "CK-S1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-S2", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child1 = "BK-SC1-" + unique();
        String child2 = "BK-SC2-" + unique();
        MvcResult result = split(parent, "CK-SPLIT",
                "[{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"L1\",\"targetMass\":60},"
                        + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"L2\",\"targetMass\":40}]", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("60").compareTo(
                body.path("children").get(0).path("targetMass").decimalValue()));

        // 子批按 60/40 精确继承：A/B 各 30、20，绑定同一配方且称量达标
        JsonNode s1 = weighingStatus(child1);
        assertTrue(s1.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal("60").compareTo(s1.path("targetMass").decimalValue()));
        assertEquals(0, new BigDecimal("30").compareTo(
                s1.path("ingredients").get(0).path("actualMass").decimalValue()));
        JsonNode s2 = weighingStatus(child2);
        assertTrue(s2.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal("20").compareTo(
                s2.path("ingredients").get(0).path("actualMass").decimalValue()));
        // 成分质量守恒：30+20=50、20+30=50
        assertEquals(0, new BigDecimal("50").compareTo(
                s1.path("ingredients").get(0).path("actualMass").decimalValue()
                        .add(s2.path("ingredients").get(0).path("actualMass").decimalValue())));

        // 已拆父批称量链冻结：更正与称量均 409
        assertEquals("SPLIT", currentStatus(parent));
        weigh(parent, "A", "1", 2, "RID-S3", 409);
        correct(parent, 1L, "1", 2, "RID-S4", 409);
    }

    @Test
    void splitInheritance_unconservableOrInvalid() throws Exception {
        String parent = "BK-SU-" + unique();
        boundBatch(parent, "100");
        weigh(parent, "A", "50", 0, "RID-U1", 201);
        weigh(parent, "B", "50", 1, "RID-U2", 201);
        passAllTests(parent, List.of("t1"));
        approve(parent, "qa-1", "QUALITY", "CK-U1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-U2", 201);

        // 无法在 6 位小数内守恒：50 * 33.333333 / 100 = 16.6666665 → 422
        split(parent, "CK-U-SP1",
                "[{\"batchKey\":\"BK-UC1-" + unique() + "\",\"batchNo\":\"L1\",\"targetMass\":33.333333},"
                        + "{\"batchKey\":\"BK-UC2-" + unique() + "\",\"batchNo\":\"L2\",\"targetMass\":33.333333},"
                        + "{\"batchKey\":\"BK-UC3-" + unique() + "\",\"batchNo\":\"L3\",\"targetMass\":33.333334}]",
                422);
        // 子批目标质量之和不等于父批目标 → 422
        split(parent, "CK-U-SP2",
                "[{\"batchKey\":\"BK-UC4-" + unique() + "\",\"batchNo\":\"L4\",\"targetMass\":60},"
                        + "{\"batchKey\":\"BK-UC5-" + unique() + "\",\"batchNo\":\"L5\",\"targetMass\":30}]",
                422);
        // 缺少 targetMass / 非法 targetMass → 400
        split(parent, "CK-U-SP3",
                "[{\"batchKey\":\"BK-UC6-" + unique() + "\",\"batchNo\":\"L6\"},"
                        + "{\"batchKey\":\"BK-UC7-" + unique() + "\",\"batchNo\":\"L7\",\"targetMass\":100}]",
                400);
        split(parent, "CK-U-SP4",
                "[{\"batchKey\":\"BK-UC8-" + unique() + "\",\"batchNo\":\"L8\",\"targetMass\":50.0000001},"
                        + "{\"batchKey\":\"BK-UC9-" + unique() + "\",\"batchNo\":\"L9\",\"targetMass\":50}]",
                400);
        // 失败拆分整体回滚：父批仍 RELEASED，无子批落库
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Long.class));

        // 未绑定配方的父批：子批不得提供 targetMass → 400
        String plain = "BK-PL-" + unique();
        createBatch(plain, List.of("t1"));
        passAllTests(plain, List.of("t1"));
        approve(plain, "qa-1", "QUALITY", "CK-P1", 201);
        approve(plain, "ops-1", "OPERATIONS", "CK-P2", 201);
        split(plain, "CK-P-SP",
                "[{\"batchKey\":\"BK-PC1-" + unique() + "\",\"batchNo\":\"L1\",\"targetMass\":50},"
                        + "{\"batchKey\":\"BK-PC2-" + unique() + "\",\"batchNo\":\"L2\",\"targetMass\":50}]",
                400);
    }

    // ---------- 冻结与门禁 ----------

    @Test
    void releasedBatch_weighingChainFrozen() throws Exception {
        String batchKey = "BK-FZ-" + unique();
        boundBatch(batchKey, "100");
        MvcResult w1 = weigh(batchKey, "A", "50", 0, "RID-F1", 201);
        long id1 = objectMapper.readTree(w1.getResponse().getContentAsString())
                .path("weighingId").asLong();
        weigh(batchKey, "B", "50", 1, "RID-F2", 201);
        passAllTests(batchKey, List.of("t1"));
        approve(batchKey, "qa-1", "QUALITY", "CK-F1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-F2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行冻结有效称量链
        weigh(batchKey, "A", "1", 2, "RID-F3", 409);
        correct(batchKey, id1, "49", 2, "RID-F4", 409);
        assertTrue(weighingStatus(batchKey).path("satisfied").asBoolean());
    }

    @Test
    void recalledAncestor_blocksChildWeighing() throws Exception {
        String parent = "BK-RA-" + unique();
        boundBatch(parent, "100");
        weigh(parent, "A", "50", 0, "RID-RA1", 201);
        weigh(parent, "B", "50", 1, "RID-RA2", 201);
        passAllTests(parent, List.of("t1"));
        approve(parent, "qa-1", "QUALITY", "CK-RA1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-RA2", 201);

        String child = "BK-RAC-" + unique();
        split(parent, "CK-RA-SP",
                "[{\"batchKey\":\"" + child + "\",\"batchNo\":\"L1\",\"targetMass\":60},"
                        + "{\"batchKey\":\"BK-RAC2-" + unique() + "\",\"batchNo\":\"L2\",\"targetMass\":40}]",
                201);
        mockMvc.perform(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RA-R\",\"reason\":\"质量投诉\"}"))
                .andExpect(status().is(201));

        // 祖先召回门禁：子批禁止新增称量与更正
        weigh(child, "A", "1", 2, "RID-RA3", 422);
        JsonNode status = weighingStatus(child);
        long inheritedId = jdbc.queryForObject(
                "SELECT MIN(id) FROM weighing WHERE batch_key = ?", Long.class, child);
        correct(child, inheritedId, "31", 2, "RID-RA4", 422);
        assertTrue(status.path("satisfied").asBoolean());
    }
    // ---------- 辅助方法 ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String ingredientsJson(String[][] pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(pairs[i][0])
                    .append("\",\"percentage\":").append(pairs[i][1]).append('}');
        }
        return sb.append(']').toString();
    }

    private MvcResult createRecipe(String recipeKey, String requestId, String ingredients,
                                   int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"ingredients\":" + ingredients + "}";
        return mockMvc.perform(post("/api/recipes")
                        .header("X-Actor-Id", "recipe-admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBatch(String batchKey, List<String> tests) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + batchKey + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":"
                + objectMapper.writeValueAsString(tests) + "}";
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(201));
    }

    /**
     * 建立 50/50 配方并绑定到新建批次，返回配方键。
     */
    private String boundBatch(String batchKey, String targetMass) throws Exception {
        String recipeKey = "R-" + batchKey;
        createRecipe(recipeKey, "RID-CR-" + batchKey,
                ingredientsJson(new String[][]{{"A", "50"}, {"B", "50"}}), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, recipeKey, targetMass, "RID-BD-" + batchKey, 201);
        return recipeKey;
    }

    private MvcResult bind(String batchKey, String recipeKey, String targetMass,
                           String requestId, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"targetMass\":" + targetMass + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recipe-binding")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void bindNoOperator(String batchKey, String recipeKey, String targetMass,
                                String requestId, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"targetMass\":" + targetMass + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/recipe-binding")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult weigh(String batchKey, String ingredient, String mass, long version,
                            String requestId, int expected) throws Exception {
        MvcResult result = weighRaw(batchKey, ingredient, mass, version, requestId);
        assertEquals(expected, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return result;
    }

    private MvcResult weighRaw(String batchKey, String ingredient, String mass, long version,
                               String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"mass\":" + mass + ",\"expectedVersion\":" + version + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void weighNoOperator(String batchKey, String ingredient, String mass, long version,
                                 String requestId, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"mass\":" + mass + ",\"expectedVersion\":" + version + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult correct(String batchKey, long correctsId, String mass, long version,
                              String requestId, int expected) throws Exception {
        MvcResult result = correctRaw(batchKey, correctsId, mass, version, requestId);
        assertEquals(expected, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return result;
    }

    private MvcResult correctRaw(String batchKey, long correctsId, String mass, long version,
                                 String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"correctsWeighingId\":" + correctsId
                + ",\"mass\":" + mass + ",\"expectedVersion\":" + version + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighing-corrections")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode weighingStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/weighing-status"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void passAllTests(String batchKey, List<String> tests) throws Exception {
        int i = 0;
        for (String test : tests) {
            i++;
            String body = "{\"commandKey\":\"CK-T-" + batchKey + "-" + i + "\",\"testKey\":\"TK-"
                    + i + "\",\"testItem\":\"" + test + "\",\"result\":\"PASS\","
                    + "\"inspector\":\"insp-" + i + "\"}";
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(201));
        }
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor)
                        .header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult split(String parentKey, String commandKey, String childrenJson,
                            int expected) throws Exception {
        String body = "{\"commandKey\":\"" + commandKey + "\",\"children\":" + childrenJson + "}";
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }
}