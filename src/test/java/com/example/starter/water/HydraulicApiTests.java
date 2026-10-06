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
 * 简化水力模型 API 集成测试：合成主流程、422/409 失败分支、幂等与并发边界。
 * 全部走 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HydraulicApiTests {

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

    private Map<String, Object> segmentBody(String requestId, String segmentKey, long resistance) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("segmentKey", segmentKey);
        body.put("resistance", resistance);
        return body;
    }

    private String createSegment(long resistance) throws Exception {
        String segmentKey = key("seg");
        JsonNode node = postOk("/api/segments", segmentBody(key("sr"), segmentKey, resistance), "ops");
        assertEquals(1, node.get("version").asInt());
        assertEquals(resistance, node.get("resistance").asLong());
        return segmentKey;
    }

    private Map<String, Object> hydraulicBody(String requestId, String allocationKey, long windowId,
                                              String userId, List<String> path, long flowRate,
                                              String start, String end, long head) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("path", path);
        body.put("flowRate", flowRate);
        body.put("supplyStartUtc", start);
        body.put("supplyEndUtc", end);
        body.put("availableHead", head);
        return body;
    }

    private String submitHydraulic(long windowId, String userId, List<String> path, long flowRate,
                                   String start, String end, long head, String actor) throws Exception {
        String allocationKey = key("ak");
        JsonNode node = postOk("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), allocationKey, windowId, userId, path, flowRate, start, end, head),
                actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    // ------------------------------------------------------------------
    // 渠段与版本
    // ------------------------------------------------------------------

    @Test
    void segmentLifecycleAndVersionGuard() throws Exception {
        String segmentKey = createSegment(1);
        // 重复创建 -> 409
        postJson("/api/segments", segmentBody(key("sr"), segmentKey, 1), "ops", 409);
        // 非法阻力 -> 400；缺操作人 -> 400
        postJson("/api/segments", segmentBody(key("sr"), key("seg"), 0), "ops", 400);
        postJson("/api/segments", segmentBody(key("sr"), key("seg"), -2), "ops", 400);
        postJson("/api/segments", segmentBody(key("sr"), key("seg"), 1), null, 400);
        // 版本不一致 -> 409
        postJson("/api/segments/" + segmentKey + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 2, "expectedVersion", 5), "ops", 409);
        // 版本匹配 -> 200，版本 +1
        JsonNode updated = postOk("/api/segments/" + segmentKey + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 2, "expectedVersion", 1), "ops");
        assertEquals(2, updated.get("resistance").asLong());
        assertEquals(2, updated.get("version").asInt());
        // 不存在的渠段 -> 404
        postJson("/api/segments/no-such-" + run + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 2, "expectedVersion", 1), "ops", 404);
    }

    // ------------------------------------------------------------------
    // 提交校验
    // ------------------------------------------------------------------

    @Test
    void hydraulicSubmitValidation() throws Exception {
        long windowId = createWindow("ch-hv-" + run,
                "2027-01-01T00:00:00Z", "2027-01-01T02:00:00Z", "1000");
        String seg = createSegment(1);
        String start = "2027-01-01T00:00:00Z";
        String end = "2027-01-01T00:10:00Z";
        // 流量 × 分钟数 = 原申请水量
        String ok = submitHydraulic(windowId, "u1", List.of(seg), 6, start, end, 100, "alice");
        JsonNode detail = getOk("/api/allocations/" + ok + "/hydraulic");
        assertEquals(6, detail.get("flowRate").asLong());
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("60", history.get("allocations").get(0).get("amount").asText());
        // 路径段数 > 5 -> 400
        List<String> six = List.of(seg, seg, seg, seg, seg, seg);
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", six, 1, start, end, 1), "alice", 400);
        // 路径重复 -> 400
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(seg, seg), 1, start, end, 1),
                "alice", 400);
        // 空路径 / 零流量 / 零水头 -> 400
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(), 1, start, end, 1), "alice", 400);
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(seg), 0, start, end, 1),
                "alice", 400);
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(seg), 1, start, end, 0),
                "alice", 400);
        // 非整分钟 -> 400；起止倒置 -> 400
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(seg), 1,
                        "2027-01-01T00:00:30Z", end, 1), "alice", 400);
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of(seg), 1, end, start, 1),
                "alice", 400);
        // 未知渠段 -> 404；未知窗口 -> 404
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), windowId, "u", List.of("no-such-" + run), 1, start, end, 1),
                "alice", 404);
        postJson("/api/allocations/hydraulic",
                hydraulicBody(key("hr"), key("ak"), 999999999L, "u", List.of(seg), 1, start, end, 1),
                "alice", 404);
        // 旧类型申请不参与模型 -> 404
        Map<String, Object> oldBody = new HashMap<>();
        oldBody.put("commandKey", key("ac"));
        oldBody.put("allocationKey", key("ak"));
        oldBody.put("windowId", windowId);
        oldBody.put("userId", "u9");
        oldBody.put("amount", "1");
        String oldKey = oldBody.get("allocationKey").toString();
        postOk("/api/allocations", oldBody, "alice");
        mvc.perform(get("/api/allocations/" + oldKey + "/hydraulic")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 合成验收用例与水头判定
    // ------------------------------------------------------------------

    @Test
    void syntheticCaseSecondApplicationRejected() throws Exception {
        long windowId = createWindow("ch-syn-" + run,
                "2027-02-01T00:00:00Z", "2027-02-01T02:00:00Z", "1000");
        String seg = createSegment(1);
        String start = "2027-02-01T00:00:00Z";
        String end = "2027-02-01T00:10:00Z";
        String a1 = submitHydraulic(windowId, "u1", List.of(seg), 6, start, end, 100, "alice");
        String a2 = submitHydraulic(windowId, "u2", List.of(seg), 6, start, end, 100, "bob");
        // 单独损失 1 × 6^2 = 36 ≤ 100，可批
        approve(a1, 200);
        JsonNode detail = getOk("/api/allocations/" + a1 + "/hydraulic");
        assertEquals(1, detail.get("slices").size());
        assertEquals(6, detail.get("slices").get(0).get("totalFlow").asLong());
        assertEquals(36, detail.get("slices").get(0).get("loss").asLong());
        assertEquals(1, detail.get("slices").get(0).get("resistanceVersion").asInt());
        // 合计损失 1 × 12^2 = 144 > 100，第二单整次拒绝
        approve(a2, 422);
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REQUESTED", history.get("allocations").get(1).get("status").asText());
        // 所有额度不变：已批准总量仍是第一单的 60
        assertEquals("60", getOk("/api/windows/" + windowId + "/capacity")
                .get("approvedTotal").asText());
    }

    @Test
    void approvalThatBreaksExistingOrderAlsoRejected() throws Exception {
        long windowId = createWindow("ch-old-" + run,
                "2027-02-02T00:00:00Z", "2027-02-02T02:00:00Z", "1000");
        String seg = createSegment(1);
        String start = "2027-02-02T00:00:00Z";
        String end = "2027-02-02T00:10:00Z";
        String a1 = submitHydraulic(windowId, "u1", List.of(seg), 6, start, end, 100, "alice");
        approve(a1, 200);
        // 本单自身合法（144 ≤ 200），但使旧单水头不足（144 > 100）-> 422
        String a2 = submitHydraulic(windowId, "u2", List.of(seg), 6, start, end, 200, "bob");
        approve(a2, 422);
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REQUESTED", history.get("allocations").get(1).get("status").asText());
        // 错开时段不叠加 -> 可批
        String a3 = submitHydraulic(windowId, "u3", List.of(seg), 6,
                "2027-02-02T01:00:00Z", "2027-02-02T01:10:00Z", 100, "carol");
        approve(a3, 200);
    }

    // ------------------------------------------------------------------
    // 阻力修改
    // ------------------------------------------------------------------

    @Test
    void resistanceUpdateThatBreachesExistingReturns409() throws Exception {
        long windowId = createWindow("ch-res-" + run,
                "2027-02-03T00:00:00Z", "2027-02-03T02:00:00Z", "1000");
        String seg = createSegment(1);
        String a1 = submitHydraulic(windowId, "u1", List.of(seg), 6,
                "2027-02-03T00:00:00Z", "2027-02-03T00:10:00Z", 100, "alice");
        approve(a1, 200);
        // k=3 时损失 3 × 36 = 108 > 100 -> 409，版本不变
        postJson("/api/segments/" + seg + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 3, "expectedVersion", 1), "ops", 409);
        // 版本仍为 1：用 expectedVersion=2 必然 409
        postJson("/api/segments/" + seg + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 2, "expectedVersion", 2), "ops", 409);
        // k=2 时损失 72 ≤ 100 -> 200，版本升为 2，快照按新参数重冻结
        JsonNode updated = postOk("/api/segments/" + seg + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 2, "expectedVersion", 1), "ops");
        assertEquals(2, updated.get("version").asInt());
        JsonNode detail = getOk("/api/allocations/" + a1 + "/hydraulic");
        assertEquals(2, detail.get("slices").get(0).get("resistance").asLong());
        assertEquals(2, detail.get("slices").get(0).get("resistanceVersion").asInt());
        assertEquals(72, detail.get("slices").get(0).get("loss").asLong());
    }

    // ------------------------------------------------------------------
    // 转让继承路径
    // ------------------------------------------------------------------

    @Test
    void transferInheritsPathAndChecksFlowConsistency() throws Exception {
        long windowId = createWindow("ch-tr-" + run,
                "2027-02-04T00:00:00Z", "2027-02-04T02:00:00Z", "1000");
        String seg = createSegment(1);
        String source = submitHydraulic(windowId, "u1", List.of(seg), 6,
                "2027-02-04T00:00:00Z", "2027-02-04T00:10:00Z", 100, "alice");
        approve(source, 200);
        // 目标 30 = 3 × 10 分钟，继承原路径后流量 3，源降为 3，合计不变
        Map<String, Object> targetBody = new HashMap<>();
        targetBody.put("commandKey", key("ac"));
        targetBody.put("allocationKey", key("ak"));
        targetBody.put("windowId", windowId);
        targetBody.put("userId", "u2");
        targetBody.put("amount", "30");
        String target = targetBody.get("allocationKey").toString();
        postOk("/api/allocations", targetBody, "bob");
        postOk("/api/transfers", Map.of("commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice");
        JsonNode targetDetail = getOk("/api/allocations/" + target + "/hydraulic");
        assertEquals(seg, targetDetail.get("path").get(0).asText());
        assertEquals(3, targetDetail.get("flowRate").asLong());
        assertEquals(100, targetDetail.get("availableHead").asLong());
        assertEquals(seg, targetDetail.get("path").get(0).asText());
        JsonNode sourceDetail = getOk("/api/allocations/" + source + "/hydraulic");
        assertEquals(3, sourceDetail.get("flowRate").asLong());
        // 水量 25 无法按 10 分钟折算为正整数流量 -> 422，源持有额度不变
        Map<String, Object> badTarget = new HashMap<>();
        badTarget.put("commandKey", key("ac"));
        badTarget.put("allocationKey", key("ak"));
        badTarget.put("windowId", windowId);
        badTarget.put("userId", "u3");
        badTarget.put("amount", "25");
        String badKey = badTarget.get("allocationKey").toString();
        postOk("/api/allocations", badTarget, "carol");
        postJson("/api/transfers", Map.of("commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", badKey), "alice", 422);
        assertEquals("30", getOk("/api/windows/" + windowId + "/history")
                .get("allocations").get(0).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotentReplayConflictAndFailureFreesKey() throws Exception {
        long windowId = createWindow("ch-idem-" + run,
                "2027-02-05T00:00:00Z", "2027-02-05T02:00:00Z", "1000");
        String seg = createSegment(1);
        // 同 requestId 同参 -> 重放首次完整响应；同键异参 -> 409
        String requestId = key("sr");
        Map<String, Object> body = segmentBody(requestId, key("seg"), 4);
        JsonNode first = postOk("/api/segments", body, "ops");
        JsonNode replay = postOk("/api/segments", body, "ops");
        assertEquals(first, replay);
        postJson("/api/segments", segmentBody(requestId, key("seg"), 5), "ops", 409);
        // 失败不占键：未知渠段 404 后，同 requestId 修正参数可成功
        String submitId = key("hr");
        String allocationKey = key("ak");
        postJson("/api/allocations/hydraulic",
                hydraulicBody(submitId, allocationKey, windowId, "u1", List.of("no-such-" + run), 6,
                        "2027-02-05T00:00:00Z", "2027-02-05T00:10:00Z", 100), "alice", 404);
        JsonNode submitted = postOk("/api/allocations/hydraulic",
                hydraulicBody(submitId, allocationKey, windowId, "u1", List.of(seg), 6,
                        "2027-02-05T00:00:00Z", "2027-02-05T00:10:00Z", 100), "alice");
        assertEquals("REQUESTED", submitted.get("status").asText());
        // 水力提交同键同参重放，只创建一条申请
        JsonNode submittedReplay = postOk("/api/allocations/hydraulic",
                hydraulicBody(submitId, allocationKey, windowId, "u1", List.of(seg), 6,
                        "2027-02-05T00:00:00Z", "2027-02-05T00:10:00Z", 100), "alice");
        assertEquals(submitted, submittedReplay);
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
        // 同键改流量 -> 409
        postJson("/api/allocations/hydraulic",
                hydraulicBody(submitId, key("ak"), windowId, "u1", List.of(seg), 7,
                        "2027-02-05T00:00:00Z", "2027-02-05T00:10:00Z", 100), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentResistanceUpdatesExactlyOneWins() throws Exception {
        String seg = createSegment(1);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = key("sr");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.updateSegmentResistance(requestId, seg, 2L, 1, "ops");
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
        assertEquals(1, ok, "同一期望版本并发修改只能成功一次");
        assertEquals(threads - 1, conflict);
        // 最终版本为 2：用 expectedVersion=2 可继续修改
        JsonNode updated = postOk("/api/segments/" + seg + "/resistance",
                Map.of("requestId", key("sr"), "resistance", 3, "expectedVersion", 2), "ops");
        assertEquals(3, updated.get("version").asInt());
    }

    @Test
    void concurrentApprovalsOnlyOneFitsHead() throws Exception {
        long windowId = createWindow("ch-conc-" + run,
                "2027-02-06T00:00:00Z", "2027-02-06T02:00:00Z", "1000");
        String seg = createSegment(1);
        String a1 = submitHydraulic(windowId, "u1", List.of(seg), 6,
                "2027-02-06T00:00:00Z", "2027-02-06T00:10:00Z", 100, "alice");
        String a2 = submitHydraulic(windowId, "u2", List.of(seg), 6,
                "2027-02-06T00:00:00Z", "2027-02-06T00:10:00Z", 100, "bob");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String cmd1 = key("ap");
        String cmd2 = key("ap");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(cmd1, a1);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(cmd2, a2);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // 合计损失 144 > 100：恰好一单批准成功，另一单 422
        assertTrue((s1 == 200) != (s2 == 200), "s1=" + s1 + " s2=" + s2);
        assertEquals("60", getOk("/api/windows/" + windowId + "/capacity")
                .get("approvedTotal").asText());
    }
}

