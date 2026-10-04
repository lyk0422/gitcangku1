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
 * 合批与并发裁决测试：合批主流程、父物料重新校验、整次回滚、
 * 合批幂等、撤回与放行并发按提交顺序裁决、并发绑定串行化。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateMergeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private int declSeq = 0;

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
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 合批主流程 ----------

    @Test
    void mergeHappy_parentsMerged_targetBoundAndReleasable() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String p1 = releasedBatch("PROD-1", "LOT-1", decl);
        String p2 = releasedBatch("PROD-1", "LOT-2", decl);
        String target = "BK-MG-T-" + unique();

        MvcResult result = merge(target, "LOT-M-1", List.of(p1, p2), decl, 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(target, body.path("targetBatchKey").asText());
        assertEquals("PENDING_RELEASE", body.path("targetStatus").asText());
        assertEquals(2, body.path("parentCount").asInt(), "实际合并父批数");
        assertEquals(2, body.path("minParentCount").asInt(), "要求的最少父批数");
        assertEquals(decl, body.path("declarationKey").asText());
        assertEquals("LOT-", body.path("matchedPrefix").asText());

        // 父批进入 MERGED 终态并离开可用列表；合批关系落库
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        List<String> available = availableKeys();
        assertFalse(available.contains(p1));
        assertFalse(available.contains(p2));
        assertTrue(available.contains(target));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE target_batch_key = ?",
                Integer.class, target));

        // 新批次已绑定目标声明，双角色批准后放行并固化快照
        JsonNode cert = certificate(target);
        assertEquals(decl, cert.path("binding").path("declarationKey").asText());
        approve(target, "qa-1", "QUALITY", 201);
        approve(target, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(target));
        assertEquals(decl, certificate(target).path("releaseSnapshot")
                .path("declarationKey").asText());
    }

    // ---------- 合批校验失败 ----------

    @Test
    void mergeValidationFailures_wholeRollback() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String p1 = releasedBatch("PROD-1", "LOT-1", decl);
        String p2 = releasedBatch("PROD-1", "LOT-2", decl);
        // 物料不同的父批（PROD-2 有自己的声明与绑定）
        String decl2 = publishDefault("PROD-2", "LOT-");
        String p3 = releasedBatch("PROD-2", "LOT-3", decl2);

        // 父物料不一致 → 422 MERGE_MATERIAL_MISMATCH
        JsonNode mismatch = objectMapper.readTree(
                merge("BK-MX1-" + unique(), "LOT-M-1", List.of(p1, p3), decl, 422)
                        .getResponse().getContentAsString());
        assertEquals("MERGE_MATERIAL_MISMATCH", mismatch.path("code").asText());

        // 声明物料与父批物料不一致 → 422 MATERIAL_MISMATCH
        JsonNode declMismatch = objectMapper.readTree(
                merge("BK-MX2-" + unique(), "LOT-M-1", List.of(p1, p2), decl2, 422)
                        .getResponse().getContentAsString());
        assertEquals("MATERIAL_MISMATCH", declMismatch.path("code").asText());

        // 目标批号不匹配声明前缀 → 422 BATCH_NO_PREFIX_MISMATCH
        JsonNode prefix = objectMapper.readTree(
                merge("BK-MX3-" + unique(), "OTH-M-1", List.of(p1, p2), decl, 422)
                        .getResponse().getContentAsString());
        assertEquals("BATCH_NO_PREFIX_MISMATCH", prefix.path("code").asText());

        // 未放行父批 → 409
        String pending = "BK-MX-PEND-" + unique();
        createBatch(pending, "PROD-1", "LOT-9", 201);
        merge("BK-MX4-" + unique(), "LOT-M-1", List.of(p1, pending), decl, 409);
        // 父批不存在 → 404
        merge("BK-MX5-" + unique(), "LOT-M-1", List.of(p1, "NO-SUCH-PARENT"), decl, 404);
        // 请求内父批重复 → 409
        merge("BK-MX6-" + unique(), "LOT-M-1", List.of(p1, p1), decl, 409);
        // 目标键已存在 → 409
        merge(p2, "LOT-M-1", List.of(p1, p2), decl, 409);

        // 全部失败均不产生副作用：父批仍 RELEASED，无新批次、无合批关系、无绑定
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals("RELEASED", currentStatus(p3));
        assertEquals(0, countRows("batch_merge"));
        assertEquals(3, countRows("batch_certificate_binding"));
    }

    @Test
    void mergeFailure_doesNotOccupyCommandKey() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String p1 = releasedBatch("PROD-1", "LOT-1", decl);
        String p2 = releasedBatch("PROD-1", "LOT-2", decl);
        String target = "BK-MF-T-" + unique();
        // 先用错误声明物料失败一次
        String decl2 = publishDefault("PROD-2", "LOT-");
        mergeWithKey("CK-MF-1", target, "LOT-M-1", List.of(p1, p2), decl2, 422);
        // 同一 commandKey 修正参数后成功
        mergeWithKey("CK-MF-1", target, "LOT-M-1", List.of(p1, p2), decl, 201);
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("PENDING_RELEASE", currentStatus(target));
    }

    // ---------- 合批幂等 ----------

    @Test
    void mergeCommandKey_sameParamsReplay_changedParams409() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String p1 = releasedBatch("PROD-1", "LOT-1", decl);
        String p2 = releasedBatch("PROD-1", "LOT-2", decl);
        String target = "BK-MI-T-" + unique();
        String body = mergeBody("CK-MI-1", target, "LOT-M-1", List.of(p1, p2), decl);

        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复落库
        assertEquals(2, countRows("batch_merge"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, target));
        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI-1", target, "LOT-M-2", List.of(p1, p2), decl)))
                .andExpect(status().isConflict());
    }
    // ---------- 并发裁决 ----------

    @Test
    void concurrentWithdrawAndFinalApproval_commitOrderDecides() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String batchKey = "BK-CW-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        passTest(batchKey);
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-A\"}")),
                () -> callStatus(post("/api/certificates/declarations/" + decl
                                + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-W\"}"))
        );
        int approval = results.get(0).get(30, TimeUnit.SECONDS);
        int withdraw = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, withdraw, "撤回始终作用于有效声明");
        if (approval == 201) {
            // 放行先提交：快照已固化，撤回不改写历史
            assertEquals("RELEASED", currentStatus(batchKey));
            assertEquals(decl, certificate(batchKey).path("releaseSnapshot")
                    .path("declarationKey").asText());
        } else {
            // 撤回先提交：放行被门禁拦截 422，批次停留 RELEASE_REVIEW，无快照
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            assertTrue(certificate(batchKey).path("releaseSnapshot").isNull());
        }
    }

    @Test
    void concurrentBinds_sameBatch_serializedExactlyOneBinding() throws Exception {
        String decl1 = publishDefault("PROD-1", "LOT-");
        String decl2 = publishDefault("PROD-1", "LOT-");
        String batchKey = "BK-CB-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-1", batchKey, decl1))),
                () -> callStatus(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-2", batchKey, decl2)))
        );
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            assertTrue(code == 201 || code == 200, "并发绑定均成功，按提交顺序后者覆盖: " + code);
        }
        // 串行化后恰好一条绑定，且为两者之一
        assertEquals(1, countRows("batch_certificate_binding"));
        String bound = certificate(batchKey).path("binding").path("declarationKey").asText();
        assertTrue(bound.equals(decl1) || bound.equals(decl2));
    }

    @Test
    void concurrentMergeAndParentRecall_commitOrderDecides() throws Exception {
        String decl = publishDefault("PROD-1", "LOT-");
        String p1 = releasedBatch("PROD-1", "LOT-1", decl);
        String p2 = releasedBatch("PROD-1", "LOT-2", decl);
        String target = "BK-CM-T-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM-M", target, "LOT-M-1", List.of(p1, p2), decl))),
                () -> callStatus(post("/api/batches/" + p1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CM-R\",\"reason\":\"异常\"}"))
        );
        int merge = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        if (merge == 201) {
            // 合批先提交：父批已 MERGED，召回随后只能拒绝（MERGED 非可召回状态）
            assertEquals(409, recall);
            assertEquals("MERGED", currentStatus(p1));
            assertEquals("PENDING_RELEASE", currentStatus(target));
        } else {
            // 召回先提交：父批 RECALLED，合批随之 409，无新批次
            assertEquals(201, recall);
            assertEquals(409, merge);
            assertEquals("RECALLED", currentStatus(p1));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, target));
        }
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String publishDefault(String material, String prefix) throws Exception {
        declSeq++;
        String declKey = "DECL-" + unique();
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P-" + unique() + "\",\"declarationKey\":\""
                                + declKey + "\",\"version\":1,\"materialCode\":\"" + material
                                + "\",\"batchNoPrefix\":\"" + prefix + "\",\"priority\":"
                                + declSeq
                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\","
                                + "\"validTo\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        return declKey;
    }

    private void createBatch(String batchKey, String productCode, String batchNo, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"" + productCode
                                + "\",\"batchNo\":\"" + batchNo
                                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().is(expected));
    }

    private void passTest(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"t1\",\"result\":\"PASS\","
                                + "\"inspector\":\"insp-1\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult bind(String batchKey, String declKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-B-" + unique(), batchKey, declKey)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String bindBody(String commandKey, String batchKey, String declKey) {
        return "{\"commandKey\":\"" + commandKey + "\",\"batchKey\":\"" + batchKey
                + "\",\"declarationKey\":\"" + declKey + "\",\"declarationVersion\":1}";
    }

    /**
     * 创建批次、通过检验、绑定声明并完成双角色批准，返回批次键。
     */
    private String releasedBatch(String productCode, String batchNo, String decl)
            throws Exception {
        String batchKey = "BK-R-" + unique();
        createBatch(batchKey, productCode, batchNo, 201);
        passTest(batchKey);
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        return batchKey;
    }

    private String mergeBody(String commandKey, String target, String targetBatchNo,
                             List<String> parents, String decl) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"targetBatchKey\":\"").append(target)
                .append("\",\"targetBatchNo\":\"").append(targetBatchNo)
                .append("\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(parents.get(i)).append('\"');
        }
        return sb.append("],\"declarationKey\":\"").append(decl)
                .append("\",\"declarationVersion\":1}").toString();
    }

    private MvcResult merge(String target, String targetBatchNo, List<String> parents,
                            String decl, int expected) throws Exception {
        return mergeWithKey("CK-M-" + unique(), target, targetBatchNo, parents, decl, expected);
    }

    private MvcResult mergeWithKey(String commandKey, String target, String targetBatchNo,
                                   List<String> parents, String decl, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, target, targetBatchNo, parents, decl)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode certificate(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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