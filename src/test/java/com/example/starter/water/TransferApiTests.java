package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同窗口额度转让 API 测试：主流程、持有额度口径、失败回滚、幂等与并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTests {

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
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
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

    private Map<String, Object> transferBody(String commandKey, String transferKey,
                                             String sourceKey, String targetKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return body;
    }

    private int transferStatus(String commandKey, String transferKey, String sourceKey,
                               String targetKey, String actor) {
        try {
            waterService.transferAllocation(commandKey, transferKey, sourceKey, targetKey, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode transfers(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/transfers");
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void transferMovesQuotaWithoutChangingApprovedTotal() throws Exception {
        long windowId = createWindow("ch-tx-" + run, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "2.500", "bob");
        approve(source, 200);

        String commandKey = key("tkc");
        String transferKey = key("tk");
        JsonNode result = postOk("/api/transfers",
                transferBody(commandKey, transferKey, source, target), "alice");
        assertEquals(transferKey, result.get("transferKey").asText());
        assertEquals(windowId, result.get("windowId").asLong());
        assertEquals(source, result.get("sourceAllocationKey").asText());
        assertEquals(target, result.get("targetAllocationKey").asText());
        assertEquals("2.5", result.get("amount").asText());
        assertEquals("alice", result.get("actor").asText());
        assertTrue(result.has("createdUtc"));

        // 容量：已批准总量不变（6），不额外扣减可用余量
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("6", cap.get("approvedTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 申请视图：原水量不可改写；源持有 3.5 仍 APPROVED；目标 APPROVED 且持有 2.5
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("6", sourceNode.get("amount").asText());
        assertEquals("3.5", sourceNode.get("heldAmount").asText());
        assertEquals("APPROVED", sourceNode.get("status").asText());
        JsonNode targetNode = allocation(windowId, target);
        assertEquals("2.5", targetNode.get("amount").asText());
        assertEquals("2.5", targetNode.get("heldAmount").asText());
        assertEquals("APPROVED", targetNode.get("status").asText());

        // 流水查询
        JsonNode list = transfers(windowId);
        assertEquals(1, list.get("transfers").size());
        assertEquals(transferKey, list.get("transfers").get(0).get("transferKey").asText());
    }

    @Test
    void sourceHeldZeroStaysApprovedCanCancelButCannotTransferAgain() throws Exception {
        long windowId = createWindow("ch-zero-" + run, "10");
        String source = submit(windowId, "user-src", "5", "alice");
        String target1 = submit(windowId, "user-dst1", "3", "bob");
        String target2 = submit(windowId, "user-dst2", "2", "carol");
        String target3 = submit(windowId, "user-dst3", "0.001", "dave");
        approve(source, 200);

        postOk("/api/transfers", transferBody(key("tkc"), key("tk"), source, target1), "alice");
        postOk("/api/transfers", transferBody(key("tkc"), key("tk"), source, target2), "alice");

        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("0", sourceNode.get("heldAmount").asText());
        assertEquals("APPROVED", sourceNode.get("status").asText());
        // 已批准总量仍为 5（0 + 3 + 2）
        assertEquals("5", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 持有额度为零不能继续转出 -> 422
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, target3), "alice", 422);
        // 仍可取消，取消后持有额度归零
        JsonNode cancelled = postOk("/api/allocations/" + source + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("heldAmount").asText());
        assertEquals("5", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void cancelSourceReleasesOnlyRemainingQuotaAndTargetCancelsItsOwn() throws Exception {
        long windowId = createWindow("ch-cancel-" + run, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "2.5", "bob");
        approve(source, 200);
        postOk("/api/transfers", transferBody(key("tkc"), key("tk"), source, target), "alice");

        // 取消源：仅释放剩余 3.5，不追回已转出的 2.5
        postOk("/api/allocations/" + source + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode cap1 = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("2.5", cap1.get("approvedTotal").asText());
        // 非目标申请人不能取消目标
        postJson("/api/allocations/" + target + "/cancel", Map.of("commandKey", key("cc")), "alice", 409);
        // 目标取消，释放其持有的 2.5
        postOk("/api/allocations/" + target + "/cancel", Map.of("commandKey", key("cc")), "bob");
        JsonNode cap2 = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("0", cap2.get("approvedTotal").asText());
        assertEquals("10", cap2.get("remaining").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void insufficientHeldReturns422AndChangesNothing() throws Exception {
        long windowId = createWindow("ch-422-" + run, "10");
        String source = submit(windowId, "user-src", "3", "alice");
        String target = submit(windowId, "user-dst", "5", "bob");
        approve(source, 200);

        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, target), "alice", 422);

        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("3", sourceNode.get("heldAmount").asText());
        assertEquals("APPROVED", sourceNode.get("status").asText());
        JsonNode targetNode = allocation(windowId, target);
        assertEquals("REQUESTED", targetNode.get("status").asText());
        assertEquals("0", targetNode.get("heldAmount").asText());
        assertEquals("3", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        assertEquals(0, transfers(windowId).get("transfers").size());
        // 目标仍是 REQUESTED，后续可走普通批准
        approve(target, 200);
    }

    @Test
    void invalidTransfersReturn409Or404Or400() throws Exception {
        long w1 = createWindow("ch-conf1-" + run, "10");
        long w2 = createWindow("ch-conf2-" + run, "10");
        String source = submit(w1, "user-src", "6", "alice");
        String sameUser = submit(w1, "user-src", "2", "alice");
        String otherUser = submit(w1, "user-other", "2", "zoe");
        String otherWindow = submit(w2, "user-dst", "2", "bob");
        String requestedSource = submit(w1, "user-req", "2", "alice");
        approve(source, 200);

        // 跨窗口 -> 409
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, otherWindow), "alice", 409);
        // 相同用水户 -> 409
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, sameUser), "alice", 409);
        // 源为 REQUESTED -> 409
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), requestedSource, otherUser),
                "alice", 409);
        // 操作人不是源申请人 -> 409
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, otherUser), "bob", 409);
        // 源/目标不存在 -> 404
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), "missing-" + run, otherUser),
                "alice", 404);
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, "missing-" + run),
                "alice", 404);
        // 目标已取消 -> 409
        postOk("/api/allocations/" + otherUser + "/cancel", Map.of("commandKey", key("cc")), "zoe");
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, otherUser), "alice", 409);
        // 目标已被普通批准 -> 409
        String approvedTarget = submit(w1, "user-done", "1", "bob");
        approve(approvedTarget, 200);
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, approvedTarget), "alice", 409);
        // 源已取消 -> 409
        String cancelledSource = submit(w1, "user-c", "1", "alice");
        approve(cancelledSource, 200);
        postOk("/api/allocations/" + cancelledSource + "/cancel", Map.of("commandKey", key("cc")), "alice");
        String freshTarget = submit(w1, "user-f", "1", "frank");
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), cancelledSource, freshTarget),
                "alice", 409);
        // 缺少 X-Actor-Id -> 400
        postJson("/api/transfers", transferBody(key("tkc"), key("tk"), source, freshTarget), null, 400);
        // 非法键 -> 400
        postJson("/api/transfers", transferBody("", key("tk"), source, freshTarget), "alice", 400);
        postJson("/api/transfers", transferBody(key("tkc"), "bad key!", source, freshTarget), "alice", 400);
        // 流水查询窗口不存在 -> 404
        mvc.perform(get("/api/windows/999999999/transfers")).andExpect(status().isNotFound());

        // 全部失败后无流水、额度不变
        assertEquals(0, transfers(w1).get("transfers").size());
        assertEquals("6", allocation(w1, source).get("heldAmount").asText());
    }

    @Test
    void transferKeyIsGloballyUniqueAcrossCommandKeys() throws Exception {
        long w1 = createWindow("ch-tk1-" + run, "10");
        long w2 = createWindow("ch-tk2-" + run, "10");
        String source1 = submit(w1, "u1", "4", "alice");
        String target1 = submit(w1, "u2", "1", "bob");
        approve(source1, 200);
        String transferKey = key("tk");
        postOk("/api/transfers", transferBody(key("tkc"), transferKey, source1, target1), "alice");

        // 换 commandKey 复用同一 transferKey（即使指向另一窗口的另一对申请）-> 409
        String source2 = submit(w2, "u3", "4", "alice");
        String target2 = submit(w2, "u4", "1", "carol");
        approve(source2, 200);
        postJson("/api/transfers", transferBody(key("tkc"), transferKey, source2, target2), "alice", 409);
        assertEquals(0, transfers(w2).get("transfers").size());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeyReplaysFirstTransferResult() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "10");
        String source = submit(windowId, "user-src", "4", "alice");
        String target = submit(windowId, "user-dst", "1", "bob");
        approve(source, 200);
        String commandKey = key("tkc");
        String transferKey = key("tk");
        Map<String, Object> body = transferBody(commandKey, transferKey, source, target);

        JsonNode first = postOk("/api/transfers", body, "alice");
        JsonNode replay = postOk("/api/transfers", body, "alice");
        assertEquals(first, replay);
        assertEquals(1, transfers(windowId).get("transfers").size());
        assertEquals("3", allocation(windowId, source).get("heldAmount").asText());

        // 同 commandKey 改参 -> 409
        postJson("/api/transfers", transferBody(commandKey, key("tk"), source, target), "alice", 409);
    }

    @Test
    void failedCommandDoesNotOccupyCommandKey() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "20");
        String source = submit(windowId, "user-src", "2", "alice");
        String tooBig = submit(windowId, "user-big", "5", "bob");
        approve(source, 200);
        String commandKey = key("tkc");

        // 首次失败（422）不占用 commandKey
        postJson("/api/transfers", transferBody(commandKey, key("tk"), source, tooBig), "alice", 422);
        // 同键同参仍然失败（业务条件未变），且不产生流水
        postJson("/api/transfers", transferBody(commandKey, key("tk"), source, tooBig), "alice", 422);
        // 同键改参执行另一个合法转让 -> 成功，证明失败不占键
        String okTarget = submit(windowId, "user-ok", "1", "carol");
        JsonNode ok = postOk("/api/transfers", transferBody(commandKey, key("tk"), source, okTarget), "alice");
        assertEquals(okTarget, ok.get("targetAllocationKey").asText());
        assertEquals(1, transfers(windowId).get("transfers").size());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void twoSourcesRacingSameTargetSucceedAtMostOnce() throws Exception {
        long windowId = createWindow("ch-race-target-" + run, "20");
        String sourceA = submit(windowId, "user-a", "4", "alice");
        String sourceB = submit(windowId, "user-b", "4", "bob");
        String target = submit(windowId, "user-t", "4", "carol");
        approve(sourceA, 200);
        approve(sourceB, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return transferStatus(key("tkc"), key("tk"), sourceA, target, "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return transferStatus(key("tkc"), key("tk"), sourceB, target, "bob");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "同一目标最多一次转让成功: " + s1 + "/" + s2);
        int conflict = (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0);
        assertEquals(1, conflict);
        // 汇总不变：4 + 4（胜方 0、负方 4、目标 4）
        assertEquals("8", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        assertEquals(1, transfers(windowId).get("transfers").size());
        JsonNode targetNode = allocation(windowId, target);
        assertEquals("APPROVED", targetNode.get("status").asText());
        assertEquals("4", targetNode.get("heldAmount").asText());
    }

    @Test
    void concurrentTransfersFromOneSourceNeverGoNegative() throws Exception {
        long windowId = createWindow("ch-race-source-" + run, "20");
        String source = submit(windowId, "user-src", "5", "alice");
        String target1 = submit(windowId, "user-t1", "3", "bob");
        String target2 = submit(windowId, "user-t2", "3", "carol");
        approve(source, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return transferStatus(key("tkc"), key("tk"), source, target1, "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return transferStatus(key("tkc"), key("tk"), source, target2, "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一笔成功，另一笔持有额度不足 422
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "源并发转出: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("2", sourceNode.get("heldAmount").asText());
        assertEquals("APPROVED", sourceNode.get("status").asText());
        // 已批准总量不变 5（源 2 + 一个目标 3）
        assertEquals("5", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void transferAndNormalApproveOfSameTargetSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-approve-" + run, "10");
        String source = submit(windowId, "user-src", "4", "alice");
        String target = submit(windowId, "user-dst", "4", "bob");
        approve(source, 200);
        String approveCommand = key("ap");
        String transferCommand = key("tkc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            return transferStatus(transferCommand, key("tk"), source, target, "alice");
        });
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, target);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue((transferStatus == 200) != (approveStatus == 200),
                "转让与普通批准争抢同一目标恰好一个成功: transfer=" + transferStatus
                        + " approve=" + approveStatus);
        if (transferStatus == 200) {
            assertEquals(409, approveStatus);
            assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
            assertEquals("4", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        } else {
            assertEquals(409, transferStatus);
            assertEquals("4", allocation(windowId, source).get("heldAmount").asText());
        }
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }

    @Test
    void transferAndCancelOfSourceSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-cancel-" + run, "10");
        String source = submit(windowId, "user-src", "4", "alice");
        String target = submit(windowId, "user-dst", "4", "bob");
        approve(source, 200);
        String cancelCommand = key("cc");
        String transferCommand = key("tkc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            return transferStatus(transferCommand, key("tk"), source, target, "alice");
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
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 取消先提交则转让 409（源已取消）；转让先提交则取消成功（只释放剩余 0）
        if (transferStatus == 200) {
            assertEquals(200, cancelStatus);
            assertEquals("CANCELLED", allocation(windowId, source).get("status").asText());
            assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
            assertEquals("4", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        } else {
            assertEquals(409, transferStatus);
            assertEquals(200, cancelStatus);
            assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        }
    }

    @Test
    void transferKeepsApprovedTotalWithinFinalCurtailment() throws Exception {
        long windowId = createWindow("ch-race-curtail-" + run, "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "2", "bob");
        approve(source, 200);
        String curtailCommand = key("cu");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            return transferStatus(key("tkc"), key("tk"), source, target, "alice");
        });
        Future<Integer> curtailFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.createCurtailment(curtailCommand, windowId, "8");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        int curtailStatus = curtailFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 转让不改变已批准总量（恒为 6），与限供 8 并发二者可按提交顺序各自成立
        assertEquals(200, transferStatus);
        assertTrue(curtailStatus == 200 || curtailStatus == 409, "curtail=" + curtailStatus);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("6", cap.get("approvedTotal").asText());
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
        // 结果无重复流水、源持有额度正确
        assertEquals(1, ((ArrayNode) transfers(windowId).get("transfers")).size());
        assertEquals("4", allocation(windowId, source).get("heldAmount").asText());
        assertFalse(allocation(windowId, target).get("heldAmount").asText().isEmpty());
    }
}
