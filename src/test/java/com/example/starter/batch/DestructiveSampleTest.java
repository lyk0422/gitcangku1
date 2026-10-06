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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 破坏性检验样本消耗测试：合成场景（批量10/预算3/耗2剩8/拆3+5）、FAIL 仍扣量、
 * 预算与剩余量上限、sampleKey 唯一与幂等、零剩余禁放行、并发消耗裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DestructiveSampleTest {

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
        jdbc.update("DELETE FROM sample_voucher");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 题干合成场景 ----------

    @Test
    void syntheticScenario_consume2Of10ThenSplit3And5() throws Exception {
        String batchKey = "BK-Q-SYN-" + unique();
        createQuantified(batchKey, List.of("含量", "无菌"), 10, 3, 201);

        // 合格检验耗 2 → 剩 8，凭证落账
        MvcResult consumed = submitTest(batchKey, "TK-1", "含量", "PASS", "insp-1", "SK-1", 2, 201);
        JsonNode consumedBody = read(consumed);
        assertEquals("SK-1", consumedBody.path("sampleKey").asText());
        assertEquals(2, consumedBody.path("consumeAmount").asInt());
        assertEquals(8, consumedBody.path("remainingQuantity").asInt());
        assertEquals(8, remainingOf(batchKey));

        // 再取 2 → 累计 4 超预算 3 → 422，检验结果与凭证均不落账
        submitTest(batchKey, "TK-2", "无菌", "PASS", "insp-1", "SK-2", 2, 422);
        assertEquals(0, countWhere("test_result", "test_key", "TK-2"));
        assertEquals(0, countWhere("sample_voucher", "sample_key", "SK-2"));
        assertEquals(8, remainingOf(batchKey));

        // 完成原放行（剩余量 8 > 0）
        submitTest(batchKey, "TK-3", "无菌", "PASS", "insp-2", null, null, 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 已放行禁新增消耗
        submitTest(batchKey, "TK-4", "含量", "PASS", "insp-3", "SK-4", 1, 409);

        // 原只按数量拆批入口对该类型拒绝
        split(batchKey, "CK-S-OLD-" + unique(),
                List.of(new String[]{"BK-Q-OLD1-" + unique(), "L1"},
                        new String[]{"BK-Q-OLD2-" + unique(), "L2"}), 409);

        // 拆 3 和 7：和 10 ≠ 剩余 8 → 422
        String c3 = "BK-Q-C3-" + unique();
        String c7 = "BK-Q-C7-" + unique();
        splitQuantities(batchKey, "CK-SQ-37-" + unique(),
                List.of(new int[]{3, 0}, new int[]{7, 0}), new String[]{c3, c7}, 422);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 拆 3 和 5：和 8 = 10 - 2 → 201
        String c5 = "BK-Q-C5-" + unique();
        MvcResult splitResult = splitQuantities(batchKey, "CK-SQ-35-" + unique(),
                List.of(new int[]{3, 1}, new int[]{5, 2}), new String[]{c3, c5}, 201);
        JsonNode splitBody = read(splitResult);
        assertEquals("SPLIT", splitBody.path("parentStatus").asText());
        assertEquals(3, splitBody.path("children").get(0).path("quantity").asInt());
        assertEquals(5, splitBody.path("children").get(1).path("quantity").asInt());
        assertEquals("QUANTIFIED", splitBody.path("children").get(0).path("batchType").asText());

        // 父批消耗保留：consumed 2、剩余 8，凭证仍在历史
        JsonNode parentHistory = historyOf(batchKey);
        assertEquals("SPLIT", parentHistory.path("batch").path("status").asText());
        assertEquals(2, parentHistory.path("batch").path("consumedAmount").asInt());
        assertEquals(8, parentHistory.path("batch").path("remainingQuantity").asInt());
        assertEquals(1, parentHistory.path("samples").size());
        assertEquals(8, parentHistory.path("samples").get(0).path("remainingAfter").asInt());
        // 子批从 0 消耗开始
        assertEquals(3, remainingOf(c3));
        assertEquals(5, remainingOf(c5));
    }

    // ---------- 消耗规则 ----------

    @Test
    void failTest_stillDeducts_andBatchRejected() throws Exception {
        String batchKey = "BK-Q-FAIL-" + unique();
        createQuantified(batchKey, List.of("t1"), 10, 3, 201);
        MvcResult result = submitTest(batchKey, "TK-F", "t1", "FAIL", "insp", "SK-F", 2, 201);
        JsonNode body = read(result);
        assertEquals("REJECTED", body.path("batchStatus").asText());
        assertEquals(8, body.path("remainingQuantity").asInt());
        assertEquals(8, remainingOf(batchKey));
        assertEquals(1, countWhere("sample_voucher", "batch_key", batchKey));
    }

    @Test
    void cumulativeConsumption_cannotExceedBudget() throws Exception {
        String batchKey = "BK-Q-BUDGET-" + unique();
        createQuantified(batchKey, List.of("t1", "t2", "t3"), 10, 3, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-1", 2, 201);
        submitTest(batchKey, "TK-2", "t2", "PASS", "i", "SK-2", 1, 201);
        assertEquals(7, remainingOf(batchKey));
        // 累计 3 已达预算，再耗 1 → 422 且不落账
        submitTest(batchKey, "TK-3", "t3", "PASS", "i", "SK-3", 1, 422);
        assertEquals(7, remainingOf(batchKey));
        assertEquals(2, countWhere("sample_voucher", "batch_key", batchKey));
    }

    @Test
    void consumption_cannotExceedRemainingQuantity() throws Exception {
        // 预算取上限（= 批量）时，上限检查等价于剩余量检查：耗 3 剩 1，再耗 2 → 422
        String batchKey = "BK-Q-REM-" + unique();
        createQuantified(batchKey, List.of("t1", "t2"), 4, 4, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-1", 3, 201);
        assertEquals(1, remainingOf(batchKey));
        submitTest(batchKey, "TK-2", "t2", "PASS", "i", "SK-2", 2, 422);
        assertEquals(1, remainingOf(batchKey));
    }

    // ---------- sampleKey 唯一与幂等 ----------

    @Test
    void sampleKey_uniquePerBatch_duplicateRejected_replayDoesNotDeductTwice() throws Exception {
        String batchKey = "BK-Q-SK-" + unique();
        createQuantified(batchKey, List.of("t1", "t2"), 10, 5, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-1", 2, 201);

        // 同一样本键绑定另一检验 → 409
        submitTest(batchKey, "TK-2", "t2", "PASS", "i", "SK-1", 1, 409);
        // 同一检验换样本键 → 409（testKey 内容不同）
        submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-9", 2, 409);

        // 重复凭证（同 testKey 同 sampleKey 同内容，换 commandKey）→ 重放原结果，不再次扣量
        MvcResult replay = submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-1", 2, 200);
        assertEquals(8, read(replay).path("remainingQuantity").asInt());
        assertEquals(8, remainingOf(batchKey));
        assertEquals(1, countWhere("sample_voucher", "batch_key", batchKey));
        assertEquals(1, countWhere("test_result", "batch_key", batchKey));
    }

    @Test
    void consumptionCommandKey_sameParamsReplay_changedParamsConflict_failureReusable() throws Exception {
        String batchKey = "BK-Q-CK-" + unique();
        createQuantified(batchKey, List.of("t1", "t2"), 10, 5, 201);

        String body = testBody("CK-T-1", "TK-1", "t1", "PASS", "i", "SK-1", 2);
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(8, remainingOf(batchKey));

        // 同 commandKey 改消耗量 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("CK-T-1", "TK-1", "t1", "PASS", "i", "SK-1", 1)))
                .andExpect(status().isConflict());

        // 失败可复用键：超预算 422 不占 commandKey，修正参数后成功
        submitTest(batchKey, "CK-T-2", "TK-2", "t2", "PASS", "i", "SK-2", 4, 422);
        submitTest(batchKey, "CK-T-2", "TK-2", "t2", "PASS", "i", "SK-2", 3, 201);
        assertEquals(5, remainingOf(batchKey));
    }

    // ---------- 类型与参数边界 ----------

    @Test
    void standardBatch_rejectsConsumption() throws Exception {
        String batchKey = "BK-Q-STD-" + unique();
        createStandard(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "i", "SK-1", 1, 422);
        assertEquals(0, countWhere("sample_voucher", "batch_key", batchKey));
    }

    @Test
    void createQuantified_validation() throws Exception {
        // 缺 quantity
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"batchKey\":\"BK-V1-" + unique()
                                + "\",\"productCode\":\"p\",\"batchNo\":\"n\","
                                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                                + "\"batchType\":\"QUANTIFIED\",\"maxSampleAmount\":1}"))
                .andExpect(status().isBadRequest());
        // maxSampleAmount 超 quantity
        createQuantified("BK-V2-" + unique(), List.of("t1"), 3, 4, 400);
        // quantity 非正
        createQuantified("BK-V3-" + unique(), List.of("t1"), 0, 0, 400);
        // STANDARD 携带 quantity
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"batchKey\":\"BK-V4-" + unique()
                                + "\",\"productCode\":\"p\",\"batchNo\":\"n\","
                                + "\"producedAt\":\"2026-01-01T00:00:00Z\",\"requiredTests\":[\"t1\"],"
                                + "\"quantity\":5}"))
                .andExpect(status().isBadRequest());
        // sampleKey 与 consumeAmount 只给其一
        String batchKey = "BK-V5-" + unique();
        createQuantified(batchKey, List.of("t1"), 5, 2, 201);
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("CK-V5", "TK-V5", "t1", "PASS", "i", "SK-V5", null)))
                .andExpect(status().isBadRequest());
        // consumeAmount 非正整数
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("CK-V6", "TK-V6", "t1", "PASS", "i", "SK-V6", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zeroRemaining_cannotBeReleased() throws Exception {
        String batchKey = "BK-Q-ZERO-" + unique();
        createQuantified(batchKey, List.of("t1"), 2, 2, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", "SK-1", 2, 201);
        assertEquals(0, remainingOf(batchKey));
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
    }

    @Test
    void splitQuantities_wrongTypeOrState_rejected() throws Exception {
        // STANDARD 父批走按数量入口 → 409
        String std = "BK-QS-STD-" + unique();
        createStandard(std, List.of("t1"), 201);
        submitTest(std, "TK-1", "t1", "PASS", "insp", null, null, 201);
        approve(std, "qa", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(std, "ops", "OPERATIONS", "CK-AO-" + unique(), 201);
        splitQuantities(std, "CK-SQS-" + unique(), List.of(new int[]{1, 0}, new int[]{1, 0}),
                new String[]{"BK-QS-C1-" + unique(), "BK-QS-C2-" + unique()}, 409);

        // 未放行 QUANTIFIED 父批 → 409
        String pending = "BK-QS-PEND-" + unique();
        createQuantified(pending, List.of("t1"), 4, 0, 201);
        splitQuantities(pending, "CK-SQP-" + unique(), List.of(new int[]{2, 0}, new int[]{2, 0}),
                new String[]{"BK-QS-C3-" + unique(), "BK-QS-C4-" + unique()}, 409);

        // 子批 maxSampleAmount 超自身 quantity → 400
        String released = "BK-QS-REL-" + unique();
        createQuantified(released, List.of("t1"), 4, 0, 201);
        submitTest(released, "TK-1", "t1", "PASS", "insp", null, null, 201);
        approve(released, "qa", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(released, "ops", "OPERATIONS", "CK-AO-" + unique(), 201);
        splitQuantities(released, "CK-SQM-" + unique(), List.of(new int[]{2, 3}, new int[]{2, 0}),
                new String[]{"BK-QS-C5-" + unique(), "BK-QS-C6-" + unique()}, 400);
        assertEquals("RELEASED", currentStatus(released));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConsumption_budgetEnforcedExactlyOnce() throws Exception {
        String batchKey = "BK-Q-RACE-" + unique();
        createQuantified(batchKey, List.of("t1", "t2"), 10, 3, 201);

        // 两笔各耗 2 的消耗检验并发，预算 3 只允许一笔成功
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("CK-R1-" + unique(), "TK-R1", "t1", "PASS", "i1",
                                "SK-R1", 2))),
                () -> callStatus(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody("CK-R2-" + unique(), "TK-R2", "t2", "PASS", "i2",
                                "SK-R2", 2)))
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
        // 只扣一笔：剩余 8，凭证 1 张，检验 1 条
        assertEquals(8, remainingOf(batchKey));
        assertEquals(1, countWhere("sample_voucher", "batch_key", batchKey));
        assertEquals(1, countWhere("test_result", "batch_key", batchKey));
    }

    @Test
    void concurrentSplitQuantities_sameCommandKey_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-Q-SRACE-" + unique();
        createQuantified(batchKey, List.of("t1"), 10, 0, 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp", null, null, 201);
        approve(batchKey, "qa", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-AO-" + unique(), 201);

        String c1 = "BK-Q-SRC1-" + unique();
        String c2 = "BK-Q-SRC2-" + unique();
        String body = splitQuantitiesBody("CK-SRACE-" + unique(),
                List.of(new int[]{4, 0}, new int[]{6, 0}), new String[]{c1, c2});
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/split-quantities")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/split-quantities")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, countWhere("batch", "batch_key", c1));
        assertEquals(1, countWhere("batch", "batch_key", c2));
        assertEquals("SPLIT", currentStatus(batchKey));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void createQuantified(String batchKey, List<String> items, int quantity,
                                  int maxSample, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":"
                + objectMapper.writeValueAsString(items)
                + ",\"batchType\":\"QUANTIFIED\",\"quantity\":" + quantity
                + ",\"maxSampleAmount\":" + maxSample + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void createStandard(String batchKey, List<String> items, int expected) throws Exception {
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":"
                + objectMapper.writeValueAsString(items) + "}";
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String testBody(String commandKey, String testKey, String item, String result,
                            String inspector, String sampleKey, Integer consumeAmount) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"testKey\":\"").append(testKey)
                .append("\",\"testItem\":\"").append(item)
                .append("\",\"result\":\"").append(result)
                .append("\",\"inspector\":\"").append(inspector).append("\"");
        if (sampleKey != null) {
            sb.append(",\"sampleKey\":\"").append(sampleKey).append("\"");
        }
        if (consumeAmount != null) {
            sb.append(",\"consumeAmount\":").append(consumeAmount);
        }
        return sb.append("}").toString();
    }

    private MvcResult submitTest(String batchKey, String testKey, String item, String result,
                                 String inspector, String sampleKey, Integer consumeAmount,
                                 int expected) throws Exception {
        return submitTest(batchKey, "CK-T-" + unique(), testKey, item, result, inspector,
                sampleKey, consumeAmount, expected);
    }

    private MvcResult submitTest(String batchKey, String commandKey, String testKey, String item,
                                 String result, String inspector, String sampleKey,
                                 Integer consumeAmount, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(commandKey, testKey, item, result, inspector,
                                sampleKey, consumeAmount)))
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

    private void split(String batchKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        mockMvc.perform(post("/api/batches/" + batchKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.append("]}").toString()))
                .andExpect(status().is(expected));
    }

    private String splitQuantitiesBody(String commandKey, List<int[]> quantityAndMax,
                                       String[] childKeys) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < childKeys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(childKeys[i])
                    .append("\",\"batchNo\":\"L").append(i)
                    .append("\",\"quantity\":").append(quantityAndMax.get(i)[0])
                    .append(",\"maxSampleAmount\":").append(quantityAndMax.get(i)[1]).append("}");
        }
        return sb.append("]}").toString();
    }

    private MvcResult splitQuantities(String batchKey, String commandKey,
                                      List<int[]> quantityAndMax, String[] childKeys, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/split-quantities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitQuantitiesBody(commandKey, quantityAndMax, childKeys)))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode historyOf(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private String currentStatus(String batchKey) throws Exception {
        return historyOf(batchKey).path("batch").path("status").asText();
    }

    private int remainingOf(String batchKey) {
        Integer remaining = jdbc.queryForObject(
                "SELECT quantity - consumed_amount FROM batch WHERE batch_key = ?",
                Integer.class, batchKey);
        return remaining == null ? -1 : remaining;
    }

    private int countWhere(String table, String column, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
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

