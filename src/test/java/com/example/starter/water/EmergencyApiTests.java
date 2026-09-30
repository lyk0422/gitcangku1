package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 紧急储备水 API 集成测试：主流程、422/409 失败分支、状态流转、并发守恒与幂等边界。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、行锁与 CHECK 约束，不 mock 数据库边界。
 * 每个用例前清空紧急储备场景数据并把总账复位为播种状态（10 单位），用例间互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmergencyService emergencyService;

    @Autowired
    private WaterService waterService;

    @Autowired
    private JdbcTemplate jdbc;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    @BeforeEach
    void resetEmergencyLedger() {
        jdbc.update("DELETE FROM reserve_flow");
        jdbc.update("DELETE FROM reserve_activation");
        jdbc.update("DELETE FROM emergency_event");
        jdbc.update("UPDATE reserve_ledger SET total_volume = 10.000, reserved_volume = 0.000,"
                + " updated_nanos = 0 WHERE id = 1");
    }

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

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-em-" + run + "-" + seq);
        body.put("startUtc", "2026-11-01T00:00:00Z");
        body.put("endUtc", "2026-11-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String registerEvent() throws Exception {
        String eventKey = key("ev");
        JsonNode node = postOk("/api/emergency/events",
                Map.of("commandKey", key("evc"), "eventKey", eventKey, "title", "干渠险情-" + seq,
                        "description", "紧急抢修输水"), null);
        assertEquals("ACTIVE", node.get("status").asText());
        return eventKey;
    }

    private Map<String, Object> activationBody(String commandKey, String requestId, String eventKey,
                                               long windowId, String volume, String a, String b) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestId", requestId);
        body.put("eventKey", eventKey);
        body.put("windowId", windowId);
        body.put("volume", volume);
        body.put("confirmerA", a);
        body.put("confirmerB", b);
        return body;
    }

    private JsonNode activate(long windowId, String eventKey, String requestId, String volume,
                              String a, String b, int expectedStatus) throws Exception {
        return objectMapper.readTree(postJson("/api/emergency/activations",
                activationBody(key("ac"), requestId, eventKey, windowId, volume, a, b),
                null, expectedStatus).getResponse().getContentAsString());
    }

    private JsonNode reserve() throws Exception {
        return getOk("/api/emergency/reserve");
    }

    private JsonNode windowReserve(long windowId) throws Exception {
        return getOk("/api/emergency/windows/" + windowId);
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("alc"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approveAllocation(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private int activateStatus(String requestId, String eventKey, long windowId, String volume,
                               String a, String b) {
        try {
            emergencyService.activate(key("ac"), requestId, eventKey, windowId, volume, a, b);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private int flowStatus(String op, String requestId, String volume, String actor) {
        try {
            if ("WRITE_OFF".equals(op)) {
                emergencyService.writeOff(key("woc"), requestId, volume, actor);
            } else {
                emergencyService.reserveReturn(key("rtc"), requestId, volume, actor);
            }
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 题干主场景
    // ------------------------------------------------------------------

    @Test
    void scenarioTenReserveActivateSixReturnSevenRejected() throws Exception {
        // 储备有 10 单位
        JsonNode initial = reserve();
        assertEquals("10", initial.get("totalVolume").asText());
        assertEquals("0", initial.get("reservedVolume").asText());
        assertEquals("10", initial.get("availableVolume").asText());

        String eventKey = registerEvent();
        long windowId = createWindow("100");

        // 事件 E 经甲乙确认启用 6 单位至窗口 W
        String requestId = key("req");
        JsonNode activation = activate(windowId, eventKey, requestId, "6", "role-jia", "role-yi", 200);
        assertEquals(requestId, activation.get("requestId").asText());
        assertEquals(eventKey, activation.get("eventKey").asText());
        assertEquals(windowId, activation.get("windowId").asLong());
        assertEquals("6", activation.get("volume").asText());
        assertEquals("role-jia", activation.get("confirmerA").asText());
        assertEquals("role-yi", activation.get("confirmerB").asText());
        assertEquals("ACTIVE", activation.get("status").asText());

        // 储备剩 4
        JsonNode after = reserve();
        assertEquals("10", after.get("totalVolume").asText());
        assertEquals("6", after.get("reservedVolume").asText());
        assertEquals("4", after.get("availableVolume").asText());

        // 窗口预占 6
        JsonNode wr = windowReserve(windowId);
        assertEquals("6", wr.get("occupiedVolume").asText());
        assertEquals("0", wr.get("writtenOffVolume").asText());
        assertEquals("0", wr.get("returnedVolume").asText());
        assertEquals(1, wr.get("activations").size());
        assertEquals("6", wr.get("activations").get(0).get("outstandingVolume").asText());

        // 归还 7 单位被拒（超过已启用且尚未核销的 6 单位），两个账本不变
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rtc"), "requestId", requestId, "volume", "7"),
                "role-jia", 422);
        JsonNode reserveFinal = reserve();
        assertEquals("6", reserveFinal.get("reservedVolume").asText());
        assertEquals("4", reserveFinal.get("availableVolume").asText());
        JsonNode wrFinal = windowReserve(windowId);
        assertEquals("6", wrFinal.get("occupiedVolume").asText());
        assertEquals("0", wrFinal.get("returnedVolume").asText());
        assertEquals(0, wrFinal.get("flows").size());

        // 普通容量账本看不到储备：已批准总量 0，可用仍为 100，响应中没有储备字段
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("0", capacity.get("approvedTotal").asText());
        assertEquals("100", capacity.get("remaining").asText());
        assertFalse(capacity.has("reserveVolume"));
        assertFalse(capacity.has("emergencyVolume"));
    }

    // ------------------------------------------------------------------
    // 失败分支：422 不动任何账本
    // ------------------------------------------------------------------

    @Test
    void nonEmergencyEventAndDuplicateConfirmerRejected422() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();

        // 同一角色重复确认 -> 422
        activate(windowId, eventKey, key("req"), "6", "role-jia", "role-jia", 422);

        // 事件失效后再启用 -> 422（非紧急用途）
        postOk("/api/emergency/events/" + eventKey + "/cancel",
                Map.of("commandKey", key("evc")), null);
        activate(windowId, eventKey, key("req"), "6", "role-jia", "role-yi", 422);

        // 储备与窗口均无任何变化
        assertEquals("0", reserve().get("reservedVolume").asText());
        assertEquals("0", windowReserve(windowId).get("occupiedVolume").asText());
        assertEquals(0, windowReserve(windowId).get("activations").size());
        // 重复取消事件 -> 409
        postJson("/api/emergency/events/" + eventKey + "/cancel",
                Map.of("commandKey", key("evc")), null, 409);
        // 未登记事件 -> 404；窗口不存在 -> 404
        activate(windowId, "missing-event-" + run, key("req"), "1", "role-jia", "role-yi", 404);
        activate(999999999L, eventKey, key("req"), "1", "role-jia", "role-yi", 404);
    }

    @Test
    void insufficientReserveReturns422AndChangesNothing() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();

        activate(windowId, eventKey, key("req"), "6", "jia", "yi", 200);
        // 剩余 4，申请 5 -> 422
        activate(windowId, eventKey, key("req"), "5", "jia", "yi", 422);
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals(1, windowReserve(windowId).get("activations").size());

        // 精确十进制：再申请 4.000 成功，4.001 失败
        long window2 = createWindow("100");
        activate(window2, eventKey, key("req"), "4.000", "jia", "bing", 200);
        assertEquals("0", reserve().get("availableVolume").asText());
        activate(window2, eventKey, key("req"), "0.001", "jia", "bing", 422);
        assertEquals("10", reserve().get("reservedVolume").asText());
        assertEquals(1, windowReserve(window2).get("activations").size());
    }

    @Test
    void invalidActivationParamsReturn400() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        // 水量非法
        activate(windowId, eventKey, key("req"), "0", "jia", "yi", 400);
        activate(windowId, eventKey, key("req"), "-1", "jia", "yi", 400);
        activate(windowId, eventKey, key("req"), "1.0001", "jia", "yi", 400);
        // 非法键
        activate(windowId, eventKey, "bad request!", "1", "jia", "yi", 400);
        assertEquals("0", reserve().get("reservedVolume").asText());
        // 缺 windowId
        Map<String, Object> body = activationBody(key("ac"), key("req"), eventKey, windowId, "1", "jia", "yi");
        body.remove("windowId");
        postJson("/api/emergency/activations", body, null, 400);
        // 核销缺 X-Actor-Id -> 400
        String requestId = key("req");
        activate(windowId, eventKey, requestId, "6", "jia", "yi", 200);
        postJson("/api/emergency/activations/" + requestId + "/write-off",
                Map.of("commandKey", key("woc"), "requestId", requestId, "volume", "1"), null, 400);
        // 核销不存在的启用 -> 404
        postJson("/api/emergency/activations/missing-" + run + "/write-off",
                Map.of("commandKey", key("woc"), "requestId", "missing-" + run, "volume", "1"),
                "jia", 404);
    }

    // ------------------------------------------------------------------
    // 核销 / 归还状态边界
    // ------------------------------------------------------------------

    @Test
    void writeOffAndReturnRespectOutstandingAndConserveBothLedgers() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        String requestId = key("req");
        activate(windowId, eventKey, requestId, "6", "jia", "yi", 200);

        // 核销 2：应急耗用，储备已预占不变，窗口占用降为 4
        JsonNode writtenOff = postOk("/api/emergency/activations/" + requestId + "/write-off",
                Map.of("commandKey", key("woc"), "requestId", requestId, "volume", "2"), "jia");
        assertEquals("2", writtenOff.get("writtenOffVolume").asText());
        assertEquals("0", writtenOff.get("returnedVolume").asText());
        assertEquals("4", writtenOff.get("outstandingVolume").asText());
        assertEquals("6", reserve().get("reservedVolume").asText());

        // 归还 5 超过尚未核销的 4 -> 422，两个账本不变
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rtc"), "requestId", requestId, "volume", "5"), "yi", 422);
        assertEquals("6", reserve().get("reservedVolume").asText());
        JsonNode wr = windowReserve(windowId);
        assertEquals("4", wr.get("occupiedVolume").asText());
        assertEquals("0", wr.get("returnedVolume").asText());

        // 归还 4 -> 退回储备总账：储备预占降为 2，窗口占用归零
        JsonNode returned = postOk("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rtc"), "requestId", requestId, "volume", "4"), "yi");
        assertEquals("4", returned.get("returnedVolume").asText());
        assertEquals("0", returned.get("outstandingVolume").asText());
        JsonNode reserve = reserve();
        assertEquals("2", reserve.get("reservedVolume").asText());
        assertEquals("8", reserve.get("availableVolume").asText());

        // 已无窗口余量：再核销/归还任意正数 -> 422
        postJson("/api/emergency/activations/" + requestId + "/write-off",
                Map.of("commandKey", key("woc"), "requestId", requestId, "volume", "0.001"), "jia", 422);
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rtc"), "requestId", requestId, "volume", "0.001"), "yi", 422);

        // 查询分别展示预占、核销与归还流水
        JsonNode finalWr = windowReserve(windowId);
        assertEquals("0", finalWr.get("occupiedVolume").asText());
        assertEquals("2", finalWr.get("writtenOffVolume").asText());
        assertEquals("4", finalWr.get("returnedVolume").asText());
        assertEquals(2, finalWr.get("flows").size());
        assertEquals("WRITE_OFF", finalWr.get("flows").get(0).get("type").asText());
        assertEquals("RETURN", finalWr.get("flows").get(1).get("type").asText());
        // 储备总量始终 10：已核销 2 离开两本账，已归还 4 回到储备，预占 2 = 10 - 8
        assertEquals("10", reserve.get("totalVolume").asText());
    }

    // ------------------------------------------------------------------
    // 幂等边界
    // ------------------------------------------------------------------

    @Test
    void requestIdReplayReturnsFirstVolumeButChangingEventOrWindowReturns409() throws Exception {
        long w1 = createWindow("100");
        long w2 = createWindow("100");
        String e1 = registerEvent();
        String e2 = registerEvent();
        String requestId = key("req");

        JsonNode first = activate(w1, e1, requestId, "6", "jia", "yi", 200);
        // 同 requestId 同事件同窗口重放（即使换新 commandKey）-> 首次转入数量，不重复扣减
        JsonNode replay = activate(w1, e1, requestId, "6", "jia", "yi", 200);
        assertEquals(first, replay);
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals(1, windowReserve(w1).get("activations").size());

        // 同 requestId 换事件 -> 409
        activate(w1, e2, requestId, "6", "jia", "yi", 409);
        // 同 requestId 换窗口 -> 409
        activate(w2, e1, requestId, "6", "jia", "yi", 409);
        // 冲突未产生新预占
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals(0, windowReserve(w2).get("activations").size());
    }

    @Test
    void commandKeyReplayAndConflictForEmergencyCommands() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        String commandKey = key("ac");
        String requestId = key("req");
        Map<String, Object> body = activationBody(commandKey, requestId, eventKey, windowId, "6", "jia", "yi");

        JsonNode first = postOk("/api/emergency/activations", body, null);
        JsonNode replay = postOk("/api/emergency/activations", body, null);
        assertEquals(first, replay);
        assertEquals("6", reserve().get("reservedVolume").asText());

        // 同 commandKey 改水量 -> 409
        postJson("/api/emergency/activations",
                activationBody(commandKey, requestId, eventKey, windowId, "5", "jia", "yi"), null, 409);
        // 失败命令不占用键：储备不足的启用失败后，同键改为登记事件之类的其他参数仍按新命令处理
        String failCommand = key("ac");
        postJson("/api/emergency/activations",
                activationBody(failCommand, key("req"), eventKey, windowId, "7", "jia", "yi"), null, 422);
        String otherRequest = key("req");
        postJson("/api/emergency/activations",
                activationBody(failCommand, otherRequest, eventKey, windowId, "3", "jia", "yi"), null, 200);
        assertEquals("9", reserve().get("reservedVolume").asText());

        // 核销命令同键重放
        String writeOffCommand = key("woc");
        Map<String, Object> woBody = Map.of("commandKey", writeOffCommand, "requestId", requestId, "volume", "1");
        JsonNode wo1 = postOk("/api/emergency/activations/" + requestId + "/write-off", woBody, "jia");
        JsonNode wo2 = postOk("/api/emergency/activations/" + requestId + "/write-off", woBody, "jia");
        assertEquals(wo1, wo2);
        // 重放不重复核销：仍只核销 1
        assertEquals("1", windowReserve(windowId).get("writtenOffVolume").asText());
    }

    // ------------------------------------------------------------------
    // 历史审批留存
    // ------------------------------------------------------------------

    @Test
    void historyKeepsEventAndTwoConfirmersAfterEventCancelled() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        String requestId = key("req");
        activate(windowId, eventKey, requestId, "6", "role-jia", "role-yi", 200);
        postOk("/api/emergency/activations/" + requestId + "/write-off",
                Map.of("commandKey", key("woc"), "requestId", requestId, "volume", "2"), "role-bing");
        // 事件事后失效
        postOk("/api/emergency/events/" + eventKey + "/cancel", Map.of("commandKey", key("evc")), null);

        JsonNode history = getOk("/api/emergency/events/" + eventKey + "/history");
        assertEquals("CANCELLED", history.get("event").get("status").asText());
        assertEquals(eventKey, history.get("event").get("eventKey").asText());
        assertEquals(1, history.get("activations").size());
        JsonNode activation = history.get("activations").get(0);
        // 历史审批保留当时事件与两位确认人
        assertEquals(eventKey, activation.get("eventKey").asText());
        assertEquals(requestId, activation.get("requestId").asText());
        assertEquals(windowId, activation.get("windowId").asLong());
        assertEquals("6", activation.get("volume").asText());
        assertEquals("role-jia", activation.get("confirmerA").asText());
        assertEquals("role-yi", activation.get("confirmerB").asText());
        assertEquals("ACTIVE", activation.get("status").asText());
        // 核销流水保留操作人与数量
        assertEquals(1, history.get("flows").size());
        assertEquals("WRITE_OFF", history.get("flows").get(0).get("type").asText());
        assertEquals("2", history.get("flows").get(0).get("volume").asText());
        assertEquals("role-bing", history.get("flows").get(0).get("actor").asText());
    }

    // ------------------------------------------------------------------
    // 并发：真实 H2 行锁裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentActivationsNeverOversellReserve() throws Exception {
        long w1 = createWindow("100");
        long w2 = createWindow("100");
        String eventKey = registerEvent();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return activateStatus(key("req"), eventKey, w1, "6", "jia", "yi");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return activateStatus(key("req"), eventKey, w2, "6", "bing", "ding");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 储备仅 10：两笔 6 恰好一笔成功，另一笔 422
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals("4", reserve().get("availableVolume").asText());
    }

    @Test
    void sameRequestIdConcurrentActivationSettlesExactlyOnce() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        String requestId = key("req");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return activateStatus(requestId, eventKey, windowId, "6", "jia", "yi");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return activateStatus(requestId, eventKey, windowId, "6", "jia", "yi");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同 requestId 并发：双方都拿到首次结果，只产生一条审批与一次 6 单位预占
        assertEquals(200, s1);
        assertEquals(200, s2);
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals(1, windowReserve(windowId).get("activations").size());
        assertEquals("6", windowReserve(windowId).get("occupiedVolume").asText());
    }

    @Test
    void emergencyActivationAndNormalApproveConcurrentlyBothConserve() throws Exception {
        // 普通账本：窗口计划 10，普通申请 8；紧急账本：同窗口启用 6。两本账独立，必须共同守恒
        long windowId = createWindow("10");
        String eventKey = registerEvent();
        String allocationKey = submitAllocation(windowId, "user-normal", "8", "alice");
        String approveCommand = key("ap");
        String requestId = key("req");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, allocationKey);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> activateFuture = pool.submit(() -> {
            gate.await();
            return activateStatus(requestId, eventKey, windowId, "6", "jia", "yi");
        });
        gate.countDown();
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        int activateStatus = activateFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两本账独立：普通批准 8 与紧急启用 6 各自成立，互不挤压
        assertEquals(200, approveStatus);
        assertEquals(200, activateStatus);
        JsonNode capacity = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("8", capacity.get("approvedTotal").asText());
        assertEquals("2", capacity.get("remaining").asText());
        assertEquals("6", reserve().get("reservedVolume").asText());
        assertEquals("4", reserve().get("availableVolume").asText());
        JsonNode wr = windowReserve(windowId);
        assertEquals("6", wr.get("occupiedVolume").asText());
        // 窗口占用共同守恒：普通已批准 8 + 紧急预占 6 = 14，各按各账，不凭空增减
        double normalUsed = Double.parseDouble(capacity.get("approvedTotal").asText());
        double emergencyUsed = Double.parseDouble(wr.get("occupiedVolume").asText());
        assertEquals(14.0, normalUsed + emergencyUsed, 0.0001);
    }

    @Test
    void concurrentWriteOffAndReturnNeverExceedActivatedOutstanding() throws Exception {
        long windowId = createWindow("100");
        String eventKey = registerEvent();
        String requestId = key("req");
        activate(windowId, eventKey, requestId, "6", "jia", "yi", 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> writeOffFuture = pool.submit(() -> {
            gate.await();
            return flowStatus("WRITE_OFF", requestId, "4", "jia");
        });
        Future<Integer> returnFuture = pool.submit(() -> {
            gate.await();
            return flowStatus("RETURN", requestId, "4", "yi");
        });
        gate.countDown();
        int writeOffStatus = writeOffFuture.get(30, TimeUnit.SECONDS);
        int returnStatus = returnFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 启用仅 6：核销 4 与归还 4 竞争，恰好一个成功，另一个 422
        assertEquals(1, (writeOffStatus == 200 ? 1 : 0) + (returnStatus == 200 ? 1 : 0),
                "writeOff=" + writeOffStatus + " return=" + returnStatus);
        assertEquals(1, (writeOffStatus == 422 ? 1 : 0) + (returnStatus == 422 ? 1 : 0));
        JsonNode wr = windowReserve(windowId);
        JsonNode activation = wr.get("activations").get(0);
        double writtenOff = Double.parseDouble(activation.get("writtenOffVolume").asText());
        double returned = Double.parseDouble(activation.get("returnedVolume").asText());
        double outstanding = Double.parseDouble(activation.get("outstandingVolume").asText());
        // 守恒：核销 + 归还恰为 4，窗口余 2；若归还胜出则储备预占降为 2
        assertEquals(4.0, writtenOff + returned, 0.0001);
        assertEquals(2.0, outstanding, 0.0001);
        double reserved = Double.parseDouble(reserve().get("reservedVolume").asText());
        assertEquals(6.0 - returned, reserved, 0.0001);
        assertTrue(reserved >= 2.0 - 0.0001);
    }
}
