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

import java.math.BigDecimal;
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
 * 多水源配额矩阵守恒重平衡 API 测试：规范化预览、守恒闭环、供给上限、已核销量、
 * 整体回滚、幂等重放、并发裁决与只读证据。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RebalanceApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String run = UUID.randomUUID().toString().substring(8);
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
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode error(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // 业务辅助
    // ------------------------------------------------------------------

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", key("ch"));
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private void configureSources(long windowId, String... sourceCapPairs) throws Exception {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < sourceCapPairs.length; i += 2) {
            sources.add(Map.of("sourceId", sourceCapPairs[i], "supplyCap", sourceCapPairs[i + 1]));
        }
        postOk("/api/windows/" + windowId + "/sources",
                Map.of("commandKey", key("sc"), "sources", sources), null);
    }

    private String submit(long windowId, String userId, String amount, String sourceId) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("sourceId", sourceId);
        postOk("/api/allocations", body, "actor-" + userId);
        return allocationKey;
    }

    private void approve(String allocationKey) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
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

    private JsonNode slices(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/slices").get("slices");
    }

    private String sliceAmount(String allocationKey, String sourceId) throws Exception {
        for (JsonNode slice : slices(allocationKey)) {
            if (sourceId.equals(slice.get("sourceId").asText())) {
                return slice.get("amount").asText();
            }
        }
        return null;
    }

    private Map<String, Object> item(String allocationKey, String from, String to, String amount) {
        return Map.of("allocationKey", allocationKey, "fromSourceId", from, "toSourceId", to,
                "amount", amount);
    }

    private List<Map<String, Object>> versions(Object... pairs) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            versions.add(Map.of("allocationKey", (String) pairs[i], "version", (Long) pairs[i + 1]));
        }
        return versions;
    }

    private Map<String, Object> previewBody(List<Map<String, Object>> items,
                                            List<Map<String, Object>> versions) {
        return Map.of("items", items, "expectedVersions", versions);
    }

    private Map<String, Object> activateBody(String requestId, String rebalanceKey,
                                             List<Map<String, Object>> items,
                                             List<Map<String, Object>> versions) {
        return Map.of("requestId", requestId, "rebalanceKey", rebalanceKey, "items", items,
                "expectedVersions", versions);
    }

    private JsonNode cell(JsonNode matrix, String allocationKey, String sourceId) {
        for (JsonNode cellNode : matrix) {
            if (allocationKey.equals(cellNode.get("allocationKey").asText())
                    && sourceId.equals(cellNode.get("sourceId").asText())) {
                return cellNode;
            }
        }
        return null;
    }

    private String cellAmount(JsonNode matrix, String allocationKey, String sourceId) {
        JsonNode cellNode = cell(matrix, allocationKey, sourceId);
        return cellNode == null ? null : cellNode.get("amount").asText();
    }

    /** 准备一个窗口：planned 300，水源 s1/s2/s3 上限各 100，A 60@s1、B 40@s2 均已批准。 */
    private record Fixture(long windowId, String allocA, String allocB) {
    }

    private Fixture fixture() throws Exception {
        long windowId = createWindow("300");
        configureSources(windowId, "s1", "100", "s2", "100", "s3", "100");
        String allocA = submit(windowId, "user-1", "60", "s1");
        String allocB = submit(windowId, "user-2", "40", "s2");
        approve(allocA);
        approve(allocB);
        return new Fixture(windowId, allocA, allocB);
    }

    // ------------------------------------------------------------------
    // 预览与规范化
    // ------------------------------------------------------------------

    @Test
    void previewNormalizesDuplicatesAndComputesPostStateWithoutPersisting() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        assertEquals(1, vA);

        // 重复明细（A s1->s2 10 与 5）规范化求和为 15；预览只读
        JsonNode preview = postOk("/api/windows/" + f.windowId() + "/rebalances/preview",
                previewBody(List.of(
                        item(f.allocA(), "s1", "s2", "10"),
                        item(f.allocB(), "s2", "s1", "8"),
                        item(f.allocA(), "s1", "s2", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);

        assertEquals("PREVIEW", preview.get("status").asText());
        assertTrue(preview.get("rebalanceKey").isNull());
        JsonNode items = preview.get("normalizedItems");
        assertEquals(2, items.size());
        // 规范化排序：fromSourceId、区块、目标水源
        assertEquals(f.allocA(), items.get(0).get("allocationKey").asText());
        assertEquals("15", items.get(0).get("amount").asText());
        assertEquals(f.allocB(), items.get(1).get("allocationKey").asText());
        assertEquals("8", items.get(1).get("amount").asText());

        // 前态矩阵：A 仅在 s1 60，B 仅在 s2 40
        JsonNode before = preview.get("beforeMatrix");
        assertEquals("60", cellAmount(before, f.allocA(), "s1"));
        assertEquals("40", cellAmount(before, f.allocB(), "s2"));
        assertNull(cellAmount(before, f.allocA(), "s2"));
        // 后态矩阵：A s1=45、s2=15；B s1=8、s2=32；行总额守恒 60 / 40
        JsonNode after = preview.get("afterMatrix");
        assertEquals("45", cellAmount(after, f.allocA(), "s1"));
        assertEquals("15", cellAmount(after, f.allocA(), "s2"));
        assertEquals("8", cellAmount(after, f.allocB(), "s1"));
        assertEquals("32", cellAmount(after, f.allocB(), "s2"));
        // 上限快照：s1 前 60 后 53，s2 前 40 后 47，s3 前 0 后 0
        JsonNode caps = preview.get("caps");
        assertEquals(3, caps.size());
        assertEquals("s1", caps.get(0).get("sourceId").asText());
        assertEquals("60", caps.get(0).get("beforeTotal").asText());
        assertEquals("53", caps.get(0).get("afterTotal").asText());
        assertEquals("47", caps.get(1).get("afterTotal").asText());
        // 预览时版本为当前版本
        assertEquals(vA, preview.get("versions").get(0).get("version").asLong());

        // 预览不落库、不改状态
        assertEquals("60", sliceAmount(f.allocA(), "s1"));
        assertEquals(vA, versionOf(f.windowId(), f.allocA()));
        assertEquals(0, getOk("/api/windows/" + f.windowId() + "/rebalances").get("rebalances").size());
    }

    // ------------------------------------------------------------------
    // 守恒闭环激活与证据
    // ------------------------------------------------------------------

    @Test
    void closedLoopRebalanceConservesTotalsAndFreezesEvidence() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        String rebalanceKey = key("rb");

        // A 内部闭环 s1->s2->s3->s1，B 单向 s2->s1；闭环合法且与明细顺序无关
        JsonNode activated = postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), rebalanceKey, List.of(
                        item(f.allocA(), "s1", "s2", "10"),
                        item(f.allocA(), "s2", "s3", "5"),
                        item(f.allocA(), "s3", "s1", "3"),
                        item(f.allocB(), "s2", "s1", "8")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);

        assertEquals("ACTIVATED", activated.get("status").asText());
        assertEquals(rebalanceKey, activated.get("rebalanceKey").asText());
        assertNotNull(activated.get("createdUtc"));
        // A: s1=53, s2=5, s3=2，总额 60 守恒；B: s1=8, s2=32，总额 40 守恒
        JsonNode after = activated.get("afterMatrix");
        assertEquals("53", cellAmount(after, f.allocA(), "s1"));
        assertEquals("5", cellAmount(after, f.allocA(), "s2"));
        assertEquals("2", cellAmount(after, f.allocA(), "s3"));
        assertEquals("8", cellAmount(after, f.allocB(), "s1"));
        assertEquals("32", cellAmount(after, f.allocB(), "s2"));
        // 激活后版本递增
        assertEquals(vA + 1, versionOf(f.windowId(), f.allocA()));
        assertEquals(vB + 1, versionOf(f.windowId(), f.allocB()));
        // 数据库分片与快照一致
        assertEquals("53", sliceAmount(f.allocA(), "s1"));
        assertEquals("5", sliceAmount(f.allocA(), "s2"));
        assertEquals("2", sliceAmount(f.allocA(), "s3"));
        assertEquals("8", sliceAmount(f.allocB(), "s1"));

        // 只读证据：冻结快照与激活响应一致，矩阵按 sourceId、区块稳定排序
        JsonNode evidence = getOk("/api/windows/" + f.windowId() + "/rebalances/" + rebalanceKey);
        assertEquals(activated, evidence);
        JsonNode evidenceAfter = evidence.get("afterMatrix");
        for (int i = 1; i < evidenceAfter.size(); i++) {
            String prev = evidenceAfter.get(i - 1).get("sourceId").asText()
                    + evidenceAfter.get(i - 1).get("allocationKey").asText();
            String curr = evidenceAfter.get(i).get("sourceId").asText()
                    + evidenceAfter.get(i).get("allocationKey").asText();
            assertTrue(prev.compareTo(curr) < 0, "矩阵未按 sourceId、区块稳定排序");
        }
        assertEquals(1, getOk("/api/windows/" + f.windowId() + "/rebalances").get("rebalances").size());
    }

    // ------------------------------------------------------------------
    // 供给上限与整体回滚
    // ------------------------------------------------------------------

    @Test
    void supplyCapExceededRollsBackWholeOrderAndFreesRequestId() throws Exception {
        long windowId = createWindow("300");
        configureSources(windowId, "s1", "100", "s2", "60");
        String allocA = submit(windowId, "user-1", "60", "s1");
        String allocB = submit(windowId, "user-2", "40", "s2");
        approve(allocA);
        approve(allocB);
        long vA = versionOf(windowId, allocA);
        long vB = versionOf(windowId, allocB);

        // s2 后态 = 40 + 30 - 5 = 65 > 上限 60：整单 422，矩阵不变
        String requestId = key("rq");
        MvcResult rejected = postJson("/api/windows/" + windowId + "/rebalances",
                activateBody(requestId, key("rb"), List.of(
                        item(allocA, "s1", "s2", "30"),
                        item(allocB, "s2", "s1", "5")),
                        versions(allocA, vA, allocB, vB)), null, 422);
        assertEquals("SUPPLY_CAP_EXCEEDED", error(rejected).get("code").asText());

        // 整体回滚：两条明细都不生效，分片与版本不变，无重平衡记录
        assertEquals("60", sliceAmount(allocA, "s1"));
        assertEquals("40", sliceAmount(allocB, "s2"));
        assertNull(sliceAmount(allocA, "s2"));
        assertEquals(vA, versionOf(windowId, allocA));
        assertEquals(vB, versionOf(windowId, allocB));
        assertEquals(0, getOk("/api/windows/" + windowId + "/rebalances").get("rebalances").size());

        // 失败不占键：同一 requestId 修正参数后成功
        JsonNode activated = postOk("/api/windows/" + windowId + "/rebalances",
                activateBody(requestId, key("rb"), List.of(
                        item(allocA, "s1", "s2", "10"),
                        item(allocB, "s2", "s1", "5")),
                        versions(allocA, vA, allocB, vB)), null);
        assertEquals("ACTIVATED", activated.get("status").asText());
        assertEquals("50", sliceAmount(allocA, "s1"));
        assertEquals("10", sliceAmount(allocA, "s2"));
    }

    // ------------------------------------------------------------------
    // 已核销用水量
    // ------------------------------------------------------------------

    @Test
    void consumedWaterCannotBeMovedAway() throws Exception {
        Fixture f = fixture();
        // A 在 s1 核销 20，版本递增
        JsonNode consumed = postOk("/api/allocations/" + f.allocA() + "/consumptions",
                Map.of("commandKey", key("cs"), "sourceId", "s1", "amount", "20"), null);
        assertEquals("20", consumed.get("consumed").asText());
        assertEquals("40", consumed.get("available").asText());
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        assertEquals(2, vA);

        // 搬走 45 会使 s1 后态 15 < 已核销 20：422
        MvcResult rejected = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "45"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 422);
        assertEquals("QUOTA_EXCEEDED", error(rejected).get("code").asText());
        assertEquals("60", sliceAmount(f.allocA(), "s1"));

        // 搬走 40 后 s1 后态恰为 20（等于已核销量）：允许
        postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "40"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);
        assertEquals("20", sliceAmount(f.allocA(), "s1"));
        assertEquals("40", sliceAmount(f.allocA(), "s2"));

        // 核销量快照被冻结在证据中
        JsonNode evidence = getOk("/api/windows/" + f.windowId() + "/rebalances").get("rebalances").get(0);
        boolean foundConsumed = false;
        for (JsonNode cellNode : evidence.get("consumed")) {
            if (f.allocA().equals(cellNode.get("allocationKey").asText())
                    && "s1".equals(cellNode.get("sourceId").asText())) {
                assertEquals("20", cellNode.get("consumed").asText());
                foundConsumed = true;
            }
        }
        assertTrue(foundConsumed, "核销量快照缺失");
    }

    @Test
    void consumeValidatesStateAndIsIdempotent() throws Exception {
        Fixture f = fixture();
        // 超过分片剩余可核销量：422
        MvcResult tooMuch = postJson("/api/allocations/" + f.allocA() + "/consumptions",
                Map.of("commandKey", key("cs"), "sourceId", "s1", "amount", "61"), null, 422);
        assertEquals("QUOTA_EXCEEDED", error(tooMuch).get("code").asText());
        // 未绑定水源：422
        MvcResult unbound = postJson("/api/allocations/" + f.allocA() + "/consumptions",
                Map.of("commandKey", key("cs"), "sourceId", "s2", "amount", "1"), null, 422);
        assertEquals("SOURCE_NOT_BOUND", error(unbound).get("code").asText());
        // 未批准申请：409
        String pending = submit(f.windowId(), "user-3", "5", "s1");
        MvcResult notApproved = postJson("/api/allocations/" + pending + "/consumptions",
                Map.of("commandKey", key("cs"), "sourceId", "s1", "amount", "1"), null, 409);
        assertEquals("ALLOCATION_NOT_APPROVED", error(notApproved).get("code").asText());

        // 成功核销并幂等重放
        String commandKey = key("cs");
        Map<String, Object> body = Map.of("commandKey", commandKey, "sourceId", "s1", "amount", "7.5");
        JsonNode first = postOk("/api/allocations/" + f.allocA() + "/consumptions", body, null);
        JsonNode replay = postOk("/api/allocations/" + f.allocA() + "/consumptions", body, null);
        assertEquals(first, replay);
        assertEquals("7.5", first.get("consumed").asText());
        // 重放不重复核销
        for (JsonNode slice : slices(f.allocA())) {
            if ("s1".equals(slice.get("sourceId").asText())) {
                assertEquals("7.5", slice.get("consumed").asText());
            }
        }
    }

    // ------------------------------------------------------------------
    // 版本、适用性与窗口关闭
    // ------------------------------------------------------------------

    @Test
    void staleVersionAndApplicabilityChangeAreRejected() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());

        // 版本过期：409 VERSION_CONFLICT
        MvcResult stale = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "10"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA + 1, f.allocB(), vB)), null, 409);
        assertEquals("VERSION_CONFLICT", error(stale).get("code").asText());

        // 目标水源未配置：422 SOURCE_NOT_APPLICABLE
        MvcResult unknown = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s9", "10"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 422);
        assertEquals("SOURCE_NOT_APPLICABLE", error(unknown).get("code").asText());

        // 适用性变化：预览时 s3 适用，重配移除 s3 后激活整单失败
        postOk("/api/windows/" + f.windowId() + "/rebalances/preview",
                previewBody(List.of(
                        item(f.allocA(), "s1", "s3", "10"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);
        configureSources(f.windowId(), "s1", "100", "s2", "100");
        MvcResult notApplicable = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s3", "10"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 422);
        assertEquals("SOURCE_NOT_APPLICABLE", error(notApplicable).get("code").asText());
        // 从已移除水源搬出（腾退）仍然允许
        postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocB(), "s2", "s1", "10"),
                        item(f.allocA(), "s1", "s2", "3")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);
        assertEquals("30", sliceAmount(f.allocB(), "s2"));
    }

    @Test
    void closedWindowRejectsRebalance() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());

        JsonNode closed = postOk("/api/windows/" + f.windowId() + "/close",
                Map.of("commandKey", key("cl")), null);
        assertEquals("CLOSED", closed.get("status").asText());
        // 重复关闭：409
        postJson("/api/windows/" + f.windowId() + "/close",
                Map.of("commandKey", key("cl")), null, 409);

        List<Map<String, Object>> items = List.of(
                item(f.allocA(), "s1", "s2", "10"), item(f.allocB(), "s2", "s1", "5"));
        List<Map<String, Object>> vers = versions(f.allocA(), vA, f.allocB(), vB);
        MvcResult preview = postJson("/api/windows/" + f.windowId() + "/rebalances/preview",
                previewBody(items, vers), null, 409);
        assertEquals("WINDOW_CLOSED", error(preview).get("code").asText());
        MvcResult activate = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), items, vers), null, 409);
        assertEquals("WINDOW_CLOSED", error(activate).get("code").asText());
        // 矩阵不变
        assertEquals("60", sliceAmount(f.allocA(), "s1"));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void requestIdReplayUsesNormalizedEquivalence() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        String requestId = key("rq");
        String rebalanceKey = key("rb");

        JsonNode first = postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(requestId, rebalanceKey, List.of(
                        item(f.allocA(), "s1", "s2", "10"),
                        item(f.allocB(), "s2", "s1", "8")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);

        // 同 requestId：明细换序 + 重复拆分（规范化后等价）视为同参，重放首次快照且不重复应用
        JsonNode replay = postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(requestId, rebalanceKey, List.of(
                        item(f.allocB(), "s2", "s1", "8"),
                        item(f.allocA(), "s1", "s2", "4"),
                        item(f.allocA(), "s1", "s2", "6")),
                        versions(f.allocB(), vB, f.allocA(), vA)), null);
        assertEquals(first, replay);
        assertEquals("50", sliceAmount(f.allocA(), "s1"));
        assertEquals("10", sliceAmount(f.allocA(), "s2"));
        assertEquals(1, getOk("/api/windows/" + f.windowId() + "/rebalances").get("rebalances").size());

        // 同 requestId 异参：409
        MvcResult conflict = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(requestId, rebalanceKey, List.of(
                        item(f.allocA(), "s1", "s2", "11"),
                        item(f.allocB(), "s2", "s1", "8")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 409);
        assertEquals("COMMAND_KEY_REUSED", error(conflict).get("code").asText());

        // rebalanceKey 全局唯一：换 requestId 复用仍 409
        MvcResult reused = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), rebalanceKey, List.of(
                        item(f.allocA(), "s1", "s2", "1"),
                        item(f.allocB(), "s2", "s1", "1")),
                        versions(f.allocA(), vA + 1, f.allocB(), vB + 1)), null, 409);
        assertEquals("REBALANCE_KEY_REUSED", error(reused).get("code").asText());
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentRebalancesSerializeByCommitOrder() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        List<Map<String, Object>> items = List.of(
                item(f.allocA(), "s1", "s2", "20"), item(f.allocB(), "s2", "s1", "5"));
        List<Map<String, Object>> vers = versions(f.allocA(), vA, f.allocB(), vB);

        // 两单都基于版本 1：先提交者成功并把版本推到 2，另一单版本冲突 409
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postJson("/api/windows/" + f.windowId() + "/rebalances",
                        activateBody(key("rq"), key("rb"), items, vers), null, 200, 409)
                        .getResponse().getStatus();
            });
            Future<Integer> second = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postJson("/api/windows/" + f.windowId() + "/rebalances",
                        activateBody(key("rq"), key("rb"), items, vers), null, 200, 409)
                        .getResponse().getStatus();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int status1 = first.get(30, TimeUnit.SECONDS);
            int status2 = second.get(30, TimeUnit.SECONDS);
            assertEquals(1, (status1 == 200 ? 1 : 0) + (status2 == 200 ? 1 : 0),
                    "并发重平衡必须恰好成功一单");
            assertEquals(1, (status1 == 409 ? 1 : 0) + (status2 == 409 ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        // 只观察到完整新态：A s1=40、s2=20，版本恰为 2
        assertEquals("40", sliceAmount(f.allocA(), "s1"));
        assertEquals("20", sliceAmount(f.allocA(), "s2"));
        assertEquals(2, versionOf(f.windowId(), f.allocA()));
        assertEquals(1, getOk("/api/windows/" + f.windowId() + "/rebalances").get("rebalances").size());
    }

    @Test
    void concurrentConsumeAndRebalanceObserveConsistentState() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());

        // 核销 25 与重平衡搬出 40 并发：任一先提交，另一必失败（版本冲突或核销后余额不足）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> consume = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postJson("/api/allocations/" + f.allocA() + "/consumptions",
                        Map.of("commandKey", key("cs"), "sourceId", "s1", "amount", "25"), null, 200, 422)
                        .getResponse().getStatus();
            });
            Future<Integer> rebalance = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postJson("/api/windows/" + f.windowId() + "/rebalances",
                        activateBody(key("rq"), key("rb"), List.of(
                                item(f.allocA(), "s1", "s2", "40"),
                                item(f.allocB(), "s2", "s1", "5")),
                                versions(f.allocA(), vA, f.allocB(), vB)), null, 200, 409, 422)
                        .getResponse().getStatus();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int consumeStatus = consume.get(30, TimeUnit.SECONDS);
            int rebalanceStatus = rebalance.get(30, TimeUnit.SECONDS);
            assertEquals(1, (consumeStatus == 200 ? 1 : 0) + (rebalanceStatus == 200 ? 1 : 0),
                    "核销与重平衡并发必须恰好成功一单");
        } finally {
            pool.shutdownNow();
        }
        // 终态一致：s1 分片额度不低于已核销量，A 行总额 60 守恒
        String s1Amount = sliceAmount(f.allocA(), "s1");
        String s1Consumed = null;
        for (JsonNode slice : slices(f.allocA())) {
            if ("s1".equals(slice.get("sourceId").asText())) {
                s1Consumed = slice.get("consumed").asText();
            }
        }
        assertNotNull(s1Consumed);
        assertTrue(new BigDecimal(s1Amount).compareTo(new BigDecimal(s1Consumed)) >= 0,
                "已核销用水量不能被搬走");
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode slice : slices(f.allocA())) {
            total = total.add(new BigDecimal(slice.get("amount").asText()));
        }
        assertEquals(0, total.compareTo(new BigDecimal("60")), "区块总额度必须守恒");
    }

    // ------------------------------------------------------------------
    // 与转让协同
    // ------------------------------------------------------------------

    @Test
    void transferAfterRebalanceDrainsSlicesInOrder() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());
        // A: s1 60 -> s1=20, s2=40
        postOk("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "40"),
                        item(f.allocB(), "s2", "s1", "5")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null);
        // C 申请 25，A 转出 25：分片按 sourceId 顺序扣减，s1 先扣到 0，s2 扣 5
        String allocC = submit(f.windowId(), "user-3", "25", "s3");
        postOk("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", f.allocA(), "targetAllocationKey", allocC), "actor-user-1");
        assertEquals("0", sliceAmount(f.allocA(), "s1"));
        assertEquals("35", sliceAmount(f.allocA(), "s2"));
        assertEquals("25", sliceAmount(allocC, "s3"));
    }

    // ------------------------------------------------------------------
    // 入参校验
    // ------------------------------------------------------------------

    @Test
    void rebalanceRequestValidation() throws Exception {
        Fixture f = fixture();
        long vA = versionOf(f.windowId(), f.allocA());
        long vB = versionOf(f.windowId(), f.allocB());

        // 明细少于 2 条：400
        postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(item(f.allocA(), "s1", "s2", "1")),
                        versions(f.allocA(), vA)), null, 400);
        // 源目标相同：400
        postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s1", "1"), item(f.allocB(), "s2", "s1", "1")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 400);
        // 金额超过 3 位小数：400
        postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "0.0001"), item(f.allocB(), "s2", "s1", "1")),
                        versions(f.allocA(), vA, f.allocB(), vB)), null, 400);
        // expectedVersions 未恰好覆盖涉及区块：400
        MvcResult mismatch = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(f.allocA(), "s1", "s2", "1"), item(f.allocB(), "s2", "s1", "1")),
                        versions(f.allocA(), vA)), null, 400);
        assertEquals("VERSION_SET_MISMATCH", error(mismatch).get("code").asText());
        // 区块不存在：404
        postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item("ak-missing", "s1", "s2", "1"), item(f.allocB(), "s2", "s1", "1")),
                        versions("ak-missing", 0L, f.allocB(), vB)), null, 404);
        // 区块未批准：409
        String pending = submit(f.windowId(), "user-3", "5", "s1");
        MvcResult notApproved = postJson("/api/windows/" + f.windowId() + "/rebalances",
                activateBody(key("rq"), key("rb"), List.of(
                        item(pending, "s1", "s2", "1"), item(f.allocB(), "s2", "s1", "1")),
                        versions(pending, 0L, f.allocB(), vB)), null, 409);
        assertEquals("ALLOCATION_NOT_APPROVED", error(notApproved).get("code").asText());
    }

    @Test
    void configureSourcesValidationAndSubmitBinding() throws Exception {
        long windowId = createWindow("100");
        // 数量边界：0 个与 11 个均 400
        postJson("/api/windows/" + windowId + "/sources",
                Map.of("commandKey", key("sc"), "sources", List.of()), null, 400);
        List<Map<String, Object>> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Map.of("sourceId", "sx" + i, "supplyCap", "1"));
        }
        postJson("/api/windows/" + windowId + "/sources",
                Map.of("commandKey", key("sc"), "sources", eleven), null, 400);
        // 重复 sourceId：400
        postJson("/api/windows/" + windowId + "/sources",
                Map.of("commandKey", key("sc"), "sources",
                        List.of(Map.of("sourceId", "s1", "supplyCap", "10"),
                                Map.of("sourceId", "s1", "supplyCap", "20"))), null, 400);
        // 上限必须为正：400
        postJson("/api/windows/" + windowId + "/sources",
                Map.of("commandKey", key("sc"), "sources",
                        List.of(Map.of("sourceId", "s1", "supplyCap", "0"))), null, 400);

        // 未配置水源的窗口：提交不得携带 sourceId
        Map<String, Object> submitBody = new HashMap<>();
        submitBody.put("commandKey", key("ac"));
        submitBody.put("allocationKey", key("ak"));
        submitBody.put("windowId", windowId);
        submitBody.put("userId", "user-1");
        submitBody.put("amount", "5");
        submitBody.put("sourceId", "s1");
        MvcResult notConfigured = postJson("/api/allocations", submitBody, "actor-user-1", 400);
        assertEquals("SOURCE_NOT_CONFIGURED", error(notConfigured).get("code").asText());

        // 配置后：sourceId 必填且必须是窗口水源；GET 按 sourceId 排序
        configureSources(windowId, "s2", "50", "s1", "80");
        JsonNode sources = getOk("/api/windows/" + windowId + "/sources").get("sources");
        assertEquals("s1", sources.get(0).get("sourceId").asText());
        assertEquals("80", sources.get(0).get("supplyCap").asText());
        assertEquals("s2", sources.get(1).get("sourceId").asText());

        submitBody.put("commandKey", key("ac"));
        submitBody.put("allocationKey", key("ak"));
        submitBody.remove("sourceId");
        MvcResult required = postJson("/api/allocations", submitBody, "actor-user-1", 400);
        assertEquals("SOURCE_REQUIRED", error(required).get("code").asText());
        submitBody.put("commandKey", key("ac"));
        submitBody.put("allocationKey", key("ak"));
        submitBody.put("sourceId", "s9");
        MvcResult unknown = postJson("/api/allocations", submitBody, "actor-user-1", 400);
        assertEquals("SOURCE_UNKNOWN", error(unknown).get("code").asText());
    }

    // ------------------------------------------------------------------
    // 辅助：允许多种预期状态的 postJson
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, String actor,
                               int... acceptedStatuses) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        MvcResult result = mvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        for (int accepted : acceptedStatuses) {
            if (actual == accepted) {
                return result;
            }
        }
        throw new AssertionError("unexpected status " + actual + ": "
                + result.getResponse().getContentAsString());
    }
}
