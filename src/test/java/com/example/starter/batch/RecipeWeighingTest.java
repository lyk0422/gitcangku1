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
import java.time.Instant;
import java.util.ArrayList;
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
 * 配方称量端到端测试：配方版本校验、绑定、称量累加与更正、放行配方门禁（422 各成分差额）、
 * 拆分质量守恒继承、requestId 幂等与并发互斥。真实 H2（MODE=MySQL）事务验证。
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
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM batch_recipe_binding");
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

    // ---------- 主流程 ----------

    @Test
    void happyFlow_recipe5050_target100_weigh50and50_releases() throws Exception {
        String recipeKey = "RC-HAPPY-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-RW-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);

        // 称量 50 与 50：占比恰为 50%/50%，满足配方
        weigh(batchKey, "淀粉", "50", 0, 201);
        weigh(batchKey, "糖", "50", 1, 201);

        JsonNode status = recipeStatus(batchKey);
        assertTrue(status.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal("100").compareTo(status.path("totalMass").decimalValue()));
        for (JsonNode ingredient : status.path("ingredients")) {
            assertTrue(ingredient.path("weighed").asBoolean());
            assertTrue(ingredient.path("withinTolerance").asBoolean());
            assertEquals(0, BigDecimal.ZERO.compareTo(ingredient.path("massDifference").decimalValue()));
        }

        submitTest(batchKey, "TK-1", "含量", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void outOfTolerance_50_100001_and_49_899999_rejected422() throws Exception {
        String recipeKey = "RC-TOL-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-TOL-" + unique();
        createBatch(batchKey, List.of("含量"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);

        // 总质量恰为 100，但淀粉占比 50.100001% 偏差 0.100001 > 0.1 个百分点
        weigh(batchKey, "淀粉", "50.100001", 0, 201);
        weigh(batchKey, "糖", "49.899999", 1, 201);

        JsonNode status = recipeStatus(batchKey);
        assertFalse(status.path("satisfied").asBoolean());

        submitTest(batchKey, "TK-1", "含量", "PASS", "insp-1", 201);
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A1\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode details = error.path("details");
        assertEquals(2, details.size(), "422 须给出各成分差额");
        for (JsonNode ingredient : details) {
            if (ingredient.path("name").asText().equals("淀粉")) {
                assertFalse(ingredient.path("withinTolerance").asBoolean());
                assertEquals(0, new BigDecimal("0.100001")
                        .compareTo(ingredient.path("massDifference").decimalValue()));
            } else {
                assertEquals(0, new BigDecimal("-0.100001")
                        .compareTo(ingredient.path("massDifference").decimalValue()));
            }
        }
        assertEquals("PENDING_RELEASE", currentStatus(batchKey), "拒绝后状态不变");
    }

    // ---------- 配方与绑定校验 ----------

    @Test
    void recipeValidation_returns400() throws Exception {
        String recipeKey = "RC-BAD-" + unique();
        // 成分 1 种 / 7 种
        createRecipe(recipeKey, 1, new String[][]{{"a", "100"}}, 400);
        createRecipe(recipeKey, 1, new String[][]{{"a", "10"}, {"b", "10"}, {"c", "10"}, {"d", "10"},
                {"e", "10"}, {"f", "10"}, {"g", "40"}}, 400);
        // 百分比为 0 / 负值
        createRecipe(recipeKey, 1, new String[][]{{"a", "0"}, {"b", "100"}}, 400);
        createRecipe(recipeKey, 1, new String[][]{{"a", "-1"}, {"b", "101"}}, 400);
        // 百分比超过 4 位小数
        createRecipe(recipeKey, 1, new String[][]{{"a", "50.00001"}, {"b", "49.99999"}}, 400);
        // 总和不等于 100
        createRecipe(recipeKey, 1, new String[][]{{"a", "50"}, {"b", "50.0001"}}, 400);
        // 成分名重复
        createRecipe(recipeKey, 1, new String[][]{{"a", "50"}, {"a", "50"}}, 400);
        // 缺操作人
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeBody("RID-NOAUTH-" + unique(), recipeKey, 1,
                                new String[][]{{"a", "50"}, {"b", "50"}})))
                .andExpect(status().isBadRequest());
        // 全部失败后配方未创建
        mockMvc.perform(get("/api/recipes/" + recipeKey + "/versions/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateRecipeVersion_returns409_andImmutable() throws Exception {
        String recipeKey = "RC-DUP-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"a", "50"}, {"b", "50"}}, 201);
        // 同版本不同内容 → 409；同版本同内容（不同 requestId）也 409，版本不可改写
        createRecipe(recipeKey, 1, new String[][]{{"a", "60"}, {"b", "40"}}, 409);
        createRecipe(recipeKey, 1, new String[][]{{"a", "50"}, {"b", "50"}}, 409);
        JsonNode recipe = objectMapper.readTree(mockMvc
                .perform(get("/api/recipes/" + recipeKey + "/versions/1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("50")
                .compareTo(recipe.path("ingredients").get(0).path("percentage").decimalValue()));
    }

    @Test
    void bindValidation_andWeighingArgumentErrors() throws Exception {
        String recipeKey = "RC-BV-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-BV-" + unique();
        createBatch(batchKey, List.of("t1"), 201);

        // 未绑定配方直接称量 → 422
        weigh(batchKey, "淀粉", "1", 0, 422);
        // 未知配方版本 → 404；目标质量非正值 / 超 6 位小数 → 400
        bind(batchKey, recipeKey, 9, "100", 404);
        bind(batchKey, recipeKey, 1, "0", 400);
        bind(batchKey, recipeKey, 1, "100.0000001", 400);
        bind(batchKey, recipeKey, 1, "100", 201);
        // 重复绑定 → 409
        bind(batchKey, recipeKey, 1, "100", 409);

        // 负量 / 零 / 超 6 位小数 / 未知成分 → 400
        weigh(batchKey, "淀粉", "-1", 0, 400);
        weigh(batchKey, "淀粉", "0", 0, 400);
        weigh(batchKey, "淀粉", "1.0000001", 0, 400);
        weigh(batchKey, "香精", "1", 0, 400);
        // 期望版本不一致 → 409
        weigh(batchKey, "淀粉", "1", 5, 409);
        // 缺操作人 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("RID-X-" + unique(), "淀粉", "1", 0)))
                .andExpect(status().isBadRequest());
        // 全部失败：称量链版本仍为 0，无称量记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- 称量累加与更正 ----------

    @Test
    void correction_replacesInsteadOfAccumulates_andHistoryKept() throws Exception {
        String recipeKey = "RC-COR-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-COR-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);

        // 同成分多次称量累加：40 + 20 = 60，超差
        weigh(batchKey, "淀粉", "40", 0, 201);
        long secondId = weighingId(weigh(batchKey, "淀粉", "20", 1, 201));
        JsonNode before = recipeStatus(batchKey);
        assertFalse(before.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal("60").compareTo(
                before.path("ingredients").get(0).path("actualMass").decimalValue()));

        // 更正最近一条有效称量（20 → 10）：以替代量重算，40 + 10 = 50
        correct(batchKey, secondId, "10", 2, 201);
        JsonNode after = recipeStatus(batchKey);
        assertEquals(0, new BigDecimal("50").compareTo(
                after.path("ingredients").get(0).path("actualMass").decimalValue()));
        assertEquals(3, after.path("weighingVersion").asLong());

        // 历史完整保留：3 条记录，被替代的 20 已失效，更正记录链接旧记录
        JsonNode history = objectMapper.readTree(mockMvc
                .perform(get("/api/batches/" + batchKey + "/weighings"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(3, history.size());
        assertFalse(history.get(1).path("effective").asBoolean());
        assertEquals(0, new BigDecimal("20").compareTo(history.get(1).path("amount").decimalValue()));
        assertEquals("CORRECTION", history.get(2).path("source").asText());
        assertEquals(secondId, history.get(2).path("supersedesId").asLong());
        assertTrue(history.get(2).path("effective").asBoolean());

        // 被替代记录不能再次更正；非最近有效记录不能更正
        correct(batchKey, secondId, "10", 3, 409);
        long firstId = history.get(0).path("weighingId").asLong();
        correct(batchKey, firstId, "10", 3, 409);
        // 不存在的称量记录 → 404
        correct(batchKey, 999999L, "10", 3, 404);

        // 补足糖后放行
        weigh(batchKey, "糖", "50", 3, 201);
        assertTrue(recipeStatus(batchKey).path("satisfied").asBoolean());
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void correctionArgumentErrors_return400() throws Exception {
        String recipeKey = "RC-CORV-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-CORV-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);
        long id = weighingId(weigh(batchKey, "淀粉", "50", 0, 201));

        // 负量 / 超精度更正 → 400；期望版本不一致 → 409
        correct(batchKey, id, "-5", 1, 400);
        correct(batchKey, id, "5.0000001", 1, 400);
        correct(batchKey, id, "5", 7, 409);
        JsonNode status = recipeStatus(batchKey);
        assertEquals(0, new BigDecimal("50").compareTo(
                status.path("ingredients").get(0).path("actualMass").decimalValue()),
                "失败更正不得改变有效称量");
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseGate_missingIngredientAndTotalMismatch_422WithDiffs() throws Exception {
        String recipeKey = "RC-GATE-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-GATE-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);

        // 只称淀粉：糖缺失 → 422，差额明细给出糖 -50
        weigh(batchKey, "淀粉", "50", 0, 201);
        JsonNode error = objectMapper.readTree(approveRaw(batchKey, "qa-1", "QUALITY", "CK-A1", 422));
        JsonNode details = error.path("details");
        assertEquals(2, details.size());
        JsonNode sugar = details.get(1);
        assertEquals("糖", sugar.path("name").asText());
        assertFalse(sugar.path("weighed").asBoolean());
        assertEquals(0, new BigDecimal("-50").compareTo(sugar.path("massDifference").decimalValue()));

        // 糖称 40：总质量 90 ≠ 100 → 仍 422
        long sugarId = weighingId(weigh(batchKey, "糖", "40", 1, 201));
        approveRaw(batchKey, "qa-1", "QUALITY", "CK-A2", 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 更正糖至 50 后放行
        correct(batchKey, sugarId, "50", 2, 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A3", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A4", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseFreezesWeighingChain_andSplitParentCannotCorrect() throws Exception {
        String recipeKey = "RC-FRZ-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-FRZ-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);
        long id = weighingId(weigh(batchKey, "淀粉", "50", 0, 201));
        weigh(batchKey, "糖", "50", 1, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行冻结有效称量链：称量与更正均 409
        weigh(batchKey, "淀粉", "1", 2, 409);
        correct(batchKey, id, "49", 2, 409);

        // 拆分为 60/40 后父批 SPLIT：已拆父批不能再更正
        split(batchKey, "CK-SP-1",
                new String[][]{{"BK-FRZ-C1-" + unique(), "L1", "60"},
                        {"BK-FRZ-C2-" + unique(), "L2", "40"}}, 201);
        assertEquals("SPLIT", currentStatus(batchKey));
        correct(batchKey, id, "49", 2, 409);
        weigh(batchKey, "淀粉", "1", 2, 409);
    }

    // ---------- 幂等 ----------

    @Test
    void requestId_sameParamsReplay_changedParams409_failureDoesNotOccupyKey() throws Exception {
        String recipeKey = "RC-IDEM-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);

        // 同 requestId 同参重放：返回首次完整响应，不新增记录
        String body = weighBody("RID-W1", "淀粉", "50", 0);
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));

        // 同 requestId 异参（数量不同 / 操作者不同）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("RID-W1", "淀粉", "51", 0)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("RID-W1", "淀粉", "50", 0)))
                .andExpect(status().isConflict());

        // 失败不占键：先用 RID-W2 提交超精度（400），再用同键成功提交
        weigh(batchKey, "糖", "1.0000001", 1, 400, "RID-W2");
        weigh(batchKey, "糖", "50", 1, 201, "RID-W2");
        assertTrue(recipeStatus(batchKey).path("satisfied").asBoolean());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-W2'", Integer.class));
    }

    // ---------- 拆分守恒继承 ----------

    @Test
    void split_inheritsIngredientMassesByChildRatio() throws Exception {
        String recipeKey = "RC-SPI-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String parent = "BK-SPI-" + unique();
        createBatch(parent, List.of("t1"), 201);
        bind(parent, recipeKey, 1, "100", 201);
        weigh(parent, "淀粉", "50", 0, 201);
        weigh(parent, "糖", "50", 1, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        String child1 = "BK-SPI-C1-" + unique();
        String child2 = "BK-SPI-C2-" + unique();
        split(parent, "CK-SP-1", new String[][]{{child1, "L1", "60"}, {child2, "L2", "40"}}, 201);

        // 子批按质量比例精确继承：60g 子批得 30/30，40g 子批得 20/20，占比不变仍满足配方
        assertChildRecipe(child1, recipeKey, "60", "30", "30");
        assertChildRecipe(child2, recipeKey, "40", "20", "20");
        // 成分质量守恒：子批之和等于父批各成分量
        BigDecimal starchSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM weighing WHERE ingredient = '淀粉'"
                        + " AND batch_key IN (?, ?) AND superseded_by IS NULL",
                BigDecimal.class, child1, child2);
        assertEquals(0, new BigDecimal("50").compareTo(starchSum));
        // 子批可继续称量（继承记录不占版本）：目标 60 已满足，补称会导致超差，仅验证可操作
        assertEquals(0, jdbc.queryForObject(
                "SELECT weighing_version FROM batch_recipe_binding WHERE batch_key = ?",
                Integer.class, child1));
    }

    @Test
    void split_nonConserving_returns422_andParentUnchanged() throws Exception {
        String recipeKey = "RC-SPN-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String parent = "BK-SPN-" + unique();
        createBatch(parent, List.of("t1"), 201);
        bind(parent, recipeKey, 1, "100", 201);
        weigh(parent, "淀粉", "50", 0, 201);
        weigh(parent, "糖", "50", 1, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        // 子批质量之和不等于父批目标总质量 → 422
        split(parent, "CK-SP-1", new String[][]{{"BK-SPN-C1-" + unique(), "L1", "60"},
                {"BK-SPN-C2-" + unique(), "L2", "30"}}, 422);
        // 50 × 33.333333 / 100 = 16.6666665，超出 6 位小数无法精确守恒 → 422
        split(parent, "CK-SP-2", new String[][]{{"BK-SPN-C3-" + unique(), "L3", "33.333333"},
                {"BK-SPN-C4-" + unique(), "L4", "33.333333"},
                {"BK-SPN-C5-" + unique(), "L5", "33.333334"}}, 422);
        // 已绑定配方的父批拆分缺少 mass → 400
        split(parent, "CK-SP-3", new String[][]{{"BK-SPN-C6-" + unique(), "L6", null},
                {"BK-SPN-C7-" + unique(), "L7", null}}, 400);

        // 整次失败：父批仍 RELEASED，未创建任何子批与继承记录
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, parent));
    }

    @Test
    void splitWithoutBinding_rejectsChildMass400() throws Exception {
        String parent = "BK-SNB-" + unique();
        createBatch(parent, List.of("t1"), 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);
        split(parent, "CK-SP-1", new String[][]{{"BK-SNB-C1-" + unique(), "L1", "60"},
                {"BK-SNB-C2-" + unique(), "L2", null}}, 400);
        assertEquals("RELEASED", currentStatus(parent));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameRequestId_weighing_singleWinnerSameResponse() throws Exception {
        String recipeKey = "RC-CW-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-CW-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);

        String body = weighBody("RID-CW-1", "淀粉", "50", 0);
        List<Future<String>> results = runConcurrent(
                () -> callBody(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callBody(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue(first.startsWith("201|"), "并发同键同参应均成功: " + first);
        assertEquals(first, second, "并发同键同参应返回首次完整响应");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'RID-CW-1'", Integer.class));
    }

    @Test
    void concurrentWeighingAndCorrection_versionConflictSerialized() throws Exception {
        String recipeKey = "RC-CV-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String batchKey = "BK-CV-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        bind(batchKey, recipeKey, 1, "100", 201);
        long id = weighingId(weigh(batchKey, "淀粉", "40", 0, 201));

        // 同一期望版本并发：称量与更正互斥，恰一个成功，另一个 409
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody("RID-CV-1", "淀粉", "10", 1))),
                () -> callStatus(post("/api/batches/" + batchKey + "/weighing-corrections")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctBody("RID-CV-2", id, "45", 1))));
        int created = 0;
        int conflicted = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflicted++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, conflicted);
        // 最终数据一致：称量链版本恰好前进 1；按胜出者核对最终有效称量
        JsonNode status = recipeStatus(batchKey);
        assertEquals(2, status.path("weighingVersion").asLong());
        Integer effective = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NULL",
                Integer.class, batchKey);
        Integer corrections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND source = 'CORRECTION'",
                Integer.class, batchKey);
        BigDecimal actualStarch = status.path("ingredients").get(0).path("actualMass").decimalValue();
        if (corrections == 1) {
            assertEquals(1, effective, "更正胜出：旧记录失效，仅替代记录有效");
            assertEquals(0, new BigDecimal("45").compareTo(actualStarch));
        } else {
            assertEquals(2, effective, "称量胜出：两条称量均有效并累加");
            assertEquals(0, new BigDecimal("50").compareTo(actualStarch));
        }
    }

    @Test
    void concurrentCorrectionAndSplitOnReleased_parentRowLockMutex() throws Exception {
        String recipeKey = "RC-CS-" + unique();
        createRecipe(recipeKey, 1, new String[][]{{"淀粉", "50"}, {"糖", "50"}}, 201);
        String parent = "BK-CS-" + unique();
        createBatch(parent, List.of("t1"), 201);
        bind(parent, recipeKey, 1, "100", 201);
        long id = weighingId(weigh(parent, "淀粉", "50", 0, 201));
        weigh(parent, "糖", "50", 1, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp-1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        // 拆分与称量更正互斥：更正因 RELEASED 冻结必 409，拆分 201；已拆父批不能再更正
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + parent + "/weighing-corrections")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctBody("RID-CS-1", id, "49", 2))),
                () -> callStatus(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-SP-1",
                                new String[][]{{"BK-CS-C1-" + unique(), "L1", "50"},
                                        {"BK-CS-C2-" + unique(), "L2", "50"}}))));
        assertEquals(409, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(201, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals("SPLIT", currentStatus(parent));
        assertEquals(0, new BigDecimal("50").compareTo(jdbc.queryForObject(
                "SELECT amount FROM weighing WHERE id = ?", BigDecimal.class, id)),
                "冻结的称量链不得被改写");
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String recipeBody(String requestId, String recipeKey, int version,
                              String[][] ingredients) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String[] ingredient : ingredients) {
            entries.add(Map.of("name", ingredient[0], "percentage", new BigDecimal(ingredient[1])));
        }
        return objectMapper.writeValueAsString(Map.of("requestId", requestId,
                "recipeKey", recipeKey, "version", version, "ingredients", entries));
    }

    private void createRecipe(String recipeKey, int version, String[][] ingredients, int expected)
            throws Exception {
        mockMvc.perform(post("/api/recipes")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeBody("RID-RC-" + unique(), recipeKey, version, ingredients)))
                .andExpect(status().is(expected));
    }

    private void bind(String batchKey, String recipeKey, int version, String targetMass, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recipe-binding")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "RID-B-" + unique(),
                                "recipeKey", recipeKey, "version", version,
                                "targetMass", new BigDecimal(targetMass)))))
                .andExpect(status().is(expected));
    }

    private String weighBody(String requestId, String ingredient, String amount, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"amount\":" + amount + ",\"expectedVersion\":" + version + "}";
    }

    private MvcResult weigh(String batchKey, String ingredient, String amount, long version,
                            int expected) throws Exception {
        return weigh(batchKey, ingredient, amount, version, expected, "RID-W-" + unique());
    }

    private MvcResult weigh(String batchKey, String ingredient, String amount, long version,
                            int expected, String requestId) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody(requestId, ingredient, amount, version)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String correctBody(String requestId, long weighingId, String amount, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"weighingId\":" + weighingId
                + ",\"amount\":" + amount + ",\"expectedVersion\":" + version + "}";
    }

    private MvcResult correct(String batchKey, long weighingId, String amount, long version,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighing-corrections")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctBody("RID-C-" + unique(), weighingId, amount, version)))
                .andExpect(status().is(expected)).andReturn();
    }

    private long weighingId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("weighingId").asLong();
    }

    private JsonNode recipeStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/recipe"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertChildRecipe(String childKey, String recipeKey, String target,
                                   String starch, String sugar) throws Exception {
        JsonNode status = recipeStatus(childKey);
        assertEquals(recipeKey, status.path("recipeKey").asText());
        assertEquals(0, new BigDecimal(target).compareTo(status.path("targetMass").decimalValue()));
        assertTrue(status.path("satisfied").asBoolean());
        assertEquals(0, new BigDecimal(starch).compareTo(
                status.path("ingredients").get(0).path("actualMass").decimalValue()));
        assertEquals(0, new BigDecimal(sugar).compareTo(
                status.path("ingredients").get(1).path("actualMass").decimalValue()));
    }

    private String splitBody(String commandKey, String[][] children) throws Exception {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (String[] child : children) {
            Map<String, Object> spec = child[2] == null
                    ? Map.of("batchKey", child[0], "batchNo", child[1])
                    : Map.of("batchKey", child[0], "batchNo", child[1],
                            "mass", new BigDecimal(child[2]));
            specs.add(spec);
        }
        return objectMapper.writeValueAsString(Map.of("commandKey", commandKey, "children", specs));
    }

    private void split(String batchKey, String commandKey, String[][] children, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected));
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "commandKey", "CK-1", "batchKey", batchKey, "productCode", "PROD-1",
                                "batchNo", "LOT-1",
                                "producedAt", Instant.parse("2026-01-02T03:04:05Z"),
                                "requiredTests", items))))
                .andExpect(status().is(expected));
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "commandKey", "CK-T-" + unique(), "testKey", testKey,
                                "testItem", item, "result", result, "inspector", inspector))))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        approveRaw(batchKey, actor, role, commandKey, expected);
    }

    private String approveRaw(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
        return result.getResponse().getContentAsString();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            return result.getResponse().getStatus() + "|" + result.getResponse().getContentAsString();
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


