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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠道蒸发损耗结算 API 测试：主流程、银行家舍入、失败分支（422/409/404）、
 * 批量整批回滚、反向更正、幂等重放与并发不超卖。全部使用真实 H2（MODE=MySQL）事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaporationApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
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

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitAndApprove(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private JsonNode registerRate(String channelId, String date, String rate) throws Exception {
        return postOk("/api/channels/" + channelId + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", date, "rate", rate), null);
    }

    private JsonNode settleOk(String allocationKey, String date, String delivered) throws Exception {
        return postOk("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", date, "delivered", delivered), null);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    /** 造一个 2026-10-01 全天窗口 + 一笔已批准申请，返回 [channelId, allocationKey, windowId]。 */
    private Object[] approvedFixture(String planned, String amount) throws Exception {
        String channel = "ch-" + key("c");
        long windowId = createWindow(channel, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", planned);
        String allocationKey = submitAndApprove(windowId, "user-1", amount, "alice");
        return new Object[]{channel, allocationKey, windowId};
    }

    // ------------------------------------------------------------------
    // 主流程与舍入口径
    // ------------------------------------------------------------------

    @Test
    void settleMainFlowWritesConservedAmountsInOneTransaction() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0.05");

        JsonNode settled = settleOk(allocationKey, "2026-10-01", "10");
        assertEquals("SETTLEMENT", settled.get("kind").asText());
        assertEquals(1, settled.get("rateVersion").asInt());
        assertEquals("0.05", settled.get("rate").asText());
        assertEquals("10", settled.get("delivered").asText());
        assertEquals("0.5", settled.get("loss").asText());
        // 守恒：上游扣减 = 送达 + 损耗
        assertEquals("10.5", settled.get("deduction").asText());
        assertEquals("89.5", settled.get("upstreamRemaining").asText());
        assertTrue(settled.get("correctsId").isNull());
        assertEquals(0, settled.get("batchNo").asInt());
        // 上游持有额度同事务扣减
        assertEquals("89.5", capacity(windowId).get("approvedTotal").asText());
        // 明细查询与写入结果一致
        JsonNode detail = getOk("/api/settlements/" + settled.get("id").asLong());
        assertEquals(settled, detail);
    }

    @Test
    void lossUsesBankersRoundingToTwoDecimals() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-01", "0.05");
        // 0.5 * 0.05 = 0.025 -> 银行家舍入 0.02（2 为偶数）
        JsonNode first = settleOk(allocationKey, "2026-10-01", "0.5");
        assertEquals("0.02", first.get("loss").asText());
        assertEquals("0.52", first.get("deduction").asText());
        // 1.5 * 0.05 = 0.075 -> 银行家舍入 0.08（8 为偶数）
        JsonNode second = settleOk(allocationKey, "2026-10-01", "1.5");
        assertEquals("0.08", second.get("loss").asText());
        assertEquals("1.58", second.get("deduction").asText());
        assertEquals("97.9", second.get("upstreamRemaining").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支
    // ------------------------------------------------------------------

    @Test
    void missingRateReturns422() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String allocationKey = (String) fixture[1];
        MvcResult result = postJson("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10"), null, 422);
        assertEquals("RATE_NOT_FOUND",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
    }

    @Test
    void dateOutsideWindowReturns422() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-02", "0.05");
        registerRate(channel, "2026-09-30", "0.05");
        // 窗口为 [2026-10-01, 2026-10-02)，左闭右开：10-02 与 09-30 均不在窗口内
        MvcResult after = postJson("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", "2026-10-02", "delivered", "1"), null, 422);
        assertEquals("DATE_OUT_OF_WINDOW",
                objectMapper.readTree(after.getResponse().getContentAsString()).get("code").asText());
        postJson("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", "2026-09-30", "delivered", "1"), null, 422);
    }

    @Test
    void insufficientUpstreamQuotaReturns422WithAmounts() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-01", "0");
        MvcResult result = postJson("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "100.01"), null, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        // 错误信息必须给出实际值、要求值与差额
        String message = error.get("message").asText();
        assertTrue(message.contains("100"), message);
        assertTrue(message.contains("100.01"), message);
        assertTrue(message.contains("0.01"), message);
    }

    @Test
    void conservationViolationReturns422() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-01", "0");
        // 送达 0.005（3 位小数）：扣减=0.005 超出两位小数口径，舍入后守恒不成立
        MvcResult result = postJson("/api/settlements", Map.of("commandKey", key("st"),
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "0.005"), null, 422);
        assertEquals("CONSERVATION_VIOLATED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        // 失败不产生任何核销记录
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(0, history.get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 蒸发率登记与锁定
    // ------------------------------------------------------------------

    @Test
    void rateCanBeRevisedBeforeFirstSettlementButNotAfter() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        JsonNode v1 = registerRate(channel, "2026-10-01", "0.05");
        assertEquals(1, v1.get("version").asInt());
        // 首次核销前可修订：版本递增，核销使用最新版本
        JsonNode v2 = registerRate(channel, "2026-10-01", "0.1");
        assertEquals(2, v2.get("version").asInt());
        JsonNode settled = settleOk(allocationKey, "2026-10-01", "10");
        assertEquals(2, settled.get("rateVersion").asInt());
        assertEquals("0.1", settled.get("rate").asText());
        assertEquals("1", settled.get("loss").asText());
        // 首次核销后修改 -> 409
        MvcResult result = postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "0.03"), null, 409);
        assertEquals("RATE_LOCKED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        // 已结算记录不被改写
        JsonNode detail = getOk("/api/settlements/" + settled.get("id").asLong());
        assertEquals("0.1", detail.get("rate").asText());
        assertEquals(2, detail.get("rateVersion").asInt());
    }

    @Test
    void invalidRateOrDateReturns400() throws Exception {
        String channel = "ch-" + key("c");
        // 超过 20%
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "0.2001"), null, 400);
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "1.5"), null, 400);
        // 超过四位小数
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "0.12345"), null, 400);
        // 负数与非法值
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "-0.1"), null, 400);
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-10-01", "rate", "abc"), null, 400);
        // 非法日期
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "2026-13-01", "rate", "0.1"), null, 400);
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", key("rr"), "date", "10/01/2026", "rate", "0.1"), null, 400);
        // 边界值 0 与 0.2 合法
        assertEquals("0", registerRate(channel, "2026-10-01", "0.0000").get("rate").asText());
        assertEquals("0.2", registerRate(channel, "2026-10-02", "0.2").get("rate").asText());
    }

    // ------------------------------------------------------------------
    // 批量核销
    // ------------------------------------------------------------------

    @Test
    void batchSettleAppliesPerItemRoundingAndCumulativeBalance() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-01", "0.05");

        Map<String, Object> body = Map.of("commandKey", key("sb"), "items", List.of(
                Map.of("allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10"),
                Map.of("allocationKey", allocationKey, "date", "2026-10-01", "delivered", "20")));
        JsonNode batch = postOk("/api/settlements/batch", body, null);
        assertEquals(2, batch.get("settlements").size());
        JsonNode first = batch.get("settlements").get(0);
        JsonNode second = batch.get("settlements").get(1);
        assertEquals(0, first.get("batchNo").asInt());
        assertEquals(1, second.get("batchNo").asInt());
        assertEquals("10.5", first.get("deduction").asText());
        assertEquals("89.5", first.get("upstreamRemaining").asText());
        assertEquals("21", second.get("deduction").asText());
        assertEquals("68.5", second.get("upstreamRemaining").asText());
        // 诊断汇总：净送达 30、净损耗 1.5、净扣减 31.5
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-diagnostics?date=2026-10-01");
        assertEquals("30", diag.get("netDelivered").asText());
        assertEquals("1.5", diag.get("netLoss").asText());
        assertEquals("31.5", diag.get("netDeduction").asText());
        assertEquals(2, diag.get("settlementCount").asLong());
        assertTrue(diag.get("locked").asBoolean());
    }

    @Test
    void batchSettleRollsBackEntirelyOnAnyFailure() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0.05");

        // 第二笔累计扣减 10.5 + 99.75 = 110.25 > 100 -> 整批 422
        Map<String, Object> quotaBatch = Map.of("commandKey", key("sb"), "items", List.of(
                Map.of("allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10"),
                Map.of("allocationKey", allocationKey, "date", "2026-10-01", "delivered", "95")));
        postJson("/api/settlements/batch", quotaBatch, null, 422);
        // 第二笔日期越界 -> 整批 422
        Map<String, Object> dateBatch = Map.of("commandKey", key("sb"), "items", List.of(
                Map.of("allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10"),
                Map.of("allocationKey", allocationKey, "date", "2026-10-05", "delivered", "1")));
        postJson("/api/settlements/batch", dateBatch, null, 422);
        // 查不到任何半成品：无核销记录、额度未动
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(0, history.get("settlements").size());
        assertEquals("100", capacity(windowId).get("approvedTotal").asText());
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-diagnostics?date=2026-10-01");
        assertEquals(0, diag.get("settlementCount").asLong());
    }

    // ------------------------------------------------------------------
    // 反向更正
    // ------------------------------------------------------------------

    @Test
    void correctionReversesSettlementAndRestoresQuota() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0.05");
        JsonNode settled = settleOk(allocationKey, "2026-10-01", "10");
        long settlementId = settled.get("id").asLong();
        assertEquals("89.5", capacity(windowId).get("approvedTotal").asText());

        JsonNode correction = postOk("/api/settlements/" + settlementId + "/correct",
                Map.of("commandKey", key("co")), null);
        assertEquals("CORRECTION", correction.get("kind").asText());
        assertEquals(settlementId, correction.get("correctsId").asLong());
        assertEquals("-10", correction.get("delivered").asText());
        assertEquals("-0.5", correction.get("loss").asText());
        assertEquals("-10.5", correction.get("deduction").asText());
        assertEquals("100", correction.get("upstreamRemaining").asText());
        // 上游额度回补
        assertEquals("100", capacity(windowId).get("approvedTotal").asText());
        // 原记录不被改写
        JsonNode original = getOk("/api/settlements/" + settlementId);
        assertEquals(settled, original);
        // 历史包含两条记录，按发生顺序
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(2, history.get("settlements").size());
        assertEquals("SETTLEMENT", history.get("settlements").get(0).get("kind").asText());
        assertEquals("CORRECTION", history.get("settlements").get(1).get("kind").asText());
        // 诊断净额归零
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-diagnostics?date=2026-10-01");
        assertEquals("0", diag.get("netDelivered").asText());
        assertEquals("0", diag.get("netDeduction").asText());
        assertEquals(1, diag.get("settlementCount").asLong());
        // 重复更正 -> 409；更正记录不能再被更正 -> 409；不存在 -> 404
        postJson("/api/settlements/" + settlementId + "/correct",
                Map.of("commandKey", key("co")), null, 409);
        postJson("/api/settlements/" + correction.get("id").asLong() + "/correct",
                Map.of("commandKey", key("co")), null, 409);
        postJson("/api/settlements/999999999/correct", Map.of("commandKey", key("co")), null, 404);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void settleIdempotencyReplayAndKeyReuse() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        registerRate(channel, "2026-10-01", "0.05");

        String commandKey = key("st");
        Map<String, Object> body = Map.of("commandKey", commandKey,
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10");
        JsonNode first = postOk("/api/settlements", body, null);
        JsonNode replay = postOk("/api/settlements", body, null);
        assertEquals(first, replay);
        // 只写入一条记录
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(1, history.get("settlements").size());
        // 同键异参 -> 409
        postJson("/api/settlements", Map.of("commandKey", commandKey,
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "20"), null, 409);
    }

    @Test
    void failedCommandDoesNotOccupyKey() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        String commandKey = key("st");
        Map<String, Object> body = Map.of("commandKey", commandKey,
                "allocationKey", allocationKey, "date", "2026-10-01", "delivered", "10");
        // 蒸发率未登记 -> 422，失败不占键
        postJson("/api/settlements", body, null, 422);
        registerRate(channel, "2026-10-01", "0.05");
        JsonNode settled = postOk("/api/settlements", body, null);
        assertEquals("10.5", settled.get("deduction").asText());
    }

    @Test
    void rateRegistrationIdempotency() throws Exception {
        String channel = "ch-" + key("c");
        String commandKey = key("rr");
        Map<String, Object> body = Map.of("commandKey", commandKey, "date", "2026-10-01", "rate", "0.05");
        JsonNode first = postOk("/api/channels/" + channel + "/evaporation-rates", body, null);
        JsonNode replay = postOk("/api/channels/" + channel + "/evaporation-rates", body, null);
        assertEquals(first, replay);
        // 同键改率 -> 409
        postJson("/api/channels/" + channel + "/evaporation-rates",
                Map.of("commandKey", commandKey, "date", "2026-10-01", "rate", "0.1"), null, 409);
        // 只登记了一个版本
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-diagnostics?date=2026-10-01");
        assertEquals(1, diag.get("versions").size());
    }

    // ------------------------------------------------------------------
    // 历史稳定与诊断
    // ------------------------------------------------------------------

    @Test
    void settledRecordsAreImmutableAcrossTransfer() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0.05");
        JsonNode settled = settleOk(allocationKey, "2026-10-01", "10");

        // 转让 50 给另一用水户：源持有 89.5 - 50 = 39.5
        String targetKey = key("ak");
        Map<String, Object> target = new HashMap<>();
        target.put("commandKey", key("ac"));
        target.put("allocationKey", targetKey);
        target.put("windowId", windowId);
        target.put("userId", "user-2");
        target.put("amount", "50");
        postOk("/api/allocations", target, "bob");
        postOk("/api/transfers", Map.of("commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", allocationKey, "targetAllocationKey", targetKey), "alice");
        // 转让后源持有 39.5，目标持有 50，已批准总量不变
        JsonNode historyAfterTransfer = getOk("/api/windows/" + windowId + "/history");
        assertEquals("39.5", historyAfterTransfer.get("allocations").get(0).get("heldAmount").asText());
        assertEquals("89.5", capacity(windowId).get("approvedTotal").asText());
        // 已结算记录不被转让改写
        JsonNode detail = getOk("/api/settlements/" + settled.get("id").asLong());
        assertEquals(settled, detail);
    }

    @Test
    void diagnosticsAndNotFoundSemantics() throws Exception {
        String channel = "ch-" + key("c");
        // 未登记：currentVersion/currentRate 为 null，未锁定，净额为 0
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-diagnostics?date=2026-10-01");
        assertTrue(diag.get("currentVersion").isNull());
        assertTrue(diag.get("currentRate").isNull());
        assertEquals(0, diag.get("versions").size());
        assertTrue(!diag.get("locked").asBoolean());
        assertEquals("0", diag.get("netDelivered").asText());
        assertEquals(0, diag.get("settlementCount").asLong());
        // 不存在的核销明细与申请历史 -> 404
        mvc.perform(get("/api/settlements/999999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/settlements"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsNeverOversellUpstreamQuota() throws Exception {
        Object[] fixture = approvedFixture("1000", "10");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("st");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.settle(commandKey, allocationKey, "2026-10-01", "6");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int quota = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                quota++;
            }
        }
        pool.shutdown();
        // 持有 10，两笔各扣 6：恰好一笔成功，不超卖
        assertEquals(1, ok);
        assertEquals(1, quota);
        assertEquals("4", capacity(windowId).get("approvedTotal").asText());
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(1, history.get("settlements").size());
        assertEquals("4", history.get("settlements").get(0).get("upstreamRemaining").asText());
    }

    @Test
    void concurrentCorrectionsSettleExactlyOnce() throws Exception {
        Object[] fixture = approvedFixture("1000", "100");
        String channel = (String) fixture[0];
        String allocationKey = (String) fixture[1];
        long windowId = (Long) fixture[2];
        registerRate(channel, "2026-10-01", "0.05");
        JsonNode settled = settleOk(allocationKey, "2026-10-01", "10");
        long settlementId = settled.get("id").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("co");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.correctSettlement(commandKey, settlementId);
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        // 恰好一次更正成功，另一次 409；额度只回补一次
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals("100", capacity(windowId).get("approvedTotal").asText());
        JsonNode history = getOk("/api/allocations/" + allocationKey + "/settlements");
        assertEquals(2, history.get("settlements").size());
        assertNotNull(history.get("settlements").get(1).get("correctsId"));
    }
}
