package com.example.starter.water;

import com.example.starter.water.MutableTestClockConfig.MutableClock;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 水库下泄曲线集成测试：曲线版本化校验、批准/转让逐小时预留与快照、核销同小时扣减、
 * 批量一次事务、并发不超卖、幂等重放与失败回滚、诊断/历史只读查询。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁；时间边界用可控 UTC 时钟确定性验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DrawdownApiTests {

    private static final String START = "2026-12-01T00:00:00Z";
    private static final String H0 = "2026-12-01T00:00:00Z";
    private static final String H1 = "2026-12-01T01:00:00Z";
    private static final String END = "2026-12-01T02:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private MutableClock clock;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
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

    private Map<String, Object> point(String hour, String cap) {
        Map<String, Object> p = new HashMap<>();
        p.put("hourUtc", hour);
        p.put("capVolume", cap);
        return p;
    }

    private Map<String, Object> planPoint(String hour, String volume) {
        Map<String, Object> p = new HashMap<>();
        p.put("hourUtc", hour);
        p.put("volume", volume);
        return p;
    }

    private long createHourlyWindow(String channel, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channel);
        body.put("startUtc", START);
        body.put("endUtc", END);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private JsonNode setCurve(long windowId, String cap0, String cap1, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("cc"));
        body.put("points", List.of(point(H0, cap0), point(H1, cap1)));
        String json = postJson("/api/windows/" + windowId + "/drawdown-curve", body, null, expectedStatus)
                .getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private String submit(long windowId, String userId, String amount, String actor,
                          List<Map<String, Object>> plan) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("plan", plan);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private String submitFullPlan(long windowId, String userId, String v0, String v1, String actor)
            throws Exception {
        return submit(windowId, userId, sum(v0, v1), actor,
                new ArrayList<>(List.of(planPoint(H0, v0), planPoint(H1, v1))));
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode drawdown(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/drawdown");
    }

    private JsonNode hourStatus(JsonNode drawdown, String hour) {
        for (JsonNode node : drawdown.get("hours")) {
            if (hour.equals(node.get("hourUtc").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("hour not found: " + hour);
    }

    private Map<String, Object> writeoffBody(String writeoffKey, String hour, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wo"));
        body.put("writeoffKey", writeoffKey);
        body.put("hourUtc", hour);
        body.put("amount", amount);
        return body;
    }

    private int statusOf(Runnable action) {
        try {
            action.run();
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private static String sum(String a, String b) {
        return new java.math.BigDecimal(a).add(new java.math.BigDecimal(b)).toPlainString();
    }

    // ------------------------------------------------------------------
    // 曲线配置与版本化
    // ------------------------------------------------------------------

    @Test
    void curveMustCoverAllHoursWithNonNegativeTwoDecimalCaps() throws Exception {
        long windowId = createHourlyWindow("ch-curve-" + run, "20");

        // 正常：两小时全覆盖，允许 0 与两位小数
        JsonNode curve = setCurve(windowId, "10.00", "0", 200);
        assertEquals(1, curve.get("version").asInt());
        assertEquals("ACTIVE", curve.get("status").asText());
        assertEquals(2, curve.get("points").size());
        assertEquals("10", curve.get("points").get(0).get("capVolume").asText());
        assertEquals("0", curve.get("points").get(1).get("capVolume").asText());

        // 缺小时 -> 400
        Map<String, Object> missing = new HashMap<>();
        missing.put("commandKey", key("cc"));
        missing.put("points", List.of(point(H0, "10")));
        postJson("/api/windows/" + windowId + "/drawdown-curve", missing, null, 400);

        // 跨窗口/多一小时 -> 400
        Map<String, Object> extra = new HashMap<>();
        extra.put("commandKey", key("cc"));
        extra.put("points", List.of(point(H0, "10"), point(H1, "10"),
                point("2026-12-01T02:00:00Z", "10")));
        postJson("/api/windows/" + windowId + "/drawdown-curve", extra, null, 400);

        // 重复小时 -> 400
        Map<String, Object> dup = new HashMap<>();
        dup.put("commandKey", key("cc"));
        dup.put("points", List.of(point(H0, "10"), point(H0, "11"), point(H1, "10")));
        postJson("/api/windows/" + windowId + "/drawdown-curve", dup, null, 400);

        // 负数、三位小数、非数字 -> 400
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", key("cc"), "points",
                        List.of(point(H0, "-1"), point(H1, "10"))), null, 400);
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", key("cc"), "points",
                        List.of(point(H0, "10.001"), point(H1, "10"))), null, 400);
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", key("cc"), "points",
                        List.of(point(H0, "abc"), point(H1, "10"))), null, 400);

        // 非整点小时 -> 400
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", key("cc"), "points",
                        List.of(point("2026-12-01T00:00:30Z", "10"), point(H1, "10"))), null, 400);

        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/drawdown-curve",
                Map.of("commandKey", key("cc"), "points",
                        List.of(point(H0, "10"), point(H1, "10"))), null, 404);
    }

    @Test
    void nonIntegralHourWindowRejectsCurve() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-half-" + run);
        body.put("startUtc", "2026-12-02T00:00:30Z");
        body.put("endUtc", "2026-12-02T02:00:00Z");
        body.put("plannedVolume", "20");
        long windowId = postOk("/api/windows", body, null).get("id").asLong();
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", key("cc"), "points", List.of(
                        point("2026-12-02T00:00:30Z", "10"),
                        point("2026-12-02T01:00:00Z", "10"))), null, 400);
    }

    @Test
    void firstCurveRejectedWhenApprovalsAlreadyExist() throws Exception {
        long windowId = createHourlyWindow("ch-pre-" + run, "20");
        // 无曲线窗口允许提交并批准不带计划的申请（历史兼容路径）
        String a = submit(windowId, "u1", "5", "alice", null);
        approve(a, 200);
        setCurve(windowId, "10", "10", 409);
    }

    @Test
    void modifyingStartedHourReturns409WhileFutureHoursCanChangeAndSnapshotKept() throws Exception {
        long windowId = createHourlyWindow("ch-started-" + run, "20");
        clock.fixedAt(Instant.parse("2026-11-30T12:00:00Z"));
        JsonNode v1 = setCurve(windowId, "10", "10", 200);
        assertEquals(1, v1.get("version").asInt());

        // 在窗口开始前批准一笔，固化 v1 快照
        String a = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a, 200);
        JsonNode allocationBefore = getOk("/api/allocations/" + a);
        assertEquals(1, allocationBefore.get("approvedCurveVersion").asInt());

        // 第一小时已经开始（00:30），修改第一小时 -> 409
        clock.fixedAt(Instant.parse("2026-12-01T00:30:00Z"));
        setCurve(windowId, "11", "10", 409);
        // 只改第二小时（01:00 尚未开始）-> 200，产生 v2
        JsonNode v2 = setCurve(windowId, "10", "12.50", 200);
        assertEquals(2, v2.get("version").asInt());
        assertEquals("12.5", v2.get("points").get(1).get("capVolume").asText());

        // 第二小时也开始后，改第二小时 -> 409
        clock.fixedAt(Instant.parse("2026-12-01T01:30:00Z"));
        setCurve(windowId, "10", "9", 409);
        // 未改变任何已开始小时的等值提交 -> 允许新版本
        JsonNode v3 = setCurve(windowId, "10", "12.50", 200);
        assertEquals(3, v3.get("version").asInt());

        // 历史快照不改写：已批准申请仍引用 v1，逐小时预留仍是 6/4
        JsonNode allocationAfter = getOk("/api/allocations/" + a);
        assertEquals(1, allocationAfter.get("approvedCurveVersion").asInt());
        JsonNode reservations = allocationAfter.get("reservations");
        assertEquals(2, reservations.size());
        assertEquals("6", reservations.get(0).get("reservedVolume").asText());
        assertEquals(1, reservations.get(0).get("curveVersion").asInt());
        assertEquals("4", reservations.get(1).get("reservedVolume").asText());
        // 诊断展示当前 v3 上限
        JsonNode status = drawdown(windowId);
        assertEquals(3, status.get("activeCurveVersion").asInt());
        assertEquals("12.5", hourStatus(status, H1).get("capVolume").asText());
    }

    @Test
    void curveCommandIsIdempotentAndFailuresDoNotOccupyKey() throws Exception {
        long windowId = createHourlyWindow("ch-curve-idem-" + run, "20");
        String commandKey = key("cc");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("points", List.of(point(H0, "10"), point(H1, "10")));
        JsonNode first = objectMapper.readTree(
                postJson("/api/windows/" + windowId + "/drawdown-curve", body, null, 200)
                        .getResponse().getContentAsString());
        JsonNode replay = objectMapper.readTree(
                postJson("/api/windows/" + windowId + "/drawdown-curve", body, null, 200)
                        .getResponse().getContentAsString());
        assertEquals(first, replay);
        assertEquals(1, drawdown(windowId).get("activeCurveVersion").asInt());

        // 同键改参 -> 409
        Map<String, Object> changed = new HashMap<>();
        changed.put("commandKey", commandKey);
        changed.put("points", List.of(point(H0, "11"), point(H1, "10")));
        postJson("/api/windows/" + windowId + "/drawdown-curve", changed, null, 409);
        assertEquals(1, drawdown(windowId).get("activeCurveVersion").asInt());

        // 失败（缺小时）不占键：同键随后可成功提交新版本
        String failThenOk = key("cc");
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", failThenOk, "points", List.of(point(H0, "10"))), null, 400);
        postJson("/api/windows/" + windowId + "/drawdown-curve",
                Map.of("commandKey", failThenOk, "points",
                        List.of(point(H0, "12"), point(H1, "10"))), null, 200);
        assertEquals(2, drawdown(windowId).get("activeCurveVersion").asInt());
    }

    // ------------------------------------------------------------------
    // 申请计划
    // ------------------------------------------------------------------

    @Test
    void allocationPlanMustCoverHoursAndSumToAmount() throws Exception {
        long windowId = createHourlyWindow("ch-plan-" + run, "20");
        setCurve(windowId, "10", "10", 200);

        // 缺少 plan -> 400
        postJson("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", key("ak"),
                "windowId", windowId, "userId", "u1", "amount", "6"), "alice", 400);

        // 计划之和不等于水量 -> 400
        submitExpect400(windowId, List.of(planPoint(H0, "6"), planPoint(H1, "5")), "10");
        // 只覆盖部分小时但和等于水量 -> 合法（申请可只在部分小时用水）
        String partial = submit(windowId, "u2", "6", "bob", List.of(planPoint(H0, "6")));
        approve(partial, 200);
        // 计划小时落在窗口外 -> 400
        submitExpect400(windowId, List.of(planPoint(H0, "3"),
                planPoint("2026-12-01T02:00:00Z", "3")), "6");
        // 重复小时 -> 400
        submitExpect400(windowId, List.of(planPoint(H0, "3"), planPoint(H0, "3")), "6");
        // 小时水量为 0 -> 400
        submitExpect400(windowId, List.of(planPoint(H0, "0"), planPoint(H1, "6")), "6");
    }

    private void submitExpect400(long windowId, List<Map<String, Object>> plan, String amount)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", "u-bad");
        body.put("amount", amount);
        body.put("plan", plan);
        postJson("/api/allocations", body, "alice", 400);
    }

    // ------------------------------------------------------------------
    // 批准逐小时预留
    // ------------------------------------------------------------------

    @Test
    void approveReservesHourlyAndReturnsAllViolatingHours() throws Exception {
        // 窗口计划总量给足，使逐小时上限（每小时 10）成为唯一约束
        long windowId = createHourlyWindow("ch-hourly-" + run, "100");
        setCurve(windowId, "10", "10", 200);

        String a1 = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a1, 200);
        JsonNode allocation = getOk("/api/allocations/" + a1);
        assertEquals(1, allocation.get("approvedCurveVersion").asInt());
        assertEquals(2, allocation.get("reservations").size());

        JsonNode status = drawdown(windowId);
        JsonNode h0 = hourStatus(status, H0);
        assertEquals("10", h0.get("capVolume").asText());
        assertEquals("6", h0.get("reservedTotal").asText());
        assertEquals("0", h0.get("writtenOffTotal").asText());
        assertEquals("6", h0.get("approvedUnwrittenTotal").asText());
        assertEquals("4", h0.get("remainingCap").asText());

        // 第二笔：h0 拟新增 5，6+5=11>10 -> 422，violations 列出 h0 及差额
        String a2 = submit(windowId, "u2", "5", "bob", List.of(planPoint(H0, "5")));
        MvcResult result = postJson("/api/allocations/" + a2 + "/approve",
                Map.of("commandKey", key("ap")), null, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("DRAWDOWN_HOURLY_CAP_EXCEEDED", error.get("code").asText());
        JsonNode violations = error.get("violations");
        assertEquals(1, violations.size());
        JsonNode v = violations.get(0);
        assertEquals(windowId, v.get("windowId").asLong());
        assertEquals(H0, v.get("hourUtc").asText());
        assertEquals("10", v.get("capVolume").asText());
        assertEquals("6", v.get("reservedVolume").asText());
        assertEquals("5", v.get("requestedVolume").asText());
        assertEquals("11", v.get("requiredVolume").asText());
        assertEquals("1", v.get("deficit").asText());
        // 失败不留预留
        assertEquals("6", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());

        // 两个小时同时超限，全部列出
        String a3 = submitFullPlan(windowId, "u3", "5", "7", "carol");
        MvcResult both = postJson("/api/allocations/" + a3 + "/approve",
                Map.of("commandKey", key("ap")), null, 422);
        JsonNode bothError = objectMapper.readTree(both.getResponse().getContentAsString());
        JsonNode bothViolations = bothError.get("violations");
        assertEquals(2, bothViolations.size());
        assertEquals(H0, bothViolations.get(0).get("hourUtc").asText());
        assertEquals(H1, bothViolations.get(1).get("hourUtc").asText());
        // h0: 已预留 6 + 本次 5 = 11，差额 1；h1: 已预留 4 + 本次 7 = 11，差额 1
        assertEquals("1", bothViolations.get(0).get("deficit").asText());
        assertEquals("1", bothViolations.get(1).get("deficit").asText());

        // 合法的小时组合仍可批准
        String a4 = submitFullPlan(windowId, "u4", "4", "6", "dave");
        approve(a4, 200);
        status = drawdown(windowId);
        assertEquals("10", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("10", hourStatus(status, H1).get("reservedTotal").asText());
        assertEquals("0", hourStatus(status, H0).get("remainingCap").asText());
    }

    @Test
    void totalQuotaAndHourlyCapsAreBothEnforced() throws Exception {
        long windowId = createHourlyWindow("ch-both-" + run, "10");
        setCurve(windowId, "100", "100", 200);
        // 逐小时放得下但超过窗口计划总量 -> 422 QUOTA_EXCEEDED
        String a = submitFullPlan(windowId, "u1", "6", "6", "alice");
        MvcResult result = postJson("/api/allocations/" + a + "/approve",
                Map.of("commandKey", key("ap")), null, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        assertTrue(error.get("violations").isNull());
    }

    @Test
    void cancelReleasesHourlyReservations() throws Exception {
        long windowId = createHourlyWindow("ch-cancel-hour-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String a = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a, 200);
        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");

        JsonNode status = drawdown(windowId);
        assertEquals("0", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("0", hourStatus(status, H0).get("writtenOffTotal").asText());
        assertEquals("10", hourStatus(status, H0).get("remainingCap").asText());
        // 快照行仍然保留（历史证据不改写）
        JsonNode allocation = getOk("/api/allocations/" + a);
        assertEquals("CANCELLED", allocation.get("status").asText());
        assertEquals(2, allocation.get("reservations").size());
        assertEquals(1, allocation.get("approvedCurveVersion").asInt());
        // 释放后可重新占用
        String a2 = submitFullPlan(windowId, "u2", "10", "10", "bob");
        approve(a2, 200);
        assertEquals("10", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 批量批准
    // ------------------------------------------------------------------

    @Test
    void batchApproveCommitsOnceAndRollsBackEverythingOnAnyFailure() throws Exception {
        // 总量 40 足够本批 22，失败点仅在逐小时上限
        long windowId = createHourlyWindow("ch-batch-" + run, "40");
        setCurve(windowId, "10", "10", 200);
        String ok1 = submit(windowId, "u1", "6", "alice", List.of(planPoint(H0, "6")));
        String good = submit(windowId, "u2", "4", "bob", List.of(planPoint(H1, "4")));
        String bad = submitFullPlan(windowId, "u3", "5", "7", "carol");

        List<String> keys = List.of(ok1, good, bad);
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ba"));
        body.put("allocationKeys", keys);
        MvcResult result = postJson("/api/allocations/batch-approve", body, null, 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("DRAWDOWN_HOURLY_CAP_EXCEEDED", error.get("code").asText());
        // 整批回滚：三笔全部仍是 REQUESTED，无任何预留
        for (String k : keys) {
            assertEquals("REQUESTED", getOk("/api/allocations/" + k).get("status").asText());
        }
        assertEquals("0", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());
        assertEquals("0", hourStatus(drawdown(windowId), H1).get("reservedTotal").asText());

        // 失败不占键：同一 commandKey 换成合法批次后成功
        Map<String, Object> retry = new HashMap<>();
        retry.put("commandKey", body.get("commandKey"));
        retry.put("allocationKeys", List.of(ok1, good));
        JsonNode resp = objectMapper.readTree(
                postJson("/api/allocations/batch-approve", retry, null, 200).getResponse().getContentAsString());
        assertEquals(2, resp.get("processedCount").asInt());
        assertEquals("6", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());
        assertEquals("4", hourStatus(drawdown(windowId), H1).get("reservedTotal").asText());

        // 同键同参重放返回首次结果，不重复执行（两笔已 APPROVED，仍返回 2 条）
        JsonNode replay = objectMapper.readTree(
                postJson("/api/allocations/batch-approve", retry, null, 200).getResponse().getContentAsString());
        assertEquals(resp, replay);
        // 同键改参 -> 409
        Map<String, Object> changed = new HashMap<>();
        changed.put("commandKey", retry.get("commandKey"));
        changed.put("allocationKeys", List.of(ok1));
        postJson("/api/allocations/batch-approve", changed, null, 409);

        // 列表重复 -> 400；空列表 -> 400
        postJson("/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(ok1, ok1)), null, 400);
        postJson("/api/allocations/batch-approve",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of()), null, 400);
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    @Test
    void writeoffDeductsOnlySameHourAndCannotBorrowOtherHours() throws Exception {
        long windowId = createHourlyWindow("ch-wo-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String a = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a, 200);

        JsonNode wo1 = postOk("/api/allocations/" + a + "/writeoffs",
                writeoffBody(key("wk"), H0, "4"), "alice");
        assertEquals(H0, wo1.get("hourUtc").asText());
        assertEquals("4", wo1.get("amount").asText());

        JsonNode status = drawdown(windowId);
        JsonNode h0 = hourStatus(status, H0);
        assertEquals("6", h0.get("reservedTotal").asText());
        assertEquals("4", h0.get("writtenOffTotal").asText());
        assertEquals("2", h0.get("approvedUnwrittenTotal").asText());
        assertEquals("4", h0.get("remainingCap").asText());

        // h0 只剩 2 未核销，再核 3 -> 422，错误体给实际值/要求值/差额
        MvcResult over = postJson("/api/allocations/" + a + "/writeoffs",
                writeoffBody(key("wk"), H0, "3"), "alice", 422);
        JsonNode error = objectMapper.readTree(over.getResponse().getContentAsString());
        assertEquals("DRAWDOWN_HOURLY_CAP_EXCEEDED", error.get("code").asText());
        JsonNode v = error.get("violations").get(0);
        assertEquals("6", v.get("reservedVolume").asText());
        assertEquals("3", v.get("requestedVolume").asText());
        assertEquals("1", v.get("deficit").asText());

        // h1 的 4 不能挪用到 h0：h1 自己可全额核销
        postOk("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H1, "4"), "alice");
        postJson("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H1, "0.001"),
                "alice", 422);
        // h0 剩余 2 仍可核销
        postOk("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H0, "2"), "alice");
        status = drawdown(windowId);
        assertEquals("6", hourStatus(status, H0).get("writtenOffTotal").asText());
        assertEquals("0", hourStatus(status, H0).get("approvedUnwrittenTotal").asText());

        // 快照不被核销改写
        JsonNode allocation = getOk("/api/allocations/" + a);
        assertEquals("6", allocation.get("reservations").get(0).get("reservedVolume").asText());
        assertEquals(1, allocation.get("approvedCurveVersion").asInt());

        // 非申请人 409；writeoffKey 复用 409；申请不存在 404；小时越界 400；非整点 400
        postJson("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H0, "1"), "bob", 409);
        postJson("/api/allocations/" + a + "/writeoffs", writeoffBody(wo1.get("writeoffKey").asText(),
                H0, "1"), "alice", 409);
        postJson("/api/allocations/no-such-" + run + "/writeoffs", writeoffBody(key("wk"), H0, "1"),
                "alice", 404);
        postJson("/api/allocations/" + a + "/writeoffs",
                writeoffBody(key("wk"), "2026-12-01T02:00:00Z", "1"), "alice", 400);
        postJson("/api/allocations/" + a + "/writeoffs",
                writeoffBody(key("wk"), "2026-12-01T00:30:00Z", "1"), "alice", 400);
    }

    @Test
    void writeoffRejectedForUnreservedHourAndNonApprovedAllocation() throws Exception {
        long windowId = createHourlyWindow("ch-wo2-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        // 该申请只在 h0 用水
        String a = submit(windowId, "u1", "6", "alice", List.of(planPoint(H0, "6")));
        // REQUESTED 不能核销 -> 409
        postJson("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H0, "1"),
                "alice", 409);
        approve(a, 200);
        // h1 没有预留 -> 409，不能借 h1 的窗口余量
        postJson("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H1, "1"),
                "alice", 409);
    }

    @Test
    void writeoffAfterCancelStillConsumesHourAndIsIdempotentReplay() throws Exception {
        long windowId = createHourlyWindow("ch-wo-cancel-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String a = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a, 200);
        String writeoffKey = key("wk");
        Map<String, Object> body = writeoffBody(writeoffKey, H0, "4");
        JsonNode first = postOk("/api/allocations/" + a + "/writeoffs", body, "alice");
        JsonNode replay = postOk("/api/allocations/" + a + "/writeoffs", body, "alice");
        assertEquals(first, replay);
        // 只产生一条核销
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("writeoffs").size());

        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");
        // 已核销的 4 既成事实仍占 h0 上限，未核销部分全部释放
        JsonNode status = drawdown(windowId);
        JsonNode h0 = hourStatus(status, H0);
        assertEquals("4", h0.get("reservedTotal").asText());
        assertEquals("4", h0.get("writtenOffTotal").asText());
        assertEquals("6", h0.get("remainingCap").asText());
        assertEquals("10", hourStatus(status, H1).get("remainingCap").asText());
        // 新申请在 h0 最多再用 6
        String a2 = submit(windowId, "u2", "7", "bob", List.of(planPoint(H0, "7")));
        postJson("/api/allocations/" + a2 + "/approve", Map.of("commandKey", key("ap")), null, 422);
        String a3 = submit(windowId, "u3", "6", "carol", List.of(planPoint(H0, "6")));
        approve(a3, 200);
        // 历史仍保留取消申请的核销流水与预留快照
        history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("writeoffs").size());
        assertNotNull(history.get("activeCurve"));
    }

    // ------------------------------------------------------------------
    // 转让逐小时
    // ------------------------------------------------------------------

    @Test
    void transferReleasesSourceHoursAndReservesTargetHours() throws Exception {
        long windowId = createHourlyWindow("ch-tx-hour-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String source = submitFullPlan(windowId, "u-src", "6", "4", "alice");
        String target = submitFullPlan(windowId, "u-dst", "3", "2", "bob");
        approve(source, 200);

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        postOk("/api/transfers", body, "alice");

        // 小时总占用不变（源释放 3/2，目标新增 3/2）
        JsonNode status = drawdown(windowId);
        assertEquals("6", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("4", hourStatus(status, H1).get("reservedTotal").asText());
        // 源剩余未核销：h0=3,h1=2；目标持有 3/2 快照（版本 1）
        JsonNode targetNode = getOk("/api/allocations/" + target);
        assertEquals("APPROVED", targetNode.get("status").asText());
        assertEquals("3", targetNode.get("reservations").get(0).get("reservedVolume").asText());
        assertEquals("2", targetNode.get("reservations").get(1).get("reservedVolume").asText());
        assertEquals(1, targetNode.get("approvedCurveVersion").asInt());
        // 源只能再核销其剩余部分：h0 再核 4 -> 422（余 3），核 3 成功
        postJson("/api/allocations/" + source + "/writeoffs",
                writeoffBody(key("wk"), H0, "4"), "alice", 422);
        postOk("/api/allocations/" + source + "/writeoffs", writeoffBody(key("wk"), H0, "3"), "alice");
    }

    @Test
    void transferIntoCrowdedHourReturns422AndRollsBack() throws Exception {
        long windowId = createHourlyWindow("ch-tx-422-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        // 其他申请已占 h0 = 5；源申请 10 全在 h1
        String other = submit(windowId, "u-other", "5", "zoe", List.of(planPoint(H0, "5")));
        String source = submit(windowId, "u-src", "10", "alice", List.of(planPoint(H1, "10")));
        approve(other, 200);
        approve(source, 200);
        // 目标把 10 全部放在 h0：源只能释放 h1，h0 净增 10 -> 5+10=15>10 -> 422
        String target = submit(windowId, "u-dst", "10", "bob", List.of(planPoint(H0, "10")));
        MvcResult result = postJson("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("DRAWDOWN_HOURLY_CAP_EXCEEDED", error.get("code").asText());
        JsonNode v = error.get("violations").get(0);
        assertEquals(H0, v.get("hourUtc").asText());
        assertEquals("5", v.get("reservedVolume").asText());
        assertEquals("10", v.get("requestedVolume").asText());
        assertEquals("5", v.get("deficit").asText());
        // 回滚：源仍持有 10，目标仍 REQUESTED，无流水
        assertEquals("10", getOk("/api/allocations/" + source).get("heldAmount").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + target).get("status").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        JsonNode status = drawdown(windowId);
        assertEquals("5", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("10", hourStatus(status, H1).get("reservedTotal").asText());
    }

    @Test
    void batchTransferSettlesCompleteFinalStateFromOneSource() throws Exception {
        long windowId = createHourlyWindow("ch-btx-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        // A 持有 10（h0=6,h1=4），批量转出 B 计划 6（h0）、C 计划 4（h1）：基于完整最终额度一次成功
        String a = submitFullPlan(windowId, "u-a", "6", "4", "alice");
        String b = submit(windowId, "u-b", "6", "bob", List.of(planPoint(H0, "6")));
        String c = submit(windowId, "u-c", "4", "carol", List.of(planPoint(H1, "4")));
        approve(a, 200);

        Map<String, Object> item1 = new HashMap<>();
        item1.put("transferKey", key("tk"));
        item1.put("sourceAllocationKey", a);
        item1.put("targetAllocationKey", b);
        Map<String, Object> item2 = new HashMap<>();
        item2.put("transferKey", key("tk"));
        item2.put("sourceAllocationKey", a);
        item2.put("targetAllocationKey", c);
        Map<String, Object> batchBody = new HashMap<>();
        batchBody.put("commandKey", key("btc"));
        batchBody.put("transfers", List.of(item1, item2));
        JsonNode resp = objectMapper.readTree(
                postJson("/api/transfers/batch", batchBody, "alice", 200).getResponse().getContentAsString());
        assertEquals(2, resp.get("processedCount").asInt());

        // 最终持有：A=0、B=6、C=4；各小时总占用不变 h0=6,h1=4
        assertEquals("0", getOk("/api/allocations/" + a).get("heldAmount").asText());
        assertEquals("6", getOk("/api/allocations/" + b).get("heldAmount").asText());
        assertEquals("4", getOk("/api/allocations/" + c).get("heldAmount").asText());
        assertEquals("APPROVED", getOk("/api/allocations/" + b).get("status").asText());
        assertEquals("APPROVED", getOk("/api/allocations/" + c).get("status").asText());
        JsonNode status = drawdown(windowId);
        assertEquals("6", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("4", hourStatus(status, H1).get("reservedTotal").asText());
        assertEquals(2, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        // A 已全部转出，不能再核销
        postJson("/api/allocations/" + a + "/writeoffs",
                writeoffBody(key("wk"), H0, "0.001"), "alice", 422);

        // 同键同参重放返回首次结果，不新增流水
        JsonNode replay = objectMapper.readTree(
                postJson("/api/transfers/batch", batchBody, "alice", 200).getResponse().getContentAsString());
        assertEquals(resp, replay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        // 同键改参 -> 409
        Map<String, Object> changed = new HashMap<>();
        changed.put("commandKey", batchBody.get("commandKey"));
        changed.put("transfers", List.of(item1));
        postJson("/api/transfers/batch", changed, "alice", 409);
    }

    @Test
    void transferCannotReleaseWrittenOffWaterAndHourlyRoomIsEnforced() throws Exception {
        long windowId = createHourlyWindow("ch-tx-wo-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        // 源 10 全在 h0，先实际核销 6，则可转让释放最多 4（不能转让已下泄水量）
        String source = submit(windowId, "u-src", "10", "alice", List.of(planPoint(H0, "10")));
        String target5 = submit(windowId, "u-t5", "5", "bob", List.of(planPoint(H0, "5")));
        String target4 = submit(windowId, "u-t4", "4", "carol", List.of(planPoint(H1, "4")));
        approve(source, 200);
        postOk("/api/allocations/" + source + "/writeoffs", writeoffBody(key("wk"), H0, "6"), "alice");

        // 持有额度仍为 10（核销不释放窗口配额），但逐小时未核销余量仅 4：转 5 -> 422
        assertEquals("10", getOk("/api/allocations/" + source).get("heldAmount").asText());
        MvcResult over = postJson("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target5), "alice", 422);
        JsonNode error = objectMapper.readTree(over.getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        // 失败回滚：目标仍 REQUESTED
        assertEquals("REQUESTED", getOk("/api/allocations/" + target5).get("status").asText());

        // 转 4 成功：源在 h0 释放未核销的 4，目标在 h1 新增 4，两小时均不超限
        postOk("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target4), "alice");
        JsonNode status = drawdown(windowId);
        // h0：已核销 6（源剩余未核销 0），h1：目标 4
        assertEquals("6", hourStatus(status, H0).get("reservedTotal").asText());
        assertEquals("6", hourStatus(status, H0).get("writtenOffTotal").asText());
        assertEquals("4", hourStatus(status, H1).get("reservedTotal").asText());
        assertEquals("6", getOk("/api/allocations/" + source).get("heldAmount").asText());
        // 源 h0 未核销余量为 0，再核销任何正数都 422
        postJson("/api/allocations/" + source + "/writeoffs",
                writeoffBody(key("wk"), H0, "0.001"), "alice", 422);
    }

    @Test
    void batchTransferRollsBackWhenLaterItemFails() throws Exception {
        long windowId = createHourlyWindow("ch-btx-fail-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String a = submit(windowId, "u-a", "10", "alice", List.of(planPoint(H0, "10")));
        String t1 = submit(windowId, "u-t1", "6", "bob", List.of(planPoint(H0, "6")));
        String t2 = submit(windowId, "u-t2", "5", "carol", List.of(planPoint(H0, "5")));
        approve(a, 200);
        // A 持 10：转出 6 后只剩 4，第二笔再转 5 持有不足 -> 整批回滚
        Map<String, Object> item1 = new HashMap<>();
        item1.put("transferKey", key("tk"));
        item1.put("sourceAllocationKey", a);
        item1.put("targetAllocationKey", t1);
        Map<String, Object> item2 = new HashMap<>();
        item2.put("transferKey", key("tk"));
        item2.put("sourceAllocationKey", a);
        item2.put("targetAllocationKey", t2);
        postJson("/api/transfers/batch",
                Map.of("commandKey", key("btc"), "transfers", List.of(item1, item2)), "alice", 422);

        assertEquals("10", getOk("/api/allocations/" + a).get("heldAmount").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + t1).get("status").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + t2).get("status").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        assertEquals("10", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());

        // 同一申请在批量中同时作为转出与转入 -> 409
        Map<String, Object> bad1 = new HashMap<>();
        bad1.put("transferKey", key("tk"));
        bad1.put("sourceAllocationKey", a);
        bad1.put("targetAllocationKey", t1);
        Map<String, Object> bad2 = new HashMap<>();
        bad2.put("transferKey", key("tk"));
        bad2.put("sourceAllocationKey", t1);
        bad2.put("targetAllocationKey", t2);
        postJson("/api/transfers/batch",
                Map.of("commandKey", key("btc"), "transfers", List.of(bad1, bad2)), "alice", 409);
        // 重复 transferKey -> 400；空批量 -> 400
        postJson("/api/transfers/batch",
                Map.of("commandKey", key("btc"), "transfers", List.of(item1, item1)), "alice", 400);
        postJson("/api/transfers/batch",
                Map.of("commandKey", key("btc"), "transfers", List.of()), "alice", 400);
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesNeverOversellHourCap() throws Exception {
        long windowId = createHourlyWindow("ch-conc-hour-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        List<String> allocations = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            allocations.add(submit(windowId, "u-" + i, "3", "actor-" + i,
                    List.of(planPoint(H0, "3"))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : allocations) {
            String commandKey = key("ap");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.approveAllocation(commandKey, allocationKey);
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int s = future.get(30, TimeUnit.SECONDS);
            if (s == 200) {
                ok++;
            } else if (s == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertEquals(3, ok, "每笔 3，h0 上限 10，恰好 3 笔成功");
        assertEquals(1, rejected);
        JsonNode h0 = hourStatus(drawdown(windowId), H0);
        assertEquals("9", h0.get("reservedTotal").asText());
        assertEquals("1", h0.get("remainingCap").asText());
    }

    @Test
    void concurrentTransfersFromOneSourceNeverOversellHour() throws Exception {
        long windowId = createHourlyWindow("ch-conc-tx-" + run, "20");
        setCurve(windowId, "10", "10", 200);
        String source = submit(windowId, "u-src", "10", "alice", List.of(planPoint(H0, "10")));
        String t1 = submit(windowId, "u-t1", "6", "bob", List.of(planPoint(H0, "6")));
        String t2 = submit(windowId, "u-t2", "6", "carol", List.of(planPoint(H0, "6")));
        approve(source, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String ck1 = key("tc");
        String tk1 = key("tk");
        String ck2 = key("tc");
        String tk2 = key("tk");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return statusOf(() -> waterService.transferAllocation(ck1, tk1, source, t1, "alice"));
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return statusOf(() -> waterService.transferAllocation(ck2, tk2, source, t2, "alice"));
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "同一小时并发转出恰好一笔成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        assertEquals("4", getOk("/api/allocations/" + source).get("heldAmount").asText());
        assertEquals("10", hourStatus(drawdown(windowId), H0).get("reservedTotal").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
    }

    // ------------------------------------------------------------------
    // 诊断与历史
    // ------------------------------------------------------------------

    @Test
    void drawdownStatusAndHistoryAreReadOnlyAndComplete() throws Exception {
        long windowId = createHourlyWindow("ch-diag-" + run, "20");
        JsonNode empty = drawdown(windowId);
        assertTrue(empty.get("activeCurveVersion").isNull());
        assertEquals(0, empty.get("hours").size());

        setCurve(windowId, "10", "10", 200);
        String a = submitFullPlan(windowId, "u1", "6", "4", "alice");
        approve(a, 200);
        postOk("/api/allocations/" + a + "/writeoffs", writeoffBody(key("wk"), H0, "2"), "alice");

        JsonNode status = drawdown(windowId);
        assertEquals(1, status.get("activeCurveVersion").asInt());
        assertEquals(2, status.get("hours").size());
        JsonNode h0 = hourStatus(status, H0);
        assertEquals("10", h0.get("capVolume").asText());
        assertEquals("6", h0.get("reservedTotal").asText());
        assertEquals("2", h0.get("writtenOffTotal").asText());
        assertEquals("4", h0.get("approvedUnwrittenTotal").asText());
        assertEquals("4", h0.get("remainingCap").asText());

        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("allocations").size());
        assertEquals(1, history.get("activeCurve").get("version").asInt());
        assertEquals(1, history.get("writeoffs").size());
        assertEquals("2", history.get("writeoffs").get(0).get("amount").asText());
        assertEquals(a, history.get("writeoffs").get(0).get("allocationKey").asText());
        assertEquals(2, history.get("allocations").get(0).get("reservations").size());

        // 只读查询不存在的窗口 -> 404
        mvc.perform(get("/api/windows/999999999/drawdown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run)).andExpect(status().isNotFound());
    }
}
