package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合批测试：主流程、父物料重新校验、失败整次回滚、commandKey 幂等与并发裁决。真实 H2 库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class MergeBatchTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final String VALID_FROM = "2026-01-01T00:00:00Z";
    private static final String VALID_TO = "2027-01-01T00:00:00Z";

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
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM certificate_binding");
        jdbc.update("DELETE FROM declaration_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM batch");
        clock.setInstant(T0);
    }

    // ---------- 主流程 ----------

    @Test
    void mergeHappy_parentsMerged_childBoundAndQuarantined() throws Exception {
        publish("DECL-M", 1, "MAT-1", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-M", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-M", "MAT-1", "LOT-P2");
        String child = "BK-MG-C-" + unique();

        MvcResult result = merge("CK-MG-1", child, "LOT-M1", List.of(p1, p2), "DECL-M", 1, 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("batchKey").asText());
        assertEquals("MAT-1", body.path("productCode").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals(2, body.path("parentCount").asInt());
        assertEquals(p1, body.path("parents").get(0).asText());
        assertEquals(p2, body.path("parents").get(1).asText());
        assertEquals("DECL-M", body.path("declarationKey").asText());
        assertEquals(1, body.path("declarationVersion").asInt());
        assertEquals("LOT-", body.path("matchedPrefix").asText());
        assertEquals(List.of("t1"), objectMapper.convertValue(body.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("2026-06-01T00:00:00Z", body.path("mergedAt").asText());

        // 父批 MERGED 不再可用；新批绑定已固化
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));
        MvcResult available = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        assertFalse(available.getResponse().getContentAsString().contains(p1));
        assertFalse(available.getResponse().getContentAsString().contains(p2));
        MvcResult cert = mockMvc.perform(get("/api/batches/" + child + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        JsonNode binding = objectMapper.readTree(cert.getResponse().getContentAsString())
                .path("binding");
        assertEquals("DECL-M", binding.path("declarationKey").asText());
        assertEquals("merger-1", binding.path("actorId").asText());
        // 合批血缘落库
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, child));
        assertEquals(List.of(p1, p2), jdbc.queryForList(
                "SELECT parent_key FROM batch_merge WHERE child_key = ? ORDER BY seq",
                String.class, child));
    }

    @Test
    void mergedBatch_canBeReleasedWithInheritedBinding() throws Exception {
        publish("DECL-MR", 1, "MAT-1", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-MR", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-MR", "MAT-1", "LOT-P2");
        String child = "BK-MR-C-" + unique();
        merge("CK-MR-1", child, "LOT-M1", List.of(p1, p2), "DECL-MR", 1, 201);

        // 合批产物携带绑定，可直接检验并放行，放行快照落库
        submitTest(child, "t1", "PASS", "insp", 201);
        approve(child, "qa-1", "QUALITY", "CK-MR-A1", 201);
        approve(child, "ops-1", "OPERATIONS", "CK-MR-A2", 201);
        assertEquals("RELEASED", currentStatus(child));
        MvcResult cert = mockMvc.perform(get("/api/batches/" + child + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        JsonNode release = objectMapper.readTree(cert.getResponse().getContentAsString())
                .path("release");
        assertEquals("DECL-MR", release.path("declarationKey").asText());
        assertEquals("LOT-", release.path("matchedPrefix").asText());
        assertEquals("2026-06-01T00:00:00Z", release.path("releasedAt").asText());
    }

    // ---------- 失败分支 ----------

    @Test
    void mergeValidationErrors_andNoPartialState() throws Exception {
        publish("DECL-MV", 1, "MAT-1", List.of("LOT-"), 1, 201);
        publish("DECL-MV2", 1, "MAT-2", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-MV", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-MV", "MAT-1", "LOT-P2");

        // 父批未放行 → 409
        String quarantined = createBatch("MAT-1", "LOT-Q1");
        merge("CK-MV-1", "BK-MV-N1-" + unique(), "LOT-M1", List.of(p1, quarantined),
                "DECL-MV", 1, 409);
        // 父批不存在 → 404
        merge("CK-MV-2", "BK-MV-N2-" + unique(), "LOT-M1", List.of(p1, "BK-NONE"),
                "DECL-MV", 1, 404);
        // 父批请求内重复 → 409
        merge("CK-MV-3", "BK-MV-N3-" + unique(), "LOT-M1", List.of(p1, p1), "DECL-MV", 1, 409);
        // 新批批号不命中声明前缀 → 422
        merge("CK-MV-4", "BK-MV-N4-" + unique(), "XX-M1", List.of(p1, p2), "DECL-MV", 1, 422);
        // 父物料与声明不一致 → 422（合批重新校验所有父物料）
        String otherMaterial = releasedBatch("DECL-MV2", "MAT-2", "LOT-P3");
        merge("CK-MV-5", "BK-MV-N5-" + unique(), "LOT-M1", List.of(p1, otherMaterial),
                "DECL-MV", 1, 422);
        // 声明已撤回 → 422
        publish("DECL-MVW", 1, "MAT-1", List.of("B-"), 1, 201);
        withdraw("DECL-MVW", 1, "CK-MV-W", 200);
        merge("CK-MV-6", "BK-MV-N6-" + unique(), "B-M1", List.of(p1, p2), "DECL-MVW", 1, 422);
        // 声明不存在 → 422
        merge("CK-MV-7", "BK-MV-N7-" + unique(), "LOT-M1", List.of(p1, p2), "DECL-NONE", 1, 422);
        // 父批数量非法 → 400
        merge("CK-MV-8", "BK-MV-N8-" + unique(), "LOT-M1", List.of(p1), "DECL-MV", 1, 400);

        // 全部失败：父批保持 RELEASED，无新批、无血缘、无绑定半成品
        assertEquals("RELEASED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        assertEquals("RELEASED", currentStatus(otherMaterial));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class), "仅三个父批自身的放行绑定");
    }

    @Test
    void mergeSuccess_parentCannotMergeAgain_andChildKeyConflict() throws Exception {
        publish("DECL-MS", 1, "MAT-1", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-MS", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-MS", "MAT-1", "LOT-P2");
        String p3 = releasedBatch("DECL-MS", "MAT-1", "LOT-P3");
        String child = "BK-MS-C-" + unique();
        merge("CK-MS-1", child, "LOT-M1", List.of(p1, p2), "DECL-MS", 1, 201);

        // MERGED 父批不能再次合批
        merge("CK-MS-2", "BK-MS-C2-" + unique(), "LOT-M2", List.of(p1, p3), "DECL-MS", 1, 409);
        // 新批 batchKey 已存在 → 409
        merge("CK-MS-3", child, "LOT-M3", List.of(p3, releasedBatch("DECL-MS", "MAT-1", "LOT-P4")),
                "DECL-MS", 1, 409);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
    }

    // ---------- 幂等 ----------

    @Test
    void mergeCommandKey_replayAndConflict_failureDoesNotOccupyKey() throws Exception {
        publish("DECL-MI", 1, "MAT-1", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-MI", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-MI", "MAT-1", "LOT-P2");
        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI-1", child, "LOT-M1", List.of(p1, p2), "DECL-MI", 1);

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
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));

        // 同 commandKey 改参 → 409
        String changed = mergeBody("CK-MI-1", child + "-X", "LOT-M1", List.of(p1, p2),
                "DECL-MI", 1);
        mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：先以不存在声明失败，修正后同键成功
        String p3 = releasedBatch("DECL-MI", "MAT-1", "LOT-P3");
        String p4 = releasedBatch("DECL-MI", "MAT-1", "LOT-P4");
        String child2 = "BK-MI-C2-" + unique();
        merge("CK-MI-2", child2, "LOT-M2", List.of(p3, p4), "DECL-NONE", 1, 422);
        merge("CK-MI-2", child2, "LOT-M2", List.of(p3, p4), "DECL-MI", 1, 201);
        assertEquals("MERGED", currentStatus(p3));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentMergeSharingParent_onlyOneWins() throws Exception {
        publish("DECL-MC", 1, "MAT-1", List.of("LOT-"), 1, 201);
        String p1 = releasedBatch("DECL-MC", "MAT-1", "LOT-P1");
        String p2 = releasedBatch("DECL-MC", "MAT-1", "LOT-P2");
        String p3 = releasedBatch("DECL-MC", "MAT-1", "LOT-P3");
        String c1 = "BK-MC-C1-" + unique();
        String c2 = "BK-MC-C2-" + unique();

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MC-1", c1, "LOT-M1", List.of(p1, p2),
                                "DECL-MC", 1))),
                () -> callStatus(post("/api/batches/merge").header("X-Actor-Id", "m2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MC-2", c2, "LOT-M2", List.of(p2, p3),
                                "DECL-MC", 1)))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "共享父批的并发合批仅一个成功: " + first + "/" + second);
        // 共享父批只被合入一次；血缘行数恰为 2
        assertEquals("MERGED", currentStatus(p2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class, c1, c2));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void publish(String declarationKey, int version, String materialCode,
                         List<String> prefixes, int priority, int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"CK-P-").append(unique())
                .append("\",\"declarationKey\":\"").append(declarationKey)
                .append("\",\"version\":").append(version)
                .append(",\"materialCode\":\"").append(materialCode)
                .append("\",\"batchNoPrefixes\":[");
        for (int i = 0; i < prefixes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(prefixes.get(i)).append('"');
        }
        sb.append("],\"priority\":").append(priority)
                .append(",\"validFrom\":\"").append(VALID_FROM)
                .append("\",\"validTo\":\"").append(VALID_TO).append("\"}");
        mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declarationKey, int version, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/declarations/" + declarationKey + "/versions/" + version
                        + "/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 创建批次（必做检验项固定为 t1），返回实际 batchKey。
     */
    private String createBatch(String productCode, String batchNo) throws Exception {
        String batchKey = "BK-P-" + unique();
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"" + productCode + "\",\"batchNo\":\"" + batchNo
                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}";
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return batchKey;
    }

    /**
     * 创建批次、绑定指定声明并走完检验与双角色批准，返回 RELEASED 批次键。
     */
    private String releasedBatch(String declarationKey, String productCode, String batchNo)
            throws Exception {
        String batchKey = createBatch(productCode, batchNo);
        String bind = "{\"commandKey\":\"CK-B-" + unique() + "\",\"bindings\":[{\"batchKey\":\""
                + batchKey + "\",\"declarationKey\":\"" + declarationKey + "\",\"version\":1}]}";
        mockMvc.perform(post("/api/batches/certificate-bindings").header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bind))
                .andExpect(status().isCreated());
        submitTest(batchKey, "t1", "PASS", "insp-" + unique(), 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
        return batchKey;
    }

    private String mergeBody(String commandKey, String batchKey, String batchNo,
                             List<String> parents, String declarationKey, int version) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"batchKey\":\"").append(batchKey)
                .append("\",\"batchNo\":\"").append(batchNo)
                .append("\",\"parents\":[");
        for (int i = 0; i < parents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parents.get(i)).append('"');
        }
        return sb.append("],\"declarationKey\":\"").append(declarationKey)
                .append("\",\"version\":").append(version).append('}').toString();
    }

    private MvcResult merge(String commandKey, String batchKey, String batchNo,
                            List<String> parents, String declarationKey, int version, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/merge").header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody(commandKey, batchKey, batchNo, parents, declarationKey,
                                version)))
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

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
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
