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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 盐负荷的转让门禁、幂等与并发测试：已灌部分不得转让、整次转让重核两侧盐预算、
 * 同键重放/改参 409/失败不占键、并发批准与实灌在真实 H2 行锁下裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SaltLedgerTransferAndConcurrencyTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(8);
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
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString());
    }

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-sc-" + run + "-" + seq);
        body.put("startUtc", "2026-10-21T00:00:00Z");
        body.put("endUtc", "2026-10-21T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private long createField(String farmerId, String saltCapGrams) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("fc"));
        body.put("fieldKey", key("fk"));
        body.put("farmerId", farmerId);
        body.put("saltCapGrams", saltCapGrams);
        return postOk("/api/fields", body, "operator").get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, Long fieldId,
                          String concentration, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (fieldId != null) {
            body.put("fieldId", fieldId);
        }
        if (concentration != null) {
            body.put("concentration", concentration);
        }
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private void irrigate(String allocationKey, String amount, String actor, int expectedStatus)
            throws Exception {
        postJson("/api/allocations/" + allocationKey + "/irrigate",
                Map.of("commandKey", key("ir"), "amount", amount), actor, expectedStatus);
    }

    private JsonNode transfer(String source, String target, String actor, int expectedStatus)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        MvcResult result = postJson("/api/transfers", body, actor, expectedStatus);
        if (expectedStatus != 200) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        for (JsonNode node : getOk("/api/windows/" + windowId + "/history").get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 转让门禁
    // ------------------------------------------------------------------

    @Test
    void irrigatedPartCannotBeTransferred() throws Exception {
        long fieldId = createField("user-src", "100");
        long windowId = createWindow("100");
        String source = submit(windowId, "user-src", "10", fieldId, "2", "alice");
        approve(source, 200);
        irrigate(source, "6", "alice", 200);
        // 可转让额度 = 10 - 6 = 4；目标 5 -> 422，目标 4 -> 200
        String tooBig = submit(windowId, "user-dst", "5", null, null, "bob");
        transfer(source, tooBig, "alice", 422);
        String ok = submit(windowId, "user-dst2", "4", null, null, "carol");
        transfer(source, ok, "alice", 200);
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("6", sourceNode.get("heldAmount").asText());
        assertEquals("6", sourceNode.get("irrigatedAmount").asText());
        // 剩余持有恰为已灌量，再转任意正水量 -> 422
        String more = submit(windowId, "user-dst3", "0.001", null, null, "dave");
        transfer(source, more, "alice", 422);
        // 已灌盐留在源田块账本，不随转让移动；源持有 6 恰为已灌量，预留归零
        JsonNode salt = getOk("/api/fields/" + fieldId + "/salt");
        assertEquals("12", salt.get("irrigatedSalt").asText());
        assertEquals("0", salt.get("reservedSalt").asText());
        assertEquals(1, salt.get("entries").size());
    }

    @Test
    void transferRechecksTargetFieldSaltBudget() throws Exception {
        long windowId = createWindow("100");
        long fieldId = createField("user-dst", "5");
        String source = submit(windowId, "user-src", "10", null, null, "alice");
        approve(source, 200);
        // 目标绑定田块上限 5 克：3 立方米浓度 2 需预留 6 克 -> 422
        String tooBig = submit(windowId, "user-dst", "3", fieldId, "2", "bob");
        transfer(source, tooBig, "alice", 422);
        assertEquals("REQUESTED", allocation(windowId, tooBig).get("status").asText());
        assertEquals("10", allocation(windowId, source).get("heldAmount").asText());
        // 2 立方米浓度 2 需预留 4 克 -> 200
        String ok = submit(windowId, "user-dst", "2", fieldId, "2", "bob");
        transfer(source, ok, "alice", 200);
        JsonNode salt = getOk("/api/fields/" + fieldId + "/salt");
        assertEquals("4", salt.get("reservedSalt").asText());
        assertEquals("1", salt.get("remainingSalt").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void irrigateIdempotencyReplayConflictAndFailureNotOccupying() throws Exception {
        long fieldId = createField("user-1", "100");
        long windowId = createWindow("100");
        String allocation = submit(windowId, "user-1", "5", fieldId, "2", "alice");
        approve(allocation, 200);
        // 失败（422）不占键
        String failKey = key("ir");
        Map<String, Object> tooMuch = Map.of("commandKey", failKey, "amount", "6");
        postJson("/api/allocations/" + allocation + "/irrigate", tooMuch, "alice", 422);
        postJson("/api/allocations/" + allocation + "/irrigate", tooMuch, "alice", 422);
        // 同键同参重放返回首次完整结果，且只记一次账
        String commandKey = key("ir");
        Map<String, Object> body = Map.of("commandKey", commandKey, "amount", "2", "expectedVersion", 1);
        JsonNode first = postOk("/api/allocations/" + allocation + "/irrigate", body, "alice");
        JsonNode replay = postOk("/api/allocations/" + allocation + "/irrigate", body, "alice");
        assertEquals(first, replay);
        assertEquals("4", first.get("saltGrams").asText());
        assertEquals(1, getOk("/api/fields/" + fieldId + "/salt").get("entries").size());
        // 同键改水量 -> 409；同键改期望版本 -> 409；同键改操作人 -> 409
        postJson("/api/allocations/" + allocation + "/irrigate",
                Map.of("commandKey", commandKey, "amount", "3", "expectedVersion", 1), "alice", 409);
        postJson("/api/allocations/" + allocation + "/irrigate",
                Map.of("commandKey", commandKey, "amount", "2", "expectedVersion", 2), "alice", 409);
        postJson("/api/allocations/" + allocation + "/irrigate",
                Map.of("commandKey", commandKey, "amount", "2", "expectedVersion", 1), "bob", 409);
        // 十进制按值：2 与 2.00 视为同参重放
        JsonNode valued = postOk("/api/allocations/" + allocation + "/irrigate",
                Map.of("commandKey", commandKey, "amount", "2.00", "expectedVersion", 1), "alice");
        assertEquals(first, valued);
        assertEquals(1, getOk("/api/fields/" + fieldId + "/salt").get("entries").size());
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesNeverExceedFieldSaltCap() throws Exception {
        long fieldId = createField("user-1", "20");
        long windowId = createWindow("100");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            keys.add(submit(windowId, "user-1", "3", fieldId, "2", "alice"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : keys) {
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
        // 每个预留 6 克，上限 20：恰好 3 个成功（18 克），其余 422
        assertEquals(3, ok);
        assertEquals(2, quota);
        JsonNode salt = getOk("/api/fields/" + fieldId + "/salt");
        assertEquals("18", salt.get("reservedSalt").asText());
        assertEquals("2", salt.get("remainingSalt").asText());
    }

    @Test
    void concurrentIrrigatesNeverExceedUnirrigatedAmount() throws Exception {
        long fieldId = createField("user-1", "100");
        long windowId = createWindow("100");
        String allocation = submit(windowId, "user-1", "5", fieldId, "2", "alice");
        approve(allocation, 200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("ir");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.irrigateAllocation(commandKey, allocation, "4", null, "alice");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int s1 = futures.get(0).get(30, TimeUnit.SECONDS);
        int s2 = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // 未灌量 5：两笔 4 恰好一笔成功，另一笔 422
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode node = allocation(windowId, allocation);
        assertEquals("4", node.get("irrigatedAmount").asText());
        JsonNode salt = getOk("/api/fields/" + fieldId + "/salt");
        assertEquals("8", salt.get("irrigatedSalt").asText());
        assertEquals("2", salt.get("reservedSalt").asText());
        assertEquals(1, salt.get("entries").size());
        assertEquals("8", salt.get("entries").get(0).get("cumulativeSaltGrams").asText());
    }

    @Test
    void concurrentIrrigateAndCancelSettleByCommitOrder() throws Exception {
        long fieldId = createField("user-1", "100");
        long windowId = createWindow("100");
        String allocation = submit(windowId, "user-1", "5", fieldId, "2", "alice");
        approve(allocation, 200);
        String irrigateCommand = key("ir");
        String cancelCommand = key("cc");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> irrigateFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.irrigateAllocation(irrigateCommand, allocation, "3", null, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(cancelCommand, allocation, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int irrigateStatus = irrigateFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // 取消必成功；实灌先提交则成功（取消保留已灌 3），取消先提交则实灌 409
        assertEquals(200, cancelStatus);
        JsonNode node = allocation(windowId, allocation);
        assertEquals("CANCELLED", node.get("status").asText());
        JsonNode salt = getOk("/api/fields/" + fieldId + "/salt");
        if (irrigateStatus == 200) {
            assertEquals("3", node.get("irrigatedAmount").asText());
            assertEquals("3", node.get("heldAmount").asText());
            assertEquals("6", salt.get("irrigatedSalt").asText());
            assertEquals("0", salt.get("reservedSalt").asText());
            assertEquals(1, salt.get("entries").size());
        } else {
            assertEquals(409, irrigateStatus);
            assertEquals("0", node.get("irrigatedAmount").asText());
            assertEquals("0", salt.get("irrigatedSalt").asText());
            assertEquals(0, salt.get("entries").size());
        }
        // 不变式：已灌水量永不超过持有额度
        assertTrue(Double.parseDouble(node.get("irrigatedAmount").asText())
                <= Double.parseDouble(node.get("heldAmount").asText()));
    }
}
