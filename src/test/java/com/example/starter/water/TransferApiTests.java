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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同窗口额度转让测试：主流程、失败回滚、持有额度语义、取消边界、
 * commandKey/transferKey 幂等与真实并发争抢（嵌入式 H2，MySQL 兼容模式）。
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

    private Map<String, Object> windowBody(String commandKey, String windowKey, String channelId,
                                           String start, String end, String planned) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return body;
    }

    private long createWindow(String channelId, String planned) throws Exception {
        JsonNode node = postOk("/api/windows",
                windowBody(key("wc"), key("wk"), channelId, "2026-11-01T00:00:00Z",
                        "2026-11-01T02:00:00Z", planned), null);
        return node.get("id").asLong();
    }

    private Map<String, Object> allocationBody(String commandKey, String allocationKey, long windowId,
                                               String userId, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        return body;
    }

    private String submit(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        postOk("/api/allocations",
                allocationBody(key("ac"), allocationKey, windowId, userId, amount), actor);
        return allocationKey;
    }

    private void approve(String allocationKey) throws Exception {
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
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

    private JsonNode transferOk(String sourceKey, String targetKey, String actor) throws Exception {
        return postOk("/api/transfers",
                transferBody(key("tc"), key("tk"), sourceKey, targetKey), actor);
    }

    private JsonNode allocationInHistory(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new AssertionError("history 中找不到申请 " + allocationKey);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private int transfersCount(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/transfers").get("transfers").size();
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void transferMovesHeldVolumeWithoutChangingApprovedTotal() throws Exception {
        long windowId = createWindow("ch-tf-" + run, "10");
        String source = submit(windowId, "alice", "6", "alice");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source);

        JsonNode before = capacity(windowId);
        assertEquals("6", before.get("approvedTotal").asText());
        assertEquals("6", before.get("approvedOriginalTotal").asText());

        JsonNode transfer = transferOk(source, target, "alice");
        assertEquals("4", transfer.get("amount").asText());
        assertEquals("2", transfer.get("sourceHeldVolumeAfter").asText());
        assertEquals(windowId, transfer.get("windowId").asLong());

        // 源：仍 APPROVED，原申请水量不变，持有额度等量减少
        JsonNode sourceAfter = allocationInHistory(windowId, source);
        assertEquals("APPROVED", sourceAfter.get("status").asText());
        assertEquals("6", sourceAfter.get("amount").asText());
        assertEquals("2", sourceAfter.get("heldVolume").asText());
        // 目标：REQUESTED -> APPROVED，持有额度等于原申请水量
        JsonNode targetAfter = allocationInHistory(windowId, target);
        assertEquals("APPROVED", targetAfter.get("status").asText());
        assertEquals("4", targetAfter.get("amount").asText());
        assertEquals("4", targetAfter.get("heldVolume").asText());

        // 窗口已批准持有总量不变，原水量汇总变为 10，不额外扣减可用余量
        JsonNode cap = capacity(windowId);
        assertEquals("6", cap.get("approvedTotal").asText());
        assertEquals("10", cap.get("approvedOriginalTotal").asText());
        assertEquals("4", cap.get("remaining").asText());

        // 流水可查：单条与窗口列表
        JsonNode single = getOk("/api/transfers/" + transfer.get("transferKey").asText());
        assertEquals(transfer, single);
        JsonNode list = getOk("/api/windows/" + windowId + "/transfers");
        assertEquals(1, list.get("transfers").size());
        assertEquals(transfer.get("transferKey").asText(),
                list.get("transfers").get(0).get("transferKey").asText());
    }

    @Test
    void transferUsesBigDecimalWithThreeDecimals() throws Exception {
        long windowId = createWindow("ch-tfbd-" + run, "1.000");
        String source = submit(windowId, "alice", "0.500", "alice");
        String target = submit(windowId, "bob", "0.333", "bob");
        approve(source);
        JsonNode transfer = transferOk(source, target, "alice");
        assertEquals("0.333", transfer.get("amount").asText());
        assertEquals("0.167", transfer.get("sourceHeldVolumeAfter").asText());
        assertEquals("0.5", capacity(windowId).get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 持有额度不足与零额度边界
    // ------------------------------------------------------------------

    @Test
    void insufficientHeldVolumeReturns422AndChangesNothing() throws Exception {
        long windowId = createWindow("ch-tf422-" + run, "10");
        String source = submit(windowId, "alice", "6", "alice");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source);
        // 先转出 4，源只剩 2
        String other = submit(windowId, "carol", "4", "carol");
        transferOk(source, other, "alice");
        // 再向 4 水量的目标转让 -> 422
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, target), "alice", 422);
        // 无任何额度变化、无流水
        assertEquals("2", allocationInHistory(windowId, source).get("heldVolume").asText());
        assertEquals("REQUESTED", allocationInHistory(windowId, target).get("status").asText());
        assertEquals("0", allocationInHistory(windowId, target).get("heldVolume").asText());
        assertEquals(1, transfersCount(windowId));
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void zeroHeldSourceStaysApprovedCanCancelButCannotTransferAgain() throws Exception {
        long windowId = createWindow("ch-tfzero-" + run, "10");
        String source = submit(windowId, "alice", "4", "alice");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source);
        transferOk(source, target, "alice");

        JsonNode sourceAfter = allocationInHistory(windowId, source);
        assertEquals("APPROVED", sourceAfter.get("status").asText());
        assertEquals("0", sourceAfter.get("heldVolume").asText());

        // 继续转出 -> 422（任何正水量目标都无法承载）
        String next = submit(windowId, "carol", "1", "carol");
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, next), "alice", 422);
        // 仍为 APPROVED 且持有 0
        assertEquals("APPROVED", allocationInHistory(windowId, source).get("status").asText());
        // 可以取消，取消后释放剩余额度 0，不追回已转出的 4
        JsonNode cancelled = postOk("/api/allocations/" + source + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("0", cancelled.get("heldVolume").asText());
        JsonNode cap = capacity(windowId);
        assertEquals("4", cap.get("approvedTotal").asText(), "已转给目标的 4 不追回");
        // 目标取消则释放其持有的 4
        postOk("/api/allocations/" + target + "/cancel", Map.of("commandKey", key("cc")), "bob");
        assertEquals("0", capacity(windowId).get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 冲突分支：失败均无额度变化
    // ------------------------------------------------------------------

    @Test
    void conflictBranchesReturn409WithoutChanges() throws Exception {
        long windowId = createWindow("ch-tf409-" + run, "20");
        long otherWindow = createWindow("ch-tf409b-" + run, "20");
        String source = submit(windowId, "alice", "5", "alice");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source);

        // 跨窗口目标
        String cross = submit(otherWindow, "bob", "4", "bob");
        assertTransferConflict(source, cross, "alice", windowId);
        // 相同用水户
        String sameUser = submit(windowId, "alice", "1", "alice");
        assertTransferConflict(source, sameUser, "alice", windowId);
        // 操作人不符
        assertTransferConflict(source, target, "mallory", windowId);
        // 源为 REQUESTED
        String pendingSource = submit(windowId, "carol", "5", "carol");
        assertTransferConflict(pendingSource, target, "carol", windowId);
        // 目标不是 REQUESTED：先完成一次转让，再以已 APPROVED 的目标重复转让
        transferOk(source, target, "alice");
        String anotherSource = submit(windowId, "dave", "5", "dave");
        approve(anotherSource);
        assertTransferConflict(anotherSource, target, "dave", windowId);
        // 源已取消：单独准备一个批准后取消的申请
        String cancelledSource = submit(windowId, "erin", "5", "erin");
        approve(cancelledSource);
        postOk("/api/allocations/" + cancelledSource + "/cancel",
                Map.of("commandKey", key("cc")), "erin");
        String anotherTarget = submit(windowId, "frank", "1", "frank");
        assertTransferConflict(cancelledSource, anotherTarget, "erin", windowId);

        // 全部失败尝试后，只有第一次转让生效；持有总量 = 源剩 1 + 目标 4 + dave 5 = 10
        JsonNode cap = capacity(windowId);
        assertEquals("10", cap.get("approvedTotal").asText());
        assertEquals(1, transfersCount(windowId));
        assertEquals("1", allocationInHistory(windowId, source).get("heldVolume").asText());
        assertEquals("4", allocationInHistory(windowId, target).get("heldVolume").asText());
        assertEquals("5", allocationInHistory(windowId, anotherSource).get("heldVolume").asText());
    }

    private void assertTransferConflict(String sourceKey, String targetKey, String actor, long windowId)
            throws Exception {
        String sourceHeld = allocationInHistory(windowId, sourceKey).get("heldVolume").asText();
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), sourceKey, targetKey), actor, 409);
        assertEquals(sourceHeld, allocationInHistory(windowId, sourceKey).get("heldVolume").asText(),
                "失败转让不得改变源持有额度");
    }

    @Test
    void notFoundAndInvalidArguments() throws Exception {
        long windowId = createWindow("ch-tfnf-" + run, "10");
        String source = submit(windowId, "alice", "4", "alice");
        approve(source);
        // 源/目标不存在 -> 404
        postJson("/api/transfers",
                transferBody(key("tc"), key("tk"), "missing-" + run, source), "alice", 404);
        postJson("/api/transfers",
                transferBody(key("tc"), key("tk"), source, "missing-" + run), "alice", 404);
        // 流水不存在 -> 404
        mvc.perform(get("/api/transfers/missing-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/transfers")).andExpect(status().isNotFound());
        // 源目标相同 -> 400
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, source), "alice", 400);
        // 缺 X-Actor-Id -> 400
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        transferBody(key("tc"), key("tk"), source, "x")))).andExpect(status().isBadRequest());
        // 空 transferKey -> 400
        postJson("/api/transfers",
                transferBody(key("tc"), "", source, "x"), "alice", 400);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void commandKeyReplaysFirstResultAndTransferKeyIsGloballyUnique() throws Exception {
        long windowId = createWindow("ch-tfidem-" + run, "10");
        String source = submit(windowId, "alice", "6", "alice");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source);

        String commandKey = key("tc");
        String transferKey = key("tk");
        Map<String, Object> body = transferBody(commandKey, transferKey, source, target);
        JsonNode first = postOk("/api/transfers", body, "alice");
        JsonNode replay = postOk("/api/transfers", body, "alice");
        assertEquals(first, replay);
        assertEquals(1, transfersCount(windowId));

        // 换 commandKey 但复用 transferKey -> 409
        postJson("/api/transfers", transferBody(key("tc"), transferKey, source, target), "alice", 409);
        // 同 commandKey 改参 -> 409
        postJson("/api/transfers", transferBody(commandKey, key("tk"), source, target), "alice", 409);
        // 同 commandKey 改操作人 -> 409
        postJson("/api/transfers", transferBody(commandKey, transferKey, source, target), "bob", 409);
    }

    @Test
    void failedTransferDoesNotOccupyCommandKey() throws Exception {
        long windowId = createWindow("ch-tfkey-" + run, "10");
        String source = submit(windowId, "alice", "2", "alice");
        String bigTarget = submit(windowId, "bob", "4", "bob");
        approve(source);

        String reusedCommand = key("tc");
        // 首次持有不足 422，commandKey 不占位
        postJson("/api/transfers", transferBody(reusedCommand, key("tk"), source, bigTarget), "alice", 422);
        // 同一个 commandKey 以新参数执行合法转让 -> 成功
        String smallTarget = submit(windowId, "carol", "1", "carol");
        JsonNode ok = postOk("/api/transfers",
                transferBody(reusedCommand, key("tk"), source, smallTarget), "alice");
        assertEquals("1", ok.get("amount").asText());
        // 失败的 transferKey 同样不占位，可在合法命令中复用（新的不可变流水）
        String failedTransferKey = key("tk");
        postJson("/api/transfers",
                transferBody(key("tc"), failedTransferKey, source, bigTarget), "alice", 422);
        String lastTarget = submit(windowId, "dave", "1", "dave");
        JsonNode second = postOk("/api/transfers",
                transferBody(key("tc"), failedTransferKey, source, lastTarget), "alice");
        assertEquals(failedTransferKey, second.get("transferKey").asText());
        assertEquals(2, transfersCount(windowId));
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void twoSourcesContendingSameTargetSucceedAtMostOnce() throws Exception {
        long windowId = createWindow("ch-tfracet-" + run, "20");
        String source1 = submit(windowId, "alice", "6", "alice");
        String source2 = submit(windowId, "carol", "6", "carol");
        String target = submit(windowId, "bob", "4", "bob");
        approve(source1);
        approve(source2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String sourceKey : List.of(source1, source2)) {
            String commandKey = key("tc");
            String transferKey = key("tk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.transferAllocation(commandKey, transferKey, sourceKey, target,
                            sourceKey.equals(source1) ? "alice" : "carol");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else {
                assertEquals(409, status, "争抢失败必须是 409");
                conflict++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一目标最多一次转让成功");
        assertEquals(1, conflict);

        // 目标只持有一份 4；窗口持有总量不变 12（胜方剩 2 + 负方 6 + 目标 4）
        JsonNode targetAfter = allocationInHistory(windowId, target);
        assertEquals("APPROVED", targetAfter.get("status").asText());
        assertEquals("4", targetAfter.get("heldVolume").asText());
        assertEquals(1, transfersCount(windowId));
        JsonNode cap = capacity(windowId);
        assertEquals("12", cap.get("approvedTotal").asText());
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }

    @Test
    void oneSourceConcurrentTransfersNeverGoNegative() throws Exception {
        // 重复多轮，提高真实并发交错概率
        for (int round = 0; round < 5; round++) {
            long windowId = createWindow("ch-tfracen-" + run + "-" + round, "20");
            String source = submit(windowId, "alice", "4", "alice");
            String t1 = submit(windowId, "bob", "3", "bob");
            String t2 = submit(windowId, "carol", "3", "carol");
            approve(source);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (String targetKey : List.of(t1, t2)) {
                String commandKey = key("tc");
                String transferKey = key("tk");
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        waterService.transferAllocation(commandKey, transferKey, source, targetKey, "alice");
                        return 200;
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                }));
            }
            gate.countDown();
            int ok = 0;
            int quota = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else {
                    assertEquals(422, status, "持有不足必须返回 422");
                    quota++;
                }
            }
            pool.shutdown();
            assertEquals(1, ok, "4 个持有额度只能满足一笔 3");
            assertEquals(1, quota);

            String held = allocationInHistory(windowId, source).get("heldVolume").asText();
            assertEquals("1", held, "持有额度不得为负");
            assertEquals("4", capacity(windowId).get("approvedTotal").asText(), "转让不改变持有总量");
            assertEquals(1, transfersCount(windowId));
        }
    }

    @Test
    void transferInterleavedWithApproveAndCurtailmentNeverExceedsAvailable() throws Exception {
        long windowId = createWindow("ch-tfracemix-" + run, "10");
        String source = submit(windowId, "alice", "6", "alice");
        String transferTarget = submit(windowId, "bob", "4", "bob");
        String approveTarget = submit(windowId, "carol", "4", "carol");
        approve(source);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        String transferCommand = key("tc");
        String transferKey = key("tk");
        String approveCommand = key("ap");
        String curtailCommand = key("cu");
        List<Future<String>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, transferTarget, "alice");
                return "transfer:200";
            } catch (ApiException e) {
                return "transfer:" + e.status().value();
            }
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, approveTarget);
                return "approve:200";
            } catch (ApiException e) {
                return "approve:" + e.status().value();
            }
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            try {
                waterService.createCurtailment(curtailCommand, windowId, "8");
                return "curtail:200";
            } catch (ApiException e) {
                return "curtail:" + e.status().value();
            }
        }));
        gate.countDown();
        for (Future<String> future : futures) {
            assertNotEquals("500", future.get(30, TimeUnit.SECONDS).split(":")[1]);
        }
        pool.shutdown();

        // 不变式：按提交顺序裁决后，已批准持有总量不超过最终可用总量
        JsonNode cap = capacity(windowId);
        double approved = Double.parseDouble(cap.get("approvedTotal").asText());
        double available = Double.parseDouble(cap.get("availableTotal").asText());
        assertTrue(approved <= available + 0.000001,
                "approved=" + approved + " available=" + available);
        // 转让若成功，窗口持有总量与其无关；源持有额度不得为负
        assertTrue(Double.parseDouble(allocationInHistory(windowId, source).get("heldVolume").asText()) >= 0);
    }
}
