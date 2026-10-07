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
 * 合成序贯检验端到端测试（真实 H2 库）：GOOD/BAD 精确分数阈值、编号连续性与不可覆盖、
 * 终止冻结、INCONCLUSIVE 拦截批准、与必做检验/双角色放行/拆分/祖先召回的联合校验、
 * requestId 幂等与并发竞争。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SequentialAssayTest {

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
        jdbc.update("DELETE FROM sequential_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程：阈值与冻结 ----------

    @Test
    void goodGood_passesAtRatio9_thenFurtherResultRejected409AndRatioFrozen() throws Exception {
        String batchKey = "BK-SQ-PASS-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        MvcResult r1 = submitSeq(batchKey, seqReq("RID-1", "op-a", 1, "GOOD"), 201);
        JsonNode n1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals("CONTINUE", n1.path("assayStatus").asText());
        assertEquals(3, n1.path("ratioNumerator").asLong());
        assertEquals(1, n1.path("ratioDenominator").asLong());
        assertEquals("QUARANTINED", n1.path("batchStatus").asText());

        MvcResult r2 = submitSeq(batchKey, seqReq("RID-2", "op-b", 2, "GOOD"), 201);
        JsonNode n2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals("PASS", n2.path("assayStatus").asText());
        assertEquals(9, n2.path("ratioNumerator").asLong());
        assertEquals(1, n2.path("ratioDenominator").asLong());
        assertEquals("9.000000", n2.path("ratioDisplay").asText());

        // 终止后新结果 409，且精确分数不被改写
        submitSeq(batchKey, seqReq("RID-3", "op-c", 3, "BAD"), 409);
        assertEquals(2, countSeqRows(batchKey));
        assertEquals(9, jdbc.queryForObject(
                "SELECT ratio_num FROM sequential_result WHERE batch_key = ? AND sample_number = 2",
                Long.class, batchKey));

        // 历史冻结完整前缀、分数与结论
        JsonNode assay = history(batchKey).path("sequentialAssay");
        assertEquals("PASS", assay.path("status").asText());
        assertEquals(2, assay.path("results").size());
        assertEquals(9, assay.path("ratioNumerator").asLong());
        assertEquals(1, assay.path("ratioDenominator").asLong());
        assertEquals("GOOD", assay.path("results").get(0).path("outcome").asText());
        assertEquals("op-a", assay.path("results").get(0).path("operator").asText());
    }

    @Test
    void badBad_failsAtRatioOneNinth_batchRejected_cannotFlipApproveOrSplit() throws Exception {
        String batchKey = "BK-SQ-FAIL-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        MvcResult r1 = submitSeq(batchKey, seqReq("RID-1", "op", 1, "BAD"), 201);
        JsonNode n1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals("CONTINUE", n1.path("assayStatus").asText());
        assertEquals(1, n1.path("ratioNumerator").asLong());
        assertEquals(3, n1.path("ratioDenominator").asLong());

        MvcResult r2 = submitSeq(batchKey, seqReq("RID-2", "op", 2, "BAD"), 201);
        JsonNode n2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals("FAIL", n2.path("assayStatus").asText());
        assertEquals(1, n2.path("ratioNumerator").asLong());
        assertEquals(9, n2.path("ratioDenominator").asLong());
        assertEquals("REJECTED", n2.path("batchStatus").asText());
        assertEquals("REJECTED", currentStatus(batchKey));

        // 不能累积后续好样本翻转 FAIL；不能批准、不能拆分、不能再提交必做检验
        submitSeq(batchKey, seqReq("RID-3", "op", 3, "GOOD"), 409);
        assertEquals("REJECTED", currentStatus(batchKey));
        approve(batchKey, "qa", "QUALITY", "CK-A-" + unique(), 409);
        split(batchKey, List.of("BK-SQ-F1-" + unique(), "BK-SQ-F2-" + unique()), 409);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 409);
        assertEquals(2, countSeqRows(batchKey));
    }

    // ---------- 编号连续性 ----------

    @Test
    void numbering_gapAndOverwriteRejected_rejectionDoesNotConsumeNumber() throws Exception {
        String batchKey = "BK-SQ-NUM-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        submitSeq(batchKey, seqReq("RID-G", "op", 2, "GOOD"), 409);
        submitSeq(batchKey, seqReq("RID-1", "op", 1, "GOOD"), 201);
        // 样本不可覆盖：同编号不同内容 409
        submitSeq(batchKey, seqReq("RID-DUP", "op", 1, "BAD"), 409);
        // 跳号 409，且拒绝不增序号：2 号仍可用
        submitSeq(batchKey, seqReq("RID-3", "op", 3, "GOOD"), 409);
        MvcResult r2 = submitSeq(batchKey, seqReq("RID-2", "op", 2, "GOOD"), 201);
        JsonNode n2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals("PASS", n2.path("assayStatus").asText());
        assertEquals(2, countSeqRows(batchKey));
    }

    @Test
    void tenAlternatingResults_inconclusive_cannotApproveAndNoEleventh() throws Exception {
        String batchKey = "BK-SQ-INC-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        for (int i = 1; i <= 10; i++) {
            MvcResult r = submitSeq(batchKey,
                    seqReq("RID-" + i, "op", i, i % 2 == 1 ? "GOOD" : "BAD"), 201);
            JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
            if (i < 10) {
                assertEquals("CONTINUE", node.path("assayStatus").asText());
            } else {
                // 满 10 次仍未到阈值：INCONCLUSIVE，比值精确为 1
                assertEquals("INCONCLUSIVE", node.path("assayStatus").asText());
                assertEquals(1, node.path("ratioNumerator").asLong());
                assertEquals(1, node.path("ratioDenominator").asLong());
            }
        }
        // 第 11 个结果 409
        submitSeq(batchKey, seqReq("RID-11", "op", 11, "GOOD"), 409);
        assertEquals(10, countSeqRows(batchKey));

        // INCONCLUSIVE 不能批准：必做检验齐全后仍为 422
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        approve(batchKey, "qa", "QUALITY", "CK-A-" + unique(), 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 与既有放行规则联合 ----------

    @Test
    void passStillRequiresRequiredTestsAndDualRoleApproval() throws Exception {
        String batchKey = "BK-SQ-REL-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);
        submitSeq(batchKey, seqReq("RID-1", "op", 1, "GOOD"), 201);
        submitSeq(batchKey, seqReq("RID-2", "op", 2, "GOOD"), 201);

        // 序贯 PASS 不代替必做检验：未齐全时批准 422
        approve(batchKey, "qa", "QUALITY", "CK-A1-" + unique(), 422);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 仍需双角色批准
        approve(batchKey, "qa", "QUALITY", "CK-A2-" + unique(), 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops", "QUALITY", "CK-A3-" + unique(), 409);
        approve(batchKey, "ops", "OPERATIONS", "CK-A4-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void notStartedAssay_cannotApprove() throws Exception {
        String batchKey = "BK-SQ-NS-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-A-" + unique(), 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    @Test
    void nonSequentialBatch_keepsOriginalRules() throws Exception {
        String batchKey = "BK-SQ-OLD-" + unique();
        createBatch(batchKey, List.of("t1"), false, 201);

        // 未启用序贯检验的批次：提交序贯结果 422，原放行规则不变
        submitSeq(batchKey, seqReq("RID-1", "op", 1, "GOOD"), 422);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", 201);
        approve(batchKey, "qa", "QUALITY", "CK-A1-" + unique(), 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-A2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertTrue(history(batchKey).path("sequentialAssay").isNull());
        assertEquals(0, countSeqRows(batchKey));
    }

    // ---------- 幂等 ----------

    @Test
    void requestId_replayAndConflict_failureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-SQ-IDEM-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        // 校验失败（operator 为空 → 400）不占键，同键修正参数后成功
        submitSeq(batchKey,
                "{\"requestId\":\"RID-V\",\"operator\":\"\",\"sampleNumber\":1,\"outcome\":\"GOOD\"}",
                400);
        submitSeq(batchKey, seqReq("RID-V", "op", 1, "GOOD"), 201);

        // 同键同参重放：返回首次完整响应
        MvcResult first = submitSeq(batchKey, seqReq("RID-IDEM", "op", 2, "BAD"), 201);
        MvcResult replay = submitSeq(batchKey, seqReq("RID-IDEM", "op", 2, "BAD"), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键改参 → 409
        submitSeq(batchKey, seqReq("RID-IDEM", "op", 2, "GOOD"), 409);

        // 业务失败（跳号 409）不占键，同键正确使用成功
        submitSeq(batchKey, seqReq("RID-F", "op", 9, "GOOD"), 409);
        submitSeq(batchKey, seqReq("RID-F", "op", 3, "GOOD"), 201);

        assertEquals(3, countSeqRows(batchKey));
        Integer commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SEQUENTIAL_RESULT'",
                Integer.class);
        assertEquals(3, commands, "仅成功命令写入 command_log");
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameNextNumber_exactlyOneSucceedsAndNumberNotConsumed() throws Exception {
        String batchKey = "BK-SQ-RACE-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);

        // 两人竞争同一下一序号：至多一条生效
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-assay/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqReq("RID-A", "op-a", 1, "GOOD"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-assay/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seqReq("RID-B", "op-b", 1, "BAD")))
        );
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
        assertEquals(1, countSeqRows(batchKey));

        // 被拒绝的请求不占用序号：2 号仍可正常提交
        submitSeq(batchKey, seqReq("RID-C", "op-c", 2, "GOOD"), 201);
        assertEquals(2, countSeqRows(batchKey));
    }

    @Test
    void concurrentSameRequestId_replaysFirstResponse() throws Exception {
        String batchKey = "BK-SQ-RACEID-" + unique();
        createBatch(batchKey, List.of("t1"), true, 201);
        String body = seqReq("RID-SAME", "op", 1, "GOOD");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-assay/results")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/sequential-assay/results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发均返回首次结果");
        }
        assertEquals(1, countSeqRows(batchKey));
    }

    // ---------- 拆分与祖先召回 ----------

    @Test
    void split_childrenInheritFlagButReassayIndependently() throws Exception {
        String parent = "BK-SQ-SP-P-" + unique();
        createBatch(parent, List.of("t1"), true, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitSeq(parent, seqReq("RID-1", "op", 1, "GOOD"), 201);
        submitSeq(parent, seqReq("RID-2", "op", 2, "GOOD"), 201);
        approve(parent, "qa", "QUALITY", "CK-A1-" + unique(), 201);
        approve(parent, "ops", "OPERATIONS", "CK-A2-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(parent));

        String child1 = "BK-SQ-SP-C1-" + unique();
        String child2 = "BK-SQ-SP-C2-" + unique();
        split(parent, List.of(child1, child2), 201);

        // 子批继承序贯检验标记，但独立重新检验：不继承父批比值
        JsonNode childAssay = history(child1).path("sequentialAssay");
        assertEquals("NOT_STARTED", childAssay.path("status").asText());
        assertEquals(1, childAssay.path("ratioNumerator").asLong());
        assertEquals(0, childAssay.path("results").size());

        MvcResult r = submitSeq(child1, seqReq("RID-C1", "op", 1, "GOOD"), 201);
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        assertEquals(3, node.path("ratioNumerator").asLong());
        assertEquals("CONTINUE", node.path("assayStatus").asText());

        // 父批已 SPLIT：新结果 409
        submitSeq(parent, seqReq("RID-P3", "op", 3, "GOOD"), 409);
    }

    @Test
    void ancestorRecall_blocksSequentialSubmitOnDescendant() throws Exception {
        String parent = "BK-SQ-RC-P-" + unique();
        createBatch(parent, List.of("t1"), true, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitSeq(parent, seqReq("RID-1", "op", 1, "GOOD"), 201);
        submitSeq(parent, seqReq("RID-2", "op", 2, "GOOD"), 201);
        approve(parent, "qa", "QUALITY", "CK-A1-" + unique(), 201);
        approve(parent, "ops", "OPERATIONS", "CK-A2-" + unique(), 201);

        String child = "BK-SQ-RC-C-" + unique();
        split(parent, List.of(child, "BK-SQ-RC-C2-" + unique()), 201);
        recall(parent, "quality-officer", "上游原料风险", 201);

        // 祖先被召回：后代禁止提交序贯检验结果（422）
        submitSeq(child, seqReq("RID-C1", "op", 1, "GOOD"), 422);
        assertEquals(0, countSeqRows(child));
    }

    @Test
    void history_frozenAndUnaffectedByDescendants() throws Exception {
        String parent = "BK-SQ-HIST-P-" + unique();
        createBatch(parent, List.of("t1"), true, 201);
        submitTest(parent, "TK-1", "t1", "PASS", "insp", 201);
        submitSeq(parent, seqReq("RID-1", "op", 1, "GOOD"), 201);
        submitSeq(parent, seqReq("RID-2", "op", 2, "GOOD"), 201);
        approve(parent, "qa", "QUALITY", "CK-A1-" + unique(), 201);
        approve(parent, "ops", "OPERATIONS", "CK-A2-" + unique(), 201);

        String child = "BK-SQ-HIST-C-" + unique();
        split(parent, List.of(child, "BK-SQ-HIST-C2-" + unique()), 201);
        submitSeq(child, seqReq("RID-C1", "op", 1, "BAD"), 201);

        // 父批历史：完整前缀、精确分数与结论冻结，不随后代变化
        JsonNode assay = history(parent).path("sequentialAssay");
        assertEquals("PASS", assay.path("status").asText());
        assertEquals(2, assay.path("results").size());
        assertEquals(9, assay.path("ratioNumerator").asLong());
        assertEquals(1, assay.path("ratioDenominator").asLong());
        assertEquals("9.000000", assay.path("ratioDisplay").asText());
        assertEquals(1, assay.path("results").get(0).path("sampleNumber").asInt());
        assertEquals(2, assay.path("results").get(1).path("sampleNumber").asInt());
        assertEquals(2, countSeqRows(parent));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, boolean sequential, int expected)
            throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":"
                + objectMapper.writeValueAsString(items)
                + ",\"sequentialAssay\":" + sequential + "}";
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String seqReq(String requestId, String operator, int number, String outcome) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"sampleNumber\":" + number + ",\"outcome\":\"" + outcome + "\"}";
    }

    private MvcResult submitSeq(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/sequential-assay/results")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"" + testKey
                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
                + "\",\"inspector\":\"" + inspector + "\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
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

    private void recall(String batchKey, String actor, String reason, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R-" + unique() + "\",\"reason\":\""
                                + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private void split(String parentKey, List<String> childKeys, int expected) throws Exception {
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < childKeys.size(); i++) {
            if (i > 0) {
                children.append(',');
            }
            children.append("{\"batchKey\":\"").append(childKeys.get(i))
                    .append("\",\"batchNo\":\"LOT-C").append(i + 1).append("\"}");
        }
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-S-" + unique() + "\",\"children\":["
                                + children + "]}"))
                .andExpect(status().is(expected));
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private int countSeqRows(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sequential_result WHERE batch_key = ?",
                Integer.class, batchKey);
        return count == null ? 0 : count;
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
