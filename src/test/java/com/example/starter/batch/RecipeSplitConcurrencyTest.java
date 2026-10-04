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

import java.math.BigDecimal;
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
 * 拆分质量继承与并发互斥测试：按比例精确继承、守恒校验、
 * 称量/更正/放行/拆分在真实 H2 行锁下的并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecipeSplitConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM weighing");
        jdbc.update("DELETE FROM batch_recipe_binding");
        jdbc.update("DELETE FROM recipe_ingredient");
        jdbc.update("DELETE FROM recipe");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 拆分质量继承 ----------

    @Test
    void split_inheritsProportionalMasses_andConserves() throws Exception {
        String parent = prepareReleasedBatch("RCP-SP1", "100",
                new String[][]{{"A", "50"}, {"B", "50"}}, new String[][]{{"A", "50"}, {"B", "50"}});
        String child1 = "BK-SC1-" + unique();
        String child2 = "BK-SC2-" + unique();
        MvcResult result = split(parent, "CK-SP-" + unique(),
                new String[][]{{child1, "L1", "60"}, {child2, "L2", "40"}}, 201);
        JsonNode children = json(result).path("children");
        assertEquals(0, new BigDecimal("60").compareTo(
                new BigDecimal(children.get(0).path("targetMassGrams").asText())));
        assertEquals(0, new BigDecimal("40").compareTo(
                new BigDecimal(children.get(1).path("targetMassGrams").asText())));

        // 子批绑定同一配方，成分质量按 60/40 比例精确继承
        assertBinding(child1, "RCP-SP1", "60");
        assertBinding(child2, "RCP-SP1", "40");
        assertIngredientMass(child1, "A", "30");
        assertIngredientMass(child1, "B", "30");
        assertIngredientMass(child2, "A", "20");
        assertIngredientMass(child2, "B", "20");
        // 继承记录类型与链版本
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND kind = 'INHERIT'",
                Integer.class, child1));
        // 守恒：各成分子批之和精确等于父批有效累计
        for (String ingredient : List.of("A", "B")) {
            BigDecimal parentTotal = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CAST(mass AS DECIMAL(30,6))),0) FROM weighing"
                            + " WHERE batch_key = ? AND ingredient = ? AND superseded_by IS NULL",
                    BigDecimal.class, parent, ingredient);
            BigDecimal childrenTotal = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CAST(mass AS DECIMAL(30,6))),0) FROM weighing"
                            + " WHERE batch_key IN (?, ?) AND ingredient = ?"
                            + " AND superseded_by IS NULL",
                    BigDecimal.class, child1, child2, ingredient);
            assertEquals(0, parentTotal.compareTo(childrenTotal), "成分 " + ingredient + " 质量守恒");
        }
        // 子批可继续称量：链版本从继承记录数开始
        MvcResult w = weigh(child1, "RID-SC1", "A", "10", 2, 201);
        assertEquals(0, new BigDecimal("40").compareTo(
                new BigDecimal(json(w).path("totals").path("A").asText())));
    }

    @Test
    void split_nonExactShare_returns422_andParentUnchanged() throws Exception {
        // 目标 3 克，成分 1/2：子批 1 克时分摊 1/3 无法在 6 位小数内精确表示
        String parent = prepareReleasedBatch("RCP-SP2", "3",
                new String[][]{{"A", "33.3333"}, {"B", "66.6667"}},
                new String[][]{{"A", "1"}, {"B", "2"}});
        String child1 = "BK-NC1-" + unique();
        String child2 = "BK-NC2-" + unique();
        split(parent, "CK-SP-" + unique(),
                new String[][]{{child1, "L1", "1"}, {child2, "L2", "2"}}, 422);
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)",
                Integer.class, child1, child2));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_recipe_binding WHERE batch_key IN (?, ?)",
                Integer.class, child1, child2));
    }

    @Test
    void split_massValidation() throws Exception {
        // 子批质量之和不等于父批目标质量 422
        String parent = prepareReleasedBatch("RCP-SP3", "100",
                new String[][]{{"A", "50"}, {"B", "50"}}, new String[][]{{"A", "50"}, {"B", "50"}});
        split(parent, "CK-SP-" + unique(),
                new String[][]{{"BK-V1-" + unique(), "L1", "70"}, {"BK-V2-" + unique(), "L2", "20"}},
                422);
        assertEquals("RELEASED", currentStatus(parent));
        // 已绑定父批的子批缺目标质量 400；子批质量非正值、超精度 400
        split(parent, "CK-SP-" + unique(),
                new String[][]{{"BK-V3-" + unique(), "L1", null}, {"BK-V4-" + unique(), "L2", "100"}},
                400);
        split(parent, "CK-SP-" + unique(),
                new String[][]{{"BK-V5-" + unique(), "L1", "-1"}, {"BK-V6-" + unique(), "L2", "101"}},
                400);
        split(parent, "CK-SP-" + unique(),
                new String[][]{{"BK-V7-" + unique(), "L1", "0.0000001"},
                        {"BK-V8-" + unique(), "L2", "99.9999999"}}, 400);
        // 未绑定配方的父批，子批不应提供目标质量 400
        String unbound = "BK-UB-" + unique();
        createBatch(unbound);
        submitPass(unbound);
        approve(unbound, "qa-1", "QUALITY", 201);
        approve(unbound, "ops-1", "OPERATIONS", 201);
        split(unbound, "CK-SP-" + unique(),
                new String[][]{{"BK-V9-" + unique(), "L1", "50"}, {"BK-V10-" + unique(), "L2", "50"}},
                400);
        // 未绑定父批不带质量仍可正常拆分（既有行为不变）
        split(unbound, "CK-SP-" + unique(),
                new String[][]{{"BK-V11-" + unique(), "L1", null}, {"BK-V12-" + unique(), "L2", null}},
                201);
    }
    // ---------- 并发互斥 ----------

    @Test
    void concurrentWeighing_sameExpectedVersion_exactlyOneWins() throws Exception {
        String batchKey = prepareBoundBatch("RCP-RW", "100");
        List<Future<Integer>> results = runConcurrent(
                () -> weighStatus(batchKey, "RID-RW1", "A", "50", 0),
                () -> weighStatus(batchKey, "RID-RW2", "A", "50", 0));
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
        assertEquals(1, created, "同一期望版本并发称量仅一个成功");
        assertEquals(1, conflict);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(0, new BigDecimal("50").compareTo(jdbc.queryForObject(
                "SELECT COALESCE(SUM(CAST(mass AS DECIMAL(30,6))),0) FROM weighing"
                        + " WHERE batch_key = ? AND superseded_by IS NULL",
                BigDecimal.class, batchKey)));
    }

    @Test
    void concurrentSameRequestId_bothGetFirstResponse_singleRow() throws Exception {
        String batchKey = prepareBoundBatch("RCP-RI", "100");
        List<Future<String>> results = runConcurrentBodies(
                () -> weighBody(batchKey, "RID-RI1", "A", "50", 0),
                () -> weighBody(batchKey, "RID-RI1", "A", "50", 0));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放均返回首次完整响应");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'WEIGH'"
                        + " AND command_key = 'RID-RI1'", Integer.class));
    }

    @Test
    void concurrentCorrectionAndFinalApproval_serializedNoPartialRelease() throws Exception {
        String batchKey = prepareBoundBatch("RCP-RA", "100");
        long id1 = weighingId(weigh(batchKey, "RID-RA1", "A", "50", 0, 201));
        weigh(batchKey, "RID-RA2", "B", "50", 1, 201);
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", 201);

        // 同时：更正 A 为 49（使配方不达）与第二个放行批准。行锁按提交顺序串行。
        List<Future<Integer>> results = runConcurrent(
                () -> correctStatus(batchKey, "RID-RA3", id1, "A", "49", 2),
                () -> approveStatus(batchKey, "ops-1", "OPERATIONS"));
        int correction = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        String finalStatus = currentStatus(batchKey);
        if (correction == 201) {
            // 更正先提交：配方不达，批准必须 422，批次不得放行
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", finalStatus);
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
        } else {
            // 批准先提交：批次 RELEASED 称量链冻结，更正 409
            assertEquals(409, correction);
            assertEquals(201, approval);
            assertEquals("RELEASED", finalStatus);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weighing WHERE batch_key = ?", Integer.class, batchKey));
        }
    }

    @Test
    void concurrentSplitAndCorrection_parentLocksOutCorrection() throws Exception {
        String parent = prepareReleasedBatch("RCP-RS", "100",
                new String[][]{{"A", "50"}, {"B", "50"}}, new String[][]{{"A", "50"}, {"B", "50"}});
        long id1 = latestValidWeighingId(parent, "A");
        String child1 = "BK-RS1-" + unique();
        String child2 = "BK-RS2-" + unique();
        // 已放行批次上并发拆分与更正：拆分成功，更正因称量链冻结/已拆分被拒
        List<Future<Integer>> results = runConcurrent(
                () -> splitStatus(parent, "CK-RS-" + unique(),
                        new String[][]{{child1, "L1", "60"}, {child2, "L2", "40"}}),
                () -> correctStatus(parent, "RID-RS1", id1, "A", "49", 2));
        assertEquals(201, results.get(0).get(30, TimeUnit.SECONDS));
        assertEquals(409, results.get(1).get(30, TimeUnit.SECONDS));
        assertEquals("SPLIT", currentStatus(parent));
        // 父批有效称量未被改写，子批继承记录已生成
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND superseded_by IS NULL",
                Integer.class, parent));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing WHERE batch_key = ? AND kind = 'INHERIT'",
                Integer.class, child1));
    }
    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long weighingId(MvcResult result) throws Exception {
        return json(result).path("weighingId").asLong();
    }

    private long latestValidWeighingId(String batchKey, String ingredient) {
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM weighing WHERE batch_key = ? AND ingredient = ?"
                        + " AND superseded_by IS NULL",
                Long.class, batchKey, ingredient);
        assertNotNull(id);
        return id;
    }

    private void assertBinding(String batchKey, String recipeKey, String targetMass) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT recipe_key, target_mass FROM batch_recipe_binding WHERE batch_key = ?",
                batchKey);
        assertEquals(recipeKey, row.get("recipe_key"));
        assertEquals(0, new BigDecimal(targetMass).compareTo(
                new BigDecimal((String) row.get("target_mass"))));
    }

    private void assertIngredientMass(String batchKey, String ingredient, String expected) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CAST(mass AS DECIMAL(30,6))),0) FROM weighing"
                        + " WHERE batch_key = ? AND ingredient = ? AND superseded_by IS NULL",
                BigDecimal.class, batchKey, ingredient);
        assertEquals(0, new BigDecimal(expected).compareTo(total),
                batchKey + " 成分 " + ingredient + " 继承量");
    }

    /**
     * 创建指定配方、批次、绑定、称量并检验放行（RELEASED）。
     */
    private String prepareReleasedBatch(String recipeKey, String target, String[][] pcts,
                                        String[][] weighings) throws Exception {
        String batchKey = prepareBoundBatch(recipeKey, target, pcts);
        int version = 0;
        for (String[] w : weighings) {
            weigh(batchKey, "RID-PW-" + unique(), w[0], w[1], version, 201);
            version++;
        }
        submitPass(batchKey);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        return batchKey;
    }

    private String prepareBoundBatch(String recipeKey, String target) throws Exception {
        return prepareBoundBatch(recipeKey, target, new String[][]{{"A", "50"}, {"B", "50"}});
    }

    private String prepareBoundBatch(String recipeKey, String target, String[][] pcts)
            throws Exception {
        createRecipe("RID-P-" + unique(), recipeKey, pcts, 201);
        String batchKey = "BK-" + recipeKey + "-" + unique();
        createBatch(batchKey);
        bind(batchKey, "RID-PB-" + unique(), recipeKey, target, 201);
        return batchKey;
    }

    private void createRecipe(String requestId, String recipeKey, String[][] pcts, int expected)
            throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] pct : pcts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", pct[0]);
            entry.put("percentage", new BigDecimal(pct[1]));
            list.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("recipeKey", recipeKey);
        body.put("ingredients", list);
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
    }

    private void createBatch(String batchKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-B-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", List.of("t1"));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
    private void bind(String batchKey, String requestId, String recipeKey, String targetMass,
                      int expected) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"recipeKey\":\"" + recipeKey
                + "\",\"targetMassGrams\":" + targetMass + "}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/recipe-binding")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String weighJson(String requestId, String ingredient, String mass, int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"ingredient\":\"" + ingredient
                + "\",\"massGrams\":" + mass + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private MvcResult weigh(String batchKey, String requestId, String ingredient, String mass,
                            int expectedVersion, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                        .header("X-Actor-Id", "weigher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson(requestId, ingredient, mass, expectedVersion)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int weighStatus(String batchKey, String requestId, String ingredient, String mass,
                            int expectedVersion) {
        return callStatus(post("/api/batches/" + batchKey + "/weighings")
                .header("X-Actor-Id", "weigher-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(weighJson(requestId, ingredient, mass, expectedVersion)));
    }

    private String weighBody(String batchKey, String requestId, String ingredient, String mass,
                             int expectedVersion) {
        try {
            return mockMvc.perform(post("/api/batches/" + batchKey + "/weighings")
                            .header("X-Actor-Id", "weigher-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(weighJson(requestId, ingredient, mass, expectedVersion)))
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int correctStatus(String batchKey, String requestId, long replacesId, String ingredient,
                              String mass, int expectedVersion) {
        String body = "{\"requestId\":\"" + requestId + "\",\"replacesWeighingId\":" + replacesId
                + ",\"ingredient\":\"" + ingredient + "\",\"massGrams\":" + mass
                + ",\"expectedVersion\":" + expectedVersion + "}";
        return callStatus(post("/api/batches/" + batchKey + "/weighing-corrections")
                .header("X-Actor-Id", "weigher-1")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void submitPass(String batchKey) throws Exception {
        String body = "{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-" + unique()
                + "\",\"testItem\":\"t1\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private int approveStatus(String batchKey, String actor, String role) {
        return callStatus(post("/api/batches/" + batchKey + "/approvals")
                .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"));
    }
    private MvcResult split(String parentKey, String commandKey, String[][] children, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitJson(commandKey, children)))
                .andExpect(status().is(expected)).andReturn();
    }

    private int splitStatus(String parentKey, String commandKey, String[][] children) {
        return callStatus(post("/api/batches/" + parentKey + "/split")
                .contentType(MediaType.APPLICATION_JSON)
                .content(splitJson(commandKey, children)));
    }

    private String splitJson(String commandKey, String[][] children) {
        StringBuilder body = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.length; i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"batchKey\":\"").append(children[i][0]).append("\",\"batchNo\":\"")
                    .append(children[i][1]).append("\"");
            if (children[i][2] != null) {
                body.append(",\"targetMassGrams\":").append(children[i][2]);
            }
            body.append("}");
        }
        return body.append("]}").toString();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("batch").path("status").asText();
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

    @SafeVarargs
    private List<Future<String>> runConcurrentBodies(Callable<String>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
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