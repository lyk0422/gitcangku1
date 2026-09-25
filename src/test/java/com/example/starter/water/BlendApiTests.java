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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 水质掺配核销 API 测试：加权盐度、总量守恒、跨表原子扣减回滚、快照冻结、
 * 水源乐观版本、转让后余额归零不可核销、取消不恢复水源量、并发裁决与幂等。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
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

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-blend-" + run);
        body.put("startUtc", "2026-12-01T00:00:00Z");
        body.put("endUtc", "2026-12-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String salinityLimit, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (salinityLimit != null) {
            body.put("salinityLimit", salinityLimit);
        }
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private String approved(long windowId, String userId, String amount, String salinityLimit, String actor)
            throws Exception {
        String allocationKey = submit(windowId, userId, amount, salinityLimit, actor);
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
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

    private long versionOf(long windowId, String allocationKey) throws Exception {
        return allocation(windowId, allocationKey).get("version").asLong();
    }

    private String createSource(String amount, String salinity) throws Exception {
        String sourceKey = key("sk");
        JsonNode node = postOk("/api/sources", Map.of(
                "commandKey", key("sc"),
                "sourceKey", sourceKey,
                "availableAmount", amount,
                "salinity", salinity), null);
        assertEquals(0, node.get("version").asLong());
        return sourceKey;
    }

    private JsonNode source(String sourceKey) throws Exception {
        return getOk("/api/sources/" + sourceKey);
    }

    private Map<String, Object> item(String sourceKey, String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("sourceKey", sourceKey);
        item.put("amount", amount);
        return item;
    }

    private Map<String, Object> blendBody(String commandKey, String blendKey, String allocationKey,
                                          long allocationVersion, String writeOffAmount,
                                          List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("blendKey", blendKey);
        body.put("allocationKey", allocationKey);
        body.put("allocationVersion", allocationVersion);
        body.put("writeOffAmount", writeOffAmount);
        body.put("items", items);
        return body;
    }

    private record ItemInput(String sourceKey, String amount) {
    }

    private List<Map<String, Object>> items(ItemInput... inputs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemInput input : inputs) {
            list.add(item(input.sourceKey(), input.amount()));
        }
        return list;
    }

    private int blendStatus(String commandKey, String blendKey, String allocationKey, long allocationVersion,
                            String writeOffAmount, List<Map<String, Object>> items, String actor) {
        try {
            waterService.blendWriteOff(commandKey, blendKey, allocationKey, allocationVersion,
                    writeOffAmount, toRequestItems(items), actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private List<com.example.starter.water.dto.Dtos.BlendItemRequest> toRequestItems(
            List<Map<String, Object>> items) {
        return items.stream()
                .map(m -> new com.example.starter.water.dto.Dtos.BlendItemRequest(
                        (String) m.get("sourceKey"), (String) m.get("amount")))
                .toList();
    }

    // ------------------------------------------------------------------
    // 主流程：加权盐度与总量守恒
    // ------------------------------------------------------------------

    @Test
    void weightedSalinityAndConservationOnSuccessfulBlend() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "600", "alice");
        // 6@500 + 3@800 + 1@200 = 10，加权 (3000+2400+200)/10 = 560 mg/L
        String s1 = createSource("6", "500");
        String s2 = createSource("5", "800");
        String s3 = createSource("4", "200");

        String blendKey = key("bk");
        JsonNode snapshot = postOk("/api/blends",
                blendBody(key("bc"), blendKey, allocationKey, 1L, "10",
                        items(new ItemInput(s3, "1"), new ItemInput(s1, "6"), new ItemInput(s2, "3"))),
                "alice");

        assertEquals(blendKey, snapshot.get("blendKey").asText());
        assertEquals(allocationKey, snapshot.get("allocationKey").asText());
        assertEquals("alice", snapshot.get("actor").asText());
        assertEquals("10", snapshot.get("totalAmount").asText());
        assertEquals("560.000000", snapshot.get("weightedSalinity").asText());
        assertEquals("600", snapshot.get("salinityLimit").asText());
        assertEquals(1, snapshot.get("allocationVersion").asLong());
        // 明细按 source_key 升序规范化，换序输入不影响顺序；取水量与盐度随对应水源
        Map<String, String[]> expectByKey = new HashMap<>();
        expectByKey.put(s1, new String[]{"6", "500"});
        expectByKey.put(s2, new String[]{"3", "800"});
        expectByKey.put(s3, new String[]{"1", "200"});
        List<String> sortedKeys = List.of(s1, s2, s3).stream().sorted().toList();
        assertEquals(3, snapshot.get("items").size());
        for (int i = 0; i < 3; i++) {
            JsonNode itemNode = snapshot.get("items").get(i);
            String expectedKey = sortedKeys.get(i);
            assertEquals(expectedKey, itemNode.get("sourceKey").asText());
            assertEquals(expectByKey.get(expectedKey)[0], itemNode.get("amount").asText());
            assertEquals(expectByKey.get(expectedKey)[1], itemNode.get("salinity").asText());
            assertEquals(i, itemNode.get("ordinal").asInt());
        }

        // 水源余量按取水量扣减（总量守恒，逐水源核对）
        assertEquals("0", source(s1).get("availableAmount").asText());
        assertEquals("2", source(s2).get("availableAmount").asText());
        assertEquals("3", source(s3).get("availableAmount").asText());
        // 申请剩余额度扣减、版本自增
        JsonNode allocationNode = allocation(windowId, allocationKey);
        assertEquals("0", allocationNode.get("heldAmount").asText());
        assertEquals(2, allocationNode.get("version").asLong());
        assertEquals("APPROVED", allocationNode.get("status").asText());

        // 按键查询返回同一不可变快照
        JsonNode queried = getOk("/api/blends/" + blendKey);
        assertEquals(snapshot, queried);
    }

    @Test
    void weightedSalinityUsesExactRoundingToSixDecimals() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approved(windowId, "user-1", "3", null, "alice");
        // 各取 1，盐度 300/600/901，加权 1801/3 = 600.333333（6 位小数 HALF_UP）
        String s1 = createSource("5", "300");
        String s2 = createSource("5", "600");
        String s3 = createSource("5", "901");
        JsonNode snapshot = postOk("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "3",
                        items(new ItemInput(s1, "1"), new ItemInput(s2, "1"), new ItemInput(s3, "1"))),
                "alice");
        assertEquals("600.333333", snapshot.get("weightedSalinity").asText());
        assertTrue(snapshot.get("salinityLimit").isNull());
    }

    @Test
    void singleSourceBlendAllowedAndZeroAvailableSourceRejected() throws Exception {
        long windowId = createWindow("10");
        String allocationKey = approved(windowId, "user-1", "2", "500", "alice");
        String empty = createSource("0", "400");
        // 空余量水源 -> 422，不占用键
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "2",
                        items(new ItemInput(empty, "2"))), "alice", 422);
        // 单水源足量核销 -> 200
        String fresh = createSource("2", "400");
        JsonNode snapshot = postOk("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "2",
                        items(new ItemInput(fresh, "2"))), "alice");
        assertEquals(1, snapshot.get("items").size());
        assertEquals("400.000000", snapshot.get("weightedSalinity").asText());
        assertEquals("0", source(fresh).get("availableAmount").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支：422 整单回滚
    // ------------------------------------------------------------------

    @Test
    void salinityOverLimitReturns422AndChangesNothing() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "500", "alice");
        String s1 = createSource("6", "500");
        String s2 = createSource("4", "800");
        // 加权 (3000+3200)/10 = 620 > 500 -> 422
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "10",
                        items(new ItemInput(s1, "6"), new ItemInput(s2, "4"))), "alice", 422)
                .getResponse().getContentAsString().contains("SALINITY_EXCEEDED");
        // 整单回滚：水源与申请均无变化，无快照
        assertEquals("6", source(s1).get("availableAmount").asText());
        assertEquals("4", source(s2).get("availableAmount").asText());
        JsonNode node = allocation(windowId, allocationKey);
        assertEquals("10", node.get("heldAmount").asText());
        assertEquals(1, node.get("version").asLong());
        assertEquals(0, getOk("/api/allocations/" + allocationKey + "/blends").get("snapshots").size());
    }

    @Test
    void sourceInsufficientReturns422AndRollsBackEverything() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("6", "500");
        String s2 = createSource("4", "500");
        // s1 拟取 7 但余量仅 6 -> 422；s2 即使够也不得被扣
        MvcResult result = postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "10",
                        items(new ItemInput(s1, "7"), new ItemInput(s2, "3"))), "alice", 422);
        assertTrue(result.getResponse().getContentAsString().contains("SOURCE_INSUFFICIENT"));
        assertTrue(result.getResponse().getContentAsString().contains(s1));
        assertEquals("6", source(s1).get("availableAmount").asText());
        assertEquals("4", source(s2).get("availableAmount").asText());
        assertEquals("10", allocation(windowId, allocationKey).get("heldAmount").asText());
        mvc.perform(get("/api/blends/" + key("bk"))).andExpect(status().isNotFound());
    }

    @Test
    void itemSumMismatchReturns422() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("6", "500");
        String s2 = createSource("4", "500");
        // 明细合计 9 != 核销 10 -> 422
        MvcResult result = postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "10",
                        items(new ItemInput(s1, "6"), new ItemInput(s2, "3"))), "alice", 422);
        assertTrue(result.getResponse().getContentAsString().contains("BLEND_TOTAL_MISMATCH"));
        assertEquals("6", source(s1).get("availableAmount").asText());
        assertEquals("4", source(s2).get("availableAmount").asText());
    }

    @Test
    void allocationHeldInsufficientReturns422() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "4", "1000", "alice");
        String s1 = createSource("10", "500");
        MvcResult result = postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "5",
                        items(new ItemInput(s1, "5"))), "alice", 422);
        assertTrue(result.getResponse().getContentAsString().contains("INSUFFICIENT_ALLOCATION_HELD"));
        assertEquals("10", source(s1).get("availableAmount").asText());
    }

    @Test
    void transferredToZeroBalanceCannotBlend() throws Exception {
        long windowId = createWindow("100");
        String source = approved(windowId, "user-src", "6", "1000", "alice");
        String target = submit(windowId, "user-dst", "6", null, "bob");
        // 全额转让后原申请余额归零
        postOk("/api/transfers", Map.of(
                "commandKey", key("tc"),
                "transferKey", key("tk"),
                "sourceAllocationKey", source,
                "targetAllocationKey", target), "alice");
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
        long version = versionOf(windowId, source);
        String ws = createSource("10", "500");
        // 余额归零的原申请不得核销 -> 422
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), source, version, "1",
                        items(new ItemInput(ws, "1"))), "alice", 422);
        assertEquals("10", source(ws).get("availableAmount").asText());
    }

    // ------------------------------------------------------------------
    // 快照冻结与水源盐度乐观修改
    // ------------------------------------------------------------------

    @Test
    void salinityChangeOnlyAffectsLaterBlendsAndSnapshotStaysFrozen() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "600", "alice");
        String ws = createSource("10", "500");

        JsonNode first = postOk("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "2",
                        items(new ItemInput(ws, "2"))), "alice");
        assertEquals("500.000000", first.get("weightedSalinity").asText());

        // expectedVersion 错误 -> 409
        postJson("/api/sources/" + ws + "/salinity",
                Map.of("commandKey", key("su"), "expectedVersion", 9, "salinity", "900"), null, 409);
        // 携带当前版本 0 修改成功，版本变为 1
        JsonNode updated = postOk("/api/sources/" + ws + "/salinity",
                Map.of("commandKey", key("su"), "expectedVersion", 0, "salinity", "900"), null);
        assertEquals("900", updated.get("salinity").asText());
        assertEquals(1, updated.get("version").asLong());
        assertEquals("8", updated.get("availableAmount").asText());
        // 旧版本再次修改 -> 409
        postJson("/api/sources/" + ws + "/salinity",
                Map.of("commandKey", key("su"), "expectedVersion", 0, "salinity", "950"), null, 409);

        // 历史快照盐度冻结为 500，不被改写
        JsonNode frozen = getOk("/api/blends/" + first.get("blendKey").asText());
        assertEquals("500", frozen.get("items").get(0).get("salinity").asText());
        assertEquals("500.000000", frozen.get("weightedSalinity").asText());

        // 后续核销使用新盐度 900，高于上限 600 -> 422
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 2L, "2",
                        items(new ItemInput(ws, "2"))), "alice", 422);
        assertEquals("8", source(ws).get("availableAmount").asText());
    }

    @Test
    void cancellingAllocationDoesNotRestoreDeductedSourceAmount() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String ws = createSource("10", "500");
        String blendKey = key("bk");
        postOk("/api/blends",
                blendBody(key("bc"), blendKey, allocationKey, 1L, "4",
                        items(new ItemInput(ws, "4"))), "alice");
        assertEquals("6", source(ws).get("availableAmount").asText());
        // 取消申请（已开始的配水）不恢复已扣水源量
        postOk("/api/allocations/" + allocationKey + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("6", source(ws).get("availableAmount").asText());
        // 快照仍在且不可变
        assertEquals(blendKey, getOk("/api/blends/" + blendKey).get("blendKey").asText());
        // 已取消申请不能再核销
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 2L, "1",
                        items(new ItemInput(ws, "1"))), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 累计盐度查询
    // ------------------------------------------------------------------

    @Test
    void blendSummaryAccumulatesWeightedSalinity() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("10", "500");
        String s2 = createSource("10", "800");
        postOk("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "6",
                        items(new ItemInput(s1, "6"))), "alice");
        postOk("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 2L, "4",
                        items(new ItemInput(s2, "4"))), "alice");

        JsonNode summary = getOk("/api/allocations/" + allocationKey + "/blend-summary");
        assertEquals("10", summary.get("amount").asText());
        assertEquals("0", summary.get("heldAmount").asText());
        assertEquals("10", summary.get("totalWrittenOff").asText());
        assertEquals("0", summary.get("remainingHeld").asText());
        // 累计加权 (6*500 + 4*800)/10 = 620
        assertEquals("620.000000", summary.get("cumulativeWeightedSalinity").asText());
        assertEquals(2, summary.get("snapshots").size());
        assertEquals(2, getOk("/api/allocations/" + allocationKey + "/blends").get("snapshots").size());

        // 无核销申请累计盐度为 null
        String fresh = approved(windowId, "user-2", "3", null, "bob");
        JsonNode empty = getOk("/api/allocations/" + fresh + "/blend-summary");
        assertEquals("0", empty.get("totalWrittenOff").asText());
        assertTrue(empty.get("cumulativeWeightedSalinity").isNull());
    }

    // ------------------------------------------------------------------
    // 参数与状态错误
    // ------------------------------------------------------------------

    @Test
    void invalidBlendParamsReturn400() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("10", "500");
        String s2 = createSource("10", "500");
        String s3 = createSource("10", "500");
        String s4 = createSource("10", "500");
        String s5 = createSource("10", "500");
        String s6 = createSource("10", "500");

        // 0 个水源 -> 400
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "1", List.of()), "alice", 400);
        // 6 个水源 -> 400
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "6",
                        items(new ItemInput(s1, "1"), new ItemInput(s2, "1"), new ItemInput(s3, "1"),
                                new ItemInput(s4, "1"), new ItemInput(s5, "1"), new ItemInput(s6, "1"))),
                "alice", 400);
        // 同核销内重复水源 -> 400
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "2",
                        items(new ItemInput(s1, "1"), new ItemInput(s1, "1"))), "alice", 400);
        // 缺少 allocationVersion -> 400
        Map<String, Object> noVersion = blendBody(key("bc"), key("bk"), allocationKey, 1L, "1",
                items(new ItemInput(s1, "1")));
        noVersion.remove("allocationVersion");
        postJson("/api/blends", noVersion, "alice", 400);
        // 取水量 0 / 超过 3 位小数 -> 400
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "1",
                        items(new ItemInput(s1, "0"))), "alice", 400);
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "1",
                        items(new ItemInput(s1, "1.0001"))), "alice", 400);
        // 缺少 X-Actor-Id -> 400
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "1",
                        items(new ItemInput(s1, "1"))), null, 400);
        // 非法键 -> 400
        postJson("/api/blends",
                blendBody("", key("bk"), allocationKey, 1L, "1", items(new ItemInput(s1, "1"))),
                "alice", 400);
        postJson("/api/blends",
                blendBody(key("bc"), "bad key!", allocationKey, 1L, "1",
                        items(new ItemInput(s1, "1"))), "alice", 400);
    }

    @Test
    void invalidSourceParamsReturn400() throws Exception {
        // 盐度负数、超过 3 位小数、缺失 -> 400
        postJson("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", key("sk"),
                "availableAmount", "10", "salinity", "-1"), null, 400);
        postJson("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", key("sk"),
                "availableAmount", "10", "salinity", "1.0001"), null, 400);
        postJson("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", key("sk"),
                "availableAmount", "10"), null, 400);
        // 可用量负数 / 非法 -> 400
        postJson("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", key("sk"),
                "availableAmount", "-0.001", "salinity", "500"), null, 400);
        // 修改盐度缺 expectedVersion -> 400
        String ws = createSource("10", "500");
        postJson("/api/sources/" + ws + "/salinity",
                Map.of("commandKey", key("su"), "salinity", "600"), null, 400);
        postJson("/api/sources/" + ws + "/salinity",
                Map.of("commandKey", key("su"), "expectedVersion", -1, "salinity", "600"), null, 400);
        // 申请盐度上限非法 -> 400
        long windowId = createWindow("100");
        Map<String, Object> badLimit = new HashMap<>();
        badLimit.put("commandKey", key("ac"));
        badLimit.put("allocationKey", key("ak"));
        badLimit.put("windowId", windowId);
        badLimit.put("userId", "u");
        badLimit.put("amount", "1");
        badLimit.put("salinityLimit", "-5");
        postJson("/api/allocations", badLimit, "alice", 400);
    }

    @Test
    void notFoundAndStateConflictsAreDistinguishable() throws Exception {
        long windowId = createWindow("100");
        String requested = submit(windowId, "user-1", "10", "1000", "alice");
        String ws = createSource("10", "500");
        // 未批准申请核销 -> 409
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), requested, 0L, "1",
                        items(new ItemInput(ws, "1"))), "alice", 409);
        // 申请不存在 -> 404
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), "missing-" + run, 0L, "1",
                        items(new ItemInput(ws, "1"))), "alice", 404);
        // 水源不存在 -> 404
        String allocationKey = approved(windowId, "user-2", "10", "1000", "bob");
        postJson("/api/blends",
                blendBody(key("bc"), key("bk"), allocationKey, 1L, "1",
                        items(new ItemInput("missing-src-" + run, "1"))), "bob", 404);
        // 快照/水源/汇总查询不存在 -> 404
        mvc.perform(get("/api/blends/no-such-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/no-such-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/blends")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/blend-summary")).andExpect(status().isNotFound());
        // 盐度修改水源不存在 -> 404
        postJson("/api/sources/no-such-" + run + "/salinity",
                Map.of("commandKey", key("su"), "expectedVersion", 0, "salinity", "600"), null, 404);
    }

    @Test
    void duplicateSourceKeyReturns409() throws Exception {
        String sourceKey = key("sk");
        postOk("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", sourceKey,
                "availableAmount", "10", "salinity", "500"), null);
        postJson("/api/sources", Map.of(
                "commandKey", key("sc"), "sourceKey", sourceKey,
                "availableAmount", "20", "salinity", "600"), null, 409);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeyReplaysFirstBlendResultIgnoringSourceOrder() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("6", "500");
        String s2 = createSource("4", "800");
        String commandKey = key("bc");
        String blendKey = key("bk");
        // 首次按 s2,s1 顺序提交
        Map<String, Object> firstBody = blendBody(commandKey, blendKey, allocationKey, 1L, "10",
                items(new ItemInput(s2, "4"), new ItemInput(s1, "6")));
        JsonNode first = postOk("/api/blends", firstBody, "alice");
        // 换序 + 同 commandKey/同参（规范化后一致）-> 重放首次完整结果
        Map<String, Object> replayBody = blendBody(commandKey, blendKey, allocationKey, 1L, "10",
                items(new ItemInput(s1, "6"), new ItemInput(s2, "4")));
        JsonNode replay = postOk("/api/blends", replayBody, "alice");
        assertEquals(first, replay);
        // 只扣减一次、只有一条快照
        assertEquals("0", source(s1).get("availableAmount").asText());
        assertEquals("0", source(s2).get("availableAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + allocationKey + "/blends").get("snapshots").size());
        assertEquals("0", allocation(windowId, allocationKey).get("heldAmount").asText());
        // 同 commandKey 改参（改申请版本）-> 409
        postJson("/api/blends",
                blendBody(commandKey, key("bk"), allocationKey, 2L, "10",
                        items(new ItemInput(s1, "6"), new ItemInput(s2, "4"))), "alice", 409);
    }

    @Test
    void failedBlendDoesNotOccupyCommandKeyOrBlendKey() throws Exception {
        long windowId = createWindow("100");
        String allocationKey = approved(windowId, "user-1", "10", "1000", "alice");
        String s1 = createSource("10", "500");
        String commandKey = key("bc");
        String blendKey = key("bk");
        // 首次 422（总量不符）不占用 commandKey 与 blendKey
        postJson("/api/blends",
                blendBody(commandKey, blendKey, allocationKey, 1L, "10",
                        items(new ItemInput(s1, "9"))), "alice", 422);
        // 同键同参仍 422，无扣减
        postJson("/api/blends",
                blendBody(commandKey, blendKey, allocationKey, 1L, "10",
                        items(new ItemInput(s1, "9"))), "alice", 422);
        assertEquals("10", source(s1).get("availableAmount").asText());
        // 同 commandKey 与 blendKey 改正参数后成功，证明失败不占键
        JsonNode ok = postOk("/api/blends",
                blendBody(commandKey, blendKey, allocationKey, 1L, "3",
                        items(new ItemInput(s1, "3"))), "alice");
        assertEquals(blendKey, ok.get("blendKey").asText());
        assertEquals("7", source(s1).get("availableAmount").asText());
    }

    @Test
    void blendKeyReuseWithDifferentCommandKeyReturns409() throws Exception {
        long windowId = createWindow("100");
        String a1 = approved(windowId, "user-1", "4", "1000", "alice");
        String a2 = approved(windowId, "user-2", "4", "1000", "bob");
        String s1 = createSource("10", "500");
        String blendKey = key("bk");
        postOk("/api/blends",
                blendBody(key("bc"), blendKey, a1, 1L, "2", items(new ItemInput(s1, "2"))), "alice");
        // 换 commandKey 复用 blendKey 核销另一申请 -> 409，且不扣减
        postJson("/api/blends",
                blendBody(key("bc"), blendKey, a2, 1L, "2", items(new ItemInput(s1, "2"))), "bob", 409);
        assertEquals("8", source(s1).get("availableAmount").asText());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentBlendsNeverOverdrawSharedSources() throws Exception {
        long windowId = createWindow("100");
        String a1 = approved(windowId, "user-1", "10", "1000", "alice");
        String a2 = approved(windowId, "user-2", "6", "1000", "bob");
        // s1 余量 8 < 两笔合计 9，s2 余量充足
        String s1 = createSource("8", "500");
        String s2 = createSource("10", "500");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bc"), key("bk"), a1, 1L, "10",
                    items(new ItemInput(s1, "6"), new ItemInput(s2, "4")), "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return blendStatus(key("bc"), key("bk"), a2, 1L, "6",
                    items(new ItemInput(s1, "3"), new ItemInput(s2, "3")), "bob");
        });
        gate.countDown();
        int r1 = f1.get(30, TimeUnit.SECONDS);
        int r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按提交顺序恰好一笔成功，另一笔 422（水源余量不足）
        assertNotEquals(r1, r2);
        assertEquals(1, (r1 == 200 ? 1 : 0) + (r2 == 200 ? 1 : 0), "r1=" + r1 + " r2=" + r2);
        assertEquals(1, (r1 == 422 ? 1 : 0) + (r2 == 422 ? 1 : 0));
        // 水源绝不会扣成负数；胜方为 a1 则 s1 余 2、s2 余 6；胜方为 a2 则 s1 余 5、s2 余 7
        BigDecimal s1Left = new BigDecimal(source(s1).get("availableAmount").asText());
        BigDecimal s2Left = new BigDecimal(source(s2).get("availableAmount").asText());
        assertTrue(s1Left.signum() >= 0);
        assertTrue(s2Left.signum() >= 0);
        boolean a1Won = s1Left.compareTo(new BigDecimal("2")) == 0;
        boolean a2Won = s1Left.compareTo(new BigDecimal("5")) == 0;
        assertTrue(a1Won || a2Won, "s1Left=" + s1Left);
        if (a1Won) {
            assertEquals(0, s2Left.compareTo(new BigDecimal("6")));
        } else {
            assertEquals(0, s2Left.compareTo(new BigDecimal("7")));
        }
        // 只有一条快照
        int snapshots = getOk("/api/allocations/" + a1 + "/blends").get("snapshots").size()
                + getOk("/api/allocations/" + a2 + "/blends").get("snapshots").size();
        assertEquals(1, snapshots);
    }

    @Test
    void blendAndTransferOfSameAllocationSettleByCommitOrder() throws Exception {
        long windowId = createWindow("100");
        String a1 = approved(windowId, "user-src", "6", "1000", "alice");
        String target = submit(windowId, "user-dst", "6", null, "bob");
        String s1 = createSource("10", "500");
        String blendCommand = key("bc");
        String transferCommand = key("tc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> blendFuture = pool.submit(() -> {
            gate.await();
            return blendStatus(blendCommand, key("bk"), a1, 1L, "6",
                    items(new ItemInput(s1, "6")), "alice");
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, key("tk"), a1, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int blendResult = blendFuture.get(30, TimeUnit.SECONDS);
        int transferResult = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 转让先提交：申请版本推进，核销 409；核销先提交：余额归零，转让 422
        assertTrue((blendResult == 200) != (transferResult == 200),
                "blend=" + blendResult + " transfer=" + transferResult);
        if (blendResult == 200) {
            assertEquals(422, transferResult);
            assertEquals("4", source(s1).get("availableAmount").asText());
            assertEquals("0", allocation(windowId, a1).get("heldAmount").asText());
        } else {
            assertEquals(409, blendResult);
            assertEquals(200, transferResult);
            assertEquals("10", source(s1).get("availableAmount").asText());
        }
    }
}
