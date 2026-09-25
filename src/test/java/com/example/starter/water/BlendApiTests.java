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
 * 水源水质掺配与窗口配额联合核销测试：加权盐度、总量守恒、跨表原子扣减、快照冻结、
 * 水源版本并发、申请版本裁决与 blendKey 幂等。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlendApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(8);
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

    private String createSource(String sourceId, String available, String salinity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("sc"));
        body.put("sourceId", sourceId);
        body.put("availableAmount", available);
        body.put("salinityMgPerL", salinity);
        postOk("/api/sources", body, null);
        return sourceId;
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String maxSalinity, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (maxSalinity != null) {
            body.put("maxSalinityMgPerL", maxSalinity);
        }
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
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

    private long allocationVersion(long windowId, String allocationKey) throws Exception {
        return allocation(windowId, allocationKey).get("version").asLong();
    }

    private Map<String, Object> blendBody(String blendKey, String allocationKey, long allocationVersion,
                                          String settleAmount, List<List<String>> sources) {
        Map<String, Object> body = new HashMap<>();
        body.put("blendKey", blendKey);
        body.put("allocationKey", allocationKey);
        body.put("allocationVersion", allocationVersion);
        body.put("settleAmount", settleAmount);
        List<Map<String, Object>> items = new ArrayList<>();
        for (List<String> pair : sources) {
            items.add(Map.of("sourceId", pair.get(0), "amount", pair.get(1)));
        }
        body.put("sources", items);
        return body;
    }

    private List<List<String>> sources(Object... pairs) {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.add(List.of((String) pairs[i], (String) pairs[i + 1]));
        }
        return result;
    }

    private int blendStatus(String blendKey, String allocationKey, long allocationVersion,
                            String settleAmount, List<List<String>> sources, String operator) {
        try {
            waterService.blend(blendKey, allocationKey, allocationVersion, settleAmount,
                    sources.stream().map(p -> new com.example.starter.water.dto.Dtos.BlendSourceItem(
                            p.get(0), p.get(1))).toList(), operator);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 主流程：加权盐度、总量守恒、跨表原子扣减
    // ------------------------------------------------------------------

    @Test
    void blendDeductsSourcesAndAllocationAtomicallyAndFreezesSnapshot() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        String s2 = createSource(key("src"), "50", "200");
        long windowId = createWindow("ch-blend-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "400", "alice");
        approve(allocationKey);
        long version = allocationVersion(windowId, allocationKey);

        String blendKey = key("bk");
        JsonNode result = postOk("/api/blends",
                blendBody(blendKey, allocationKey, version, "4", sources(s1, "1", s2, "3")), "alice");
        assertEquals(blendKey, result.get("blendKey").asText());
        assertEquals(allocationKey, result.get("allocationKey").asText());
        assertEquals(version, result.get("allocationVersion").asLong());
        assertEquals("alice", result.get("operator").asText());
        assertEquals("4", result.get("settleAmount").asText());
        // 加权平均盐度 = (1*500 + 3*200) / 4 = 275
        assertEquals("275", result.get("weightedSalinityMgPerL").asText());
        assertEquals(2, result.get("sources").size());
        // 明细按 sourceId 字典序冻结
        JsonNode line1 = result.get("sources").get(0);
        assertEquals(s1, line1.get("sourceId").asText());
        assertEquals("1", line1.get("amount").asText());
        assertEquals("500", line1.get("salinityMgPerL").asText());
        assertEquals(0, line1.get("sourceVersion").asLong());
        JsonNode line2 = result.get("sources").get(1);
        assertEquals(s2, line2.get("sourceId").asText());
        assertEquals("3", line2.get("amount").asText());
        assertEquals("200", line2.get("salinityMgPerL").asText());

        // 水源余量与申请剩余额度同事务扣减
        assertEquals("99", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("47", getOk("/api/sources/" + s2).get("availableAmount").asText());
        JsonNode allocation = allocation(windowId, allocationKey);
        assertEquals("6", allocation.get("heldAmount").asText());
        assertEquals("APPROVED", allocation.get("status").asText());
        assertEquals(version + 1, allocation.get("version").asLong());

        // 快照查询与核销响应一致
        JsonNode snapshot = getOk("/api/blends/" + blendKey);
        assertEquals(result, snapshot);

        // 申请累计盐度
        JsonNode salinity = getOk("/api/allocations/" + allocationKey + "/salinity");
        assertEquals("4", salinity.get("settledTotal").asText());
        assertEquals("275", salinity.get("cumulativeSalinityMgPerL").asText());
        assertEquals(1, salinity.get("blendCount").asInt());
    }

    @Test
    void cumulativeSalinityAveragesAcrossMultipleBlends() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        String s2 = createSource(key("src"), "100", "200");
        long windowId = createWindow("ch-cum-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        postOk("/api/blends", blendBody(key("bk"), allocationKey, 1, "2", sources(s1, "2")), "alice");
        postOk("/api/blends", blendBody(key("bk"), allocationKey, 2, "2", sources(s2, "2")), "alice");

        // 累计盐度 = (2*500 + 2*200) / 4 = 350
        JsonNode salinity = getOk("/api/allocations/" + allocationKey + "/salinity");
        assertEquals("4", salinity.get("settledTotal").asText());
        assertEquals("350", salinity.get("cumulativeSalinityMgPerL").asText());
        assertEquals(2, salinity.get("blendCount").asInt());
        assertEquals("6", allocation(windowId, allocationKey).get("heldAmount").asText());
    }

    @Test
    void allocationWithoutSalinityLimitSkipsSalinityCheck() throws Exception {
        String s1 = createSource(key("src"), "100", "900");
        long windowId = createWindow("ch-nolimit-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "5", null, "alice");
        approve(allocationKey);

        JsonNode result = postOk("/api/blends",
                blendBody(key("bk"), allocationKey, 1, "2", sources(s1, "2")), "alice");
        assertEquals("900", result.get("weightedSalinityMgPerL").asText());
        JsonNode allocation = allocation(windowId, allocationKey);
        assertTrue(allocation.get("maxSalinityMgPerL").isNull());
        assertEquals("3", allocation.get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支：422 携带水源余量或计算值
    // ------------------------------------------------------------------

    @Test
    void weightedSalinityAboveLimitReturns422WithComputedValue() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        String s2 = createSource(key("src"), "100", "200");
        long windowId = createWindow("ch-salt-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "300", "alice");
        approve(allocationKey);

        // 加权平均盐度 = (2*500 + 2*200) / 4 = 350 > 300
        MvcResult result = postJson("/api/blends",
                blendBody(key("bk"), allocationKey, 1, "4", sources(s1, "2", s2, "2")), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SALINITY_EXCEEDED", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("350"), error.get("message").asText());
        assertTrue(error.get("message").asText().contains("300"), error.get("message").asText());

        // 无任何扣减、无快照
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("100", getOk("/api/sources/" + s2).get("availableAmount").asText());
        assertEquals("10", allocation(windowId, allocationKey).get("heldAmount").asText());
        mvc.perform(get("/api/blends/" + "missing-" + run)).andExpect(status().isNotFound());
        JsonNode salinity = getOk("/api/allocations/" + allocationKey + "/salinity");
        assertEquals("0", salinity.get("settledTotal").asText());
        assertTrue(salinity.get("cumulativeSalinityMgPerL").isNull());
        assertEquals(0, salinity.get("blendCount").asInt());
    }

    @Test
    void totalMismatchReturns422WithComputedTotal() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-total-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        MvcResult result = postJson("/api/blends",
                blendBody(key("bk"), allocationKey, 1, "5", sources(s1, "4")), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("BLEND_TOTAL_MISMATCH", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("4"), error.get("message").asText());
        assertTrue(error.get("message").asText().contains("5"), error.get("message").asText());
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("10", allocation(windowId, allocationKey).get("heldAmount").asText());
    }

    @Test
    void insufficientSourceReturns422WithRemaining() throws Exception {
        String s1 = createSource(key("src"), "3", "100");
        long windowId = createWindow("ch-insuf-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        MvcResult result = postJson("/api/blends",
                blendBody(key("bk"), allocationKey, 1, "4", sources(s1, "4")), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SOURCE_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("3"), error.get("message").asText());
        assertEquals("3", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("10", allocation(windowId, allocationKey).get("heldAmount").asText());
    }

    @Test
    void allocationBalanceInsufficientReturns422() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-bal-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "2", "1000", "alice");
        approve(allocationKey);

        MvcResult result = postJson("/api/blends",
                blendBody(key("bk"), allocationKey, 1, "3", sources(s1, "3")), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("ALLOCATION_BALANCE_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("2"), error.get("message").asText());
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
    }

    @Test
    void transferredOutZeroBalanceCannotBlend() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-zero-" + run, "20");
        String source = submit(windowId, "user-src", "5", "1000", "alice");
        String target = submit(windowId, "user-dst", "5", null, "bob");
        approve(source);
        // 转让全部额度，源申请余额归零
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", source);
        transfer.put("targetAllocationKey", target);
        postOk("/api/transfers", transfer, "alice");
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());

        long version = allocationVersion(windowId, source);
        MvcResult result = postJson("/api/blends",
                blendBody(key("bk"), source, version, "1", sources(s1, "1")), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("ALLOCATION_BALANCE_INSUFFICIENT", error.get("code").asText());
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
    }

    @Test
    void invalidBlendRequestsReturn400Or404Or409() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-invalid-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        // 0 个水源 -> 400
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "1", sources()), "alice", 400);
        // 6 个水源 -> 400
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "6",
                sources(s1, "1", s1, "1", s1, "1", s1, "1", s1, "1", s1, "1")), "alice", 400);
        // 重复水源 -> 400
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "2",
                sources(s1, "1", s1, "1")), "alice", 400);
        // 取水量非法 -> 400
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "1",
                sources(s1, "0.0001")), "alice", 400);
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "1",
                sources(s1, "0")), "alice", 400);
        // 申请版本缺失/非法 -> 400
        Map<String, Object> noVersion = blendBody(key("bk"), allocationKey, 1, "1", sources(s1, "1"));
        noVersion.remove("allocationVersion");
        postJson("/api/blends", noVersion, "alice", 400);
        // 缺少 X-Actor-Id -> 400
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "1", sources(s1, "1")), null, 400);
        // 申请不存在 -> 404
        postJson("/api/blends", blendBody(key("bk"), "missing-" + run, 1, "1", sources(s1, "1")),
                "alice", 404);
        // 水源不存在 -> 404
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 1, "1",
                sources("missing-" + run, "1")), "alice", 404);
        // 申请版本过期 -> 409
        postJson("/api/blends", blendBody(key("bk"), allocationKey, 0, "1", sources(s1, "1")),
                "alice", 409);
        // 申请未批准 -> 409
        String requested = submit(windowId, "user-2", "2", "1000", "bob");
        postJson("/api/blends", blendBody(key("bk"), requested, 0, "1", sources(s1, "1")), "bob", 409);
        // 快照不存在 -> 404
        mvc.perform(get("/api/blends/" + "missing-" + run)).andExpect(status().isNotFound());
        // 累计盐度查询申请不存在 -> 404
        mvc.perform(get("/api/allocations/" + "missing-" + run + "/salinity"))
                .andExpect(status().isNotFound());
        // 水源查询不存在 -> 404
        mvc.perform(get("/api/sources/" + "missing-" + run)).andExpect(status().isNotFound());

        // 全部失败后无任何扣减
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("10", allocation(windowId, allocationKey).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 水源盐度修改与快照冻结
    // ------------------------------------------------------------------

    @Test
    void salinityUpdateRequiresVersionAndDoesNotRewriteSnapshot() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        long windowId = createWindow("ch-freeze-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        String blendKey = key("bk");
        postOk("/api/blends", blendBody(blendKey, allocationKey, 1, "2", sources(s1, "2")), "alice");

        // 版本不符 -> 409
        Map<String, Object> stale = new HashMap<>();
        stale.put("commandKey", key("su"));
        stale.put("expectedVersion", 5);
        stale.put("salinityMgPerL", "800");
        postJson("/api/sources/" + s1 + "/salinity", stale, null, 409);

        // 携带正确 expectedVersion 修改成功，版本 +1
        Map<String, Object> update = new HashMap<>();
        update.put("commandKey", key("su"));
        update.put("expectedVersion", 0);
        update.put("salinityMgPerL", "800");
        JsonNode updated = postOk("/api/sources/" + s1 + "/salinity", update, null);
        assertEquals("800", updated.get("salinityMgPerL").asText());
        assertEquals(1, updated.get("version").asLong());

        // 历史快照不被改写
        JsonNode snapshot = getOk("/api/blends/" + blendKey);
        assertEquals("500", snapshot.get("sources").get(0).get("salinityMgPerL").asText());
        assertEquals(0, snapshot.get("sources").get(0).get("sourceVersion").asLong());
        assertEquals("500", snapshot.get("weightedSalinityMgPerL").asText());

        // 后续核销使用新盐度与新水源版本
        JsonNode second = postOk("/api/blends",
                blendBody(key("bk"), allocationKey, 2, "2", sources(s1, "2")), "alice");
        assertEquals("800", second.get("sources").get(0).get("salinityMgPerL").asText());
        assertEquals(1, second.get("sources").get(0).get("sourceVersion").asLong());
        assertEquals("800", second.get("weightedSalinityMgPerL").asText());
        // 累计盐度 = (2*500 + 2*800) / 4 = 650
        assertEquals("650", getOk("/api/allocations/" + allocationKey + "/salinity")
                .get("cumulativeSalinityMgPerL").asText());
    }

    @Test
    void concurrentSalinityUpdatesSettleByCommitOrder() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            try {
                waterService.updateSourceSalinity(key("su"), s1, 0L, "600");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            try {
                waterService.updateSourceSalinity(key("su"), s1, 0L, "700");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1Status = f1.get(30, TimeUnit.SECONDS);
        int s2Status = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1Status == 200 ? 1 : 0) + (s2Status == 200 ? 1 : 0),
                "同一 expectedVersion 并发修改恰好一个成功: " + s1Status + "/" + s2Status);
        assertEquals(1, (s1Status == 409 ? 1 : 0) + (s2Status == 409 ? 1 : 0));
        JsonNode source = getOk("/api/sources/" + s1);
        assertEquals(1, source.get("version").asLong());
        assertTrue(List.of("600", "700").contains(source.get("salinityMgPerL").asText()));
    }

    // ------------------------------------------------------------------
    // blendKey 幂等
    // ------------------------------------------------------------------

    @Test
    void sourceOrderInsensitiveAndSameBlendKeyReplaysFirstResult() throws Exception {
        String s1 = createSource(key("src"), "100", "500");
        String s2 = createSource(key("src"), "100", "200");
        long windowId = createWindow("ch-idem-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);
        String blendKey = key("bk");

        JsonNode first = postOk("/api/blends",
                blendBody(blendKey, allocationKey, 1, "4", sources(s1, "1", s2, "3")), "alice");
        // 换序视为同参：同键重放首次完整结果
        JsonNode replay = postOk("/api/blends",
                blendBody(blendKey, allocationKey, 1, "4", sources(s2, "3", s1, "1")), "alice");
        assertEquals(first, replay);
        // 只扣减一次、只有一个快照
        assertEquals("99", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("97", getOk("/api/sources/" + s2).get("availableAmount").asText());
        assertEquals("6", allocation(windowId, allocationKey).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/salinity").get("blendCount").asInt());

        // 同 blendKey 改参 -> 409
        postJson("/api/blends", blendBody(blendKey, allocationKey, 1, "4", sources(s1, "2", s2, "2")),
                "alice", 409);
        postJson("/api/blends", blendBody(blendKey, allocationKey, 1, "4", sources(s1, "1", s2, "3")),
                "bob", 409);
    }

    @Test
    void failedBlendDoesNotOccupyBlendKey() throws Exception {
        String s1 = createSource(key("src"), "100", "900");
        String s2 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-failidem-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "400", "alice");
        approve(allocationKey);
        String blendKey = key("bk");

        // 首次失败（盐度超限 900 > 400）不占用 blendKey
        postJson("/api/blends", blendBody(blendKey, allocationKey, 1, "2", sources(s1, "2")),
                "alice", 422);
        // 同键改参后成功
        JsonNode ok = postOk("/api/blends",
                blendBody(blendKey, allocationKey, 1, "2", sources(s2, "2")), "alice");
        assertEquals("100", ok.get("weightedSalinityMgPerL").asText());
        assertEquals("98", getOk("/api/sources/" + s2).get("availableAmount").asText());
        assertEquals("100", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/salinity").get("blendCount").asInt());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentBlendsOnSameAllocationSettleByCommitOrder() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-race-alloc-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bk"), allocationKey, 1, "4", sources(s1, "4"), "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bk"), allocationKey, 1, "4", sources(s1, "4"), "alice");
        });
        gate.countDown();
        int s1Status = f1.get(30, TimeUnit.SECONDS);
        int s2Status = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一申请版本并发核销：恰好一个成功，另一个版本冲突 409
        assertEquals(1, (s1Status == 200 ? 1 : 0) + (s2Status == 200 ? 1 : 0),
                "并发核销: " + s1Status + "/" + s2Status);
        assertEquals(1, (s1Status == 409 ? 1 : 0) + (s2Status == 409 ? 1 : 0));
        assertEquals("6", allocation(windowId, allocationKey).get("heldAmount").asText());
        assertEquals("96", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/salinity").get("blendCount").asInt());
    }

    @Test
    void concurrentBlendsOnSameSourceNeverGoNegative() throws Exception {
        String s1 = createSource(key("src"), "5", "100");
        long windowId = createWindow("ch-race-source-" + run, "20");
        String allocA = submit(windowId, "user-a", "4", "1000", "alice");
        String allocB = submit(windowId, "user-b", "4", "1000", "bob");
        approve(allocA);
        approve(allocB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bk"), allocA, 1, "4", sources(s1, "4"), "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bk"), allocB, 1, "4", sources(s1, "4"), "bob");
        });
        gate.countDown();
        int s1Status = f1.get(30, TimeUnit.SECONDS);
        int s2Status = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 水源可用量 5，两笔各需 4：恰好一笔成功，另一笔 422
        assertEquals(1, (s1Status == 200 ? 1 : 0) + (s2Status == 200 ? 1 : 0),
                "水源争用: " + s1Status + "/" + s2Status);
        assertEquals(1, (s1Status == 422 ? 1 : 0) + (s2Status == 422 ? 1 : 0));
        assertEquals("1", getOk("/api/sources/" + s1).get("availableAmount").asText());
        // 只有胜方申请被扣减
        String winner = s1Status == 200 ? allocA : allocB;
        String loser = s1Status == 200 ? allocB : allocA;
        assertEquals("0", allocation(windowId, winner).get("heldAmount").asText());
        assertEquals("4", allocation(windowId, loser).get("heldAmount").asText());
    }

    @Test
    void concurrentSameBlendKeyReplaysSingleResult() throws Exception {
        String s1 = createSource(key("src"), "100", "100");
        long windowId = createWindow("ch-race-key-" + run, "20");
        String allocationKey = submit(windowId, "user-1", "10", "1000", "alice");
        approve(allocationKey);
        String blendKey = key("bk");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return blendStatus(blendKey, allocationKey, 1, "4", sources(s1, "4"), "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return blendStatus(blendKey, allocationKey, 1, "4", sources(s1, "4"), "alice");
        });
        gate.countDown();
        int s1Status = f1.get(30, TimeUnit.SECONDS);
        int s2Status = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同 blendKey 同参并发：一个执行、一个重放或稍后重放成功，最终只扣减一次
        assertTrue(s1Status == 200 || s1Status == 409, "s1=" + s1Status);
        assertTrue(s2Status == 200 || s2Status == 409, "s2=" + s2Status);
        assertEquals("96", getOk("/api/sources/" + s1).get("availableAmount").asText());
        assertEquals("6", allocation(windowId, allocationKey).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/salinity").get("blendCount").asInt());
    }
}
