package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次效价衰减模型测试：模型登记/修订与校验、放行时点核验（可注入时钟）、
 * 拆分效价加权与权重守恒、诊断查询、历史固化不改写、commandKey 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchPotencyTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM potency_evaluation");
        jdbc.update("DELETE FROM batch_potency_model");
        jdbc.update("DELETE FROM batch");
        clock.setInstant(T0);
    }

    // ---------- 模型登记与修订 ----------

    @Test
    void registerModel_happyAndRevision_versionsAccumulateAndHistoryKept() throws Exception {
        String batch = "BK-PM-" + unique();
        createBatch(batch, List.of("t1"), 201);

        MvcResult first = registerModel(batch, "CK-PM-1", "100", "90", "1", 201);
        JsonNode body = objectMapper.readTree(first.getResponse().getContentAsString());
        assertEquals(1, body.path("version").asInt());
        assertEquals(0, new BigDecimal("100").compareTo(body.path("initialPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(body.path("minReleasePotency").decimalValue()));
        assertEquals(0, new BigDecimal("1").compareTo(body.path("decayRatePerDay").decimalValue()));
        assertEquals("2026-06-01T00:00:00Z", body.path("registeredAt").asText());

        // 修订产生新版本，历史版本保留不改写
        clock.plusDays(1);
        MvcResult second = registerModel(batch, "CK-PM-2", "120", "95", "0.5", 201);
        JsonNode revised = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(2, revised.path("version").asInt());
        assertEquals("2026-06-02T00:00:00Z", revised.path("registeredAt").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class,
                batch));
        assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject(
                "SELECT initial_potency FROM batch_potency_model WHERE batch_key = ? AND version = 1",
                BigDecimal.class, batch)));
    }

    @Test
    void registerModel_negativeValues_return422() throws Exception {
        String batch = "BK-PMN-" + unique();
        createBatch(batch, List.of("t1"), 201);
        registerModel(batch, "CK-PMN-1", "-1", "90", "1", 422);
        registerModel(batch, "CK-PMN-2", "100", "-90", "1", 422);
        registerModel(batch, "CK-PMN-3", "100", "90", "-0.5", 422);
        // 失败不占键：修正参数后同键成功
        registerModel(batch, "CK-PMN-3", "100", "90", "0.5", 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class,
                batch));
    }

    @Test
    void registerModel_unknownBatch404_terminalStatus409() throws Exception {
        registerModel("NO-SUCH-BATCH", "CK-PM404", "100", "90", "1", 404);

        String rejected = "BK-PMR-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        registerModel(rejected, "CK-PMR-1", "100", "90", "1", 409);
    }

    @Test
    void registerModel_commandKeyReplayAndConflict() throws Exception {
        String batch = "BK-PMI-" + unique();
        createBatch(batch, List.of("t1"), 201);
        String body = modelBody("CK-PMI", "100", "90", "1");

        MvcResult first = mockMvc.perform(put("/api/batches/" + batch + "/potency-model")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(put("/api/batches/" + batch + "/potency-model")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不产生新版本
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class,
                batch));

        // 同键改参 → 409
        mockMvc.perform(put("/api/batches/" + batch + "/potency-model")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelBody("CK-PMI", "100", "80", "1")))
                .andExpect(status().isConflict());
        // 操作者属于指纹：同键不同操作者 → 409
        mockMvc.perform(put("/api/batches/" + batch + "/potency-model")
                        .header("X-Actor-Id", "qa-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---------- 放行时点核验 ----------

    @Test
    void release_withModel_passesAndPersistsEvaluation() throws Exception {
        String batch = "BK-RLP-" + unique();
        createBatch(batch, List.of("t1"), 201);
        registerModel(batch, "CK-RLP-M", "100", "90", "1", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-RLP-A1", 201);

        clock.plusDays(5);
        approve(batch, "ops-1", "OPERATIONS", "CK-RLP-A2", 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 固化：计算时刻、模型版本、输入效价、经过天数与舍入结果
        List<JsonNode> evaluations = evaluationsOf(batch);
        assertEquals(1, evaluations.size());
        JsonNode eval = evaluations.get(0);
        assertEquals("RELEASE", eval.path("operation").asText());
        assertEquals(1, eval.path("modelVersion").asInt());
        assertEquals(5, eval.path("elapsedDays").asLong());
        assertEquals(0, new BigDecimal("100").compareTo(eval.path("initialPotency").decimalValue()));
        assertEquals(0, new BigDecimal("1").compareTo(eval.path("decayRatePerDay").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(eval.path("minReleasePotency").decimalValue()));
        assertEquals(0, new BigDecimal("95.0000").compareTo(eval.path("currentPotency").decimalValue()));
        assertEquals("2026-06-06T00:00:00Z", eval.path("evaluatedAt").asText());
        assertTrue(eval.path("relatedKey").isNull());
    }

    @Test
    void release_belowThreshold_returns422WithDetails_failureDoesNotOccupyKey() throws Exception {
        String batch = "BK-RLF-" + unique();
        createBatch(batch, List.of("t1"), 201);
        registerModel(batch, "CK-RLF-M", "100", "90", "1", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-RLF-A1", 201);

        clock.plusDays(11);
        MvcResult rejected = mockMvc.perform(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RLF-A2\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", error.path("code").asText());
        // 差额明细：实际值 89、要求值 90、差额 -1
        assertEquals(0, new BigDecimal("89.0000").compareTo(
                error.path("details").path("actualPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(
                error.path("details").path("requiredPotency").decimalValue()));
        assertEquals(0, new BigDecimal("-1.0000").compareTo(
                error.path("details").path("difference").decimalValue()));

        // 批次停留 RELEASE_REVIEW，无评估记录，无批准记录
        assertEquals("RELEASE_REVIEW", currentStatus(batch));
        assertEquals(0, evaluationsOf(batch).size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batch));

        // 失败不占键：修订模型降低阈值后，同一 commandKey 放行成功
        registerModel(batch, "CK-RLF-M2", "100", "80", "1", 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-RLF-A2", 201);
        assertEquals("RELEASED", currentStatus(batch));
        List<JsonNode> evaluations = evaluationsOf(batch);
        assertEquals(1, evaluations.size());
        assertEquals(2, evaluations.get(0).path("modelVersion").asInt());
        // 修订模型以登记时刻为衰减起算点：v2 登记于第 11 日，放行同日评估经过 0 天
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                evaluations.get(0).path("currentPotency").decimalValue()));
    }

    @Test
    void release_readsConsistentLatestModel() throws Exception {
        String batch = "BK-RLC-" + unique();
        createBatch(batch, List.of("t1"), 201);
        // v1 衰减极快必然不合格；v2 不衰减
        registerModel(batch, "CK-RLC-M1", "100", "90", "100", 201);
        registerModel(batch, "CK-RLC-M2", "100", "90", "0", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-RLC-A1", 201);
        clock.plusDays(1);
        approve(batch, "ops-1", "OPERATIONS", "CK-RLC-A2", 201);
        assertEquals("RELEASED", currentStatus(batch));
        List<JsonNode> evaluations = evaluationsOf(batch);
        assertEquals(2, evaluations.get(0).path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                evaluations.get(0).path("currentPotency").decimalValue()));
    }

    @Test
    void release_withoutModel_behavesAsBefore() throws Exception {
        String batch = "BK-RLN-" + unique();
        createBatch(batch, List.of("t1"), 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-RLN-A1", 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-RLN-A2", 201);
        assertEquals("RELEASED", currentStatus(batch));
        assertEquals(0, evaluationsOf(batch).size());
    }

    // ---------- 诊断查询 ----------

    @Test
    void potencyStatus_reflectsClock_andNullSemanticsWithoutModel() throws Exception {
        String batch = "BK-DG-" + unique();
        createBatch(batch, List.of("t1"), 201);

        // 未登记模型：model 与各计算字段为 null
        MvcResult noModel = mockMvc.perform(get("/api/batches/" + batch + "/potency"))
                .andExpect(status().isOk()).andReturn();
        JsonNode empty = objectMapper.readTree(noModel.getResponse().getContentAsString());
        assertTrue(empty.path("model").isNull());
        assertTrue(empty.path("currentPotency").isNull());
        assertTrue(empty.path("releaseQualified").isNull());

        registerModel(batch, "CK-DG-M", "100", "90", "2", 201);
        clock.plusDays(3);
        MvcResult result = mockMvc.perform(get("/api/batches/" + batch + "/potency"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, body.path("model").path("version").asInt());
        assertEquals(3, body.path("elapsedDays").asLong());
        assertEquals(0, new BigDecimal("94.0000").compareTo(
                body.path("currentPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90").compareTo(
                body.path("requiredPotency").decimalValue()));
        assertEquals(0, new BigDecimal("4.0000").compareTo(
                body.path("difference").decimalValue()));
        assertTrue(body.path("releaseQualified").asBoolean());
        assertEquals("2026-06-04T00:00:00Z", body.path("evaluatedAt").asText());

        // 读取不改变状态：再次读取结果一致，无评估记录产生
        MvcResult again = mockMvc.perform(get("/api/batches/" + batch + "/potency"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(result.getResponse().getContentAsString(),
                again.getResponse().getContentAsString());
        assertEquals(0, evaluationsOf(batch).size());

        mockMvc.perform(get("/api/batches/NO-SUCH/potency"))
                .andExpect(status().isNotFound());
    }

    // ---------- 拆分效价加权 ----------

    @Test
    void split_withModel_childrenInheritCurrentPotency_andEvaluationPersisted() throws Exception {
        String parent = "BK-SPP-" + unique();
        createBatch(parent, List.of("t1"), 201);
        registerModel(parent, "CK-SPP-M", "100", "50", "2", 201);
        submitTest(parent, "t1", "PASS", "insp", 201);
        approve(parent, "qa-1", "QUALITY", "CK-SPP-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SPP-A2", 201);

        clock.plusDays(10);
        String c1 = "BK-SPP-C1-" + unique();
        String c2 = "BK-SPP-C2-" + unique();
        MvcResult result = split(parent, "CK-SPP-S", "10",
                List.of(new String[]{c1, "L1", "4"}, new String[]{c2, "L2", "6"}), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // 子批初始效价 = 父批拆分时刻当前效价 100 - 2*10 = 80
        for (int i = 0; i < 2; i++) {
            assertEquals(0, new BigDecimal("80.0000").compareTo(
                    body.path("children").get(i).path("initialPotency").decimalValue()));
        }
        // 子批模型 v1：初始 80，阈值与衰减率继承父批，登记时刻为拆分时刻
        for (String child : List.of(c1, c2)) {
            MvcResult status = mockMvc.perform(get("/api/batches/" + child + "/potency"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode model = objectMapper.readTree(status.getResponse().getContentAsString())
                    .path("model");
            assertEquals(1, model.path("version").asInt());
            assertEquals(0, new BigDecimal("80.0000").compareTo(
                    model.path("initialPotency").decimalValue()));
            assertEquals(0, new BigDecimal("50").compareTo(
                    model.path("minReleasePotency").decimalValue()));
            assertEquals(0, new BigDecimal("2").compareTo(
                    model.path("decayRatePerDay").decimalValue()));
            assertEquals("2026-06-11T00:00:00Z", model.path("registeredAt").asText());
        }
        // 父批评估记录固化：放行 1 条 + 拆分每个子批 1 条，按写入顺序
        List<JsonNode> evaluations = evaluationsOf(parent);
        assertEquals(3, evaluations.size());
        assertEquals("RELEASE", evaluations.get(0).path("operation").asText());
        assertEquals("SPLIT", evaluations.get(1).path("operation").asText());
        assertEquals("SPLIT", evaluations.get(2).path("operation").asText());
        assertEquals(c1, evaluations.get(1).path("relatedKey").asText());
        assertEquals(c2, evaluations.get(2).path("relatedKey").asText());
        assertEquals(10, evaluations.get(1).path("elapsedDays").asLong());
        assertEquals(0, new BigDecimal("80.0000").compareTo(
                evaluations.get(1).path("currentPotency").decimalValue()));
        assertEquals("2026-06-11T00:00:00Z", evaluations.get(1).path("evaluatedAt").asText());
    }

    @Test
    void split_weightViolations_return422_parentUnchanged() throws Exception {
        String parent = "BK-SPW-" + unique();
        createBatch(parent, List.of("t1"), 201);
        registerModel(parent, "CK-SPW-M", "100", "50", "1", 201);
        submitTest(parent, "t1", "PASS", "insp", 201);
        approve(parent, "qa-1", "QUALITY", "CK-SPW-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SPW-A2", 201);

        // 权重不守恒 → 422，携带实际值/要求值/差额
        MvcResult notConserved = split(parent, "CK-SPW-1", "10",
                List.of(new String[]{"BK-SPW-A-" + unique(), "L1", "4"},
                        new String[]{"BK-SPW-B-" + unique(), "L2", "5"}), 422);
        JsonNode error = objectMapper.readTree(notConserved.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("10").compareTo(
                error.path("details").path("requiredWeight").decimalValue()));
        assertEquals(0, new BigDecimal("9").compareTo(
                error.path("details").path("actualWeight").decimalValue()));
        assertEquals(0, new BigDecimal("-1").compareTo(
                error.path("details").path("difference").decimalValue()));

        // 缺子批权重 → 422；非正权重 → 422；缺 parentWeight → 422
        split(parent, "CK-SPW-2", "10",
                List.of(new String[]{"BK-SPW-C-" + unique(), "L1", "4"},
                        new String[]{"BK-SPW-D-" + unique(), "L2", null}), 422);
        split(parent, "CK-SPW-3", "10",
                List.of(new String[]{"BK-SPW-E-" + unique(), "L1", "0"},
                        new String[]{"BK-SPW-F-" + unique(), "L2", "10"}), 422);
        split(parent, "CK-SPW-4", null,
                List.of(new String[]{"BK-SPW-G-" + unique(), "L1", "4"},
                        new String[]{"BK-SPW-H-" + unique(), "L2", "6"}), 422);

        // 全部失败：父批不变、无子批、无血缘、无评估记录
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evaluation WHERE batch_key = ? AND operation = 'SPLIT'",
                Integer.class, parent));

        // 失败不占键：修正后同键成功
        String c1 = "BK-SPW-OK1-" + unique();
        String c2 = "BK-SPW-OK2-" + unique();
        split(parent, "CK-SPW-1", "10",
                List.of(new String[]{c1, "L1", "4"}, new String[]{c2, "L2", "6"}), 201);
        assertEquals("SPLIT", currentStatus(parent));
    }

    @Test
    void split_withoutModel_legacyBehaviorUnchanged() throws Exception {
        String parent = "BK-SPL-" + unique();
        createBatch(parent, List.of("t1"), 201);
        submitTest(parent, "t1", "PASS", "insp", 201);
        approve(parent, "qa-1", "QUALITY", "CK-SPL-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SPL-A2", 201);

        String c1 = "BK-SPL-C1-" + unique();
        MvcResult result = split(parent, "CK-SPL-S", null,
                List.of(new String[]{c1, "L1", null},
                        new String[]{"BK-SPL-C2-" + unique(), "L2", null}), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.path("children").get(0).path("initialPotency").isNull());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_potency_model WHERE batch_key = ?", Integer.class, c1));
    }

    // ---------- 历史固化 ----------

    @Test
    void history_potencyEvidenceSurvivesModelRevision() throws Exception {
        String batch = "BK-HIS-" + unique();
        createBatch(batch, List.of("t1"), 201);
        registerModel(batch, "CK-HIS-M1", "100", "90", "1", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-HIS-A1", 201);
        clock.plusDays(5);
        approve(batch, "ops-1", "OPERATIONS", "CK-HIS-A2", 201);

        // 放行后修订模型：历史评估记录不改写
        registerModel(batch, "CK-HIS-M2", "50", "10", "0", 201);
        MvcResult history = mockMvc.perform(get("/api/batches/" + batch + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        // 当前模型为 v2
        assertEquals(2, node.path("potencyModel").path("version").asInt());
        // 放行评估仍记录 v1 的输入与结果
        JsonNode eval = node.path("potencyEvaluations").get(0);
        assertEquals(1, eval.path("modelVersion").asInt());
        assertEquals(0, new BigDecimal("95.0000").compareTo(
                eval.path("currentPotency").decimalValue()));
        assertEquals(5, eval.path("elapsedDays").asLong());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        StringBuilder tests = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                tests.append(',');
            }
            tests.append("\"").append(items.get(i)).append("\"");
        }
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":["
                                + tests + "]}"))
                .andExpect(status().is(expected));
    }

    private String modelBody(String commandKey, String initial, String min, String rate) {
        return "{\"commandKey\":\"" + commandKey + "\",\"initialPotency\":" + initial
                + ",\"minReleasePotency\":" + min + ",\"decayRatePerDay\":" + rate + "}";
    }

    private MvcResult registerModel(String batchKey, String commandKey, String initial, String min,
                                    String rate, int expected) throws Exception {
        return mockMvc.perform(put("/api/batches/" + batchKey + "/potency-model")
                        .header("X-Actor-Id", "qa-modeler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelBody(commandKey, initial, min, rate)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                                + "\",\"inspector\":\"" + inspector + "\"}"))
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

    private MvcResult split(String parentKey, String commandKey, String parentWeight,
                            List<String[]> children, int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey).append("\"");
        if (parentWeight != null) {
            sb.append(",\"parentWeight\":").append(parentWeight);
        }
        sb.append(",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"");
            if (children.get(i)[2] != null) {
                sb.append(",\"weight\":").append(children.get(i)[2]);
            }
            sb.append("}");
        }
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.append("]}").toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<JsonNode> evaluationsOf(String batchKey) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("potencyEvaluations");
        List<JsonNode> result = new java.util.ArrayList<>();
        node.forEach(result::add);
        return result;
    }
}
