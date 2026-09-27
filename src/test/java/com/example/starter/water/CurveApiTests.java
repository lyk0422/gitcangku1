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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 水库下泄曲线 API 测试：曲线配置与版本、逐小时预留守恒、核销、批量批准、
 * 失败分支、幂等与并发边界。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurveApiTests {

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
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
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

    private Map<String, Object> curveBody(String commandKey, String... hourLimitPairs) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < hourLimitPairs.length; i += 2) {
            points.add(Map.of("hourStartUtc", hourLimitPairs[i], "limit", hourLimitPairs[i + 1]));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("points", points);
        return body;
    }

    private void putCurve(long windowId, int expectedStatus, String... hourLimitPairs) throws Exception {
        postJson("/api/windows/" + windowId + "/curve", curveBody(key("cv"), hourLimitPairs), null,
                expectedStatus);
    }

    private String submit(long windowId, String userId, String amount, String actor,
                          String... hourAmountPairs) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (hourAmountPairs.length > 0) {
            List<Map<String, Object>> hourly = new ArrayList<>();
            for (int i = 0; i < hourAmountPairs.length; i += 2) {
                hourly.add(Map.of("hourStartUtc", hourAmountPairs[i], "amount", hourAmountPairs[i + 1]));
            }
            body.put("hourlyAmounts", hourly);
        }
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private JsonNode approve(String allocationKey, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? null : objectMapper.readTree(content);
    }

    private JsonNode hours(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/hours");
    }

    private JsonNode hourAt(JsonNode hoursResponse, String hourStartUtc) {
        for (JsonNode node : hoursResponse.get("hours")) {
            if (hourStartUtc.equals(node.get("hourStartUtc").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("hour not found: " + hourStartUtc);
    }

    // ------------------------------------------------------------------
    // 曲线配置与版本
    // ------------------------------------------------------------------

    @Test
    void putCurveCoversAllHoursAndVersionsAccumulate() throws Exception {
        long windowId = createWindow("ch-cv-" + run,
                "2026-10-20T00:00:00Z", "2026-10-20T02:00:00Z", "10");
        JsonNode curve = postOk("/api/windows/" + windowId + "/curve",
                curveBody(key("cv"), "2026-10-20T00:00:00Z", "1.5", "2026-10-20T01:00:00Z", "2"), null);
        assertEquals(1, curve.get("version").asInt());
        assertEquals(2, curve.get("points").size());
        assertEquals("1.5", curve.get("points").get(0).get("limit").asText());

        // 当前曲线查询
        JsonNode current = getOk("/api/windows/" + windowId + "/curve");
        assertEquals(1, current.get("version").asInt());

        // 全量替换（窗口在未来，全部小时未开始）-> 版本 2
        JsonNode v2 = postOk("/api/windows/" + windowId + "/curve",
                curveBody(key("cv"), "2026-10-20T00:00:00Z", "3", "2026-10-20T01:00:00Z", "0.25"), null);
        assertEquals(2, v2.get("version").asInt());
        assertEquals("0.25", v2.get("points").get(1).get("limit").asText());

        // 历史版本完整保留且不可改写
        JsonNode versions = getOk("/api/windows/" + windowId + "/curve/versions");
        assertEquals(2, versions.get("versions").size());
        assertEquals(1, versions.get("versions").get(0).get("version").asInt());
        assertEquals("1.5", versions.get("versions").get(0).get("points").get(0).get("limit").asText());
        assertEquals(2, versions.get("versions").get(1).get("version").asInt());
        assertEquals("3", getOk("/api/windows/" + windowId + "/curve").get("points").get(0)
                .get("limit").asText());
    }

    @Test
    void curveValidationFailures() throws Exception {
        long windowId = createWindow("ch-cvb-" + run,
                "2026-10-21T00:00:00Z", "2026-10-21T02:00:00Z", "10");
        String h0 = "2026-10-21T00:00:00Z";
        String h1 = "2026-10-21T01:00:00Z";
        // 缺失小时 -> 400
        putCurve(windowId, 400, h0, "1");
        // 跨窗口小时 -> 400
        putCurve(windowId, 400, h0, "1", h1, "1", "2026-10-21T02:00:00Z", "1");
        // 非整点 -> 400
        putCurve(windowId, 400, "2026-10-21T00:30:00Z", "1", h1, "1");
        // 超过 2 位小数 -> 400
        putCurve(windowId, 400, h0, "1.001", h1, "1");
        // 负数 -> 400
        putCurve(windowId, 400, h0, "-1", h1, "1");
        // 重复小时 -> 400
        putCurve(windowId, 400, h0, "1", h0, "2", h1, "1");
        // 空点列表 -> 400
        postJson("/api/windows/" + windowId + "/curve", curveBody(key("cv")), null, 400);
        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/curve", curveBody(key("cv"), h0, "1", h1, "1"), null, 404);
        // 未配置曲线时查询 -> 404
        mvc.perform(get("/api/windows/" + windowId + "/curve")).andExpect(status().isNotFound());
        // 非负：0 合法
        putCurve(windowId, 200, h0, "0", h1, "1.25");
    }

    @Test
    void nonHourAlignedWindowRequiresFloorToCeilingHours() throws Exception {
        // 窗口 00:30 - 02:30 -> 覆盖小时 00, 01, 02
        long windowId = createWindow("ch-cvf-" + run,
                "2026-10-22T00:30:00Z", "2026-10-22T02:30:00Z", "10");
        putCurve(windowId, 400, "2026-10-22T00:00:00Z", "1", "2026-10-22T01:00:00Z", "1");
        JsonNode curve = postOk("/api/windows/" + windowId + "/curve",
                curveBody(key("cv"), "2026-10-22T00:00:00Z", "1", "2026-10-22T01:00:00Z", "2",
                        "2026-10-22T02:00:00Z", "3"), null);
        assertEquals(3, curve.get("points").size());
        JsonNode diag = hours(windowId);
        assertEquals(3, diag.get("hours").size());
        assertEquals("3", hourAt(diag, "2026-10-22T02:00:00Z").get("limit").asText());
    }

    @Test
    void startedHoursCannotBeModified() throws Exception {
        Instant hour0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        String h0 = hour0.toString();
        String h1 = hour0.plus(1, ChronoUnit.HOURS).toString();
        String h2 = hour0.plus(2, ChronoUnit.HOURS).toString();
        String h3 = hour0.plus(3, ChronoUnit.HOURS).toString();
        long windowId = createWindow("ch-cvs-" + run, h0, hour0.plus(4, ChronoUnit.HOURS).toString(), "10");
        putCurve(windowId, 200, h0, "1", h1, "1", h2, "1", h3, "1");

        // 修改已开始小时（h0/h1/h2 均已开始）-> 409
        putCurve(windowId, 409, h0, "2", h1, "1", h2, "1", h3, "1");
        putCurve(windowId, 409, h0, "1", h1, "1", h2, "5", h3, "1");
        // 仅修改尚未开始的 h3 -> 200，版本递增
        JsonNode v2 = postOk("/api/windows/" + windowId + "/curve",
                curveBody(key("cv"), h0, "1", h1, "1", h2, "1", h3, "9"), null);
        assertEquals(2, v2.get("version").asInt());
        assertEquals("9", hourAt(hours(windowId), h3).get("limit").asText());
        assertEquals("1", hourAt(hours(windowId), h0).get("limit").asText());
    }

    // ------------------------------------------------------------------
    // 逐小时预留与批准
    // ------------------------------------------------------------------

    @Test
    void approveWithCurveReservesHoursAndFixesSnapshot() throws Exception {
        long windowId = createWindow("ch-ap-" + run,
                "2026-10-23T00:00:00Z", "2026-10-23T02:00:00Z", "10");
        String h0 = "2026-10-23T00:00:00Z";
        String h1 = "2026-10-23T01:00:00Z";
        putCurve(windowId, 200, h0, "1", h1, "2");

        String a1 = submit(windowId, "user-1", "1.5", "alice", h0, "0.5", h1, "1");
        approve(a1, 200);

        JsonNode diag = hours(windowId);
        assertEquals("0.5", hourAt(diag, h0).get("reserved").asText());
        assertEquals("0.5", hourAt(diag, h0).get("remaining").asText());
        assertEquals("1", hourAt(diag, h1).get("reserved").asText());
        assertEquals("1", hourAt(diag, h1).get("remaining").asText());
        assertEquals("0", hourAt(diag, h0).get("redeemed").asText());

        // 明细：逐小时状态 + 批准快照（曲线版本 1）
        JsonNode detail = getOk("/api/allocations/" + a1);
        assertEquals("APPROVED", detail.get("allocation").get("status").asText());
        assertEquals(2, detail.get("hours").size());
        assertEquals("0.5", detail.get("hours").get(0).get("reserved").asText());
        assertEquals("0.5", detail.get("hours").get(0).get("remaining").asText());
        JsonNode snapshot = detail.get("approvalSnapshot");
        assertEquals(1, snapshot.get("curveVersion").asInt());
        assertEquals(2, snapshot.get("hours").size());
        assertEquals("1", snapshot.get("hours").get(1).get("amount").asText());

        // 曲线调整后快照不改写
        putCurve(windowId, 200, h0, "9", h1, "9");
        JsonNode detailAfter = getOk("/api/allocations/" + a1);
        assertEquals(1, detailAfter.get("approvalSnapshot").get("curveVersion").asInt());
        assertEquals("0.5", detailAfter.get("approvalSnapshot").get("hours").get(0).get("amount").asText());
    }

    @Test
    void approveExceedingCurveListsAllViolationsAndKeyNotOccupied() throws Exception {
        long windowId = createWindow("apx-" + run,
                "2026-10-24T00:00:00Z", "2026-10-24T02:00:00Z", "10");
        String h0 = "2026-10-24T00:00:00Z";
        String h1 = "2026-10-24T01:00:00Z";
        putCurve(windowId, 200, h0, "1", h1, "1");

        String a1 = submit(windowId, "user-1", "0.8", "alice", h0, "0.8");
        approve(a1, 200);
        // a2 在 h0 超 0.3、h1 超 0.5，两小时都应列出
        String a2 = submit(windowId, "user-2", "2", "bob", h0, "0.5", h1, "1.5");
        String approveCommand = key("ap");
        MvcResult rejected = postJson("/api/allocations/" + a2 + "/approve",
                Map.of("commandKey", approveCommand), null, 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("CURVE_LIMIT_EXCEEDED", error.get("code").asText());
        assertEquals(2, error.get("violations").size());
        JsonNode v0 = error.get("violations").get(0);
        assertEquals(h0, v0.get("hourStartUtc").asText());
        assertEquals("1", v0.get("limit").asText());
        assertEquals("1.3", v0.get("required").asText());
        assertEquals("0.3", v0.get("deficit").asText());
        JsonNode v1 = error.get("violations").get(1);
        assertEquals(h1, v1.get("hourStartUtc").asText());
        assertEquals("1.5", v1.get("required").asText());
        assertEquals("0.5", v1.get("deficit").asText());

        // 申请仍为 REQUESTED，无快照，诊断无变化
        JsonNode detail = getOk("/api/allocations/" + a2);
        assertEquals("REQUESTED", detail.get("allocation").get("status").asText());
        assertTrue(detail.get("approvalSnapshot").isNull());
        assertEquals("0.8", hourAt(hours(windowId), h0).get("reserved").asText());

        // 失败不占键：提高曲线后同 commandKey 重试成功
        putCurve(windowId, 200, h0, "2", h1, "2");
        JsonNode approved = postOk("/api/allocations/" + a2 + "/approve",
                Map.of("commandKey", approveCommand), null);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals("1.3", hourAt(hours(windowId), h0).get("reserved").asText());
    }

    @Test
    void hourlySubmitValidation() throws Exception {
        long windowId = createWindow("ch-hv-" + run,
                "2026-10-25T00:00:00Z", "2026-10-25T02:00:00Z", "10");
        String h0 = "2026-10-25T00:00:00Z";
        String h1 = "2026-10-25T01:00:00Z";
        // 合计不等于申请水量 -> 400
        submitExpect(windowId, "1", 400, h0, "0.4", h1, "0.4");
        // 重复小时 -> 400
        submitExpect(windowId, "1", 400, h0, "0.5", h0, "0.5");
        // 跨窗口小时 -> 400
        submitExpect(windowId, "1", 400, h0, "0.5", "2026-10-25T02:00:00Z", "0.5");
        // 非整点 -> 400
        submitExpect(windowId, "1", 400, "2026-10-25T00:30:00Z", "1");
        // 合法
        submitExpect(windowId, "1", 200, h0, "0.4", h1, "0.6");
    }

    private void submitExpect(long windowId, String amount, int expectedStatus,
                              String... hourAmountPairs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", "user-x");
        body.put("amount", amount);
        List<Map<String, Object>> hourly = new ArrayList<>();
        for (int i = 0; i < hourAmountPairs.length; i += 2) {
            hourly.add(Map.of("hourStartUtc", hourAmountPairs[i], "amount", hourAmountPairs[i + 1]));
        }
        body.put("hourlyAmounts", hourly);
        postJson("/api/allocations", body, "alice", expectedStatus);
    }

    @Test
    void approveWithoutHourlyWhenCurveExistsReturns409() throws Exception {
        long windowId = createWindow("ch-miss-" + run,
                "2026-10-26T00:00:00Z", "2026-10-26T01:00:00Z", "10");
        String a1 = submit(windowId, "user-1", "1", "alice");
        approve(a1, 200);
        // 配置曲线后，无逐小时分布的申请不能批准
        putCurve(windowId, 200, "2026-10-26T00:00:00Z", "5");
        String a2 = submit(windowId, "user-2", "1", "bob");
        approve(a2, 409);
        assertEquals("REQUESTED", getOk("/api/allocations/" + a2).get("allocation").get("status").asText());
        // 有分布的申请正常批准
        String a3 = submit(windowId, "user-3", "1", "carol", "2026-10-26T00:00:00Z", "1");
        approve(a3, 200);
    }

    // ------------------------------------------------------------------
    // 转让逐小时守恒
    // ------------------------------------------------------------------

    @Test
    void transferMovesHourlyReservationWithConservation() throws Exception {
        long windowId = createWindow("ch-th-" + run,
                "2026-10-27T00:00:00Z", "2026-10-27T01:00:00Z", "10");
        String h0 = "2026-10-27T00:00:00Z";
        putCurve(windowId, 200, h0, "10");
        String source = submit(windowId, "user-src", "6", "alice", h0, "6");
        String target = submit(windowId, "user-dst", "2.5", "bob", h0, "2.5");
        approve(source, 200);

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        postOk("/api/transfers", body, "alice");

        // 逐小时守恒：转让前后窗口小时负载不变（6）
        assertEquals("6", hourAt(hours(windowId), h0).get("reserved").asText());
        // 源逐小时预留等额扣减
        JsonNode sourceDetail = getOk("/api/allocations/" + source);
        assertEquals("3.5", sourceDetail.get("hours").get(0).get("reserved").asText());
        // 目标批准并固化快照
        JsonNode targetDetail = getOk("/api/allocations/" + target);
        assertEquals("APPROVED", targetDetail.get("allocation").get("status").asText());
        assertEquals("2.5", targetDetail.get("hours").get(0).get("reserved").asText());
        assertEquals(1, targetDetail.get("approvalSnapshot").get("curveVersion").asInt());
    }

    @Test
    void transferWithInsufficientSourceHourReturns422() throws Exception {
        long windowId = createWindow("ch-ti-" + run,
                "2026-10-28T00:00:00Z", "2026-10-28T02:00:00Z", "10");
        String h0 = "2026-10-28T00:00:00Z";
        String h1 = "2026-10-28T01:00:00Z";
        putCurve(windowId, 200, h0, "10", h1, "10");
        // 源总额 4 足够目标 3，但 h0 只有 2
        String source = submit(windowId, "user-src", "4", "alice", h0, "2", h1, "2");
        String target = submit(windowId, "user-dst", "3", "bob", h0, "3");
        approve(source, 200);

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        MvcResult rejected = postJson("/api/transfers", body, "alice", 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("SOURCE_HOUR_INSUFFICIENT", error.get("code").asText());
        assertEquals(1, error.get("violations").size());
        assertEquals("2", error.get("violations").get(0).get("limit").asText());
        assertEquals("3", error.get("violations").get(0).get("required").asText());
        assertEquals("1", error.get("violations").get(0).get("deficit").asText());

        // 全部回滚：源预留不变、目标仍 REQUESTED
        assertEquals("2", getOk("/api/allocations/" + source).get("hours").get(0).get("reserved").asText());
        assertEquals("REQUESTED",
                getOk("/api/allocations/" + target).get("allocation").get("status").asText());
    }

    // ------------------------------------------------------------------
    // 取消与核销
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesHourlyReservationButKeepsRedeemed() throws Exception {
        long windowId = createWindow("ch-cr-" + run,
                "2026-10-29T00:00:00Z", "2026-10-29T01:00:00Z", "10");
        String h0 = "2026-10-29T00:00:00Z";
        putCurve(windowId, 200, h0, "10");
        String a1 = submit(windowId, "user-1", "5", "alice", h0, "5");
        approve(a1, 200);
        // 先核销 2，再取消：未核销 3 释放，已核销 2 保留
        redeem(a1, h0, "2", 200);
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");

        JsonNode diag = hourAt(hours(windowId), h0);
        assertEquals("0", diag.get("reserved").asText());
        assertEquals("2", diag.get("redeemed").asText());
        assertEquals("8", diag.get("remaining").asText());
        JsonNode detail = getOk("/api/allocations/" + a1);
        assertEquals("CANCELLED", detail.get("allocation").get("status").asText());
        assertEquals("2", detail.get("hours").get(0).get("reserved").asText());
        assertEquals("2", detail.get("hours").get(0).get("redeemed").asText());
        assertEquals("0", detail.get("hours").get(0).get("remaining").asText());
    }

    @Test
    void redeemDeductsSameHourOnlyAndRejectsOverflow() throws Exception {
        long windowId = createWindow("ch-rd-" + run,
                "2026-10-30T00:00:00Z", "2026-10-30T02:00:00Z", "10");
        String h0 = "2026-10-30T00:00:00Z";
        String h1 = "2026-10-30T01:00:00Z";
        putCurve(windowId, 200, h0, "10", h1, "10");
        String a1 = submit(windowId, "user-1", "1", "alice", h0, "0.6", h1, "0.4");
        approve(a1, 200);

        // 核销 h0 0.5：同小时扣减，持有额度同步减少
        JsonNode redemption = redeem(a1, h0, "0.5", 200);
        assertEquals(h0, redemption.get("hourStartUtc").asText());
        assertEquals("0.5", redemption.get("amount").asText());
        JsonNode detail = getOk("/api/allocations/" + a1);
        assertEquals("0.5", detail.get("allocation").get("heldAmount").asText());
        assertEquals("0.1", detail.get("hours").get(0).get("remaining").asText());
        assertEquals("0.5", detail.get("hours").get(0).get("redeemed").asText());
        assertEquals(1, detail.get("redemptions").size());

        // 不能挪用 h1 余量：h0 仅剩 0.1，核销 0.2 -> 422 及差额
        MvcResult rejected = postJson("/api/allocations/" + a1 + "/redeem",
                Map.of("commandKey", key("rd"), "hourStartUtc", h0, "amount", "0.2"), "alice", 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("REDEEM_EXCEEDS_HOUR", error.get("code").asText());
        assertEquals("0.1", error.get("violations").get(0).get("limit").asText());
        assertEquals("0.2", error.get("violations").get(0).get("required").asText());
        assertEquals("0.1", error.get("violations").get(0).get("deficit").asText());
        // 未预留的小时 -> 422（可用为 0）
        postJson("/api/allocations/" + a1 + "/redeem",
                Map.of("commandKey", key("rd"), "hourStartUtc", "2026-10-30T05:00:00Z", "amount", "0.1"),
                "alice", 422);
        // 未批准申请不能核销 -> 409
        String a2 = submit(windowId, "user-2", "1", "bob", h0, "1");
        postJson("/api/allocations/" + a2 + "/redeem",
                Map.of("commandKey", key("rd"), "hourStartUtc", h0, "amount", "0.1"), "bob", 409);
        // 核销流水不可变且只有一条
        assertEquals(1, getOk("/api/allocations/" + a1).get("redemptions").size());
    }

    private JsonNode redeem(String allocationKey, String hourStartUtc, String amount, int expectedStatus)
            throws Exception {
        MvcResult result = postJson("/api/allocations/" + allocationKey + "/redeem",
                Map.of("commandKey", key("rd"), "hourStartUtc", hourStartUtc, "amount", amount), "alice",
                expectedStatus);
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? null : objectMapper.readTree(content);
    }

    // ------------------------------------------------------------------
    // 批量批准
    // ------------------------------------------------------------------

    @Test
    void batchApproveValidatesFinalStateAndRollsBackEntirely() throws Exception {
        long windowId = createWindow("ch-ba-" + run,
                "2026-10-31T00:00:00Z", "2026-10-31T01:00:00Z", "10");
        String h0 = "2026-10-31T00:00:00Z";
        putCurve(windowId, 200, h0, "1");
        String a1 = submit(windowId, "user-1", "0.6", "alice", h0, "0.6");
        String a2 = submit(windowId, "user-2", "0.5", "bob", h0, "0.5");
        String a3 = submit(windowId, "user-3", "0.4", "carol", h0, "0.4");

        // 合并后 0.6 + 0.5 = 1.1 > 1 -> 整次 422，两者都未批准
        MvcResult rejected = postJson("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a2)), null, 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("CURVE_LIMIT_EXCEEDED", error.get("code").asText());
        assertEquals("1.1", error.get("violations").get(0).get("required").asText());
        assertEquals("0.1", error.get("violations").get(0).get("deficit").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + a1).get("allocation").get("status").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + a2).get("allocation").get("status").asText());
        assertEquals("0", hourAt(hours(windowId), h0).get("reserved").asText());

        // 0.6 + 0.4 = 1.0 恰好等于上限 -> 整批成功
        JsonNode ok = postOk("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a3)), null);
        assertEquals(2, ok.get("allocations").size());
        assertEquals("APPROVED", ok.get("allocations").get(0).get("status").asText());
        assertEquals("1", hourAt(hours(windowId), h0).get("reserved").asText());

        // 批内含已批准申请 -> 409，其余申请不受影响
        String a4 = submit(windowId, "user-4", "0.1", "dave", h0, "0.1");
        postJson("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a4)), null, 409);
        assertEquals("REQUESTED", getOk("/api/allocations/" + a4).get("allocation").get("status").asText());
        // 重复键 -> 400
        postJson("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a2, a2)), null, 400);
    }

    @Test
    void batchApproveRespectsWindowQuota() throws Exception {
        long windowId = createWindow("ch-bq-" + run,
                "2026-11-01T00:00:00Z", "2026-11-01T01:00:00Z", "1");
        String a1 = submit(windowId, "user-1", "0.6", "alice");
        String a2 = submit(windowId, "user-2", "0.5", "bob");
        // 无曲线时只校验窗口配额：0.6 + 0.5 > 1 -> 422 整批回滚
        postJson("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1, a2)), null, 422);
        assertEquals("REQUESTED", getOk("/api/allocations/" + a1).get("allocation").get("status").asText());
        assertEquals("REQUESTED", getOk("/api/allocations/" + a2).get("allocation").get("status").asText());
        JsonNode ok = postOk("/api/allocations/approve-batch",
                Map.of("commandKey", key("ba"), "allocationKeys", List.of(a1)), null);
        assertEquals(1, ok.get("allocations").size());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void curvePutAndRedeemAreIdempotent() throws Exception {
        long windowId = createWindow("ch-ci-" + run,
                "2026-11-02T00:00:00Z", "2026-11-02T01:00:00Z", "10");
        String h0 = "2026-11-02T00:00:00Z";
        String curveCommand = key("cv");
        Map<String, Object> curveBody = curveBody(curveCommand, h0, "5");
        JsonNode first = postOk("/api/windows/" + windowId + "/curve", curveBody, null);
        JsonNode replay = postOk("/api/windows/" + windowId + "/curve", curveBody, null);
        assertEquals(first, replay);
        // 只产生一个版本
        assertEquals(1, getOk("/api/windows/" + windowId + "/curve/versions").get("versions").size());
        // 同键改参 -> 409
        postJson("/api/windows/" + windowId + "/curve", curveBody(curveCommand, h0, "6"), null, 409);

        String a1 = submit(windowId, "user-1", "1", "alice", h0, "1");
        approve(a1, 200);
        String redeemCommand = key("rd");
        Map<String, Object> redeemBody = Map.of("commandKey", redeemCommand, "hourStartUtc", h0,
                "amount", "0.4");
        JsonNode r1 = postOk("/api/allocations/" + a1 + "/redeem", redeemBody, "alice");
        JsonNode r2 = postOk("/api/allocations/" + a1 + "/redeem", redeemBody, "alice");
        assertEquals(r1, r2);
        assertEquals(1, getOk("/api/allocations/" + a1).get("redemptions").size());
        // 同键改参 -> 409
        postJson("/api/allocations/" + a1 + "/redeem",
                Map.of("commandKey", redeemCommand, "hourStartUtc", h0, "amount", "0.5"), "alice", 409);
        assertEquals("0.6", getOk("/api/allocations/" + a1).get("allocation").get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesNeverExceedHourLimit() throws Exception {
        long windowId = createWindow("ch-cc-" + run,
                "2026-11-03T00:00:00Z", "2026-11-03T01:00:00Z", "10");
        String h0 = "2026-11-03T00:00:00Z";
        putCurve(windowId, 200, h0, "1");
        List<String> allocationKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allocationKeys.add(submit(windowId, "user-" + i, "0.2", "alice", h0, "0.2"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : allocationKeys) {
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
        int exceeded = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                exceeded++;
            }
        }
        pool.shutdown();
        assertEquals(5, ok, "小时上限 1.0 恰好容纳 5 个 0.2");
        assertEquals(5, exceeded);
        JsonNode diag = hourAt(hours(windowId), h0);
        assertEquals("1", diag.get("reserved").asText());
        assertEquals("0", diag.get("remaining").asText());
    }

    @Test
    void diagnosticsAreStableAndReadOnly() throws Exception {
        long windowId = createWindow("ch-dg-" + run,
                "2026-11-04T00:00:00Z", "2026-11-04T01:00:00Z", "10");
        String h0 = "2026-11-04T00:00:00Z";
        // 未配置曲线：limit/remaining 为 null
        JsonNode before = hourAt(hours(windowId), h0);
        assertTrue(before.get("limit").isNull());
        assertTrue(before.get("remaining").isNull());
        assertEquals("0", before.get("reserved").asText());

        putCurve(windowId, 200, h0, "3");
        String a1 = submit(windowId, "user-1", "1", "alice", h0, "1");
        approve(a1, 200);
        JsonNode first = hours(windowId);
        JsonNode second = hours(windowId);
        assertEquals(first, second, "读取不改变状态");
        assertEquals("3", hourAt(second, h0).get("limit").asText());
        assertEquals("1", hourAt(second, h0).get("reserved").asText());
        assertEquals("2", hourAt(second, h0).get("remaining").asText());
        // 明细查询窗口/申请不存在 -> 404
        mvc.perform(get("/api/windows/999999999/hours")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/curve/versions")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run)).andExpect(status().isNotFound());
        assertNotNull(getOk("/api/allocations/" + a1).get("approvalSnapshot"));
        assertEquals(1, getOk("/api/allocations/" + a1).get("approvalSnapshot").get("curveVersion").asInt());
    }
}
