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
import java.util.HashMap;
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
 * 批次产率核算测试：登记/修订主流程、小数精度、版本裁决、父子投入产出守恒、
 * 多批次整单回滚、召回血缘闭包门禁、yieldKey 幂等与并发裁决、分配汇总与阻断原因查询。
 * 全部通过真实 H2（MySQL 兼容模式）验证唯一约束、事务回滚与行锁并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchYieldTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM batch_yield");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 登记主流程与小数精度 ----------

    @Test
    void registerYield_happyPath_rateRoundedTo4Decimals_rawValuesRetained() throws Exception {
        String batch = "BK-Y-R-" + unique();
        createBatch(batch);

        MvcResult result = submitYield(yieldBody("YK-R1", "op-1",
                List.of(item(batch, "3", "2", null))), 201);
        JsonNode record = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("records").get(0);
        assertEquals(batch, record.path("batchKey").asText());
        assertEquals("3.000", record.path("inputQty").asText());
        assertEquals("2.000", record.path("outputQty").asText());
        // 2/3 = 0.66666... → 四位小数 HALF_UP
        assertEquals("0.6667", record.path("yieldRate").asText());
        assertEquals(1, record.path("version").asLong());
        assertEquals("op-1", record.path("operator").asText());

        // 查询视图与提交响应一致，原始数值保留
        JsonNode queried = getJson("/api/batches/" + batch + "/yield", 200);
        assertEquals("3.000", queried.path("inputQty").asText());
        assertEquals("2.000", queried.path("outputQty").asText());
        assertEquals("0.6667", queried.path("yieldRate").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));
    }

    @Test
    void registerYield_decimalPrecisionRules() throws Exception {
        // 四位小数 → 400
        String b1 = "BK-Y-D1-" + unique();
        createBatch(b1);
        submitYield(yieldBody("YK-D1", "op", List.of(item(b1, "1.0001", "1", null))), 400);
        // 零与负数 → 400
        String b2 = "BK-Y-D2-" + unique();
        createBatch(b2);
        submitYield(yieldBody("YK-D2", "op", List.of(item(b2, "0", "1", null))), 400);
        submitYield(yieldBody("YK-D3", "op", List.of(item(b2, "1", "-0.5", null))), 400);
        // 三位小数边界 → 201，1/3 产率四位小数
        submitYield(yieldBody("YK-D4", "op", List.of(item(b2, "3", "1.001", null))), 201);
        JsonNode queried = getJson("/api/batches/" + b2 + "/yield", 200);
        assertEquals("1.001", queried.path("outputQty").asText());
        assertEquals("0.3337", queried.path("yieldRate").asText());
        // 末尾零不影响数值：1.2300 规范化为 1.230 存储
        String b3 = "BK-Y-D3-" + unique();
        createBatch(b3);
        submitYield(yieldBody("YK-D5", "op", List.of(item(b3, "1.2300", "1", null))), 201);
        assertEquals("1.230", getJson("/api/batches/" + b3 + "/yield", 200)
                .path("inputQty").asText());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_yield", Integer.class));
    }

    @Test
    void registerYield_unknownBatchReturns404_andMissingYieldReturns404() throws Exception {
        submitYield(yieldBody("YK-404", "op",
                List.of(item("NO-SUCH-BATCH", "1", "1", null))), 404);
        String batch = "BK-Y-NY-" + unique();
        createBatch(batch);
        mockMvc.perform(get("/api/batches/" + batch + "/yield"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/yield"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/yield-allocation"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/yield-block"))
                .andExpect(status().isNotFound());
    }

    // ---------- 修订与版本裁决 ----------

    @Test
    void reviseYield_expectedVersionRules() throws Exception {
        String batch = "BK-Y-V-" + unique();
        createBatch(batch);
        submitYield(yieldBody("YK-V1", "op-1", List.of(item(batch, "10", "9", null))), 201);

        // 重复登记（不带 expectedVersion）→ 409
        submitYield(yieldBody("YK-V2", "op-1", List.of(item(batch, "10", "8", null))), 409);
        // 版本不匹配 → 409
        submitYield(yieldBody("YK-V3", "op-1", List.of(item(batch, "10", "8", 5L))), 409);
        // 携带正确 expectedVersion 修订 → 200，版本 +1
        MvcResult revised = submitYield(yieldBody("YK-V4", "op-2",
                List.of(item(batch, "10", "8", 1L))), 200);
        JsonNode record = objectMapper.readTree(revised.getResponse().getContentAsString())
                .path("records").get(0);
        assertEquals(2, record.path("version").asLong());
        assertEquals("8.000", record.path("outputQty").asText());
        assertEquals("op-2", record.path("operator").asText());
        assertEquals("0.8000", record.path("yieldRate").asText());

        JsonNode queried = getJson("/api/batches/" + batch + "/yield", 200);
        assertEquals(2, queried.path("version").asLong());
        assertEquals("8.000", queried.path("outputQty").asText());
        // 始终仅一份记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));

        // 未登记批次携带 expectedVersion → 409
        String other = "BK-Y-V2-" + unique();
        createBatch(other);
        submitYield(yieldBody("YK-V5", "op", List.of(item(other, "1", "1", 1L))), 409);
    }

    // ---------- 父子投入产出守恒 ----------

    @Test
    void splitChildrenConservation_exceedReturns422WithDetails() throws Exception {
        String parent = "BK-Y-CP-" + unique();
        String child1 = "BK-Y-CC1-" + unique();
        String child2 = "BK-Y-CC2-" + unique();
        createSplitParent(parent, child1, child2);

        // 子批先于父批登记 → 422（父批未登记产出量）
        submitYield(yieldBody("YK-CP0", "op", List.of(item(child1, "10", "9", null))), 422);

        // 父批登记产出 100
        submitYield(yieldBody("YK-CP1", "op", List.of(item(parent, "120", "100", null))), 201);
        // 子批 1 分配 60 → 201
        submitYield(yieldBody("YK-CP2", "op", List.of(item(child1, "60", "58", null))), 201);
        // 子批 2 拟分配 50：60 + 50 > 100 → 422，报文含父批次、已分配、拟分配
        MvcResult exceeded = mockMvc.perform(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-CP3", "op", List.of(item(child2, "50", "49", null)))))
                .andExpect(status().is(422)).andReturn();
        String message = objectMapper.readTree(exceeded.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains(parent), "报文应含父批次: " + message);
        assertTrue(message.contains("60.000"), "报文应含已分配量: " + message);
        assertTrue(message.contains("50.000"), "报文应含拟分配量: " + message);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, child2));

        // 子批 2 分配 40：60 + 40 = 100 恰好不超 → 201
        submitYield(yieldBody("YK-CP4", "op", List.of(item(child2, "40", "39", null))), 201);

        // 无血缘的独立批次不受父批约束
        String free = "BK-Y-FREE-" + unique();
        createBatch(free);
        submitYield(yieldBody("YK-CP5", "op", List.of(item(free, "999", "1", null))), 201);
    }

    @Test
    void multiBatchRequest_validatesFinalAllocationThenRollsBackAtomically() throws Exception {
        String parent = "BK-Y-MP-" + unique();
        String child1 = "BK-Y-MC1-" + unique();
        String child2 = "BK-Y-MC2-" + unique();
        String child3 = "BK-Y-MC3-" + unique();
        createBatch(parent);
        releaseBatch(parent);
        split(parent, List.of(child1, child2, child3));
        submitYield(yieldBody("YK-MP0", "op", List.of(item(parent, "120", "100", null))), 201);
        submitYield(yieldBody("YK-MP1", "op", List.of(item(child1, "60", "58", null))), 201);

        // 同一请求：child2 拟分配 30（合计 90 合法），child3 拟分配 20（最终合计 110 超额）
        // → 整单 422，child2 也不得写入
        submitYield(yieldBody("YK-MP2", "op", List.of(
                item(child2, "30", "29", null),
                item(child3, "20", "19", null))), 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key IN (?, ?)",
                Integer.class, child2, child3));

        // 同一请求内多子批最终分配合计合法 → 整单成功
        submitYield(yieldBody("YK-MP3", "op", List.of(
                item(child2, "30", "29", null),
                item(child3, "10", "9", null))), 201);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key IN (?, ?)",
                Integer.class, child2, child3));
    }

    @Test
    void reviseParentOutputBelowChildrenAllocation_returns422_noPartialEffect() throws Exception {
        String parent = "BK-Y-PP-" + unique();
        String child1 = "BK-Y-PC1-" + unique();
        String child2 = "BK-Y-PC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-PP1", "op", List.of(item(parent, "120", "100", null))), 201);
        submitYield(yieldBody("YK-PP2", "op", List.of(item(child1, "60", "58", null))), 201);
        submitYield(yieldBody("YK-PP3", "op", List.of(item(child2, "30", "29", null))), 201);

        // 父批产出修订为 80 < 已分配 90 → 422，且不部分生效
        submitYield(yieldBody("YK-PP4", "op", List.of(item(parent, "120", "80", 1L))), 422);
        JsonNode parentYield = getJson("/api/batches/" + parent + "/yield", 200);
        assertEquals("100.000", parentYield.path("outputQty").asText());
        assertEquals(1, parentYield.path("version").asLong());

        // 修订为 90 等于已分配 → 200
        submitYield(yieldBody("YK-PP5", "op", List.of(item(parent, "120", "90", 1L))), 200);
        assertEquals("90.000", getJson("/api/batches/" + parent + "/yield", 200)
                .path("outputQty").asText());
    }

    @Test
    void reviseChildInput_replacesOldAllocation() throws Exception {
        String parent = "BK-Y-RP-" + unique();
        String child1 = "BK-Y-RC1-" + unique();
        String child2 = "BK-Y-RC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-RP1", "op", List.of(item(parent, "120", "100", null))), 201);
        submitYield(yieldBody("YK-RP2", "op", List.of(item(child1, "60", "58", null))), 201);

        // child1 修订投入 60 → 90：按最终分配 90 校验（不是 60+90）→ 200（修订）
        submitYield(yieldBody("YK-RP3", "op", List.of(item(child1, "90", "88", 1L))), 200);
        // child2 拟分配 20：90 + 20 > 100 → 422
        submitYield(yieldBody("YK-RP4", "op", List.of(item(child2, "20", "19", null))), 422);
        // child2 拟分配 10：90 + 10 = 100 → 201
        submitYield(yieldBody("YK-RP5", "op", List.of(item(child2, "10", "9", null))), 201);
    }

    // ---------- 召回门禁 ----------

    @Test
    void recallClosure_blocksRegisterAndRevise_returns409_recordsPreserved() throws Exception {
        String parent = "BK-Y-QP-" + unique();
        String child1 = "BK-Y-QC1-" + unique();
        String child2 = "BK-Y-QC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-QP1", "op", List.of(item(parent, "120", "100", null))), 201);
        submitYield(yieldBody("YK-QP1B", "op", List.of(item(child1, "60", "58", null))), 201);

        recall(parent, "qa-lead", "上游原料污染", "CK-Y-QR", 201);

        // 召回批次自身：修订 → 409
        submitYield(yieldBody("YK-QP2", "op", List.of(item(parent, "120", "95", 1L))), 409);
        // 血缘闭包内后代：登记 → 409，修订 → 409
        submitYield(yieldBody("YK-QP3", "op", List.of(item(child2, "10", "9", null))), 409);
        submitYield(yieldBody("YK-QP4", "op", List.of(item(child1, "60", "57", 1L))), 409);

        // 已登记记录不删除、不改写
        JsonNode parentYield = getJson("/api/batches/" + parent + "/yield", 200);
        assertEquals("100.000", parentYield.path("outputQty").asText());
        assertEquals(1, parentYield.path("version").asLong());
        JsonNode childYield = getJson("/api/batches/" + child1 + "/yield", 200);
        assertEquals("60.000", childYield.path("inputQty").asText());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_yield", Integer.class));

        // 阻断原因查询：后代指向召回祖先，召回批次指向自身，无关批次不阻断
        JsonNode childBlock = getJson("/api/batches/" + child2 + "/yield-block", 200);
        assertTrue(childBlock.path("blocked").asBoolean());
        assertEquals(parent, childBlock.path("recalledBatchKey").asText());
        assertEquals("上游原料污染", childBlock.path("reason").asText());
        JsonNode parentBlock = getJson("/api/batches/" + parent + "/yield-block", 200);
        assertTrue(parentBlock.path("blocked").asBoolean());
        assertEquals(parent, parentBlock.path("recalledBatchKey").asText());
        String free = "BK-Y-QF-" + unique();
        createBatch(free);
        JsonNode freeBlock = getJson("/api/batches/" + free + "/yield-block", 200);
        assertFalse(freeBlock.path("blocked").asBoolean());
        assertTrue(freeBlock.path("recalledBatchKey").isNull());
        // 无关批次可正常登记
        submitYield(yieldBody("YK-QP5", "op", List.of(item(free, "5", "4", null))), 201);
    }

    // ---------- yieldKey 幂等 ----------

    @Test
    void yieldKey_sameParamsReplaysSnapshot_changedParamsConflicts_failureDoesNotOccupyKey()
            throws Exception {
        String batch = "BK-Y-I-" + unique();
        createBatch(batch);
        String body = yieldBody("YK-IDEM", "op-1", List.of(item(batch, "10", "9", null)));

        MvcResult first = mockMvc.perform(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));

        // 同键改参 → 409
        submitYield(yieldBody("YK-IDEM", "op-1", List.of(item(batch, "10", "8", null))), 409);

        // 失败不占键：先以超额请求失败，再同键修正参数成功
        String parent = "BK-Y-IP-" + unique();
        String child1 = "BK-Y-IC1-" + unique();
        String child2 = "BK-Y-IC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-IP1", "op", List.of(item(parent, "100", "10", null))), 201);
        submitYield(yieldBody("YK-IFAIL", "op", List.of(item(child1, "20", "1", null))), 422);
        submitYield(yieldBody("YK-IFAIL", "op", List.of(item(child1, "5", "4", null))), 201);
        assertEquals("5.000", getJson("/api/batches/" + child1 + "/yield", 200)
                .path("inputQty").asText());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentChildrenRegister_exceedingParentOutput_exactlyOneWins() throws Exception {
        String parent = "BK-Y-XP-" + unique();
        String child1 = "BK-Y-XC1-" + unique();
        String child2 = "BK-Y-XC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-XP0", "op", List.of(item(parent, "120", "100", null))), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-X1", "op-a", List.of(item(child1, "70", "69", null))))),
                () -> callStatus(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-X2", "op-b", List.of(item(child2, "70", "69", null))))));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 各拟分配 70，父批产出 100：按提交顺序恰好一笔成功，另一笔 422
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "两笔各 70 的登记只能成功一笔: " + first + "," + second);
        assertTrue(first == 422 || second == 422);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key IN (?, ?)",
                Integer.class, child1, child2));
        // 最终分配不超过父批产出
        JsonNode allocation = getJson("/api/batches/" + parent + "/yield-allocation", 200);
        assertEquals("70.000", allocation.path("allocatedQty").asText());
        assertEquals("30.000", allocation.path("remainingQty").asText());
    }

    @Test
    void concurrentSameYieldKey_replaysFirstSnapshot() throws Exception {
        String batch = "BK-Y-XS-" + unique();
        createBatch(batch);
        String body = yieldBody("YK-XSAME", "op", List.of(item(batch, "10", "9", null)));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON).content(body)));
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'YIELD'", Integer.class));
    }

    @Test
    void concurrentRegisterAndAncestorRecall_commitOrderDecides() throws Exception {
        String parent = "BK-Y-XR-" + unique();
        String child = "BK-Y-XRC-" + unique();
        String sibling = "BK-Y-XRS-" + unique();
        createSplitParent(parent, child, sibling);
        submitYield(yieldBody("YK-XR0", "op", List.of(item(parent, "120", "100", null))), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-XR1", "op", List.of(item(child, "10", "9", null))))),
                () -> callStatus(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-Y-XR\",\"reason\":\"根批召回\"}")));
        int register = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 父批始终可召回");
        if (register == 201) {
            // 登记先提交：记录保留，但随后不得再修订
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, child));
            submitYield(yieldBody("YK-XR2", "op", List.of(item(child, "10", "8", 1L))), 409);
        } else {
            // 召回先提交：登记被 409 拦截，无记录
            assertEquals(409, register);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, child));
        }
    }

    // ---------- 分配汇总查询 ----------

    @Test
    void allocationSummary_parentAndChildViews() throws Exception {
        String parent = "BK-Y-AP-" + unique();
        String child1 = "BK-Y-AC1-" + unique();
        String child2 = "BK-Y-AC2-" + unique();
        createSplitParent(parent, child1, child2);
        submitYield(yieldBody("YK-AP1", "op", List.of(item(parent, "120", "100", null))), 201);
        submitYield(yieldBody("YK-AP2", "op", List.of(item(child1, "60", "58", null))), 201);

        JsonNode parentView = getJson("/api/batches/" + parent + "/yield-allocation", 200);
        assertEquals("100.000", parentView.path("outputQty").asText());
        assertEquals("60.000", parentView.path("allocatedQty").asText());
        assertEquals("40.000", parentView.path("remainingQty").asText());
        assertEquals(2, parentView.path("children").size());
        JsonNode c1 = parentView.path("children").get(0);
        assertEquals(child1, c1.path("batchKey").asText());
        assertEquals("60.000", c1.path("inputQty").asText());
        assertEquals("0.9667", c1.path("yieldRate").asText());
        // 未登记子批明细数量为 null
        assertTrue(parentView.path("children").get(1).path("inputQty").isNull());
        assertEquals(0, parentView.path("parents").size());

        JsonNode childView = getJson("/api/batches/" + child1 + "/yield-allocation", 200);
        assertEquals("58.000", childView.path("outputQty").asText());
        assertEquals(1, childView.path("parents").size());
        JsonNode p = childView.path("parents").get(0);
        assertEquals(parent, p.path("parentKey").asText());
        assertEquals("100.000", p.path("parentOutputQty").asText());
        assertEquals("60.000", p.path("allocatedQty").asText());
        assertEquals("40.000", p.path("remainingQty").asText());
        assertEquals("60.000", p.path("thisInputQty").asText());

        // 未登记批次：汇总字段为 null，子批分配为 0
        JsonNode emptyView = getJson("/api/batches/" + child2 + "/yield-allocation", 200);
        assertTrue(emptyView.path("outputQty").isNull());
        assertTrue(emptyView.path("remainingQty").isNull());
        assertEquals("0", emptyView.path("allocatedQty").asText());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-C-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", "PROD-1");
        body.put("batchNo", "LOT-1");
        body.put("producedAt", Instant.parse("2026-01-02T03:04:05Z"));
        body.put("requiredTests", List.of("t1"));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey) throws Exception {
        submitTestPass(batchKey);
        approve(batchKey, "qa-" + unique(), "QUALITY");
        approve(batchKey, "ops-" + unique(), "OPERATIONS");
    }

    private void submitTestPass(String batchKey) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", "CK-T-" + unique());
        body.put("testKey", "TK-" + unique());
        body.put("testItem", "t1");
        body.put("result", "PASS");
        body.put("inspector", "insp-" + unique());
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void split(String parentKey, List<String> childKeys) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"CK-S-").append(unique())
                .append("\",\"children\":[");
        for (int i = 0; i < childKeys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(childKeys.get(i))
                    .append("\",\"batchNo\":\"L").append(i + 1).append("\"}");
        }
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.append("]}").toString()))
                .andExpect(status().isCreated());
    }

    /**
     * 创建 RELEASED 父批并拆分为两个子批。
     */
    private void createSplitParent(String parent, String child1, String child2) throws Exception {
        createBatch(parent);
        releaseBatch(parent);
        split(parent, List.of(child1, child2));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    /**
     * 单个产率项：expectedVersion 为 null 表示登记，否则表示修订。
     */
    private Map<String, Object> item(String batchKey, String inputQty, String outputQty,
                                     Long expectedVersion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batchKey", batchKey);
        item.put("inputQty", inputQty);
        item.put("outputQty", outputQty);
        if (expectedVersion != null) {
            item.put("expectedVersion", expectedVersion);
        }
        return item;
    }

    private String yieldBody(String yieldKey, String operator, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("yieldKey", yieldKey);
        body.put("operator", operator);
        body.put("items", items);
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult submitYield(String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode getJson(String url, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(url))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
