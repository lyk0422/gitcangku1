package com.example.starter.water;

import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 蒸发结算 API 集成测试：蒸发率版本化与冻结、单笔/批量核销（银行家舍入、守恒、完整最终余额
 * 预校验）、422 失败分支与整批回滚、反向更正、幂等重放、同一上游额度并发不超卖、可控时钟。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、唯一约束与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaporationSettlementApiTests {

    private static final String WINDOW_START = "2026-11-10T00:00:00Z";
    private static final String WINDOW_END = "2026-11-12T00:00:00Z";

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

    @AfterEach
    void resetClock() {
        waterService.setClock(Clock.systemUTC());
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        return postJson(url, body, null, expectedStatus);
    }

    private MvcResult postJson(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postJson(url, body, 200).getResponse().getContentAsString());
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode postExpect(String url, Map<String, Object> body, int status, String code) throws Exception {
        JsonNode node = objectMapper.readTree(
                postJson(url, body, status).getResponse().getContentAsString());
        assertEquals(code, node.get("code").asText());
        return node;
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String channel() {
        return "ch-ev-" + run;
    }

    private long createWindow(String planned) throws Exception {
        return createWindow(channel(), planned);
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", WINDOW_START);
        body.put("endUtc", WINDOW_END);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body).get("id").asLong();
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor) throws Exception {
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

    private String approvedAllocation(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = submitAllocation(windowId, userId, amount, actor);
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")));
        return allocationKey;
    }

    private JsonNode registerRate(String channelId, String date, String rate) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("rc"));
        body.put("channelId", channelId);
        body.put("rateDate", date);
        body.put("rate", rate);
        return postOk("/api/evaporation-rates", body);
    }

    private JsonNode registerRate(String date, String rate) throws Exception {
        return registerRate(channel(), date, rate);
    }

    private Map<String, Object> rateBody(String channelId, String date, String rate) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("rc"));
        body.put("channelId", channelId);
        body.put("rateDate", date);
        body.put("rate", rate);
        return body;
    }

    private Map<String, Object> settleBody(String commandKey, String batchKey, String allocationKey,
                                           String date, String delivered) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("batchKey", batchKey);
        body.put("allocationKey", allocationKey);
        body.put("deliveryDate", date);
        body.put("deliveredVolume", delivered);
        return body;
    }

    private Map<String, Object> item(String settlementKey, String allocationKey, String date,
                                     String delivered) {
        Map<String, Object> body = new HashMap<>();
        body.put("settlementKey", settlementKey);
        body.put("allocationKey", allocationKey);
        body.put("deliveryDate", date);
        body.put("deliveredVolume", delivered);
        return body;
    }

    private JsonNode allocationView(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private Map<String, Object> transferBody(String transferKey, String source, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tc"));
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        return body;
    }

    // ------------------------------------------------------------------
    // 主流程：率版本、冻结、核销守恒
    // ------------------------------------------------------------------

    @Test
    void settleUsesBankerRoundingFreezesDayRateAndUpdatesTotalsInOneTx() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approvedAllocation(windowId, "user-1", "10", "alice");
        JsonNode rate = registerRate("2026-11-10", "0.1000");
        assertEquals(1, rate.get("version").asInt());
        assertTrue(rate.get("current").asBoolean());

        // 0.250 × 0.1 = 0.025，银行家舍入到偶数百分位 -> 0.02；上游扣减 = 0.25 + 0.02 = 0.27
        String commandKey = key("sc");
        String batchKey = key("bk");
        Map<String, Object> body = settleBody(commandKey, batchKey, allocationKey, "2026-11-10", "0.250");
        JsonNode voucher = postOk("/api/settlements", body);
        assertEquals(batchKey + ":1", voucher.get("settlementKey").asText());
        assertEquals(batchKey, voucher.get("batchKey").asText());
        assertEquals(windowId, voucher.get("windowId").asLong());
        assertEquals(channel(), voucher.get("channelId").asText());
        assertEquals(allocationKey, voucher.get("allocationKey").asText());
        assertEquals("2026-11-10", voucher.get("deliveryDate").asText());
        assertEquals(1, voucher.get("rateVersion").asInt());
        assertEquals("0.1", voucher.get("rate").asText());
        assertEquals("0.25", voucher.get("deliveredVolume").asText());
        assertEquals("0.02", voucher.get("loss").asText());
        assertEquals("0.27", voucher.get("upstreamDeduction").asText());
        assertEquals("SETTLED", voucher.get("status").asText());
        assertNotNull(voucher.get("createdUtc"));

        // 同参重放返回首次完整结果，不产生第二笔
        JsonNode replay = postOk("/api/settlements", body);
        assertEquals(voucher, replay);

        // 申请：持有 9.73、累计送达 0.25、累计损耗 0.02；上游扣减=送达+损耗
        JsonNode allocation = allocationView(windowId, allocationKey);
        assertEquals("9.73", allocation.get("heldAmount").asText());
        assertEquals("0.25", allocation.get("settledDelivered").asText());
        assertEquals("0.02", allocation.get("settledLoss").asText());
        assertEquals("9.73", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 单笔凭证查询与窗口明细
        assertEquals("0.27", getOk("/api/settlements/" + batchKey + ":1").get("upstreamDeduction").asText());
        JsonNode settlements = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, settlements.get("settlements").size());
        assertEquals(0, settlements.get("corrections").size());

        // 当天首次核销后修改率 -> 409，且不产生新版本
        postExpect("/api/evaporation-rates", rateBody(channel(), "2026-11-10", "0.15"),
                409, "RATE_FROZEN");
        JsonNode rateHistory = getOk("/api/channels/" + channel() + "/evaporation-rates");
        long dayVersions = jsonNodes(rateHistory.get("rates")).stream()
                .filter(n -> "2026-11-10".equals(n.get("rateDate").asText())).count();
        assertEquals(1, dayVersions, "冻结后不得追加新版本");

        // 同键改参重放核销 -> 409
        postExpect("/api/settlements",
                settleBody(commandKey, batchKey, allocationKey, "2026-11-10", "0.251"),
                409, "COMMAND_KEY_REUSED");
    }

    @Test
    void rateCanBeRevisedBeforeFirstSettlementAndVoucherFreezesTheNewVersion() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approvedAllocation(windowId, "user-1", "10", "alice");
        // 11-11：先登记 0.2（v1），未核销前改为 0.05（v2）
        JsonNode v1 = registerRate("2026-11-11", "0.2000");
        assertEquals(1, v1.get("version").asInt());
        JsonNode v2 = registerRate("2026-11-11", "0.0500");
        assertEquals(2, v2.get("version").asInt());
        assertTrue(v2.get("current").asBoolean());

        JsonNode rates = getOk("/api/channels/" + channel() + "/evaporation-rates").get("rates");
        assertEquals(1, rates.get(0).get("version").asInt());
        assertEquals(false, rates.get(0).get("current").asBoolean());
        assertEquals(2, rates.get(1).get("version").asInt());
        assertEquals(true, rates.get(1).get("current").asBoolean());

        // 核销固化 v2：1.000 × 0.05 = 0.05，扣 1.05
        JsonNode voucher = postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-11", "1.000"));
        assertEquals(2, voucher.get("rateVersion").asInt());
        assertEquals("0.05", voucher.get("loss").asText());
        assertEquals("1.05", voucher.get("upstreamDeduction").asText());

        // 跨日版本独立：11-10 另登记 0.2（v1），凭证固化 11-10 的 v1
        registerRate("2026-11-10", "0.2000");
        JsonNode otherDay = postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "1.000"));
        assertEquals(1, otherDay.get("rateVersion").asInt());
        assertEquals("0.2", otherDay.get("loss").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支：缺率、窗口外、余额不足
    // ------------------------------------------------------------------

    @Test
    void missingRateReturns422AndWritesNothing() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approvedAllocation(windowId, "user-1", "10", "alice");
        String commandKey = key("sc");
        String batchKey = key("bk");
        // 11-11 未登记率 -> 422
        postExpect("/api/settlements",
                settleBody(commandKey, batchKey, allocationKey, "2026-11-11", "1"),
                422, "EVAPORATION_RATE_MISSING");
        JsonNode allocation = allocationView(windowId, allocationKey);
        assertEquals("10", allocation.get("heldAmount").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        // 失败不占键：补登率后同 commandKey 同参可成功
        registerRate("2026-11-11", "0.1");
        JsonNode ok = postOk("/api/settlements",
                settleBody(commandKey, batchKey, allocationKey, "2026-11-11", "1"));
        assertEquals("SETTLED", ok.get("status").asText());
    }

    @Test
    void deliveryOutsideWindowReturns422AtBothBoundaries() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approvedAllocation(windowId, "user-1", "10", "alice");
        registerRate("2026-11-09", "0.1");
        registerRate("2026-11-12", "0.1");
        // 窗口 [11-10 00:00, 11-12 00:00)：11-09 在前、11-12 当日在后（左闭右开）
        postExpect("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-09", "1"),
                422, "DELIVERY_OUTSIDE_WINDOW");
        postExpect("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-12", "1"),
                422, "DELIVERY_OUTSIDE_WINDOW");
        // 边界日 11-10 与 11-11 合法（11-11 的整日在窗口内）
        registerRate("2026-11-10", "0.1");
        registerRate("2026-11-11", "0.1");
        postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "0.1"));
        postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-11", "0.1"));
    }

    @Test
    void insufficientUpstreamQuotaReturns422WithShortfallAndNoPartialWrite() throws Exception {
        long windowId = createWindow("1");
        String allocationKey = approvedAllocation(windowId, "user-1", "0.300", "alice");
        registerRate("2026-11-10", "0.1000");
        // 0.250 扣 0.27 -> 余 0.03；0.010 损耗 0.001 舍入 0.00 扣 0.01 -> 余 0.02；0.020 扣 0.02 -> 余 0
        postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "0.250"));
        postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "0.010"));
        postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "0.020"));
        assertEquals("0", allocationView(windowId, allocationKey).get("heldAmount").asText());
        // 余额为零再核销 -> 422，差额等于要求扣减
        JsonNode error = postExpect("/api/settlements",
                settleBody(key("sc"), key("bk"), allocationKey, "2026-11-10", "0.010"),
                422, "UPSTREAM_QUOTA_EXCEEDED");
        assertTrue(error.get("message").asText().contains("差额 0.01"));
        // 无新增凭证、持有额度未变负
        JsonNode settlements = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(3, settlements.get("settlements").size());
        assertEquals("0", allocationView(windowId, allocationKey).get("heldAmount").asText());

        // 诊断给出实际值与要求扣减、差额（只读不落库）
        JsonNode diag = getOk("/api/allocations/" + allocationKey
                + "/settlement-diagnostic?deliveryDate=2026-11-10&deliveredVolume=0.010");
        assertEquals("0", diag.get("heldAmount").asText());
        assertEquals("0.28", diag.get("settledDelivered").asText());
        assertEquals("0.02", diag.get("settledLoss").asText());
        assertEquals("0.01", diag.get("trialDelivered").asText());
        assertEquals("0", diag.get("trialLoss").asText());
        assertEquals("0.01", diag.get("trialDeduction").asText());
        assertEquals("0.01", diag.get("shortfall").asText());
    }

    @Test
    void settlingNonApprovedAllocationIsRejected() throws Exception {
        long windowId = createWindow("10");
        String requestedKey = submitAllocation(windowId, "user-x", "2", "alice");
        registerRate("2026-11-10", "0.1");
        postExpect("/api/settlements",
                settleBody(key("sc"), key("bk"), requestedKey, "2026-11-10", "1"),
                409, "ALLOCATION_NOT_SETTLEABLE");
        // 已取消申请同样不可核销
        String cancelledKey = submitAllocation(windowId, "user-y", "2", "bob");
        postOk("/api/allocations/" + cancelledKey + "/approve", Map.of("commandKey", key("ap")));
        postOk("/api/allocations/" + cancelledKey + "/cancel", Map.of("commandKey", key("cc")), "bob");
        postExpect("/api/settlements",
                settleBody(key("sc"), key("bk"), cancelledKey, "2026-11-10", "1"),
                409, "ALLOCATION_NOT_SETTLEABLE");
    }

    // ------------------------------------------------------------------
    // 批量核销：成功、整批回滚、完整最终余额预校验
    // ------------------------------------------------------------------

    @Test
    void batchSettlesAllItemsAgainstFinalBalancesOrRollsBackEverything() throws Exception {
        long windowId = createWindow("20");
        String a = approvedAllocation(windowId, "user-a", "6", "alice");
        String b = approvedAllocation(windowId, "user-b", "4", "bob");
        registerRate("2026-11-10", "0.1000");
        registerRate("2026-11-11", "0.2000");

        // 失败批：A 送达 3 扣 3.30 可行；B 送达 4 损耗 0.80 扣 4.80 > 4，整批 422
        String failedCommand = key("bc");
        Map<String, Object> badBatch = new HashMap<>();
        badBatch.put("commandKey", failedCommand);
        badBatch.put("batchKey", key("bb"));
        badBatch.put("items", List.of(
                item(key("sk"), a, "2026-11-10", "3"),
                item(key("sk"), b, "2026-11-11", "4")));
        postExpect("/api/settlements/batch", badBatch, 422, "UPSTREAM_QUOTA_EXCEEDED");

        // 整批回滚：无凭证、两申请持有额度不变
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("6", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals("4", allocationView(windowId, b).get("heldAmount").asText());
        // 失败不占 commandKey：同键提交一个合法批
        Map<String, Object> goodBatch = new HashMap<>();
        goodBatch.put("commandKey", failedCommand);
        goodBatch.put("batchKey", key("bb"));
        goodBatch.put("items", List.of(
                item(key("sk"), a, "2026-11-10", "3"),
                item(key("sk"), b, "2026-11-11", "2")));
        JsonNode result = postOk("/api/settlements/batch", goodBatch);
        assertEquals(2, result.get("itemCount").asInt());
        // 顺序与请求一致
        JsonNode first = result.get("settlements").get(0);
        JsonNode second = result.get("settlements").get(1);
        assertEquals(a, first.get("allocationKey").asText());
        assertEquals("0.3", first.get("loss").asText());
        assertEquals("3.3", first.get("upstreamDeduction").asText());
        assertEquals(b, second.get("allocationKey").asText());
        assertEquals("0.4", second.get("loss").asText());
        assertEquals("2.4", second.get("upstreamDeduction").asText());
        assertEquals("2.7", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals("1.6", allocationView(windowId, b).get("heldAmount").asText());
        // 批次重放：同键同参返回首次结果，凭证数不增加
        JsonNode replay = postOk("/api/settlements/batch", goodBatch);
        assertEquals(result, replay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void batchValidatesCumulativeFinalBalanceOfSameAllocation() throws Exception {
        long windowId = createWindow("2");
        String a = approvedAllocation(windowId, "user-a", "1.000", "alice");
        registerRate("2026-11-10", "0.1000");
        // 两笔各 0.5：单笔扣 0.55 都可行，累计 1.10 > 持有 1.00，按完整最终余额预校验整批失败
        Map<String, Object> batch = new HashMap<>();
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bb"));
        batch.put("items", List.of(
                item(key("sk"), a, "2026-11-10", "0.5"),
                item(key("sk"), a, "2026-11-10", "0.5")));
        postExpect("/api/settlements/batch", batch, 422, "UPSTREAM_QUOTA_EXCEEDED");
        assertEquals("1", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());

        // 调整为累计扣 0.55 + 0.44 = 0.99 可行
        Map<String, Object> okBatch = new HashMap<>();
        okBatch.put("commandKey", key("bc"));
        okBatch.put("batchKey", key("bb"));
        okBatch.put("items", List.of(
                item(key("sk"), a, "2026-11-10", "0.5"),
                item(key("sk"), a, "2026-11-10", "0.4")));
        JsonNode result = postOk("/api/settlements/batch", okBatch);
        assertEquals(2, result.get("settlements").size());
        assertEquals("0.01", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals("0.9", allocationView(windowId, a).get("settledDelivered").asText());
        assertEquals("0.09", allocationView(windowId, a).get("settledLoss").asText());
    }

    @Test
    void batchRejectsMixedWindowsDuplicateKeysAndEmptyItems() throws Exception {
        long w1 = createWindow("10");
        String otherChannel = "ch-ev-other-" + run;
        long w2 = createWindow(otherChannel, "10");
        String a = approvedAllocation(w1, "user-a", "6", "alice");
        String c = approvedAllocation(w2, "user-c", "6", "zoe");
        registerRate("2026-11-10", "0.1");
        registerRate(otherChannel, "2026-11-10", "0.1");

        // 跨窗口 -> 422
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("commandKey", key("bc"));
        mixed.put("batchKey", key("bb"));
        mixed.put("items", List.of(
                item(key("sk"), a, "2026-11-10", "1"),
                item(key("sk"), c, "2026-11-10", "1")));
        postExpect("/api/settlements/batch", mixed, 422, "BATCH_MIXED_WINDOWS");

        // 批内 settlementKey 重复 -> 400
        String dup = key("sk");
        Map<String, Object> dupBatch = new HashMap<>();
        dupBatch.put("commandKey", key("bc"));
        dupBatch.put("batchKey", key("bb"));
        dupBatch.put("items", List.of(
                item(dup, a, "2026-11-10", "1"),
                item(dup, a, "2026-11-10", "1")));
        postExpect("/api/settlements/batch", dupBatch, 400, "INVALID_ARGUMENT");

        // 空 items -> 400
        Map<String, Object> empty = new HashMap<>();
        empty.put("commandKey", key("bc"));
        empty.put("batchKey", key("bb"));
        empty.put("items", List.of());
        postExpect("/api/settlements/batch", empty, 400, "INVALID_ARGUMENT");

        // 全部失败后无凭证
        assertEquals(0, getOk("/api/windows/" + w1 + "/settlements").get("settlements").size());
    }

    @Test
    void reusedBatchAndSettlementKeysReturn409WithoutSideEffects() throws Exception {
        long windowId = createWindow("20");
        String a = approvedAllocation(windowId, "user-a", "6", "alice");
        String b = approvedAllocation(windowId, "user-b", "6", "bob");
        registerRate("2026-11-10", "0.1");
        String batchKey = key("bb");
        postOk("/api/settlements", settleBody(key("sc"), batchKey, a, "2026-11-10", "1"));
        // 换 commandKey 复用 batchKey（单笔路径 settlementKey=batchKey:1）-> 409
        postExpect("/api/settlements",
                settleBody(key("sc"), batchKey, b, "2026-11-10", "1"),
                409, "BATCH_KEY_REUSED");
        // 批处理显式复用既有 settlementKey -> 409
        Map<String, Object> batch = new HashMap<>();
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bb"));
        batch.put("items", List.of(item(batchKey + ":1", b, "2026-11-10", "1")));
        postExpect("/api/settlements/batch", batch, 409, "SETTLEMENT_KEY_REUSED");
        assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("6", allocationView(windowId, b).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 反向更正
    // ------------------------------------------------------------------

    @Test
    void correctionReversesVoucherRestoresQuotaAndKeepsEvidence() throws Exception {
        long windowId = createWindow("10");
        String a = approvedAllocation(windowId, "user-a", "10", "alice");
        registerRate("2026-11-10", "0.1");
        JsonNode voucher = postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), a, "2026-11-10", "1"));
        assertEquals("1.1", voucher.get("upstreamDeduction").asText());

        String commandKey = key("xc");
        String correctionKey = key("xk");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("correctionKey", correctionKey);
        body.put("reason", "计量错误，全额冲回");
        String settlementKey = voucher.get("settlementKey").asText();
        JsonNode correction = postOk("/api/settlements/" + settlementKey + "/corrections", body);
        assertEquals(correctionKey, correction.get("correctionKey").asText());
        assertEquals(settlementKey, correction.get("settlementKey").asText());
        assertEquals("1", correction.get("deliveredRefund").asText());
        assertEquals("0.1", correction.get("lossRefund").asText());
        assertEquals("1.1", correction.get("deductionRefund").asText());

        // 同参重放
        JsonNode replay = postOk("/api/settlements/" + settlementKey + "/corrections", body);
        assertEquals(correction, replay);

        // 额度与累计恢复；凭证置 REVERSED 但证据保留
        JsonNode allocation = allocationView(windowId, a);
        assertEquals("10", allocation.get("heldAmount").asText());
        assertEquals("0", allocation.get("settledDelivered").asText());
        assertEquals("0", allocation.get("settledLoss").asText());
        JsonNode settled = getOk("/api/settlements/" + settlementKey);
        assertEquals("REVERSED", settled.get("status").asText());
        JsonNode history = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, history.get("settlements").size());
        assertEquals("REVERSED", history.get("settlements").get(0).get("status").asText());
        assertEquals(1, history.get("corrections").size());

        // 再次更正 -> 409；同键改参 -> 409；更正不存在凭证 -> 404；空原因 -> 400
        postExpect("/api/settlements/" + settlementKey + "/corrections",
                Map.of("commandKey", key("xc"), "correctionKey", key("xk"), "reason", "再冲一次"),
                409, "SETTLEMENT_ALREADY_REVERSED");
        postExpect("/api/settlements/" + settlementKey + "/corrections",
                Map.of("commandKey", commandKey, "correctionKey", key("xk"), "reason", "改原因"),
                409, "COMMAND_KEY_REUSED");
        postExpect("/api/settlements/missing-" + run + "/corrections",
                Map.of("commandKey", key("xc"), "correctionKey", key("xk"), "reason", "x"),
                404, "SETTLEMENT_NOT_FOUND");
        postExpect("/api/settlements/" + settlementKey + "/corrections",
                Map.of("commandKey", key("xc"), "correctionKey", key("xk"), "reason", "  "),
                400, "INVALID_ARGUMENT");

        // 冲回后可对该申请重新核销，率仍冻结在 v1
        JsonNode again = postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), a, "2026-11-10", "0.5"));
        assertEquals(1, again.get("rateVersion").asInt());
        assertEquals("0.05", again.get("loss").asText());
        assertEquals(2, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 证据稳定：转让不改写已结算凭证
    // ------------------------------------------------------------------

    @Test
    void laterTransferDoesNotRewriteSettledEvidence() throws Exception {
        long windowId = createWindow("10");
        String source = approvedAllocation(windowId, "user-src", "6", "alice");
        String target = submitAllocation(windowId, "user-dst", "2", "bob");
        registerRate("2026-11-10", "0.1");
        JsonNode voucher = postOk("/api/settlements",
                settleBody(key("sc"), key("bk"), source, "2026-11-10", "1"));
        // 把剩余额度中的 2 转出
        postOk("/api/transfers", transferBody(key("tk"), source, target), "alice");
        JsonNode frozen = getOk("/api/settlements/" + voucher.get("settlementKey").asText());
        assertEquals("SETTLED", frozen.get("status").asText());
        assertEquals("1", frozen.get("deliveredVolume").asText());
        assertEquals("0.1", frozen.get("loss").asText());
        assertEquals("1.1", frozen.get("upstreamDeduction").asText());
        JsonNode sourceView = allocationView(windowId, source);
        // 6 - 1.1 核销 - 2 转让 = 2.9；已结算累计不随转让变化
        assertEquals("2.9", sourceView.get("heldAmount").asText());
        assertEquals("1", sourceView.get("settledDelivered").asText());
        assertEquals("0.1", sourceView.get("settledLoss").asText());
    }

    // ------------------------------------------------------------------
    // 取消未发生的预约不产生蒸发损耗
    // ------------------------------------------------------------------

    @Test
    void cancelWithoutSettlementCreatesNoLossAndNoVoucher() throws Exception {
        long windowId = createWindow("10");
        String requestedKey = submitAllocation(windowId, "user-1", "3", "alice");
        registerRate("2026-11-10", "0.1");
        JsonNode cancelled = postOk("/api/allocations/" + requestedKey + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("settledDelivered").asText());
        assertEquals("0", cancelled.get("settledLoss").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 可控时钟
    // ------------------------------------------------------------------

    @Test
    void controlledClockDrivesSettlementTimestamp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-10T12:34:56.789Z"), ZoneOffset.UTC);
        waterService.setClock(fixed);
        EvaporationRateResponse rate = waterService.registerEvaporationRate(
                key("rc"), "ch-clock-" + run, "2026-11-10", "0.1");
        assertEquals("2026-11-10T12:34:56.789Z", rate.createdUtc());
        waterService.setClock(Clock.fixed(Instant.parse("2026-11-10T23:59:59Z"), ZoneOffset.UTC));
        // 同一日再次登记（无核销）产生 v2，时间随时钟变化
        EvaporationRateResponse v2 = waterService.registerEvaporationRate(
                key("rc"), "ch-clock-" + run, "2026-11-10", "0.12");
        assertEquals(2, v2.version());
        assertEquals("2026-11-10T23:59:59Z", v2.createdUtc());
    }

    // ------------------------------------------------------------------
    // 并发：同一上游额度并发扣减不超卖
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsOnSameAllocationNeverOversell() throws Exception {
        long windowId = createWindow("1");
        String allocationKey = approvedAllocation(windowId, "user-1", "1.000", "alice");
        registerRate("2026-11-10", "0.1000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("sc");
            String batchKey = key("bk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.settleDelivery(commandKey, batchKey, allocationKey,
                            "2026-11-10", "0.600");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        // 每笔扣 0.66，两笔合计 1.32 > 1.00：恰好一笔成功、一笔 422
        assertEquals(1, ok);
        assertEquals(1, rejected);
        JsonNode allocation = allocationView(windowId, allocationKey);
        assertEquals("0.34", allocation.get("heldAmount").asText());
        assertEquals("0.6", allocation.get("settledDelivered").asText());
        assertEquals("0.06", allocation.get("settledLoss").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void concurrentSettlementAndTransferSettleByCommitOrderWithoutOversell() throws Exception {
        long windowId = createWindow("10");
        String source = approvedAllocation(windowId, "user-src", "5", "alice");
        String target = submitAllocation(windowId, "user-dst", "4", "bob");
        registerRate("2026-11-10", "0.1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String settleCommand = key("sc");
        String transferCommand = key("tc");
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            try {
                // 送达 2 -> 扣 2.2
                waterService.settleDelivery(settleCommand, key("bk"), source, "2026-11-10", "2");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, key("tk"), source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 核销先提交：持有 2.8 < 4 -> 转让 422；转让先提交：持有 1 < 2.2 -> 核销 422。恰好一笔成功。
        assertTrue((settleStatus == 200) != (transferStatus == 200),
                "settle=" + settleStatus + " transfer=" + transferStatus);
        JsonNode sourceView = allocationView(windowId, source);
        if (settleStatus == 200) {
            assertEquals(422, transferStatus);
            assertEquals("2.8", sourceView.get("heldAmount").asText());
            assertEquals("2", sourceView.get("settledDelivered").asText());
            assertEquals("0.2", sourceView.get("settledLoss").asText());
        } else {
            assertEquals(422, settleStatus);
            assertEquals("1", sourceView.get("heldAmount").asText());
            assertEquals("0", sourceView.get("settledDelivered").asText());
        }
        // 最终持有额度恒非负，已批准总量不超过可用总量
        assertTrue(new java.math.BigDecimal(sourceView.get("heldAmount").asText()).signum() >= 0);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }

    // ------------------------------------------------------------------
    // 诊断查询只读
    // ------------------------------------------------------------------

    @Test
    void diagnosticIsReadOnlyAndReportsMissingRateAsNull() throws Exception {
        long windowId = createWindow("10");
        String a = approvedAllocation(windowId, "user-a", "3.5", "alice");
        // 无率：试算不给损耗与扣减
        JsonNode before = getOk("/api/allocations/" + a
                + "/settlement-diagnostic?deliveryDate=2026-11-11&deliveredVolume=1");
        assertEquals("3.5", before.get("heldAmount").asText());
        assertNull(before.get("rate").asText(null));
        assertNull(before.get("trialLoss").asText(null));
        // 不传日期：仅返回余额类实际值
        JsonNode bare = getOk("/api/allocations/" + a + "/settlement-diagnostic");
        assertEquals("3.5", bare.get("remainingSettleable").asText());
        assertNull(bare.get("deliveryDate").asText(null));
        // 诊断不改变任何状态
        assertEquals("3.5", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        // 申请不存在 -> 404
        mvc.perform(get("/api/allocations/missing-" + run + "/settlement-diagnostic"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRateParamsReturn400() throws Exception {
        postExpect("/api/evaporation-rates", rateBody(channel(), "2026-11-10", "0.2001"),
                400, "INVALID_ARGUMENT");
        postExpect("/api/evaporation-rates", rateBody(channel(), "2026-11-10", "-0.01"),
                400, "INVALID_ARGUMENT");
        postExpect("/api/evaporation-rates", rateBody(channel(), "2026-11-10", "0.12345"),
                400, "INVALID_ARGUMENT");
        postExpect("/api/evaporation-rates", rateBody(channel(), "bad-date", "0.1"),
                400, "INVALID_ARGUMENT");
        postExpect("/api/evaporation-rates", rateBody(channel(), "2026-11-10", "abc"),
                400, "INVALID_ARGUMENT");
    }

    private List<JsonNode> jsonNodes(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }
}
