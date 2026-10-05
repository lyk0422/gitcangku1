package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 渠段在途交付 API 测试：主流程（发出 10、旅时 5 分钟、到时确认 8 / 损失 2）、
 * 失败分支、幂等重放与并发边界。使用可控时钟替代服务端 Clock，不靠休眠等待到水时刻。
 * 全部运行在 H2 内存库（MODE=MySQL）真实事务上。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryApiTests {

    /** 可控时钟：测试可推进服务端时间以越过了到水时刻。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private WaterService waterService;

    @Autowired
    private WaterRepository repository;

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

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-21T00:00:00Z");
        body.put("endUtc", "2026-10-21T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
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
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private long versionOf(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node.get("version").asLong();
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private Map<String, Object> dispatchBody(String requestId, String deliveryKey, String allocationKey,
                                             String volume, int travelMinutes, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("deliveryKey", deliveryKey);
        body.put("allocationKey", allocationKey);
        body.put("volume", volume);
        body.put("dispatchUtc", clock.instant().toString());
        body.put("travelMinutes", travelMinutes);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode dispatchOk(String deliveryKey, String allocationKey, long windowId,
                                String volume, int travelMinutes, String actor) throws Exception {
        return postOk("/api/deliveries",
                dispatchBody(key("rid"), deliveryKey, allocationKey, volume, travelMinutes,
                        versionOf(windowId, allocationKey)), actor);
    }

    private Map<String, Object> confirmBody(String requestId, String receivedVolume, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("receivedVolume", receivedVolume);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode summary(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/deliveries");
    }

    // ------------------------------------------------------------------
    // 主流程（题干合成场景）
    // ------------------------------------------------------------------

    @Test
    void dispatchTenConfirmEightLossTwo() throws Exception {
        long windowId = createWindow("ch-d-" + run, "100");
        String allocation = submitAndApprove(windowId, "user-1", "50", "alice");

        // 发出 10 立方米、旅时 5 分钟 -> IN_TRANSIT，到水时刻 = 发出 + 5 分钟
        JsonNode delivery = dispatchOk(key("dk"), allocation, windowId, "10", 5, "alice");
        String deliveryKey = delivery.get("deliveryKey").asText();
        assertEquals("IN_TRANSIT", delivery.get("status").asText());
        assertEquals("10", delivery.get("volume").asText());
        assertEquals(5, delivery.get("travelMinutes").asInt());
        assertEquals(clock.instant().plusSeconds(300).toString(), delivery.get("arrivalUtc").asText());
        assertTrue(delivery.get("receivedVolume").isNull());
        assertTrue(delivery.get("lostVolume").isNull());

        // 提前确认 -> 409
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "8", 0),
                "alice", 409);

        // 服务端时钟推进 5 分钟到达到水时刻；未确认前不自动假定全量收到
        clock.advanceSeconds(300);
        JsonNode before = summary(allocation);
        assertEquals("10", before.get("inTransit").asText());
        assertEquals("0", before.get("received").asText());

        // 到时确认收到 8 -> RECEIVED，损失 2
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(key("rid"), "8", 0), "alice");
        assertEquals("RECEIVED", confirmed.get("status").asText());
        assertEquals("8", confirmed.get("receivedVolume").asText());
        assertEquals("2", confirmed.get("lostVolume").asText());
        assertEquals(1, confirmed.get("version").asLong());

        // 汇总：批准 50、发出 10、未发 40、在途 0、收到 8、损失 2；损失不返还未发量
        JsonNode sum = summary(allocation);
        assertEquals("50", sum.get("approved").asText());
        assertEquals("10", sum.get("dispatched").asText());
        assertEquals("40", sum.get("undispatched").asText());
        assertEquals("0", sum.get("inTransit").asText());
        assertEquals("8", sum.get("received").asText());
        assertEquals("2", sum.get("lost").asText());
        assertEquals(1, sum.get("deliveries").size());
    }

    @Test
    void confirmZeroReceivedIsLost() throws Exception {
        long windowId = createWindow("ch-lost-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String deliveryKey = dispatchOk(key("dk"), allocation, windowId, "5", 1, "alice")
                .get("deliveryKey").asText();
        clock.advanceSeconds(60);
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(key("rid"), "0", 0), "alice");
        assertEquals("LOST", confirmed.get("status").asText());
        assertEquals("0", confirmed.get("receivedVolume").asText());
        assertEquals("5", confirmed.get("lostVolume").asText());
        JsonNode sum = summary(allocation);
        assertEquals("5", sum.get("lost").asText());
        assertEquals("5", sum.get("undispatched").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void invalidDispatchParamsReturn400() throws Exception {
        long windowId = createWindow("ch-bad-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        long version = versionOf(windowId, allocation);
        // 水量：0 / 负数 / 超过 3 位小数 / 非数字
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "0", 5, version),
                "alice", 400);
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "-1", 5, version),
                "alice", 400);
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "1.0001", 5, version),
                "alice", 400);
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "abc", 5, version),
                "alice", 400);
        // 旅时：0 / 121
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "1", 0, version),
                "alice", 400);
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "1", 121, version),
                "alice", 400);
        // 非法发出时刻
        Map<String, Object> badTime = dispatchBody(key("rid"), key("dk"), allocation, "1", 5, version);
        badTime.put("dispatchUtc", "not-a-time");
        postJson("/api/deliveries", badTime, "alice", 400);
        // 缺 expectedVersion
        Map<String, Object> noVersion = dispatchBody(key("rid"), key("dk"), allocation, "1", 5, version);
        noVersion.remove("expectedVersion");
        postJson("/api/deliveries", noVersion, "alice", 400);
        // 缺 X-Actor-Id
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), allocation, "1", 5, version),
                null, 400);
        // 全部失败后无交付单
        assertEquals(0, summary(allocation).get("deliveries").size());
    }

    @Test
    void dispatchStateAndOwnershipConflicts() throws Exception {
        long windowId = createWindow("ch-conf-" + run, "20");
        // REQUESTED 申请不能发出 -> 409
        String requested = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", requested);
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "5");
        postOk("/api/allocations", body, "alice");
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), requested, "1", 5, 0),
                "alice", 409);
        // 申请不存在 -> 404
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), "missing-" + run, "1", 5, 0),
                "alice", 404);
        // 非申请人发出 -> 409
        String approved = submitAndApprove(windowId, "user-1", "10", "alice");
        long version = versionOf(windowId, approved);
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), approved, "1", 5, version),
                "bob", 409);
        // 期望版本不一致 -> 409
        postJson("/api/deliveries", dispatchBody(key("rid"), key("dk"), approved, "1", 5, version + 9),
                "alice", 409);
        // 汇总查询申请不存在 -> 404
        mvc.perform(get("/api/allocations/missing-" + run + "/deliveries"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cumulativeDispatchCannotExceedApproved() throws Exception {
        long windowId = createWindow("ch-cap-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        dispatchOk(key("dk"), allocation, windowId, "6", 5, "alice");
        dispatchOk(key("dk"), allocation, windowId, "4", 5, "alice");
        // 累计 10 已达批准量，再发 0.001 -> 422
        postJson("/api/deliveries",
                dispatchBody(key("rid"), key("dk"), allocation, "0.001", 5,
                        versionOf(windowId, allocation)), "alice", 422);
        JsonNode sum = summary(allocation);
        assertEquals("10", sum.get("dispatched").asText());
        assertEquals("0", sum.get("undispatched").asText());
        assertEquals(2, sum.get("deliveries").size());
    }

    @Test
    void invalidConfirmParamsAndState() throws Exception {
        long windowId = createWindow("ch-cbad-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String deliveryKey = dispatchOk(key("dk"), allocation, windowId, "5", 5, "alice")
                .get("deliveryKey").asText();
        // 确认不存在的交付单 -> 404
        postJson("/api/deliveries/missing-" + run + "/confirm", confirmBody(key("rid"), "1", 0),
                "alice", 404);
        // 收到量：负数 / 超 3 位小数 -> 400
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "-1", 0),
                "alice", 400);
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "1.0001", 0),
                "alice", 400);
        clock.advanceSeconds(300);
        // 超过发出量 -> 400
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "5.001", 0),
                "alice", 400);
        // 期望版本不一致 -> 409
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "5", 7),
                "alice", 409);
        // 正常结单后重复确认（新 requestId）-> 409
        postOk("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "5", 0), "alice");
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "5", 1),
                "alice", 409);
        // 损失只记一次
        JsonNode sum = summary(allocation);
        assertEquals("5", sum.get("received").asText());
        assertEquals("0", sum.get("lost").asText());
    }

    @Test
    void deliveryKeyIsGloballyUniqueAcrossRequestIds() throws Exception {
        long windowId = createWindow("ch-dk-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String deliveryKey = key("dk");
        dispatchOk(deliveryKey, allocation, windowId, "1", 5, "alice");
        // 换 requestId 复用同一 deliveryKey -> 409
        postJson("/api/deliveries",
                dispatchBody(key("rid"), deliveryKey, allocation, "1", 5,
                        versionOf(windowId, allocation)), "alice", 409);
        assertEquals(1, summary(allocation).get("deliveries").size());
    }

    // ------------------------------------------------------------------
    // 幂等重放
    // ------------------------------------------------------------------

    @Test
    void replayReturnsFirstResponseEvenAfterSettlement() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String requestId = key("rid");
        Map<String, Object> body = dispatchBody(requestId, key("dk"), allocation, "10", 5,
                versionOf(windowId, allocation));
        JsonNode first = postOk("/api/deliveries", body, "alice");
        assertEquals("IN_TRANSIT", first.get("status").asText());
        String deliveryKey = first.get("deliveryKey").asText();

        // 到水并结单后重放发出命令：仍返回首次完整响应（IN_TRANSIT），不重放成结单
        clock.advanceSeconds(300);
        postOk("/api/deliveries/" + deliveryKey + "/confirm", confirmBody(key("rid"), "8", 0), "alice");
        JsonNode replay = postOk("/api/deliveries", body, "alice");
        assertEquals(first, replay);
        assertEquals("IN_TRANSIT", replay.get("status").asText());
        assertEquals(1, summary(allocation).get("deliveries").size());

        // 同 requestId 改参 -> 409
        Map<String, Object> altered = dispatchBody(requestId, key("dk"), allocation, "9", 5,
                versionOf(windowId, allocation));
        postJson("/api/deliveries", altered, "alice", 409);
    }

    @Test
    void confirmReplayAndFailedCommandDoesNotOccupyKey() throws Exception {
        long windowId = createWindow("ch-idem2-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String deliveryKey = dispatchOk(key("dk"), allocation, windowId, "6", 5, "alice")
                .get("deliveryKey").asText();
        clock.advanceSeconds(300);
        String confirmRequest = key("rid");
        JsonNode first = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(confirmRequest, "4", 0), "alice");
        JsonNode replay = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(confirmRequest, "4", 0), "alice");
        assertEquals(first, replay);
        // 同键改收到量 -> 409
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(confirmRequest, "3", 0), "alice", 409);
        // 损失只记一次
        JsonNode sum = summary(allocation);
        assertEquals("4", sum.get("received").asText());
        assertEquals("2", sum.get("lost").asText());

        // 失败的发出（超批准量 422）不占 requestId：同键改参后可成功
        String failRequest = key("rid");
        postJson("/api/deliveries",
                dispatchBody(failRequest, key("dk"), allocation, "100", 5,
                        versionOf(windowId, allocation)), "alice", 422);
        JsonNode ok = postOk("/api/deliveries",
                dispatchBody(failRequest, key("dk"), allocation, "4", 5,
                        versionOf(windowId, allocation)), "alice");
        assertEquals("4", ok.get("volume").asText());
        assertEquals("10", summary(allocation).get("dispatched").asText());
    }

    // ------------------------------------------------------------------
    // 与转让 / 取消的联动
    // ------------------------------------------------------------------

    @Test
    void transferCannotReleaseInTransitOrSettledAmount() throws Exception {
        long windowId = createWindow("ch-tx-" + run, "100");
        String source = submitAndApprove(windowId, "user-src", "50", "alice");
        dispatchOk(key("dk"), source, windowId, "10", 5, "alice");
        // 转让 41：转让后持有 9 < 累计发出 10 -> 422
        String target41 = submitRequested(windowId, "user-dst1", "41", "bob");
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, target41), "alice", 422);
        // 转让 40：转让后持有 10 == 累计发出 10 -> 允许
        String target40 = submitRequested(windowId, "user-dst2", "40", "carol");
        postOk("/api/transfers", transferBody(key("tkc"), key("tk"), source, target40), "alice");
        JsonNode sum = summary(source);
        assertEquals("10", sum.get("approved").asText());
        assertEquals("10", sum.get("dispatched").asText());
        assertEquals("0", sum.get("undispatched").asText());
        // 再转 1：持有 10 - 1 = 9 < 10 -> 422
        String target1 = submitRequested(windowId, "user-dst3", "1", "dave");
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, target1), "alice", 422);
    }

    @Test
    void cancelReleasesOnlyUndispatchedAndDeliveryStillConfirmable() throws Exception {
        long windowId = createWindow("ch-cc-" + run, "100");
        String allocation = submitAndApprove(windowId, "user-1", "50", "alice");
        String deliveryKey = dispatchOk(key("dk"), allocation, windowId, "10", 5, "alice")
                .get("deliveryKey").asText();
        // 取消：仅释放未发 40，在途 10 不释放
        JsonNode cancelled = postOk("/api/allocations/" + allocation + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("10", cancelled.get("heldAmount").asText());
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 在途交付单仍可到时确认
        clock.advanceSeconds(300);
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                confirmBody(key("rid"), "7", 0), "alice");
        assertEquals("RECEIVED", confirmed.get("status").asText());
        JsonNode sum = summary(allocation);
        assertEquals("10", sum.get("approved").asText());
        assertEquals("7", sum.get("received").asText());
        assertEquals("3", sum.get("lost").asText());
        assertEquals("0", sum.get("undispatched").asText());
    }

    private String submitRequested(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private Map<String, Object> transferBody(String commandKey, String transferKey,
                                             String sourceKey, String targetKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return body;
    }

    // ------------------------------------------------------------------
    // 并发边界（真实 H2 行锁与事务，协调同时发起并设超时）
    // ------------------------------------------------------------------

    /** 并发发出：版本冲突时按最新版本重试（失败不占 requestId），返回最终 HTTP 状态。 */
    private int dispatchConcurrent(String requestId, String deliveryKey, String allocationKey,
                                   String volume, String actor) {
        for (int attempt = 0; attempt < 30; attempt++) {
            long version = repository.findAllocationByKey(allocationKey).version();
            try {
                waterService.dispatchDelivery(requestId, deliveryKey, allocationKey, volume,
                        clock.instant().toString(), 5, version, actor);
                return 200;
            } catch (ApiException e) {
                if ("VERSION_CONFLICT".equals(e.code())) {
                    continue;
                }
                return e.status().value();
            }
        }
        throw new IllegalStateException("版本冲突重试次数过多");
    }

    @Test
    void concurrentDispatchesNeverExceedApproved() throws Exception {
        long windowId = createWindow("ch-cd-" + run, "20");
        // 5 个申请各批准 2，每个申请 2 个线程并发发出 2 -> 每个申请恰好一笔成功
        List<String> allocations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            allocations.add(submitAndApprove(windowId, "user-" + i, "2", "actor-" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocation : allocations) {
            for (int t = 0; t < 2; t++) {
                String requestId = key("rid");
                String deliveryKey = key("dk");
                futures.add(pool.submit(() -> {
                    gate.await();
                    return dispatchConcurrent(requestId, deliveryKey, allocation, "2",
                            "actor-" + allocations.indexOf(allocation));
                }));
            }
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
        assertEquals(5, ok, "每个申请恰好一笔发出成功");
        assertEquals(5, quota, "其余并发发出因累计超批准量被拒绝");
        for (String allocation : allocations) {
            JsonNode sum = summary(allocation);
            assertEquals("2", sum.get("dispatched").asText());
            assertEquals("0", sum.get("undispatched").asText());
        }
    }

    @Test
    void concurrentConfirmsSettleExactlyOnce() throws Exception {
        long windowId = createWindow("ch-cc2-" + run, "20");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String deliveryKey = dispatchOk(key("dk"), allocation, windowId, "6", 5, "alice")
                .get("deliveryKey").asText();
        clock.advanceSeconds(300);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String requestId = key("rid");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.confirmDelivery(requestId, deliveryKey, "4", 0L, "alice");
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
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "并发确认恰好一次结单: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        // 损失不重复记账：收到 4、损失 2 恰好一次
        JsonNode sum = summary(allocation);
        assertEquals("4", sum.get("received").asText());
        assertEquals("2", sum.get("lost").asText());
        assertEquals(1, sum.get("deliveries").size());
    }

    @Test
    void concurrentDispatchAndTransferSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-dt-" + run, "20");
        String source = submitAndApprove(windowId, "user-src", "10", "alice");
        String target = submitRequested(windowId, "user-dst", "6", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String dispatchRequest = key("rid");
        String deliveryKey = key("dk");
        // 发出 6 与转让 6 互斥：任一先提交后，另一个都会使 持有 < 累计发出 或 累计发出 > 持有
        Future<Integer> dispatchFuture = pool.submit(() -> {
            gate.await();
            return dispatchConcurrent(dispatchRequest, deliveryKey, source, "6", "alice");
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(key("tkc"), key("tk"), source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int dispatchStatus = dispatchFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue((dispatchStatus == 200) != (transferStatus == 200),
                "发出与转让恰好一个成功: dispatch=" + dispatchStatus + " transfer=" + transferStatus);
        JsonNode sum = summary(source);
        if (dispatchStatus == 200) {
            assertEquals(422, transferStatus);
            assertEquals("6", sum.get("dispatched").asText());
            assertEquals("10", sum.get("approved").asText());
        } else {
            assertEquals(422, dispatchStatus);
            assertEquals("0", sum.get("dispatched").asText());
            assertEquals("4", sum.get("approved").asText());
        }
    }
}