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

import java.util.HashMap;
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
 * 实际用水核销 API 测试：主流程、容量口径、失败回滚、幂等与并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、行锁与唯一/CHECK 约束，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UsageApiTests {

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

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-12-01T00:00:00Z");
        body.put("endUtc", "2026-12-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String actor) throws Exception {
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

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
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

    private Map<String, Object> usageBody(String commandKey, String usageKey,
                                          String allocationKey, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("usageKey", usageKey);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        return body;
    }

    private int usageStatus(String commandKey, String usageKey, String allocationKey,
                            String amount, String actor) {
        try {
            waterService.recordUsage(commandKey, usageKey, allocationKey, amount, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode usages(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/usages");
    }

    // ------------------------------------------------------------------
    // 主流程与容量口径
    // ------------------------------------------------------------------

    @Test
    void usageConsumesHeldAndKeepsWindowOccupancyUnchanged() throws Exception {
        long windowId = createWindow("ch-use-" + run, "10");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);

        String usageKey = key("uk");
        JsonNode result = postOk("/api/usages", usageBody(key("uc"), usageKey, a1, "2.500"), "alice");
        assertEquals(usageKey, result.get("usageKey").asText());
        assertEquals(windowId, result.get("windowId").asLong());
        assertEquals(a1, result.get("allocationKey").asText());
        assertEquals("2.5", result.get("amount").asText());
        assertEquals("2.5", result.get("usedAfter").asText());
        assertEquals("3.5", result.get("heldAfter").asText());
        assertEquals("alice", result.get("actor").asText());
        assertTrue(result.has("createdUtc"));

        // 申请视图：原水量不变，未用 3.5，已用 2.5
        JsonNode node = allocation(windowId, a1);
        assertEquals("6", node.get("amount").asText());
        assertEquals("3.5", node.get("heldAmount").asText());
        assertEquals("2.5", node.get("usedAmount").asText());
        assertEquals("APPROVED", node.get("status").asText());

        // 容量口径：APPROVED 持有 3.5，全部已用 2.5，已占用仍为 6，余量 4
        JsonNode cap = capacity(windowId);
        assertEquals("3.5", cap.get("approvedTotal").asText());
        assertEquals("2.5", cap.get("usedTotal").asText());
        assertEquals("6", cap.get("occupiedTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 单笔查询与窗口流水
        JsonNode one = getOk("/api/usages/" + usageKey);
        assertEquals(usageKey, one.get("usageKey").asText());
        JsonNode list = usages(windowId);
        assertEquals(1, list.get("usages").size());
        assertEquals(usageKey, list.get("usages").get(0).get("usageKey").asText());
    }

    @Test
    void cumulativeUsageAccumulatesAndExactArithmeticHolds() throws Exception {
        long windowId = createWindow("ch-cum-" + run, "1.000");
        String a1 = submit(windowId, "u1", "1.000", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.333"), "alice");
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.333"), "alice");
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.333"), "alice");
        // 已用 0.999，再核销 0.002 -> 422；核销 0.001 -> 成功
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.002"), "alice", 422);
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.001"), "alice");

        JsonNode node = allocation(windowId, a1);
        assertEquals("0", node.get("heldAmount").asText());
        assertEquals("1", node.get("usedAmount").asText());
        JsonNode cap = capacity(windowId);
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("1", cap.get("usedTotal").asText());
        assertEquals("1", cap.get("occupiedTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
        assertEquals(4, usages(windowId).get("usages").size());
        // 未用为零后再核销仍 422，已用量不允许冲销
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "0.001"), "alice", 422);
    }

    @Test
    void cancelReleasesOnlyUnusedHeldButUsedStaysOccupied() throws Exception {
        long windowId = createWindow("ch-cancel-" + run, "10");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "4"), "alice");
        // 取消前占用 6（持有 2 + 已用 4）
        JsonNode before = capacity(windowId);
        assertEquals("6", before.get("occupiedTotal").asText());
        // 取消：仅释放未用 2，已用 4 仍占窗口容量
        JsonNode cancelled = postOk("/api/allocations/" + a1 + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("heldAmount").asText());
        assertEquals("4", cancelled.get("usedAmount").asText());
        JsonNode after = capacity(windowId);
        assertEquals("0", after.get("approvedTotal").asText());
        assertEquals("4", after.get("usedTotal").asText());
        assertEquals("4", after.get("occupiedTotal").asText());
        assertEquals("6", after.get("remaining").asText());
        // 取消后不得再核销
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "1"), "alice", 409);
        // 已用容量不可被新批准突破：再批准 7 将使占用 11 > 10 -> 422；批准 6 恰好 -> 200
        String a2 = submit(windowId, "user-2", "7", "bob");
        approve(a2, 422);
        String a3 = submit(windowId, "user-3", "6", "carol");
        approve(a3, 200);
        // 核销流水不因取消而改写
        assertEquals(1, usages(windowId).get("usages").size());
        JsonNode node = allocation(windowId, a1);
        assertEquals("6", node.get("amount").asText());
        assertEquals("4", node.get("usedAmount").asText());
    }

    @Test
    void usedWaterBlocksNewApprovalsAndCurtailmentAdjustment() throws Exception {
        long windowId = createWindow("ch-block-" + run, "10");
        // a1 批准 6、核销 5 后取消：未用额度释放，但已用 5 永远占用窗口容量
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "5"), "alice");
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        assertEquals("5", capacity(windowId).get("occupiedTotal").asText());

        // 已用容量压缩后续批准空间：5 + 6 > 10 -> 422；5 + 5 == 10 -> 200
        String a2 = submit(windowId, "user-2", "6", "bob");
        approve(a2, 422);
        String a3 = submit(windowId, "user-3", "5", "carol");
        approve(a3, 200);
        // 占用 10（已取消 a1 的已用 5 + APPROVED a3 持有 5）：限供 9 -> 409，限供 10 -> 200
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "9"), null, 409);
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "10"), null);

        // 取消 a3 释放全部持有，窗口只剩已取消 a1 的已用 5
        postOk("/api/allocations/" + a3 + "/cancel", Map.of("commandKey", key("cc")), "carol");
        JsonNode cap = capacity(windowId);
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("5", cap.get("usedTotal").asText());
        assertEquals("5", cap.get("occupiedTotal").asText());
        // 取消生效限供后，即使窗口内已无任何 APPROVED 申请，已用 5 仍阻止限供下调到 4
        postOk("/api/windows/" + windowId + "/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null);
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu2"), "volume", "4"), null, 409);
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu2"), "volume", "5"), null);
    }

    @Test
    void transferCanOnlyMoveUnusedHeldAmount() throws Exception {
        long windowId = createWindow("ch-tu-" + run, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target3 = submit(windowId, "user-t3", "3", "bob");
        String target2 = submit(windowId, "user-t2", "2", "carol");
        approve(source, 200);
        // 已用 4 -> 未用仅 2，转不走已用部分
        postOk("/api/usages", usageBody(key("uc"), key("uk"), source, "4"), "alice");
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, target3), "alice", 422);
        // 仅可转让剩余 2；窗口占用始终 6
        postOk("/api/transfers", transferBody(key("tkc"), key("tk"), source, target2), "alice");
        JsonNode src = allocation(windowId, source);
        assertEquals("0", src.get("heldAmount").asText());
        assertEquals("4", src.get("usedAmount").asText());
        JsonNode cap = capacity(windowId);
        assertEquals("2", cap.get("approvedTotal").asText());
        assertEquals("4", cap.get("usedTotal").asText());
        assertEquals("6", cap.get("occupiedTotal").asText());
        // 持有为零再核销 -> 422
        postJson("/api/usages", usageBody(key("uc"), key("uk"), source, "1"), "alice", 422);
    }

    @Test
    void historyIncludesUsageFlows() throws Exception {
        long windowId = createWindow("ch-his-" + run, "10");
        String a1 = submit(windowId, "user-1", "5", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "1.5"), "alice");
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(1, history.get("usages").size());
        assertEquals("1.5", history.get("usages").get(0).get("amount").asText());
        assertEquals("1.5", history.get("usages").get(0).get("usedAfter").asText());
        assertEquals("3.5", history.get("usages").get(0).get("heldAfter").asText());
        assertEquals("1.5", history.get("allocations").get(0).get("usedAmount").asText());
        // 历史同时包含转让流水数组（即使为空）
        assertTrue(history.has("transfers"));
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
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void insufficientHeldReturns422AndLeavesNoTrace() throws Exception {
        long windowId = createWindow("ch-422-" + run, "10");
        String a1 = submit(windowId, "user-1", "3", "alice");
        approve(a1, 200);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "3.001"), "alice", 422);
        JsonNode node = allocation(windowId, a1);
        assertEquals("3", node.get("heldAmount").asText());
        assertEquals("0", node.get("usedAmount").asText());
        assertEquals(0, usages(windowId).get("usages").size());
        // 申请仍可正常核销 3
        postOk("/api/usages", usageBody(key("uc"), key("uk"), a1, "3"), "alice");
    }

    @Test
    void invalidUsageReturns400Or404Or409() throws Exception {
        long windowId = createWindow("ch-conf-" + run, "10");
        String a1 = submit(windowId, "user-1", "4", "alice");
        String a2 = submit(windowId, "user-2", "4", "bob");
        approve(a1, 200);

        // REQUESTED 申请不能核销 -> 409
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a2, "1"), "bob", 409);
        // 非原申请人核销 -> 409
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "1"), "bob", 409);
        // 申请不存在 -> 404
        postJson("/api/usages", usageBody(key("uc"), key("uk"), "missing-" + run, "1"),
                "alice", 404);
        mvc.perform(get("/api/usages/missing-" + run)).andExpect(status().isNotFound());
        // 流水查询窗口不存在 -> 404
        mvc.perform(get("/api/windows/999999999/usages")).andExpect(status().isNotFound());
        // 非法水量：0、负数、超 3 位小数、非数字 -> 400
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "0"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "-1"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "1.0001"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "abc"), "alice", 400);
        // 非法键、缺 X-Actor-Id -> 400
        postJson("/api/usages", usageBody("", key("uk"), a1, "1"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), "bad key!", a1, "1"), "alice", 400);
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "1"), null, 400);

        // 取消后核销 -> 409
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        postJson("/api/usages", usageBody(key("uc"), key("uk"), a1, "1"), "alice", 409);
        // 全部失败后无流水、计数不变
        assertEquals(0, usages(windowId).get("usages").size());
        JsonNode node = allocation(windowId, a1);
        assertEquals("0", node.get("usedAmount").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeyReplaysFirstUsageResult() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "10");
        String a1 = submit(windowId, "user-1", "5", "alice");
        approve(a1, 200);
        String commandKey = key("uc");
        String usageKey = key("uk");
        Map<String, Object> body = usageBody(commandKey, usageKey, a1, "2");
        JsonNode first = postOk("/api/usages", body, "alice");
        JsonNode replay = postOk("/api/usages", body, "alice");
        assertEquals(first, replay);
        // 只核销一次
        assertEquals(1, usages(windowId).get("usages").size());
        assertEquals("3", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("2", allocation(windowId, a1).get("usedAmount").asText());
        // 同 commandKey 改水量 -> 409；改 usageKey -> 409
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "3"), "alice", 409);
        postJson("/api/usages", usageBody(commandKey, key("uk"), a1, "2"), "alice", 409);
    }

    @Test
    void failedUsageDoesNotOccupyKeys() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "10");
        String a1 = submit(windowId, "user-1", "2", "alice");
        approve(a1, 200);
        String commandKey = key("uc");
        String usageKey = key("uk");
        // 首次 422 不占 commandKey 也不占 usageKey
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "3"), "alice", 422);
        // 换 commandKey 复用同一 usageKey 的失败尝试也不占 usageKey
        postJson("/api/usages", usageBody(key("uc"), usageKey, a1, "3"), "alice", 422);
        assertEquals(0, usages(windowId).get("usages").size());
        // 同 commandKey + 同 usageKey 改为合法水量 -> 成功，证明失败不占键
        JsonNode ok = postOk("/api/usages", usageBody(commandKey, usageKey, a1, "1"), "alice");
        assertEquals("1", ok.get("amount").asText());
        assertEquals(1, usages(windowId).get("usages").size());
    }

    @Test
    void reusedUsageKeyWithNewCommandKeyReturns409() throws Exception {
        long w1 = createWindow("ch-uk1-" + run, "10");
        long w2 = createWindow("ch-uk2-" + run, "10");
        String a1 = submit(w1, "u1", "4", "alice");
        approve(a1, 200);
        String a2 = submit(w2, "u2", "4", "alice");
        approve(a2, 200);
        String usageKey = key("uk");
        postOk("/api/usages", usageBody(key("uc"), usageKey, a1, "1"), "alice");
        // 换 commandKey 复用同一 usageKey（即使指向另一窗口的另一申请）-> 409，不留第二条流水
        postJson("/api/usages", usageBody(key("uc"), usageKey, a2, "1"), "alice", 409);
        assertEquals(0, usages(w2).get("usages").size());
        assertEquals("4", allocation(w2, a2).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentUsagesOnOneAllocationNeverGoNegative() throws Exception {
        long windowId = createWindow("ch-race-use-" + run, "10");
        String a1 = submit(windowId, "user-1", "5", "alice");
        approve(a1, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), key("uk"), a1, "3", "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), key("uk"), a1, "3", "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "并发核销: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 42 ? 1 : 0));
        JsonNode node = allocation(windowId, a1);
        assertEquals("2", node.get("heldAmount").asText());
        assertEquals("3", node.get("usedAmount").asText());
        assertEquals(1, usages(windowId).get("usages").size());
        JsonNode cap = capacity(windowId);
        assertEquals("5", cap.get("occupiedTotal").asText());
    }

    @Test
    void sameUsageKeyRacedByTwoCommandsSucceedsOnce() throws Exception {
        long windowId = createWindow("ch-race-key-" + run, "10");
        String a1 = submit(windowId, "user-1", "5", "alice");
        approve(a1, 200);
        String usageKey = key("uk");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), usageKey, a1, "2", "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), usageKey, a1, "2", "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "同键并发: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        assertEquals(1, usages(windowId).get("usages").size());
        assertEquals("2", allocation(windowId, a1).get("usedAmount").asText());
    }

    @Test
    void usageAndTransferFromSameSourceSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-ut-" + run, "10");
        String source = submit(windowId, "user-src", "5", "alice");
        String target = submit(windowId, "user-t", "3", "bob");
        approve(source, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> usageFuture = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), key("uk"), source, "3", "alice");
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
        int usageStatus = usageFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 核销 3 与转让 3 争抢同一份 5 的持有额度：恰好一个成功
        assertEquals(1, (usageStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0),
                "usage=" + usageStatus + " transfer=" + transferStatus);
        if (usageStatus == 200) {
            assertEquals(422, transferStatus);
            assertEquals("2", allocation(windowId, source).get("heldAmount").asText());
            assertEquals("3", allocation(windowId, source).get("usedAmount").asText());
        } else {
            assertEquals(422, usageStatus);
            assertEquals("2", allocation(windowId, source).get("heldAmount").asText());
            assertEquals("0", allocation(windowId, source).get("usedAmount").asText());
            assertEquals("APPROVED", allocation(windowId, target).get("status").asText());
        }
        // 额度守恒：窗口占用恒为 5，持有非负，流水至多一条
        JsonNode cap = capacity(windowId);
        assertEquals("5", cap.get("occupiedTotal").asText());
        assertEquals(usageStatus == 200 ? 1 : 0, usages(windowId).get("usages").size());
    }

    @Test
    void usageAndCancelOfSameSourceSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-uc-" + run, "10");
        String source = submit(windowId, "user-src", "5", "alice");
        approve(source, 200);
        String cancelCommand = key("cc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> usageFuture = pool.submit(() -> {
            gate.await();
            return usageStatus(key("uc"), key("uk"), source, "3", "alice");
        });
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(cancelCommand, source, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int usageStatus = usageFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 取消先提交：核销 409，已用 0、占用 0；核销先提交：取消仍成功（释放剩余 2），已用 3 继续占容量
        if (usageStatus == 200) {
            assertEquals(200, cancelStatus);
            assertEquals("CANCELLED", allocation(windowId, source).get("status").asText());
            assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
            assertEquals("3", allocation(windowId, source).get("usedAmount").asText());
            assertEquals("3", capacity(windowId).get("occupiedTotal").asText());
            assertEquals(1, usages(windowId).get("usages").size());
        } else {
            assertEquals(409, usageStatus);
            assertEquals(200, cancelStatus);
            assertEquals("0", capacity(windowId).get("occupiedTotal").asText());
            assertEquals(0, usages(windowId).get("usages").size());
        }
    }
}
