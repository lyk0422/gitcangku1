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
 * 配方称量端到端测试（真实 H2 事务）：合成 50%/50% 的 100 克配方主流程与超差拒绝、
 * 称量更正替代、拆分按比例守恒继承、requestId 幂等、期望版本与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchRecipeWeighingTest {

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

    // ---------- 题干合成场景 ----------

    @Test
    void synthetic5050_weigh50And50_releases() throws Exception {
        String recipeKey = "REC-SYN-" + unique();
        String batchKey = "BK-SYN-" + unique();
        prepareWeighedBatch(recipeKey, batchKey, "50", "50");

        // 放行前：符合配方，称量链未冻结
        JsonNode before = recipeStatus(batchKey);
        assertTrue(before.path("conforms").asBoolean());
        assertFalse(before.path("frozen").asBoolean());
        assertEquals(2, before.path("stateVersion").asLong());

        passTestsAndFirstApproval(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行后称量链冻结
        JsonNode after = recipeStatus(batchKey);
        assertTrue(after.path("frozen").asBoolean());
        assertEquals(0, new BigDecimal(after.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("50")));
    }

    @Test
    void outOfTolerance_50100001And49899999_rejected422_thenCorrectedAndReleased() throws Exception {
        String recipeKey = "REC-TOL-" + unique();
        String batchKey = "BK-TOL-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        long idA = weighingId(weigh(batchKey, "RQ-W1-" + unique(), "成分A", "50.100001", 0, 201));
        long idB = weighingId(weigh(batchKey, "RQ-W2-" + unique(), "成分B", "49.899999", 1, 201));

        passTestsAndFirstApproval(batchKey);
        // 总质量精确等于目标，但成分A占比偏差 0.100001 > 0.1 个百分点 → 422 且给出各成分差额
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 422);
        JsonNode details = objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("details");
        assertTrue(details.path("totalMatches").asBoolean(), "总质量相等但占比超差仍须拒绝");
        assertFalse(details.path("conforms").asBoolean());
        assertEquals(0, new BigDecimal(details.path("ingredients").get(0)
                        .path("massDifference").asText()).compareTo(new BigDecimal("0.100001")));
        assertEquals(0, new BigDecimal(details.path("ingredients").get(1)
                        .path("massDifference").asText()).compareTo(new BigDecimal("-0.100001")));
        assertFalse(details.path("ingredients").get(0).path("withinTolerance").asBoolean());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 追加更正：以替代量重算后满足配方，可放行
        correct(batchKey, "RQ-X1-" + unique(), idA, "50", 2, 201);
        correct(batchKey, "RQ-X2-" + unique(), idB, "50", 3, 201);
        JsonNode status = recipeStatus(batchKey);
        assertTrue(status.path("conforms").asBoolean());
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void missingIngredient_returns422WithGaps() throws Exception {
        String recipeKey = "REC-MIS-" + unique();
        String batchKey = "BK-MIS-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", "100", 0, 201);

        passTestsAndFirstApproval(batchKey);
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 422);
        JsonNode ingredients = objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("details").path("ingredients");
        assertTrue(ingredients.get(0).path("weighed").asBoolean());
        assertFalse(ingredients.get(1).path("weighed").asBoolean(), "成分B未称量必须体现为缺项");
        assertTrue(ingredients.get(1).path("massDifference").isNull());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
    }

    @Test
    void totalMismatch_returns422() throws Exception {
        String recipeKey = "REC-TOT-" + unique();
        String batchKey = "BK-TOT-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", "50", 0, 201);
        weigh(batchKey, "RQ-W2-" + unique(), "成分B", "49", 1, 201);

        passTestsAndFirstApproval(batchKey);
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 422);
        JsonNode details = objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("details");
        assertFalse(details.path("totalMatches").asBoolean());
        assertEquals(0, new BigDecimal(details.path("totalMassDifference").asText())
                .compareTo(new BigDecimal("-1")));
    }

    // ---------- 参数校验 400 ----------

    @Test
    void weighValidation_negativeOverPrecisionUnknown_return400_andFailureDoesNotOccupyRequestId()
            throws Exception {
        String recipeKey = "REC-VAL-" + unique();
        String batchKey = "BK-VAL-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);

        // 负量、超精度（7 位小数）、未知成分均 400
        weigh(batchKey, "RQ-N1-" + unique(), "成分A", "-5", 0, 400);
        weigh(batchKey, "RQ-N2-" + unique(), "成分A", "1.0000001", 0, 400);
        String failedKey = "RQ-N3-" + unique();
        weigh(batchKey, failedKey, "未知成分", "10", 0, 400);
        // 失败不占键：同一 requestId 修正参数后可成功
        weigh(batchKey, failedKey, "成分A", "10", 0, 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void recipeValidation_invalidDefinitions_return400() throws Exception {
        // 成分数量越界（1 种 / 7 种）
        createRecipe("RQ-C-" + unique(), "REC-BAD1-" + unique(), 1,
                ingredients("成分A", "100"), 400);
        createRecipe("RQ-C-" + unique(), "REC-BAD7-" + unique(), 1,
                ingredients("a", "10", "b", "10", "c", "10", "d", "10", "e", "10", "f", "10",
                        "g", "40"), 400);
        // 总和 99.9999 不精确等于 100
        createRecipe("RQ-C-" + unique(), "REC-BADSUM-" + unique(), 1,
                ingredients("成分A", "50", "成分B", "49.9999"), 400);
        // 非正值
        createRecipe("RQ-C-" + unique(), "REC-BADNEG-" + unique(), 1,
                ingredients("成分A", "-1", "成分B", "101"), 400);
        // 超过 4 位小数
        createRecipe("RQ-C-" + unique(), "REC-BADSC-" + unique(), 1,
                ingredients("成分A", "50.00001", "成分B", "49.99999"), 400);
        // 成分名重复
        createRecipe("RQ-C-" + unique(), "REC-BADDUP-" + unique(), 1,
                ingredients("成分A", "50", "成分A", "50"), 400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recipe_version", Integer.class));
    }

    @Test
    void recipeVersionImmutable_sameContent200_differentContent409() throws Exception {
        String recipeKey = "REC-IMM-" + unique();
        createRecipe("RQ-C1-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        // 同版本同内容（不同 requestId）→ 200 返回既有定义
        MvcResult again = createRecipe("RQ-C2-" + unique(), recipeKey, 1,
                ingredients("成分A", "50", "成分B", "50"), 200);
        assertEquals(recipeKey, objectMapper.readTree(again.getResponse().getContentAsString())
                .path("recipeKey").asText());
        // 同版本不同内容 → 409（不可变）
        createRecipe("RQ-C3-" + unique(), recipeKey, 1, ingredients("成分A", "60", "成分B", "40"), 409);
        // 新版本号不受影响
        createRecipe("RQ-C4-" + unique(), recipeKey, 2, ingredients("成分A", "60", "成分B", "40"), 201);
    }

    // ---------- 称量更正 ----------

    @Test
    void correctionReplacesNotAccumulates_andReleaseSucceeds() throws Exception {
        String recipeKey = "REC-COR-" + unique();
        String batchKey = "BK-COR-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        long idA = weighingId(weigh(batchKey, "RQ-W1-" + unique(), "成分A", "60", 0, 201));
        // 更正为替代量 50：有效累计为 50 而非 110
        MvcResult correction = correct(batchKey, "RQ-X1-" + unique(), idA, "50", 1, 201);
        JsonNode correctionBody = objectMapper.readTree(correction.getResponse().getContentAsString());
        assertEquals(idA, correctionBody.path("correctedWeighingId").asLong());
        weigh(batchKey, "RQ-W2-" + unique(), "成分B", "50", 2, 201);

        JsonNode status = recipeStatus(batchKey);
        assertEquals(2, status.path("weighings").size(), "被替代记录不再出现在有效称量链");
        assertEquals(0, new BigDecimal(status.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("50")));
        assertTrue(status.path("conforms").asBoolean());

        passTestsAndFirstApproval(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 被替代的旧记录仍留痕（superseded_by 指向更正记录）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE id = ? AND superseded_by IS NOT NULL",
                Integer.class, idA));
    }

    @Test
    void correctionConstraints_nonLatestOrSuperseded409_unknown404() throws Exception {
        String recipeKey = "REC-CC-" + unique();
        String batchKey = "BK-CC-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        long id1 = weighingId(weigh(batchKey, "RQ-W1-" + unique(), "成分A", "10", 0, 201));
        long id2 = weighingId(weigh(batchKey, "RQ-W2-" + unique(), "成分A", "20", 1, 201));

        // 只能更正该成分最近一条有效称量
        correct(batchKey, "RQ-X1-" + unique(), id1, "15", 2, 409);
        // 未知称量 ID → 404
        correct(batchKey, "RQ-X2-" + unique(), 999999999L, "15", 2, 404);
        // 更正最近一条成功
        correct(batchKey, "RQ-X3-" + unique(), id2, "30", 2, 201);
        // 已被替代的记录不能再次更正
        correct(batchKey, "RQ-X4-" + unique(), id2, "35", 3, 409);
        JsonNode status = recipeStatus(batchKey);
        assertEquals(0, new BigDecimal(status.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("40")), "有效累计为 10 + 30（替代而非累加）");
    }

    // ---------- 期望版本与幂等 ----------

    @Test
    void expectedVersionMismatch_returns409() throws Exception {
        String recipeKey = "REC-EV-" + unique();
        String batchKey = "BK-EV-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", "50", 5, 409);
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", "50", 0, 201);
        assertEquals(1, recipeStatus(batchKey).path("stateVersion").asLong());
    }

    @Test
    void requestIdReplay_sameParamsReplays_changedParams409() throws Exception {
        String recipeKey = "REC-IDEM-" + unique();
        String batchKey = "BK-IDEM-" + unique();
        String createKey = "RQ-C-" + unique();
        MvcResult first = createRecipe(createKey, recipeKey, 1,
                ingredients("成分A", "50", "成分B", "50"), 201);
        MvcResult replay = createRecipe(createKey, recipeKey, 1,
                ingredients("成分A", "50", "成分B", "50"), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同 requestId 同参重放首次完整响应");
        // 同 requestId 异参 → 409
        createRecipe(createKey, recipeKey, 2, ingredients("成分A", "50", "成分B", "50"), 409);

        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        String weighKey = "RQ-W1-" + unique();
        MvcResult w1 = weigh(batchKey, weighKey, "成分A", "50", 0, 201);
        MvcResult w1Replay = weigh(batchKey, weighKey, "成分A", "50", 0, 201);
        assertEquals(w1.getResponse().getContentAsString(),
                w1Replay.getResponse().getContentAsString());
        weigh(batchKey, weighKey, "成分A", "60", 0, 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
    }

    // ---------- 冻结与绑定校验 ----------

    @Test
    void frozenAfterRelease_weighAndCorrectReturn409() throws Exception {
        String recipeKey = "REC-FRZ-" + unique();
        String batchKey = "BK-FRZ-" + unique();
        prepareWeighedBatch(recipeKey, batchKey, "50", "50");
        passTestsAndFirstApproval(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        weigh(batchKey, "RQ-W3-" + unique(), "成分A", "1", 2, 409);
        long idA = jdbc.queryForObject(
                "SELECT id FROM weighing WHERE batch_key = ? ORDER BY id LIMIT 1",
                Long.class, batchKey);
        correct(batchKey, "RQ-X1-" + unique(), idA, "51", 2, 409);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void bindValidation_unboundWeigh422_unknownRecipe404_duplicateBind409() throws Exception {
        String batchKey = "BK-BIND-" + unique();
        createBatch(batchKey, List.of("t1"));
        // 未绑定配方不能称量 → 422
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", "10", 0, 422);
        // 绑定不存在的配方版本 → 404
        bind(batchKey, "RQ-B1-" + unique(), "NO-SUCH-RECIPE", 1, "100", 404);
        // 目标总质量超精度 → 400
        String recipeKey = "REC-BIND-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        bind(batchKey, "RQ-B2-" + unique(), recipeKey, 1, "100.0000001", 400);
        // 正常绑定后重复绑定 → 409
        bind(batchKey, "RQ-B3-" + unique(), recipeKey, 1, "100", 201);
        bind(batchKey, "RQ-B4-" + unique(), recipeKey, 1, "200", 409);
        // 未知批次 → 404
        bind("NO-SUCH-BATCH", "RQ-B5-" + unique(), recipeKey, 1, "100", 404);
    }

    // ---------- 拆分继承 ----------

    @Test
    void splitInheritsProportionalMasses_childrenReleasable() throws Exception {
        String recipeKey = "REC-SP-" + unique();
        String parent = "BK-SP-P-" + unique();
        prepareWeighedBatch(recipeKey, parent, "50", "50");
        passTestsAndFirstApproval(parent);
        approve(parent, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child1 = "BK-SP-C1-" + unique();
        String child2 = "BK-SP-C2-" + unique();
        split(parent, "CK-SP-" + unique(),
                List.of(child(child1, "L1", "60"), child(child2, "L2", "40")), 201);
        assertEquals("SPLIT", currentStatus(parent));

        // 子批按质量比例精确继承：60% → 30/30，40% → 20/20
        JsonNode status1 = recipeStatus(child1);
        assertEquals(0, new BigDecimal(status1.path("targetMass").asText())
                .compareTo(new BigDecimal("60")));
        assertEquals(0, new BigDecimal(status1.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("30")));
        assertEquals(0, new BigDecimal(status1.path("totals").get(1).path("actualMass").asText())
                .compareTo(new BigDecimal("30")));
        assertTrue(status1.path("conforms").asBoolean());
        assertEquals(2, status1.path("weighings").size());
        assertTrue(status1.path("weighings").get(0).path("inherited").asBoolean());
        JsonNode status2 = recipeStatus(child2);
        assertEquals(0, new BigDecimal(status2.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("20")));

        // 已拆父批不能再更正
        long parentWeighing = jdbc.queryForObject(
                "SELECT id FROM weighing WHERE batch_key = ? ORDER BY id LIMIT 1",
                Long.class, parent);
        correct(parent, "RQ-X-" + unique(), parentWeighing, "51", 2, 409);

        // 子批继承的投料满足配方，可直接走检验与审批放行
        passTestsAndFirstApproval(child1);
        approve(child1, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(child1));
    }

    @Test
    void splitNonConservingOrSumMismatch_returns422() throws Exception {
        String recipeKey = "REC-SPN-" + unique();
        String parent = "BK-SPN-P-" + unique();
        prepareWeighedBatch(recipeKey, parent, "50", "50");
        passTestsAndFirstApproval(parent);
        approve(parent, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);

        // 33.333333 + 66.666667 = 100，但 50×33.333333/100 = 16.6666665 超出 6 位小数 → 422
        split(parent, "CK-SP1-" + unique(),
                List.of(child("BK-SPN-C1-" + unique(), "L1", "33.333333"),
                        child("BK-SPN-C2-" + unique(), "L2", "66.666667")), 422);
        // 子批目标之和 90 ≠ 100 → 422
        split(parent, "CK-SP2-" + unique(),
                List.of(child("BK-SPN-C3-" + unique(), "L3", "50"),
                        child("BK-SPN-C4-" + unique(), "L4", "40")), 422);

        assertEquals("RELEASED", currentStatus(parent), "拆分失败父批状态不变");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key LIKE 'BK-SPN-C%'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, parent));
    }

    @Test
    void splitTargetMassValidation_returns400() throws Exception {
        // 父批已绑定配方：子批缺 targetMass → 400
        String recipeKey = "REC-SPV-" + unique();
        String parent = "BK-SPV-P-" + unique();
        prepareWeighedBatch(recipeKey, parent, "50", "50");
        passTestsAndFirstApproval(parent);
        approve(parent, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        split(parent, "CK-SP1-" + unique(),
                List.of(child("BK-SPV-C1-" + unique(), "L1", null),
                        child("BK-SPV-C2-" + unique(), "L2", "100")), 400);

        // 父批未绑定配方：子批不得指定 targetMass → 400
        String plainParent = "BK-SPV-PL-" + unique();
        createBatch(plainParent, List.of("t1"));
        submitPass(plainParent, "TK-1", "t1", "insp-1");
        approve(plainParent, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(plainParent, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(plainParent));
        split(plainParent, "CK-SP2-" + unique(),
                List.of(child("BK-SPV-C3-" + unique(), "L3", "60"),
                        child("BK-SPV-C4-" + unique(), "L4", "40")), 400);
        // 未绑定配方的父批不带 targetMass 仍可正常拆分
        split(plainParent, "CK-SP3-" + unique(),
                List.of(child("BK-SPV-C5-" + unique(), "L5", null),
                        child("BK-SPV-C6-" + unique(), "L6", null)), 201);
        assertEquals("SPLIT", currentStatus(plainParent));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentWeighSameExpectedVersion_exactlyOneWins() throws Exception {
        String recipeKey = "REC-CW-" + unique();
        String batchKey = "BK-CW-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);

        // 两个称量携带相同期望版本 0 并发：行锁串行后仅一个成功，另一个版本不匹配 409
        List<Future<Integer>> results = runConcurrent(
                () -> weighStatus(batchKey, "RQ-WA-" + unique(), "成分A", "50", 0),
                () -> weighStatus(batchKey, "RQ-WB-" + unique(), "成分B", "50", 0));
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, conflict);
        assertEquals(1, recipeStatus(batchKey).path("stateVersion").asLong());
        // 以最新版本补称另一成分后满足配方
        JsonNode totals = recipeStatus(batchKey);
        String missing = totals.path("totals").get(0).path("weighed").asBoolean() ? "成分B" : "成分A";
        weigh(batchKey, "RQ-W2-" + unique(), missing, "50", 1, 201);
        assertTrue(recipeStatus(batchKey).path("conforms").asBoolean());
    }

    @Test
    void concurrentSameRequestId_replaysSameResult() throws Exception {
        String recipeKey = "REC-CR-" + unique();
        String batchKey = "BK-CR-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);

        String requestId = "RQ-W-" + unique();
        List<Future<String>> results = runConcurrent(
                () -> weighResponseBody(batchKey, requestId, "成分A", "50", 0),
                () -> weighResponseBody(batchKey, requestId, "成分A", "50", 0));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同 requestId 同参并发重放同一首次响应");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH' AND command_key = ?",
                Integer.class, requestId));
    }

    @Test
    void concurrentCorrectionAndFinalApproval_serializeConsistently() throws Exception {
        String recipeKey = "REC-CX-" + unique();
        String batchKey = "BK-CX-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        long idA = weighingId(weigh(batchKey, "RQ-W1-" + unique(), "成分A", "60", 0, 201));
        weigh(batchKey, "RQ-W2-" + unique(), "成分B", "50", 1, 201);
        passTestsAndFirstApproval(batchKey);

        // 更正（60→50）与最终批准并发：父批行锁互斥，按提交顺序裁决
        String approveKey = "CK-AO-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> correctStatus(batchKey, "RQ-X1-" + unique(), idA, "50", 2),
                () -> approveStatus(batchKey, "ops-1", "OPERATIONS", approveKey));
        int correction = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, correction);
        if (approval == 201) {
            // 更正先提交：批准看到更正后的合规投料
            assertEquals("RELEASED", currentStatus(batchKey));
        } else {
            // 批准先获锁：投料不合规 422，更正随后提交；更正后可再次批准放行
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            approve(batchKey, "ops-1", "OPERATIONS", approveKey, 201);
            assertEquals("RELEASED", currentStatus(batchKey));
        }
        JsonNode status = recipeStatus(batchKey);
        assertTrue(status.path("conforms").asBoolean());
        assertEquals(0, new BigDecimal(status.path("totals").get(0).path("actualMass").asText())
                .compareTo(new BigDecimal("50")));
    }

    // ---------- 持久化 ----------

    @Test
    void dataPersistedInRecipeTables() throws Exception {
        String recipeKey = "REC-PER-" + unique();
        String batchKey = "BK-PER-" + unique();
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        long idA = weighingId(weigh(batchKey, "RQ-W1-" + unique(), "成分A", "60", 0, 201));
        correct(batchKey, "RQ-X1-" + unique(), idA, "50", 1, 201);
        weigh(batchKey, "RQ-W2-" + unique(), "成分B", "50", 2, 201);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_version WHERE recipe_key = ?", Integer.class, recipeKey));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_ingredient WHERE recipe_key = ?", Integer.class, recipeKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_recipe WHERE batch_key = ? AND state_version = 3",
                Integer.class, batchKey));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NULL",
                Integer.class, batchKey));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type IN"
                        + " ('CREATE_RECIPE','BIND_RECIPE','WEIGH','CORRECT_WEIGH')", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<Map<String, Object>> ingredients(String... nameAndPercentage) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < nameAndPercentage.length; i += 2) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", nameAndPercentage[i]);
            entry.put("percentage", new BigDecimal(nameAndPercentage[i + 1]));
            list.add(entry);
        }
        return list;
    }

    private MvcResult createRecipe(String requestId, String recipeKey, int version,
                                   List<Map<String, Object>> ingredients, int expected)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("recipeKey", recipeKey);
        body.put("version", version);
        body.put("ingredients", ingredients);
        return mockMvc.perform(post("/api/recipes")
                        .header("X-Actor-Id", "recipe-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", items);
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private MvcResult bind(String batchKey, String requestId, String recipeKey, int version,
                           String targetMass, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("recipeKey", recipeKey);
        body.put("version", version);
        body.put("targetMass", new BigDecimal(targetMass));
        return mockMvc.perform(post("/api/batches/" + batchKey + "/recipe")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String weighBody(String batchKey, String requestId, String ingredient, String mass,
                             long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ingredient", ingredient);
        body.put("mass", new BigDecimal(mass));
        body.put("expectedVersion", expectedVersion);
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult weigh(String batchKey, String requestId, String ingredient, String mass,
                            long expectedVersion, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighBody(batchKey, requestId, ingredient, mass, expectedVersion)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int weighStatus(String batchKey, String requestId, String ingredient, String mass,
                            long expectedVersion) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                            .header("X-Actor-Id", "weigher-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(weighBody(batchKey, requestId, ingredient, mass, expectedVersion)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String weighResponseBody(String batchKey, String requestId, String ingredient,
                                     String mass, long expectedVersion) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                            .header("X-Actor-Id", "weigher-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(weighBody(batchKey, requestId, ingredient, mass, expectedVersion)))
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MvcResult correct(String batchKey, String requestId, long weighingId, String mass,
                              long expectedVersion, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("weighingId", weighingId);
        body.put("mass", new BigDecimal(mass));
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings/corrections")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int correctStatus(String batchKey, String requestId, long weighingId, String mass,
                              long expectedVersion) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", requestId);
            body.put("weighingId", weighingId);
            body.put("mass", new BigDecimal(mass));
            body.put("expectedVersion", expectedVersion);
            return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings/corrections")
                            .header("X-Actor-Id", "weigher-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void submitPass(String batchKey, String testKey, String item, String inspector)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", testKey);
        body.put("testItem", item);
        body.put("result", "PASS");
        body.put("inspector", inspector);
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private int approveStatus(String batchKey, String actor, String role, String commandKey) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                            .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"" + commandKey + "\"}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> child(String batchKey, String batchNo, String targetMass) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("batchKey", batchKey);
        child.put("batchNo", batchNo);
        if (targetMass != null) {
            child.put("targetMass", new BigDecimal(targetMass));
        }
        return child;
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

    /**
     * 建 50%/50% 配方、建批、绑定 100 克目标并按给定质量称量两种成分。
     */
    private void prepareWeighedBatch(String recipeKey, String batchKey, String massA, String massB)
            throws Exception {
        createRecipe("RQ-C-" + unique(), recipeKey, 1, ingredients("成分A", "50", "成分B", "50"), 201);
        createBatch(batchKey, List.of("t1"));
        bind(batchKey, "RQ-B-" + unique(), recipeKey, 1, "100", 201);
        weigh(batchKey, "RQ-W1-" + unique(), "成分A", massA, 0, 201);
        weigh(batchKey, "RQ-W2-" + unique(), "成分B", massB, 1, 201);
    }

    private void passTestsAndFirstApproval(String batchKey) throws Exception {
        submitPass(batchKey, "TK-1", "t1", "insp-1");
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
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

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
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
