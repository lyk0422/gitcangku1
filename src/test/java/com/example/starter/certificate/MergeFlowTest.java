package com.example.starter.certificate;

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
 * 合批端到端测试：主流程、父物料重新校验、失败整次回滚、commandKey 幂等、并发裁决、
 * 合批产物重新绑定后放行全链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MergeFlowTest {

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
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM merge_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyMerge_parentsMerged_childQuarantined_thenRebindAndRelease() throws Exception {
        String decl = "DECL-M-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-M-P1-" + unique();
        String p2 = "BK-M-P2-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1", "t2"), decl);
        releaseBatch(p2, "PROD-1", "LOT-2", List.of("t2", "t3"), decl);

        String child = "BK-M-C-" + unique();
        MvcResult result = merge("CK-M-1", child, "LOT-M1", List.of(p1, p2), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("PROD-1", body.path("productCode").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals(2, body.path("parentCount").asInt(), "实际父批数量");
        assertEquals(List.of("t1", "t2", "t3"), objectMapper.convertValue(
                body.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 父批 MERGED 终态且不再可用；子批可用
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        List<String> available = availableKeys();
        assertFalse(available.contains(p1));
        assertFalse(available.contains(p2));
        assertTrue(available.contains(child));

        // 合批血缘落库；合批产物不继承绑定（须重新校验绑定）
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE child_key = ?", Integer.class, child));
        JsonNode cert = certificate(child);
        assertTrue(cert.path("binding").isNull(), "合批产物不继承父批绑定");
        assertTrue(cert.path("releaseSnapshot").isNull());

        // 父批历史与放行快照不改写
        JsonNode p1History = history(p1);
        assertEquals("MERGED", p1History.path("batch").path("status").asText());
        assertEquals(decl, p1History.path("releaseSnapshot").path("declarationKey").asText());

        // 合批产物重新绑定后走完整检验与放行链路
        bind(child, decl, 201);
        submitPass(child, "t1");
        submitPass(child, "t2");
        submitPass(child, "t3");
        approve(child, "qa-1", "QUALITY", 201);
        approve(child, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(child));
        assertEquals(decl, certificate(child).path("releaseSnapshot").path("declarationKey")
                .asText());
    }

    // ---------- 失败分支与回滚 ----------

    @Test
    void mergeInvalidParams_return400and409() throws Exception {
        String decl = "DECL-MP-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-MP-P1-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl);

        // 父批数量实际为 1，要求 2～5 → 400
        merge("CK-MP-1", "BK-MP-C1-" + unique(), "LOT-M", List.of(p1), 400);
        // 6 个父批 → 400
        merge("CK-MP-2", "BK-MP-C2-" + unique(), "LOT-M",
                List.of(p1, "a", "b", "c", "d", "e"), 400);
        // 父批键重复 → 409
        merge("CK-MP-3", "BK-MP-C3-" + unique(), "LOT-M", List.of(p1, p1), 409);
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MP-4", "BK-MP-C4-" + unique(), "LOT-M",
                                List.of(p1, "x"))))
                .andExpect(status().isBadRequest());
        // 父批不存在 → 404
        merge("CK-MP-5", "BK-MP-C5-" + unique(), "LOT-M", List.of(p1, "NO-SUCH-PARENT"), 404);
        // 父批未放行 → 409
        String pending = "BK-MP-PEND-" + unique();
        createBatch(pending, "PROD-1", "LOT-9", List.of("t1"), 201);
        merge("CK-MP-7", "BK-MP-C7-" + unique(), "LOT-M", List.of(p1, pending), 409);
        // 子批键已存在 → 409
        String exist = "BK-MP-EXIST-" + unique();
        createBatch(exist, "PROD-1", "LOT-8", List.of("t1"), 201);
        merge("CK-MP-6", exist, "LOT-M", List.of(p1, pending), 409);
        // 全部失败均无残留
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage",
                Integer.class));
        assertEquals("RELEASED", currentStatus(p1));
    }

    @Test
    void mergeMaterialMismatch_returns422_andRollsBack() throws Exception {
        String decl1 = "DECL-MM-1-" + unique();
        String decl2 = "DECL-MM-2-" + unique();
        publish(decl1, "PROD-1", "LOT", 1, 201);
        publish(decl2, "PROD-2", "LOT", 1, 201);
        String p1 = "BK-MM-P1-" + unique();
        String p2 = "BK-MM-P2-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl1);
        releaseBatch(p2, "PROD-2", "LOT-2", List.of("t1"), decl2);

        String child = "BK-MM-C-" + unique();
        merge("CK-MM-1", child, "LOT-M", List.of(p1, p2), 422);
        // 整次回滚：无子批、无血缘、父批状态不变
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage",
                Integer.class));
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
    }

    @Test
    void mergeRevalidatesParentDeclaration_withdrawnDeclarationRollsBack() throws Exception {
        String decl = "DECL-RV-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-RV-P1-" + unique();
        String p2 = "BK-RV-P2-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl);
        releaseBatch(p2, "PROD-1", "LOT-2", List.of("t1"), decl);

        // 放行后声明被撤回：历史快照保留，但合批须重新校验所有父物料 → 422 整次回滚
        withdraw(decl, 201);
        String child = "BK-RV-C-" + unique();
        merge("CK-RV-1", child, "LOT-M", List.of(p1, p2), 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage",
                Integer.class));
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot",
                Integer.class), "父批放行快照不被合批失败改写");

        // 失败不占键：发布新声明并重新绑定不可行（父批已放行），但同键修正为合法父批组合可成功
        String decl2 = "DECL-RV-2-" + unique();
        publish(decl2, "PROD-1", "LOT", 2, 201);
        String p3 = "BK-RV-P3-" + unique();
        String p4 = "BK-RV-P4-" + unique();
        releaseBatch(p3, "PROD-1", "LOT-3", List.of("t1"), decl2);
        releaseBatch(p4, "PROD-1", "LOT-4", List.of("t1"), decl2);
        merge("CK-RV-1", child, "LOT-M", List.of(p3, p4), 201);
        assertEquals("MERGED", currentStatus(p3));
        assertEquals("QUARANTINED", currentStatus(child));
    }

    // ---------- 幂等 ----------

    @Test
    void mergeCommandKey_sameParamsReplaysFirstResult_changedParamsConflicts() throws Exception {
        String decl = "DECL-MI-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-MI-P1-" + unique();
        String p2 = "BK-MI-P2-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl);
        releaseBatch(p2, "PROD-1", "LOT-2", List.of("t1"), decl);
        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI-1", child, "LOT-M", List.of(p1, p2));

        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复创建
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE child_key = ?", Integer.class, child));

        // 同 commandKey 改参（子批号不同）→ 409
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI-1", child, "LOT-M-CHANGED", List.of(p1, p2))))
                .andExpect(status().isConflict());
        // 同 commandKey 换操作人 → 409（指纹含操作者）
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "other-planner")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergeSharingParent_onlyOneSucceeds() throws Exception {
        String decl = "DECL-CM-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-CM-P1-" + unique();
        String p2 = "BK-CM-P2-" + unique();
        String p3 = "BK-CM-P3-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl);
        releaseBatch(p2, "PROD-1", "LOT-2", List.of("t1"), decl);
        releaseBatch(p3, "PROD-1", "LOT-3", List.of("t1"), decl);
        String c1 = "BK-CM-C1-" + unique();
        String c2 = "BK-CM-C2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM-1", c1, "LOT-M1", List.of(p1, p2)))),
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM-2", c2, "LOT-M2", List.of(p2, p3))))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok, "共享父批 P2 的两次合批仅一次成功");
        assertEquals(1, conflict);
        // 每个父批至多参与一次合批；总共只有 2 条合批血缘
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE status = 'QUARANTINED' AND batch_key IN (?, ?)",
                Integer.class, c1, c2));
    }

    @Test
    void concurrentMergeSameCommandKey_sameWinnerForBoth() throws Exception {
        String decl = "DECL-CK-" + unique();
        publish(decl, "PROD-1", "LOT", 1, 201);
        String p1 = "BK-CK-P1-" + unique();
        String p2 = "BK-CK-P2-" + unique();
        releaseBatch(p1, "PROD-1", "LOT-1", List.of("t1"), decl);
        releaseBatch(p2, "PROD-1", "LOT-2", List.of("t1"), decl);
        String child = "BK-CK-C-" + unique();
        String body = mergeBody("CK-CK-1", child, "LOT-M", List.of(p1, p2));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE child_key = ?", Integer.class, child));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void publish(String declKey, String material, String prefix, int priority,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P-" + unique()
                                + "\",\"declarationKey\":\"" + declKey
                                + "\",\"version\":1,\"materialCode\":\"" + material
                                + "\",\"batchNoPrefix\":\"" + prefix + "\",\"priority\":"
                                + priority
                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\","
                                + "\"validTo\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declKey, int expected) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations/" + declKey + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-W-" + unique()
                                + "\",\"reason\":\"供应商撤回\"}"))
                .andExpect(status().is(expected));
    }

    private void createBatch(String batchKey, String productCode, String batchNo,
                             List<String> items, int expected) throws Exception {
        StringBuilder tests = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                tests.append(',');
            }
            tests.append('\"').append(items.get(i)).append('\"');
        }
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"" + productCode
                                + "\",\"batchNo\":\"" + batchNo
                                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[" + tests + "]}"))
                .andExpect(status().is(expected));
    }

    /**
     * 创建批次、绑定声明、检验全部 PASS、双角色批准，进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String productCode, String batchNo,
                              List<String> items, String decl) throws Exception {
        createBatch(batchKey, productCode, batchNo, items, 201);
        bind(batchKey, decl, 201);
        for (String item : items) {
            submitPass(batchKey, item);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private void bind(String batchKey, String decl, int expected) throws Exception {
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-B-" + unique()
                                + "\",\"bindings\":[{\"batchKey\":\"" + batchKey
                                + "\",\"declarationKey\":\"" + decl
                                + "\",\"declarationVersion\":1}]}"))
                .andExpect(status().is(expected));
    }

    private void submitPass(String batchKey, String item) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"" + item
                                + "\",\"result\":\"PASS\",\"inspector\":\"insp-" + unique()
                                + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private String mergeBody(String commandKey, String childKey, String childNo,
                             List<String> parentKeys) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"childBatchKey\":\"").append(childKey)
                .append("\",\"childBatchNo\":\"").append(childNo).append("\",\"parentKeys\":[");
        for (int i = 0; i < parentKeys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(parentKeys.get(i)).append('\"');
        }
        return sb.append("]}").toString();
    }

    private MvcResult merge(String commandKey, String childKey, String childNo,
                            List<String> parentKeys, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, childKey, childNo, parentKeys)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode certificate(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
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
