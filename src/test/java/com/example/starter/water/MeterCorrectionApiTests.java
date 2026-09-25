package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
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

import java.math.BigDecimal;
import java.time.Instant;
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
 * 配水计量更正 API 集成测试：核销原流水、计量版本、批量批准反向核算、储备约束、
 * 窗口状态、撤销反向流水、拒绝原因查询、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeterCorrectionApiTests {

    private static final String OPEN_START = "2099-01-01T00:00:00Z";
    private static final String OPEN_END = "2099-01-02T00:00:00Z";
    private static final String CLOSED_START = "2020-01-01T00:00:00Z";
    private static final String CLOSED_END = "2020-01-01T01:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @Autowired
    private WaterRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        String json = postJson(url, body, actor, 200).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String start, String end, String planned)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitAllocation(long windowId, String userId, String amount, String actor)
            throws Exception {
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

    private Map<String, Object> writeoffBody(String commandKey, String writeoffKey, String amount,
                                             String meterUtc) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeoffKey", writeoffKey);
        body.put("amount", amount);
        body.put("meterUtc", meterUtc);
        return body;
    }

    /** 成功核销并返回 writeoffKey。 */
    private String writeoff(String allocationKey, String amount, String meterUtc, String actor)
            throws Exception {
        String writeoffKey = key("wo");
        JsonNode node = postOk("/api/allocations/" + allocationKey + "/writeoffs",
                writeoffBody(key("woc"), writeoffKey, amount, meterUtc), actor);
        assertEquals(writeoffKey, node.get("writeoffKey").asText());
        return writeoffKey;
    }

    private Map<String, Object> correctionBody(String meterKey, String writeoffKey, int version,
                                               String corrected, String meterUtc, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("meterKey", meterKey);
        body.put("writeoffKey", writeoffKey);
        body.put("originalVersion", version);
        body.put("correctedAmount", corrected);
        body.put("meterUtc", meterUtc);
        body.put("reason", reason);
        return body;
    }

    /** 登记更正并返回 meterKey。 */
    private String requestCorrection(String writeoffKey, int version, String corrected,
                                     String meterUtc, String actor) throws Exception {
        String meterKey = key("mc");
        JsonNode node = postOk("/api/corrections",
                correctionBody(meterKey, writeoffKey, version, corrected, meterUtc, "读表误差"),
                actor);
        assertEquals("REQUESTED", node.get("status").asText());
        return meterKey;
    }

    private JsonNode approveCorrections(List<String> meterKeys, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/corrections/approve",
                Map.of("commandKey", key("cap"), "meterKeys", meterKeys), null, expectedStatus);
        String json = result.getResponse().getContentAsString();
        return json.isEmpty() ? null : objectMapper.readTree(json);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    private JsonNode reverseLedger(String allocationKey) throws Exception {
        return getOk("/api/allocations/" + allocationKey + "/ledger");
    }

    private JsonNode rejections(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/rejections");
    }

    private boolean hasRejection(JsonNode rejections, String code) {
        for (JsonNode node : rejections.get("rejections")) {
            if (code.equals(node.get("code").asText())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 核销（原流水）
    // ------------------------------------------------------------------

    @Test
    void writeoffMainFlowVersioningAndBalanceEvolution() throws Exception {
        long windowId = createWindow("ch-mc1-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);

        String w1 = key("wo");
        JsonNode first = postOk("/api/allocations/" + a1 + "/writeoffs",
                writeoffBody(key("woc"), w1, "2.5", "2099-01-01T00:10:00Z"), "alice");
        assertEquals(1, first.get("version").asInt());
        assertEquals("2.5", first.get("amount").asText());
        String w2 = key("wo");
        JsonNode second = postOk("/api/allocations/" + a1 + "/writeoffs",
                writeoffBody(key("woc"), w2, "1.5", "2099-01-01T00:20:00Z"), "alice");
        assertEquals(2, second.get("version").asInt());

        // 原流水：按版本升序，创建后不可变
        JsonNode writeoffs = getOk("/api/allocations/" + a1 + "/writeoffs");
        assertEquals(2, writeoffs.get("writeoffs").size());
        assertEquals(w1, writeoffs.get("writeoffs").get(0).get("writeoffKey").asText());
        assertEquals(w2, writeoffs.get("writeoffs").get(1).get("writeoffKey").asText());
        assertEquals("2", capacity(windowId).get("approvedTotal").asText());

        // 余额演算：6 -> 3.5 -> 2
        JsonNode evolution = getOk("/api/allocations/" + a1 + "/balance-evolution");
        assertEquals("6", evolution.get("startBalance").asText());
        assertEquals(2, evolution.get("events").size());
        assertEquals("WRITEOFF", evolution.get("events").get(0).get("kind").asText());
        assertEquals("-2.5", evolution.get("events").get(0).get("delta").asText());
        assertEquals("3.5", evolution.get("events").get(0).get("balance").asText());
        assertEquals("2", evolution.get("events").get(1).get("balance").asText());
        assertEquals("2", evolution.get("finalBalance").asText());

        // 尚无反向流水
        assertEquals(0, reverseLedger(a1).get("entries").size());
    }

    @Test
    void writeoffFailuresAreDistinguishable() throws Exception {
        long windowId = createWindow("ch-mc2-" + run, OPEN_START, OPEN_END, "10");
        String requested = submitAllocation(windowId, "user-1", "1", "alice");
        String approved = submitAllocation(windowId, "user-2", "1", "bob");
        approve(approved, 200);

        // 申请不存在 -> 404
        postJson("/api/allocations/no-such-" + run + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "1", "2099-01-01T00:10:00Z"), "alice", 404);
        // 未批准申请 -> 409
        postJson("/api/allocations/" + requested + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "1", "2099-01-01T00:10:00Z"), "alice", 409);
        // 超过持有额度 -> 422
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "2", "2099-01-01T00:10:00Z"), "bob", 422);
        // 数量非法 -> 400
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "1.0001", "2099-01-01T00:10:00Z"), "bob", 400);
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "-1", "2099-01-01T00:10:00Z"), "bob", 400);
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "0", "2099-01-01T00:10:00Z"), "bob", 400);
        // 读表时刻非法 -> 400
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "0.5", "not-a-time"), "bob", 400);

        // 同 commandKey 同参重放；换 commandKey 复用 writeoffKey -> 409
        String writeoffKey = key("wo");
        String commandKey = key("woc");
        Map<String, Object> body = writeoffBody(commandKey, writeoffKey, "0.5",
                "2099-01-01T00:10:00Z");
        JsonNode first = postOk("/api/allocations/" + approved + "/writeoffs", body, "bob");
        JsonNode replay = postOk("/api/allocations/" + approved + "/writeoffs", body, "bob");
        assertEquals(first, replay);
        postJson("/api/allocations/" + approved + "/writeoffs",
                writeoffBody(key("woc"), writeoffKey, "0.5", "2099-01-01T00:10:00Z"), "bob", 409);
        assertEquals(1, getOk("/api/allocations/" + approved + "/writeoffs").get("writeoffs").size());
    }

    @Test
    void writeoffRejectedWhenWindowClosed() throws Exception {
        long windowId = createWindow("ch-mc3-" + run, CLOSED_START, CLOSED_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        MvcResult result = postJson("/api/allocations/" + a1 + "/writeoffs",
                writeoffBody(key("woc"), key("wo"), "1", "2020-01-01T00:30:00Z"), "alice", 409);
        assertTrue(result.getResponse().getContentAsString().contains("WINDOW_CLOSED"));
    }

    // ------------------------------------------------------------------
    // 更正登记与 meterKey 指纹幂等
    // ------------------------------------------------------------------

    @Test
    void correctionRequestFingerprintAndVersionRules() throws Exception {
        long windowId = createWindow("ch-mc4-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");

        // 登记成功
        String meterKey = key("mc");
        Map<String, Object> body = correctionBody(meterKey, w1, 1, "3",
                "2099-01-01T00:30:00Z", "读表误差");
        JsonNode created = postOk("/api/corrections", body, "carol");
        assertEquals("REQUESTED", created.get("status").asText());
        assertEquals("3", created.get("correctedAmount").asText());
        assertTrue(created.get("decidedUtc").isNull());
        // 同键同指纹重放
        JsonNode replay = postOk("/api/corrections", body, "carol");
        assertEquals(created, replay);
        // 同键异指纹 -> 409
        postJson("/api/corrections", correctionBody(meterKey, w1, 1, "3.5",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 409);
        postJson("/api/corrections", correctionBody(meterKey, w1, 1, "3",
                "2099-01-01T00:30:00Z", "读表误差"), "dave", 409);

        // 版本不匹配 -> 409；失败不占键，同键改正参数后成功
        String meterKey2 = key("mc");
        postJson("/api/corrections", correctionBody(meterKey2, w1, 2, "3",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 409);
        JsonNode fixed = postOk("/api/corrections", correctionBody(meterKey2, w1, 1, "3",
                "2099-01-01T00:30:00Z", "读表误差"), "carol");
        assertEquals("REQUESTED", fixed.get("status").asText());

        // 核销不存在 -> 404；参数非法 -> 400
        postJson("/api/corrections", correctionBody(key("mc"), "no-such-" + run, 1, "3",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 404);
        postJson("/api/corrections", correctionBody(key("mc"), w1, 1, "-1",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 400);
        postJson("/api/corrections", correctionBody(key("mc"), w1, 1, "1.0001",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 400);
        postJson("/api/corrections", correctionBody(key("mc"), w1, 0, "3",
                "2099-01-01T00:30:00Z", "读表误差"), "carol", 400);
        postJson("/api/corrections", correctionBody(key("mc"), w1, 1, "3",
                "2099-01-01T00:30:00Z", "  "), "carol", 400);

        // 更正详情查询与 404
        JsonNode detail = getOk("/api/corrections/" + meterKey);
        assertEquals("REQUESTED", detail.get("status").asText());
        mvc.perform(get("/api/corrections/no-such-" + run)).andExpect(status().isNotFound());

        // 拒绝原因可查询
        JsonNode rejections = rejections(windowId);
        assertTrue(hasRejection(rejections, "METER_KEY_REUSED"));
        assertTrue(hasRejection(rejections, "WRITEOFF_VERSION_MISMATCH"));
    }

    // ------------------------------------------------------------------
    // 批量批准：反向流水、读表快照、账目反算
    // ------------------------------------------------------------------

    @Test
    void approveBatchCreatesReverseLedgerAndSnapshot() throws Exception {
        long windowId = createWindow("ch-mc5-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String w2 = writeoff(a1, "3", "2099-01-01T00:20:00Z", "alice");
        assertEquals("3", capacity(windowId).get("approvedTotal").asText());

        String c1 = requestCorrection(w1, 1, "3", "2099-01-01T00:30:00Z", "carol");
        String c2 = requestCorrection(w2, 2, "5", "2099-01-01T00:40:00Z", "carol");
        String approveCommand = key("cap");
        Map<String, Object> approveBody = Map.of("commandKey", approveCommand,
                "meterKeys", List.of(c1, c2));
        JsonNode approved = postOk("/api/corrections/approve", approveBody, null);
        assertEquals(2, approved.get("approved").size());
        assertEquals("APPROVED", approved.get("approved").get(0).get("status").asText());
        assertNotNull(approved.get("approved").get(0).get("decidedUtc"));
        // 同 commandKey 同参重放首次结果
        JsonNode approvedReplay = postOk("/api/corrections/approve", approveBody, null);
        assertEquals(approved, approvedReplay);

        // 最终余额：3 + (4-3) - (5-3) = 2
        assertEquals("2", capacity(windowId).get("approvedTotal").asText());

        // 反向流水：c1 返还 1，c2 补扣 2
        JsonNode ledger = reverseLedger(a1);
        assertEquals(2, ledger.get("entries").size());
        JsonNode e1 = ledger.get("entries").get(0);
        assertEquals("CORRECTION", e1.get("kind").asText());
        assertEquals(c1, e1.get("refKey").asText());
        assertEquals("1", e1.get("delta").asText());
        assertEquals("4", e1.get("balanceAfter").asText());
        JsonNode e2 = ledger.get("entries").get(1);
        assertEquals("-2", e2.get("delta").asText());
        assertEquals("2", e2.get("balanceAfter").asText());

        // 原核销不被覆盖
        JsonNode writeoffs = getOk("/api/allocations/" + a1 + "/writeoffs");
        assertEquals("4", writeoffs.get("writeoffs").get(0).get("amount").asText());
        assertEquals("3", writeoffs.get("writeoffs").get(1).get("amount").asText());

        // 不可变读表快照已创建
        assertEquals(2, repository.listSnapshotIds(windowId).size());

        // 余额演算含反向流水：6 -> 3 -> 4 -> 2
        JsonNode evolution = getOk("/api/allocations/" + a1 + "/balance-evolution");
        assertEquals(4, evolution.get("events").size());
        assertEquals("2", evolution.get("finalBalance").asText());

        // 重复批准（换 commandKey）-> 409；同 commandKey 重放见下
        approveCorrections(List.of(c1), 409);
    }

    @Test
    void approveBatchNegativeBalanceRollsBackEverything() throws Exception {
        long windowId = createWindow("ch-mc6-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        String a2 = submitAllocation(windowId, "user-2", "5", "bob");
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        // 已结算转让：A 转出 5 给 B，A 持有 1
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", a1);
        transfer.put("targetAllocationKey", a2);
        postOk("/api/transfers", transfer, "alice");
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());

        // 校正为 8：10 - 8 - 5 = -3，历史时点之后可用量为负 -> 422
        String c1 = requestCorrection(w1, 1, "8", "2099-01-01T00:30:00Z", "carol");
        String commandKey = key("cap");
        MvcResult result = postJson("/api/corrections/approve",
                Map.of("commandKey", commandKey, "meterKeys", List.of(c1)), null, 422);
        assertTrue(result.getResponse().getContentAsString().contains("NEGATIVE_BALANCE"));

        // 全部回滚：更正仍 REQUESTED、持有额度不变、无反向流水
        assertEquals("REQUESTED", getOk("/api/corrections/" + c1).get("status").asText());
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
        assertEquals(0, reverseLedger(a1).get("entries").size());
        assertEquals(0, repository.listSnapshotIds(windowId).size());
        assertTrue(hasRejection(rejections(windowId), "NEGATIVE_BALANCE"));

        // 失败不占键：同 commandKey 同参可再次裁决（仍为 422 而非 409）
        postJson("/api/corrections/approve",
                Map.of("commandKey", commandKey, "meterKeys", List.of(c1)), null, 422);
    }

    @Test
    void approveBatchReserveEncroachmentReturns422() throws Exception {
        long windowId = createWindow("ch-mc7-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "5", "2099-01-01T00:10:00Z", "alice");
        // 限供 3：当前已结算持有 1，合法
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "3"), null);

        // 校正为 2 将返还 3，持有 4 超过可用总量 3，侵占储备 -> 422
        String c1 = requestCorrection(w1, 1, "2", "2099-01-01T00:30:00Z", "carol");
        MvcResult result = postJson("/api/corrections/approve",
                Map.of("commandKey", key("cap"), "meterKeys", List.of(c1)), null, 422);
        assertTrue(result.getResponse().getContentAsString().contains("RESERVE_ENCROACHED"));
        assertEquals("REQUESTED", getOk("/api/corrections/" + c1).get("status").asText());
        assertEquals("1", capacity(windowId).get("approvedTotal").asText());
        assertTrue(hasRejection(rejections(windowId), "RESERVE_ENCROACHED"));
    }

    @Test
    void approveCorrectionsRejectsUnknownAndEmptyBatch() throws Exception {
        long windowId = createWindow("ch-mc8-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String c1 = requestCorrection(w1, 1, "3", "2099-01-01T00:30:00Z", "carol");

        // 批次含未知更正 -> 404，整批回滚
        postJson("/api/corrections/approve",
                Map.of("commandKey", key("cap"), "meterKeys", List.of(c1, "no-such-" + run)),
                null, 404);
        assertEquals("REQUESTED", getOk("/api/corrections/" + c1).get("status").asText());
        // 空批次 -> 400
        postJson("/api/corrections/approve",
                Map.of("commandKey", key("cap"), "meterKeys", List.of()), null, 400);
    }

    // ------------------------------------------------------------------
    // 窗口关闭后的更正规则
    // ------------------------------------------------------------------

    @Test
    void closedWindowAllowsRegistrationButNotApproveOrRevoke() throws Exception {
        long windowId = createWindow("ch-mc9-" + run, CLOSED_START, CLOSED_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        // 窗口已关闭，核销被禁止；直接落库一条历史核销模拟关闭前的已结算数据
        AllocationRow allocation = repository.findAllocationByKey(a1);
        long meterNanos = WaterService.toNanos(Instant.parse("2020-01-01T00:30:00Z"));
        repository.insertWriteoff(key("wo"), a1, windowId, 1, new BigDecimal("4.000"),
                meterNanos, "alice", WaterService.nowNanos());
        repository.decrementHeldAmount(allocation.id(), new BigDecimal("4.000"),
                WaterService.nowNanos());
        String writeoffKey = getOk("/api/allocations/" + a1 + "/writeoffs")
                .get("writeoffs").get(0).get("writeoffKey").asText();

        // 窗口关闭后仍可登记更正申请
        String c1 = requestCorrection(writeoffKey, 1, "3", "2020-01-01T00:40:00Z", "carol");
        // 但不能批准影响已结算余额
        MvcResult result = postJson("/api/corrections/approve",
                Map.of("commandKey", key("cap"), "meterKeys", List.of(c1)), null, 409);
        assertTrue(result.getResponse().getContentAsString().contains("WINDOW_CLOSED"));
        assertEquals("REQUESTED", getOk("/api/corrections/" + c1).get("status").asText());

        // 已批准更正（历史数据）在窗口关闭后也不能撤销
        CorrectionRow row = repository.findCorrectionByKey(c1);
        repository.updateCorrectionStatus(row.id(), "APPROVED", WaterService.nowNanos());
        MvcResult revokeResult = postJson("/api/corrections/" + c1 + "/revoke",
                Map.of("commandKey", key("crv")), null, 409);
        assertTrue(revokeResult.getResponse().getContentAsString().contains("WINDOW_CLOSED"));

        JsonNode rejections = rejections(windowId);
        assertTrue(hasRejection(rejections, "WINDOW_CLOSED"));
    }

    // ------------------------------------------------------------------
    // 撤销：反向流水与最终态校验
    // ------------------------------------------------------------------

    @Test
    void revokeCreatesReversalAndRestoresBalance() throws Exception {
        long windowId = createWindow("ch-mc10-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String c1 = requestCorrection(w1, 1, "2", "2099-01-01T00:30:00Z", "carol");
        approveCorrections(List.of(c1), 200);
        assertEquals("8", capacity(windowId).get("approvedTotal").asText());

        // 撤销：再产生一条反向流水，持有额度回到 6
        String revokeCommand = key("crv");
        JsonNode revoked = postOk("/api/corrections/" + c1 + "/revoke",
                Map.of("commandKey", revokeCommand), null);
        assertEquals("REVOKED", revoked.get("status").asText());
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());

        JsonNode ledger = reverseLedger(a1);
        assertEquals(2, ledger.get("entries").size());
        JsonNode reversal = ledger.get("entries").get(1);
        assertEquals("REVOCATION", reversal.get("kind").asText());
        assertEquals(c1, reversal.get("refKey").asText());
        assertEquals("-2", reversal.get("delta").asText());
        assertEquals("6", reversal.get("balanceAfter").asText());

        // 撤销命令幂等重放；重复撤销（换 commandKey）-> 409
        JsonNode replay = postOk("/api/corrections/" + c1 + "/revoke",
                Map.of("commandKey", revokeCommand), null);
        assertEquals(revoked, replay);
        postJson("/api/corrections/" + c1 + "/revoke", Map.of("commandKey", key("crv")), null, 409);
        // 撤销不存在的更正 -> 404
        postJson("/api/corrections/no-such-" + run + "/revoke",
                Map.of("commandKey", key("crv")), null, 404);
    }

    @Test
    void revokeMustPassSameFinalStateValidation() throws Exception {
        long windowId = createWindow("ch-mc11-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        String a2 = submitAllocation(windowId, "user-2", "7", "bob");
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String c1 = requestCorrection(w1, 1, "2", "2099-01-01T00:30:00Z", "carol");
        approveCorrections(List.of(c1), 200);
        // 更正返还的 2 被转出：A 持有 8 - 7 = 1
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", a1);
        transfer.put("targetAllocationKey", a2);
        postOk("/api/transfers", transfer, "alice");

        // 撤销将恢复核销量 4：10 - 4 - 7 = -1 -> 422，更正确保持 APPROVED
        MvcResult result = postJson("/api/corrections/" + c1 + "/revoke",
                Map.of("commandKey", key("crv")), null, 422);
        assertTrue(result.getResponse().getContentAsString().contains("NEGATIVE_BALANCE"));
        assertEquals("APPROVED", getOk("/api/corrections/" + c1).get("status").asText());
        assertEquals("8", capacity(windowId).get("approvedTotal").asText());
        assertEquals(1, reverseLedger(a1).get("entries").size());
        assertTrue(hasRejection(rejections(windowId), "NEGATIVE_BALANCE"));
    }

    // ------------------------------------------------------------------
    // 查询边界
    // ------------------------------------------------------------------

    @Test
    void queryEndpointsReturn404ForUnknownKeys() throws Exception {
        mvc.perform(get("/api/allocations/no-such-" + run + "/writeoffs"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/ledger"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/balance-evolution"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/rejections")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 并发与幂等边界
    // ------------------------------------------------------------------

    @Test
    void concurrentCorrectionRequestsSameKeyReplaySingleRow() throws Exception {
        long windowId = createWindow("ch-mc12-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "6", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String meterKey = key("mc");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                // 并发同键：胜出者提交前其余线程可能得到 COMMAND_CONFLICT，有限重试后应重放成功
                for (int attempt = 0; attempt < 100; attempt++) {
                    try {
                        return waterService.requestCorrection(meterKey, w1, 1, "3",
                                "2099-01-01T00:30:00Z", "读表误差", "carol");
                    } catch (ApiException e) {
                        if (!"COMMAND_CONFLICT".equals(e.code())) {
                            return e.status().value();
                        }
                        Thread.sleep(20);
                    }
                }
                return -1;
            }));
        }
        gate.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        for (Object result : results) {
            assertTrue(result instanceof com.example.starter.water.dto.Dtos.CorrectionResponse,
                    "并发同键同参应全部重放成功: " + result);
        }
        // 只落库一条更正
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM meter_correction WHERE meter_key = ?", Integer.class, meterKey);
        assertEquals(1, count);
    }

    @Test
    void concurrentWriteoffsNeverExceedHeldAmount() throws Exception {
        long windowId = createWindow("ch-mc13-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "5", "alice");
        approve(a1, 200);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String commandKey = key("woc");
            String writeoffKey = key("wo");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.createWriteoff(commandKey, a1, writeoffKey, "1",
                            "2099-01-01T00:10:00Z", "alice");
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
            } else if (status == 422) {
                quota++;
            }
        }
        pool.shutdown();
        assertEquals(5, ok, "持有 5，恰好 5 笔 1 的核销成功");
        assertEquals(3, quota);
        assertEquals("0", capacity(windowId).get("approvedTotal").asText());
        assertEquals(5, getOk("/api/allocations/" + a1 + "/writeoffs").get("writeoffs").size());
    }

    @Test
    void concurrentApproveSameCorrectionOnlyOneWins() throws Exception {
        long windowId = createWindow("ch-mc14-" + run, OPEN_START, OPEN_END, "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        String w1 = writeoff(a1, "4", "2099-01-01T00:10:00Z", "alice");
        String c1 = requestCorrection(w1, 1, "2", "2099-01-01T00:30:00Z", "carol");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("cap");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.approveCorrections(commandKey, List.of(c1));
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
        assertEquals(1, ok);
        assertEquals(1, conflict);
        // 只应用一次：持有 10 - 4 + 2 = 8，反向流水恰好一条
        assertEquals("8", capacity(windowId).get("approvedTotal").asText());
        assertEquals(1, reverseLedger(a1).get("entries").size());
        assertEquals("APPROVED", getOk("/api/corrections/" + c1).get("status").asText());
    }
}
