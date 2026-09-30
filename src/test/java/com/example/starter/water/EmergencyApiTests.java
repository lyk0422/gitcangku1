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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 紧急储备 API 测试：双角色确认主流程、422 失败不动账、409 幂等/换目标冲突、
 * 核销/归还边界、储备与普通排班隔离，以及紧急启用与普通分配并发时两本账与窗口占用共同守恒。
 * 全部使用真实 H2 内存库（MODE=MySQL）事务与行锁，不 mock 数据库边界。
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

    /**
     * 储备账户为全局单例，每个用例前清空紧急场景数据并把储备重置为初始 10，
     * 保证用例相互隔离（普通配水数据通过每用例唯一键与渠道隔离）。
     */
    @BeforeEach
    void resetEmergencyLedger() {
        jdbc.update("DELETE FROM emergency_flow");
        jdbc.update("DELETE FROM emergency_confirmation");
        jdbc.update("DELETE FROM emergency_request");
        jdbc.update("DELETE FROM emergency_event");
        jdbc.update("UPDATE reserve_account SET total_volume = 10.000, remaining_volume = 10.000,"
                + " updated_nanos = 0 WHERE id = 1");
    }

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, String role, String actor,
                               int expectedStatus) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request = request.header("X-Role-Id", role);
        }
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String role, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, role, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-11-01T00:00:00Z");
        body.put("endUtc", "2026-11-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null, null).get("id").asLong();
    }

    private String createEvent(String title) throws Exception {
        String eventKey = key("ev");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ec"));
        body.put("eventKey", eventKey);
        body.put("title", title);
        JsonNode node = postOk("/api/emergency/events", body, null, null);
        assertEquals("VALID", node.get("status").asText());
        return eventKey;
    }

    private Map<String, Object> activationBody(String commandKey, String requestId, String eventKey,
                                               long windowId, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestId", requestId);
        body.put("eventKey", eventKey);
        body.put("windowId", windowId);
        body.put("amount", amount);
        return body;
    }

    private int confirmStatus(String commandKey, String requestId, String eventKey, long windowId,
                              String amount, String role, String actor) {
        try {
            emergencyService.confirmActivation(commandKey, requestId, eventKey, windowId, amount, role, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode reserve() throws Exception {
        return getOk("/api/emergency/reserve");
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    // ------------------------------------------------------------------
    // 主流程：题干场景 —— 储备 10，事件 E 经甲乙确认启用 6 至 W，储备剩 4；归还 7 被拒
    // ------------------------------------------------------------------

    @Test
    void twoRolesActivateSixAndOverReturnIsRejected() throws Exception {
        long windowId = createWindow("ch-main-" + run, "10");
        String eventKey = createEvent("干渠决口 E-" + run);

        String requestId = key("req");
        // 首位确认（甲，调度角色）：PENDING，储备不动
        JsonNode first = postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventKey, windowId, "6"), "DISPATCH", "jia");
        assertEquals("PENDING", first.get("status").asText());
        assertEquals("0", first.get("outstandingAmount").asText());
        assertEquals(1, first.get("confirmations").size());
        assertEquals("10", reserve().get("remainingVolume").asText());

        // 第二位确认（乙，审计角色）：ACTIVE，扣储备 10 -> 4
        JsonNode activated = postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventKey, windowId, "6"), "AUDIT", "yi");
        assertEquals("ACTIVE", activated.get("status").asText());
        assertEquals("6", activated.get("amount").asText());
        assertEquals("6", activated.get("outstandingAmount").asText());
        assertEquals("4", activated.get("reserveRemaining").asText());
        assertEquals(2, activated.get("confirmations").size());
        assertNotNull(activated.get("activatedUtc"));

        JsonNode reserve = reserve();
        assertEquals("10", reserve.get("totalVolume").asText());
        assertEquals("4", reserve.get("remainingVolume").asText());
        assertEquals("6", reserve.get("activatedVolume").asText());
        assertEquals("0", reserve.get("writtenOffVolume").asText());
        assertEquals("0", reserve.get("returnedVolume").asText());
        assertEquals("6", reserve.get("outstandingVolume").asText());

        // 目标窗口预占 6
        JsonNode windowEmergency = getOk("/api/emergency/windows/" + windowId);
        assertEquals("6", windowEmergency.get("outstandingVolume").asText());
        assertEquals(1, windowEmergency.get("requests").size());

        // 窗口容量：普通排班不显示储备总量，但剩余容量计入紧急预占
        JsonNode cap = capacity(windowId);
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("6", cap.get("emergencyOutstandingTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 归还 7（超过已启用未核销 6）-> 422，两个账本不变
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rc"), "amount", "7"), null, "jia", 422);
        JsonNode reserveAfter = reserve();
        assertEquals("4", reserveAfter.get("remainingVolume").asText());
        assertEquals("0", reserveAfter.get("returnedVolume").asText());
        JsonNode requestAfter = getOk("/api/emergency/activations/" + requestId);
        assertEquals("6", requestAfter.get("outstandingAmount").asText());
        assertEquals("ACTIVE", requestAfter.get("status").asText());
        // 归还流水为空
        assertEquals(1, getOk("/api/emergency/activations/" + requestId + "/flows").get("flows").size());

        // 核销 4 + 归还 2 结清：储备回补 2 -> 6
        postOk("/api/emergency/activations/" + requestId + "/writeoff",
                Map.of("commandKey", key("wc2"), "amount", "4"), null, "yi");
        JsonNode returned = postOk("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rc2"), "amount", "2"), null, "yi");
        assertEquals("SETTLED", returned.get("status").asText());
        assertEquals("0", returned.get("outstandingAmount").asText());
        assertEquals("6", reserve().get("remainingVolume").asText());
        assertEquals("4", reserve().get("writtenOffVolume").asText());
        assertEquals("2", reserve().get("returnedVolume").asText());
        // 结清后任何核销/归还均 422
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rc3"), "amount", "0.001"), null, "yi", 422);
        postJson("/api/emergency/activations/" + requestId + "/writeoff",
                Map.of("commandKey", key("wc3"), "amount", "0.001"), null, "yi", 422);

        // 流水：启用、核销、归还各一笔
        JsonNode flows = getOk("/api/emergency/activations/" + requestId + "/flows");
        assertEquals(3, flows.get("flows").size());
        assertEquals("ACTIVATION", flows.get("flows").get(0).get("flowType").asText());
        assertEquals("WRITEOFF", flows.get("flows").get(1).get("flowType").asText());
        assertEquals("RETURN", flows.get("flows").get(2).get("flowType").asText());
        // 历史事件保留事件与两位确认人
        JsonNode detail = getOk("/api/emergency/events/" + eventKey);
        assertEquals(eventKey, detail.get("event").get("eventKey").asText());
        JsonNode historyRequest = detail.get("requests").get(0);
        assertEquals(requestId, historyRequest.get("requestId").asText());
        assertEquals(2, historyRequest.get("confirmations").size());
        assertEquals("jia", historyRequest.get("confirmations").get(0).get("confirmer").asText());
        assertEquals("yi", historyRequest.get("confirmations").get(1).get("confirmer").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支：非紧急事件、储备不足、重复角色/确认人 —— 均不动任何账本
    // ------------------------------------------------------------------

    @Test
    void invalidEventInsufficientReserveAndDuplicateRolesReturn422() throws Exception {
        long windowId = createWindow("ch-422-" + run, "100");

        // 非紧急（作废）事件不能启用
        String invalidEvent = createEvent("误报事件");
        postOk("/api/emergency/events/" + invalidEvent + "/invalidate",
                Map.of("commandKey", key("ic")), null, null);
        String badRequest = key("req");
        postJson("/api/emergency/activations",
                activationBody(key("xc"), badRequest, invalidEvent, windowId, "1"), "DISPATCH", "jia",
                422);
        mvc.perform(get("/api/emergency/activations/" + badRequest)).andExpect(status().isNotFound());

        // 储备 10：先启用 7（事件 A），再尝试启用 4（事件 B）储备不足
        String eventA = createEvent("事件A");
        String reqA = key("req");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqA, eventA, windowId, "7"), "DISPATCH", "jia");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqA, eventA, windowId, "7"), "AUDIT", "yi");
        assertEquals("3", reserve().get("remainingVolume").asText());

        String eventB = createEvent("事件B");
        String reqB = key("req");
        // 首位确认成功（PENDING），第二位时储备不足
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqB, eventB, windowId, "4"), "DISPATCH", "bing");
        postJson("/api/emergency/activations",
                activationBody(key("xc"), reqB, eventB, windowId, "4"), "AUDIT", "ding", 422);
        // 储备与窗口预占不变；reqB 保持 PENDING 且无启用流水
        assertEquals("3", reserve().get("remainingVolume").asText());
        JsonNode pendingB = getOk("/api/emergency/activations/" + reqB);
        assertEquals("PENDING", pendingB.get("status").asText());
        assertEquals("0", pendingB.get("outstandingAmount").asText());
        assertEquals(0, getOk("/api/emergency/activations/" + reqB + "/flows").get("flows").size());
        // 储备补足后（归还 reqA 4）第二位确认可成功
        postOk("/api/emergency/activations/" + reqA + "/return",
                Map.of("commandKey", key("rc"), "amount", "4"), null, "jia");
        assertEquals("7", reserve().get("remainingVolume").asText());
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqB, eventB, windowId, "4"), "AUDIT", "ding");
        assertEquals("3", reserve().get("remainingVolume").asText());

        // 重复角色 / 重复确认人 -> 422
        String eventC = createEvent("事件C");
        String reqC = key("req");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqC, eventC, windowId, "1"), "DISPATCH", "jia");
        postJson("/api/emergency/activations",
                activationBody(key("xc"), reqC, eventC, windowId, "1"), "DISPATCH", "other", 422);
        postJson("/api/emergency/activations",
                activationBody(key("xc"), reqC, eventC, windowId, "1"), "AUDIT", "jia", 422);
        // 不同角色不同确认人 -> 成功
        postOk("/api/emergency/activations",
                activationBody(key("xc"), reqC, eventC, windowId, "1"), "AUDIT", "yi");
    }

    @Test
    void windowCapacityBlocksEmergencyAndNormalAllocationFromBothSides() throws Exception {
        // 窗口容量只有 5：紧急启用 6 -> 422
        long smallWindow = createWindow("ch-small-" + run, "5");
        String eventKey = createEvent("小窗口事件");
        String req = key("req");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), req, eventKey, smallWindow, "6"), "DISPATCH", "jia");
        postJson("/api/emergency/activations",
                activationBody(key("xc"), req, eventKey, smallWindow, "6"), "AUDIT", "yi", 422);
        assertEquals("10", reserve().get("remainingVolume").asText());

        // 紧急启用 3 后普通批准 3 超过剩余 2 -> 422；普通容量视图含紧急预占
        long windowId = createWindow("ch-side-" + run, "5");
        String event2 = createEvent("占用事件");
        String req2 = key("req");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), req2, event2, windowId, "3"), "DISPATCH", "jia");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), req2, event2, windowId, "3"), "AUDIT", "yi");

        Map<String, Object> allocation = new HashMap<>();
        allocation.put("commandKey", key("ac"));
        allocation.put("allocationKey", key("ak"));
        allocation.put("windowId", windowId);
        allocation.put("userId", "u1");
        allocation.put("amount", "3");
        postOk("/api/allocations", allocation, null, "bob");
        postJson("/api/allocations/" + allocation.get("allocationKey") + "/approve",
                Map.of("commandKey", key("ap")), null, null, 422);
        // 2 可以批准：普通 2 + 紧急 3 = 5
        Map<String, Object> allocation2 = new HashMap<>();
        allocation2.put("commandKey", key("ac2"));
        allocation2.put("allocationKey", key("ak"));
        allocation2.put("windowId", windowId);
        allocation2.put("userId", "u2");
        allocation2.put("amount", "2");
        postOk("/api/allocations", allocation2, null, "carol");
        postOk("/api/allocations/" + allocation2.get("allocationKey") + "/approve",
                Map.of("commandKey", key("ap2")), null, null);
        JsonNode cap = capacity(windowId);
        assertEquals("2", cap.get("approvedTotal").asText());
        assertEquals("3", cap.get("emergencyOutstandingTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
        // 紧急归还 1 释放窗口预占并回补储备
        postOk("/api/emergency/activations/" + req2 + "/return",
                Map.of("commandKey", key("rc"), "amount", "1"), null, "jia");
        assertEquals("1", capacity(windowId).get("remaining").asText());
    }

    // ------------------------------------------------------------------
    // 409：同一 requestId 换事件或窗口；第三人复用已启用 requestId
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstTransferButChangingEventOrWindowReturns409() throws Exception {
        long w1 = createWindow("ch-r1-" + run, "10");
        long w2 = createWindow("ch-r2-" + run, "10");
        String eventA = createEvent("事件A409");
        String eventB = createEvent("事件B409");
        String requestId = key("req");
        String firstCommand = key("xc");
        Map<String, Object> bodyA = activationBody(firstCommand, requestId, eventA, w1, "6");

        JsonNode first = postOk("/api/emergency/activations", bodyA, "DISPATCH", "jia");
        assertEquals("PENDING", first.get("status").asText());
        // PENDING 阶段同 commandKey 同参重放 -> 返回首次 PENDING 结果（幂等框架，不新增确认记录）
        JsonNode replayPending = postOk("/api/emergency/activations", bodyA, "DISPATCH", "jia");
        assertEquals("PENDING", replayPending.get("status").asText());
        assertEquals(1, replayPending.get("confirmations").size());

        // PENDING 阶段换窗口/事件/数量 -> 409
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w2, "6"), "DISPATCH", "jia", 409);
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventB, w1, "6"), "DISPATCH", "jia", 409);
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w1, "5"), "DISPATCH", "jia", 409);

        // 第二位确认必须使用新的 commandKey
        JsonNode activated = postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w1, "6"), "AUDIT", "yi");
        assertEquals("6", activated.get("amount").asText());
        // 启用后两位确认人各自带新 commandKey 重放：返回首次转入数量，不新增流水、不二次扣减
        JsonNode replay1 = postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w1, "6"), "AUDIT", "yi");
        JsonNode replay2 = postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w1, "6"), "DISPATCH", "jia");
        assertEquals("6", replay1.get("outstandingAmount").asText());
        assertEquals("6", replay2.get("outstandingAmount").asText());
        assertEquals("4", reserve().get("remainingVolume").asText());
        assertEquals(1, getOk("/api/emergency/activations/" + requestId + "/flows").get("flows").size());

        // 第三人复用同一 requestId -> 409
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w1, "6"), "SAFETY", "bing", 409);
        // 启用后换事件/窗口 -> 409
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventB, w1, "6"), "AUDIT", "yi", 409);
        postJson("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventA, w2, "6"), "AUDIT", "yi", 409);

        // commandKey 幂等：同键同参直接重放首次响应
        Map<String, Object> idemBody = activationBody(key("xc"), requestId, eventA, w1, "6");
        JsonNode a = postOk("/api/emergency/activations", idemBody, "AUDIT", "yi");
        JsonNode b = postOk("/api/emergency/activations", idemBody, "AUDIT", "yi");
        assertEquals(a, b);
        // 旧 commandKey 改角色重放 -> 409
        postJson("/api/emergency/activations",
                activationBody(firstCommand, requestId, eventA, w1, "6"), "AUDIT", "yi", 409);
    }

    @Test
    void notFoundAndPendingSettlementAreRejected() throws Exception {
        long windowId = createWindow("ch-nf-" + run, "10");
        mvc.perform(get("/api/emergency/activations/missing-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/emergency/events/missing-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/emergency/windows/" + windowId + "/flows")).andExpect(status().isOk());
        mvc.perform(get("/api/emergency/windows/999999999")).andExpect(status().isNotFound());

        String eventKey = createEvent("待确认事件");
        String requestId = key("req");
        postOk("/api/emergency/activations",
                activationBody(key("xc"), requestId, eventKey, windowId, "2"), "DISPATCH", "jia");
        // PENDING 不能核销/归还 -> 409，储备不动
        postJson("/api/emergency/activations/" + requestId + "/writeoff",
                Map.of("commandKey", key("wc2"), "amount", "1"), null, "jia", 409);
        postJson("/api/emergency/activations/" + requestId + "/return",
                Map.of("commandKey", key("rc"), "amount", "1"), null, "jia", 409);
        assertEquals("10", reserve().get("remainingVolume").asText());

        // 非法参数 -> 400
        postJson("/api/emergency/activations",
                activationBody(key("xc"), key("req"), eventKey, windowId, "0"), "AUDIT", "yi", 400);
        postJson("/api/emergency/activations",
                activationBody(key("xc"), key("req"), eventKey, windowId, "1"), "bad role!", "yi", 400);
    }

    // ------------------------------------------------------------------
    // 并发：紧急启用与普通分配并发，两本账及窗口占用共同守恒
    // ------------------------------------------------------------------

    @Test
    void concurrentEmergencyActivationAndNormalApproveKeepConservation() throws Exception {
        long windowId = createWindow("ch-race-em-" + run, "10");
        String eventKey = createEvent("并发事件");
        // 先提交两笔普通申请各 4，并由首位确认创建 PENDING 紧急启用 6；窗口总容量 10
        String alloc1 = submitAllocation(windowId, "u1", "4", "bob");
        String alloc2 = submitAllocation(windowId, "u2", "4", "carol");
        String requestId = key("req");
        emergencyService.confirmActivation(key("xc"), requestId, eventKey, windowId, "6", "DISPATCH", "jia");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> emergencyFuture = pool.submit(() -> {
            gate.await();
            return confirmStatus(key("xc"), requestId, eventKey, windowId, "6", "AUDIT", "yi");
        });
        Future<Integer> approve1Future = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(key("ap"), alloc1);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> approve2Future = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(key("ap"), alloc2);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int emergencyStatus = emergencyFuture.get(30, TimeUnit.SECONDS);
        int approve1Status = approve1Future.get(30, TimeUnit.SECONDS);
        int approve2Status = approve2Future.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 串行裁决只有两类结局：
        //  紧急在两笔普通之前或之间提交成功（6+4=10）：紧急 200 且恰好一笔普通成功；
        //  两笔普通先都成功（8）：紧急 6 将使占用达 14，422 且储备不动。
        int normalOk = (approve1Status == 200 ? 1 : 0) + (approve2Status == 200 ? 1 : 0);
        if (emergencyStatus == 200) {
            assertEquals(1, normalOk, "a1=" + approve1Status + " a2=" + approve2Status);
            assertEquals("4", reserve().get("remainingVolume").asText());
            assertEquals("6", capacity(windowId).get("emergencyOutstandingTotal").asText());
        } else {
            assertEquals(422, emergencyStatus);
            assertEquals(2, normalOk);
            assertEquals("10", reserve().get("remainingVolume").asText());
            assertEquals("0", capacity(windowId).get("emergencyOutstandingTotal").asText());
        }
        // 共同守恒：普通已批准 + 紧急未结清 <= 可用总量 10；储备账实存量 = 10 - 紧急未结清
        JsonNode cap = capacity(windowId);
        double occupied = Double.parseDouble(cap.get("approvedTotal").asText())
                + Double.parseDouble(cap.get("emergencyOutstandingTotal").asText());
        assertTrue(occupied <= 10.0001, "occupied=" + occupied);
        double reserveRemaining = Double.parseDouble(reserve().get("remainingVolume").asText());
        double emergencyOutstanding = Double.parseDouble(cap.get("emergencyOutstandingTotal").asText());
        assertEquals(10.0, reserveRemaining + emergencyOutstanding, 0.0001);
    }

    @Test
    void concurrentSecondConfirmationsActivateExactlyOnce() throws Exception {
        long windowId = createWindow("ch-race-confirm-" + run, "10");
        String eventKey = createEvent("重复确认并发事件");
        String requestId = key("req");
        emergencyService.confirmActivation(key("xc"), requestId, eventKey, windowId, "6", "DISPATCH", "jia");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 乙与丙几乎同时做第二位确认（不同角色不同人），只允许一个成功
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return confirmStatus(key("xc"), requestId, eventKey, windowId, "6", "AUDIT", "yi");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return confirmStatus(key("xc"), requestId, eventKey, windowId, "6", "SAFETY", "bing");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        // 胜方 200，负方 409（请求已被另一人启用）
        int conflict = (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0);
        assertEquals(1, conflict);
        assertEquals("4", reserve().get("remainingVolume").asText());
        assertEquals(2, getOk("/api/emergency/activations/" + requestId).get("confirmations").size());
        assertEquals(1, getOk("/api/emergency/activations/" + requestId + "/flows").get("flows").size());
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        JsonNode node = postOk("/api/allocations", body, null, actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }
}
