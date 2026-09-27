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
 * 合批测试：多父加权效价主流程、父批未检验/未登记模型/效价不合格/权重不守恒整次回滚、
 * commandKey 幂等、多父血缘与祖先召回拦截、并发合批裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeTest {

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

    // ---------- 合批主流程 ----------

    @Test
    void happyMerge_weightedPotency_parentsMerged_childQuarantined() throws Exception {
        // 父批 A：初始 100 不衰减，权重 2；父批 B：初始 80 不衰减，权重 3
        String parentA = "BK-MG-A-" + unique();
        String parentB = "BK-MG-B-" + unique();
        releasedWithModel(parentA, "100", "10", "0");
        releasedWithModel(parentB, "80", "10", "0");

        String child = "BK-MG-C-" + unique();
        // 请求内父批乱序：规范化排序不影响结果
        MvcResult result = merge("CK-MG-1",
                List.of(new String[]{parentB, "3"}, new String[]{parentA, "2"}),
                new String[]{child, "L-M1", "5"}, 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("QUARANTINED", body.path("childStatus").asText());
        // 子批初始效价 = (100*2 + 80*3) / 5 = 88
        assertEquals(0, new BigDecimal("88.0000").compareTo(
                body.path("childInitialPotency").decimalValue()));
        assertEquals(0, new BigDecimal("5").compareTo(body.path("childWeight").decimalValue()));
        assertEquals(2, body.path("parents").size());
        // 父批按规范化排序（batchKey 升序）返回，均落定 MERGED，携带实际/要求/差额
        assertEquals(parentA, body.path("parents").get(0).path("batchKey").asText());
        assertEquals("MERGED", body.path("parents").get(0).path("status").asText());
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                body.path("parents").get(0).path("currentPotency").decimalValue()));
        assertEquals(0, new BigDecimal("90.0000").compareTo(
                body.path("parents").get(0).path("difference").decimalValue()));
        assertEquals(parentB, body.path("parents").get(1).path("batchKey").asText());

        // 父批 MERGED 不再可用；子批出现在可用列表
        assertEquals("MERGED", currentStatus(parentA));
        assertEquals("MERGED", currentStatus(parentB));
        List<String> available = availableKeys();
        assertFalse(available.contains(parentA));
        assertFalse(available.contains(parentB));
        assertTrue(available.contains(child));

        // 子批模型 v1：初始效价 88，阈值/衰减率继承规范化首个父批
        MvcResult potency = mockMvc.perform(get("/api/batches/" + child + "/potency"))
                .andExpect(status().isOk()).andReturn();
        JsonNode model = objectMapper.readTree(potency.getResponse().getContentAsString())
                .path("model");
        assertEquals(1, model.path("version").asInt());
        assertEquals(0, new BigDecimal("88.0000").compareTo(
                model.path("initialPotency").decimalValue()));

        // 多父血缘：两条边，子批祖先含两个父批
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(2, ancestorNodes.size());
        assertEquals(parentA, ancestorNodes.get(0).path("batchKey").asText());
        assertEquals(parentB, ancestorNodes.get(1).path("batchKey").asText());

        // 每个父批固化一条 MERGE 评估记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evaluation WHERE batch_key = ? AND operation = 'MERGE'",
                Integer.class, parentA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evaluation WHERE batch_key = ? AND operation = 'MERGE'",
                Integer.class, parentB));
    }

    @Test
    void merge_parentPotencyUnqualified_returns422_wholeRollback() throws Exception {
        String parentA = "BK-MF-A-" + unique();
        String parentB = "BK-MF-B-" + unique();
        releasedWithModel(parentA, "100", "10", "0");
        // 父批 B 每天衰减 20，阈值 50：放行时 100 合格，5 天后为 0 不合格
        releasedWithModel(parentB, "100", "50", "20");

        clock.plusDays(5);
        String child = "BK-MF-C-" + unique();
        MvcResult result = merge("CK-MF-1",
                List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                new String[]{child, "L1", "5"}, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(parentB, error.path("details").path("parentBatchKey").asText());
        assertEquals(0, new BigDecimal("0.0000").compareTo(
                error.path("details").path("actualPotency").decimalValue()));
        assertEquals(0, new BigDecimal("50").compareTo(
                error.path("details").path("requiredPotency").decimalValue()));
        assertEquals(0, new BigDecimal("-50.0000").compareTo(
                error.path("details").path("difference").decimalValue()));

        // 整次回滚：无子批、无血缘、无评估记录，父批仍 RELEASED
        assertEquals("RELEASED", currentStatus(parentA));
        assertEquals("RELEASED", currentStatus(parentB));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evaluation WHERE operation = 'MERGE'",
                Integer.class));

        // 失败不占键：修订 B 的模型阈值后同键重试成功
        registerModel(parentB, "CK-MF-M2", "100", "0", "0", 201);
        merge("CK-MF-1", List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                new String[]{child, "L1", "5"}, 201);
        assertEquals("MERGED", currentStatus(parentA));
        assertEquals("QUARANTINED", currentStatus(child));
    }

    // ---------- 合批失败分支 ----------

    @Test
    void merge_weightNotConserved_returns422() throws Exception {
        String parentA = "BK-MW-A-" + unique();
        String parentB = "BK-MW-B-" + unique();
        releasedWithModel(parentA, "100", "10", "0");
        releasedWithModel(parentB, "80", "10", "0");

        MvcResult result = merge("CK-MW-1",
                List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                new String[]{"BK-MW-C-" + unique(), "L1", "6"}, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("6").compareTo(
                error.path("details").path("requiredWeight").decimalValue()));
        assertEquals(0, new BigDecimal("5").compareTo(
                error.path("details").path("actualWeight").decimalValue()));
        assertEquals("RELEASED", currentStatus(parentA));
        assertEquals("RELEASED", currentStatus(parentB));

        // 非正权重 → 422
        merge("CK-MW-2", List.of(new String[]{parentA, "0"}, new String[]{parentB, "3"}),
                new String[]{"BK-MW-C2-" + unique(), "L1", "3"}, 422);
    }

    @Test
    void merge_parentNotReleasedOrWithoutModel_returns422() throws Exception {
        String released = "BK-MNR-R-" + unique();
        releasedWithModel(released, "100", "10", "0");

        // 父批未检验（QUARANTINED）→ 422
        String quarantined = "BK-MNR-Q-" + unique();
        createBatch(quarantined, List.of("t1"), 201);
        merge("CK-MNR-1", List.of(new String[]{released, "2"}, new String[]{quarantined, "3"}),
                new String[]{"BK-MNR-C1-" + unique(), "L1", "5"}, 422);

        // 父批检验中（PENDING_RELEASE）→ 422
        String pending = "BK-MNR-P-" + unique();
        createBatch(pending, List.of("t1"), 201);
        submitTest(pending, "t1", "PASS", "insp", 201);
        merge("CK-MNR-2", List.of(new String[]{released, "2"}, new String[]{pending, "3"}),
                new String[]{"BK-MNR-C2-" + unique(), "L1", "5"}, 422);

        // 父批已放行但未登记效价模型 → 422
        String noModel = "BK-MNR-N-" + unique();
        createBatch(noModel, List.of("t1"), 201);
        submitTest(noModel, "t1", "PASS", "insp", 201);
        approve(noModel, "qa-1", "QUALITY", "CK-MNR-A1", 201);
        approve(noModel, "ops-1", "OPERATIONS", "CK-MNR-A2", 201);
        merge("CK-MNR-3", List.of(new String[]{released, "2"}, new String[]{noModel, "3"}),
                new String[]{"BK-MNR-C3-" + unique(), "L1", "5"}, 422);

        assertEquals("RELEASED", currentStatus(released));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
    }

    @Test
    void merge_unknownParent404_childKeyConflict409_terminalParent409() throws Exception {
        String released = "BK-MX-R-" + unique();
        releasedWithModel(released, "100", "10", "0");

        // 父批不存在 → 404
        merge("CK-MX-1", List.of(new String[]{released, "2"}, new String[]{"NO-SUCH", "3"}),
                new String[]{"BK-MX-C1-" + unique(), "L1", "5"}, 404);

        // 子批键已存在 → 409
        String other = "BK-MX-O-" + unique();
        releasedWithModel(other, "90", "10", "0");
        merge("CK-MX-2", List.of(new String[]{released, "2"}, new String[]{other, "3"}),
                new String[]{released, "L1", "5"}, 409);

        // 父批键请求内重复 → 409
        merge("CK-MX-3", List.of(new String[]{released, "2"}, new String[]{released, "3"}),
                new String[]{"BK-MX-C3-" + unique(), "L1", "5"}, 409);

        // 已 MERGED 的父批不能再次合批 → 409
        String parentB = "BK-MX-B-" + unique();
        releasedWithModel(parentB, "80", "10", "0");
        merge("CK-MX-4", List.of(new String[]{released, "2"}, new String[]{parentB, "3"}),
                new String[]{"BK-MX-C4-" + unique(), "L1", "5"}, 201);
        String parentC = "BK-MX-C-" + unique();
        releasedWithModel(parentC, "70", "10", "0");
        merge("CK-MX-5", List.of(new String[]{released, "2"}, new String[]{parentC, "3"}),
                new String[]{"BK-MX-C5-" + unique(), "L1", "5"}, 409);
    }

    // ---------- 合批幂等 ----------

    @Test
    void merge_commandKeyReplayAndConflict() throws Exception {
        String parentA = "BK-MI-A-" + unique();
        String parentB = "BK-MI-B-" + unique();
        releasedWithModel(parentA, "100", "10", "0");
        releasedWithModel(parentB, "80", "10", "0");
        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI",
                List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                new String[]{child, "L1", "5"});

        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不产生额外血缘与评估记录
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM potency_evaluation WHERE operation = 'MERGE'",
                Integer.class));

        // 同键改参 → 409；同键不同操作者 → 409
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI",
                                List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                                new String[]{"BK-MI-C2-" + unique(), "L1", "5"})))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "op-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---------- 多父血缘召回 ----------

    @Test
    void recallOneMergeParent_blocksChildAndDescendants() throws Exception {
        String parentA = "BK-MR-A-" + unique();
        String parentB = "BK-MR-B-" + unique();
        releasedWithModel(parentA, "100", "10", "0");
        releasedWithModel(parentB, "80", "10", "0");
        String child = "BK-MR-C-" + unique();
        merge("CK-MR-1", List.of(new String[]{parentA, "2"}, new String[]{parentB, "3"}),
                new String[]{child, "L1", "5"}, 201);

        // MERGED 父批可召回；召回后合批子批立即不可用
        recall(parentA, "qa-lead", "原料污染", "CK-MR-R1", 201);
        assertEquals("RECALLED", currentStatus(parentA));
        assertFalse(availableKeys().contains(child));
        // 子批自身状态不改写，但禁止新增检验
        assertEquals("QUARANTINED", currentStatus(child));
        submitTest(child, "t1", "PASS", "insp-x", 422);

        // 祖先查询：parentA 自身被直接召回（不算祖先召回），parentB 无召回祖先
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(parentA, nodes.get(0).path("batchKey").asText());
        assertEquals("RECALLED", nodes.get(0).path("status").asText());
        assertTrue(nodes.get(0).path("unavailableDueToRecalledAncestor").isNull());
        assertEquals(parentB, nodes.get(1).path("batchKey").asText());
        assertTrue(nodes.get(1).path("unavailableDueToRecalledAncestor").isNull());

        // 后代查询：合批子批标注导致其不可用的召回祖先
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + parentA + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descendantNodes = objectMapper.readTree(
                descendants.getResponse().getContentAsString());
        assertEquals(1, descendantNodes.size());
        assertEquals(child, descendantNodes.get(0).path("batchKey").asText());
        assertEquals(parentA,
                descendantNodes.get(0).path("unavailableDueToRecalledAncestor").asText());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergesSharingParent_exactlyOneWins() throws Exception {
        String shared = "BK-MC-S-" + unique();
        String otherA = "BK-MC-A-" + unique();
        String otherB = "BK-MC-B-" + unique();
        releasedWithModel(shared, "100", "10", "0");
        releasedWithModel(otherA, "90", "10", "0");
        releasedWithModel(otherB, "80", "10", "0");
        String child1 = "BK-MC-C1-" + unique();
        String child2 = "BK-MC-C2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MC-1",
                                List.of(new String[]{shared, "2"}, new String[]{otherA, "3"}),
                                new String[]{child1, "L1", "5"}))),
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "op-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MC-2",
                                List.of(new String[]{shared, "2"}, new String[]{otherB, "3"}),
                                new String[]{child2, "L1", "5"}))));

        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 共享父批只能被合批一次：恰好一个 201，另一个 409（父批已 MERGED）
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "共享父批的两次合批恰好一次成功: " + first + "/" + second);
        assertEquals(1, (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0));
        assertEquals("MERGED", currentStatus(shared));
        // 只有一个子批被创建
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class,
                child1, child2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建批次、登记效价模型并走完检验与双角色批准进入 RELEASED。
     */
    private void releasedWithModel(String batchKey, String initial, String min, String rate)
            throws Exception {
        createBatch(batchKey, List.of("t1"), 201);
        registerModel(batchKey, "CK-M-" + unique(), initial, min, rate, 201);
        submitTest(batchKey, "t1", "PASS", "insp", 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
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

    private void registerModel(String batchKey, String commandKey, String initial, String min,
                               String rate, int expected) throws Exception {
        mockMvc.perform(put("/api/batches/" + batchKey + "/potency-model")
                        .header("X-Actor-Id", "qa-modeler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"initialPotency\":" + initial
                                + ",\"minReleasePotency\":" + min + ",\"decayRatePerDay\":" + rate
                                + "}"))
                .andExpect(status().is(expected));
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

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private String mergeBody(String commandKey, List<String[]> parents, String[] child) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(parents.get(i)[0])
                    .append("\",\"weight\":").append(parents.get(i)[1]).append("}");
        }
        sb.append("],\"child\":{\"batchKey\":\"").append(child[0])
                .append("\",\"batchNo\":\"").append(child[1])
                .append("\",\"weight\":").append(child[2]).append("}}");
        return sb.toString();
    }

    private MvcResult merge(String commandKey, List<String[]> parents, String[] child, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, parents, child)))
                .andExpect(status().is(expected)).andReturn();
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
