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
 * 闸门双池执行 API 测试：登记校验、执行主流程、水位/界值/额度门禁、取消与转让联动、
 * 幂等与并发边界。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
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
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 闸门窗口请求体；面积固定 10/10，便于水位口算。 */
    private Map<String, Object> gateWindowBody(String commandKey, String windowKey, String channelId,
                                               long upperInit, long upperMin, long upperMax,
                                               long lowerInit, long lowerMin, long lowerMax, String planned) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        body.put("upperAreaM2", 10);
        body.put("upperInitialVolumeM3", upperInit);
        body.put("upperMinVolumeM3", upperMin);
        body.put("upperMaxVolumeM3", upperMax);
        body.put("lowerAreaM2", 10);
        body.put("lowerInitialVolumeM3", lowerInit);
        body.put("lowerMinVolumeM3", lowerMin);
        body.put("lowerMaxVolumeM3", lowerMax);
        return body;
    }

    private long createGateWindow(String channelId, long upperInit, long upperMin, long upperMax,
                                  long lowerInit, long lowerMin, long lowerMax, String planned)
            throws Exception {
        JsonNode node = postOk("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channelId,
                upperInit, upperMin, upperMax, lowerInit, lowerMin, lowerMax, planned), null);
        return node.get("id").asLong();
    }

    private String submitAndApprove(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private Map<String, Object> executeBody(String commandKey, String executionKey, String volume,
                                            long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("executionKey", executionKey);
        body.put("volume", volume);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode executeOk(String allocationKey, String volume, long expectedVersion, String actor)
            throws Exception {
        return postOk("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("exc"), key("exk"), volume, expectedVersion), actor);
    }

    private int executeViaService(String commandKey, String executionKey, String allocationKey, String volume,
                                  long expectedVersion, String actor) {
        try {
            waterService.executeAllocation(commandKey, allocationKey, executionKey, volume, expectedVersion, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode basins(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/basins");
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        for (JsonNode node : getOk("/api/windows/" + windowId + "/history").get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private JsonNode executions(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/executions");
    }

    // ------------------------------------------------------------------
    // 登记校验
    // ------------------------------------------------------------------

    @Test
    void gateWindowRegistrationValidatesBasinParams() throws Exception {
        String channel = "gch-bad-" + run;
        // 合法登记：面积 10/10，上池 30 [10,40]，下池 10 [0,30]，水位 3 与 1，差恰为 2 米
        long windowId = createGateWindow(channel, 30, 10, 40, 10, 0, 30, "100");
        JsonNode basin = basins(windowId);
        assertEquals(10, basin.get("upperAreaM2").asLong());
        assertEquals(30, basin.get("upperVolumeM3").asLong());
        assertEquals(10, basin.get("lowerVolumeM3").asLong());
        assertEquals("3", basin.get("upperLevelM").asText());
        assertEquals("1", basin.get("lowerLevelM").asText());
        assertEquals(0, basin.get("version").asLong());

        // 面积非正 -> 400
        Map<String, Object> badArea = gateWindowBody(key("gwc"), key("gwk"), channel,
                30, 10, 40, 10, 0, 30, "100");
        badArea.put("upperAreaM2", 0);
        postJson("/api/gate-windows", badArea, null, 400);
        // 上界小于下界 -> 400
        postJson("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channel,
                30, 40, 10, 10, 0, 30, "100"), null, 400);
        // 初始体积越界 -> 400
        postJson("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channel,
                50, 10, 40, 10, 0, 30, "100"), null, 400);
        postJson("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channel,
                30, 10, 40, 5, 10, 30, "100"), null, 400);
        // 初始水位差超过 2 米（上 4 米、下 1 米）-> 400
        postJson("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channel,
                40, 10, 50, 10, 0, 30, "100"), null, 400);
        // 缺双池字段 -> 400
        Map<String, Object> missing = gateWindowBody(key("gwc"), key("gwk"), channel,
                30, 10, 40, 10, 0, 30, "100");
        missing.remove("lowerMinVolumeM3");
        postJson("/api/gate-windows", missing, null, 400);
        // 负体积 -> 400
        postJson("/api/gate-windows", gateWindowBody(key("gwc"), key("gwk"), channel,
                -1, 10, 40, 10, 0, 30, "100"), null, 400);
    }

    // ------------------------------------------------------------------
    // 执行主流程（题干合成样例）
    // ------------------------------------------------------------------

    @Test
    void executeMovesWaterAndEnforcesBounds() throws Exception {
        // 合成样例：两池面积均 10，上池 30、下池 10
        long windowId = createGateWindow("gch-main-" + run, 30, 10, 40, 10, 0, 30, "100");
        String allocation = submitAndApprove(windowId, "user-1", "30", "alice");

        // 执行 5 -> 上池 25、下池 15，水位 2.5 与 1.5，差 1 米，合法
        JsonNode executed = executeOk(allocation, "5", 0, "gate-op");
        assertEquals(5, executed.get("volumeM3").asLong());
        assertEquals(30, executed.get("upperVolumeBeforeM3").asLong());
        assertEquals(25, executed.get("upperVolumeAfterM3").asLong());
        assertEquals(10, executed.get("lowerVolumeBeforeM3").asLong());
        assertEquals(15, executed.get("lowerVolumeAfterM3").asLong());
        assertEquals("3", executed.get("upperLevelBeforeM").asText());
        assertEquals("2.5", executed.get("upperLevelAfterM").asText());
        assertEquals("1", executed.get("lowerLevelBeforeM").asText());
        assertEquals("1.5", executed.get("lowerLevelAfterM").asText());
        assertEquals(1, executed.get("version").asLong());
        assertEquals("25", executed.get("remainingExecutable").asText());
        assertEquals("gate-op", executed.get("actor").asText());

        JsonNode basin = basins(windowId);
        assertEquals(25, basin.get("upperVolumeM3").asLong());
        assertEquals(15, basin.get("lowerVolumeM3").asLong());
        assertEquals(1, basin.get("version").asLong());

        // 再执行 20：上池 5 越下界 10 且下池 35 越上界 30 -> 整次 422，双池与执行账均不变
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "20", 1), "gate-op", 422);
        JsonNode basinAfter = basins(windowId);
        assertEquals(25, basinAfter.get("upperVolumeM3").asLong());
        assertEquals(15, basinAfter.get("lowerVolumeM3").asLong());
        assertEquals(1, basinAfter.get("version").asLong());
        JsonNode list = executions(allocation);
        assertEquals(1, list.get("executions").size());
        assertEquals("5", list.get("executedTotal").asText());
        assertEquals("25", list.get("remainingExecutable").asText());
        JsonNode allocationNode = allocation(windowId, allocation);
        assertEquals("5", allocationNode.get("executedAmount").asText());
        assertEquals("30", allocationNode.get("heldAmount").asText());
    }

    @Test
    void waterLevelGateRejectsExcessiveDiff() throws Exception {
        // 上池 10（1 米）、下池 30（3 米），差恰为 2 米；再从上池取水会使差超过 2 米
        long windowId = createGateWindow("gch-lvl-" + run, 10, 0, 100, 30, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "50", "alice");
        // 执行 1 -> 上 0.9 米、下 3.1 米，差 2.2 米 -> 422
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "1", 0), "gate-op", 422);
        JsonNode basin = basins(windowId);
        assertEquals(10, basin.get("upperVolumeM3").asLong());
        assertEquals(30, basin.get("lowerVolumeM3").asLong());
        assertEquals(0, basin.get("version").asLong());
        assertEquals(0, executions(allocation).get("executions").size());
    }

    // ------------------------------------------------------------------
    // 额度与状态门禁
    // ------------------------------------------------------------------

    @Test
    void executeRequiresApprovedAndWithinApprovedAmount() throws Exception {
        long windowId = createGateWindow("gch-quota-" + run, 30, 0, 100, 10, 0, 100, "100");
        // REQUESTED 状态 -> 422
        String pending = key("ak");
        Map<String, Object> submit = new HashMap<>();
        submit.put("commandKey", key("ac"));
        submit.put("allocationKey", pending);
        submit.put("windowId", windowId);
        submit.put("userId", "user-1");
        submit.put("amount", "10");
        postOk("/api/allocations", submit, "alice");
        postJson("/api/allocations/" + pending + "/executions",
                executeBody(key("exc"), key("exk"), "1", 0), "gate-op", 422);

        String allocation = submitAndApprove(windowId, "user-2", "10", "bob");
        // 非正整数水量 -> 400
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "0", 0), "gate-op", 400);
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "1.5", 0), "gate-op", 400);
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "-3", 0), "gate-op", 400);
        // 累计执行 4 + 4 = 8 <= 10，再来 4 则 12 > 10 -> 422
        executeOk(allocation, "4", 0, "gate-op");
        executeOk(allocation, "4", 1, "gate-op");
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "4", 2), "gate-op", 422);
        JsonNode list = executions(allocation);
        assertEquals(2, list.get("executions").size());
        assertEquals("8", list.get("executedTotal").asText());
        assertEquals("2", list.get("remainingExecutable").asText());
        // 取消后不可再执行 -> 422
        postOk("/api/allocations/" + allocation + "/cancel", Map.of("commandKey", key("cc")), "bob");
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "1", 2), "gate-op", 422);
        // 申请不存在 -> 404
        postJson("/api/allocations/missing-" + run + "/executions",
                executeBody(key("exc"), key("exk"), "1", 0), "gate-op", 404);
    }

    @Test
    void normalWindowDoesNotEnableBasinRules() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "gch-plain-" + run);
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", "100");
        long windowId = postOk("/api/windows", body, null).get("id").asLong();
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        // 普通窗口无双池 -> 执行 422，双池查询 404
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "1", 0), "gate-op", 422);
        mvc.perform(get("/api/windows/" + windowId + "/basins")).andExpect(status().isNotFound());
        assertEquals(0, executions(allocation).get("executions").size());
    }

    // ------------------------------------------------------------------
    // 取消与转让联动
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesOnlyUnexecutedQuota() throws Exception {
        long windowId = createGateWindow("gch-cancel-" + run, 30, 0, 100, 10, 0, 100, "30");
        String allocation = submitAndApprove(windowId, "user-1", "30", "alice");
        executeOk(allocation, "5", 0, "gate-op");
        // 取消：只释放未执行的 25，已执行 5 不退回，双池体积不变
        JsonNode cancelled = postOk("/api/allocations/" + allocation + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("5", cancelled.get("heldAmount").asText());
        assertEquals("5", cancelled.get("executedAmount").asText());
        JsonNode basin = basins(windowId);
        assertEquals(25, basin.get("upperVolumeM3").asLong());
        assertEquals(15, basin.get("lowerVolumeM3").asLong());
        // 已批准占用剩余 5（已执行量），释放的 25 可被新申请占用
        JsonNode cap = capacity(windowId);
        assertEquals("5", cap.get("approvedTotal").asText());
        assertEquals("25", cap.get("remaining").asText());
        String reallocated = submitAndApprove(windowId, "user-2", "25", "bob");
        assertEquals("APPROVED", allocation(windowId, reallocated).get("status").asText());
        // 再申请 1 将超出 -> 422
        String extra = key("ak");
        Map<String, Object> submit = new HashMap<>();
        submit.put("commandKey", key("ac"));
        submit.put("allocationKey", extra);
        submit.put("windowId", windowId);
        submit.put("userId", "user-3");
        submit.put("amount", "1");
        postOk("/api/allocations", submit, "carol");
        postJson("/api/allocations/" + extra + "/approve", Map.of("commandKey", key("ap")), null, 422);
    }

    @Test
    void transferMovesOnlyUnexecutedQuota() throws Exception {
        long windowId = createGateWindow("gch-tx-" + run, 30, 0, 100, 10, 0, 100, "100");
        String source = submitAndApprove(windowId, "user-src", "10", "alice");
        executeOk(source, "4", 0, "gate-op");
        // 可转让额度 = 持有 10 - 已执行 4 = 6；目标 7 -> 422
        String tooBig = key("ak");
        Map<String, Object> submit1 = new HashMap<>();
        submit1.put("commandKey", key("ac"));
        submit1.put("allocationKey", tooBig);
        submit1.put("windowId", windowId);
        submit1.put("userId", "user-dst");
        submit1.put("amount", "7");
        postOk("/api/allocations", submit1, "bob");
        Map<String, Object> transfer1 = new HashMap<>();
        transfer1.put("commandKey", key("tkc"));
        transfer1.put("transferKey", key("tk"));
        transfer1.put("sourceAllocationKey", source);
        transfer1.put("targetAllocationKey", tooBig);
        postJson("/api/transfers", transfer1, "alice", 422);
        // 目标 6 -> 成功，源持有 4 恰等于已执行量
        String ok = key("ak");
        Map<String, Object> submit2 = new HashMap<>();
        submit2.put("commandKey", key("ac"));
        submit2.put("allocationKey", ok);
        submit2.put("windowId", windowId);
        submit2.put("userId", "user-dst2");
        submit2.put("amount", "6");
        postOk("/api/allocations", submit2, "carol");
        Map<String, Object> transfer2 = new HashMap<>();
        transfer2.put("commandKey", key("tkc"));
        transfer2.put("transferKey", key("tk"));
        transfer2.put("sourceAllocationKey", source);
        transfer2.put("targetAllocationKey", ok);
        postOk("/api/transfers", transfer2, "alice");
        JsonNode sourceNode = allocation(windowId, source);
        assertEquals("4", sourceNode.get("heldAmount").asText());
        assertEquals("4", sourceNode.get("executedAmount").asText());
        // 源剩余可执行 0，再执行 -> 422
        postJson("/api/allocations/" + source + "/executions",
                executeBody(key("exc"), key("exk"), "1", 1), "gate-op", 422);
    }

    // ------------------------------------------------------------------
    // 幂等与版本
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeyReplaysWithoutRedelivering() throws Exception {
        long windowId = createGateWindow("gch-idem-" + run, 30, 0, 100, 10, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String commandKey = key("exc");
        Map<String, Object> body = executeBody(commandKey, key("exk"), "5", 0);
        JsonNode first = postOk("/api/allocations/" + allocation + "/executions", body, "gate-op");
        // 同键同参重放：返回首次完整响应，不再次输水（版本仍前进到 1 后不变）
        JsonNode replay = postOk("/api/allocations/" + allocation + "/executions", body, "gate-op");
        assertEquals(first, replay);
        JsonNode basin = basins(windowId);
        assertEquals(25, basin.get("upperVolumeM3").asLong());
        assertEquals(15, basin.get("lowerVolumeM3").asLong());
        assertEquals(1, basin.get("version").asLong());
        assertEquals(1, executions(allocation).get("executions").size());
        // 同键改参（改水量/改版本/改操作人）-> 409
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(commandKey, key("exk"), "4", 0), "gate-op", 409);
        Map<String, Object> otherVersion = executeBody(commandKey, first.get("executionKey").asText(), "5", 1);
        postJson("/api/allocations/" + allocation + "/executions", otherVersion, "gate-op", 409);
        postJson("/api/allocations/" + allocation + "/executions", body, "other-op", 409);
        assertEquals(1, executions(allocation).get("executions").size());
    }

    @Test
    void failedExecutionDoesNotOccupyCommandKey() throws Exception {
        long windowId = createGateWindow("gch-fail-" + run, 30, 10, 40, 10, 0, 30, "100");
        String allocation = submitAndApprove(windowId, "user-1", "30", "alice");
        String commandKey = key("exc");
        // 首次失败（越界 422）不占键
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(commandKey, key("exk"), "25", 0), "gate-op", 422);
        // 同键改参执行合法水量 -> 成功
        JsonNode ok = postOk("/api/allocations/" + allocation + "/executions",
                executeBody(commandKey, key("exk"), "5", 0), "gate-op");
        assertEquals(5, ok.get("volumeM3").asLong());
        assertEquals(1, executions(allocation).get("executions").size());
    }

    @Test
    void executionKeyIsGloballyUniqueAcrossCommandKeys() throws Exception {
        long windowId = createGateWindow("gch-exk-" + run, 30, 0, 100, 10, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String executionKey = key("exk");
        executeOkWithKey(allocation, executionKey, "5", 0);
        // 换 commandKey 复用同一 executionKey -> 409，双池不变
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), executionKey, "5", 1), "gate-op", 409);
        assertEquals(1, basins(windowId).get("version").asLong());
        assertEquals(1, executions(allocation).get("executions").size());
    }

    private JsonNode executeOkWithKey(String allocationKey, String executionKey, String volume,
                                      long expectedVersion) throws Exception {
        return postOk("/api/allocations/" + allocationKey + "/executions",
                executeBody(key("exc"), executionKey, volume, expectedVersion), "gate-op");
    }

    @Test
    void staleExpectedVersionReturns409() throws Exception {
        long windowId = createGateWindow("gch-ver-" + run, 30, 0, 100, 10, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        executeOk(allocation, "2", 0, "gate-op");
        // 版本已前进到 1，旧期望版本 0 -> 409，双池不变
        postJson("/api/allocations/" + allocation + "/executions",
                executeBody(key("exc"), key("exk"), "2", 0), "gate-op", 409);
        JsonNode basin = basins(windowId);
        assertEquals(28, basin.get("upperVolumeM3").asLong());
        assertEquals(1, basin.get("version").asLong());
        // 当前版本 1 可继续执行
        executeOk(allocation, "2", 1, "gate-op");
        assertEquals(2, basins(windowId).get("version").asLong());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentExecutionsOnSharedBasinsStayConsistent() throws Exception {
        long windowId = createGateWindow("gch-conc-" + run, 30, 0, 100, 10, 0, 100, "100");
        String a1 = submitAndApprove(windowId, "user-1", "10", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "10", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return executeViaService(key("exc"), key("exk"), a1, "4", 0, "op-1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return executeViaService(key("exc"), key("exk"), a2, "4", 0, "op-2");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 窗口行锁串行化：恰一个以版本 0 成功，另一个版本过期 409
        assertTrue((s1 == 200 && s2 == 409) || (s1 == 409 && s2 == 200), "s1=" + s1 + " s2=" + s2);
        JsonNode basin = basins(windowId);
        assertEquals(26, basin.get("upperVolumeM3").asLong());
        assertEquals(14, basin.get("lowerVolumeM3").asLong());
        assertEquals(1, basin.get("version").asLong());
        // 失败方用当前版本重试可成功，最终双池与执行账一致
        String loser = s1 == 409 ? a1 : a2;
        executeOk(loser, "4", 1, "op-r");
        JsonNode basin2 = basins(windowId);
        assertEquals(22, basin2.get("upperVolumeM3").asLong());
        assertEquals(18, basin2.get("lowerVolumeM3").asLong());
        assertEquals(2, basin2.get("version").asLong());
        assertEquals(1, executions(a1).get("executions").size());
        assertEquals(1, executions(a2).get("executions").size());
        assertEquals("6", executions(a1).get("remainingExecutable").asText());
        assertEquals("6", executions(a2).get("remainingExecutable").asText());
    }

    @Test
    void concurrentExecuteAndCancelSettleByCommitOrder() throws Exception {
        long windowId = createGateWindow("gch-race-" + run, 30, 0, 100, 10, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String cancelCommand = key("cc");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> executeFuture = pool.submit(() -> {
            gate.await();
            return executeViaService(key("exc"), key("exk"), allocation, "4", 0, "gate-op");
        });
        Future<Integer> cancelFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.cancelAllocation(cancelCommand, allocation, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int executeStatus = executeFuture.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(200, cancelStatus);
        JsonNode node = allocation(windowId, allocation);
        assertEquals("CANCELLED", node.get("status").asText());
        if (executeStatus == 200) {
            // 执行先提交：已执行 4 不退回，取消只释放未执行的 6
            assertEquals("4", node.get("executedAmount").asText());
            assertEquals("4", node.get("heldAmount").asText());
            JsonNode basin = basins(windowId);
            assertEquals(26, basin.get("upperVolumeM3").asLong());
            assertEquals(14, basin.get("lowerVolumeM3").asLong());
            assertEquals("4", capacity(windowId).get("approvedTotal").asText());
        } else {
            // 取消先提交：执行 422，双池与执行账均不变
            assertEquals(422, executeStatus);
            assertEquals("0", node.get("executedAmount").asText());
            assertEquals("0", node.get("heldAmount").asText());
            JsonNode basin = basins(windowId);
            assertEquals(30, basin.get("upperVolumeM3").asLong());
            assertEquals(10, basin.get("lowerVolumeM3").asLong());
            assertEquals("0", capacity(windowId).get("approvedTotal").asText());
        }
    }

    @Test
    void concurrentSameCommandKeyDeliversWaterAtMostOnce() throws Exception {
        long windowId = createGateWindow("gch-samekey-" + run, 30, 0, 100, 10, 0, 100, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10", "alice");
        String commandKey = key("exc");
        String executionKey = key("exk");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return executeViaService(commandKey, executionKey, allocation, "5", 0, "gate-op");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return executeViaService(commandKey, executionKey, allocation, "5", 0, "gate-op");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同键并发：至多一次真实输水；另一个为重放 200 或唯一键竞争后的 409 提示
        assertTrue(s1 == 200 || s1 == 409, "s1=" + s1);
        assertTrue(s2 == 200 || s2 == 409, "s2=" + s2);
        assertTrue(s1 == 200 || s2 == 200, "至少一个成功: " + s1 + "/" + s2);
        JsonNode basin = basins(windowId);
        assertEquals(25, basin.get("upperVolumeM3").asLong());
        assertEquals(15, basin.get("lowerVolumeM3").asLong());
        assertEquals(1, basin.get("version").asLong());
        assertEquals(1, executions(allocation).get("executions").size());
        assertEquals("5", executions(allocation).get("executedTotal").asText());
    }
}