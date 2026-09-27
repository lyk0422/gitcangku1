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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配水计量传感异常与核销 API 测试：主流程、失败分支、整批事务、幂等、稳定排序与并发提交裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、唯一约束与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SensorApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SensorService sensorService;

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

    private String postBody(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        return postJson(url, body, actor, expectedStatus).getResponse().getContentAsString();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postBody(url, body, actor, 200));
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> ruleBody(String commandKey, String rate, Integer gap) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("maxFlowRate", rate);
        body.put("maxGapMinutes", gap);
        return body;
    }

    private JsonNode configureRule(String channel, String actor, String rate, int gap) throws Exception {
        return postOk("/api/channels/" + channel + "/sensor-rules", ruleBody(key("rc"), rate, gap), actor);
    }

    private Map<String, Object> reading(String utc, String cumulative) {
        Map<String, Object> item = new HashMap<>();
        item.put("readingUtc", utc);
        item.put("cumulative", cumulative);
        return item;
    }

    private Map<String, Object> batchBody(String commandKey, String batchKey, List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("batchKey", batchKey);
        body.put("readings", items);
        return body;
    }

    private JsonNode submitBatch(String channel, String actor, String batchKey,
                                 Map<String, Object>... items) throws Exception {
        return postOk("/api/channels/" + channel + "/readings",
                batchBody(key("bc"), batchKey, List.of(items)), actor);
    }

    private String submitBatchStatus(String channel, String actor, String batchKey,
                                     List<Map<String, Object>> items, int expectedStatus) throws Exception {
        return postBody("/api/channels/" + channel + "/readings",
                batchBody(key("bc"), batchKey, items), actor, expectedStatus);
    }

    private long createWindow(String channel, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channel);
        body.put("startUtc", "2026-01-01T00:00:00Z");
        body.put("endUtc", "2027-01-01T00:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String approvedAllocation(long windowId, String user, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", user);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private Map<String, Object> writeOffBody(String commandKey, String writeOffKey, String allocationKey,
                                             String amount, String atUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeOffKey", writeOffKey);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        body.put("atUtc", atUtc);
        return body;
    }

    private JsonNode writeOffOk(String allocationKey, String amount, String atUtc, String actor)
            throws Exception {
        return postOk("/api/write-offs",
                writeOffBody(key("wc"), key("wo"), allocationKey, amount, atUtc), actor);
    }

    private String writeOffStatus(String allocationKey, String amount, String atUtc, String actor,
                                  int expectedStatus) throws Exception {
        return postBody("/api/write-offs",
                writeOffBody(key("wc"), key("wo"), allocationKey, amount, atUtc), actor, expectedStatus);
    }

    // ------------------------------------------------------------------
    // 规则版本
    // ------------------------------------------------------------------

    @Test
    void ruleVersionsAreImmutableAndIncrement() throws Exception {
        String channel = "ch-rule-" + run;
        JsonNode v1 = configureRule(channel, "alice", "2.5", 30);
        assertEquals(1, v1.get("ruleVersion").asInt());
        assertEquals("2.5", v1.get("maxFlowRate").asText());
        assertEquals(30, v1.get("maxGapMinutes").asInt());
        assertEquals("alice", v1.get("actor").asText());

        JsonNode v2 = configureRule(channel, "bob", "1.25", 45);
        assertEquals(2, v2.get("ruleVersion").asInt());
        assertEquals("1.25", v2.get("maxFlowRate").asText());
        assertEquals(45, v2.get("maxGapMinutes").asInt());

        // 诊断当前规则为最新版本，历史版本不可变
        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertEquals(2, diag.get("currentRule").get("ruleVersion").asInt());
        assertFalse(diag.get("frozen").asBoolean());
    }

    @Test
    void invalidRuleParamsReturn422() throws Exception {
        String channel = "ch-rulebad-" + run;
        for (String badRate : List.of("0", "-1", "abc", "1.0000001", "")) {
            String json = postBody("/api/channels/" + channel + "/sensor-rules",
                    ruleBody(key("rc"), badRate, 30), "alice", 422);
            assertTrue(objectMapper.readTree(json).get("code").asText().equals("RULE_PARAM_INVALID"),
                    "rate=" + badRate);
        }
        for (Integer badGap : java.util.Arrays.asList(0, -5, null)) {
            String json = postBody("/api/channels/" + channel + "/sensor-rules",
                    ruleBody(key("rc"), "2", badGap), "alice", 422);
            assertEquals("RULE_PARAM_INVALID", objectMapper.readTree(json).get("code").asText());
        }
        // 全部失败后渠道仍不存在
        mvc.perform(get("/api/channels/" + channel + "/diagnostics")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 读数与异常主流程
    // ------------------------------------------------------------------

    @Test
    void healthyReadingsDoNotFreezeChannel() throws Exception {
        String channel = "ch-ok-" + run;
        configureRule(channel, "alice", "2", 30);
        JsonNode result = submitBatch(channel, "alice", key("bk"),
                reading("2026-02-01T00:00:00Z", "10"),
                reading("2026-02-01T00:10:00Z", "20"),
                reading("2026-02-01T00:20:00Z", "25"));
        assertEquals(3, result.get("readings").size());
        assertEquals(0, result.get("anomalies").size());
        assertFalse(result.get("frozen").asBoolean());
        assertEquals(1, result.get("readings").get(0).get("ruleVersion").asInt());

        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertFalse(diag.get("frozen").asBoolean());
        assertEquals(0, diag.get("anomalies").size());
    }

    @Test
    void regressionCreatesAnomalyWithActualRequiredAndDeltaValues() throws Exception {
        String channel = "ch-reg-" + run;
        configureRule(channel, "alice", "2", 30);
        submitBatch(channel, "alice", key("bk"), reading("2026-02-02T00:00:00Z", "100"));
        JsonNode result = submitBatch(channel, "alice", key("bk"),
                reading("2026-02-02T00:10:00Z", "90"));

        assertTrue(result.get("frozen").asBoolean());
        JsonNode anomaly = result.get("anomalies").get(0);
        assertEquals(List.of("REGRESSION"), toStringList(anomaly.get("reasons")));
        assertEquals(1, anomaly.get("ruleVersion").asInt());
        assertEquals("100", anomaly.get("fromCumulative").asText());
        assertEquals("90", anomaly.get("toCumulative").asText());
        // 实际值
        assertEquals("-10", anomaly.get("actualDelta").asText());
        assertEquals("10", anomaly.get("actualGapMinutes").asText());
        assertEquals("-1", anomaly.get("actualFlowRate").asText());
        // 要求值
        assertEquals("2", anomaly.get("requiredMaxFlowRate").asText());
        assertEquals(30, anomaly.get("requiredMaxGapMinutes").asInt());
        // 差额
        assertEquals("0", anomaly.get("flowOverBy").asText());
        assertEquals("0", anomaly.get("gapOverByMinutes").asText());
        assertEquals("alice", anomaly.get("detectorActor").asText());
        assertFalse(anomaly.get("resolved").asBoolean());
        assertTrue(anomaly.get("resolution").isNull());

        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertTrue(diag.get("frozen").asBoolean());
        assertEquals(1, diag.get("anomalies").size());
    }

    @Test
    void flowExceededAndGapExceededReportActualRequiredAndOverBy() throws Exception {
        String channel = "ch-flow-" + run;
        configureRule(channel, "alice", "2", 30);
        // 1 分钟内增量 10 立方米 -> 瞬时 10 > 2，差额 8
        JsonNode flowResult = submitBatch(channel, "alice", key("bk"),
                reading("2026-02-03T00:00:00Z", "0"),
                reading("2026-02-03T00:01:00Z", "10"));
        JsonNode flow = flowResult.get("anomalies").get(0);
        assertEquals(List.of("FLOW_EXCEEDED"), toStringList(flow.get("reasons")));
        assertEquals("10", flow.get("actualDelta").asText());
        assertEquals("1", flow.get("actualGapMinutes").asText());
        assertEquals("10", flow.get("actualFlowRate").asText());
        assertEquals("2", flow.get("requiredMaxFlowRate").asText());
        assertEquals("8", flow.get("flowOverBy").asText());
        assertEquals("0", flow.get("gapOverByMinutes").asText());

        // 解除后再测间隔超限：61 分钟 > 30，差额 31 分钟，流量 10/61 不超限
        long anomalyId = flow.get("id").asLong();
        JsonNode resolved = postOk("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("xc"), "仪表卡滞，已现场校准", "10"), "bob");
        assertTrue(resolved.get("resolved").asBoolean());
        JsonNode gapResult = submitBatch(channel, "alice", key("bk"),
                reading("2026-02-03T01:02:00Z", "20"));
        JsonNode gap = gapResult.get("anomalies").get(0);
        assertEquals(List.of("GAP_EXCEEDED"), toStringList(gap.get("reasons")));
        assertEquals("61", gap.get("actualGapMinutes").asText());
        assertEquals(30, gap.get("requiredMaxGapMinutes").asInt());
        assertEquals("31", gap.get("gapOverByMinutes").asText());
        assertEquals("0", gap.get("flowOverBy").asText());
    }

    @Test
    void pairCanHitMultipleReasonsAtOnce() throws Exception {
        String channel = "ch-multi-" + run;
        configureRule(channel, "alice", "2", 30);
        // 间隔 60 分钟 + 倒退：同时命中 REGRESSION 与 GAP_EXCEEDED
        JsonNode result = submitBatch(channel, "alice", key("bk"),
                reading("2026-02-04T00:00:00Z", "100"),
                reading("2026-02-04T01:00:00Z", "50"));
        JsonNode anomaly = result.get("anomalies").get(0);
        List<String> reasons = toStringList(anomaly.get("reasons"));
        assertTrue(reasons.contains("REGRESSION"));
        assertTrue(reasons.contains("GAP_EXCEEDED"));
        assertFalse(reasons.contains("FLOW_EXCEEDED"));
    }

    // ------------------------------------------------------------------
    // 整批校验与事务原子性
    // ------------------------------------------------------------------

    @Test
    void nonIncrementedTimestampsReturn422AndNothingIsWritten() throws Exception {
        String channel = "ch-ts-" + run;
        configureRule(channel, "alice", "2", 60);
        // 批内相等时刻
        String json = submitBatchStatus(channel, "alice", key("bk"), List.of(
                reading("2026-02-05T00:00:00Z", "1"),
                reading("2026-02-05T00:00:00Z", "2")), 422);
        assertEquals("READING_TIME_NOT_INCREMENTED", objectMapper.readTree(json).get("code").asText());
        // 批内乱序（后提交的时刻更早）
        submitBatchStatus(channel, "alice", key("bk"), List.of(
                reading("2026-02-05T00:10:00Z", "2"),
                reading("2026-02-05T00:05:00Z", "1")), 422);
        // 与历史末点同刻/更早
        submitBatch(channel, "alice", key("bk"), reading("2026-02-05T00:00:00Z", "1"));
        submitBatchStatus(channel, "alice", key("bk"),
                List.of(reading("2026-02-05T00:00:00Z", "2")), 422);
        // 整批失败不留任何半成品
        JsonNode history = getOk("/api/channels/" + channel + "/readings");
        assertEquals(1, history.get("readings").size());
        assertFalse(getOk("/api/channels/" + channel + "/diagnostics").get("frozen").asBoolean());
    }

    @Test
    void laterPairFailingRollsBackWholeBatch() throws Exception {
        String channel = "ch-rollback-" + run;
        configureRule(channel, "alice", "2", 60);
        // 前两对合法，第三对时刻倒退：整批回滚，读数与异常都查不到
        submitBatchStatus(channel, "alice", "bk-rollback-" + run, List.of(
                reading("2026-02-06T00:00:00Z", "0"),
                reading("2026-02-06T00:10:00Z", "5"),
                reading("2026-02-06T00:05:00Z", "6")), 422);
        JsonNode history = getOk("/api/channels/" + channel + "/readings");
        assertEquals(0, history.get("readings").size());
        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertEquals(0, diag.get("anomalies").size());
        assertFalse(diag.get("frozen").asBoolean());
        // 同 batchKey 在失败后可用于新的成功批次（失败不占业务键）
        JsonNode retry = submitBatch(channel, "alice", "bk-rollback-" + run,
                reading("2026-02-06T00:00:00Z", "0"));
        assertEquals(1, retry.get("readings").size());
    }

    @Test
    void malformedReadingsReturn400AndEmptyBatchRejected() throws Exception {
        String channel = "ch-mal-" + run;
        configureRule(channel, "alice", "2", 60);
        submitBatchStatus(channel, "alice", key("bk"),
                List.of(reading("not-a-time", "1")), 400);
        submitBatchStatus(channel, "alice", key("bk"),
                List.of(reading("2026-02-07T00:00:00Z", "-1")), 400);
        submitBatchStatus(channel, "alice", key("bk"),
                List.of(reading("2026-02-07T00:00:00Z", "1.0001")), 400);
        postBody("/api/channels/" + channel + "/readings",
                batchBody(key("bc"), key("bk"), List.of()), "alice", 400);
        // 未配置规则的渠道提交读数 -> 404
        submitBatchStatus("ch-missing-" + run, "alice", key("bk"),
                List.of(reading("2026-02-07T00:00:00Z", "1")), 404);
    }

    // ------------------------------------------------------------------
    // 冻结拦截核销、转让仍可登记、解除恢复
    // ------------------------------------------------------------------

    @Test
    void frozenChannelBlocksWriteOffButTransferStillRegisters() throws Exception {
        String channel = "ch-freeze-" + run;
        configureRule(channel, "alice", "2", 60);
        long windowId = createWindow(channel, "100");
        String source = approvedAllocation(windowId, "user-src", "8", "alice");
        String targetKey;
        {
            Map<String, Object> body = new HashMap<>();
            body.put("commandKey", key("ac"));
            targetKey = key("ak");
            body.put("allocationKey", targetKey);
            body.put("windowId", windowId);
            body.put("userId", "user-dst");
            body.put("amount", "3");
            postOk("/api/allocations", body, "bob");
        }

        // 冻结前核销 2 成功
        JsonNode wo = writeOffOk(source, "2", "2026-03-01T00:00:00Z", "alice");
        assertEquals("2", wo.get("amount").asText());
        assertEquals(channel, wo.get("channelId").asText());

        // 读数倒退 -> 冻结
        submitBatch(channel, "alice", key("bk"),
                reading("2026-03-01T01:00:00Z", "100"),
                reading("2026-03-01T01:10:00Z", "90"));

        // 冻结后新核销被拦截，422 原因可区分
        String frozenJson = writeOffStatus(source, "1", "2026-03-01T02:00:00Z", "alice", 422);
        assertEquals("CHANNEL_FROZEN", objectMapper.readTree(frozenJson).get("code").asText());

        // 冻结后转让仍可登记（源 APPROVED -> 目标 APPROVED）
        JsonNode transfer = postOk("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", targetKey), "alice");
        assertEquals(targetKey, transfer.get("targetAllocationKey").asText());
        // 但被冻结渠道上的目标申请同样不能完成核销
        String frozenJson2 = writeOffStatus(targetKey, "1", "2026-03-01T02:05:00Z", "bob", 422);
        assertEquals("CHANNEL_FROZEN", objectMapper.readTree(frozenJson2).get("code").asText());

        // 同一提交人不能解除 -> 409
        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        long anomalyId = diag.get("anomalies").get(0).get("id").asLong();
        postBody("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("xc"), "自报自解不允许", "90"), "alice", 409);
        // 异常不存在 -> 404
        postBody("/api/anomalies/999999999/resolve",
                resolveBody(key("xc"), "不存在", "1"), "bob", 404);
        // 诊断说明缺失 -> 400
        postBody("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("xc"), "  ", "90"), "bob", 400);

        // 不同操作人确认诊断说明与替代读数 -> 解除
        JsonNode resolved = postOk("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("xc"), "现场核对为仪表气囊，替代读数 99", "99"), "bob");
        assertTrue(resolved.get("resolved").asBoolean());
        assertEquals("99", resolved.get("resolution").get("replacementCumulative").asText());
        assertEquals("bob", resolved.get("resolution").get("resolverActor").asText());
        assertEquals("2026-03-01T01:10:00Z",
                resolved.get("resolution").get("replacementReadingUtc").asText());
        // 原异常证据不被改写
        assertEquals(List.of("REGRESSION"), toStringList(resolved.get("reasons")));
        assertEquals("-10", resolved.get("actualDelta").asText());
        assertEquals("alice", resolved.get("detectorActor").asText());

        // 重复解除 -> 409
        postBody("/api/anomalies/" + anomalyId + "/resolve",
                resolveBody(key("xc"), "再次解除", "98"), "carol", 409);

        // 解冻后核销恢复；替代读数出现在诊断中
        assertFalse(getOk("/api/channels/" + channel + "/diagnostics").get("frozen").asBoolean());
        JsonNode diagAfter = getOk("/api/channels/" + channel + "/diagnostics");
        assertEquals(1, diagAfter.get("replacementReadings").size());
        assertEquals("99", diagAfter.get("replacementReadings").get(0).get("replacementCumulative").asText());

        // 源：批准 8、冻结前核销 2、转让转出 3，持有余 3；再核销 3 成功，既有 2 保留
        writeOffOk(source, "3", "2026-03-01T03:00:00Z", "alice");
        JsonNode list = getOk("/api/allocations/" + source + "/write-offs");
        assertEquals("5", list.get("availableAmount").asText());
        assertEquals("5", list.get("writtenOffTotal").asText());
        assertEquals("0", list.get("remaining").asText());
        assertEquals(2, list.get("writeOffs").size());
        // 已无剩余：核销 3.001 返回 422，响应给出实际/要求/差额
        String overJson = writeOffStatus(source, "3.001", "2026-03-01T03:30:00Z", "alice", 422);
        JsonNode error = objectMapper.readTree(overJson);
        assertEquals("WRITE_OFF_EXCEEDS_AVAILABLE", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("差额 3.001"));
    }

    @Test
    void allAnomaliesMustBeResolvedBeforeUnfreezing() throws Exception {
        String channel = "ch-two-" + run;
        configureRule(channel, "alice", "2", 60);
        long windowId = createWindow(channel, "100");
        String allocation = approvedAllocation(windowId, "u1", "10", "alice");

        // 两次倒退：第一批 100->90（异常1），第二批以 90 为历史末点再倒退到 80（异常2）
        submitBatch(channel, "alice", key("bk"),
                reading("2026-03-02T00:00:00Z", "100"),
                reading("2026-03-02T00:10:00Z", "90"));
        submitBatch(channel, "alice", key("bk"),
                reading("2026-03-02T00:20:00Z", "80"));
        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertEquals(2, diag.get("anomalies").size());
        assertTrue(diag.get("frozen").asBoolean());

        long first = diag.get("anomalies").get(0).get("id").asLong();
        long second = diag.get("anomalies").get(1).get("id").asLong();
        postOk("/api/anomalies/" + first + "/resolve",
                resolveBody(key("xc"), "第一次异常诊断", "95"), "bob");
        // 仍有一个未解除：持续拦截
        assertTrue(getOk("/api/channels/" + channel + "/diagnostics").get("frozen").asBoolean());
        writeOffStatus(allocation, "1", "2026-03-02T04:00:00Z", "alice", 422);
        postOk("/api/anomalies/" + second + "/resolve",
                resolveBody(key("xc"), "第二次异常诊断", "85"), "carol");
        assertFalse(getOk("/api/channels/" + channel + "/diagnostics").get("frozen").asBoolean());
        writeOffOk(allocation, "1", "2026-03-02T05:00:00Z", "alice");
    }

    @Test
    void writeOffRejectsNonApprovedAllocationAndUnknownKeys() throws Exception {
        String channel = "ch-wost-" + run;
        configureRule(channel, "alice", "2", 60);
        long windowId = createWindow(channel, "100");
        // REQUESTED 申请不能核销
        String requestedKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", requestedKey);
        body.put("windowId", windowId);
        body.put("userId", "u1");
        body.put("amount", "5");
        postOk("/api/allocations", body, "alice");
        writeOffStatus(requestedKey, "1", "2026-03-03T00:00:00Z", "alice", 409);
        // 不存在的申请 -> 404
        writeOffStatus("no-such-" + run, "1", "2026-03-03T00:00:00Z", "alice", 404);
    }

    // ------------------------------------------------------------------
    // 稳定排序、规则版本证据与历史 null 语义
    // ------------------------------------------------------------------

    @Test
    void readingHistoryIsStableSortedAcrossBatchesAndRuleVersionIsFixed() throws Exception {
        String channel = "ch-sort-" + run;
        configureRule(channel, "alice", "100", 1000);
        // 读数为追加式时间序列：跨批次也必须时刻递增，历史按时刻稳定归并
        submitBatch(channel, "alice", key("bk"),
                reading("2026-04-01T00:00:00Z", "10"),
                reading("2026-04-01T00:20:00Z", "30"));
        submitBatch(channel, "alice", key("bk"),
                reading("2026-04-01T00:30:00Z", "35"),
                reading("2026-04-01T00:50:00Z", "50"));
        // 新批次首点早于渠道最新读数 -> 422，不写入
        submitBatchStatus(channel, "alice", key("bk"),
                List.of(reading("2026-04-01T00:10:00Z", "20")), 422);

        JsonNode history = getOk("/api/channels/" + channel + "/readings");
        List<JsonNode> readings = new ArrayList<>();
        history.get("readings").forEach(readings::add);
        assertEquals(List.of("2026-04-01T00:00:00Z", "2026-04-01T00:20:00Z",
                "2026-04-01T00:30:00Z", "2026-04-01T00:50:00Z"),
                readings.stream().map(r -> r.get("readingUtc").asText()).toList());
        // 批内序号作为提交证据稳定保留
        assertEquals(List.of(0, 1, 0, 1),
                readings.stream().map(r -> r.get("seqNo").asInt()).toList());
        readings.forEach(r -> assertEquals(1, r.get("ruleVersion").asInt()));

        // 新版本后提交的读数固定新版本号，旧读数证据不变
        configureRule(channel, "bob", "1", 10);
        submitBatch(channel, "carol", key("bk"), reading("2026-04-01T01:00:00Z", "50.1"));
        JsonNode history2 = getOk("/api/channels/" + channel + "/readings");
        assertEquals(1, history2.get("readings").get(0).get("ruleVersion").asInt());
        assertEquals(2, history2.get("readings").get(4).get("ruleVersion").asInt());

        // 无异常时诊断 anomalies 为空、frozen=false、replacementReadings 为空数组（非 null）
        JsonNode diag = getOk("/api/channels/" + channel + "/diagnostics");
        assertTrue(diag.get("anomalies").isArray());
        assertEquals(0, diag.get("anomalies").size());
        assertTrue(diag.get("replacementReadings").isArray());
    }

    // ------------------------------------------------------------------
    // 幂等：同参重放快照、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void ruleCommandReplayDoesNotCreateAnotherVersion() throws Exception {
        String channel = "ch-irule-" + run;
        String commandKey = key("rc");
        Map<String, Object> body = ruleBody(commandKey, "2", 30);
        JsonNode first = postOk("/api/channels/" + channel + "/sensor-rules", body, "alice");
        JsonNode replay = postOk("/api/channels/" + channel + "/sensor-rules", body, "alice");
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/channels/" + channel + "/diagnostics").get("currentRule")
                .get("ruleVersion").asInt());
        // 同键改操作者 -> 409
        postBody("/api/channels/" + channel + "/sensor-rules",
                ruleBody(commandKey, "2", 30), "bob", 409);
    }

    @Test
    void readingBatchReplayReturnsSnapshotAndDifferentParamsConflict() throws Exception {
        String channel = "ch-iread-" + run;
        configureRule(channel, "alice", "100", 1000);
        String commandKey = key("bc");
        String batchKey = key("bk");
        Map<String, Object> body = batchBody(commandKey, batchKey, List.of(
                reading("2026-05-01T00:00:00Z", "10"),
                reading("2026-05-01T00:10:00Z", "20")));
        JsonNode first = postOk("/api/channels/" + channel + "/readings", body, "alice");
        JsonNode replay = postOk("/api/channels/" + channel + "/readings", body, "alice");
        assertEquals(first, replay);
        // 重放不重复写入
        assertEquals(2, getOk("/api/channels/" + channel + "/readings").get("readings").size());
        // 同键改操作者 -> 409（指纹含操作者）
        postBody("/api/channels/" + channel + "/readings", body, "bob", 409);
        // 同键改读数集合 -> 409
        Map<String, Object> changed = batchBody(commandKey, batchKey, List.of(
                reading("2026-05-01T00:00:00Z", "10"),
                reading("2026-05-01T00:10:00Z", "21")));
        postBody("/api/channels/" + channel + "/readings", changed, "alice", 409);
        // 409 未写入多余读数
        assertEquals(2, getOk("/api/channels/" + channel + "/readings").get("readings").size());
    }

    @Test
    void failedReadingBatchDoesNotOccupyCommandKey() throws Exception {
        String channel = "ch-ifail-" + run;
        configureRule(channel, "alice", "100", 1000);
        String commandKey = key("bc");
        // 首次提交时刻不递增 -> 422，失败不占 commandKey
        postBody("/api/channels/" + channel + "/readings",
                batchBody(commandKey, key("bk"), List.of(
                        reading("2026-05-02T00:10:00Z", "1"),
                        reading("2026-05-02T00:00:00Z", "2"))), "alice", 422);
        // 同键改参为合法批次 -> 成功
        JsonNode ok = postOk("/api/channels/" + channel + "/readings",
                batchBody(commandKey, key("bk"), List.of(
                        reading("2026-05-02T00:00:00Z", "1"),
                        reading("2026-05-02T00:10:00Z", "2"))), "alice");
        assertEquals(2, ok.get("readings").size());
        assertEquals(2, getOk("/api/channels/" + channel + "/readings").get("readings").size());
    }

    @Test
    void writeOffReplayAppliesOnceAndDifferentParamsConflict() throws Exception {
        String channel = "ch-iwo-" + run;
        configureRule(channel, "alice", "100", 1000);
        long windowId = createWindow(channel, "100");
        String allocation = approvedAllocation(windowId, "u1", "5", "alice");
        String commandKey = key("wc");
        Map<String, Object> body = writeOffBody(commandKey, key("wo"), allocation, "2",
                "2026-05-03T00:00:00Z");
        JsonNode first = postOk("/api/write-offs", body, "alice");
        JsonNode replay = postOk("/api/write-offs", body, "alice");
        assertEquals(first, replay);
        JsonNode list = getOk("/api/allocations/" + allocation + "/write-offs");
        assertEquals(1, list.get("writeOffs").size());
        assertEquals("2", list.get("writtenOffTotal").asText());
        assertEquals("3", list.get("remaining").asText());
        // 同键改核销额 -> 409
        postBody("/api/write-offs",
                writeOffBody(commandKey, key("wo"), allocation, "3", "2026-05-03T00:00:00Z"),
                "alice", 409);
        // 同键改操作者 -> 409
        postBody("/api/write-offs",
                writeOffBody(commandKey, key("wo"), allocation, "2", "2026-05-03T00:00:00Z"),
                "bob", 409);
        // writeOffKey 换 commandKey 复用 -> 409
        String reusedKey = (String) body.get("writeOffKey");
        postBody("/api/write-offs",
                writeOffBody(key("wc"), reusedKey, allocation, "1", "2026-05-03T01:00:00Z"),
                "alice", 409);
        assertEquals(1, getOk("/api/allocations/" + allocation + "/write-offs").get("writeOffs").size());
    }

    // ------------------------------------------------------------------
    // 并发裁决：异常先提交则同刻后核销被拦截
    // ------------------------------------------------------------------

    @Test
    void anomalyCommittedFirstBlocksSameInstantWriteOff() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int anomalyFirstRounds = 0;
        int rounds = 8;
        for (int round = 0; round < rounds; round++) {
            String channel = "ch-race-" + run + "-" + round;
            configureRule(channel, "alice", "100", 1000);
            long windowId = createWindow(channel, "100");
            String allocation = approvedAllocation(windowId, "u1", "10", "alice");
            String batchKey = key("bk");
            String writeOffKey = key("wo");
            String readCommand = key("bc");
            String woCommand = key("wc");

            CountDownLatch gate = new CountDownLatch(1);
            Future<Integer> readingFuture = pool.submit(() -> {
                gate.await();
                try {
                    sensorService.submitReadings(readCommand, batchKey, channel, List.of(
                            new com.example.starter.water.dto.Dtos.ReadingItem(
                                    "2026-06-01T00:00:00Z", "100"),
                            new com.example.starter.water.dto.Dtos.ReadingItem(
                                    "2026-06-01T00:10:00Z", "90")), "alice");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                } catch (Exception e) {
                    return 500;
                }
            });
            Future<Integer> writeOffFuture = pool.submit(() -> {
                gate.await();
                try {
                    sensorService.writeOff(woCommand, writeOffKey, allocation, "1",
                            "2026-06-01T00:10:00Z", "alice");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                } catch (Exception e) {
                    return 500;
                }
            });
            gate.countDown();
            int readingStatus = readingFuture.get(30, TimeUnit.SECONDS);
            int writeOffStatus = writeOffFuture.get(30, TimeUnit.SECONDS);
            assertEquals(200, readingStatus, "读数批次自身应成功");
            assertTrue(writeOffStatus == 200 || writeOffStatus == 422,
                    "核销只可能成功或被冻结拦截，实际: " + writeOffStatus);
            if (writeOffStatus == 422) {
                anomalyFirstRounds++;
            }
            // 不变式：若渠道最终冻结，成功的核销只能是冻结前提交的那一笔（数量最多 1）
            JsonNode list = getOk("/api/allocations/" + allocation + "/write-offs");
            int woCount = list.get("writeOffs").size();
            assertTrue(getOk("/api/channels/" + channel + "/diagnostics").get("frozen").asBoolean());
            assertTrue(woCount <= 1, "异常先提交时不得有冻结后核销，实际笔数: " + woCount);
            if (writeOffStatus == 422) {
                assertEquals(0, woCount, "异常先提交则同刻核销必须被拦截");
            }
        }
        pool.shutdown();
        assertTrue(anomalyFirstRounds >= 1,
                "8 轮并发中应至少出现一次异常先提交拦截核销，实际异常先提交轮数: " + anomalyFirstRounds);
    }

    @Test
    void concurrentReadingsSettleByCommitOrderWithoutPartialWrites() throws Exception {
        // 两个批次并发提交同一时刻读数：至多一个批次成功，另一个 422；读数总数与成功批次一致
        String channel = "ch-race2-" + run;
        configureRule(channel, "alice", "100", 1000);
        String b1 = key("bk");
        String b2 = key("bk");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                sensorService.submitReadings(key("bc"), b1, channel, List.of(
                        new com.example.starter.water.dto.Dtos.ReadingItem(
                                "2026-06-02T00:00:00Z", "10")), "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                sensorService.submitReadings(key("bc"), b2, channel, List.of(
                        new com.example.starter.water.dto.Dtos.ReadingItem(
                                "2026-06-02T00:00:00Z", "20")), "bob");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "同刻读数恰好一个批次成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode history = getOk("/api/channels/" + channel + "/readings");
        assertEquals(1, history.get("readings").size());
        String winner = s1 == 200 ? "10" : "20";
        assertEquals(winner, history.get("readings").get(0).get("cumulative").asText());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> resolveBody(String commandKey, String note, String replacement) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("diagnosisNote", note);
        body.put("replacementCumulative", replacement);
        return body;
    }

    private List<String> toStringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
