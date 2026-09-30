package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 灌渠中断与排班顺延 API 测试：主流程、422 失败回滚（已开始/越界/占用冲突）、
 * requestId 幂等、可控时钟下的状态分支、登记与发布并发裁决、历史用水核销快照。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutageApiTests {

    /** 测试专用可变时钟，固定在基准时刻并可向前拨，确定性验证“未开始/已开始”分支。 */
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-11-10T09:00:00Z");

        void setInstant(Instant value) {
            this.instant = value;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 用主时钟覆盖生产系统时钟，仅影响本测试类的独立上下文。 */
    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    private static final String T = "2026-11-10T";
    private static final String BASE = T + "09:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    @BeforeEach
    void resetClock() {
        clock.setInstant(Instant.parse(BASE));
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

    private long createWindow(String channelId, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", "100");
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String publish(long windowId, String user, String amount, String actor,
                           String scheduleStart, String scheduleEnd) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", user);
        body.put("amount", amount);
        body.put("scheduledStartUtc", scheduleStart);
        body.put("scheduledEndUtc", scheduleEnd);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey) throws Exception {
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
    }

    private Map<String, Object> outageBody(String commandKey, String requestId, String channel,
                                           String start, String end, int minutes, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestId", requestId);
        body.put("channelId", channel);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("postponeMinutes", minutes);
        body.put("reason", reason);
        return body;
    }

    private JsonNode registerOutage(String channel, String start, String end, int minutes, String reason)
            throws Exception {
        return postOk("/api/outages",
                outageBody(key("oc"), key("req"), channel, start, end, minutes, reason), null);
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

    private int registerStatus(String commandKey, String requestId, String channel,
                               String start, String end, int minutes, String reason) {
        try {
            waterService.registerOutage(commandKey, requestId, channel, start, end, minutes, reason);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    /** 数据库层不变式：同灌渠任一有效排班占用不得与任一已登记中断窗口交叉。 */
    private int scheduleOutageCrossingCount(String channel) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN supply_window w ON a.window_id = w.id"
                        + " JOIN canal_outage o ON o.channel_key = w.channel_id"
                        + " WHERE w.channel_id = ? AND a.status IN ('REQUESTED', 'APPROVED')"
                        + " AND a.scheduled_start_nanos IS NOT NULL"
                        + " AND a.scheduled_start_nanos < o.end_nanos"
                        + " AND o.start_nanos < a.scheduled_end_nanos",
                Integer.class, channel);
        return count == null ? 0 : count;
    }

    /** 数据库层不变式：同灌渠有效排班占用两两不得重叠。 */
    private int scheduleOverlapPairCount(String channel) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a1 JOIN supply_window w1 ON a1.window_id = w1.id"
                        + " JOIN allocation a2 ON a1.id < a2.id JOIN supply_window w2 ON a2.window_id = w2.id"
                        + " WHERE w1.channel_id = ? AND w2.channel_id = ?"
                        + " AND a1.status IN ('REQUESTED', 'APPROVED')"
                        + " AND a2.status IN ('REQUESTED', 'APPROVED')"
                        + " AND a1.scheduled_start_nanos IS NOT NULL"
                        + " AND a2.scheduled_start_nanos IS NOT NULL"
                        + " AND a1.scheduled_start_nanos < a2.scheduled_end_nanos"
                        + " AND a2.scheduled_start_nanos < a1.scheduled_end_nanos",
                Integer.class, channel, channel);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 主流程：题干场景 灌渠C 10:00-11:00 中断，10:30 排班顺延 60 分钟
    // ------------------------------------------------------------------

    @Test
    void outagePostponesAllCrossingUnstartedSchedulesBySameMinutes() throws Exception {
        String channel = "ch-c-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        String a2 = publish(windowId, "u2", "10", "bob", T + "10:45:00Z", T + "11:00:00Z");
        // 与中断右端点相邻，不受影响
        String a3 = publish(windowId, "u3", "10", "carol", T + "11:00:00Z", T + "11:15:00Z");

        String requestId = key("req");
        JsonNode outage = postOk("/api/outages", outageBody(key("oc"), requestId, channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "灌渠C施工中断"), null);
        assertEquals(requestId, outage.get("requestId").asText());
        assertEquals(channel, outage.get("channelId").asText());
        assertEquals("灌渠C施工中断", outage.get("reason").asText());
        assertEquals(60, outage.get("postponeMinutes").asInt());
        assertEquals("REGISTERED", outage.get("status").asText());
        assertEquals(2, outage.get("postponements").size());

        JsonNode p1 = outage.get("postponements").get(0);
        assertEquals(a1, p1.get("allocationKey").asText());
        assertEquals(T + "10:30:00Z", p1.get("originalStartUtc").asText());
        assertEquals(T + "10:45:00Z", p1.get("originalEndUtc").asText());
        assertEquals(T + "11:30:00Z", p1.get("newStartUtc").asText());
        assertEquals(T + "11:45:00Z", p1.get("newEndUtc").asText());
        assertEquals(60, p1.get("postponeMinutes").asInt());
        assertEquals("灌渠C施工中断", p1.get("reason").asText());
        JsonNode p2 = outage.get("postponements").get(1);
        assertEquals(a2, p2.get("allocationKey").asText());
        assertEquals(T + "11:45:00Z", p2.get("newStartUtc").asText());
        assertEquals(T + "12:00:00Z", p2.get("newEndUtc").asText());

        // 申请当前窗口为新窗口；a3 未被顺延
        assertEquals(T + "11:30:00Z", allocationView(windowId, a1).get("scheduledStartUtc").asText());
        assertEquals(T + "11:45:00Z", allocationView(windowId, a2).get("scheduledStartUtc").asText());
        assertEquals(T + "11:00:00Z", allocationView(windowId, a3).get("scheduledStartUtc").asText());
        // 顺延不改变水量口径（REQUESTED 仍不占额度）
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 查询：按 requestId 与按灌渠均给出原排班、新窗口、原因与对应关系
        JsonNode byRequest = getOk("/api/outages/" + requestId);
        assertEquals(outage, byRequest);
        JsonNode byChannel = getOk("/api/channels/" + channel + "/outages");
        assertEquals(1, byChannel.get("outages").size());
        assertEquals(requestId, byChannel.get("outages").get(0).get("requestId").asText());
        assertEquals(2, byChannel.get("outages").get(0).get("postponements").size());

        // 最终无排班与中断交叉
        assertEquals(0, scheduleOutageCrossingCount(channel));
        assertEquals(0, scheduleOverlapPairCount(channel));
    }

    @Test
    void outageWithoutCrossingSchedulesRegistersWithEmptyPostponements() throws Exception {
        String channel = "ch-empty-" + run;
        createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        JsonNode outage = registerOutage(channel, T + "10:00:00Z", T + "11:00:00Z", 60, "无排班中断");
        assertEquals(0, outage.get("postponements").size());
    }

    @Test
    void cancelledScheduleIsNotPostponed() throws Exception {
        String channel = "ch-cancel-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode outage = registerOutage(channel, T + "10:00:00Z", T + "11:00:00Z", 60, "中断");
        assertEquals(0, outage.get("postponements").size());
        assertEquals("CANCELLED", allocationView(windowId, a1).get("status").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支：整批回滚
    // ------------------------------------------------------------------

    @Test
    void postponeBeyondWindowReturns422AndRollsBackEverything() throws Exception {
        String channel = "ch-beyond-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "12:00:00Z");
        // 两条相邻排班都与中断 10:00-11:00 交叉；a2 原结束 11:15，顺延 60 分后 12:15 超出窗口 12:00
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:35:00Z", T + "10:50:00Z");
        String a2 = publish(windowId, "u2", "10", "bob", T + "10:50:00Z", T + "11:15:00Z");
        String requestId = key("req");

        // 顺延 60 分钟：a2 新窗口 11:50-12:15 超出配水窗口 12:00 -> 422
        MvcResult result = postJson("/api/outages",
                outageBody(key("oc"), requestId, channel, T + "10:00:00Z", T + "11:00:00Z", 60, "越界"),
                null, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("POSTPONE_OUT_OF_WINDOW", error.get("code").asText());

        // 中断状态未写入：按 requestId 查询 404，灌渠列表为空
        mvc.perform(get("/api/outages/" + requestId)).andExpect(status().isNotFound());
        assertEquals(0, getOk("/api/channels/" + channel + "/outages").get("outages").size());
        // 排班保持原窗口（a1 虽本身不越界，也随整批回滚未动）
        assertEquals(T + "10:35:00Z", allocationView(windowId, a1).get("scheduledStartUtc").asText());
        assertEquals(T + "10:50:00Z", allocationView(windowId, a2).get("scheduledStartUtc").asText());
        // 额度无影响
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 失败不占用 requestId/commandKey：顺延 45 分钟，新窗口 11:20-11:35、11:35-12:00 均脱离中断且在窗口内 -> 成功
        JsonNode retry = postOk("/api/outages",
                outageBody(key("oc"), requestId, channel, T + "10:00:00Z", T + "11:00:00Z", 45, "越界"),
                null);
        assertEquals(2, retry.get("postponements").size());
        assertEquals(T + "11:20:00Z", retry.get("postponements").get(0).get("newStartUtc").asText());
        assertEquals(T + "11:35:00Z", retry.get("postponements").get(0).get("newEndUtc").asText());
        assertEquals(T + "11:35:00Z", retry.get("postponements").get(1).get("newStartUtc").asText());
        assertEquals(T + "12:00:00Z", retry.get("postponements").get(1).get("newEndUtc").asText());
        assertEquals(0, scheduleOutageCrossingCount(channel));
        assertEquals(0, scheduleOverlapPairCount(channel));
    }

    @Test
    void alreadyStartedScheduleReturns422AndChangesNothing() throws Exception {
        String channel = "ch-started-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        String a2 = publish(windowId, "u2", "10", "bob", T + "10:45:00Z", T + "11:00:00Z");

        // 时钟拨到 10:30：排班刚开始（开始时刻 <= 当前时刻即已开始，左闭）-> 422
        clock.setInstant(Instant.parse(T + "10:30:00Z"));
        String requestId = key("req");
        MvcResult result = postJson("/api/outages",
                outageBody(key("oc"), requestId, channel, T + "10:00:00Z", T + "11:00:00Z", 60, "已开始"),
                null, 422);
        assertEquals("SCHEDULE_ALREADY_STARTED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        // 整批回滚：a2 虽未开始也不得顺延
        assertEquals(T + "10:45:00Z", allocationView(windowId, a2).get("scheduledStartUtc").asText());
        assertEquals(0, getOk("/api/channels/" + channel + "/outages").get("outages").size());

        // 时钟拨到 10:29:59：排班尚未开始 -> 顺延成功
        clock.setInstant(Instant.parse(T + "10:29:59Z"));
        JsonNode outage = postOk("/api/outages",
                outageBody(key("oc"), requestId, channel, T + "10:00:00Z", T + "11:00:00Z", 60, "已开始"),
                null);
        assertEquals(2, outage.get("postponements").size());
        assertEquals(T + "11:30:00Z", allocationView(windowId, a1).get("scheduledStartUtc").asText());
        assertEquals(0, scheduleOutageCrossingCount(channel));
    }

    @Test
    void postponeConflictingOtherOccupantReturns422ThenLargerShiftSucceeds() throws Exception {
        String channel = "ch-conflict-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        // 11:30-11:45 已有占用；顺延 60 分钟会撞它
        publish(windowId, "u2", "10", "bob", T + "11:30:00Z", T + "11:45:00Z");

        postJson("/api/outages",
                outageBody(key("oc"), key("req"), channel, T + "10:00:00Z", T + "11:00:00Z", 60, "冲突"),
                null, 422).getResponse();
        // a1 保持原窗口
        assertEquals(T + "10:30:00Z", allocationView(windowId, a1).get("scheduledStartUtc").asText());
        assertEquals(0, getOk("/api/channels/" + channel + "/outages").get("outages").size());

        // 顺延 90 分钟 -> 12:00-12:15，不与 11:30 占用重叠，成功
        JsonNode outage = postOk("/api/outages",
                outageBody(key("oc"), key("req"), channel, T + "10:00:00Z", T + "11:00:00Z", 90, "冲突"),
                null);
        assertEquals(T + "12:00:00Z",
                outage.get("postponements").get(0).get("newStartUtc").asText());
        assertEquals(0, scheduleOverlapPairCount(channel));
        assertEquals(0, scheduleOutageCrossingCount(channel));
    }

    @Test
    void adjacentEndpointsNeverCountAsOverlap() throws Exception {
        String channel = "ch-adj-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "12:00:00Z");
        // 两条首尾相接的排班同时被顺延，顺延后仍首尾相接
        publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        publish(windowId, "u2", "10", "bob", T + "10:45:00Z", T + "11:00:00Z");
        JsonNode outage = registerOutage(channel, T + "10:00:00Z", T + "11:00:00Z", 30, "相邻");
        assertEquals(2, outage.get("postponements").size());
        assertEquals(T + "11:00:00Z", outage.get("postponements").get(0).get("newStartUtc").asText());
        assertEquals(T + "11:15:00Z", outage.get("postponements").get(0).get("newEndUtc").asText());
        assertEquals(T + "11:15:00Z", outage.get("postponements").get(1).get("newStartUtc").asText());
        assertEquals(0, scheduleOverlapPairCount(channel));
        // 顺延后首条排班起点与中断右端点 11:00 相邻，不算与中断交叉
        assertEquals(0, scheduleOutageCrossingCount(channel));
    }

    // ------------------------------------------------------------------
    // 排班发布与中断冲突
    // ------------------------------------------------------------------

    @Test
    void publishRejectsOutageOverlapOccupantOverlapAndOutOfWindow() throws Exception {
        String channel = "ch-pub-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        registerOutage(channel, T + "10:00:00Z", T + "11:00:00Z", 60, "先登记中断");
        // 与中断交叉 -> 409
        postJson("/api/allocations", scheduleBody(key("ac"), key("ak"), windowId,
                T + "10:30:00Z", T + "10:45:00Z"), "alice", 409);
        // 与中断右端点相邻 -> 200
        String adjacent = publish(windowId, "u1", "10", "alice", T + "11:00:00Z", T + "11:30:00Z");
        // 与既有占用重叠 -> 409
        postJson("/api/allocations", scheduleBody(key("ac"), key("ak"), windowId,
                T + "11:05:00Z", T + "11:10:00Z"), "bob", 409);
        // 与既有占用相邻 -> 200
        publish(windowId, "u2", "10", "bob", T + "11:30:00Z", T + "11:45:00Z");
        // 超出配水窗口 -> 422
        MvcResult beyond = postJson("/api/allocations", scheduleBody(key("ac"), key("ak"), windowId,
                T + "12:30:00Z", T + "13:30:00Z"), "carol", 422);
        assertEquals("SCHEDULE_OUT_OF_WINDOW",
                objectMapper.readTree(beyond.getResponse().getContentAsString()).get("code").asText());
        // 只给排班开始不给结束 -> 400
        Map<String, Object> half = scheduleBody(key("ac"), key("ak"), windowId,
                T + "12:00:00Z", T + "12:30:00Z");
        half.remove("scheduledEndUtc");
        postJson("/api/allocations", half, "alice", 400);
        // 已发布的相邻排班保持存在
        assertEquals(T + "11:00:00Z", allocationView(windowId, adjacent).get("scheduledStartUtc").asText());
        assertEquals(0, scheduleOverlapPairCount(channel));
    }

    private Map<String, Object> scheduleBody(String commandKey, String allocationKey, long windowId,
                                             String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", "u");
        body.put("amount", "10");
        body.put("scheduledStartUtc", start);
        body.put("scheduledEndUtc", end);
        return body;
    }

    // ------------------------------------------------------------------
    // requestId 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstResultDifferentMinutesReturns409() throws Exception {
        String channel = "ch-idem-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        String requestId = key("req");
        String commandKey = key("oc");
        Map<String, Object> body = outageBody(commandKey, requestId, channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "幂等");

        JsonNode first = postOk("/api/outages", body, null);
        JsonNode replay = postOk("/api/outages", body, null);
        assertEquals(first, replay);
        // 只有一条中断、一条顺延关系
        assertEquals(1, getOk("/api/channels/" + channel + "/outages").get("outages").size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_postponement WHERE outage_request_id = ?",
                Integer.class, requestId));

        // 同 commandKey 换顺延分钟数 -> 409（命令键改参）
        postJson("/api/outages", outageBody(commandKey, requestId, channel,
                T + "10:00:00Z", T + "11:00:00Z", 30, "幂等"), null, 409);
        // 换 commandKey 但同一 requestId 换顺延分钟数 -> 409（业务幂等键改参）
        postJson("/api/outages", outageBody(key("oc"), requestId, channel,
                T + "10:00:00Z", T + "11:00:00Z", 90, "幂等"), null, 409);
        // 同 requestId 换渠道/窗口/原因同样 409
        postJson("/api/outages", outageBody(key("oc"), requestId, "ch-other-" + run,
                T + "10:00:00Z", T + "11:00:00Z", 60, "幂等"), null, 409);
        postJson("/api/outages", outageBody(key("oc"), requestId, channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "改原因"), null, 409);

        // 换 commandKey 但同 requestId 同参 -> 重放首次结果（受影响排班与新窗口一致）
        JsonNode replayOtherCommand = postOk("/api/outages",
                outageBody(key("oc"), requestId, channel, T + "10:00:00Z", T + "11:00:00Z", 60, "幂等"),
                null);
        assertEquals(first, replayOtherCommand);
        assertEquals(1, getOk("/api/channels/" + channel + "/outages").get("outages").size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_postponement WHERE outage_request_id = ?",
                Integer.class, requestId));
    }

    @Test
    void invalidOutageParamsReturn400() throws Exception {
        String channel = "ch-bad-" + run;
        // 起止倒置
        postJson("/api/outages", outageBody(key("oc"), key("req"), channel,
                T + "11:00:00Z", T + "10:00:00Z", 60, "x"), null, 400);
        // 顺延分钟数 0 / 负数 / 缺失
        postJson("/api/outages", outageBody(key("oc"), key("req"), channel,
                T + "10:00:00Z", T + "11:00:00Z", 0, "x"), null, 400);
        postJson("/api/outages", outageBody(key("oc"), key("req"), channel,
                T + "10:00:00Z", T + "11:00:00Z", -30, "x"), null, 400);
        Map<String, Object> noMinutes = outageBody(key("oc"), key("req"), channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "x");
        noMinutes.remove("postponeMinutes");
        postJson("/api/outages", noMinutes, null, 400);
        // 原因缺失/空白
        postJson("/api/outages", outageBody(key("oc"), key("req"), channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "   "), null, 400);
        // 非法键
        postJson("/api/outages", outageBody(key("oc"), "bad request!", channel,
                T + "10:00:00Z", T + "11:00:00Z", 60, "x"), null, 400);
        // 未知中断查询 -> 404
        mvc.perform(get("/api/outages/missing-" + run)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 并发：登记中断与排班发布竞争，不能留下交叉占用
    // ------------------------------------------------------------------

    @Test
    void outageRegisterAndSchedulePublishRaceLeavesNoCrossingOccupancy() throws Exception {
        String channel = "ch-race-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        // 既有排班 10:40-10:50：中断若先提交，它被顺延到 11:40-11:50
        publish(windowId, "u0", "10", "alice", T + "10:40:00Z", T + "10:50:00Z");

        String outageCommand = key("oc");
        String outageRequest = key("req");
        String publishCommand = key("ac");
        String publishKey = key("ak");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> outageFuture = pool.submit(() -> {
            gate.await();
            return registerStatus(outageCommand, outageRequest, channel,
                    T + "10:00:00Z", T + "11:00:00Z", 60, "竞争中断");
        });
        Future<Integer> publishFuture = pool.submit(() -> {
            gate.await();
            // 新排班瞄准顺延后的落点 11:40-11:45：中断先提交则与被顺延排班冲突
            try {
                waterService.submitAllocation(publishCommand, publishKey, windowId, "u9", "10", "bob",
                        T + "11:40:00Z", T + "11:45:00Z");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int outageStatus = outageFuture.get(30, TimeUnit.SECONDS);
        int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 中断先提交：发布因与顺延后的 11:40-11:50 冲突而 409；发布先提交：顺延撞占用而 422
        assertTrue((outageStatus == 200) != (publishStatus == 200),
                "登记与发布竞争恰好一方成功: outage=" + outageStatus + " publish=" + publishStatus);
        if (outageStatus == 200) {
            assertEquals(409, publishStatus);
        } else {
            assertEquals(422, outageStatus);
            assertEquals(200, publishStatus);
        }
        // 业务结果核对：无任何排班与中断交叉、无排班两两重叠
        assertEquals(0, scheduleOutageCrossingCount(channel), "竞争后不得留下交叉占用");
        assertEquals(0, scheduleOverlapPairCount(channel), "竞争后不得留下重叠占用");
        // 中断至多一条
        assertEquals(outageStatus == 200 ? 1 : 0,
                getOk("/api/channels/" + channel + "/outages").get("outages").size());
    }

    // ------------------------------------------------------------------
    // 历史用水核销：快照当时排班，顺延不改写已完成记录
    // ------------------------------------------------------------------

    @Test
    void settlementKeepsScheduleSnapshotAfterPostponement() throws Exception {
        String channel = "ch-usage-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String a1 = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        approve(a1);

        // 中断前核销 4 立方米，快照原排班 10:30-10:45
        String usageKey = key("uk");
        Map<String, Object> settleBody = new HashMap<>();
        settleBody.put("commandKey", key("sc"));
        settleBody.put("usageKey", usageKey);
        settleBody.put("volume", "4");
        JsonNode settlement = postOk("/api/allocations/" + a1 + "/settlements", settleBody, "alice");
        assertEquals(usageKey, settlement.get("usageKey").asText());
        assertEquals(T + "10:30:00Z", settlement.get("scheduleStartUtc").asText());
        assertEquals(T + "10:45:00Z", settlement.get("scheduleEndUtc").asText());
        assertEquals("4", settlement.get("volume").asText());
        assertEquals("alice", settlement.get("actor").asText());

        // 同 commandKey 重放，返回首次结果且不新增核销
        JsonNode replay = postOk("/api/allocations/" + a1 + "/settlements", settleBody, "alice");
        assertEquals(settlement, replay);
        assertEquals(1, getOk("/api/allocations/" + a1 + "/settlements").get("settlements").size());

        // 之后顺延 60 分钟
        registerOutage(channel, T + "10:00:00Z", T + "11:00:00Z", 60, "中断");
        // 申请当前窗口已变为 11:30-11:45
        assertEquals(T + "11:30:00Z", allocationView(windowId, a1).get("scheduledStartUtc").asText());
        // 历史核销仍引用当时的 10:30-10:45，不被改写
        JsonNode settlements = getOk("/api/allocations/" + a1 + "/settlements");
        assertEquals(1, settlements.get("settlements").size());
        JsonNode snapshot = settlements.get("settlements").get(0);
        assertEquals(T + "10:30:00Z", snapshot.get("scheduleStartUtc").asText());
        assertEquals(T + "10:45:00Z", snapshot.get("scheduleEndUtc").asText());

        // 再核销 7 立方米将超过持有额度 10（累计 11）-> 422，不产生记录
        postJson("/api/allocations/" + a1 + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", key("uk"), "volume", "7"), "alice", 422);
        // 核销 6 立方米：累计 10，恰好等于持有额度 -> 成功
        postOk("/api/allocations/" + a1 + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", key("uk"), "volume", "6"), "alice");
        assertEquals(2, getOk("/api/allocations/" + a1 + "/settlements").get("settlements").size());
        // usageKey 换 commandKey 复用 -> 409
        postJson("/api/allocations/" + a1 + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", usageKey, "volume", "1"), "alice", 409);

        // 申请取消后历史核销仍可查询，快照不变
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode afterCancel = getOk("/api/allocations/" + a1 + "/settlements");
        assertEquals(2, afterCancel.get("settlements").size());
        assertEquals(T + "10:30:00Z",
                afterCancel.get("settlements").get(0).get("scheduleStartUtc").asText());
    }

    @Test
    void settlementRequiresExistingApprovedAllocation() throws Exception {
        String channel = "ch-settle-" + run;
        long windowId = createWindow(channel, T + "10:00:00Z", T + "13:00:00Z");
        String requested = publish(windowId, "u1", "10", "alice", T + "10:30:00Z", T + "10:45:00Z");
        // REQUESTED 不能核销 -> 409
        postJson("/api/allocations/" + requested + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", key("uk"), "volume", "1"), "alice", 409);
        // 申请不存在 -> 404
        postJson("/api/allocations/missing-" + run + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", key("uk"), "volume", "1"), "alice", 404);
        mvc.perform(get("/api/allocations/missing-" + run + "/settlements"))
                .andExpect(status().isNotFound());
        // 非法水量 -> 400
        approve(requested);
        postJson("/api/allocations/" + requested + "/settlements",
                Map.of("commandKey", key("sc"), "usageKey", key("uk"), "volume", "0"), "alice", 400);
        // 无任何核销写入
        assertEquals(0, getOk("/api/allocations/" + requested + "/settlements").get("settlements").size());
    }
}
