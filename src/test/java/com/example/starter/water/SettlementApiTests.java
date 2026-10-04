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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 窗口阶梯结算与对账 API 测试：主流程、合成差额场景、失败分支、幂等与并发边界。
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

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-s-" + run + "-" + seq);
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

    private void cancel(String allocationKey, String actor) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/cancel",
                Map.of("commandKey", key("cc")), actor, 200);
    }

    private void transfer(String sourceKey, String targetKey, String actor) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        postOk("/api/transfers", body, actor);
    }

    private JsonNode settle(long windowId, int expectedStatus) throws Exception {
        return settle(windowId, key("st"), expectedStatus);
    }

    private JsonNode settle(long windowId, String requestId, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/windows/" + windowId + "/settlements",
                Map.of("requestId", requestId), null, expectedStatus);
        String json = result.getResponse().getContentAsString();
        return json.isEmpty() ? null : objectMapper.readTree(json);
    }

    private JsonNode reconcile(long windowId, long expectedVersion, int expectedStatus) throws Exception {
        return reconcile(windowId, key("rc"), expectedVersion, expectedStatus);
    }

    private JsonNode reconcile(long windowId, String requestId, long expectedVersion, int expectedStatus)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedSettlementVersion", expectedVersion);
        MvcResult result = postJson("/api/windows/" + windowId + "/reconciliations",
                body, null, expectedStatus);
        String json = result.getResponse().getContentAsString();
        return json.isEmpty() ? null : objectMapper.readTree(json);
    }
    // ------------------------------------------------------------------
    // 主流程与阶梯计费
    // ------------------------------------------------------------------

    @Test
    void settlementBillsPerFarmerWithTieredRatesAndFreezesVersion() throws Exception {
        long windowId = createWindow("1000");
        String a1 = submit(windowId, "user-a", "120", "alice");
        String a2 = submit(windowId, "user-a", "30", "alice");
        String b1 = submit(windowId, "user-b", "80", "bob");
        approve(a1);
        approve(a2);
        approve(b1);
        // 未批准申请不参与结算
        submit(windowId, "user-c", "10", "carol");

        JsonNode v1 = settle(windowId, 200);
        assertEquals(1, v1.get("version").asInt());
        assertEquals(windowId, v1.get("windowId").asLong());
        // user-a 合计 150：100*2 + 50*3 = 350；user-b 80*2 = 160；合计 510
        assertEquals("510.00", v1.get("totalAmount").asText());
        // 版本 1 差额等于应收总额
        assertEquals("510.00", v1.get("diffAmount").asText());
        // 费率冻结
        assertEquals("100", v1.get("firstTierLimit").asText());
        assertEquals("2.00", v1.get("firstTierRate").asText());
        assertEquals("3.00", v1.get("secondTierRate").asText());
        assertNotNull(v1.get("createdUtc"));
        // 农户分组与精确水量冻结，不含未批准的 user-c
        JsonNode lines = v1.get("lines");
        assertEquals(2, lines.size());
        assertEquals("user-a", lines.get(0).get("userId").asText());
        assertEquals("150", lines.get(0).get("volume").asText());
        assertEquals("350.00", lines.get(0).get("amount").asText());
        assertEquals("user-b", lines.get(1).get("userId").asText());
        assertEquals("80", lines.get(1).get("volume").asText());
        assertEquals("160.00", lines.get(1).get("amount").asText());

        // 查询：当前净应收与版本列表
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("currentVersion").asInt());
        assertEquals("510.00", list.get("currentNetReceivable").asText());
        assertEquals(1, list.get("settlements").size());
        JsonNode current = getOk("/api/windows/" + windowId + "/settlements/current");
        assertEquals("510.00", current.get("totalAmount").asText());
    }

    @Test
    void amountRoundsHalfUpToCents() throws Exception {
        long windowId = createWindow("1000");
        // 100.005：第一阶梯 100*2=200，超出 0.005*3=0.015，HALF_UP 到 0.02
        String a1 = submit(windowId, "user-a", "100.005", "alice");
        // 0.001：0.002 -> HALF_UP 到 0.00
        String b1 = submit(windowId, "user-b", "0.001", "bob");
        approve(a1);
        approve(b1);
        JsonNode v1 = settle(windowId, 200);
        JsonNode lines = v1.get("lines");
        assertEquals("200.02", lines.get(0).get("amount").asText());
        assertEquals("0.00", lines.get(1).get("amount").asText());
        assertEquals("200.02", v1.get("totalAmount").asText());
    }

    @Test
    void syntheticTransferScenarioProducesExpectedDiffs() throws Exception {
        long windowId = createWindow("1000");
        String source = submit(windowId, "user-a", "150", "alice");
        approve(source);

        // 合成 150 立方米应收 350 元
        JsonNode v1 = settle(windowId, 200);
        assertEquals("350.00", v1.get("totalAmount").asText());
        assertEquals("350.00", v1.get("diffAmount").asText());

        // 结算后仍允许原有额度转让：转出 20（接收方随后取消，水离开窗口）
        String target1 = submit(windowId, "user-b", "20", "bob");
        transfer(source, target1, "alice");
        cancel(target1, "bob");

        // 对账：应收 100*2 + 30*3 = 290，差额 -60（相对最近有效版本而非首单）
        JsonNode v2 = reconcile(windowId, 1, 200);
        assertEquals(2, v2.get("version").asInt());
        assertEquals("290.00", v2.get("totalAmount").asText());
        assertEquals("-60.00", v2.get("diffAmount").asText());

        // 再转出 10：应收 100*2 + 20*3 = 260，此次差额 -30（而非与首单比较的 -90）
        String target2 = submit(windowId, "user-c", "10", "carol");
        transfer(source, target2, "alice");
        cancel(target2, "carol");
        JsonNode v3 = reconcile(windowId, 2, 200);
        assertEquals(3, v3.get("version").asInt());
        assertEquals("260.00", v3.get("totalAmount").asText());
        assertEquals("-30.00", v3.get("diffAmount").asText());

        // 旧单不变，当前净应收 260，差额账本合计等于当前净应收
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(3, list.get("currentVersion").asInt());
        assertEquals("260.00", list.get("currentNetReceivable").asText());
        JsonNode settlements = list.get("settlements");
        assertEquals(3, settlements.size());
        assertEquals("350.00", settlements.get(0).get("totalAmount").asText());
        assertEquals("150", settlements.get(0).get("lines").get(0).get("volume").asText());
        assertEquals("290.00", settlements.get(1).get("totalAmount").asText());
        assertEquals("-60.00", settlements.get(1).get("diffAmount").asText());
        assertEquals("260.00", settlements.get(2).get("totalAmount").asText());
        assertEquals("-30.00", settlements.get(2).get("diffAmount").asText());
        BigDecimal diffSum = BigDecimal.ZERO;
        for (JsonNode node : settlements) {
            diffSum = diffSum.add(new BigDecimal(node.get("diffAmount").asText()));
        }
        assertEquals(0, diffSum.compareTo(new BigDecimal("260.00")));
    }

    @Test
    void noChangeReconcileStillSavesZeroDiffVersion() throws Exception {
        long windowId = createWindow("100");
        String a1 = submit(windowId, "user-a", "50", "alice");
        approve(a1);
        settle(windowId, 200);
        JsonNode v2 = reconcile(windowId, 1, 200);
        assertEquals(2, v2.get("version").asInt());
        assertEquals("100.00", v2.get("totalAmount").asText());
        assertEquals("0.00", v2.get("diffAmount").asText());
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(2, list.get("settlements").size());
        assertEquals(2, list.get("currentVersion").asInt());
    }

    @Test
    void cancelAfterSettlementProducesNegativeDiffWithoutDeletingOldBills() throws Exception {
        long windowId = createWindow("100");
        String a1 = submit(windowId, "user-a", "50", "alice");
        approve(a1);
        settle(windowId, 200);
        cancel(a1, "alice");
        JsonNode v2 = reconcile(windowId, 1, 200);
        assertEquals("0.00", v2.get("totalAmount").asText());
        assertEquals("-100.00", v2.get("diffAmount").asText());
        // 旧账保留：版本 1 仍为 100.00
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(2, list.get("settlements").size());
        assertEquals("100.00", list.get("settlements").get(0).get("totalAmount").asText());
        assertEquals("0.00", list.get("currentNetReceivable").asText());
    }
    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void settlementLifecycleErrors() throws Exception {
        long windowId = createWindow("100");
        // 未结算时对账 -> 404；查询当前结算 -> 404
        reconcile(windowId, 1, 404);
        mvc.perform(get("/api/windows/" + windowId + "/settlements/current"))
                .andExpect(status().isNotFound());
        // 无批准申请也可结算（应收 0）
        JsonNode v1 = settle(windowId, 200);
        assertEquals("0.00", v1.get("totalAmount").asText());
        // 重复结算 -> 409，应走对账
        settle(windowId, 409);
        // 期望版本不符 -> 409；缺期望版本 -> 400；版本 0 -> 400
        reconcile(windowId, 3, 409);
        postJson("/api/windows/" + windowId + "/reconciliations",
                Map.of("requestId", key("rc")), null, 400);
        reconcile(windowId, 0, 400);
        // 非法 requestId -> 400
        postJson("/api/windows/" + windowId + "/settlements",
                Map.of("requestId", "bad key!"), null, 400);
        // 窗口不存在 -> 404
        settle(999999999L, 404);
        reconcile(999999999L, 1, 404);
        mvc.perform(get("/api/windows/999999999/settlements")).andExpect(status().isNotFound());
        // 失败后仍可正常对账推进
        JsonNode v2 = reconcile(windowId, 1, 200);
        assertEquals(2, v2.get("version").asInt());
    }

    @Test
    void invalidFarmerTotalIsRejected422() {
        // 阶梯计费主流程数值
        assertEquals(new BigDecimal("350.00"), WaterService.billFarmer(new BigDecimal("150")));
        assertEquals(new BigDecimal("200.00"), WaterService.billFarmer(new BigDecimal("100")));
        assertEquals(new BigDecimal("0.00"), WaterService.billFarmer(BigDecimal.ZERO));
        // 农户总量非法 -> 422（SETTLEMENT_INVALID_TOTAL），调用方事务回滚整张对账
        ApiException negative = assertThrows(ApiException.class,
                () -> WaterService.billFarmer(new BigDecimal("-0.001")));
        assertEquals(422, negative.status().value());
        assertEquals("SETTLEMENT_INVALID_TOTAL", negative.code());
        ApiException tooPrecise = assertThrows(ApiException.class,
                () -> WaterService.billFarmer(new BigDecimal("1.0001")));
        assertEquals(422, tooPrecise.status().value());
        ApiException nullVolume = assertThrows(ApiException.class,
                () -> WaterService.billFarmer(null));
        assertEquals(422, nullVolume.status().value());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstResponseAndDifferentParamsConflict() throws Exception {
        long windowId = createWindow("100");
        String a1 = submit(windowId, "user-a", "10", "alice");
        approve(a1);
        String requestId = key("st");
        JsonNode first = settle(windowId, requestId, 200);
        JsonNode replay = settle(windowId, requestId, 200);
        assertEquals(first, replay);
        // 只创建了一个版本
        assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());

        // 对账同键同参重放首次完整响应
        String reconcileId = key("rc");
        JsonNode r1 = reconcile(windowId, reconcileId, 1, 200);
        JsonNode r1Replay = reconcile(windowId, reconcileId, 1, 200);
        assertEquals(r1, r1Replay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        // 同键改参（期望版本不同）-> 409
        reconcile(windowId, reconcileId, 2, 409);
        // 结算键与对账键互不复用：结算键改用于对账 -> 409
        reconcile(windowId, requestId, 2, 409);
    }

    @Test
    void failedReconcileDoesNotOccupyRequestId() throws Exception {
        long windowId = createWindow("100");
        String a1 = submit(windowId, "user-a", "10", "alice");
        approve(a1);
        settle(windowId, 200);
        String requestId = key("rc");
        // 期望版本错误 -> 409，失败不占键
        reconcile(windowId, requestId, 5, 409);
        // 同键修正参数后成功
        JsonNode ok = reconcile(windowId, requestId, 1, 200);
        assertEquals(2, ok.get("version").asInt());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentReconcilesAdvanceVersionOnlyOnce() throws Exception {
        long windowId = createWindow("100");
        String a1 = submit(windowId, "user-a", "10", "alice");
        approve(a1);
        settle(windowId, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String requestId1 = key("rc");
        String requestId2 = key("rc");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return reconcileStatus(requestId1, windowId, 1L);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return reconcileStatus(requestId2, windowId, 1L);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同窗口并发对账只有一份版本推进：一个 200，另一个版本冲突 409
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "reconcile: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(2, list.get("settlements").size());
        assertEquals(2, list.get("currentVersion").asInt());
    }

    @Test
    void reconcileAndTransferSerializeOnWindowLock() throws Exception {
        long windowId = createWindow("1000");
        String source = submit(windowId, "user-a", "150", "alice");
        approve(source);
        settle(windowId, 200);
        String target = submit(windowId, "user-b", "20", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String reconcileId = key("rc");
        String transferCommand = key("tkc");
        String transferKey = key("tk");
        Future<Integer> reconcileFuture = pool.submit(() -> {
            gate.await();
            return reconcileStatus(reconcileId, windowId, 1L);
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int reconcileStatus = reconcileFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两者读取同一窗口锁定的完整状态，均可成功；版本只推进一次
        assertEquals(200, reconcileStatus);
        assertEquals(200, transferStatus);
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(2, list.get("settlements").size());
        // 对账结果必为转让前 350 或转让后 330（user-a 290 + user-b 40）之一，不出现中间态
        String total = list.get("currentNetReceivable").asText();
        assertTrue("350.00".equals(total) || "330.00".equals(total), "total=" + total);
        // 再次对账（期望版本 2）反映最终状态：转让已提交则应收 330
        JsonNode v3 = reconcile(windowId, 2, 200);
        assertEquals("330.00", v3.get("totalAmount").asText());
    }

    private int reconcileStatus(String requestId, long windowId, long expectedVersion) {
        try {
            waterService.reconcile(requestId, windowId, expectedVersion);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }
}