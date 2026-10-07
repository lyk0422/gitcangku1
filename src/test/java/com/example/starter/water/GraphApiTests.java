package com.example.starter.water;

import com.example.starter.water.dto.Dtos.EdgeSpec;
import com.example.starter.water.dto.Dtos.GraphSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * 有向渠网门禁 API 测试：图登记、路径申请、边容量与全图无环批准、图修订、取消释放、
 * 转让同路径约束、幂等与并发裁决。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GraphApiTests {

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

    private Map<String, Object> edge(String from, String to, long capacity) {
        Map<String, Object> edge = new HashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("capacity", capacity);
        return edge;
    }

    private Map<String, Object> graphSpec(List<String> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private long createGraphWindow(String channelId, String planned, List<String> nodes,
                                   List<Map<String, Object>> edges) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        body.put("graph", graphSpec(nodes, edges));
        JsonNode node = postOk("/api/windows", body, null);
        assertEquals(1, node.get("graphVersion").asInt());
        return node.get("id").asLong();
    }

    private long createPlainWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        JsonNode node = postOk("/api/windows", body, null);
        assertTrue(node.get("graphVersion").isNull());
        return node.get("id").asLong();
    }

    private Map<String, Object> pathAllocationBody(String commandKey, String allocationKey, long windowId,
                                                   String userId, String amount, List<String> path, Long flow) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (path != null) {
            body.put("path", path);
        }
        if (flow != null) {
            body.put("flow", flow);
        }
        return body;
    }

    private String submitPath(long windowId, String userId, String amount, List<String> path, long flow,
                              String actor) throws Exception {
        String allocationKey = key("ak");
        JsonNode node = postOk("/api/allocations",
                pathAllocationBody(key("ac"), allocationKey, windowId, userId, amount, path, flow), actor);
        assertEquals("REQUESTED", node.get("status").asText());
        assertEquals(flow, node.get("flow").asLong());
        assertEquals(path, objectMapper.convertValue(node.get("path"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        return allocationKey;
    }

    private JsonNode approve(String allocationKey, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode cancel(String allocationKey, String actor, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/allocations/" + allocationKey + "/cancel",
                Map.of("commandKey", key("cc")), actor, expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> revisionBody(String requestId, long expectedVersion, List<String> nodes,
                                             List<Map<String, Object>> edges) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedGraphVersion", expectedVersion);
        body.put("graph", graphSpec(nodes, edges));
        return body;
    }

    private JsonNode revise(long windowId, String requestId, long expectedVersion, List<String> nodes,
                            List<Map<String, Object>> edges, String actor, int expectedStatus)
            throws Exception {
        MvcResult result = postJson("/api/windows/" + windowId + "/graph",
                revisionBody(requestId, expectedVersion, nodes, edges), actor, expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void graphWindowRegistrationAndPathApproval() throws Exception {
        long windowId = createGraphWindow("ch-g-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5), edge("C", "A", 5)));

        JsonNode graph = getOk("/api/windows/" + windowId + "/graph");
        assertEquals(1, graph.get("graphVersion").asInt());
        assertEquals(3, graph.get("nodes").size());
        assertEquals(3, graph.get("edges").size());

        String a1 = submitPath(windowId, "user-1", "10", List.of("A", "B", "C"), 2, "alice");
        approve(a1, 200);
        JsonNode cap = capacity(windowId);
        assertEquals("10", cap.get("approvedTotal").asText());
        assertEquals("90", cap.get("remaining").asText());

        // 历史冻结图版本与边全集，申请保留有序路径与流量
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("graph").get("graphVersion").asInt());
        assertEquals(3, history.get("graph").get("edges").size());
        JsonNode allocation = allocation(windowId, a1);
        assertEquals(2, allocation.get("flow").asLong());
        assertEquals("A", allocation.get("path").get(0).asText());
        assertEquals("B", allocation.get("path").get(1).asText());
        assertEquals("C", allocation.get("path").get(2).asText());
    }

    @Test
    void syntheticCycleScenarioFromSpecification() throws Exception {
        // 合成已批准 A->B、B->C 后，C->A 单独无环但加入会成环应 409；
        // 取消 B->C 后再批准 C->A 可成功且容量账正确
        long windowId = createGraphWindow("ch-cyc-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5), edge("C", "A", 5)));
        String ab = submitPath(windowId, "user-1", "5", List.of("A", "B"), 1, "alice");
        String bc = submitPath(windowId, "user-2", "5", List.of("B", "C"), 1, "bob");
        String ca = submitPath(windowId, "user-3", "5", List.of("C", "A"), 1, "carol");
        approve(ab, 200);
        approve(bc, 200);

        // C->A 单独无环，但加入已批准边并集成环 -> 409，且不占任何新额度、流量
        JsonNode rejected = approve(ca, 409);
        assertEquals("PATH_CYCLE", rejected.get("code").asText());
        assertEquals("REQUESTED", allocation(windowId, ca).get("status").asText());
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
        // C->A 上没有流量贡献：图修订删去 C->A 成功（版本 2），随后再加回（版本 3）
        revise(windowId, key("gr"), 1, List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5)), "admin", 200);
        revise(windowId, key("gr"), 2, List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5), edge("C", "A", 5)), "admin", 200);

        // 取消 B->C 释放其贡献后，C->A 可批准
        cancel(bc, "bob", 200);
        approve(ca, 200);
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());

        // 容量账：B->C 已无贡献可删去（版本 4）；A->B 仍有贡献不能删 -> 409
        revise(windowId, key("gr"), 3, List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("C", "A", 5)), "admin", 200);
        JsonNode broken = revise(windowId, key("gr"), 4, List.of("A", "B", "C"),
                List.of(edge("C", "A", 5)), "admin", 409);
        assertEquals("GRAPH_REVISION_BREAKS_APPROVED", broken.get("code").asText());
        JsonNode graph = getOk("/api/windows/" + windowId + "/graph");
        assertEquals(4, graph.get("graphVersion").asInt());
        assertEquals(2, graph.get("edges").size());
    }

    @Test
    void edgeCapacityLimitsApprovedFlowSum() throws Exception {
        long windowId = createGraphWindow("ch-cap-" + run, "100", List.of("A", "B"),
                List.of(edge("A", "B", 3)));
        String a1 = submitPath(windowId, "user-1", "5", List.of("A", "B"), 2, "alice");
        String a2 = submitPath(windowId, "user-2", "5", List.of("A", "B"), 2, "bob");
        String a3 = submitPath(windowId, "user-3", "5", List.of("A", "B"), 1, "carol");
        approve(a1, 200);
        // 2 + 2 > 3 -> 422，且不占流量
        JsonNode rejected = approve(a2, 422);
        assertEquals("EDGE_CAPACITY_EXCEEDED", rejected.get("code").asText());
        assertEquals("REQUESTED", allocation(windowId, a2).get("status").asText());
        // 2 + 1 == 3 -> 200
        approve(a3, 200);
        // 容量降至已批准流量之和以下 -> 409；等于 -> 200
        JsonNode broken = revise(windowId, key("gr"), 1, List.of("A", "B"),
                List.of(edge("A", "B", 2)), "admin", 409);
        assertEquals("GRAPH_REVISION_BREAKS_APPROVED", broken.get("code").asText());
        JsonNode revised = revise(windowId, key("gr"), 1, List.of("A", "B"),
                List.of(edge("A", "B", 3)), "admin", 200);
        assertEquals(2, revised.get("graphVersion").asInt());
    }

    @Test
    void pathAndGraphValidationFailuresReturn400() throws Exception {
        long windowId = createGraphWindow("ch-val-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5), edge("B", "A", 5)));
        // 路径断开：A->C 无渠段 -> 400
        JsonNode broken = objectMapper.readTree(postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1", List.of("A", "C"), 1L),
                "alice", 400).getResponse().getContentAsString());
        assertEquals("PATH_BROKEN", broken.get("code").asText());
        // 重复节点 -> 400
        JsonNode repeated = objectMapper.readTree(postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                        List.of("A", "B", "A"), 1L), "alice", 400).getResponse().getContentAsString());
        assertEquals("PATH_NODE_REPEATED", repeated.get("code").asText());
        // 9 条渠段（10 个节点）超出上限 -> 400
        postJson("/api/allocations", pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J"), 1L), "alice", 400);
        // 流量缺失 / 非正 -> 400
        postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1", List.of("A", "B"), null),
                "alice", 400);
        postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1", List.of("A", "B"), 0L),
                "alice", 400);
        // 渠网窗口缺少路径 -> 400
        postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), windowId, "u", "1", null, null), "alice", 400);
        // 普通窗口不适用门禁：携带路径 -> 400
        long plainWindow = createPlainWindow("ch-plain-" + run, "100");
        postJson("/api/allocations",
                pathAllocationBody(key("ac"), key("ak"), plainWindow, "u", "1", List.of("A", "B"), 1L),
                "alice", 400);
        // 普通窗口正常申请不受影响
        String plain = key("ak");
        postOk("/api/allocations",
                pathAllocationBody(key("ac"), plain, plainWindow, "u", "1", null, null), "alice");
        approve(plain, 200);
    }

    @Test
    void invalidGraphSpecsReturn400() throws Exception {
        Map<String, Object> base = new HashMap<>();
        base.put("commandKey", key("wc"));
        base.put("windowKey", key("wk"));
        base.put("channelId", "ch-gs-" + run);
        base.put("startUtc", "2026-10-01T00:00:00Z");
        base.put("endUtc", "2026-10-01T02:00:00Z");
        base.put("plannedVolume", "100");
        // 11 个节点 -> 400
        base.put("graph", graphSpec(List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"),
                List.of(edge("A", "B", 1))));
        postJson("/api/windows", base, null, 400);
        // 重复节点 -> 400
        base.put("graph", graphSpec(List.of("A", "A"), List.of(edge("A", "A", 1))));
        postJson("/api/windows", base, null, 400);
        // 自环 -> 400
        base.put("graph", graphSpec(List.of("A", "B"), List.of(edge("A", "A", 1))));
        postJson("/api/windows", base, null, 400);
        // 重复渠段 -> 400
        base.put("graph", graphSpec(List.of("A", "B"), List.of(edge("A", "B", 1), edge("A", "B", 2))));
        postJson("/api/windows", base, null, 400);
        // 容量非正 -> 400
        base.put("graph", graphSpec(List.of("A", "B"), List.of(edge("A", "B", 0))));
        postJson("/api/windows", base, null, 400);
        // 端点未登记 -> 400
        base.put("graph", graphSpec(List.of("A", "B"), List.of(edge("A", "C", 1))));
        postJson("/api/windows", base, null, 400);
        // 空渠段 -> 400
        base.put("graph", graphSpec(List.of("A", "B"), List.of()));
        postJson("/api/windows", base, null, 400);
    }

    @Test
    void graphRevisionVersionAndActorRules() throws Exception {
        long windowId = createGraphWindow("ch-rev-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5)));
        // 期望版本不一致 -> 409
        JsonNode mismatch = revise(windowId, key("gr"), 2, List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5)), "admin", 409);
        assertEquals("GRAPH_VERSION_CONFLICT", mismatch.get("code").asText());
        // 缺少操作人 -> 400；缺少期望版本 -> 400
        postJson("/api/windows/" + windowId + "/graph",
                revisionBody(key("gr"), 1, List.of("A", "B"), List.of(edge("A", "B", 5))), null, 400);
        Map<String, Object> noVersion = new HashMap<>();
        noVersion.put("requestId", key("gr"));
        noVersion.put("graph", graphSpec(List.of("A", "B"), List.of(edge("A", "B", 5))));
        postJson("/api/windows/" + windowId + "/graph", noVersion, "admin", 400);
        // 合法修订：提升容量并加边 -> 版本 2
        JsonNode revised = revise(windowId, key("gr"), 1, List.of("A", "B", "C"),
                List.of(edge("A", "B", 9), edge("B", "C", 5), edge("C", "A", 2)), "admin", 200);
        assertEquals(2, revised.get("graphVersion").asInt());
        assertEquals(3, revised.get("edges").size());
        // 普通窗口不适用修订 -> 409；查询 -> 404
        long plainWindow = createPlainWindow("ch-revp-" + run, "100");
        postJson("/api/windows/" + plainWindow + "/graph",
                revisionBody(key("gr"), 1, List.of("A", "B"), List.of(edge("A", "B", 5))), "admin", 409);
        mvc.perform(get("/api/windows/" + plainWindow + "/graph")).andExpect(status().isNotFound());
    }

    @Test
    void graphRevisionIdempotencyAndFailureDoesNotOccupyKey() throws Exception {
        long windowId = createGraphWindow("ch-idem-" + run, "100", List.of("A", "B"),
                List.of(edge("A", "B", 5)));
        String requestId = key("gr");
        Map<String, Object> body = revisionBody(requestId, 1, List.of("A", "B"),
                List.of(edge("A", "B", 8)));
        JsonNode first = postOk("/api/windows/" + windowId + "/graph", body, "admin");
        JsonNode replay = postOk("/api/windows/" + windowId + "/graph", body, "admin");
        assertEquals(first, replay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/graph").get("graphVersion").asInt());
        // 同 requestId 改参（改容量/改期望版本/改操作人）-> 409
        postJson("/api/windows/" + windowId + "/graph",
                revisionBody(requestId, 1, List.of("A", "B"), List.of(edge("A", "B", 9))), "admin", 409);
        postJson("/api/windows/" + windowId + "/graph",
                revisionBody(requestId, 2, List.of("A", "B"), List.of(edge("A", "B", 8))), "admin", 409);
        // 失败的修订不占键：版本冲突 409 后，用同一 requestId 携带正确版本成功
        String retryId = key("gr");
        revise(windowId, retryId, 99, List.of("A", "B"), List.of(edge("A", "B", 4)), "admin", 409);
        JsonNode ok = revise(windowId, retryId, 2, List.of("A", "B"), List.of(edge("A", "B", 4)),
                "admin", 200);
        assertEquals(3, ok.get("graphVersion").asInt());
    }

    @Test
    void transferOnGraphWindowRequiresSamePathAndFlow() throws Exception {
        long windowId = createGraphWindow("ch-tx-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 2), edge("B", "C", 5)));
        String source = submitPath(windowId, "user-src", "6", List.of("A", "B"), 2, "alice");
        approve(source, 200);
        // 不同路径 -> 409；不同流量 -> 409
        String otherPath = submitPath(windowId, "user-dst1", "6", List.of("B", "C"), 2, "bob");
        postJson("/api/transfers", transferBody(source, otherPath), "alice", 409);
        String otherFlow = submitPath(windowId, "user-dst2", "6", List.of("A", "B"), 1, "bob");
        postJson("/api/transfers", transferBody(source, otherFlow), "alice", 409);
        // 部分转让使源与目标同时贡献流量，2 + 2 > 容量 2 -> 422 且全部回滚
        String partial = submitPath(windowId, "user-dst3", "3", List.of("A", "B"), 2, "bob");
        JsonNode exceeded = objectMapper.readTree(postJson("/api/transfers",
                transferBody(source, partial), "alice", 422).getResponse().getContentAsString());
        assertEquals("EDGE_CAPACITY_EXCEEDED", exceeded.get("code").asText());
        assertEquals("6", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("REQUESTED", allocation(windowId, partial).get("status").asText());
        // 全额转让（源持有 6 == 目标原水量 6）：流量贡献整体转移，渠段之和不变
        String full = submitPath(windowId, "user-dst4", "6", List.of("A", "B"), 2, "bob");
        postJson("/api/transfers", transferBody(source, full), "alice", 200);
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("APPROVED", allocation(windowId, full).get("status").asText());
        // A->B 仍有目标贡献的流量：修订删去 -> 409
        revise(windowId, key("gr"), 1, List.of("A", "B", "C"), List.of(edge("B", "C", 5)),
                "admin", 409);
    }

    private Map<String, Object> transferBody(String sourceKey, String targetKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return body;
    }

    // ------------------------------------------------------------------
    // 并发裁决（真实 H2 行锁串行化）
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesFormingCycleSettleExactlyOnce() throws Exception {
        long windowId = createGraphWindow("ch-race-" + run, "100", List.of("A", "B"),
                List.of(edge("A", "B", 5), edge("B", "A", 5)));
        String ab = submitPath(windowId, "user-1", "5", List.of("A", "B"), 1, "alice");
        String ba = submitPath(windowId, "user-2", "5", List.of("B", "A"), 1, "bob");

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

        // 两条路径单独都合法，但合成环：按事务提交顺序恰好一个成功
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        assertEquals("5", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void concurrentRevisionsWithSameExpectedVersionSettleOnce() throws Exception {
        long windowId = createGraphWindow("ch-racev-" + run, "100", List.of("A", "B"),
                List.of(edge("A", "B", 5)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return reviseStatus(key("gr"), windowId, 1, 7);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return reviseStatus(key("gr"), windowId, 1, 9);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一期望版本并发修订：恰好一个成功，另一个版本冲突 409，版本只前进一次
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        JsonNode graph = getOk("/api/windows/" + windowId + "/graph");
        assertEquals(2, graph.get("graphVersion").asInt());
        long capacity = graph.get("edges").get(0).get("capacity").asLong();
        assertTrue(capacity == 7 || capacity == 9);
    }

    @Test
    void concurrentRevisionAndApproveKeepApprovedSetIntact() throws Exception {
        long windowId = createGraphWindow("ch-racea-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5)));
        String approved = submitPath(windowId, "user-1", "5", List.of("A", "B"), 2, "alice");
        approve(approved, 200);
        String pending = submitPath(windowId, "user-2", "5", List.of("B", "C"), 1, "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 修订试图删去仍有贡献的 A->B；与批准并发，二者在窗口行锁下串行核验
        Future<Integer> reviseFuture = pool.submit(() -> {
            gate.await();
            return reviseStatus(key("gr"), windowId, 1, List.of("A", "B", "C"),
                    List.of(edge("B", "C", 5)));
        });
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), pending);
        });
        gate.countDown();
        int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS);
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 修订必然失败（A->B 贡献不消失）；批准与修订串行，最终已批准集合不被破坏
        assertEquals(409, reviseStatus);
        assertEquals(200, approveStatus);
        JsonNode graph = getOk("/api/windows/" + windowId + "/graph");
        assertEquals(1, graph.get("graphVersion").asInt());
        assertEquals(2, graph.get("edges").size());
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
    }

    private int approveStatus(String commandKey, String allocationKey) {
        try {
            waterService.approveAllocation(commandKey, allocationKey);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private int reviseStatus(String requestId, long windowId, long expectedVersion, long newCapacity) {
        return reviseStatus(requestId, windowId, expectedVersion, List.of("A", "B"),
                List.of(edge("A", "B", newCapacity)));
    }

    private int reviseStatus(String requestId, long windowId, long expectedVersion, List<String> nodes,
                             List<Map<String, Object>> edges) {
        try {
            waterService.reviseGraph(requestId, windowId, expectedVersion,
                    new GraphSpec(nodes,
                            edges.stream().map(e -> new EdgeSpec((String) e.get("from"),
                                    (String) e.get("to"),
                                    ((Number) e.get("capacity")).longValue())).toList()),
                    "admin");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 失败不占键与历史冻结
    // ------------------------------------------------------------------

    @Test
    void failedCycleApproveDoesNotOccupyCommandKey() throws Exception {
        long windowId = createGraphWindow("ch-fk-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5), edge("C", "A", 5)));
        String ab = submitPath(windowId, "user-1", "5", List.of("A", "B"), 1, "alice");
        String bc = submitPath(windowId, "user-2", "5", List.of("B", "C"), 1, "bob");
        String ca = submitPath(windowId, "user-3", "5", List.of("C", "A"), 1, "carol");
        approve(ab, 200);
        approve(bc, 200);

        // 成环 409 失败不占 commandKey：取消 B->C 后用同一 commandKey 重试成功
        String commandKey = key("ap");
        postJson("/api/allocations/" + ca + "/approve", Map.of("commandKey", commandKey), null, 409);
        cancel(bc, "bob", 200);
        JsonNode ok = objectMapper.readTree(postJson("/api/allocations/" + ca + "/approve",
                Map.of("commandKey", commandKey), null, 200).getResponse().getContentAsString());
        assertEquals("APPROVED", ok.get("status").asText());
        // 同键同参重放返回首次完整结果
        JsonNode replay = objectMapper.readTree(postJson("/api/allocations/" + ca + "/approve",
                Map.of("commandKey", commandKey), null, 200).getResponse().getContentAsString());
        assertEquals(ok, replay);
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void historyFreezesPathsAndGraphRegardlessOfCancel() throws Exception {
        long windowId = createGraphWindow("ch-his-" + run, "100", List.of("A", "B", "C"),
                List.of(edge("A", "B", 5), edge("B", "C", 5)));
        String a1 = submitPath(windowId, "user-1", "5", List.of("A", "B", "C"), 2, "alice");
        approve(a1, 200);
        cancel(a1, "alice", 200);

        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        JsonNode cancelled = history.get("allocations").get(0);
        assertEquals("CANCELLED", cancelled.get("status").asText());
        // 取消后路径与流量仍冻结保留，顺序不变
        assertEquals(2, cancelled.get("flow").asLong());
        assertEquals("A", cancelled.get("path").get(0).asText());
        assertEquals("B", cancelled.get("path").get(1).asText());
        assertEquals("C", cancelled.get("path").get(2).asText());
        // 历史携带冻结的边全集与图版本
        assertEquals(1, history.get("graph").get("graphVersion").asInt());
        assertEquals(2, history.get("graph").get("edges").size());
        // 取消释放贡献：A->B、B->C 均可从图中删去
        revise(windowId, key("gr"), 1, List.of("A", "B", "C"), List.of(edge("A", "B", 5)),
                "admin", 200);
        // 普通窗口历史不携带图
        long plainWindow = createPlainWindow("ch-hisp-" + run, "100");
        JsonNode plainHistory = getOk("/api/windows/" + plainWindow + "/history");
        assertTrue(plainHistory.get("graph").isNull());
    }
}
