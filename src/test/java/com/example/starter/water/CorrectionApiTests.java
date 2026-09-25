package com.example.starter.water;

import com.example.starter.water.dto.Dtos.CorrectionBatchResponse;
import com.example.starter.water.dto.Dtos.CorrectionResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配水计量更正 API 测试：计量版本、账目反算、储备约束、窗口状态、批量原子性、幂等与并发边界。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrectionApiTests {

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

    private String submitAndApprove(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postOk("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null);
        return allocationKey;
    }

    private Map<String, Object> correctionBody(String meterKey, String allocationKey, long baseVersion,
                                               String correctedAmount, String readingUtc, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("meterKey", meterKey);
        body.put("allocationKey", allocationKey);
        body.put("baseVersion", baseVersion);
        body.put("correctedAmount", correctedAmount);
        body.put("readingUtc", readingUtc);
        body.put("reason", reason);
        return body;
    }

    private String submitCorrection(String allocationKey, long baseVersion, String correctedAmount,
                                    String actor) throws Exception {
        String meterKey = key("mk");
        postOk("/api/corrections",
                correctionBody(meterKey, allocationKey, baseVersion, correctedAmount,
                        "2026-10-20T00:30:00Z", "读表偏差修正"), actor);
        return meterKey;
    }

    private JsonNode approveBatch(List<String> meterKeys, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/corrections/approve",
                Map.of("commandKey", key("ca"), "meterKeys", meterKeys), null, expectedStatus);
        return result.getResponse().getContentAsString().isEmpty()
                ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (node.get("allocationKey").asText().equals(allocationKey)) {
                return node;
            }
        }
        throw new AssertionError("allocation not found: " + allocationKey);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    // ------------------------------------------------------------------
    // 主流程：登记 -> 批准 -> 反向流水 + 快照 + 余额演算
    // ------------------------------------------------------------------

    @Test
    void submitApproveCorrectionAndBalanceEvolution() throws Exception {
        long windowId = createWindow("ch-mc-" + run,
                "2026-10-20T00:00:00Z", "2026-10-20T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");

        String meterKey = key("mk");
        JsonNode submitted = postOk("/api/corrections",
                correctionBody(meterKey, a1, 1L, "3.5", "2026-10-20T00:30:00Z", "读表偏差修正"), "carol");
        assertEquals("REQUESTED", submitted.get("status").asText());
        assertEquals(1L, submitted.get("baseVersion").asLong());
        assertEquals("3.5", submitted.get("correctedAmount").asText());
        assertEquals("2026-10-20T00:30:00Z", submitted.get("readingUtc").asText());
        assertEquals("读表偏差修正", submitted.get("reason").asText());
        assertEquals("carol", submitted.get("actor").asText());
        assertTrue(submitted.get("previousAmount").isNull());
        // 未批准时没有读表快照
        mvc.perform(get("/api/corrections/" + meterKey + "/snapshot")).andExpect(status().isNotFound());

        JsonNode batch = approveBatch(List.of(meterKey), 200);
        JsonNode approved = batch.get("corrections").get(0);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals("4", approved.get("previousAmount").asText());

        // 核销记录：持有额度改写为校正数量，版本递增，原申请水量不覆盖
        JsonNode allocation = allocation(windowId, a1);
        assertEquals("3.5", allocation.get("heldAmount").asText());
        assertEquals("4", allocation.get("amount").asText());
        assertEquals(2L, allocation.get("version").asLong());
        assertEquals("3.5", capacity(windowId).get("approvedTotal").asText());

        // 不可变读表快照
        JsonNode snapshot = getOk("/api/corrections/" + meterKey + "/snapshot");
        assertEquals(meterKey, snapshot.get("meterKey").asText());
        assertEquals("3.5", snapshot.get("correctedAmount").asText());
        assertEquals("2026-10-20T00:30:00Z", snapshot.get("readingUtc").asText());
        assertEquals("读表偏差修正", snapshot.get("reason").asText());

        // 余额演算：原核销 -> 更正反向流水
        JsonNode ledger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(2, ledger.get("entries").size());
        JsonNode writeOff = ledger.get("entries").get(0);
        assertEquals("WRITE_OFF", writeOff.get("entryType").asText());
        assertEquals("4", writeOff.get("delta").asText());
        assertEquals("4", writeOff.get("balanceAfter").asText());
        JsonNode correction = ledger.get("entries").get(1);
        assertEquals("CORRECTION", correction.get("entryType").asText());
        assertEquals(meterKey, correction.get("meterKey").asText());
        assertEquals("-0.5", correction.get("delta").asText());
        assertEquals("3.5", correction.get("balanceAfter").asText());
    }

    // ------------------------------------------------------------------
    // 计量版本
    // ------------------------------------------------------------------

    @Test
    void staleBaseVersionRejectedAndVersionIncrements() throws Exception {
        long windowId = createWindow("ch-ver-" + run,
                "2026-10-21T00:00:00Z", "2026-10-21T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");

        // 原核销版本与当前版本不一致 -> 409 STALE_VERSION
        String m1 = submitCorrection(a1, 2L, "3", "carol");
        JsonNode error = approveBatch(List.of(m1), 409);
        assertEquals("STALE_VERSION", error.get("code").asText());
        // 失败后更正仍为 REQUESTED，持有额度未变
        assertEquals("REQUESTED", getOk("/api/corrections/" + m1).get("status").asText());
        assertEquals("4", allocation(windowId, a1).get("heldAmount").asText());

        // 正确版本批准 -> 版本 2
        String m2 = submitCorrection(a1, 1L, "3", "carol");
        approveBatch(List.of(m2), 200);
        assertEquals(2L, allocation(windowId, a1).get("version").asLong());

        // 旧版本再次批准 -> 409；新版本 -> 200
        String m3 = submitCorrection(a1, 1L, "2", "carol");
        assertEquals("STALE_VERSION", approveBatch(List.of(m3), 409).get("code").asText());
        String m4 = submitCorrection(a1, 2L, "2", "carol");
        approveBatch(List.of(m4), 200);
        JsonNode allocation = allocation(windowId, a1);
        assertEquals(3L, allocation.get("version").asLong());
        assertEquals("2", allocation.get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 撤销：恢复余额 + 撤销反向流水 + 最终态校验
    // ------------------------------------------------------------------

    @Test
    void revokeRestoresBalanceAndWritesReversal() throws Exception {
        long windowId = createWindow("ch-rev-" + run,
                "2026-10-22T00:00:00Z", "2026-10-22T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");
        String meterKey = submitCorrection(a1, 1L, "2.5", "carol");
        approveBatch(List.of(meterKey), 200);
        assertEquals("2.5", allocation(windowId, a1).get("heldAmount").asText());

        JsonNode revoked = postOk("/api/corrections/" + meterKey + "/revoke",
                Map.of("commandKey", key("cr")), null);
        assertEquals("REVOKED", revoked.get("status").asText());
        // 恢复批准前持有额度，版本再次递增
        JsonNode allocation = allocation(windowId, a1);
        assertEquals("4", allocation.get("heldAmount").asText());
        assertEquals(3L, allocation.get("version").asLong());
        assertEquals("4", capacity(windowId).get("approvedTotal").asText());

        // 撤销反向流水
        JsonNode ledger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(3, ledger.get("entries").size());
        JsonNode reversal = ledger.get("entries").get(2);
        assertEquals("REVERSAL", reversal.get("entryType").asText());
        assertEquals(meterKey, reversal.get("meterKey").asText());
        assertEquals("1.5", reversal.get("delta").asText());
        assertEquals("4", reversal.get("balanceAfter").asText());

        // 重复撤销 -> 409；已撤销不能再批准 -> 409
        postJson("/api/corrections/" + meterKey + "/revoke",
                Map.of("commandKey", key("cr")), null, 409);
        assertEquals("CORRECTION_ALREADY_REVOKED", approveBatch(List.of(meterKey), 409).get("code").asText());
    }

    @Test
    void revokeMustPassReserveCheck() throws Exception {
        // 计划 1.0：a1 核销 0.6，更正为 0.4 后空出 0.2 被 a2 占用，撤销更正当且仅当不侵占储备
        long windowId = createWindow("ch-revres-" + run,
                "2026-10-23T00:00:00Z", "2026-10-23T02:00:00Z", "1.000");
        String a1 = submitAndApprove(windowId, "user-1", "0.6", "alice");
        String meterKey = submitCorrection(a1, 1L, "0.4", "carol");
        approveBatch(List.of(meterKey), 200);
        String a2 = submitAndApprove(windowId, "user-2", "0.6", "bob");
        assertEquals("1", capacity(windowId).get("approvedTotal").asText());
        // 撤销将恢复 0.6 -> 总量 1.2 侵占储备 -> 422，状态不变
        JsonNode error = objectMapper.readTree(postJson("/api/corrections/" + meterKey + "/revoke",
                Map.of("commandKey", key("cr")), null, 422).getResponse().getContentAsString());
        assertEquals("RESERVE_VIOLATION", error.get("code").asText());
        assertEquals("APPROVED", getOk("/api/corrections/" + meterKey).get("status").asText());
        assertEquals("0.4", allocation(windowId, a1).get("heldAmount").asText());
        // 释放 a2 后撤销成功
        postOk("/api/allocations/" + a2 + "/cancel", Map.of("commandKey", key("cc")), "bob");
        postOk("/api/corrections/" + meterKey + "/revoke", Map.of("commandKey", key("cr")), null);
        assertEquals("0.6", allocation(windowId, a1).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 储备约束与批量原子性
    // ------------------------------------------------------------------

    @Test
    void reserveViolationRollsBackWholeBatch() throws Exception {
        long windowId = createWindow("ch-res-" + run,
                "2026-10-24T00:00:00Z", "2026-10-24T02:00:00Z", "1.000");
        String a1 = submitAndApprove(windowId, "user-1", "0.6", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "0.4", "bob");
        String m1 = submitCorrection(a1, 1L, "0.5", "carol");
        String m2 = submitCorrection(a2, 1L, "0.6", "carol");

        // m1 后总量 0.9 合法；m2 后总量 1.1 侵占储备 -> 422，整批回滚
        JsonNode error = approveBatch(List.of(m1, m2), 422);
        assertEquals("RESERVE_VIOLATION", error.get("code").asText());

        // 批内第一条也不入账：状态、持有额度、版本、快照、流水均无半成品
        assertEquals("REQUESTED", getOk("/api/corrections/" + m1).get("status").asText());
        assertEquals("REQUESTED", getOk("/api/corrections/" + m2).get("status").asText());
        assertEquals("0.6", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals(1L, allocation(windowId, a1).get("version").asLong());
        mvc.perform(get("/api/corrections/" + m1 + "/snapshot")).andExpect(status().isNotFound());
        JsonNode ledger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(1, ledger.get("entries").size());
        assertEquals("WRITE_OFF", ledger.get("entries").get(0).get("entryType").asText());
        assertEquals("1", capacity(windowId).get("approvedTotal").asText());

        // 单条合法更正仍可批准（失败不占键、不留状态）
        approveBatch(List.of(m1), 200);
        assertEquals("0.5", allocation(windowId, a1).get("heldAmount").asText());
    }

    @Test
    void batchAdjudicatedInSubmissionOrder() throws Exception {
        // 两条更正竞争同一储备：按提交顺序，先登记者入账，后登记者 422
        long windowId = createWindow("ch-ord-" + run,
                "2026-10-25T00:00:00Z", "2026-10-25T02:00:00Z", "1.000");
        String a1 = submitAndApprove(windowId, "user-1", "0.5", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "0.5", "bob");
        String first = submitCorrection(a1, 1L, "0.8", "carol");
        String second = submitCorrection(a2, 1L, "0.8", "carol");
        // 故意反序传入，仍按提交顺序裁决：先登记的 first 先入账，总量 1.3 侵占储备被拒绝
        JsonNode error = approveBatch(List.of(second, first), 422);
        assertEquals("RESERVE_VIOLATION", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(first));
        // 调减不侵占储备，可正常批准
        String down = submitCorrection(a1, 1L, "0.4", "carol");
        approveBatch(List.of(down), 200);
        assertEquals("0.4", allocation(windowId, a1).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 窗口状态
    // ------------------------------------------------------------------

    @Test
    void closedWindowAllowsRegistrationButBlocksApproveAndRevoke() throws Exception {
        java.util.function.LongSupplier original = WaterService.clockNanos;
        try {
            long t0 = WaterService.toNanos(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            WaterService.clockNanos = () -> t0;
            long windowId = createWindow("ch-closed-" + run,
                    "2026-01-01T00:00:00Z", "2026-01-01T01:00:00Z", "10");
            String a1 = submitAndApprove(windowId, "user-1", "4", "alice");
            String approved = submitCorrection(a1, 1L, "3", "carol");
            approveBatch(List.of(approved), 200);
            String pending = submitCorrection(a1, 2L, "2", "carol");

            // 窗口关闭后
            long t1 = WaterService.toNanos(java.time.Instant.parse("2026-01-01T02:00:00Z"));
            WaterService.clockNanos = () -> t1;
            // 只能登记，不能批准影响已结算余额
            String late = key("mk");
            postOk("/api/corrections",
                    correctionBody(late, a1, 2L, "2.5", "2026-01-01T00:40:00Z", "关窗后登记"), "carol");
            assertEquals("WINDOW_CLOSED", approveBatch(List.of(pending), 409).get("code").asText());
            assertEquals("WINDOW_CLOSED", approveBatch(List.of(late), 409).get("code").asText());
            assertEquals("REQUESTED", getOk("/api/corrections/" + pending).get("status").asText());
            // 已批准更正也不能撤销
            JsonNode error = objectMapper.readTree(postJson("/api/corrections/" + approved + "/revoke",
                    Map.of("commandKey", key("cr")), null, 409).getResponse().getContentAsString());
            assertEquals("WINDOW_CLOSED", error.get("code").asText());
            assertEquals("APPROVED", getOk("/api/corrections/" + approved).get("status").asText());
            assertEquals("3", allocation(windowId, a1).get("heldAmount").asText());
        } finally {
            WaterService.clockNanos = original;
        }
    }

    // ------------------------------------------------------------------
    // 失败分支：参数、状态、不存在
    // ------------------------------------------------------------------

    @Test
    void invalidCorrectionParamsReturn400() throws Exception {
        long windowId = createWindow("ch-badc-" + run,
                "2026-10-26T00:00:00Z", "2026-10-26T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");
        // 负数
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 1L, "-0.5", "2026-10-26T00:30:00Z", "r"), "carol", 400);
        // 超过 3 位小数
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 1L, "1.0001", "2026-10-26T00:30:00Z", "r"), "carol", 400);
        // 非数字
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 1L, "abc", "2026-10-26T00:30:00Z", "r"), "carol", 400);
        // 非法读表时刻
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 1L, "1", "not-a-time", "r"), "carol", 400);
        // 空原因
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 1L, "1", "2026-10-26T00:30:00Z", "  "), "carol", 400);
        // 非法版本
        postJson("/api/corrections",
                correctionBody(key("mk"), a1, 0L, "1", "2026-10-26T00:30:00Z", "r"), "carol", 400);
        // 校正为 0 合法（校正后不得为负，零允许）
        String zero = key("mk");
        postOk("/api/corrections",
                correctionBody(zero, a1, 1L, "0", "2026-10-26T00:30:00Z", "表计归零"), "carol");
    }

    @Test
    void correctionStateAndNotFoundFailuresAreDistinguishable() throws Exception {
        long windowId = createWindow("ch-nfc-" + run,
                "2026-10-27T00:00:00Z", "2026-10-27T02:00:00Z", "10");
        // 核销记录不存在 -> 404
        postJson("/api/corrections",
                correctionBody(key("mk"), "no-such-" + run, 1L, "1", "2026-10-27T00:30:00Z", "r"),
                "carol", 404);
        // 未批准申请不是核销记录 -> 409
        String requested = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", requested);
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "1");
        postOk("/api/allocations", body, "alice");
        JsonNode error = objectMapper.readTree(postJson("/api/corrections",
                correctionBody(key("mk"), requested, 1L, "0.5", "2026-10-27T00:30:00Z", "r"),
                "carol", 409).getResponse().getContentAsString());
        assertEquals("ALLOCATION_NOT_APPROVED", error.get("code").asText());
        // 更正不存在 -> 404
        approveBatch(List.of("no-such-" + run), 404);
        postJson("/api/corrections/no-such-" + run + "/revoke",
                Map.of("commandKey", key("cr")), null, 404);
        mvc.perform(get("/api/corrections/no-such-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/ledger")).andExpect(status().isNotFound());
        // 未批准的更正不能撤销 -> 409
        String a1 = submitAndApprove(windowId, "user-2", "2", "bob");
        String pending = submitCorrection(a1, 1L, "1", "carol");
        JsonNode revokeError = objectMapper.readTree(postJson("/api/corrections/" + pending + "/revoke",
                Map.of("commandKey", key("cr")), null, 409).getResponse().getContentAsString());
        assertEquals("CORRECTION_NOT_APPROVED", revokeError.get("code").asText());
        // 重复批准 -> 409
        approveBatch(List.of(pending), 200);
        assertEquals("CORRECTION_ALREADY_APPROVED",
                approveBatch(List.of(pending), 409).get("code").asText());
    }

    // ------------------------------------------------------------------
    // meterKey 幂等
    // ------------------------------------------------------------------

    @Test
    void meterKeyReplaysAndFailureDoesNotOccupyKey() throws Exception {
        long windowId = createWindow("ch-idemc-" + run,
                "2026-10-28T00:00:00Z", "2026-10-28T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");

        // 失败不占键：先以非法数量失败，再同键成功
        String meterKey = key("mk");
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 1L, "-1", "2026-10-28T00:30:00Z", "r"), "carol", 400);
        Map<String, Object> body = correctionBody(meterKey, a1, 1L, "3",
                "2026-10-28T00:30:00Z", "读表偏差修正");
        JsonNode first = postOk("/api/corrections", body, "carol");
        // 同键同参重放
        JsonNode replay = postOk("/api/corrections", body, "carol");
        assertEquals(first, replay);
        // 同键改参（校正数/版本/读表时刻/原因/操作者任一变化）-> 409
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 1L, "3.1", "2026-10-28T00:30:00Z", "读表偏差修正"),
                "carol", 409);
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 2L, "3", "2026-10-28T00:30:00Z", "读表偏差修正"),
                "carol", 409);
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 1L, "3", "2026-10-28T00:31:00Z", "读表偏差修正"),
                "carol", 409);
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 1L, "3", "2026-10-28T00:30:00Z", "另一个原因"),
                "carol", 409);
        postJson("/api/corrections",
                correctionBody(meterKey, a1, 1L, "3", "2026-10-28T00:30:00Z", "读表偏差修正"),
                "dave", 409);

        // 批准命令同键重放
        String commandKey = key("ca");
        JsonNode approved = postOk("/api/corrections/approve",
                Map.of("commandKey", commandKey, "meterKeys", List.of(meterKey)), null);
        JsonNode approvedReplay = postOk("/api/corrections/approve",
                Map.of("commandKey", commandKey, "meterKeys", List.of(meterKey)), null);
        assertEquals(approved, approvedReplay);
        // 只入账一次
        JsonNode ledger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(2, ledger.get("entries").size());
    }

    // ------------------------------------------------------------------
    // 预检：查询拒绝原因
    // ------------------------------------------------------------------

    @Test
    void previewReportsDistinguishableRejectReasons() throws Exception {
        long windowId = createWindow("ch-prev-" + run,
                "2026-10-29T00:00:00Z", "2026-10-29T02:00:00Z", "1.000");
        String a1 = submitAndApprove(windowId, "user-1", "0.6", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "0.4", "bob");
        String approved = submitCorrection(a2, 1L, "0.3", "carol");
        approveBatch(List.of(approved), 200);
        String ok = submitCorrection(a1, 1L, "0.5", "carol");
        String reserve = submitCorrection(a2, 2L, "0.6", "carol");
        String stale = submitCorrection(a1, 7L, "0.5", "carol");

        JsonNode preview = postOk("/api/corrections/preview",
                Map.of("meterKeys", List.of(ok, reserve, stale, approved, "no-such-" + run)), null);
        JsonNode items = preview.get("items");
        assertEquals(5, items.size());
        assertTrue(items.get(0).get("ok").asBoolean());
        assertEquals("RESERVE_VIOLATION", items.get(1).get("code").asText());
        assertEquals("STALE_VERSION", items.get(2).get("code").asText());
        assertEquals("CORRECTION_ALREADY_APPROVED", items.get(3).get("code").asText());
        assertEquals("CORRECTION_NOT_FOUND", items.get(4).get("code").asText());
        // 预检不落库：状态与余额不变
        assertEquals("REQUESTED", getOk("/api/corrections/" + ok).get("status").asText());
        assertEquals("0.6", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals(2, getOk("/api/allocations/" + a2 + "/ledger").get("entries").size());
    }

    // ------------------------------------------------------------------
    // 流水：转让与取消入账
    // ------------------------------------------------------------------

    @Test
    void ledgerRecordsTransferAndCancel() throws Exception {
        long windowId = createWindow("ch-led-" + run,
                "2026-10-30T00:00:00Z", "2026-10-30T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "5", "alice");
        String a2 = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", a2);
        body.put("windowId", windowId);
        body.put("userId", "user-2");
        body.put("amount", "2");
        postOk("/api/allocations", body, "bob");
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", a1);
        transfer.put("targetAllocationKey", a2);
        postOk("/api/transfers", transfer, "alice");
        postOk("/api/allocations/" + a2 + "/cancel", Map.of("commandKey", key("cc")), "bob");

        JsonNode sourceLedger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(2, sourceLedger.get("entries").size());
        assertEquals("WRITE_OFF", sourceLedger.get("entries").get(0).get("entryType").asText());
        JsonNode transferOut = sourceLedger.get("entries").get(1);
        assertEquals("TRANSFER_OUT", transferOut.get("entryType").asText());
        assertEquals("-2", transferOut.get("delta").asText());
        assertEquals("3", transferOut.get("balanceAfter").asText());

        JsonNode targetLedger = getOk("/api/allocations/" + a2 + "/ledger");
        assertEquals(2, targetLedger.get("entries").size());
        assertEquals("WRITE_OFF", targetLedger.get("entries").get(0).get("entryType").asText());
        JsonNode cancel = targetLedger.get("entries").get(1);
        assertEquals("CANCEL", cancel.get("entryType").asText());
        assertEquals("-2", cancel.get("delta").asText());
        assertEquals("0", cancel.get("balanceAfter").asText());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentBatchApprovalsNeverBreachReserve() throws Exception {
        long windowId = createWindow("ch-concc-" + run,
                "2026-10-31T00:00:00Z", "2026-10-31T02:00:00Z", "1.500");
        String a1 = submitAndApprove(windowId, "user-1", "0.5", "alice");
        String a2 = submitAndApprove(windowId, "user-2", "0.5", "bob");
        // 两条更正各自合法（总量 1.5），同时批准则 2.0 侵占储备
        String m1 = submitCorrection(a1, 1L, "1.0", "carol");
        String m2 = submitCorrection(a2, 1L, "1.0", "carol");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> batch = List.of(m1, m2);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (String meterKey : batch) {
            String commandKey = key("ca");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.approveCorrections(commandKey, List.of(meterKey));
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int first = futures.get(0).get(30, TimeUnit.SECONDS);
        int second = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按事务提交顺序：恰好一个批准成功，另一个 422 侵占储备
        assertTrue((first == 200) != (second == 200), "first=" + first + " second=" + second);
        assertEquals(422, first == 200 ? second : first);
        assertEquals("1.5", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void concurrentSameApproveCommandKeyReplaysSingleResult() throws Exception {
        long windowId = createWindow("ch-concid-" + run,
                "2026-11-01T00:00:00Z", "2026-11-01T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");
        String meterKey = submitCorrection(a1, 1L, "3", "carol");
        String commandKey = key("ca");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<CorrectionBatchResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                // 与并发命令冲突时按客户端语义重试，最终应重放到同一首次结果
                return callWithConflictRetry(
                        () -> waterService.approveCorrections(commandKey, List.of(meterKey)));
            }));
        }
        gate.countDown();
        CorrectionBatchResponse r1 = futures.get(0).get(30, TimeUnit.SECONDS);
        CorrectionBatchResponse r2 = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同键重放：两个线程得到同一首次结果，且只入账一次
        assertEquals(r1, r2);
        assertEquals("APPROVED", r1.corrections().get(0).status());
        JsonNode ledger = getOk("/api/allocations/" + a1 + "/ledger");
        assertEquals(2, ledger.get("entries").size());
        assertEquals("3", allocation(windowId, a1).get("heldAmount").asText());
        assertNotNull(getOk("/api/corrections/" + meterKey + "/snapshot"));
    }

    @Test
    void concurrentSubmitSameMeterKeyCreatesSingleCorrection() throws Exception {
        long windowId = createWindow("ch-concs-" + run,
                "2026-11-02T00:00:00Z", "2026-11-02T02:00:00Z", "10");
        String a1 = submitAndApprove(windowId, "user-1", "4", "alice");
        String meterKey = key("mk");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<CorrectionResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return callWithConflictRetry(() -> waterService.submitCorrection(meterKey, a1, 1L, "3",
                        "2026-11-02T00:30:00Z", "并发登记", "carol"));
            }));
        }
        gate.countDown();
        CorrectionResponse r1 = futures.get(0).get(30, TimeUnit.SECONDS);
        CorrectionResponse r2 = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同键同参并发：重放同一结果，只创建一条更正，批准后只入账一次
        assertEquals(r1, r2);
        assertEquals("REQUESTED", r1.status());
        approveBatch(List.of(meterKey), 200);
        assertEquals(2, getOk("/api/allocations/" + a1 + "/ledger").get("entries").size());
    }

    /** 并发同键命令遇到 COMMAND_CONFLICT（首次命令尚未提交）时短暂等待并重试。 */
    private <T> T callWithConflictRetry(java.util.concurrent.Callable<T> call) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return call.call();
            } catch (ApiException e) {
                if (!"COMMAND_CONFLICT".equals(e.code()) || attempt == 49) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
