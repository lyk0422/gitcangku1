package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SettleWithdrawalRequest.SettleItem;
import com.example.starter.exposure.web.SnapshotItemResponse;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 公告版本撤回与在途曝光批量结算 H2（MODE=MySQL）集成测试：
 * 撤回冻结、回执决议、显式结算、整体回滚、幂等与只读查询。
 */
@SpringBootTest
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
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    static final long T0 = BASE.toEpochMilli();

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
    ObjectMapper objectMapper;
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
        jdbc.update("DELETE FROM exposure_snapshot_item");
        jdbc.update("DELETE FROM exposure_withdrawal");
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
                                        String campaignId, int version, long cutoffAtUtc) {
        return service.withdraw(
                new WithdrawCampaignRequest(requestId, withdrawalKey, campaignId, version, cutoffAtUtc));
    }

    private SnapshotItemResponse receipt(String reservationId, String requestId,
                                         String receiptKey, long occurredAtUtc) {
        return service.receipt(reservationId, new ReceiptRequest(requestId, receiptKey, occurredAtUtc));
    }

    private SettleWithdrawalRequest settleReq(String requestId, WithdrawalResponse withdrawal) {
        List<SettleItem> items = withdrawal.items().stream()
                .map(i -> new SettleItem(i.reservationId(), i.campaignVersion()))
                .toList();
        return new SettleWithdrawalRequest(requestId, items);
    }

    @Test
    @DisplayName("撤回主流程：冻结在途预占为 SETTLING 快照，已确认不回退，原子禁止新预占")
    void withdraw_freezesInflight_blocksNewApply_keepsConfirmed() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));

        WithdrawalResponse w = withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        assertEquals("w1", w.withdrawalKey());
        assertEquals("c1", w.campaignId());
        assertEquals(1, w.campaignVersion());
        assertEquals(T0 + 30_000L, w.cutoffAtUtc());
        assertEquals("SETTLING", w.status().name());
        assertEquals(T0, w.createdAtUtc());
        assertNull(w.completedAtUtc());
        // 仅冻结在途预占 r1；已确认的 r2 不回退、不进快照
        assertEquals(1, w.items().size());
        SnapshotItemResponse item = w.items().get(0);
        assertEquals(r1.reservationId(), item.reservationId());
        assertEquals(1, item.campaignVersion());
        assertEquals("v1", item.visitorKey());
        assertEquals(T0, item.reservedAtUtc());
        assertEquals(T0 + 60_000L, item.expiresAtUtc());
        assertEquals("PENDING", item.decision().name());
        assertNull(item.receiptKey());
        assertNull(item.decidedAtUtc());

        assertEquals("SETTLING", service.getReservation(r1.reservationId()).status().name());
        assertEquals("CONFIRMED", service.getReservation(r2.reservationId()).status().name());

        // 原子禁止新预占
        assert409(() -> apply("req-a3", "c1", "v3"));

        // SETTLING 与 CONFIRMED 均占用额度
        QuotaResponse quota = service.queryQuota("c1", null, DAY);
        assertEquals(2, quota.usedTotal());
    }

    @Test
    @DisplayName("合法回执：occurredAt 早于截点、不早于预占、提交时未到期 → CONFIRMED 并消耗原频控，撤回完成")
    void receipt_validConfirm_keepsQuota_completesWithdrawal() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        SnapshotItemResponse decided = receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 10_000L);
        assertEquals("CONFIRMED", decided.decision().name());
        assertEquals("rk-1", decided.receiptKey());
        assertEquals(T0 + 10_000L, decided.occurredAtUtc());
        assertEquals(T0, decided.decidedAtUtc());
        assertEquals("CONFIRMED", service.getReservation(r1.reservationId()).status().name());

        // 确认消耗原频控：账目保持占用
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 全部快照项终态 → 撤回 COMPLETED
        WithdrawalResponse w = service.getWithdrawal("w1");
        assertEquals("COMPLETED", w.status().name());
        assertEquals(T0, w.completedAtUtc());

        // requestId 同参重放：原响应
        SnapshotItemResponse replay = receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 10_000L);
        assertEquals("CONFIRMED", replay.decision().name());
        // receiptKey 业务键重放（新 requestId、同键同参）：原决议
        SnapshotItemResponse bizReplay = receipt(r1.reservationId(), "req-rc2", "rk-1", T0 + 10_000L);
        assertEquals("CONFIRMED", bizReplay.decision().name());
        // 同 receiptKey 异参 → 409；新 receiptKey 对已决议项 → 409
        assert409(() -> receipt(r1.reservationId(), "req-rc3", "rk-1", T0 + 11_000L));
        assert409(() -> receipt(r1.reservationId(), "req-rc4", "rk-2", T0 + 10_000L));
    }

    @Test
    @DisplayName("非法回执变体：occurredAt 不早于截点、早于预占时刻、提交时已到期 → REJECTED 并释放额度")
    void receipt_invalidVariants_rejected_quotaReleased() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        // occurredAt == 截点（不早于截点）→ REJECTED
        SnapshotItemResponse d1 = receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 30_000L);
        assertEquals("REJECTED", d1.decision().name());
        // occurredAt 早于预占时刻 → REJECTED
        SnapshotItemResponse d2 = receipt(r2.reservationId(), "req-rc2", "rk-2", T0 - 1L);
        assertEquals("REJECTED", d2.decision().name());
        // 提交时到达到期时刻 → REJECTED（occurredAt 本身合法也不确认）
        mutableClock().advanceMillis(60_000L);
        SnapshotItemResponse d3 = receipt(r3.reservationId(), "req-rc3", "rk-3", T0 + 10_000L);
        assertEquals("REJECTED", d3.decision().name());

        assertEquals("REJECTED", service.getReservation(r1.reservationId()).status().name());
        assertEquals("REJECTED", service.getReservation(r2.reservationId()).status().name());
        assertEquals("REJECTED", service.getReservation(r3.reservationId()).status().name());

        // 驳回释放两级额度
        QuotaResponse quota = service.queryQuota("c1", null, DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(10, quota.remainingTotal());

        // 全部终态 → COMPLETED
        assertEquals("COMPLETED", service.getWithdrawal("w1").status().name());
    }

    @Test
    @DisplayName("显式结算：无法合法确认项释放为 REJECTED，仍可确认项保持 PENDING 并返回 409；终态后可结算完成")
    void settle_partialRelease_conflictThenCompletes() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        // 第二笔晚 40 秒预占，到期时刻更晚
        mutableClock().advanceMillis(40_000L);
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        // 截点在两笔预占之后：r1 预占时刻 T0、r2 预占时刻 T0+40s，均早于截点 T0+50s
        WithdrawalResponse w = withdraw("req-w1", "w1", "c1", 1, T0 + 50_000L);
        assertEquals(2, w.items().size());

        // 集合缺项 → 409 且整体回滚：两项仍 PENDING，账目不变（此刻无到期项，查询不改变状态）
        SettleWithdrawalRequest partial = new SettleWithdrawalRequest("req-s0",
                List.of(new SettleItem(r1.reservationId(), 1)));
        assert409(() -> service.settle("w1", partial));
        WithdrawalResponse afterFailed = service.getWithdrawal("w1");
        assertEquals("SETTLING", afterFailed.status().name());
        assertTrue(afterFailed.items().stream().allMatch(i -> i.decision().name().equals("PENDING")));
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());

        // 推进到 T0+70s：r1 已到期（T0+60s）无法合法确认；r2 未到期（T0+100s）仍可合法确认
        mutableClock().advanceMillis(30_000L);

        // 完整集合结算：r1 释放为 REJECTED，r2 保持 PENDING，整体 409；失败不占键
        assert409(() -> service.settle("w1", settleReq("req-s1", w)));
        WithdrawalResponse afterSettle = service.getWithdrawal("w1");
        assertEquals("SETTLING", afterSettle.status().name());
        SnapshotItemResponse item1 = afterSettle.items().stream()
                .filter(i -> i.reservationId().equals(r1.reservationId())).findFirst().orElseThrow();
        SnapshotItemResponse item2 = afterSettle.items().stream()
                .filter(i -> i.reservationId().equals(r2.reservationId())).findFirst().orElseThrow();
        assertEquals("REJECTED", item1.decision().name());
        assertNull(item1.receiptKey());
        assertEquals("PENDING", item2.decision().name());
        assertEquals("REJECTED", service.getReservation(r1.reservationId()).status().name());
        // r1 额度已释放，r2 仍占用
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // r2 合法回执确认后全部终态 → COMPLETED
        SnapshotItemResponse d2 = receipt(r2.reservationId(), "req-rc2", "rk-2", T0 + 45_000L);
        assertEquals("CONFIRMED", d2.decision().name());
        assertEquals("COMPLETED", service.getWithdrawal("w1").status().name());

        // 409 的结算未占键：同键同参重试，全部终态后返回 200 结果
        WithdrawalResponse completed = service.settle("w1", settleReq("req-s1", w));
        assertEquals("COMPLETED", completed.status().name());
        assertNotNull(completed.completedAtUtc());
    }

    @Test
    @DisplayName("撤回校验与整体回滚：版本不符/重复撤回/键冲突 409，失败不产生任何状态变更")
    void withdraw_validationFailures_rollBackCompletely() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");

        // 版本不符 → 409；公告未撤回、无快照、预占仍在途、额度仍占用
        assert409(() -> withdraw("req-w0", "w0", "c1", 2, T0 + 30_000L));
        assertEquals("RESERVED", service.getReservation(r1.reservationId()).status().name());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assert404(() -> service.getWithdrawal("w0"));
        // 失败不占 requestId 也不占 withdrawalKey：修正参数后同键可成功
        WithdrawalResponse w = withdraw("req-w0", "w0", "c1", 1, T0 + 30_000L);
        assertEquals("SETTLING", w.status().name());

        // 同 withdrawalKey 同参（新 requestId）→ 重放原结果
        WithdrawalResponse replay = withdraw("req-w9", "w0", "c1", 1, T0 + 30_000L);
        assertEquals("w0", replay.withdrawalKey());
        assertEquals(T0 + 30_000L, replay.cutoffAtUtc());
        // 同 withdrawalKey 异参 → 409
        assert409(() -> withdraw("req-w10", "w0", "c1", 1, T0 + 31_000L));
        // 公告已撤回：新撤回键 → 409
        assert409(() -> withdraw("req-w11", "w1", "c1", 1, T0 + 30_000L));
        // 不存在的公告 → 404
        assert404(() -> withdraw("req-w12", "w2", "nope", 1, T0 + 30_000L));
    }

    @Test
    @DisplayName("SETTLING 到期释放：查询触发结算为 REJECTED 并完成撤回；撤回查询本身只读不触发结算")
    void settlingExpiry_lazyRelease_andReadOnlyQuery() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        // 推进到到期时刻之后
        mutableClock().advanceMillis(61_000L);

        // 撤回查询只读：不触发到期结算，快照项仍 PENDING
        WithdrawalResponse readonly = service.getWithdrawal("w1");
        assertEquals("PENDING", readonly.items().get(0).decision().name());
        assertEquals("SETTLING", readonly.status().name());

        // 额度查询触发到期释放：REJECTED、额度释放、撤回完成
        QuotaResponse quota = service.queryQuota("c1", null, DAY);
        assertEquals(0, quota.usedTotal());
        WithdrawalResponse settled = service.getWithdrawal("w1");
        assertEquals("REJECTED", settled.items().get(0).decision().name());
        assertEquals("COMPLETED", settled.status().name());
        assertEquals("REJECTED", service.getReservation(r1.reservationId()).status().name());
    }

    @Test
    @DisplayName("SETTLING 预占禁止普通确认/取消：409；REJECTED 后确认同样 409")
    void settlingReservation_rejectsPlainConfirmCancel() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        assert409(() -> service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1")));
        assert409(() -> service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1")));
        // 状态未被普通操作改变
        assertEquals("SETTLING", service.getReservation(r1.reservationId()).status().name());

        receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 40_000L);
        assertEquals("REJECTED", service.getReservation(r1.reservationId()).status().name());
        assert409(() -> service.confirm(r1.reservationId(), new ReservationActionRequest("req-k2")));
    }

    @Test
    @DisplayName("回执幂等：requestId 异参 409；receiptKey 跨预占唯一；失败不占键")
    void receipt_idempotencyAndKeyUniqueness() {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 10_000L);
        // 同 requestId 异参 → 409
        assert409(() -> receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 11_000L));
        // 同 receiptKey 用于其他预占 → 409
        assert409(() -> receipt(r2.reservationId(), "req-rc2", "rk-1", T0 + 10_000L));

        // 非快照预占回执 → 404，且失败不占 requestId
        createCampaign("req-c2", "c2", 10, 5);
        ReservationResponse other = apply("req-a9", "c2", "v9");
        assert404(() -> receipt(other.reservationId(), "req-rc9", "rk-9", T0 + 10_000L));
        SnapshotItemResponse d2 = receipt(r2.reservationId(), "req-rc9", "rk-9", T0 + 10_000L);
        assertEquals("CONFIRMED", d2.decision().name());
    }

    @Test
    @DisplayName("HTTP 语义：撤回 201、查询 200、回执 200、结算未竟 409、参数缺失 400、未知撤回 404")
    void httpSemantics_withdrawalEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":5}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated());

        long cutoff = T0 + 30_000L;
        mockMvc.perform(post("/api/exposure/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"withdrawalKey\":\"wh\",\"campaignId\":\"ch\","
                                + "\"campaignVersion\":1,\"cutoffAtUtc\":" + cutoff + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SETTLING"))
                .andExpect(jsonPath("$.cutoffAtUtc").value(cutoff))
                .andExpect(jsonPath("$.items[0].decision").value("PENDING"))
                .andExpect(jsonPath("$.items[0].visitorKey").value("u1"));

        String reservationId = jdbc.queryForObject(
                "SELECT reservation_id FROM exposure_reservation WHERE campaign_id = 'ch'",
                String.class);

        // 回执：合法确认
        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/receipt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"receiptKey\":\"rk-h\","
                                + "\"occurredAtUtc\":" + (T0 + 10_000L) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("CONFIRMED"))
                .andExpect(jsonPath("$.receiptKey").value("rk-h"));

        // 查询：截点与逐项决议
        mockMvc.perform(get("/api/exposure/withdrawals/wh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].decision").value("CONFIRMED"));

        // 结算：已完成撤回的完整集合 → 200
        mockMvc.perform(post("/api/exposure/withdrawals/wh/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"items\":[{\"reservationId\":\""
                                + reservationId + "\",\"campaignVersion\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 参数缺失 → 400；未知撤回 → 404
        mockMvc.perform(post("/api/exposure/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"withdrawalKey\":\"w2\",\"campaignId\":\"ch\","
                                + "\"campaignVersion\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exposure/withdrawals/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("结算未竟的 HTTP 409：仍可合法确认项存在时 settle 返回 409")
    void httpSemantics_settleIncompleteReturns409() throws Exception {
        createCampaign("req-c1", "c1", 10, 5);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        withdraw("req-w1", "w1", "c1", 1, T0 + 30_000L);

        mockMvc.perform(post("/api/exposure/withdrawals/w1/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hs\",\"items\":[{\"reservationId\":\""
                                + r1.reservationId() + "\",\"campaignVersion\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 409 后项仍 PENDING，可正常回执
        SnapshotItemResponse decided = receipt(r1.reservationId(), "req-rc1", "rk-1", T0 + 10_000L);
        assertEquals("CONFIRMED", decided.decision().name());
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
