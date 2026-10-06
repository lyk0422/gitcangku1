package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
 * 简化水力模型并发测试：共享渠段上的并发批准、并发阻力修改与并发同幂等键提交，
 * 在真实 H2（MODE=MySQL）行锁与唯一约束上验证，不设 Thread.sleep，统一用门闩协调并设超时。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HydraulicConcurrencyTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private HydraulicService hydraulicService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getOk(String url) throws Exception {
        return objectMapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private long createWindow() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-hc-" + run + "-" + seq);
        body.put("startUtc", "2027-01-01T00:00:00Z");
        body.put("endUtc", "2027-01-02T00:00:00Z");
        body.put("plannedVolume", "100000");
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private void createSegment(String segmentKey, long resistance) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", key("sc"));
        body.put("segmentKey", segmentKey);
        body.put("resistance", resistance);
        postOk("/api/segments", body, "operator");
    }

    private String submitPath(long windowId, String userId, long flow, long head, String segment,
                              String actor) throws Exception {
        String allocationKey = key("pa");
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", key("pac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("flow", flow);
        body.put("startUtc", "2027-01-01T00:00:00Z");
        body.put("endUtc", "2027-01-01T01:00:00Z");
        body.put("availableHead", head);
        body.put("segments", List.of(segment));
        postOk("/api/path-allocations", body, actor);
        return allocationKey;
    }

    private int approveStatus(String commandKey, String allocationKey) {
        try {
            waterService.approveAllocation(commandKey, allocationKey);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    @Test
    void concurrentApprovalsOnSharedSegmentAdmitExactlyOne() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1);
        long windowId = createWindow();
        String a = submitPath(windowId, "u1", 6, 100, segment, "alice");
        String b = submitPath(windowId, "u2", 6, 100, segment, "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), a);
        });
        Future<Integer> fb = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), b);
        });
        gate.countDown();
        int sa = fa.get(30, TimeUnit.SECONDS);
        int sb = fb.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 合计损失 144 > 100：恰好一单批准成功，另一单整次 422
        assertEquals(1, (sa == 200 ? 1 : 0) + (sb == 200 ? 1 : 0), "approve: " + sa + "/" + sb);
        assertEquals(1, (sa == 422 ? 1 : 0) + (sb == 422 ? 1 : 0));
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("360", cap.get("approvedTotal").asText());
    }

    @Test
    void concurrentResistanceUpdatesSettleByVersion() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                hydraulicService.updateResistance(key("su"), segment, 2L, 1L, "operator");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                hydraulicService.updateResistance(key("su"), segment, 3L, 1L, "operator");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一 expectedVersion=1 并发修改：恰好一个成功，版本只前进一次
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "update: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode after = getOk("/api/segments/" + segment);
        assertEquals(2, after.get("version").asInt());
    }

    @Test
    void concurrentSameRequestIdSubmitsCreateExactlyOne() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1);
        long windowId = createWindow();
        String requestId = key("pac");
        String allocationKey = key("pa");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                hydraulicService.submitPathAllocation(requestId, allocationKey, windowId, "u1", 6L,
                        "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100L, List.of(segment),
                        "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                hydraulicService.submitPathAllocation(requestId, allocationKey, windowId, "u1", 6L,
                        "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100L, List.of(segment),
                        "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 并发同键：成功重放或提示处理中（409），绝不泄漏唯一键异常（500）
        assertTrue(s1 == 200 || s1 == 409, "s1=" + s1);
        assertTrue(s2 == 200 || s2 == 409, "s2=" + s2);
        // 恰好创建一条申请；随后同键同参调用返回首次完整响应
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
        var replay = hydraulicService.submitPathAllocation(requestId, allocationKey, windowId, "u1",
                6L, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100L, List.of(segment), "alice");
        assertEquals(allocationKey, replay.allocationKey());
        assertEquals("360", replay.amount());
    }
}
