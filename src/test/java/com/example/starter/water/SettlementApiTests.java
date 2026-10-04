package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶梯计费结算与差额账本 API 测试：主流程、合成业务序列、版本校验、幂等、
 * 非法状态 422 与同窗口并发对账。全部使用 H2 内存库（MODE=MySQL）真实事务与行锁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private JdbcTemplate jdbc;

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

    private void approve(String allocationKey) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
    }

    private void cancel(String allocationKey, String actor) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/cancel",
                Map.of("commandKey", key("cc")), actor, 200);
    }

    private void transfer(String sourceKey, String targetKey, String actor) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        postOk("/api/transfers", body, actor);
    }

    private Map<String, Object> settleBody(String requestId, Long expectedSettlementVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        if (expectedSettlementVersion != null) {
            body.put("expectedSettlementVersion", expectedSettlementVersion);
        }
        return body;
    }

    private JsonNode settleOk(long windowId, String requestId, Long expected, String actor)
            throws Exception {
        return postOk("/api/windows/" + windowId + "/settlements",
                settleBody(requestId, expected), actor);
    }

    private JsonNode line(JsonNode settlement, String userId) {
        for (JsonNode node : settlement.get("lines")) {
            if (userId.equals(node.get("userId").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("line not found: " + userId);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void settleCreatesVersion1WithTieredBilling() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        // 农户 A 两笔已批准申请合计 150；农户 B 一笔 80
        String a1 = submit(windowId, "user-a", "100", "alice");
        String a2 = submit(windowId, "user-a", "50", "alice");
        String b1 = submit(windowId, "user-b", "80", "bob");
        approve(a1);
        approve(a2);
        approve(b1);
        // 未批准与已取消的申请不参与结算
        submit(windowId, "user-c", "30", "carol");
        String d1 = submit(windowId, "user-d", "40", "dave");
        approve(d1);
        cancel(d1, "dave");

        JsonNode v1 = settleOk(windowId, key("rq"), null, "op-1");
        assertEquals(1, v1.get("version").asInt());
        assertEquals("510.00", v1.get("totalAmount").asText());
        assertEquals("510.00", v1.get("deltaAmount").asText());
        assertEquals("100", v1.get("baseThreshold").asText());
        assertEquals("2", v1.get("baseRate").asText());
        assertEquals("3", v1.get("excessRate").asText());
        assertEquals(2, v1.get("lines").size());

        JsonNode lineA = line(v1, "user-a");
        assertEquals("150", lineA.get("volume").asText());
        assertEquals("100", lineA.get("baseVolume").asText());
        assertEquals("50", lineA.get("excessVolume").asText());
        assertEquals("350.00", lineA.get("amount").asText());
        JsonNode lineB = line(v1, "user-b");
        assertEquals("80", lineB.get("volume").asText());
        assertEquals("0", lineB.get("excessVolume").asText());
        assertEquals("160.00", lineB.get("amount").asText());

        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("settlements").size());
        JsonNode current = getOk("/api/windows/" + windowId + "/settlements/current");
        assertEquals(1, current.get("version").asInt());
        assertEquals("510.00", current.get("totalAmount").asText());
        JsonNode detail = getOk("/api/windows/" + windowId + "/settlements/1");
        assertEquals("510.00", detail.get("totalAmount").asText());
    }

    @Test
    void reconcileAfterTransferAndCancelMatchesSynthetic() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "150", "alice");
        approve(a1);
        // 合成 150 立方米应收 350 元
        JsonNode v1 = settleOk(windowId, key("rq"), null, "op-1");
        assertEquals("350.00", v1.get("totalAmount").asText());

        // 转出 20 后受让方取消，应收 290、差额 -60
        String b1 = submit(windowId, "user-b", "20", "bob");
        transfer(a1, b1, "alice");
        cancel(b1, "bob");
        JsonNode v2 = settleOk(windowId, key("rq"), 1L, "op-1");
        assertEquals(2, v2.get("version").asInt());
        assertEquals("290.00", v2.get("totalAmount").asText());
        assertEquals("-60.00", v2.get("deltaAmount").asText());
        assertEquals("130", line(v2, "user-a").get("volume").asText());
        assertEquals("290.00", line(v2, "user-a").get("amount").asText());

        // 再转出 10 后受让方取消，应收 260、此次差额 -30
        String c1 = submit(windowId, "user-c", "10", "carol");
        transfer(a1, c1, "alice");
        cancel(c1, "carol");
        JsonNode v3 = settleOk(windowId, key("rq"), 2L, "op-1");
        assertEquals(3, v3.get("version").asInt());
        assertEquals("260.00", v3.get("totalAmount").asText());
        assertEquals("-30.00", v3.get("deltaAmount").asText());

        // 旧单不变，差额账本完整，当前净应收为最新版本
        JsonNode oldV1 = getOk("/api/windows/" + windowId + "/settlements/1");
        assertEquals("350.00", oldV1.get("totalAmount").asText());
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(3, list.get("settlements").size());
        assertEquals("350.00", list.get("settlements").get(0).get("deltaAmount").asText());
        assertEquals("-60.00", list.get("settlements").get(1).get("deltaAmount").asText());
        assertEquals("-30.00", list.get("settlements").get(2).get("deltaAmount").asText());
        JsonNode current = getOk("/api/windows/" + windowId + "/settlements/current");
        assertEquals(3, current.get("version").asInt());
        assertEquals("260.00", current.get("totalAmount").asText());
    }

    @Test
    void reconcileWithoutChangeStoresZeroDeltaVersion() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "150", "alice");
        approve(a1);
        settleOk(windowId, key("rq"), null, "op-1");
        JsonNode v2 = settleOk(windowId, key("rq"), 1L, "op-1");
        assertEquals(2, v2.get("version").asInt());
        assertEquals("350.00", v2.get("totalAmount").asText());
        assertEquals("0.00", v2.get("deltaAmount").asText());
    }

    @Test
    void reconcileValidatesExpectedVersion() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "50", "alice");
        approve(a1);
        // 首版不应提交 expectedSettlementVersion
        postJson("/api/windows/" + windowId + "/settlements", settleBody(key("rq"), 0L), "op-1", 409);
        settleOk(windowId, key("rq"), null, "op-1");
        // 对账必须提交期望版本且与最新版本一致
        postJson("/api/windows/" + windowId + "/settlements", settleBody(key("rq"), null), "op-1", 400);
        postJson("/api/windows/" + windowId + "/settlements", settleBody(key("rq"), 3L), "op-1", 409);
        JsonNode v2 = settleOk(windowId, key("rq"), 1L, "op-1");
        assertEquals(2, v2.get("version").asInt());
    }

    // ------------------------------------------------------------------
    // 幂等与失败分支
    // ------------------------------------------------------------------

    @Test
    void requestIdReplayAndConflict() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "150", "alice");
        approve(a1);

        String requestId = key("rq");
        JsonNode first = settleOk(windowId, requestId, null, "op-1");
        // 同键同参重放首次完整响应，不产生新版本
        JsonNode replay = settleOk(windowId, requestId, null, "op-1");
        assertEquals(first.get("version").asInt(), replay.get("version").asInt());
        assertEquals(first.get("totalAmount").asText(), replay.get("totalAmount").asText());
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(1, list.get("settlements").size());
        // 同键异参（含期望版本不同）返回 409
        postJson("/api/windows/" + windowId + "/settlements", settleBody(requestId, 1L), "op-1", 409);

        // 失败不占键：错误期望版本 409 后，同一 requestId 可携带正确参数成功
        String retryId = key("rq");
        postJson("/api/windows/" + windowId + "/settlements", settleBody(retryId, 9L), "op-1", 409);
        JsonNode v2 = settleOk(windowId, retryId, 1L, "op-1");
        assertEquals(2, v2.get("version").asInt());
    }

    @Test
    void invalidFarmerTotalYields422AndWritesNothing() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "100", "alice");
        approve(a1);
        settleOk(windowId, key("rq"), null, "op-1");

        // 直接注入一笔持有额度超过原申请量的已批准申请（绕过 CHECK 约束模拟非法状态）
        String badKey = key("ak-bad");
        jdbc.execute("ALTER TABLE allocation DROP CONSTRAINT chk_allocation_held");
        try {
            jdbc.update("INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount,"
                            + " requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, 10, 25, 'mallory', 'APPROVED', 0, 0)",
                    badKey, windowId, "user-b");
            MvcResult result = postJson("/api/windows/" + windowId + "/settlements",
                    settleBody(key("rq"), 1L), "op-1", 422);
            JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals("SETTLEMENT_INVALID_STATE", error.get("code").asText());
            // 整张对账不写：版本不推进，当前净应收不变
            JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
            assertEquals(1, list.get("settlements").size());
            JsonNode current = getOk("/api/windows/" + windowId + "/settlements/current");
            assertEquals(1, current.get("version").asInt());
            assertEquals("200.00", current.get("totalAmount").asText());
        } finally {
            jdbc.update("DELETE FROM allocation WHERE allocation_key = ?", badKey);
            jdbc.execute("ALTER TABLE allocation ADD CONSTRAINT chk_allocation_held"
                    + " CHECK (held_amount >= 0 AND held_amount <= amount)");
        }
        // 清理非法数据后对账恢复可用
        JsonNode v2 = settleOk(windowId, key("rq"), 1L, "op-1");
        assertEquals(2, v2.get("version").asInt());
    }

    @Test
    void tierRoundingHalfUpToFen() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "100.005", "alice");
        approve(a1);
        JsonNode v1 = settleOk(windowId, key("rq"), null, "op-1");
        // 100*2 + 0.005*3 = 200.015，HALF_UP 到分为 200.02
        assertEquals("200.02", v1.get("totalAmount").asText());
        JsonNode lineA = line(v1, "user-a");
        assertEquals("100.005", lineA.get("volume").asText());
        assertEquals("0.005", lineA.get("excessVolume").asText());
        assertEquals("200.02", lineA.get("amount").asText());
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentReconcileAdvancesSingleVersion() throws Exception {
        long windowId = createWindow(key("ch"), "1000");
        String a1 = submit(windowId, "user-a", "150", "alice");
        approve(a1);
        settleOk(windowId, key("rq"), null, "op-1");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<String> requestIds = List.of(key("rq"), key("rq"));
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = requestIds.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(30, TimeUnit.SECONDS));
                    try {
                        waterService.settle(requestId, windowId, 1L, "op-1");
                        return 200;
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            go.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int code = future.get(60, TimeUnit.SECONDS);
                if (code == 200) {
                    ok++;
                } else if (code == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok);
            assertEquals(1, conflict);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        // 同窗口并发对账只有一份版本推进
        JsonNode list = getOk("/api/windows/" + windowId + "/settlements");
        assertEquals(2, list.get("settlements").size());
        JsonNode current = getOk("/api/windows/" + windowId + "/settlements/current");
        assertEquals(2, current.get("version").asInt());
        assertEquals("350.00", current.get("totalAmount").asText());
    }
}
