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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 特性规范端到端测试（真实 H2 事务）：规范版本只追加、测量向量整体求值、
 * 换版门禁、requestId 幂等与并发边界。
 * 示例规范：x≤10、y≤10、x+y≤15 —— x=8、y=8 单项合格但整体 FAIL；x=7、y=8 可 PASS。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpecFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_freeze");
        jdbc.update("DELETE FROM spec_measurement");
        jdbc.update("DELETE FROM spec_constraint_coeff");
        jdbc.update("DELETE FROM spec_constraint");
        jdbc.update("DELETE FROM spec_feature");
        jdbc.update("DELETE FROM spec_version");
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
    void happyFlow_passVectorThenDualApproval_freezesSpecAndVector() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);

        // 必做检验 PASS 但无测量向量：仍在隔离，不能批准
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-EARLY", 422);

        // x=7、y=8：x+y=15 等号合格 → PASS，进入待放行
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 最终批准冻结完整系数、上界与向量
        Integer freezes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze WHERE batch_key = ? AND spec_version = 1",
                Integer.class, batchKey);
        assertEquals(1, freezes);
        String constraintsJson = jdbc.queryForObject(
                "SELECT constraints_json FROM release_freeze WHERE batch_key = ?",
                String.class, batchKey);
        assertTrue(constraintsJson.contains("\"upperBound\":15"), constraintsJson);
        String vectorJson = jdbc.queryForObject(
                "SELECT vector_json FROM release_freeze WHERE batch_key = ?",
                String.class, batchKey);
        assertTrue(vectorJson.contains("7") && vectorJson.contains("8"), vectorJson);
    }

    @Test
    void coupledFail_individuallyOkButSumExceeded_rejected() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);

        // x=8、y=8：单项 x≤10、y≤10 均合格，x+y=16>15 → FAIL 且批次 REJECTED
        MvcResult result = submitMeasurement(batchKey, "M-1", "[8, 8]", "RID-M1", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("FAIL", body.path("outcome").asText());
        assertEquals("REJECTED", body.path("batchStatus").asText());
        assertEquals("REJECTED", currentStatus(batchKey));

        // 拒绝后不能再提交测量、不能批准
        submitMeasurement(batchKey, "M-2", "[7, 8]", "RID-M2", 409);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 409);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_measurement WHERE batch_key = ?", Integer.class, batchKey);
        assertEquals(1, rows);
    }

    @Test
    void equalBoundary_andPrecision_passesExactly() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        // x=10、y=5：x=10 等号、x+y=15 等号 → PASS
        submitMeasurement(batchKey, "M-1", "[10, 5]", "RID-M1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 400 分支：缺特性 / 超精度，不留检验、不占键 ----------

    @Test
    void missingFeature_returns400_leavesNoRecord_andKeyReusable() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);

        // 缺特性：只给 1 个值 → 400，不留检验记录
        submitMeasurement(batchKey, "M-1", "[7]", "RID-M1", 400);
        assertEquals(0, countRows("spec_measurement", batchKey));
        // 失败不占键：同 requestId 改正参数后成功
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 201);
        assertEquals(1, countRows("spec_measurement", batchKey));
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    @Test
    void overPrecision_returns400_leavesNoRecord() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);

        // 7 位小数 → 400；6 位小数合法
        submitMeasurement(batchKey, "M-1", "[7.0000001, 8]", "RID-M1", 400);
        assertEquals(0, countRows("spec_measurement", batchKey));
        submitMeasurement(batchKey, "M-1", "[7.000001, 8]", "RID-M1", 201);
        // 7.000001 + 8 = 15.000001 > 15 → FAIL → REJECTED（精确判断，不依赖显示舍入）
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    @Test
    void unboundBatch_submitMeasurement_returns422() throws Exception {
        String batchKey = "BK-NOSPEC-" + unique();
        String body = "{\"commandKey\":\"CK-1\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"P\",\"batchNo\":\"N\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 422);
        assertEquals(0, countRows("spec_measurement", batchKey));
    }

    // ---------- 规范版本只追加与参数校验 ----------

    @Test
    void specVersioning_appendOnly() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        // 同版本重复（不同 requestId）→ 409；旧版本 → 409；更大版本 → 201
        createSpec(specKey, 1, "RID-S2", 409);
        createSpec(specKey, 2, "RID-S3", 201);
        createSpec(specKey, 2, "RID-S4", 409);
        createSpec(specKey, 1, "RID-S5", 409);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_version WHERE spec_key = ?", Integer.class, specKey);
        assertEquals(2, versions);
    }

    @Test
    void specValidation_rejectsIllegalDefinitions() throws Exception {
        String specKey = "SP-" + unique();
        // 系数全零
        String allZero = "{\"requestId\":\"R1\",\"operator\":\"o\",\"specKey\":\"" + specKey
                + "\",\"version\":1,\"features\":[\"x\",\"y\"],"
                + "\"constraints\":[{\"coefficients\":[0,0],\"upperBound\":10}]}";
        postSpec(allZero, 400);
        // 系数超范围（101）
        String tooBig = "{\"requestId\":\"R2\",\"operator\":\"o\",\"specKey\":\"" + specKey
                + "\",\"version\":1,\"features\":[\"x\",\"y\"],"
                + "\"constraints\":[{\"coefficients\":[101,0],\"upperBound\":10}]}";
        postSpec(tooBig, 400);
        // 系数数量与特性数不一致
        String mismatched = "{\"requestId\":\"R3\",\"operator\":\"o\",\"specKey\":\"" + specKey
                + "\",\"version\":1,\"features\":[\"x\",\"y\"],"
                + "\"constraints\":[{\"coefficients\":[1],\"upperBound\":10}]}";
        postSpec(mismatched, 400);
        // 特性 1 个 / 约束 0 条 / 约束 9 条
        postSpec("{\"requestId\":\"R4\",\"operator\":\"o\",\"specKey\":\"" + specKey
                + "\",\"version\":1,\"features\":[\"x\"],"
                + "\"constraints\":[{\"coefficients\":[1],\"upperBound\":10}]}", 400);
        postSpec("{\"requestId\":\"R5\",\"operator\":\"o\",\"specKey\":\"" + specKey
                + "\",\"version\":1,\"features\":[\"x\",\"y\"],\"constraints\":[]}", 400);
        StringBuilder nine = new StringBuilder("{\"requestId\":\"R6\",\"operator\":\"o\","
                + "\"specKey\":\"" + specKey + "\",\"version\":1,\"features\":[\"x\",\"y\"],"
                + "\"constraints\":[");
        for (int i = 0; i < 9; i++) {
            nine.append(i == 0 ? "" : ",").append("{\"coefficients\":[1,0],\"upperBound\":10}");
        }
        postSpec(nine + "]}", 400);
        // 全部失败不占键、不留版本
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_version WHERE spec_key = ?", Integer.class, specKey)
                .intValue());
    }

    // ---------- 换版 ----------

    @Test
    void rebind_unreleasedBatchWithoutApprovals_oldVectorKeptButNotReleaseBasis() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        // v2：x+y≤14（更严）
        String v2 = "{\"requestId\":\"RID-S2\",\"operator\":\"spec-admin\",\"specKey\":\""
                + specKey + "\",\"version\":2,\"features\":[\"x\",\"y\"],"
                + "\"constraints\":[{\"coefficients\":[1,0],\"upperBound\":10},"
                + "{\"coefficients\":[0,1],\"upperBound\":10},"
                + "{\"coefficients\":[1,1],\"upperBound\":14}]}";
        postSpec(v2, 201);

        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // expectedVersion 不符 → 409
        rebind(batchKey, 2, 2, "RID-RB-WRONG", 409);
        // 换版成功：旧向量保留但不再作为放行依据，状态回退 QUARANTINED
        rebind(batchKey, 1, 2, "RID-RB-1", 201);
        assertEquals("QUARANTINED", currentStatus(batchKey));
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(1, node.path("measurements").size());
        assertEquals(1, node.path("measurements").get(0).path("specVersion").asInt());
        assertEquals(2, node.path("batch").path("specVersion").asInt());
        // 新旧版本单项值不能拼成一次证据：v2 需重新提交完整向量
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);
        submitMeasurement(batchKey, "M-2", "[7, 7]", "RID-M2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-A2", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze WHERE batch_key = ? AND spec_version = 2",
                Integer.class, batchKey).intValue());
    }

    @Test
    void rebind_guards() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        createSpec(specKey, 2, "RID-S2", 201);

        // 已有批准 → 409
        String approved = "BK-RB-A-" + unique();
        createBoundBatch(approved, specKey, 1, 201);
        submitTest(approved, "t1", "PASS", "insp-1", 201);
        submitMeasurement(approved, "M-1", "[7, 8]", "RID-M1", 201);
        approve(approved, "qa-1", "QUALITY", "CK-A1", 201);
        rebind(approved, 1, 2, "RID-RB-A", 409);

        // 已放行 → 409
        approve(approved, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(approved));
        rebind(approved, 1, 2, "RID-RB-B", 409);

        // 未绑定规范 → 422；新版本相同 → 400；目标版本不存在 → 422
        String unbound = "BK-RB-U-" + unique();
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-1\",\"batchKey\":\"" + unbound
                                + "\",\"productCode\":\"P\",\"batchNo\":\"N\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().isCreated());
        rebind(unbound, 1, 2, "RID-RB-C", 422);
        String bound = "BK-RB-B-" + unique();
        createBoundBatch(bound, specKey, 1, 201);
        rebind(bound, 1, 1, "RID-RB-D", 400);
        rebind(bound, 1, 99, "RID-RB-E", 422);
    }

    // ---------- 幂等 ----------

    @Test
    void requestId_replaysFirstResponse_andDecimalSameValueSameParams() throws Exception {
        String specKey = "SP-" + unique();
        String body = specBody(specKey, 1, "RID-IDEM");
        MvcResult first = mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同 requestId 同参重放 → 首次完整响应
        MvcResult replay = mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 十进制同值同参：upperBound 10.0 与 10 视为相同 → 仍为重放
        String sameValue = specBody(specKey, 1, "RID-IDEM").replace("\"upperBound\":10",
                "\"upperBound\":10.0");
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content(sameValue))
                .andExpect(status().isCreated());
        // 同 requestId 改参（上界 16）→ 409
        String changed = specBody(specKey, 1, "RID-IDEM").replace("\"upperBound\":15",
                "\"upperBound\":16");
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_version WHERE spec_key = ?", Integer.class, specKey)
                .intValue());
    }

    @Test
    void measurementKeyAndRequestId_idempotency() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);

        MvcResult first = submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 201);
        // 同 requestId 同参（7.00 与 7 同值）→ 重放首次响应
        MvcResult replay = submitMeasurement(batchKey, "M-1", "[7.00, 8.0]", "RID-M1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 requestId 改参 → 409
        submitMeasurement(batchKey, "M-1", "[6, 8]", "RID-M1", 409);
        // 不同 requestId、同 measurementKey 同内容 → 返回原结果（200）
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1-OTHER", 200);
        // 同 measurementKey 不同内容 → 409
        submitMeasurement(batchKey, "M-1", "[8, 8]", "RID-M1-OTHER2", 409);
        assertEquals(1, countRows("spec_measurement", batchKey));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameRequestId_createSpec_singleVersionRow() throws Exception {
        String specKey = "SP-" + unique();
        String body = specBody(specKey, 1, "RID-RACE");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content(body)),
                () -> callStatus(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_version WHERE spec_key = ?", Integer.class, specKey)
                .intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CREATE_SPEC'",
                Integer.class).intValue());
    }

    @Test
    void concurrentMeasurementAndRebind_serialized_noUniqueKeyLeak() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        createSpec(specKey, 2, "RID-S2", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);

        // 向量 [7,7] 在 v1（x+y≤15）与 v2（x+y≤15，同定义）下均 PASS
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-M1\",\"operator\":\"m\","
                                + "\"measurementKey\":\"M-1\",\"values\":[7,7]}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/spec-rebind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-RB1\",\"operator\":\"m\","
                                + "\"expectedVersion\":1,\"newVersion\":2}")));
        int measure = results.get(0).get(30, TimeUnit.SECONDS);
        int rebind = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, measure);
        assertEquals(201, rebind);
        // 行锁串行化：测量只记录一次，批次最终绑定 v2
        assertEquals(1, countRows("spec_measurement", batchKey));
        assertEquals(2, jdbc.queryForObject(
                "SELECT spec_version FROM batch WHERE batch_key = ?", Integer.class, batchKey)
                .intValue());
        String finalStatus = currentStatus(batchKey);
        assertTrue(finalStatus.equals("PENDING_RELEASE") || finalStatus.equals("QUARANTINED"),
                "测量先于换版则回退 QUARANTINED，换版先于测量则 PENDING_RELEASE，实际: " + finalStatus);
    }

    @Test
    void concurrentDualApprovals_singleFreezeRow() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String batchKey = "BK-SP-" + unique();
        createBoundBatch(batchKey, specKey, 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        submitMeasurement(batchKey, "M-1", "[7, 8]", "RID-M1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CA2\"}")));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(2, countRows("approval", batchKey));
        // 最终批准只发生一次：冻结记录唯一（uk_freeze_batch 实际约束）
        assertEquals(1, countRows("release_freeze", batchKey));
    }

    // ---------- 拆分继承与祖先召回门禁 ----------

    @Test
    void split_childrenInheritSpecVersion_butNotMeasurements() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String parent = "BK-P-" + unique();
        createBoundBatch(parent, specKey, 1, 201);
        submitTest(parent, "t1", "PASS", "insp-1", 201);
        submitMeasurement(parent, "M-1", "[7, 8]", "RID-M1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child1 = "BK-C1-" + unique();
        String child2 = "BK-C2-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"N1\"},"
                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"N2\"}]}";
        MvcResult splitResult = mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated()).andReturn();
        JsonNode children = objectMapper.readTree(splitResult.getResponse().getContentAsString())
                .path("children");
        assertEquals(specKey, children.get(0).path("specKey").asText());
        assertEquals(1, children.get(0).path("specVersion").asInt());

        // 子批不继承测量：直接批准 422；重新提交向量 PASS 后可放行
        assertEquals(0, countRows("spec_measurement", child1));
        submitTest(child1, "t1", "PASS", "insp-2", 201);
        approve(child1, "qa-9", "QUALITY", "CK-C1-A", 422);
        submitMeasurement(child1, "M-C1", "[7, 8]", "RID-M-C1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(child1));
        approve(child1, "qa-9", "QUALITY", "CK-C1-B", 201);
    }

    @Test
    void recalledAncestor_blocksMeasurement() throws Exception {
        String specKey = "SP-" + unique();
        createSpec(specKey, 1, "RID-S1", 201);
        String parent = "BK-P-" + unique();
        createBoundBatch(parent, specKey, 1, 201);
        submitTest(parent, "t1", "PASS", "insp-1", 201);
        submitMeasurement(parent, "M-1", "[7, 8]", "RID-M1", 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        String child = "BK-C-" + unique();
        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"N1\"},"
                + "{\"batchKey\":\"BK-C2-" + unique() + "\",\"batchNo\":\"N2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC\",\"reason\":\"质量风险\"}"))
                .andExpect(status().isCreated());
        // 祖先已召回：子批禁止提交测量向量
        submitMeasurement(child, "M-C", "[7, 8]", "RID-M-C", 422);
        assertEquals(0, countRows("spec_measurement", child));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 示例规范请求体：features [x,y]；约束 x≤10、y≤10、x+y≤15。
     */
    private String specBody(String specKey, int version, String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"spec-admin\","
                + "\"specKey\":\"" + specKey + "\",\"version\":" + version + ","
                + "\"features\":[\"x\",\"y\"],"
                + "\"constraints\":["
                + "{\"coefficients\":[1,0],\"upperBound\":10},"
                + "{\"coefficients\":[0,1],\"upperBound\":10},"
                + "{\"coefficients\":[1,1],\"upperBound\":15}]}";
    }

    private void createSpec(String specKey, int version, String requestId,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON)
                        .content(specBody(specKey, version, requestId)))
                .andExpect(status().is(expected));
    }

    private void createBoundBatch(String batchKey, String specKey, int specVersion,
                                  int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"P\",\"batchNo\":\"N\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"],"
                + "\"specKey\":\"" + specKey + "\",\"specVersion\":" + specVersion + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                + unique() + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                + "\",\"inspector\":\"" + inspector + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private MvcResult submitMeasurement(String batchKey, String measurementKey, String valuesJson,
                                        String requestId, int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"measurer-1\","
                + "\"measurementKey\":\"" + measurementKey + "\",\"values\":" + valuesJson + "}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private int countRows(String table, String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE batch_key = ?", Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    private void postSpec(String body, int expected) throws Exception {
        mockMvc.perform(post("/api/specs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void rebind(String batchKey, int expectedVersion, int newVersion, String requestId,
                        int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"rebinder-1\","
                + "\"expectedVersion\":" + expectedVersion + ",\"newVersion\":" + newVersion + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/spec-rebind")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    @SafeVarargs
    private final List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
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
