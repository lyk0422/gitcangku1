package com.example.starter.water;

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

import java.util.HashMap;
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
 * 灌渠中断与排班顺延 API 集成测试。全部使用 H2 内存库（MODE=MySQL）真实事务、唯一约束与行锁，
 * 不 mock 数据库边界；时间判断使用 OutageService 的可控时钟，不依赖睡眠。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutageApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutageService outageService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    @AfterEach
    void resetClock() {
        outageService.setFixedNow(null);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postJson(url, body, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", "100");
        return postOk("/api/windows", body).get("id").asLong();
    }

    private String publish(long windowId, String start, String end) throws Exception {
        String scheduleKey = key("sk");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("sc"));
        body.put("scheduleKey", scheduleKey);
        body.put("windowId", windowId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        postOk("/api/schedules", body);
        return scheduleKey;
    }

    private Map<String, Object> outageBody(String commandKey, String outageKey, String channelId,
                                           String start, String end, int postponeMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("outageKey", outageKey);
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("reason", "canal-C maintenance");
        body.put("postponeMinutes", postponeMinutes);
        return body;
    }

    private JsonNode registerOutage(String channelId, String start, String end, int minutes) throws Exception {
        return postOk("/api/outages", outageBody(key("oc"), key("ok"), channelId, start, end, minutes));
    }

    private JsonNode schedule(String scheduleKey) throws Exception {
        return getOk("/api/schedules/" + scheduleKey);
    }

    private JsonNode outage(String outageKey) throws Exception {
        return getOk("/api/outages/" + outageKey);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void outagePostponesAllAffectedUnstartedSchedulesBySameMinutes() throws Exception {
        outageService.setFixedNow("2026-10-20T09:30:00Z");
        String channel = "ch-main-" + run;
        long windowId = createWindow(channel, "2026-10-20T08:00:00Z", "2026-10-20T13:00:00Z");
        // s1 不在中断窗口；s2/s3 与 10:00-11:00 交叉；s4 左闭右开相邻（11:00 开始）不受影响
        String s1 = publish(windowId, "2026-10-20T09:00:00Z", "2026-10-20T09:45:00Z");
        String s2 = publish(windowId, "2026-10-20T10:15:00Z", "2026-10-20T10:30:00Z");
        String s3 = publish(windowId, "2026-10-20T10:30:00Z", "2026-10-20T10:45:00Z");
        String s4 = publish(windowId, "2026-10-20T11:00:00Z", "2026-10-20T11:15:00Z");

        JsonNode result = registerOutage(channel,
                "2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z", 60);
        assertEquals("REGISTERED", result.get("status").asText());
        assertEquals(60, result.get("postponeMinutes").asInt());
        assertEquals(2, result.get("affected").size());

        // s1/s4 不动
        JsonNode s1Node = schedule(s1);
        assertEquals("2026-10-20T09:00:00Z", s1Node.get("startUtc").asText());
        assertEquals(0, s1Node.get("postponements").size());
        JsonNode s4Node = schedule(s4);
        assertEquals("2026-10-20T11:00:00Z", s4Node.get("startUtc").asText());

        // s2 10:15-10:30 -> 11:15-11:30（与 s4 11:00-11:15 相邻）；s3 10:30-10:45 -> 11:30-11:45
        JsonNode s2Node = schedule(s2);
        assertEquals("2026-10-20T11:15:00Z", s2Node.get("startUtc").asText());
        assertEquals("2026-10-20T11:30:00Z", s2Node.get("endUtc").asText());
        assertEquals("2026-10-20T10:15:00Z", s2Node.get("originalStartUtc").asText());
        assertEquals("2026-10-20T10:30:00Z", s2Node.get("originalEndUtc").asText());
        JsonNode s3Node = schedule(s3);
        assertEquals("2026-10-20T11:30:00Z", s3Node.get("startUtc").asText());
        assertEquals("2026-10-20T11:45:00Z", s3Node.get("endUtc").asText());

        // 顺延链：中断原因、顺延分钟、from/to 对应关系
        JsonNode chain = s2Node.get("postponements");
        assertEquals(1, chain.size());
        assertEquals("canal-C maintenance", chain.get(0).get("reason").asText());
        assertEquals(60, chain.get(0).get("postponeMinutes").asInt());
        assertEquals("2026-10-20T10:15:00Z", chain.get(0).get("fromStartUtc").asText());
        assertEquals("2026-10-20T11:15:00Z", chain.get(0).get("toStartUtc").asText());
        assertEquals("2026-10-20T10:15:00Z", chain.get(0).get("originalStartUtc").asText());

        // 中断查询给出受影响排班的原排班与新窗口
        JsonNode outageView = outage(result.get("outageKey").asText());
        assertEquals(2, outageView.get("affected").size());
        JsonNode affectedS3 = outageView.get("affected").get(1);
        assertEquals(s3, affectedS3.get("scheduleKey").asText());
        assertEquals("2026-10-20T10:30:00Z", affectedS3.get("originalStartUtc").asText());
        assertEquals("2026-10-20T10:45:00Z", affectedS3.get("previousEndUtc").asText());
        assertEquals("2026-10-20T11:45:00Z", affectedS3.get("newEndUtc").asText());
    }

    @Test
    void registerOutageWithoutAffectedSchedulesStillRegistered() throws Exception {
        outageService.setFixedNow("2026-10-21T08:00:00Z");
        String channel = "ch-empty-" + run;
        long windowId = createWindow(channel, "2026-10-21T08:00:00Z", "2026-10-21T18:00:00Z");
        publish(windowId, "2026-10-21T11:00:00Z", "2026-10-21T11:30:00Z");
        JsonNode result = registerOutage(channel,
                "2026-10-21T10:00:00Z", "2026-10-21T11:00:00Z", 30);
        assertEquals("REGISTERED", result.get("status").asText());
        assertEquals(0, result.get("affected").size());
    }

    @Test
    void postponeDoesNotChangeQuota() throws Exception {
        outageService.setFixedNow("2026-10-22T09:00:00Z");
        String channel = "ch-quota-" + run;
        long windowId = createWindow(channel, "2026-10-22T08:00:00Z", "2026-10-22T13:00:00Z");
        publish(windowId, "2026-10-22T10:30:00Z", "2026-10-22T11:00:00Z");
        // 既有配额体系不受顺延影响
        Map<String, Object> alloc = new HashMap<>();
        alloc.put("commandKey", key("ac"));
        alloc.put("allocationKey", key("ak"));
        alloc.put("windowId", windowId);
        alloc.put("userId", "user-1");
        alloc.put("amount", "12.5");
        mvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content(objectMapper.writeValueAsString(alloc)))
                .andExpect(status().isOk());
        registerOutage(channel, "2026-10-22T10:00:00Z", "2026-10-22T11:00:00Z", 60);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("100", cap.get("remaining").asText());
    }

    // ------------------------------------------------------------------
    // 排班发布
    // ------------------------------------------------------------------

    @Test
    void publishRejectsOverlapWithScheduleAndOutageButAllowsAdjacency() throws Exception {
        outageService.setFixedNow("2026-10-23T08:00:00Z");
        String channel = "ch-pub-" + run;
        long windowId = createWindow(channel, "2026-10-23T08:00:00Z", "2026-10-23T18:00:00Z");
        publish(windowId, "2026-10-23T10:30:00Z", "2026-10-23T11:00:00Z");
        // 与既有排班交叉 -> 409；相邻 -> 200
        postJson("/api/schedules", publishBody(key("sc"), key("sk"), windowId,
                "2026-10-23T10:45:00Z", "2026-10-23T11:15:00Z"), 409);
        publish(windowId, "2026-10-23T11:00:00Z", "2026-10-23T11:30:00Z");

        registerOutage(channel, "2026-10-23T14:00:00Z", "2026-10-23T15:00:00Z", 30);
        // 落进中断窗口 -> 409；端点相邻 -> 200
        postJson("/api/schedules", publishBody(key("sc"), key("sk"), windowId,
                "2026-10-23T14:30:00Z", "2026-10-23T14:45:00Z"), 409);
        publish(windowId, "2026-10-23T15:00:00Z", "2026-10-23T15:15:00Z");
    }

    @Test
    void publishOutsideSupplyWindowReturns422AndBadParamsReturn400() throws Exception {
        String channel = "ch-bad-" + run;
        long windowId = createWindow(channel, "2026-10-24T10:00:00Z", "2026-10-24T12:00:00Z");
        // 超出配水窗口 -> 422
        postJson("/api/schedules", publishBody(key("sc"), key("sk"), windowId,
                "2026-10-24T11:30:00Z", "2026-10-24T12:30:00Z"), 422);
        // 起止倒置 -> 400
        postJson("/api/schedules", publishBody(key("sc"), key("sk"), windowId,
                "2026-10-24T11:30:00Z", "2026-10-24T11:00:00Z"), 400);
        // 窗口不存在 -> 404
        postJson("/api/schedules", publishBody(key("sc"), key("sk"), 999999999L,
                "2026-10-24T11:00:00Z", "2026-10-24T11:30:00Z"), 404);
        // 非法键 -> 400
        postJson("/api/schedules", publishBody("", key("sk"), windowId,
                "2026-10-24T11:00:00Z", "2026-10-24T11:30:00Z"), 400);
        mvc.perform(get("/api/schedules/missing-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/outages/missing-" + run)).andExpect(status().isNotFound());
    }

    private Map<String, Object> publishBody(String commandKey, String scheduleKey, long windowId,
                                            String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("scheduleKey", scheduleKey);
        body.put("windowId", windowId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        return body;
    }

    // ------------------------------------------------------------------
    // 422 整批失败分支
    // ------------------------------------------------------------------

    @Test
    void postponeBeyondSupplyWindowRejectsWholeBatch() throws Exception {
        outageService.setFixedNow("2026-10-25T09:00:00Z");
        String channel = "ch-edge-" + run;
        long windowId = createWindow(channel, "2026-10-25T08:00:00Z", "2026-10-25T12:00:00Z");
        String s1 = publish(windowId, "2026-10-25T10:15:00Z", "2026-10-25T10:30:00Z");
        // s2 顺延后 11:30-12:30 超出窗口结束时刻 12:00
        String s2 = publish(windowId, "2026-10-25T10:30:00Z", "2026-10-25T11:30:00Z");

        MvcResult result = postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-25T10:00:00Z", "2026-10-25T11:00:00Z", 60), 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("POSTPONE_OUT_OF_WINDOW", error.get("code").asText());

        // 整批回滚：s1/s2 窗口原样、中断未落库
        assertEquals("2026-10-25T10:15:00Z", schedule(s1).get("startUtc").asText());
        assertEquals("2026-10-25T10:30:00Z", schedule(s2).get("startUtc").asText());
        assertEquals(0, schedule(s2).get("postponements").size());
    }

    @Test
    void startedScheduleRejectsWholeBatch() throws Exception {
        String channel = "ch-started-" + run;
        long windowId = createWindow(channel, "2026-10-26T08:00:00Z", "2026-10-26T13:00:00Z");
        // s1 与 s2 互不重叠；判定时刻 10:30 时 s1 已开始，s2 未开始
        String s1 = publish(windowId, "2026-10-26T10:15:00Z", "2026-10-26T10:25:00Z");
        String s2 = publish(windowId, "2026-10-26T10:35:00Z", "2026-10-26T10:50:00Z");
        // 当前时刻 10:30：s1 已开始（起点已过且未核销），s2 未开始；整批拒绝
        outageService.setFixedNow("2026-10-26T10:30:00Z");
        MvcResult result = postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-26T10:00:00Z", "2026-10-26T11:00:00Z", 60), 422);
        assertEquals("SCHEDULE_STARTED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        // s2 虽未开始也保持原样
        assertEquals("2026-10-26T10:15:00Z", schedule(s1).get("startUtc").asText());
        assertEquals("2026-10-26T10:35:00Z", schedule(s2).get("startUtc").asText());
        assertEquals(0, schedule(s1).get("postponements").size());
        assertEquals(0, schedule(s2).get("postponements").size());
    }

    @Test
    void postponeConflictAndAdjacency() throws Exception {
        outageService.setFixedNow("2026-10-27T09:00:00Z");
        String channel = "ch-conf-" + run;
        long windowId = createWindow(channel, "2026-10-27T08:00:00Z", "2026-10-27T13:00:00Z");
        String moving = publish(windowId, "2026-10-27T10:30:00Z", "2026-10-27T10:45:00Z");
        // 顺延后 moving 为 11:30-11:45，与 blocker 11:20-11:40 交叉 -> 422
        publish(windowId, "2026-10-27T11:20:00Z", "2026-10-27T11:40:00Z");
        postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-27T10:00:00Z", "2026-10-27T11:00:00Z", 60), 422);
        assertEquals("2026-10-27T10:30:00Z", schedule(moving).get("startUtc").asText());

        // blocker 与新窗口仅相邻（11:15-11:30）-> 顺延成功
        String channel2 = "ch-adj-" + run;
        long window2 = createWindow(channel2, "2026-10-27T08:00:00Z", "2026-10-27T13:00:00Z");
        String moving2 = publish(window2, "2026-10-27T10:30:00Z", "2026-10-27T10:45:00Z");
        publish(window2, "2026-10-27T11:45:00Z", "2026-10-27T12:00:00Z");
        registerOutage(channel2, "2026-10-27T10:00:00Z", "2026-10-27T11:00:00Z", 60);
        assertEquals("2026-10-27T11:30:00Z", schedule(moving2).get("startUtc").asText());
        assertEquals("2026-10-27T11:45:00Z", schedule(moving2).get("endUtc").asText());
    }

    @Test
    void postponeStillInsideOutageWindowRejected() throws Exception {
        outageService.setFixedNow("2026-10-28T09:00:00Z");
        String channel = "ch-still-" + run;
        long windowId = createWindow(channel, "2026-10-28T08:00:00Z", "2026-10-28T13:00:00Z");
        String s1 = publish(windowId, "2026-10-28T10:30:00Z", "2026-10-28T10:45:00Z");
        // 顺延 15 分钟后 10:45-11:00 仍与中断 10:00-11:00 交叉 -> 422
        MvcResult result = postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z", 15), 422);
        assertEquals("POSTPONE_STILL_IN_OUTAGE",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertEquals("2026-10-28T10:30:00Z", schedule(s1).get("startUtc").asText());
        // 顺延 30 分钟后 11:00-11:15 与中断端点相邻 -> 成功
        registerOutage(channel, "2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z", 30);
        assertEquals("2026-10-28T11:00:00Z", schedule(s1).get("startUtc").asText());
    }

    @Test
    void invalidOutageParamsReturn400() throws Exception {
        String channel = "ch-obad-" + run;
        createWindow(channel, "2026-10-29T08:00:00Z", "2026-10-29T13:00:00Z");
        postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-29T11:00:00Z", "2026-10-29T10:00:00Z", 60), 400);
        postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-29T10:00:00Z", "2026-10-29T11:00:00Z", 0), 400);
        postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-10-29T10:00:00Z", "2026-10-29T11:00:00Z", -5), 400);
        Map<String, Object> noReason = outageBody(key("oc"), key("ok"), channel,
                "2026-10-29T10:00:00Z", "2026-10-29T11:00:00Z", 60);
        noReason.remove("reason");
        postJson("/api/outages", noReason, 400);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstResultAndDifferentMinutesReturn409() throws Exception {
        outageService.setFixedNow("2026-10-30T09:00:00Z");
        String channel = "ch-idem-" + run;
        long windowId = createWindow(channel, "2026-10-30T08:00:00Z", "2026-10-30T13:00:00Z");
        String s1 = publish(windowId, "2026-10-30T10:30:00Z", "2026-10-30T10:45:00Z");

        String commandKey = key("oc");
        String outageKey = key("ok");
        Map<String, Object> body = outageBody(commandKey, outageKey, channel,
                "2026-10-30T10:00:00Z", "2026-10-30T11:00:00Z", 60);
        JsonNode first = postOk("/api/outages", body);
        JsonNode replay = postOk("/api/outages", body);
        assertEquals(first, replay);
        // 只顺延一次
        assertEquals(1, schedule(s1).get("postponements").size());
        assertEquals("2026-10-30T11:30:00Z", schedule(s1).get("startUtc").asText());
        // 同 requestId 换顺延分钟数 -> 409
        postJson("/api/outages", outageBody(commandKey, outageKey, channel,
                "2026-10-30T10:00:00Z", "2026-10-30T11:00:00Z", 30), 409);
        // 同 requestId 换中断窗口 -> 409
        postJson("/api/outages", outageBody(commandKey, outageKey, channel,
                "2026-10-30T10:00:00Z", "2026-10-30T10:30:00Z", 60), 409);
        // 换 requestId 复用 outageKey -> 409
        postJson("/api/outages", outageBody(key("oc"), outageKey, channel,
                "2026-10-30T10:00:00Z", "2026-10-30T11:00:00Z", 60), 409);
        // 数据仍是首次结果
        assertEquals(1, schedule(s1).get("postponements").size());
    }

    @Test
    void failedOutageDoesNotOccupyCommandKey() throws Exception {
        outageService.setFixedNow("2026-11-01T09:00:00Z");
        String channel = "ch-failidem-" + run;
        long windowId = createWindow(channel, "2026-11-01T08:00:00Z", "2026-11-01T13:00:00Z");
        String s1 = publish(windowId, "2026-11-01T10:30:00Z", "2026-11-01T10:45:00Z");
        String commandKey = key("oc");
        String outageKey = key("ok");
        // 顺延 15 仍在中断窗口 -> 422，不占用 commandKey/outageKey
        postJson("/api/outages", outageBody(commandKey, outageKey, channel,
                "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z", 15), 422);
        postJson("/api/outages", outageBody(commandKey, outageKey, channel,
                "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z", 15), 422);
        // 同键改用合法顺延 60 -> 成功
        JsonNode ok = postOk("/api/outages", outageBody(commandKey, outageKey, channel,
                "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z", 60));
        assertEquals(outageKey, ok.get("outageKey").asText());
        assertEquals(1, schedule(s1).get("postponements").size());
    }

    // ------------------------------------------------------------------
    // 多次顺延与用水核销
    // ------------------------------------------------------------------

    @Test
    void multipleOutagesBuildPostponementChain() throws Exception {
        outageService.setFixedNow("2026-11-02T09:00:00Z");
        String channel = "ch-chain-" + run;
        long windowId = createWindow(channel, "2026-11-02T08:00:00Z", "2026-11-02T18:00:00Z");
        String s1 = publish(windowId, "2026-11-02T10:30:00Z", "2026-11-02T11:00:00Z");
        registerOutage(channel, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", 60);
        // 第二次中断与顺延后的 11:30-12:00 交叉，再顺延 30 分钟
        registerOutage(channel, "2026-11-02T11:15:00Z", "2026-11-02T12:00:00Z", 30);
        JsonNode node = schedule(s1);
        assertEquals("2026-11-02T12:00:00Z", node.get("startUtc").asText());
        assertEquals("2026-11-02T12:30:00Z", node.get("endUtc").asText());
        assertEquals("2026-11-02T10:30:00Z", node.get("originalStartUtc").asText());
        JsonNode chain = node.get("postponements");
        assertEquals(2, chain.size());
        assertEquals(60, chain.get(0).get("postponeMinutes").asInt());
        assertEquals("2026-11-02T11:30:00Z", chain.get(1).get("fromStartUtc").asText());
        assertEquals("2026-11-02T12:00:00Z", chain.get(1).get("toStartUtc").asText());
    }

    @Test
    void settlementSnapshotsScheduleAndLaterPostponementDoesNotRewriteIt() throws Exception {
        String channel = "ch-settle-" + run;
        long windowId = createWindow(channel, "2026-11-03T08:00:00Z", "2026-11-03T18:00:00Z");
        String settled = publish(windowId, "2026-11-03T10:30:00Z", "2026-11-03T11:00:00Z");
        // 未开始核销 -> 422
        outageService.setFixedNow("2026-11-03T09:00:00Z");
        postJson("/api/schedules/" + settled + "/settlements",
                settlementBody(key("stc"), key("stk"), "3.5"), 422);
        // 到点核销：快照 10:30-11:00
        outageService.setFixedNow("2026-11-03T10:45:00Z");
        String settlementKey = key("stk");
        JsonNode settlement = postOk("/api/schedules/" + settled + "/settlements",
                settlementBody(key("stc"), settlementKey, "3.5"));
        assertEquals("SETTLED", settlement.get("status").asText());
        assertEquals("2026-11-03T10:30:00Z", settlement.get("scheduledStartUtc").asText());
        assertEquals("2026-11-03T11:00:00Z", settlement.get("scheduledEndUtc").asText());
        assertEquals("3.5", settlement.get("volume").asText());
        assertEquals("COMPLETED", schedule(settled).get("status").asText());
        // 重复核销 -> 409
        postJson("/api/schedules/" + settled + "/settlements",
                settlementBody(key("stc"), key("stk"), "3.5"), 409);

        // 已核销排班与新中断窗口交叉 -> 整批 422，且核销记录不改写
        MvcResult result = postJson("/api/outages", outageBody(key("oc"), key("ok"), channel,
                "2026-11-03T10:45:00Z", "2026-11-03T11:30:00Z", 60), 422);
        assertEquals("OUTAGE_OVERLAPS_COMPLETED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        JsonNode reloaded = getOk("/api/settlements/" + settlementKey);
        assertEquals("2026-11-03T10:30:00Z", reloaded.get("scheduledStartUtc").asText());
        assertEquals("2026-11-03T11:00:00Z", reloaded.get("scheduledEndUtc").asText());
    }

    @Test
    void postponeThenSettleUsesCurrentWindowAsSnapshot() throws Exception {
        String channel = "ch-settle2-" + run;
        long windowId = createWindow(channel, "2026-11-04T08:00:00Z", "2026-11-04T18:00:00Z");
        String s1 = publish(windowId, "2026-11-04T10:30:00Z", "2026-11-04T11:00:00Z");
        outageService.setFixedNow("2026-11-04T09:00:00Z");
        registerOutage(channel, "2026-11-04T10:00:00Z", "2026-11-04T11:00:00Z", 60);
        outageService.setFixedNow("2026-11-04T11:45:00Z");
        JsonNode settlement = postOk("/api/schedules/" + s1 + "/settlements",
                settlementBody(key("stc"), key("stk"), "2"));
        // 核销引用当时（顺延后）的排班
        assertEquals("2026-11-04T11:30:00Z", settlement.get("scheduledStartUtc").asText());
        assertEquals("2026-11-04T12:00:00Z", settlement.get("scheduledEndUtc").asText());
    }

    private Map<String, Object> settlementBody(String commandKey, String settlementKey, String volume) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("settlementKey", settlementKey);
        body.put("volume", volume);
        return body;
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentOutageAndPublishNeverLeaveCrossingOccupancy() throws Exception {
        outageService.setFixedNow("2026-11-05T09:00:00Z");
        String channel = "ch-race-" + run;
        long windowId = createWindow(channel, "2026-11-05T08:00:00Z", "2026-11-05T18:00:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String publishCommand = key("sc");
        String scheduleKey = key("sk");
        String outageCommand = key("oc");
        String outageKey = key("ok");

        Future<Integer> publishFuture = pool.submit(() -> {
            gate.await();
            try {
                outageService.publishSchedule(publishCommand, scheduleKey, windowId,
                        "2026-11-05T10:30:00Z", "2026-11-05T10:45:00Z");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> outageFuture = pool.submit(() -> {
            gate.await();
            try {
                outageService.registerOutage(outageCommand, outageKey, channel,
                        "2026-11-05T10:00:00Z", "2026-11-05T11:00:00Z", "race outage", 60);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
        int outageStatus = outageFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(200, outageStatus);
        assertTrue(publishStatus == 200 || publishStatus == 409, "publish=" + publishStatus);

        JsonNode outageView = outage(outageKey);
        assertEquals("REGISTERED", outageView.get("status").asText());
        if (publishStatus == 200) {
            // 发布先提交：排班被中断整批顺延到 11:30，不出现在中断窗口内
            JsonNode node = schedule(scheduleKey);
            assertEquals("2026-11-05T11:30:00Z", node.get("startUtc").asText());
            assertEquals(1, node.get("postponements").size());
            assertEquals(1, outageView.get("affected").size());
        } else {
            // 中断先提交：排班落进中断窗口被拒，排班未落库（查询 404）
            assertEquals("SCHEDULE_NOT_FOUND", errorCode(() -> schedule(scheduleKey)));
            assertEquals(0, outageView.get("affected").size());
        }
    }

    @Test
    void concurrentSameOutageRequestIdBothReplayWithSinglePostponement() throws Exception {
        outageService.setFixedNow("2026-11-06T09:00:00Z");
        String channel = "ch-race-idem-" + run;
        long windowId = createWindow(channel, "2026-11-06T08:00:00Z", "2026-11-06T18:00:00Z");
        String s1 = publish(windowId, "2026-11-06T10:30:00Z", "2026-11-06T10:45:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String commandKey = key("oc");
        String outageKey = key("ok");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return registerStatus(commandKey, outageKey, channel);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return registerStatus(commandKey, outageKey, channel);
        });
        gate.countDown();
        assertEquals(200, f1.get(30, TimeUnit.SECONDS));
        assertEquals(200, f2.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        // 只有一次顺延：中断唯一、顺延链唯一
        assertEquals(1, schedule(s1).get("postponements").size());
        assertEquals("2026-11-06T11:30:00Z", schedule(s1).get("startUtc").asText());
        JsonNode view = outage(outageKey);
        assertEquals(1, view.get("affected").size());
    }

    private int registerStatus(String commandKey, String outageKey, String channel) {
        try {
            outageService.registerOutage(commandKey, outageKey, channel,
                    "2026-11-06T10:00:00Z", "2026-11-06T11:00:00Z", "same request", 60);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private String errorCode(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("expected ApiException");
        } catch (ApiException e) {
            return e.code();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------
    // started 标记随可控时钟变化
    // ------------------------------------------------------------------

    @Test
    void startedFlagFollowsControlledClock() throws Exception {
        String channel = "ch-clock-" + run;
        long windowId = createWindow(channel, "2026-11-07T08:00:00Z", "2026-11-07T18:00:00Z");
        String s1 = publish(windowId, "2026-11-07T10:30:00Z", "2026-11-07T11:00:00Z");
        outageService.setFixedNow("2026-11-07T10:00:00Z");
        assertFalse(schedule(s1).get("started").asBoolean());
        outageService.setFixedNow("2026-11-07T10:30:00Z");
        assertTrue(schedule(s1).get("started").asBoolean());
        assertNotNull(schedule(s1));
    }
}
