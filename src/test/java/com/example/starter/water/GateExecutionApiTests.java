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
 * 合成闸门执行与双池水位门禁 API 测试：主流程、失败原子性、幂等与并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GateExecutionApiTests {

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

    private Map<String, Object> basinWindowBody(String commandKey, String windowKey, String channelId,
                                                long upperArea, long lowerArea,
                                                String upperInit, String lowerInit,
                                                String upperMin, String upperMax,
                                                String lowerMin, String lowerMax) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", "100");
        body.put("upperAreaM2", upperArea);
        body.put("lowerAreaM2", lowerArea);
        body.put("upperInitialVolumeM3", upperInit);
        body.put("lowerInitialVolumeM3", lowerInit);
        body.put("upperMinVolumeM3", upperMin);
        body.put("upperMaxVolumeM3", upperMax);
        body.put("lowerMinVolumeM3", lowerMin);
        body.put("lowerMaxVolumeM3", lowerMax);
        return body;
    }

    private long createBasinWindow(String channelId, long upperArea, long lowerArea,
                                   String upperInit, String lowerInit,
                                   String upperMin, String upperMax,
                                   String lowerMin, String lowerMax) throws Exception {
        JsonNode node = postOk("/api/basin-windows",
                basinWindowBody(key("bwc"), key("bwk"), channelId, upperArea, lowerArea,
                        upperInit, lowerInit, upperMin, upperMax, lowerMin, lowerMax), "operator");
        return node.get("window").get("id").asLong();
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

    private Map<String, Object> executeBody(String commandKey, String executionKey, String volume) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("executionKey", executionKey);
        body.put("volume", volume);
        return body;
    }

    private JsonNode executeOk(String allocationKey, String volume, String actor) throws Exception {
        return postOk("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("ec"), key("ek"), volume), actor);
    }

    private JsonNode basins(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/basins");
    }

    private JsonNode executions(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/executions");
    }

    private int executeStatus(String commandKey, String executionKey, String allocationKey,
                              String volume, String actor) {
        try {
            waterService.executeAllocation(commandKey, allocationKey, executionKey, volume, null, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 主流程：题干合成场景
    // ------------------------------------------------------------------

    @Test
    void syntheticScenarioExecuteBoundsFailureAndCancelKeepsExecuted() throws Exception {
        // 合成两池：面积均 10，上池 30 [10,40]，下池 10 [5,30]
        long windowId = createBasinWindow("ch-syn-" + run, 10, 10, "30", "10", "10", "40", "5", "30");
        JsonNode basins0 = basins(windowId);
        assertEquals("30", basins0.get("upperVolumeM3").asText());
        assertEquals("10", basins0.get("lowerVolumeM3").asText());
        assertEquals("3", basins0.get("upperLevelM").asText());
        assertEquals("1", basins0.get("lowerLevelM").asText());
        assertEquals("2", basins0.get("levelDiffM").asText());

        String allocationKey = submit(windowId, "user-1", "30", "alice");
        approve(allocationKey, 200);

        // 执行 5：上池 25、下池 15，水位 2.5 与 1.5，合法
        JsonNode executed = executeOk(allocationKey, "5", "alice");
        assertEquals("5", executed.get("volume").asText());
        assertEquals("30", executed.get("upperBeforeM3").asText());
        assertEquals("25", executed.get("upperAfterM3").asText());
        assertEquals("10", executed.get("lowerBeforeM3").asText());
        assertEquals("15", executed.get("lowerAfterM3").asText());
        assertEquals("3", executed.get("upperLevelBeforeM").asText());
        assertEquals("2.5", executed.get("upperLevelAfterM").asText());
        assertEquals("1", executed.get("lowerLevelBeforeM").asText());
        assertEquals("1.5", executed.get("lowerLevelAfterM").asText());
        assertEquals("5", executed.get("executedTotal").asText());
        assertEquals("25", executed.get("remainingExecutable").asText());
        JsonNode basins1 = basins(windowId);
        assertEquals("25", basins1.get("upperVolumeM3").asText());
        assertEquals("15", basins1.get("lowerVolumeM3").asText());

        // 再执行 20：上池 5 越下界 10、下池 35 越上界 30 -> 422，整次失败
        MvcResult rejected = postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("ec"), key("ek"), "20"), "alice", 422);
        assertEquals("VOLUME_OUT_OF_BOUNDS",
                objectMapper.readTree(rejected.getResponse().getContentAsString()).get("code").asText());
        // 双池与执行账均不变
        JsonNode basins2 = basins(windowId);
        assertEquals("25", basins2.get("upperVolumeM3").asText());
        assertEquals("15", basins2.get("lowerVolumeM3").asText());
        JsonNode detail1 = executions(allocationKey);
        assertEquals(1, detail1.get("executions").size());
        assertEquals("5", detail1.get("executedTotal").asText());
        assertEquals("25", detail1.get("remainingExecutable").asText());

        // 取消只释放未执行的 25，首笔 5 不能因取消退回
        postOk("/api/allocations/" + allocationKey + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode basins3 = basins(windowId);
        assertEquals("25", basins3.get("upperVolumeM3").asText());
        assertEquals("15", basins3.get("lowerVolumeM3").asText());
        JsonNode detail2 = executions(allocationKey);
        assertEquals(1, detail2.get("executions").size());
        assertEquals("5", detail2.get("executedTotal").asText());
        assertEquals("0", detail2.get("remainingExecutable").asText());
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 水位差门禁（精确算术，显示舍入不参与）
    // ------------------------------------------------------------------

    @Test
    void levelDiffGateUsesExactArithmetic() throws Exception {
        // 面积 3/3，上池 31、下池 25：水位 31/3 与 25/3 均为无限循环，差恰为 2 米，允许执行
        long exactWindow = createBasinWindow("ch-exact-" + run, 3, 3, "31", "25", "0", "100", "0", "100");
        String a1 = submit(exactWindow, "user-1", "3", "alice");
        approve(a1, 200);
        JsonNode executed = executeOk(a1, "3", "alice");
        assertEquals("28", executed.get("upperAfterM3").asText());
        assertEquals("28", executed.get("lowerAfterM3").asText());

        // 上池水位低于下池：执行会拉大水位差。10/10，上 10（1 米）、下 20（2 米）
        long diffWindow = createBasinWindow("ch-diff-" + run, 10, 10, "10", "20", "0", "40", "0", "30");
        String a2 = submit(diffWindow, "user-2", "10", "bob");
        approve(a2, 200);
        // 执行 5：上 5（0.5）、下 25（2.5），差恰为 2 米，允许
        executeOk(a2, "5", "bob");
        // 再执行 1：上 4（0.4）、下 26（2.6），差 2.2 米 -> 422，双池不变
        MvcResult rejected = postJson("/api/allocations/" + a2 + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "bob", 422);
        assertEquals("LEVEL_DIFF_EXCEEDED",
                objectMapper.readTree(rejected.getResponse().getContentAsString()).get("code").asText());
        JsonNode basins = basins(diffWindow);
        assertEquals("5", basins.get("upperVolumeM3").asText());
        assertEquals("25", basins.get("lowerVolumeM3").asText());
        assertEquals(1, executions(a2).get("executions").size());

        // 执行前水位差已超 2 米（上 35 即 3.5 米、下 10 即 1 米）：任何执行 -> 422
        long violatedWindow = createBasinWindow("ch-vio-" + run, 10, 10, "35", "10", "0", "40", "0", "30");
        String a3 = submit(violatedWindow, "user-3", "1", "carol");
        approve(a3, 200);
        MvcResult beforeRejected = postJson("/api/allocations/" + a3 + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "carol", 422);
        assertEquals("LEVEL_DIFF_EXCEEDED",
                objectMapper.readTree(beforeRejected.getResponse().getContentAsString())
                        .get("code").asText());
        assertEquals("35", basins(violatedWindow).get("upperVolumeM3").asText());
        assertEquals(0, executions(a3).get("executions").size());
    }

    // ------------------------------------------------------------------
    // 累计执行不超过批准量
    // ------------------------------------------------------------------

    @Test
    void cumulativeExecutionCannotExceedApprovedAmount() throws Exception {
        long windowId = createBasinWindow("ch-cum-" + run, 10, 10, "30", "10", "0", "40", "0", "30");
        String allocationKey = submit(windowId, "user-1", "8", "alice");
        approve(allocationKey, 200);

        executeOk(allocationKey, "5", "alice");
        // 累计 5 + 4 > 8 -> 422
        MvcResult rejected = postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("ec"), key("ek"), "4"), "alice", 422);
        assertEquals("EXECUTION_EXCEEDS_QUOTA",
                objectMapper.readTree(rejected.getResponse().getContentAsString()).get("code").asText());
        // 恰好执行剩余 3 成功，之后持有额度为 0，再执行 1 -> 422
        executeOk(allocationKey, "3", "alice");
        postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "alice", 422);

        JsonNode detail = executions(allocationKey);
        assertEquals(2, detail.get("executions").size());
        assertEquals("8", detail.get("executedTotal").asText());
        assertEquals("0", detail.get("remainingExecutable").asText());
        JsonNode basins = basins(windowId);
        assertEquals("22", basins.get("upperVolumeM3").asText());
        assertEquals("18", basins.get("lowerVolumeM3").asText());
    }

    // ------------------------------------------------------------------
    // 状态门禁与普通窗口
    // ------------------------------------------------------------------

    @Test
    void executionRequiresApprovedAllocationAndBasinWindow() throws Exception {
        long windowId = createBasinWindow("ch-state-" + run, 10, 10, "30", "10", "0", "40", "0", "30");
        // REQUESTED -> 422
        String requested = submit(windowId, "user-1", "5", "alice");
        MvcResult r1 = postJson("/api/allocations/" + requested + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "alice", 422);
        assertEquals("EXECUTION_NOT_APPROVED",
                objectMapper.readTree(r1.getResponse().getContentAsString()).get("code").asText());
        // CANCELLED -> 422
        approve(requested, 200);
        postOk("/api/allocations/" + requested + "/cancel", Map.of("commandKey", key("cc")), "alice");
        postJson("/api/allocations/" + requested + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "alice", 422);
        // 申请不存在 -> 404
        postJson("/api/allocations/missing-" + run + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "alice", 404);

        // 普通窗口不启用双池规则 -> 422
        Map<String, Object> normalWindow = new HashMap<>();
        normalWindow.put("commandKey", key("wc"));
        normalWindow.put("windowKey", key("wk"));
        normalWindow.put("channelId", "ch-plain-" + run);
        normalWindow.put("startUtc", "2026-10-01T00:00:00Z");
        normalWindow.put("endUtc", "2026-10-01T02:00:00Z");
        normalWindow.put("plannedVolume", "100");
        long plainWindowId = postOk("/api/windows", normalWindow, null).get("id").asLong();
        String plainAllocation = submit(plainWindowId, "user-2", "5", "bob");
        approve(plainAllocation, 200);
        MvcResult r2 = postJson("/api/allocations/" + plainAllocation + "/executions",
                executeBody(key("ec"), key("ek"), "1"), "bob", 422);
        assertEquals("BASIN_NOT_CONFIGURED",
                objectMapper.readTree(r2.getResponse().getContentAsString()).get("code").asText());
        // 普通窗口无双池 -> 404
        mvc.perform(get("/api/windows/" + plainWindowId + "/basins")).andExpect(status().isNotFound());

        // 执行水量必须为正整数 -> 400
        String approved = submit(windowId, "user-3", "5", "carol");
        approve(approved, 200);
        postJson("/api/allocations/" + approved + "/executions",
                executeBody(key("ec"), key("ek"), "1.5"), "carol", 400);
        postJson("/api/allocations/" + approved + "/executions",
                executeBody(key("ec"), key("ek"), "0"), "carol", 400);
        postJson("/api/allocations/" + approved + "/executions",
                executeBody(key("ec"), key("ek"), "-3"), "carol", 400);
        postJson("/api/allocations/" + approved + "/executions",
                executeBody(key("ec"), key("ek"), "abc"), "carol", 400);
        // 缺少操作人 -> 400
        postJson("/api/allocations/" + approved + "/executions",
                executeBody(key("ec"), key("ek"), "1"), null, 400);
        // 全部失败后双池与执行账不变
        assertEquals("30", basins(windowId).get("upperVolumeM3").asText());
        assertEquals(0, executions(approved).get("executions").size());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotentReplayDoesNotDeliverTwiceAndFailuresReleaseKey() throws Exception {
        long windowId = createBasinWindow("ch-idem-" + run, 10, 10, "30", "10", "0", "40", "0", "30");
        String allocationKey = submit(windowId, "user-1", "30", "alice");
        approve(allocationKey, 200);

        // 同 requestId 同参重放：返回首次完整响应，不再次输水
        String commandKey = key("ec");
        String executionKey = key("ek");
        Map<String, Object> body = executeBody(commandKey, executionKey, "5");
        JsonNode first = postOk("/api/allocations/" + allocationKey + "/executions", body, "alice");
        JsonNode replay = postOk("/api/allocations/" + allocationKey + "/executions", body, "alice");
        assertEquals(first, replay);
        assertEquals("25", basins(windowId).get("upperVolumeM3").asText());
        assertEquals(1, executions(allocationKey).get("executions").size());

        // 同 requestId 改参 -> 409
        postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(commandKey, executionKey, "6"), "alice", 409);
        postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(commandKey, key("ek"), "5"), "alice", 409);
        // 换 requestId 复用同一 executionKey -> 409
        postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("ec"), executionKey, "5"), "alice", 409);
        assertEquals(1, executions(allocationKey).get("executions").size());

        // 失败不占键：先 422（越界），同键改参后成功
        String failKey = key("ec");
        postJson("/api/allocations/" + allocationKey + "/executions",
                executeBody(failKey, key("ek"), "20"), "alice", 422);
        JsonNode ok = postOk("/api/allocations/" + allocationKey + "/executions",
                executeBody(failKey, key("ek"), "2"), "alice");
        assertEquals("2", ok.get("volume").asText());
        assertEquals("23", basins(windowId).get("upperVolumeM3").asText());
        assertEquals(2, executions(allocationKey).get("executions").size());
    }

    // ------------------------------------------------------------------
    // 转让只转未执行量
    // ------------------------------------------------------------------

    @Test
    void transferOnlyMovesUnexecutedQuota() throws Exception {
        long windowId = createBasinWindow("ch-tx-" + run, 10, 10, "30", "10", "0", "40", "0", "30");
        String source = submit(windowId, "user-src", "10", "alice");
        approve(source, 200);
        // 执行 4 后持有额度 6，已执行 4 不可转让
        executeOk(source, "4", "alice");

        String tooBig = submit(windowId, "user-dst", "8", "bob");
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tkc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", tooBig);
        postJson("/api/transfers", transferBody, "alice", 422);

        String fits = submit(windowId, "user-dst2", "6", "carol");
        Map<String, Object> okBody = new HashMap<>();
        okBody.put("commandKey", key("tkc"));
        okBody.put("transferKey", key("tk"));
        okBody.put("sourceAllocationKey", source);
        okBody.put("targetAllocationKey", fits);
        postOk("/api/transfers", okBody, "alice");

        JsonNode detail = executions(source);
        assertEquals("4", detail.get("executedTotal").asText());
        assertEquals("0", detail.get("remainingExecutable").asText());
        // 已执行的 4 仍在双池中
        assertEquals("26", basins(windowId).get("upperVolumeM3").asText());
        assertEquals("14", basins(windowId).get("lowerVolumeM3").asText());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentExecutionsRespectFinalBasinVolumes() throws Exception {
        // 上池下界 18：两笔 8 同时执行将使上池 30 -> 14 越界，恰好一笔成功
        long windowId = createBasinWindow("ch-conc-" + run, 10, 10, "30", "10", "18", "40", "0", "30");
        String a1 = submit(windowId, "user-1", "8", "alice");
        String a2 = submit(windowId, "user-2", "8", "bob");
        approve(a1, 200);
        approve(a2, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return executeStatus(key("ec"), key("ek"), a1, "8", "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return executeStatus(key("ec"), key("ek"), a2, "8", "bob");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "两笔执行共同核对双池最终体积，禁止分别检查后超灌: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        // 最终体积恰反映一笔执行
        JsonNode basins = basins(windowId);
        assertEquals("22", basins.get("upperVolumeM3").asText());
        assertEquals("18", basins.get("lowerVolumeM3").asText());
        String winner = s1 == 200 ? a1 : a2;
        String loser = s1 == 200 ? a2 : a1;
        assertEquals(1, executions(winner).get("executions").size());
        assertEquals(0, executions(loser).get("executions").size());
    }

    @Test
    void executeAndCancelSettleByCommitOrder() throws Exception {
        long windowId = createBasinWindow("ch-race-" + run, 10, 10, "30", "10", "0", "40", "0", "30");
        String allocationKey = submit(windowId, "user-1", "8", "alice");
        approve(allocationKey, 200);
        String executeCommand = key("ec");
        String cancelCommand = key("cc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> executeFuture = pool.submit(() -> {
            gate.await();
            return executeStatus(executeCommand, key("ek"), allocationKey, "5", "alice");
        });
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(cancelCommand, allocationKey, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int executeStatus = executeFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        JsonNode detail = executions(allocationKey);
        if (executeStatus == 200) {
            // 执行先提交：取消只释放剩余 3，已执行 5 不退回
            assertEquals(200, cancelStatus);
            assertEquals("5", detail.get("executedTotal").asText());
            assertEquals("0", detail.get("remainingExecutable").asText());
            assertEquals("25", basins(windowId).get("upperVolumeM3").asText());
            assertEquals("15", basins(windowId).get("lowerVolumeM3").asText());
        } else {
            // 取消先提交：执行 422，双池不变
            assertEquals(422, executeStatus);
            assertEquals(200, cancelStatus);
            assertEquals("0", detail.get("executedTotal").asText());
            assertEquals("30", basins(windowId).get("upperVolumeM3").asText());
            assertEquals("10", basins(windowId).get("lowerVolumeM3").asText());
        }
    }

    // ------------------------------------------------------------------
    // 双池注册校验与查询 404
    // ------------------------------------------------------------------

    @Test
    void basinRegistrationValidation() throws Exception {
        String channel = "ch-reg-" + run;
        // 面积必须为正整数
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                0, 10, "30", "10", "0", "40", "0", "30"), "operator", 400);
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, -1, "30", "10", "0", "40", "0", "30"), "operator", 400);
        Map<String, Object> missingArea = basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "30", "10", "0", "40", "0", "30");
        missingArea.remove("upperAreaM2");
        postJson("/api/basin-windows", missingArea, "operator", 400);
        // 初始体积越界 / 上下界倒置 / 负体积
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "50", "10", "0", "40", "0", "30"), "operator", 400);
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "30", "10", "40", "0", "0", "30"), "operator", 400);
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "30", "10", "-1", "40", "0", "30"), "operator", 400);
        // 缺少操作人 -> 400
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "30", "10", "0", "40", "0", "30"), null, 400);
        // 合法注册后同渠道重叠 -> 409
        long windowId = createBasinWindow(channel, 10, 10, "30", "10", "0", "40", "0", "30");
        postJson("/api/basin-windows", basinWindowBody(key("bwc"), key("bwk"), channel,
                10, 10, "30", "10", "0", "40", "0", "30"), "operator", 409);

        // 查询 404：窗口不存在 / 申请不存在
        mvc.perform(get("/api/windows/999999999/basins")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/missing-" + run + "/executions"))
                .andExpect(status().isNotFound());
        // 双池窗口可查询
        JsonNode basins = basins(windowId);
        assertEquals("30", basins.get("upperVolumeM3").asText());
        assertEquals("10", basins.get("lowerVolumeM3").asText());
    }
}
