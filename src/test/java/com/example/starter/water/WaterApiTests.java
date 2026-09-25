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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 灌区配水 API 集成测试：主流程、失败分支、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaterApiTests {

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

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> windowBody(String commandKey, String windowKey, String channelId,
                                           String start, String end, String planned, Object quarter) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        body.put("quarter", quarter);
        return body;
    }

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        return createWindow(channelId, 1, start, end, planned);
    }

    private long createWindow(String channelId, int quarter, String start, String end, String planned)
            throws Exception {
        JsonNode node = postOk("/api/windows",
                windowBody(key("wc"), key("wk"), channelId, start, end, planned, quarter), null);
        return node.get("id").asLong();
    }

    private Map<String, Object> allocationBody(String commandKey, String allocationKey, long windowId,
                                               String userId, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        return body;
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        JsonNode node = postOk("/api/allocations",
                allocationBody(key("ac"), allocationKey, windowId, userId, amount), actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    // ------------------------------------------------------------------
    // 窗口
    // ------------------------------------------------------------------

    @Test
    void createWindowAndQueryCapacity() throws Exception {
        long windowId = createWindow("ch-a", "2026-10-01T00:00:00Z", "2026-10-01T01:00:00Z", "100.500");
        JsonNode cap = capacity(windowId);
        assertEquals("100.5", cap.get("plannedVolume").asText());
        assertEquals("100.5", cap.get("availableTotal").asText());
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("100.5", cap.get("remaining").asText());
        assertTrue(cap.get("activeCurtailmentVolume").isNull());
    }

    @Test
    void overlappingWindowRejectedButAdjacentAllowed() throws Exception {
        String channel = "ch-ov-" + run;
        createWindow(channel, "2026-10-02T10:00:00Z", "2026-10-02T11:00:00Z", "10");
        // 重叠 -> 409
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-02T10:30:00Z", "2026-10-02T11:30:00Z", "10", 1), null, 409);
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-02T09:00:00Z", "2026-10-02T10:00:01Z", "10", 1), null, 409);
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-02T09:00:00Z", "2026-10-02T12:00:00Z", "10", 1), null, 409);
        // 相邻 -> 合法
        createWindow(channel, "2026-10-02T11:00:00Z", "2026-10-02T12:00:00Z", "10");
        createWindow(channel, "2026-10-02T09:00:00Z", "2026-10-02T10:00:00Z", "10");
        // 不同渠道重叠 -> 合法
        createWindow("ch-other-" + run, "2026-10-02T10:30:00Z", "2026-10-02T11:30:00Z", "10");
    }

    @Test
    void invalidWindowParamsReturn400() throws Exception {
        String channel = "ch-bad-" + run;
        // 超过 3 位小数
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "1.0001", 1), null, 400);
        // 零与负数
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "0", 1), null, 400);
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "-5", 1), null, 400);
        // 非数字
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "abc", 1), null, 400);
        // 起止倒置
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T01:00:00Z", "2026-10-03T00:00:00Z", "1", 1), null, 400);
        // 非法时间
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "not-a-time", "2026-10-03T01:00:00Z", "1", 1), null, 400);
        // 季度缺失或越界
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "1", null), null, 400);
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "1", 0), null, 400);
        postJson("/api/windows", windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "1", 5), null, 400);
        // 缺字段
        Map<String, Object> body = windowBody(key("wc"), key("wk"), channel,
                "2026-10-03T00:00:00Z", "2026-10-03T01:00:00Z", "1", 1);
        body.remove("commandKey");
        postJson("/api/windows", body, null, 400);
    }

    // ------------------------------------------------------------------
    // 申请主流程与配额
    // ------------------------------------------------------------------

    @Test
    void submitApproveAndCapacityAccounting() throws Exception {
        long windowId = createWindow("ch-flow-" + run,
                "2026-10-04T00:00:00Z", "2026-10-04T02:00:00Z", "1.000");
        String a1 = submitAllocation(windowId, "user-1", "0.4", "alice");
        approve(a1, 200);
        JsonNode cap = capacity(windowId);
        assertEquals("0.4", cap.get("approvedTotal").asText());
        assertEquals("0.6", cap.get("remaining").asText());
    }

    @Test
    void approveBeyondQuotaReturns422() throws Exception {
        long windowId = createWindow("ch-q-" + run,
                "2026-10-05T00:00:00Z", "2026-10-05T02:00:00Z", "1.000");
        String a1 = submitAllocation(windowId, "user-1", "0.6", "alice");
        String a2 = submitAllocation(windowId, "user-2", "0.5", "bob");
        approve(a1, 200);
        approve(a2, 422);
        // 申请仍为 REQUESTED，可稍后批准
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REQUESTED", history.get("allocations").get(1).get("status").asText());
    }

    @Test
    void approveUsesExactBigDecimalArithmetic() throws Exception {
        long windowId = createWindow("ch-bd-" + run,
                "2026-10-06T00:00:00Z", "2026-10-06T02:00:00Z", "1.000");
        String a1 = submitAllocation(windowId, "u1", "0.333", "alice");
        String a2 = submitAllocation(windowId, "u2", "0.333", "alice");
        String a3 = submitAllocation(windowId, "u3", "0.333", "alice");
        approve(a1, 200);
        approve(a2, 200);
        approve(a3, 200);
        // 0.999 已批准，再批准 0.002 将精确超出 1.000
        String a4 = submitAllocation(windowId, "u4", "0.002", "alice");
        approve(a4, 422);
        String a5 = submitAllocation(windowId, "u5", "0.001", "alice");
        approve(a5, 200);
        JsonNode cap = capacity(windowId);
        assertEquals("1", cap.get("approvedTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
    }

    @Test
    void cancelReleasesWaterAndIsRestrictedToRequester() throws Exception {
        long windowId = createWindow("ch-c-" + run,
                "2026-10-07T00:00:00Z", "2026-10-07T02:00:00Z", "1.000");
        String a1 = submitAllocation(windowId, "user-1", "0.8", "alice");
        approve(a1, 200);
        // 非申请人取消 -> 409
        postJson("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "bob", 409);
        // 申请人取消 -> 200，水量立即释放
        JsonNode cancelled = postOk("/api/allocations/" + a1 + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", capacity(windowId).get("approvedTotal").asText());
        // 重复取消 -> 409；已取消不能批准 -> 409
        postJson("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice", 409);
        approve(a1, 409);
        // 释放后可重新占用
        String a2 = submitAllocation(windowId, "user-2", "0.9", "bob");
        approve(a2, 200);
    }

    @Test
    void notFoundAndStateConflictsAreDistinguishable() throws Exception {
        long windowId = createWindow("ch-nf-" + run,
                "2026-10-08T00:00:00Z", "2026-10-08T02:00:00Z", "5");
        // 窗口不存在 -> 404
        postJson("/api/allocations", allocationBody(key("ac"), key("ak"), 999999999L, "u", "1"), "alice", 404);
        mvc.perform(get("/api/windows/999999999/capacity")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/history")).andExpect(status().isNotFound());
        postJson("/api/windows/999999999/curtailment",
                Map.of("commandKey", key("cu"), "volume", "1"), null, 404);
        postJson("/api/windows/999999999/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null, 404);
        // 申请不存在 -> 404
        approve("no-such-" + run, 404);
        postJson("/api/allocations/no-such-" + run + "/cancel",
                Map.of("commandKey", key("cc")), "alice", 404);
        // 重复批准 -> 409
        String a1 = submitAllocation(windowId, "user-1", "1", "alice");
        approve(a1, 200);
        approve(a1, 409);
    }

    // ------------------------------------------------------------------
    // 限供
    // ------------------------------------------------------------------

    @Test
    void curtailmentLifecycle() throws Exception {
        long windowId = createWindow("ch-cur-" + run,
                "2026-10-09T00:00:00Z", "2026-10-09T02:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        // 拟定限供低于已批准总量 -> 409
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "5"), null, 409);
        // 限供 8 -> 可用总量变为 8
        JsonNode curtailment = postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "8"), null);
        assertEquals("ACTIVE", curtailment.get("status").asText());
        JsonNode cap = capacity(windowId);
        assertEquals("8", cap.get("availableTotal").asText());
        assertEquals("8", cap.get("activeCurtailmentVolume").asText());
        // 6 + 3 > 8 -> 422；6 + 2 == 8 -> 200
        String a2 = submitAllocation(windowId, "user-2", "3", "bob");
        approve(a2, 422);
        String a3 = submitAllocation(windowId, "user-3", "2", "carol");
        approve(a3, 200);
        // 重复限供 -> 409
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "9"), null, 409);
        // 取消限供 -> 恢复计划水量
        JsonNode cancelled = postOk("/api/windows/" + windowId + "/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null);
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertNotNull(cancelled.get("cancelledUtc"));
        JsonNode capAfter = capacity(windowId);
        assertEquals("10", capAfter.get("availableTotal").asText());
        assertTrue(capAfter.get("activeCurtailmentVolume").isNull());
        // 再次取消 -> 409
        postJson("/api/windows/" + windowId + "/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null, 409);
    }

    @Test
    void invalidCurtailmentParamsReturn400() throws Exception {
        long windowId = createWindow("ch-curbad-" + run,
                "2026-10-10T00:00:00Z", "2026-10-10T02:00:00Z", "10");
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "0"), null, 400);
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "-1"), null, 400);
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "1.0001"), null, 400);
        // 超过计划水量 -> 400
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "10.001"), null, 400);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeySameParamsReplaysFirstResult() throws Exception {
        long windowId = createWindow("ch-idem-" + run,
                "2026-10-11T00:00:00Z", "2026-10-11T02:00:00Z", "5");
        String allocationKey = key("ak");
        String commandKey = key("ac");
        Map<String, Object> body = allocationBody(commandKey, allocationKey, windowId, "user-1", "1.5");
        JsonNode first = postOk("/api/allocations", body, "alice");
        JsonNode replay = postOk("/api/allocations", body, "alice");
        assertEquals(first, replay);
        // 只创建了一条申请
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("allocations").size());
        // 批准命令重放
        String approveKey = key("ap");
        JsonNode approved = postOk("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", approveKey), null);
        JsonNode approvedReplay = postOk("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", approveKey), null);
        assertEquals(approved, approvedReplay);
        assertEquals("1.5", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void sameCommandKeyDifferentParamsReturns409() throws Exception {
        long windowId = createWindow("ch-idem2-" + run,
                "2026-10-12T00:00:00Z", "2026-10-12T02:00:00Z", "5");
        String commandKey = key("ac");
        postOk("/api/allocations", allocationBody(commandKey, key("ak"), windowId, "user-1", "1"), "alice");
        // 同键改水量 -> 409
        postJson("/api/allocations", allocationBody(commandKey, key("ak"), windowId, "user-1", "2"), "alice", 409);
        // 同键改操作人 -> 409
        postJson("/api/allocations", allocationBody(commandKey, key("ak"), windowId, "user-1", "1"), "bob", 409);
        // 窗口创建同键改参 -> 409
        String windowCommand = key("wc");
        String windowKey = key("wk");
        postOk("/api/windows", windowBody(windowCommand, windowKey, "ch-x-" + run,
                "2026-11-01T00:00:00Z", "2026-11-01T01:00:00Z", "1", 1), null);
        postJson("/api/windows", windowBody(windowCommand, windowKey, "ch-y-" + run,
                "2026-11-01T00:00:00Z", "2026-11-01T01:00:00Z", "1", 1), null, 409);
    }

    // ------------------------------------------------------------------
    // 历史明细
    // ------------------------------------------------------------------

    @Test
    void historyContainsWindowAllocationsAndCurtailments() throws Exception {
        long windowId = createWindow("ch-his-" + run,
                "2026-10-13T00:00:00Z", "2026-10-13T02:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "3", "alice");
        String a2 = submitAllocation(windowId, "user-2", "2", "bob");
        approve(a1, 200);
        postOk("/api/allocations/" + a2 + "/cancel", Map.of("commandKey", key("cc")), "bob");
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "8"), null);
        postOk("/api/windows/" + windowId + "/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null);

        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(windowId, history.get("window").get("id").asLong());
        assertEquals("10", history.get("window").get("availableTotal").asText());
        assertEquals(2, history.get("allocations").size());
        assertEquals("APPROVED", history.get("allocations").get(0).get("status").asText());
        assertEquals("CANCELLED", history.get("allocations").get(1).get("status").asText());
        assertEquals(1, history.get("curtailments").size());
        assertEquals("CANCELLED", history.get("curtailments").get(0).get("status").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesNeverExceedAvailableTotal() throws Exception {
        long windowId = createWindow("ch-conc-" + run,
                "2026-10-14T00:00:00Z", "2026-10-14T02:00:00Z", "1.000");
        List<String> allocationKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allocationKeys.add(submitAllocation(windowId, "user-" + i, "0.2", "alice"));
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
        int quota = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                quota++;
            }
        }
        pool.shutdown();
        assertEquals(5, ok, "恰好 5 个 0.2 可以放入 1.000");
        assertEquals(5, quota);
        JsonNode cap = capacity(windowId);
        assertEquals("1", cap.get("approvedTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
    }

    @Test
    void concurrentApproveAndCurtailmentRespectFinalAvailableTotal() throws Exception {
        long windowId = createWindow("ch-race-" + run,
                "2026-10-15T00:00:00Z", "2026-10-15T02:00:00Z", "10");
        String approved = submitAllocation(windowId, "user-1", "6", "alice");
        approve(approved, 200);
        String pending = submitAllocation(windowId, "user-2", "4", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String approveCommand = key("ap");
        String curtailCommand = key("cu");
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, pending);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> curtailFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.createCurtailment(curtailCommand, windowId, "8");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        int curtailStatus = curtailFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按事务提交顺序：恰好一个成功（先批准 6+4=10 则限供 8 冲突；先限供 8 则批准 10 超额）
        assertTrue((approveStatus == 200) != (curtailStatus == 200),
                "approve=" + approveStatus + " curtail=" + curtailStatus);
        if (approveStatus == 200) {
            assertEquals(409, curtailStatus);
            assertEquals("10", capacity(windowId).get("approvedTotal").asText());
        } else {
            assertEquals(422, approveStatus);
            assertEquals("8", capacity(windowId).get("availableTotal").asText());
        }
        // 不变式：已批准总量永不超过最终可用总量
        JsonNode cap = capacity(windowId);
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }
}
