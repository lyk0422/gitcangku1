package com.example.starter.batch;

import com.example.starter.batch.dto.IntervalSpec;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 序号区间拆批测试（真实 H2）：合成父域 1..10 拆分主流程、遗漏/重叠/区间外 422 整次回滚、
 * 拆批门禁、requestId 幂等（区间换序同参）、序号归属与召回状态、多级拆分与并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SerialBatchSplitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM batch_serial_interval");
        jdbc.update("DELETE FROM serial_domain_lock");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 拆批主流程：父域 1..10 拆为 1..4、5..10 ----------

    @Test
    void happySerialSplit_parentOneToTen_childrenOneToFourAndFiveToTen() throws Exception {
        String parent = "SB-SP-" + unique();
        String product = "PROD-SP-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String child1 = "SB-SPC1-" + unique();
        String child2 = "SB-SPC2-" + unique();

        MvcResult result = serialSplit(parent, "RID-" + unique(), List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(5, 10)))), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(parent, body.path("parentBatchKey").asText());
        assertEquals("SPLIT", body.path("parentStatus").asText());
        assertEquals(2, body.path("children").size());
        // 子数量按区间长度生成
        assertEquals(4, body.path("children").get(0).path("quantity").asInt());
        assertEquals(6, body.path("children").get(1).path("quantity").asInt());
        assertEquals(1, body.path("children").get(0).path("intervals").get(0).path("start").asInt());
        assertEquals(4, body.path("children").get(0).path("intervals").get(0).path("end").asInt());
        assertEquals(5, body.path("children").get(1).path("intervals").get(0).path("start").asInt());
        assertEquals(10, body.path("children").get(1).path("intervals").get(0).path("end").asInt());

        // 父域冻结为历史；子批持有当前域
        JsonNode parentDomain = intervalsOf(parent);
        assertFalse(parentDomain.path("current").asBoolean(), "拆后父域只作历史");
        assertEquals(10, parentDomain.path("intervals").get(0).path("end").asInt());
        assertTrue(intervalsOf(child1).path("current").asBoolean());
        assertEquals(4, jdbc.queryForObject("SELECT quantity FROM batch WHERE batch_key = ?",
                Integer.class, child1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));

        // 序号归属：每件序号仅属于一个当前叶批
        assertLeaf(product, 3, child1, false);
        assertLeaf(product, 7, child2, false);
        assertLeaf(product, 10, child2, false);
        mockMvc.perform(get("/api/serial-batches/lookup")
                        .param("productCode", product).param("serial", "11"))
                .andExpect(status().isNotFound());
    }

    // ---------- 失败分支：整次 422，父批完整且无任何子批 ----------

    @Test
    void overlapRejected_parentIntactAndNoChildren() throws Exception {
        String parent = "SB-OV-" + unique();
        String product = "PROD-OV-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String child1 = "SB-OVC1-" + unique();
        String child2 = "SB-OVC2-" + unique();
        String requestId = "RID-" + unique();

        // 第二子改 4..10 即与第一子 1..4 重叠 → 422
        serialSplit(parent, requestId, List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(4, 10)))), 422);
        assertParentIntact(parent, child1, child2);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId), "失败不占幂等键");

        // 修正为 5..10 后同 requestId 成功
        serialSplit(parent, requestId, List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(5, 10)))), 201);
        assertEquals("SPLIT", currentStatus(parent));
    }

    @Test
    void missingAndOutOfRangeSerials_rejected422() throws Exception {
        String parent = "SB-MO-" + unique();
        String product = "PROD-MO-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        // 遗漏：缺少序号 5
        serialSplit(parent, "RID-" + unique(), List.of(
                child("SB-MOC1-" + unique(), List.of(iv(1, 4))),
                child("SB-MOC2-" + unique(), List.of(iv(6, 10)))), 422);
        // 区间外：11 超出父域
        serialSplit(parent, "RID-" + unique(), List.of(
                child("SB-MOC3-" + unique(), List.of(iv(1, 4))),
                child("SB-MOC4-" + unique(), List.of(iv(5, 11)))), 422);
        // 遗漏：仅覆盖 1..9
        serialSplit(parent, "RID-" + unique(), List.of(
                child("SB-MOC5-" + unique(), List.of(iv(1, 4))),
                child("SB-MOC6-" + unique(), List.of(iv(5, 9)))), 422);
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_serial_interval WHERE batch_key = ?",
                Integer.class, parent), "父批域保持原样，未新增任何区间");
    }

    // ---------- 拆批门禁 ----------

    @Test
    void splitGates_releasedRequired_plainRejected_oldEntryRejected_recalledAncestorBlocked()
            throws Exception {
        // 未放行（QUARANTINED）序号批 → 409
        String product = "PROD-G-" + unique();
        String notReleased = "SB-G1-" + unique();
        register(notReleased, product, 10, List.of(iv(1, 10)));
        serialSplit(notReleased, "RID-" + unique(), List.of(
                child("SB-G1C1-" + unique(), List.of(iv(1, 5))),
                child("SB-G1C2-" + unique(), List.of(iv(6, 10)))), 409);

        // 普通批走序号区间入口 → 409
        String plain = "BK-PLAIN-" + unique();
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P-" + unique() + "\",\"batchKey\":\"" + plain
                                + "\",\"productCode\":\"PROD-P\",\"batchNo\":\"n\","
                                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().isCreated());
        release(plain);
        serialSplit(plain, "RID-" + unique(), List.of(
                child("SB-G2C1-" + unique(), List.of(iv(1, 5))),
                child("SB-G2C2-" + unique(), List.of(iv(6, 10)))), 409);

        // 序号父批不能走原只按数量拆批入口 → 409
        String serialParent = "SB-G3-" + unique();
        registerReleased("PROD-G3-" + unique(), serialParent, 10, List.of(iv(1, 10)));
        mockMvc.perform(post("/api/batches/" + serialParent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-S-" + unique() + "\",\"children\":["
                                + "{\"batchKey\":\"SB-G3C1-" + unique() + "\",\"batchNo\":\"a\"},"
                                + "{\"batchKey\":\"SB-G3C2-" + unique() + "\",\"batchNo\":\"b\"}]}"))
                .andExpect(status().isConflict());
        assertEquals("RELEASED", currentStatus(serialParent));

        // 祖先召回 → 后代禁止拆分（422）
        String root = "SB-G4-" + unique();
        String productR = "PROD-GR-" + unique();
        registerReleased(productR, root, 10, List.of(iv(1, 10)));
        String childA = "SB-G4C1-" + unique();
        serialSplit(root, "RID-" + unique(), List.of(
                child(childA, List.of(iv(1, 5))),
                child("SB-G4C2-" + unique(), List.of(iv(6, 10)))), 201);
        release(childA);
        recall(root, "CK-RC-" + unique(), 201);
        serialSplit(childA, "RID-" + unique(), List.of(
                child("SB-G4D1-" + unique(), List.of(iv(1, 2))),
                child("SB-G4D2-" + unique(), List.of(iv(3, 5)))), 422);
        assertEquals("RELEASED", currentStatus(childA));
    }

    // ---------- requestId 幂等：区间换序同参 ----------

    @Test
    void requestId_intervalReorderSameParams_replaysFirstResponse() throws Exception {
        String parent = "SB-RO-" + unique();
        String product = "PROD-RO-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String child1 = "SB-ROC1-" + unique();
        String child2 = "SB-ROC2-" + unique();
        String requestId = "RID-" + unique();

        // 子批2区间乱序 [8,10],[5,7] 与 [5,7],[8,10] 同参
        MvcResult first = serialSplit(parent, requestId, List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(8, 10), iv(5, 7)))), 201);
        MvcResult replay = serialSplit(parent, requestId, List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(5, 7), iv(8, 10)))), 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "区间换序同参，重放首次完整响应");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));

        // 同 requestId 改参（不同的合法划分）→ 409
        serialSplit(parent, requestId, List.of(
                child(child1, List.of(iv(1, 5))),
                child(child2, List.of(iv(6, 10)))), 409);
        // 同 requestId 改操作者 → 409
        String changedOperator = objectMapper.writeValueAsString(new SplitCmd(requestId, "op-2",
                0L, List.of(child(child1, List.of(iv(1, 4))),
                        child(child2, List.of(iv(5, 7), iv(8, 10))))));
        mockMvc.perform(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(changedOperator))
                .andExpect(status().isConflict());
    }

    // ---------- 序号归属与召回状态 ----------

    @Test
    void lookup_reflectsSelfAndAncestorRecall() throws Exception {
        String parent = "SB-LK-" + unique();
        String product = "PROD-LK-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String child1 = "SB-LKC1-" + unique();
        String child2 = "SB-LKC2-" + unique();
        serialSplit(parent, "RID-" + unique(), List.of(
                child(child1, List.of(iv(1, 4))),
                child(child2, List.of(iv(5, 10)))), 201);
        assertLeaf(product, 3, child1, false);

        // 召回已拆分父批 → 全部后代序号不可用（祖先召回，父批状态变为 RECALLED）
        recall(parent, "CK-RC-" + unique(), 201);
        JsonNode afterAncestorRecall = lookup(product, 3);
        assertEquals(child1, afterAncestorRecall.path("batchKey").asText());
        assertTrue(afterAncestorRecall.path("recalled").asBoolean());
        assertEquals(parent, afterAncestorRecall.path("recalledBy").asText());
        // 同一棵树另一子批的序号同样受祖先召回影响，且归属仍是叶批 child2
        JsonNode sibling = lookup(product, 7);
        assertEquals(child2, sibling.path("batchKey").asText());
        assertTrue(sibling.path("recalled").asBoolean());
        assertEquals(parent, sibling.path("recalledBy").asText());

        // 无关产品的序号树不受影响
        String otherRoot = "SB-LKO-" + unique();
        String otherProduct = "PROD-LKO-" + unique();
        register(otherRoot, otherProduct, 2, List.of(iv(1, 2)));
        assertLeaf(otherProduct, 1, otherRoot, false);
    }

    @Test
    void lookup_selfRecalledLeaf() throws Exception {
        String parent = "SB-SR-" + unique();
        String product = "PROD-SR-" + unique();
        registerReleased(product, parent, 4, List.of(iv(1, 4)));
        recall(parent, "CK-RC-" + unique(), 201);
        JsonNode node = lookup(product, 2);
        assertEquals(parent, node.path("batchKey").asText());
        assertEquals("RECALLED", node.path("batchStatus").asText());
        assertTrue(node.path("recalled").asBoolean());
        assertEquals(parent, node.path("recalledBy").asText());
    }

    // ---------- 多级拆分：叶批归属随拆分推进 ----------

    @Test
    void multiLevelSplit_leafOwnershipFollowsSplits() throws Exception {
        String root = "SB-ML-" + unique();
        String product = "PROD-ML-" + unique();
        registerReleased(product, root, 10, List.of(iv(1, 10)));
        String child1 = "SB-MLC1-" + unique();
        String child2 = "SB-MLC2-" + unique();
        serialSplit(root, "RID-" + unique(), List.of(
                child(child1, List.of(iv(1, 5))),
                child(child2, List.of(iv(6, 10)))), 201);
        release(child1);
        String grand1 = "SB-MLD1-" + unique();
        String grand2 = "SB-MLD2-" + unique();
        serialSplit(child1, "RID-" + unique(), List.of(
                child(grand1, List.of(iv(1, 2))),
                child(grand2, List.of(iv(3, 5)))), 201);

        assertLeaf(product, 1, grand1, false);
        assertLeaf(product, 4, grand2, false);
        assertLeaf(product, 8, child2, false);
        // 中间父批已拆分，其域仅作历史
        JsonNode child1Domain = intervalsOf(child1);
        assertFalse(child1Domain.path("current").asBoolean());
        assertEquals(1, child1Domain.path("intervals").size());
        assertEquals(5, child1Domain.path("intervals").get(0).path("end").asInt());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSerialSplit_sameParent_exactlyOneWins() throws Exception {
        String parent = "SB-CS-" + unique();
        String product = "PROD-CS-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String bodyA = splitBody("RID-" + unique(), List.of(
                child("SB-CSC1-" + unique(), List.of(iv(1, 4))),
                child("SB-CSC2-" + unique(), List.of(iv(5, 10)))));
        String bodyB = splitBody("RID-" + unique(), List.of(
                child("SB-CSC3-" + unique(), List.of(iv(1, 5))),
                child("SB-CSC4-" + unique(), List.of(iv(6, 10)))));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB)));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "同一父批并发拆分必须恰好一个成功，实际: " + first + "," + second);
        assertEquals("SPLIT", currentStatus(parent));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        // 子批区间精确覆盖父域 10 件，无重复无遗漏
        assertEquals(10, jdbc.queryForObject("SELECT COALESCE(SUM(end_serial - start_serial + 1),0)"
                + " FROM batch_serial_interval WHERE kind = 'SPLIT'", Integer.class));
    }

    @Test
    void concurrentSameRequestId_split_bothReplayFirstResponse() throws Exception {
        String parent = "SB-CI-" + unique();
        String product = "PROD-CI-" + unique();
        registerReleased(product, parent, 10, List.of(iv(1, 10)));
        String body = splitBody("RID-" + unique(), List.of(
                child("SB-CIC1-" + unique(), List.of(iv(1, 4))),
                child("SB-CIC2-" + unique(), List.of(iv(5, 10)))));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log"
                + " WHERE operation = 'SERIAL_SPLIT'", Integer.class), "同键并发只占一个幂等键");
    }

    // ---------- helpers ----------

    private void assertParentIntact(String parent, String... childKeys) throws Exception {
        assertEquals("RELEASED", currentStatus(parent));
        for (String childKey : childKeys) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, childKey), "失败拆批不得建立子批: " + childKey);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage",
                Integer.class), "失败拆批不得建立血缘");
    }

    private record RegisterCmd(String requestId, String operator, long expectedVersion,
                               String batchKey, String productCode, String batchNo,
                               Instant producedAt, List<String> requiredTests, int quantity,
                               List<IntervalSpec> intervals) {
    }

    private record SplitChildCmd(String batchKey, String batchNo, List<IntervalSpec> intervals) {
    }

    private record SplitCmd(String requestId, String operator, long expectedVersion,
                            List<SplitChildCmd> children) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private IntervalSpec iv(int start, int end) {
        return new IntervalSpec(start, end);
    }

    private SplitChildCmd child(String batchKey, List<IntervalSpec> intervals) {
        return new SplitChildCmd(batchKey, "LOT-" + batchKey.substring(batchKey.length() - 4),
                intervals);
    }

    private void register(String batchKey, String productCode, int quantity,
                          List<IntervalSpec> intervals) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterCmd("RID-" + unique(), "op-1",
                0L, batchKey, productCode, "LOT-1", Instant.parse("2026-01-02T03:04:05Z"),
                List.of("t1"), quantity, intervals));
        mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void registerReleased(String productCode, String batchKey, int quantity,
                                  List<IntervalSpec> intervals) throws Exception {
        register(batchKey, productCode, quantity, intervals);
        release(batchKey);
    }

    private void release(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-1\","
                                + "\"testItem\":\"t1\",\"result\":\"PASS\",\"inspector\":\"insp-1\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "qa-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A1-" + unique() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A2-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"r\"}"))
                .andExpect(status().is(expected));
    }

    private String splitBody(String requestId, List<SplitChildCmd> children) throws Exception {
        return objectMapper.writeValueAsString(new SplitCmd(requestId, "op-1", 0L, children));
    }

    private MvcResult serialSplit(String parentKey, String requestId,
                                  List<SplitChildCmd> children, int expected) throws Exception {
        return mockMvc.perform(post("/api/serial-batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(requestId, children)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode lookup(String productCode, int serial) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/serial-batches/lookup")
                        .param("productCode", productCode).param("serial", String.valueOf(serial)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertLeaf(String productCode, int serial, String expectedBatch,
                            boolean expectedRecalled) throws Exception {
        JsonNode node = lookup(productCode, serial);
        assertEquals(expectedBatch, node.path("batchKey").asText(),
                "序号 " + serial + " 应归属叶批 " + expectedBatch);
        assertEquals(expectedRecalled, node.path("recalled").asBoolean());
    }

    private JsonNode intervalsOf(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/serial-batches/" + batchKey + "/intervals"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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