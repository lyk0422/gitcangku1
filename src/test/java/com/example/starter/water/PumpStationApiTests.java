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
 * 泵站流量曲线 API 集成测试：合成样例、结构校验、积分、流量与斜率上限、
 * 版本化配置、幂等与并发。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PumpStationApiTests {

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

    private Map<String, Object> stationBody(String requestId, String stationKey, String maxFlow,
                                            String maxRamp, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("stationKey", stationKey);
        body.put("maxFlow", maxFlow);
        body.put("maxRampPerMinute", maxRamp);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode upsertStation(String stationKey, String maxFlow, String maxRamp,
                                   long expectedVersion, int expectedStatus) throws Exception {
        String json = postJson("/api/pump-stations",
                stationBody(key("rq"), stationKey, maxFlow, maxRamp, expectedVersion), "operator",
                expectedStatus).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    /** 合成三角曲线：首末为 0，中点为峰值。 */
    private List<Map<String, Object>> triangle(String start, String mid, String end, String peak) {
        return List.of(
                Map.of("timeUtc", start, "flow", "0"),
                Map.of("timeUtc", mid, "flow", peak),
                Map.of("timeUtc", end, "flow", "0"));
    }

    private String submitCurve(long windowId, String userId, String amount, String actor,
                               String stationKey, List<Map<String, Object>> curve) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("pumpStationKey", stationKey);
        body.put("curve", curve);
        JsonNode node = postOk("/api/allocations", body, actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }

    private JsonNode submitCurveRaw(long windowId, String userId, String amount, String actor,
                                    String stationKey, List<Map<String, Object>> curve,
                                    String commandKey, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (stationKey != null) {
            body.put("pumpStationKey", stationKey);
        }
        body.put("curve", curve);
        String json = postJson("/api/allocations", body, actor, expectedStatus)
                .getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode approve(String allocationKey, int expectedStatus) throws Exception {
        String json = postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode historyAllocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (node.get("allocationKey").asText().equals(allocationKey)) {
                return node;
            }
        }
        throw new AssertionError("申请不存在: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 合成样例与主流程
    // ------------------------------------------------------------------

    /** 合成 0、10、0 三个相距一分钟节点，积分 10；最大流量 10 可批准，同形第二申请被拒绝。 */
    @Test
    void syntheticTriangleApprovesFirstAndRejectsSecond() throws Exception {
        long windowId = createWindow("ch-syn-" + run,
                "2026-11-01T00:00:00Z", "2026-11-01T00:02:00Z", "1000");
        String station = key("st");
        JsonNode created = upsertStation(station, "10", "1000", 0, 200);
        assertEquals(1, created.get("version").asLong());
        assertEquals("10", created.get("maxFlow").asText());

        List<Map<String, Object>> curve = triangle("2026-11-01T00:00:00Z",
                "2026-11-01T00:01:00Z", "2026-11-01T00:02:00Z", "10");
        String first = submitCurve(windowId, "user-1", "10", "alice", station, curve);
        JsonNode approved = approve(first, 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals(station, approved.get("pumpStationKey").asText());
        assertEquals(3, approved.get("curve").size());
        assertEquals(1, approved.get("approvedStationVersion").asLong());

        // 同形第二申请同时占用：叠加峰值 20 > 10，拒绝且仍待审、配额不扣
        String second = submitCurve(windowId, "user-2", "10", "bob", station, curve);
        JsonNode rejected = approve(second, 422);
        assertEquals("FLOW_LIMIT_EXCEEDED", rejected.get("code").asText());
        JsonNode view = historyAllocation(windowId, second);
        assertEquals("REQUESTED", view.get("status").asText());
        assertEquals("0", view.get("heldAmount").asText());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", capacity.get("approvedTotal").asText());
        assertEquals("990", capacity.get("remaining").asText());
    }

    /** 积分不等返回 422，失败不占键，修正后同 commandKey 可成功。 */
    @Test
    void integralMismatchReturns422AndDoesNotOccupyKey() throws Exception {
        long windowId = createWindow("ch-int-" + run,
                "2026-11-02T00:00:00Z", "2026-11-02T00:02:00Z", "1000");
        String station = key("st");
        upsertStation(station, "10", "1000", 0, 200);
        List<Map<String, Object>> curve = triangle("2026-11-02T00:00:00Z",
                "2026-11-02T00:01:00Z", "2026-11-02T00:02:00Z", "10");
        String commandKey = key("ac");
        JsonNode mismatch = submitCurveRaw(windowId, "user-1", "9.999", "alice", station, curve,
                commandKey, 422);
        assertEquals("CURVE_INTEGRAL_MISMATCH", mismatch.get("code").asText());
        // 失败不占键：同 commandKey 修正水量后成功
        JsonNode ok = submitCurveRaw(windowId, "user-1", "10", "alice", station, curve, commandKey, 200);
        assertEquals("REQUESTED", ok.get("status").asText());
    }
    /** 曲线结构校验：节点数、整分钟、递增、窗口边界、流量格式、泵站存在性。 */
    @Test
    void curveStructuralValidation() throws Exception {
        long windowId = createWindow("ch-val-" + run,
                "2026-11-03T00:00:00Z", "2026-11-03T00:20:00Z", "1000");
        String station = key("st");
        upsertStation(station, "10", "1000", 0, 200);
        // 单节点
        submitCurveRaw(windowId, "u", "1", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "0")), key("ac"), 400);
        // 21 个节点
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            tooMany.add(Map.of("timeUtc", String.format("2026-11-03T00:%02d:00Z", i), "flow", "0"));
        }
        submitCurveRaw(windowId, "u", "1", "alice", station, tooMany, key("ac"), 400);
        // 非整分钟
        submitCurveRaw(windowId, "u", "1", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:00:30Z", "flow", "0"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "0")), key("ac"), 400);
        // 时刻不递增
        submitCurveRaw(windowId, "u", "1", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:01:00Z", "flow", "0"),
                        Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "0")), key("ac"), 400);
        // 首末不等于窗口边界（积分与水量一致，确保命中边界校验）
        submitCurveRaw(windowId, "u", "19", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:01:00Z", "flow", "1"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "1")), key("ac"), 400);
        // 负流量
        submitCurveRaw(windowId, "u", "1", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "-1"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "0")), key("ac"), 400);
        // 超过 3 位小数
        submitCurveRaw(windowId, "u", "1", "alice", station,
                List.of(Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "0.0001"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "0")), key("ac"), 400);
        // 附曲线但缺 pumpStationKey
        submitCurveRaw(windowId, "u", "1", "alice", null,
                List.of(Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "0"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "0")), key("ac"), 400);
        // 泵站不存在（积分与水量一致，确保命中存在性校验）
        submitCurveRaw(windowId, "u", "20", "alice", key("st-unknown"),
                List.of(Map.of("timeUtc", "2026-11-03T00:00:00Z", "flow", "1"),
                        Map.of("timeUtc", "2026-11-03T00:20:00Z", "flow", "1")), key("ac"), 404);
    }

    /** 每分钟最大变化量：斜率越限 422，申请仍待审。 */
    @Test
    void rampLimitExceededReturns422() throws Exception {
        long windowId = createWindow("ch-ramp-" + run,
                "2026-11-04T00:00:00Z", "2026-11-04T00:02:00Z", "1000");
        String station = key("st");
        upsertStation(station, "1000", "5", 0, 200);
        String allocation = submitCurve(windowId, "user-1", "10", "alice", station,
                triangle("2026-11-04T00:00:00Z", "2026-11-04T00:01:00Z",
                        "2026-11-04T00:02:00Z", "10"));
        JsonNode rejected = approve(allocation, 422);
        assertEquals("RAMP_LIMIT_EXCEEDED", rejected.get("code").asText());
        assertEquals("REQUESTED", historyAllocation(windowId, allocation).get("status").asText());
    }

    /** 取消只去除该申请贡献：取消后同形申请可批准。 */
    @Test
    void cancelRemovesOnlyOwnContribution() throws Exception {
        long windowId = createWindow("ch-cancel-" + run,
                "2026-11-05T00:00:00Z", "2026-11-05T00:02:00Z", "1000");
        String station = key("st");
        upsertStation(station, "10", "1000", 0, 200);
        List<Map<String, Object>> curve = triangle("2026-11-05T00:00:00Z",
                "2026-11-05T00:01:00Z", "2026-11-05T00:02:00Z", "6");
        String first = submitCurve(windowId, "user-1", "6", "alice", station, curve);
        approve(first, 200);
        String second = submitCurve(windowId, "user-2", "6", "bob", station, curve);
        approve(second, 422);
        // 非本人取消 409；本人取消后第二申请可批准
        postJson("/api/allocations/" + first + "/cancel", Map.of("commandKey", key("cc")), "bob", 409);
        postJson("/api/allocations/" + first + "/cancel", Map.of("commandKey", key("cc")), "alice", 200);
        JsonNode approved = approve(second, 200);
        assertEquals("APPROVED", approved.get("status").asText());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("6", capacity.get("approvedTotal").asText());
    }

    /** 配置版本递增、期望版本校验、参数缩小越限 409、批准冻结版本。 */
    @Test
    void configVersioningAndShrinkGuard() throws Exception {
        long windowId = createWindow("ch-ver-" + run,
                "2026-11-06T00:00:00Z", "2026-11-06T00:02:00Z", "1000");
        String station = key("st");
        JsonNode created = upsertStation(station, "10", "1000", 0, 200);
        assertEquals(1, created.get("version").asLong());
        // 重复创建与错误期望版本均 409
        upsertStation(station, "10", "1000", 0, 409);
        upsertStation(station, "10", "1000", 2, 409);
        // 合法更新：版本递增
        JsonNode updated = upsertStation(station, "10", "1000", 1, 200);
        assertEquals(2, updated.get("version").asLong());

        String allocation = submitCurve(windowId, "user-1", "8", "alice", station,
                triangle("2026-11-06T00:00:00Z", "2026-11-06T00:01:00Z",
                        "2026-11-06T00:02:00Z", "8"));
        JsonNode approved = approve(allocation, 200);
        assertEquals(2, approved.get("approvedStationVersion").asLong());

        // 参数缩小使已有曲线非法：409，版本不变
        upsertStation(station, "5", "1000", 2, 409);
        assertEquals(2, getOk("/api/pump-stations/" + station).get("version").asLong());
        // 缩小到恰好等于峰值：合法，版本递增；旧批准结果不变
        upsertStation(station, "8", "1000", 2, 200);
        assertEquals(3, getOk("/api/pump-stations/" + station).get("version").asLong());
        JsonNode view = historyAllocation(windowId, allocation);
        assertEquals("APPROVED", view.get("status").asText());
        assertEquals(2, view.get("approvedStationVersion").asLong());
    }

    /** requestId 幂等：同键同参重放首次响应，异参 409，失败不占键，身份含操作者。 */
    @Test
    void requestIdIdempotency() throws Exception {
        String station = key("st");
        String requestId = key("rq");
        Map<String, Object> body = stationBody(requestId, station, "10", "100", 0);
        JsonNode first = objectMapper.readTree(postJson("/api/pump-stations", body, "operator", 200)
                .getResponse().getContentAsString());
        assertEquals(1, first.get("version").asLong());
        // 同键同参重放：返回首次完整响应，版本不递增
        JsonNode replay = objectMapper.readTree(postJson("/api/pump-stations", body, "operator", 200)
                .getResponse().getContentAsString());
        assertEquals(first.toString(), replay.toString());
        assertEquals(1, getOk("/api/pump-stations/" + station).get("version").asLong());
        // 同键异参 409（参数、操作者均属于身份）
        postJson("/api/pump-stations", stationBody(requestId, station, "20", "100", 0), "operator", 409);
        postJson("/api/pump-stations", body, "other-operator", 409);
        // 失败不占键：版本冲突后同 requestId 修正参数可成功
        String failed = key("rq");
        postJson("/api/pump-stations", stationBody(failed, key("st"), "10", "100", 5), "operator", 409);
        JsonNode recovered = objectMapper.readTree(postJson("/api/pump-stations",
                stationBody(failed, key("st"), "10", "100", 0), "operator", 200)
                .getResponse().getContentAsString());
        assertEquals(1, recovered.get("version").asLong());
    }
    /** 并发曲线批准：同泵站叠加越限时恰好一个成功。 */
    @Test
    void concurrentCurveApprovesAreSerialized() throws Exception {
        long windowId = createWindow("ch-conc-" + run,
                "2026-11-07T00:00:00Z", "2026-11-07T00:02:00Z", "1000");
        String station = key("st");
        upsertStation(station, "10", "1000", 0, 200);
        List<Map<String, Object>> curve = triangle("2026-11-07T00:00:00Z",
                "2026-11-07T00:01:00Z", "2026-11-07T00:02:00Z", "10");
        String a1 = submitCurve(windowId, "user-1", "10", "alice", station, curve);
        String a2 = submitCurve(windowId, "user-2", "10", "bob", station, curve);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : List.of(a1, a2)) {
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
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "叠加峰值 20 > 10，恰好一个批准成功");
        assertEquals(1, rejected);
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", capacity.get("approvedTotal").asText());
    }

    /** 并发配置更新：相同期望版本恰好一个成功，版本只递增一次。 */
    @Test
    void concurrentConfigUpdatesAreSerialized() throws Exception {
        String station = key("st");
        upsertStation(station, "10", "100", 0, 200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String maxFlow : List.of("20", "30")) {
            String requestId = key("rq");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.upsertPumpStation(requestId, station, maxFlow, "100", 1L, "operator");
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
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals(2, getOk("/api/pump-stations/" + station).get("version").asLong());
    }

    /** 并发同 requestId 同参：全部返回首次响应，不泄露唯一键异常。 */
    @Test
    void concurrentSameRequestIdReplaysFirstResponse() throws Exception {
        String station = key("st");
        String requestId = key("rq");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                var response = waterService.upsertPumpStation(requestId, station, "10", "100", 0L,
                        "operator");
                return response.version() + ":" + response.maxFlow();
            }));
        }
        gate.countDown();
        for (Future<String> future : futures) {
            assertEquals("1:10", future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, getOk("/api/pump-stations/" + station).get("version").asLong());
    }

    /** 无曲线旧申请按原流程，不占用泵站容量。 */
    @Test
    void plainAllocationWithoutCurveFollowsOriginalFlow() throws Exception {
        long windowId = createWindow("ch-plain-" + run,
                "2026-11-08T00:00:00Z", "2026-11-08T02:00:00Z", "500");
        String station = key("st");
        upsertStation(station, "1", "1", 0, 200);
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "500");
        JsonNode submitted = postOk("/api/allocations", body, "alice");
        assertTrue(submitted.get("pumpStationKey").isNull());
        assertTrue(submitted.get("curve").isNull());
        String allocationKey = submitted.get("allocationKey").asText();
        JsonNode approved = approve(allocationKey, 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertTrue(approved.get("approvedStationVersion").isNull());
        // 泵站配置与窗口容量口径不变
        assertEquals(1, getOk("/api/pump-stations/" + station).get("version").asLong());
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("500", capacity.get("approvedTotal").asText());
        assertEquals("0", capacity.get("remaining").asText());
    }

    /** 附曲线申请不参与额度转让。 */
    @Test
    void transferWithCurveAllocationRejected() throws Exception {
        long windowId = createWindow("ch-tr-" + run,
                "2026-11-09T00:00:00Z", "2026-11-09T00:02:00Z", "1000");
        String station = key("st");
        upsertStation(station, "10", "1000", 0, 200);
        String source = submitCurve(windowId, "user-1", "6", "alice", station,
                triangle("2026-11-09T00:00:00Z", "2026-11-09T00:01:00Z",
                        "2026-11-09T00:02:00Z", "6"));
        approve(source, 200);
        Map<String, Object> targetBody = new HashMap<>();
        targetBody.put("commandKey", key("ac"));
        targetBody.put("allocationKey", key("ak"));
        targetBody.put("windowId", windowId);
        targetBody.put("userId", "user-2");
        targetBody.put("amount", "1");
        String target = postOk("/api/allocations", targetBody, "bob").get("allocationKey").asText();
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", source);
        transfer.put("targetAllocationKey", target);
        JsonNode rejected = objectMapper.readTree(postJson("/api/transfers", transfer, "alice", 409)
                .getResponse().getContentAsString());
        assertEquals("CURVE_TRANSFER_UNSUPPORTED", rejected.get("code").asText());
        // 源持有额度不变
        assertEquals("6", historyAllocation(windowId, source).get("heldAmount").asText());
    }
}