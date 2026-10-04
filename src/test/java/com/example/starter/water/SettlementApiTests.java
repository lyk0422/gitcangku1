package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶梯计费结算与差额账本 API 测试：主流程（含题干合成算例）、失败分支、幂等与并发边界。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
    }

    private void transfer(String sourceKey, String targetKey, String actor) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        postOk("/api/transfers", body, actor);
    }

    private JsonNode settle(long windowId, String requestId, String actor) throws Exception {
        return postOk("/api/windows/" + windowId + "/settlement",
                Map.of("requestId", requestId), actor);
    }

    private JsonNode reconcile(long windowId, String requestId, int expectedVersion, String actor)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedSettlementVersion", expectedVersion);
        return postOk("/api/windows/" + windowId + "/settlement/reconcile", body, actor);
    }

    private JsonNode settlements(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/settlement");
    }

    private JsonNode lineOf(JsonNode settlement, String userId) {
        for (JsonNode line : settlement.get("lines")) {
            if (userId.equals(line.get("userId").asText())) {
                return line;
            }
        }
        throw new IllegalStateException("line not found for user: " + userId);
    }

    // ------------------------------------------------------------------
    // 主流程：阶梯计费与题干合成算例
    // ------------------------------------------------------------------

    @Test
    void settleBillsOnlyApprovedAllocationsPerFarmerWithTieredRates() throws Exception {
        long windowId = createWindow("ch-st-" + run, "1000");
        String userA = "fa-" + run + "-1";
        String userB = "fb-" + run + "-1";
        String userC = "fc-" + run + "-1";
        approve(submit(windowId, userA, "150", "alice"));
        approve(submit(windowId, userB, "100.005", "bob"));
        approve(submit(windowId, userC, "50", "carol"));
        // 未批准申请不参与结算
        submit(windowId, "fd-" + run + "-1", "500", "dave");
        // 其他窗口的已批准申请不参与本窗口结算
        long otherWindow = createWindow("ch-st-other-" + run, "1000");
        approve(submit(otherWindow, userA, "999", "alice"));

        JsonNode v1 = settle(windowId, key("rq"), "ops-1");
        assertEquals(windowId, v1.get("windowId").asLong());
        assertEquals(1, v1.get("version").asInt());
        assertEquals("100", v1.get("tier1Limit").asText());
        assertEquals("2.00", v1.get("tier1Rate").asText());
        assertEquals("3.00", v1.get("tier2Rate").asText());
        assertNotNull(v1.get("createdUtc").asText());
        assertEquals(3, v1.get("lines").size());

        // 150 -> 100*2 + 50*3 = 350.00
        JsonNode lineA = lineOf(v1, userA);
        assertEquals("150", lineA.get("approvedAmount").asText());
        assertEquals("350.00", lineA.get("amount").asText());
        assertEquals("350.00", lineA.get("diffAmount").asText());
        // 100.005 -> 200 + 0.015 = 200.015 -> HALF_UP 到分 200.02
        JsonNode lineB = lineOf(v1, userB);
        assertEquals("100.005", lineB.get("approvedAmount").asText());
        assertEquals("200.02", lineB.get("amount").asText());
        // 50 -> 100.00
        JsonNode lineC = lineOf(v1, userC);
        assertEquals("50", lineC.get("approvedAmount").asText());
        assertEquals("100.00", lineC.get("amount").asText());
        assertEquals("650.02", v1.get("totalAmount").asText());

        JsonNode list = settlements(windowId);
        assertEquals(1, list.get("currentVersion").asInt());
        assertEquals("650.02", list.get("currentNetReceivable").asText());
        assertEquals(1, list.get("settlements").size());
    }

    @Test
    void specScenario150ThenTransfer20ThenTransfer10() throws Exception {
        long windowId = createWindow("ch-spec-" + run, "1000");
        String userA = "fa-" + run + "-2";
        String userB = "fb-" + run + "-2";
        String source = submit(windowId, userA, "150", "alice");
        approve(source);

        // 合成 150 立方米应收 350 元
        JsonNode v1 = settle(windowId, key("rq"), "ops-1");
        assertEquals("350.00", lineOf(v1, userA).get("amount").asText());
        assertEquals("350.00", v1.get("totalAmount").asText());

        // 结算后仍允许额度转让：转出 20
        String target1 = submit(windowId, userB, "20", "bob");
        transfer(source, target1, "alice");
        JsonNode v2 = reconcile(windowId, key("rq"), 1, "ops-1");
        assertEquals(2, v2.get("version").asInt());
        // 转出 20 后应收 290、差额 -60
        JsonNode v2A = lineOf(v2, userA);
        assertEquals("130", v2A.get("approvedAmount").asText());
        assertEquals("290.00", v2A.get("amount").asText());
        assertEquals("-60.00", v2A.get("diffAmount").asText());
        JsonNode v2B = lineOf(v2, userB);
        assertEquals("20", v2B.get("approvedAmount").asText());
        assertEquals("40.00", v2B.get("amount").asText());
        assertEquals("40.00", v2B.get("diffAmount").asText());
        assertEquals("330.00", v2.get("totalAmount").asText());

        // 再转出 10：应收 260、此次差额 -30
        String target2 = submit(windowId, userB, "10", "bob");
        transfer(source, target2, "alice");
        JsonNode v3 = reconcile(windowId, key("rq"), 2, "ops-1");
        assertEquals(3, v3.get("version").asInt());
        JsonNode v3A = lineOf(v3, userA);
        assertEquals("120", v3A.get("approvedAmount").asText());
        assertEquals("260.00", v3A.get("amount").asText());
        assertEquals("-30.00", v3A.get("diffAmount").asText());
        JsonNode v3B = lineOf(v3, userB);
        assertEquals("30", v3B.get("approvedAmount").asText());
        assertEquals("60.00", v3B.get("amount").asText());
        assertEquals("20.00", v3B.get("diffAmount").asText());
        assertEquals("320.00", v3.get("totalAmount").asText());

        // 旧结算单不被改写；当前净应收为最新版本合计
        JsonNode list = settlements(windowId);
        assertEquals(3, list.get("currentVersion").asInt());
        assertEquals("320.00", list.get("currentNetReceivable").asText());
        assertEquals(3, list.get("settlements").size());
        JsonNode first = list.get("settlements").get(0);
        assertEquals(1, first.get("version").asInt());
        assertEquals("350.00", first.get("totalAmount").asText());
        assertEquals("150", lineOf(first, userA).get("approvedAmount").asText());
        assertEquals("350.00", lineOf(first, userA).get("amount").asText());
        JsonNode second = list.get("settlements").get(1);
        assertEquals("290.00", lineOf(second, userA).get("amount").asText());
        assertEquals("-60.00", lineOf(second, userA).get("diffAmount").asText());
    }
    // ------------------------------------------------------------------
    // 零差额与取消负差额
    // ------------------------------------------------------------------

    @Test
    void reconcileWithoutChangeSavesZeroDiffVersion() throws Exception {
        long windowId = createWindow("ch-zero-" + run, "1000");
        String userA = "fa-" + run + "-3";
        approve(submit(windowId, userA, "150", "alice"));
        settle(windowId, key("rq"), "ops-1");

        JsonNode v2 = reconcile(windowId, key("rq"), 1, "ops-1");
        assertEquals(2, v2.get("version").asInt());
        JsonNode line = lineOf(v2, userA);
        assertEquals("150", line.get("approvedAmount").asText());
        assertEquals("350.00", line.get("amount").asText());
        assertEquals("0.00", line.get("diffAmount").asText());
        assertEquals("350.00", v2.get("totalAmount").asText());
        assertEquals(2, settlements(windowId).get("settlements").size());
    }

    @Test
    void cancelProducesNegativeDiffInsteadOfDeletingLedger() throws Exception {
        long windowId = createWindow("ch-neg-" + run, "1000");
        String userA = "fa-" + run + "-4";
        String userB = "fb-" + run + "-4";
        String a1 = submit(windowId, userA, "150", "alice");
        approve(a1);
        approve(submit(windowId, userB, "20", "bob"));
        settle(windowId, key("rq"), "ops-1");

        // 取消已批准申请：产生负差额而非删除原账
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode v2 = reconcile(windowId, key("rq"), 1, "ops-1");
        JsonNode lineA = lineOf(v2, userA);
        assertEquals("0", lineA.get("approvedAmount").asText());
        assertEquals("0.00", lineA.get("amount").asText());
        assertEquals("-350.00", lineA.get("diffAmount").asText());
        assertEquals("40.00", lineOf(v2, userB).get("amount").asText());
        assertEquals("40.00", v2.get("totalAmount").asText());

        // 旧账保留：v1 仍为 350.00
        JsonNode list = settlements(windowId);
        assertEquals("350.00", lineOf(list.get("settlements").get(0), userA).get("amount").asText());
        assertEquals("40.00", list.get("currentNetReceivable").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void settleAndReconcileFailureBranches() throws Exception {
        long windowId = createWindow("ch-fail-" + run, "1000");
        approve(submit(windowId, "fa-" + run + "-5", "10", "alice"));

        // 未结算先对账 -> 409
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", key("rq"));
        body.put("expectedSettlementVersion", 1);
        postJson("/api/windows/" + windowId + "/settlement/reconcile", body, "ops-1", 409);
        // 缺少 expectedSettlementVersion -> 400
        postJson("/api/windows/" + windowId + "/settlement/reconcile",
                Map.of("requestId", key("rq")), "ops-1", 400);
        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/settlement", Map.of("requestId", key("rq")), "ops-1", 404);
        postJson("/api/windows/999999999/settlement/reconcile", body, "ops-1", 404);
        mvc.perform(get("/api/windows/999999999/settlement")).andExpect(status().isNotFound());
        // 缺少操作者 -> 400；非法 requestId -> 400
        postJson("/api/windows/" + windowId + "/settlement",
                Map.of("requestId", key("rq")), null, 400);
        postJson("/api/windows/" + windowId + "/settlement",
                Map.of("requestId", "bad key!"), "ops-1", 400);

        // 未结算时查询：空列表、当前版本 0、净应收为 null
        JsonNode empty = settlements(windowId);
        assertEquals(0, empty.get("currentVersion").asInt());
        assertTrue(empty.get("currentNetReceivable").isNull());
        assertEquals(0, empty.get("settlements").size());

        // 重复首次结算 -> 409
        settle(windowId, key("rq"), "ops-1");
        postJson("/api/windows/" + windowId + "/settlement",
                Map.of("requestId", key("rq")), "ops-1", 409);
        // 期望版本不一致 -> 409，且不产生新版本
        body = new HashMap<>();
        body.put("requestId", key("rq"));
        body.put("expectedSettlementVersion", 5);
        postJson("/api/windows/" + windowId + "/settlement/reconcile", body, "ops-1", 409);
        assertEquals(1, settlements(windowId).get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 幂等：requestId 结构化身份
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstResponseAndDifferentParamsReturn409() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "1000");
        String userA = "fa-" + run + "-6";
        approve(submit(windowId, userA, "150", "alice"));

        // 同键同参重放首次完整响应，只推进一个版本
        String settleRequest = key("rq");
        JsonNode first = settle(windowId, settleRequest, "ops-1");
        JsonNode replay = settle(windowId, settleRequest, "ops-1");
        assertEquals(first, replay);
        assertEquals(1, settlements(windowId).get("settlements").size());

        // 同键用于不同操作 -> 409
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", settleRequest);
        body.put("expectedSettlementVersion", 1);
        postJson("/api/windows/" + windowId + "/settlement/reconcile", body, "ops-1", 409);
        // 同键改操作者 -> 409
        postJson("/api/windows/" + windowId + "/settlement",
                Map.of("requestId", settleRequest), "ops-2", 409);
        // 同键改资源（另一窗口）-> 409
        long otherWindow = createWindow("ch-idem2-" + run, "1000");
        postJson("/api/windows/" + otherWindow + "/settlement",
                Map.of("requestId", settleRequest), "ops-1", 409);

        // 对账重放：同键同参返回首次响应；同键改期望版本 -> 409
        String reconcileRequest = key("rq");
        JsonNode v2 = reconcile(windowId, reconcileRequest, 1, "ops-1");
        JsonNode v2Replay = reconcile(windowId, reconcileRequest, 1, "ops-1");
        assertEquals(v2, v2Replay);
        assertEquals(2, settlements(windowId).get("settlements").size());
        body = new HashMap<>();
        body.put("requestId", reconcileRequest);
        body.put("expectedSettlementVersion", 2);
        postJson("/api/windows/" + windowId + "/settlement/reconcile", body, "ops-1", 409);
        assertEquals(2, settlements(windowId).get("settlements").size());
    }

    @Test
    void failedReconcileDoesNotOccupyRequestId() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "1000");
        approve(submit(windowId, "fa-" + run + "-7", "150", "alice"));
        settle(windowId, key("rq"), "ops-1");

        String requestId = key("rq");
        // 期望版本错误 -> 409，失败不占键
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedSettlementVersion", 99);
        postJson("/api/windows/" + windowId + "/settlement/reconcile", body, "ops-1", 409);
        // 同键修正参数后成功
        JsonNode v2 = reconcile(windowId, requestId, 1, "ops-1");
        assertEquals(2, v2.get("version").asInt());
        assertEquals(2, settlements(windowId).get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentReconcilesAdvanceVersionOnlyOnce() throws Exception {
        long windowId = createWindow("ch-conc-" + run, "1000");
        approve(submit(windowId, "fa-" + run + "-8", "150", "alice"));
        settle(windowId, key("rq"), "ops-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String request1 = key("rq");
        String request2 = key("rq");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return reconcileStatus(windowId, request1, 1);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return reconcileStatus(windowId, request2, 1);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同窗口并发对账只有一份版本推进：恰好一个 200，另一个 409
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "reconcile=" + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode list = settlements(windowId);
        assertEquals(2, list.get("settlements").size());
        assertEquals(2, list.get("currentVersion").asInt());
    }

    @Test
    void reconcileAndTransferSerializeOnWindowState() throws Exception {
        long windowId = createWindow("ch-race-" + run, "1000");
        String userA = "fa-" + run + "-9";
        String userB = "fb-" + run + "-9";
        String source = submit(windowId, userA, "150", "alice");
        approve(source);
        settle(windowId, key("rq"), "ops-1");
        String target = submit(windowId, userB, "20", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String transferCommand = key("tkc");
        String transferKey = key("tk");
        String reconcileRequest = key("rq");
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> reconcileFuture = pool.submit(() -> {
            gate.await();
            return reconcileStatus(windowId, reconcileRequest, 1);
        });
        gate.countDown();
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        int reconcileStatus = reconcileFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 转让与对账按窗口行锁串行：两者都成功，对账读到的是某一完整窗口状态
        assertEquals(200, transferStatus);
        assertEquals(200, reconcileStatus);
        JsonNode v2 = settlements(windowId).get("settlements").get(1);
        BigDecimal lineSum = BigDecimal.ZERO;
        for (JsonNode line : v2.get("lines")) {
            lineSum = lineSum.add(new BigDecimal(line.get("amount").asText()));
        }
        assertEquals(0, lineSum.compareTo(new BigDecimal(v2.get("totalAmount").asText())),
                "明细合计必须等于结算单总额");
        String approvedA = lineOf(v2, userA).get("approvedAmount").asText();
        assertTrue("150".equals(approvedA) || "130".equals(approvedA),
                "对账读到转让前 150 或转让后 130 的完整状态: " + approvedA);

        // 再次对账必然反映转让后的状态：A 应收 290、差额相对上一版本确定
        JsonNode v3 = reconcile(windowId, key("rq"), 2, "ops-1");
        JsonNode v3A = lineOf(v3, userA);
        assertEquals("130", v3A.get("approvedAmount").asText());
        assertEquals("290.00", v3A.get("amount").asText());
        assertEquals("40.00", lineOf(v3, userB).get("amount").asText());
        assertEquals("330.00", settlements(windowId).get("currentNetReceivable").asText());
    }

    private int reconcileStatus(long windowId, String requestId, int expectedVersion) {
        try {
            waterService.reconcile(requestId, windowId, expectedVersion, "ops-1");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }
}