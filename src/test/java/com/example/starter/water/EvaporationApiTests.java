package com.example.starter.water;

import com.example.starter.water.dto.Dtos.SettlementBatchResponse;
import com.example.starter.water.dto.Dtos.SettlementItemRequest;
import com.example.starter.water.dto.Dtos.SettlementResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠道蒸发损耗结算 API 集成测试：蒸发率登记/锁定、核销主流程与银行家舍入、
 * 422 失败分支、批量原子性、反向更正、幂等与并发边界。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaporationApiTests {

    private static final String START = "2026-10-01T00:00:00Z";
    private static final String END = "2026-10-03T00:00:00Z";
    private static final String D1 = "2026-10-01";
    private static final String D2 = "2026-10-02";

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

    private String channel() {
        return "ch-ev-" + run + "-" + (++seq);
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

    private JsonNode putRate(String channel, String date, String rate, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(put("/api/channels/" + channel + "/evaporation-rates/" + date)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("commandKey", key("rc"), "rate", rate))))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getStatus(String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", START);
        body.put("endUtc", END);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submitAndApprove(long windowId, String userId, String amount) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, "alice");
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    private Map<String, Object> item(String settlementKey, String allocationKey, String date,
                                     String delivered) {
        Map<String, Object> item = new HashMap<>();
        item.put("settlementKey", settlementKey);
        item.put("allocationKey", allocationKey);
        item.put("date", date);
        item.put("deliveredAmount", delivered);
        return item;
    }

    private JsonNode settle(String commandKey, int expectedStatus, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("items", items);
        return objectMapper.readTree(
                postJson("/api/settlements", body, null, expectedStatus).getResponse().getContentAsString());
    }

    private String heldAmount(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/history")
                .get("allocations").get(0).get("heldAmount").asText();
    }

    // ------------------------------------------------------------------
    // 蒸发率登记与校验
    // ------------------------------------------------------------------

    @Test
    void rateUpsertModifyQueryAndValidation() throws Exception {
        String channel = channel();
        // 首次登记：版本 1
        JsonNode created = putRate(channel, D1, "0.1234", 200);
        assertEquals(channel, created.get("channelId").asText());
        assertEquals(D1, created.get("date").asText());
        assertEquals("0.1234", created.get("rate").asText());
        assertEquals(1, created.get("version").asInt());
        assertFalse(created.get("locked").asBoolean());
        // 首次核销前可修改：版本递增
        JsonNode modified = putRate(channel, D1, "0.15", 200);
        assertEquals(2, modified.get("version").asInt());
        assertEquals("0.15", modified.get("rate").asText());
        // 边界值 0 与 0.2 合法
        putRate(channel, D2, "0", 200);
        putRate(channel, "2026-10-04", "0.2", 200);
        // 查询
        JsonNode fetched = getOk("/api/channels/" + channel + "/evaporation-rates/" + D1);
        assertEquals("0.15", fetched.get("rate").asText());
        assertEquals(2, fetched.get("version").asInt());
        JsonNode list = getOk("/api/channels/" + channel + "/evaporation-rates");
        assertEquals(3, list.get("rates").size());
        assertEquals(D1, list.get("rates").get(0).get("date").asText());
        // 未登记 -> 404
        getStatus("/api/channels/" + channel + "/evaporation-rates/2026-12-31", 404);
        // 非法率：超上限、超四位小数、负数、非数字 -> 400
        putRate(channel, "2026-10-05", "0.21", 400);
        putRate(channel, "2026-10-05", "0.20001", 400);
        putRate(channel, "2026-10-05", "-0.1", 400);
        putRate(channel, "2026-10-05", "abc", 400);
        // 非法日期 -> 400
        putRate(channel, "2026-13-01", "0.1", 400);
        putRate(channel, "not-a-date", "0.1", 400);
    }

    // ------------------------------------------------------------------
    // 核销主流程与银行家舍入
    // ------------------------------------------------------------------

    @Test
    void settlementHappyPathWithBankersRounding() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "1000");
        String allocation = submitAndApprove(windowId, "user-1", "100");
        putRate(channel, D1, "0.0250", 200);

        // 1 * 0.025 = 0.025 -> 银行家舍入到 0.02（2 为偶数）；上游扣减 1.02
        JsonNode first = settle(key("sc"), 200, List.of(item(key("sk"), allocation, D1, "1")))
                .get("settlements").get(0);
        assertEquals("SETTLEMENT", first.get("kind").asText());
        assertEquals("1", first.get("deliveredAmount").asText());
        assertEquals("0.02", first.get("lossAmount").asText());
        assertEquals("1.02", first.get("deductedAmount").asText());
        assertEquals("98.98", first.get("remainingHeldAmount").asText());
        assertEquals(1, first.get("rateVersion").asInt());
        assertEquals("0.025", first.get("rate").asText());
        assertTrue(first.get("reversalOfKey").isNull());

        // 3 * 0.025 = 0.075 -> 银行家舍入到 0.08（7 为奇数进位到偶数 8）；上游扣减 3.08
        JsonNode second = settle(key("sc"), 200, List.of(item(key("sk"), allocation, D1, "3")))
                .get("settlements").get(0);
        assertEquals("0.08", second.get("lossAmount").asText());
        assertEquals("3.08", second.get("deductedAmount").asText());
        assertEquals("95.9", second.get("remainingHeldAmount").asText());

        // 上游持有额度 = 100 - 1.02 - 3.08 = 95.9
        assertEquals("95.9", heldAmount(windowId));
        // 明细查询固化率版本与金额
        JsonNode detail = getOk("/api/settlements/" + first.get("settlementKey").asText());
        assertEquals("1.02", detail.get("deductedAmount").asText());
        assertEquals(allocation, detail.get("allocationKey").asText());
        assertEquals(channel, detail.get("channelId").asText());
    }

    // ------------------------------------------------------------------
    // 422 失败分支：率缺失 / 日期越窗 / 额度不足 / 守恒不成立，均无部分写入
    // ------------------------------------------------------------------

    @Test
    void settlementFailuresReturn422WithoutPartialWrite() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "1");
        putRate(channel, D1, "0.1", 200);
        // 窗口外日期先登记率，排除率缺失干扰
        putRate(channel, "2026-10-05", "0.1", 200);

        // 蒸发率缺失 -> 422 RATE_MISSING
        String sk1 = key("sk");
        JsonNode e1 = settle(key("sc"), 422, List.of(item(sk1, allocation, D2, "0.5")));
        assertEquals("RATE_MISSING", e1.get("code").asText());

        // 日期不在配水窗口（窗口只覆盖 10-01、10-02 两个 UTC 自然日）-> 422 DATE_OUT_OF_WINDOW
        String sk2 = key("sk");
        JsonNode e2 = settle(key("sc"), 422, List.of(item(sk2, allocation, "2026-10-05", "0.5")));
        assertEquals("DATE_OUT_OF_WINDOW", e2.get("code").asText());

        // 上游剩余额度不足：持有 1，扣减 1 + 0.1 = 1.1 -> 422，消息含实际/要求/差额
        String sk3 = key("sk");
        JsonNode e3 = settle(key("sc"), 422, List.of(item(sk3, allocation, D1, "1")));
        assertEquals("UPSTREAM_QUOTA_INSUFFICIENT", e3.get("code").asText());
        assertTrue(e3.get("message").asText().contains("1.1"), e3.get("message").asText());
        assertTrue(e3.get("message").asText().contains("差额"), e3.get("message").asText());

        // 舍入后守恒不成立：1.001 + 0.10 = 1.101，两位小数扣减 1.10 不等于送达+损耗 -> 422
        String sk4 = key("sk");
        JsonNode e4 = settle(key("sc"), 422, List.of(item(sk4, allocation, D1, "1.001")));
        assertEquals("CONSERVATION_VIOLATION", e4.get("code").asText());

        // 无任何部分写入：核销记录查询不到，持有额度不变
        for (String sk : List.of(sk1, sk2, sk3, sk4)) {
            getStatus("/api/settlements/" + sk, 404);
        }
        assertEquals(0, getOk("/api/allocations/" + allocation + "/settlements").get("settlements").size());
        assertEquals("1", heldAmount(windowId));
    }

    @Test
    void settlementOnNonApprovedAllocationReturns409() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        // 仅提交不批准
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", "user-1");
        body.put("amount", "10");
        postOk("/api/allocations", body, "alice");
        putRate(channel, D1, "0.1", 200);
        JsonNode error = settle(key("sc"), 409, List.of(item(key("sk"), allocationKey, D1, "1")));
        assertEquals("ALLOCATION_NOT_APPROVED", error.get("code").asText());
        // 申请不存在 -> 404
        settle(key("sc"), 404, List.of(item(key("sk"), "no-such-" + run, D1, "1")));
    }

    // ------------------------------------------------------------------
    // 率锁定与记录不可变
    // ------------------------------------------------------------------

    @Test
    void rateLockedAfterFirstSettlementAndRecordsImmutable() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String source = submitAndApprove(windowId, "user-1", "50");
        putRate(channel, D1, "0.1", 200);
        String settlementKey = key("sk");
        settle(key("sc"), 200, List.of(item(settlementKey, source, D1, "10")));

        // 当日首次核销后修改率 -> 409 RATE_LOCKED
        JsonNode locked = putRate(channel, D1, "0.2", 409);
        assertEquals("RATE_LOCKED", locked.get("code").asText());
        // 其他日期不受影响
        putRate(channel, D2, "0.2", 200);

        // 转让额度不改写已结算记录
        String targetKey = key("ak");
        Map<String, Object> targetBody = new HashMap<>();
        targetBody.put("commandKey", key("ac"));
        targetBody.put("allocationKey", targetKey);
        targetBody.put("windowId", windowId);
        targetBody.put("userId", "user-2");
        targetBody.put("amount", "5");
        postOk("/api/allocations", targetBody, "bob");
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", targetKey);
        postOk("/api/transfers", transferBody, "alice");

        JsonNode detail = getOk("/api/settlements/" + settlementKey);
        assertEquals("0.1", detail.get("rate").asText());
        assertEquals(1, detail.get("rateVersion").asInt());
        assertEquals("10", detail.get("deliveredAmount").asText());
        assertEquals("1", detail.get("lossAmount").asText());
        assertEquals("11", detail.get("deductedAmount").asText());
        assertEquals("39", detail.get("remainingHeldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 批量核销：完整最终余额预校验，任一失败整批回滚
    // ------------------------------------------------------------------

    @Test
    void batchSettlementValidatesFinalBalanceAndRollsBack() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10");
        putRate(channel, D1, "0.1", 200);

        // 6.6 + 4.4 = 11 > 10：第二笔使最终余额为负 -> 整批 422，第一笔也不写入
        String sk1 = key("sk");
        String sk2 = key("sk");
        JsonNode error = settle(key("sc"), 422, List.of(
                item(sk1, allocation, D1, "6"), item(sk2, allocation, D1, "4")));
        assertEquals("UPSTREAM_QUOTA_INSUFFICIENT", error.get("code").asText());
        getStatus("/api/settlements/" + sk1, 404);
        getStatus("/api/settlements/" + sk2, 404);
        assertEquals("10", heldAmount(windowId));

        // 5.5 + 4.4 = 9.9 <= 10 -> 整批成功，逐笔剩余额度固化
        JsonNode ok = settle(key("sc"), 200, List.of(
                item(key("sk"), allocation, D1, "5"), item(key("sk"), allocation, D1, "4")));
        assertEquals(2, ok.get("settlements").size());
        assertEquals("5.5", ok.get("settlements").get(0).get("deductedAmount").asText());
        assertEquals("4.5", ok.get("settlements").get(0).get("remainingHeldAmount").asText());
        assertEquals("4.4", ok.get("settlements").get(1).get("deductedAmount").asText());
        assertEquals("0.1", ok.get("settlements").get(1).get("remainingHeldAmount").asText());
        assertEquals("0.1", heldAmount(windowId));

        // 批内重复 settlementKey -> 400；空批 -> 400
        String dup = key("sk");
        settle(key("sc"), 400, List.of(
                item(dup, allocation, D1, "0.01"), item(dup, allocation, D1, "0.01")));
        settle(key("sc"), 400, List.of());
    }

    // ------------------------------------------------------------------
    // 反向更正
    // ------------------------------------------------------------------

    @Test
    void reversalRestoresQuotaAndKeepsOriginalImmutable() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "100");
        putRate(channel, D1, "0.1", 200);
        String settlementKey = key("sk");
        settle(key("sc"), 200, List.of(item(settlementKey, allocation, D1, "10")));
        assertEquals("89", heldAmount(windowId));

        // 反向更正：等额负值记录 + 回补上游额度
        String reversalKey = key("rk");
        JsonNode reversal = postOk("/api/settlements/" + settlementKey + "/reverse",
                Map.of("commandKey", key("vc"), "reversalKey", reversalKey), null);
        assertEquals("REVERSAL", reversal.get("kind").asText());
        assertEquals("-10", reversal.get("deliveredAmount").asText());
        assertEquals("-1", reversal.get("lossAmount").asText());
        assertEquals("-11", reversal.get("deductedAmount").asText());
        assertEquals("100", reversal.get("remainingHeldAmount").asText());
        assertEquals(settlementKey, reversal.get("reversalOfKey").asText());
        assertEquals("100", heldAmount(windowId));

        // 原记录不被改写
        JsonNode original = getOk("/api/settlements/" + settlementKey);
        assertEquals("SETTLEMENT", original.get("kind").asText());
        assertEquals("11", original.get("deductedAmount").asText());
        assertEquals("89", original.get("remainingHeldAmount").asText());

        // 重复更正 -> 409；更正反向记录 -> 409；原核销不存在 -> 404
        JsonNode again = objectMapper.readTree(postJson("/api/settlements/" + settlementKey + "/reverse",
                Map.of("commandKey", key("vc"), "reversalKey", key("rk")), null, 409)
                .getResponse().getContentAsString());
        assertEquals("ALREADY_REVERSED", again.get("code").asText());
        JsonNode notReversable = objectMapper.readTree(postJson("/api/settlements/" + reversalKey + "/reverse",
                Map.of("commandKey", key("vc"), "reversalKey", key("rk")), null, 409)
                .getResponse().getContentAsString());
        assertEquals("NOT_REVERSABLE", notReversable.get("code").asText());
        postJson("/api/settlements/no-such-" + run + "/reverse",
                Map.of("commandKey", key("vc"), "reversalKey", key("rk")), null, 404);

        // 诊断：正常核销与反向更正分列，净额为 0
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-rates/" + D1 + "/diagnostics");
        assertEquals(1, diag.get("settlementCount").asLong());
        assertEquals(1, diag.get("reversalCount").asLong());
        assertEquals("10", diag.get("settledDelivered").asText());
        assertEquals("-10", diag.get("reversedDelivered").asText());
        assertEquals("0", diag.get("netDelivered").asText());
        assertEquals("0", diag.get("netLoss").asText());
        assertEquals("0", diag.get("netDeducted").asText());
    }

    // ------------------------------------------------------------------
    // 幂等：同参重放、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void settlementIdempotencyAndFailureDoesNotOccupyKey() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10");
        putRate(channel, D1, "0.1", 200);

        // 同 commandKey 同参重放 -> 返回首次完整结果，只写一条记录
        String commandKey = key("sc");
        String settlementKey = key("sk");
        List<Map<String, Object>> items = List.of(item(settlementKey, allocation, D1, "5"));
        JsonNode first = settle(commandKey, 200, items);
        JsonNode replay = settle(commandKey, 200, items);
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/allocations/" + allocation + "/settlements").get("settlements").size());

        // 同 commandKey 异参 -> 409
        settle(commandKey, 409, List.of(item(settlementKey, allocation, D1, "6")));
        // 换 commandKey 复用 settlementKey -> 409
        JsonNode reused = settle(key("sc"), 409, List.of(item(settlementKey, allocation, D1, "1")));
        assertEquals("SETTLEMENT_KEY_REUSED", reused.get("code").asText());

        // 失败命令不占键：先 422（扣减 9.9 超过剩余 4.5），修正参数后同键成功
        String failedCommand = key("sc");
        settle(failedCommand, 422, List.of(item(key("sk"), allocation, D1, "9")));
        JsonNode fixed = settle(failedCommand, 200, List.of(item(key("sk"), allocation, D1, "0.4")));
        assertEquals(1, fixed.get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsNeverOversell() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10");
        putRate(channel, D1, "0", 200);

        // 两笔各扣 6，余额 10 只够一笔：恰好一个成功一个 422
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("sc");
            String settlementKey = key("sk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.settleDeliveries(commandKey,
                            List.of(new SettlementItemRequest(settlementKey, allocation, D1, "6")));
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
        assertEquals(1, ok);
        assertEquals(1, insufficient);
        assertEquals("4", heldAmount(windowId));
    }

    @Test
    void concurrentRateModifyAndFirstSettlementAdjudicatedByCommitOrder() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "100");
        putRate(channel, D1, "0.1", 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String modifyCommand = key("rc");
        String settleCommand = key("sc");
        String settlementKey = key("sk");
        Future<Integer> modifyFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.upsertEvaporationRate(modifyCommand, channel, D1, "0.2");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<SettlementBatchResponse> settleFuture = pool.submit(() -> {
            gate.await();
            return waterService.settleDeliveries(settleCommand,
                    List.of(new SettlementItemRequest(settlementKey, allocation, D1, "10")));
        });
        gate.countDown();
        int modifyStatus = modifyFuture.get(30, TimeUnit.SECONDS);
        SettlementBatchResponse settlement = settleFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按提交顺序裁决：修改成功则核销必须用新率（0.2/版本2/扣减12）；
        // 核销先提交则修改必须 409 且核销固化旧率（0.1/版本1/扣减11）
        SettlementResponse record = settlement.settlements().get(0);
        if (modifyStatus == 200) {
            assertEquals("0.2", record.rate());
            assertEquals(2, record.rateVersion());
            assertEquals("12", record.deductedAmount());
        } else {
            assertEquals(409, modifyStatus);
            assertEquals("0.1", record.rate());
            assertEquals(1, record.rateVersion());
            assertEquals("11", record.deductedAmount());
        }
    }

    // ------------------------------------------------------------------
    // 取消未发生预约不产生蒸发损耗；只读查询不改变状态
    // ------------------------------------------------------------------

    @Test
    void cancelWithoutDeliveryProducesNoEvaporationLoss() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "10");
        putRate(channel, D1, "0.1", 200);
        // 未发生任何核销即取消：不产生损耗记录
        postOk("/api/allocations/" + allocation + "/cancel", Map.of("commandKey", key("cc")), "alice");
        assertEquals(0, getOk("/api/allocations/" + allocation + "/settlements").get("settlements").size());
        JsonNode diag = getOk("/api/channels/" + channel + "/evaporation-rates/" + D1 + "/diagnostics");
        assertEquals(0, diag.get("settlementCount").asLong());
        assertEquals("0", diag.get("netDeducted").asText());
        // 已取消申请不能核销 -> 409
        JsonNode error = settle(key("sc"), 409, List.of(item(key("sk"), allocation, D1, "1")));
        assertEquals("ALLOCATION_NOT_APPROVED", error.get("code").asText());
    }

    @Test
    void queriesAndDiagnosticsAreReadOnly() throws Exception {
        String channel = channel();
        long windowId = createWindow(channel, "100");
        String allocation = submitAndApprove(windowId, "user-1", "100");
        putRate(channel, D1, "0.1", 200);
        String settlementKey = key("sk");
        settle(key("sc"), 200, List.of(item(settlementKey, allocation, D1, "10")));

        // 重复读取结果一致，且不改变任何状态
        JsonNode diag1 = getOk("/api/channels/" + channel + "/evaporation-rates/" + D1 + "/diagnostics");
        JsonNode diag2 = getOk("/api/channels/" + channel + "/evaporation-rates/" + D1 + "/diagnostics");
        assertEquals(diag1, diag2);
        assertEquals("10", diag1.get("settledDelivered").asText());
        assertEquals("1", diag1.get("settledLoss").asText());
        assertEquals("11", diag1.get("settledDeducted").asText());
        assertEquals("11", diag1.get("netDeducted").asText());
        assertTrue(diag1.get("locked").asBoolean());

        JsonNode detail1 = getOk("/api/settlements/" + settlementKey);
        JsonNode detail2 = getOk("/api/settlements/" + settlementKey);
        assertEquals(detail1, detail2);

        JsonNode history = getOk("/api/allocations/" + allocation + "/settlements");
        assertEquals(1, history.get("settlements").size());
        assertEquals(settlementKey, history.get("settlements").get(0).get("settlementKey").asText());
        // 读取后持有额度未被改变
        assertEquals("89", heldAmount(windowId));
        // 未登记日期的诊断：率为 null、计数为 0
        JsonNode empty = getOk("/api/channels/" + channel + "/evaporation-rates/" + D2 + "/diagnostics");
        assertTrue(empty.get("rate").isNull());
        assertTrue(empty.get("version").isNull());
        assertFalse(empty.get("locked").asBoolean());
        assertEquals(0, empty.get("settlementCount").asLong());
    }
}
