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
 * 配水传感异常 API 集成测试：渠道规则版本、累计读数、三类 ANOMALY 与冻结、
 * 不同操作人解除、冻结期转让放行/核销拦截、批量原子校验、幂等、并发同刻裁决与只读证据。
 * 全部走 H2 内存库（MODE=MySQL）真实事务、唯一约束与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SensorAnomalyApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private JdbcTemplate jdbc;

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

    private Map<String, Object> readingsBody(String commandKey, String channelId,
                                             String[][] atAndValue) {
        List<Map<String, Object>> readings = new ArrayList<>();
        for (String[] pair : atAndValue) {
            Map<String, Object> item = new HashMap<>();
            item.put("readingAtUtc", pair[0]);
            item.put("cumulativeReading", pair[1]);
            readings.add(item);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("channelId", channelId);
        body.put("readings", readings);
        return body;
    }

    /** 配置渠道规则并返回版本视图。 */
    private JsonNode configure(String channelId, String maxFlowRate, String maxIntervalMinutes,
                               String effectiveFrom, String actor) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("cc"));
        body.put("channelId", channelId);
        body.put("maxFlowRate", maxFlowRate);
        body.put("maxIntervalMinutes", maxIntervalMinutes);
        body.put("effectiveFromUtc", effectiveFrom);
        return postOk("/api/channels/" + channelId + "/config", body, actor);
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-11-10T00:00:00Z");
        body.put("endUtc", "2026-11-10T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitApproved(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    private String submitRequested(long windowId, String userId, String amount, String actor)
            throws Exception {
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

    private Map<String, Object> writeoffBody(String commandKey, String writeoffKey,
                                             String allocationKey, String amount, String atUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeoffKey", writeoffKey);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        body.put("atUtc", atUtc);
        return body;
    }

    private int count(String table, String column, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 规则版本
    // ------------------------------------------------------------------

    @Test
    void ruleConfigsAreImmutableVersionedAndApplyByEffectiveTime() throws Exception {
        String channel = "ch-rule-" + run;
        JsonNode v1 = configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        assertEquals(1, v1.get("version").asInt());
        assertEquals("10", v1.get("maxFlowRate").asText());
        assertEquals("60", v1.get("maxIntervalMinutes").asText());
        JsonNode v2 = configure(channel, "20", "30.000", "2026-11-10T10:45:00Z", "bob");
        assertEquals(2, v2.get("version").asInt());
        assertEquals("20", v2.get("maxFlowRate").asText());
        assertEquals("30", v2.get("maxIntervalMinutes").asText());

        // 10:00 与 10:30 适用 v1（流量 8≤10、间隔 30≤60 均不触异常）；
        // 11:00 适用 v2（流量 8≤20、间隔 30≤30 边界不触异常）
        JsonNode ok = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "4"},
                        {"2026-11-10T11:00:00Z", "8"}}), "alice");
        assertEquals(3, ok.get("storedReadings").size());
        assertEquals(0, ok.get("anomalies").size());
        assertEquals(1, ok.get("storedReadings").get(0).get("ruleVersion").asInt());
        assertEquals(1, ok.get("storedReadings").get(1).get("ruleVersion").asInt());
        assertEquals(2, ok.get("storedReadings").get(2).get("ruleVersion").asInt());

        // 历史规则版本按版本号升序且字段不可变
        JsonNode history = getOk("/api/channels/" + channel + "/history");
        assertEquals(2, history.get("ruleVersions").size());
        assertEquals(1, history.get("ruleVersions").get(0).get("version").asInt());
        assertEquals("10", history.get("ruleVersions").get(0).get("maxFlowRate").asText());
        assertEquals("20", history.get("ruleVersions").get(1).get("maxFlowRate").asText());
    }

    @Test
    void invalidRuleParamsReturn422() throws Exception {
        String channel = "ch-badrule-" + run;
        String[][] badRules = {
                {"0", "60"}, {"-1", "60"}, {"1.0000001", "60"}, {"abc", "60"},
                {"10", "0"}, {"10", "-5"}, {"10", "1.0001"},
        };
        for (String[] rule : badRules) {
            Map<String, Object> body = new HashMap<>();
            body.put("commandKey", key("cc"));
            body.put("channelId", channel);
            body.put("maxFlowRate", rule[0]);
            body.put("maxIntervalMinutes", rule[1]);
            body.put("effectiveFromUtc", "2026-11-01T00:00:00Z");
            MvcResult result = postJson("/api/channels/" + channel + "/config", body, "alice", 422);
            JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals("INVALID_RULE_PARAM", error.get("code").asText());
        }
        // 全部失败后没有任何版本
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_config WHERE channel_id = ?", Integer.class, channel));
    }

    // ------------------------------------------------------------------
    // 三类异常主流程
    // ------------------------------------------------------------------

    @Test
    void flowExceededCreatesAnomalyWithActualRequiredDifference() throws Exception {
        String channel = "ch-flow-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "6"}}), "alice");
        assertEquals(2, result.get("storedReadings").size());
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("FLOW_EXCEEDED", anomaly.get("type").asText());
        assertEquals(1, anomaly.get("ruleVersion").asInt());
        assertEquals("2026-11-10T10:00:00Z", anomaly.get("windowStartUtc").asText());
        assertEquals("2026-11-10T10:30:00Z", anomaly.get("windowEndUtc").asText());
        assertEquals("2026-11-10T10:00:00Z", anomaly.get("previousReadingAtUtc").asText());
        assertEquals("0", anomaly.get("previousValue").asText());
        assertEquals("2026-11-10T10:30:00Z", anomaly.get("readingAtUtc").asText());
        assertEquals("6", anomaly.get("readingValue").asText());
        // 实际 12 m3/h、要求 10、差额 2
        assertEquals("12", anomaly.get("actualValue").asText());
        assertEquals("10", anomaly.get("requiredValue").asText());
        assertEquals("2", anomaly.get("difference").asText());
        assertEquals("FLOW_M3_PER_H", anomaly.get("metric").asText());
        assertEquals("alice", anomaly.get("reporter").asText());
        assertEquals("OPEN", anomaly.get("status").asText());
        assertNull(anomaly.get("resolutionId").asText(null));
        assertTrue(anomaly.get("resolution").isNull());
    }

    @Test
    void regressionCreatesAnomalyAndDoesNotEvaluateFlow() throws Exception {
        String channel = "ch-reg-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "10"},
                        {"2026-11-10T10:30:00Z", "8"}}), "alice");
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("REGRESSION", anomaly.get("type").asText());
        assertEquals("8", anomaly.get("actualValue").asText());
        assertEquals("10", anomaly.get("requiredValue").asText());
        assertEquals("2", anomaly.get("difference").asText());
        assertEquals("CUMULATIVE_M3", anomaly.get("metric").asText());
    }

    @Test
    void intervalExceededCreatesAnomalyInMinutes() throws Exception {
        String channel = "ch-int-" + run;
        configure(channel, "10", "30", "2026-11-01T00:00:00Z", "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T11:00:00Z", "5"}}), "alice");
        assertEquals(1, result.get("anomalies").size());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals("INTERVAL_EXCEEDED", anomaly.get("type").asText());
        // 实际间隔 60 分钟、要求 ≤30、差额 30；流量 5 不超限
        assertEquals("60", anomaly.get("actualValue").asText());
        assertEquals("30", anomaly.get("requiredValue").asText());
        assertEquals("30", anomaly.get("difference").asText());
        assertEquals("INTERVAL_MINUTES", anomaly.get("metric").asText());
    }

    @Test
    void regressionAndIntervalCanCoexistOnSameReading() throws Exception {
        String channel = "ch-both-" + run;
        configure(channel, "10", "10", "2026-11-01T00:00:00Z", "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "10"},
                        {"2026-11-10T10:30:00Z", "8"}}), "alice");
        assertEquals(2, result.get("anomalies").size());
        assertEquals("REGRESSION", result.get("anomalies").get(0).get("type").asText());
        assertEquals("INTERVAL_EXCEEDED", result.get("anomalies").get(1).get("type").asText());
    }

    @Test
    void normalReadingsCreateNoAnomaly() throws Exception {
        String channel = "ch-ok-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        JsonNode result = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "4"},
                        {"2026-11-10T11:00:00Z", "9"}}), "alice");
        assertEquals(0, result.get("anomalies").size());
        assertEquals(3, result.get("storedReadings").size());
    }

    @Test
    void readingsOnUnconfiguredChannelReturn422() throws Exception {
        String channel = "ch-nocfg-" + run;
        MvcResult result = postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel,
                        new String[][]{{"2026-11-10T10:00:00Z", "0"}}), "alice", 422);
        assertEquals("CHANNEL_NOT_CONFIGURED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
    }

    // ------------------------------------------------------------------
    // 批量原子性
    // ------------------------------------------------------------------

    @Test
    void wholeBatchRejectedWhenSequenceInvalidAndNothingPersisted() throws Exception {
        String channel = "ch-atom-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel,
                        new String[][]{{"2026-11-10T10:00:00Z", "0"}}), "alice");

        // 批内第二条时刻不递增（等于第一条）→ 422
        String commandKey = key("rc");
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, channel, new String[][]{
                        {"2026-11-10T10:30:00Z", "4"},
                        {"2026-11-10T10:30:00Z", "5"}}), "alice", 422);
        // 晚于渠道最新读数的判罬也要覆盖：本批最后一条倒退到存量时刻之前 → 422
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:30:00Z", "4"},
                        {"2026-11-10T09:59:00Z", "5"}}), "alice", 422);

        // 失败整批无任何读数/异常落库
        JsonNode history = getOk("/api/channels/" + channel + "/history");
        assertEquals(1, history.get("readings").size());
        assertEquals(0, history.get("anomalies").size());

        // 失败不占 commandKey：同键改为合法批次后成功
        JsonNode retry = postOk("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, channel, new String[][]{
                        {"2026-11-10T10:30:00Z", "4"},
                        {"2026-11-10T11:00:00Z", "5"}}), "alice");
        assertEquals(2, retry.get("storedReadings").size());
    }

    // ------------------------------------------------------------------
    // 冻结、核销、转让与解除
    // ------------------------------------------------------------------

    @Test
    void freezeBlocksWriteoffButAllowsTransferAndResolutionRestores() throws Exception {
        String channel = "ch-freeze-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        long windowId = createWindow(channel, "100");
        String allocation = submitApproved(windowId, "user-1", "5", "alice");

        // 异常读数冻结渠道
        JsonNode submitted = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "6"}}), "alice");
        long anomalyId = submitted.get("anomalies").get(0).get("id").asLong();

        // 冻结期核销被拦截（422），无半成品：流水不存在、持有额度不变
        MvcResult blocked = postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), allocation, "1", "2026-11-10T00:30:00Z"),
                "carol", 422);
        assertEquals("CHANNEL_FROZEN",
                objectMapper.readTree(blocked.getResponse().getContentAsString()).get("code").asText());
        assertEquals(0, count("writeoff", "allocation_key", allocation));
        JsonNode allocationNode = getAllocation(windowId, allocation);
        assertEquals("5", allocationNode.get("heldAmount").asText());
        assertEquals("APPROVED", allocationNode.get("status").asText());

        // 冻结不影响额度转让登记，且转让完成
        String source = submitApproved(windowId, "user-src", "4", "alice");
        String target = submitRequested(windowId, "user-dst", "2", "bob");
        JsonNode transfer = postOk("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice");
        assertEquals(target, transfer.get("targetAllocationKey").asText());
        assertEquals("2", getAllocation(windowId, source).get("heldAmount").asText());

        // 解除必须换人
        postJson("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "传感器故障，已现场复核",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "4.5"),
                "alice", 409);
        JsonNode resolved = postOk("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "传感器倒转，现场复核替代读数 4.5",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "4.5"),
                "bob");
        assertEquals("RESOLVED", resolved.get("anomaly").get("status").asText());
        assertEquals("bob", resolved.get("resolution").get("resolver").asText());
        assertEquals("4.5", resolved.get("resolution").get("replacementValue").asText());
        assertNotNull(resolved.get("anomaly").get("resolutionId").asText(null));

        // 原异常证据不被改写
        JsonNode detail = getOk("/api/anomalies/" + anomalyId);
        assertEquals("6", detail.get("anomaly").get("readingValue").asText());
        assertEquals("12", detail.get("anomaly").get("actualValue").asText());
        assertEquals("2", detail.get("anomaly").get("difference").asText());
        JsonNode channelHistory = getOk("/api/channels/" + channel + "/history");
        assertEquals("6", channelHistory.get("readings").get(1).get("cumulativeReading").asText());

        // 解除后核销成功，持有额度等额扣减
        JsonNode writeoff = postOk("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), allocation, "1.500", "2026-11-10T00:30:00Z"), "carol");
        assertEquals("1.5", writeoff.get("amount").asText());
        assertEquals("3.5", writeoff.get("heldAmountAfter").asText());
        assertEquals("3.5", getAllocation(windowId, allocation).get("heldAmount").asText());
        JsonNode writeoffs = getOk("/api/windows/" + windowId + "/writeoffs");
        assertEquals(1, writeoffs.get("writeoffs").size());
        assertEquals("3.5", writeoffs.get("writeoffs").get(0).get("heldAmountAfter").asText());
    }

    @Test
    void resolutionFailuresAre422Or409AndAnomalyStaysOpen() throws Exception {
        String channel = "ch-res-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        JsonNode submitted = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "10"},
                        {"2026-11-10T10:30:00Z", "8"}}), "alice");
        long anomalyId = submitted.get("anomalies").get(0).get("id").asLong();

        // 诊断为空 → 422
        postJson("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "   ",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "10"),
                "bob", 422);
        // 替代读数低于前一条读数 10 → 422
        MvcResult low = postJson("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "替代值偏低",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "9.999"),
                "bob", 422);
        assertEquals("REPLACEMENT_BELOW_PREVIOUS",
                objectMapper.readTree(low.getResponse().getContentAsString()).get("code").asText());
        // 同一操作人 → 409
        postJson("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "本人确认",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "10"),
                "alice", 409);
        // 异常不存在 → 404
        postJson("/api/anomalies/999999999/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "不存在",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "10"),
                "bob", 404);

        // 失败后异常仍 OPEN 且无解除记录
        JsonNode detail = getOk("/api/anomalies/" + anomalyId);
        assertEquals("OPEN", detail.get("anomaly").get("status").asText());
        assertTrue(detail.get("resolution").isNull());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM anomaly_resolution WHERE anomaly_id = ?", Integer.class, anomalyId));

        // 成功解除后重复解除 → 409
        postOk("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc"), "diagnosisNote", "复核完成",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "10"), "bob");
        postJson("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", key("arc2"), "diagnosisNote", "再次确认",
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "10"),
                "carol", 409);
    }

    // ------------------------------------------------------------------
    // 核销业务分支与幂等
    // ------------------------------------------------------------------

    @Test
    void writeoffBusinessFailuresAndIdempotency() throws Exception {
        String channel = "ch-wo-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        long windowId = createWindow(channel, "100");
        String allocation = submitApproved(windowId, "user-1", "5", "alice");
        String requested = submitRequested(windowId, "user-2", "1", "bob");

        // 时刻在窗口外（左闭右开）→ 422
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), allocation, "1", "2026-11-10T02:00:00Z"),
                "carol", 422);
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), allocation, "1", "2026-11-09T23:59:59Z"),
                "carol", 422);
        // REQUESTED 申请不能核销 → 409
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), requested, "1", "2026-11-10T00:30:00Z"),
                "carol", 409);
        // 超过持有额度 → 422，差额在错误信息中
        MvcResult over = postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), allocation, "6", "2026-11-10T00:30:00Z"),
                "carol", 422);
        assertTrue(objectMapper.readTree(over.getResponse().getContentAsString()).get("message").asText()
                .contains("差额 1"));
        // 申请不存在 → 404
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk2"), "missing-" + run, "1", "2026-11-10T00:30:00Z"),
                "carol", 404);

        // 全部失败无流水
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM writeoff WHERE window_id = ?", Integer.class, windowId));

        // 成功核销并幂等重放首次完整响应快照
        String commandKey = key("wo");
        String writeoffKey = key("wk2");
        Map<String, Object> body = writeoffBody(commandKey, writeoffKey, allocation, "2",
                "2026-11-10T00:30:00Z");
        JsonNode first = postOk("/api/writeoffs", body, "carol");
        JsonNode replay = postOk("/api/writeoffs", body, "carol");
        assertEquals(first, replay);
        assertEquals("3", first.get("heldAmountAfter").asText());
        // 同 commandKey 改参 → 409
        postJson("/api/writeoffs",
                writeoffBody(commandKey, key("wk3"), allocation, "1", "2026-11-10T00:30:00Z"),
                "carol", 409);
        // 换 commandKey 复用 writeoffKey → 409，额度不变
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), writeoffKey, allocation, "2", "2026-11-10T00:30:00Z"),
                "carol", 409);
        assertEquals("3", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 第二笔核销扣减后的新快照
        JsonNode second = postOk("/api/writeoffs",
                writeoffBody(key("wo"), key("wk4"), allocation, "1.500", "2026-11-10T01:00:00Z"), "carol");
        assertEquals("1.5", second.get("heldAmountAfter").asText());
        JsonNode writeoffs = getOk("/api/windows/" + windowId + "/writeoffs");
        assertEquals(2, writeoffs.get("writeoffs").size());
        // 历史快照不被后续核销覆盖：第一笔 heldAmountAfter 仍为 3
        assertEquals("3", writeoffs.get("writeoffs").get(0).get("heldAmountAfter").asText());
        assertEquals("1.5", writeoffs.get("writeoffs").get(1).get("heldAmountAfter").asText());
    }

    @Test
    void failedWriteoffDoesNotOccupyCommandKey() throws Exception {
        String channel = "ch-wofail-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        long windowId = createWindow(channel, "100");
        String allocation = submitApproved(windowId, "user-1", "2", "alice");
        String commandKey = key("wo");

        postJson("/api/writeoffs",
                writeoffBody(commandKey, key("wk2"), allocation, "5", "2026-11-10T00:30:00Z"),
                "carol", 422);
        // 同键改为合法参数后成功，证明失败不占键
        JsonNode ok = postOk("/api/writeoffs",
                writeoffBody(commandKey, key("wk3"), allocation, "1", "2026-11-10T00:30:00Z"), "carol");
        assertEquals("1", ok.get("amount").asText());
    }

    // ------------------------------------------------------------------
    // 读数幂等与指纹
    // ------------------------------------------------------------------

    @Test
    void readingsReplaySnapshotAndDifferentParamsConflict() throws Exception {
        String channel = "ch-ridem-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        String commandKey = key("rc");
        Map<String, Object> body = readingsBody(commandKey, channel, new String[][]{
                {"2026-11-10T10:00:00Z", "0"},
                {"2026-11-10T10:30:00Z", "4"}});
        JsonNode first = postOk("/api/channels/" + channel + "/readings", body, "alice");
        JsonNode replay = postOk("/api/channels/" + channel + "/readings", body, "alice");
        assertEquals(first, replay);
        // 重放不产生重复读数
        assertEquals(2, getOk("/api/channels/" + channel + "/history").get("readings").size());

        // 同键改读数（集合内容变化）→ 409
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "4.500"}}), "alice", 409);
        // 同键改操作人 → 409
        postJson("/api/channels/" + channel + "/readings",
                readingsBody(commandKey, channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "4"}}), "bob", 409);

        // 幂等指纹是结构化 JSON：集合以 JSON 数组承载而非裸分隔符拼接
        String params = jdbc.queryForObject(
                "SELECT params FROM command_log WHERE command_key = ?", String.class, commandKey);
        assertNotNull(params);
        assertTrue(params.startsWith("{\"operation\":\"READINGS_SUBMIT\""));
        assertTrue(params.contains("\"readings\":[{\"readingAtNanos\""));
        JsonNode parsed = objectMapper.readTree(params);
        assertEquals(2, parsed.get("readings").size());
        assertEquals("alice", parsed.get("reporter").asText());
    }

    @Test
    void resolutionFingerprintRoundTripsSpecialCharacters() throws Exception {
        String channel = "ch-fp-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        JsonNode submitted = postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T10:30:00Z", "6"}}), "alice");
        long anomalyId = submitted.get("anomalies").get(0).get("id").asLong();
        String note = "探头倒转|复测，读数\"偏差\"已修正";
        String commandKey = key("arc");
        JsonNode first = postOk("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", commandKey, "diagnosisNote", note,
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "4"), "bob");
        JsonNode replay = postOk("/api/anomalies/" + anomalyId + "/resolve", Map.of(
                "commandKey", commandKey, "diagnosisNote", note,
                "replacementAtUtc", "2026-11-10T10:30:00Z", "replacementValue", "4"), "bob");
        assertEquals(first, replay);
        // 指纹中特殊字符经 JSON 转义后可完整往返，不会与分隔符混淆
        String params = jdbc.queryForObject(
                "SELECT params FROM command_log WHERE command_key = ?", String.class, commandKey);
        assertEquals(note, objectMapper.readTree(params).get("diagnosisNote").asText());
    }

    // ------------------------------------------------------------------
    // 只读视图与证据稳定性
    // ------------------------------------------------------------------

    @Test
    void readsAreStableSortedAndDoNotChangeState() throws Exception {
        String channel = "ch-read-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        postOk("/api/channels/" + channel + "/readings",
                readingsBody(key("rc"), channel, new String[][]{
                        {"2026-11-10T10:00:00Z", "0"},
                        {"2026-11-10T11:30:00Z", "20"}}), "alice");

        JsonNode history1 = getOk("/api/channels/" + channel + "/history");
        JsonNode history2 = getOk("/api/channels/" + channel + "/history");
        assertEquals(history1, history2);
        // 读数按采样时刻升序；异常按主键升序（先流量后间隔）
        assertEquals("2026-11-10T10:00:00Z", history1.get("readings").get(0).get("readingAtUtc").asText());
        assertEquals("2026-11-10T11:30:00Z", history1.get("readings").get(1).get("readingAtUtc").asText());
        assertEquals(2, history1.get("anomalies").size());
        assertEquals("FLOW_EXCEEDED", history1.get("anomalies").get(0).get("type").asText());
        assertEquals("INTERVAL_EXCEEDED", history1.get("anomalies").get(1).get("type").asText());
        // 两条异常均由第二条读数相对第一条触发，前置读数字段非空；首批读数不会产生异常
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM anomaly WHERE channel_id = ? AND prev_reading_id IS NOT NULL",
                Integer.class, channel));
        long firstReadingId = history1.get("readings").get(0).get("id").asLong();
        assertTrue(firstReadingId > 0);

        // 未配置渠道历史 → 404
        mvc.perform(get("/api/channels/missing-" + run + "/history"))
                .andExpect(status().isNotFound());
        // 读取后数据条数不变（读取本身不产生任何副作用）
        assertEquals(2, getOk("/api/channels/" + channel + "/history").get("readings").size());
    }

    // ------------------------------------------------------------------
    // 并发：异常提交与核销按提交顺序裁决
    // ------------------------------------------------------------------

    @Test
    void anomalyAndWriteoffSettleByCommitOrder() throws Exception {
        String channel = "ch-race-" + run;
        configure(channel, "10", "60", "2026-11-01T00:00:00Z", "alice");
        long windowId = createWindow(channel, "100");
        String allocation = submitApproved(windowId, "user-1", "5", "alice");
        String writeoffKey = key("wk2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> readingFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.submitReadings(key("rc"), channel, List.of(
                        new com.example.starter.water.dto.Dtos.ReadingInput(
                                "2026-11-10T10:00:00Z", "0"),
                        new com.example.starter.water.dto.Dtos.ReadingInput(
                                "2026-11-10T10:30:00Z", "6")), "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> writeoffFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.writeoff(key("wo"), writeoffKey, allocation, "1",
                        "2026-11-10T00:30:00Z", "carol");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int readingStatus = readingFuture.get(30, TimeUnit.SECONDS);
        int writeoffStatus = writeoffFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 读数提交本身始终成功（异常是其结果）；核销取决于提交顺序
        assertEquals(200, readingStatus);
        assertTrue(writeoffStatus == 200 || writeoffStatus == 422,
                "writeoff=" + writeoffStatus);
        Integer writeoffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM writeoff WHERE writeoff_key = ?", Integer.class, writeoffKey);
        if (writeoffStatus == 200) {
            // 核销先提交：恰好 1 条流水，持有额度扣减，随后渠道冻结
            assertEquals(1, writeoffCount);
            assertEquals("4", getAllocation(windowId, allocation).get("heldAmount").asText());
        } else {
            // 异常先提交：核销被拦截，无流水，持有额度不变，渠道冻结
            assertEquals(0, writeoffCount);
            assertEquals("5", getAllocation(windowId, allocation).get("heldAmount").asText());
        }
        // 最终一致：渠道存在 1 条 OPEN 异常
        Integer openAnomalies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM anomaly WHERE channel_id = ? AND status = 'OPEN'",
                Integer.class, channel);
        assertEquals(1, openAnomalies);
        // 冻结后再次核销必被拦截
        postJson("/api/writeoffs",
                writeoffBody(key("wo"), key("wk5"), allocation, "1", "2026-11-10T00:45:00Z"),
                "carol", 422);
    }

    private JsonNode getAllocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }
}
