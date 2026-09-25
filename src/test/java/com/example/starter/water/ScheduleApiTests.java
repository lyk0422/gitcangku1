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
 * 轮灌排班与用水核销集成测试：渠道时段互斥、排班前置校验、核销时段约束、
 * 取消边界、快照固化、幂等与并发裁决。时间相关用例使用远未来（2027）与
 * 历史（2020）固定时刻，避免依赖运行日期。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleApiTests {

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
        String json = postJson(url, body, actor, 200).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode postError(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        String json = postJson(url, body, null, expectedStatus).getResponse().getContentAsString();
        return objectMapper.readTree(json);
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

    private Map<String, Object> scheduleBody(String commandKey, String scheduleKey, String allocationKey,
                                             String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("scheduleKey", scheduleKey);
        body.put("allocationKey", allocationKey);
        body.put("startUtc", start);
        body.put("endUtc", end);
        return body;
    }

    private JsonNode scheduleOk(String scheduleKey, String allocationKey, String start, String end)
            throws Exception {
        return postOk("/api/schedules", scheduleBody(key("sc"), scheduleKey, allocationKey, start, end), null);
    }

    private Map<String, Object> consumptionBody(String commandKey, String amount, String occurredUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("amount", amount);
        body.put("occurredUtc", occurredUtc);
        return body;
    }

    // ------------------------------------------------------------------
    // 排班主流程与查询
    // ------------------------------------------------------------------

    @Test
    void scheduleLifecycleAndQueries() throws Exception {
        String channel = "ch-sch-" + run;
        long windowId = createWindow(channel, "2027-01-01T00:00:00Z", "2027-01-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");

        String scheduleKey = key("sk");
        JsonNode created = scheduleOk(scheduleKey, allocationKey,
                "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z");
        assertEquals(scheduleKey, created.get("scheduleKey").asText());
        assertEquals(channel, created.get("channelId").asText());
        assertEquals(allocationKey, created.get("allocationKey").asText());
        assertEquals(windowId, created.get("windowId").asLong());
        assertEquals("ACTIVE", created.get("status").asText());
        // 快照 = 排班时剩余未核销水量（持有 10，未核销）
        assertEquals("10", created.get("remainingSnapshot").asText());
        assertTrue(created.get("cancelledUtc").isNull());

        // 渠道排班表
        JsonNode channelView = getOk("/api/channels/" + channel + "/schedules");
        assertEquals(channel, channelView.get("channelId").asText());
        assertEquals(1, channelView.get("schedules").size());
        assertEquals(scheduleKey, channelView.get("schedules").get(0).get("scheduleKey").asText());

        // 申请时段明细
        JsonNode allocationView = getOk("/api/allocations/" + allocationKey + "/schedules");
        assertEquals(allocationKey, allocationView.get("allocationKey").asText());
        assertEquals(1, allocationView.get("schedules").size());

        // 取消：立即释放占用，历史记录保留
        JsonNode cancelled = postOk("/api/schedules/" + scheduleKey + "/cancel",
                Map.of("commandKey", key("scc")), null);
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertNotNull(cancelled.get("cancelledUtc"));
        JsonNode afterCancel = getOk("/api/allocations/" + allocationKey + "/schedules");
        assertEquals(1, afterCancel.get("schedules").size());
        assertEquals("CANCELLED", afterCancel.get("schedules").get(0).get("status").asText());
        // 快照不随取消改写
        assertEquals("10", afterCancel.get("schedules").get(0).get("remainingSnapshot").asText());
    }

    @Test
    void invalidScheduleParamsReturn400() throws Exception {
        String channel = "ch-sbad-" + run;
        long windowId = createWindow(channel, "2027-02-01T00:00:00Z", "2027-02-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");
        // 时长 29 分钟 < 30
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-02-01T01:00:00Z", "2027-02-01T01:29:00Z"), null, 400);
        // 时长 721 分钟 > 720
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-02-01T00:00:00Z", "2027-02-01T12:01:00Z"), null, 400);
        // 起止倒置
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-02-01T02:00:00Z", "2027-02-01T01:00:00Z"), null, 400);
        // 超出窗口范围
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-01-31T23:30:00Z", "2027-02-01T00:30:00Z"), null, 400);
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-02-01T11:30:00Z", "2027-02-01T12:30:00Z"), null, 400);
        // 非法时间
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "not-a-time", "2027-02-01T01:00:00Z"), null, 400);
        // 申请不存在 -> 404
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), "no-such-" + run,
                "2027-02-01T01:00:00Z", "2027-02-01T02:00:00Z"), null, 404);
        // 取消不存在的排班 -> 404
        postJson("/api/schedules/no-such-" + run + "/cancel", Map.of("commandKey", key("scc")), null, 404);
        // 未知申请时段明细 -> 404
        mvc.perform(get("/api/allocations/no-such-" + run + "/schedules")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 渠道时段互斥
    // ------------------------------------------------------------------

    @Test
    void overlappingScheduleConflict409AdjacentAllowed() throws Exception {
        String channel = "ch-sov-" + run;
        long windowId = createWindow(channel, "2027-03-01T00:00:00Z", "2027-03-01T12:00:00Z", "20");
        String a1 = submitAndApprove(windowId, "user-1", "10", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "10", "bob");
        String first = key("sk");
        scheduleOk(first, a1, "2027-03-01T02:00:00Z", "2027-03-01T03:00:00Z");

        // 部分重叠（不同申请、同渠道）-> 409，报文给出冲突时段
        JsonNode error = postError("/api/schedules",
                scheduleBody(key("sc"), key("sk"), a2, "2027-03-01T02:30:00Z", "2027-03-01T03:30:00Z"), 409);
        assertEquals("SCHEDULE_OVERLAP", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(first));
        assertTrue(error.get("message").asText().contains("2027-03-01T02:00:00Z"));
        // 包含与被包含 -> 409
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), a2,
                "2027-03-01T01:00:00Z", "2027-03-01T04:00:00Z"), null, 409);
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), a2,
                "2027-03-01T02:15:00Z", "2027-03-01T02:45:00Z"), null, 409);
        // 端点相接 -> 合法
        scheduleOk(key("sk"), a2, "2027-03-01T03:00:00Z", "2027-03-01T04:00:00Z");
        scheduleOk(key("sk"), a2, "2027-03-01T01:00:00Z", "2027-03-01T02:00:00Z");
        // 不同渠道同刻重叠 -> 合法
        String otherChannel = "ch-sov2-" + run;
        long otherWindow = createWindow(otherChannel, "2027-03-01T00:00:00Z", "2027-03-01T12:00:00Z", "10");
        String a3 = submitAndApprove(otherWindow, "user-3", "5", "carol");
        scheduleOk(key("sk"), a3, "2027-03-01T02:00:00Z", "2027-03-01T03:00:00Z");
    }

    @Test
    void cancelReleasesChannelOccupancy() throws Exception {
        String channel = "ch-srel-" + run;
        long windowId = createWindow(channel, "2027-04-01T00:00:00Z", "2027-04-01T12:00:00Z", "20");
        String a1 = submitAndApprove(windowId, "user-1", "10", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "10", "bob");
        String first = key("sk");
        scheduleOk(first, a1, "2027-04-01T02:00:00Z", "2027-04-01T03:00:00Z");
        // 占用中：a2 同时段 -> 409
        postJson("/api/schedules", scheduleBody(key("sc"), key("sk"), a2,
                "2027-04-01T02:00:00Z", "2027-04-01T03:00:00Z"), null, 409);
        // 取消后立即释放：a2 同时段 -> 200
        postOk("/api/schedules/" + first + "/cancel", Map.of("commandKey", key("scc")), null);
        scheduleOk(key("sk"), a2, "2027-04-01T02:00:00Z", "2027-04-01T03:00:00Z");
    }

    // ------------------------------------------------------------------
    // 排班前置校验（422）
    // ------------------------------------------------------------------

    @Test
    void schedulePreconditionsReturn422() throws Exception {
        String channel = "ch-spre-" + run;
        long windowId = createWindow(channel, "2027-05-01T00:00:00Z", "2027-05-01T12:00:00Z", "20");
        // REQUESTED（未批准，剩余为零）-> 422
        String requested = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", requested);
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "5");
        postOk("/api/allocations", body, "alice");
        JsonNode err1 = postError("/api/schedules", scheduleBody(key("sc"), key("sk"), requested,
                "2027-05-01T01:00:00Z", "2027-05-01T02:00:00Z"), 422);
        assertEquals("SCHEDULE_NO_REMAINING", err1.get("code").asText());

        // 已取消 -> 422
        String approved = submitAndApprove(windowId, "user-2", "5", "bob");
        postOk("/api/allocations/" + approved + "/cancel", Map.of("commandKey", key("cc")), "bob");
        JsonNode err2 = postError("/api/schedules", scheduleBody(key("sc"), key("sk"), approved,
                "2027-05-01T01:00:00Z", "2027-05-01T02:00:00Z"), 422);
        assertEquals("SCHEDULE_NOT_ALLOWED", err2.get("code").asText());

        // 转让使剩余水量归零后不得再排班 -> 422
        String source = submitAndApprove(windowId, "user-3", "5", "carol");
        String target = key("ak");
        Map<String, Object> targetBody = new HashMap<>();
        targetBody.put("commandKey", key("ac"));
        targetBody.put("allocationKey", target);
        targetBody.put("windowId", windowId);
        targetBody.put("userId", "user-4");
        targetBody.put("amount", "5");
        postOk("/api/allocations", targetBody, "dave");
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", target);
        postOk("/api/transfers", transferBody, "carol");
        JsonNode err3 = postError("/api/schedules", scheduleBody(key("sc"), key("sk"), source,
                "2027-05-01T01:00:00Z", "2027-05-01T02:00:00Z"), 422);
        assertEquals("SCHEDULE_NO_REMAINING", err3.get("code").asText());
    }

    @Test
    void maxThreeActiveSlotsPerAllocation() throws Exception {
        String channel = "ch-smax-" + run;
        long windowId = createWindow(channel, "2027-06-01T00:00:00Z", "2027-06-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");
        scheduleOk(key("sk"), allocationKey, "2027-06-01T00:00:00Z", "2027-06-01T00:30:00Z");
        scheduleOk(key("sk"), allocationKey, "2027-06-01T01:00:00Z", "2027-06-01T01:30:00Z");
        String third = key("sk");
        scheduleOk(third, allocationKey, "2027-06-01T02:00:00Z", "2027-06-01T02:30:00Z");
        // 第 4 个生效时段 -> 422
        JsonNode error = postError("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-06-01T03:00:00Z", "2027-06-01T03:30:00Z"), 422);
        assertEquals("SCHEDULE_SLOT_LIMIT", error.get("code").asText());
        // 取消一个后可再排
        postOk("/api/schedules/" + third + "/cancel", Map.of("commandKey", key("scc")), null);
        scheduleOk(key("sk"), allocationKey, "2027-06-01T03:00:00Z", "2027-06-01T03:30:00Z");
    }

    @Test
    void cancelStartedOrRepeatedScheduleReturns409() throws Exception {
        // 历史窗口：时段起始时刻已过
        String channel = "ch-sstarted-" + run;
        long windowId = createWindow(channel, "2020-01-01T00:00:00Z", "2020-01-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");
        String started = key("sk");
        scheduleOk(started, allocationKey, "2020-01-01T01:00:00Z", "2020-01-01T02:00:00Z");
        JsonNode error = postError("/api/schedules/" + started + "/cancel",
                Map.of("commandKey", key("scc")), 409);
        assertEquals("SCHEDULE_ALREADY_STARTED", error.get("code").asText());

        // 未来时段：取消成功，重复取消 -> 409
        String futureChannel = "ch-srep-" + run;
        long futureWindow = createWindow(futureChannel, "2027-07-01T00:00:00Z", "2027-07-01T12:00:00Z", "10");
        String futureAllocation = submitAndApprove(futureWindow, "user-2", "10", "bob");
        String future = key("sk");
        scheduleOk(future, futureAllocation, "2027-07-01T01:00:00Z", "2027-07-01T02:00:00Z");
        postOk("/api/schedules/" + future + "/cancel", Map.of("commandKey", key("scc")), null);
        JsonNode repeated = postError("/api/schedules/" + future + "/cancel",
                Map.of("commandKey", key("scc")), 409);
        assertEquals("SCHEDULE_ALREADY_CANCELLED", repeated.get("code").asText());
    }

    // ------------------------------------------------------------------
    // 核销时段约束与快照固化
    // ------------------------------------------------------------------

    @Test
    void consumptionMustFallWithinActiveSlot() throws Exception {
        String channel = "ch-cons-" + run;
        long windowId = createWindow(channel, "2027-08-01T00:00:00Z", "2027-08-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");
        String scheduleKey = key("sk");
        scheduleOk(scheduleKey, allocationKey, "2027-08-01T02:00:00Z", "2027-08-01T04:00:00Z");

        // 时段起点（含）-> 200，剩余 10 - 4 = 6
        JsonNode consumed = postOk("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "4", "2027-08-01T02:00:00Z"), null);
        assertEquals("4", consumed.get("amount").asText());
        assertEquals("6", consumed.get("remainingAfter").asText());
        // 时段内 -> 200，剩余 6 - 1.5 = 4.5
        JsonNode consumed2 = postOk("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "1.5", "2027-08-01T03:00:00Z"), null);
        assertEquals("4.5", consumed2.get("remainingAfter").asText());
        // 时段终点（不含）-> 422，报文给出最近可用时段
        JsonNode atEnd = postError("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "1", "2027-08-01T04:00:00Z"), 422);
        assertEquals("NO_ACTIVE_SLOT", atEnd.get("code").asText());
        assertTrue(atEnd.get("message").asText().contains(scheduleKey));
        assertTrue(atEnd.get("message").asText().contains("2027-08-01T02:00:00Z"));
        // 时段外 -> 422
        postJson("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "1", "2027-08-01T05:00:00Z"), null, 422);
        // 核销超过剩余水量（4.5）-> 422 既有配额规则
        JsonNode exceed = postError("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "4.6", "2027-08-01T03:30:00Z"), 422);
        assertEquals("QUOTA_EXCEEDED", exceed.get("code").asText());
        // 恰好核销剩余全部 -> 200，剩余归零
        JsonNode last = postOk("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "4.5", "2027-08-01T03:45:00Z"), null);
        assertEquals("0", last.get("remainingAfter").asText());
        // 剩余归零后再核销 -> 422；再排班 -> 422
        postJson("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "0.001", "2027-08-01T03:50:00Z"), null, 422);
        JsonNode noRemaining = postError("/api/schedules", scheduleBody(key("sc"), key("sk"), allocationKey,
                "2027-08-01T06:00:00Z", "2027-08-01T07:00:00Z"), 422);
        assertEquals("SCHEDULE_NO_REMAINING", noRemaining.get("code").asText());

        // 快照固化：排班时剩余 10，不因后续核销改写
        JsonNode view = getOk("/api/allocations/" + allocationKey + "/schedules");
        assertEquals("10", view.get("schedules").get(0).get("remainingSnapshot").asText());
    }

    @Test
    void consumptionOnCancelledSlotReturns422() throws Exception {
        String channel = "ch-consc-" + run;
        long windowId = createWindow(channel, "2027-09-01T00:00:00Z", "2027-09-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");
        String scheduleKey = key("sk");
        scheduleOk(scheduleKey, allocationKey, "2027-09-01T02:00:00Z", "2027-09-01T03:00:00Z");
        postOk("/api/schedules/" + scheduleKey + "/cancel", Map.of("commandKey", key("scc")), null);
        // 已取消时段不再生效 -> 422
        JsonNode error = postError("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "1", "2027-09-01T02:30:00Z"), 422);
        assertEquals("NO_ACTIVE_SLOT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("没有生效时段"));
        // 申请不存在 -> 404
        postJson("/api/allocations/no-such-" + run + "/consumptions",
                consumptionBody(key("uc"), "1", "2027-09-01T02:30:00Z"), null, 404);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void scheduleIdempotencyReplayAndKeyReuse() throws Exception {
        String channel = "ch-sidem-" + run;
        long windowId = createWindow(channel, "2027-10-01T00:00:00Z", "2027-10-01T12:00:00Z", "10");
        String allocationKey = submitAndApprove(windowId, "user-1", "10", "alice");

        // 同键同参重放首次结果，只建一条记录
        String commandKey = key("sc");
        String scheduleKey = key("sk");
        Map<String, Object> body = scheduleBody(commandKey, scheduleKey, allocationKey,
                "2027-10-01T01:00:00Z", "2027-10-01T02:00:00Z");
        JsonNode first = postOk("/api/schedules", body, null);
        JsonNode replay = postOk("/api/schedules", body, null);
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/schedules").get("schedules").size());
        // 同键异参 -> 409
        postJson("/api/schedules", scheduleBody(commandKey, scheduleKey, allocationKey,
                "2027-10-01T03:00:00Z", "2027-10-01T04:00:00Z"), null, 409);
        // 取消命令重放
        String cancelCommand = key("scc");
        JsonNode cancelled = postOk("/api/schedules/" + scheduleKey + "/cancel",
                Map.of("commandKey", cancelCommand), null);
        JsonNode cancelledReplay = postOk("/api/schedules/" + scheduleKey + "/cancel",
                Map.of("commandKey", cancelCommand), null);
        assertEquals(cancelled, cancelledReplay);
        // 核销命令重放
        String consumeCommand = key("uc");
        scheduleOk(key("sk2"), allocationKey, "2027-10-01T05:00:00Z", "2027-10-01T06:00:00Z");
        Map<String, Object> consumeBody = consumptionBody(consumeCommand, "2", "2027-10-01T05:30:00Z");
        JsonNode consumed = postOk("/api/allocations/" + allocationKey + "/consumptions", consumeBody, null);
        JsonNode consumedReplay = postOk("/api/allocations/" + allocationKey + "/consumptions",
                consumeBody, null);
        assertEquals(consumed, consumedReplay);
        // 重放不重复扣减：再核销 8 应等于剩余全部（10 - 2）
        JsonNode rest = postOk("/api/allocations/" + allocationKey + "/consumptions",
                consumptionBody(key("uc"), "8", "2027-10-01T05:45:00Z"), null);
        assertEquals("0", rest.get("remainingAfter").asText());
    }

    @Test
    void failedCommandDoesNotOccupyKey() throws Exception {
        String channel = "ch-sfail-" + run;
        long windowId = createWindow(channel, "2027-11-01T00:00:00Z", "2027-11-01T12:00:00Z", "10");
        // 未批准申请排班 -> 422，失败不占键
        String allocationKey = key("ak");
        Map<String, Object> allocationBody = new HashMap<>();
        allocationBody.put("commandKey", key("ac"));
        allocationBody.put("allocationKey", allocationKey);
        allocationBody.put("windowId", windowId);
        allocationBody.put("userId", "user-1");
        allocationBody.put("amount", "10");
        postOk("/api/allocations", allocationBody, "alice");
        String commandKey = key("sc");
        Map<String, Object> body = scheduleBody(commandKey, key("sk"), allocationKey,
                "2027-11-01T01:00:00Z", "2027-11-01T02:00:00Z");
        postJson("/api/schedules", body, null, 422);
        // 批准后同键同参重试 -> 成功
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        JsonNode created = postOk("/api/schedules", body, null);
        assertEquals("ACTIVE", created.get("status").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentOverlappingSchedulesOnlyOneSucceeds() throws Exception {
        String channel = "ch-sconc-" + run;
        long windowId = createWindow(channel, "2027-12-01T00:00:00Z", "2027-12-01T12:00:00Z", "20");
        String a1 = submitAndApprove(windowId, "user-1", "10", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "10", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : new String[]{a1, a2}) {
            String commandKey = key("sc");
            String scheduleKey = key("sk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.createSchedule(commandKey, scheduleKey, allocationKey,
                            "2027-12-01T02:00:00Z", "2027-12-01T03:00:00Z");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int first = futures.get(0).get(30, TimeUnit.SECONDS);
        int second = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一渠道并发排班重叠时段：按事务提交顺序最多一个成功
        assertTrue((first == 200) != (second == 200),
                "first=" + first + " second=" + second);
        assertEquals(409, first == 200 ? second : first);
        // 渠道排班表恰好一条 ACTIVE
        JsonNode view = getOk("/api/channels/" + channel + "/schedules");
        assertEquals(1, view.get("schedules").size());
        assertEquals("ACTIVE", view.get("schedules").get(0).get("status").asText());
        assertTrue(view.get("schedules").get(0).get("cancelledUtc").isNull());
    }
}
