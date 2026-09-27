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
 * 批次效价衰减与放行时点核验的真实 H2（MODE=MySQL）集成测试：
 * 模型版本化、放行时刻核验 422、拆分效价继承、多父合批数量加权与事务回滚、
 * 证据固化与历史稳定排序、commandKey 幂等、数量/权重边界与并发提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PotencyFlowTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UtcClock clock;

    @BeforeEach
    void setUp() {
        clock.pin(T0);
        jdbc.update("DELETE FROM potency_evidence");
        jdbc.update("DELETE FROM potency_model");
        jdbc.update("DELETE FROM batch_merge_parent");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---------- 模型登记与放行主流程 ----------

    @Test
    void modelSet_thenReleaseAtDecayedMoment_freezesActualRequiredAndDifference() throws Exception {
        String b = "BK-PR-" + unique();
        create(b, null);
        setModel(b, "mgr-1", "CK-M1", "100", "90", "0.5", 201);

        JsonNode potencyAtT0 = potency(b);
        assertEquals("PASS", potencyAtT0.path("decision").asText());
        assertNum("100.000000", potencyAtT0.path("currentPotency"));
        assertNum("90.000000", potencyAtT0.path("thresholdPotency"));
        assertNum("10.000000", potencyAtT0.path("difference"));
        assertEquals(1, potencyAtT0.path("model").path("version").asInt());

        // 10 个 UTC 日后：100 - 0.5*10 = 95，仍高于阈值 90
        pin("2026-03-11T00:00:00Z");
        JsonNode potencyLater = potency(b);
        assertNum("95.000000", potencyLater.path("currentPotency"));
        assertNum("10.000000", potencyLater.path("elapsedDays"));
        assertNum("5.000000", potencyLater.path("difference"));

        release(b, "CK-A1", "CK-A2", 201);
        assertEquals("RELEASED", statusOf(b));

        // 第二笔放行批准响应携带实际值/要求值/差额、固化时刻与模型版本
        JsonNode approvals = history(b).path("approvals");
        JsonNode second = approvals.get(1);
        assertEquals(1, second.path("potencyModelVersion").asInt());
        assertEquals("2026-03-11T00:00:00Z", second.path("potencyEvaluatedAt").asText());
        assertNum("95.000000", second.path("currentPotency"));
        assertNum("90.000000", second.path("thresholdPotency"));
        assertNum("5.000000", second.path("difference"));
        // 第一笔批准没有效价核验数值（null 语义稳定）
        assertTrue(history(b).path("approvals").get(0).path("currentPotency").isNull());

        // 证据固化并按 id 稳定排序：MODEL_SET 然后 RELEASE PASS
        List<JsonNode> evidence = evidenceOf(b);
        assertEquals("MODEL_SET", evidence.get(0).path("eventType").asText());
        assertEquals("RELEASE", evidence.get(1).path("eventType").asText());
        JsonNode releaseEv = evidence.get(1);
        assertEquals("PASS", releaseEv.path("decision").asText());
        assertEquals(1, releaseEv.path("modelVersion").asInt());
        assertNum("95.000000", releaseEv.path("currentPotency"));
        assertNum("90.000000", releaseEv.path("thresholdPotency"));
        assertNum("5.000000", releaseEv.path("difference"));
        assertEquals("2026-03-11T00:00:00Z", releaseEv.path("occurredAt").asText());
    }

    // ---------- 放行失败分支与不占键 ----------

    @Test
    void releaseBelowThreshold_422_freezesFailEvidence_noApprovalNoKey_thenSameKeySucceedsAfterRevision()
            throws Exception {
        String b = "BK-422-" + unique();
        create(b, null);
        setModel(b, "mgr-1", "CK-M1", "100", "90", "1", 201);
        pin("2026-03-12T00:00:00Z"); // 11 天后效价 89 < 90
        submitPass(b);
        approve(b, "qa-1", "QUALITY", "CK-A1", 201);

        MvcResult blocked = mockMvc.perform(post("/api/batches/" + b + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A2\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode err = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("POTENCY_BELOW_THRESHOLD", err.path("code").asText());
        assertTrue(err.path("message").asText().contains("89.000000"));
        assertTrue(err.path("message").asText().contains("90.000000"));
        assertTrue(err.path("message").asText().contains("-1.000000"));

        // 业务回滚：无第二笔批准、状态停留 RELEASE_REVIEW、失败不占 commandKey
        assertEquals("RELEASE_REVIEW", statusOf(b));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE batch_key = ?",
                Integer.class, b));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-A2'", Integer.class));

        // FAIL 证据仍被固化，含实际值/要求值/差额与命令键
        JsonNode failEv = lastEvidence(b);
        assertEquals("RELEASE", failEv.path("eventType").asText());
        assertEquals("FAIL", failEv.path("decision").asText());
        assertNum("89.000000", failEv.path("currentPotency"));
        assertNum("90.000000", failEv.path("thresholdPotency"));
        assertNum("-1.000000", failEv.path("difference"));
        assertEquals("CK-A2", failEv.path("commandKey").asText());

        // 同键再次尝试仍失败（失败不占键），留下第二条 FAIL 证据
        approve(b, "ops-1", "OPERATIONS", "CK-A2", 422);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evidence WHERE batch_key = ? AND event_type = 'RELEASE'"
                        + " AND decision = 'FAIL'", Integer.class, b));

        // 模型修订为 v2（零衰减）使此刻效价合格；同一 commandKey 修正后成功并占键
        setModel(b, "mgr-2", "CK-M2", "100", "90", "0", 201);
        approve(b, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", statusOf(b));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-A2'", Integer.class));
        // 成功重放返回首次完整快照，不改变状态
        approve(b, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", statusOf(b));
        // 历史 FAIL 证据不被成功放行改写
        List<JsonNode> evidence = evidenceOf(b);
        assertTrue(evidence.stream().anyMatch(e -> "FAIL".equals(e.path("decision").asText())));
        JsonNode passRelease = evidence.stream()
                .filter(e -> "RELEASE".equals(e.path("eventType").asText())
                        && "PASS".equals(e.path("decision").asText()))
                .findFirst().orElseThrow();
        assertEquals(2, passRelease.path("modelVersion").asInt(), "成功放行读取并固化修订后的 v2");
    }

    // ---------- 模型参数与 404 ----------

    @Test
    void negativeModelParams_return422_nullsReturn400_unknownBatch404() throws Exception {
        String b = "BK-NEG-" + unique();
        create(b, null);
        setModel(b, "u", "CK-1", "-0.01", "90", "1", 422);
        setModel(b, "u", "CK-2", "100", "-1", "1", 422);
        setModel(b, "u", "CK-3", "100", "90", "-0.5", 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM potency_model", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM potency_evidence", Integer.class));

        String missingField = "{\"commandKey\":\"CK-4\",\"initialPotency\":100,\"minPotency\":90}";
        mockMvc.perform(post("/api/batches/" + b + "/potency-model").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(missingField))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/batches/NO-SUCH/potency-model").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelBody("CK-5", "100", "90", "0")))
                .andExpect(status().isNotFound());
    }

    // ---------- 模型修订版本化与幂等 ----------

    @Test
    void modelRevision_versionedAndImmutable_replaySameConflictDifferent_actorPartOfFingerprint()
            throws Exception {
        String b = "BK-REV-" + unique();
        create(b, null);
        String v1 = setModelReturning(b, "mgr-1", "CK-M1", "100", "90", "1");
        String replay = setModelReturning(b, "mgr-1", "CK-M1", "100", "90", "1");
        assertEquals(v1, replay, "同键同参重放返回首次响应快照");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM potency_model", Integer.class));

        // 同键改参 → 409；同键换操作者 → 409（操作者是指纹一部分）
        setModel(b, "mgr-1", "CK-M1", "100", "80", "1", 409);
        setModel(b, "mgr-2", "CK-M1", "100", "90", "1", 409);

        setModel(b, "mgr-2", "CK-M2", "100", "80", "0.5", 201);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM potency_model WHERE batch_key = ?",
                Integer.class, b));
        // v1 历史版本不被改写
        Map2 v1Row = queryModel(b, 1);
        assertEquals("100.000000", v1Row.a()); // initial_potency
        assertEquals("1.000000", v1Row.b());   // decay_rate
        assertEquals("90.000000", jdbc.queryForObject(
                "SELECT min_potency FROM potency_model WHERE batch_key = ? AND version = 1",
                BigDecimal.class, b).toPlainString());
        JsonNode view = potency(b);
        assertEquals(2, view.path("model").path("version").asInt());
        assertNum("80.000000", view.path("model").path("minPotency"));
        // 证据按固化顺序保留两个 MODEL_SET，数值各自稳定
        List<JsonNode> evidence = evidenceOf(b);
        assertEquals(1, evidence.get(0).path("modelVersion").asInt());
        assertEquals(2, evidence.get(1).path("modelVersion").asInt());
        assertNum("90.000000", evidence.get(0).path("minPotency"));
        assertNum("80.000000", evidence.get(1).path("minPotency"));
    }

    // ---------- 拆分效价继承 ----------

    @Test
    void split_freezesParentMomentPotencyAsChildInitial_andChildDecaysFromSplit() throws Exception {
        String p = "BK-SP-" + unique();
        create(p, null);
        setModel(p, "u", "CK-M", "100", "80", "0.5", 201);
        pin("2026-03-11T00:00:00Z"); // 放行时刻效价 95
        release(p, "CK-A1", "CK-A2", 201);

        pin("2026-03-31T00:00:00Z"); // 拆分时刻效价 85
        String c1 = "BK-SP-C1-" + unique();
        String c2 = "BK-SP-C2-" + unique();
        MvcResult result = split(p, "u", "CK-S", List.of(new String[]{c1, "L1"},
                new String[]{c2, "L2"}), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode inheritance = body.path("children").get(0).path("potency");
        assertEquals(1, inheritance.path("parentModelVersion").asInt());
        assertEquals(1, inheritance.path("childModelVersion").asInt());
        assertNum("30.000000", inheritance.path("elapsedDays"));
        assertNum("15.000000", inheritance.path("decayedPotency"));
        assertNum("85.000000", inheritance.path("parentPotency"));
        assertNum("85.000000", inheritance.path("initialPotency"));
        assertNum("80.000000", inheritance.path("minPotency"));
        assertNum("0.500000", inheritance.path("decayRate"));
        assertNum("5.000000", inheritance.path("difference"));
        assertEquals("PASS", inheritance.path("decision").asText());
        // 线性 JSON 保留 6 位刻度（时间精度稳定）
        String raw = result.getResponse().getContentAsString();
        assertTrue(raw.contains("\"elapsedDays\":30.000000"), raw);
        assertTrue(raw.contains("\"parentPotency\":85.000000"), raw);

        // 子批模型以拆分时刻为基准，后续继续衰减
        Map2 childModel = queryModel(c1, 1);
        assertEquals("85.000000", childModel.a());
        assertEquals("0.500000", childModel.b());
        JsonNode childView = potency(c1);
        assertNum("85.000000", childView.path("currentPotency"));
        pin("2026-04-10T00:00:00Z"); // 再 10 天：85 - 5 = 80 恰好阈值，边界合格
        assertNum("80.000000", potency(c1).path("currentPotency"));
        assertEquals("PASS", potency(c1).path("decision").asText());

        // 子批 SPLIT 证据固化在子批上，输入父批经结构化 JSON 记录
        JsonNode splitEv = lastEvidence(c1);
        assertEquals("SPLIT", splitEv.path("eventType").asText());
        assertNum("85.000000", splitEv.path("currentPotency"));
        assertTrue(splitEv.path("inputsJson").asText().contains(p));
    }

    @Test
    void splitWithoutModel_recordsNullInheritanceAndNotConfiguredEvidence() throws Exception {
        String p = "BK-SPNULL-" + unique();
        create(p, null);
        release(p, "CK-A1", "CK-A2", 201); // 历史批次无模型也可走既有放行
        JsonNode releaseApproval = history(p).path("approvals").get(1);
        assertTrue(releaseApproval.path("currentPotency").isNull());
        assertEquals("NOT_CONFIGURED", potency(p).path("decision").asText());

        String c1 = "BK-SPNULL-C1-" + unique();
        MvcResult result = split(p, "u", "CK-S", List.of(new String[]{c1, "L1"},
                new String[]{"BK-SPNULL-C2-" + unique(), "L2"}), 201);
        JsonNode child = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("children").get(0);
        assertTrue(child.path("potency").isNull());
        assertEquals("NOT_CONFIGURED", lastEvidence(c1).path("decision").asText());
        assertEquals("NOT_CONFIGURED", potency(c1).path("decision").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM potency_model WHERE batch_key = ?",
                Integer.class, c1));
    }

    // ---------- 合批主流程 ----------

    @Test
    void merge_weightsMomentPotencyByQuantity_normalizesSetOrder_andFreezesContributions()
            throws Exception {
        String a = "BK-MG-A-" + unique();
        String b = "BK-MG-B-" + unique();
        create(a, new BigDecimal("60"));
        create(b, new BigDecimal("40"));
        setModel(a, "u", "CK-MA", "100", "80", "0", 201);
        setModel(b, "u", "CK-MB", "90", "80", "0", 201);
        release(a, "CK-AA1", "CK-AA2", 201);
        release(b, "CK-AB1", "CK-AB2", 201);

        String child = "BK-MG-C-" + unique();
        // 请求中故意以 B、A 乱序给出；规范化后按 batchKey 升序加权
        String body = mergeBody("CK-MG", child, "LOT-C",
                List.of(new String[]{b, "40"}, new String[]{a, "60"}));
        MvcResult result = mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, json.path("childBatchKey").asText());
        assertNum("100.000000", json.path("totalQuantity"));
        assertNum("96.000000", json.path("childInitialPotency"),
                "100*0.6 + 90*0.4 = 96");
        assertNum("80.000000", json.path("childMinPotency"));
        assertNum("0.000000", json.path("childDecayRate"));
        JsonNode p1 = json.path("parents").get(0);
        assertEquals(a, p1.path("batchKey").asText());
        assertNum("0.6000000000", p1.path("weight"));
        assertNum("100.000000", p1.path("currentPotency"));
        assertNum("20.000000", p1.path("difference"));
        assertNum("0.4000000000", json.path("parents").get(1).path("weight"));
        // 线性 JSON 中效价 6 位、权重 10 位刻度稳定
        String raw = result.getResponse().getContentAsString();
        assertTrue(raw.contains("\"totalQuantity\":100.000000"), raw);
        assertTrue(raw.contains("\"weight\":0.6000000000"), raw);

        // 终态：父批 MERGED 不可用，子批 QUARANTINED；模型与贡献落库
        assertEquals("MERGED", statusOf(a));
        assertEquals("MERGED", statusOf(b));
        assertEquals("QUARANTINED", statusOf(child));
        assertEquals(0, new BigDecimal("100.000000").compareTo(jdbc.queryForObject(
                "SELECT quantity FROM batch WHERE batch_key = ?", BigDecimal.class, child)));
        Map2 childModel = queryModel(child, 1);
        assertEquals("96.000000", childModel.a()); // 初始效价
        assertEquals("0.000000", childModel.b()); // 加权衰减率
        assertEquals("80.000000", jdbc.queryForObject(
                "SELECT min_potency FROM potency_model WHERE batch_key = ? AND version = 1",
                BigDecimal.class, child).toPlainString());

        // 贡献明细查询稳定排序；祖先查询包含两个父批
        MvcResult contributions = mockMvc.perform(get("/api/batches/" + child + "/contributions"))
                .andExpect(status().isOk()).andReturn();
        JsonNode contrib = objectMapper.readTree(contributions.getResponse().getContentAsString());
        assertEquals(2, contrib.size());
        assertEquals(a, contrib.get(0).path("parentBatchKey").asText());
        assertNum("100.000000", contrib.get(0).path("potencyAtMerge"));
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorKeys = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorKeys.size());

        // 子批 MERGE 证据固化加权结果与全部父批输入
        JsonNode mergeEv = lastEvidence(child);
        assertEquals("MERGE", mergeEv.path("eventType").asText());
        assertEquals("PASS", mergeEv.path("decision").asText());
        assertNum("96.000000", mergeEv.path("currentPotency"));
        assertTrue(mergeEv.path("inputsJson").asText().contains(a));
        assertTrue(mergeEv.path("inputsJson").asText().contains(b));
    }

    @Test
    void merge_usesParentPotencyAtOperationMoment_afterDecay() throws Exception {
        String a = "BK-MD-A-" + unique();
        String b = "BK-MD-B-" + unique();
        create(a, new BigDecimal("60"));
        create(b, new BigDecimal("40"));
        setModel(a, "u", "CK-MA", "100", "80", "1", 201);   // 10 日后 90
        setModel(b, "u", "CK-MB", "90", "80", "0.5", 201);  // 10 日后 85
        release(a, "CK-AA1", "CK-AA2", 201);
        release(b, "CK-AB1", "CK-AB2", 201);

        pin("2026-03-11T00:00:00Z");
        String child = "BK-MD-C-" + unique();
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MD", child, "L",
                                List.of(new String[]{a, "60"}, new String[]{b, "40"}))))
                .andExpect(status().isCreated());
        // 90*0.6 + 85*0.4 = 88；子批衰减率 1*0.6+0.5*0.4=0.8；阈值取最严格 80
        Map2 childModel = queryModel(child, 1);
        assertEquals("88.000000", childModel.a());
        assertEquals("0.800000", childModel.b());
        assertEquals("80.000000", jdbc.queryForObject(
                "SELECT min_potency FROM potency_model WHERE batch_key = ? AND version = 1",
                BigDecimal.class, child).toPlainString());
    }

    // ---------- 合批失败分支与事务回滚 ----------

    @Test
    void mergeOneParentBelowThreshold_fullRollback_failEvidence_freeKeyRetrySucceeds()
            throws Exception {
        String good = "BK-MF-G-" + unique();
        String bad = "BK-MF-B-" + unique();
        create(good, new BigDecimal("50"));
        create(bad, new BigDecimal("50"));
        setModel(good, "u", "CK-MG", "100", "80", "0.5", 201); // 11 天后 94.5 ≥ 80
        setModel(bad, "u", "CK-MB", "100", "95", "1", 201);    // 11 天后 89 < 95
        release(good, "CK-G1", "CK-G2", 201);
        release(bad, "CK-B1", "CK-B2", 201);
        pin("2026-03-12T00:00:00Z"); // 11 天后：good 94.5 ≥ 80，bad 89 < 95

        String child = "BK-MF-C-" + unique();
        String body = mergeBody("CK-MF", child, "L",
                List.of(new String[]{bad, "50"}, new String[]{good, "50"}));
        MvcResult blocked = mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode err = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("POTENCY_BELOW_THRESHOLD", err.path("code").asText());
        assertTrue(err.path("message").asText().contains(bad));
        assertTrue(err.path("message").asText().contains("89.000000"));
        assertTrue(err.path("message").asText().contains("95.000000"));
        assertTrue(err.path("message").asText().contains("-6.000000"));

        // 整次回滚：无子批、无贡献、父批仍 RELEASED、不占 commandKey
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent", Integer.class));
        assertEquals("RELEASED", statusOf(good));
        assertEquals("RELEASED", statusOf(bad));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-MF'", Integer.class));
        // 不合格父批上固化 FAIL 证据，合格父批不写证据
        JsonNode badFail = lastEvidence(bad);
        assertEquals("MERGE", badFail.path("eventType").asText());
        assertEquals("FAIL", badFail.path("decision").asText());
        assertEquals("CK-MF", badFail.path("commandKey").asText());
        assertTrue(evidenceOf(good).stream().noneMatch(e -> "MERGE".equals(e.path("eventType").asText())));

        // 修订 bad 模型 v2 使其此刻合格，同一 commandKey 重试成功
        setModel(bad, "u", "CK-MB2", "100", "95", "0", 201);
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertEquals("QUARANTINED", statusOf(child));
        assertEquals("MERGED", statusOf(bad));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-MF'", Integer.class));
    }

    @Test
    void mergeValidationFailures_untested_duplicateKey_overQuantity_unknown_conflict() throws Exception {
        String released = "BK-MV-R-" + unique();
        String quarantined = "BK-MV-Q-" + unique();
        create(released, new BigDecimal("10"));
        create(quarantined, new BigDecimal("10"));
        setModel(released, "u", "CK-MR", "100", "80", "0", 201);
        release(released, "CK-R1", "CK-R2", 201);

        // 父批未检验合格（非 RELEASED）→ 422，无半成品
        String child1 = "BK-MV-C1-" + unique();
        mergeExpect("CK-MV1", child1,
                List.of(new String[]{released, "5"}, new String[]{quarantined, "5"}), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child1));

        // 请求内父批重复 → 权重不守恒 422
        mergeExpect("CK-MV2", "BK-MV-C2-" + unique(),
                List.of(new String[]{released, "5"}, new String[]{released, "5"}), 422);

        // 投入数量超过父批登记数量 → 422，响应含实际值/要求值/差额（另一个父批合规已放行）
        String other = "BK-MV-O-" + unique();
        create(other, new BigDecimal("20"));
        setModel(other, "u", "CK-MO", "100", "80", "0", 201);
        release(other, "CK-O1", "CK-O2", 201);
        MvcResult overOnly = mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MV3", "BK-MV-C3B-" + unique(), "L",
                                List.of(new String[]{released, "11"}, new String[]{other, "5"}))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String overMsg = objectMapper.readTree(overOnly.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(overMsg.contains("11"));
        assertTrue(overMsg.contains("10") || overMsg.contains("超出差额=1"));

        // 数量为负/零 → 422
        mergeExpect("CK-MV4", "BK-MV-C4-" + unique(),
                List.of(new String[]{released, "0"}, new String[]{other, "5"}), 422);
        String negative = mergeBodyRaw("CK-MV5", "BK-MV-C5-" + unique(), "L",
                List.of(new String[]{released, "-3"}, new String[]{other, "5"}));
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(negative))
                .andExpect(status().isUnprocessableEntity());

        // 未知父批 → 404；缺操作者 → 400
        mergeExpect("CK-MV6", "BK-MV-C6-" + unique(),
                List.of(new String[]{released, "5"}, new String[]{"NO-SUCH-PARENT", "5"}), 404);
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MV7", "BK-MV-C7-" + unique(), "L",
                                List.of(new String[]{released, "5"}, new String[]{other, "5"}))))
                .andExpect(status().isBadRequest());

        // 子批键已存在 → 409
        mergeExpect("CK-MV8", released,
                List.of(new String[]{released, "5"}, new String[]{other, "5"}), 409);

        // 全部失败均无半成品、父批状态不变
        assertEquals("RELEASED", statusOf(released));
        assertEquals("RELEASED", statusOf(other));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent", Integer.class));
    }

    @Test
    void merge_parentWithNullQuantity_allowsAnyPositiveInput() throws Exception {
        String a = "BK-MN-A-" + unique();
        String b = "BK-MN-B-" + unique();
        create(a, null);
        create(b, null);
        setModel(a, "u", "CK-MA", "100", "80", "0", 201);
        setModel(b, "u", "CK-MB", "100", "80", "0", 201);
        release(a, "CK-A1", "CK-A2", 201);
        release(b, "CK-B1", "CK-B2", 201);
        mergeExpect("CK-MN", "BK-MN-C-" + unique(),
                List.of(new String[]{a, "999999"}, new String[]{b, "0.000001"}), 201);
    }

    @Test
    void merge_idempotentReplayChangedParamsConflict_andNoHalfProductOnFailure() throws Exception {
        String a = "BK-MI-A-" + unique();
        String b = "BK-MI-B-" + unique();
        create(a, new BigDecimal("50"));
        create(b, new BigDecimal("50"));
        setModel(a, "u", "CK-MA", "100", "80", "0", 201);
        setModel(b, "u", "CK-MB", "90", "80", "0", 201);
        release(a, "CK-A1", "CK-A2", 201);
        release(b, "CK-B1", "CK-B2", 201);

        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI", child, "L",
                List.of(new String[]{a, "50"}, new String[]{b, "50"}));
        MvcResult first = mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent", Integer.class));

        // 同键改数量（影响结果）→ 409；同键换操作者 → 409
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI", "BK-MI-C2-" + unique(), "L",
                                List.of(new String[]{a, "40"}, new String[]{b, "60"}))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "someone-else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI", "BK-MI-C3-" + unique(), "L",
                                List.of(new String[]{a, "50"}, new String[]{b, "50"}))))
                .andExpect(status().isConflict());
    }

    @Test
    void merge_blockedWhenParentHasRecalledAncestor() throws Exception {
        // root 拆分出 child；child 放行；root 召回；child 与另一批次合批应 422
        String root = "BK-MR-R-" + unique();
        create(root, null);
        setModel(root, "u", "CK-MR", "100", "80", "0", 201);
        release(root, "CK-R1", "CK-R2", 201);
        String child = "BK-MR-C-" + unique();
        split(root, "u", "CK-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-MR-C2-" + unique(), "L2"}), 201);
        setModel(child, "u", "CK-MC", "100", "80", "0", 201);
        release(child, "CK-C1", "CK-C2", 201);
        recall(root, "u", "上游污染", "CK-RC");

        String other = "BK-MR-O-" + unique();
        create(other, null);
        setModel(other, "u", "CK-MO", "100", "80", "0", 201);
        release(other, "CK-O1", "CK-O2", 201);
        mergeExpect("CK-MX", "BK-MR-X-" + unique(),
                List.of(new String[]{child, "1"}, new String[]{other, "1"}), 422);
        assertEquals("RELEASED", statusOf(child));
        assertEquals("RELEASED", statusOf(other));
    }

    @Test
    void recallMergedParent_propagatesToMergeChildAcrossMergeEdges() throws Exception {
        String a = "BK-RMP-A-" + unique();
        String b = "BK-RMP-B-" + unique();
        create(a, new BigDecimal("50"));
        create(b, new BigDecimal("50"));
        setModel(a, "u", "CK-MA", "100", "80", "0", 201);
        setModel(b, "u", "CK-MB", "100", "80", "0", 201);
        release(a, "CK-A1", "CK-A2", 201);
        release(b, "CK-B1", "CK-B2", 201);
        String child = "BK-RMP-C-" + unique();
        mergeExpect("CK-MRG", child,
                List.of(new String[]{a, "50"}, new String[]{b, "50"}), 201);
        assertEquals("MERGED", statusOf(a));

        // MERGED 父批可召回；合批子批立即跨合批边被排除出可用查询
        recall(a, "u", "父批合批后召回", "CK-RA");
        assertEquals("RECALLED", statusOf(a));
        MvcResult available = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        objectMapper.readTree(available.getResponse().getContentAsString())
                .forEach(n -> keys.add(n.path("batchKey").asText()));
        assertFalse(keys.contains(child));

        // 子批自身状态不改写，但祖先查询标注召回祖先；后代查询反向可见
        assertEquals("QUARANTINED", statusOf(child));
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        // 两个父批均在祖先列表：被直接召回的 a 状态 RECALLED 且不把自身标为召回祖先；
        // 未召回的 b 状态 MERGED，二者的 unavailableDueToRecalledAncestor 均为 null
        JsonNode aEntry = null;
        boolean hasB = false;
        for (JsonNode node : ancestorNodes) {
            if (a.equals(node.path("batchKey").asText())) {
                aEntry = node;
            }
            if (b.equals(node.path("batchKey").asText())) {
                hasB = true;
            }
        }
        assertNotNull(aEntry);
        assertEquals("RECALLED", aEntry.path("status").asText());
        assertTrue(aEntry.path("unavailableDueToRecalledAncestor").isNull());
        assertTrue(hasB);
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + a + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descKeys = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertTrue(descKeys.findValues("batchKey").stream()
                .anyMatch(n -> child.equals(n.asText())));

        // 子批新增检验/放行被祖先召回拦截 422
        mockMvc.perform(post("/api/batches/" + child + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("TK-RMP", "t1", "PASS", "insp")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 并发：提交顺序裁决 ----------

    @Test
    void concurrentMergesSharingParent_commitOrderDecides_noHalfProducts() throws Exception {
        String a = "BK-CM-A-" + unique();
        String b = "BK-CM-B-" + unique();
        String c = "BK-CM-C-" + unique();
        for (String k : List.of(a, b, c)) {
            create(k, new BigDecimal("100"));
            setModel(k, "u", "CK-M-" + k, "100", "80", "0", 201);
            release(k, "CK-1-" + k, "CK-2-" + k, 201);
        }
        String child1 = "BK-CM-X-" + unique();
        String child2 = "BK-CM-Y-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> call(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM1", child1, "L1",
                                List.of(new String[]{a, "50"}, new String[]{b, "50"})))),
                () -> call(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM2", child2, "L2",
                                List.of(new String[]{a, "50"}, new String[]{c, "50"}))))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 422) || (first == 422 && second == 201),
                "必须恰好一个合批成功，另一个因父批已合批返回 422，实际为 " + first + "/" + second);

        // 只有一个合批子批与一组两条贡献；共享父批 MERGED，落败合批的另一父批保持 RELEASED
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?) AND status = 'QUARANTINED'",
                Integer.class, child1, child2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_parent", Integer.class));
        assertEquals("MERGED", jdbc.queryForObject("SELECT status FROM batch WHERE batch_key = ?",
                String.class, a));
    }

    @Test
    void concurrentModelRevisionAndRelease_commitOrderDecides_consistentSnapshot() throws Exception {
        String b = "BK-CR-" + unique();
        create(b, null);
        setModel(b, "u", "CK-M1", "100", "90", "0", 201);
        submitPass(b);
        approve(b, "qa-1", "QUALITY", "CK-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> call(post("/api/batches/" + b + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-A2\"}")),
                () -> call(post("/api/batches/" + b + "/potency-model").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelBody("CK-M2", "100", "101", "0")))
        );
        int releaseCode = results.get(0).get(30, TimeUnit.SECONDS);
        int modelCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, modelCode);
        if (releaseCode == 201) {
            // 放行先提交：固化 PASS v1，批次 RELEASED
            assertEquals("RELEASED", statusOf(b));
            JsonNode ev = lastEvent(b, "RELEASE");
            assertEquals("PASS", ev.path("decision").asText());
            assertEquals(1, ev.path("modelVersion").asInt());
        } else {
            // 修订先提交：放行读取 v2（阈值 101）核验失败，停留 RELEASE_REVIEW 并固化 FAIL v2
            assertEquals(422, releaseCode);
            assertEquals("RELEASE_REVIEW", statusOf(b));
            JsonNode ev = lastEvent(b, "RELEASE");
            assertEquals("FAIL", ev.path("decision").asText());
            assertEquals(2, ev.path("modelVersion").asInt());
            assertNum("101.000000", ev.path("thresholdPotency"));
        }
    }

    // ---------- helpers ----------

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, BigDecimal quantity) {
    }

    private record ModelCmd(String commandKey, BigDecimal initialPotency, BigDecimal minPotency,
                            BigDecimal decayRate) {
    }

    private record MergeParentInput(String batchKey, BigDecimal quantity) {
    }

    private record MergeCmd(String commandKey, String childBatchKey, String childBatchNo,
                            List<MergeParentInput> parents) {
    }

    /** 模型行简易二元组：a=initial_potency，b=decay_rate（min 单独查询）。 */
    private record Map2(String a, String b) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void pin(String iso) {
        clock.pin(Instant.parse(iso));
    }

    private void create(String batchKey, BigDecimal quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-" + unique(), T0, List.of("t1"), quantity));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String modelBody(String ck, String initial, String min, String decay) throws Exception {
        return objectMapper.writeValueAsString(new ModelCmd(ck, new BigDecimal(initial),
                new BigDecimal(min), new BigDecimal(decay)));
    }

    private void setModel(String batchKey, String actor, String ck, String initial, String min,
                          String decay, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/potency-model")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(modelBody(ck, initial, min, decay)))
                .andExpect(status().is(expected));
    }

    private String setModelReturning(String batchKey, String actor, String ck, String initial,
                                     String min, String decay) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/potency-model")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(modelBody(ck, initial, min, decay)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private void submitPass(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("TK-" + unique(), "t1", "PASS", "insp-" + unique())))
                .andExpect(status().isCreated());
    }

    private String testBody(String testKey, String item, String result, String inspector)
            throws Exception {
        return objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), testKey, item,
                result, inspector));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String ck, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck + "\"}"))
                .andExpect(status().is(expected));
    }

    private void release(String batchKey, String ck1, String ck2, int expected) throws Exception {
        submitPass(batchKey);
        approve(batchKey, "qa-" + unique(), "QUALITY", ck1, 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", ck2, expected);
    }

    private void recall(String batchKey, String actor, String reason, String ck) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall").header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + ck + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult split(String parent, String actor, String ck, List<String[]> children,
                            int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(ck)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        sb.append("]}");
        return mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private String mergeBody(String ck, String child, String childNo, List<String[]> parents)
            throws Exception {
        return mergeBodyRaw(ck, child, childNo, parents);
    }

    private String mergeBodyRaw(String ck, String child, String childNo, List<String[]> parents)
            throws Exception {
        List<MergeParentInput> inputs = new ArrayList<>();
        for (String[] p : parents) {
            inputs.add(new MergeParentInput(p[0], new BigDecimal(p[1])));
        }
        return objectMapper.writeValueAsString(new MergeCmd(ck, child, childNo, inputs));
    }

    private void mergeExpect(String ck, String child, List<String[]> parents, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(ck, child, "L", parents)))
                .andExpect(status().is(expected));
    }

    private JsonNode potency(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/potency"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private String statusOf(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private List<JsonNode> evidenceOf(String batchKey) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        history(batchKey).path("evidence").forEach(list::add);
        return list;
    }

    private JsonNode lastEvidence(String batchKey) throws Exception {
        List<JsonNode> list = evidenceOf(batchKey);
        assertFalse(list.isEmpty(), "至少应有一条证据: " + batchKey);
        return list.get(list.size() - 1);
    }

    private JsonNode lastEvent(String batchKey, String eventType) throws Exception {
        return evidenceOf(batchKey).stream()
                .filter(e -> eventType.equals(e.path("eventType").asText()))
                .reduce((a, b2) -> b2)
                .orElseThrow(() -> new AssertionError("缺少 " + eventType + " 证据: " + batchKey));
    }

    /**
     * 数值断言：JSON 树读取数字会归一化为 double 丢失尾零，故按 BigDecimal 数值比较
     * （刻度稳定性由原始响应串 contains 断言单独保证）。
     */
    private void assertNum(String expected, JsonNode actual) {
        assertNum(expected, actual, null);
    }

    private void assertNum(String expected, JsonNode actual, String message) {
        assertFalse(actual.isMissingNode() || actual.isNull(),
                (message == null ? "" : message + " ") + "数值字段缺失或为 null: " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                message == null ? ("期望 " + expected + " 实际 " + actual) : message);
    }

    private Map2 queryModel(String batchKey, int version) {
        BigDecimal initial = jdbc.queryForObject(
                "SELECT initial_potency FROM potency_model WHERE batch_key = ? AND version = ?",
                BigDecimal.class, batchKey, version);
        BigDecimal decay = jdbc.queryForObject(
                "SELECT decay_rate FROM potency_model WHERE batch_key = ? AND version = ?",
                BigDecimal.class, batchKey, version);
        return new Map2(initial.toPlainString(), decay.toPlainString());
    }

    private int call(MockHttpServletRequestBuilder req) {
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
