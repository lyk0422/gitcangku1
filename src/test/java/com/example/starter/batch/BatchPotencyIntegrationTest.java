package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 效价衰减端到端 H2 测试（MODE=MySQL 真实内存库，无 mock/Map 替代）：
 * 放行门、UTC 衰减固化、模型修订提交顺序裁决、拆分数量守恒与效价继承、
 * 合批数量加权与整次回滚、失败不占键、commandKey 幂等、并发行锁裁决与历史不改写。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchPotencyIntegrationTest {

    private static final Instant ANCHOR = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeSource timeSource;

    @BeforeEach
    void cleanTablesAndClock() {
        jdbc.update("DELETE FROM potency_calculation");
        jdbc.update("DELETE FROM batch_potency_model");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
        fixClock(ANCHOR);
    }

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    // ---------- 主流程：放行效价门 + 固化 ----------

    @Test
    void releaseWithinThreshold_persistsFrozenCalculation_andHistoryAndDiagnostics() throws Exception {
        String batch = "P-OK-" + uid();
        createTrackedBatch(batch, "100", "1", "80", "100", 201);
        passAllAndFirstApproval(batch);

        // 放行时刻 = 锚点后 10 个 UTC 日：100 - 1*10 = 90 ≥ 80
        fixClock(ANCHOR.plusSeconds(10 * 86400L));
        MvcResult result = approve(batch, "ops-1", "OPERATIONS", "CK-REL", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("RELEASED", body.path("batchStatus").asText());
        JsonNode calc = body.path("potencyCalculation");
        assertEquals("APPROVE", calc.path("operationType").asText());
        assertEquals(1, calc.path("modelVersion").asInt());
        assertBd("90", calc.path("resultPotency"));
        assertBd("80", calc.path("thresholdPotency"));
        assertTrue(calc.path("eligible").asBoolean());
        assertBd("90", calc.path("inputs").get(0).path("inputPotency"));
        assertBd("100", calc.path("inputs").get(0).path("weight"));
        assertBd("10", calc.path("inputs").get(0).path("elapsedDays"));

        // 固化记录真实落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE batch_key = ? AND operation_type = 'APPROVE'"
                        + " AND result_potency = 90 AND eligible = 1", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ? AND version = 1",
                Integer.class, batch));

        // 历史明细含固化计算，排序稳定
        JsonNode history = getJson("/api/batches/" + batch + "/history");
        assertEquals(1, history.path("potencyCalculations").size());
        assertBd("90", history.path("potencyCalculations").get(0).path("resultPotency"));
        assertBd("90", history.path("approvals").get(1)
                .path("potencyCalculation").path("resultPotency"));
        assertTrue(history.path("approvals").get(0).path("potencyCalculation").isNull());

        // 诊断只读：当前效价 90、余量 10；重复查询不产生新数据
        JsonNode diag = getJson("/api/batches/" + batch + "/potency");
        assertBd("90", diag.path("current").path("actualPotency"));
        assertBd("10", diag.path("current").path("margin"));
        assertTrue(diag.path("current").path("eligible").asBoolean());
        assertEquals(1, diag.path("versions").size());
        getJson("/api/batches/" + batch + "/potency");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE batch_key = ?", Integer.class, batch));
    }

    // ---------- 失败分支：低于阈值 422，回滚且不占键 ----------

    @Test
    void releaseBelowThreshold_returns422_rollsBack_andKeyCanBeReused() throws Exception {
        String batch = "P-LOW-" + uid();
        createTrackedBatch(batch, "100", "1", "80", "100", 201);
        passAllAndFirstApproval(batch);

        // 锚点后 21 日：100 - 21 = 79 < 80
        fixClock(ANCHOR.plusSeconds(21 * 86400L));
        MvcResult rejected = mockMvc.perform(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-REL\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode err = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", err.path("code").asText());
        assertEquals(1, err.path("modelVersion").asInt());
        assertBd("79", err.path("actualPotency"));
        assertBd("80", err.path("requiredPotency"));
        assertBd("-1", err.path("margin"));

        // 事务回滚：停留 RELEASE_REVIEW，仅 1 条批准，无固化记录，失败不占 commandKey
        assertEquals("RELEASE_REVIEW", currentStatus(batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batch));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE batch_key = ?", Integer.class, batch));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-REL'", Integer.class));

        // 时钟拨回到合格时刻后，同一 commandKey 可成功（失败不占键）
        fixClock(ANCHOR.plusSeconds(5 * 86400L));
        approve(batch, "ops-1", "OPERATIONS", "CK-REL", 201);
        assertEquals("RELEASED", currentStatus(batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE batch_key = ? AND result_potency = 95",
                Integer.class, batch));
    }

    @Test
    void negativePotencyAndRateAndThreshold_return422_partialGroupReturns400() throws Exception {
        // 初始效价为负
        createTrackedBatch("P-NEG-I-" + uid(), "-1", "1", "80", "100", 422);
        // 衰减率为负
        createTrackedBatch("P-NEG-R-" + uid(), "100", "-1", "80", "100", 422);
        // 阈值为负
        createTrackedBatch("P-NEG-T-" + uid(), "100", "1", "-5", "100", 422);
        // 数量为 0 / 负
        createTrackedBatch("P-ZERO-" + uid(), "100", "1", "80", "0", 422);
        createTrackedBatch("P-NEG-Q-" + uid(), "100", "1", "80", "-3", 422);
        // 效价字段只给一部分 → 400
        String partial = "{\"commandKey\":\"CK-C\",\"batchKey\":\"P-PART-" + uid()
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"L1\","
                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                + "\"quantity\":100}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(partial))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));
    }

    // ---------- 拆分：数量守恒 + 操作时刻效价加权（单父） ----------

    @Test
    void split_freezesParentPotencyForChildren_andQuantityMustConserve() throws Exception {
        String parent = "P-SP-" + uid();
        createTrackedBatch(parent, "100", "1", "80", "100", 201);
        passAllAndFirstApproval(parent);
        fixClock(ANCHOR.plusSeconds(10 * 86400L));
        approve(parent, "ops-1", "OPERATIONS", "CK-REL", 201);

        String c1 = "P-SP-C1-" + uid();
        String c2 = "P-SP-C2-" + uid();
        MvcResult split = split(parent, "CK-SPLIT",
                List.of(child(c1, "L1", "60"), child(c2, "L2", "40")), 201);
        JsonNode body = objectMapper.readTree(split.getResponse().getContentAsString());
        assertBd("90", body.path("children").get(0).path("potency")
                .path("initialPotency"));
        assertBd("1", body.path("children").get(0).path("potency")
                .path("dailyDecayRate"));
        assertBd("80", body.path("children").get(1).path("potency")
                .path("minReleasePotency"));
        assertBd("60", body.path("children").get(0).path("quantity"));

        // 子批 v1 锚点=拆分时刻；血缘带数量；每个子批一条 SPLIT 固化记录
        assertEquals("2026-01-11T00:00:00Z", body.path("children").get(0).path("potency")
                .path("anchorAt").asText());
        assertEquals(60, jdbc.queryForObject(
                "SELECT quantity FROM batch_lineage WHERE parent_key = ? AND child_key = ?",
                BigDecimal.class, parent, c1).intValue());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE operation_type = 'SPLIT'"
                        + " AND result_potency = 90", Integer.class));

        // 数量不守恒：422，整次回滚，父批不变，不留子批/血缘/固化（用另一个未拆分的 RELEASED 父批验证）
        String parent2 = "P-SP2-" + uid();
        createTrackedBatch(parent2, "100", "1", "80", "100", 201);
        releaseAt(parent2, 10);
        String b1 = "P-SP-B1-" + uid();
        split(parent2, "CK-SPLIT-BAD",
                List.of(child(b1, "L1", "60"), child("P-SP-B2-" + uid(), "L2", "30")), 422);
        assertEquals("RELEASED", currentStatus(parent2));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, b1));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent2));
    }

    @Test
    void splitMissingChildQuantity_returns400_andKeyNotOccupied() throws Exception {
        String parent = "P-SQ-" + uid();
        createTrackedBatch(parent, "100", "0", "80", "100", 201);
        releaseAt(parent, 0);

        String body = "{\"commandKey\":\"CK-SQ\",\"children\":["
                + "{\"batchKey\":\"P-SQ-C1-" + uid() + "\",\"batchNo\":\"L1\",\"quantity\":60},"
                + "{\"batchKey\":\"P-SQ-C2-" + uid() + "\",\"batchNo\":\"L2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals("RELEASED", currentStatus(parent));
        // 补齐数量后同键成功
        split(parent, "CK-SQ",
                List.of(child("P-SQ-C1-" + uid(), "L1", "60"),
                        child("P-SQ-C2-" + uid(), "L2", "40")), 201);
    }

    // ---------- 合批：多父数量加权、任一不合格整次回滚 ----------

    @Test
    void merge_weightsParentsByQuantity_andOneIneligibleParentRollsBackEverything() throws Exception {
        // A: 率 0.5/日；B: 率 1/日；均在锚点后 10 日放行（95、90 合格）
        String a = "P-MG-A-" + uid();
        String b = "P-MG-B-" + uid();
        createTrackedBatch(a, "100", "0.5", "80", "100", 201);
        createTrackedBatch(b, "100", "1", "80", "100", 201);
        releaseAt(a, 10);
        releaseAt(b, 10);

        // 锚点后 25 日：A=87.5 合格，B=75 不合格 → 合批 422，整次回滚
        fixClock(ANCHOR.plusSeconds(25 * 86400L));
        String child = "P-MG-C-" + uid();
        MvcResult bad = merge(List.of(parent(a, "100"), parent(b, "100")),
                child(child, "LC", "200"), 422);
        JsonNode err = objectMapper.readTree(bad.getResponse().getContentAsString());
        assertEquals(b, err.path("batchKey").asText());
        assertBd("75", err.path("actualPotency"));
        assertBd("80", err.path("requiredPotency"));
        assertBd("-5", err.path("margin"));
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("RELEASED", currentStatus(b));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE operation_type = 'MERGE'", Integer.class));

        // 拨回到锚点后 15 日：A=92.5、B=85 均合格 → 合批成功
        fixClock(ANCHOR.plusSeconds(15 * 86400L));
        MvcResult ok = merge(List.of(parent(a, "100"), parent(b, "100")),
                child(child, "LC", "200"), 201);
        JsonNode merged = objectMapper.readTree(ok.getResponse().getContentAsString());
        // (100*92.5 + 100*85)/200 = 88.75；率 0.75；阈值 80
        assertBd("88.75", merged.path("potency").path("initialPotency"));
        assertBd("0.75", merged.path("potency").path("dailyDecayRate"));
        assertBd("80", merged.path("potency").path("minReleasePotency"));
        assertBd("200", merged.path("quantity"));
        assertEquals("QUARANTINED", merged.path("status").asText());
        assertEquals(0, merged.path("calculation").path("modelVersion").asInt());
        assertEquals(2, merged.path("calculation").path("inputs").size());

        // 父批整体消耗置 SPLIT；子批两个父批；必做检验项取并集；固化落库
        assertEquals("SPLIT", currentStatus(a));
        assertEquals("SPLIT", currentStatus(b));
        JsonNode ancestors = getJson("/api/batches/" + child + "/ancestors");
        assertEquals(2, ancestors.size());
        JsonNode history = getJson("/api/batches/" + child + "/history");
        assertEquals(1, history.path("potencyCalculations").size());
        assertEquals("MERGE", history.path("potencyCalculations").get(0).path("operationType").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));
        // 新批必须重新检验放行
        assertTrue(getJson("/api/batches/" + child + "/history").path("tests").isEmpty());
    }

    @Test
    void mergePreconditions_uninspected_partialInvestment_nonConservation_return422() throws Exception {
        String a = "P-MP-A-" + uid();
        String b = "P-MP-B-" + uid();
        String c = "P-MP-C-" + uid();
        createTrackedBatch(a, "100", "0", "80", "100", 201);
        createTrackedBatch(b, "100", "0", "80", "100", 201);
        createTrackedBatch(c, "100", "0", "80", "100", 201);
        releaseAt(a, 0);
        releaseAt(b, 0);
        // c 未检验，仍处 QUARANTINED
        fixClock(ANCHOR.plusSeconds(2 * 86400L));

        // 父批未检验 → 422，回滚
        merge(List.of(parent(a, "100"), parent(c, "100")),
                child("P-MP-X1-" + uid(), "LX", "200"), 422);
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("QUARANTINED", currentStatus(c));

        // 部分投入（50 ≠ 父批 100）→ 422
        MvcResult partial = merge(List.of(parent(a, "50"), parent(b, "100")),
                child("P-MP-X2-" + uid(), "LX", "150"), 422);
        JsonNode err = objectMapper.readTree(partial.getResponse().getContentAsString());
        assertBd("50", err.path("actualQuantity"));
        assertBd("100", err.path("requiredQuantity"));
        assertBd("-50", err.path("margin"));

        // 权重不守恒：投入 200，新批 199 → 422，差额 -1
        MvcResult mismatch = merge(List.of(parent(a, "100"), parent(b, "100")),
                child("P-MP-X3-" + uid(), "LX", "199"), 422);
        JsonNode mErr = objectMapper.readTree(mismatch.getResponse().getContentAsString());
        assertBd("199", mErr.path("actualQuantity"));
        assertBd("200", mErr.path("requiredQuantity"));
        assertBd("-1", mErr.path("margin"));

        // 父批重复 → 422；全部失败均不留结果
        merge(List.of(parent(a, "100"), parent(a, "100")),
                child("P-MP-X4-" + uid(), "LX", "200"), 422);
        assertEquals("RELEASED", currentStatus(a));
        assertEquals("RELEASED", currentStatus(b));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
    }

    // ---------- 模型修订：追加版本、提交顺序裁决、历史不改写 ----------

    @Test
    void revisionAppendsVersion_releaseReadsLatest_andHistoryNotRewritten() throws Exception {
        String batch = "P-RV-" + uid();
        createTrackedBatch(batch, "100", "1", "80", "100", 201);
        passAllAndFirstApproval(batch);

        // 锚点后 15 日 v1 当前 85，本可放行
        fixClock(ANCHOR.plusSeconds(15 * 86400L));

        // 修订 v2：初始 90、率 1（锚点不变）→ 当前 75，不合格
        revise(batch, "CK-RV2", "90", "1", "80", 201);
        JsonNode diag = getJson("/api/batches/" + batch + "/potency");
        assertEquals(2, diag.path("current").path("modelVersion").asInt());
        assertFalse(diag.path("current").path("eligible").asBoolean());
        approve(batch, "ops-1", "OPERATIONS", "CK-REL", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 同键同参重放返回首次快照、不新增版本；异参 409
        revise(batch, "CK-RV2", "90", "1", "80", 201);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class, batch));
        revise(batch, "CK-RV2", "91", "1", "80", 409);

        // 修订 v3：初始 100、率 0.5 → 当前 92.5，放行成功并固化 v3
        revise(batch, "CK-RV3", "100", "0.5", "80", 201);
        MvcResult released = approve(batch, "ops-1", "OPERATIONS", "CK-REL", 201);
        JsonNode calc = objectMapper.readTree(released.getResponse().getContentAsString())
                .path("potencyCalculation");
        assertEquals(3, calc.path("modelVersion").asInt());
        assertBd("92.5", calc.path("resultPotency"));

        // 放行后再修订 v4，历史固化仍为 v3/92.5，旧证据不回写
        revise(batch, "CK-RV4", "70", "0", "80", 201);
        JsonNode history = getJson("/api/batches/" + batch + "/history");
        assertEquals(3, history.path("potencyCalculations").get(0).path("modelVersion").asInt());
        assertBd("92.5", history.path("potencyCalculations").get(0).path("resultPotency"));
        assertEquals(4, getJson("/api/batches/" + batch + "/potency").path("current")
                .path("modelVersion").asInt());

        // 负参数修订 → 422 且不追加版本
        revise(batch, "CK-RV-BAD", "-1", "1", "80", 422);
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class, batch));
    }

    @Test
    void concurrentRevisionAndFinalApproval_commitOrderDecides_oneConsistentOutcome() throws Exception {
        String batch = "P-CC-" + uid();
        createTrackedBatch(batch, "100", "1", "80", "100", 201);
        passAllAndFirstApproval(batch);
        // v1 在 15 日当前 85 合格；修订 v2 初始 90 率 1 → 当前 75 不合格
        fixClock(ANCHOR.plusSeconds(15 * 86400L));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/potency-revisions")
                        .header("X-Actor-Id", "modeler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CC-RV\",\"initialPotency\":90,"
                                + "\"dailyDecayRate\":1,\"minReleasePotency\":80}")),
                () -> callStatus(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CC-REL\"}"))
        );

        int revision = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, revision, "修订始终可追加（RELEASE_REVIEW 或 RELEASED 均允许）");
        Integer calcRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_calculation WHERE batch_key = ?", Integer.class, batch);
        if (approval == 201) {
            // 放行先提交：读到 v1 85 合格；随后修订追加 v2
            assertEquals("RELEASED", currentStatus(batch));
            assertEquals(1, calcRows);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT model_version FROM potency_calculation WHERE batch_key = ?",
                    Integer.class, batch));
        } else {
            // 修订先提交：放行读到 v2 75 不合格 → 422 回滚
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batch));
            assertEquals(0, calcRows);
        }
    }

    // ---------- 旧路径兼容：未登记效价的批次行为不变 ----------

    @Test
    void batchWithoutPotencyModel_releasesWithoutGate_andHasNullPotency() throws Exception {
        String batch = "P-LEGACY-" + uid();
        String body = "{\"commandKey\":\"CK-C\",\"batchKey\":\"" + batch
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"L1\","
                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        passAllAndFirstApproval(batch);
        fixClock(Instant.parse("2030-01-01T00:00:00Z"));
        MvcResult result = approve(batch, "ops-1", "OPERATIONS", "CK-REL", 201);
        JsonNode approvalBody = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(approvalBody.path("potencyCalculation").isNull());
        JsonNode history = getJson("/api/batches/" + batch + "/history");
        assertTrue(history.path("batch").path("potency").isNull());
        assertEquals(0, history.path("potencyCalculations").size());
        // 未登记模型不能修订
        revise(batch, "CK-RV", "100", "1", "80", 422);
    }

    // ---------- helpers ----------

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void fixClock(Instant instant) {
        timeSource.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    /**
     * 按数值比较 JSON 中的定点数字（不受 Jackson 节点文本标度影响，如 90/9E+1/90.000000）。
     */
    private static void assertBd(String expected, JsonNode node) {
        assertEquals(0, node.decimalValue().compareTo(new BigDecimal(expected)),
                () -> "期望数值 " + expected + " 实际 " + node.decimalValue().toPlainString());
    }

    private void createTrackedBatch(String batchKey, String initial, String rate,
                                    String threshold, String quantity, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + uid() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-" + batchKey
                + "\",\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                + "\"quantity\":" + quantity + ",\"initialPotency\":" + initial
                + ",\"minReleasePotency\":" + threshold + ",\"dailyDecayRate\":" + rate + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void passAllAndFirstApproval(String batchKey) throws Exception {
        String testBody = "{\"commandKey\":\"CK-T-" + uid() + "\",\"testKey\":\"TK-" + uid()
                + "\",\"testItem\":\"t1\",\"result\":\"PASS\",\"inspector\":\"insp-" + uid() + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(testBody))
                .andExpect(status().isCreated());
        approve(batchKey, "qa-1", "QUALITY", "CK-QA-" + uid(), 201);
    }

    /**
     * 在锚点后 days 天的时钟下完成检验与双角色批准。
     */
    private void releaseAt(String batchKey, long days) throws Exception {
        fixClock(ANCHOR.plusSeconds(days * 86400L));
        passAllAndFirstApproval(batchKey);
        approve(batchKey, "ops-" + uid(), "OPERATIONS", "CK-OP-" + uid(), 201);
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult revise(String batchKey, String commandKey, String initial, String rate,
                             String threshold, int expected) throws Exception {
        String body = "{\"commandKey\":\"" + commandKey + "\",\"initialPotency\":" + initial
                + ",\"dailyDecayRate\":" + rate + ",\"minReleasePotency\":" + threshold + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/potency-revisions")
                        .header("X-Actor-Id", "modeler")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record ChildSpec(String batchKey, String batchNo, String quantity) {
    }

    private ChildSpec child(String batchKey, String batchNo, String quantity) {
        return new ChildSpec(batchKey, batchNo, quantity);
    }

    private record ParentSpec(String batchKey, String quantity) {
    }

    private ParentSpec parent(String batchKey, String quantity) {
        return new ParentSpec(batchKey, quantity);
    }

    private MvcResult split(String parentKey, String commandKey, List<ChildSpec> children,
                            int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ChildSpec c = children.get(i);
            sb.append("{\"batchKey\":\"").append(c.batchKey()).append("\",\"batchNo\":\"")
                    .append(c.batchNo()).append("\",\"quantity\":").append(c.quantity()).append('}');
        }
        sb.append("]}");
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult merge(List<ParentSpec> parents, ChildSpec child, int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"CK-MG-" + uid()
                + "\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ParentSpec p = parents.get(i);
            sb.append("{\"batchKey\":\"").append(p.batchKey()).append("\",\"quantity\":")
                    .append(p.quantity()).append('}');
        }
        sb.append("],\"child\":{\"batchKey\":\"").append(child.batchKey())
                .append("\",\"batchNo\":\"").append(child.batchNo())
                .append("\",\"quantity\":").append(child.quantity()).append("}}");
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/history").path("batch").path("status").asText();
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
