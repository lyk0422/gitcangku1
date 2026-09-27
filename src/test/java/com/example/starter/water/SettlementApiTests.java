package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 蒸发结算 API 集成测试：全部基于真实 H2 内存库（MODE=MySQL）的事务、行锁、唯一与 CHECK 约束，
 * 覆盖主流程、422/409 失败分支与整批回滚、幂等重放、并发扣减不超卖、证据固化与反向更正。
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

    @Autowired
    private JdbcTemplate jdbc;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult call(String method, String url, Map<String, Object> body, String actor, int expected)
            throws Exception {
        var builder = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(method);
        };
        builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode ok(String method, String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(call(method, url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return ok("POST", url, body, actor);
    }

    private JsonNode putOk(String url, Map<String, Object> body) throws Exception {
        return ok("PUT", url, body, null);
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode error(String method, String url, Map<String, Object> body, String actor, int expected)
            throws Exception {
        return objectMapper.readTree(call(method, url, body, actor, expected).getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // 场景搭建
    // ------------------------------------------------------------------

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return putOrPostWindow(body);
    }

    private long putOrPostWindow(Map<String, Object> body) throws Exception {
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitApprove(long windowId, String userId, String amount, String actor) throws Exception {
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

    private String submitOnly(long windowId, String userId, String amount, String actor) throws Exception {
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

    private JsonNode putRate(String channel, String day, String rate) throws Exception {
        return putOk("/api/channels/" + channel + "/evaporation-rates/" + day,
                Map.of("commandKey", key("rc"), "rate", rate));
    }

    private Map<String, Object> settlementBody(String commandKey, String settlementKey, long windowId,
                                               String allocationKey, String occurred, String delivered,
                                               String downstream, String dayUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("settlementKey", settlementKey);
        body.put("windowId", windowId);
        body.put("allocationKey", allocationKey);
        body.put("occurredUtc", occurred);
        body.put("deliveredVolume", delivered);
        if (downstream != null) {
            body.put("downstreamUserId", downstream);
        }
        if (dayUtc != null) {
            body.put("dayUtc", dayUtc);
        }
        return body;
    }

    private Map<String, Object> item(String settlementKey, String allocationKey, String occurred,
                                     String delivered) {
        return item(settlementKey, allocationKey, occurred, delivered, null, null);
    }

    private Map<String, Object> item(String settlementKey, String allocationKey, String occurred,
                                     String delivered, String downstream, String dayUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("settlementKey", settlementKey);
        body.put("allocationKey", allocationKey);
        body.put("occurredUtc", occurred);
        body.put("deliveredVolume", delivered);
        if (downstream != null) {
            body.put("downstreamUserId", downstream);
        }
        if (dayUtc != null) {
            body.put("dayUtc", dayUtc);
        }
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

    private int settleStatus(String commandKey, String settlementKey, long windowId, String allocationKey,
                             String occurred, String delivered, String actor) {
        try {
            waterService.settleDelivery(commandKey, settlementKey, windowId, allocationKey, occurred,
                    delivered, null, null, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void rateRegistrationVersioningAndSettlementFlow() throws Exception {
        String channel = "ch-ev-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "user-up", "10", "alice");

        // 首次登记版本 1，核销前修改版本递增
        JsonNode rate1 = putRate(channel, "2026-10-16", "0.0500");
        assertEquals("0.0500", rate1.get("rate").asText());
        assertEquals(1, rate1.get("version").asLong());
        assertFalse(rate1.get("frozen").asBoolean());
        JsonNode rate2 = putRate(channel, "2026-10-16", "0.1000");
        assertEquals(2, rate2.get("version").asLong());

        // 核销：送达 2，率 10% -> 损耗 0.20，上游扣减 2.20（银行家舍入到两位）
        JsonNode result = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "2", "user-down", null), "gauger");
        JsonNode settlement = result.get("settlement");
        assertEquals("2", settlement.get("deliveredVolume").asText());
        assertEquals("0.2", settlement.get("lossVolume").asText());
        assertEquals("2.2", settlement.get("upstreamDeduction").asText());
        assertEquals("0.1000", settlement.get("rate").asText());
        assertEquals(2, settlement.get("rateVersion").asLong());
        assertEquals("SETTLED", settlement.get("status").asText());
        assertEquals("user-down", settlement.get("downstreamUserId").asText());
        assertEquals("2026-10-16", settlement.get("dayUtc").asText());
        assertEquals("7.8", result.get("upstreamRemaining").asText());
        assertEquals("2.2", result.get("upstreamRequired").asText());

        // 上游持有额度同步扣减
        assertEquals("7.8", allocationView(windowId, upstream).get("heldAmount").asText());

        // 数据库层守恒：上游扣减 = 送达 + 损耗
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT delivered_volume, loss_volume, upstream_deduction, rate_value, rate_version"
                        + " FROM settlement WHERE settlement_key = ?",
                settlement.get("settlementKey").asText());
        BigDecimal delivered = (BigDecimal) row.get("delivered_volume");
        BigDecimal loss = (BigDecimal) row.get("loss_volume");
        BigDecimal deduction = (BigDecimal) row.get("upstream_deduction");
        assertEquals(0, delivered.add(loss).compareTo(deduction));

        // 率诊断：当日已冻结
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-rates");
        JsonNode rateRow = diag.get("rates").get(0);
        assertTrue(rateRow.get("frozen").asBoolean());
        assertNotNull(rateRow.get("firstSettlementUtc"));

        // 明细查询
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("settlements").size());
        assertNull(list.get("settlements").get(0).get("correctionKey").asText(null));
        JsonNode byAllocation = getOk("/api/allocations/" + upstream + "/settlements");
        assertEquals(1, byAllocation.get("settlements").size());
    }

    @Test
    void bankerRoundingCaseRoundsLossUpButKeepsConservation() throws Exception {
        String channel = "ch-round-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.0500");
        // 送达 0.12：损耗精确 0.006 -> 银行家舍入 0.01；扣减精确 0.126 -> 0.13；守恒成立
        JsonNode result = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "0.12", null, null), "alice");
        assertEquals("0.01", result.get("settlement").get("lossVolume").asText());
        assertEquals("0.13", result.get("settlement").get("upstreamDeduction").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支
    // ------------------------------------------------------------------

    @Test
    void missingRateReturns422AndWritesNothing() throws Exception {
        String channel = "ch-norate-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        JsonNode err = error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, null), "alice", 422);
        assertEquals("EVAPORATION_RATE_MISSING", err.get("code").asText());
        assertEquals("10", allocationView(windowId, upstream).get("heldAmount").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void outsideWindowReturns422WithActualAndRequiredIncludingHalfOpenBoundary() throws Exception {
        String channel = "ch-window-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-16T12:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0");

        // 左闭：恰为窗口开始 -> 成功
        postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T00:00:00Z", "0.1", null, null), "alice");
        // 右开：恰为窗口结束 -> 422，响应携带实际值/要求值
        JsonNode atEnd = error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T12:00:00Z", "0.1", null, null), "alice", 422);
        assertEquals("SETTLEMENT_OUTSIDE_WINDOW", atEnd.get("code").asText());
        assertEquals("2026-10-16T12:00:00Z", atEnd.get("actual").asText());
        assertTrue(atEnd.get("required").asText().contains("2026-10-16T12:00:00Z"));
        // 窗口之前 -> 422
        error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-15T23:59:59Z", "0.1", null, null), "alice", 422);

        // 只有一笔成功写入
        assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void insufficientUpstreamQuotaReturns422WithActualRequiredDeltaAndNoPartialWrite() throws Exception {
        String channel = "ch-quota-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "1", "alice");
        putRate(channel, "2026-10-16", "0.1");
        // 送达 1 -> 扣减 1.1 > 持有 1 -> 422
        JsonNode err = error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, null), "alice", 422);
        assertEquals("UPSTREAM_QUOTA_INSUFFICIENT", err.get("code").asText());
        assertEquals("1", err.get("actual").asText());
        assertEquals("1.1", err.get("required").asText());
        assertEquals("0.1", err.get("delta").asText());
        // 未写任何核销，额度不变
        assertEquals("1", allocationView(windowId, upstream).get("heldAmount").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void roundingConservationFailureReturns422() throws Exception {
        String channel = "ch-conserve-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.0400");
        // 送达 0.125、率 4%：计入送达 round2(0.125)=0.12，损耗 0.005 -> 0.00，扣减 0.13；
        // 独立舍入后 0.13 != 0.12 + 0，守恒不成立
        JsonNode err = error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "0.125", null, null), "alice", 422);
        assertEquals("ROUNDING_CONSERVATION", err.get("code").asText());
        assertEquals("0.13", err.get("actual").asText());
        assertEquals("0.12", err.get("required").asText());
        assertEquals("0.01", err.get("delta").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("10", allocationView(windowId, upstream).get("heldAmount").asText());
    }

    @Test
    void tinyDeliveryRoundingToZeroDeductionReturns422() throws Exception {
        String channel = "ch-tiny-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0");
        // 送达 0.001：两位小数银行家舍入后扣减为 0 -> 422（可区分错误码），不产生记录
        JsonNode err = error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "0.001", null, null), "alice", 422);
        assertEquals("SETTLEMENT_VOLUME_TOO_SMALL", err.get("code").asText());
        assertEquals("0", err.get("actual").asText());
        assertEquals("0.01", err.get("required").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("10", allocationView(windowId, upstream).get("heldAmount").asText());
    }

    @Test
    void upstreamMustBeApproved() throws Exception {        String channel = "ch-state-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String requested = submitOnly(windowId, "u1", "1", "alice");
        String cancelled = submitApprove(windowId, "u2", "1", "bob");
        postOk("/api/allocations/" + cancelled + "/cancel", Map.of("commandKey", key("cc")), "bob");
        putRate(channel, "2026-10-16", "0");

        error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, requested,
                        "2026-10-16T08:00:00Z", "0.1", null, null), "alice", 409);
        error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, cancelled,
                        "2026-10-16T08:00:00Z", "0.1", null, null), "alice", 409);
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 率冻结与证据固化
    // ------------------------------------------------------------------

    @Test
    void rateFreezesAfterFirstSettlementAndEvidenceIsImmutable() throws Exception {
        String channel = "ch-freeze-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");

        JsonNode settlement = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, null), "alice").get("settlement");

        // 首次核销后修改当日率 -> 409
        JsonNode frozenErr = error("PUT",
                "/api/channels/" + channel + "/evaporation-rates/2026-10-16",
                Map.of("commandKey", key("rc"), "rate", "0.15"), null, 409);
        assertEquals("RATE_FROZEN", frozenErr.get("code").asText());

        // 转让/后续操作不改写已结算证据：转出剩余 8.9，原核销值不变
        String target = submitOnly(windowId, "u2", "8.9", "carol");
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", upstream);
        transfer.put("targetAllocationKey", target);
        postOk("/api/transfers", transfer, "alice");

        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        JsonNode stored = list.get("settlements").get(0);
        assertEquals(settlement.get("settlementKey").asText(), stored.get("settlementKey").asText());
        assertEquals("1", stored.get("deliveredVolume").asText());
        assertEquals("0.1", stored.get("lossVolume").asText());
        assertEquals("1.1", stored.get("upstreamDeduction").asText());
        assertEquals("0.1000", stored.get("rate").asText());
        assertEquals(1, stored.get("rateVersion").asLong());
        assertEquals("0", allocationView(windowId, upstream).get("heldAmount").asText());
    }

    @Test
    void nextDayUsesIndependentRate() throws Exception {
        String channel = "ch-twodays-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-18T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");
        putRate(channel, "2026-10-17", "0.2");

        JsonNode day1 = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T23:59:59Z", "1", null, null), "alice").get("settlement");
        JsonNode day2 = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-17T00:00:00Z", "1", null, null), "alice").get("settlement");
        assertEquals("0.1", day1.get("lossVolume").asText());
        assertEquals("0.2", day2.get("lossVolume").asText());
        // 次日率登记不影响已冻结的 16 日率版本
        assertEquals(1, day1.get("rateVersion").asLong());
        assertEquals(1, day2.get("rateVersion").asLong());
    }

    @Test
    void explicitDayMismatchReturns400() throws Exception {
        String channel = "ch-daym-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-18T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0");
        error("POST", "/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, "2026-10-17"), "alice", 400);
    }

    @Test
    void invalidRatesReturn400() throws Exception {
        String channel = "ch-badrate-" + run;
        for (String bad : List.of("0.2001", "1", "-0.01", "0.12345", "abc", "")) {
            error("PUT", "/api/channels/" + channel + "/evaporation-rates/2026-10-16",
                    Map.of("commandKey", key("rc"), "rate", bad), null, 400);
        }
        // 非法日期
        error("PUT", "/api/channels/" + channel + "/evaporation-rates/16-10-2026",
                Map.of("commandKey", key("rc"), "rate", "0.1"), null, 400);
    }

    // ------------------------------------------------------------------
    // 批量核销
    // ------------------------------------------------------------------

    @Test
    void batchSettlesAgainstCompleteFinalBalances() throws Exception {
        String channel = "ch-batch-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String a = submitApprove(windowId, "ua", "0.6", "alice");
        String b = submitApprove(windowId, "ub", "10", "bob");
        putRate(channel, "2026-10-16", "0");

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("bc"));
        body.put("batchKey", key("bk"));
        body.put("windowId", windowId);
        body.put("items", List.of(
                item(key("sk"), a, "2026-10-16T08:00:00Z", "0.3"),
                item(key("sk"), a, "2026-10-16T09:00:00Z", "0.3"),
                item(key("sk"), b, "2026-10-16T09:30:00Z", "2.5")));
        JsonNode batch = postOk("/api/settlements/batch", body, "alice");
        assertEquals(3, batch.get("settlements").size());
        // 同一上游聚合扣减，最终余额 0
        JsonNode balances = batch.get("finalBalances");
        JsonNode balanceA = null;
        for (JsonNode balance : balances) {
            if (a.equals(balance.get("allocationKey").asText())) {
                balanceA = balance;
            }
        }
        assertNotNull(balanceA);
        assertEquals("0", balanceA.get("remaining").asText());
        assertEquals("0.6", balanceA.get("required").asText());
        assertEquals("0", allocationView(windowId, a).get("heldAmount").asText());
        assertEquals("7.5", allocationView(windowId, b).get("heldAmount").asText());
        assertEquals(3, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
    }

    @Test
    void batchFailureRollsBackEverythingAndDoesNotOccupyCommandKey() throws Exception {
        String channel = "ch-batchfail-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String a = submitApprove(windowId, "ua", "0.5", "alice");
        putRate(channel, "2026-10-16", "0");

        // 第一笔 0.3 成功口径下，第二笔再扣 0.3 超出最终余额 -> 整批 422，定位明细 1
        Map<String, Object> failing = new HashMap<>();
        String commandKey = key("bc");
        failing.put("commandKey", commandKey);
        failing.put("batchKey", key("bk"));
        failing.put("windowId", windowId);
        String sk1 = key("sk");
        failing.put("items", List.of(
                item(sk1, a, "2026-10-16T08:00:00Z", "0.3"),
                item(key("sk"), a, "2026-10-16T09:00:00Z", "0.3")));
        JsonNode err = error("POST", "/api/settlements/batch", failing, "alice", 422);
        assertEquals("UPSTREAM_QUOTA_INSUFFICIENT", err.get("code").asText());
        assertEquals(1, err.get("itemIndex").asInt());
        assertEquals("0.5", err.get("actual").asText());
        assertEquals("0.6", err.get("required").asText());
        assertEquals("0.1", err.get("delta").asText());

        // 整批回滚：无核销、额度不变
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("0.5", allocationView(windowId, a).get("heldAmount").asText());
        Integer settlementRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE window_id = ?", Integer.class, windowId);
        assertEquals(0, settlementRows);

        // 失败不占键：同 commandKey 提交一个合法批量 -> 成功
        Map<String, Object> good = new HashMap<>();
        good.put("commandKey", commandKey);
        good.put("batchKey", key("bk"));
        good.put("windowId", windowId);
        good.put("items", List.of(
                item(key("sk"), a, "2026-10-16T08:00:00Z", "0.3")));
        JsonNode batch = postOk("/api/settlements/batch", good, "alice");
        assertEquals(1, batch.get("settlements").size());
    }

    @Test
    void batchRoundingFailureOnAnyItemRollsBackWholeBatch() throws Exception {
        String channel = "ch-batchround-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String a = submitApprove(windowId, "ua", "10", "alice");
        putRate(channel, "2026-10-16", "0.0400");

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("bc"));
        body.put("batchKey", key("bk"));
        body.put("windowId", windowId);
        body.put("items", List.of(
                item(key("sk"), a, "2026-10-16T08:00:00Z", "1"),
                item(key("sk"), a, "2026-10-16T09:00:00Z", "0.125")));
        JsonNode err = error("POST", "/api/settlements/batch", body, "alice", 422);
        assertEquals("ROUNDING_CONSERVATION", err.get("code").asText());
        assertEquals(1, err.get("itemIndex").asInt());
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        assertEquals("10", allocationView(windowId, a).get("heldAmount").asText());
    }

    @Test
    void batchKeyReusedAcrossCommandKeysReturns409() throws Exception {
        String channel = "ch-batchkey-" + run;
        long w1 = createWindow(channel + "-1", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        long w2 = createWindow(channel + "-2", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String a1 = submitApprove(w1, "u1", "10", "alice");
        String a2 = submitApprove(w2, "u2", "10", "bob");
        putRate(channel + "-1", "2026-10-16", "0");
        putRate(channel + "-2", "2026-10-16", "0");
        String batchKey = key("bk");

        Map<String, Object> first = new HashMap<>();
        first.put("commandKey", key("bc"));
        first.put("batchKey", batchKey);
        first.put("windowId", w1);
        first.put("items", List.of(item(key("sk"), a1, "2026-10-16T08:00:00Z", "1")));
        postOk("/api/settlements/batch", first, "alice");

        // 换 commandKey 复用同一 batchKey（即使指向另一窗口）-> 409，不产生第二批记录
        Map<String, Object> second = new HashMap<>();
        second.put("commandKey", key("bc"));
        second.put("batchKey", batchKey);
        second.put("windowId", w2);
        second.put("items", List.of(item(key("sk"), a2, "2026-10-16T08:00:00Z", "1")));
        JsonNode err = error("POST", "/api/settlements/batch", second, "bob", 409);
        assertEquals("BATCH_KEY_REUSED", err.get("code").asText());
        assertEquals(0, getOk("/api/windows/" + w2 + "/settlements").get("settlements").size());
        assertEquals("10", allocationView(w2, a2).get("heldAmount").asText());
    }

    @Test
    void batchRejectsEmptyAndDuplicateKeys() throws Exception {
        String channel = "ch-batchbad-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        Map<String, Object> empty = new HashMap<>();
        empty.put("commandKey", key("bc"));
        empty.put("batchKey", key("bk"));
        empty.put("windowId", windowId);
        empty.put("items", List.of());
        error("POST", "/api/settlements/batch", empty, "alice", 400);

        String a = submitApprove(windowId, "ua", "10", "alice");
        putRate(channel, "2026-10-16", "0");
        String dupKey = key("sk");
        Map<String, Object> dup = new HashMap<>();
        dup.put("commandKey", key("bc"));
        dup.put("batchKey", key("bk"));
        dup.put("windowId", windowId);
        dup.put("items", List.of(
                item(dupKey, a, "2026-10-16T08:00:00Z", "1"),
                item(dupKey, a, "2026-10-16T09:00:00Z", "1")));
        JsonNode err = error("POST", "/api/settlements/batch", dup, "alice", 400);
        assertEquals("SETTLEMENT_KEY_DUPLICATED", err.get("code").asText());
        assertEquals(1, err.get("itemIndex").asInt());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void settlementIdempotentReplayAndConflict() throws Exception {
        String channel = "ch-idem-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");
        String commandKey = key("sc");
        Map<String, Object> body = settlementBody(commandKey, key("sk"), windowId, upstream,
                "2026-10-16T08:00:00Z", "1", null, null);
        JsonNode first = postOk("/api/settlements", body, "alice");
        JsonNode replay = postOk("/api/settlements", body, "alice");
        assertEquals(first, replay);
        // 只扣减一次、只有一条核销
        assertEquals("8.9", allocationView(windowId, upstream).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());

        // 同键改参（送达量）-> 409
        error("POST", "/api/settlements",
                settlementBody(commandKey, key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "2", null, null), "alice", 409);
        // 换 commandKey 复用 settlementKey -> 409
        error("POST", "/api/settlements",
                settlementBody(key("sc"), first.get("settlement").get("settlementKey").asText(),
                        windowId, upstream, "2026-10-16T08:00:00Z", "1", null, null), "alice", 409);
        // 同键改操作人 -> 409
        error("POST", "/api/settlements", body, "bob", 409);
    }

    @Test
    void rateCommandReplaysAndConflicts() throws Exception {
        String channel = "ch-rateidem-" + run;
        String commandKey = key("rc");
        Map<String, Object> body = Map.of("commandKey", commandKey, "rate", "0.1");
        JsonNode first = putOk("/api/channels/" + channel + "/evaporation-rates/2026-10-16", body);
        JsonNode replay = putOk("/api/channels/" + channel + "/evaporation-rates/2026-10-16", body);
        assertEquals(first, replay);
        // 首次登记只产生版本 1（重放未再次 +1）
        assertEquals(1, replay.get("version").asLong());
        error("PUT", "/api/channels/" + channel + "/evaporation-rates/2026-10-16",
                Map.of("commandKey", commandKey, "rate", "0.11"), null, 409);
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsOnSameUpstreamNeverOversell() throws Exception {
        String channel = "ch-race-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "0.5", "alice");
        putRate(channel, "2026-10-16", "0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return settleStatus(key("sc"), key("sk"), windowId, upstream,
                    "2026-10-16T08:00:00Z", "0.3", "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return settleStatus(key("sc"), key("sk"), windowId, upstream,
                    "2026-10-16T09:00:00Z", "0.3", "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "核销状态 " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        assertEquals("0.2", allocationView(windowId, upstream).get("heldAmount").asText());
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("settlements").size());
        BigDecimal deducted = jdbc.queryForObject(
                "SELECT COALESCE(SUM(upstream_deduction),0) FROM settlement WHERE window_id = ?",
                BigDecimal.class, windowId);
        assertEquals(0, new BigDecimal("0.3").compareTo(deducted));
    }

    @Test
    void settlementAndTransferSettleByCommitOrderWithoutOverselling() throws Exception {
        String channel = "ch-race-tx-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String source = submitApprove(windowId, "src", "0.5", "alice");
        String target = submitOnly(windowId, "dst", "0.5", "bob");
        putRate(channel, "2026-10-16", "0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String settleCommand = key("sc");
        String settleKey = key("sk");
        String transferCommand = key("tc");
        String transferKey = key("tk");
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            return settleStatus(settleCommand, settleKey, windowId, source,
                    "2026-10-16T08:00:00Z", "0.5", "alice");
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
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一上游 0.5 额度，两笔扣减按提交顺序裁决，恰好一笔成功
        assertEquals(1, (settleStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0),
                "settle=" + settleStatus + " transfer=" + transferStatus);
        assertEquals(1, (settleStatus == 422 ? 1 : 0) + (transferStatus == 422 ? 1 : 0));
        // 最终：源持有 0；额度要么在核销中扣除，要么在目标持有
        assertEquals("0", allocationView(windowId, source).get("heldAmount").asText());
        if (settleStatus == 200) {
            assertEquals("REQUESTED", allocationView(windowId, target).get("status").asText());
            assertEquals(1, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        } else {
            assertEquals("APPROVED", allocationView(windowId, target).get("status").asText());
            assertEquals("0.5", allocationView(windowId, target).get("heldAmount").asText());
            assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("settlements").size());
        }
    }

    @Test
    void rateChangeAndFirstSettlementArbitrateByCommitOrder() throws Exception {
        String channel = "ch-race-rate-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String settleCommand = key("sc");
        String settleKey = key("sk");
        String rateCommand = key("rc");
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            return settleStatus(settleCommand, settleKey, windowId, upstream,
                    "2026-10-16T08:00:00Z", "1", "alice");
        });
        Future<Integer> rateFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.putEvaporationRate(rateCommand, channel, "2026-10-16", "0.2000");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        int rateStatus = rateFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(200, settleStatus);
        JsonNode settlement = getOk("/api/windows/" + windowId + "/settlements").get("settlements").get(0);
        if (rateStatus == 200) {
            // 率修改先提交：核销按 20% 结算并固化版本 2，随后率冻结
            assertEquals("0.2", settlement.get("lossVolume").asText());
            assertEquals(2, settlement.get("rateVersion").asLong());
        } else {
            // 核销先提交：率冻结，修改 409，核销固化版本 1 与 10%
            assertEquals(409, rateStatus);
            assertEquals("0.1", settlement.get("lossVolume").asText());
            assertEquals(1, settlement.get("rateVersion").asLong());
        }
        // 无论谁先提交，首次核销后再次修改一律 409
        error("PUT", "/api/channels/" + channel + "/evaporation-rates/2026-10-16",
                Map.of("commandKey", key("rc"), "rate", "0.15"), null, 409);
    }

    // ------------------------------------------------------------------
    // 反向更正
    // ------------------------------------------------------------------

    @Test
    void reversalAppendsEvidenceRestoresQuotaAndKeepsOriginalUnchanged() throws Exception {
        String channel = "ch-rev-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");
        JsonNode original = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "2", null, null), "alice").get("settlement");
        assertEquals("7.8", allocationView(windowId, upstream).get("heldAmount").asText());

        String correctionKey = key("xk");
        String reversalCommandKey = key("xc");
        Map<String, Object> reversalBody = new HashMap<>();
        reversalBody.put("commandKey", reversalCommandKey);
        reversalBody.put("correctionKey", correctionKey);
        reversalBody.put("reason", "计量设备误读，冲销该笔核销");
        JsonNode reversal = postOk("/api/settlements/" + original.get("settlementKey").asText()
                + "/reversal", reversalBody, "supervisor");
        assertEquals(correctionKey, reversal.get("correctionKey").asText());
        assertEquals(original.get("settlementKey").asText(), reversal.get("originalSettlementKey").asText());
        assertEquals("2", reversal.get("reversedDelivered").asText());
        assertEquals("0.2", reversal.get("reversedLoss").asText());
        assertEquals("2.2", reversal.get("restoredUpstream").asText());
        assertEquals(1, reversal.get("rateVersion").asLong());

        // 上游持有额度回补
        assertEquals("10", allocationView(windowId, upstream).get("heldAmount").asText());
        // 明细：原核销不动并标记 correctionKey；更正只追加
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("settlements").size());
        JsonNode stored = list.get("settlements").get(0);
        assertEquals(correctionKey, stored.get("correctionKey").asText());
        assertEquals("2", stored.get("deliveredVolume").asText());
        assertEquals("0.2", stored.get("lossVolume").asText());
        assertEquals("2.2", stored.get("upstreamDeduction").asText());
        assertEquals(1, list.get("corrections").size());

        // 同键同参重放返回首次结果，不重复回补/追加
        JsonNode reversalReplay = postOk("/api/settlements/" + original.get("settlementKey").asText()
                + "/reversal", reversalBody, "supervisor");
        assertEquals(correctionKey, reversalReplay.get("correctionKey").asText());
        assertEquals("10", allocationView(windowId, upstream).get("heldAmount").asText());
        // 换 commandKey 重复更正同一核销 -> 409（一笔核销至多一条更正）
        Map<String, Object> again = new HashMap<>();
        again.put("commandKey", key("xc"));
        again.put("correctionKey", key("xk"));
        again.put("reason", "再次冲销");
        error("POST", "/api/settlements/" + original.get("settlementKey").asText() + "/reversal",
                again, "supervisor", 409);
        // 原 commandKey 改参（不同 reason）-> 409
        Map<String, Object> changed = new HashMap<>();
        changed.put("commandKey", reversalCommandKey);
        changed.put("correctionKey", correctionKey);
        changed.put("reason", "参数不同");
        error("POST", "/api/settlements/" + original.get("settlementKey").asText() + "/reversal",
                changed, "supervisor", 409);

        // 更正不存在的核销 -> 404；缺 reason -> 400
        error("POST", "/api/settlements/missing-" + run + "/reversal",
                Map.of("commandKey", key("xc"), "correctionKey", key("xk"), "reason", "x"),
                "supervisor", 404);
    }

    @Test
    void reversalAfterUpstreamCancelledReturns409() throws Exception {
        String channel = "ch-revcancel-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "2", "alice");
        putRate(channel, "2026-10-16", "0");
        JsonNode original = postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, null), "alice").get("settlement");
        postOk("/api/allocations/" + upstream + "/cancel", Map.of("commandKey", key("cc")), "alice");
        error("POST", "/api/settlements/" + original.get("settlementKey").asText() + "/reversal",
                Map.of("commandKey", key("xc"), "correctionKey", key("xk"), "reason", "冲销"),
                "supervisor", 409);
        // 未产生更正
        assertEquals(0, getOk("/api/windows/" + windowId + "/settlements").get("corrections").size());
    }

    // ------------------------------------------------------------------
    // 预约取消与读取语义
    // ------------------------------------------------------------------

    @Test
    void cancelUnoccurredReservationCreatesNoLoss() throws Exception {
        String channel = "ch-reserve-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String reserved = submitOnly(windowId, "u", "3", "alice");
        putRate(channel, "2026-10-16", "0.2");
        // 未发生（未批准、未核销）的预约取消：不产生任何蒸发损耗
        JsonNode cancelled = postOk("/api/allocations/" + reserved + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("heldAmount").asText());
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(0, list.get("settlements").size());
        assertEquals(0, list.get("corrections").size());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE window_id = ?", Integer.class, windowId));
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void readsDoNotChangeStateAndHistoryIsStable() throws Exception {
        String channel = "ch-read-" + run;
        long windowId = createWindow(channel, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String upstream = submitApprove(windowId, "u", "10", "alice");
        putRate(channel, "2026-10-16", "0.1");
        postOk("/api/settlements",
                settlementBody(key("sc"), key("sk"), windowId, upstream,
                        "2026-10-16T08:00:00Z", "1", null, null), "alice");

        JsonNode list1 = getOk("/api/windows/" + windowId + "/settlements");
        JsonNode diag1 = getOk("/api/channels/" + channel + "/evaporation-rates");
        JsonNode byAlloc1 = getOk("/api/allocations/" + upstream + "/settlements");
        // 再读一次：内容稳定、排序不变、冻结状态不被读取改变
        assertEquals(list1, getOk("/api/windows/" + windowId + "/settlements"));
        assertEquals(diag1, getOk("/api/channels/" + channel + "/evaporation-rates"));
        assertEquals(byAlloc1, getOk("/api/allocations/" + upstream + "/settlements"));
        assertTrue(diag1.get("rates").get(0).get("frozen").asBoolean());
    }

    @Test
    void settlementList404AndBatchWindowValidation() throws Exception {
        mvc.perform(get("/api/windows/999999999/settlements")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/missing-" + run + "/settlements")).andExpect(status().isNotFound());

        String channel = "ch-misc-" + run;
        long w1 = createWindow(channel + "-1", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        long w2 = createWindow(channel + "-2", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", "100");
        String otherWindowAllocation = submitApprove(w2, "u", "1", "alice");
        putRate(channel + "-1", "2026-10-16", "0");
        putRate(channel + "-2", "2026-10-16", "0");
        // 批量中引用其他窗口的上游 -> 整批 409 且定位明细 0
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("bc"));
        body.put("batchKey", key("bk"));
        body.put("windowId", w1);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(key("sk"), otherWindowAllocation, "2026-10-16T08:00:00Z", "0.1"));
        body.put("items", items);
        JsonNode err = error("POST", "/api/settlements/batch", body, "alice", 409);
        assertEquals("DIFFERENT_WINDOW", err.get("code").asText());
        assertEquals(0, err.get("itemIndex").asInt());
    }
}
