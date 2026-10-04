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
 * 泵站流量曲线批准集成测试：公开流程、异常分支、幂等与并发。
 * 曲线时刻全部由请求体给定（UTC 整分钟），不依赖系统时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PumpStationApiTests {

    /** 合成曲线窗口：2026-01-01 00:00 至 00:02 UTC，节点 0、10、0 相距一分钟，积分 10。 */
    private static final String START = "2026-01-01T00:00:00Z";
    private static final String MID = "2026-01-01T00:01:00Z";
    private static final String END = "2026-01-01T00:02:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

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

    private JsonNode postError(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        return objectMapper.readTree(
                postJson(url, body, actor, expectedStatus).getResponse().getContentAsString());
    }

    private Map<String, Object> configBody(String requestId, String maxFlow, String maxRamp,
                                           long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("maxFlow", maxFlow);
        body.put("maxRampPerMinute", maxRamp);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode createStation(String stationKey, String maxFlow, String maxRamp) throws Exception {
        JsonNode node = postOk("/api/pump-stations/" + stationKey + "/config",
                configBody(key("cfg"), maxFlow, maxRamp, 0L), "tester");
        assertEquals(1L, node.get("version").asLong());
        return node;
    }

    private long createWindow(String planned, String stationKey) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", key("ch"));
        body.put("startUtc", START);
        body.put("endUtc", END);
        body.put("plannedVolume", planned);
        if (stationKey != null) {
            body.put("pumpStationKey", stationKey);
        }
        JsonNode node = postOk("/api/windows", body, null);
        return node.get("id").asLong();
    }

    private List<Map<String, Object>> canonicalCurve(String peakFlow) {
        List<Map<String, Object>> curve = new ArrayList<>();
        curve.add(Map.of("timeUtc", START, "flow", "0"));
        curve.add(Map.of("timeUtc", MID, "flow", peakFlow));
        curve.add(Map.of("timeUtc", END, "flow", "0"));
        return curve;
    }

    private Map<String, Object> curveAllocationBody(String requestId, String allocationKey, long windowId,
                                                    String userId, String amount,
                                                    List<Map<String, Object>> curve) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("curve", curve);
        return body;
    }

    private String submitCurve(long windowId, String userId, String amount,
                               List<Map<String, Object>> curve, String actor) throws Exception {
        String allocationKey = key("ak");
        JsonNode node = postOk("/api/curve-allocations",
                curveAllocationBody(key("req"), allocationKey, windowId, userId, amount, curve), actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }

    private JsonNode approve(String allocationKey, int expectedStatus) throws Exception {
        return postError("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode getAllocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode allocation : history.get("allocations")) {
            if (allocation.get("allocationKey").asText().equals(allocationKey)) {
                return allocation;
            }
        }
        throw new AssertionError("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 泵站配置：创建、版本、幂等
    // ------------------------------------------------------------------

    @Test
    void configCreateReplayAndVersionConflict() throws Exception {
        String stationKey = key("st");
        Map<String, Object> body = configBody(key("cfg"), "10", "10", 0L);
        JsonNode created = postOk("/api/pump-stations/" + stationKey + "/config", body, "alice");
        assertEquals("10", created.get("maxFlow").asText());
        assertEquals(1L, created.get("version").asLong());

        // 同键同参重放：返回首次完整响应，版本不重复递增
        JsonNode replayed = postOk("/api/pump-stations/" + stationKey + "/config", body, "alice");
        assertEquals(created.toString(), replayed.toString());
        assertEquals(1L, getOk("/api/pump-stations/" + stationKey).get("version").asLong());

        // 同键异参：409
        Map<String, Object> changed = configBody(body.get("requestId").toString(), "20", "10", 0L);
        postJson("/api/pump-stations/" + stationKey + "/config", changed, "alice", 409);

        // 期望版本不一致：409
        postJson("/api/pump-stations/" + stationKey + "/config",
                configBody(key("cfg"), "20", "10", 5L), "alice", 409);

        // 版本匹配的调整：版本递增
        JsonNode updated = postOk("/api/pump-stations/" + stationKey + "/config",
                configBody(key("cfg"), "20", "15", 1L), "alice");
        assertEquals(2L, updated.get("version").asLong());
        assertEquals("15", updated.get("maxRampPerMinute").asText());

        postJson("/api/pump-stations/" + key("st404") + "/config",
                configBody(key("cfg"), "1", "1", 0L), null, 200);
        mvc.perform(get("/api/pump-stations/" + key("missing"))).andExpect(status().isNotFound());
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndActorIsIdentity() throws Exception {
        String stationKey = key("st");
        String requestId = key("cfg");
        // 业务失败（版本冲突）不占键
        postJson("/api/pump-stations/" + stationKey + "/config",
                configBody(requestId, "10", "10", 5L), "alice", 409);
        JsonNode created = postOk("/api/pump-stations/" + stationKey + "/config",
                configBody(requestId, "10", "10", 0L), "alice");
        assertEquals(1L, created.get("version").asLong());

        // 操作者是身份的一部分：同键同参但换人重放返回 409
        String stationKey2 = key("st");
        String requestId2 = key("cfg");
        postOk("/api/pump-stations/" + stationKey2 + "/config",
                configBody(requestId2, "10", "10", 0L), "alice");
        postJson("/api/pump-stations/" + stationKey2 + "/config",
                configBody(requestId2, "10", "10", 0L), "bob", 409);
        // 同人同键同参：完整重放
        postOk("/api/pump-stations/" + stationKey2 + "/config",
                configBody(requestId2, "10", "10", 0L), "alice");
    }
    // ------------------------------------------------------------------
    // 曲线批准主流程与异常分支
    // ------------------------------------------------------------------

    @Test
    void curveApproveHappyPathAndSecondOccupantRejected() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);

        // 合成 0、10、0 三个相距一分钟节点，积分 10，最大流量 10 可批准
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        JsonNode approved = approve(a, 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals(1L, approved.get("stationVersion").asLong());
        assertEquals(3, approved.get("curve").size());
        assertEquals("10", approved.get("curve").get(1).get("flow").asText());

        // 另一同形申请同时占用：叠加流量 20 超过最大流量 10，拒绝且仍待审、配额不扣
        String b = submitCurve(windowId, "user-2", "10", canonicalCurve("10"), "bob");
        JsonNode rejected = approve(b, 422);
        assertEquals("PUMP_FLOW_EXCEEDED", rejected.get("code").asText());
        assertEquals("REQUESTED", getAllocation(windowId, b).get("status").asText());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", capacity.get("approvedTotal").asText());
        assertEquals("90", capacity.get("remaining").asText());
    }

    @Test
    void integralMismatchStaysRequestedAndQuotaUntouched() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        // 曲线积分 9，申请水量 10：批准时 422，申请仍待审且配额不扣
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("9"), "alice");
        JsonNode rejected = approve(a, 422);
        assertEquals("CURVE_INTEGRAL_MISMATCH", rejected.get("code").asText());
        assertEquals("REQUESTED", getAllocation(windowId, a).get("status").asText());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("0", capacity.get("approvedTotal").asText());
        assertEquals("100", capacity.get("remaining").asText());
    }

    @Test
    void cancelRemovesOnlyOwnContribution() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        approve(a, 200);
        String b = submitCurve(windowId, "user-2", "10", canonicalCurve("10"), "bob");
        approve(b, 422);
        // 取消 A 只去除其贡献，B 随后可批准
        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode approved = approve(b, 200);
        assertEquals("APPROVED", approved.get("status").asText());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", capacity.get("approvedTotal").asText());
    }

    @Test
    void configDecreaseViolatingApprovedCurveRejected409() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        approve(a, 200);

        // 参数调小使已有曲线非法：409，配置与版本不变
        JsonNode conflict = postError("/api/pump-stations/" + stationKey + "/config",
                configBody(key("cfg"), "5", "10", 1L), "tester", 409);
        assertEquals("CONFIG_VIOLATES_APPROVED", conflict.get("code").asText());
        assertEquals(1L, getOk("/api/pump-stations/" + stationKey).get("version").asLong());

        // 合法调大：版本递增；旧批准结果冻结不改（stationVersion 仍为 1）
        postOk("/api/pump-stations/" + stationKey + "/config",
                configBody(key("cfg"), "20", "20", 1L), "tester");
        assertEquals(1L, getAllocation(windowId, a).get("stationVersion").asLong());

        // 新申请按新参数判定：叠加 20 流量与 20 斜率均可通过，冻结版本为 2
        String b = submitCurve(windowId, "user-2", "10", canonicalCurve("10"), "bob");
        JsonNode approved = approve(b, 200);
        assertEquals(2L, approved.get("stationVersion").asLong());
    }

    @Test
    void plainAllocationNotMixedIntoPumpCapacity() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("200", stationKey);
        // 无曲线旧申请按原流程只约束总水量
        String plainKey = key("ak");
        Map<String, Object> plain = new HashMap<>();
        plain.put("commandKey", key("ac"));
        plain.put("allocationKey", plainKey);
        plain.put("windowId", windowId);
        plain.put("userId", "user-0");
        plain.put("amount", "100");
        postOk("/api/allocations", plain, "carol");
        approve(plainKey, 200);

        // 曲线申请不占用旧申请的流量口径：峰值 10 恰好可批准
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        approve(a, 200);
        // 第二条同形曲线叠加越限：422（总水量 120 远低于 200，拒绝原因是瞬时流量）
        String b = submitCurve(windowId, "user-2", "10", canonicalCurve("10"), "bob");
        JsonNode rejected = approve(b, 422);
        assertEquals("PUMP_FLOW_EXCEEDED", rejected.get("code").asText());
    }

    @Test
    void curveTransferRejected() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        String source = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        approve(source, 200);
        String targetKey = key("ak");
        Map<String, Object> target = new HashMap<>();
        target.put("commandKey", key("ac"));
        target.put("allocationKey", targetKey);
        target.put("windowId", windowId);
        target.put("userId", "user-2");
        target.put("amount", "5");
        postOk("/api/allocations", target, "bob");
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", source);
        transfer.put("targetAllocationKey", targetKey);
        JsonNode rejected = postError("/api/transfers", transfer, "alice", 409);
        assertEquals("CURVE_TRANSFER_UNSUPPORTED", rejected.get("code").asText());
    }
    // ------------------------------------------------------------------
    // 曲线结构校验（提交时 400）
    // ------------------------------------------------------------------

    @Test
    void curveStructureValidation() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        long plainWindowId = createWindow("100", null);

        // 节点数不足 2 个
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", List.of(Map.of("timeUtc", START, "flow", "0"))), "alice", 400);
        // 节点数超过 20 个
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            tooMany.add(Map.of("timeUtc", "2026-01-01T00:" + String.format("%02d", i) + ":00Z",
                    "flow", "0"));
        }
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", tooMany), "alice", 400);
        // 时刻非严格递增
        List<Map<String, Object>> notIncreasing = new ArrayList<>();
        notIncreasing.add(Map.of("timeUtc", START, "flow", "0"));
        notIncreasing.add(Map.of("timeUtc", START, "flow", "0"));
        notIncreasing.add(Map.of("timeUtc", END, "flow", "0"));
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", notIncreasing), "alice", 400);
        // 非 UTC 整分钟
        List<Map<String, Object>> notWholeMinute = new ArrayList<>();
        notWholeMinute.add(Map.of("timeUtc", START, "flow", "0"));
        notWholeMinute.add(Map.of("timeUtc", "2026-01-01T00:01:30Z", "flow", "10"));
        notWholeMinute.add(Map.of("timeUtc", END, "flow", "0"));
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", notWholeMinute), "alice", 400);
        // 流量为负
        List<Map<String, Object>> negative = new ArrayList<>();
        negative.add(Map.of("timeUtc", START, "flow", "0"));
        negative.add(Map.of("timeUtc", MID, "flow", "-1"));
        negative.add(Map.of("timeUtc", END, "flow", "0"));
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", negative), "alice", 400);
        // 流量超过 3 位小数
        List<Map<String, Object>> tooPrecise = new ArrayList<>();
        tooPrecise.add(Map.of("timeUtc", START, "flow", "0"));
        tooPrecise.add(Map.of("timeUtc", MID, "flow", "1.0001"));
        tooPrecise.add(Map.of("timeUtc", END, "flow", "0"));
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", tooPrecise), "alice", 400);
        // 首末节点不等于窗口边界
        List<Map<String, Object>> badBoundary = new ArrayList<>();
        badBoundary.add(Map.of("timeUtc", START, "flow", "0"));
        badBoundary.add(Map.of("timeUtc", MID, "flow", "10"));
        badBoundary.add(Map.of("timeUtc", "2026-01-01T00:03:00Z", "flow", "0"));
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), windowId,
                "user-1", "10", badBoundary), "alice", 400);
        // 窗口未关联泵站
        postJson("/api/curve-allocations", curveAllocationBody(key("req"), key("ak"), plainWindowId,
                "user-1", "10", canonicalCurve("10")), "alice", 400);
    }

    @Test
    void curveSubmitIdempotency() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        String requestId = key("req");
        String allocationKey = key("ak");
        Map<String, Object> body = curveAllocationBody(requestId, allocationKey, windowId,
                "user-1", "10", canonicalCurve("10"));
        JsonNode first = postOk("/api/curve-allocations", body, "alice");
        // 同键同参重放：返回首次完整响应，不产生第二条申请
        JsonNode replayed = postOk("/api/curve-allocations", body, "alice");
        assertEquals(first.toString(), replayed.toString());
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("allocations").size());
        // 同键异参：409
        Map<String, Object> changed = curveAllocationBody(requestId, allocationKey, windowId,
                "user-1", "20", canonicalCurve("10"));
        postJson("/api/curve-allocations", changed, "alice", 409);
    }

    // ------------------------------------------------------------------
    // 并发：同泵站曲线批准串行裁决；同 requestId 并发不泄露唯一键异常
    // ------------------------------------------------------------------

    @Test
    void concurrentCurveApprovalsSerializedByStationLock() throws Exception {
        String stationKey = key("st");
        createStation(stationKey, "10", "10");
        long windowId = createWindow("100", stationKey);
        String a = submitCurve(windowId, "user-1", "10", canonicalCurve("10"), "alice");
        String b = submitCurve(windowId, "user-2", "10", canonicalCurve("10"), "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : List.of(a, b)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post("/api/allocations/" + allocationKey + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("commandKey", key("ap")))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        // 恰好一个批准成功，另一个 422；最终数据一致
        assertEquals(List.of(1, 1),
                List.of((int) statuses.stream().filter(s -> s == 200).count(),
                        (int) statuses.stream().filter(s -> s == 422).count()));
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", capacity.get("approvedTotal").asText());
        String statusA = getAllocation(windowId, a).get("status").asText();
        String statusB = getAllocation(windowId, b).get("status").asText();
        assertTrue("APPROVED".equals(statusA) ^ "APPROVED".equals(statusB));
    }

    @Test
    void concurrentSameRequestIdReplayedWithoutKeyLeak() throws Exception {
        String stationKey = key("st");
        String requestId = key("cfg");
        Map<String, Object> body = configBody(requestId, "10", "10", 0L);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                MvcResult result = mvc.perform(post("/api/pump-stations/" + stationKey + "/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Actor-Id", "alice")
                                .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk()).andReturn();
                return objectMapper.readTree(result.getResponse().getContentAsString());
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        String first = null;
        for (Future<JsonNode> future : futures) {
            JsonNode node = future.get(30, TimeUnit.SECONDS);
            assertEquals(1L, node.get("version").asLong());
            if (first == null) {
                first = node.toString();
            } else {
                assertEquals(first, node.toString());
            }
        }
        pool.shutdownNow();
        // 泵站只创建一次，版本为 1
        assertEquals(1L, getOk("/api/pump-stations/" + stationKey).get("version").asLong());
        assertNotNull(first);
    }
}