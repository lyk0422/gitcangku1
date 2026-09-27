package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配水传感异常 API 集成测试：全部跑在真实 H2 内存库（MODE=MySQL）上，验证真实事务、
 * 行锁裁决、唯一约束、回滚无半成品与命令幂等；不 mock 数据库边界。
 * 时间判定用固定的合成 UTC 时刻，裁决时间用可控固定时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeterApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterService meterService;
    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    @BeforeAll
    void fixClock() {
        meterService.setClock(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
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
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> ruleBody(String commandKey, String maxFlowRate, Integer maxIntervalMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("maxFlowRate", maxFlowRate);
        body.put("maxIntervalMinutes", maxIntervalMinutes);
        return body;
    }

    private Map<String, Object> readingsBody(String commandKey, String... pairs) {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.add(Map.of("readingUtc", pairs[i], "cumulativeVolume", pairs[i + 1]));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("readings", items);
        return body;
    }

    private Map<String, Object> resolveBody(String commandKey, String note, String utc, String cumulative) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("diagnosisNote", note);
        body.put("replacementReadingUtc", utc);
        body.put("replacementCumulativeVolume", cumulative);
        return body;
    }

    private String configure(String channel, String rate, int interval, String actor) throws Exception {
        String commandKey = key("rc");
        postOk("/api/channels/" + channel + "/rule", ruleBody(commandKey, rate, interval), actor);
        return commandKey;
    }

    private JsonNode channelView(String channel) throws Exception {
        return getOk("/api/channels/" + channel);
    }

    private long createWindowOnChannel(String channel, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channel);
        body.put("startUtc", "2026-11-01T00:00:00Z");
        body.put("endUtc", "2026-11-01T02:00:00Z");
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

    private int approveStatus(String allocationKey) {
        try {
            waterService.approveAllocation(key("ap"), allocationKey);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 渠道配置与规则版本
    // ------------------------------------------------------------------

    @Test
    void configureRegistersChannelAndAppendsVersions() throws Exception {
        String channel = "ch-rule-" + run;
        configure(channel, "1.500000", 15, "alice");
        JsonNode first = channelView(channel);
        assertFalse(first.get("frozen").asBoolean());
        assertEquals(0, first.get("activeAnomalyCount").asInt());
        assertEquals(1, first.get("currentRuleVersion").asInt());
        assertEquals("1.5", first.get("maxFlowRate").asText());
        assertEquals(15, first.get("maxIntervalMinutes").asInt());
        assertEquals("alice", first.get("createdBy").asText());
        assertEquals(1, first.get("versions").size());

        // 第二次配置只追加版本，历史版本不可变
        configure(channel, "2.000000", 20, "bob");
        JsonNode second = channelView(channel);
        assertEquals(2, second.get("currentRuleVersion").asInt());
        assertEquals(2, second.get("versions").size());
        JsonNode v1 = second.get("versions").get(0);
        assertEquals(1, v1.get("version").asInt());
        assertEquals("1.5", v1.get("maxFlowRate").asText());
        assertEquals(15, v1.get("maxIntervalMinutes").asInt());
        assertEquals("alice", v1.get("createdBy").asText());
        assertEquals(2, second.get("versions").get(1).get("version").asInt());
    }

    @Test
    void invalidRuleParamsReturn422AndDoNotRegisterChannel() throws Exception {
        String channel = "ch-rulebad-" + run;
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "0", 10), "alice", 422);
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "-1", 10), "alice", 422);
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "abc", 10), "alice", 422);
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "1", 0), "alice", 422);
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "1", null), "alice", 422);
        // 缺 X-Actor-Id -> 400
        postJson("/api/channels/" + channel + "/rule", ruleBody(key("rc"), "1", 10), null, 400);
        // 失败未注册渠道
        mvc.perform(get("/api/channels/" + channel)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 读数主流程与三类异常
    // ------------------------------------------------------------------

    @Test
    void firstReadingsAreAcceptedWithoutAnomalyAndRemainImmutable() throws Exception {
        String channel = "ch-ok-" + run;
        configure(channel, "1.000000", 10, "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "10"), "alice");
        assertEquals(1, result.get("ruleVersion").asInt());
        assertFalse(result.get("frozen").asBoolean());
        assertEquals(0, result.get("anomalies").size());
        assertEquals(2, result.get("readings").size());
        assertEquals(0, result.get("readings").get(0).get("batchSeq").asInt());
        assertEquals(1, result.get("readings").get(1).get("batchSeq").asInt());

        JsonNode list = getOk("/api/channels/" + channel + "/readings");
        assertEquals(2, list.get("readings").size());
        // 稳定排序：测量时刻升序
        assertEquals("2026-10-01T00:00:00Z", list.get("readings").get(0).get("readingUtc").asText());
        assertEquals("2026-10-01T00:10:00Z", list.get("readings").get(1).get("readingUtc").asText());
        // 重复读取不改状态
        JsonNode again = getOk("/api/channels/" + channel + "/readings");
        assertEquals(again, list);
    }

    @Test
    void flowRateAnomalyFreezesChannelWithActualRequiredAndDifference() throws Exception {
        String channel = "ch-flow-" + run;
        configure(channel, "0.900000", 60, "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T00:10:00Z", "20"),
                "alice");
        assertTrue(result.get("frozen").asBoolean());
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("FLOW_RATE", anomaly.get("anomalyType").asText());
        assertEquals(1, anomaly.get("ruleVersion").asInt());
        assertEquals("10", anomaly.get("volumeDelta").asText());
        assertEquals("10", anomaly.get("durationMinutes").asText());
        assertEquals("1", anomaly.get("actualFlowRate").asText());
        assertEquals("0.9", anomaly.get("requiredMaxFlowRate").asText());
        assertEquals(60, anomaly.get("requiredMaxIntervalMinutes").asInt());
        assertEquals("0.1", anomaly.get("flowRateExcess").asText());
        assertNull(anomaly.get("regressionAmount").asText(null));
        assertNull(anomaly.get("intervalExcessMinutes").asText(null));
        assertNull(anomaly.get("resolution").asText(null));
        assertEquals("alice", anomaly.get("submitter").asText());
        assertTrue(channelView(channel).get("frozen").asBoolean());
        assertEquals(1, channelView(channel).get("activeAnomalyCount").asInt());
    }

    @Test
    void regressionAndIntervalGapProduceTwoAnomalies() throws Exception {
        String channel = "ch-two-" + run;
        configure(channel, "999", 10, "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T01:00:00Z", "4"),
                "alice");
        assertTrue(result.get("frozen").asBoolean());
        assertEquals(2, result.get("anomalies").size());
        JsonNode regression = result.get("anomalies").get(0);
        JsonNode gap = result.get("anomalies").get(1);
        assertEquals("REGRESSION", regression.get("anomalyType").asText());
        assertEquals("-6", regression.get("volumeDelta").asText());
        assertEquals("6", regression.get("regressionAmount").asText());
        assertNull(regression.get("flowRateExcess").asText(null));
        assertEquals("INTERVAL_GAP", gap.get("anomalyType").asText());
        assertEquals("50", gap.get("intervalExcessMinutes").asText());
        assertNull(gap.get("regressionAmount").asText(null));
        // 历史查询稳定排序
        JsonNode history = getOk("/api/channels/" + channel + "/anomalies");
        assertEquals(2, history.get("anomalies").size());
        assertEquals("REGRESSION", history.get("anomalies").get(0).get("anomalyType").asText());
    }

    @Test
    void invalidSequenceRejectsWholeBatchBeforeAnyWrite() throws Exception {
        String channel = "ch-batch-" + run;
        configure(channel, "999", 60, "alice");
        // 末条时刻倒退：整批 422
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"),
                        "2026-10-01T00:00:00Z", "1",
                        "2026-10-01T00:10:00Z", "2",
                        "2026-10-01T00:09:00Z", "3"), "alice", 422);
        // 同刻 -> 422
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "1", "2026-10-01T00:00:00Z", "2"),
                "alice", 422);
        // 空批次、非法累计值 -> 422
        postJson("/api/channels/" + channel + "/readings",
                Map.of("commandKey", key("rd"), "readings", List.of()), "alice", 422);
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "-1"), "alice", 422);
        // 没有任何读数/异常写入，渠道未冻结
        assertEquals(0, getOk("/api/channels/" + channel + "/readings").get("readings").size());
        assertFalse(channelView(channel).get("frozen").asBoolean());
    }

    @Test
    void readingsMustContinueStrictlyAfterExistingHead() throws Exception {
        String channel = "ch-head-" + run;
        configure(channel, "999", 60, "alice");
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "1", "2026-10-01T00:10:00Z", "2"),
                "alice");
        // 早于链头 / 同刻 -> 422，不写入
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:09:00Z", "3"), "alice", 422);
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:10:00Z", "3"), "alice", 422);
        // 严格晚于链头 -> 成功
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:20:00Z", "3"), "alice");
        assertEquals(3, getOk("/api/channels/" + channel + "/readings").get("readings").size());
    }

    @Test
    void readingUnknownChannelIs404() throws Exception {
        String channel = "ch-missing-" + run;
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "1"), "alice", 404);
    }

    @Test
    void nanosecondPrecisionIsPreservedAndUsedInDuration() throws Exception {
        String channel = "ch-nano-" + run;
        configure(channel, "999", 60, "alice");
        String t0 = "2026-10-01T00:00:00.123456789Z";
        String t1 = "2026-10-01T00:00:10.123456789Z";
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), t0, "0", t1, "10"), "alice");
        assertEquals(t0, result.get("readings").get(0).get("readingUtc").asText());
        assertEquals(t1, result.get("readings").get(1).get("readingUtc").asText());
        // 明细与重放读取保持纳秒精度
        JsonNode listed = getOk("/api/channels/" + channel + "/readings").get("readings");
        assertEquals(t0, listed.get(0).get("readingUtc").asText());
        assertEquals(t1, listed.get(1).get("readingUtc").asText());
    }

    // ------------------------------------------------------------------
    // 冻结：核销拦截，转让放行
    // ------------------------------------------------------------------

    @Test
    void frozenChannelBlocksNewWriteOffButAllowsTransferRegistration() throws Exception {
        String channel = "ch-freeze-" + run;
        configure(channel, "0.900000", 60, "alice");
        long windowId = createWindowOnChannel(channel, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "2", "bob");
        // 冻结前完成源申请核销
        assertEquals(200, approveStatus(source));

        // 超速异常 -> 冻结
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "100"),
                "alice");

        // 冻结渠道不能完成新核销（普通批准）-> 422
        assertEquals(422, approveStatus(target));
        // 目标仍 REQUESTED，容量未变
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("6", cap.get("approvedTotal").asText());

        // 冻结后转让仍可登记（不新增核销总量）
        JsonNode transfer = postOk("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice");
        assertEquals(target, transfer.get("targetAllocationKey").asText());
        assertEquals("6", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 转让后目标虽 APPROVED 持有额度，渠道仍冻结（解冻前其额度本就通过转让登记，不涉及新核销）
        assertTrue(channelView(channel).get("frozen").asBoolean());
    }

    // ------------------------------------------------------------------
    // 异常解除：不同操作人 + 诊断说明 + 替代读数
    // ------------------------------------------------------------------

    @Test
    void resolveFlowAnomalyRequiresDifferentActorAndValidReplacement() throws Exception {
        String channel = "ch-resolve-" + run;
        configure(channel, "1.000000", 60, "alice");
        long windowId = createWindowOnChannel(channel, "10");
        String pending = submit(windowId, "user-1", "3", "bob");

        JsonNode submit = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "100"),
                "alice");
        long anomalyId = submit.get("anomalies").get(0).get("anomalyId").asLong();
        assertEquals(422, approveStatus(pending));

        String url = "/api/anomalies/" + anomalyId + "/resolve";
        // 同一操作人解除 -> 409
        postJson(url, resolveBody(key("rs"), "现场复核为气塞", "2026-10-01T00:05:00Z", "50"), "alice", 409);
        // 诊断说明为空 -> 422
        postJson(url, resolveBody(key("rs"), "  ", "2026-10-01T00:05:00Z", "50"), "bob", 422);
        // 替代时刻不居中 -> 422
        postJson(url, resolveBody(key("rs"), "现场复核为气塞", "2026-10-01T00:10:00Z", "50"), "bob", 422);
        postJson(url, resolveBody(key("rs"), "现场复核为气塞", "2026-10-01T00:00:00Z", "50"), "bob", 422);
        // 替代读数仍超速（5 分钟 100，速率 20 > 1）-> 422
        postJson(url, resolveBody(key("rs"), "现场复核为气塞", "2026-10-01T00:05:00Z", "100"), "bob", 422);
        // 替代值为负 -> 422
        postJson(url, resolveBody(key("rs"), "现场复核为气塞", "2026-10-01T00:05:00Z", "-1"), "bob", 422);
        // 异常不存在 -> 404
        postJson("/api/anomalies/999999999/resolve",
                resolveBody(key("rs"), "x", "2026-10-01T00:05:00Z", "1"), "bob", 404);

        // 全部失败尝试均未产生解除证据，渠道仍冻结
        JsonNode before = getOk("/api/anomalies/" + anomalyId);
        assertNull(before.get("resolution").asText(null));
        assertEquals(422, approveStatus(pending));

        // 不同操作人 + 合法替代读数 -> 解除
        JsonNode resolved = postOk(url,
                resolveBody(key("rs"), "现场复核为气塞，按替代读数修正", "2026-10-01T00:05:00Z", "5"), "bob");
        JsonNode resolution = resolved.get("resolution");
        assertNotNull(resolution);
        assertEquals("bob", resolution.get("resolver").asText());
        assertEquals("现场复核为气塞，按替代读数修正", resolution.get("diagnosisNote").asText());
        assertEquals("2026-10-01T00:05:00Z", resolution.get("replacementReadingUtc").asText());
        assertEquals("5", resolution.get("replacementCumulativeVolume").asText());

        // 原异常证据不改写
        assertEquals("alice", resolved.get("submitter").asText());
        assertEquals("100", resolved.get("readingCumulative").asText());
        assertEquals("FLOW_RATE", resolved.get("anomalyType").asText());
        assertEquals("100", resolved.get("volumeDelta").asText());

        // 渠道解冻，核销可完成；重复解除 -> 409
        assertFalse(channelView(channel).get("frozen").asBoolean());
        assertEquals(0, channelView(channel).get("activeAnomalyCount").asInt());
        assertEquals(200, approveStatus(pending));
        postJson(url, resolveBody(key("rs"), "再次确认", "2026-10-01T00:06:00Z", "6"), "carol", 409);
    }

    @Test
    void channelStaysFrozenUntilEveryAnomalyResolved() throws Exception {
        String channel = "ch-multi-" + run;
        configure(channel, "999", 10, "alice");
        long windowId = createWindowOnChannel(channel, "10");
        String pending = submit(windowId, "user-1", "1", "bob");

        // 倒退 + 超间隔 -> 两条异常
        JsonNode submit = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T01:00:00Z", "4"),
                "alice");
        long regressionId = submit.get("anomalies").get(0).get("anomalyId").asLong();
        long gapId = submit.get("anomalies").get(1).get("anomalyId").asLong();
        assertEquals(422, approveStatus(pending));

        // 只解除倒退：替代点本身必须干净（距前序不超过 10 分钟、不倒退、不超速）；渠道仍冻结
        postOk("/api/anomalies/" + regressionId + "/resolve",
                resolveBody(key("rs"), "倒退由表具清零导致", "2026-10-01T00:05:00Z", "10"), "bob");
        assertTrue(channelView(channel).get("frozen").asBoolean());
        assertEquals(1, channelView(channel).get("activeAnomalyCount").asInt());
        assertEquals(422, approveStatus(pending));

        // 再解除超间隔（替代读数距前序不超过 10 分钟）-> 全部解冻
        postOk("/api/anomalies/" + gapId + "/resolve",
                resolveBody(key("rs"), "补传缺失时段读数", "2026-10-01T00:08:00Z", "10.2"), "bob");
        assertFalse(channelView(channel).get("frozen").asBoolean());
        assertEquals(200, approveStatus(pending));

        // 历史证据：两条异常都带解除，排序稳定
        JsonNode history = getOk("/api/channels/" + channel + "/anomalies");
        assertEquals(2, history.get("anomalies").size());
        assertNotNull(history.get("anomalies").get(0).get("resolution"));
        assertNotNull(history.get("anomalies").get(1).get("resolution"));
    }

    @Test
    void resolutionDoesNotRewriteExistingWriteOffsOrReadings() throws Exception {
        String channel = "ch-evidence-" + run;
        configure(channel, "999", 60, "alice");
        long windowId = createWindowOnChannel(channel, "10");
        String approved = submit(windowId, "user-1", "3", "bob");
        assertEquals(200, approveStatus(approved));

        JsonNode submit = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T00:10:00Z", "4"),
                "alice");
        long anomalyId = submit.get("anomalies").get(0).get("anomalyId").asLong();
        postOk("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("rs"), "表具故障", "2026-10-01T00:05:00Z", "10"), "bob");

        // 既有核销保持 APPROVED、容量不变；原读数不被替代值改写
        assertEquals("APPROVED", getOk("/api/windows/" + windowId + "/history")
                .get("allocations").get(0).get("status").asText());
        assertEquals("3", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        JsonNode readings = getOk("/api/channels/" + channel + "/readings").get("readings");
        assertEquals(2, readings.size());
        assertEquals("4", readings.get(1).get("cumulativeVolume").asText());

        // 后续新读数仍从不可变的最新实际读数（4）续接：涨到 5 为正常
        JsonNode next = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:20:00Z", "5"), "carol");
        assertEquals(0, next.get("anomalies").size());
    }

    // ------------------------------------------------------------------
    // 幂等：快照重放、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void readingCommandReplaysFirstSnapshotAndRejectsDifferentParams() throws Exception {
        String channel = "ch-idem-" + run;
        configure(channel, "0.900000", 60, "alice");
        String commandKey = key("rd");
        Map<String, Object> body = readingsBody(commandKey,
                "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "100");
        JsonNode first = postOk("/api/channels/" + channel + "/readings", body, "alice");
        JsonNode replay = postOk("/api/channels/" + channel + "/readings", body, "alice");
        assertEquals(first, replay);
        // 读数与异常均只写入一次
        assertEquals(2, getOk("/api/channels/" + channel + "/readings").get("readings").size());
        assertEquals(1, getOk("/api/channels/" + channel + "/anomalies").get("anomalies").size());

        // 同键改参（改累计值、改操作者）-> 409
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "99"),
                "alice", 409);
        postJson("/api/channels/" + channel + "/readings", body, "bob", 409);
    }

    @Test
    void configureReplayReturnsFirstSnapshotEvenAfterStateMoves() throws Exception {
        String channel = "ch-cfgidem-" + run;
        String commandKey = configure(channel, "1.000000", 10, "alice");
        // 推进到版本 2 并制造冻结
        configure(channel, "2.000000", 20, "bob");
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "0", "2026-10-01T00:10:00Z", "100"),
                "carol");
        // 首次配置命令重放：返回首次成功响应快照（版本 1、未冻结）
        JsonNode replay = postOk("/api/channels/" + channel + "/rule",
                ruleBody(commandKey, "1.000000", 10), "alice");
        assertEquals(1, replay.get("currentRuleVersion").asInt());
        assertFalse(replay.get("frozen").asBoolean());
        // 当前真实状态已推进
        JsonNode current = channelView(channel);
        assertEquals(2, current.get("currentRuleVersion").asInt());
        assertTrue(current.get("frozen").asBoolean());
    }

    @Test
    void failedReadingCommandDoesNotOccupyKey() throws Exception {
        String channel = "ch-failidem-" + run;
        configure(channel, "999", 60, "alice");
        String commandKey = key("rd");
        // 非法序列失败 -> 不占键
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-10-01T00:10:00Z", "1", "2026-10-01T00:09:00Z", "2"),
                "alice", 422);
        // 同键可用于一组合法读数
        JsonNode ok = postOk("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, "2026-10-01T00:00:00Z", "1", "2026-10-01T00:10:00Z", "2"),
                "alice");
        assertEquals(2, ok.get("readings").size());
        assertEquals(2, getOk("/api/channels/" + channel + "/readings").get("readings").size());
    }

    @Test
    void resolveCommandReplaysAndRejectsDifferentParams() throws Exception {
        String channel = "ch-residem-" + run;
        configure(channel, "999", 60, "alice");
        JsonNode submit = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T00:10:00Z", "4"),
                "alice");
        long anomalyId = submit.get("anomalies").get(0).get("anomalyId").asLong();
        String commandKey = key("rs");
        Map<String, Object> body = resolveBody(commandKey, "表具故障", "2026-10-01T00:05:00Z", "10");
        JsonNode first = postOk("/api/anomalies/" + anomalyId + "/resolve", body, "bob");
        JsonNode replay = postOk("/api/anomalies/" + anomalyId + "/resolve", body, "bob");
        assertEquals(first, replay);
        // 同键改诊断说明 -> 409
        postJson("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(commandKey, "不同的诊断", "2026-10-01T00:05:00Z", "10"), "bob", 409);
        // 同键改解除人 -> 409
        postJson("/api/anomalies/" + anomalyId + "/resolve", body, "carol", 409);
    }

    // ------------------------------------------------------------------
    // 并发：按事务提交顺序裁决
    // ------------------------------------------------------------------

    @Test
    void anomalyAndWriteOffSettleByCommitOrder() throws Exception {
        String channel = "ch-race-" + run;
        configure(channel, "0.900000", 60, "alice");
        long windowId = createWindowOnChannel(channel, "10");
        String pending = submit(windowId, "user-1", "3", "bob");
        String readingCommand = key("rd");
        String approveCommand = key("ap");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Boolean> anomalyFuture = pool.submit(() -> {
            gate.await();
            meterService.submitReadings(readingCommand, channel, List.of(
                    new com.example.starter.water.dto.Dtos.ReadingItem("2026-10-01T00:00:00Z", "0"),
                    new com.example.starter.water.dto.Dtos.ReadingItem("2026-10-01T00:10:00Z", "100")),
                    "alice");
            return true;
        });
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, pending);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        anomalyFuture.get(30, TimeUnit.SECONDS);
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 异常必然存在（渠道最终冻结）；核销按提交顺序要么被拦截 422，要么先于异常成功
        assertTrue(approveStatus == 200 || approveStatus == 422, "approve=" + approveStatus);
        assertTrue(channelView(channel).get("frozen").asBoolean());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        String approvedTotal = cap.get("approvedTotal").asText();
        if (approveStatus == 200) {
            assertEquals("3", approvedTotal);
        } else {
            assertEquals("0", approvedTotal);
        }
        assertTrue(Double.parseDouble(approvedTotal)
                <= Double.parseDouble(cap.get("availableTotal").asText()));
        // 异常证据恰好一条
        assertEquals(1, getOk("/api/channels/" + channel + "/anomalies").get("anomalies").size());
    }

    @Test
    void concurrentReadingBatchesNeverProduceOutOfOrderOrPartialBatch() throws Exception {
        String channel = "ch-racebatch-" + run;
        configure(channel, "999", 60, "alice");
        // 两批首刻相同：渠道行锁串行后，后提交者在链头检查处 422，绝不依赖唯一约束报错，也无半成品
        String commandA = key("rd");
        String commandB = key("rd");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> submitStatus(gate, commandA, channel,
                "2026-10-01T00:00:00Z", "1", "2026-10-01T00:10:00Z", "2"));
        Future<Integer> fb = pool.submit(() -> submitStatus(gate, commandB, channel,
                "2026-10-01T00:00:00Z", "1", "2026-10-01T00:10:00Z", "2"));
        gate.countDown();
        int sa = fa.get(30, TimeUnit.SECONDS);
        int sb = fb.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (sa == 200 ? 1 : 0) + (sb == 200 ? 1 : 0), "同刻两批恰好一批成功: " + sa + "/" + sb);
        assertEquals(1, (sa == 422 ? 1 : 0) + (sb == 422 ? 1 : 0));
        // 成功批次完整写入两条，无重复时刻、无半成品
        JsonNode readings = getOk("/api/channels/" + channel + "/readings").get("readings");
        assertEquals(2, readings.size());
        assertEquals("2026-10-01T00:00:00Z", readings.get(0).get("readingUtc").asText());
        assertEquals("2026-10-01T00:10:00Z", readings.get(1).get("readingUtc").asText());
        // 失败批次不占键：其 commandKey 可用于另一组合法读数
        String failedCommand = sa == 422 ? commandA : commandB;
        JsonNode retry = postOk("/api/channels/" + channel + "/readings",
                readingsBody(failedCommand, "2026-10-01T00:20:00Z", "3"), "alice");
        assertEquals(1, retry.get("readings").size());
    }

    @Test
    void concurrentResolutionsOfSameAnomalySucceedAtMostOnce() throws Exception {
        String channel = "ch-raceresolve-" + run;
        configure(channel, "999", 60, "alice");
        JsonNode submit = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rd"), "2026-10-01T00:00:00Z", "10", "2026-10-01T00:10:00Z", "4"),
                "alice");
        long anomalyId = submit.get("anomalies").get(0).get("anomalyId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> resolveStatus(gate, key("rs"), anomalyId, "bob"));
        Future<Integer> f2 = pool.submit(() -> resolveStatus(gate, key("rs"), anomalyId, "carol"));
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一个解除成功，另一个因异常已解除被拒 409
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "resolve=" + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        // 只有一条解除证据，渠道解冻
        JsonNode anomaly = getOk("/api/anomalies/" + anomalyId);
        assertNotNull(anomaly.get("resolution"));
        assertFalse(channelView(channel).get("frozen").asBoolean());
    }

    private Integer resolveStatus(CountDownLatch gate, String commandKey, long anomalyId, String resolver) {
        try {
            gate.await();
            meterService.resolveAnomaly(commandKey, anomalyId, resolver, "并发解除诊断",
                    "2026-10-01T00:05:00Z", "10");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Integer submitStatus(CountDownLatch gate, String commandKey, String channel, String... pairs) {        try {
            gate.await();
            List<com.example.starter.water.dto.Dtos.ReadingItem> items = new ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) {
                items.add(new com.example.starter.water.dto.Dtos.ReadingItem(pairs[i], pairs[i + 1]));
            }
            meterService.submitReadings(commandKey, channel, items, "alice");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
