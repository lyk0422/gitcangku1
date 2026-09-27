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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配水计量传感异常与实际核销 API 集成测试：主流程、失败分支、幂等、事务与并发边界。
 * 所有时刻为 ISO-8601 UTC 字符串。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SensorAnomalyApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

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
        String json = postJson(url, body, actor, 200).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 并发场景使用：不校验状态，直接返回 HTTP 状态码。 */
    private int postStatus(String url, Map<String, Object> body, String actor) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode getStatus(String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // 业务辅助
    // ------------------------------------------------------------------

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-03-01T00:00:00Z");
        body.put("endUtc", "2026-03-02T00:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        JsonNode node = postOk("/api/allocations", body, actor);
        return node.get("allocationKey").asText();
    }

    private void approve(String allocationKey) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (node.get("allocationKey").asText().equals(allocationKey)) {
                return node;
            }
        }
        throw new AssertionError("allocation not found: " + allocationKey);
    }

    private Map<String, Object> ruleBody(String commandKey, String maxFlowRate, Long maxIntervalMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("maxFlowRate", maxFlowRate);
        body.put("maxIntervalMinutes", maxIntervalMinutes);
        return body;
    }

    private JsonNode configureRule(String channelId, String maxFlowRate, long maxIntervalMinutes,
                                   String actor) throws Exception {
        return postOk("/api/channels/" + channelId + "/rules",
                ruleBody(key("rc"), maxFlowRate, maxIntervalMinutes), actor);
    }

    private Map<String, Object> readingsBody(String commandKey, String... observedAndValues) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        List<Map<String, Object>> readings = new ArrayList<>();
        for (int i = 0; i < observedAndValues.length; i += 2) {
            Map<String, Object> item = new HashMap<>();
            item.put("observedUtc", observedAndValues[i]);
            item.put("cumulativeValue", observedAndValues[i + 1]);
            readings.add(item);
        }
        body.put("readings", readings);
        return body;
    }

    private JsonNode submitReadingsOk(String channelId, String actor, String... observedAndValues)
            throws Exception {
        return postOk("/api/channels/" + channelId + "/readings",
                readingsBody(key("rd"), observedAndValues), actor);
    }

    private Map<String, Object> releaseBody(String commandKey, String diagnosis, String substituteValue,
                                            String substituteObservedUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("diagnosis", diagnosis);
        body.put("substituteValue", substituteValue);
        body.put("substituteObservedUtc", substituteObservedUtc);
        return body;
    }

    private Map<String, Object> settleBody(String commandKey, String settlementKey, String allocationKey,
                                           String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("settlementKey", settlementKey);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        return body;
    }

    private JsonNode settleOk(String allocationKey, String amount, String actor) throws Exception {
        return postOk("/api/settlements", settleBody(key("sc"), key("sk"), allocationKey, amount), actor);
    }

    private JsonNode channelStatus(String channelId) throws Exception {
        return getOk("/api/channels/" + channelId + "/status");
    }

    // ------------------------------------------------------------------
    // 规则配置
    // ------------------------------------------------------------------

    @Test
    void ruleConfigValidationReturns422AndVersionsIncrement() throws Exception {
        String channel = key("ch-rule");
        // 上限非法：零、负数、非数字、过多小数位
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "0", 30L), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "-1", 30L), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "abc", 30L), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "1.0001", 30L), "op", 422);
        // 间隔非法：0、负数、缺失、超界
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "2.5", 0L), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "2.5", -5L), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "2.5", null), "op", 422);
        postJson("/api/channels/" + channel + "/rules", ruleBody(key("rc"), "2.5", 5256001L), "op", 422);
        // 失败不占键：上述 commandKey 均不阻塞后续同名键之外的命令，渠道仍未登记
        getStatus("/api/channels/" + channel + "/status", 404);

        JsonNode v1 = configureRule(channel, "2.5", 30, "op");
        assertEquals(1, v1.get("version").asInt());
        assertEquals("2.5", v1.get("maxFlowRate").asText());
        assertEquals(30, v1.get("maxIntervalMinutes").asLong());
        assertEquals("op", v1.get("actor").asText());

        JsonNode v2 = configureRule(channel, "3", 45, "op");
        assertEquals(2, v2.get("version").asInt());

        JsonNode rules = getOk("/api/channels/" + channel + "/rules");
        assertEquals(2, rules.get("currentVersion").asInt());
        assertEquals(2, rules.get("versions").size());
        assertEquals(1, rules.get("versions").get(0).get("version").asInt());
        assertEquals(2, rules.get("versions").get(1).get("version").asInt());
        // 历史版本不可变：v1 仍保持原参数
        assertEquals("2.5", rules.get("versions").get(0).get("maxFlowRate").asText());
        assertEquals("3", rules.get("versions").get(1).get("maxFlowRate").asText());
    }

    // ------------------------------------------------------------------
    // 读数提交与查询
    // ------------------------------------------------------------------

    @Test
    void readingsMainFlowAndStableOrdering() throws Exception {
        String channel = key("ch-read");
        configureRule(channel, "10", 60, "op");

        JsonNode result = submitReadingsOk(channel, "sensor-a",
                "2026-03-01T00:00:00Z", "0",
                "2026-03-01T00:10:00Z", "5",
                "2026-03-01T00:20:00Z", "10.5");
        assertEquals(3, result.get("submitted").asInt());
        assertFalse(result.get("frozen").asBoolean());
        assertEquals(0, result.get("anomalies").size());
        assertEquals(3, result.get("readings").size());

        JsonNode list = getOk("/api/channels/" + channel + "/readings");
        assertEquals(3, list.get("readings").size());
        assertEquals("2026-03-01T00:00:00Z", list.get("readings").get(0).get("observedUtc").asText());
        assertEquals("2026-03-01T00:10:00Z", list.get("readings").get(1).get("observedUtc").asText());
        assertEquals("10.5", list.get("readings").get(2).get("cumulativeValue").asText());
        assertEquals("sensor-a", list.get("readings").get(0).get("actor").asText());
        assertNotNull(list.get("readings").get(0).get("createdUtc").asText());

        JsonNode status = channelStatus(channel);
        assertFalse(status.get("frozen").asBoolean());
        assertEquals(0, status.get("openAnomalyCount").asInt());
        assertEquals(1, status.get("currentRuleVersion").asInt());

        getStatus("/api/channels/ch-unknown-" + run + "/readings", 404);
        getStatus("/api/channels/ch-unknown-" + run + "/anomalies", 404);
    }

    @Test
    void invalidReadingsBatchWritesNothingAndFailedKeyIsReusable() throws Exception {
        String channel = key("ch-atomic");
        configureRule(channel, "10", 60, "op");

        // 读数值非法：整批不写入
        JsonNode err = objectMapper.readTree(postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-03-01T00:00:00Z", "1", "2026-03-01T00:01:00Z", "abc"),
                "sensor-a", 422).getResponse().getContentAsString());
        assertEquals("INVALID_READING_VALUE", err.get("code").asText());
        assertEquals(0, getOk("/api/channels/" + channel + "/readings").get("readings").size());

        // 批内时刻不递增（相等）
        JsonNode err2 = objectMapper.readTree(postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-03-01T00:00:00Z", "1", "2026-03-01T00:00:00Z", "2"),
                "sensor-a", 422).getResponse().getContentAsString());
        assertEquals("READING_TIME_NOT_INCREASING", err2.get("code").asText());
        // 批内时刻倒退
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-03-01T00:01:00Z", "1", "2026-03-01T00:00:00Z", "2"),
                "sensor-a", 422);
        assertEquals(0, getOk("/api/channels/" + channel + "/readings").get("readings").size());

        // 失败不占键：同一 commandKey 修正参数后成功
        String commandKey = key("rd");
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-03-01T00:00:00Z", "1", "2026-03-01T00:00:00Z", "2"),
                "sensor-a", 422);
        JsonNode ok = postOk("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-03-01T00:00:00Z", "1"), "sensor-a");
        assertEquals(1, ok.get("submitted").asInt());

        // 与已有计量点相比时刻不递增
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-03-01T00:00:00Z", "2"), "sensor-a", 422);
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-02-28T23:59:00Z", "2"), "sensor-a", 422);
        assertEquals(1, getOk("/api/channels/" + channel + "/readings").get("readings").size());
    }

    // ------------------------------------------------------------------
    // 异常生成、冻结与解除
    // ------------------------------------------------------------------

    @Test
    void regressionAnomalyFreezesSettlementUntilReleasedByDifferentOperator() throws Exception {
        String channel = key("ch-freeze");
        long windowId = createWindow(channel, "100");
        String source = submitAllocation(windowId, "user-1", "10", "alice");
        approve(source);
        configureRule(channel, "100", 6000, "op");

        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "100");
        JsonNode result = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:01:00Z", "50");
        assertTrue(result.get("frozen").asBoolean());
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        long anomalyId = anomaly.get("id").asLong();
        assertEquals("REGRESSION", anomaly.get("type").asText());
        assertEquals("50", anomaly.get("actualValue").asText());
        assertEquals("100", anomaly.get("requiredValue").asText());
        assertEquals("-50", anomaly.get("deltaValue").asText());
        assertEquals(1, anomaly.get("ruleVersion").asInt());
        assertEquals("sensor-a", anomaly.get("readingActor").asText());
        assertFalse(anomaly.get("released").asBoolean());

        JsonNode status = channelStatus(channel);
        assertTrue(status.get("frozen").asBoolean());
        assertEquals(1, status.get("openAnomalyCount").asInt());

        // 冻结拦截新核销
        JsonNode frozen = objectMapper.readTree(postJson("/api/settlements",
                settleBody(key("sc"), key("sk"), source, "1"), "alice", 409)
                .getResponse().getContentAsString());
        assertEquals("CHANNEL_FROZEN", frozen.get("code").asText());

        // 冻结后转让仍可登记
        String target = submitAllocation(windowId, "user-2", "3", "bob");
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", target);
        postJson("/api/transfers", transferBody, "alice", 200);

        // 同一操作人不能解除自己触发的异常
        postJson("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "传感器漂移", "80", "2026-03-01T00:01:00Z"), "sensor-a", 409);

        // 不同操作人确认诊断说明与替代读数后解除
        JsonNode release = postOk("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "传感器漂移，已现场校准", "80", "2026-03-01T00:01:00Z"), "operator-b");
        assertEquals(anomalyId, release.get("anomalyId").asLong());
        assertFalse(release.get("channelFrozen").asBoolean());
        assertEquals("operator-b", release.get("actor").asText());
        assertFalse(channelStatus(channel).get("frozen").asBoolean());

        // 解除不改写原异常：诊断查询中异常证据保持原值，解除记录可查
        JsonNode anomalies = getOk("/api/channels/" + channel + "/anomalies");
        assertEquals(1, anomalies.get("anomalies").size());
        JsonNode detail = anomalies.get("anomalies").get(0);
        assertEquals("50", detail.get("anomaly").get("actualValue").asText());
        assertEquals("100", detail.get("anomaly").get("requiredValue").asText());
        assertTrue(detail.get("anomaly").get("released").asBoolean());
        assertEquals("传感器漂移，已现场校准", detail.get("release").get("diagnosis").asText());
        assertEquals("80", detail.get("release").get("substituteValue").asText());

        // 解除后核销恢复：源持有 10 - 3（转让）= 7，核销 4 余 3
        JsonNode settlement = settleOk(source, "4", "alice");
        assertEquals("4", settlement.get("amount").asText());
        assertEquals("3", settlement.get("remainingHeld").asText());
        assertEquals(channel, settlement.get("channelId").asText());
        assertEquals("3", allocation(windowId, source).get("heldAmount").asText());

        JsonNode settlements = getOk("/api/allocations/" + source + "/settlements");
        assertEquals(1, settlements.get("settlements").size());
        assertEquals("4", settlements.get("settlements").get(0).get("amount").asText());
    }

    @Test
    void flowExceededAnomalyCarriesActualRequiredAndDelta() throws Exception {
        String channel = key("ch-flow");
        configureRule(channel, "1", 6000, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "0");
        JsonNode result = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:10Z", "20");
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("FLOW_EXCEEDED", anomaly.get("type").asText());
        // 实际流量 20 / 10s = 2 立方米/秒，要求上限 1，差额 1
        assertEquals("2", anomaly.get("actualValue").asText());
        assertEquals("1", anomaly.get("requiredValue").asText());
        assertEquals("1", anomaly.get("deltaValue").asText());
        assertTrue(channelStatus(channel).get("frozen").asBoolean());
    }

    @Test
    void intervalExceededAnomalyCarriesActualRequiredAndDelta() throws Exception {
        String channel = key("ch-gap");
        configureRule(channel, "1000", 1, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "0");
        JsonNode result = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:02:00Z", "0.001");
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("INTERVAL_EXCEEDED", anomaly.get("type").asText());
        // 实际间隔 2 分钟，要求上限 1 分钟，差额 1
        assertEquals("2", anomaly.get("actualValue").asText());
        assertEquals("1", anomaly.get("requiredValue").asText());
        assertEquals("1", anomaly.get("deltaValue").asText());
    }

    @Test
    void releaseValidationFailures() throws Exception {
        String channel = key("ch-rel");
        configureRule(channel, "100", 6000, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "100");
        JsonNode result = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:01:00Z", "50");
        long anomalyId = result.get("anomalies").get(0).get("id").asLong();

        // 异常不存在
        postJson("/api/anomalies/99999999/release",
                releaseBody(key("rl"), "诊断", "80", "2026-03-01T00:01:00Z"), "operator-b", 404);
        // 诊断说明缺失
        postJson("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), " ", "80", "2026-03-01T00:01:00Z"), "operator-b", 400);
        // 替代读数非法
        postJson("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "诊断", "abc", "2026-03-01T00:01:00Z"), "operator-b", 422);
        // 替代读数时刻早于触发读数时刻
        JsonNode early = objectMapper.readTree(postJson("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "诊断", "80", "2026-03-01T00:00:30Z"), "operator-b", 422)
                .getResponse().getContentAsString());
        assertEquals("SUBSTITUTE_TIME_TOO_EARLY", early.get("code").asText());
        // 渠道仍未解除
        assertTrue(channelStatus(channel).get("frozen").asBoolean());

        postOk("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "诊断", "80", "2026-03-01T00:01:00Z"), "operator-b");
        // 重复解除
        JsonNode again = objectMapper.readTree(postJson("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "再次解除", "80", "2026-03-01T00:01:00Z"), "operator-c", 409)
                .getResponse().getContentAsString());
        assertEquals("ANOMALY_ALREADY_RELEASED", again.get("code").asText());
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    @Test
    void settlementFailuresAndHistory() throws Exception {
        String channel = key("ch-settle");
        long windowId = createWindow(channel, "100");
        String pending = submitAllocation(windowId, "user-1", "5", "alice");

        // 申请不存在
        postJson("/api/settlements", settleBody(key("sc"), key("sk"), "ak-none-" + run, "1"), "alice", 404);
        // 非 APPROVED 状态
        JsonNode notApproved = objectMapper.readTree(postJson("/api/settlements",
                settleBody(key("sc"), key("sk"), pending, "1"), "alice", 409)
                .getResponse().getContentAsString());
        assertEquals("ALLOCATION_NOT_APPROVED", notApproved.get("code").asText());
        // 水量格式非法
        postJson("/api/settlements", settleBody(key("sc"), key("sk"), pending, "abc"), "alice", 400);

        approve(pending);
        // 超过持有额度：响应含实际值、要求值与差额
        JsonNode exceed = objectMapper.readTree(postJson("/api/settlements",
                settleBody(key("sc"), key("sk"), pending, "6"), "alice", 422)
                .getResponse().getContentAsString());
        assertEquals("SETTLEMENT_EXCEEDS_HELD", exceed.get("code").asText());
        assertTrue(exceed.get("message").asText().contains("6"));
        assertTrue(exceed.get("message").asText().contains("5"));

        // 部分核销：持有 5 - 2 = 3
        JsonNode first = settleOk(pending, "2", "alice");
        assertEquals("3", first.get("remainingHeld").asText());

        // settlementKey 复用（换 commandKey）返回 409，额度无变化
        String settleKey = key("sk");
        postOk("/api/settlements", settleBody(key("sc"), settleKey, pending, "1"), "alice");
        JsonNode reused = objectMapper.readTree(postJson("/api/settlements",
                settleBody(key("sc"), settleKey, pending, "1"), "alice", 409)
                .getResponse().getContentAsString());
        assertEquals("SETTLEMENT_KEY_REUSED", reused.get("code").asText());
        assertEquals("2", allocation(windowId, pending).get("heldAmount").asText());

        // 全额核销剩余 2
        JsonNode full = settleOk(pending, "2", "alice");
        assertEquals("0", full.get("remainingHeld").asText());
        assertEquals("0", allocation(windowId, pending).get("heldAmount").asText());

        // 核销历史按提交顺序稳定返回
        JsonNode settlements = getOk("/api/allocations/" + pending + "/settlements");
        assertEquals(3, settlements.get("settlements").size());
        assertEquals("2", settlements.get("settlements").get(0).get("amount").asText());
        assertEquals("1", settlements.get("settlements").get(1).get("amount").asText());
        assertEquals("2", settlements.get("settlements").get(2).get("amount").asText());
        getStatus("/api/allocations/ak-none-" + run + "/settlements", 404);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotentReplayReturnsSnapshotAndParamChangeConflicts() throws Exception {
        String channel = key("ch-idem");
        configureRule(channel, "10", 60, "op");

        // 读数命令：同键同参重放返回首次完整快照
        String commandKey = key("rd");
        Map<String, Object> body = readingsBody(commandKey, "2026-03-01T00:00:00Z", "1");
        String first = postJson("/api/channels/" + channel + "/readings", body, "sensor-a", 200)
                .getResponse().getContentAsString();
        String replay = postJson("/api/channels/" + channel + "/readings", body, "sensor-a", 200)
                .getResponse().getContentAsString();
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/channels/" + channel + "/readings").get("readings").size());
        // 同键异参 409
        JsonNode conflict = objectMapper.readTree(postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-03-01T00:01:00Z", "2"), "sensor-a", 409)
                .getResponse().getContentAsString());
        assertEquals("COMMAND_KEY_REUSED", conflict.get("code").asText());
        // 同键同人但参数不同也 409；同参不同人同样 409（指纹含操作者）
        postJson("/api/channels/" + channel + "/readings", body, "sensor-b", 409);

        // 核销命令：重放返回首次响应快照（remainingHeld 不随后续核销变化）
        long windowId = createWindow(channel, "100");
        String allocation = submitAllocation(windowId, "user-1", "10", "alice");
        approve(allocation);
        String settleCommand = key("sc");
        Map<String, Object> settleBody = settleBody(settleCommand, key("sk"), allocation, "4");
        String settleFirst = postJson("/api/settlements", settleBody, "alice", 200)
                .getResponse().getContentAsString();
        settleOk(allocation, "1", "alice");
        String settleReplay = postJson("/api/settlements", settleBody, "alice", 200)
                .getResponse().getContentAsString();
        assertEquals(settleFirst, settleReplay);
        assertEquals("6", objectMapper.readTree(settleReplay).get("remainingHeld").asText());
        assertEquals("5", allocation(windowId, allocation).get("heldAmount").asText());
        // 同键异参 409
        postJson("/api/settlements", settleBody(settleCommand, key("sk"), allocation, "2"), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 替代读数基线与规则版本
    // ------------------------------------------------------------------

    @Test
    void substituteReadingBecomesBaselineAfterRelease() throws Exception {
        String channel = key("ch-sub");
        configureRule(channel, "1", 6000, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "100");
        JsonNode anomalyResult = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:10Z", "50");
        long anomalyId = anomalyResult.get("anomalies").get(0).get("id").asLong();
        postOk("/api/anomalies/" + anomalyId + "/release",
                releaseBody(key("rl"), "仪表故障，以人工抄表值替代", "80", "2026-03-01T00:00:10Z"),
                "operator-b");

        // 基线为替代值 80：81 - 80 = 1，10 秒流量 0.1 不超限；若以原读数 50 为基线则流量 3.1 超限
        JsonNode next = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:20Z", "81");
        assertEquals(0, next.get("anomalies").size());
        assertFalse(channelStatus(channel).get("frozen").asBoolean());
        assertEquals(1, getOk("/api/channels/" + channel + "/anomalies").get("anomalies").size());
    }

    @Test
    void latestRuleVersionAppliesToNewReadings() throws Exception {
        String channel = key("ch-ver");
        configureRule(channel, "1000", 60, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "0");
        // 间隔上限收紧为 1 分钟（版本 2）
        configureRule(channel, "1000", 1, "op");
        JsonNode result = submitReadingsOk(channel, "sensor-a", "2026-03-01T00:30:00Z", "1");
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("INTERVAL_EXCEEDED", anomaly.get("type").asText());
        assertEquals(2, anomaly.get("ruleVersion").asInt());
        assertEquals("30", anomaly.get("actualValue").asText());
        assertEquals("1", anomaly.get("requiredValue").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsNeverExceedHeld() throws Exception {
        String channel = key("ch-race-settle");
        long windowId = createWindow(channel, "100");
        String allocation = submitAllocation(windowId, "user-1", "5", "alice");
        approve(allocation);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return postStatus("/api/settlements", settleBody(key("sc"), key("sk"), allocation, "4"),
                    "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return postStatus("/api/settlements", settleBody(key("sc"), key("sk"), allocation, "4"),
                    "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "并发核销: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        // 持有额度 5 - 4 = 1，绝不为负
        assertEquals("1", allocation(windowId, allocation).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocation + "/settlements")
                .get("settlements").size());
    }

    @Test
    void anomalyAndSettlementAdjudicatedBySubmissionOrder() throws Exception {
        String channel = key("ch-race-anom");
        long windowId = createWindow(channel, "100");
        String allocation = submitAllocation(windowId, "user-1", "10", "alice");
        approve(allocation);
        configureRule(channel, "100", 6000, "op");
        submitReadingsOk(channel, "sensor-a", "2026-03-01T00:00:00Z", "100");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> readingsFuture = pool.submit(() -> {
            gate.await();
            return postStatus("/api/channels/" + channel + "/readings",
                    readingsBody(key("rd"), "2026-03-01T00:01:00Z", "50"), "sensor-a");
        });
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            return postStatus("/api/settlements", settleBody(key("sc"), key("sk"), allocation, "4"),
                    "alice");
        });
        gate.countDown();
        int readingsStatus = readingsFuture.get(30, TimeUnit.SECONDS);
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 读数提交总是成功并生成异常冻结
        assertEquals(200, readingsStatus);
        assertTrue(channelStatus(channel).get("frozen").asBoolean());
        if (settleStatus == 200) {
            // 核销先提交：冻结前已完成，持有额度扣减
            assertEquals("6", allocation(windowId, allocation).get("heldAmount").asText());
            assertEquals(1, getOk("/api/allocations/" + allocation + "/settlements")
                    .get("settlements").size());
        } else {
            // 异常先提交：同刻后核销被拦截
            assertEquals(409, settleStatus);
            assertEquals("10", allocation(windowId, allocation).get("heldAmount").asText());
            assertEquals(0, getOk("/api/allocations/" + allocation + "/settlements")
                    .get("settlements").size());
        }
    }
}
