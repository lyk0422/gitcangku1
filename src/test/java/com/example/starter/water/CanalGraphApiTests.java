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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 有向渠网门禁 API 测试：渠网登记、路径校验、批准门禁（容量/合环）、取消释放、图修订与转让约束。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CanalGraphApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    private Map<String, Object> windowBody(String commandKey, String windowKey, String channelId,
                                           String planned) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return body;
    }

    private long createWindow(String channelId, String planned) throws Exception {
        return postOk("/api/windows", windowBody(key("wc"), key("wk"), channelId, planned), null)
                .get("id").asLong();
    }

    private Map<String, Object> canalWindowBody(String commandKey, String windowKey, String channelId,
                                                String planned, List<String> nodes, List<String[]> edges) {
        Map<String, Object> body = windowBody(commandKey, windowKey, channelId, planned);
        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges.stream()
                .map(e -> Map.of("from", e[0], "to", e[1], "capacity", e[2])).toList());
        body.put("canalGraph", graph);
        return body;
    }

    private long createCanalWindow(String channelId, String planned, List<String> nodes,
                                   List<String[]> edges) throws Exception {
        return postOk("/api/windows",
                canalWindowBody(key("wc"), key("wk"), channelId, planned, nodes, edges), null)
                .get("id").asLong();
    }

    private Map<String, Object> canalAllocationBody(String commandKey, String allocationKey, long windowId,
                                                    String userId, String amount, List<String[]> path,
                                                    String flowRate) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (path != null) {
            body.put("path", path.stream().map(s -> Map.of("from", s[0], "to", s[1])).toList());
        }
        if (flowRate != null) {
            body.put("flowRate", flowRate);
        }
        return body;
    }

    private String submitCanal(long windowId, String userId, String amount, List<String[]> path,
                               String flowRate, String actor) throws Exception {
        String allocationKey = key("ak");
        JsonNode node = postOk("/api/allocations",
                canalAllocationBody(key("ac"), allocationKey, windowId, userId, amount, path, flowRate), actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return allocationKey;
    }

    private MvcResult approve(String allocationKey, String actor, int expectedStatus) throws Exception {
        return postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), actor, expectedStatus);
    }

    private JsonNode graph(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/canal-graph");
    }

    private String edgeFlow(JsonNode graph, String from, String to) {
        for (JsonNode edge : graph.get("edges")) {
            if (from.equals(edge.get("from").asText()) && to.equals(edge.get("to").asText())) {
                return edge.get("approvedFlow").asText();
            }
        }
        throw new IllegalStateException("edge not found: " + from + "->" + to);
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
    // 渠网登记
    // ------------------------------------------------------------------

    @Test
    void canalWindowRegistrationAndGraphView() throws Exception {
        long windowId = createCanalWindow("ch-canal-" + run, "100", List.of("A", "B", "C"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "C", "10.500"},
                        new String[]{"C", "A", "10"}));
        JsonNode graph = graph(windowId);
        assertEquals(1, graph.get("version").asInt());
        assertEquals(3, graph.get("nodes").size());
        assertEquals(3, graph.get("edges").size());
        assertEquals("0", edgeFlow(graph, "A", "B"));
        for (JsonNode edge : graph.get("edges")) {
            if ("B".equals(edge.get("from").asText())) {
                assertEquals("10.5", edge.get("capacity").asText());
            }
        }
        // 窗口视图携带图版本；普通窗口为 null 且查询渠网图 404
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("window").get("graphVersion").asInt());
        long normalWindow = createWindow("ch-normal-" + run, "10");
        assertTrue(getOk("/api/windows/" + normalWindow + "/history").get("window")
                .get("graphVersion").isNull());
        mvc.perform(get("/api/windows/" + normalWindow + "/canal-graph")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/canal-graph")).andExpect(status().isNotFound());
    }

    @Test
    void invalidGraphRegistrationReturns400() throws Exception {
        // 11 个节点超限
        List<String> elevenNodes = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            elevenNodes.add("n" + i);
        }
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g1-" + run, "10",
                elevenNodes, List.of()), null, 400);
        // 21 条渠段超限
        List<String> tenNodes = elevenNodes.subList(0, 10);
        List<String[]> twentyOneEdges = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOneEdges.add(new String[]{"n" + (i % 10), "n" + ((i / 10 + 1 + i % 10) % 10), "1"});
        }
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g2-" + run, "10",
                tenNodes, twentyOneEdges), null, 400);
        // 渠段端点未登记
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g3-" + run, "10",
                List.of("A", "B"), List.<String[]>of(new String[]{"A", "Z", "1"})), null, 400);
        // 渠段重复
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g4-" + run, "10",
                List.of("A", "B"),
                List.of(new String[]{"A", "B", "1"}, new String[]{"A", "B", "2"})), null, 400);
        // 自环
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g5-" + run, "10",
                List.of("A", "B"), List.<String[]>of(new String[]{"A", "A", "1"})), null, 400);
        // 容量非法
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g6-" + run, "10",
                List.of("A", "B"), List.<String[]>of(new String[]{"A", "B", "0"})), null, 400);
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g7-" + run, "10",
                List.of("A", "B"), List.<String[]>of(new String[]{"A", "B", "1.0001"})), null, 400);
        // 节点重复
        postJson("/api/windows", canalWindowBody(key("wc"), key("wk"), "ch-g8-" + run, "10",
                List.of("A", "A"), List.of()), null, 400);
    }
    // ------------------------------------------------------------------
    // 路径与流量校验
    // ------------------------------------------------------------------

    @Test
    void pathValidationFailuresReturn400() throws Exception {
        long windowId = createCanalWindow("ch-pv-" + run, "100", List.of("A", "B", "C"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "C", "10"},
                        new String[]{"C", "A", "10"}));
        // 路径断开
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.of(new String[]{"A", "B"}, new String[]{"C", "A"}), "1"), "alice", 400);
        // 节点重复（A->B->C->A 节点序列 A,B,C,A）
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.of(new String[]{"A", "B"}, new String[]{"B", "C"}, new String[]{"C", "A"}),
                "1"), "alice", 400);
        // 渠段不在图中
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.<String[]>of(new String[]{"A", "C"}), "1"), "alice", 400);
        // 空路径
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.of(), "1"), "alice", 400);
        // 缺 flowRate / 缺 path
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.<String[]>of(new String[]{"A", "B"}), null), "alice", 400);
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                null, "1"), "alice", 400);
        // 流量非整数、零、负数
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.<String[]>of(new String[]{"A", "B"}), "1.5"), "alice", 400);
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.<String[]>of(new String[]{"A", "B"}), "0"), "alice", 400);
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                List.<String[]>of(new String[]{"A", "B"}), "-3"), "alice", 400);
        // 渠网窗口申请必须携带路径
        Map<String, Object> noPath = new HashMap<>();
        noPath.put("commandKey", key("ac"));
        noPath.put("allocationKey", key("ak"));
        noPath.put("windowId", windowId);
        noPath.put("userId", "u");
        noPath.put("amount", "1");
        postJson("/api/allocations", noPath, "alice", 400);
        // 普通窗口不适用渠网门禁
        long normalWindow = createWindow("ch-pv-normal-" + run, "10");
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), normalWindow, "u", "1",
                List.<String[]>of(new String[]{"A", "B"}), "1"), "alice", 400);
    }

    @Test
    void pathLongerThanEightSegmentsReturns400() throws Exception {
        List<String> nodes = new ArrayList<>();
        List<String[]> chain = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add("n" + i);
        }
        for (int i = 0; i < 9; i++) {
            chain.add(new String[]{"n" + i, "n" + (i + 1), "10"});
        }
        long windowId = createCanalWindow("ch-long-" + run, "100", nodes, chain);
        // 9 段合法简单路径仍超上限 -> 400
        postJson("/api/allocations", canalAllocationBody(key("ac"), key("ak"), windowId, "u", "1",
                chain, "1"), "alice", 400);
        // 8 段合法
        submitCanal(windowId, "u", "1", chain.subList(0, 8), "1", "alice");
    }

    // ------------------------------------------------------------------
    // 批准门禁：容量与合环
    // ------------------------------------------------------------------

    @Test
    void approveAccountsEdgeFlowAndRequiresActor() throws Exception {
        long windowId = createCanalWindow("ch-flow-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "5"}));
        String a1 = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "3", "alice");
        // 渠网窗口批准必须携带非空操作者
        approve(a1, null, 400);
        approve(a1, "admin", 200);
        assertEquals("3", edgeFlow(graph(windowId), "A", "B"));
        // 3 + 3 > 5 -> 422，且不占流量
        String a2 = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"A", "B"}), "3", "bob");
        MvcResult rejected = approve(a2, "admin", 422);
        assertEquals("FLOW_CAPACITY_EXCEEDED", errorCode(rejected));
        assertEquals("REQUESTED", allocation(windowId, a2).get("status").asText());
        assertEquals("3", edgeFlow(graph(windowId), "A", "B"));
        // 3 + 2 == 5 -> 200
        String a3 = submitCanal(windowId, "u3", "10", List.<String[]>of(new String[]{"A", "B"}), "2", "carol");
        approve(a3, "admin", 200);
        assertEquals("5", edgeFlow(graph(windowId), "A", "B"));
        assertEquals("20", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void cycleScenarioFromSpec() throws Exception {
        long windowId = createCanalWindow("ch-cycle-" + run, "100", List.of("A", "B", "C"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "C", "10"},
                        new String[]{"C", "A", "10"}));
        String ab = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "3", "alice");
        String bc = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"B", "C"}), "3", "bob");
        String ca = submitCanal(windowId, "u3", "10", List.<String[]>of(new String[]{"C", "A"}), "3", "carol");
        approve(ab, "admin", 200);
        approve(bc, "admin", 200);
        // C->A 单独无环，但与已批准 A->B、B->C 合成有向环 -> 409，不占任何新额度、流量
        MvcResult cycle = approve(ca, "admin", 409);
        assertEquals("PATH_CYCLE", errorCode(cycle));
        assertEquals("REQUESTED", allocation(windowId, ca).get("status").asText());
        assertEquals("0", edgeFlow(graph(windowId), "C", "A"));
        assertEquals("20", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 取消 B->C 后释放其流量贡献，再批准 C->A 成功且容量账正确
        postOk("/api/allocations/" + bc + "/cancel", Map.of("commandKey", key("cc")), "bob");
        assertEquals("0", edgeFlow(graph(windowId), "B", "C"));
        approve(ca, "admin", 200);
        JsonNode graph = graph(windowId);
        assertEquals("3", edgeFlow(graph, "C", "A"));
        assertEquals("3", edgeFlow(graph, "A", "B"));
        assertEquals("0", edgeFlow(graph, "B", "C"));
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("20", cap.get("approvedTotal").asText());
        assertEquals("80", cap.get("remaining").asText());
        // 历史冻结：已取消的 B->C 仍保留路径快照与流量申报
        JsonNode bcNode = allocation(windowId, bc);
        assertEquals("CANCELLED", bcNode.get("status").asText());
        assertEquals(1, bcNode.get("path").size());
        assertEquals("B", bcNode.get("path").get(0).get("from").asText());
        assertEquals("3", bcNode.get("flowRate").asText());
    }

    @Test
    void cancelReleasesFlowContribution() throws Exception {
        long windowId = createCanalWindow("ch-rel-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "10"}));
        String a1 = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "alice");
        approve(a1, "admin", 200);
        assertEquals("4", edgeFlow(graph(windowId), "A", "B"));
        // 4 + 9 > 10 -> 422
        String a2 = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"A", "B"}), "9", "bob");
        approve(a2, "admin", 422);
        // 取消释放贡献后 9 可批准
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        assertEquals("0", edgeFlow(graph(windowId), "A", "B"));
        approve(a2, "admin", 200);
        assertEquals("9", edgeFlow(graph(windowId), "A", "B"));
    }
    // ------------------------------------------------------------------
    // 图修订
    // ------------------------------------------------------------------

    private Map<String, Object> revisionBody(String requestId, long expectedVersion,
                                             List<String[]> updates, List<String[]> adds,
                                             List<String[]> removes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedGraphVersion", expectedVersion);
        if (updates != null) {
            body.put("updates", updates.stream()
                    .map(e -> Map.of("from", e[0], "to", e[1], "capacity", e[2])).toList());
        }
        if (adds != null) {
            body.put("add", adds.stream()
                    .map(e -> Map.of("from", e[0], "to", e[1], "capacity", e[2])).toList());
        }
        if (removes != null) {
            body.put("remove", removes.stream().map(e -> Map.of("from", e[0], "to", e[1])).toList());
        }
        return body;
    }

    private MvcResult revise(long windowId, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        return postJson("/api/windows/" + windowId + "/canal-graph/revisions", body, actor, expectedStatus);
    }

    @Test
    void graphRevisionRules() throws Exception {
        long windowId = createCanalWindow("ch-rev-" + run, "100", List.of("A", "B", "C", "D"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "C", "10"}));
        String a1 = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "alice");
        approve(a1, "admin", 200);
        // 版本不符 -> 409
        MvcResult wrongVersion = revise(windowId, revisionBody(key("rv"), 2, null, null, null), "ops", 409);
        assertEquals("GRAPH_VERSION_CONFLICT", errorCode(wrongVersion));
        // 容量调低到已批准流量之下 -> 409
        MvcResult below = revise(windowId,
                revisionBody(key("rv"), 1, List.<String[]>of(new String[]{"A", "B", "3"}), null, null), "ops", 409);
        assertEquals("CAPACITY_BELOW_APPROVED", errorCode(below));
        // 删除仍有贡献的共享边 -> 409
        MvcResult inUse = revise(windowId,
                revisionBody(key("rv"), 1, null, null, List.<String[]>of(new String[]{"A", "B"})), "ops", 409);
        assertEquals("EDGE_IN_USE", errorCode(inUse));
        // 容量调到等于已批准流量 -> 200，版本 +1
        JsonNode revised = objectMapper.readTree(revise(windowId,
                revisionBody(key("rv"), 1, List.<String[]>of(new String[]{"A", "B", "4"}), null, null), "ops", 200)
                .getResponse().getContentAsString());
        assertEquals(2, revised.get("version").asInt());
        // 新增渠段 -> 200；重复新增 -> 409；端点未登记 -> 400
        revise(windowId, revisionBody(key("rv"), 2, null, List.<String[]>of(new String[]{"C", "D", "6"}), null),
                "ops", 200);
        revise(windowId, revisionBody(key("rv"), 3, null, List.<String[]>of(new String[]{"A", "B", "1"}), null),
                "ops", 409);
        revise(windowId, revisionBody(key("rv"), 3, null, List.<String[]>of(new String[]{"A", "Z", "1"}), null),
                "ops", 400);
        // 删除无贡献渠段 -> 200
        revise(windowId, revisionBody(key("rv"), 3, null, null, List.<String[]>of(new String[]{"B", "C"})), "ops", 200);
        assertEquals(4, graph(windowId).get("version").asInt());
        // 取消释放贡献后共享边可删
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        revise(windowId, revisionBody(key("rv"), 4, null, null, List.<String[]>of(new String[]{"A", "B"})), "ops", 200);
        JsonNode graph = graph(windowId);
        assertEquals(5, graph.get("version").asInt());
        assertEquals(1, graph.get("edges").size());
        // 删除不存在的渠段 -> 404；普通窗口修订 -> 400；缺版本 -> 400；缺操作者 -> 400
        revise(windowId, revisionBody(key("rv"), 5, null, null, List.<String[]>of(new String[]{"A", "B"})), "ops", 404);
        long normalWindow = createWindow("ch-rev-normal-" + run, "10");
        revise(normalWindow, revisionBody(key("rv"), 1, null, null, null), "ops", 400);
        Map<String, Object> noVersion = new HashMap<>();
        noVersion.put("requestId", key("rv"));
        revise(windowId, noVersion, "ops", 400);
        revise(windowId, revisionBody(key("rv"), 5, null, null, null), null, 400);
    }

    @Test
    void revisionIdempotencyReplaysByValue() throws Exception {
        long windowId = createCanalWindow("ch-revidem-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "10"}));
        String requestId = key("rv");
        // 十进制按值：先以 "8.0" 修订，再以 "8" 重放同参
        JsonNode first = objectMapper.readTree(revise(windowId,
                revisionBody(requestId, 1, List.<String[]>of(new String[]{"A", "B", "8.0"}), null, null), "ops", 200)
                .getResponse().getContentAsString());
        JsonNode replay = objectMapper.readTree(revise(windowId,
                revisionBody(requestId, 1, List.<String[]>of(new String[]{"A", "B", "8"}), null, null), "ops", 200)
                .getResponse().getContentAsString());
        assertEquals(first, replay);
        // 版本只推进一次
        assertEquals(2, graph(windowId).get("version").asInt());
        // 同 requestId 改参（容量/操作者）-> 409
        revise(windowId, revisionBody(requestId, 1, List.<String[]>of(new String[]{"A", "B", "9"}), null, null),
                "ops", 409);
        revise(windowId, revisionBody(requestId, 1, List.<String[]>of(new String[]{"A", "B", "8"}), null, null),
                "other", 409);
    }

    // ------------------------------------------------------------------
    // 渠网转让约束
    // ------------------------------------------------------------------

    private Map<String, Object> transferBody(String transferKey, String sourceKey, String targetKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return body;
    }

    @Test
    void canalTransferRequiresSamePathSameFlowAndFullAmount() throws Exception {
        long windowId = createCanalWindow("ch-tx-" + run, "100", List.of("A", "B"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "A", "10"}));
        String source = submitCanal(windowId, "u-src", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "alice");
        approve(source, "admin", 200);
        String diffPath = submitCanal(windowId, "u-p", "10", List.<String[]>of(new String[]{"B", "A"}), "4", "bob");
        String diffFlow = submitCanal(windowId, "u-f", "10", List.<String[]>of(new String[]{"A", "B"}), "5", "carol");
        String partial = submitCanal(windowId, "u-t", "5", List.<String[]>of(new String[]{"A", "B"}), "4", "dave");
        // 路径不同 / 流量不同 / 部分转出 -> 409
        postJson("/api/transfers", transferBody(key("tk"), source, diffPath), "alice", 409);
        postJson("/api/transfers", transferBody(key("tk"), source, diffFlow), "alice", 409);
        postJson("/api/transfers", transferBody(key("tk"), source, partial), "alice", 409);
        // 同路径同流量且一次性转出全部持有额度 -> 200，渠段流量贡献不变
        String target = submitCanal(windowId, "u-ok", "10", List.<String[]>of(new String[]{"A", "B"}), "4", "erin");
        postOk("/api/transfers", transferBody(key("tk"), source, target), "alice");
        assertEquals("4", edgeFlow(graph(windowId), "A", "B"));
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("APPROVED", allocation(windowId, target).get("status").asText());
        assertEquals("10", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 幂等：提交与失败不占键
    // ------------------------------------------------------------------

    @Test
    void canalSubmitIdempotencyAndFailureDoesNotOccupyKey() throws Exception {
        long windowId = createCanalWindow("ch-subidem-" + run, "100", List.of("A", "B"),
                List.<String[]>of(new String[]{"A", "B", "10"}));
        // 十进制按值：amount "1.50" 与 "1.5" 视为同参重放
        String commandKey = key("ac");
        String allocationKey = key("ak");
        JsonNode first = postOk("/api/allocations", canalAllocationBody(commandKey, allocationKey,
                windowId, "u1", "1.50", List.<String[]>of(new String[]{"A", "B"}), "3"), "alice");
        JsonNode replay = postOk("/api/allocations", canalAllocationBody(commandKey, allocationKey,
                windowId, "u1", "1.5", List.<String[]>of(new String[]{"A", "B"}), "3"), "alice");
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
        // 同键改流量 -> 409
        postJson("/api/allocations", canalAllocationBody(commandKey, allocationKey, windowId, "u1", "1.5",
                List.<String[]>of(new String[]{"A", "B"}), "4"), "alice", 409);
        // 失败（渠段不在图中）不占键：同键修正参数后成功
        String failedKey = key("ac");
        postJson("/api/allocations", canalAllocationBody(failedKey, key("ak"), windowId, "u2", "1",
                List.<String[]>of(new String[]{"B", "A"}), "1"), "bob", 400);
        postOk("/api/allocations", canalAllocationBody(failedKey, key("ak"), windowId, "u2", "1",
                List.<String[]>of(new String[]{"A", "B"}), "1"), "bob");
    }

    @Test
    void failedCycleApproveDoesNotOccupyCommandKey() throws Exception {
        long windowId = createCanalWindow("ch-retry-" + run, "100", List.of("A", "B", "C"),
                List.of(new String[]{"A", "B", "10"}, new String[]{"B", "C", "10"},
                        new String[]{"C", "A", "10"}));
        String ab = submitCanal(windowId, "u1", "10", List.<String[]>of(new String[]{"A", "B"}), "3", "alice");
        String bc = submitCanal(windowId, "u2", "10", List.<String[]>of(new String[]{"B", "C"}), "3", "bob");
        String ca = submitCanal(windowId, "u3", "10", List.<String[]>of(new String[]{"C", "A"}), "3", "carol");
        approve(ab, "admin", 200);
        approve(bc, "admin", 200);
        // 合环失败不占 commandKey
        String approveKey = key("ap");
        postJson("/api/allocations/" + ca + "/approve", Map.of("commandKey", approveKey), "admin", 409);
        postOk("/api/allocations/" + bc + "/cancel", Map.of("commandKey", key("cc")), "bob");
        // 同键重试成功
        JsonNode approved = postOk("/api/allocations/" + ca + "/approve",
                Map.of("commandKey", approveKey), "admin");
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals("3", edgeFlow(graph(windowId), "C", "A"));
    }
}
