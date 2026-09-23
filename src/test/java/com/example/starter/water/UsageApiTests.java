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
 * 实际用水核销 API 测试：主流程、已用/未用/已占用/可用口径、失败回滚、幂等与并发守恒。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
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

    private int consumeStatus(String commandKey, String usageKey, String allocationKey,
                              String amount, String actor) {
        try {
            waterService.consumeUsage(commandKey, usageKey, allocationKey, amount, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode usages(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/usages");
    }

    // ------------------------------------------------------------------
    // 主流程与口径
    // ------------------------------------------------------------------

    @Test
    void consumeReducesHeldAddsUsedAndKeepsOccupiedUnchanged() throws Exception {
        long windowId = createWindow("ch-use-" + run, "10");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);

        JsonNode cap0 = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("0", cap0.get("usedTotal").asText());
        assertEquals("6", cap0.get("approvedTotal").asText());
        assertEquals("6", cap0.get("occupiedTotal").asText());
        assertEquals("4", cap0.get("remaining").asText());

        String commandKey = key("ukc");
        String usageKey = key("uk");
        JsonNode result = postOk("/api/usages", usageBody(commandKey, usageKey, a1, "2.500"), "alice");
        assertEquals(usageKey, result.get("usage").get("usageKey").asText());
        assertEquals(a1, result.get("usage").get("allocationKey").asText());
        assertEquals(windowId, result.get("usage").get("windowId").asLong());
        assertEquals("2.5", result.get("usage").get("amount").asText());
        assertEquals("alice", result.get("usage").get("actor").asText());
        assertTrue(result.get("usage").has("createdUtc"));
        // 核销后申请口径快照
        assertEquals("2.5", result.get("usedAmount").asText());
        assertEquals("3.5", result.get("heldAmount").asText());
        assertEquals("6", result.get("occupiedTotal").asText());
        assertEquals("10", result.get("availableTotal").asText());
        assertEquals("4", result.get("remaining").asText());

        // 申请视图：原水量不变，持有=未用 3.5，累计已用 2.5
        JsonNode node = allocation(windowId, a1);
        assertEquals("6", node.get("amount").asText());
        assertEquals("3.5", node.get("heldAmount").asText());
        assertEquals("2.5", node.get("usedAmount").asText());
        assertEquals("APPROVED", node.get("status").asText());

        // 容量：已用 2.5 + 持有 3.5 = 已占用 6，核销不改变占用总量与余量
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("2.5", cap.get("usedTotal").asText());
        assertEquals("3.5", cap.get("approvedTotal").asText());
        assertEquals("6", cap.get("occupiedTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 核销流水查询
        JsonNode list = usages(windowId);
        assertEquals(1, list.get("usages").size());
        assertEquals(usageKey, list.get("usages").get(0).get("usageKey").asText());
        // 历史扩展容量：含核销流水
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("usages").size());

        // 再核销 3.5：持有清零、已用 6，仍 APPROVED
        JsonNode result2 = postOk("/api/usages", usageBody(key("ukc"), key("uk"), a1, "3.5"), "alice");
        assertEquals("6", result2.get("usedAmount").asText());
        assertEquals("0", result2.get("heldAmount").asText());
        assertEquals("6", result2.get("occupiedTotal").asText());
        assertEquals(2, usages(windowId).get("usages").size());
    }

    @Test
    void consumedWaterStaysOccupiedAfterCancelAndCannotBeReused() throws Exception {
        long windowId = createWindow("ch-used-cancel-" + run, "10");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("ukc"), key("uk"), a1, "4"), "alice");

        // 取消：仅释放未用持有额度 2，已用 4 仍占窗口容量
        JsonNode cancelled = postOk("/api/allocations/" + a1 + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("heldAmount").asText());
        assertEquals("4", cancelled.get("usedAmount").asText());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("4", cap.get("usedTotal").asText());
        assertEquals("0", cap.get("approvedTotal").asText());
        assertEquals("4", cap.get("occupiedTotal").asText());
        assertEquals("6", cap.get("remaining").asText());

        // 已用 4 不释放：新申请最多再批 6
        String a2 = submit(windowId, "user-2", "6.001", "bob");
        approve(a2, 422);
        String a3 = submit(windowId, "user-3", "6", "carol");
        approve(a3, 200);

        // 取消后不得再核销；流水保留不可变
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), a1, "1"), "alice", 409);
        assertEquals(1, usages(windowId).get("usages").size());
        assertEquals("4", allocation(windowId, a1).get("usedAmount").asText());
    }

    @Test
    void transferCanOnlyMoveRemainingHeldNotUsedPart() throws Exception {
        long windowId = createWindow("ch-use-tx-" + run, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "3", "bob");
        approve(source, 200);
        // 核销 4：仅剩未用持有 2
        postOk("/api/usages", usageBody(key("ukc"), key("uk"), source, "4"), "alice");

        // 转让 3 超过剩余持有 2 -> 422，不能转走已用部分
        postJson("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice", 422);

        // 转让 2 成功：源持有清零、已用仍 4；目标持有 2；窗口已用 4 + 持有 2 = 占用 6
        String target2 = submit(windowId, "user-dst2", "2", "carol");
        postOk("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target2), "alice");
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("0", sourceNode.get("heldAmount").asText());
        assertEquals("4", sourceNode.get("usedAmount").asText());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("4", cap.get("usedTotal").asText());
        assertEquals("2", cap.get("approvedTotal").asText());
        assertEquals("6", cap.get("occupiedTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 目标持有 2 可由目标本人核销
        postOk("/api/usages", usageBody(key("ukc"), key("uk"), target2, "2"), "carol");
        JsonNode cap2 = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("6", cap2.get("usedTotal").asText());
        assertEquals("0", cap2.get("approvedTotal").asText());
        assertEquals("6", cap2.get("occupiedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void insufficientHeldReturns422AndRollsEverythingBack() throws Exception {
        long windowId = createWindow("ch-use-422-" + run, "10");
        String a1 = submit(windowId, "user-1", "3", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("ukc"), key("uk"), a1, "2"), "alice");

        // 剩余持有 1，核销 1.5 -> 422
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), a1, "1.5"), "alice", 422);

        // 计数与流水均无残留
        JsonNode node = allocation(windowId, a1);
        assertEquals("1", node.get("heldAmount").asText());
        assertEquals("2", node.get("usedAmount").asText());
        assertEquals(1, usages(windowId).get("usages").size());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("2", cap.get("usedTotal").asText());
        assertEquals("1", cap.get("approvedTotal").asText());
        assertEquals("3", cap.get("occupiedTotal").asText());
    }

    @Test
    void usageConflictsReturn400Or404Or409() throws Exception {
        long windowId = createWindow("ch-use-conf-" + run, "10");
        String requested = submit(windowId, "user-r", "2", "alice");
        String approved = submit(windowId, "user-a", "2", "alice");
        approve(approved, 200);

        // REQUESTED 申请不能核销 -> 409
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), requested, "1"), "alice", 409);
        // 非原申请人核销 -> 409
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "1"), "bob", 409);
        // 申请不存在 -> 404
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), "missing-" + run, "1"), "alice", 404);
        // 非法水量：0、负数、超 3 位小数、非数字 -> 400
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "0"), "alice", 400);
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "-1"), "alice", 400);
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "1.0001"), "alice", 400);
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "abc"), "alice", 400);
        // 非法键 -> 400
        postJson("/api/usages", usageBody("", key("uk"), approved, "1"), "alice", 400);
        postJson("/api/usages", usageBody(key("ukc"), "bad key!", approved, "1"), "alice", 400);
        // 缺少 X-Actor-Id -> 400
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "1"), null, 400);
        // 已取消申请 -> 409
        postOk("/api/allocations/" + approved + "/cancel", Map.of("commandKey", key("cc")), "alice");
        postJson("/api/usages", usageBody(key("ukc"), key("uk"), approved, "1"), "alice", 409);
        // 流水查询窗口不存在 -> 404
        mvc.perform(get("/api/windows/999999999/usages")).andExpect(status().isNotFound());

        // 全部失败后无任何核销流水、计数不变
        assertEquals(0, usages(windowId).get("usages").size());
    }

    @Test
    void usageKeyIsGloballyUniqueAcrossCommandKeys() throws Exception {
        long w1 = createWindow("ch-uk1-" + run, "10");
        long w2 = createWindow("ch-uk2-" + run, "10");
        String a1 = submit(w1, "u1", "3", "alice");
        String a2 = submit(w2, "u2", "3", "alice");
        approve(a1, 200);
        approve(a2, 200);
        String usageKey = key("uk");
        postOk("/api/usages", usageBody(key("ukc"), usageKey, a1, "1"), "alice");

        // 换 commandKey 复用同一 usageKey（即使指向另一窗口的另一申请）-> 409
        postJson("/api/usages", usageBody(key("ukc"), usageKey, a2, "1"), "alice", 409);
        assertEquals(0, usages(w2).get("usages").size());
        assertEquals("3", allocation(w2, a2).get("heldAmount").asText());
        assertEquals("0", allocation(w2, a2).get("usedAmount").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeySameParamsReplaysFirstUsageResult() throws Exception {
        long windowId = createWindow("ch-use-idem-" + run, "10");
        String a1 = submit(windowId, "user-1", "4", "alice");
        approve(a1, 200);
        String commandKey = key("ukc");
        String usageKey = key("uk");
        Map<String, Object> body = usageBody(commandKey, usageKey, a1, "1.5");

        JsonNode first = postOk("/api/usages", body, "alice");
        JsonNode replay = postOk("/api/usages", body, "alice");
        assertEquals(first, replay);
        // 只产生一条核销流水、只扣一次
        assertEquals(1, usages(windowId).get("usages").size());
        assertEquals("2.5", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("1.5", allocation(windowId, a1).get("usedAmount").asText());

        // 同 commandKey 改参（水量/usageKey/操作人）-> 409
        postJson("/api/usages", usageBody(commandKey, key("uk"), a1, "1"), "alice", 409);
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "1"), "alice", 409);
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "1.5"), "bob", 409);
    }

    @Test
    void failedUsageDoesNotOccupyCommandKeyOrUsageKey() throws Exception {
        long windowId = createWindow("ch-use-failidem-" + run, "10");
        String a1 = submit(windowId, "user-1", "2", "alice");
        approve(a1, 200);
        String commandKey = key("ukc");
        String usageKey = key("uk");

        // 首次失败（422）不占用 commandKey 与 usageKey
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "3"), "alice", 422);
        postJson("/api/usages", usageBody(commandKey, usageKey, a1, "3"), "alice", 422);
        assertEquals(0, usages(windowId).get("usages").size());

        // 同 commandKey 改参执行合法核销 -> 成功，证明失败不占 commandKey
        JsonNode ok = postOk("/api/usages", usageBody(commandKey, key("uk"), a1, "1"), "alice");
        assertEquals("1", ok.get("usedAmount").asText());
        assertEquals(1, usages(windowId).get("usages").size());

        // 失败过的 usageKey 也可被新 commandKey 成功复用（失败同样不占 usageKey）
        postOk("/api/usages", usageBody(key("ukc"), usageKey, a1, "0.5"), "alice");
        assertEquals(2, usages(windowId).get("usages").size());
        assertEquals("1.5", allocation(windowId, a1).get("usedAmount").asText());
    }

    // ------------------------------------------------------------------
    // 与批准/限供的新占用公式
    // ------------------------------------------------------------------

    @Test
    void approveAndCurtailmentUseOccupiedFormulaIncludingUsed() throws Exception {
        long windowId = createWindow("ch-use-formula-" + run, "10");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        postOk("/api/usages", usageBody(key("ukc"), key("uk"), a1, "5"), "alice");
        // 占用 6（已用 5 + 持有 1），限供 6 允许；限供 5 低于占用 -> 409
        postJson("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "5"), null, 409);
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "6"), null);
        // 限供 6 后余量 0，新批 0.001 -> 422
        String a2 = submit(windowId, "user-2", "0.001", "bob");
        approve(a2, 422);
        // a1 取消释放未用 1，但已用 5 仍占用：限供 6 下余量变 1，可批 1
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        String a3 = submit(windowId, "user-3", "1", "carol");
        approve(a3, 200);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("5", cap.get("usedTotal").asText());
        assertEquals("1", cap.get("approvedTotal").asText());
        assertEquals("6", cap.get("occupiedTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
    }

    // ------------------------------------------------------------------
    // 并发守恒
    // ------------------------------------------------------------------

    @Test
    void concurrentUsageAndTransferFromOneAllocationNeverDoubleSpend() throws Exception {
        long windowId = createWindow("ch-use-race-tx-" + run, "10");
        String source = submit(windowId, "user-src", "5", "alice");
        String target = submit(windowId, "user-t", "3", "bob");
        approve(source, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String usageCommandKey = key("ukc");
        String usageKey = key("uk");
        String transferCommandKey = key("tkc");
        String transferKey = key("tk");
        Future<Integer> usageFuture = pool.submit(() -> {
            gate.await();
            return consumeStatus(usageCommandKey, usageKey, source, "3", "alice");
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommandKey, transferKey, source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int usageStatus = usageFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一 3 份未用水量：核销与转让恰好一个成功，另一个 422（持有不足）
        assertEquals(1, (usageStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0),
                "usage=" + usageStatus + " transfer=" + transferStatus);
        assertEquals(1, (usageStatus == 422 ? 1 : 0) + (transferStatus == 422 ? 1 : 0));
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("5", new java.math.BigDecimal(sourceNode.get("usedAmount").asText())
                .add(new java.math.BigDecimal(sourceNode.get("heldAmount").asText())).toPlainString());
        // 窗口占用恒为 5
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("5", cap.get("occupiedTotal").asText());
        assertEquals("5", new java.math.BigDecimal(cap.get("usedTotal").asText())
                .add(new java.math.BigDecimal(cap.get("approvedTotal").asText())).toPlainString());
        // 恰好一条核销流水或零条（转让胜），转让流水至多一条
        int usageCount = usages(windowId).get("usages").size();
        int transferCount = getOk("/api/windows/" + windowId + "/transfers").get("transfers").size();
        assertEquals(usageStatus == 200 ? 1 : 0, usageCount);
        assertEquals(transferStatus == 200 ? 1 : 0, transferCount);
    }

    @Test
    void concurrentUsagesNeverExceedHeldAmount() throws Exception {
        long windowId = createWindow("ch-use-race-use-" + run, "10");
        String a1 = submit(windowId, "user-1", "1.000", "alice");
        approve(a1, 200);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch gate = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // 在主线程预生成幂等键，避免 key() 的非线程安全计数在并发下产生重复键
            String commandKey = key("ukc");
            String uniqueUsageKey = key("uk");
            futures.add(pool.submit(() -> {
                gate.await();
                return consumeStatus(commandKey, uniqueUsageKey, a1, "0.3", "alice");
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
        // 0.3 * 3 = 0.9 成功，第 4 笔 0.3 超过剩余 0.1 -> 422
        assertEquals(3, ok);
        assertEquals(2, quota);
        JsonNode node = allocation(windowId, a1);
        assertEquals("0.9", node.get("usedAmount").asText());
        assertEquals("0.1", node.get("heldAmount").asText());
        assertEquals(3, usages(windowId).get("usages").size());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("1", cap.get("occupiedTotal").asText());
        assertEquals("9", cap.get("remaining").asText());
    }

    @Test
    void concurrentUsageAndCancelSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-use-race-cancel-" + run, "10");
        String a1 = submit(windowId, "user-1", "4", "alice");
        approve(a1, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String usageCommandKey = key("ukc");
        String usageKey = key("uk");
        String cancelCommandKey = key("cc");
        Future<Integer> usageFuture = pool.submit(() -> {
            gate.await();
            return consumeStatus(usageCommandKey, usageKey, a1, "3", "alice");
        });
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(cancelCommandKey, a1, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int usageStatus = usageFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 取消先提交：核销 409，持有全释放、已用 0；核销先提交：取消成功，只释放剩余 1、已用 3 保留
        assertEquals(200, cancelStatus);
        JsonNode node = allocation(windowId, a1);
        assertEquals("CANCELLED", node.get("status").asText());
        if (usageStatus == 200) {
            assertEquals("3", node.get("usedAmount").asText());
            assertEquals("0", node.get("heldAmount").asText());
            assertEquals(1, usages(windowId).get("usages").size());
            assertEquals("3", getOk("/api/windows/" + windowId + "/capacity").get("occupiedTotal").asText());
        } else {
            assertEquals(409, usageStatus);
            assertEquals("0", node.get("usedAmount").asText());
            assertEquals(0, usages(windowId).get("usages").size());
            assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("occupiedTotal").asText());
        }
    }
}
