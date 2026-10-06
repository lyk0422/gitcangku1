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
 * 破坏性检验样本消耗测试：合成场景（批量10/预算3）、消耗参数与上限校验、
 * FAIL 仍扣量、sampleKey 幂等与唯一、剩余量为 0 禁止放行、数量批次拆分守恒、
 * 已放行/已拆父批禁消耗、并发消耗不超预算、重复凭证不重复扣量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchDestructiveSampleTest {

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
        jdbc.update("DELETE FROM sample_consumption");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 合成场景：批量 10、预算 3 ----------

    @Test
    void syntheticScenario_quantity10Budget3_consume2ThenSplit3And5() throws Exception {
        String batchKey = "BK-SYN-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 10, 3, 201);

        // 合格检验耗 2 → 剩 8，凭证落账
        MvcResult consume = submitTest(batchKey,
                testReq("CK-T1", "TK-1", "t1", "PASS", "insp-1", "SK-1", 2), 201);
        JsonNode consumeBody = read(consume);
        assertEquals("SK-1", consumeBody.path("sampleKey").asText());
        assertEquals(2, consumeBody.path("consumeQuantity").asInt());
        assertEquals(8, consumeBody.path("remainingAfter").asInt());
        assertConsumed(batchKey, 2);

        // 再取 2：累计 4 超预算 3 → 422，本次检验结果与凭证均不落账（TK-1 仍在）
        submitTest(batchKey, testReq("CK-T2", "TK-2", "t2", "PASS", "insp-2", "SK-2", 2), 422);
        assertEquals(1, countRows("test_result", batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key = ? AND test_key = 'TK-2'",
                Integer.class, batchKey));
        assertEquals(1, countRows("sample_consumption", batchKey));
        assertConsumed(batchKey, 2);

        // 失败不占键：同 commandKey 修正为不耗样提交 → 201，完成原放行
        submitTest(batchKey, testReq("CK-T2", "TK-2", "t2", "PASS", "insp-2", null, null), 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 拆 3 和 7：和 10 ≠ 剩余 8 → 422，父批不变
        split(batchKey, "CK-SP-1",
                List.of(child("BK-SYN-X1-" + unique(), 3), child("BK-SYN-X2-" + unique(), 7)), 422);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 失败不占键：同 commandKey 拆 3 和 5（和 8 = 10 - 2）→ 合法
        String c1 = "BK-SYN-C1-" + unique();
        String c2 = "BK-SYN-C2-" + unique();
        MvcResult ok = split(batchKey, "CK-SP-1", List.of(child(c1, 3), child(c2, 5)), 201);
        JsonNode splitBody = read(ok);
        assertEquals("SPLIT", splitBody.path("parentStatus").asText());
        assertEquals(3, splitBody.path("children").get(0).path("quantity").asInt());
        assertEquals(5, splitBody.path("children").get(1).path("quantity").asInt());

        // 父批消耗保留；子批为数量批次、取样预算 0、无消耗
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("sample_consumption", batchKey));
        assertQuantityRow(c1, 3, 0, 0);
        assertQuantityRow(c2, 5, 0, 0);
    }

    // ---------- 创建与消耗参数校验 ----------

    @Test
    void createBatchQuantitySpecValidation_returns400() throws Exception {
        // quantity 缺 maxSampleQuantity
        createBatch("BK-QV1-" + unique(), List.of("t1"), 10, null, 400);
        // maxSampleQuantity 缺 quantity
        createBatch("BK-QV2-" + unique(), List.of("t1"), null, 3, 400);
        // maxSampleQuantity 超过批次数量
        createBatch("BK-QV3-" + unique(), List.of("t1"), 3, 4, 400);
        // quantity 非正整数
        createBatch("BK-QV4-" + unique(), List.of("t1"), 0, 0, 400);
        // maxSampleQuantity 负数
        createBatch("BK-QV5-" + unique(), List.of("t1"), 10, -1, 400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));
        // 边界合法：预算等于批次数量
        createBatch("BK-QV6-" + unique(), List.of("t1"), 3, 3, 201);
        // 边界合法：预算为 0
        createBatch("BK-QV7-" + unique(), List.of("t1"), 3, 0, 201);
    }

    @Test
    void consumptionParamValidation() throws Exception {
        // 普通批次不支持样本消耗 → 422
        String normal = "BK-NRM-" + unique();
        createBatch(normal, List.of("t1"), null, null, 201);
        submitTest(normal, testReq("CK-N1", "TK-N1", "t1", "PASS", "i", "SK-N", 1), 422);
        assertEquals(0, countRows("test_result", normal));

        String qty = "BK-QTY-" + unique();
        createBatch(qty, List.of("t1", "t2", "t3", "t4"), 10, 5, 201);
        // 只有 sampleKey 没有 consumeQuantity → 400
        submitTest(qty, testReq("CK-P1", "TK-P1", "t1", "PASS", "i", "SK-P1", null), 400);
        // 只有 consumeQuantity 没有 sampleKey → 400
        submitTest(qty, testReq("CK-P2", "TK-P2", "t2", "PASS", "i", null, 1), 400);
        // consumeQuantity 非正整数 → 400
        submitTest(qty, testReq("CK-P3", "TK-P3", "t3", "PASS", "i", "SK-P3", 0), 400);
        // sampleKey 空白 → 400
        submitTest(qty, testReq("CK-P4", "TK-P4", "t4", "PASS", "i", "  ", 1), 400);
        assertEquals(0, countRows("test_result", qty));
        assertEquals(0, countRows("sample_consumption", qty));
    }

    // ---------- 失败检验仍扣量 ----------

    @Test
    void failTestStillConsumes_andRejectedKeepsVoucher() throws Exception {
        String batchKey = "BK-FAIL-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 5, 2, 201);

        // FAIL 不是接口失败：合法提交原子落账，消耗照样扣减
        MvcResult result = submitTest(batchKey,
                testReq("CK-F1", "TK-F1", "t1", "FAIL", "insp-1", "SK-F1", 2), 201);
        JsonNode body = read(result);
        assertEquals("REJECTED", body.path("batchStatus").asText());
        assertEquals(3, body.path("remainingAfter").asInt());
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("sample_consumption", batchKey));

        // REJECTED 终态：禁止新增检验（含消耗），凭证保留
        submitTest(batchKey, testReq("CK-F2", "TK-F2", "t2", "PASS", "insp-2", "SK-F2", 1), 409);
        assertConsumed(batchKey, 2);

        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = read(history);
        assertEquals(1, node.path("samples").size());
        assertEquals("SK-F1", node.path("samples").get(0).path("sampleKey").asText());
        assertEquals(2, node.path("samples").get(0).path("quantity").asInt());
        assertEquals(3, node.path("samples").get(0).path("remainingAfter").asInt());
        assertEquals("TK-F1", node.path("samples").get(0).path("testKey").asText());
    }

    // ---------- sampleKey 幂等与唯一 ----------

    @Test
    void sampleKeyReplayAndUniqueness() throws Exception {
        String batchKey = "BK-SK-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 10, 5, 201);
        submitTest(batchKey, testReq("CK-S1", "TK-1", "t1", "PASS", "insp-1", "SK-1", 2), 201);

        // 同 testKey 同内容（含消耗）换 commandKey 重放 → 200，不再次扣量
        MvcResult replay = submitTest(batchKey,
                testReq("CK-S2", "TK-1", "t1", "PASS", "insp-1", "SK-1", 2), 200);
        assertEquals(8, read(replay).path("remainingAfter").asInt());
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("test_result", batchKey));
        assertEquals(1, countRows("sample_consumption", batchKey));

        // 同 testKey 改消耗量 → 409
        submitTest(batchKey, testReq("CK-S3", "TK-1", "t1", "PASS", "insp-1", "SK-1", 3), 409);
        // 同 testKey 去掉消耗 → 409
        submitTest(batchKey, testReq("CK-S4", "TK-1", "t1", "PASS", "insp-1", null, null), 409);
        // 同 sampleKey 绑到另一检验 → 409
        submitTest(batchKey, testReq("CK-S5", "TK-2", "t2", "PASS", "insp-2", "SK-1", 1), 409);
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("test_result", batchKey));
        assertEquals(1, countRows("sample_consumption", batchKey));

        // 累计至上限合法：再耗 3（累计 5 = 预算）→ 201，剩 5
        MvcResult toBudget = submitTest(batchKey,
                testReq("CK-S6", "TK-2", "t2", "PASS", "insp-2", "SK-2", 3), 201);
        assertEquals(5, read(toBudget).path("remainingAfter").asInt());
        assertConsumed(batchKey, 5);
    }

    // ---------- 剩余量为 0 禁止放行 ----------

    @Test
    void remainingZeroBlocksRelease_returns422() throws Exception {
        String batchKey = "BK-ZERO-" + unique();
        createBatch(batchKey, List.of("t1"), 2, 2, 201);
        submitTest(batchKey, testReq("CK-Z1", "TK-Z1", "t1", "PASS", "insp-1", "SK-Z1", 2), 201);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 必需项已合格但剩余量为 0：两个角色的批准都被拒
        approve(batchKey, "qa-1", "QUALITY", "CK-ZA1", 422);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-ZA2", 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
        assertEquals(0, countRows("approval", batchKey));
    }

    // ---------- 数量批次拆分规则 ----------

    @Test
    void splitQuantityRules() throws Exception {
        String parent = "BK-SQ-P-" + unique();
        createBatch(parent, List.of("t1"), 6, 0, 201);
        submitTest(parent, testReq("CK-T1", "TK-1", "t1", "PASS", "insp", null, null), 201);
        approve(parent, "qa-1", "QUALITY", "CK-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-A2", 201);

        // 新类型拆分缺子批数量 → 422
        split(parent, "CK-SQ-1",
                List.of(child("BK-SQ-A1-" + unique(), null), child("BK-SQ-A2-" + unique(), null)),
                422);
        // 子批数量和 5 ≠ 剩余 6 → 422
        split(parent, "CK-SQ-2",
                List.of(child("BK-SQ-B1-" + unique(), 2), child("BK-SQ-B2-" + unique(), 3)), 422);
        // 子批数量非正整数 → 400
        split(parent, "CK-SQ-3",
                List.of(child("BK-SQ-C1-" + unique(), 0), child("BK-SQ-C2-" + unique(), 6)), 400);
        assertEquals("RELEASED", currentStatus(parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));

        // 和等于剩余量 → 201
        String c1 = "BK-SQ-OK1-" + unique();
        String c2 = "BK-SQ-OK2-" + unique();
        split(parent, "CK-SQ-4", List.of(child(c1, 2), child(c2, 4)), 201);
        assertQuantityRow(c1, 2, 0, 0);
        assertQuantityRow(c2, 4, 0, 0);

        // 普通批次拆分携带数量 → 422；不携带 → 201（行为不变）
        String normal = "BK-SQ-N-" + unique();
        createBatch(normal, List.of("t1"), null, null, 201);
        submitTest(normal, testReq("CK-TN", "TK-N", "t1", "PASS", "insp", null, null), 201);
        approve(normal, "qa-1", "QUALITY", "CK-AN1", 201);
        approve(normal, "ops-1", "OPERATIONS", "CK-AN2", 201);
        split(normal, "CK-SQ-N1",
                List.of(child("BK-SQ-N1-" + unique(), 1), child("BK-SQ-N2-" + unique(), 1)), 422);
        split(normal, "CK-SQ-N2",
                List.of(child("BK-SQ-N3-" + unique(), null), child("BK-SQ-N4-" + unique(), null)),
                201);
    }

    // ---------- 已放行/已拆父批禁新增消耗 ----------

    @Test
    void releasedOrSplitBatchRejectsConsumption() throws Exception {
        String batchKey = "BK-LOCK-" + unique();
        createBatch(batchKey, List.of("t1"), 4, 2, 201);
        submitTest(batchKey, testReq("CK-L1", "TK-L1", "t1", "PASS", "insp", null, null), 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-LA1", 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-LA2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 已放行禁止新增消耗检验
        submitTest(batchKey, testReq("CK-L2", "TK-L2", "t1", "PASS", "insp", "SK-L2", 1), 409);
        assertConsumed(batchKey, 0);

        // 拆分后父批禁止新增消耗检验
        split(batchKey, "CK-LS",
                List.of(child("BK-LOCK-C1-" + unique(), 2), child("BK-LOCK-C2-" + unique(), 2)),
                201);
        submitTest(batchKey, testReq("CK-L3", "TK-L3", "t1", "PASS", "insp", "SK-L3", 1), 409);
        assertConsumed(batchKey, 0);
        assertEquals(0, countRows("sample_consumption", batchKey));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConsumptionsExceedingBudget_onlyOneCommits() throws Exception {
        String batchKey = "BK-CC-" + unique();
        createBatch(batchKey, List.of("t1", "t2"), 10, 3, 201);

        // 并发各耗 2，预算 3：行锁串行化后恰一个成功，另一个 422 且不落账
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("CK-CC1", "TK-C1", "t1", "PASS", "insp-1", "SK-C1", 2))),
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("CK-CC2", "TK-C2", "t2", "PASS", "insp-2", "SK-C2", 2)))
        );

        int created = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 422) {
                rejected++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, rejected);
        // 最终数据：只扣一份量，只落一条检验与一张凭证
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("test_result", batchKey));
        assertEquals(1, countRows("sample_consumption", batchKey));
    }

    @Test
    void concurrentSameVoucher_noDoubleDeduction() throws Exception {
        String batchKey = "BK-CV-" + unique();
        createBatch(batchKey, List.of("t1"), 10, 5, 201);

        // 同一凭证同一检验内容、不同 commandKey 并发：一次落账一次重放，扣量仅一次
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("CK-CV1", "TK-V1", "t1", "PASS", "insp-1", "SK-V1", 2))),
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testReq("CK-CV2", "TK-V1", "t1", "PASS", "insp-1", "SK-V1", 2)))
        );

        int created = 0;
        int replayed = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 200) {
                replayed++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, created);
        assertEquals(1, replayed);
        assertConsumed(batchKey, 2);
        assertEquals(1, countRows("test_result", batchKey));
        assertEquals(1, countRows("sample_consumption", batchKey));
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    // ---------- 历史与数量字段 ----------

    @Test
    void historyIncludesSamplesAndQuantities() throws Exception {
        String batchKey = "BK-HIST-" + unique();
        createBatch(batchKey, List.of("t1"), 10, 3, 201);
        submitTest(batchKey, testReq("CK-H1", "TK-H1", "t1", "PASS", "insp-1", "SK-H1", 2), 201);

        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = read(history);
        JsonNode batch = node.path("batch");
        assertEquals(10, batch.path("quantity").asInt());
        assertEquals(3, batch.path("maxSampleQuantity").asInt());
        assertEquals(2, batch.path("consumedQuantity").asInt());
        assertEquals(8, batch.path("remainingQuantity").asInt());
        assertEquals(1, node.path("samples").size());
        JsonNode sample = node.path("samples").get(0);
        assertEquals("SK-H1", sample.path("sampleKey").asText());
        assertEquals("TK-H1", sample.path("testKey").asText());
        assertEquals(2, sample.path("quantity").asInt());
        assertEquals(8, sample.path("remainingAfter").asInt());
        // 检验结果携带消耗信息
        JsonNode test = node.path("tests").get(0);
        assertEquals("SK-H1", test.path("sampleKey").asText());
        assertEquals(2, test.path("consumeQuantity").asInt());
        assertEquals(8, test.path("remainingAfter").asInt());

        // 普通批次数量字段为 null、样本列表为空
        String normal = "BK-HIST-N-" + unique();
        createBatch(normal, List.of("t1"), null, null, 201);
        MvcResult normalHistory = mockMvc.perform(get("/api/batches/" + normal + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode normalNode = read(normalHistory);
        assertTrue(normalNode.path("batch").path("quantity").isNull());
        assertTrue(normalNode.path("batch").path("maxSampleQuantity").isNull());
        assertTrue(normalNode.path("batch").path("consumedQuantity").isNull());
        assertTrue(normalNode.path("batch").path("remainingQuantity").isNull());
        assertEquals(0, normalNode.path("samples").size());
    }


    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createBody(String batchKey, List<String> items, Integer quantity,
                              Integer maxSample) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", items);
        if (quantity != null) {
            body.put("quantity", quantity);
        }
        if (maxSample != null) {
            body.put("maxSampleQuantity", maxSample);
        }
        return objectMapper.writeValueAsString(body);
    }

    private void createBatch(String batchKey, List<String> items, Integer quantity,
                             Integer maxSample, int expected) throws Exception {
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(batchKey, items, quantity, maxSample)))
                .andExpect(status().is(expected));
    }

    private String testReq(String commandKey, String testKey, String item, String result,
                           String inspector, String sampleKey, Integer consume) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("testKey", testKey);
        body.put("testItem", item);
        body.put("result", result);
        body.put("inspector", inspector);
        if (sampleKey != null) {
            body.put("sampleKey", sampleKey);
        }
        if (consume != null) {
            body.put("consumeQuantity", consume);
        }
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult submitTest(String batchKey, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private Map<String, Object> child(String batchKey, Integer quantity) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("batchKey", batchKey);
        child.put("batchNo", "L-" + batchKey.substring(batchKey.length() - 4));
        if (quantity != null) {
            child.put("quantity", quantity);
        }
        return child;
    }

    private MvcResult split(String parentKey, String commandKey, List<Map<String, Object>> children,
                            int expected) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("commandKey", commandKey, "children", children));
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private int countRows(String table, String batchKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private void assertConsumed(String batchKey, int expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT consumed_quantity FROM batch WHERE batch_key = ?", Integer.class, batchKey));
    }

    private void assertQuantityRow(String batchKey, int quantity, int maxSample, int consumed) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT quantity, max_sample_quantity, consumed_quantity FROM batch"
                        + " WHERE batch_key = ?", batchKey);
        assertEquals(quantity, ((Number) row.get("quantity")).intValue());
        assertEquals(maxSample, ((Number) row.get("max_sample_quantity")).intValue());
        assertEquals(consumed, ((Number) row.get("consumed_quantity")).intValue());
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
