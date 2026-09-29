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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 产率平衡核算端到端测试（真实 H2 内存库 MODE=MySQL）：题干样例主流程、超限双人处置门禁、
 * 负数 422、requestId/commandKey 幂等与 409、处置版本过期、历史冻结快照、并发裁决与唯一约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class YieldReconciliationFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 读取响应时保留 DECIMAL 的精度与三位 scale（默认解析为 double 会把 0.500 读成 0.5）。
     */
    private ObjectMapper decimalMapper;

    @BeforeEach
    void cleanTables() {
        decimalMapper = objectMapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM yield_disposal");
        jdbc.update("DELETE FROM yield_reconciliation");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程：题干样例 ----------

    @Test
    void deviationExactlyAtLimit_isPassing_andBatchCanBeReleased() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);

        // 投料100.000、合格90.000、损耗8.000、报废1.500 → 差额0.500，恰好等于上限 → 合格可放行
        MvcResult rawResult = submitYieldRaw(batchKey, "RY-EXACT", "100.000", "90.000", "8.000", "1.500", 201);
        String rawBody = rawResult.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(rawBody.contains("\"deviation\":0.500"),
                "响应报文应包含三位小数偏差，实际: " + rawBody);
        JsonNode body = decimalMapper.readTree(rawBody);
        long version = body.path("version").asLong();
        assertTrue(version >= 1);
        assertAmount(body, "deviation", "0.500");
        assertAmount(body, "limit", "0.500");
        assertTrue(body.path("withinLimit").asBoolean());
        assertEquals("WITHIN_LIMIT", body.path("gateStatus").asText());

        JsonNode current = currentYield(batchKey);
        assertEquals(version, current.path("version").asLong());
        assertAmount(current, "inputQty", "100.000");
        assertAmount(current, "goodQty", "90.000");
        assertAmount(current, "lossQty", "8.000");
        assertAmount(current, "scrapQty", "1.500");
        assertAmount(current, "deviation", "0.500");
        assertAmount(current, "limit", "0.500");
        assertEquals("WITHIN_LIMIT", current.path("gateStatus").asText());

        approve(batchKey, "qa-1", "QUALITY", "CK-Q1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O1", 201);
        assertEquals("RELEASED", batchStatus(batchKey));
    }

    @Test
    void exceededBatch_pendingDisposal_cannotRelease_untilTwoDifferentDisposersConfirm() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);

        // 报废 1.000 → 差额 1.000 超限，进入待处置
        JsonNode body = submitYield(batchKey, "RY-OVER", "100.000", "90.000", "8.000", "1.000", 201);
        long v1 = body.path("version").asLong();
        assertAmount(body, "deviation", "1.000");
        assertFalse(body.path("withinLimit").asBoolean());
        assertEquals("EXCEEDED_PENDING", body.path("gateStatus").asText());

        // 门禁未解除：放行被 422
        approve(batchKey, "qa-1", "QUALITY", "CK-Q1", 422);
        assertEquals("PENDING_RELEASE", batchStatus(batchKey));

        // 第一名处置人：仍待处置，仍不能放行
        JsonNode d1 = dispose(batchKey, "disp-a", v1, "CK-D1", 201);
        assertEquals(1, d1.path("sequence").asInt());
        assertEquals(List.of("disp-a"), disposers(d1));
        assertEquals("EXCEEDED_PENDING", d1.path("gateStatus").asText());
        approve(batchKey, "qa-1", "QUALITY", "CK-Q2", 422);

        // 同一处置人换 commandKey 重复确认：返回其首次确认（200），不新增记录
        JsonNode d1Replay = dispose(batchKey, "disp-a", v1, "CK-D1-RETRY", 200);
        assertEquals(1, d1Replay.path("sequence").asInt());
        assertEquals(1, disposalCount(v1));

        // 第二名不同处置人：门禁解除，随后双角色放行成功
        JsonNode d2 = dispose(batchKey, "disp-b", v1, "CK-D2", 201);
        assertEquals(2, d2.path("sequence").asInt());
        assertEquals(List.of("disp-a", "disp-b"), disposers(d2));
        assertEquals("DISPOSED", d2.path("gateStatus").asText());
        assertEquals("DISPOSED", currentYield(batchKey).path("gateStatus").asText());

        approve(batchKey, "qa-1", "QUALITY", "CK-Q3", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O1", 201);
        assertEquals("RELEASED", batchStatus(batchKey));

        // 已放行（终态）不能再补处置
        dispose(batchKey, "disp-c", v1, "CK-D-LATE", 409);
        assertEquals(2, disposalCount(v1));
    }

    // ---------- 拒绝分支 ----------

    @Test
    void noYieldSubmitted_approvalReturns422() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        assertEquals("NONE", currentYield(batchKey).path("gateStatus").asText());
        assertTrue(currentYield(batchKey).path("version").isNull());
        approve(batchKey, "qa-1", "QUALITY", "CK-Q1", 422);
        assertEquals("PENDING_RELEASE", batchStatus(batchKey));
    }

    @Test
    void negativeQuantity_returns422_andDoesNotChangeOriginalReconciliation() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        long firstVersion = submitYield(batchKey, "RY-OK", "100.000", "100.000", "0.000", "0.000", 201)
                .path("version").asLong();

        submitYield(batchKey, "RY-NEG", "100.000", "90.000", "8.000", "-1.000", 422);
        submitYield(batchKey, "RY-NEG2", "-0.001", "0.000", "0.000", "0.000", 422);

        // 原核算未改动：仍为首次版本、门禁 WITHIN_LIMIT、无新版本落库
        JsonNode current = currentYield(batchKey);
        assertEquals(firstVersion, current.path("version").asLong());
        assertEquals("RY-OK", current.path("requestId").asText());
        assertEquals("WITHIN_LIMIT", current.path("gateStatus").asText());
        assertEquals(1, yieldCount(batchKey));
    }

    @Test
    void disposeStaleVersion_returns422_andKeepsOldConfirmation() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        long v1 = submitYield(batchKey, "RY-V1", "100.000", "90.000", "8.000", "1.000", 201)
                .path("version").asLong();
        dispose(batchKey, "disp-a", v1, "CK-D1", 201);

        // 新版本提交后旧版本过期：处置旧版本 422，且不写入确认
        long v2 = submitYield(batchKey, "RY-V2", "100.000", "90.000", "8.000", "1.200", 201)
                .path("version").asLong();
        assertTrue(v2 > v1);
        dispose(batchKey, "disp-b", v1, "CK-D-OLD", 422);
        assertEquals(1, disposalCount(v1), "过期版本的处置被拒绝，不新增确认");
        assertEquals(0, disposalCount(v2));

        // 旧版本上的处置人不能算作新版本的处置：disp-a 对新版本是第一名
        JsonNode onV2 = dispose(batchKey, "disp-a", v2, "CK-D2", 201);
        assertEquals(1, onV2.path("sequence").asInt());
        assertEquals("EXCEEDED_PENDING", onV2.path("gateStatus").asText());
    }

    @Test
    void disposeWithinLimitOrMissingVersion_returns409or422() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        long version = submitYield(batchKey, "RY-OK", "100.000", "100.000", "0.000", "0.000", 201)
                .path("version").asLong();

        // 未超限版本无需处置 → 409
        dispose(batchKey, "disp-a", version, "CK-D1", 409);
        // 不存在的版本 → 版本过期 422
        dispose(batchKey, "disp-a", version + 1000, "CK-D2", 422);
        // 缺 X-Actor-Id → 400
        String body = "{\"commandKey\":\"CK-D3\",\"version\":" + version + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/yields/disposals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yield_disposal", Integer.class));
    }

    @Test
    void rejectedBatch_submitAndDisposeAreRejected_andNoYieldDataWritten() throws Exception {
        String batchKey = newBatch("0.500");
        // 检验 FAIL 使批次 REJECTED
        String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), "TK-" + unique(),
                "t1", "FAIL", "insp-1"));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        submitYield(batchKey, "RY-LATE", "100.000", "90.000", "8.000", "1.000", 409);
        assertEquals(0, yieldCount(batchKey));
        assertEquals("NONE", currentYield(batchKey).path("gateStatus").asText());
    }

    @Test
    void unknownBatch_yieldEndpointsReturn404() throws Exception {
        submitYieldRaw("NO-SUCH-BATCH", "RY-X", "1", "1", "0", "0", 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/yields/current"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等与 409 ----------

    @Test
    void sameRequestId_replaysFirstDeviationAndStatus_changedQuantitiesConflict() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);

        MvcResult first = submitYieldRaw(batchKey, "RY-IDEM", "100.000", "90.000", "8.000", "1.000", 201);
        MvcResult replay = submitYieldRaw(batchKey, "RY-IDEM", "100.000", "90.000", "8.000", "1.000", 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, yieldCount(batchKey), "重放不新增核算版本");

        // 同 requestId 换数量 → 409，原核算不动
        submitYield(batchKey, "RY-IDEM", "100.000", "90.000", "8.000", "1.500", 409);
        JsonNode current = currentYield(batchKey);
        assertAmount(current, "deviation", "1.000");
        assertEquals("EXCEEDED_PENDING", current.path("gateStatus").asText());
        assertEquals(1, yieldCount(batchKey));
    }

    @Test
    void sameRequestId_canBeUsedOnDifferentBatches() throws Exception {
        String b1 = newBatch("0.500");
        String b2 = newBatch("0.500");
        passTests(b1);
        passTests(b2);
        submitYield(b1, "RY-SHARED", "100.000", "100.000", "0.000", "0.000", 201);
        submitYield(b2, "RY-SHARED", "100.000", "100.000", "0.000", "0.000", 201);
        assertEquals(1, yieldCount(b1));
        assertEquals(1, yieldCount(b2));
    }

    @Test
    void disposeCommandKey_sameReplays_changedVersionConflicts() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        long v1 = submitYield(batchKey, "RY-V1", "100.000", "90.000", "8.000", "1.000", 201)
                .path("version").asLong();

        MvcResult first = disposeRaw(batchKey, "disp-a", v1, "CK-D-IDEM", 201);
        MvcResult replay = disposeRaw(batchKey, "disp-a", v1, "CK-D-IDEM", 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 同 commandKey 换版本 → 409
        long v2 = submitYield(batchKey, "RY-V2", "100.000", "90.000", "8.000", "1.100", 201)
                .path("version").asLong();
        disposeRaw(batchKey, "disp-a", v2, "CK-D-IDEM", 409);
        assertEquals(1, disposalCount(v1));
        assertEquals(0, disposalCount(v2));
    }

    // ---------- 历史冻结快照 ----------

    @Test
    void history_keepsFrozenLimitAndTwoDisposersPerVersion() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        // v1 超限，双处置解除
        long v1 = submitYield(batchKey, "RY-V1", "100.000", "90.000", "8.000", "1.000", 201)
                .path("version").asLong();
        dispose(batchKey, "disp-a", v1, "CK-D1", 201);
        dispose(batchKey, "disp-b", v1, "CK-D2", 201);
        // v2 再次超限，无处置
        long v2 = submitYield(batchKey, "RY-V2", "100.000", "90.000", "7.000", "1.000", 201)
                .path("version").asLong();

        JsonNode history = historyYields(batchKey);
        assertEquals(2, history.size());

        JsonNode h1 = history.get(0);
        assertEquals(v1, h1.path("version").asLong());
        assertAmount(h1, "deviation", "1.000");
        // 历史保留计算所用上限快照
        assertAmount(h1, "limit", "0.500");
        assertEquals("DISPOSED", h1.path("gateStatus").asText());
        assertEquals(List.of("disp-a", "disp-b"), disposers(h1));

        JsonNode h2 = history.get(1);
        assertEquals(v2, h2.path("version").asLong());
        assertAmount(h2, "deviation", "2.000");
        assertEquals("EXCEEDED_PENDING", h2.path("gateStatus").asText());
        assertEquals(0, h2.path("disposers").size());

        // 批次当前门禁只认最新版本：v2 待处置，不能放行
        assertEquals("EXCEEDED_PENDING", currentYield(batchKey).path("gateStatus").asText());
        approve(batchKey, "qa-1", "QUALITY", "CK-Q1", 422);

        // 每个版本的冻结上限独立落库
        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ? AND deviation_limit = 0.500",
                Integer.class, batchKey);
        assertEquals(2, snapshotRows);
    }

    @Test
    void quantities_areStoredWithExactlyThreeDecimals() throws Exception {
        String batchKey = newBatch("1.000");
        passTests(batchKey);
        // 多于三位的输入按四舍五入规范化后参与计算与存储
        MvcResult raw = submitYieldRaw(batchKey, "RY-SCALE", "100.0004", "99.9996", "0.0001", "0.0001", 201);
        // 线上报文统一三位小数字面量
        String rawBody = raw.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(rawBody.contains("\"inputQty\":100.000"), rawBody);
        JsonNode current = currentYield(batchKey);
        assertAmount(current, "inputQty", "100.000");
        assertAmount(current, "goodQty", "100.000");
        assertAmount(current, "lossQty", "0.000");
        assertAmount(current, "scrapQty", "0.000");
        assertAmount(current, "deviation", "0.000");
    }

    // ---------- 并发 ----------

    @Test
    void concurrentExceededYieldAndApproval_neverReleasesOverLimitBatch() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(postYield(batchKey, "RY-RACE", "100.000", "90.000", "8.000", "1.000")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-Q1\"}"))
        );

        // 批准先获锁：读到 NONE → 422；核算先提交：读到 EXCEEDED_PENDING → 422。任何顺序都不得放行。
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(422, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals("EXCEEDED_PENDING", currentYield(batchKey).path("gateStatus").asText());
        assertEquals("PENDING_RELEASE", batchStatus(batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
    }

    @Test
    void concurrentSecondDisposalAndApproval_committedGateStatusDecides() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);
        long v1 = submitYield(batchKey, "RY-V1", "100.000", "90.000", "8.000", "1.000", 201)
                .path("version").asLong();
        dispose(batchKey, "disp-a", v1, "CK-D1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/yields/disposals")
                        .header("X-Actor-Id", "disp-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-D2\",\"version\":" + v1 + "}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-Q1\"}"))
        );

        int disposalCode = results.get(0).get(30, TimeUnit.SECONDS);
        int approvalCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, disposalCode, "第二名处置人提交成功，门禁最终解除");
        assertTrue(approvalCode == 201 || approvalCode == 422,
                "批准按已提交门禁状态裁决：处置先提交则 201，批准先提交则 422");

        // 并发结束后数据一致：门禁 DISPOSED，不存在"超限待处置却已放行"
        assertEquals("DISPOSED", currentYield(batchKey).path("gateStatus").asText());
        assertEquals(2, disposalCount(v1));
        // 批准恰好先到被拒（0 条批准）或在处置后提交（1 条批准，RELEASE_REVIEW）：补齐双角色后均可放行
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey);
        if (approvals == 0) {
            approve(batchKey, "qa-1", "QUALITY", "CK-Q2", 201);
        } else {
            assertEquals(1, approvals);
            assertEquals("RELEASE_REVIEW", batchStatus(batchKey));
        }
        approve(batchKey, "ops-1", "OPERATIONS", "CK-O1", 201);
        assertEquals("RELEASED", batchStatus(batchKey));
    }

    @Test
    void concurrentSameRequestId_bothGetFirstResult_andSingleVersionRow() throws Exception {
        String batchKey = newBatch("0.500");
        passTests(batchKey);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(postYield(batchKey, "RY-CONC", "100.000", "90.000", "8.000", "1.000")),
                () -> callStatus(postYield(batchKey, "RY-CONC", "100.000", "90.000", "8.000", "1.000"))
        );
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(201, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals(1, yieldCount(batchKey));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests, BigDecimal deviationLimit) {
    }

    /**
     * 创建含一个必做检验项、指定冻结上限的批次，返回批次键。
     */
    private String newBatch(String limit) throws Exception {
        String batchKey = "BK-YLD-" + unique();
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                new BigDecimal(limit)));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    private void passTests(String batchKey) throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), "TK-" + unique(),
                "t1", "PASS", "insp-1"));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private String yieldBody(String requestId, String input, String good, String loss, String scrap) {
        return "{\"requestId\":\"" + requestId + "\",\"inputQty\":" + input
                + ",\"goodQty\":" + good + ",\"lossQty\":" + loss + ",\"scrapQty\":" + scrap + "}";
    }

    private MockHttpServletRequestBuilder postYield(String batchKey, String requestId,
                                                    String input, String good, String loss, String scrap) {
        return post("/api/batches/" + batchKey + "/yields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(yieldBody(requestId, input, good, loss, scrap));
    }

    private JsonNode submitYield(String batchKey, String requestId, String input, String good,
                                 String loss, String scrap, int expected) throws Exception {
        MvcResult result = submitYieldRaw(batchKey, requestId, input, good, loss, scrap, expected);
        return decimalMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult submitYieldRaw(String batchKey, String requestId, String input, String good,
                                     String loss, String scrap, int expected) throws Exception {
        return mockMvc.perform(postYield(batchKey, requestId, input, good, loss, scrap))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode dispose(String batchKey, String disposer, long version, String commandKey,
                             int expected) throws Exception {
        MvcResult result = disposeRaw(batchKey, disposer, version, commandKey, expected);
        return decimalMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult disposeRaw(String batchKey, String disposer, long version, String commandKey,
                                 int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/yields/disposals")
                        .header("X-Actor-Id", disposer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"version\":" + version + "}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode currentYield(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/yields/current"))
                .andExpect(status().isOk()).andReturn();
        return decimalMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode historyYields(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return decimalMapper.readTree(result.getResponse().getContentAsString()).path("yields");
    }

    private String batchStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> disposers(JsonNode node) {
        List<String> ids = new ArrayList<>();
        node.path("disposers").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    /**
     * 数值断言：按 BigDecimal 数值比较（忽略 scale 写法差异），同时验证字段为 JSON 数字而非字符串。
     */
    private void assertAmount(JsonNode node, String field, String expected) {
        JsonNode value = node.path(field);
        assertTrue(value.isNumber(), field + " 应为 JSON 数字");
        assertEquals(0, value.decimalValue().compareTo(new BigDecimal(expected)),
                field + " 数值应为 " + expected + "，实际 " + value.decimalValue());
    }

    private int yieldCount(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_reconciliation WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private int disposalCount(long version) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_disposal WHERE version_id = ?",
                Integer.class, version);
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
