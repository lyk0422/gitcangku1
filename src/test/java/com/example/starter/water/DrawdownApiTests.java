package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 水库下泄曲线 API 集成测试：曲线版本与覆盖校验、逐小时预留、批量批准/转让整批回滚、
 * 取消释放、按小时核销、历史快照不可变、幂等与并发不超卖。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，时钟通过 WaterService#setClock 可控。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrawdownApiTests {

    private static final String BASE = "2026-10-20T00:00:00Z";

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
        waterService.setClock(null);
    }

    private void clockAt(String utc) {
        waterService.setClock(() -> Instant.parse(utc));
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult call(String method, String url, Map<String, Object> body, String actor, int expected)
            throws Exception {
        var builder = "PUT".equals(method) ? put(url) : post(url);
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        if (actor != null) {
            builder = builder.header("X-Actor-Id", actor);
        }
        return mvc.perform(builder).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode ok(String method, String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(call(method, url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        return objectMapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode error(String method, String url, Map<String, Object> body, String actor, int expected)
            throws Exception {
        return objectMapper.readTree(call(method, url, body, actor, expected).getResponse().getContentAsString());
    }

    private long createAlignedWindow(String channel, int hours, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channel);
        body.put("startUtc", BASE);
        body.put("endUtc", "2026-10-20T" + String.format("%02d", hours) + ":00:00Z");
        body.put("plannedVolume", planned);
        return ok("POST", "/api/windows", body, null).get("id").asLong();
    }

    private Map<String, Object> point(String hour, String cap) {
        Map<String, Object> p = new HashMap<>();
        p.put("hourStartUtc", hour);
        p.put("capVolume", cap);
        return p;
    }

    private List<Map<String, Object>> caps(long windowHours, String cap) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int h = 0; h < windowHours; h++) {
            points.add(point("2026-10-20T" + String.format("%02d", h) + ":00:00Z", cap));
        }
        return points;
    }

    private JsonNode putCurve(long windowId, List<Map<String, Object>> points, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("dc"));
        body.put("points", points);
        if (expected == 200) {
            return ok("PUT", "/api/windows/" + windowId + "/drawdown", body, null);
        }
        return error("PUT", "/api/windows/" + windowId + "/drawdown", body, null, expected);
    }

    private String submit(long windowId, String userId, String amount, int startHour, int endHour,
                          String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (startHour >= 0) {
            body.put("usageStartUtc", "2026-10-20T" + String.format("%02d", startHour) + ":00:00Z");
            body.put("usageEndUtc", "2026-10-20T" + String.format("%02d", endHour) + ":00:00Z");
        }
        ok("POST", "/api/allocations", body, actor);
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

    private JsonNode approveExpect(String allocationKey, int expected) throws Exception {
        Map<String, Object> body = Map.of("commandKey", key("ap"));
        if (expected == 200) {
            return ok("POST", "/api/allocations/" + allocationKey + "/approve", body, null);
        }
        return error("POST", "/api/allocations/" + allocationKey + "/approve", body, null, expected);
    }

    private JsonNode usage(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/drawdown/usage");
    }

    private JsonNode hourUsage(long windowId, int hour) throws Exception {
        JsonNode hours = usage(windowId).get("hours");
        for (JsonNode node : hours) {
            if (node.get("hourStartUtc").asText().equals(
                    "2026-10-20T" + String.format("%02d", hour) + ":00:00Z")) {
                return node;
            }
        }
        throw new IllegalStateException("hour not found: " + hour);
    }

    private Map<String, Object> writeoffBody(String writeoffKey, String allocationKey, int hour, String volume) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wo"));
        body.put("writeoffKey", writeoffKey);
        body.put("allocationKey", allocationKey);
        body.put("hourStartUtc", "2026-10-20T" + String.format("%02d", hour) + ":00:00Z");
        body.put("volume", volume);
        return body;
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
    // 曲线配置与覆盖校验
    // ------------------------------------------------------------------

    @Test
    void curveMustCoverEveryWindowHourWithoutDuplicates() throws Exception {
        long windowId = createAlignedWindow("ch-curve-" + run, 3, "100");

        // 缺小时
        List<Map<String, Object>> missing = caps(3, "5");
        missing.remove(1);
        JsonNode missingErr = putCurve(windowId, missing, 400);
        assertEquals("CURVE_HOUR_COVERAGE", missingErr.get("code").asText());
        assertEquals(3, missingErr.get("details").get("requiredHourCount").asInt());
        assertEquals(1, missingErr.get("details").get("missingHours").size());

        // 重复小时
        List<Map<String, Object>> dup = caps(3, "5");
        dup.add(point("2026-10-20T01:00:00Z", "5"));
        assertEquals("CURVE_DUPLICATE_HOUR", putCurve(windowId, dup, 400).get("code").asText());

        // 跨窗口点
        List<Map<String, Object>> extra = caps(3, "5");
        extra.set(2, point("2026-10-20T05:00:00Z", "5"));
        JsonNode extraErr = putCurve(windowId, extra, 400);
        assertEquals("CURVE_HOUR_COVERAGE", extraErr.get("code").asText());
        assertEquals(1, extraErr.get("details").get("unexpectedHours").size());

        // 负数、超过 2 位小数、空序列
        assertEquals("INVALID_ARGUMENT", putCurve(windowId, List.of(
                point(BASE, "-0.01"), point("2026-10-20T01:00:00Z", "5"),
                point("2026-10-20T02:00:00Z", "5")), 400).get("code").asText());
        assertEquals("INVALID_ARGUMENT", putCurve(windowId, List.of(
                point(BASE, "1.001"), point("2026-10-20T01:00:00Z", "5"),
                point("2026-10-20T02:00:00Z", "5")), 400).get("code").asText());
        error("PUT", "/api/windows/" + windowId + "/drawdown",
                Map.of("commandKey", key("dc"), "points", List.of()), null, 400);

        // 非整点对齐窗口不能配置曲线
        Map<String, Object> winBody = new HashMap<>();
        winBody.put("commandKey", key("wc"));
        winBody.put("windowKey", key("wk"));
        winBody.put("channelId", "ch-curve-unaligned-" + run);
        winBody.put("startUtc", "2026-10-21T00:30:00Z");
        winBody.put("endUtc", "2026-10-21T02:30:00Z");
        winBody.put("plannedVolume", "10");
        long unaligned = ok("POST", "/api/windows", winBody, null).get("id").asLong();
        List<Map<String, Object>> points = List.of(
                point("2026-10-21T00:00:00Z", "5"), point("2026-10-21T01:00:00Z", "5"),
                point("2026-10-21T02:00:00Z", "5"));
        assertEquals("WINDOW_NOT_HOUR_ALIGNED", putCurve(unaligned, points, 400).get("code").asText());

        // 合法：允许 0 上限；乱序提交同样成功
        List<Map<String, Object>> valid = new ArrayList<>(List.of(
                point("2026-10-20T02:00:00Z", "0.00"), point(BASE, "5.00"),
                point("2026-10-20T01:00:00Z", "5")));
        JsonNode curve = putCurve(windowId, valid, 200);
        assertEquals(1, curve.get("version").asInt());
        assertEquals("ACTIVE", curve.get("status").asText());
        assertEquals(3, curve.get("points").size());
        // 响应点按小时排序，0 上限保留 2 位语义
        assertEquals(BASE, curve.get("points").get(0).get("hourStartUtc").asText());
        assertEquals("0", curve.get("points").get(2).get("capVolume").asText());

        JsonNode fetched = getOk("/api/windows/" + windowId + "/drawdown");
        assertEquals(1, fetched.get("version").asInt());
        // 未配置曲线的窗口查询返回 404
        long noCurve = createAlignedWindow("ch-nocurve-" + run, 1, "10");
        mvc.perform(get("/api/windows/" + noCurve + "/drawdown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/" + noCurve + "/drawdown/usage")).andExpect(status().isNotFound());
    }

    @Test
    void modifyingStartedHourReturns409AndListsHours() throws Exception {
        clockAt("2026-10-20T00:30:00Z");
        long windowId = createAlignedWindow("ch-started-" + run, 3, "100");
        putCurve(windowId, caps(3, "10"), 200);

        // 小时 0 已开始（now=00:30 >= 00:00），修改其上限 -> 409
        List<Map<String, Object>> changed = caps(3, "10");
        changed.set(0, point(BASE, "11"));
        JsonNode err = putCurve(windowId, changed, 409);
        assertEquals("CURVE_HOUR_ALREADY_STARTED", err.get("code").asText());
        assertEquals(1, err.get("details").get("changedStartedHours").size());
        assertEquals(BASE, err.get("details").get("changedStartedHours").get(0).asText());

        // 已开始但值保持不变，仅调整未来小时 -> 新版本 200
        List<Map<String, Object>> futureOnly = caps(3, "10");
        futureOnly.set(2, point("2026-10-20T02:00:00Z", "12"));
        JsonNode v2 = putCurve(windowId, futureOnly, 200);
        assertEquals(2, v2.get("version").asInt());
        // 旧版本固化为 SUPERSEDED，历史可查
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        ArrayNode curves = (ArrayNode) history.get("drawdownCurves");
        assertEquals(2, curves.size());
        assertEquals("SUPERSEDED", curves.get(0).get("status").asText());
        assertEquals("ACTIVE", curves.get(1).get("status").asText());
        assertEquals("10", curves.get(0).get("points").get(0).get("capVolume").asText());

        // 恰好整点也算已开始
        clockAt(BASE);
        List<Map<String, Object>> touchHour0 = caps(3, "10");
        touchHour0.set(0, point(BASE, "9"));
        assertEquals("CURVE_HOUR_ALREADY_STARTED", putCurve(windowId, touchHour0, 409).get("code").asText());
    }

    // ------------------------------------------------------------------
    // 申请提交校验
    // ------------------------------------------------------------------

    @Test
    void allocationUsageRangeValidatedAgainstCurveWindow() throws Exception {
        long windowId = createAlignedWindow("ch-submit-" + run, 3, "100");
        // 未配置曲线时不能给区间
        Map<String, Object> withRange = new HashMap<>();
        withRange.put("commandKey", key("ac"));
        withRange.put("allocationKey", key("ak"));
        withRange.put("windowId", windowId);
        withRange.put("userId", "u1");
        withRange.put("amount", "1");
        withRange.put("usageStartUtc", BASE);
        withRange.put("usageEndUtc", "2026-10-20T01:00:00Z");
        assertEquals("USAGE_WITHOUT_CURVE",
                error("POST", "/api/allocations", withRange, "alice", 400).get("code").asText());

        putCurve(windowId, caps(3, "10"), 200);
        // 缺区间 -> 400 USAGE_REQUIRED（error() 已断言状态码）
        JsonNode missingRange = submitExpect(windowId, "u1", "1", null, null, "alice", 400);
        assertEquals("USAGE_REQUIRED", missingRange.get("code").asText());
        // 区间越窗
        assertEquals("USAGE_OUT_OF_WINDOW", submitExpect(windowId, "u1", "1", 0, 5, "alice", 400)
                .get("code").asText());
        // 空区间/非整点
        assertEquals("INVALID_ARGUMENT", submitExpect(windowId, "u1", "1", 1, 1, "alice", 400)
                .get("code").asText());
        Map<String, Object> halfHour = allocationBody(windowId, "u1", "1",
                "2026-10-20T00:30:00Z", "2026-10-20T01:00:00Z");
        assertEquals("INVALID_ARGUMENT",
                error("POST", "/api/allocations", halfHour, "alice", 400).get("code").asText());
        // 曲线窗口最多 2 位小数
        assertEquals("INVALID_ARGUMENT", submitExpect(windowId, "u1", "1.001", 0, 1, "alice", 400)
                .get("code").asText());
        // 合法：部分小时区间
        String key = submit(windowId, "u1", "1.5", 1, 3, "alice");
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        JsonNode node = findAllocation(history, key);
        assertEquals("2026-10-20T01:00:00Z", node.get("usageStartUtc").asText());
        assertEquals("2026-10-20T03:00:00Z", node.get("usageEndUtc").asText());
        assertTrue(node.get("approvalCurveVersion").isNull());
    }

    private JsonNode submitExpect(long windowId, String userId, String amount, Integer startHour,
                                  Integer endHour, String actor, int expected) throws Exception {
        String s = startHour == null ? null : "2026-10-20T" + String.format("%02d", startHour) + ":00:00Z";
        String e = endHour == null ? null : "2026-10-20T" + String.format("%02d", endHour) + ":00:00Z";
        Map<String, Object> body = allocationBody(windowId, userId, amount, s, e);
        return error("POST", "/api/allocations", body, actor, expected);
    }

    private Map<String, Object> allocationBody(long windowId, String userId, String amount,
                                               String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (start != null) {
            body.put("usageStartUtc", start);
            body.put("usageEndUtc", end);
        }
        return body;
    }

    private JsonNode findAllocation(JsonNode history, String allocationKey) {
        for (JsonNode node : history.get("allocations")) {
            if (node.get("allocationKey").asText().equals(allocationKey)) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 批准：逐小时预留与 422 明细
    // ------------------------------------------------------------------

    @Test
    void approveSpreadsHourlyAndRejectsWithAllViolatingHours() throws Exception {
        long windowId = createAlignedWindow("ch-hourly-" + run, 2, "100");
        // 上限 [2, 5]
        List<Map<String, Object>> points = List.of(point(BASE, "2"), point("2026-10-20T01:00:00Z", "5"));
        putCurve(windowId, points, 200);

        // 4 立方米覆盖 2 小时：均分 [2, 2]，恰好装满小时 0
        String a1 = submit(windowId, "u1", "4", 0, 2, "alice");
        JsonNode approved = approveExpect(a1, 200);
        assertEquals(1, approved.get("approvalCurveVersion").asInt());
        JsonNode h0 = hourUsage(windowId, 0);
        assertEquals("2", h0.get("committedVolume").asText());
        assertEquals("0", h0.get("remaining").asText());
        assertEquals("2", hourUsage(windowId, 1).get("committedVolume").asText());

        // 3 立方米只占小时 0：2(已占) + 3 > 2，422 列出小时与差额
        String a2 = submit(windowId, "u2", "3", 0, 1, "bob");
        JsonNode err = approveExpect(a2, 422);
        assertEquals("HOURLY_CAP_EXCEEDED", err.get("code").asText());
        JsonNode details = err.get("details");
        assertEquals(1, details.size());
        assertEquals(BASE, details.get(0).get("hourStartUtc").asText());
        assertEquals("2", details.get(0).get("capVolume").asText());
        assertEquals("2", details.get(0).get("committedVolume").asText());
        assertEquals("3", details.get(0).get("requestedVolume").asText());
        assertEquals("3", details.get(0).get("deficitVolume").asText());
        // 失败不留预留：申请仍 REQUESTED
        assertEquals("REQUESTED", findAllocation(getOk("/api/windows/" + windowId + "/history"), a2)
                .get("status").asText());

        // 3 立方米占小时 1：已占 2，上限 5，可放入
        String a3 = submit(windowId, "u3", "3", 1, 2, "carol");
        approveExpect(a3, 200);
        assertEquals("5", hourUsage(windowId, 1).get("committedVolume").asText());
        assertEquals("0", hourUsage(windowId, 1).get("remaining").asText());

        // 多小时同时超限：1.01 覆盖 2 小时均分 [0.51, 0.50]；
        // 小时 0：2 + 0.51 > 2（差额 0.51），小时 1：已被 a1(2)+a3(3) 填满 5，再加 0.50 亦超限（差额 0.50）
        String a4 = submit(windowId, "u4", "1.01", 0, 2, "dave");
        JsonNode err2 = approveExpect(a4, 422);
        JsonNode details2 = err2.get("details");
        assertEquals(2, details2.size());
        assertEquals(BASE, details2.get(0).get("hourStartUtc").asText());
        assertEquals("0.51", details2.get(0).get("deficitVolume").asText());
        assertEquals("2026-10-20T01:00:00Z", details2.get(1).get("hourStartUtc").asText());
        assertEquals("0.5", details2.get(1).get("deficitVolume").asText());
    }

    @Test
    void batchApproveChecksCompleteFinalStateAndRollsBackAtomically() throws Exception {
        long windowId = createAlignedWindow("ch-batch-" + run, 1, "100");
        putCurve(windowId, caps(1, "3"), 200);
        String a1 = submit(windowId, "u1", "2", 0, 1, "alice");
        String a2 = submit(windowId, "u2", "2", 0, 1, "bob");
        String a3 = submit(windowId, "u3", "1", 0, 1, "carol");

        // 整批 [2, 2] 合计 4 > 3 -> 422，整批回滚，两笔都仍 REQUESTED
        JsonNode err = error("POST", "/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a2)), null, 422);
        assertEquals("HOURLY_CAP_EXCEEDED", err.get("code").asText());
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REQUESTED", findAllocation(history, a1).get("status").asText());
        assertEquals("REQUESTED", findAllocation(history, a2).get("status").asText());
        assertEquals("0", hourUsage(windowId, 0).get("committedVolume").asText());

        // 整批 [2, 1] 合计恰好 3 -> 成功，顺序与请求一致
        JsonNode batch = ok("POST", "/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a3)), null);
        assertEquals(2, batch.get("allocations").size());
        assertEquals(a1, batch.get("allocations").get(0).get("allocationKey").asText());
        assertEquals(a3, batch.get("allocations").get(1).get("allocationKey").asText());
        assertEquals("3", hourUsage(windowId, 0).get("committedVolume").asText());

        // 批量内混入已批准 -> 409 且带序号，整批无新增
        JsonNode conflict = error("POST", "/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a2, a1)), null, 409);
        assertEquals("ALLOCATION_ALREADY_APPROVED", conflict.get("code").asText());
        assertEquals(1, conflict.get("details").get("index").asInt());

        // 跨窗口 -> 409；不存在 -> 404
        long other = createAlignedWindow("ch-batch-other-" + run, 1, "10");
        putCurve(other, caps(1, "10"), 200);
        String otherA = submit(other, "ox", "1", 0, 1, "alice");
        error("POST", "/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a2, otherA)), null, 409);
        JsonNode notFound = error("POST", "/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of("missing-" + run)), null, 404);
        assertEquals(0, notFound.get("details").get("index").asInt());
    }

    // ------------------------------------------------------------------
    // 取消释放
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesReservedHoursAndKeepsHistoricalSnapshot() throws Exception {
        long windowId = createAlignedWindow("ch-cancel-" + run, 2, "100");
        putCurve(windowId, caps(2, "4"), 200);
        String a1 = submit(windowId, "u1", "4", 0, 2, "alice");
        approveExpect(a1, 200);
        assertEquals("2", hourUsage(windowId, 0).get("committedVolume").asText());

        JsonNode cancelled = ok("POST", "/api/allocations/" + a1 + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals(1, cancelled.get("approvalCurveVersion").asInt());
        // 两小时占用归零，预留与释放可在历史快照中核对
        assertEquals("0", hourUsage(windowId, 0).get("committedVolume").asText());
        assertEquals("2", hourUsage(windowId, 0).get("releasedTotal").asText());
        // reservedTotal 为该小时固化预留（4 总量均分 2 小时，每小时 2），不随后续释放改写
        assertEquals("2", hourUsage(windowId, 0).get("reservedTotal").asText());
        assertEquals("0", hourUsage(windowId, 1).get("committedVolume").asText());
        assertEquals("2", hourUsage(windowId, 1).get("releasedTotal").asText());

        // 释放后新申请可重新占用
        String a2 = submit(windowId, "u2", "4", 0, 2, "bob");
        approveExpect(a2, 200);
        assertEquals("2", hourUsage(windowId, 0).get("committedVolume").asText());

        // 历史快照：固化预留仍为 2，释放为 2，核销为 0，曲线版本仍为 1
        JsonNode group = reservationGroup(windowId, a1);
        assertEquals(1, group.get("approvalCurveVersion").asInt());
        JsonNode firstHour = group.get("reservations").get(0);
        assertEquals("2", firstHour.get("reservedVolume").asText());
        assertEquals("2", firstHour.get("releasedVolume").asText());
        assertEquals("0", firstHour.get("writtenOffVolume").asText());
        assertEquals(1, firstHour.get("curveVersion").asInt());
    }

    private JsonNode reservationGroup(long windowId, String allocationKey) throws Exception {
        JsonNode groups = getOk("/api/windows/" + windowId + "/history").get("reservations");
        for (JsonNode group : groups) {
            if (allocationKey.equals(group.get("allocationKey").asText())) {
                return group;
            }
        }
        throw new IllegalStateException("reservation group not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 核销：同一小时扣减，已核销仍占容量，取消只释放未核销部分
    // ------------------------------------------------------------------

    @Test
    void writeoffConsumesOnlySameHourReservation() throws Exception {
        long windowId = createAlignedWindow("ch-wo-" + run, 2, "100");
        putCurve(windowId, caps(2, "2"), 200);
        String a1 = submit(windowId, "u1", "4", 0, 2, "alice");
        approveExpect(a1, 200);

        // 小时 0 核销 1.5：成功；该小时占用仍为 2（已实际下泄），不可再新增预留
        JsonNode wo = ok("POST", "/api/writeoffs", writeoffBody(key("wk"), a1, 0, "1.5"), "alice");
        assertEquals("1.5", wo.get("volume").asText());
        assertEquals(BASE, wo.get("hourStartUtc").asText());
        assertEquals("alice", wo.get("actor").asText());
        assertEquals("2", hourUsage(windowId, 0).get("committedVolume").asText());
        assertEquals("1.5", hourUsage(windowId, 0).get("writtenOffTotal").asText());
        assertEquals("0", hourUsage(windowId, 0).get("remaining").asText());

        // 再核销 0.6 超过该小时剩余可核销 0.5 -> 422，明细给出实际/要求/差额
        JsonNode over = error("POST", "/api/writeoffs", writeoffBody(key("wk"), a1, 0, "0.6"), "alice", 422);
        assertEquals("WRITEOFF_EXCEEDS_RESERVATION", over.get("code").asText());
        assertEquals("0.5", over.get("details").get("remainingVolume").asText());
        assertEquals("0.6", over.get("details").get("requestedVolume").asText());
        assertEquals("0.1", over.get("details").get("deficitVolume").asText());

        // 不能挪用小时 1 的余量核销小时 0；小时 1 自身可核销 2
        error("POST", "/api/writeoffs", writeoffBody(key("wk"), a1, 1, "2.1"), "alice", 422);
        ok("POST", "/api/writeoffs", writeoffBody(key("wk"), a1, 1, "2"), "alice");

        // 区间外小时 / REQUESTED 申请 / 非整数小时 -> 明确失败
        String pending = submit(windowId, "u2", "1", 0, 1, "bob");
        error("POST", "/api/writeoffs", writeoffBody(key("wk"), pending, 0, "1"), "bob", 409);
        Map<String, Object> badHour = writeoffBody(key("wk"), a1, 0, "1");
        badHour.put("hourStartUtc", "2026-10-20T00:30:00Z");
        error("POST", "/api/writeoffs", badHour, "alice", 400);
        Map<String, Object> outOfRange = writeoffBody(key("wk"), a1, 0, "0.5");
        outOfRange.put("hourStartUtc", "2026-10-20T02:00:00Z");
        error("POST", "/api/writeoffs", outOfRange, "alice", 422);

        // 取消已部分核销的申请：小时 0 只释放剩余 0.5，不释放已核销的 1.5
        ok("POST", "/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode h0 = hourUsage(windowId, 0);
        assertEquals("1.5", h0.get("committedVolume").asText());
        assertEquals("0.5", h0.get("releasedTotal").asText());
        JsonNode group = reservationGroup(windowId, a1);
        assertEquals("2", group.get("reservations").get(0).get("reservedVolume").asText());
        assertEquals("1.5", group.get("reservations").get(0).get("writtenOffVolume").asText());
        assertEquals("0.5", group.get("reservations").get(0).get("releasedVolume").asText());
        // 已取消不能继续核销
        error("POST", "/api/writeoffs", writeoffBody(key("wk"), a1, 0, "0.5"), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 转让与批量转让
    // ------------------------------------------------------------------

    @Test
    void transferReservesTargetHoursAndBatchRollsBackOnAnyFailure() throws Exception {
        long windowId = createAlignedWindow("ch-txhour-" + run, 1, "100");
        putCurve(windowId, caps(1, "10"), 200);
        // 源持有 6，另一已批准申请占 3.5，小时当前占用 9.5，仅剩 0.5
        String source = submit(windowId, "u-src", "6", 0, 1, "alice");
        approveExpect(source, 200);
        String other = submit(windowId, "u-other", "3.5", 0, 1, "zoe");
        approveExpect(other, 200);
        String t1 = submit(windowId, "u-t1", "0.5", 0, 1, "bob");
        String t2 = submit(windowId, "u-t2", "0.3", 0, 1, "carol");
        String t3 = submit(windowId, "u-t3", "0.2", 0, 1, "dave");

        // 单笔转让：源持有充足（6 >= 0.5），但小时余量仅 0.5，9.5 + 0.5 = 10 恰好成功
        JsonNode tx = ok("POST", "/api/transfers", transferBody(key("tk"), source, t1), "alice");
        assertEquals(t1, tx.get("targetAllocationKey").asText());
        assertEquals("10", hourUsage(windowId, 0).get("committedVolume").asText());
        assertEquals("0", hourUsage(windowId, 0).get("remaining").asText());
        JsonNode targetNode = findAllocation(getOk("/api/windows/" + windowId + "/history"), t1);
        assertEquals("APPROVED", targetNode.get("status").asText());
        assertEquals(1, targetNode.get("approvalCurveVersion").asInt());

        // 批量转让 [0.3, 0.2]：源持有 5.5 充足，但完整最终小时占用 10 + 0.5 = 10.5 > 10
        // -> HOURLY_CAP_EXCEEDED，整批回滚：无新流水、两目标仍 REQUESTED、占用不变
        JsonNode err = error("POST", "/api/transfers/batch",
                Map.of("commandKey", key("tb"), "transfers", List.of(
                        transferItem(key("tk"), source, t2), transferItem(key("tk"), source, t3))),
                "alice", 422);
        assertEquals("HOURLY_CAP_EXCEEDED", err.get("code").asText());
        JsonNode transfers = getOk("/api/windows/" + windowId + "/transfers").get("transfers");
        assertEquals(1, transfers.size());
        assertEquals("10", hourUsage(windowId, 0).get("committedVolume").asText());
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REQUESTED", findAllocation(history, t2).get("status").asText());
        assertEquals("REQUESTED", findAllocation(history, t3).get("status").asText());

        // 取消 t1 释放 0.5 后，同一批 [0.3, 0.2] 完整最终占用恰好 10，整批成功
        ok("POST", "/api/allocations/" + t1 + "/cancel", Map.of("commandKey", key("cc")), "bob");
        JsonNode batch = ok("POST", "/api/transfers/batch",
                Map.of("commandKey", key("tb"), "transfers", List.of(
                        transferItem(key("tk"), source, t2), transferItem(key("tk"), source, t3))),
                "alice");
        assertEquals(2, batch.get("transfers").size());
        assertEquals("10", hourUsage(windowId, 0).get("committedVolume").asText());

        // 批量内源持有不足同样整批回滚：源剩余持有 5.0，两笔各 3（不同小时不适用，单小时窗口下先验持有）
        String t4 = submit(windowId, "u-t4", "3", 0, 1, "erin");
        String t5 = submit(windowId, "u-t5", "3", 0, 1, "frank");
        JsonNode quotaErr = error("POST", "/api/transfers/batch",
                Map.of("commandKey", key("tb"), "transfers", List.of(
                        transferItem(key("tk"), source, t4), transferItem(key("tk"), source, t5))),
                "alice", 422);
        assertEquals("QUOTA_EXCEEDED", quotaErr.get("code").asText());
        assertEquals(1, quotaErr.get("details").get("index").asInt());
        assertEquals(3, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        // 批内 transferKey 重复 -> 400
        String dupKey = key("tk");
        error("POST", "/api/transfers/batch",
                Map.of("commandKey", key("tb"), "transfers", List.of(
                        transferItem(dupKey, source, t4), transferItem(dupKey, source, t5))),
                "alice", 400);
    }

    private Map<String, Object> transferItem(String transferKey, String source, String target) {
        Map<String, Object> item = new HashMap<>();
        item.put("transferKey", transferKey);
        item.put("sourceAllocationKey", source);
        item.put("targetAllocationKey", target);
        return item;
    }

    // ------------------------------------------------------------------
    // 快照不可变
    // ------------------------------------------------------------------

    @Test
    void approvalSnapshotIsNotRewrittenByLaterCurveChange() throws Exception {
        long windowId = createAlignedWindow("ch-snap-" + run, 3, "100");
        putCurve(windowId, caps(3, "4"), 200);
        String a1 = submit(windowId, "u1", "4", 0, 2, "alice");
        approveExpect(a1, 200);

        // 时钟推进到 01:30：小时 0、1 已开始，仅未来小时 2 可上调；生成 v2
        clockAt("2026-10-20T01:30:00Z");
        List<Map<String, Object>> raised = caps(3, "4");
        raised.set(2, point("2026-10-20T02:00:00Z", "9"));
        JsonNode v2 = putCurve(windowId, raised, 200);
        assertEquals(2, v2.get("version").asInt());

        // 申请与逐小时预留仍固化 v1 与原始分布，诊断上限已切换为 v2
        JsonNode allocation = findAllocation(getOk("/api/windows/" + windowId + "/history"), a1);
        assertEquals(1, allocation.get("approvalCurveVersion").asInt());
        JsonNode group = reservationGroup(windowId, a1);
        assertEquals(1, group.get("approvalCurveVersion").asInt());
        group.get("reservations").forEach(r -> {
            assertEquals(1, r.get("curveVersion").asInt());
            assertEquals("2", r.get("reservedVolume").asText());
        });
        JsonNode usageNow = usage(windowId);
        assertEquals(2, usageNow.get("curveVersion").asInt());
        assertEquals("4", usageNow.get("hours").get(0).get("capVolume").asText());
        assertEquals("9", usageNow.get("hours").get(2).get("capVolume").asText());
        // 占用仍按旧预留 2 计
        assertEquals("2", usageNow.get("hours").get(0).get("committedVolume").asText());
        assertEquals("0", usageNow.get("hours").get(2).get("committedVolume").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void commandIdempotencyReplaySameParamsRejectDifferentAndFailuresDoNotOccupy() throws Exception {
        long windowId = createAlignedWindow("ch-idem-" + run, 2, "100");
        String curveCommand = key("dc");
        Map<String, Object> curveBody = new HashMap<>();
        curveBody.put("commandKey", curveCommand);
        curveBody.put("points", caps(2, "5"));
        JsonNode firstCurve = ok("PUT", "/api/windows/" + windowId + "/drawdown", curveBody, null);
        JsonNode replayCurve = ok("PUT", "/api/windows/" + windowId + "/drawdown", curveBody, null);
        assertEquals(firstCurve, replayCurve);
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("drawdownCurves").size());
        // 同键改参 -> 409
        Map<String, Object> changed = new HashMap<>(curveBody);
        changed.put("points", caps(2, "6"));
        error("PUT", "/api/windows/" + windowId + "/drawdown", changed, null, 409);

        // 批准命令同参重放
        String a1 = submit(windowId, "u1", "4", 0, 2, "alice");
        String approveCommand = key("ap");
        Map<String, Object> approveBody = Map.of("commandKey", approveCommand);
        JsonNode first = ok("POST", "/api/allocations/" + a1 + "/approve", approveBody, null);
        JsonNode replay = ok("POST", "/api/allocations/" + a1 + "/approve", approveBody, null);
        assertEquals(first, replay);

        // 失败的批准不占键：小时 0 上限 5，a1 已占 2；a2 再申请小时 0 的 4 将达 6 > 5 先 422；
        // 取消 a1 释放后同键成功
        String a2 = submit(windowId, "u2", "4", 0, 1, "bob");
        String failKey = key("ap");
        error("POST", "/api/allocations/" + a2 + "/approve", Map.of("commandKey", failKey), null, 422);
        error("POST", "/api/allocations/" + a2 + "/approve", Map.of("commandKey", failKey), null, 422);
        ok("POST", "/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        ok("POST", "/api/allocations/" + a2 + "/approve", Map.of("commandKey", failKey), null);

        // 核销幂等：同参重放首次响应，异参 409，失败不占键
        String woKey = key("wk");
        String woCommand = key("woc");
        Map<String, Object> woBody = writeoffBody(woKey, a2, 0, "0.5");
        woBody.put("commandKey", woCommand);
        JsonNode wo1 = ok("POST", "/api/writeoffs", woBody, "bob");
        JsonNode wo2 = ok("POST", "/api/writeoffs", woBody, "bob");
        assertEquals(wo1, wo2);
        // 只有一条核销流水
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("writeoffs").size());
        // 同 commandKey 改量 -> 409
        Map<String, Object> woChanged = new HashMap<>(woBody);
        woChanged.put("volume", "0.6");
        error("POST", "/api/writeoffs", woChanged, "bob", 409);
        // 失败核销（小时 0 剩余可核销 4-0.5=3.5，3.6 超额）不占键：先 422，随后同键核销 1 成功
        String failCommand = key("woc");
        error("POST", "/api/writeoffs", writeoffWithCommand(failCommand, key("wk"), a2, 0, "3.6"),
                "bob", 422);
        ok("POST", "/api/writeoffs", writeoffWithCommand(failCommand, key("wk"), a2, 0, "1"), "bob");
        // a2 只覆盖小时 0，在小时 1 没有固化预留，不能挪用
        error("POST", "/api/writeoffs", writeoffWithCommand(key("woc"), key("wk"), a2, 1, "2"),
                "bob", 422);
    }

    private Map<String, Object> writeoffWithCommand(String commandKey, String writeoffKey, String allocation,
                                                    int hour, String volume) {
        Map<String, Object> body = writeoffBody(writeoffKey, allocation, hour, volume);
        body.put("commandKey", commandKey);
        return body;
    }

    // ------------------------------------------------------------------
    // 并发：同一小时不超卖
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesNeverOversellSameHour() throws Exception {
        long windowId = createAlignedWindow("ch-conc-hour-" + run, 1, "100");
        putCurve(windowId, caps(1, "3"), 200);
        List<String> allocations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            allocations.add(submit(windowId, "u" + i, "1", 0, 1, "alice"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocation : allocations) {
            String allocationKey = allocation;
            futures.add(pool.submit(() -> {
                gate.await();
                return approveStatus(allocationKey);
            }));
        }
        gate.countDown();
        int okCount = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                okCount++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertEquals(3, okCount, "上限 3，恰好 3 笔成功");
        assertEquals(2, rejected);
        assertEquals("3", hourUsage(windowId, 0).get("committedVolume").asText());
        assertEquals("0", hourUsage(windowId, 0).get("remaining").asText());
    }

    @Test
    void concurrentApproveAndCurveLoweringSettleByCommitOrder() throws Exception {
        clockAt("2026-10-20T00:30:00Z");
        long windowId = createAlignedWindow("ch-conc-curve-" + run, 2, "100");
        putCurve(windowId, caps(2, "5"), 200);
        // 小时 0 已开始不可改；批准只占未来小时 1，曲线下调小时 1 与批准竞争
        String a1 = submit(windowId, "u1", "4", 1, 2, "alice");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String approveCommand = key("ap");
        String curveCommand = key("dc");
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, a1);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> curveStatus = pool.submit(() -> {
            gate.await();
            try {
                List<Map<String, Object>> lowered = caps(2, "5");
                lowered.set(1, point("2026-10-20T01:00:00Z", "3"));
                waterService.putDrawdownCurve(curveCommand, windowId,
                        lowered.stream().map(p -> new com.example.starter.water.dto.Dtos.DrawdownPointRequest(
                                p.get("hourStartUtc").toString(), p.get("capVolume").toString())).toList());
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int approve = approveFuture.get(30, TimeUnit.SECONDS);
        int curve = curveStatus.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按提交顺序：先批准（小时1 占用4）则下调到3冲突409；先下调3则批准4>3为422
        assertTrue((approve == 200) != (curve == 200), "approve=" + approve + " curve=" + curve);
        JsonNode hour1 = hourUsage(windowId, 1);
        if (approve == 200) {
            assertEquals(409, curve);
            assertEquals("4", hour1.get("committedVolume").asText());
            assertEquals("5", hour1.get("capVolume").asText());
        } else {
            assertEquals(422, approve);
            assertEquals("3", hour1.get("capVolume").asText());
            assertEquals("0", hour1.get("committedVolume").asText());
        }
        assertNotNull(usage(windowId));
    }
}
