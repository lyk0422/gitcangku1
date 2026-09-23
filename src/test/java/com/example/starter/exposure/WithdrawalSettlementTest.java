package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SettleWithdrawalRequest.ReservationVersionKey;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalItemResponse;
import com.example.starter.exposure.web.WithdrawalResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告版本撤回截点与在途曝光批量结算 H2（MODE=MySQL）测试：
 * 主流程、回执时间规则、显式结算、整体回滚、幂等与只读查询。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_withdrawal_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalSettlementTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final long BASE_MS = BASE.toEpochMilli();
    static final long TTL = 60_000L;
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    /** 以可控时钟覆盖生产系统时钟。 */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_receipt");
        jdbc.update("DELETE FROM withdrawal_item");
        jdbc.update("DELETE FROM withdrawal");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    private WithdrawalResponse withdraw(String requestId, String withdrawalKey,
                                        String campaignId, long cutoffAt) {
        return service.withdraw(new WithdrawCampaignRequest(
                requestId, withdrawalKey, campaignId, 1, cutoffAt));
    }

    private Map<String, WithdrawalItemResponse> itemsById(WithdrawalResponse withdrawal) {
        return withdrawal.items().stream()
                .collect(Collectors.toMap(WithdrawalItemResponse::reservationId, Function.identity()));
    }

    private List<ReservationVersionKey> keysOf(WithdrawalResponse withdrawal) {
        return withdrawal.items().stream()
                .map(item -> new ReservationVersionKey(item.reservationId(), item.campaignVersion()))
                .toList();
    }

    @Test
    @DisplayName("撤回主流程：冻结 PENDING 为 SETTLING 快照，已确认不回退，原子禁止新预占")
    void withdraw_freezesPendingBansNewApplyAndKeepsConfirmed() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse confirmed = apply("req-a1", "c1", "v1");
        ReservationResponse pending = apply("req-a2", "c1", "v2");
        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));

        WithdrawalResponse withdrawal = withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);

        assertEquals("w1", withdrawal.withdrawalKey());
        assertEquals("c1", withdrawal.campaignId());
        assertEquals(1, withdrawal.campaignVersion());
        assertEquals(BASE_MS + 30_000L, withdrawal.cutoffAtUtc());
        assertEquals("SETTLING", withdrawal.status().name());
        assertEquals(BASE_MS, withdrawal.createdAtUtc());
        assertNull(withdrawal.completedAtUtc());

        // 仅 PENDING 预占进入快照；已确认曝光不回退、不进快照
        assertEquals(1, withdrawal.items().size());
        WithdrawalItemResponse item = withdrawal.items().get(0);
        assertEquals(pending.reservationId(), item.reservationId());
        assertEquals(1, item.campaignVersion());
        assertEquals("v2", item.visitorId());
        assertEquals(BASE_MS, item.reservedAtUtc());
        assertEquals(BASE_MS + TTL, item.expiresAtUtc());
        assertEquals("SETTLING", item.status().name());
        assertNull(item.decidedAtUtc());
        assertNull(item.decisionReason());

        // 原子禁止新预占：409 且两级额度不变
        assert409(() -> apply("req-a3", "c1", "v3"));
        QuotaResponse quota = service.queryQuota("c1", null, DAY);
        assertEquals(2, quota.usedTotal());

        // 已确认曝光保持 CONFIRMED 且持续占用额度
        ReservationResponse confirmedView = service.getReservation(confirmed.reservationId());
        assertEquals("CONFIRMED", confirmedView.status().name());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("回执时间规则：不早于预占时刻、早于截点、提交时未过期才可确认，其余释放为 REJECTED")
    void receipt_timeRulesConfirmOrReject() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        ReservationResponse r4 = apply("req-a4", "c1", "v4");
        WithdrawalResponse withdrawal = withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);
        assertEquals(4, withdrawal.items().size());

        // occurredAt == reservedAt（边界，不早于预占时刻）且早于截点、未过期 → CONFIRMED
        ReceiptResponse ok = service.submitReceipt(
                new ReceiptRequest("req-rc1", "rk1", r1.reservationId(), BASE_MS));
        assertEquals("CONFIRMED", ok.decision().name());
        assertEquals(BASE_MS, ok.decidedAtUtc());

        // occurredAt == cutoffAt（不早于截点失败）→ REJECTED
        ReceiptResponse atCutoff = service.submitReceipt(
                new ReceiptRequest("req-rc2", "rk2", r2.reservationId(), BASE_MS + 30_000L));
        assertEquals("REJECTED", atCutoff.decision().name());

        // occurredAt < reservedAt → REJECTED
        ReceiptResponse tooEarly = service.submitReceipt(
                new ReceiptRequest("req-rc3", "rk3", r3.reservationId(), BASE_MS - 1));
        assertEquals("REJECTED", tooEarly.decision().name());

        // 提交时刻达到到期时刻 → 即使 occurredAt 合法也 REJECTED
        mutableClock().advanceMillis(TTL);
        ReceiptResponse tooLate = service.submitReceipt(
                new ReceiptRequest("req-rc4", "rk4", r4.reservationId(), BASE_MS + 10_000L));
        assertEquals("REJECTED", tooLate.decision().name());

        // 确认消耗原频控（持续占用），REJECTED 全部释放
        QuotaResponse quota = service.queryQuota("c1", null, DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(0, service.queryQuota("c1", "v2", DAY).usedVisitor());

        // 预占单终态与快照决议一致；全部终态后撤回 COMPLETED
        assertEquals("CONFIRMED", service.getReservation(r1.reservationId()).status().name());
        assertEquals("REJECTED", service.getReservation(r2.reservationId()).status().name());
        assertEquals("REJECTED", service.getReservation(r3.reservationId()).status().name());
        assertEquals("REJECTED", service.getReservation(r4.reservationId()).status().name());

        WithdrawalResponse view = service.getWithdrawal("w1");
        assertEquals("COMPLETED", view.status().name());
        assertNotNull(view.completedAtUtc());
        Map<String, WithdrawalItemResponse> byId = itemsById(view);
        assertEquals("CONFIRMED", byId.get(r1.reservationId()).status().name());
        assertEquals("RECEIPT_CONFIRMED", byId.get(r1.reservationId()).decisionReason());
        assertEquals("REJECTED", byId.get(r2.reservationId()).status().name());
        assertEquals("RECEIPT_REJECTED", byId.get(r2.reservationId()).decisionReason());
        assertEquals("REJECTED", byId.get(r4.reservationId()).status().name());
        assertEquals("RECEIPT_REJECTED", byId.get(r4.reservationId()).decisionReason());
    }

    @Test
    @DisplayName("显式结算：仍有可合法确认项返回 409 且整体不变更；过期后同键重试成功收口")
    void settle_conflictWhenConfirmable_rollsBackAndRetriesAfterExpiry() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        WithdrawalResponse withdrawal = withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);
        List<ReservationVersionKey> fullSet = keysOf(withdrawal);

        // r1 先被回执拒绝（occurredAt 不早于截点）
        ReceiptResponse rejected = service.submitReceipt(
                new ReceiptRequest("req-rc1", "rk1", r1.reservationId(), BASE_MS + 30_000L));
        assertEquals("REJECTED", rejected.decision().name());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // r2 仍可合法确认：结算 409，且整体不变更（不释放 r2、不收口、不占幂等键）
        assert409(() -> service.settle("w1", new SettleWithdrawalRequest("req-s1", fullSet)));
        WithdrawalResponse afterConflict = service.getWithdrawal("w1");
        assertEquals("SETTLING", afterConflict.status().name());
        Map<String, WithdrawalItemResponse> byId = itemsById(afterConflict);
        assertEquals("REJECTED", byId.get(r1.reservationId()).status().name());
        assertEquals("SETTLING", byId.get(r2.reservationId()).status().name());
        assertEquals("RESERVED", service.getReservation(r2.reservationId()).status().name());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 推进到到期时刻后，同一 requestId 同参重试：r2 已无法合法确认，释放为 EXPIRED 并收口
        mutableClock().advanceMillis(TTL);
        WithdrawalResponse settled = service.settle("w1", new SettleWithdrawalRequest("req-s1", fullSet));
        assertEquals("COMPLETED", settled.status().name());
        assertEquals(BASE_MS + TTL, settled.completedAtUtc());
        Map<String, WithdrawalItemResponse> settledById = itemsById(settled);
        assertEquals("EXPIRED", settledById.get(r2.reservationId()).status().name());
        assertEquals("SETTLED_EXPIRED", settledById.get(r2.reservationId()).decisionReason());
        assertEquals("EXPIRED", service.getReservation(r2.reservationId()).status().name());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v2", DAY).usedVisitor());
    }

    @Test
    @DisplayName("显式结算：预占时刻不早于截点的未回执项立即释放为 REJECTED；集合不匹配 409")
    void settle_noValidOccurredAtRejectsImmediatelyAndSetMismatch409() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        // 截点早于预占时刻：不存在合法 occurredAt
        WithdrawalResponse withdrawal = withdraw("req-w1", "w1", "c1", BASE_MS - 1);

        // 集合不完整 / 版本不符 / 多出成员均 409
        assert409(() -> service.settle("w1", new SettleWithdrawalRequest("req-s0",
                List.of(new ReservationVersionKey(r1.reservationId(), 2)))));
        assert409(() -> service.settle("w1", new SettleWithdrawalRequest("req-s00",
                List.of(new ReservationVersionKey(r1.reservationId(), 1),
                        new ReservationVersionKey("ghost", 1)))));

        WithdrawalResponse settled = service.settle("w1",
                new SettleWithdrawalRequest("req-s1", keysOf(withdrawal)));
        assertEquals("COMPLETED", settled.status().name());
        WithdrawalItemResponse item = settled.items().get(0);
        assertEquals("REJECTED", item.status().name());
        assertEquals("SETTLED_NO_VALID_OCCURRENCE", item.decisionReason());
        assertEquals("REJECTED", service.getReservation(r1.reservationId()).status().name());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 已 COMPLETED 的撤回再次同集合结算：返回当前状态，无变更
        WithdrawalResponse again = service.settle("w1",
                new SettleWithdrawalRequest("req-s2", keysOf(withdrawal)));
        assertEquals("COMPLETED", again.status().name());
    }

    @Test
    @DisplayName("幂等：requestId 同参重放、异参 409、失败不占键；withdrawalKey/receiptKey 唯一")
    void idempotency_requestIdAndBusinessKeys() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");

        WithdrawalResponse first = withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);
        // 同 requestId 同参重放：返回原结果，不产生第二条撤回
        WithdrawalResponse replay = withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);
        assertEquals(first.items().size(), replay.items().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM withdrawal", Integer.class));
        // 同 requestId 异参 → 409
        assert409(() -> withdraw("req-w1", "w1", "c1", BASE_MS + 20_000L));
        // 同 withdrawalKey 不同 requestId → 唯一约束 409
        assert409(() -> withdraw("req-w2", "w1", "c1", BASE_MS + 30_000L));

        // 回执确认 r1
        ReceiptResponse confirmed = service.submitReceipt(
                new ReceiptRequest("req-rc1", "rk1", r1.reservationId(), BASE_MS + 1_000L));
        assertEquals("CONFIRMED", confirmed.decision().name());
        // 同 requestId 重放 → 原决议
        ReceiptResponse receiptReplay = service.submitReceipt(
                new ReceiptRequest("req-rc1", "rk1", r1.reservationId(), BASE_MS + 1_000L));
        assertEquals("CONFIRMED", receiptReplay.decision().name());
        // 同 receiptKey 同参（新 requestId）→ 原决议
        ReceiptResponse sameKey = service.submitReceipt(
                new ReceiptRequest("req-rc2", "rk1", r1.reservationId(), BASE_MS + 1_000L));
        assertEquals("CONFIRMED", sameKey.decision().name());
        // 同 receiptKey 异参 → 409
        assert409(() -> service.submitReceipt(
                new ReceiptRequest("req-rc3", "rk1", r1.reservationId(), BASE_MS + 2_000L)));
        // 已决议快照项上的新回执 → 409
        assert409(() -> service.submitReceipt(
                new ReceiptRequest("req-rc4", "rk2", r1.reservationId(), BASE_MS + 1_000L)));
        // 确认未被重复决议影响：额度仍恰好占用 2（r1 确认 + r2 预占）
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());

        // 失败不占键：非快照预占回执 409 后，同一 requestId 可用于合法回执
        createCampaign("req-c2", "c2", 10, 10);
        ReservationResponse other = apply("req-a9", "c2", "v9");
        assert409(() -> service.submitReceipt(
                new ReceiptRequest("req-rc5", "rk5", other.reservationId(), BASE_MS)));
        ReceiptResponse valid = service.submitReceipt(
                new ReceiptRequest("req-rc5", "rk6", r2.reservationId(), BASE_MS + 1_000L));
        assertEquals("CONFIRMED", valid.decision().name());
    }

    @Test
    @DisplayName("快照内预占禁止旧确认/取消端点；查询撤回只读不触发到期结算")
    void snapshotGuardAndReadOnlyQuery() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        withdraw("req-w1", "w1", "c1", BASE_MS + 30_000L);

        // 旧确认/取消端点不得绕过截点规则
        assert409(() -> service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1")));
        assert409(() -> service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1")));
        assertEquals("RESERVED", service.getReservation(r1.reservationId()).status().name());

        // 查询只读：推进到到期时刻后查询撤回，快照项不被结算
        mutableClock().advanceMillis(TTL);
        WithdrawalResponse view = service.getWithdrawal("w1");
        assertEquals("SETTLING", view.status().name());
        assertEquals("SETTLING", view.items().get(0).status().name());

        // 既有预占查询触发到期结算：快照项同步 EXPIRED，撤回收口
        assertEquals("EXPIRED", service.getReservation(r1.reservationId()).status().name());
        WithdrawalResponse after = service.getWithdrawal("w1");
        assertEquals("COMPLETED", after.status().name());
        assertEquals("EXPIRED", after.items().get(0).status().name());
        assertEquals("EXPIRED", after.items().get(0).decisionReason());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("错误语义：撤回/结算/回执 404 与版本不匹配 409")
    void errorSemantics_notFoundAndVersionMismatch() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");

        assert404(() -> service.getWithdrawal("nope"));
        assert404(() -> service.settle("nope", new SettleWithdrawalRequest("req-s1",
                List.of(new ReservationVersionKey(r1.reservationId(), 1)))));
        assert404(() -> service.submitReceipt(new ReceiptRequest("req-rc1", "rk1", "ghost", BASE_MS)));
        assert404(() -> service.withdraw(
                new WithdrawCampaignRequest("req-w1", "w1", "ghost", 1, BASE_MS)));
        // 版本与服务端当前版本不一致 → 409
        assert409(() -> service.withdraw(
                new WithdrawCampaignRequest("req-w2", "w2", "c1", 2, BASE_MS)));
    }

    @Test
    @DisplayName("HTTP 语义：撤回 201、回执 201、结算 200/409、只读查询 200")
    void httpSemantics_withdrawReceiptSettle() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignVersion").value(1));

        String withdrawalBody = "{\"requestId\":\"h3\",\"withdrawalKey\":\"wk1\","
                + "\"campaignId\":\"ch\",\"campaignVersion\":1,\"cutoffAt\":"
                + (BASE_MS + 30_000L) + "}";
        String withdrawalJson = mockMvc.perform(post("/api/exposure/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawalBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SETTLING"))
                .andExpect(jsonPath("$.cutoffAtUtc").value(BASE_MS + 30_000L))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String reservationId = com.jayway.jsonpath.JsonPath.read(withdrawalJson, "$.items[0].reservationId");

        // 仍有可合法确认项：结算 409
        mockMvc.perform(post("/api/exposure/withdrawals/wk1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"items\":[{\"reservationId\":\""
                                + reservationId + "\",\"campaignVersion\":1}]}"))
                .andExpect(status().isConflict());

        // 合法回执确认
        mockMvc.perform(post("/api/exposure/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"receiptKey\":\"rk1\",\"reservationId\":\""
                                + reservationId + "\",\"occurredAt\":" + (BASE_MS + 1_000L) + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("CONFIRMED"));

        // 全部终态后结算收口
        mockMvc.perform(post("/api/exposure/withdrawals/wk1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"items\":[{\"reservationId\":\""
                                + reservationId + "\",\"campaignVersion\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"));

        mockMvc.perform(get("/api/exposure/withdrawals/wk1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 参数校验：缺 cutoffAt → 400
        mockMvc.perform(post("/api/exposure/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"withdrawalKey\":\"wk2\","
                                + "\"campaignId\":\"ch\",\"campaignVersion\":1}"))
                .andExpect(status().isBadRequest());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
