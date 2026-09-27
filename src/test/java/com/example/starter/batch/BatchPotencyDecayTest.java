package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
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
 * 批次效价衰减端到端测试（真实 H2，MODE=MySQL）：效价登记校验、放行效价闸与证据固化、
 * 拆分/合批加权与权重守恒、多父回滚、模型修订不改写历史、commandKey 幂等与并发裁决。
 * 时钟为可注入的 {@link MutableClock}，所有时间断言基于定点 UTC 时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BatchPotencyDecayTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClockAndTables() {
        clock.set(T0);
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch_merge_lineage");
        jdbc.update("DELETE FROM potency_evidence");
        jdbc.update("DELETE FROM batch");
        // 模型历史只增不改，但每个用例从内置版本 1 重新出发
        jdbc.update("DELETE FROM potency_model WHERE version > 1");
    }

    // ---------- 效价登记 ----------

    @Test
    void createWithPotency_registersFields_andRejectsInvalidValues() throws Exception {
        String key = "BK-POT-OK-" + unique();
        MvcResult created = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key, "100", "90", "2", "50")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("100").compareTo(body.path("initialPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(body.path("minReleasePotency").decimalValue()));
        assertEquals(0, new BigDecimal("2").compareTo(body.path("decayRatePerDay").decimalValue()));
        assertEquals(0, new BigDecimal("50").compareTo(body.path("quantity").decimalValue()));
        assertEquals("2026-06-01T00:00:00Z", body.path("potencyEpochAt").asText());

        // 未登记效价的批次：效价字段为 null（null 语义稳定）
        String plain = "BK-POT-PLAIN-" + unique();
        MvcResult plainCreated = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(plain, null, null, null, null)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode plainBody = objectMapper.readTree(plainCreated.getResponse().getContentAsString());
        assertTrue(plainBody.path("initialPotency").isNull());
        assertTrue(plainBody.path("quantity").isNull());
        assertTrue(plainBody.path("potencyEpochAt").isNull());

        // 效价、阈值、衰减率为负 → 422；数量非正 → 422
        expectCreateStatus("BK-POT-N1-" + unique(), "-1", "90", "2", "50", 422);
        expectCreateStatus("BK-POT-N2-" + unique(), "100", "-0.5", "2", "50", 422);
        expectCreateStatus("BK-POT-N3-" + unique(), "100", "90", "-2", "50", 422);
        expectCreateStatus("BK-POT-N4-" + unique(), "100", "90", "2", "0", 422);
        // 效价四元组缺一项 → 400
        expectCreateStatus("BK-POT-PART-" + unique(), "100", null, null, null, 400);
    }

    // ---------- 放行效价闸 ----------

    @Test
    void release_aboveThreshold_succeeds_andFreezesEvidence() throws Exception {
        String key = "BK-REL-OK-" + unique();
        createPotent(key, "100", "90", "2", "50");
        submitTest(key, "t1", "PASS", "insp-1", 201);
        approve(key, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);

        clock.plusDays(3); // 2026-06-04：效价 100 - 2×3 = 94 ≥ 90
        approve(key, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(key));

        JsonNode evidence = singleEvidence(key, "RELEASE");
        assertEquals("2026-06-04T00:00:00Z", evidence.path("computedAt").asText());
        assertEquals(1, evidence.path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("94").compareTo(evidence.path("roundedPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(evidence.path("threshold").decimalValue()));
        assertEquals(3, evidence.path("inputs").path("elapsedDays").asInt());
        assertEquals("100", evidence.path("inputs").path("initialPotency").asText());

        // 历史明细包含同一份证据，且读取不改状态
        MvcResult history = mockMvc.perform(get("/api/batches/" + key + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(1, node.path("potencyEvidence").size());
        assertEquals("RELEASED", currentStatus(key));
    }

    @Test
    void release_belowThreshold_returns422_andFailureDoesNotOccupyCommandKey() throws Exception {
        String key = "BK-REL-LOW-" + unique();
        createPotent(key, "100", "95", "2", "50");
        submitTest(key, "t1", "PASS", "insp-1", 201);
        approve(key, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);

        clock.plusDays(3); // 效价 94 < 95
        String commandKey = "CK-AO-LOW-" + unique();
        MvcResult rejected = mockMvc.perform(post("/api/batches/" + key + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("94").compareTo(error.path("actual").decimalValue()));
        assertEquals(0, new BigDecimal("95").compareTo(error.path("required").decimalValue()));
        assertEquals(0, new BigDecimal("-1").compareTo(error.path("difference").decimalValue()));

        // 整笔不生效：停留 RELEASE_REVIEW，无第二笔批准、无证据、失败不占键
        assertEquals("RELEASE_REVIEW", currentStatus(key));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evidence WHERE batch_key = ?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = ?", Integer.class, commandKey));

        // 时钟回拨到第 1 天（效价 98 ≥ 95），同一 commandKey 可成功重试
        clock.set(T0.plus(java.time.Duration.ofDays(1)));
        approve(key, "ops-1", "OPERATIONS", commandKey, 201);
        assertEquals("RELEASED", currentStatus(key));
        JsonNode evidence = singleEvidence(key, "RELEASE");
        assertEquals(0, new BigDecimal("98").compareTo(evidence.path("roundedPotency").decimalValue()));
    }

    // ---------- 拆分效价 ----------

    @Test
    void split_potentParent_weightConservationAndChildPotency() throws Exception {
        String parent = "BK-SP-P-" + unique();
        createPotent(parent, "100", "50", "1", "100");
        releaseBatch(parent, "insp-1");

        clock.plusDays(10); // 父批当前效价 100 - 1×10 = 90
        // 权重不守恒：40 + 50 ≠ 100 → 422，携带实际值/要求值/差额，父批不变
        String c1 = "BK-SP-C1-" + unique();
        String c2 = "BK-SP-C2-" + unique();
        MvcResult mismatch = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-SP-BAD-" + unique(),
                                List.of(child(c1, "L1", "40"), child(c2, "L2", "50")))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(mismatch.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("90").compareTo(error.path("actual").decimalValue()));
        assertEquals(0, new BigDecimal("100").compareTo(error.path("required").decimalValue()));
        assertEquals(0, new BigDecimal("-10").compareTo(error.path("difference").decimalValue()));
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class, c1, c2));

        // 权重守恒：40 + 60 = 100 → 201；子批初始效价为父批拆分时刻效价 90
        String c3 = "BK-SP-C3-" + unique();
        MvcResult split = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-SP-OK-" + unique(),
                                List.of(child(c1, "L1", "40"), child(c3, "L3", "60")))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(split.getResponse().getContentAsString());
        assertEquals(1, body.path("modelVersion").asInt());
        assertEquals("2026-06-11T00:00:00Z", body.path("splitAt").asText());
        for (JsonNode childNode : body.path("children")) {
            assertEquals(0, new BigDecimal("90").compareTo(
                    childNode.path("initialPotency").decimalValue()));
            assertEquals("2026-06-11T00:00:00Z", childNode.path("potencyEpochAt").asText());
            assertEquals(0, new BigDecimal("1").compareTo(
                    childNode.path("decayRatePerDay").decimalValue()));
        }
        // 每个子批各固化一条 SPLIT 证据
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evidence WHERE batch_key = ? AND operation = 'SPLIT'",
                Integer.class, c1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evidence WHERE batch_key = ? AND operation = 'SPLIT'",
                Integer.class, c3));

        // 子批继续衰减：再 5 天效价 85，可放行并固化 RELEASE 证据
        clock.plusDays(5);
        releaseBatch(c1, "insp-2");
        assertEquals("RELEASED", currentStatus(c1));
        JsonNode evidence = singleEvidence(c1, "RELEASE");
        assertEquals(0, new BigDecimal("85").compareTo(evidence.path("roundedPotency").decimalValue()));
    }

    // ---------- 合批 ----------

    @Test
    void merge_happyFlow_weightedAverage_lineageAndRecallPropagation() throws Exception {
        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        createPotent(p1, "100", "50", "0", "10");
        createPotent(p2, "80", "60", "0", "30");
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        clock.plusDays(2);
        String child = "BK-MG-C-" + unique();
        MvcResult merged = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MG-1-" + unique(), child, "LOT-M1", "40",
                                List.of(parent(p1, "10"), parent(p2, "30")))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(merged.getResponse().getContentAsString());
        // (100×10 + 80×30) / 40 = 85；阈值取父批最大值 60；衰减率加权为 0
        assertEquals(0, new BigDecimal("85").compareTo(body.path("initialPotency").decimalValue()));
        assertEquals(0, new BigDecimal("60").compareTo(
                body.path("minReleasePotency").decimalValue()));
        assertEquals(0, new BigDecimal("0").compareTo(body.path("decayRatePerDay").decimalValue()));
        assertEquals(1, body.path("modelVersion").asInt());
        assertEquals(2, body.path("parents").size());
        assertEquals("MERGED", body.path("parents").get(0).path("parentStatus").asText());

        // 父批 MERGED 不再可用；子批 QUARANTINED 可用
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        List<String> available = availableKeys();
        assertFalse(available.contains(p1));
        assertFalse(available.contains(p2));
        assertTrue(available.contains(child));

        // 合批血缘：子批祖先含两个父批；证据输入含两个父批快照
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        assertEquals(p1, ancestorNodes.get(0).path("batchKey").asText());
        assertEquals(p2, ancestorNodes.get(1).path("batchKey").asText());
        JsonNode evidence = singleEvidence(child, "MERGE");
        assertEquals(2, evidence.path("inputs").path("parents").size());
        assertTrue(evidence.path("threshold").isNull(), "MERGE 证据无放行阈值，threshold 为 null");

        // 召回任一父批 → 子批立即不可用且禁止新增检验
        recall(p1, "qa-lead", "上游污染", "CK-MG-R1-" + unique(), 201);
        assertFalse(availableKeys().contains(child));
        submitTest(child, "t1", "PASS", "insp-3", 422);
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + p1 + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descendantNodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertEquals(child, descendantNodes.get(0).path("batchKey").asText());
        assertEquals(p1, descendantNodes.get(0).path("unavailableDueToRecalledAncestor").asText());
    }

    @Test
    void merge_parentBelowThreshold_wholeRollback_andKeyReusable() throws Exception {
        String p1 = "BK-MR-P1-" + unique();
        String p2 = "BK-MR-P2-" + unique();
        createPotent(p1, "100", "50", "0", "10");
        createPotent(p2, "90", "60", "10", "20");
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        clock.plusDays(5); // P2 效价 90 - 10×5 = 40 < 60
        String child = "BK-MR-C-" + unique();
        String commandKey = "CK-MR-1-" + unique();
        MvcResult failed = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, child, "LOT-M2", "30",
                                List.of(parent(p1, "10"), parent(p2, "20")))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(failed.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("40").compareTo(error.path("actual").decimalValue()));
        assertEquals(0, new BigDecimal("60").compareTo(error.path("required").decimalValue()));
        assertEquals(0, new BigDecimal("-20").compareTo(error.path("difference").decimalValue()));

        // 整次回滚：无子批、无血缘、无证据、父批仍 RELEASED、失败不占键
        mockMvc.perform(get("/api/batches/" + child + "/history")).andExpect(status().isNotFound());
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_lineage WHERE child_key = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evidence WHERE batch_key = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = ?", Integer.class, commandKey));

        // 时钟回拨到第 0 天（P2 效价 90 ≥ 60），同一 commandKey 修正后可成功
        clock.set(T0);
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, child, "LOT-M2", "30",
                                List.of(parent(p1, "10"), parent(p2, "20")))))
                .andExpect(status().isCreated());
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("QUARANTINED", currentStatus(child));
    }

    @Test
    void merge_validationFailures_distinguishCauses() throws Exception {
        // 父批未检验（QUARANTINED）→ 422
        String untested = "BK-MV-UNT-" + unique();
        createPotent(untested, "100", "50", "0", "10");
        String okParent = "BK-MV-OK-" + unique();
        createPotent(okParent, "100", "50", "0", "10");
        releaseBatch(okParent, "insp-1");
        mergeExpect("BK-MV-C1-" + unique(), "20",
                List.of(parent(untested, "10"), parent(okParent, "10")), 422);

        // 父批未登记效价 → 422
        String plain = "BK-MV-PLAIN-" + unique();
        createPlain(plain);
        releaseBatch(plain, "insp-2");
        mergeExpect("BK-MV-C2-" + unique(), "20",
                List.of(parent(plain, "10"), parent(okParent, "10")), 422);

        // 父批权重不等于登记数量 → 422
        mergeExpect("BK-MV-C3-" + unique(), "20",
                List.of(parent(okParent, "5"), parent(untested, "10")), 422);

        // 权重之和与子批数量不守恒 → 422 且携带差额
        String p1 = "BK-MV-P1-" + unique();
        String p2 = "BK-MV-P2-" + unique();
        createPotent(p1, "100", "50", "0", "10");
        createPotent(p2, "100", "50", "0", "30");
        releaseBatch(p1, "insp-3");
        releaseBatch(p2, "insp-4");
        MvcResult mismatch = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MV-SUM-" + unique(), "BK-MV-C4-" + unique(),
                                "LOT", "35", List.of(parent(p1, "10"), parent(p2, "30")))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(mismatch.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("40").compareTo(error.path("actual").decimalValue()));
        assertEquals(0, new BigDecimal("35").compareTo(error.path("required").decimalValue()));
        assertEquals(0, new BigDecimal("5").compareTo(error.path("difference").decimalValue()));

        // 请求内父批重复 → 400
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MV-DUP-" + unique(), "BK-MV-C5-" + unique(),
                                "LOT", "20", List.of(parent(p1, "10"), parent(p1, "10")))))
                .andExpect(status().isBadRequest());

        // 父批不存在 → 404
        mergeExpect("BK-MV-C6-" + unique(), "20",
                List.of(parent(p1, "10"), parent("NO-SUCH-PARENT", "10")), 404);

        // 产品编码不一致 → 422
        String otherProduct = "BK-MV-OP-" + unique();
        createPotent(otherProduct, "PROD-OTHER", "100", "50", "0", "10");
        releaseBatch(otherProduct, "insp-5");
        mergeExpect("BK-MV-C7-" + unique(), "20",
                List.of(parent(p1, "10"), parent(otherProduct, "10")), 422);

        // 全部失败均不产生子批与父批状态变化
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_lineage",
                Integer.class));
    }

    @Test
    void merge_commandKeyReplayAndChangedParams() throws Exception {
        String p1 = "BK-MI-P1-" + unique();
        String p2 = "BK-MI-P2-" + unique();
        createPotent(p1, "100", "50", "0", "10");
        createPotent(p2, "90", "50", "0", "10");
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI-1-" + unique(), child, "LOT-MI", "20",
                List.of(parent(p1, "10"), parent(p2, "10")));
        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复建批：子批与血缘各一份
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_lineage WHERE child_key = ?", Integer.class, child));

        // 同 commandKey 改参（子批数量不同）→ 409
        String changed = mergeBody(firstCommandKeyOf(body), "BK-MI-C2-" + unique(), "LOT-MI", "25",
                List.of(parent(p1, "10"), parent(p2, "10")));
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 效价模型 ----------

    @Test
    void modelRevision_doesNotRewriteHistory_andReleaseReadsConsistentModel() throws Exception {
        // 版本 1（scale 4）下放行批次 A：97.65 原样保留
        String batchA = "BK-PM-A-" + unique();
        createPotent(batchA, "97.65", "90", "0", "10");
        releaseBatch(batchA, "insp-1");
        JsonNode evidenceA = singleEvidence(batchA, "RELEASE");
        assertEquals(1, evidenceA.path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("97.65").compareTo(
                evidenceA.path("roundedPotency").decimalValue()));

        // 注册版本 2（scale 0）
        mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PM-1-" + unique()
                                + "\",\"scale\":0,\"roundingMode\":\"HALF_UP\"}"))
                .andExpect(status().isCreated());

        // 版本 2 下放行批次 B：97.65 舍入为 98
        String batchB = "BK-PM-B-" + unique();
        createPotent(batchB, "97.65", "90", "0", "10");
        releaseBatch(batchB, "insp-2");
        JsonNode evidenceB = singleEvidence(batchB, "RELEASE");
        assertEquals(2, evidenceB.path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("98").compareTo(
                evidenceB.path("roundedPotency").decimalValue()));

        // 历史证据不被模型修订改写
        JsonNode evidenceAAfter = singleEvidence(batchA, "RELEASE");
        assertEquals(1, evidenceAAfter.path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("97.65").compareTo(
                evidenceAAfter.path("roundedPotency").decimalValue()));

        // 模型版本历史可查、按版本升序
        MvcResult models = mockMvc.perform(get("/api/potency-models"))
                .andExpect(status().isOk()).andReturn();
        JsonNode modelNodes = objectMapper.readTree(models.getResponse().getContentAsString());
        assertEquals(2, modelNodes.size());
        assertEquals(1, modelNodes.get(0).path("version").asInt());
        assertEquals(4, modelNodes.get(0).path("scale").asInt());
        assertEquals(2, modelNodes.get(1).path("version").asInt());
        assertEquals(0, modelNodes.get(1).path("scale").asInt());
    }

    @Test
    void modelRegistration_validationAndIdempotency() throws Exception {
        // 非法舍入模式 → 400；scale 越界 → 400
        mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PMV-1\",\"scale\":2,\"roundingMode\":\"CEILING\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PMV-2\",\"scale\":11,\"roundingMode\":\"HALF_UP\"}"))
                .andExpect(status().isBadRequest());

        String commandKey = "CK-PMV-3-" + unique();
        String body = "{\"commandKey\":\"" + commandKey
                + "\",\"scale\":2,\"roundingMode\":\"DOWN\"}";
        MvcResult first = mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(2, objectMapper.readTree(first.getResponse().getContentAsString())
                .path("version").asInt());
        // 同键同参重放返回首次快照
        MvcResult replay = mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM potency_model", Integer.class));
        // 同键改参 → 409
        mockMvc.perform(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey
                                + "\",\"scale\":3,\"roundingMode\":\"DOWN\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrent_modelUpdateAndRelease_readConsistentModelByCommitOrder() throws Exception {
        String key = "BK-CM-R-" + unique();
        createPotent(key, "97.65", "90", "0", "10");
        submitTest(key, "t1", "PASS", "insp-1", 201);
        approve(key, "qa-1", "QUALITY", "CK-CM-AQ-" + unique(), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + key + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CM-AO-" + unique() + "\"}")),
                () -> callStatus(post("/api/potency-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CM-M-" + unique()
                                + "\",\"scale\":0,\"roundingMode\":\"HALF_UP\"}"))
        );
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(201, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals("RELEASED", currentStatus(key));

        // 放行读取一致模型：证据版本与舍入结果必须自洽
        JsonNode evidence = singleEvidence(key, "RELEASE");
        int version = evidence.path("modelVersion").asInt();
        if (version == 1) {
            assertEquals(0, new BigDecimal("97.65").compareTo(
                    evidence.path("roundedPotency").decimalValue()));
        } else {
            assertEquals(2, version);
            assertEquals(0, new BigDecimal("98").compareTo(
                    evidence.path("roundedPotency").decimalValue()));
        }
    }

    @Test
    void concurrent_mergeSameCommandKey_sameWinnerForBoth() throws Exception {
        String p1 = "BK-CMG-P1-" + unique();
        String p2 = "BK-CMG-P2-" + unique();
        createPotent(p1, "100", "50", "0", "10");
        createPotent(p2, "90", "50", "0", "10");
        releaseBatch(p1, "insp-1");
        releaseBatch(p2, "insp-2");

        String child = "BK-CMG-C-" + unique();
        String body = mergeBody("CK-CMG-1-" + unique(), child, "LOT-C", "20",
                List.of(parent(p1, "10"), parent(p2, "10")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge_lineage WHERE child_key = ?", Integer.class,
                child));
        assertEquals("MERGED", currentStatus(p1));
    }

    // ---------- 证据查询 ----------

    @Test
    void potencyEvidenceQueries_readOnly_stableOrderAndNullSemantics() throws Exception {
        String parent = "BK-EQ-P-" + unique();
        createPotent(parent, "100", "50", "1", "100");
        releaseBatch(parent, "insp-1");
        clock.plusDays(10);
        String c1 = "BK-EQ-C1-" + unique();
        String c2 = "BK-EQ-C2-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody("CK-EQ-S-" + unique(),
                                List.of(child(c1, "L1", "40"), child(c2, "L2", "60")))))
                .andExpect(status().isCreated());

        // 查询不改状态：两次读取结果一致
        MvcResult first = mockMvc.perform(get("/api/batches/" + c1 + "/potency-evidence"))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(get("/api/batches/" + c1 + "/potency-evidence"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString());
        JsonNode evidence = objectMapper.readTree(first.getResponse().getContentAsString());
        assertEquals(1, evidence.size());
        assertEquals("SPLIT", evidence.get(0).path("operation").asText());
        assertTrue(evidence.get(0).path("threshold").isNull(), "SPLIT 证据 threshold 为 null");
        assertEquals(parent, evidence.get(0).path("inputs").path("parents").get(0)
                .path("batchKey").asText());

        // 未登记效价的批次：证据为空数组而非 null
        String plain = "BK-EQ-PLAIN-" + unique();
        createPlain(plain);
        MvcResult empty = mockMvc.perform(get("/api/batches/" + plain + "/potency-evidence"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(empty.getResponse().getContentAsString()).size());
        MvcResult history = mockMvc.perform(get("/api/batches/" + plain + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(history.getResponse().getContentAsString())
                .path("potencyEvidence").size());

        // 未知批次 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/potency-evidence"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String[] child(String batchKey, String batchNo, String quantity) {
        return new String[]{batchKey, batchNo, quantity};
    }

    private String[] parent(String batchKey, String weight) {
        return new String[]{batchKey, weight};
    }

    private String createBody(String batchKey, String initialPotency, String minReleasePotency,
                              String decayRate, String quantity) throws Exception {
        return createBody(batchKey, "PROD-1", initialPotency, minReleasePotency, decayRate, quantity);
    }

    private String createBody(String batchKey, String productCode, String initialPotency,
                              String minReleasePotency, String decayRate, String quantity)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", productCode);
        body.put("batchNo", "LOT-1");
        body.put("producedAt", T0.toString());
        body.put("requiredTests", List.of("t1"));
        body.put("initialPotency", initialPotency == null ? null : new BigDecimal(initialPotency));
        body.put("minReleasePotency",
                minReleasePotency == null ? null : new BigDecimal(minReleasePotency));
        body.put("decayRatePerDay", decayRate == null ? null : new BigDecimal(decayRate));
        body.put("quantity", quantity == null ? null : new BigDecimal(quantity));
        return objectMapper.writeValueAsString(body);
    }

    private void createPotent(String batchKey, String initialPotency, String minReleasePotency,
                              String decayRate, String quantity) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(batchKey, initialPotency, minReleasePotency, decayRate,
                                quantity)))
                .andExpect(status().isCreated());
    }

    private void createPotent(String batchKey, String productCode, String initialPotency,
                              String minReleasePotency, String decayRate, String quantity)
            throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(batchKey, productCode, initialPotency,
                                minReleasePotency, decayRate, quantity)))
                .andExpect(status().isCreated());
    }

    private void createPlain(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(batchKey, null, null, null, null)))
                .andExpect(status().isCreated());
    }

    private void expectCreateStatus(String batchKey, String initialPotency,
                                    String minReleasePotency, String decayRate, String quantity,
                                    int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(batchKey, initialPotency, minReleasePotency, decayRate,
                                quantity)))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED（效价闸在当前时钟下必须通过）。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        submitTest(batchKey, "t1", "PASS", inspector, 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", "TK-" + unique());
        body.put("testItem", item);
        body.put("result", result);
        body.put("inspector", inspector);
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
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

    private String splitBody(String commandKey, List<String[]> children) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1])
                    .append("\",\"quantity\":").append(children.get(i)[2]).append('}');
        }
        return sb.append("]}").toString();
    }

    private String mergeBody(String commandKey, String batchKey, String batchNo, String quantity,
                             List<String[]> parents) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("batchKey", batchKey);
        body.put("batchNo", batchNo);
        body.put("quantity", new BigDecimal(quantity));
        List<Map<String, Object>> parentList = new ArrayList<>();
        for (String[] parent : parents) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("batchKey", parent[0]);
            p.put("weight", new BigDecimal(parent[1]));
            parentList.add(p);
        }
        body.put("parents", parentList);
        return objectMapper.writeValueAsString(body);
    }

    private void mergeExpect(String childKey, String quantity, List<String[]> parents, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-M-" + unique(), childKey, "LOT-M", quantity,
                                parents)))
                .andExpect(status().is(expected));
    }

    private String firstCommandKeyOf(String body) throws Exception {
        return objectMapper.readTree(body).path("commandKey").asText();
    }

    private JsonNode singleEvidence(String batchKey, String operation) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/potency-evidence"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode found = null;
        for (JsonNode node : array) {
            if (operation.equals(node.path("operation").asText())) {
                assertNull(found, "同一操作应只有一条证据: " + operation);
                found = node;
            }
        }
        assertNotNull(found, "应存在 " + operation + " 证据");
        return found;
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
