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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旱情分级削减 API 集成测试：比例削减取整、同级总量守恒、升级重算与回补、回补越界 422、
 * 幂等与并发定序、等级/明细/历史查询。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DroughtApiTests {

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
        body.put("channelId", "ch-" + run + "-" + seq);
        body.put("startUtc", "2026-10-20T00:00:00Z");
        body.put("endUtc", "2026-10-20T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private void submit(String allocationKey, long windowId, String userId, String priority, String amount,
                        String actor) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (priority != null) {
            body.put("priority", priority);
        }
        JsonNode node = postOk("/api/allocations", body, actor);
        assertEquals("REQUESTED", node.get("status").asText());
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private Map<String, Object> droughtBody(String commandKey, String curtailmentKey, String level,
                                            int essential, int normal, int deferrable, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("curtailmentKey", curtailmentKey);
        body.put("level", level);
        body.put("essentialPct", essential);
        body.put("normalPct", normal);
        body.put("deferrablePct", deferrable);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode declare(long windowId, String curtailmentKey, String level, int essential, int normal,
                             int deferrable, long expectedVersion) throws Exception {
        return postOk("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), curtailmentKey, level, essential, normal, deferrable, expectedVersion),
                null);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode allocationFromHistory(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode allocation : history.get("allocations")) {
            if (allocationKey.equals(allocation.get("allocationKey").asText())) {
                return allocation;
            }
        }
        throw new AssertionError("申请不存在于历史: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 比例削减取整与总量守恒
    // ------------------------------------------------------------------

    @Test
    void declareCurtailsByPriorityWithConservationRounding() throws Exception {
        long windowId = createWindow("100");
        String essential = "e1-" + run;
        String normal = "n1-" + run;
        String deferrable1 = "d1-" + run;
        String deferrable2 = "d2-" + run;
        submit(essential, windowId, "user-1", "ESSENTIAL", "10.005", "alice");
        submit(normal, windowId, "user-2", "NORMAL", "20.002", "bob");
        submit(deferrable1, windowId, "user-3", "DEFERRABLE", "0.005", "carol");
        submit(deferrable2, windowId, "user-4", "DEFERRABLE", "0.005", "dan");
        submit("r1-" + run, windowId, "user-5", "NORMAL", "50", "erin");
        approve(essential, 200);
        approve(normal, 200);
        approve(deferrable1, 200);
        approve(deferrable2, 200);

        JsonNode response = declare(windowId, key("dk"), "LEVEL2", 10, 20, 33, 0);
        assertEquals("LEVEL2", response.get("level").asText());
        assertEquals(1, response.get("windowVersion").asLong());

        // ESSENTIAL：10.005*90/100 = 9.0045 -> 9.005（HALF_UP）
        // NORMAL：20.002*80/100 = 16.0016 -> 16.002
        // DEFERRABLE：逐笔 0.005*67/100 = 0.00335 -> 0.003，两笔合计 0.006；
        // 该级基线总量 0.010*67/100 = 0.0067 -> 0.007，取整差额 +0.001 由申请标识升序首笔 d1 承担
        assertEquals("9.005", allocationFromHistory(windowId, essential).get("heldAmount").asText());
        assertEquals("16.002", allocationFromHistory(windowId, normal).get("heldAmount").asText());
        assertEquals("0.004", allocationFromHistory(windowId, deferrable1).get("heldAmount").asText());
        assertEquals("0.003", allocationFromHistory(windowId, deferrable2).get("heldAmount").asText());
        // 原申请水量不得改写
        assertEquals("10.005", allocationFromHistory(windowId, essential).get("amount").asText());
        // 未批准申请不参与削减
        assertEquals("0", allocationFromHistory(windowId, "r1-" + run).get("heldAmount").asText());

        // 已批准总量下降、可用余量同步释放：9.005+16.002+0.004+0.003 = 25.014
        JsonNode cap = capacity(windowId);
        assertEquals("25.014", cap.get("approvedTotal").asText());
        assertEquals("74.986", cap.get("remaining").asText());
        assertEquals("LEVEL2", cap.get("droughtLevel").asText());

        // 削减明细按申请标识升序，记录调整前后持有额度
        JsonNode details = response.get("details");
        assertEquals(4, details.size());
        assertEquals(deferrable1, details.get(0).get("allocationKey").asText());
        assertEquals("0.005", details.get(0).get("previousHeld").asText());
        assertEquals("0.004", details.get(0).get("newHeld").asText());
        assertEquals(deferrable2, details.get(1).get("allocationKey").asText());
        assertEquals("0.003", details.get(1).get("newHeld").asText());
        assertEquals(essential, details.get(2).get("allocationKey").asText());
        assertEquals("9.005", details.get(2).get("newHeld").asText());
        assertEquals(normal, details.get(3).get("allocationKey").asText());
        assertEquals("16.002", details.get(3).get("newHeld").asText());
    }

    // ------------------------------------------------------------------
    // 升级重算、降级/恢复回补、版本校验
    // ------------------------------------------------------------------

    @Test
    void upgradeRecomputesOnBaselineAndRestoreReturnsToOriginal() throws Exception {
        long windowId = createWindow("100");
        String a = "a-" + run;
        String b = "b-" + run;
        submit(a, windowId, "user-1", "NORMAL", "10", "alice");
        submit(b, windowId, "user-2", "DEFERRABLE", "20", "bob");
        approve(a, 200);
        approve(b, 200);

        declare(windowId, key("dk"), "LEVEL1", 10, 20, 30, 0);
        assertEquals("8", allocationFromHistory(windowId, a).get("heldAmount").asText());
        assertEquals("14", allocationFromHistory(windowId, b).get("heldAmount").asText());

        // 提升等级：按最新百分比在原始持有额度（基线 10/20）上重算，而非在 8/14 上再削减
        declare(windowId, key("dk"), "LEVEL3", 20, 40, 60, 1);
        assertEquals("6", allocationFromHistory(windowId, a).get("heldAmount").asText());
        assertEquals("8", allocationFromHistory(windowId, b).get("heldAmount").asText());

        // 恢复 NONE：按基线回补到原申请水量
        declare(windowId, key("dk"), "NONE", 0, 0, 0, 2);
        assertEquals("10", allocationFromHistory(windowId, a).get("heldAmount").asText());
        assertEquals("20", allocationFromHistory(windowId, b).get("heldAmount").asText());

        JsonNode drought = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", drought.get("level").asText());
        assertEquals(3, drought.get("version").asLong());

        // 过期版本 -> 409
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 30, 1), null, 409);
    }

    @Test
    void downgradeRestoresPartiallyFromBaseline() throws Exception {
        long windowId = createWindow("100");
        String a = "a-" + run;
        submit(a, windowId, "user-1", "NORMAL", "10", "alice");
        approve(a, 200);
        declare(windowId, key("dk"), "LEVEL3", 10, 60, 90, 0);
        assertEquals("4", allocationFromHistory(windowId, a).get("heldAmount").asText());
        // 降级：按基线 10 回补到 40% 削减后的 6
        declare(windowId, key("dk"), "LEVEL1", 10, 40, 90, 1);
        assertEquals("6", allocationFromHistory(windowId, a).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 回补越界 422 且额度不变
    // ------------------------------------------------------------------

    @Test
    void restoreOverflowReturns422AndKeepsAmountsUnchanged() throws Exception {
        long windowId = createWindow("10");
        String a = "a-" + run;
        submit(a, windowId, "user-1", "NORMAL", "6", "alice");
        approve(a, 200);
        declare(windowId, key("dk"), "LEVEL1", 0, 50, 100, 0);
        assertEquals("3", allocationFromHistory(windowId, a).get("heldAmount").asText());

        // 削减期间创建限供 4（当前已批准 3 <= 4，合法）
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "4"), null);

        // 恢复 NONE 将使已批准总量 6 超过可用总量 4 -> 整次 422，额度、等级、版本均不变
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "NONE", 0, 0, 0, 1), null, 422);
        assertEquals("3", allocationFromHistory(windowId, a).get("heldAmount").asText());
        JsonNode drought = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("LEVEL1", drought.get("level").asText());
        assertEquals(1, drought.get("version").asLong());

        // 取消限供后恢复成功，回补到原申请水量
        postOk("/api/windows/" + windowId + "/curtailment/cancel",
                Map.of("commandKey", key("cuc")), null);
        declare(windowId, key("dk"), "NONE", 0, 0, 0, 1);
        assertEquals("6", allocationFromHistory(windowId, a).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 参数与比例次序校验
    // ------------------------------------------------------------------

    @Test
    void invalidDroughtParamsRejected() throws Exception {
        long windowId = createWindow("10");
        // 比例次序 ESSENTIAL<=NORMAL<=DEFERRABLE 违反 -> 422
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 50, 20, 80, 0), null, 422);
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 15, 0), null, 422);
        // 百分比越界 -> 400
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", -1, 20, 30, 0), null, 400);
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 101, 0), null, 400);
        // 非法等级 -> 400
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL4", 10, 20, 30, 0), null, 400);
        // 缺 expectedVersion -> 400
        Map<String, Object> noVersion = droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 30, 0);
        noVersion.remove("expectedVersion");
        postJson("/api/windows/" + windowId + "/drought", noVersion, null, 400);
        // 缺 curtailmentKey -> 400
        Map<String, Object> noKey = droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 30, 0);
        noKey.remove("curtailmentKey");
        postJson("/api/windows/" + windowId + "/drought", noKey, null, 400);
        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 10, 20, 30, 0), null, 404);
        // 全部失败后窗口仍处于初始状态
        JsonNode drought = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", drought.get("level").asText());
        assertEquals(0, drought.get("version").asLong());
        assertTrue(drought.get("latest").isNull());
    }

    @Test
    void invalidPriorityRejectedAndDefaultIsNormal() throws Exception {
        long windowId = createWindow("10");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "1");
        body.put("priority", "URGENT");
        postJson("/api/allocations", body, "alice", 400);
        // 缺省优先级为 NORMAL
        String allocationKey = key("ak");
        submit(allocationKey, windowId, "user-1", null, "1", "alice");
        assertEquals("NORMAL", allocationFromHistory(windowId, allocationKey).get("priority").asText());
        String essentialKey = key("ak");
        submit(essentialKey, windowId, "user-2", "ESSENTIAL", "1", "bob");
        assertEquals("ESSENTIAL", allocationFromHistory(windowId, essentialKey).get("priority").asText());
    }

    // ------------------------------------------------------------------
    // 幂等与 curtailmentKey 唯一
    // ------------------------------------------------------------------

    @Test
    void droughtKeyUniqueAndCommandIdempotent() throws Exception {
        long windowId = createWindow("10");
        String a = "a-" + run;
        submit(a, windowId, "user-1", "NORMAL", "6", "alice");
        approve(a, 200);

        String commandKey = key("dc");
        String curtailmentKey = key("dk");
        Map<String, Object> body = droughtBody(commandKey, curtailmentKey, "LEVEL1", 10, 20, 30, 0);
        JsonNode first = postOk("/api/windows/" + windowId + "/drought", body, null);
        // 同键同参重放首次结果，不重复削减
        JsonNode replay = postOk("/api/windows/" + windowId + "/drought", body, null);
        assertEquals(first, replay);
        assertEquals("4.8", allocationFromHistory(windowId, a).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought/history").get("curtailments").size());
        // 同键改参 -> 409
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(commandKey, key("dk"), "LEVEL1", 10, 20, 40, 0), null, 409);
        // 换 commandKey 复用 curtailmentKey -> 409
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), curtailmentKey, "LEVEL2", 10, 20, 30, 1), null, 409);

        // 失败不占键：版本冲突 409 后，同一 commandKey 修正参数可成功
        String failedCommand = key("dc");
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(failedCommand, key("dk"), "LEVEL2", 10, 20, 30, 99), null, 409);
        JsonNode recovered = postOk("/api/windows/" + windowId + "/drought",
                droughtBody(failedCommand, key("dk"), "LEVEL2", 10, 20, 30, 1), null);
        assertEquals("LEVEL2", recovered.get("level").asText());
        assertEquals(2, recovered.get("windowVersion").asLong());
    }

    // ------------------------------------------------------------------
    // 削减期间新批准与转让按削减后余量校验
    // ------------------------------------------------------------------

    @Test
    void approvalsAndTransfersUseCurtailedRemaining() throws Exception {
        long windowId = createWindow("10");
        String a1 = "a1-" + run;
        submit(a1, windowId, "user-1", "NORMAL", "8", "alice");
        approve(a1, 200);
        declare(windowId, key("dk"), "LEVEL1", 0, 50, 100, 0);
        assertEquals("4", allocationFromHistory(windowId, a1).get("heldAmount").asText());
        assertEquals("6", capacity(windowId).get("remaining").asText());

        // 削减后余量 6：批准 5 成功，再批准 2 超额 422，批准 1 成功
        String a2 = "a2-" + run;
        submit(a2, windowId, "user-2", "NORMAL", "5", "bob");
        approve(a2, 200);
        String a3 = "a3-" + run;
        submit(a3, windowId, "user-3", "NORMAL", "2", "carol");
        approve(a3, 422);
        String a4 = "a4-" + run;
        submit(a4, windowId, "user-4", "NORMAL", "1", "dan");
        approve(a4, 200);
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());

        // 转让按当前持有额度校验：a2 持有 5，可转出 5；a1 持有 4，转出 5 超额 422
        String t1 = "t1-" + run;
        submit(t1, windowId, "user-5", "NORMAL", "5", "erin");
        postOk("/api/transfers", Map.of("commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", a2, "targetAllocationKey", t1), "bob");
        assertEquals("0", allocationFromHistory(windowId, a2).get("heldAmount").asText());
        String t2 = "t2-" + run;
        submit(t2, windowId, "user-6", "NORMAL", "5", "fred");
        postJson("/api/transfers", Map.of("commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", a1, "targetAllocationKey", t2), "alice", 422);
    }

    // ------------------------------------------------------------------
    // 并发定序
    // ------------------------------------------------------------------

    @Test
    void concurrentDeclaresAdjudicatedByVersion() throws Exception {
        long windowId = createWindow("10");
        String a = "a-" + run;
        submit(a, windowId, "user-1", "NORMAL", "6", "alice");
        approve(a, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String declareCommand1 = key("dc");
        String declareKey1 = key("dk");
        String declareCommand2 = key("dc");
        String declareKey2 = key("dk");
        Future<Integer> first = pool.submit(() -> {
            gate.await();
            try {
                waterService.declareDrought(declareCommand1, declareKey1, windowId, "LEVEL1", 0, 50, 100, 0L);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> second = pool.submit(() -> {
            gate.await();
            try {
                waterService.declareDrought(declareCommand2, declareKey2, windowId, "LEVEL2", 0, 30, 100, 0L);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int firstStatus = first.get(30, TimeUnit.SECONDS);
        int secondStatus = second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 相同 expectedVersion=0 并发声明：按提交顺序恰好一个成功，另一个版本冲突 409
        assertTrue((firstStatus == 200) != (secondStatus == 200),
                "first=" + firstStatus + " second=" + secondStatus);
        assertEquals(409, firstStatus == 200 ? secondStatus : firstStatus);
        JsonNode drought = getOk("/api/windows/" + windowId + "/drought");
        assertEquals(1, drought.get("version").asLong());
        String level = drought.get("level").asText();
        // 生效等级与最终持有额度一致：LEVEL1 -> 3，LEVEL2 -> 4.2
        String expectedHeld = "LEVEL1".equals(level) ? "3" : "4.2";
        assertEquals(expectedHeld, allocationFromHistory(windowId, a).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought/history").get("curtailments").size());
    }

    @Test
    void concurrentDeclareAndApproveKeepApprovedWithinAvailable() throws Exception {
        long windowId = createWindow("10");
        String a1 = "a1-" + run;
        submit(a1, windowId, "user-1", "NORMAL", "8", "alice");
        approve(a1, 200);
        String a2 = "a2-" + run;
        submit(a2, windowId, "user-2", "NORMAL", "2", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String declareCommand = key("dc");
        String declareKey = key("dk");
        String approveCommand = key("ap");
        Future<Integer> declareFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.declareDrought(declareCommand, declareKey, windowId, "LEVEL1", 0, 50, 100, 0L);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, a2);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int declareStatus = declareFuture.get(30, TimeUnit.SECONDS);
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两种提交顺序均合法：先批准（8+2<=10 后削减）或先削减（4+2<=10）
        assertEquals(200, declareStatus);
        assertEquals(200, approveStatus);
        JsonNode cap = capacity(windowId);
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
        assertEquals("LEVEL1", cap.get("droughtLevel").asText());
    }

    // ------------------------------------------------------------------
    // 等级、明细与历史查询
    // ------------------------------------------------------------------

    @Test
    void droughtQueriesExposeLevelDetailsAndHistory() throws Exception {
        long windowId = createWindow("10");
        // 初始：NONE、版本 0、无声明
        JsonNode initial = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", initial.get("level").asText());
        assertEquals(0, initial.get("version").asLong());
        assertTrue(initial.get("latest").isNull());

        String a = "a-" + run;
        submit(a, windowId, "user-1", "DEFERRABLE", "4", "alice");
        approve(a, 200);
        String key1 = key("dk");
        declare(windowId, key1, "LEVEL1", 0, 25, 50, 0);
        declare(windowId, key("dk"), "NONE", 0, 0, 0, 1);

        JsonNode current = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", current.get("level").asText());
        assertEquals(2, current.get("version").asLong());
        assertEquals("NONE", current.get("latest").get("level").asText());
        // 恢复明细：2 -> 4
        JsonNode latestDetails = current.get("latest").get("details");
        assertEquals(1, latestDetails.size());
        assertEquals(a, latestDetails.get(0).get("allocationKey").asText());
        assertEquals("2", latestDetails.get(0).get("previousHeld").asText());
        assertEquals("4", latestDetails.get(0).get("newHeld").asText());

        JsonNode history = getOk("/api/windows/" + windowId + "/drought/history");
        assertEquals(2, history.get("curtailments").size());
        assertEquals("LEVEL1", history.get("curtailments").get(0).get("level").asText());
        assertEquals(key1, history.get("curtailments").get(0).get("curtailmentKey").asText());
        assertEquals(1, history.get("curtailments").get(0).get("windowVersion").asLong());
        assertEquals("NONE", history.get("curtailments").get(1).get("level").asText());
        assertEquals(2, history.get("curtailments").get(1).get("windowVersion").asLong());

        // 窗口历史明细包含旱情声明，窗口视图携带等级与版本
        JsonNode windowHistory = getOk("/api/windows/" + windowId + "/history");
        assertEquals(2, windowHistory.get("droughtCurtailments").size());
        assertEquals("NONE", windowHistory.get("window").get("droughtLevel").asText());
        assertEquals(2, windowHistory.get("window").get("version").asLong());

        // 窗口不存在 -> 404
        mvc.perform(get("/api/windows/999999999/drought")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/drought/history")).andExpect(status().isNotFound());
    }

    @Test
    void curtailmentKeyIsGloballyUniqueAcrossWindows() throws Exception {
        long firstWindow = createWindow("10");
        long secondWindow = createWindow("10");
        String curtailmentKey = key("dk");
        declare(firstWindow, curtailmentKey, "LEVEL1", 10, 20, 30, 0);
        // 同一 curtailmentKey 用于另一窗口 -> 409
        postJson("/api/windows/" + secondWindow + "/drought",
                droughtBody(key("dc"), curtailmentKey, "LEVEL1", 10, 20, 30, 0), null, 409);
        assertNotNull(getOk("/api/windows/" + firstWindow + "/drought").get("latest"));
        assertTrue(getOk("/api/windows/" + secondWindow + "/drought").get("latest").isNull());
    }
}
