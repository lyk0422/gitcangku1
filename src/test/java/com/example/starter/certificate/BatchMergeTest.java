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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合批测试：主流程（父批 MERGED、子批继承检验项并绑定声明）、父物料重新校验、
 * 失败整次回滚、幂等、并发裁决与 MERGED 终态约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private int declarationPriority;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM certificate_declaration_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 合批主流程 ----------

    @Test
    void mergeHappy_parentsMerged_childInheritsTestsAndBinding() throws Exception {
        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        releaseParent(p2, "LOT-P2", List.of("t2"));
        publishDecl("DECL-MG", 1, "PROD-1", List.of("LOT"), 201);

        String child = "BK-MG-C-" + unique();
        MvcResult result = merge("CK-MG-1", List.of(p1, p2), child, "LOT-C1", "DECL-MG", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("QUARANTINED", body.path("childStatus").asText());
        assertEquals("PROD-1", body.path("productCode").asText());
        assertEquals(2, body.path("parentCount").asInt());
        assertEquals(2, body.path("parents").size());
        assertEquals(p1, body.path("parents").get(0).path("batchKey").asText());
        assertEquals("MERGED", body.path("parents").get(0).path("status").asText());
        assertEquals("MERGED", body.path("parents").get(1).path("status").asText());
        assertEquals("DECL-MG", body.path("declarationKey").asText());
        assertEquals("LOT", body.path("matchedPrefix").asText());
        assertFalse(body.path("mergedAt").isNull());

        // 父批终态 MERGED 且不再可用；子批可用并绑定声明
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        List<String> available = availableKeys();
        assertFalse(available.contains(p1));
        assertFalse(available.contains(p2));
        assertTrue(available.contains(child));
        JsonNode cert = certificateJson(child);
        assertEquals("DECL-MG", cert.path("binding").path("declarationKey").asText());
        assertEquals("LOT", cert.path("binding").path("matchedPrefix").asText());

        // 子批必做检验项为父批并集（按请求顺序）
        JsonNode childHistory = historyJson(child);
        List<String> required = new ArrayList<>();
        childHistory.path("batch").path("requiredTests").forEach(t -> required.add(t.asText()));
        assertEquals(List.of("t1", "t2"), required);

        // 合批关系落库
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, child));
    }

    // ---------- 父物料重新校验 ----------

    @Test
    void mergeRevalidatesParentMaterial_mismatchRollsBack() throws Exception {
        String p1 = "BK-MM-P1-" + unique();
        String p2 = "BK-MM-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        // 第二父批物料不同，凭 OTHER-MAT 声明放行
        createBatch(p2, "OTHER-MAT", "LOT-P2", List.of("t1"));
        releaseBatch(p2);
        publishDecl("DECL-MM", 1, "PROD-1", List.of("LOT"), 201);

        String child = "BK-MM-C-" + unique();
        MvcResult rejected = merge("CK-MM-1", List.of(p1, p2), child, "LOT-C1", "DECL-MM", 422);
        assertEquals("MATERIAL_MISMATCH", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        // 整次回滚：子批未创建，父批仍 RELEASED，无合批关系与绑定
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, child));
    }

    @Test
    void mergeParentPrefixMismatch_rollsBack() throws Exception {
        String p1 = "BK-PM-P1-" + unique();
        String p2 = "BK-PM-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        releaseParent(p2, "XX-P2", List.of("t1"));
        publishDecl("DECL-PM", 1, "PROD-1", List.of("LOT"), 201);

        String child = "BK-PM-C-" + unique();
        MvcResult rejected = merge("CK-PM-1", List.of(p1, p2), child, "LOT-C1", "DECL-PM", 422);
        assertEquals("PREFIX_MISMATCH", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    // ---------- 失败分支 ----------

    @Test
    void mergeFailures_409_404_400() throws Exception {
        String released = "BK-MF-R-" + unique();
        releaseParent(released, "LOT-P1", List.of("t1"));
        String quarantined = "BK-MF-Q-" + unique();
        createBatch(quarantined, "PROD-1", "LOT-P2", List.of("t1"));
        publishDecl("DECL-MF", 1, "PROD-1", List.of("LOT"), 201);

        // 父批未放行 → 409
        merge("CK-MF-1", List.of(released, quarantined), "BK-MF-C1-" + unique(), "LOT-C1",
                "DECL-MF", 409);
        // 子批键已存在 → 409
        merge("CK-MF-2", List.of(released, released + "-X"), quarantined, "LOT-C1",
                "DECL-MF", 409);
        // 父批不存在 → 404
        merge("CK-MF-3", List.of(released, "NO-SUCH-PARENT"), "BK-MF-C3-" + unique(), "LOT-C1",
                "DECL-MF", 404);
        // 声明不存在 → 404（两个父批均已放行，确保走到声明校验）
        String released2 = "BK-MF-R2-" + unique();
        releaseParent(released2, "LOT-P5", List.of("t1"));
        merge("CK-MF-4", List.of(released, released2), "BK-MF-C4-" + unique(), "LOT-C1",
                "NO-SUCH-DECL", 404);
        // 请求内父批重复 → 400
        merge("CK-MF-5", List.of(released, released), "BK-MF-C5-" + unique(), "LOT-C1",
                "DECL-MF", 400);
        // 子批键与父批相同 → 400
        merge("CK-MF-6", List.of(released, quarantined), released, "LOT-C1", "DECL-MF", 400);
        // 父批数量不足 → 400
        merge("CK-MF-7", List.of(released), "BK-MF-C7-" + unique(), "LOT-C1", "DECL-MF", 400);

        // 声明已撤回 → 422
        String p2 = "BK-MF-P2-" + unique();
        String p3 = "BK-MF-P3-" + unique();
        releaseParent(p2, "LOT-P8", List.of("t1"));
        releaseParent(p3, "LOT-P9", List.of("t1"));
        publishDecl("DECL-MF-WD", 1, "PROD-1", List.of("LOT"), 201);
        withdraw("DECL-MF-WD", 1, 200);
        MvcResult rejected = merge("CK-MF-8", List.of(p2, p3), "BK-MF-C8-" + unique(), "LOT-C1",
                "DECL-MF-WD", 422);
        assertEquals("DECLARATION_WITHDRAWN", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    // ---------- 合批产物完整放行 ----------

    @Test
    void mergeChild_completesFullReleaseFlow_withSnapshot() throws Exception {
        String p1 = "BK-MC-P1-" + unique();
        String p2 = "BK-MC-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        releaseParent(p2, "LOT-P2", List.of("t2"));
        publishDecl("DECL-MC", 1, "PROD-1", List.of("LOT"), 201);

        String child = "BK-MC-C-" + unique();
        merge("CK-MC-1", List.of(p1, p2), child, "LOT-C1", "DECL-MC", 201);

        // 子批重新检验、双角色批准后放行，快照固化合批声明
        submitTest(child, "t1", "PASS", "insp-1", 201);
        submitTest(child, "t2", "PASS", "insp-2", 201);
        approve(child, "qa-1", "QUALITY", 201);
        approve(child, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(child));
        JsonNode snapshot = historyJson(child).path("releaseSnapshot");
        assertEquals("DECL-MC", snapshot.path("declarationKey").asText());
        assertEquals("LOT", snapshot.path("matchedPrefix").asText());
    }

    // ---------- 合批幂等 ----------

    @Test
    void mergeCommandKey_replayReturnsFirstSnapshot_changedParams409_failureDoesNotOccupyKey()
            throws Exception {
        String p1 = "BK-MI-P1-" + unique();
        String p2 = "BK-MI-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        releaseParent(p2, "LOT-P2", List.of("t1"));
        publishDecl("DECL-MI", 1, "PROD-1", List.of("LOT"), 201);

        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI-1", List.of(p1, p2), child, "LOT-C1", "DECL-MI");
        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复合批
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));

        // 同 commandKey 改参 → 409
        String changed = mergeBody("CK-MI-1", List.of(p1, p2), "BK-MI-C2-" + unique(), "LOT-C2",
                "DECL-MI");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：子批键冲突 409 后同键修正参数成功
        String p3 = "BK-MI-P3-" + unique();
        String p4 = "BK-MI-P4-" + unique();
        releaseParent(p3, "LOT-P3", List.of("t1"));
        releaseParent(p4, "LOT-P4", List.of("t1"));
        String conflict = mergeBody("CK-MI-2", List.of(p3, p4), child, "LOT-C1", "DECL-MI");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict());
        String fixed = mergeBody("CK-MI-2", List.of(p3, p4), "BK-MI-C3-" + unique(), "LOT-C3",
                "DECL-MI");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMerges_sharingParent_exactlyOneSucceeds() throws Exception {
        String shared = "BK-CM-S-" + unique();
        String other = "BK-CM-O-" + unique();
        releaseParent(shared, "LOT-P1", List.of("t1"));
        releaseParent(other, "LOT-P2", List.of("t1"));
        publishDecl("DECL-CM", 1, "PROD-1", List.of("LOT"), 201);

        String childA = "BK-CM-CA-" + unique();
        String childB = "BK-CM-CB-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM-A", List.of(shared, other), childA, "LOT-CA",
                                "DECL-CM"))),
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-CM-B", List.of(shared, other), childB, "LOT-CB",
                                "DECL-CM")))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 共享父批只能参与一次合批：恰一个 201，另一个 409
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "共享父批的并发合批恰有一个成功: " + first + ", " + second);
        assertTrue(first == 409 || second == 409);
        assertEquals("MERGED", currentStatus(shared));
        assertEquals("MERGED", currentStatus(other));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    // ---------- MERGED 终态 ----------

    @Test
    void mergedParent_isTerminal_notTestableApprovableRecallableOrSplittable() throws Exception {
        String p1 = "BK-MT-P1-" + unique();
        String p2 = "BK-MT-P2-" + unique();
        releaseParent(p1, "LOT-P1", List.of("t1"));
        releaseParent(p2, "LOT-P2", List.of("t1"));
        publishDecl("DECL-MT", 1, "PROD-1", List.of("LOT"), 201);
        String child = "BK-MT-C-" + unique();
        merge("CK-MT-1", List.of(p1, p2), child, "LOT-C1", "DECL-MT", 201);

        submitTest(p1, "t1", "PASS", "insp-9", 409);
        approve(p1, "qa-9", "QUALITY", 409);
        mockMvc.perform(post("/api/batches/" + p1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-MT-R\",\"reason\":\"r\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + p1 + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-MT-S\",\"children\":["
                                + "{\"batchKey\":\"BK-MT-X1-" + unique()
                                + "\",\"batchNo\":\"L1\"},{\"batchKey\":\"BK-MT-X2-" + unique()
                                + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().isConflict());
        // MERGED 父批不可再次参与合批
        String p3 = "BK-MT-P3-" + unique();
        releaseParent(p3, "LOT-P3", List.of("t1"));
        merge("CK-MT-2", List.of(p1, p3), "BK-MT-C2-" + unique(), "LOT-C2", "DECL-MT", 409);
        assertEquals("MERGED", currentStatus(p1));
        assertFalse(availableKeys().contains(p1));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, String productCode, String batchNo,
                             List<String> items) throws Exception {
        String tests = items.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(","));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"" + productCode
                                + "\",\"batchNo\":\"" + batchNo
                                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":["
                                + tests + "]}"))
                .andExpect(status().isCreated());
    }

    /**
     * 创建 PROD-1 父批并走完 检验 PASS + 绑定 + 双角色批准 进入 RELEASED。
     */
    private void releaseParent(String batchKey, String batchNo, List<String> items)
            throws Exception {
        createBatch(batchKey, "PROD-1", batchNo, items);
        releaseBatch(batchKey);
    }

    /**
     * 让既有批次完成 检验 PASS + 绑定（声明前缀取批次自身批号）+ 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey) throws Exception {
        JsonNode batch = historyJson(batchKey).path("batch");
        for (JsonNode item : batch.path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", "insp-1", 201);
        }
        String declKey = "DECL-R-" + unique();
        publishDecl(declKey, 1, batch.path("productCode").asText(),
                List.of(batch.path("batchNo").asText()), 201);
        MvcResult bound = mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BIND-" + unique()
                                + "\",\"declarationKey\":\"" + declKey + "\",\"version\":1}"))
                .andReturn();
        int bindStatus = bound.getResponse().getStatus();
        assertTrue(bindStatus == 200 || bindStatus == 201, "绑定应成功: " + bindStatus);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
    }

    private void publishDecl(String declarationKey, int version, String materialCode,
                             List<String> prefixes, int expected) throws Exception {
        String pfx = prefixes.stream().map(p -> "\"" + p + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PUB-" + unique() + "\",\"declarationKey\":\""
                                + declarationKey + "\",\"version\":" + version
                                + ",\"supplierId\":\"SUP-1\",\"materialCode\":\"" + materialCode
                                + "\",\"prefixes\":[" + pfx + "],\"priority\":"
                                + (++declarationPriority)
                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\","
                                + "\"validTo\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declarationKey, int version, int expected) throws Exception {
        mockMvc.perform(post("/api/declarations/" + declarationKey + "/versions/" + version
                        + "/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-WD-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private String mergeBody(String commandKey, List<String> parents, String childKey,
                             String childBatchNo, String declarationKey) {
        String parentJson = parents.stream().map(p -> "\"" + p + "\"")
                .collect(Collectors.joining(","));
        return "{\"commandKey\":\"" + commandKey + "\",\"parentBatchKeys\":[" + parentJson
                + "],\"childBatchKey\":\"" + childKey + "\",\"childBatchNo\":\"" + childBatchNo
                + "\",\"declarationKey\":\"" + declarationKey + "\",\"declarationVersion\":1}";
    }

    private MvcResult merge(String commandKey, List<String> parents, String childKey,
                            String childBatchNo, String declarationKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, parents, childKey, childBatchNo,
                                declarationKey)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"" + item + "\",\"result\":\""
                                + result + "\",\"inspector\":\"" + inspector + "\"}"))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        return historyJson(batchKey).path("batch").path("status").asText();
    }

    private JsonNode historyJson(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode certificateJson(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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