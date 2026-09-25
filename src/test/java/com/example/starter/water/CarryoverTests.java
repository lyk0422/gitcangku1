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
 * 季度结转测试：上限计算、迁移原子性、双重占用拦截、并发与幂等边界、流水与余量查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CarryoverTests {

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

    private long createWindow(String channelId, int quarter, String start, String end, String planned)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        body.put("quarter", quarter);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitApproved(long windowId, String userId, String amount) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, "alice");
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private Map<String, Object> carryoverBody(String commandKey, String carryoverKey, String sourceAllocationKey,
                                              long sourceWindowId, long targetWindowId, String userId,
                                              String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("carryoverKey", carryoverKey);
        body.put("sourceAllocationKey", sourceAllocationKey);
        body.put("sourceWindowId", sourceWindowId);
        body.put("targetWindowId", targetWindowId);
        body.put("userId", userId);
        body.put("amount", amount);
        return body;
    }

    private JsonNode carryoverOk(String carryoverKey, String sourceAllocationKey, long sourceWindowId,
                                 long targetWindowId, String userId, String amount) throws Exception {
        return postOk("/api/carryovers",
                carryoverBody(key("cc"), carryoverKey, sourceAllocationKey, sourceWindowId, targetWindowId,
                        userId, amount), null);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode allocationOf(long windowId, String allocationKey) throws Exception {
        JsonNode allocations = getOk("/api/windows/" + windowId + "/history").get("allocations");
        for (JsonNode node : allocations) {
            if (node.get("allocationKey").asText().equals(allocationKey)) {
                return node;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 主流程与上限计算
    // ------------------------------------------------------------------

    @Test
    void carryoverMovesRemainderToTargetWindowAtomically() throws Exception {
        long sourceWindow = createWindow("ch-co-a-" + run, 2,
                "2026-04-01T00:00:00Z", "2026-04-01T02:00:00Z", "10");
        long targetWindow = createWindow("ch-co-b-" + run, 2,
                "2026-04-02T00:00:00Z", "2026-04-02T02:00:00Z", "5");
        String source = submitApproved(sourceWindow, "user-1-" + run, "6");

        String carryoverKey = key("co");
        JsonNode response = carryoverOk(carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "2.5");
        assertEquals(carryoverKey, response.get("carryoverKey").asText());
        assertEquals("user-1-" + run, response.get("userId").asText());
        assertEquals(sourceWindow, response.get("sourceWindowId").asLong());
        assertEquals(targetWindow, response.get("targetWindowId").asLong());
        assertEquals("2.5", response.get("amount").asText());
        assertNotNull(response.get("createdUtc"));

        // 源申请：原水量与状态不变，已结转 2.5，余量 3.5
        JsonNode sourceAllocation = allocationOf(sourceWindow, source);
        assertEquals("APPROVED", sourceAllocation.get("status").asText());
        assertEquals("6", sourceAllocation.get("amount").asText());
        assertEquals("2.5", sourceAllocation.get("carriedOut").asText());
        // 源窗口已批准总量历史口径不变
        assertEquals("6", capacity(sourceWindow).get("approvedTotal").asText());
        // 目标窗口容量统计同事务更新：新建 APPROVED 申请 2.5
        JsonNode targetCap = capacity(targetWindow);
        assertEquals("2.5", targetCap.get("approvedTotal").asText());
        assertEquals("2.5", targetCap.get("remaining").asText());
        JsonNode targetAllocation = allocationOf(targetWindow, "carryover:" + carryoverKey);
        assertNotNull(targetAllocation);
        assertEquals("APPROVED", targetAllocation.get("status").asText());
        assertEquals("user-1-" + run, targetAllocation.get("userId").asText());

        // 结转流水可按用水户查询
        JsonNode carryovers = getOk("/api/carryovers?userId=user-1-" + run);
        assertEquals(1, carryovers.size());
        assertEquals(carryoverKey, carryovers.get(0).get("carryoverKey").asText());
        assertEquals(source, carryovers.get(0).get("sourceAllocationKey").asText());
        assertEquals("carryover:" + carryoverKey, carryovers.get(0).get("targetAllocationKey").asText());

        // 按用水户的跨窗口余量查询
        JsonNode remainders = getOk("/api/users/user-1-" + run + "/carryover-remainders?quarter=2");
        assertEquals(2, remainders.get("items").size());
        JsonNode sourceItem = remainders.get("items").get(0).get("windowId").asLong() == sourceWindow
                ? remainders.get("items").get(0) : remainders.get("items").get(1);
        assertEquals("3.5", sourceItem.get("carryableRemainder").asText());
    }

    @Test
    void carryoverBeyondSourceRemainderReturns422AndZeroRemainderBlocksFurther() throws Exception {
        long sourceWindow = createWindow("ch-lim-a-" + run, 1,
                "2026-01-01T00:00:00Z", "2026-01-01T02:00:00Z", "10");
        long targetWindow = createWindow("ch-lim-b-" + run, 1,
                "2026-01-02T00:00:00Z", "2026-01-02T02:00:00Z", "20");
        String source = submitApproved(sourceWindow, "user-1-" + run, "6");

        // 超过源余量 -> 422
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), source, sourceWindow, targetWindow, "user-1-" + run, "6.001"),
                null, 422);
        // 失败回滚：源余量与目标容量不变
        assertEquals("0", allocationOf(sourceWindow, source).get("carriedOut").asText());
        assertEquals("0", capacity(targetWindow).get("approvedTotal").asText());
        // 结转自余量上限 -> 200；余量归零
        carryoverOk(key("co"), source, sourceWindow, targetWindow, "user-1-" + run, "6");
        assertEquals("6", allocationOf(sourceWindow, source).get("carriedOut").asText());
        // 余量归零后不得再结转 -> 422
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), source, sourceWindow, targetWindow, "user-1-" + run, "0.001"),
                null, 422);
    }

    @Test
    void carryoverBeyondTargetCapacityReturns422() throws Exception {
        long sourceWindow = createWindow("ch-cap-a-" + run, 3,
                "2026-07-01T00:00:00Z", "2026-07-01T02:00:00Z", "10");
        long targetWindow = createWindow("ch-cap-b-" + run, 3,
                "2026-07-02T00:00:00Z", "2026-07-02T02:00:00Z", "3");
        String source = submitApproved(sourceWindow, "user-1-" + run, "8");
        submitApproved(targetWindow, "user-2", "2");

        // 目标窗口剩余容量 1，结转 2 -> 422
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), source, sourceWindow, targetWindow, "user-1-" + run, "2"),
                null, 422);
        // 回滚：源余量未扣减
        assertEquals("0", allocationOf(sourceWindow, source).get("carriedOut").asText());
        // 结转 1 恰好放满 -> 200
        carryoverOk(key("co"), source, sourceWindow, targetWindow, "user-1-" + run, "1");
        assertEquals("3", capacity(targetWindow).get("approvedTotal").asText());
        assertEquals("0", capacity(targetWindow).get("remaining").asText());
    }

    // ------------------------------------------------------------------
    // 校验失败分支
    // ------------------------------------------------------------------

    @Test
    void carryoverValidationFailures() throws Exception {
        long q1Window = createWindow("ch-v-a-" + run, 1,
                "2026-01-05T00:00:00Z", "2026-01-05T02:00:00Z", "10");
        long q1Target = createWindow("ch-v-b-" + run, 1,
                "2026-01-06T00:00:00Z", "2026-01-06T02:00:00Z", "10");
        long q2Window = createWindow("ch-v-c-" + run, 2,
                "2026-04-05T00:00:00Z", "2026-04-05T02:00:00Z", "10");
        String approved = submitApproved(q1Window, "user-1-" + run, "5");

        // 跨季度 -> 409
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, q2Window, "user-1-" + run, "1"), null, 409);
        // 用水户不一致 -> 409
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, q1Target, "user-2", "1"), null, 409);
        // 源窗口与申请不符 -> 409
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Target, q1Window, "user-1-" + run, "1"), null, 409);
        // 源申请不存在 -> 404；目标窗口不存在 -> 404
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), "no-such-" + run, q1Window, q1Target, "user-1-" + run, "1"),
                null, 404);
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, 999999999L, "user-1-" + run, "1"), null, 404);
        // 源目标同窗口 -> 400；非法水量 -> 400
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, q1Window, "user-1-" + run, "1"), null, 400);
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, q1Target, "user-1-" + run, "1.0001"),
                null, 400);
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), approved, q1Window, q1Target, "user-1-" + run, "0"), null, 400);

        // 源申请非 APPROVED -> 409（REQUESTED 与 CANCELLED）
        String requested = key("ak");
        Map<String, Object> submit = new HashMap<>();
        submit.put("commandKey", key("ac"));
        submit.put("allocationKey", requested);
        submit.put("windowId", q1Window);
        submit.put("userId", "user-1-" + run);
        submit.put("amount", "1");
        postOk("/api/allocations", submit, "alice");
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), requested, q1Window, q1Target, "user-1-" + run, "1"), null, 409);
        postOk("/api/allocations/" + requested + "/cancel", Map.of("commandKey", key("cx")), "alice");
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("co"), requested, q1Window, q1Target, "user-1-" + run, "1"), null, 409);

        // 全部失败均回滚：源余量与目标容量不变
        assertEquals("0", allocationOf(q1Window, approved).get("carriedOut").asText());
        assertEquals("0", capacity(q1Target).get("approvedTotal").asText());
        assertEquals(0, getOk("/api/carryovers?userId=user-1-" + run).size());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void carryoverCommandKeyIdempotency() throws Exception {
        long sourceWindow = createWindow("ch-id-a-" + run, 4,
                "2026-10-01T00:00:00Z", "2026-10-01T02:00:00Z", "10");
        long targetWindow = createWindow("ch-id-b-" + run, 4,
                "2026-10-02T00:00:00Z", "2026-10-02T02:00:00Z", "10");
        String source = submitApproved(sourceWindow, "user-1-" + run, "5");

        // 失败不占键：先以超量失败（422），同键改合法水量后成功
        String commandKey = key("cc");
        String carryoverKey = key("co");
        postJson("/api/carryovers",
                carryoverBody(commandKey, carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "9"),
                null, 422);
        JsonNode ok = postOk("/api/carryovers",
                carryoverBody(commandKey, carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "2"),
                null);
        // 同键同参重放首次结果
        JsonNode replay = postOk("/api/carryovers",
                carryoverBody(commandKey, carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "2"),
                null);
        assertEquals(ok, replay);
        // 同键异参 -> 409
        postJson("/api/carryovers",
                carryoverBody(commandKey, carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "3"),
                null, 409);
        // 只迁移了一次
        assertEquals("2", allocationOf(sourceWindow, source).get("carriedOut").asText());
        assertEquals(1, getOk("/api/carryovers?userId=user-1-" + run).size());
        // carryoverKey 全局唯一：换 commandKey 复用同一 carryoverKey -> 409
        postJson("/api/carryovers",
                carryoverBody(key("cc"), carryoverKey, source, sourceWindow, targetWindow, "user-1-" + run, "1"),
                null, 409);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentCarryoverOnSameSourceAllowsOnlyOne() throws Exception {
        long sourceWindow = createWindow("ch-dbl-a-" + run, 2,
                "2026-04-10T00:00:00Z", "2026-04-10T02:00:00Z", "10");
        long targetA = createWindow("ch-dbl-b-" + run, 2,
                "2026-04-11T00:00:00Z", "2026-04-11T02:00:00Z", "10");
        long targetB = createWindow("ch-dbl-c-" + run, 2,
                "2026-04-12T00:00:00Z", "2026-04-12T02:00:00Z", "10");
        String source = submitApproved(sourceWindow, "user-1-" + run, "10");

        // 两个并发结转各 6（余量仅 10），目标不同：最多一个成功，另一个 409
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (long target : new long[]{targetA, targetB}) {
            String commandKey = key("cc");
            String carryoverKey = key("co");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.carryover(commandKey, carryoverKey, source, sourceWindow, target,
                            "user-1-" + run, "6");
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

        assertTrue((first == 200) != (second == 200),
                "恰好一个成功: first=" + first + " second=" + second);
        assertEquals(409, first == 200 ? second : first);
        // 源申请只被扣减一次，无双重占用
        assertEquals("6", allocationOf(sourceWindow, source).get("carriedOut").asText());
        assertEquals(1, getOk("/api/carryovers?userId=user-1-" + run).size());
    }

    @Test
    void concurrentCarryoversIntoSameTargetRespectCapacity() throws Exception {
        long sourceA = createWindow("ch-tg-a-" + run, 3,
                "2026-07-10T00:00:00Z", "2026-07-10T02:00:00Z", "10");
        long sourceB = createWindow("ch-tg-b-" + run, 3,
                "2026-07-11T00:00:00Z", "2026-07-11T02:00:00Z", "10");
        long target = createWindow("ch-tg-c-" + run, 3,
                "2026-07-12T00:00:00Z", "2026-07-12T02:00:00Z", "10");
        String allocA = submitApproved(sourceA, "user-1-" + run, "8");
        String allocB = submitApproved(sourceB, "user-1-" + run, "8");

        // 两笔 6 同时进入容量 10 的目标窗口：按事务提交顺序恰一个成功
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : new String[]{allocA, allocB}) {
            long sourceWindow = allocationKey.equals(allocA) ? sourceA : sourceB;
            String commandKey = key("cc");
            String carryoverKey = key("co");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.carryover(commandKey, carryoverKey, allocationKey, sourceWindow, target,
                            "user-1-" + run, "6");
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

        assertTrue((first == 200) != (second == 200),
                "恰好一个成功: first=" + first + " second=" + second);
        assertEquals(422, first == 200 ? second : first);
        // 不变式：目标窗口已批准总量不超过可用总量
        JsonNode cap = capacity(target);
        assertEquals("6", cap.get("approvedTotal").asText());
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }

    @Test
    void remainderQueryScopesByUserAndQuarter() throws Exception {
        long w1 = createWindow("ch-rq-a-" + run, 2, "2026-04-20T00:00:00Z", "2026-04-20T02:00:00Z", "10");
        long w2 = createWindow("ch-rq-b-" + run, 2, "2026-04-21T00:00:00Z", "2026-04-21T02:00:00Z", "10");
        long w3 = createWindow("ch-rq-c-" + run, 3, "2026-07-20T00:00:00Z", "2026-07-20T02:00:00Z", "10");
        String a1 = submitApproved(w1, "user-rq-" + run, "4");
        submitApproved(w2, "user-rq-" + run, "3");
        submitApproved(w3, "user-rq-" + run, "7");
        submitApproved(w1, "user-other-" + run, "1");
        carryoverOk(key("co"), a1, w1, w2, "user-rq-" + run, "1.5");

        JsonNode q2 = getOk("/api/users/user-rq-" + run + "/carryover-remainders?quarter=2");
        assertEquals(3, q2.get("items").size());
        Map<String, JsonNode> byWindow = new HashMap<>();
        for (JsonNode item : q2.get("items")) {
            byWindow.put(item.get("windowId").asText(), item);
        }
        assertEquals("2.5", byWindow.get(String.valueOf(w1)).get("carryableRemainder").asText());
        assertEquals("1.5", byWindow.get(String.valueOf(w1)).get("carriedOut").asText());
        // w2 含原申请 3 与结转进来的 1.5 两条
        // 其他季度不包含
        JsonNode q3 = getOk("/api/users/user-rq-" + run + "/carryover-remainders?quarter=3");
        assertEquals(1, q3.get("items").size());
        assertEquals("7", q3.get("items").get(0).get("carryableRemainder").asText());
        // 其他用水户互不可见
        JsonNode other = getOk("/api/users/user-other-" + run + "/carryover-remainders?quarter=2");
        assertEquals(1, other.get("items").size());
        // 非法季度 -> 400
        mvc.perform(get("/api/users/user-rq-" + run + "/carryover-remainders?quarter=5"))
                .andExpect(status().isBadRequest());
    }
}
