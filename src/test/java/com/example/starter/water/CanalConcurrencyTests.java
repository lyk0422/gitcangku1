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
 * 有向渠网门禁并发测试：批准、取消、图修订在同一窗口行锁内串行核验最终集合。
 * 真实 H2 事务与行锁，协调真实并发操作并设置超时。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CanalConcurrencyTests {

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

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createCanalWindow(String channelId, String planned, List<String> nodes,
                                   List<String[]> edges) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges.stream()
                .map(e -> Map.of("from", e[0], "to", e[1], "capacity", e[2])).toList());
        body.put("canalGraph", graph);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitCanal(long windowId, String userId, String amount, List<String[]> path,
                               String flowRate, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("path", path.stream().map(s -> Map.of("from", s[0], "to", s[1])).toList());
        body.put("flowRate", flowRate);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private int approveStatus(String commandKey, String allocationKey) {
        try {
            waterService.approveAllocation(commandKey, allocationKey, "admin");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private String edgeFlow(JsonNode graph, String from, String to) {
        for (JsonNode edge : graph.get("edges")) {
            if (from.equals(edge.get("from").asText()) && to.equals(edge.get("to").asText())) {
                return edge.get("approvedFlow").asText();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesOnSharedEdgeRespectCapacity() throws Exception {
        long windowId = createCanalWindow("ch-cedge-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "5"}));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            keys.add(submitCanal(windowId, "u" + i, "10", List.<String[]>of(new String[]{"A", "B"}), "2", "alice"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : keys) {
            String commandKey = key("ap");
            futures.add(pool.submit(() -> {
                gate.await();
                return approveStatus(commandKey, allocationKey);
            }));
        }
        gate.countDown();
        int ok = 0;
        int flowExceeded = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                flowExceeded++;
            }
        }
        pool.shutdown();
        assertEquals(2, ok, "容量 5 恰好容纳两个流量 2");
        assertEquals(2, flowExceeded);
        assertEquals("4", edgeFlow(getOk("/api/windows/" + windowId + "/canal-graph"), "A", "B"));
        assertEquals("20", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void concurrentCycleFormationIsSerialized() throws Exception {
        long windowId = createCanalWindow("ch-ccyc-" + run, "100", List.of("A", "B"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "A", "10"}));
        String ab = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "1", "alice");
        String ba = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"B", "A"}), "1", "bob");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), ab);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), ba);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // 两条反向路径各自无环，合成环：恰好一个批准成功，另一个 409
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode graph = getOk("/api/windows/" + windowId + "/canal-graph");
        int approved = ("1".equals(edgeFlow(graph, "A", "B")) ? 1 : 0)
                + ("1".equals(edgeFlow(graph, "B", "A")) ? 1 : 0);
        assertEquals(1, approved);
        assertEquals("10", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }
    @Test
    void concurrentApproveAndRevisionSettleByCommitOrder() throws Exception {
        long windowId = createCanalWindow("ch-crev-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "10"}));
        String approved = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "alice");
        assertEquals(200, approveStatus(key("ap"), approved));
        String pending = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"A", "B"}), "5", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), pending);
        });
        Future<Integer> reviseFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.reviseCanalGraph(key("rv"), windowId, 1L,
                        List.of(new com.example.starter.water.dto.Dtos.CanalEdgeSpec("A", "B", "8")),
                        List.of(), List.of(), "ops");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        int reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按提交顺序恰好一个成功：先批准则 4+5=9 使降容 8 冲突；先降容则批准 9>8 超额
        assertTrue((approveResult == 200) != (reviseResult == 200),
                "approve=" + approveResult + " revise=" + reviseResult);
        if (approveResult == 200) {
            assertEquals(409, reviseResult);
        } else {
            assertEquals(422, approveResult);
        }
        // 不变式：最终已批准流量不超过最终容量
        JsonNode graph = getOk("/api/windows/" + windowId + "/canal-graph");
        long flow = Long.parseLong(edgeFlow(graph, "A", "B"));
        String capacity = null;
        for (JsonNode edge : graph.get("edges")) {
            capacity = edge.get("capacity").asText();
        }
        assertTrue(flow <= Long.parseLong(capacity), "flow=" + flow + " capacity=" + capacity);
    }

    @Test
    void concurrentCancelAndEdgeRemovalSettleByCommitOrder() throws Exception {
        long windowId = createCanalWindow("ch-crem-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "10"}));
        String allocation = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "alice");
        assertEquals(200, approveStatus(key("ap"), allocation));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(key("cc"), allocation, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> reviseFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.reviseCanalGraph(key("rv"), windowId, 1L, List.of(), List.of(),
                        List.of(new com.example.starter.water.dto.Dtos.CanalEdgeRef("A", "B")), "ops");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);
        int reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 取消总是成功；删边仅在取消先提交（贡献已释放）时成功，否则 409 EDGE_IN_USE
        assertEquals(200, cancelResult);
        assertTrue(reviseResult == 200 || reviseResult == 409, "revise=" + reviseResult);
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("CANCELLED", history.get("allocations").get(0).get("status").asText());
        JsonNode graph = getOk("/api/windows/" + windowId + "/canal-graph");
        if (reviseResult == 200) {
            assertEquals(0, graph.get("edges").size());
            assertEquals(2, graph.get("version").asInt());
        } else {
            assertEquals(1, graph.get("edges").size());
            assertEquals("0", edgeFlow(graph, "A", "B"));
        }
    }
}
