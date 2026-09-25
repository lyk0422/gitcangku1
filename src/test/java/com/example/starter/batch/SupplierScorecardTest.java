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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 供应商评分卡与准入门槛测试：滑动窗口评分、门槛判定、requestId 幂等、
 * 历史不可篡改与并发边界，全部基于真实 H2（MODE=MySQL）数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierScorecardTest {

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
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch");
        jdbc.update("DELETE FROM supplier_threshold");
    }

    // ---------- 滑动窗口评分 ----------

    @Test
    void scoreDetail_releasedPlusOneRecalledMinusTen_windowSortedByBatchKey() throws Exception {
        String supplier = "SUP-SCORE-" + unique();
        String b1 = releaseBatch(supplier, "BK-Z-" + unique());
        String b2 = releaseBatch(supplier, "BK-A-" + unique());
        String b3 = releaseBatch(supplier, "BK-M-" + unique());
        recall(b3, "u", "质量问题", "CK-RC-" + unique(), 201);

        JsonNode score = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(supplier, score.path("supplierId").asText());
        assertEquals(-8, score.path("score").asInt(), "2 个 RELEASED(+1) 与 1 个 RECALLED(-10)");
        assertEquals(3, score.path("windowSize").asInt());
        JsonNode window = score.path("window");
        assertEquals(3, window.size());
        // 窗口清单按批次标识排序：BK-A < BK-M < BK-Z
        assertEquals(b2, window.get(0).path("batchKey").asText());
        assertEquals(b3, window.get(1).path("batchKey").asText());
        assertEquals(b1, window.get(2).path("batchKey").asText());
        assertEquals(1, window.get(0).path("contribution").asInt());
        assertEquals("RELEASED", window.get(0).path("status").asText());
        assertEquals(-10, window.get(1).path("contribution").asInt());
        assertEquals("RECALLED", window.get(1).path("status").asText());
        assertEquals(1, window.get(2).path("contribution").asInt());
        assertEquals("RELEASED", window.get(2).path("status").asText());
    }

    @Test
    void scoreQuery_isReadOnlyAndStable() throws Exception {
        String supplier = "SUP-RO-" + unique();
        releaseBatch(supplier, "BK-RO-" + unique());

        Integer commandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM command_log",
                Integer.class);
        JsonNode first = getJson("/api/suppliers/" + supplier + "/score");
        JsonNode second = getJson("/api/suppliers/" + supplier + "/score");
        getJson("/api/suppliers/" + supplier + "/score-history");
        getJson("/api/suppliers/" + supplier + "/threshold");
        Integer commandsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM command_log",
                Integer.class);

        assertEquals(first.toString(), second.toString(), "重复查询结果一致");
        assertEquals(commandsBefore, commandsAfter, "只读查询不得写入幂等命令日志");
    }

    @Test
    void slidingWindow_keepsOnlyLatest20TerminalBatches() throws Exception {
        String supplier = "SUP-WIN-" + unique();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            keys.add(releaseBatch(supplier, "BK-W" + i + "-" + unique()));
        }
        // 21 个终态批次，窗口只含最近 20 个（第 1 个滑出）
        JsonNode score = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(20, score.path("windowSize").asInt());
        assertEquals(20, score.path("score").asInt());
        List<String> windowKeys = new ArrayList<>();
        score.path("window").forEach(n -> windowKeys.add(n.path("batchKey").asText()));
        assertFalse(windowKeys.contains(keys.get(0)), "最老批次已滑出窗口");
        assertTrue(windowKeys.contains(keys.get(20)));

        // 召回窗口内最老的一批（第 2 个创建的）：窗口变为第 2～21 个，含 1 个召回
        recall(keys.get(1), "u", "召回", "CK-RC-" + unique(), 201);
        JsonNode after = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(20, after.path("windowSize").asInt());
        assertEquals(9, after.path("score").asInt(), "19 个 RELEASED(+1) 与 1 个 RECALLED(-10)");
    }

    @Test
    void score_clippedAtMinus100() throws Exception {
        String supplier = "SUP-CLIP-" + unique();
        for (int i = 0; i < 11; i++) {
            String key = releaseBatch(supplier, "BK-C" + i + "-" + unique());
            recall(key, "u", "召回" + i, "CK-RC-" + unique(), 201);
        }
        JsonNode score = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(-100, score.path("score").asInt(), "11 个召回合计 -110，裁剪到 -100");
    }

    @Test
    void unknownSupplier_zeroScoreEmptyWindowAndNullThreshold() throws Exception {
        String supplier = "SUP-NONE-" + unique();
        JsonNode score = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(0, score.path("score").asInt(), "初始分数为 0");
        assertEquals(0, score.path("windowSize").asInt());
        assertEquals(0, score.path("window").size());

        JsonNode threshold = getJson("/api/suppliers/" + supplier + "/threshold");
        assertTrue(threshold.path("threshold").isNull());
        assertTrue(threshold.path("updatedAt").isNull());

        JsonNode history = getJson("/api/suppliers/" + supplier + "/score-history");
        assertEquals(0, history.path("trajectory").size());
    }

    // ---------- 准入门槛 ----------

    @Test
    void gate_blocksCreateBelowThreshold_withScoreAndThresholdInBody() throws Exception {
        String supplier = "SUP-GATE-" + unique();
        String recalled = releaseBatch(supplier, "BK-G1-" + unique());
        recall(recalled, "u", "召回", "CK-RC-" + unique(), 201);
        // 当前评分 -10；门槛 -5 → 拦截
        setThreshold(supplier, "REQ-G1", -5, 200);

        MvcResult rejected = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, "BK-G2-" + unique(), "CK-G2")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals(-10, body.path("currentScore").asInt());
        assertEquals(-5, body.path("threshold").asInt());

        // 门槛下调到 -10：评分等于门槛 → 放行
        setThreshold(supplier, "REQ-G2", -10, 200);
        createBatch(supplier, "BK-G3-" + unique(), "CK-G3", 201);
        // 门槛上调到 0：立即生效 → 再次拦截
        setThreshold(supplier, "REQ-G3", 0, 200);
        createBatch(supplier, "BK-G4-" + unique(), "CK-G4", 422);
    }

    @Test
    void gate_notSet_noRestriction() throws Exception {
        String supplier = "SUP-NOGATE-" + unique();
        String recalled = releaseBatch(supplier, "BK-N1-" + unique());
        recall(recalled, "u", "召回", "CK-RC-" + unique(), 201);
        // 评分 -10，但未设置门槛 → 不受限制
        createBatch(supplier, "BK-N2-" + unique(), "CK-N2", 201);
    }

    @Test
    void gate_doesNotAffectExistingBatches_andNotRetroactive() throws Exception {
        String supplier = "SUP-EXIST-" + unique();
        // 先创建批次（仍在隔离），再设置高门槛：已存在批次照常检验/批准/召回
        String existing = "BK-EX-" + unique();
        createBatch(supplier, existing, "CK-EX", 201);
        setThreshold(supplier, "REQ-EX", 100, 200);

        submitTest(existing, "TK-1", "t1", "PASS", "insp", 201);
        approve(existing, "qa", "QUALITY", "CK-AQ", 201);
        approve(existing, "ops", "OPERATIONS", "CK-AO", 201);
        assertEquals("RELEASED", currentStatus(existing));
        recall(existing, "u", "召回", "CK-ER", 201);
        assertEquals("RECALLED", currentStatus(existing));
    }

    @Test
    void recallBelowThreshold_blocksSubsequentCreates_butRecallItselfAllowed() throws Exception {
        String supplier = "SUP-RC-" + unique();
        setThreshold(supplier, "REQ-RC", 0, 200);
        // 评分 0 >= 0 → 允许创建并放行
        String key = releaseBatch(supplier, "BK-R1-" + unique());
        // 评分 1 >= 0 → 再创建一个在途批次
        String inflight = "BK-R2-" + unique();
        createBatch(supplier, inflight, "CK-R2", 201);
        // 召回已放行批次：召回操作本身不受评分限制
        recall(key, "u", "严重缺陷", "CK-RR", 201);
        // 评分变为 -10 < 0 → 后续创建立即被拦截
        MvcResult rejected = mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, "BK-R3-" + unique(), "CK-R3")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals(-10, body.path("currentScore").asInt());
        assertEquals(0, body.path("threshold").asInt());
        // 在途批次不受影响，可继续走完放行
        submitTest(inflight, "TK-1", "t1", "PASS", "insp", 201);
        approve(inflight, "qa", "QUALITY", "CK-RQ", 201);
        approve(inflight, "ops", "OPERATIONS", "CK-RO", 201);
        assertEquals("RELEASED", currentStatus(inflight));
    }

    // ---------- 门槛设置幂等与校验 ----------

    @Test
    void thresholdIdempotency_sameKeyReplay_changedParams409_failureNotOccupying() throws Exception {
        String supplier = "SUP-IDEM-" + unique();
        String body = thresholdBody("REQ-T1", 5);
        MvcResult first = mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次结果");
        // 阈值未被重放改变
        assertEquals(5, getJson("/api/suppliers/" + supplier + "/threshold")
                .path("threshold").asInt());

        // 同 requestId 异参 → 409
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody("REQ-T1", 10)))
                .andExpect(status().isConflict());
        assertEquals(5, getJson("/api/suppliers/" + supplier + "/threshold")
                .path("threshold").asInt());

        // 失败不占键：非法阈值 400 后，同 requestId 可正常使用
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody("REQ-T2", 150)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody("REQ-T2", 7)))
                .andExpect(status().isOk());
        assertEquals(7, getJson("/api/suppliers/" + supplier + "/threshold")
                .path("threshold").asInt());
    }

    @Test
    void thresholdValidation_outOfRangeOrMissing_returns400() throws Exception {
        String supplier = "SUP-VAL-" + unique();
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody("REQ-V1", -101)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody("REQ-V2", 101)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":5}"))
                .andExpect(status().isBadRequest());
        // 边界值合法
        setThreshold(supplier, "REQ-V3", -100, 200);
        setThreshold(supplier, "REQ-V4", 100, 200);
    }

    // ---------- 历史轨迹与不可篡改 ----------

    @Test
    void scoreHistory_trajectoryFollowsTerminalEvents() throws Exception {
        String supplier = "SUP-HIST-" + unique();
        String b1 = releaseBatch(supplier, "BK-H1-" + unique());
        String b2 = releaseBatch(supplier, "BK-H2-" + unique());

        // 召回前轨迹：b1(+1)→1，b2(+1)→2
        JsonNode before = getJson("/api/suppliers/" + supplier + "/score-history")
                .path("trajectory");
        assertEquals(2, before.size());
        assertEquals(b1, before.get(0).path("batchKey").asText());
        assertEquals(1, before.get(0).path("contribution").asInt());
        assertEquals(1, before.get(0).path("scoreAfter").asInt());
        assertEquals(b2, before.get(1).path("batchKey").asText());
        assertEquals("RELEASED", before.get(1).path("status").asText());
        assertEquals(2, before.get(1).path("scoreAfter").asInt());

        // 召回后轨迹按当前终态重算：b2 贡献 -10
        recall(b2, "u", "召回", "CK-HR", 201);
        JsonNode after = getJson("/api/suppliers/" + supplier + "/score-history")
                .path("trajectory");
        assertEquals(2, after.size());
        assertEquals(1, after.get(0).path("scoreAfter").asInt());
        assertEquals(b2, after.get(1).path("batchKey").asText());
        assertEquals("RECALLED", after.get(1).path("status").asText());
        assertEquals(-10, after.get(1).path("contribution").asInt());
        assertEquals(-9, after.get(1).path("scoreAfter").asInt());
    }

    @Test
    void terminalBatches_areImmutable_scoreStaysConsistent() throws Exception {
        String supplier = "SUP-IMM-" + unique();
        String released = releaseBatch(supplier, "BK-I1-" + unique());
        // 终态批次不可再提交检验/批准，历史不可篡改
        submitTest(released, "TK-X", "t1", "FAIL", "insp-x", 409);
        approve(released, "qa2", "QUALITY", "CK-IX", 409);
        JsonNode score = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(1, score.path("score").asInt());

        recall(released, "u", "召回", "CK-IR", 201);
        // 已召回批次不可再次召回、不可再检验
        recall(released, "u", "再次召回", "CK-IR2", 409);
        submitTest(released, "TK-Y", "t1", "PASS", "insp-y", 409);
        JsonNode after = getJson("/api/suppliers/" + supplier + "/score");
        assertEquals(-10, after.path("score").asInt());
        // 历史中的批准记录仍然完整保留
        JsonNode history = getJson("/api/batches/" + released + "/history");
        assertEquals(2, history.path("approvals").size());
        assertEquals(1, history.path("tests").size());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentCreates_belowThreshold_allRejectedNoBatchCreated() throws Exception {
        String supplier = "SUP-CONC-" + unique();
        String recalled = releaseBatch(supplier, "BK-C0-" + unique());
        recall(recalled, "u", "召回", "CK-C0", 201);
        setThreshold(supplier, "REQ-C0", 0, 200);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, "BK-CC1-" + unique(), "CK-CC1"))),
                () -> callStatus(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, "BK-CC2-" + unique(), "CK-CC2"))),
                () -> callStatus(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, "BK-CC3-" + unique(), "CK-CC3")))
        );
        for (Future<Integer> f : results) {
            assertEquals(422, f.get(30, TimeUnit.SECONDS), "低于门槛的并发创建一律 422");
        }
        Integer created = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE supplier_id = ? AND status = 'QUARANTINED'",
                Integer.class, supplier);
        assertEquals(0, created, "被拦截的创建不得留下批次");
    }

    @Test
    void concurrentThresholdSet_sameRequestId_sameWinnerForBoth() throws Exception {
        String supplier = "SUP-TCONC-" + unique();
        String body = thresholdBody("REQ-CONC", 3);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(3, getJson("/api/suppliers/" + supplier + "/threshold")
                .path("threshold").asInt());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_threshold WHERE supplier_id = ?",
                Integer.class, supplier);
        assertEquals(1, rows);
        Integer commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'SET_THRESHOLD'"
                        + " AND command_key = 'REQ-CONC'", Integer.class);
        assertEquals(1, commands);
    }

    @Test
    void concurrentCreate_sameCommandKey_belowThreshold_failureDoesNotOccupyKey() throws Exception {
        String supplier = "SUP-FNO-" + unique();
        String recalled = releaseBatch(supplier, "BK-F0-" + unique());
        recall(recalled, "u", "召回", "CK-F0", 201);
        setThreshold(supplier, "REQ-F0", 0, 200);

        // 被门禁拒绝（422）不占 commandKey：同一 commandKey 在门槛解除后可成功复用
        String body = createBody(supplier, "BK-F1-" + unique(), "CK-F1");
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        setThreshold(supplier, "REQ-F1", -100, 200);
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             String supplierId, Instant producedAt, List<String> requiredTests) {
    }

    private String createBody(String supplier, String batchKey, String commandKey) throws Exception {
        return objectMapper.writeValueAsString(new CreateCmd(commandKey, batchKey, "PROD-1",
                "LOT-1", supplier, Instant.parse("2026-01-02T03:04:05Z"), List.of("t1")));
    }

    private void createBatch(String supplier, String batchKey, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, batchKey, commandKey)))
                .andExpect(status().is(expected));
    }

    /**
     * 创建并走完一个单检验项批次的放行流程，返回 batchKey。
     */
    private String releaseBatch(String supplier, String batchKey) throws Exception {
        createBatch(supplier, batchKey, "CK-C-" + unique(), 201);
        submitTest(batchKey, "TK-" + unique(), "t1", "PASS", "insp-" + unique(), 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        return batchKey;
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"" + testKey
                                + "\",\"testItem\":\"" + item + "\",\"result\":\"" + result
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

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private String thresholdBody(String requestId, int threshold) {
        return "{\"requestId\":\"" + requestId + "\",\"threshold\":" + threshold + "}";
    }

    private void setThreshold(String supplier, String requestId, int threshold, int expected)
            throws Exception {
        mockMvc.perform(put("/api/suppliers/" + supplier + "/threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(thresholdBody(requestId, threshold)))
                .andExpect(status().is(expected));
    }

    private JsonNode getJson(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return getJson("/api/batches/" + batchKey + "/history")
                .path("batch").path("status").asText();
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
