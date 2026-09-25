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
 * 批次产率核算测试：小数精度与四位产率展示、版本修订、父子投入产出守恒、
 * 多批次单事务回滚、召回门禁、yieldKey 幂等与并发裁决、产率/分配汇总/召回阻断查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchYieldAccountingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM batch_yield");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程与查询 ----------

    @Test
    void happyRegister_rawValuesRetained_rateShownFourDecimals_andQueries() throws Exception {
        String batch = "BK-Y-H-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");

        MvcResult result = submitYield("op-1", "YK-H-1",
                List.of(entryJson(batch, null, "100.5", "95.25")), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("YK-H-1", body.path("yieldKey").asText());
        JsonNode entry = body.path("entries").get(0);
        assertEquals(batch, entry.path("batchKey").asText());
        assertEquals(1, entry.path("version").asInt());
        assertEquals("op-1", entry.path("operatorId").asText());
        // 95.25 / 100.5 = 0.947761... 按四位小数 HALF_UP 展示
        assertEquals(0, new BigDecimal("0.9478").compareTo(entry.path("yieldRate").decimalValue()));
        // 原始数值保留（含尾部零）
        assertTrue(result.getResponse().getContentAsString().contains("95.25"));
        assertEquals(0, new BigDecimal("100.5").compareTo(entry.path("inputQuantity").decimalValue()));

        // 查询批次产率
        JsonNode yieldView = getYield(batch);
        assertEquals("RELEASED", yieldView.path("status").asText());
        assertEquals(1, yieldView.path("yield").path("version").asInt());
        assertEquals(0, new BigDecimal("0.9478")
                .compareTo(yieldView.path("yield").path("yieldRate").decimalValue()));
        assertTrue(yieldView.path("recallBlock").isNull());

        // 父子分配汇总：无父无子
        JsonNode allocation = getAllocation(batch);
        assertEquals(0, new BigDecimal("95.25")
                .compareTo(allocation.path("outputQuantity").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(allocation.path("allocatedQuantity").decimalValue()));
        assertEquals(0, new BigDecimal("95.25")
                .compareTo(allocation.path("remainingQuantity").decimalValue()));
        assertTrue(allocation.path("parent").isNull());
        assertEquals(0, allocation.path("children").size());
    }

    @Test
    void decimalPrecision_threeDecimalsOk_fourDecimalsOrNonPositiveRejected() throws Exception {
        String b1 = "BK-Y-D1-" + unique();
        createBatch(b1, List.of("t1"), 201);
        releaseBatch(b1, "insp-1");
        // 整数与小数混合，1/3 产率四位小数截断
        MvcResult result = submitYield("op", "YK-D-1",
                List.of(entryJson(b1, null, "3", "1")), 201);
        JsonNode entry = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("entries").get(0);
        assertEquals(0, new BigDecimal("0.3333").compareTo(entry.path("yieldRate").decimalValue()));

        // 三位小数边界：原始数值（含尾部零）保留
        String b2 = "BK-Y-D2-" + unique();
        createBatch(b2, List.of("t1"), 201);
        releaseBatch(b2, "insp-1");
        MvcResult scaled = submitYield("op", "YK-D-2",
                List.of(entryJson(b2, null, "2.500", "1.875")), 201);
        assertTrue(scaled.getResponse().getContentAsString().contains("2.500"));
        assertEquals(0, new BigDecimal("0.7500").compareTo(objectMapper
                .readTree(scaled.getResponse().getContentAsString())
                .path("entries").get(0).path("yieldRate").decimalValue()));

        // 四位小数 → 400
        submitYield("op", "YK-D-3", List.of(entryJson(b2, 1L, "1.0005", "1")), 400);
        // 零与负数 → 400
        submitYield("op", "YK-D-4", List.of(entryJson(b2, 1L, "0", "1")), 400);
        submitYield("op", "YK-D-5", List.of(entryJson(b2, 1L, "1", "-0.5")), 400);
        // 失败请求不写入
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, b2));
    }

    @Test
    void statusGate_onlyReleasedOrSplitCanRegister() throws Exception {
        String quarantined = "BK-Y-SQ-" + unique();
        createBatch(quarantined, List.of("t1"), 201);
        submitYield("op", "YK-S-1", List.of(entryJson(quarantined, null, "10", "9")), 409);

        String pending = "BK-Y-SP-" + unique();
        createBatch(pending, List.of("t1"), 201);
        submitTest(pending, "t1", "PASS", "insp", 201);
        submitYield("op", "YK-S-2", List.of(entryJson(pending, null, "10", "9")), 409);

        String rejected = "BK-Y-SR-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        submitYield("op", "YK-S-3", List.of(entryJson(rejected, null, "10", "9")), 409);
    }

    @Test
    void versionSemantics_singleRecordPerBatch_revisionRequiresExpectedVersion() throws Exception {
        String batch = "BK-Y-V-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");

        submitYield("op", "YK-V-1", List.of(entryJson(batch, null, "100", "90")), 201);
        // 已存在记录，无 expectedVersion 重复登记 → 409
        submitYield("op", "YK-V-2", List.of(entryJson(batch, null, "100", "91")), 409);
        // expectedVersion 与当前版本不一致 → 409
        submitYield("op", "YK-V-3", List.of(entryJson(batch, 3L, "100", "91")), 409);
        // 携带正确 expectedVersion 修订 → 版本递增，数值更新
        MvcResult revised = submitYield("op-2", "YK-V-4",
                List.of(entryJson(batch, 1L, "120", "114")), 201);
        JsonNode entry = objectMapper.readTree(revised.getResponse().getContentAsString())
                .path("entries").get(0);
        assertEquals(2, entry.path("version").asInt());
        assertEquals("op-2", entry.path("operatorId").asText());
        assertEquals(0, new BigDecimal("0.9500").compareTo(entry.path("yieldRate").decimalValue()));
        // 旧版本号再次修订 → 409
        submitYield("op", "YK-V-5", List.of(entryJson(batch, 1L, "120", "115")), 409);
        // 同一批次仍仅一份记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM batch_yield WHERE batch_key = ?", Integer.class, batch));

        // 无记录批次携带 expectedVersion → 409
        String fresh = "BK-Y-VF-" + unique();
        createBatch(fresh, List.of("t1"), 201);
        releaseBatch(fresh, "insp-1");
        submitYield("op", "YK-V-6", List.of(entryJson(fresh, 1L, "10", "9")), 409);
    }

    // ---------- 父子守恒 ----------

    @Test
    void parentChildConservation_parentOutputCapsChildrenInputs() throws Exception {
        String parent = "BK-Y-PC-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String c1 = "BK-Y-PC-C1-" + unique();
        String c2 = "BK-Y-PC-C2-" + unique();
        split(parent, "CK-PC-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        releaseBatch(c1, "insp-2");
        releaseBatch(c2, "insp-3");

        // 父批未登记产出量 → 422
        MvcResult noParent = submitYield("op", "YK-PC-1",
                List.of(entryJson(c1, null, "10", "9")), 422);
        assertTrue(objectMapper.readTree(noParent.getResponse().getContentAsString())
                .path("message").asText().contains(parent));

        // 父批（SPLIT 完成批次）登记产出量 100
        submitYield("op", "YK-PC-2", List.of(entryJson(parent, null, "110", "100")), 201);
        // 子批投入 60 ≤ 100 → 201
        submitYield("op", "YK-PC-3", List.of(entryJson(c1, null, "60", "58")), 201);
        // 再分配 50：60 + 50 > 100 → 422，报文给出父批次、已分配与拟分配量
        MvcResult exceeded = submitYield("op", "YK-PC-4",
                List.of(entryJson(c2, null, "50", "48")), 422);
        String message = objectMapper.readTree(exceeded.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains(parent), message);
        assertTrue(message.contains("已分配 60.000"), message);
        assertTrue(message.contains("拟分配 50"), message);
        // 恰好分满 100 → 201
        submitYield("op", "YK-PC-5", List.of(entryJson(c2, null, "40", "39")), 201);

        // 分配汇总：父批产出 100，已分配 100，剩余 0
        JsonNode allocation = getAllocation(parent);
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(allocation.path("outputQuantity").decimalValue()));
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(allocation.path("allocatedQuantity").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                allocation.path("remainingQuantity").decimalValue()));
        assertEquals(2, allocation.path("children").size());
        // 子批视角：parent 汇总指向父批
        JsonNode childView = getAllocation(c1);
        assertEquals(parent, childView.path("parent").path("batchKey").asText());
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(childView.path("parent").path("allocatedQuantity").decimalValue()));
    }

    @Test
    void reviseParentOutputBelowAllocated_rejected422_noPartialEffect() throws Exception {
        String parent = "BK-Y-RP-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String c1 = "BK-Y-RP-C1-" + unique();
        split(parent, "CK-RP-S", List.of(new String[]{c1, "L1"},
                new String[]{"BK-Y-RP-C2-" + unique(), "L2"}), 201);
        releaseBatch(c1, "insp-2");
        submitYield("op", "YK-RP-1", List.of(entryJson(parent, null, "110", "100")), 201);
        submitYield("op", "YK-RP-2", List.of(entryJson(c1, null, "60", "58")), 201);

        // 修订父批产出量到 50 < 已分配 60 → 422
        MvcResult rejected = submitYield("op", "YK-RP-3",
                List.of(entryJson(parent, 1L, "110", "50")), 422);
        String message = objectMapper.readTree(rejected.getResponse().getContentAsString())
                .path("message").asText();
        assertTrue(message.contains(parent), message);
        assertTrue(message.contains("60.000"), message);
        // 不部分生效：父批产出量与版本不变
        JsonNode yieldView = getYield(parent);
        assertEquals(1, yieldView.path("yield").path("version").asInt());
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(yieldView.path("yield").path("outputQuantity").decimalValue()));
    }

    @Test
    void multiEntryRequest_validatedAsFinalState_andRollbackAsWhole() throws Exception {
        // 同一请求内先登记父批产出量再登记子批投入：按最终分配校验通过
        String parent = "BK-Y-ME-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String c1 = "BK-Y-ME-C1-" + unique();
        split(parent, "CK-ME-S", List.of(new String[]{c1, "L1"},
                new String[]{"BK-Y-ME-C2-" + unique(), "L2"}), 201);
        releaseBatch(c1, "insp-2");
        submitYield("op", "YK-ME-1", List.of(
                entryJson(parent, null, "110", "100"),
                entryJson(c1, null, "60", "58")), 201);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_yield", Integer.class));

        // 多批次整单超分 → 422 且整单回滚，任一条目都不落库
        String parent2 = "BK-Y-ME-P2-" + unique();
        createBatch(parent2, List.of("t1"), 201);
        releaseBatch(parent2, "insp-1");
        String d1 = "BK-Y-ME-D1-" + unique();
        String d2 = "BK-Y-ME-D2-" + unique();
        split(parent2, "CK-ME-S2", List.of(new String[]{d1, "L1"}, new String[]{d2, "L2"}), 201);
        releaseBatch(d1, "insp-2");
        releaseBatch(d2, "insp-3");
        submitYield("op", "YK-ME-2", List.of(entryJson(parent2, null, "110", "100")), 201);
        submitYield("op", "YK-ME-3", List.of(
                entryJson(d1, null, "60", "58"),
                entryJson(d2, null, "50", "49")), 422);
        assertTrue(getYield(d1).path("yield").isNull());
        assertTrue(getYield(d2).path("yield").isNull());

        // 单条失败（未知批次）整单回滚：同伴条目不落库
        submitYield("op", "YK-ME-4", List.of(
                entryJson(d1, null, "60", "58"),
                entryJson("NO-SUCH-BATCH", null, "1", "1")), 404);
        assertTrue(getYield(d1).path("yield").isNull());
    }

    // ---------- 召回门禁 ----------

    @Test
    void recallGate_recalledClosureBlocked409_existingRecordsRetained() throws Exception {
        String root = "BK-Y-RG-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String c1 = "BK-Y-RG-C1-" + unique();
        String c2 = "BK-Y-RG-C2-" + unique();
        split(root, "CK-RG-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        releaseBatch(c1, "insp-2");
        releaseBatch(c2, "insp-3");
        submitYield("op", "YK-RG-1", List.of(entryJson(root, null, "110", "100")), 201);
        submitYield("op", "YK-RG-2", List.of(entryJson(c1, null, "60", "58")), 201);

        recall(root, "qa-lead", "上游原料污染", "CK-RG-R", 201);

        // 召回批次自身：新增/修订 → 409
        submitYield("op", "YK-RG-3", List.of(entryJson(root, 1L, "110", "99")), 409);
        // 血缘闭包内后代：新增/修订 → 409
        submitYield("op", "YK-RG-4", List.of(entryJson(c2, null, "40", "39")), 409);
        submitYield("op", "YK-RG-5", List.of(entryJson(c1, 1L, "60", "57")), 409);

        // 已登记记录不删除，查询返回召回阻断原因
        JsonNode rootView = getYield(root);
        assertEquals(1, rootView.path("yield").path("version").asInt());
        assertEquals(root, rootView.path("recallBlock").path("recalledBatchKey").asText());
        assertTrue(rootView.path("recallBlock").path("direct").asBoolean());
        assertEquals("上游原料污染", rootView.path("recallBlock").path("reason").asText());

        JsonNode childView = getYield(c1);
        assertEquals(1, childView.path("yield").path("version").asInt());
        assertEquals(root, childView.path("recallBlock").path("recalledBatchKey").asText());
        assertFalse(childView.path("recallBlock").path("direct").asBoolean());

        JsonNode c2View = getYield(c2);
        assertTrue(c2View.path("yield").isNull());
        assertEquals(root, c2View.path("recallBlock").path("recalledBatchKey").asText());
    }

    // ---------- 幂等 ----------

    @Test
    void yieldKeyIdempotency_sameParamsReplay_changedParamsConflict_failureDoesNotOccupyKey()
            throws Exception {
        String batch = "BK-Y-ID-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");
        String body = yieldBody("YK-ID-1", List.of(entryJson(batch, null, "100", "95")));

        MvcResult first = submitYieldRaw("op-1", body, 201);
        MvcResult replay = submitYieldRaw("op-1", body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不产生第二份记录，版本不前进
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM batch_yield WHERE batch_key = ?", Integer.class, batch));

        // 同 yieldKey 改参 → 409
        submitYield("op-1", "YK-ID-1", List.of(entryJson(batch, null, "100", "96")), 409);
        // 同 yieldKey 改操作者 → 409（指纹含操作者）
        submitYield("op-2", "YK-ID-1", List.of(entryJson(batch, null, "100", "95")), 409);

        // 失败不占键：先以超分失败，再同键修正成功
        String parent = "BK-Y-ID-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String child = "BK-Y-ID-C-" + unique();
        split(parent, "CK-ID-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-Y-ID-C2-" + unique(), "L2"}), 201);
        releaseBatch(child, "insp-2");
        submitYield("op", "YK-ID-2", List.of(entryJson(parent, null, "110", "100")), 201);
        submitYield("op", "YK-ID-F", List.of(entryJson(child, null, "120", "110")), 422);
        submitYield("op", "YK-ID-F", List.of(entryJson(child, null, "60", "58")), 201);
    }

    // ---------- 参数校验与未知批次 ----------

    @Test
    void invalidRequests_return400or409or404() throws Exception {
        String batch = "BK-Y-B-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");

        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/yields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-B-0", List.of(entryJson(batch, null, "1", "1")))))
                .andExpect(status().isBadRequest());
        // 空 entries → 400
        submitYield("op", "YK-B-1", List.of(), 400);
        // 请求内 batchKey 重复 → 409
        submitYield("op", "YK-B-2", List.of(
                entryJson(batch, null, "1", "1"),
                entryJson(batch, null, "2", "2")), 409);
        // 未知批次 → 404；未知批次查询 → 404
        submitYield("op", "YK-B-3", List.of(entryJson("NO-SUCH", null, "1", "1")), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH/yield")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/yield/allocation"))
                .andExpect(status().isNotFound());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSiblingRegistrations_parentOutputEnforcedExactlyOnce() throws Exception {
        String parent = "BK-Y-CS-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        releaseBatch(parent, "insp-1");
        String c1 = "BK-Y-CS-C1-" + unique();
        String c2 = "BK-Y-CS-C2-" + unique();
        split(parent, "CK-CS-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        releaseBatch(c1, "insp-2");
        releaseBatch(c2, "insp-3");
        submitYield("op", "YK-CS-0", List.of(entryJson(parent, null, "110", "100")), 201);

        // 两个子批并发各拟分配 60：父批行锁串行化，恰好一个成功一个 422
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/yields")
                        .header("X-Actor-Id", "op-a").contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-CS-1",
                                List.of(entryJson(c1, null, "60", "58"))))),
                () -> callStatus(post("/api/batches/yields")
                        .header("X-Actor-Id", "op-b").contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-CS-2",
                                List.of(entryJson(c2, null, "60", "58"))))));
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, countOf(201, first, second), "恰好一个登记成功: " + first + "/" + second);
        assertEquals(1, countOf(422, first, second));
        // 最终数据：仅一份子批产率，已分配不超过父批产出量
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_yield y"
                + " JOIN batch_lineage l ON y.batch_key = l.child_key"
                + " WHERE l.parent_key = ?", Integer.class, parent));
        JsonNode allocation = getAllocation(parent);
        assertTrue(allocation.path("allocatedQuantity").decimalValue()
                        .compareTo(allocation.path("outputQuantity").decimalValue()) <= 0,
                "已分配不得超过父批产出量");
    }

    @Test
    void concurrentSameYieldKey_sameSnapshotForBoth() throws Exception {
        String batch = "BK-Y-CK-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");
        String body = yieldBody("YK-CK-1", List.of(entryJson(batch, null, "100", "95")));

        List<Future<String>> results = runConcurrentBodies(
                () -> callBody(post("/api/batches/yields")
                        .header("X-Actor-Id", "op").contentType(MediaType.APPLICATION_JSON)
                        .content(body)),
                () -> callBody(post("/api/batches/yields")
                        .header("X-Actor-Id", "op").contentType(MediaType.APPLICATION_JSON)
                        .content(body)));
        String first = results.get(0).get(30, TimeUnit.SECONDS);
        String second = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(first, second, "同键同参并发重放返回首次快照");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch));
    }

    @Test
    void concurrentRecallAndYield_commitOrderDecides() throws Exception {
        String batch = "BK-Y-CR-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/yields")
                        .header("X-Actor-Id", "op").contentType(MediaType.APPLICATION_JSON)
                        .content(yieldBody("YK-CR-1",
                                List.of(entryJson(batch, null, "100", "95"))))),
                () -> callStatus(post("/api/batches/" + batch + "/recall")
                        .header("X-Actor-Id", "qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-R\",\"reason\":\"质量召回\"}")));
        int yield = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "RELEASED 批次始终可召回");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_yield WHERE batch_key = ?", Integer.class, batch);
        if (yield == 201) {
            // 产率先提交：记录保留，随后召回生效但不删除记录
            assertEquals(1, count);
        } else {
            // 召回先提交：产率被门禁拦截 409，无记录
            assertEquals(409, yield);
            assertEquals(0, count);
        }
        assertEquals("RECALLED", currentStatus(batch));
        // 无论哪种顺序，召回后新增产率一律 409
        submitYield("op", "YK-CR-2", List.of(entryJson(batch, null, "100", "95")), 409);
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int countOf(int expected, int... actuals) {
        int count = 0;
        for (int actual : actuals) {
            if (actual == expected) {
                count++;
            }
        }
        return count;
    }

    private String entryJson(String batchKey, Long expectedVersion, String input, String output) {
        StringBuilder sb = new StringBuilder("{\"batchKey\":\"").append(batchKey).append("\"");
        if (expectedVersion != null) {
            sb.append(",\"expectedVersion\":").append(expectedVersion);
        }
        return sb.append(",\"inputQuantity\":").append(input)
                .append(",\"outputQuantity\":").append(output).append("}").toString();
    }

    private String yieldBody(String yieldKey, List<String> entries) {
        return "{\"yieldKey\":\"" + yieldKey + "\",\"entries\":["
                + String.join(",", entries) + "]}";
    }

    private MvcResult submitYieldRaw(String actor, String body, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/yields")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult submitYield(String actor, String yieldKey, List<String> entries, int expected)
            throws Exception {
        return submitYieldRaw(actor, yieldBody(yieldKey, entries), expected);
    }

    private JsonNode getYield(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/yield"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getAllocation(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/yield/allocation"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCmd("CK-C-" + unique(), batchKey, "PROD-1", "LOT-1",
                                        Instant.parse("2026-01-02T03:04:05Z"), items))))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-T-" + unique(), "TK-" + unique(), item, result,
                                        inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
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

    private String splitBody(String commandKey, List<String[]> children) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void split(String parentKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
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

    private String callBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getContentAsString();
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
