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
 * 批次包装标签核销测试：计划登记、批量封箱、作废、放行数量守恒门禁、
 * 放行快照固化、幂等重放、并发同标签唯一约束及诊断查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackLabelReconciliationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_label_snapshot");
        jdbc.update("DELETE FROM label_usage");
        jdbc.update("DELETE FROM carton");
        jdbc.update("DELETE FROM pack_plan");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_planSealRelease_andSnapshotFrozen() throws Exception {
        String batchKey = "BK-PACK-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 3, 1000, 1010, 201);

        MvcResult sealResult = seal(batchKey, "CK-SEAL-1",
                List.of(carton("CT-1", 1000, 1), carton("CT-2", 1001, 2)), 201);
        JsonNode sealBody = read(sealResult);
        assertEquals(3, sealBody.path("sealedQuantity").asInt());
        assertEquals(3, sealBody.path("plannedQuantity").asInt());
        assertEquals(0, sealBody.path("remainingQuantity").asInt());
        assertEquals(2, sealBody.path("cartons").size());

        JsonNode recon = reconciliation(batchKey);
        assertTrue(recon.path("complete").asBoolean());
        assertEquals(2, recon.path("activeCartonCount").asInt());
        assertEquals(2, recon.path("usedLabelCount").asInt());
        assertTrue(recon.path("snapshot").isNull());

        passAllTestsAndApproveFirst(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行快照：计划数量、实际封箱数量、规范排序标签摘要与每个封箱版本
        JsonNode after = reconciliation(batchKey);
        JsonNode snapshot = after.path("snapshot");
        assertFalse(snapshot.isNull());
        assertEquals(3, snapshot.path("plannedQuantity").asInt());
        assertEquals(3, snapshot.path("sealedQuantity").asInt());
        assertEquals(2, snapshot.path("cartonCount").asInt());
        assertEquals(List.of(1000L, 1001L), toLongList(snapshot.path("labels")));
        assertEquals(64, snapshot.path("labelDigest").asText().length());
        assertEquals(1, snapshot.path("cartonVersions").path("CT-1").asInt());
        assertEquals(1, snapshot.path("cartonVersions").path("CT-2").asInt());
        assertTrue(after.path("discrepancy").isNull());
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseBlockedWhenPlanIncomplete_returns422WithDifference_thenCompletes() throws Exception {
        String batchKey = "BK-GATE-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 5, 100, 200, 201);
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 2)), 201);

        passAllTestsAndApproveFirst(batchKey);
        // 第二批准触发放行：封箱数量不足 → 422，响应含实际值/要求值/差额
        MvcResult blocked = approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 422);
        String message = read(blocked).path("message").asText();
        assertTrue(message.contains("计划=5"), message);
        assertTrue(message.contains("实际已封=2"), message);
        assertTrue(message.contains("差额=3"), message);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 失败的批准不占 commandKey：补足封箱后同键重试成功
        seal(batchKey, "CK-SEAL-2", List.of(carton("CT-2", 101, 3)), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    // ---------- 封箱校验失败分支 ----------

    @Test
    void labelOutOfRange_returns422_andNoPartialOccupation() throws Exception {
        String batchKey = "BK-RANGE-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 5, 100, 110, 201);

        // 一个合法标签 + 一个越界标签（110 为开区间端点）：整次 422 回滚
        MvcResult result = seal(batchKey, "CK-SEAL-1",
                List.of(carton("CT-1", 100, 1), carton("CT-2", 110, 1)), 422);
        String message = read(result).path("message").asText();
        assertTrue(message.contains("标签=110"), message);
        assertTrue(message.contains("[100, 110)"), message);

        // 不留下部分占用：合法标签 100 也未占用
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM label_usage", Integer.class));
        JsonNode recon = reconciliation(batchKey);
        assertEquals(0, recon.path("sealedQuantity").asInt());
        assertEquals(5, recon.path("remainingQuantity").asInt());
    }

    @Test
    void duplicateLabelInRequest_returns409_andRollsBack() throws Exception {
        String batchKey = "BK-DUPLBL-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 5, 100, 110, 201);

        seal(batchKey, "CK-SEAL-1",
                List.of(carton("CT-1", 100, 1), carton("CT-2", 100, 1)), 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM label_usage", Integer.class));
    }

    @Test
    void labelUsedByOtherBatch_returns409() throws Exception {
        String batchA = "BK-LBL-A-" + unique();
        String batchB = "BK-LBL-B-" + unique();
        createBatch(batchA);
        createBatch(batchB);
        registerPlan(batchA, "CK-PLAN-A", 3, 500, 510, 201);
        registerPlan(batchB, "CK-PLAN-B", 3, 500, 510, 201);

        seal(batchA, "CK-SEAL-A", List.of(carton("CT-A1", 500, 1)), 201);
        // 每个标签只能关联一个批次和一个封箱
        MvcResult conflict = seal(batchB, "CK-SEAL-B", List.of(carton("CT-B1", 500, 1)), 409);
        String message = read(conflict).path("message").asText();
        assertTrue(message.contains("占用批次=" + batchA), message);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM label_usage", Integer.class));
    }

    @Test
    void quantityExceedsPlan_returns422WithAmounts() throws Exception {
        String batchKey = "BK-OVER-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 2, 100, 110, 201);
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 2)), 201);

        MvcResult over = seal(batchKey, "CK-SEAL-2", List.of(carton("CT-2", 101, 1)), 422);
        String message = read(over).path("message").asText();
        assertTrue(message.contains("计划=2"), message);
        assertTrue(message.contains("已封=2"), message);
        assertTrue(message.contains("本次=1"), message);
        assertTrue(message.contains("超出=1"), message);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));
    }

    @Test
    void sealWithoutPlan_returns422() throws Exception {
        String batchKey = "BK-NOPLAN-" + unique();
        createBatch(batchKey);
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 1)), 422);
    }

    @Test
    void sealAndVoidOnReleasedOrRecalledBatch_returns409() throws Exception {
        String batchKey = "BK-RELSEAL-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 1, 100, 110, 201);
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 1)), 201);
        passAllTestsAndApproveFirst(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 已放行：禁止再封箱、禁止作废
        seal(batchKey, "CK-SEAL-2", List.of(carton("CT-2", 101, 1)), 409);
        voidCarton(batchKey, "CT-1", "CK-VOID-1", "误贴", 409);

        // 已召回：禁止封箱与作废
        recall(batchKey, "CK-RC-1", 201);
        seal(batchKey, "CK-SEAL-3", List.of(carton("CT-3", 102, 1)), 409);
        voidCarton(batchKey, "CT-1", "CK-VOID-2", "误贴", 409);
    }

    // ---------- 作废 ----------

    @Test
    void voidReleasesQuantityAndLabel_labelReusable_historyStable() throws Exception {
        String batchKey = "BK-VOID-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 3, 100, 110, 201);
        seal(batchKey, "CK-SEAL-1",
                List.of(carton("CT-1", 100, 2), carton("CT-2", 101, 1)), 201);

        MvcResult voided = voidCarton(batchKey, "CT-1", "CK-VOID-1", "贴错标签", 200);
        JsonNode voidBody = read(voided);
        assertEquals("VOIDED", voidBody.path("status").asText());
        assertEquals(2, voidBody.path("releasedQuantity").asInt());
        assertEquals(2, voidBody.path("version").asInt());
        assertEquals("贴错标签", voidBody.path("voidReason").asText());
        assertEquals(1, voidBody.path("sealedQuantity").asInt());
        assertEquals(2, voidBody.path("remainingQuantity").asInt());

        // 标签 100 已释放，可被新封箱复用
        seal(batchKey, "CK-SEAL-2", List.of(carton("CT-3", 100, 2)), 201);
        JsonNode recon = reconciliation(batchKey);
        assertEquals(3, recon.path("sealedQuantity").asInt());
        assertEquals(2, recon.path("activeCartonCount").asInt());
        assertEquals(1, recon.path("voidedCartonCount").asInt());
        assertTrue(recon.path("complete").asBoolean());

        // 历史稳定：作废封箱原始数量/标签/创建时间不改写，作废字段已固化
        MvcResult cartonsResult = mockMvc.perform(get("/api/batches/" + batchKey + "/cartons"))
                .andExpect(status().isOk()).andReturn();
        JsonNode cartons = read(cartonsResult);
        assertEquals(3, cartons.size());
        JsonNode voidedCarton = cartons.get(0);
        assertEquals("CT-1", voidedCarton.path("cartonKey").asText());
        assertEquals(100, voidedCarton.path("labelNo").asInt());
        assertEquals(2, voidedCarton.path("quantity").asInt());
        assertEquals("VOIDED", voidedCarton.path("status").asText());
        assertEquals(2, voidedCarton.path("version").asInt());
        assertEquals("贴错标签", voidedCarton.path("voidReason").asText());
        assertFalse(voidedCarton.path("voidedAt").isNull());

        // 重复作废 → 409；作废不存在的封箱 → 404
        voidCarton(batchKey, "CT-1", "CK-VOID-2", "再次作废", 409);
        voidCarton(batchKey, "CT-NOPE", "CK-VOID-3", "不存在", 404);
    }

    // ---------- 计划登记规则 ----------

    @Test
    void planRegistrationRules() throws Exception {
        // 批次不存在 → 404
        mockMvc.perform(post("/api/batches/NO-SUCH/pack-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("CK-P", 3, 100, 110)))
                .andExpect(status().isNotFound());

        String batchKey = "BK-PLAN-" + unique();
        createBatch(batchKey);
        // 号段非法（起点 >= 终点）→ 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/pack-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("CK-P1", 3, 110, 110)))
                .andExpect(status().isBadRequest());
        // 号段容量小于计划数量 → 422，含容量/要求/差额
        MvcResult small = mockMvc.perform(post("/api/batches/" + batchKey + "/pack-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("CK-P2", 5, 100, 103)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String message = read(small).path("message").asText();
        assertTrue(message.contains("容量=3"), message);
        assertTrue(message.contains("要求计划数量=5"), message);
        assertTrue(message.contains("差额=2"), message);
        // 失败不占键：同 commandKey 修正参数后成功
        registerPlan(batchKey, "CK-P2", 3, 100, 110, 201);
        // 重复登记 → 409
        registerPlan(batchKey, "CK-P3", 3, 200, 210, 409);
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_replaySameResult_changedParamsConflict_failureNotOccupying() throws Exception {
        String batchKey = "BK-IDEM-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 5, 100, 110, 201);

        String body = sealBody("CK-SEAL-1", List.of(carton("CT-1", 100, 1)));
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不新增封箱
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));

        // 同 commandKey 改参 → 409
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 2)), 409);

        // 失败不占键：越界 422 后同键修正参数成功
        seal(batchKey, "CK-SEAL-F", List.of(carton("CT-2", 999, 1)), 422);
        seal(batchKey, "CK-SEAL-F", List.of(carton("CT-2", 101, 1)), 201);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameLabelAcrossBatches_uniqueConstraintAllowsOnlyOne() throws Exception {
        String batchA = "BK-RACE-A-" + unique();
        String batchB = "BK-RACE-B-" + unique();
        createBatch(batchA);
        createBatch(batchB);
        registerPlan(batchA, "CK-PLAN-A", 3, 700, 710, 201);
        registerPlan(batchB, "CK-PLAN-B", 3, 700, 710, 201);

        // 两个批次并发占用同一标签：数据库唯一约束保证最多一次成功
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchA + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SA", List.of(carton("CT-A", 700, 1))))),
                () -> callStatus(post("/api/batches/" + batchB + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-SB", List.of(carton("CT-B", 700, 1))))));

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
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_usage WHERE label_no = 700", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));
    }

    @Test
    void concurrentSameBatchSameLabel_serializedByRowLock() throws Exception {
        String batchKey = "BK-RACE-1-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 3, 800, 810, 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-S1", List.of(carton("CT-1", 800, 1))))),
                () -> callStatus(post("/api/batches/" + batchKey + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody("CK-S2", List.of(carton("CT-2", 800, 1))))));

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
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_usage WHERE label_no = 800", Integer.class));
    }

    // ---------- 快照不可改写与诊断 ----------

    @Test
    void snapshotImmutableAfterRelease_andReadsDoNotChangeState() throws Exception {
        String batchKey = "BK-SNAP-" + unique();
        createBatch(batchKey);
        registerPlan(batchKey, "CK-PLAN-1", 2, 100, 110, 201);
        seal(batchKey, "CK-SEAL-1", List.of(carton("CT-1", 100, 2)), 201);
        passAllTestsAndApproveFirst(batchKey);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AP-2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode snap1 = reconciliation(batchKey).path("snapshot");
        assertFalse(snap1.isNull());
        // 已放行批次作废申请被拒，快照不改写
        voidCarton(batchKey, "CT-1", "CK-VOID-1", "试图作废", 409);
        JsonNode snap2 = reconciliation(batchKey).path("snapshot");
        assertEquals(snap1, snap2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_label_snapshot WHERE batch_key = ?",
                Integer.class, batchKey));
        // 快照封箱版本仍为放行时的 1
        assertEquals(1, snap2.path("cartonVersions").path("CT-1").asInt());
        // 无差异诊断
        assertTrue(reconciliation(batchKey).path("discrepancy").isNull());

        // 读取不改变状态：两次诊断结果一致，数据行数不变
        JsonNode recon1 = reconciliation(batchKey);
        JsonNode recon2 = reconciliation(batchKey);
        assertEquals(recon1, recon2);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM carton", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM label_usage", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey) throws Exception {
        String body = "{\"commandKey\":\"CK-CREATE-" + batchKey + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"外观\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String planBody(String commandKey, int planned, long start, long end) {
        return "{\"commandKey\":\"" + commandKey + "\",\"plannedQuantity\":" + planned
                + ",\"labelStart\":" + start + ",\"labelEnd\":" + end + "}";
    }

    private void registerPlan(String batchKey, String commandKey, int planned, long start,
                              long end, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/pack-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(commandKey, planned, start, end)))
                .andExpect(status().is(expected));
    }

    private String carton(String cartonKey, long labelNo, int quantity) {
        return "{\"cartonKey\":\"" + cartonKey + "\",\"labelNo\":" + labelNo
                + ",\"quantity\":" + quantity + "}";
    }

    private String sealBody(String commandKey, List<String> cartons) {
        return "{\"commandKey\":\"" + commandKey + "\",\"cartons\":["
                + String.join(",", cartons) + "]}";
    }

    private MvcResult seal(String batchKey, String commandKey, List<String> cartons, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/cartons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sealBody(commandKey, cartons)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult voidCarton(String batchKey, String cartonKey, String commandKey,
                                 String reason, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/cartons/" + cartonKey + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void passAllTestsAndApproveFirst(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique()
                                + "\",\"testKey\":\"TK-1\",\"testItem\":\"外观\","
                                + "\"result\":\"PASS\",\"inspector\":\"insp-1\"}"))
                .andExpect(status().isCreated());
        approve(batchKey, "qa-1", "QUALITY", "CK-AP-1", 201);
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void recall(String batchKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"客户投诉\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode reconciliation(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/pack-reconciliation"))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> toLongList(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asLong()));
        return values;
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
