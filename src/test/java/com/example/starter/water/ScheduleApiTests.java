package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
 * 轮灌排班与用水核销 API 测试：渠道时段互斥、排班前置校验、核销时段约束、
 * 取消规则、查询、幂等重放与并发裁决。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
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

    @Autowired
    private DataSource dataSource;

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

    private long createWindow(String channelId, String day, String startHour, String endHour,
                              String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", day + "T" + startHour + ":00:00Z");
        body.put("endUtc", day + "T" + endHour + ":00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String approvedAllocation(long windowId, String userId, String amount, String actor)
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

    private Map<String, Object> scheduleBody(String commandKey, String scheduleKey, String allocationKey,
                                             String startUtc, String endUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("scheduleKey", scheduleKey);
        body.put("allocationKey", allocationKey);
        body.put("startUtc", startUtc);
        body.put("endUtc", endUtc);
        return body;
    }

    private JsonNode scheduleOk(String allocationKey, String actor, String start, String end)
            throws Exception {
        return postOk("/api/schedules",
                scheduleBody(key("sc"), key("sk"), allocationKey, start, end), actor);
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

    private Map<String, Object> usageBody(String commandKey, String usageKey, String allocationKey,
                                          String volume, String usedAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("usageKey", usageKey);
        body.put("allocationKey", allocationKey);
        body.put("volume", volume);
        body.put("usedAtUtc", usedAt);
        return body;
    }

    private JsonNode findInChannel(String channelId, String scheduleKey) throws Exception {
        JsonNode resp = getOk("/api/channels/" + channelId + "/schedules");
        for (JsonNode node : resp.get("schedules")) {
            if (scheduleKey.equals(node.get("scheduleKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("schedule not found in channel: " + scheduleKey);
    }

    private long activeCount(String channelId) throws Exception {
        JsonNode resp = getOk("/api/channels/" + channelId + "/schedules");
        long count = 0;
        for (JsonNode node : resp.get("schedules")) {
            if ("ACTIVE".equals(node.get("status").asText())) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 主流程：排班、查询、取消释放、再占用
    // ------------------------------------------------------------------

    @Test
    void scheduleQueryCancelAndReoccupy() throws Exception {
        String channel = "ch-life-" + run;
        long windowId = createWindow(channel, "2026-11-01", "00", "03", "20");
        String allocation = approvedAllocation(windowId, "user-1", "10", "alice");

        JsonNode schedule = scheduleOk(allocation, "alice",
                "2026-11-01T00:00:00Z", "2026-11-01T00:30:00Z");
        assertEquals(channel, schedule.get("channelId").asText());
        assertEquals(windowId, schedule.get("windowId").asLong());
        assertEquals(allocation, schedule.get("allocationKey").asText());
        assertEquals("2026-11-01T00:00:00Z", schedule.get("startUtc").asText());
        assertEquals("2026-11-01T00:30:00Z", schedule.get("endUtc").asText());
        assertEquals("ACTIVE", schedule.get("status").asText());
        // 固化申请时剩余水量快照
        assertEquals("10", schedule.get("remainingSnapshot").asText());
        assertTrue(schedule.get("cancelledUtc").isNull());

        // 渠道排班表
        JsonNode channelResp = getOk("/api/channels/" + channel + "/schedules");
        assertEquals(channel, channelResp.get("channelId").asText());
        assertEquals(1, channelResp.get("schedules").size());
        // 申请时段明细
        JsonNode allocResp = getOk("/api/allocations/" + allocation + "/schedules");
        assertEquals(1, allocResp.get("schedules").size());

        // 取消 -> 立即释放占用，历史记录保留
        String scheduleKey = schedule.get("scheduleKey").asText();
        JsonNode cancelled = postOk("/api/schedules/" + scheduleKey + "/cancel",
                Map.of("commandKey", key("scc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertNotNull(cancelled.get("cancelledUtc"));
        assertEquals(0, activeCount(channel));
        assertEquals("CANCELLED", findInChannel(channel, scheduleKey).get("status").asText());

        // 释放后同一时刻可被另一申请重新占用
        String other = approvedAllocation(windowId, "user-2", "3", "bob");
        JsonNode reoccupied = scheduleOk(other, "bob",
                "2026-11-01T00:00:00Z", "2026-11-01T00:30:00Z");
        assertEquals("ACTIVE", reoccupied.get("status").asText());
        assertEquals(1, activeCount(channel));
    }

    // ------------------------------------------------------------------
    // 渠道互斥：重叠 409 带冲突时段，端点相接与跨渠道合法
    // ------------------------------------------------------------------

    @Test
    void overlappingSlotReturns409WithConflictsButAdjacentAllowed() throws Exception {
        String channel = "ch-mutex-" + run;
        long windowId = createWindow(channel, "2026-11-02", "00", "04", "10");
        String a = approvedAllocation(windowId, "user-a", "5", "alice");
        String b = approvedAllocation(windowId, "user-b", "5", "bob");

        JsonNode first = scheduleOk(a, "alice", "2026-11-02T01:00:00Z", "2026-11-02T02:00:00Z");

        // 区间重叠 -> 409，details.conflicts 给出冲突时段
        MvcResult result = postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), b,
                        "2026-11-02T01:30:00Z", "2026-11-02T02:30:00Z"), "bob", 409);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SCHEDULE_OVERLAP", error.get("code").asText());
        JsonNode conflicts = error.get("details").get("conflicts");
        assertEquals(1, conflicts.size());
        assertEquals(first.get("scheduleKey").asText(), conflicts.get(0).get("scheduleKey").asText());

        // 完全包含同样冲突
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), b,
                        "2026-11-02T01:15:00Z", "2026-11-02T01:45:00Z"), "bob", 409);

        // 端点相接合法（左闭右开）
        scheduleOk(b, "bob", "2026-11-02T02:00:00Z", "2026-11-02T02:30:00Z");
        scheduleOk(b, "bob", "2026-11-02T00:00:00Z", "2026-11-02T01:00:00Z");
        assertEquals(3, activeCount(channel));

        // 不同渠道同一时刻合法
        String otherChannel = "ch-mutex-other-" + run;
        long otherWindow = createWindow(otherChannel, "2026-11-02", "00", "04", "10");
        String c = approvedAllocation(otherWindow, "user-c", "5", "carol");
        scheduleOk(c, "carol", "2026-11-02T01:00:00Z", "2026-11-02T02:00:00Z");
    }

    // ------------------------------------------------------------------
    // 排班前置校验
    // ------------------------------------------------------------------

    @Test
    void schedulingPreconditionsRejectInvalidRequests() throws Exception {
        String channel = "ch-pre-" + run;
        long windowId = createWindow(channel, "2026-11-03", "00", "14", "20");

        // REQUESTED 申请不能排班 -> 422
        String requestedKey = key("ak");
        postOk("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", requestedKey, "windowId", windowId,
                "userId", "user-r", "amount", "5"), "zoe");
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), requestedKey,
                        "2026-11-03T00:00:00Z", "2026-11-03T00:30:00Z"), "zoe", 422);

        // 已取消申请不能排班 -> 422
        String cancelled = approvedAllocation(windowId, "user-c", "2", "carl");
        postOk("/api/allocations/" + cancelled + "/cancel", Map.of("commandKey", key("cc")), "carl");
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), cancelled,
                        "2026-11-03T00:00:00Z", "2026-11-03T00:30:00Z"), "carl", 422);

        // 非申请人排班 -> 409
        String owned = approvedAllocation(windowId, "user-o", "5", "alice");
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), owned,
                        "2026-11-03T00:00:00Z", "2026-11-03T00:30:00Z"), "bob", 409);

        // 申请不存在 -> 404
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), "missing-" + run,
                        "2026-11-03T00:00:00Z", "2026-11-03T00:30:00Z"), "alice", 404);

        // 时长 30 与 720 分钟合法；29 与 721 -> 400
        String dur = approvedAllocation(windowId, "user-d", "10", "dave");
        scheduleOk(dur, "dave", "2026-11-03T01:00:00Z", "2026-11-03T01:30:00Z");
        scheduleOk(dur, "dave", "2026-11-03T01:30:00Z", "2026-11-03T13:30:00Z");
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), dur,
                        "2026-11-03T20:00:00Z", "2026-11-03T20:29:00Z"), "dave", 400);
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), dur,
                        "2026-11-03T20:00:00Z", "2026-11-04T08:01:00Z"), "dave", 400);

        // 时段超出窗口边界 -> 400（即使时长合法）
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), dur,
                        "2026-11-03T13:45:00Z", "2026-11-03T14:15:00Z"), "dave", 400);
    }

    @Test
    void maxThreeActiveSlotsAndZeroRemainingRejectScheduling() throws Exception {
        String channel = "ch-max-" + run;
        long windowId = createWindow(channel, "2026-11-04", "00", "05", "20");
        String allocation = approvedAllocation(windowId, "user-1", "10", "alice");

        // 同一申请三个相邻生效时段
        scheduleOk(allocation, "alice", "2026-11-04T00:00:00Z", "2026-11-04T00:30:00Z");
        scheduleOk(allocation, "alice", "2026-11-04T00:30:00Z", "2026-11-04T01:00:00Z");
        scheduleOk(allocation, "alice", "2026-11-04T01:00:00Z", "2026-11-04T01:30:00Z");
        // 第四个 -> 422
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), allocation,
                        "2026-11-04T01:30:00Z", "2026-11-04T02:00:00Z"), "alice", 422);

        // 取消一个后可再排（已取消不再计数）
        JsonNode mine = getOk("/api/allocations/" + allocation + "/schedules");
        String firstKey = mine.get("schedules").get(0).get("scheduleKey").asText();
        postOk("/api/schedules/" + firstKey + "/cancel", Map.of("commandKey", key("scc")), "alice");
        JsonNode fourth = scheduleOk(allocation, "alice",
                "2026-11-04T01:30:00Z", "2026-11-04T02:00:00Z");
        assertEquals("ACTIVE", fourth.get("status").asText());

        // 全额核销后剩余未核销水量归零，不能再排班
        // 取消 01:30 段，改排 02:00 段以承载核销时刻，生效时段仍为 3 个
        postOk("/api/schedules/" + fourth.get("scheduleKey").asText() + "/cancel",
                Map.of("commandKey", key("scc")), "alice");
        scheduleOk(allocation, "alice", "2026-11-04T02:00:00Z", "2026-11-04T02:30:00Z");
        postOk("/api/usages", usageBody(key("uc"), key("uk"), allocation, "10",
                "2026-11-04T02:15:00Z"), "alice");
        JsonNode drainedView = allocationView(windowId, allocation);
        assertEquals("10", drainedView.get("writtenOffAmount").asText());
        assertEquals("0", new java.math.BigDecimal(drainedView.get("heldAmount").asText())
                .subtract(new java.math.BigDecimal(drainedView.get("writtenOffAmount").asText()))
                .toPlainString());
        MvcResult noRemaining = postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), allocation,
                        "2026-11-04T03:00:00Z", "2026-11-04T03:30:00Z"), "alice", 422);
        assertEquals("NO_REMAINING_WATER",
                objectMapper.readTree(noRemaining.getResponse().getContentAsString()).get("code").asText());

        // 转让使持有额度归零（APPROVED 但剩余 0）后同样不能排班
        String transferred = approvedAllocation(windowId, "user-2", "3", "bob");
        String target = key("ak");
        postOk("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", target, "windowId", windowId,
                "userId", "user-3", "amount", "3"), "carol");
        postOk("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", transferred, "targetAllocationKey", target), "bob");
        postJson("/api/schedules",
                scheduleBody(key("sc"), key("sk"), transferred,
                        "2026-11-04T03:00:00Z", "2026-11-04T03:30:00Z"), "bob", 422);
    }

    // ------------------------------------------------------------------
    // 核销时段约束与剩余水量
    // ------------------------------------------------------------------

    @Test
    void usageMustFallInActiveSlotAndCannotExceedRemaining() throws Exception {
        String channel = "ch-use-" + run;
        long windowId = createWindow(channel, "2026-11-05", "00", "04", "20");
        String allocation = approvedAllocation(windowId, "user-1", "10", "alice");
        JsonNode slot = scheduleOk(allocation, "alice",
                "2026-11-05T01:00:00Z", "2026-11-05T02:00:00Z");

        // 左闭：起始时刻核销合法
        JsonNode usage1 = postOk("/api/usages", usageBody(key("uc"), key("uk"), allocation, "3",
                "2026-11-05T01:00:00Z"), "alice");
        assertEquals("3", usage1.get("volume").asText());
        assertEquals(slot.get("id").asLong(), usage1.get("scheduleId").asLong());

        // 申请视图累计核销 3，剩余 7
        JsonNode view = allocationView(windowId, allocation);
        assertEquals("3", view.get("writtenOffAmount").asText());
        assertEquals("10", view.get("heldAmount").asText());

        // 排班快照不随后续核销改写
        assertEquals("10", findInChannel(channel, slot.get("scheduleKey").asText())
                .get("remainingSnapshot").asText());

        // 超过剩余 -> 422 QUOTA_EXCEEDED，且不产生核销
        postJson("/api/usages", usageBody(key("uc"), key("uk"), allocation, "8",
                "2026-11-05T01:30:00Z"), "alice", 422);
        assertEquals("3", allocationView(windowId, allocation).get("writtenOffAmount").asText());

        // 右开：结束时刻 exactly 不在时段内 -> 422，给出最近可用时段
        MvcResult atEnd = postJson("/api/usages", usageBody(key("uc"), key("uk"), allocation, "1",
                "2026-11-05T02:00:00Z"), "alice", 422);
        JsonNode endError = objectMapper.readTree(atEnd.getResponse().getContentAsString());
        assertEquals("OUTSIDE_ACTIVE_SLOT", endError.get("code").asText());
        assertEquals(slot.get("scheduleKey").asText(),
                endError.get("details").get("nearestSchedule").get("scheduleKey").asText());

        // 完全没有排班的申请 -> 422 且 details 为 null
        String noSlot = approvedAllocation(windowId, "user-2", "4", "bob");
        MvcResult none = postJson("/api/usages", usageBody(key("uc"), key("uk"), noSlot, "1",
                "2026-11-05T01:30:00Z"), "bob", 422);
        JsonNode noneError = objectMapper.readTree(none.getResponse().getContentAsString());
        assertEquals("OUTSIDE_ACTIVE_SLOT", noneError.get("code").asText());
        assertTrue(noneError.get("details").isNull());

        // 取消时段后其中时刻不可再核销 -> 422
        postOk("/api/schedules/" + slot.get("scheduleKey").asText() + "/cancel",
                Map.of("commandKey", key("scc")), "alice");
        postJson("/api/usages", usageBody(key("uc"), key("uk"), allocation, "1",
                "2026-11-05T01:30:00Z"), "alice", 422);

        // 非法水量 -> 400；非申请人 -> 409
        postJson("/api/usages", usageBody(key("uc"), key("uk"), allocation, "0",
                "2026-11-05T01:30:00Z"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), allocation, "1",
                "2026-11-05T01:30:00Z"), "bob", 409);
    }

    // ------------------------------------------------------------------
    // 取消规则：起始时刻已到 409（可控时钟）、重复取消、不存在
    // ------------------------------------------------------------------

    @Test
    void startedSlotCannotBeCancelledUnderControlledClock() throws Exception {
        String channel = "ch-clock-" + run;
        long windowId = createWindow(channel, "2026-11-06", "00", "04", "10");
        String allocation = approvedAllocation(windowId, "user-1", "10", "alice");
        JsonNode slot = scheduleOk(allocation, "alice",
                "2026-11-06T01:00:00Z", "2026-11-06T02:00:00Z");
        String scheduleKey = slot.get("scheduleKey").asText();

        WaterRepository repository = new WaterRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource));

        // 起始时刻前一分钟：可以取消
        WaterService before = new WaterService(repository,
                new DataSourceTransactionManager(dataSource), objectMapper,
                Clock.fixed(Instant.parse("2026-11-06T00:59:00Z"), ZoneOffset.UTC));
        var cancelled = before.cancelSchedule(key("scc"), scheduleKey, "alice");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(0, activeCount(channel));

        // 重新排一个时段验证起始时刻已到不可取消
        JsonNode second = scheduleOk(allocation, "alice",
                "2026-11-06T02:00:00Z", "2026-11-06T03:00:00Z");
        String secondKey = second.get("scheduleKey").asText();
        WaterService atStart = new WaterService(repository,
                new DataSourceTransactionManager(dataSource), objectMapper,
                Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneOffset.UTC));
        try {
            atStart.cancelSchedule(key("scc"), secondKey, "alice");
            throw new AssertionError("起始时刻已到的时段必须拒绝取消");
        } catch (ApiException e) {
            assertEquals(409, e.status().value());
            assertEquals("SCHEDULE_ALREADY_STARTED", e.code());
        }
        // 未被取消，仍为 ACTIVE
        assertEquals("ACTIVE", findInChannel(channel, secondKey).get("status").asText());
        // 重复取消一个已取消时段 -> 409
        try {
            before.cancelSchedule(key("scc"), scheduleKey, "alice");
            throw new AssertionError("重复取消必须被拒绝");
        } catch (ApiException e) {
            assertEquals("SCHEDULE_ALREADY_CANCELLED", e.code());
        }
        // 不存在 -> 404
        WaterService later = new WaterService(repository,
                new DataSourceTransactionManager(dataSource), objectMapper,
                Clock.fixed(Instant.parse("2026-11-06T01:30:00Z"), ZoneOffset.UTC));
        try {
            later.cancelSchedule(key("scc"), "missing-" + run, "alice");
            throw new AssertionError("不存在的排班必须 404");
        } catch (ApiException e) {
            assertEquals(404, e.status().value());
        }
    }

    // ------------------------------------------------------------------
    // 幂等：同键同参重放、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void commandIdempotencyForScheduleCancelAndUsage() throws Exception {
        String channel = "ch-idem-" + run;
        long windowId = createWindow(channel, "2026-11-07", "00", "04", "20");
        String allocation = approvedAllocation(windowId, "user-1", "10", "alice");

        String commandKey = key("sc");
        String scheduleKey = key("sk");
        Map<String, Object> body = scheduleBody(commandKey, scheduleKey, allocation,
                "2026-11-07T00:00:00Z", "2026-11-07T00:30:00Z");
        JsonNode first = postOk("/api/schedules", body, "alice");
        JsonNode replay = postOk("/api/schedules", body, "alice");
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/allocations/" + allocation + "/schedules").get("schedules").size());

        // 同键改参 -> 409
        postJson("/api/schedules",
                scheduleBody(commandKey, key("sk"), allocation,
                        "2026-11-07T00:00:00Z", "2026-11-07T00:30:00Z"), "alice", 409);

        // 失败（重叠）不占用 commandKey：同键同参仍 409，改参后可成功
        String other = approvedAllocation(windowId, "user-2", "5", "bob");
        String overlapCommand = key("sc");
        postJson("/api/schedules",
                scheduleBody(overlapCommand, key("sk"), other,
                        "2026-11-07T00:15:00Z", "2026-11-07T00:45:00Z"), "bob", 409);
        postJson("/api/schedules",
                scheduleBody(overlapCommand, key("sk"), other,
                        "2026-11-07T00:15:00Z", "2026-11-07T00:45:00Z"), "bob", 409);
        JsonNode recovered = postOk("/api/schedules",
                scheduleBody(overlapCommand, key("sk"), other,
                        "2026-11-07T01:00:00Z", "2026-11-07T01:30:00Z"), "bob");
        assertEquals("ACTIVE", recovered.get("status").asText());

        // 取消命令同键同参重放
        String cancelKey = key("scc");
        Map<String, Object> cancelBody = Map.of("commandKey", cancelKey);
        JsonNode cancel1 = postOk("/api/schedules/" + scheduleKey + "/cancel", cancelBody, "alice");
        JsonNode cancel2 = postOk("/api/schedules/" + scheduleKey + "/cancel", cancelBody, "alice");
        assertEquals(cancel1, cancel2);

        // 核销命令同键同参重放，累计核销只增加一次
        String otherKey = recovered.get("allocationKey").asText();
        String usageCommand = key("uc");
        String usageKey = key("uk");
        Map<String, Object> usageBody = usageBody(usageCommand, usageKey, otherKey, "2",
                "2026-11-07T01:15:00Z");
        JsonNode u1 = postOk("/api/usages", usageBody, "bob");
        JsonNode u2 = postOk("/api/usages", usageBody, "bob");
        assertEquals(u1, u2);
        assertEquals("2", allocationView(windowId, otherKey).get("writtenOffAmount").asText());
        // 核销同键改参 -> 409
        postJson("/api/usages", usageBody(usageCommand, key("uk"), otherKey, "2",
                "2026-11-07T01:15:00Z"), "bob", 409);
        // 换 commandKey 复用 usageKey -> 409
        postJson("/api/usages", usageBody(key("uc"), usageKey, otherKey, "1",
                "2026-11-07T01:15:00Z"), "bob", 409);
    }

    // ------------------------------------------------------------------
    // 并发：同渠道重叠排班恰好一个成功
    // ------------------------------------------------------------------

    @Test
    void concurrentOverlappingSchedulesSucceedAtMostOnce() throws Exception {
        String channel = "ch-race-sched-" + run;
        long windowId = createWindow(channel, "2026-11-08", "00", "04", "20");
        String a = approvedAllocation(windowId, "user-a", "5", "alice");
        String b = approvedAllocation(windowId, "user-b", "5", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                waterService.createSchedule(key("sc"), key("sk"), a,
                        "2026-11-08T01:00:00Z", "2026-11-08T02:00:00Z", "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                waterService.createSchedule(key("sc"), key("sk"), b,
                        "2026-11-08T01:30:00Z", "2026-11-08T02:30:00Z", "bob");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "重叠排班: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        assertEquals(1, activeCount(channel));
    }

    // ------------------------------------------------------------------
    // 并发：排班与转让按提交顺序裁决，归零后不可排班
    // ------------------------------------------------------------------

    @Test
    void concurrentScheduleAndTransferSettleByCommitOrder() throws Exception {
        String channel = "ch-race-tx-" + run;
        long windowId = createWindow(channel, "2026-11-09", "00", "04", "20");
        String source = approvedAllocation(windowId, "user-src", "5", "alice");
        String targetKey = key("ak");
        postOk("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", targetKey, "windowId", windowId,
                "userId", "user-dst", "amount", "5"), "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String scheduleCommand = key("sc");
        String scheduleKey = key("sk");
        String transferCommand = key("tc");
        String transferKey = key("tk");
        Future<Integer> scheduleFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.createSchedule(scheduleCommand, scheduleKey, source,
                        "2026-11-09T01:00:00Z", "2026-11-09T01:30:00Z", "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, targetKey, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int scheduleStatus = scheduleFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 转让恒成功（排班不消耗额度）；排班先提交则二者皆成功（快照固化为 5），转让先提交则排班 422
        assertEquals(200, transferStatus, "转让应成功");
        assertTrue(scheduleStatus == 200 || scheduleStatus == 422, "schedule=" + scheduleStatus);
        JsonNode sourceView = allocationView(windowId, source);
        assertEquals("0", sourceView.get("heldAmount").asText());
        if (scheduleStatus == 200) {
            // 快照固化申请时剩余 5，不因随后转让归零而改写
            assertEquals("5", findInChannel(channel, scheduleKey).get("remainingSnapshot").asText());
            assertEquals("ACTIVE", findInChannel(channel, scheduleKey).get("status").asText());
        }
        assertEquals(1, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
    }

    // ------------------------------------------------------------------
    // 并发：核销与全额转让争抢剩余额度，恰好一个成功
    // ------------------------------------------------------------------

    @Test
    void concurrentUsageAndTransferNeverExceedRemaining() throws Exception {
        String channel = "ch-race-use-" + run;
        long windowId = createWindow(channel, "2026-11-10", "00", "04", "20");
        String source = approvedAllocation(windowId, "user-src", "5", "alice");
        scheduleOk(source, "alice", "2026-11-10T01:00:00Z", "2026-11-10T02:00:00Z");
        String targetKey = key("ak");
        postOk("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", targetKey, "windowId", windowId,
                "userId", "user-dst", "amount", "5"), "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String usageCommand = key("uc");
        String usageKey = key("uk");
        String transferCommand = key("tc");
        String transferKey = key("tk");
        Future<Integer> usageFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.writeOffUsage(usageCommand, usageKey, source, "5",
                        "2026-11-10T01:30:00Z", "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, targetKey, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int usageStatus = usageFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 核销先提交：转让因剩余不足 422（持有 5/核销 5）；转让先提交：核销因剩余不足 422（持有 0/核销 0）
        assertEquals(1, (usageStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0),
                "usage=" + usageStatus + " transfer=" + transferStatus);
        assertEquals(1, (usageStatus == 422 ? 1 : 0) + (transferStatus == 422 ? 1 : 0));
        JsonNode view = allocationView(windowId, source);
        if (usageStatus == 200) {
            assertEquals("5", view.get("heldAmount").asText());
            assertEquals("5", view.get("writtenOffAmount").asText());
        } else {
            assertEquals("0", view.get("heldAmount").asText());
            assertEquals("0", view.get("writtenOffAmount").asText());
        }
        // 剩余未核销水量恒为 0，两条路径都不超过原额度
        assertEquals("0", new java.math.BigDecimal(view.get("heldAmount").asText())
                .subtract(new java.math.BigDecimal(view.get("writtenOffAmount").asText())).toPlainString());
    }
}
