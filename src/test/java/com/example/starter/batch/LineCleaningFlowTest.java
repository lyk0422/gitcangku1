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
 * 生产线加工顺序与相邻批次清洗验证端到端测试（真实 H2 事务）：
 * 主流程（残留超限阻断、二次清洗确认后放行）、失败分支、顺序/版本约束、
 * 召回血缘拦截、requestId 幂等重放与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LineCleaningFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM cleaning_record");
        jdbc.update("DELETE FROM line_sequence");
        jdbc.update("DELETE FROM production_line");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, String productCode, List<String> items, int expected)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", productCode);
        body.put("batchNo", "LOT-" + batchKey);
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", items);
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    private void passTest(String batchKey, String item, String inspector, int expected)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", "TK-" + unique());
        body.put("testItem", item);
        body.put("result", "PASS");
        body.put("inspector", inspector);
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 建批 + 检验通过 + 双角色批准，直达 RELEASED。
     */
    private void releaseBatch(String batchKey, String productCode) throws Exception {
        createBatch(batchKey, productCode, List.of("t1"), 201);
        passTest(batchKey, "t1", "insp-" + unique(), 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private MvcResult register(String lineId, String batchKey, long expectedVersion,
                               String requestId, String actor, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("batchKey", batchKey);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/lines/" + lineId + "/sequences")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult submitCleaning(String lineId, String cleaningId, String from, String to,
                                     String residual, long expectedVersion, String requestId,
                                     String actor, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("cleaningId", cleaningId);
        body.put("fromBatchKey", from);
        body.put("toBatchKey", to);
        body.put("residualAmount", residual);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/lines/" + lineId + "/cleanings")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult confirm(String cleaningId, String actor, String role, long expectedVersion,
                              String requestId, int expected) throws Exception {
        return mockMvc.perform(post("/api/cleanings/" + cleaningId + "/confirmations")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":"
                                + expectedVersion + "}"))
                .andExpect(status().is(expected)).andReturn();
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

    private int registerStatus(String lineId, String batchKey, long expectedVersion,
                               String requestId, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("batchKey", batchKey);
        body.put("expectedVersion", expectedVersion);
        try {
            return callStatus(post("/api/lines/" + lineId + "/sequences")
                    .header("X-Actor-Id", actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)));
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
    // ---------- 主流程：题干场景 ----------

    @Test
    void happyPath_residualOverLimitBlocksThenSecondCleaningConfirmedReleases() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        releaseBatch(a, "PROD-A");
        createBatch(b, "PROD-B", List.of("t1"), 201);

        // 首批 A 上机，无需清洗
        MvcResult regA = register(line, a, 1, "RID-" + unique(), "op-1", 201);
        assertTrue(objectMapper.readTree(regA.getResponse().getContentAsString())
                .path("prevBatchKey").isNull(), "首批无前一批次");

        // 第一次清洗残留 0.100001 毫克（未舍入判断超过 0.1）：确认被 422 阻断，不产生有效清洗
        submitCleaning(line, "CL-1", a, b, "0.100001", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-1", "qa-1", "QUALITY", 0, "RID-" + unique(), 422);
        // 无有效清洗，B 不得上机
        register(line, b, 2, "RID-" + unique(), "op-1", 422);

        // 第二次清洗 0.09 毫克，另一名 QUALITY 确认后有效
        submitCleaning(line, "CL-2", a, b, "0.09", 1, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-2", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        MvcResult regB = register(line, b, 2, "RID-" + unique(), "op-1", 201);
        assertEquals(a, objectMapper.readTree(regB.getResponse().getContentAsString())
                .path("prevBatchKey").asText());

        // B 通过检验与双角色批准后放行（冻结相邻批次与合格清洗）
        passTest(b, "t1", "insp-" + unique(), 201);
        approve(b, "qa-" + unique(), "QUALITY", 201);
        approve(b, "ops-" + unique(), "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(b));

        // 旧不合格清洗记录保持原样，不被翻转为合格；第二次清洗只新增记录
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM cleaning_record WHERE cleaning_id = 'CL-1'", String.class));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM cleaning_record WHERE cleaning_id = 'CL-2'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cleaning_record"
                + " WHERE line_id = ? AND from_batch_key = ? AND to_batch_key = ?",
                Integer.class, line, a, b));
        assertEquals(3, jdbc.queryForObject("SELECT next_seq FROM production_line WHERE line_id = ?",
                Integer.class, line));
    }

    @Test
    void firstBatchAndSameProductNeedNoCleaning() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-A", List.of("t1"), 201);

        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        // 同产品代码后批无需清洗记录
        register(line, b, 2, "RID-" + unique(), "op-1", 201);

        MvcResult seq = mockMvc.perform(get("/api/lines/" + line + "/sequences"))
                .andExpect(status().isOk()).andReturn();
        JsonNode entries = objectMapper.readTree(seq.getResponse().getContentAsString());
        assertEquals(2, entries.size());
        assertEquals(a, entries.get(0).path("batchKey").asText());
        assertEquals(b, entries.get(1).path("batchKey").asText());
        assertEquals(a, entries.get(1).path("prevBatchKey").asText());
    }

    // ---------- 失败分支 ----------

    @Test
    void residualFormatAndRangeValidation() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);

        // 非法格式或超范围一律 400：非数值、负数、超过 100、超过 6 位小数
        for (String bad : List.of("abc", "-0.1", "100.000001", "0.1234567", "1.5.2", "1,5")) {
            submitCleaning(line, "CL-" + unique(), a, b, bad, 0, "RID-" + unique(), "cl", 400);
        }
        // 上限 100 毫克可提交，但确认时按未舍入原值判定超过 0.1 → 422
        submitCleaning(line, "CL-MAX", a, b, "100", 0, "RID-" + unique(), "cl", 201);
        confirm("CL-MAX", "qa-1", "QUALITY", 0, "RID-" + unique(), 422);
        // 边界 0.1 毫克（含）确认通过
        submitCleaning(line, "CL-EDGE", a, b, "0.1", 1, "RID-" + unique(), "cl", 201);
        confirm("CL-EDGE", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
    }

    @Test
    void confirmConstraints_roleActorVersionAndUnknown() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);
        submitCleaning(line, "CL-C", a, b, "0.05", 0, "RID-" + unique(), "submitter-1", 201);

        // 确认人同提交人 → 422
        confirm("CL-C", "submitter-1", "QUALITY", 0, "RID-" + unique(), 422);
        // 非 QUALITY 角色 → 422
        confirm("CL-C", "qa-1", "OPERATIONS", 0, "RID-" + unique(), 422);
        // 缺角色头 → 400
        mockMvc.perform(post("/api/cleanings/CL-C/confirmations")
                        .header("X-Actor-Id", "qa-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-X\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 期望版本不符 → 409
        confirm("CL-C", "qa-1", "QUALITY", 5, "RID-" + unique(), 409);
        // 清洗记录不存在 → 404
        confirm("CL-NOPE", "qa-1", "QUALITY", 0, "RID-" + unique(), 404);

        // 合法确认成功，记录版本升为 1
        confirm("CL-C", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        // 重复确认（不同 requestId）→ 409
        confirm("CL-C", "qa-2", "QUALITY", 1, "RID-" + unique(), 409);
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM cleaning_record WHERE cleaning_id = 'CL-C'", String.class));
    }

    @Test
    void registrationConstraints_sequenceMultiLineUnknownAndCleaning() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);

        // 插队：首批期望版本 2 → 409
        register(line, a, 2, "RID-" + unique(), "op-1", 409);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        // 同一批次重复登记 → 409
        register(line, a, 2, "RID-" + unique(), "op-1", 409);
        // 一批挂多线 → 409
        register("LINE-OTHER-" + unique(), a, 1, "RID-" + unique(), "op-1", 409);
        // 批次不存在 → 404
        register(line, "BK-NOPE", 2, "RID-" + unique(), "op-1", 404);
        // 不同产品且无有效清洗 → 422
        register(line, b, 2, "RID-" + unique(), "op-1", 422);
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/lines/" + line + "/sequences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RID-H\",\"batchKey\":\"" + b
                                + "\",\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest());
        // 生产线不存在 → 404
        mockMvc.perform(get("/api/lines/LINE-NOPE/sequences"))
                .andExpect(status().isNotFound());
    }
    @Test
    void secondCleaningAppendsRecord_oldUnqualifiedNeverFlipped() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);

        // 第一次清洗残留超限：确认 422，记录保持 PENDING
        submitCleaning(line, "CL-OLD", a, b, "0.5", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-OLD", "qa-1", "QUALITY", 0, "RID-" + unique(), 422);
        // 第二次清洗只新增记录（期望版本 = 已有记录数 1）
        submitCleaning(line, "CL-NEW", a, b, "0.05", 1, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-NEW", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        // 旧记录不可翻转为合格：再次确认仍 422
        confirm("CL-OLD", "qa-2", "QUALITY", 0, "RID-" + unique(), 422);

        JsonNode old = objectMapper.readTree(mockMvc.perform(get("/api/cleanings/CL-OLD"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("PENDING", old.path("status").asText());
        assertEquals(0, old.path("version").asInt());
        assertEquals("0.5", old.path("residualAmount").asText(), "残留量保留未舍入原值");
        // 新清洗生效后 B 可上机
        register(line, b, 2, "RID-" + unique(), "op-1", 201);
    }

    // ---------- 召回血缘拦截 ----------

    @Test
    void recalledPredecessorBlocksRelease() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        releaseBatch(a, "PROD-A");
        createBatch(b, "PROD-B", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        submitCleaning(line, "CL-R", a, b, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-R", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        register(line, b, 2, "RID-" + unique(), "op-1", 201);

        passTest(b, "t1", "insp-" + unique(), 201);
        approve(b, "qa-" + unique(), "QUALITY", 201);
        // 前批 A 被召回后，B 不得放行
        recall(a, "召回：前批污染");
        approve(b, "ops-" + unique(), "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b));
    }

    @Test
    void recalledAncestorOfPredecessorBlocksRelease() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String c1 = "BK-C1-" + unique();
        String c2 = "BK-C2-" + unique();
        String d = "BK-D-" + unique();
        releaseBatch(a, "PROD-A");
        // A 拆分为子批 C1/C2，A 置为 SPLIT
        mockMvc.perform(post("/api/batches/" + a + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-S-" + unique() + "\",\"children\":["
                                + "{\"batchKey\":\"" + c1 + "\",\"batchNo\":\"L1\"},"
                                + "{\"batchKey\":\"" + c2 + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().isCreated());
        createBatch(d, "PROD-D", List.of("t1"), 201);

        register(line, c1, 1, "RID-" + unique(), "op-1", 201);
        submitCleaning(line, "CL-D", c1, d, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-D", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        register(line, d, 2, "RID-" + unique(), "op-1", 201);

        passTest(d, "t1", "insp-" + unique(), 201);
        approve(d, "qa-" + unique(), "QUALITY", 201);
        // 前批 C1 的祖先 A 被召回：C1 处于召回血缘中，D 不得放行
        recall(a, "召回：祖先批次质量事件");
        approve(d, "ops-" + unique(), "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(d));
    }

    private void recall(String batchKey, String reason) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "qa-" + unique())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-" + unique() + "\",\"reason\":\""
                                + reason + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---------- requestId 幂等 ----------

    @Test
    void requestId_sameParamsReplayFirstResponse_changedParamsConflict() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);

        // 登记：同键同参重放首次完整响应；同键异参 409
        String regRid = "RID-REG-" + unique();
        MvcResult first = register(line, a, 1, regRid, "op-1", 201);
        MvcResult replay = register(line, a, 1, regRid, "op-1", 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        register(line, b, 1, regRid, "op-1", 409);

        // 清洗提交：同键同参重放；同键改残留量 409
        String clRid = "RID-CL-" + unique();
        MvcResult clFirst = submitCleaning(line, "CL-I", a, b, "0.09", 0, clRid, "cleaner-1", 201);
        MvcResult clReplay = submitCleaning(line, "CL-I", a, b, "0.09", 0, clRid, "cleaner-1", 201);
        assertEquals(clFirst.getResponse().getContentAsString(),
                clReplay.getResponse().getContentAsString());
        submitCleaning(line, "CL-I", a, b, "0.08", 0, clRid, "cleaner-1", 409);

        // 确认：同键同参重放；同键改期望版本 409
        String cfRid = "RID-CF-" + unique();
        MvcResult cfFirst = confirm("CL-I", "qa-1", "QUALITY", 0, cfRid, 201);
        MvcResult cfReplay = confirm("CL-I", "qa-1", "QUALITY", 0, cfRid, 201);
        assertEquals(cfFirst.getResponse().getContentAsString(),
                cfReplay.getResponse().getContentAsString());
        confirm("CL-I", "qa-1", "QUALITY", 1, cfRid, 409);
        // 重放不新增记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cleaning_record WHERE cleaning_id = 'CL-I'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_sequence WHERE line_id = ?", Integer.class, line));
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);

        // 无有效清洗登记失败（422），失败不占键
        String rid = "RID-F-" + unique();
        register(line, b, 2, rid, "op-1", 422);
        submitCleaning(line, "CL-F", a, b, "0.09", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-F", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        // 同一 requestId 携带相同参数重试，本次成功
        register(line, b, 2, rid, "op-1", 201);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_sequence WHERE line_id = ?", Integer.class, line));
    }
    // ---------- 并发 ----------

    @Test
    void concurrentRegistration_sameLineExactlyOneWinner() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-A", List.of("t1"), 201);

        // 两个批次并发抢占同一顺序号：恰一个成功，顺序号不重叠
        List<Future<Integer>> results = runConcurrent(
                () -> registerStatus(line, a, 1, "RID-" + unique(), "op-1"),
                () -> registerStatus(line, b, 1, "RID-" + unique(), "op-2"));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "并发登记恰一个成功: " + first + "/" + second);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_sequence WHERE line_id = ?", Integer.class, line));
        assertEquals(2, jdbc.queryForObject(
                "SELECT next_seq FROM production_line WHERE line_id = ?", Integer.class, line));

        // 同一批次并发登记两条生产线：恰一个成功（禁止一批挂多线）
        String x = "BK-X-" + unique();
        createBatch(x, "PROD-X", List.of("t1"), 201);
        String line1 = "LINE-M1-" + unique();
        String line2 = "LINE-M2-" + unique();
        List<Future<Integer>> multi = runConcurrent(
                () -> registerStatus(line1, x, 1, "RID-" + unique(), "op-1"),
                () -> registerStatus(line2, x, 1, "RID-" + unique(), "op-2"));
        int m1 = multi.get(0).get(30, TimeUnit.SECONDS);
        int m2 = multi.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((m1 == 201 && m2 == 409) || (m1 == 409 && m2 == 201),
                "一批挂多线并发恰一个成功: " + m1 + "/" + m2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_sequence WHERE batch_key = ?", Integer.class, x));
    }

    @Test
    void concurrentRegistration_mustUseActualPrevBatchCleaning() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        String c = "BK-C-" + unique();
        createBatch(a, "PROD-A", List.of("t1"), 201);
        createBatch(b, "PROD-B", List.of("t1"), 201);
        createBatch(c, "PROD-C", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        // B、C 各自持有针对前批 A 的有效清洗
        submitCleaning(line, "CL-AB", a, b, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-AB", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        submitCleaning(line, "CL-AC", a, c, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-AC", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);

        // B、C 并发抢占顺序号 2：恰一个成功
        List<Future<Integer>> race = runConcurrent(
                () -> registerStatus(line, b, 2, "RID-" + unique(), "op-1"),
                () -> registerStatus(line, c, 2, "RID-" + unique(), "op-2"));
        int sb = race.get(0).get(30, TimeUnit.SECONDS);
        int sc = race.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((sb == 201 && sc == 409) || (sb == 409 && sc == 201));
        String winner = sb == 201 ? b : c;
        String loser = sb == 201 ? c : b;

        // 落败方不得沿用上一对批次（A→落败方）的清洗：实际前批已是胜方 → 422
        register(line, loser, 3, "RID-" + unique(), "op-1", 422);
        // 针对实际前批（胜方→落败方）补做清洗并确认后可登记
        submitCleaning(line, "CL-WL", winner, loser, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-WL", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        register(line, loser, 3, "RID-" + unique(), "op-1", 201);

        MvcResult seq = mockMvc.perform(get("/api/lines/" + line + "/sequences"))
                .andExpect(status().isOk()).andReturn();
        JsonNode entries = objectMapper.readTree(seq.getResponse().getContentAsString());
        assertEquals(3, entries.size());
        assertEquals(winner, entries.get(1).path("batchKey").asText());
        assertEquals(winner, entries.get(2).path("prevBatchKey").asText());
        assertEquals(loser, entries.get(2).path("batchKey").asText());
    }

    @Test
    void concurrentReleaseAndRecall_consistentFinalState() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        releaseBatch(a, "PROD-A");
        createBatch(b, "PROD-B", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        submitCleaning(line, "CL-X", a, b, "0.05", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-X", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        register(line, b, 2, "RID-" + unique(), "op-1", 201);
        passTest(b, "t1", "insp-" + unique(), 201);
        approve(b, "qa-" + unique(), "QUALITY", 201);

        // 放行（第二批准）与前批召回竞争：只能按提交顺序落一种结果
        List<Future<Integer>> race = runConcurrent(
                () -> callStatus(post("/api/batches/" + b + "/approvals")
                        .header("X-Actor-Id", "ops-" + unique()).header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-O-" + unique() + "\"}")),
                () -> callStatus(post("/api/batches/" + a + "/recall")
                        .header("X-Actor-Id", "qa-" + unique())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-" + unique()
                                + "\",\"reason\":\"前批召回\"}")));
        int release = race.get(0).get(30, TimeUnit.SECONDS);
        int recallStatus = race.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recallStatus, "A 为 RELEASED，召回始终有效");
        String bStatus = currentStatus(b);
        if (release == 201) {
            // 放行先提交：B 已 RELEASED（冻结时前批未召回）
            assertEquals("RELEASED", bStatus);
        } else {
            // 召回先提交：前批处于召回状态，放行被 422 阻断
            assertEquals(422, release);
            assertEquals("RELEASE_REVIEW", bStatus);
        }
        assertEquals("RECALLED", currentStatus(a));
    }

    // ---------- 持久化核对 ----------

    @Test
    void lineAndCleaningDataPersistedInTables() throws Exception {
        String line = "LINE-" + unique();
        String a = "BK-A-" + unique();
        String b = "BK-B-" + unique();
        releaseBatch(a, "PROD-A");
        createBatch(b, "PROD-B", List.of("t1"), 201);
        register(line, a, 1, "RID-" + unique(), "op-1", 201);
        submitCleaning(line, "CL-P", a, b, "0.09", 0, "RID-" + unique(), "cleaner-1", 201);
        confirm("CL-P", "qa-1", "QUALITY", 0, "RID-" + unique(), 201);
        register(line, b, 2, "RID-" + unique(), "op-1", 201);

        assertEquals(3, jdbc.queryForObject(
                "SELECT next_seq FROM production_line WHERE line_id = ?", Integer.class, line));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_sequence"
                + " WHERE line_id = ? AND seq = 1 AND batch_key = ? AND prev_batch_key IS NULL",
                Integer.class, line, a));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_sequence"
                + " WHERE line_id = ? AND seq = 2 AND batch_key = ? AND prev_batch_key = ?",
                Integer.class, line, b, a));
        // 残留量按未舍入原值保存
        assertEquals("0.09", jdbc.queryForObject(
                "SELECT residual_amount FROM cleaning_record WHERE cleaning_id = 'CL-P'",
                String.class));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM cleaning_record WHERE cleaning_id = 'CL-P'", String.class));
        assertEquals("qa-1", jdbc.queryForObject(
                "SELECT confirmed_by FROM cleaning_record WHERE cleaning_id = 'CL-P'",
                String.class));
        // 三类新命令的幂等快照均已落库（登记两次、清洗提交与确认各一次）
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'REGISTER_SEQUENCE'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SUBMIT_CLEANING'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CONFIRM_CLEANING'",
                Integer.class));
    }
}