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
 * 耦合公差规范端到端测试（真实 H2 事务）：规范版本只追加、测量向量整体判定、
 * 显式换版、批准版本校验与放行冻结、全局 requestId 幂等与并发边界。
 *
 * <p>合成规范：x≤10、y≤10 且 x+y≤15——x=8、y=8 单项合格但整体 FAIL；x=7、y=8 可 PASS。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchSpecFlowTest {

    private static final String XY_FEATURES = "[\"x\",\"y\"]";
    private static final String XY_CONSTRAINTS = "[{\"coefficients\":[1,0],\"bound\":10},"
            + "{\"coefficients\":[0,1],\"bound\":10},{\"coefficients\":[1,1],\"bound\":15}]";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_freeze");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM spec_version");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 规范定义与版本 ----------

    @Test
    void specCreate_appendAndValidation() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, "RID-C1", XY_FEATURES, XY_CONSTRAINTS, 201);
        // 重复创建同 specKey → 409
        createSpec(specKey, "RID-C2", XY_FEATURES, XY_CONSTRAINTS, 409);
        // 追加版本 → 2，再追加 → 3
        appendSpec(specKey, "RID-C3", XY_FEATURES,
                "[{\"coefficients\":[1,0],\"bound\":5},{\"coefficients\":[0,1],\"bound\":5}]", 201);
        appendSpec(specKey, "RID-C4", XY_FEATURES, XY_CONSTRAINTS, 201);
        // 不存在的规范追加 → 404
        appendSpec("SP-NONE-" + unique(), "RID-C5", XY_FEATURES, XY_CONSTRAINTS, 404);

        MvcResult v2 = mockMvc.perform(get("/api/specs/" + specKey + "/versions/2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(v2.getResponse().getContentAsString());
        assertEquals(2, node.path("version").asInt());
        assertEquals(5, node.path("constraints").get(0).path("bound").asInt());
        mockMvc.perform(get("/api/specs/" + specKey + "/versions/9"))
                .andExpect(status().isNotFound());

        // 非法定义：特性数越界、重复特性、系数全零、系数超范围、约束数越界、系数个数不符
        createSpec("SP-B1-" + unique(), "RID-B1", "[\"x\"]", XY_CONSTRAINTS, 400);
        createSpec("SP-B2-" + unique(), "RID-B2", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]",
                XY_CONSTRAINTS, 400);
        createSpec("SP-B3-" + unique(), "RID-B3", "[\"x\",\"x\"]", XY_CONSTRAINTS, 400);
        createSpec("SP-B4-" + unique(), "RID-B4", XY_FEATURES,
                "[{\"coefficients\":[0,0],\"bound\":10}]", 400);
        createSpec("SP-B5-" + unique(), "RID-B5", XY_FEATURES,
                "[{\"coefficients\":[101,0],\"bound\":10}]", 400);
        createSpec("SP-B6-" + unique(), "RID-B6", XY_FEATURES, "[]", 400);
        createSpec("SP-B7-" + unique(), "RID-B7", XY_FEATURES,
                "[{\"coefficients\":[1,0],\"bound\":10},{\"coefficients\":[0,1],\"bound\":10},"
                        + "{\"coefficients\":[1,1],\"bound\":15},{\"coefficients\":[2,0],\"bound\":20},"
                        + "{\"coefficients\":[0,2],\"bound\":20},{\"coefficients\":[1,-1],\"bound\":5},"
                        + "{\"coefficients\":[-1,1],\"bound\":5},{\"coefficients\":[3,3],\"bound\":30},"
                        + "{\"coefficients\":[1,2],\"bound\":25}]", 400);
        createSpec("SP-B8-" + unique(), "RID-B8", XY_FEATURES,
                "[{\"coefficients\":[1],\"bound\":10}]", 400);
        // 缺 operator / requestId → 400
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-B9\",\"specKey\":\"SP-B9\",\"features\":"
                                + XY_FEATURES + ",\"constraints\":" + XY_CONSTRAINTS + "}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, countRows("spec_version", "spec_key", "SP-B9"));
    }

    // ---------- 题干合成示例 ----------

    @Test
    void coupledTolerance_singleItemsPassButOverallFails() throws Exception {
        String specKey = "SP-XY-" + unique();
        createSpec(specKey, "RID-XY-1", XY_FEATURES, XY_CONSTRAINTS, 201);

        // x=8、y=8：单项 x≤10、y≤10 合格，但 x+y=16>15 整体 FAIL → REJECTED
        String failing = "BK-XY-F-" + unique();
        createBoundBatch(failing, specKey, 1, 201);
        submitTest(failing, "t1", "PASS", "insp-1", 201);
        assertEquals("QUARANTINED", currentStatus(failing), "向量未 PASS 前不得进入待放行");
        MvcResult failResult = measure(failing, "RID-M1", 1, "{\"x\":8,\"y\":8}", 201);
        JsonNode failNode = objectMapper.readTree(failResult.getResponse().getContentAsString());
        assertEquals("FAIL", failNode.path("outcome").asText());
        JsonNode violated = failNode.path("violatedConstraints");
        assertEquals(1, violated.size());
        assertEquals(3, violated.get(0).asInt(), "仅第 3 条 x+y≤15 越界");
        assertEquals("REJECTED", currentStatus(failing));
        // FAIL 已留检验记录
        assertEquals(1, countRows("measurement", "batch_key", failing));

        // x=7、y=8：全部约束满足 → PASS → 可继续原批准流程直至 RELEASED
        String passing = "BK-XY-P-" + unique();
        createBoundBatch(passing, specKey, 1, 201);
        submitTest(passing, "t1", "PASS", "insp-2", 201);
        MvcResult passResult = measure(passing, "RID-M2", 1, "{\"x\":7,\"y\":8}", 201);
        assertEquals("PASS", objectMapper.readTree(passResult.getResponse().getContentAsString())
                .path("outcome").asText());
        assertEquals("PENDING_RELEASE", currentStatus(passing));
        approve(passing, "qa-1", "QUALITY", "CK-XY-A1", 1, 201);
        approve(passing, "ops-1", "OPERATIONS", "CK-XY-A2", 1, 201);
        assertEquals("RELEASED", currentStatus(passing));

        // 最终批准冻结完整系数、上界与向量
        String constraintsDef = jdbc.queryForObject(
                "SELECT constraints_def FROM release_freeze WHERE batch_key = ?",
                String.class, passing);
        String vector = jdbc.queryForObject(
                "SELECT vector FROM release_freeze WHERE batch_key = ?",
                String.class, passing);
        JsonNode frozenConstraints = objectMapper.readTree(constraintsDef);
        assertEquals(3, frozenConstraints.size());
        assertEquals(15, frozenConstraints.get(2).path("bound").asInt());
        assertEquals(1, frozenConstraints.get(2).path("coefficients").get(0).asInt());
        JsonNode frozenVector = objectMapper.readTree(vector);
        assertEquals(0, frozenVector.path("x").decimalValue()
                .compareTo(new java.math.BigDecimal("7")));
        assertEquals(0, frozenVector.path("y").decimalValue()
                .compareTo(new java.math.BigDecimal("8")));
    }

    @Test
    void equalityBoundary_exactDecimalPasses() throws Exception {
        String specKey = "SP-EQ-" + unique();
        createSpec(specKey, "RID-EQ-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-EQ-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        // x=10、y=5：x=10 等号合格，x+y=15 等号合格 → PASS
        MvcResult result = measure(batchKey, "RID-EQ-M", 1, "{\"x\":10.000000,\"y\":5}", 201);
        assertEquals("PASS", objectMapper.readTree(result.getResponse().getContentAsString())
                .path("outcome").asText());
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 400 不留检验 ----------

    @Test
    void missingFeatureAndOverPrecision_return400WithoutRecord() throws Exception {
        String specKey = "SP-V-" + unique();
        createSpec(specKey, "RID-V-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-V-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);

        // 缺特性 → 400，不留检验
        measure(batchKey, "RID-V-M1", 1, "{\"x\":7}", 400);
        // 多特性 → 400
        measure(batchKey, "RID-V-M2", 1, "{\"x\":7,\"y\":8,\"z\":1}", 400);
        // 超精度（7 位小数）→ 400
        measure(batchKey, "RID-V-M3", 1, "{\"x\":7.1234567,\"y\":8}", 400);
        assertEquals(0, countRows("measurement", "batch_key", batchKey), "400 不得留下检验记录");
        assertEquals("QUARANTINED", currentStatus(batchKey));

        // 失败不占键：同一 requestId 修正参数后可成功
        measure(batchKey, "RID-V-M3", 1, "{\"x\":7.123456,\"y\":8}", 201);
        assertEquals(1, countRows("measurement", "batch_key", batchKey));
    }

    @Test
    void legalVectorOutOfBounds_recordsFailAndRejects() throws Exception {
        String specKey = "SP-OOB-" + unique();
        createSpec(specKey, "RID-O-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-OOB-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        // x=11 合法精度但越界 → 记录 FAIL 并进入 REJECTED
        measure(batchKey, "RID-O-M1", 1, "{\"x\":11,\"y\":1}", 201);
        assertEquals("REJECTED", currentStatus(batchKey));
        assertEquals(1, countRows("measurement", "batch_key", batchKey));
        // REJECTED 后不得再提交向量
        measure(batchKey, "RID-O-M2", 1, "{\"x\":1,\"y\":1}", 409);
    }

    @Test
    void unboundBatch_rejectsMeasurementAndVersionedApproval() throws Exception {
        String batchKey = "BK-UNB-" + unique();
        String body = "{\"commandKey\":\"CK-U-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"p\",\"batchNo\":\"n\","
                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        measure(batchKey, "RID-U-M1", 1, "{\"x\":1,\"y\":1}", 422);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-U-A1", 1, 400);
        approve(batchKey, "qa", "QUALITY", "CK-U-A2", null, 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
    }

    // ---------- 显式换版 ----------

    @Test
    void rebindSpec_oldVectorRetainedButNotReleaseEvidence() throws Exception {
        String specKey = "SP-RB-" + unique();
        createSpec(specKey, "RID-RB-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        // v2 更严：x+y≤10
        appendSpec(specKey, "RID-RB-2", XY_FEATURES,
                "[{\"coefficients\":[1,0],\"bound\":10},{\"coefficients\":[0,1],\"bound\":10},"
                        + "{\"coefficients\":[1,1],\"bound\":10}]", 201);

        String batchKey = "BK-RB-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        measure(batchKey, "RID-RB-M1", 1, "{\"x\":7,\"y\":8}", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 批准共同校验绑定版本：缺 expectedVersion → 400；与当前绑定版本不符 → 409
        approve(batchKey, "qa", "QUALITY", "CK-RB-A0", null, 400);
        approve(batchKey, "qa", "QUALITY", "CK-RB-A1", 2, 409);
        assertEquals(0, countRows("approval", "batch_key", batchKey));

        // 期望版本不符 → 409；目标版本不存在 → 404；同版本 → 409
        rebind(batchKey, "RID-RB-R1", 2, 2, 409);
        rebind(batchKey, "RID-RB-R2", 1, 9, 404);
        rebind(batchKey, "RID-RB-R3", 1, 1, 409);
        assertEquals(1, currentSpecVersion(batchKey));

        // 正确换版：旧向量保留但不再作为放行依据，状态回退 QUARANTINED
        rebind(batchKey, "RID-RB-R4", 1, 2, 201);
        assertEquals(2, currentSpecVersion(batchKey));
        assertEquals("QUARANTINED", currentStatus(batchKey));
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode measurements = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("measurements");
        assertEquals(1, measurements.size(), "旧向量与结论保留");
        assertEquals(1, measurements.get(0).path("specVersion").asInt());
        assertEquals("PASS", measurements.get(0).path("outcome").asText());

        // 旧版本证据不能拼接：按旧版提交向量 409；换版后批次回退隔离，批准一律被状态门禁拦截
        measure(batchKey, "RID-RB-M2", 1, "{\"x\":7,\"y\":8}", 409);
        approve(batchKey, "qa", "QUALITY", "CK-RB-A2", 1, 422);
        approve(batchKey, "qa", "QUALITY", "CK-RB-A3", 2, 422);
        assertEquals("QUARANTINED", currentStatus(batchKey));

        // 新版本下 x=7、y=8 越界（x+y=15>10）→ FAIL → REJECTED
        measure(batchKey, "RID-RB-M3", 2, "{\"x\":7,\"y\":8}", 201);
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    @Test
    void rebindSpec_blockedAfterApprovalOrRelease() throws Exception {
        String specKey = "SP-RB2-" + unique();
        createSpec(specKey, "RID-R2-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        appendSpec(specKey, "RID-R2-2", XY_FEATURES, XY_CONSTRAINTS, 201);

        String batchKey = "BK-RB2-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        measure(batchKey, "RID-R2-M1", 1, "{\"x\":7,\"y\":8}", 201);
        approve(batchKey, "qa", "QUALITY", "CK-R2-A1", 1, 201);
        // 已有批准 → 换版 409
        rebind(batchKey, "RID-R2-R1", 1, 2, 409);
        approve(batchKey, "ops", "OPERATIONS", "CK-R2-A2", 1, 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 已放行 → 换版 409
        rebind(batchKey, "RID-R2-R2", 1, 2, 409);
        assertEquals(1, currentSpecVersion(batchKey));
    }

    // ---------- 拆分继承 ----------

    @Test
    void split_childrenInheritSpecVersionButNotMeasurements() throws Exception {
        String specKey = "SP-SP-" + unique();
        createSpec(specKey, "RID-SP-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String parent = "BK-SP-P-" + unique();
        createBoundBatch(parent, specKey, 1, 201);
        submitTest(parent, "t1", "PASS", "insp", 201);
        measure(parent, "RID-SP-M1", 1, "{\"x\":7,\"y\":8}", 201);
        approve(parent, "qa", "QUALITY", "CK-SP-A1", 1, 201);
        approve(parent, "ops", "OPERATIONS", "CK-SP-A2", 1, 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child = "BK-SP-C-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SP-S1\",\"children\":["
                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"c1\"},"
                + "{\"batchKey\":\"" + child + "-2\",\"batchNo\":\"c2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        // 子批继承规范版本但不继承测量向量：需重新检验
        assertEquals(1, currentSpecVersion(child));
        assertEquals(0, countRows("measurement", "batch_key", child));
        submitTest(child, "t1", "PASS", "insp-c", 201);
        assertEquals("QUARANTINED", currentStatus(child), "子批未继承父批向量，不得直接待放行");
        measure(child, "RID-SP-M2", 1, "{\"x\":7,\"y\":8}", 201);
        assertEquals("PENDING_RELEASE", currentStatus(child));
    }

    // ---------- 幂等 ----------

    @Test
    void measurementIdempotency_replayConflictAndDecimalEquivalence() throws Exception {
        String specKey = "SP-ID-" + unique();
        createSpec(specKey, "RID-ID-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-ID-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);

        MvcResult first = measure(batchKey, "RID-ID-M1", 1, "{\"x\":7.5,\"y\":8}", 201);
        // 同 requestId 同值不同表示（7.50 与 7.5 同值同参）→ 重放首次响应，不新增记录
        MvcResult replay = measure(batchKey, "RID-ID-M1", 1, "{\"x\":7.50,\"y\":8.00}", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countRows("measurement", "batch_key", batchKey));
        // 同 requestId 改参 → 409
        measure(batchKey, "RID-ID-M1", 1, "{\"x\":7.5,\"y\":9}", 409);
        assertEquals(1, countRows("measurement", "batch_key", batchKey));

        // 全局 requestId：同一键跨操作改参 → 409
        createSpec("SP-ID2-" + unique(), "RID-ID-M1", XY_FEATURES, XY_CONSTRAINTS, 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameRequestId_measurement_singleRecordSameResponse() throws Exception {
        String specKey = "SP-CC-" + unique();
        createSpec(specKey, "RID-CC-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-CC-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);

        String body = "{\"requestId\":\"RID-CC-M1\",\"operator\":\"op-m\","
                + "\"expectedVersion\":1,\"values\":{\"x\":7,\"y\":8}}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, countRows("measurement", "batch_key", batchKey),
                "并发同键不得产生重复检验或暴露唯一键错误");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    @Test
    void concurrentFailAndPassMeasurements_neverReleaseRejected() throws Exception {
        String specKey = "SP-CF-" + unique();
        createSpec(specKey, "RID-CF-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-CF-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);

        String passBody = "{\"requestId\":\"RID-CF-P\",\"operator\":\"op-m\","
                + "\"expectedVersion\":1,\"values\":{\"x\":7,\"y\":8}}";
        String failBody = "{\"requestId\":\"RID-CF-F\",\"operator\":\"op-m\","
                + "\"expectedVersion\":1,\"values\":{\"x\":8,\"y\":8}}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(passBody)),
                () -> callStatus(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(failBody)));

        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // FAIL 先提交则 PASS 请求看到 REJECTED 得 409；PASS 先提交则 FAIL 仍记录并拒绝批次
        assertTrue((first == 201 && second == 201) || (first == 409 && second == 201)
                || (first == 201 && second == 409), "意外状态码: " + first + "/" + second);
        assertEquals("REJECTED", currentStatus(batchKey));
        Integer fails = jdbc.queryForObject("SELECT COUNT(*) FROM measurement"
                + " WHERE batch_key = ? AND outcome = 'FAIL'", Integer.class, batchKey);
        assertEquals(1, fails, "FAIL 向量必须恰好记录一次");
        approve(batchKey, "qa", "QUALITY", "CK-CF-A1", 1, 409);
    }

    @Test
    void concurrentAppendVersions_uniqueVersionAssignment() throws Exception {
        String specKey = "SP-CA-" + unique();
        createSpec(specKey, "RID-CA-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String appendBody = "{\"requestId\":\"RID-CA-2\",\"operator\":\"op-spec\","
                + "\"features\":" + XY_FEATURES + ",\"constraints\":" + XY_CONSTRAINTS + "}";
        String appendBody2 = "{\"requestId\":\"RID-CA-3\",\"operator\":\"op-spec\","
                + "\"features\":" + XY_FEATURES + ",\"constraints\":" + XY_CONSTRAINTS + "}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/specs/" + specKey + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(appendBody)),
                () -> callStatus(post("/api/specs/" + specKey + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(appendBody2)));
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(201, results.get(1).get(30, TimeUnit.SECONDS));
        Integer max = jdbc.queryForObject("SELECT MAX(version) FROM spec_version WHERE spec_key = ?",
                Integer.class, specKey);
        assertEquals(3, max, "并发追加应串行分配版本 2 与 3，不暴露唯一键错误");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM spec_version WHERE spec_key = ?",
                Integer.class, specKey);
        assertEquals(3, count);
    }

    @Test
    void freezeSnapshot_survivesLaterSpecVersions() throws Exception {
        String specKey = "SP-FZ-" + unique();
        createSpec(specKey, "RID-FZ-1", XY_FEATURES, XY_CONSTRAINTS, 201);
        String batchKey = "BK-FZ-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        measure(batchKey, "RID-FZ-M1", 1, "{\"x\":7,\"y\":8}", 201);
        approve(batchKey, "qa", "QUALITY", "CK-FZ-A1", 1, 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-FZ-A2", 1, 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行后规范继续追加新版本：冻结快照保持 v1 的系数、上界与向量不变
        appendSpec(specKey, "RID-FZ-2", XY_FEATURES,
                "[{\"coefficients\":[1,1],\"bound\":1}]", 201);
        Integer frozenVersion = jdbc.queryForObject(
                "SELECT spec_version FROM release_freeze WHERE batch_key = ?",
                Integer.class, batchKey);
        assertEquals(1, frozenVersion);
        String constraintsDef = jdbc.queryForObject(
                "SELECT constraints_def FROM release_freeze WHERE batch_key = ?",
                String.class, batchKey);
        JsonNode frozen = objectMapper.readTree(constraintsDef);
        assertEquals(3, frozen.size(), "冻结保留 v1 全部 3 条约束");
        assertEquals(15, frozen.get(2).path("bound").asInt());
        String vector = jdbc.queryForObject(
                "SELECT vector FROM release_freeze WHERE batch_key = ?",
                String.class, batchKey);
        assertEquals(0, objectMapper.readTree(vector).path("x").decimalValue()
                .compareTo(new java.math.BigDecimal("7")));
    }

    // ---------- helpers（第一部分） ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createSpec(String specKey, String requestId, String features, String constraints,
                            int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-spec\","
                + "\"specKey\":\"" + specKey + "\",\"features\":" + features
                + ",\"constraints\":" + constraints + "}";
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void appendSpec(String specKey, String requestId, String features, String constraints,
                            int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-spec\","
                + "\"features\":" + features + ",\"constraints\":" + constraints + "}";
        mockMvc.perform(post("/api/specs/" + specKey + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void createBoundBatch(String batchKey, String specKey, int specVersion, int expected)
            throws Exception {
        String body = "{\"commandKey\":\"CK-B-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"p\",\"batchNo\":\"n\","
                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                + "\"specKey\":\"" + specKey + "\",\"specVersion\":" + specVersion + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                + "\",\"inspector\":\"" + inspector + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult measure(String batchKey, String requestId, int expectedVersion,
                              String valuesJson, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-m\","
                + "\"expectedVersion\":" + expectedVersion + ",\"values\":" + valuesJson + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         Integer expectedVersion, int expected) throws Exception {
        String versionPart = expectedVersion == null ? ""
                : ",\"expectedVersion\":" + expectedVersion;
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"" + versionPart + "}"))
                .andExpect(status().is(expected));
    }

    private MvcResult rebind(String batchKey, String requestId, int expectedVersion,
                             int newVersion, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-r\","
                + "\"expectedVersion\":" + expectedVersion + ",\"newVersion\":" + newVersion + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/spec-version")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int currentSpecVersion(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("specVersion").asInt();
    }

    private int countRows(String table, String column, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return count == null ? 0 : count;
    }

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

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
