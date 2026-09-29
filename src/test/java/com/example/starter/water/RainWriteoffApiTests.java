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
 * 灌区降雨抵扣与窗口核销 API 测试：主流程、守恒、失败分支与逐项原因、事务回滚无半成品、
 * 幂等（同参重放/异参 409/失败不占键）、并发裁决、诊断与历史稳定排序。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、唯一约束与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RainWriteoffApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private WaterRepository repository;

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

    private String submitApproved(long windowId, String userId, String amount, String coefficient,
                                  String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (coefficient != null) {
            body.put("creditCoefficient", coefficient);
        }
        JsonNode node = postOk("/api/allocations", body, actor);
        assertEquals("REQUESTED", node.get("status").asText());
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    private String registerRain(long windowId, String rainMillis, String observedUtc, String actor)
            throws Exception {
        String rainKey = key("rk");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("rrc"));
        body.put("rainKey", rainKey);
        body.put("rainMillis", rainMillis);
        body.put("observedUtc", observedUtc);
        JsonNode node = postOk("/api/windows/" + windowId + "/rain", body, actor);
        assertEquals("PENDING", node.get("status").asText());
        return rainKey;
    }

    private JsonNode registerRain(String rainKey, String commandKey, long windowId, String rainMillis,
                                  String observedUtc, String actor, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("rainKey", rainKey);
        body.put("rainMillis", rainMillis);
        body.put("observedUtc", observedUtc);
        return objectMapper.readTree(postJson("/api/windows/" + windowId + "/rain", body, actor,
                expectedStatus).getResponse().getContentAsString());
    }

    private void confirm(String rainKey, String officer, int expectedStatus) throws Exception {
        postJson("/api/rain/" + rainKey + "/confirm", Map.of("commandKey", key("rcf")),
                officer, expectedStatus);
    }

    private JsonNode confirmOk(String rainKey, String officer) throws Exception {
        return postOk("/api/rain/" + rainKey + "/confirm", Map.of("commandKey", key("rcf")), officer);
    }

    private JsonNode makeEffectiveRain(long windowId, String rainMillis) throws Exception {
        String rainKey = registerRain(windowId, rainMillis, "2026-10-01T00:30:00Z", "clerk");
        JsonNode afterFirst = confirmOk(rainKey, "officer-a");
        assertEquals("PENDING", afterFirst.get("status").asText());
        JsonNode effective = confirmOk(rainKey, "officer-b");
        assertEquals("EFFECTIVE", effective.get("status").asText());
        return effective;
    }

    private Map<String, Object> item(String allocationKey, String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("allocationKey", allocationKey);
        item.put("amount", amount);
        return item;
    }

    private JsonNode writeoff(long windowId, String writeoffKey, List<Map<String, Object>> items,
                              String actor, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("woc"));
        body.put("writeoffKey", writeoffKey);
        body.put("items", items);
        return objectMapper.readTree(postJson("/api/windows/" + windowId + "/writeoffs", body, actor,
                expectedStatus).getResponse().getContentAsString());
    }

    private JsonNode writeoffOk(long windowId, String allocationKey, String amount, String actor)
            throws Exception {
        String writeoffKey = key("wo");
        return writeoff(windowId, writeoffKey, List.of(item(allocationKey, amount)), actor, 200);
    }

    private JsonNode getAllocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 主流程与守恒
    // ------------------------------------------------------------------

    @Test
    void rainConfirmationCreditAndWriteoffConservation() throws Exception {
        long windowId = createWindow("ch-rain-flow-" + run, "10");
        String allocation = submitApproved(windowId, "user-1", "5", "0.2", "alice");

        String rainKey = registerRain(windowId, "10.000", "2026-10-01T00:30:00Z", "clerk");
        // 仅一名确认：仍 PENDING，此时核销无抵扣
        confirm(rainKey, "officer-a", 200);
        JsonNode before = writeoffOk(windowId, allocation, "1", "alice");
        assertEquals("0", before.get("totalCredit").asText());
        assertEquals("1", before.get("totalQuotaDeduction").asText());
        // 持有额度 5 -> 4
        assertEquals("4", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 第二名不同水务角色确认后生效
        JsonNode effective = confirmOk(rainKey, "officer-b");
        assertEquals("EFFECTIVE", effective.get("status").asText());
        assertNotNull(effective.get("effectiveUtc"));
        assertEquals(2, effective.get("confirmations").size());
        assertEquals(2, effective.get("requiredConfirmations").asInt());

        // 核销 3：抵扣 10mm * 0.2 = 2，配额扣减 1；2 + 1 = 3
        JsonNode writeoff = writeoffOk(windowId, allocation, "3", "alice");
        assertEquals("3", writeoff.get("totalAmount").asText());
        assertEquals("2", writeoff.get("totalCredit").asText());
        assertEquals("1", writeoff.get("totalQuotaDeduction").asText());
        JsonNode itemNode = writeoff.get("items").get(0);
        assertEquals(0, itemNode.get("seq").asInt());
        assertEquals("2", itemNode.get("credit").asText());
        assertEquals("1", itemNode.get("quotaDeduction").asText());
        assertEquals("0.2", itemNode.get("coefficient").asText());
        assertEquals(rainKey, itemNode.get("appliedRains").get(0).get("rainKey").asText());
        assertEquals("10", itemNode.get("appliedRains").get(0).get("rainMillis").asText());
        assertEquals("2", itemNode.get("appliedRains").get(0).get("credit").asText());

        // 持有额度：5 - 1（首笔配额）- 1（本笔配额）= 3；降雨抵扣不扣持有额度以外的量
        assertEquals("3", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 核销单可查询且快照不可变
        JsonNode queried = getOk("/api/writeoffs/" + writeoff.get("writeoffKey").asText());
        assertEquals(writeoff, queried);
        assertEquals("SETTLED", queried.get("status").asText());

        // 诊断返回实际生效雨量与申请级预览（按当前持有额度 3：抵扣 2，配额 1，差额 0）
        JsonNode diag = getOk("/api/windows/" + windowId + "/rain/diagnostics");
        assertEquals("10", diag.get("effectiveRainMillis").asText());
        assertEquals(1, diag.get("effectiveRain").size());
        JsonNode allocationDiag = diag.get("allocations").get(0);
        assertEquals("2", allocationDiag.get("creditPreview").asText());
        assertEquals("1", allocationDiag.get("quotaDeductionPreview").asText());
        assertEquals("0", allocationDiag.get("shortfallPreview").asText());
    }

    @Test
    void creditIsCappedAtWriteoffAmountAndQuotaDeductionNonNegative() throws Exception {
        long windowId = createWindow("ch-cap-" + run, "100");
        String allocation = submitApproved(windowId, "user-1", "5", "10", "alice");
        makeEffectiveRain(windowId, "1000.000");

        JsonNode writeoff = writeoffOk(windowId, allocation, "5", "alice");
        // 理论抵扣 10000，封顶为本次核销量 5，配额扣减为 0
        assertEquals("5", writeoff.get("totalCredit").asText());
        assertEquals("0", writeoff.get("totalQuotaDeduction").asText());
        assertEquals("5", getAllocation(windowId, allocation).get("heldAmount").asText());
        // 降雨不被“消耗”：再次核销 5 仍全额抵扣
        JsonNode again = writeoffOk(windowId, allocation, "5", "alice");
        assertEquals("5", again.get("totalCredit").asText());
        assertEquals("0", again.get("totalQuotaDeduction").asText());
        assertEquals("5", getAllocation(windowId, allocation).get("heldAmount").asText());
    }

    @Test
    void allocationWithoutCoefficientGetsNoCredit() throws Exception {
        long windowId = createWindow("ch-nocoef-" + run, "10");
        String withCoefficient = submitApproved(windowId, "user-1", "3", "0.2", "alice");
        String withoutCoefficient = submitApproved(windowId, "user-2", "3", null, "bob");
        makeEffectiveRain(windowId, "10.000");

        JsonNode w1 = writeoffOk(windowId, withoutCoefficient, "3", "bob");
        assertEquals("0", w1.get("totalCredit").asText());
        assertEquals("3", w1.get("totalQuotaDeduction").asText());
        assertTrue(w1.get("items").get(0).get("coefficient").isNull());
        assertEquals(0, w1.get("items").get(0).get("appliedRains").size());

        JsonNode w2 = writeoffOk(windowId, withCoefficient, "3", "alice");
        assertEquals("2", w2.get("totalCredit").asText());
        assertEquals("1", w2.get("totalQuotaDeduction").asText());

        // null 系数语义在申请视图稳定
        assertTrue(getAllocation(windowId, withoutCoefficient).get("creditCoefficient").isNull());
        assertEquals("0.2", getAllocation(windowId, withCoefficient).get("creditCoefficient").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支：422 业务校验 / 400 参数 / 409 状态 / 404
    // ------------------------------------------------------------------

    @Test
    void invalidRainAndCoefficientsAreRejectedWithDistinctStatus() throws Exception {
        long windowId = createWindow("ch-rainbad-" + run, "10");

        // 零、负降雨量 -> 422
        registerRain(key("rk"), key("rrc"), windowId, "0", "2026-10-01T00:30:00Z", "clerk", 422);
        registerRain(key("rk"), key("rrc"), windowId, "-2.5", "2026-10-01T00:30:00Z", "clerk", 422);
        // 非法格式 -> 400
        registerRain(key("rk"), key("rrc"), windowId, "abc", "2026-10-01T00:30:00Z", "clerk", 400);
        registerRain(key("rk"), key("rrc"), windowId, "1.0001", "2026-10-01T00:30:00Z", "clerk", 400);

        // 观测时刻窗口不匹配：早于起点、恰好等于终点（左闭右开）-> 422；起点时刻合法
        registerRain(key("rk"), key("rrc"), windowId, "1", "2026-09-30T23:59:59Z", "clerk", 422);
        registerRain(key("rk"), key("rrc"), windowId, "1", "2026-10-01T02:00:00Z", "clerk", 422);
        String atStart = registerRain(windowId, "1", "2026-10-01T00:00:00Z", "clerk");
        // 纳秒精度时间被完整保留并原样返回
        String preciseKey = key("rk");
        JsonNode precise = registerRain(preciseKey, key("rrc"), windowId, "2.5",
                "2026-10-01T00:45:00.123456789Z", "clerk", 200);
        assertEquals("2026-10-01T00:45:00.123456789Z", precise.get("observedUtc").asText());
        assertEquals("2.5", precise.get("rainMillis").asText());

        // 同窗口存在 PENDING 不阻止登记；生效后再登记 -> 422
        String r1 = registerRain(windowId, "3", "2026-10-01T00:20:00Z", "clerk");
        confirm(r1, "officer-a", 200);
        confirm(r1, "officer-b", 200);
        registerRain(key("rk"), key("rrc"), windowId, "4", "2026-10-01T00:25:00Z", "clerk", 422);

        // 系数负 -> 422；格式坏 -> 400
        String allocationKey = key("ak");
        Map<String, Object> negCoeff = new HashMap<>();
        negCoeff.put("commandKey", key("ac"));
        negCoeff.put("allocationKey", allocationKey);
        negCoeff.put("windowId", windowId);
        negCoeff.put("userId", "u1");
        negCoeff.put("amount", "1");
        negCoeff.put("creditCoefficient", "-0.1");
        postJson("/api/allocations", negCoeff, "alice", 422);
        negCoeff.put("commandKey", key("ac"));
        negCoeff.put("creditCoefficient", "0.1234567");
        postJson("/api/allocations", negCoeff, "alice", 400);

        // 降雨/核销对象不存在 -> 404；窗口不存在 -> 404
        postJson("/api/rain/missing-" + run + "/confirm", Map.of("commandKey", key("rcf")),
                "officer-a", 404);
        postJson("/api/rain/missing-" + run + "/revoke", Map.of("commandKey", key("rrv")),
                "clerk", 404);
        registerRain(key("rk"), key("rrc"), 999999999L, "1", "2026-10-01T00:30:00Z", "clerk", 404);

        // 已撤销不能确认；重复撤销 409；同一角色重复确认 409
        confirm(atStart, "officer-a", 200);
        confirm(atStart, "officer-a", 409);
        // PENDING 记录可撤销；撤销后确认 409、重复撤销 409
        JsonNode revoked = postOk("/api/rain/" + atStart + "/revoke",
                Map.of("commandKey", key("rrv")), "supervisor");
        assertEquals("REVOKED", revoked.get("status").asText());
        assertEquals("supervisor", revoked.get("revokedBy").asText());
        assertNotNull(revoked.get("revokedUtc"));
        assertTrue(revoked.get("effectiveUtc").isNull());
        confirm(atStart, "officer-d", 409);
        postJson("/api/rain/" + atStart + "/revoke", Map.of("commandKey", key("rrv")),
                "supervisor", 409);
    }

    @Test
    void writeoffBeyondHeldReturns422WithActualRequiredDifference() throws Exception {
        long windowId = createWindow("ch-wobad-" + run, "10");
        String allocation = submitApproved(windowId, "user-1", "5", "0.2", "alice");
        makeEffectiveRain(windowId, "10.000");

        // 核销 8：抵扣 2 封顶之外，需配额 6 > 持有 5 -> 422，差额 1
        String failedWriteoffKey = key("wo");
        JsonNode error = writeoff(windowId, failedWriteoffKey, List.of(item(allocation, "8")), "alice", 422);
        assertEquals("WRITEOFF_REJECTED", error.get("code").asText());
        JsonNode detail = error.get("details").get(0);
        assertEquals(0, detail.get("item").asInt());
        assertEquals("QUOTA_EXCEEDED", detail.get("code").asText());
        assertEquals("6", detail.get("requested").asText());
        assertEquals("5", detail.get("actual").asText());
        assertEquals("1", detail.get("difference").asText());
        // 失败后无半成品：核销单查不到，持有额度不变，仍可成功核销
        mvc.perform(get("/api/writeoffs/" + failedWriteoffKey)).andExpect(status().isNotFound());
        assertEquals("5", getAllocation(windowId, allocation).get("heldAmount").asText());
        writeoffOk(windowId, allocation, "5", "alice");
    }

    // ------------------------------------------------------------------
    // 撤销不改写已结算核销
    // ------------------------------------------------------------------

    @Test
    void revokingRainAfterWriteoffKeepsSettledEvidenceFrozen() throws Exception {
        long windowId = createWindow("ch-freeze-" + run, "10");
        String allocation = submitApproved(windowId, "user-1", "5", "0.2", "alice");
        JsonNode rain = makeEffectiveRain(windowId, "10.000");
        String rainKey = rain.get("rainKey").asText();
        String writeoffKey = key("wo");
        JsonNode first = writeoff(windowId, writeoffKey, List.of(item(allocation, "5")), "alice", 200);
        assertEquals("2", first.get("totalCredit").asText());
        assertEquals("3", first.get("totalQuotaDeduction").asText());
        assertEquals("2", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 撤销降雨：已结算核销快照不变
        postJson("/api/rain/" + rainKey + "/revoke", Map.of("commandKey", key("rrv")),
                "supervisor", 200);
        JsonNode frozen = getOk("/api/writeoffs/" + writeoffKey);
        assertEquals(first, frozen);
        assertEquals("2", frozen.get("items").get(0).get("appliedRains").get(0).get("credit").asText());
        assertEquals("0.2", frozen.get("items").get(0).get("coefficient").asText());
        assertEquals("2", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 撤销后新核销不再享受抵扣：核销 2 全部扣配额
        JsonNode later = writeoffOk(windowId, allocation, "2", "alice");
        assertEquals("0", later.get("totalCredit").asText());
        assertEquals("2", later.get("totalQuotaDeduction").asText());
        assertEquals("0", getAllocation(windowId, allocation).get("heldAmount").asText());

        // 降雨视图保留生效时刻等历史证据，撤销字段齐备
        JsonNode list = getOk("/api/windows/" + windowId + "/rain");
        JsonNode rainView = null;
        for (JsonNode node : list.get("records")) {
            if (rainKey.equals(node.get("rainKey").asText())) {
                rainView = node;
            }
        }
        assertNotNull(rainView);
        assertEquals("REVOKED", rainView.get("status").asText());
        assertNotNull(rainView.get("effectiveUtc"));
        assertEquals(2, rainView.get("confirmations").size());
    }

    // ------------------------------------------------------------------
    // 批量核销：先验最终余额，失败整批回滚
    // ------------------------------------------------------------------

    @Test
    void batchWriteoffValidatesFinalBalancesAndLeavesNoHalfState() throws Exception {
        long windowId = createWindow("ch-batch-" + run, "20");
        String a1 = submitApproved(windowId, "user-1", "5", "0.2", "alice");
        String a2 = submitApproved(windowId, "user-2", "5", "0.1", "bob");
        makeEffectiveRain(windowId, "10.000");

        String writeoffKey = key("wo");
        // a1 需配额 3（持有 5，够）；a2 核销 8：需配额 7（持有 5，差额 2）且超过原申请水量 5（差额 3）
        // -> 整批 422，同一笔返回两个可区分原因
        JsonNode error = writeoff(windowId, writeoffKey,
                List.of(item(a1, "5"), item(a2, "8")), "clerk", 422);
        JsonNode details = error.get("details");
        assertEquals(2, details.size());
        assertEquals(1, details.get(0).get("item").asInt());
        assertEquals(a2, details.get(0).get("allocationKey").asText());
        assertEquals("QUOTA_EXCEEDED", details.get(0).get("code").asText());
        assertEquals("2", details.get(0).get("difference").asText());
        assertEquals("WRITEOFF_EXCEEDS_ORIGINAL_AMOUNT", details.get(1).get("code").asText());
        assertEquals("3", details.get(1).get("difference").asText());
        // 两笔持有额度均未变化，核销单不存在，历史无核销
        assertEquals("5", getAllocation(windowId, a1).get("heldAmount").asText());
        assertEquals("5", getAllocation(windowId, a2).get("heldAmount").asText());
        mvc.perform(get("/api/writeoffs/" + writeoffKey)).andExpect(status().isNotFound());
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(0, history.get("writeoffs").size());

        // 同键同参（业务条件未变）仍 422；改合法批次后同键可用（失败不占键）
        writeoff(windowId, writeoffKey, List.of(item(a1, "5"), item(a2, "8")), "clerk", 422);
        JsonNode ok = writeoff(windowId, writeoffKey,
                List.of(item(a1, "5"), item(a2, "4")), "clerk", 200);
        // a1: 抵扣2 配额3；a2: 抵扣1 配额3；总量 9 / 3 / 6
        assertEquals("9", ok.get("totalAmount").asText());
        assertEquals("3", ok.get("totalCredit").asText());
        assertEquals("6", ok.get("totalQuotaDeduction").asText());
        assertEquals(2, ok.get("items").size());
        assertEquals(a1, ok.get("items").get(0).get("allocationKey").asText());
        assertEquals(0, ok.get("items").get(0).get("seq").asInt());
        assertEquals(1, ok.get("items").get(1).get("seq").asInt());
        assertEquals("2", getAllocation(windowId, a1).get("heldAmount").asText());
        assertEquals("2", getAllocation(windowId, a2).get("heldAmount").asText());
    }

    @Test
    void batchWriteoffRejectsCrossWindowNonApprovedAndEmptyItems() throws Exception {
        long w1 = createWindow("ch-b1-" + run, "10");
        long w2 = createWindow("ch-b2-" + run, "10");
        String a1 = submitApproved(w1, "user-1", "2", null, "alice");
        String otherWindow = submitApproved(w2, "user-2", "2", null, "bob");
        String requested = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", requested);
        body.put("windowId", w1);
        body.put("userId", "user-3");
        body.put("amount", "2");
        postOk("/api/allocations", body, "carol");

        // 跨窗口 -> 422，明细原因可区分
        JsonNode cross = writeoff(w1, key("wo"), List.of(item(a1, "1"), item(otherWindow, "1")),
                "clerk", 422);
        assertEquals("WRITEOFF_REJECTED", cross.get("code").asText());
        assertEquals("DIFFERENT_WINDOW", cross.get("details").get(0).get("code").asText());
        // 路径窗口与首项窗口不一致 -> 409
        JsonNode mismatch = writeoff(w2, key("wo"), List.of(item(a1, "1")), "clerk", 409);
        assertEquals("WRITEOFF_WINDOW_MISMATCH", mismatch.get("code").asText());
        // 非 APPROVED -> 422 明细
        JsonNode notApproved = writeoff(w1, key("wo"), List.of(item(requested, "1")), "clerk", 422);
        assertEquals("ALLOCATION_NOT_APPROVED", notApproved.get("details").get(0).get("code").asText());
        // 空 items -> 400
        Map<String, Object> emptyBatch = new HashMap<>();
        emptyBatch.put("commandKey", key("woc"));
        emptyBatch.put("writeoffKey", key("wo"));
        emptyBatch.put("items", List.of());
        postJson("/api/windows/" + w1 + "/writeoffs", emptyBatch, "clerk", 400);
        // 申请不存在 -> 404
        writeoff(w1, key("wo"), List.of(item("missing-" + run, "1")), "clerk", 404);
    }

    // ------------------------------------------------------------------
    // 幂等：同参快照重放、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void rainAndWriteoffCommandsReplaySnapshotAndRejectDifferentParams() throws Exception {
        long windowId = createWindow("ch-rain-idem-" + run, "10");
        String allocation = submitApproved(windowId, "user-1", "5", "0.2", "alice");
        makeEffectiveRain(windowId, "10.000");

        // 核销同键同参重放：返回首次完整响应快照，不重复扣减
        String commandKey = key("woc");
        String writeoffKey = key("wo");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeoffKey", writeoffKey);
        body.put("items", List.of(item(allocation, "4")));
        JsonNode first = postOk("/api/windows/" + windowId + "/writeoffs", body, "alice");
        JsonNode replay = postOk("/api/windows/" + windowId + "/writeoffs", body, "alice");
        assertEquals(first, replay);
        assertEquals("3", getAllocation(windowId, allocation).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("writeoffs").size());

        // 同键改数量 -> 409；改操作者 -> 409；改 writeoffKey -> 409；改明细集合 -> 409
        body.put("items", List.of(item(allocation, "3")));
        postJson("/api/windows/" + windowId + "/writeoffs", body, "alice", 409);
        postJson("/api/windows/" + windowId + "/writeoffs", body, "bob", 409);
        body.put("items", List.of(item(allocation, "4")));
        body.put("writeoffKey", key("wo"));
        postJson("/api/windows/" + windowId + "/writeoffs", body, "alice", 409);
        body.put("writeoffKey", writeoffKey);
        body.put("items", List.of(item(allocation, "2"), item(allocation, "2")));
        postJson("/api/windows/" + windowId + "/writeoffs", body, "alice", 409);

        // 降雨登记/确认幂等在独立窗口验证（本窗口已有生效降雨，不能再登记）
        long rainWindowId = createWindow("ch-rain-idem2-" + run, "10");
        String rainCommand = key("rrc");
        String rainKey = key("rk");
        Map<String, Object> rainBody = new HashMap<>();
        rainBody.put("commandKey", rainCommand);
        rainBody.put("rainKey", rainKey);
        rainBody.put("rainMillis", "6.000");
        rainBody.put("observedUtc", "2026-10-01T00:55:00Z");
        JsonNode rainFirst = postOk("/api/windows/" + rainWindowId + "/rain", rainBody, "clerk");
        JsonNode rainReplay = postOk("/api/windows/" + rainWindowId + "/rain", rainBody, "clerk");
        assertEquals(rainFirst, rainReplay);
        rainBody.put("rainMillis", "7.000");
        postJson("/api/windows/" + rainWindowId + "/rain", rainBody, "clerk", 409);
        rainBody.put("rainMillis", "6.000");
        rainBody.put("observedUtc", "2026-10-01T00:56:00Z");
        postJson("/api/windows/" + rainWindowId + "/rain", rainBody, "clerk", 409);

        // 确认命令同键同参重放（第一名确认人），改操作者 409
        String confirmKey = key("rcf");
        JsonNode c1 = postOk("/api/rain/" + rainKey + "/confirm",
                Map.of("commandKey", confirmKey), "officer-c");
        JsonNode c2 = postOk("/api/rain/" + rainKey + "/confirm",
                Map.of("commandKey", confirmKey), "officer-c");
        assertEquals(c1, c2);
        postJson("/api/rain/" + rainKey + "/confirm", Map.of("commandKey", confirmKey),
                "officer-d", 409);

        // 读取不改变状态：重复查询诊断/历史/核销单后状态一致
        assertEquals(first, getOk("/api/writeoffs/" + writeoffKey));
    }

    @Test
    void failedRainRegistrationDoesNotOccupyCommandKey() throws Exception {
        long windowId = createWindow("ch-rain-failidem-" + run, "10");
        String commandKey = key("rrc");
        // 首次失败（负雨量 422）不占键
        registerRain(key("rk"), commandKey, windowId, "-9", "2026-10-01T00:30:00Z", "clerk", 422);
        // 同键用于一次合法登记 -> 成功
        JsonNode ok = registerRain(key("rk"), commandKey, windowId, "9", "2026-10-01T00:30:00Z",
                "clerk", 200);
        assertEquals("9", ok.get("rainMillis").asText());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void twoPendingRainsRacingToEffectiveProduceExactlyOne() throws Exception {
        long windowId = createWindow("ch-rain-race-" + run, "10");
        String r1 = registerRain(windowId, "4", "2026-10-01T00:10:00Z", "clerk");
        String r2 = registerRain(windowId, "5", "2026-10-01T00:20:00Z", "clerk");
        // 各先有一名确认人
        confirm(r1, "officer-a", 200);
        confirm(r2, "officer-c", 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> confirmStatus(key("rcf"), r1, "officer-b", gate));
        Future<Integer> f2 = pool.submit(() -> confirmStatus(key("rcf"), r2, "officer-d", gate));
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一条生效，另一条 422；数据库中同窗口只有一条 EFFECTIVE，且生效记录有两名确认人
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        List<WaterRepository.RainRecordRow> effectiveRains = repository.listEffectiveRain(windowId);
        assertEquals(1, effectiveRains.size());
        assertEquals(2, repository.listRainConfirmations(effectiveRains.get(0).id()).size());
        // 落败记录仍为 PENDING，其第二次确认随事务回滚，仍只有一名确认人
        long pendingId = repository.findRainByKey(r1).status().equals("PENDING")
                ? repository.findRainByKey(r1).id() : repository.findRainByKey(r2).id();
        assertEquals(1, repository.listRainConfirmations(pendingId).size());
    }

    @Test
    void concurrentWriteoffsNeverDeductionBeyondHeldAmount() throws Exception {
        long windowId = createWindow("ch-wo-race-" + run, "10");
        String allocation = submitApproved(windowId, "user-1", "5", "0.2", "alice");
        makeEffectiveRain(windowId, "10.000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 每笔核销 5：抵扣 2、配额扣减 3；持有 5 只够一笔成功
        Future<Integer> f1 = pool.submit(() -> writeoffStatus(key("woc"), key("wo"), windowId,
                List.of(item(allocation, "5")), "alice", gate));
        Future<Integer> f2 = pool.submit(() -> writeoffStatus(key("woc"), key("wo"), windowId,
                List.of(item(allocation, "5")), "alice", gate));
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        // 恰好一笔配额扣减 3：持有 5 -> 2；恰好一张核销单
        assertEquals("2", getAllocation(windowId, allocation).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("writeoffs").size());
    }

    @Test
    void writeoffAndTransferOfSameAllocationSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-wo-tx-" + run, "10");
        String source = submitApproved(windowId, "user-src", "5", "0.2", "alice");
        String target = submitApproved(windowId, "user-dst", "3", null, "bob");
        makeEffectiveRain(windowId, "10.000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String writeoffCommand = key("woc");
        String writeoffKey = key("wo");
        String transferCommand = key("tkc");
        String transferKey = key("tk");
        Future<Integer> writeoffFuture = pool.submit(() -> {
            gate.await();
            return writeoffStatus(writeoffCommand, writeoffKey, windowId,
                    List.of(item(source, "5")), "alice", gate);
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, source, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int writeoffStatus = writeoffFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 转让先提交：源持有 2，核销需配额 3 > 2 -> 核销 422；
        // 核销先提交：源持有 2（扣 3），转让需 3 > 2 -> 转让 422。二者不会都成功，额度永不为负。
        assertTrue((writeoffStatus == 200) != (transferStatus == 200),
                "writeoff=" + writeoffStatus + " transfer=" + transferStatus);
        JsonNode sourceNode = getAllocation(windowId, source);
        assertTrue(Double.parseDouble(sourceNode.get("heldAmount").asText()) >= 0);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()) + 0.000001);
    }

    private int confirmStatus(String commandKey, String rainKey, String officer, CountDownLatch gate) {
        try {
            gate.await();
            waterService.confirmRain(commandKey, rainKey, officer);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private int writeoffStatus(String commandKey, String writeoffKey, long windowId,
                               List<Map<String, Object>> rawItems, String actor, CountDownLatch gate) {
        try {
            gate.await();
            List<com.example.starter.water.dto.Dtos.WriteoffItemRequest> items = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                items.add(new com.example.starter.water.dto.Dtos.WriteoffItemRequest(
                        (String) raw.get("allocationKey"), (String) raw.get("amount")));
            }
            waterService.writeoffBatch(commandKey, writeoffKey, windowId, items, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 历史稳定排序与 null 语义
    // ------------------------------------------------------------------

    @Test
    void historyContainsRainsAndWriteoffsInStableOrderWithNullSemantics() throws Exception {
        long windowId = createWindow("ch-his-" + run, "20");
        String a1 = submitApproved(windowId, "user-1", "8", "0.2", "alice");
        // 一条 PENDING（永不生效）与一条 EFFECTIVE
        String pending = registerRain(windowId, "1.000", "2026-10-01T00:05:00Z", "clerk");
        String r2 = registerRain(windowId, "10.000", "2026-10-01T00:10:00Z", "clerk");
        confirm(r2, "officer-a", 200);
        confirm(r2, "officer-b", 200);

        JsonNode w1 = writeoffOk(windowId, a1, "4", "alice");
        JsonNode w2 = writeoffOk(windowId, a1, "4", "alice");

        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        // 新增历史字段追加在既有字段之后，既有字段仍在
        assertTrue(history.has("rainRecords"));
        assertTrue(history.has("writeoffs"));
        assertEquals(2, history.get("rainRecords").size());
        // 按登记先后排序：pending 在前
        assertEquals(pending, history.get("rainRecords").get(0).get("rainKey").asText());
        assertEquals("PENDING", history.get("rainRecords").get(0).get("status").asText());
        assertTrue(history.get("rainRecords").get(0).get("effectiveUtc").isNull());
        assertTrue(history.get("rainRecords").get(0).get("revokedUtc").isNull());
        assertTrue(history.get("rainRecords").get(0).get("revokedBy").isNull());
        assertEquals(0, history.get("rainRecords").get(0).get("confirmations").size());
        assertEquals("EFFECTIVE", history.get("rainRecords").get(1).get("status").asText());

        // 核销按先后排序，明细 seq 从 0 起稳定
        List<String> writeoffKeys = List.of(w1.get("writeoffKey").asText(), w2.get("writeoffKey").asText());
        assertEquals(writeoffKeys.get(0), history.get("writeoffs").get(0).get("writeoffKey").asText());
        assertEquals(writeoffKeys.get(1), history.get("writeoffs").get(1).get("writeoffKey").asText());
        assertEquals(0, history.get("writeoffs").get(0).get("items").get(0).get("seq").asInt());

        // 降雨列表接口与历史一致，且稳定排序
        JsonNode rainList = getOk("/api/windows/" + windowId + "/rain");
        assertEquals(pending, rainList.get("records").get(0).get("rainKey").asText());
        mvc.perform(get("/api/windows/999999999/rain")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/rain/diagnostics")).andExpect(status().isNotFound());
    }
}
