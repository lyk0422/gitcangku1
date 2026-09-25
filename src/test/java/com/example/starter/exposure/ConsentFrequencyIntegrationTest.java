package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.BatchApplyResponse;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.EvaluationResponse;
import com.example.starter.exposure.web.GrantConsentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同意版本与频控联合裁决 H2（MODE=MySQL）测试：
 * 同意版本时间轴、撤回、静默/冷却窗口、类别版本、批量全有或全无、快照与拒绝原因、幂等。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentFrequencyIntegrationTest {

    /** 可控时钟：固定起点，可设置/推进。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
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

    static final Instant BASE = Instant.parse("2026-09-26T10:00:00Z");
    static final long T0 = BASE.toEpochMilli();
    static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM visitor_consent");
        jdbc.update("DELETE FROM consent_scope");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 辅助构造 ----

    private CreateCampaignRequest campaignReq(String campaignId, String category,
                                              int total, int perVisitor,
                                              Integer silenceStart, Integer silenceEnd,
                                              Long minIntervalMillis) {
        return new CreateCampaignRequest("req-c-" + campaignId, campaignId, total, perVisitor,
                category, silenceStart, silenceEnd, minIntervalMillis);
    }

    private GrantConsentRequest consentReq(String reqId, String visitor, String category,
                                    String decision, long version, long start, long end) {
        return new GrantConsentRequest(reqId, visitor, category, decision, version, start, end);
    }

    private ApiException expectFailure(int httpStatus, String reason, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(httpStatus, ex.getStatus().value(), "HTTP 状态码不符");
        assertEquals(reason, ex.getReason(), "失败原因码不符");
        return ex;
    }

    private long reservationCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_reservation", Long.class);
        return count == null ? 0L : count;
    }

    // ---- 同意版本与时间轴 ----

    @Test
    @DisplayName("缺少有效 ALLOW 时申请返回 CONSENT_DENIED(403)，不创建预占且不扣两级频次预算")
    void apply_withoutAllow_deniedAndNoQuotaCharged() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));

        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));

        assertEquals(0, reservationCount());
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        assertEquals(10, quota.remainingTotal());
        assertEquals(10, quota.remainingVisitor());
    }

    @Test
    @DisplayName("命中有效 DENY 返回 CONSENT_DENIED；区间左闭右开边界严格")
    void apply_insideDenyWindow_deniedWithHalfOpenBoundaries() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        // DENY 覆盖 [T0+1000, T0+2000)
        service.grantConsent(consentReq("req-g1", "v1", "promo", "DENY", 1L,
                T0 + 1_000L, T0 + 2_000L));

        // 起点之前：无 ALLOW 仍拒绝（CONSENT_DENIED），但不是命中 DENY 区间
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 999L));
        assertFalse(service.evaluate("c1", "v1").allowed());

        // 起点（含）命中 DENY
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 1_000L));
        EvaluationResponse atStart = service.evaluate("c1", "v1");
        assertFalse(atStart.allowed());
        assertEquals("CONSENT_DENIED", atStart.reason());
        assertEquals(1L, atStart.consentVersion());
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));

        // 终点（不含）已出区间：缺少 ALLOW 依旧拒绝，但命中版本应为 null
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 2_000L));
        EvaluationResponse atEnd = service.evaluate("c1", "v1");
        assertFalse(atEnd.allowed());
        assertNull(atEnd.consentVersion());
    }

    @Test
    @DisplayName("高版本区间覆盖低版本：旧区间截断为残片，时间轴按时刻裁决为 ALLOW/DENY/ALLOW")
    void grant_higherVersion_splitsOldIntervalIntoFragments() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        // v1 ALLOW [T0, T0+1000)
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L, T0, T0 + 1_000L));
        // v2 DENY [T0+400, T0+600)：把 ALLOW 截成左右两段
        service.grantConsent(consentReq("req-g2", "v1", "promo", "DENY", 2L,
                T0 + 400L, T0 + 600L));

        List<ConsentResponse> intervals = service.queryConsents("v1", "promo");
        assertEquals(3, intervals.size(), "时间轴应包含左 ALLOW、DENY、右 ALLOW 三段");
        assertEquals(1L, intervals.get(0).consentVersion());
        assertEquals("ALLOW", intervals.get(0).decision());
        assertEquals(T0, intervals.get(0).effectiveStartUtc());
        assertEquals(T0 + 400L, intervals.get(0).effectiveEndUtc());
        assertEquals(2L, intervals.get(1).consentVersion());
        assertEquals("DENY", intervals.get(1).decision());
        assertEquals(1L, intervals.get(2).consentVersion());
        assertEquals(T0 + 600L, intervals.get(2).effectiveStartUtc());
        assertEquals(T0 + 1_000L, intervals.get(2).effectiveEndUtc());

        // 200ms：ALLOW 通过并建单
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 200L));
        assertTrue(service.evaluate("c1", "v1").allowed());
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(1L, r1.consentVersion());
        assertEquals("ALLOW", r1.consentDecision());

        // 500ms：DENY 拒绝
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 500L));
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v1")));

        // 700ms：右残片 ALLOW 再次通过
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 700L));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v1"));
        assertEquals(1L, r3.consentVersion());
    }

    @Test
    @DisplayName("新区间完整包裹旧 DENY 区间：旧区间截为左右两个同版本残片，高版本 ALLOW 可恢复授权")
    void grant_newIntervalWrapsOldDeny_oldSplitIntoTwoFragments() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "DENY", 1L,
                T0 + 400L, T0 + 600L));
        service.grantConsent(consentReq("req-g2", "v1", "promo", "ALLOW", 2L,
                T0 + 450L, T0 + 550L));

        List<ConsentResponse> intervals = service.queryConsents("v1", "promo");
        long denyFragments = intervals.stream()
                .filter(c -> "DENY".equals(c.decision()) && "ACTIVE".equals(c.status())).count();
        assertEquals(2, denyFragments, "DENY 应被截成左右两个残片");

        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 450L));
        assertTrue(service.evaluate("c1", "v1").allowed(), "高版本 ALLOW 区间内允许");
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 420L));
        assertFalse(service.evaluate("c1", "v1").allowed(), "DENY 左残片仍拒绝");
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 550L));
        assertFalse(service.evaluate("c1", "v1").allowed(), "终点不含，DENY 右残片恢复拒绝");
    }

    @Test
    @DisplayName("同意版本必须严格递增；DENY 不可被低版本覆盖（版本冲突前置拦截）")
    void grant_nonIncrementingVersion_conflictAndDoesNotOccupyKey() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "DENY", 5L, T0, T0 + 10_000L));

        expectFailure(409, "CONSENT_VERSION_CONFLICT",
                () -> service.grantConsent(consentReq("req-g2", "v1", "promo", "ALLOW", 5L,
                        T0, T0 + 10_000L)));
        expectFailure(409, "CONSENT_VERSION_CONFLICT",
                () -> service.grantConsent(consentReq("req-g3", "v1", "promo", "ALLOW", 4L,
                        T0, T0 + 10_000L)));

        // 失败不占键：req-g2 换成合法高版本后同键可成功
        ConsentResponse retry = service.grantConsent(consentReq("req-g2", "v1", "promo",
                "ALLOW", 6L, T0, T0 + 10_000L));
        assertEquals(6L, retry.consentVersion());

        // 高版本 ALLOW 已覆盖 DENY，申请通过——DENY 只是不被“更低”版本覆盖
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("RESERVED", r.status().name());
        assertEquals(6L, r.consentVersion());
    }

    @Test
    @DisplayName("同 requestKey 重放同意提交返回最初判定；异参重放 409")
    void grantConsent_idempotentReplay_conflictOnDifferentParams() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        ConsentResponse first = service.grantConsent(consentReq(
                "key-g1", "v1", "promo", "ALLOW", 1L, T0, T0 + 10_000L));

        ConsentResponse replay = service.grantConsent(consentReq(
                "key-g1", "v1", "promo", "ALLOW", 1L, T0, T0 + 10_000L));
        assertEquals(first.consentId(), replay.consentId(), "同键重放必须返回最初记录");

        expectFailure(409, "IDEMPOTENCY_CONFLICT", () -> service.grantConsent(consentReq(
                "key-g1", "v1", "promo", "ALLOW", 2L, T0, T0 + 10_000L)));
        expectFailure(409, "IDEMPOTENCY_CONFLICT", () -> service.grantConsent(consentReq(
                "key-g1", "v1", "promo", "DENY", 1L, T0, T0 + 10_000L)));
    }

    // ---- 撤回 ----

    @Test
    @DisplayName("撤回只影响之后的预占；撤回前的预占固化同意版本且回执正常结算")
    void withdraw_onlyAffectsLaterReservations_snapshotStaysFixed() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        ConsentResponse consent = service.grantConsent(consentReq(
                "req-g1", "v1", "promo", "ALLOW", 1L, T0 - 1_000L, T0 + 10_000L));
        ReservationResponse before = service.apply(
                new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(consent.consentId(), before.consentId());
        assertEquals(1L, before.consentVersion());

        ConsentResponse withdrawn = service.withdrawConsent(consent.consentId(),
                new WithdrawConsentRequest("req-w1"));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(T0, withdrawn.withdrawnAtUtc());

        // 撤回后新预占被拒，且不扣额度
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v1")));
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());

        // 已建预占仍按既有回执确认，快照版本不变
        ReservationResponse confirmed = service.confirm(before.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1L, confirmed.consentVersion());
        assertEquals("promo", confirmed.category());

        // 重复撤回 409
        expectFailure(409, "CONSENT_NOT_ACTIVE",
                () -> service.withdrawConsent(consent.consentId(),
                        new WithdrawConsentRequest("req-w2")));
        // 撤回同键重放返回最初结果
        ConsentResponse replay = service.withdrawConsent(consent.consentId(),
                new WithdrawConsentRequest("req-w1"));
        assertEquals("WITHDRAWN", replay.status());
    }

    // ---- 静默窗口与冷却频控 ----

    @Test
    @DisplayName("UTC 静默窗口左闭右开：窗口内 SILENCE_PERIOD，窗口外正常预占")
    void silenceWindow_halfOpenEnforced() {
        // 静默 [10:00:30, 10:01:00)
        service.createCampaign(campaignReq("c1", "promo", 10, 10, 36_030, 36_060, null));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 60_000L, T0 + 120_000L));

        ReservationResponse before = service.apply(
                new ApplyExposureRequest("req-a0", "c1", "v1"));
        assertNotNull(before.reservationId());

        mutableClock().setInstant(Instant.parse("2026-09-26T10:00:30Z"));
        expectFailure(429, "SILENCE_PERIOD",
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));

        mutableClock().setInstant(Instant.parse("2026-09-26T10:00:59Z"));
        expectFailure(429, "SILENCE_PERIOD",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v1")));

        // 终点不含：10:01:00 恢复
        mutableClock().setInstant(Instant.parse("2026-09-26T10:01:00Z"));
        assertTrue(service.evaluate("c1", "v1").allowed());
    }

    @Test
    @DisplayName("跨 UTC 午夜静默窗口：窗口跨日两段均拒绝，窗口外允许")
    void silenceWindow_wrappingMidnight_enforcedBothSegments() {
        // 静默 [23:59:50, 00:00:10)（起点大于终点表示跨午夜）
        service.createCampaign(campaignReq("c1", "promo", 10, 10, 86_390, 10, null));
        // 同意窗口覆盖两个 UTC 日，避免与同意裁决原因混淆
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 100_000_000L, T0 + 100_000_000L));

        mutableClock().setInstant(Instant.parse("2026-09-26T23:59:49Z"));
        assertTrue(service.evaluate("c1", "v1").allowed());
        mutableClock().setInstant(Instant.parse("2026-09-26T23:59:50Z"));
        assertEquals("SILENCE_PERIOD", service.evaluate("c1", "v1").reason());
        mutableClock().setInstant(Instant.parse("2026-09-27T00:00:05Z"));
        assertEquals("SILENCE_PERIOD", service.evaluate("c1", "v1").reason());
        mutableClock().setInstant(Instant.parse("2026-09-27T00:00:10Z"));
        assertTrue(service.evaluate("c1", "v1").allowed());
    }

    @Test
    @DisplayName("冷却频控：间隔内存在有效曝光则 FREQUENCY_LIMIT；取消/过期后不再视为有效曝光")
    void frequencyLimit_effectiveExposuresOnly() {
        // 冷却 10 秒，访客当日上限 5（避免与预算混淆）
        service.createCampaign(campaignReq("c1", "promo", 10, 5, null, null, 10_000L));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 60_000L, T0 + 120_000L));

        ReservationResponse r1 = service.apply(
                new ApplyExposureRequest("req-a1", "c1", "v1"));

        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 5_000L));
        expectFailure(429, "FREQUENCY_LIMIT",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v1")));

        // 取消后最近无有效曝光：可再次申请
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = service.apply(
                new ApplyExposureRequest("req-a3", "c1", "v1"));
        assertEquals("RESERVED", r2.status().name());

        // 已确认的曝光同样触发冷却
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 6_000L));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 8_000L));
        expectFailure(429, "FREQUENCY_LIMIT",
                () -> service.apply(new ApplyExposureRequest("req-a4", "c1", "v1")));

        // 间隔外恢复
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 16_001L));
        assertTrue(service.evaluate("c1", "v1").allowed());
    }

    // ---- 活动类别修改 ----

    @Test
    @DisplayName("类别修改：版本 +1 且旧同意不迁移；已建预占保留旧类别快照")
    void updateCategory_bumpsVersionAndDoesNotMigrateConsent() {
        service.createCampaign(campaignReq("c1", "promo", 10, 10, null, null, null));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 1_000L, T0 + 10_000L));
        ReservationResponse before = service.apply(
                new ApplyExposureRequest("req-a1", "c1", "v1"));

        var updated = service.updateCategory("c1", new UpdateCategoryRequest("req-u1", "news"));
        assertEquals("news", updated.category());
        assertEquals(2, updated.version());

        // 新类别无同意：拒绝且不扣额度
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v1")));

        // 旧预占快照不变
        ReservationResponse detail = service.getReservation(before.reservationId());
        assertEquals("promo", detail.category());
        assertEquals(1L, detail.consentVersion());

        // 旧同意仍挂在 promo 类别下，未迁移到 news
        assertTrue(service.queryConsents("v1", "news").isEmpty());
        assertEquals(1, service.queryConsents("v1", "promo").size());

        // 对 news 授予同意后恢复
        service.grantConsent(consentReq("req-g2", "v1", "news", "ALLOW", 1L,
                T0 - 1_000L, T0 + 10_000L));
        ReservationResponse after = service.apply(
                new ApplyExposureRequest("req-a3", "c1", "v1"));
        assertEquals("news", after.category());

        // 类别修改异参重放 409；同键重放返回最初结果
        expectFailure(409, "IDEMPOTENCY_CONFLICT",
                () -> service.updateCategory("c1", new UpdateCategoryRequest("req-u1", "games")));
        var replay = service.updateCategory("c1", new UpdateCategoryRequest("req-u1", "news"));
        assertEquals(2, replay.version(), "同键重放返回最初判定，版本不重复递增");
    }

    // ---- 批量预占 ----

    @Test
    @DisplayName("批量预占成功：每访客一张预占单，总额度按访客数占用，快照各自固化")
    void batchApply_success_chargesAllVisitors() {
        service.createCampaign(campaignReq("c1", "promo", 10, 5, null, null, null));
        for (String v : List.of("v1", "v2", "v3")) {
            service.grantConsent(consentReq("req-g-" + v, v, "promo", "ALLOW", 1L,
                    T0 - 1_000L, T0 + 10_000L));
        }

        BatchApplyResponse resp = service.batchApply(
                new BatchApplyRequest("req-b1", "c1", List.of("v3", "v1", "v2")));
        assertEquals(3, resp.reservations().size());
        // 响应按去重后字典序
        assertEquals(List.of("v1", "v2", "v3"),
                resp.reservations().stream().map(ReservationResponse::visitorId).toList());
        assertEquals(3, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v2", DAY).usedVisitor());
        assertTrue(resp.reservations().stream().allMatch(r -> "promo".equals(r.category())));
        assertTrue(resp.reservations().stream().allMatch(r -> r.consentVersion() == 1L));
    }

    @Test
    @DisplayName("批量预占任一访客缺同意：整批回滚，无预占单、无任何账目写入")
    void batchApply_oneConsentDenied_entireBatchRolledBack() {
        service.createCampaign(campaignReq("c1", "promo", 10, 5, null, null, null));
        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 1_000L, T0 + 10_000L));
        service.grantConsent(consentReq("req-g3", "v3", "promo", "ALLOW", 1L,
                T0 - 1_000L, T0 + 10_000L));
        // v2 无同意

        expectFailure(403, "CONSENT_DENIED", () -> service.batchApply(
                new BatchApplyRequest("req-b1", "c1", List.of("v1", "v2", "v3"))));

        assertEquals(0, reservationCount(), "失败批次不得留下预占单");
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal(), "总账不得被扣减");
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedVisitor(), "访客账不得被扣减");

        // 失败不占键：补齐同意后同键同参可成功
        service.grantConsent(consentReq("req-g2", "v2", "promo", "ALLOW", 1L,
                T0 - 1_000L, T0 + 10_000L));
        BatchApplyResponse retry = service.batchApply(
                new BatchApplyRequest("req-b1", "c1", List.of("v1", "v2", "v3")));
        assertEquals(3, retry.reservations().size());
    }

    @Test
    @DisplayName("批量预占按最终账目预校验：总额度不足整批回滚；同键重放返回相同预占单")
    void batchApply_totalBudgetShort_rollbackAndReplay() {
        service.createCampaign(campaignReq("c1", "promo", 2, 5, null, null, null));
        for (String v : List.of("v1", "v2", "v3")) {
            service.grantConsent(consentReq("req-g-" + v, v, "promo", "ALLOW", 1L,
                    T0 - 1_000L, T0 + 10_000L));
        }

        expectFailure(429, "BUDGET_EXHAUSTED", () -> service.batchApply(
                new BatchApplyRequest("req-b1", "c1", List.of("v1", "v2", "v3"))));
        assertEquals(0, reservationCount());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 改为 2 个访客后同键异参属不同请求集合 → 409；用新键成功
        BatchApplyResponse ok = service.batchApply(
                new BatchApplyRequest("req-b2", "c1", List.of("v1", "v2")));
        assertEquals(2, ok.reservations().size());

        // 同键重放返回最初成功结果
        BatchApplyResponse replay = service.batchApply(
                new BatchApplyRequest("req-b2", "c1", List.of("v1", "v2")));
        assertEquals(ok.reservations().stream().map(ReservationResponse::reservationId).toList(),
                replay.reservations().stream().map(ReservationResponse::reservationId).toList());
    }

    // ---- 拒绝原因查询 ----

    @Test
    @DisplayName("预校验按同意→静默→频控→预算顺序返回首个可区分原因")
    void evaluate_reasonsInPriorityOrder() {
        // 静默 + 冷却同时配置；无同意时原因仍是 CONSENT_DENIED
        service.createCampaign(campaignReq("c1", "promo", 1, 1, 36_000, 36_000 + 3_600, 10_000L));

        EvaluationResponse noConsent = service.evaluate("c1", "v1");
        assertFalse(noConsent.allowed());
        assertEquals("CONSENT_DENIED", noConsent.reason());
        assertNull(noConsent.consentVersion());

        service.grantConsent(consentReq("req-g1", "v1", "promo", "ALLOW", 1L,
                T0 - 1_000L, T0 + 100_000L));
        // BASE=10:00:00 落在静默 [10:00:00,11:00:00) 内
        EvaluationResponse silence = service.evaluate("c1", "v1");
        assertEquals("SILENCE_PERIOD", silence.reason());

        // 无静默公告、已有一笔有效预占 → 频控
        service.createCampaign(campaignReq("c2", "promo", 10, 10, null, null, 10_000L));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c2", "v1"));
        assertEquals("RESERVED", r.status().name());
        assertEquals("FREQUENCY_LIMIT", service.evaluate("c2", "v1").reason());

        // 冷却窗口外但总额已满 → 预算
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 11_000L));
        service.createCampaign(campaignReq("c3", "promo", 1, 1, null, null, null));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c3", "v1"));
        assertNotNull(r3.reservationId());
        assertEquals("BUDGET_EXHAUSTED", service.evaluate("c3", "v1").reason());
    }
}
