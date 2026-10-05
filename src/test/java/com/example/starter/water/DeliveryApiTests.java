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
import java.time.temporal.ChronoUnit;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠段在途交付 API 测试：主流程（合成发出 10、旅时 5 分钟、到时确认收到 8）、
 * 失败分支、幂等重放与联合判定。使用可控时钟与 H2 内存库（MODE=MySQL）真实事务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryApiTests {

    /** 可控时钟：测试显式推进，验证到水时刻前后确认行为。 */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceMinutes(long minutes) {
            instant = instant.plus(minutes, ChronoUnit.MINUTES);
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
    private WaterService waterService;

    @Autowired
    private WaterRepository repository;

    @Autowired
    private MutableClock clock;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private synchronized String key(String prefix) {
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
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString());
    }

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-d-" + run + "-" + seq);
        body.put("startUtc", "2026-10-20T00:00:00Z");
        body.put("endUtc", "2026-10-21T00:00:00Z");
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
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    private long allocationVersion(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node.get("version").asLong();
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private Map<String, Object> deliveryBody(String requestId, String deliveryKey, String allocationKey,
                                             String volume, String dispatchUtc, int transitMinutes,
                                             long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("deliveryKey", deliveryKey);
        body.put("allocationKey", allocationKey);
        body.put("volume", volume);
        body.put("dispatchUtc", dispatchUtc);
        body.put("transitMinutes", transitMinutes);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode summary(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/deliveries");
    }

    // ------------------------------------------------------------------
    // 主流程：题干合成场景
    // ------------------------------------------------------------------

    @Test
    void syntheticScenarioDispatch10Confirm8Lost2() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");

        // 发出 10 立方米、旅时 5 分钟；发出时刻 = 当前时钟，到水 = +5 分钟
        String dispatchUtc = clock.instant().toString();
        JsonNode delivery = postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "10", dispatchUtc, 5, allocationVersion(windowId, allocation)), "alice");
        String deliveryKey = delivery.get("deliveryKey").asText();
        assertEquals("IN_TRANSIT", delivery.get("status").asText());
        assertEquals("10", delivery.get("volume").asText());
        assertEquals(5, delivery.get("transitMinutes").asInt());
        assertEquals(clock.instant().plus(5, ChronoUnit.MINUTES).toString(),
                delivery.get("arrivalUtc").asText());
        assertTrue(delivery.get("receivedVolume").isNull());
        assertTrue(delivery.get("lostVolume").isNull());

        // 在途：批准 10、未发 0、在途 10、收到 0、损失 0
        JsonNode s1 = summary(allocation);
        assertEquals("10", s1.get("approved").asText());
        assertEquals("10", s1.get("dispatched").asText());
        assertEquals("0", s1.get("undispatched").asText());
        assertEquals("10", s1.get("inTransit").asText());
        assertEquals("0", s1.get("received").asText());
        assertEquals("0", s1.get("lost").asText());

        // 提前确认 -> 409
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "8", "expectedVersion", 0), "alice", 409);

        // 时钟推进 5 分钟到达到水时刻，确认收到 8 -> RECEIVED，损失 2
        clock.advanceMinutes(5);
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "8", "expectedVersion", 0), "alice");
        assertEquals("RECEIVED", confirmed.get("status").asText());
        assertEquals("8", confirmed.get("receivedVolume").asText());
        assertEquals("2", confirmed.get("lostVolume").asText());
        assertNotNull(confirmed.get("confirmedUtc"));

        // 在途 0、收到 8、损失 2；未发量不因损失返还（仍为 0，批准量仍 10）
        JsonNode s2 = summary(allocation);
        assertEquals("10", s2.get("approved").asText());
        assertEquals("10", s2.get("dispatched").asText());
        assertEquals("0", s2.get("undispatched").asText());
        assertEquals("0", s2.get("inTransit").asText());
        assertEquals("8", s2.get("received").asText());
        assertEquals("2", s2.get("lost").asText());
        // 恒等式：发出 = 在途 + 收到 + 损失
        assertEquals(10.0, s2.get("inTransit").asDouble() + s2.get("received").asDouble()
                + s2.get("lost").asDouble(), 1e-9);
    }

    @Test
    void receivedZeroMarksLostAndDoesNotRefundQuota() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "6", "alice");
        String deliveryKey = postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "4", clock.instant().toString(), 10, allocationVersion(windowId, allocation)), "alice")
                .get("deliveryKey").asText();
        clock.advanceMinutes(10);
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "0", "expectedVersion", 0), "alice");
        assertEquals("LOST", confirmed.get("status").asText());
        assertEquals("0", confirmed.get("receivedVolume").asText());
        assertEquals("4", confirmed.get("lostVolume").asText());
        JsonNode s = summary(allocation);
        assertEquals("6", s.get("approved").asText());
        assertEquals("4", s.get("lost").asText());
        // 损失不返还：未发量仍为 2，不是 6
        assertEquals("2", s.get("undispatched").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void dispatchFailuresAreRejected() throws Exception {
        long windowId = createWindow("10");
        String requestedOnly;
        {
            String allocationKey = key("ak");
            Map<String, Object> body = new HashMap<>();
            body.put("commandKey", key("ac"));
            body.put("allocationKey", allocationKey);
            body.put("windowId", windowId);
            body.put("userId", "user-0");
            body.put("amount", "2");
            postOk("/api/allocations", body, "alice");
            requestedOnly = allocationKey;
        }
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        long version = allocationVersion(windowId, allocation);
        String now = clock.instant().toString();

        // 申请未批准 -> 409
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), requestedOnly,
                "1", now, 5, 0), "alice", 409);
        // 申请不存在 -> 404
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), "missing-" + run,
                "1", now, 5, 0), "alice", 404);
        // 期望版本不符 -> 409
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1", now, 5, version + 9), "alice", 409);
        // 旅时越界 -> 400
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1", now, 0, version), "alice", 400);
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1", now, 121, version), "alice", 400);
        // 水量非法 -> 400
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "0", now, 5, version), "alice", 400);
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1.0001", now, 5, version), "alice", 400);
        // 缺操作人 -> 400
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1", now, 5, version), null, 400);
        // 超发：发出 4 后再发 2 > 批准 5 -> 422
        postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "4", now, 5, version), "alice");
        postJson("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "2", now, 5, allocationVersion(windowId, allocation)), "alice", 422);
        // 恰可再发 1（累计 = 批准量）
        postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "1", now, 5, allocationVersion(windowId, allocation)), "alice");
        assertEquals("5", summary(allocation).get("dispatched").asText());
    }

    @Test
    void deliveryKeyReuseWithNewRequestIdReturns409() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        String deliveryKey = key("dk");
        postOk("/api/deliveries", deliveryBody(key("rid"), deliveryKey, allocation,
                "2", clock.instant().toString(), 5, allocationVersion(windowId, allocation)), "alice");
        // deliveryKey 全局唯一：换 requestId 复用 -> 409
        postJson("/api/deliveries", deliveryBody(key("rid"), deliveryKey, allocation,
                "1", clock.instant().toString(), 5, allocationVersion(windowId, allocation)),
                "alice", 409);
        assertEquals(1, summary(allocation).get("deliveries").size());
    }

    @Test
    void confirmFailuresAreRejected() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        String deliveryKey = postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "3", clock.instant().toString(), 5, allocationVersion(windowId, allocation)), "alice")
                .get("deliveryKey").asText();
        // 确认不存在的交付单 -> 404
        postJson("/api/deliveries/missing-" + run + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "1", "expectedVersion", 0), "alice", 404);
        // 收到量非法 -> 400
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "-1", "expectedVersion", 0), "alice", 400);
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "1.0001", "expectedVersion", 0),
                "alice", 400);
        // 期望版本不符 -> 409
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "1", "expectedVersion", 7), "alice", 409);
        clock.advanceMinutes(5);
        // 收到量超过发出量 -> 400
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "3.001", "expectedVersion", 0),
                "alice", 400);
        // 正常确认后重复确认（新 requestId）-> 409，不重复记损失
        postOk("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "3", "expectedVersion", 0), "alice");
        postJson("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "3", "expectedVersion", 1), "alice", 409);
        JsonNode s = summary(allocation);
        assertEquals("3", s.get("received").asText());
        assertEquals("0", s.get("lost").asText());
        assertEquals(1, s.get("deliveries").size());
    }

    // ------------------------------------------------------------------
    // 幂等与重放
    // ------------------------------------------------------------------

    @Test
    void replayReturnsFirstResponseAndKeepsFirstStatus() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        String requestId = key("rid");
        Map<String, Object> body = deliveryBody(requestId, key("dk"), allocation,
                "3", clock.instant().toString(), 5, allocationVersion(windowId, allocation));
        JsonNode first = postOk("/api/deliveries", body, "alice");
        String deliveryKey = first.get("deliveryKey").asText();
        // 同键同参重放 -> 首次完整响应
        JsonNode replay = postOk("/api/deliveries", body, "alice");
        assertEquals(first, replay);
        // 到水并确认结单后再次重放发出命令：仍返回首次 IN_TRANSIT 响应，不重放成结单
        clock.advanceMinutes(5);
        postOk("/api/deliveries/" + deliveryKey + "/confirm",
                Map.of("requestId", key("rid"), "receivedVolume", "3", "expectedVersion", 0), "alice");
        JsonNode replayAfterSettle = postOk("/api/deliveries", body, "alice");
        assertEquals("IN_TRANSIT", replayAfterSettle.get("status").asText());
        assertEquals(first, replayAfterSettle);
        // 确认命令同键同参重放 -> 首次结单响应，不重复记损失
        JsonNode s = summary(allocation);
        assertEquals(1, s.get("deliveries").size());
        assertEquals("RECEIVED", s.get("deliveries").get(0).get("status").asText());
        assertEquals("3", s.get("received").asText());
        assertEquals("0", s.get("lost").asText());
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndFailureDoesNotOccupyKey() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        String requestId = key("rid");
        postOk("/api/deliveries", deliveryBody(requestId, key("dk"), allocation,
                "2", clock.instant().toString(), 5, allocationVersion(windowId, allocation)), "alice");
        // 同 requestId 改参 -> 409
        postJson("/api/deliveries", deliveryBody(requestId, key("dk"), allocation,
                "1", clock.instant().toString(), 5, allocationVersion(windowId, allocation)),
                "alice", 409);
        postJson("/api/deliveries", deliveryBody(requestId, key("dk"), allocation,
                "2", clock.instant().toString(), 10, allocationVersion(windowId, allocation)),
                "alice", 409);

        // 失败不占键：提前确认 409 后，同 requestId 同参在到水后可成功
        String deliveryKey = key("dk");
        postOk("/api/deliveries", deliveryBody(key("rid"), deliveryKey, allocation,
                "1", clock.instant().toString(), 5, allocationVersion(windowId, allocation)), "alice");
        String confirmRequest = key("rid");
        Map<String, Object> confirmBody = Map.of("requestId", confirmRequest,
                "receivedVolume", "1", "expectedVersion", 0);
        postJson("/api/deliveries/" + deliveryKey + "/confirm", confirmBody, "alice", 409);
        clock.advanceMinutes(5);
        JsonNode confirmed = postOk("/api/deliveries/" + deliveryKey + "/confirm", confirmBody, "alice");
        assertEquals("RECEIVED", confirmed.get("status").asText());
    }

    // ------------------------------------------------------------------
    // 与转让、取消的联合判定
    // ------------------------------------------------------------------

    @Test
    void transferAndCancelCannotReleaseDispatchedVolume() throws Exception {
        long windowId = createWindow("20");
        String source = submitAndApprove(windowId, "user-src", "10", "alice");
        // 发出 6，持有 10，转让后须至少覆盖累计发出量
        postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), source,
                "6", clock.instant().toString(), 30, allocationVersion(windowId, source)), "alice");
        // 有发出记录的申请不能取消 -> 409
        postJson("/api/allocations/" + source + "/cancel", Map.of("commandKey", key("cc")), "alice", 409);
        // 转让 5 后持有 5 < 累计发出 6 -> 422
        String target5 = key("ak");
        {
            Map<String, Object> body = new HashMap<>();
            body.put("commandKey", key("ac"));
            body.put("allocationKey", target5);
            body.put("windowId", windowId);
            body.put("userId", "user-dst");
            body.put("amount", "5");
            postOk("/api/allocations", body, "bob");
        }
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tkc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", target5);
        postJson("/api/transfers", transferBody, "alice", 422);
        // 转让 4 后持有 6 == 累计发出 6 -> 允许
        String target4 = key("ak");
        {
            Map<String, Object> body = new HashMap<>();
            body.put("commandKey", key("ac"));
            body.put("allocationKey", target4);
            body.put("windowId", windowId);
            body.put("userId", "user-dst2");
            body.put("amount", "4");
            postOk("/api/allocations", body, "carol");
        }
        transferBody.put("commandKey", key("tkc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("targetAllocationKey", target4);
        postOk("/api/transfers", transferBody, "alice");
        // 转让后源批准量 6，累计发出 6，未发 0
        JsonNode s = summary(source);
        assertEquals("6", s.get("approved").asText());
        assertEquals("6", s.get("dispatched").asText());
        assertEquals("0", s.get("undispatched").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    /** 并发发出：版本冲突时重读版本重试，直到成功或配额 422。 */
    private int dispatchWithRetry(String allocationKey, String volume) {
        for (int attempt = 0; attempt < 50; attempt++) {
            long version = repository.findAllocationByKey(allocationKey).version();
            try {
                waterService.createDelivery(key("rid"), key("dk"), allocationKey, volume,
                        clock.instant().toString(), 5, version, "alice");
                return 200;
            } catch (ApiException e) {
                if (e.status().value() == 409 && "VERSION_CONFLICT".equals(e.code())) {
                    continue;
                }
                return e.status().value();
            }
        }
        throw new IllegalStateException("dispatch retry exhausted");
    }

    @Test
    void concurrentDispatchesNeverExceedApprovedAmount() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return dispatchWithRetry(allocation, "4");
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
        // 批准 10，每笔 4：恰好 2 笔成功（8），其余 4 笔 422，绝不超发
        assertEquals(2, ok, "恰好 2 笔 4 可以放入 10");
        assertEquals(4, quota);
        JsonNode s = summary(allocation);
        assertEquals("10", s.get("approved").asText());
        assertEquals("8", s.get("dispatched").asText());
        assertEquals("2", s.get("undispatched").asText());
        assertEquals("8", s.get("inTransit").asText());
        assertEquals(2, s.get("deliveries").size());
    }

    @Test
    void concurrentConfirmsSettleOnlyOnce() throws Exception {
        long windowId = createWindow("10");
        String allocation = submitAndApprove(windowId, "user-1", "5", "alice");
        String deliveryKey = postOk("/api/deliveries", deliveryBody(key("rid"), key("dk"), allocation,
                "5", clock.instant().toString(), 5, allocationVersion(windowId, allocation)), "alice")
                .get("deliveryKey").asText();
        clock.advanceMinutes(5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String requestId = key("rid");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.confirmDelivery(requestId, deliveryKey, "3", 0L, "alice");
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

        // 恰好一次结单成功，另一次 409（版本或状态冲突），损失不重复记录
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "confirm: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode s = summary(allocation);
        assertEquals("0", s.get("inTransit").asText());
        assertEquals("3", s.get("received").asText());
        assertEquals("2", s.get("lost").asText());
        assertEquals(1, s.get("deliveries").size());
    }
}
