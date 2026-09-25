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
 * 季度结转集成测试：上限计算、迁移原子性、双重占用拦截、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CarryoverApiTests {

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

    private long createWindow(String channelId, String start, String end, String planned, Integer quarter)
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

    private String submitAndApprove(long windowId, String userId, String amount) throws Exception {
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

    private Map<String, Object> carryoverBody(String commandKey, String carryoverKey, long sourceWindowId,
                                              long targetWindowId, String userId, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("carryoverKey", carryoverKey);
        body.put("sourceWindowId", sourceWindowId);
        body.put("targetWindowId", targetWindowId);
        body.put("userId", userId);
        body.put("amount", amount);
        return body;
    }

    private JsonNode carryoverOk(long sourceWindowId, long targetWindowId, String userId, String amount)
            throws Exception {
        return postOk("/api/carryovers",
                carryoverBody(key("cc"), key("ck"), sourceWindowId, targetWindowId, userId, amount), null);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode balance(String userId) throws Exception {
        return getOk("/api/users/" + userId + "/carryover-balance");
    }

    private JsonNode balanceEntry(String userId, String allocationKey) throws Exception {
        for (JsonNode entry : balance(userId).get("allocations")) {
            if (entry.get("allocationKey").asText().equals(allocationKey)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 主流程与上限计算
    // ------------------------------------------------------------------

    @Test
    void carryoverMovesRemainderToSameQuarterWindow() throws Exception {
        long source = createWindow("ch-co-s-" + run, "2027-01-05T00:00:00Z", "2027-01-05T02:00:00Z", "10", 1);
        long target = createWindow("ch-co-t-" + run, "2027-01-06T00:00:00Z", "2027-01-06T02:00:00Z", "5", 1);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "4.500");

        JsonNode carryover = carryoverOk(source, target, userId, "1.750");
        assertEquals(source, carryover.get("sourceWindowId").asLong());
        assertEquals(target, carryover.get("targetWindowId").asLong());
        assertEquals(userId, carryover.get("userId").asText());
        assertEquals("1.75", carryover.get("amount").asText());
        assertNotNull(carryover.get("createdUtc"));

        // 目标窗口出现新的 APPROVED 申请，容量统计同步更新
        JsonNode targetCap = capacity(target);
        assertEquals("1.75", targetCap.get("approvedTotal").asText());
        assertEquals("3.25", targetCap.get("remaining").asText());
        String targetAllocationKey = carryover.get("targetAllocationKey").asText();
        JsonNode targetEntry = balanceEntry(userId, targetAllocationKey);
        assertNotNull(targetEntry);
        assertEquals("APPROVED", targetEntry.get("status").asText());
        assertEquals(target, targetEntry.get("windowId").asLong());

        // 源申请原水量不变，已转出 1.75，剩余可结转余量 2.75
        JsonNode sourceEntry = balanceEntry(userId, sourceAllocation);
        assertNotNull(sourceEntry);
        assertEquals("4.5", sourceEntry.get("amount").asText());
        assertEquals("0", sourceEntry.get("consumedVolume").asText());
        assertEquals("1.75", sourceEntry.get("carriedOutVolume").asText());
        assertEquals("2.75", sourceEntry.get("remaining").asText());
        // 源窗口已批准总量的历史口径不变
        assertEquals("4.5", capacity(source).get("approvedTotal").asText());

        // 结转流水可按用水户查询
        JsonNode ledger = getOk("/api/carryovers?userId=" + userId);
        assertEquals(1, ledger.size());
        assertEquals(carryover.get("carryoverKey").asText(), ledger.get(0).get("carryoverKey").asText());
        assertEquals("1.75", ledger.get(0).get("amount").asText());
    }

    @Test
    void remainderExhaustedThenNoFurtherCarryover() throws Exception {
        long source = createWindow("ch-ex-s-" + run, "2027-02-01T00:00:00Z", "2027-02-01T02:00:00Z", "10", 2);
        long target = createWindow("ch-ex-t-" + run, "2027-02-02T00:00:00Z", "2027-02-02T02:00:00Z", "10", 2);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "2.000");

        // 结转全部余量 -> 余量归零
        carryoverOk(source, target, userId, "2.000");
        JsonNode entry = balanceEntry(userId, sourceAllocation);
        assertEquals("2", entry.get("carriedOutVolume").asText());
        assertEquals("0", entry.get("remaining").asText());
        // 余量归零后不得再结转 -> 422
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("ck"), source, target, userId, "0.001"), null, 422);
    }

    @Test
    void carryoverExceedingSourceRemainderReturns422() throws Exception {
        long source = createWindow("ch-ov-s-" + run, "2027-02-05T00:00:00Z", "2027-02-05T02:00:00Z", "10", 2);
        long target = createWindow("ch-ov-t-" + run, "2027-02-06T00:00:00Z", "2027-02-06T02:00:00Z", "10", 2);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "1.000");
        carryoverOk(source, target, userId, "0.400");
        // 剩余 0.6，结转 0.601 -> 422（BigDecimal 精确比较）
        postJson("/api/carryovers",
                carryoverBody(key("cc"), key("ck"), source, target, userId, "0.601"), null, 422);
        // 恰好 0.600 -> 200
        carryoverOk(source, target, userId, "0.600");
        assertEquals("0", balanceEntry(userId, sourceAllocation).get("remaining").asText());
    }

    @Test
    void carryoverExceedingTargetCapacityReturns422AndRollsBack() throws Exception {
        long source = createWindow("ch-tc-s-" + run, "2027-03-01T00:00:00Z", "2027-03-01T02:00:00Z", "10", 3);
        long target = createWindow("ch-tc-t-" + run, "2027-03-02T00:00:00Z", "2027-03-02T02:00:00Z", "1.000", 3);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "5");
        submitAndApprove(target, "other-" + run, "0.800");

        // 目标剩余容量 0.2，结转 0.5 -> 422
        String commandKey = key("cc");
        postJson("/api/carryovers",
                carryoverBody(commandKey, key("ck"), source, target, userId, "0.500"), null, 422);

        // 原子性：源余量不变、目标无新申请、无结转流水
        JsonNode sourceEntry = balanceEntry(userId, sourceAllocation);
        assertEquals("0", sourceEntry.get("carriedOutVolume").asText());
        assertEquals("5", sourceEntry.get("remaining").asText());
        JsonNode targetCap = capacity(target);
        assertEquals("0.8", targetCap.get("approvedTotal").asText());
        assertEquals(0, getOk("/api/carryovers?userId=" + userId).size());
        // 失败不占键：同一 commandKey 改用合法参数可成功
        JsonNode ok = postOk("/api/carryovers",
                carryoverBody(commandKey, key("ck"), source, target, userId, "0.200"), null);
        assertEquals("0.2", ok.get("amount").asText());
        assertEquals("1", capacity(target).get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 校验分支
    // ------------------------------------------------------------------

    @Test
    void quarterMismatchOrMissingReturns422() throws Exception {
        long q1 = createWindow("ch-q1-" + run, "2027-04-01T00:00:00Z", "2027-04-01T02:00:00Z", "10", 1);
        long q2 = createWindow("ch-q2-" + run, "2027-04-02T00:00:00Z", "2027-04-02T02:00:00Z", "10", 2);
        long noQuarter = createWindow("ch-qn-" + run, "2027-04-03T00:00:00Z", "2027-04-03T02:00:00Z", "10", null);
        String userId = "user-" + run;
        submitAndApprove(q1, userId, "1");
        submitAndApprove(noQuarter, userId, "1");
        // 季度不同 -> 422
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), q1, q2, userId, "0.5"), null, 422);
        // 目标未标记季度 -> 422
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), q1, noQuarter, userId, "0.5"), null, 422);
        // 源未标记季度 -> 422
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), noQuarter, q1, userId, "0.5"), null, 422);
    }

    @Test
    void invalidQuarterOnWindowCreateReturns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-qbad-" + run);
        body.put("startUtc", "2027-04-05T00:00:00Z");
        body.put("endUtc", "2027-04-05T01:00:00Z");
        body.put("plannedVolume", "1");
        body.put("quarter", 0);
        postJson("/api/windows", body, null, 400);
        body.put("quarter", 5);
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        postJson("/api/windows", body, null, 400);
        body.put("quarter", 4);
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        postJson("/api/windows", body, null, 200);
    }

    @Test
    void sourceAllocationStateValidation() throws Exception {
        long source = createWindow("ch-st-s-" + run, "2027-05-01T00:00:00Z", "2027-05-01T02:00:00Z", "10", 4);
        long target = createWindow("ch-st-t-" + run, "2027-05-02T00:00:00Z", "2027-05-02T02:00:00Z", "10", 4);
        String userId = "user-" + run;
        // 用水户在源窗口没有申请 -> 404
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), source, target, userId, "1"), null, 404);
        // 窗口不存在 -> 404
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), 999999999L, target, userId, "1"), null, 404);
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), source, 999999999L, userId, "1"), null, 404);
        // 申请仍为 REQUESTED -> 409
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", source);
        body.put("userId", userId);
        body.put("amount", "1");
        postOk("/api/allocations", body, "alice");
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), source, target, userId, "1"), null, 409);
        // 已取消 -> 409
        postOk("/api/allocations/" + allocationKey + "/cancel", Map.of("commandKey", key("cc")), "alice");
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), source, target, userId, "1"), null, 409);
        // 源与目标相同 -> 400
        postJson("/api/carryovers", carryoverBody(key("cc"), key("ck"), source, source, userId, "1"), null, 400);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void carryoverIdempotencyReplayAndConflict() throws Exception {
        long source = createWindow("ch-ci-s-" + run, "2027-06-01T00:00:00Z", "2027-06-01T02:00:00Z", "10", 1);
        long target = createWindow("ch-ci-t-" + run, "2027-06-02T00:00:00Z", "2027-06-02T02:00:00Z", "10", 1);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "3");

        String commandKey = key("cc");
        String carryoverKey = key("ck");
        Map<String, Object> body = carryoverBody(commandKey, carryoverKey, source, target, userId, "1.000");
        JsonNode first = postOk("/api/carryovers", body, null);
        // 同键同参重放首次结果，余量只扣一次
        JsonNode replay = postOk("/api/carryovers", body, null);
        assertEquals(first, replay);
        assertEquals("1", balanceEntry(userId, sourceAllocation).get("carriedOutVolume").asText());
        assertEquals(1, getOk("/api/carryovers?userId=" + userId).size());
        // 同键异参 -> 409
        postJson("/api/carryovers",
                carryoverBody(commandKey, carryoverKey, source, target, userId, "2.000"), null, 409);
        // carryoverKey 全局唯一：换 commandKey 复用同一 carryoverKey -> 409
        postJson("/api/carryovers",
                carryoverBody(key("cc"), carryoverKey, source, target, userId, "0.500"), null, 409);
        assertEquals("1", balanceEntry(userId, sourceAllocation).get("carriedOutVolume").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentCarryoverOnSameSourceAllowsOnlyOne() throws Exception {
        long source = createWindow("ch-dc-s-" + run, "2027-07-01T00:00:00Z", "2027-07-01T02:00:00Z", "10", 2);
        long target1 = createWindow("ch-dc-t1-" + run, "2027-07-02T00:00:00Z", "2027-07-02T02:00:00Z", "10", 2);
        long target2 = createWindow("ch-dc-t2-" + run, "2027-07-03T00:00:00Z", "2027-07-03T02:00:00Z", "10", 2);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "2.000");

        // 两个并发结转各自要求转走全部余量到不同目标窗口：最多一个成功，另一个 409
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (long target : new long[]{target1, target2}) {
            String commandKey = key("cc");
            String carryoverKey = key("ck");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.carryover(commandKey, carryoverKey, source, target, userId, "2.000");
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
        assertTrue(first == 409 || second == 409, "失败者必须返回 409");
        // 源余量只被扣减一次，不存在双重占用
        JsonNode entry = balanceEntry(userId, sourceAllocation);
        assertEquals("2", entry.get("carriedOutVolume").asText());
        assertEquals("0", entry.get("remaining").asText());
        assertEquals(1, getOk("/api/carryovers?userId=" + userId).size());
    }

    @Test
    void concurrentCarryoverAndApproveRespectTargetCapacity() throws Exception {
        long source = createWindow("ch-cr-s-" + run, "2027-08-01T00:00:00Z", "2027-08-01T02:00:00Z", "10", 3);
        long target = createWindow("ch-cr-t-" + run, "2027-08-02T00:00:00Z", "2027-08-02T02:00:00Z", "10", 3);
        String userId = "user-" + run;
        String sourceAllocation = submitAndApprove(source, userId, "4");
        submitAndApprove(target, "other-" + run, "6");
        // 目标窗口待批准申请 4：批准与结转 4 并发，恰好一个成功（6+4=10）
        String pendingKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", pendingKey);
        body.put("windowId", target);
        body.put("userId", "third-" + run);
        body.put("amount", "4");
        postOk("/api/allocations", body, "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String approveCommand = key("ap");
        String carryCommand = key("cc");
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, pendingKey);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> carryFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.carryover(carryCommand, key("ck"), source, target, userId, "4");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        int carryStatus = carryFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按事务提交顺序裁决：恰好一个成功，另一个 422
        assertTrue((approveStatus == 200) != (carryStatus == 200),
                "approve=" + approveStatus + " carry=" + carryStatus);
        assertTrue(approveStatus == 422 || carryStatus == 422);
        // 不变式：目标窗口已批准总量永不超过可用总量
        JsonNode cap = capacity(target);
        assertEquals("10", cap.get("approvedTotal").asText());
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
        if (carryStatus == 200) {
            assertEquals(1, getOk("/api/carryovers?userId=" + userId).size());
        } else {
            assertEquals(0, getOk("/api/carryovers?userId=" + userId).size());
            assertEquals("4", balanceEntry(userId, sourceAllocation).get("remaining").asText());
        }
    }
}
