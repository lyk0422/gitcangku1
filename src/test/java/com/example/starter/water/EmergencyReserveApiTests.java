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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 应急储备与常规转让隔离的端到端测试。
 *
 * <p>覆盖：储备调整与版本、常规批准/转让/核销的储备隔离 422（含常规余额与储备量）、
 * 应急核销仅扣储备且按窗口+应急编号去重、批量先校验后单事务扣减的原子回滚、
 * 下调储备对已批准未核销常规转让后态的回查、窗口结束/关闭边界与快照保留、
 * reserveKey 指纹的成功重放/改指纹 409/失败不占键，以及储备调整与转让、应急核销之间
 * 按事务提交顺序的真实并发裁决。全部使用 H2 内存库（MODE=MySQL）真实事务与唯一约束。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyReserveApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(0, 12);
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

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
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

    private Map<String, Object> reserveBody(String reserveKey, long version, String reserveVolume) {
        Map<String, Object> body = new HashMap<>();
        body.put("reserveKey", reserveKey);
        body.put("expectedVersion", version);
        body.put("reserveVolume", reserveVolume);
        return body;
    }

    private JsonNode reserveStatus(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/reserve");
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private Map<String, Object> transferBody(String transferKey, String source, String target,
                                             String reserveKey, long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        body.put("reserveKey", reserveKey);
        body.put("expectedVersion", version);
        return body;
    }

    private Map<String, Object> regularBody(String reserveKey, long version, String allocationKey,
                                            String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("reserveKey", reserveKey);
        body.put("expectedVersion", version);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        return body;
    }

    private Map<String, Object> emergencyItem(String emergencyId, String approver, String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("emergencyId", emergencyId);
        item.put("approver", approver);
        item.put("amount", amount);
        return item;
    }

    private Map<String, Object> emergencyBatchBody(String reserveKey, long version,
                                                   List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("reserveKey", reserveKey);
        body.put("expectedVersion", version);
        body.put("items", items);
        return body;
    }

    private JsonNode emergencyOk(long windowId, String reserveKey, long version,
                                 List<Map<String, Object>> items, String actor) throws Exception {
        return postOk("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(reserveKey, version, items), actor);
    }

    // ------------------------------------------------------------------
    // 储备调整与容量查询
    // ------------------------------------------------------------------

    @Test
    void reserveAdjustmentUpdatesSnapshotAndCapacityView() throws Exception {
        long windowId = createWindow("ch-r-" + run, "2026-10-20T00:00:00Z", "2026-10-20T02:00:00Z", "10");
        JsonNode before = reserveStatus(windowId);
        assertEquals("0", before.get("reserveVolume").asText());
        assertEquals("10", before.get("regularAvailable").asText());
        assertEquals("0", before.get("reserveRemaining").asText());
        assertEquals(0, before.get("version").asLong());
        assertEquals("OPEN", before.get("windowStatus").asText());
        assertEquals(0, before.get("blockReasons").size());

        JsonNode resp = postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 0L, "3.500"), "dispatcher");
        assertEquals("3.5", resp.get("reserveVolume").asText());
        assertEquals("6.5", resp.get("regularAvailable").asText());
        assertEquals("3.5", resp.get("reserveRemaining").asText());
        assertEquals(1, resp.get("version").asLong());
        assertEquals("OPEN", resp.get("status").asText());

        JsonNode cap = capacity(windowId);
        assertEquals("3.5", cap.get("reserveVolume").asText());
        assertEquals("6.5", cap.get("regularAvailable").asText());
        assertEquals("3.5", cap.get("reserveRemaining").asText());

        // 超过窗口总配额 -> 400
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 1L, "10.001"), "dispatcher", 400);
        // 超过 3 位小数 -> 400
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 1L, "1.0001"), "dispatcher", 400);
        // 缺 expectedVersion -> 400
        Map<String, Object> noVersion = new HashMap<>();
        noVersion.put("reserveKey", key("rk"));
        noVersion.put("reserveVolume", "2");
        postJson("/api/windows/" + windowId + "/reserve", noVersion, "dispatcher", 400);
        // 窗口不存在 -> 404
        postJson("/api/windows/999999999/reserve", reserveBody(key("rk"), 0L, "1"), "dispatcher", 404);
    }

    @Test
    void normalApproveIsIsolatedFromReserve() throws Exception {
        long windowId = createWindow("ch-iso-" + run, "2026-10-21T00:00:00Z", "2026-10-21T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "4"), "dispatcher");
        // 常规池仅 6：批准 6 恰好放满
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        // 再批准 0.001 将侵入储备量 -> 422，消息含常规余额与储备量
        String a2 = submit(windowId, "user-2", "0.001", "bob");
        MvcResult blocked = postJson("/api/allocations/" + a2 + "/approve",
                Map.of("commandKey", key("ap")), null, 422);
        JsonNode error = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("RESERVE_ISOLATION", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("常规余额"));
        assertTrue(error.get("message").asText().contains("应急储备量"));
        // 未发生批准
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
        assertEquals("0", reserveStatus(windowId).get("regularBalance").asText());
    }

    // ------------------------------------------------------------------
    // 常规转让 / 常规核销的隔离
    // ------------------------------------------------------------------

    @Test
    void regularWriteoffConsumesHeldAmountAndKeepsReserveUntouched() throws Exception {
        long windowId = createWindow("ch-rw-" + run, "2026-10-22T00:00:00Z", "2026-10-22T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "4"), "dispatcher");
        String a1 = submit(windowId, "user-1", "6", "alice");
        approve(a1, 200);

        JsonNode wo = postOk("/api/windows/" + windowId + "/writeoffs/regular",
                regularBody(key("rk"), 1L, a1, "2"), "alice");
        assertEquals(a1, wo.get("allocationKey").asText());
        assertEquals("2", wo.get("amount").asText());
        assertEquals("4", wo.get("reserveVolume").asText());
        assertEquals("0", wo.get("regularBalance").asText());
        assertEquals("alice", wo.get("actor").asText());

        // 持有剩 4、常规占用仍为 6（held 4 + 核销 2），储备 4 分文未动
        JsonNode status = reserveStatus(windowId);
        assertEquals("4", status.get("approvedHeld").asText());
        assertEquals("2", status.get("regularWrittenOff").asText());
        assertEquals("0", status.get("emergencyWrittenOff").asText());
        assertEquals("4", status.get("reserveRemaining").asText());
        assertEquals("0", status.get("regularBalance").asText());
        assertEquals(2, status.get("version").asLong());

        // 再核销 5 超过申请持有 4 -> 422（持有不足），消息含余额与储备
        MvcResult blocked = postJson("/api/windows/" + windowId + "/writeoffs/regular",
                regularBody(key("rk"), 2L, a1, "5"), "alice", 422);
        JsonNode error = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("常规余额"));
        // 数据无变化
        JsonNode after = reserveStatus(windowId);
        assertEquals("4", after.get("approvedHeld").asText());
        assertEquals("2", after.get("regularWrittenOff").asText());

        // 非 APPROVED 申请常规核销 -> 409
        String requested = submit(windowId, "user-2", "1", "bob");
        postJson("/api/windows/" + windowId + "/writeoffs/regular",
                regularBody(key("rk"), 2L, requested, "1"), "bob", 409);
        // 申请不存在 -> 404
        postJson("/api/windows/" + windowId + "/writeoffs/regular",
                regularBody(key("rk"), 2L, "missing-" + run, "1"), "bob", 404);
    }

    @Test
    void transferAndWriteoffBlockedOnceRegularPoolIntrudesReserve() throws Exception {
        // 通过限供把总配额压到储备与常规持有之和以下，制造常规占用侵入储备的状态：
        // 总配额 10、已批准持有 6、储备 4；限供到 8（>=储备 4 且 >=已批准 6，合法），
        // 此后常规池 = 8-4 = 4 < 持有 6，新常规转让/常规核销必须 422。
        long windowId = createWindow("ch-blk-" + run, "2026-10-23T00:00:00Z", "2026-10-23T02:00:00Z", "10");
        String source = submit(windowId, "user-src", "6", "alice");
        approve(source, 200);
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "4"), "dispatcher");
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "8"), null);

        JsonNode status = reserveStatus(windowId);
        assertEquals("8", status.get("totalQuota").asText());
        assertEquals("4", status.get("reserveVolume").asText());
        assertEquals("-2", status.get("regularBalance").asText());
        assertTrue(status.get("blockReasons").toString().contains("REGULAR_RESERVE_INTRUSION"),
                status.get("blockReasons").toString());

        // 常规转让（转让守恒，但不得在侵占态结算）-> 422
        String target = submit(windowId, "user-dst", "1", "bob");
        postJson("/api/transfers",
                transferBody(key("tk"), source, target, key("rk"), 2L), "alice", 422);
        // 常规核销 -> 422
        postJson("/api/windows/" + windowId + "/writeoffs/regular",
                regularBody(key("rk"), 2L, source, "1"), "alice", 422);
        // 应急核销仍可从独立储备扣减
        JsonNode emergency = emergencyOk(windowId, key("rk"), 2L,
                List.of(emergencyItem("em-block", "chief", "2")), "alice");
        assertEquals(1, emergency.size());
        assertEquals("2", emergency.get(0).get("amount").asText());
        assertEquals("2", reserveStatus(windowId).get("reserveRemaining").asText());
    }

    // ------------------------------------------------------------------
    // 下调储备对已批准未核销常规转让后态的回查
    // ------------------------------------------------------------------

    @Test
    void reserveReductionIsRejectedAgainstTransferSettledStateAtomically() throws Exception {
        long windowId = createWindow("ch-down-" + run, "2026-10-24T00:00:00Z", "2026-10-24T02:00:00Z", "10");
        // 源批准 6，向目标转让 2：转让后态为源持有 4、目标持有 2（常规占用仍 6）
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "2", "bob");
        approve(source, 200);
        postOk("/api/transfers", transferBody(key("tk"), source, target, key("rk"), 0L), "alice");
        // 划定储备 4：常规池 6 恰好容纳转让后态 6
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 1L, "4"), "dispatcher");
        // 限供到 8 使总配额收紧（>=储备 4），随后任何下调都必须回查转让后态 6
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "8"), null);

        // 下调到 3：3 + 转让后态 6 = 9 > 新总配额 8 -> 422，整单不生效
        MvcResult blocked = postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 2L, "3"), "dispatcher", 422);
        JsonNode error = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("RESERVE_ISOLATION", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("转让后态"));
        // 不部分生效：储备仍 4、版本未前进
        JsonNode status = reserveStatus(windowId);
        assertEquals("4", status.get("reserveVolume").asText());
        assertEquals(2, status.get("version").asLong());
        // 下调到 2：2 + 6 = 8 恰好合法
        JsonNode ok = postOk("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 2L, "2"), "dispatcher");
        assertEquals("2", ok.get("reserveVolume").asText());
        assertEquals(3, ok.get("version").asLong());
    }

    // ------------------------------------------------------------------
    // 应急核销：审批、编号去重、仅扣储备
    // ------------------------------------------------------------------

    @Test
    void emergencyWriteoffRequiresApproverAndDeductsOnlyReserve() throws Exception {
        long windowId = createWindow("ch-em-" + run, "2026-10-25T00:00:00Z", "2026-10-25T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "5"), "dispatcher");

        // 缺审批人 -> 400
        Map<String, Object> noApprover = emergencyBatchBody(key("rk"), 1L,
                List.of(emergencyItem("em-1", null, "1")));
        postJson("/api/windows/" + windowId + "/writeoffs/emergency", noApprover, "alice", 400);
        // 缺应急编号 -> 400
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 1L, List.of(emergencyItem(null, "chief", "1"))),
                "alice", 400);
        // 空批量 -> 400
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 1L, List.of()), "alice", 400);

        JsonNode result = emergencyOk(windowId, key("rk"), 1L,
                List.of(emergencyItem("em-1", "chief-zhou", "3.5"),
                        emergencyItem("em-2", "chief-lin", "1")), "alice");
        assertEquals(2, result.size());
        assertEquals("em-1", result.get(0).get("emergencyId").asText());
        assertEquals("chief-zhou", result.get(0).get("approver").asText());
        assertEquals("alice", result.get(0).get("actor").asText());
        assertEquals("3.5", result.get(0).get("amount").asText());
        // 储备量快照保留为核销时的 5
        assertEquals("5", result.get(0).get("reserveSnapshot").asText());
        assertEquals("5", result.get(1).get("reserveSnapshot").asText());
        assertNotNull(result.get(0).get("createdUtc"));

        JsonNode status = reserveStatus(windowId);
        assertEquals("0.5", status.get("reserveRemaining").asText());
        assertEquals("4.5", status.get("emergencyWrittenOff").asText());
        // 储备扣减不影响常规量
        assertEquals("5", status.get("regularAvailable").asText());
        assertEquals("0", status.get("approvedHeld").asText());

        // 同窗口同应急编号再次核销 -> 409
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 2L, List.of(emergencyItem("em-1", "chief-zhou", "0.5"))),
                "alice", 409);
        // 超过储备余额 0.5 -> 422
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 2L, List.of(emergencyItem("em-3", "chief", "0.6"))),
                "alice", 422);
        // 储备余额与流水数不变
        assertEquals("0.5", reserveStatus(windowId).get("reserveRemaining").asText());
        assertEquals(2, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
    }

    @Test
    void emergencyBatchValidatesAllBeforeAnyDeductionAndRollsBackAtomically() throws Exception {
        long windowId = createWindow("ch-batch-" + run, "2026-10-26T00:00:00Z", "2026-10-26T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "5"), "dispatcher");
        String failedKey = key("rk");
        // 合计 3+3=6 > 储备余额 5 -> 422，整单回滚
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(failedKey, 1L,
                        List.of(emergencyItem("em-a", "chief", "3"),
                                emergencyItem("em-b", "chief", "3"))), "alice", 422);
        // 无任何流水、储备未扣
        assertEquals(0, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
        assertEquals("5", reserveStatus(windowId).get("reserveRemaining").asText());

        // 批内应急编号重复 -> 422，整单回滚
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 1L,
                        List.of(emergencyItem("em-c", "chief", "1"),
                                emergencyItem("em-c", "chief", "1"))), "alice", 422);
        assertEquals(0, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());

        // 先成功占用 em-d，再批量含 em-d -> 409，整单回滚（em-e 也不得写入）
        emergencyOk(windowId, key("rk"), 1L, List.of(emergencyItem("em-d", "chief", "1")), "alice");
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 2L,
                        List.of(emergencyItem("em-d", "chief", "1"),
                                emergencyItem("em-e", "chief", "1"))), "alice", 409);
        assertEquals(1, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
        assertEquals("4", reserveStatus(windowId).get("reserveRemaining").asText());

        // 失败不占键：failedKey 可被金额不同的合法批量复用并成功（余额 4，2+2 恰好用尽）
        JsonNode retry = emergencyOk(windowId, failedKey, 2L,
                List.of(emergencyItem("em-f", "chief", "2"),
                        emergencyItem("em-g", "chief", "2")), "alice");
        assertEquals(2, retry.size());
        assertEquals("0", reserveStatus(windowId).get("reserveRemaining").asText());
    }

    // ------------------------------------------------------------------
    // 窗口边界
    // ------------------------------------------------------------------

    @Test
    void noNewEmergencyWriteoffAfterWindowEndButSnapshotRemains() throws Exception {
        // 结束时刻在过去的窗口：创建即已结束
        long endedId = createWindow("ch-past-" + run, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", "10");
        postOk("/api/windows/" + endedId + "/reserve", reserveBody(key("rk"), 0L, "3"), "dispatcher");
        MvcResult ended = postJson("/api/windows/" + endedId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 1L, List.of(emergencyItem("em-past", "chief", "1"))),
                "alice", 409);
        assertEquals("WINDOW_ENDED",
                objectMapper.readTree(ended.getResponse().getContentAsString()).get("code").asText());
        assertTrue(reserveStatus(endedId).get("blockReasons").toString().contains("WINDOW_ENDED"));

        // 未来窗口：应急核销成功后关闭窗口，历史快照保留，新建被拒
        long windowId = createWindow("ch-close-" + run, "2026-11-20T00:00:00Z", "2026-11-20T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "4"), "dispatcher");
        JsonNode writeoff = emergencyOk(windowId, key("rk"), 1L,
                List.of(emergencyItem("em-open", "chief", "1.5")), "alice");
        assertEquals("4", writeoff.get(0).get("reserveSnapshot").asText());

        JsonNode closed = postOk("/api/windows/" + windowId + "/close",
                Map.of("reserveKey", key("rk"), "expectedVersion", 2L), "dispatcher");
        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals(3, closed.get("version").asLong());
        // 重复关闭 -> 409
        postJson("/api/windows/" + windowId + "/close",
                Map.of("reserveKey", key("rk"), "expectedVersion", 3L), "dispatcher", 409);
        // 关闭后调整储备 -> 409（储备快照冻结）
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(key("rk"), 3L, "2"), "dispatcher", 409);
        // 关闭后新建应急核销 -> 409
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(key("rk"), 3L, List.of(emergencyItem("em-after", "chief", "1"))),
                "alice", 409);
        // 历史储备快照与应急核销流水仍可查询
        JsonNode historyList = getOk("/api/windows/" + windowId + "/writeoffs/emergency");
        assertEquals(1, historyList.size());
        assertEquals("em-open", historyList.get(0).get("emergencyId").asText());
        assertEquals("4", historyList.get(0).get("reserveSnapshot").asText());
        JsonNode status = reserveStatus(windowId);
        assertEquals("CLOSED", status.get("windowStatus").asText());
        assertEquals("4", status.get("reserveVolume").asText());
        assertEquals("2.5", status.get("reserveRemaining").asText());
    }

    // ------------------------------------------------------------------
    // reserveKey 指纹幂等
    // ------------------------------------------------------------------

    @Test
    void reserveKeyReplaysFirstSnapshotAndRejectsFingerprintChange() throws Exception {
        long windowId = createWindow("ch-key-" + run, "2026-10-27T00:00:00Z", "2026-10-27T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "5"), "dispatcher");

        // 应急批量同键同指纹重放：返回首次两条流水，不重复扣减
        String rk = key("rk");
        List<Map<String, Object>> items = List.of(emergencyItem("em-x", "chief", "1"),
                emergencyItem("em-y", "chief", "1"));
        JsonNode first = emergencyOk(windowId, rk, 1L, items, "alice");
        JsonNode replay = emergencyOk(windowId, rk, 1L, items, "alice");
        assertEquals(first, replay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
        assertEquals("3", reserveStatus(windowId).get("reserveRemaining").asText());
        // 同键改数量（指纹变化）-> 409
        postJson("/api/windows/" + windowId + "/writeoffs/emergency",
                emergencyBatchBody(rk, 1L, List.of(emergencyItem("em-x", "chief", "2"))),
                "alice", 409);

        // 储备调整同键同指纹重放
        String adjustKey = key("rk");
        Map<String, Object> adjustBody = reserveBody(adjustKey, 1L, "5");
        JsonNode a1 = postOk("/api/windows/" + windowId + "/reserve", adjustBody, "dispatcher");
        JsonNode a2 = postOk("/api/windows/" + windowId + "/reserve", adjustBody, "dispatcher");
        assertEquals(a1, a2);
        // 同键改储备量 -> 409
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(adjustKey, 1L, "4"), "dispatcher", 409);
        // 同键改操作者 -> 409
        postJson("/api/windows/" + windowId + "/reserve",
                reserveBody(adjustKey, 1L, "5"), "someone-else", 409);

        // 窗口关闭同键重放
        String closeKey = key("rk");
        Map<String, Object> closeBody = Map.of("reserveKey", closeKey, "expectedVersion", 2L);
        JsonNode c1 = postOk("/api/windows/" + windowId + "/close", closeBody, "dispatcher");
        JsonNode c2 = postOk("/api/windows/" + windowId + "/close", closeBody, "dispatcher");
        assertEquals(c1, c2);
        assertEquals("CLOSED", c2.get("status").asText());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void reserveAdjustmentAndTransferSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-r-" + run, "2026-10-28T00:00:00Z", "2026-10-28T02:00:00Z", "10");
        String source = submit(windowId, "user-src", "6", "alice");
        String target = submit(windowId, "user-dst", "4", "bob");
        approve(source, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String adjustKey = key("rk");
        String transferReserveKey = key("rk");
        String transferKey = key("tk");
        Future<Integer> adjustFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.adjustReserve(adjustKey, windowId, 0L, "6", "dispatcher");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(key("tkc"), transferKey, source, target, "alice",
                        transferReserveKey, 0L);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int adjustStatus = adjustFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 储备 6 与转让后态占用 6 不能并存（6+6>10）：恰好一个成功，失败方 422
        assertEquals(1, (adjustStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0),
                "adjust=" + adjustStatus + " transfer=" + transferStatus);
        int failed = adjustStatus == 200 ? transferStatus : adjustStatus;
        assertEquals(422, failed);
        JsonNode status = reserveStatus(windowId);
        if (adjustStatus == 200) {
            // 储备先提交：转让被隔离阻断，储备 6、无流水
            assertEquals("6", status.get("reserveVolume").asText());
            assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        } else {
            // 转让先提交：储备调整被后态回查拒绝，储备仍 0、流水 1 条
            assertEquals("0", status.get("reserveVolume").asText());
            assertEquals(1, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        }
    }

    @Test
    void concurrentEmergencyWriteoffsSettleByCommitOrderWithoutOverspendingReserve() throws Exception {
        long windowId = createWindow("ch-race-e-" + run, "2026-10-29T00:00:00Z", "2026-10-29T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "5"), "dispatcher");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String emergencyId : List.of("em-conc-1", "em-conc-2")) {
            String rk = key("rk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.emergencyWriteoffBatch(rk, windowId, 1L,
                            List.of(new com.example.starter.water.dto.Dtos.EmergencyWriteoffItem(
                                    emergencyId, "chief", "3")), "alice");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int insufficient = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                insufficient++;
            }
        }
        pool.shutdown();
        // 储备 5：两笔各 3 按提交顺序恰好一笔成功，另一笔储备不足回滚
        assertEquals(1, ok);
        assertEquals(1, insufficient);
        JsonNode status = reserveStatus(windowId);
        assertEquals("2", status.get("reserveRemaining").asText());
        assertEquals("3", status.get("emergencyWrittenOff").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
    }

    @Test
    void concurrentSameEmergencyIdSucceedsAtMostOnce() throws Exception {
        long windowId = createWindow("ch-race-id-" + run, "2026-10-30T00:00:00Z", "2026-10-30T02:00:00Z", "10");
        postOk("/api/windows/" + windowId + "/reserve", reserveBody(key("rk"), 0L, "5"), "dispatcher");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String rk = key("rk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.emergencyWriteoffBatch(rk, windowId, 1L,
                            List.of(new com.example.starter.water.dto.Dtos.EmergencyWriteoffItem(
                                    "em-once", "chief", "1")), "alice");
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
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一窗口同一应急编号只能核销一次");
        assertEquals(1, conflict);
        assertEquals(1, getOk("/api/windows/" + windowId + "/writeoffs/emergency").size());
        assertEquals("4", reserveStatus(windowId).get("reserveRemaining").asText());
    }
}
