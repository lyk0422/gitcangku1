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
 * 应急储备 API 测试：储备隔离、版本化调整、常规/应急核销、批量原子扣减、
 * 窗口边界、下调后态回查、幂等与并发裁决。全部使用 H2 内存库（MODE=MySQL）真实事务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyReserveApiTests {

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

    private long createWindow(String channelId, String planned) throws Exception {
        return createWindow(channelId, "2026-12-01T00:00:00Z", "2026-12-01T02:00:00Z", planned);
    }

    private Map<String, Object> reserveBody(String reserveKey, String volume, int expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("reserveKey", reserveKey);
        body.put("reserveVolume", volume);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode setReserve(long windowId, String volume, int expectedVersion) throws Exception {
        return postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), volume, expectedVersion), "operator");
    }

    private JsonNode reserveStatus(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/reserve");
    }

    private String submitAndApprove(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postOk("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private Map<String, Object> regularBody(String commandKey, String writeOffKey, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeOffKey", writeOffKey);
        body.put("amount", amount);
        return body;
    }

    private Map<String, Object> emergencyBody(String commandKey, String writeOffKey, String emergencyId,
                                              String approver, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeOffKey", writeOffKey);
        body.put("emergencyId", emergencyId);
        body.put("approver", approver);
        body.put("amount", amount);
        return body;
    }

    private JsonNode emergencyWriteOff(long windowId, String emergencyId, String amount) throws Exception {
        return postOk("/api/windows/" + windowId + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), emergencyId, "chief", amount), null);
    }

    // ------------------------------------------------------------------
    // 储备划定与版本
    // ------------------------------------------------------------------

    @Test
    void setReserveAndQueryStatus() throws Exception {
        long windowId = createWindow("ch-rs-" + run, "10");
        JsonNode status = setReserve(windowId, "3", 0);
        assertEquals("3", status.get("reserveVolume").asText());
        assertEquals("3", status.get("reserveBalance").asText());
        assertEquals("7", status.get("regularAvailable").asText());
        assertEquals(1, status.get("version").asInt());
        assertEquals(1, status.get("reserveHistory").size());
        // 携带新版本继续调整
        JsonNode adjusted = setReserve(windowId, "4.5", 1);
        assertEquals("4.5", adjusted.get("reserveVolume").asText());
        assertEquals("5.5", adjusted.get("regularAvailable").asText());
        assertEquals(2, adjusted.get("version").asInt());
        assertEquals(2, adjusted.get("reserveHistory").size());
        assertEquals("3", adjusted.get("reserveHistory").get(0).get("newVolume").asText());
        assertEquals("4.5", adjusted.get("reserveHistory").get(1).get("newVolume").asText());
        assertEquals("operator", adjusted.get("reserveHistory").get(1).get("actor").asText());
    }

    @Test
    void setReserveValidationFailures() throws Exception {
        long windowId = createWindow("ch-rv-" + run, "10");
        // 超过 3 位小数 / 负数 / 超过窗口总配额 -> 400
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "1.0001", 0), "operator", 400);
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "-1", 0), "operator", 400);
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "10.001", 0), "operator", 400);
        // 缺 expectedVersion -> 400
        Map<String, Object> body = reserveBody(key("rk"), "1", 0);
        body.remove("expectedVersion");
        postJson("/api/windows/" + windowId + "/reserve", body, "operator", 400);
        // 版本不匹配 -> 409
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "1", 3), "operator", 409);
        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/reserve",
                reserveBody(key("rk"), "1", 0), "operator", 404);
        mvc.perform(get("/api/windows/999999999/reserve")).andExpect(status().isNotFound());
        // 全部失败不占版本
        JsonNode status = setReserve(windowId, "2", 0);
        assertEquals(1, status.get("version").asInt());
    }

    @Test
    void reserveKeyReplaysSnapshotAndFailureDoesNotOccupyKey() throws Exception {
        long windowId = createWindow("ch-ri-" + run, "10");
        String reserveKey = key("rk");
        JsonNode first = postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(reserveKey, "3", 0), "operator");
        JsonNode replay = postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(reserveKey, "3", 0), "operator");
        assertEquals(first, replay);
        assertEquals(1, reserveStatus(windowId).get("reserveHistory").size());
        // 同键改参 -> 409
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(reserveKey, "4", 0), "operator", 409);
        // 失败不占键：版本错误 -> 409 后，同键修正参数可成功
        String failKey = key("rk");
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(failKey, "5", 9), "operator", 409);
        JsonNode recovered = postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(failKey, "5", 1), "operator");
        assertEquals("5", recovered.get("reserveVolume").asText());
        assertEquals(2, recovered.get("version").asInt());
    }

    // ------------------------------------------------------------------
    // 储备隔离：常规核销与常规转让
    // ------------------------------------------------------------------

    @Test
    void regularWriteOffCannotBreachReserve() throws Exception {
        long windowId = createWindow("ch-rw-" + run, "10");
        setReserve(windowId, "4", 0);
        // 常规余额 6：核销 5 成功，再核销 2 将侵占储备 -> 422 且报常规余额与储备量
        postJson("/api/windows/" + windowId + "/write-offs",
                regularBody(key("cc"), key("wo"), "5"), null, 200);
        MvcResult rejected = postJson("/api/windows/" + windowId + "/write-offs",
                regularBody(key("cc"), key("wo"), "2"), null, 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("REGULAR_POOL_EXCEEDED", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("常规余额 1"));
        assertTrue(error.get("message").asText().contains("储备量 4"));
        // 恰好核销到储备下限成功
        postJson("/api/windows/" + windowId + "/write-offs",
                regularBody(key("cc"), key("wo"), "1"), null, 200);
        JsonNode status = reserveStatus(windowId);
        assertEquals("6", status.get("regularUsed").asText());
        assertEquals("0", status.get("regularAvailable").asText());
        assertEquals("4", status.get("reserveBalance").asText());
        assertTrue(status.get("blockedReasons").toString().contains("常规可用量已达储备下限"));
    }

    @Test
    void transferStillWorksWithinReserveIsolation() throws Exception {
        long windowId = createWindow("ch-rt-" + run, "10");
        setReserve(windowId, "2", 0);
        String source = submitAndApprove(windowId, "user-1", "5", "alice");
        String target = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", target);
        body.put("windowId", windowId);
        body.put("userId", "user-2");
        body.put("amount", "3");
        postOk("/api/allocations", body, "bob");
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", source);
        transfer.put("targetAllocationKey", target);
        JsonNode result = postOk("/api/transfers", transfer, "alice");
        assertEquals("3", result.get("amount").asText());
        // 储备不受常规转让影响
        JsonNode status = reserveStatus(windowId);
        assertEquals("2", status.get("reserveBalance").asText());
    }

    // ------------------------------------------------------------------
    // 储备调整回查已批准未核销后态
    // ------------------------------------------------------------------

    @Test
    void reserveAdjustmentChecksApprovedButNotWrittenOffOccupation() throws Exception {
        long windowId = createWindow("ch-ro-" + run, "10");
        submitAndApprove(windowId, "user-1", "8", "alice");
        // 已批准未核销占用 8：储备 3 使常规池只剩 7 -> 422，不部分生效
        MvcResult rejected = postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "3", 0), "operator", 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("RESERVE_CONFLICT", error.get("code").asText());
        JsonNode status = reserveStatus(windowId);
        assertEquals("0", status.get("reserveVolume").asText());
        assertEquals(0, status.get("version").asInt());
        assertEquals(0, status.get("reserveHistory").size());
        // 储备 2：常规池 8 恰好容纳占用 -> 200
        setReserve(windowId, "2", 0);
        // 下调到 1：常规池变大，回查通过 -> 200
        JsonNode lowered = setReserve(windowId, "1", 1);
        assertEquals("1", lowered.get("reserveVolume").asText());
        // 常规核销 1 后占用变为 9，上调储备到 2 使常规池 8 < 9 -> 422
        postJson("/api/windows/" + windowId + "/write-offs",
                regularBody(key("cc"), key("wo"), "1"), null, 200);
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), "2", 2), "operator", 422);
        assertEquals("1", reserveStatus(windowId).get("reserveVolume").asText());
    }

    // ------------------------------------------------------------------
    // 应急核销
    // ------------------------------------------------------------------

    @Test
    void emergencyWriteOffMainFlowAndFailures() throws Exception {
        long windowId = createWindow("ch-ew-" + run, "10");
        setReserve(windowId, "4", 0);
        JsonNode first = emergencyWriteOff(windowId, "emg-1", "1.5");
        assertEquals("emg-1", first.get("emergencyId").asText());
        assertEquals("chief", first.get("approver").asText());
        assertEquals("2.5", reserveStatus(windowId).get("reserveBalance").asText());
        // 缺 emergencyId / 审批人 -> 400
        Map<String, Object> noId = emergencyBody(key("ec"), key("ew"), "emg-2", "chief", "1");
        noId.remove("emergencyId");
        postJson("/api/windows/" + windowId + "/emergency-write-offs", noId, null, 400);
        Map<String, Object> noApprover = emergencyBody(key("ec"), key("ew"), "emg-2", "chief", "1");
        noApprover.remove("approver");
        postJson("/api/windows/" + windowId + "/emergency-write-offs", noApprover, null, 400);
        // 超过储备余额 -> 422
        postJson("/api/windows/" + windowId + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), "emg-2", "chief", "3"), null, 422);
        // 同一 emergencyId 同一窗口只能核销一次 -> 409
        postJson("/api/windows/" + windowId + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), "emg-1", "chief", "1"), null, 409);
        // 不同窗口可复用同一应急编号
        long other = createWindow("ch-ew2-" + run, "10");
        setReserve(other, "4", 0);
        postJson("/api/windows/" + other + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), "emg-1", "chief", "1"), null, 200);
        // 应急核销不影响常规可用量
        JsonNode status = reserveStatus(windowId);
        assertEquals("6", status.get("regularAvailable").asText());
        assertEquals(1, status.get("emergencyWriteOffs").size());
    }

    @Test
    void emergencyBatchIsAtomic() throws Exception {
        long windowId = createWindow("ch-eb-" + run, "10");
        setReserve(windowId, "5", 0);
        // 全部合法 -> 单事务扣减
        Map<String, Object> batch1 = new HashMap<>();
        batch1.put("commandKey", key("ec"));
        batch1.put("batchKey", key("eb"));
        batch1.put("items", List.of(
                Map.of("emergencyId", "emg-a", "approver", "chief", "amount", "2"),
                Map.of("emergencyId", "emg-b", "approver", "deputy", "amount", "1.5")));
        JsonNode done = postOk("/api/windows/" + windowId + "/emergency-write-offs/batch", batch1, null);
        assertEquals("3.5", done.get("totalAmount").asText());
        assertEquals(2, done.get("items").size());
        assertEquals("1.5", reserveStatus(windowId).get("reserveBalance").asText());
        // 合计超过储备余额 -> 422 整单回滚
        Map<String, Object> batch2 = new HashMap<>();
        batch2.put("commandKey", key("ec"));
        batch2.put("batchKey", key("eb"));
        batch2.put("items", List.of(
                Map.of("emergencyId", "emg-c", "approver", "chief", "amount", "1"),
                Map.of("emergencyId", "emg-d", "approver", "chief", "amount", "1")));
        postJson("/api/windows/" + windowId + "/emergency-write-offs/batch", batch2, null, 422);
        // 批次内应急编号重复 -> 409 整单回滚
        Map<String, Object> batch3 = new HashMap<>();
        batch3.put("commandKey", key("ec"));
        batch3.put("batchKey", key("eb"));
        batch3.put("items", List.of(
                Map.of("emergencyId", "emg-e", "approver", "chief", "amount", "0.5"),
                Map.of("emergencyId", "emg-e", "approver", "chief", "amount", "0.5")));
        postJson("/api/windows/" + windowId + "/emergency-write-offs/batch", batch3, null, 409);
        // 与历史应急编号冲突 -> 409 整单回滚
        Map<String, Object> batch4 = new HashMap<>();
        batch4.put("commandKey", key("ec"));
        batch4.put("batchKey", key("eb"));
        batch4.put("items", List.of(
                Map.of("emergencyId", "emg-f", "approver", "chief", "amount", "0.5"),
                Map.of("emergencyId", "emg-a", "approver", "chief", "amount", "0.5")));
        postJson("/api/windows/" + windowId + "/emergency-write-offs/batch", batch4, null, 409);
        // 回滚验证：余额不变，只有首批 2 条记录
        JsonNode status = reserveStatus(windowId);
        assertEquals("1.5", status.get("reserveBalance").asText());
        assertEquals("3.5", status.get("reserveUsed").asText());
        assertEquals(2, status.get("emergencyWriteOffs").size());
    }

    // ------------------------------------------------------------------
    // 窗口边界
    // ------------------------------------------------------------------

    @Test
    void endedOrClosedWindowRejectsEmergencyButKeepsSnapshots() throws Exception {
        // 已过期窗口：不得新建应急核销，储备快照保留可查
        long ended = createWindow("ch-end-" + run,
                "2020-01-01T00:00:00Z", "2020-01-01T01:00:00Z", "10");
        setReserve(ended, "2", 0);
        postJson("/api/windows/" + ended + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), "emg-x", "chief", "1"), null, 409);
        JsonNode endedStatus = reserveStatus(ended);
        assertTrue(endedStatus.get("windowClosed").asBoolean());
        assertTrue(endedStatus.get("blockedReasons").toString().contains("禁止新建应急核销"));
        assertEquals(1, endedStatus.get("reserveHistory").size());
        // 主动关闭窗口：关闭后不得新建应急核销，重复关闭 409，历史保留
        long windowId = createWindow("ch-cls-" + run, "10");
        setReserve(windowId, "3", 0);
        emergencyWriteOff(windowId, "emg-y", "1");
        JsonNode closed = postOk("/api/windows/" + windowId + "/close",
                Map.of("commandKey", key("cc")), null);
        assertTrue(closed.get("closed").asBoolean());
        postJson("/api/windows/" + windowId + "/emergency-write-offs",
                emergencyBody(key("ec"), key("ew"), "emg-z", "chief", "1"), null, 409);
        postJson("/api/windows/" + windowId + "/close", Map.of("commandKey", key("cc")), null, 409);
        JsonNode closedStatus = reserveStatus(windowId);
        assertTrue(closedStatus.get("windowClosed").asBoolean());
        assertEquals(1, closedStatus.get("reserveHistory").size());
        assertEquals(1, closedStatus.get("emergencyWriteOffs").size());
        assertEquals("2", closedStatus.get("reserveBalance").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentEmergencyWriteOffsNeverExceedReserve() throws Exception {
        long windowId = createWindow("ch-ce-" + run, "100");
        setReserve(windowId, "1", 0);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String commandKey = key("ec");
            String writeOffKey = key("ew");
            String emergencyId = key("emg");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.emergencyWriteOff(commandKey, writeOffKey, windowId, emergencyId,
                            "chief", "0.2");
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
        assertEquals(5, ok, "储备 1.000 恰好容纳 5 笔 0.2 应急核销");
        assertEquals(5, quota);
        JsonNode status = reserveStatus(windowId);
        assertEquals("1", status.get("reserveUsed").asText());
        assertEquals("0", status.get("reserveBalance").asText());
        assertEquals(5, status.get("emergencyWriteOffs").size());
    }

    @Test
    void concurrentSetReserveWithSameVersionHasSingleWinner() throws Exception {
        long windowId = createWindow("ch-cv-" + run, "10");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String reserveKey = key("rk");
            String volume = String.valueOf(i + 1);
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.setReserve(reserveKey, windowId, volume, 0, "operator");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一 expectedVersion 的并发储备调整只有一个成功");
        assertEquals(7, conflict);
        JsonNode status = reserveStatus(windowId);
        assertEquals(1, status.get("version").asInt());
        assertEquals(1, status.get("reserveHistory").size());
    }
}
