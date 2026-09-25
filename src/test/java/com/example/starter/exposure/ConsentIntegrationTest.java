package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SubmitConsentRequest;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 曝光同意版本 + 频控联合裁决 H2（MODE=MySQL）集成测试：
 * 覆盖同意版本裁决、左闭右开时间窗口、静默时段、频控账目、撤回固化、
 * 类别修改不迁移、批量最终账目回滚与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-09-26T10:00:00Z");
    static final long T0 = BASE.toEpochMilli();
    static final LocalDate DAY = LocalDate.of(2026, 9, 26);

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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM visitor_consent");
        jdbc.update("DELETE FROM consent_mutex");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 辅助构造 ----

    private CreateCampaignRequest categorizedCampaign(String reqId, String campaignId,
                                                      int total, int perVisitor, String category) {
        return new CreateCampaignRequest(reqId, campaignId, total, perVisitor,
                category, null, null);
    }

    private CreateCampaignRequest silentCampaign(String reqId, String campaignId,
                                                 int total, int perVisitor,
                                                 String category, Integer start, Integer end) {
        return new CreateCampaignRequest(reqId, campaignId, total, perVisitor, category, start, end);
    }

    private SubmitConsentRequest consent(String reqId, String visitor, String category,
                                         String decision, int version, Long start, Long end) {
        return new SubmitConsentRequest(reqId, visitor, category, decision, version, start, end);
    }

    private ApiException expectFailure(int httpStatus, String code, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(httpStatus, ex.getStatus().value(),
                "HTTP 状态应为 " + httpStatus + "，实际信息：" + ex.getMessage());
        assertEquals(code, ex.getCode(), "错误码应为 " + code);
        return ex;
    }

    private int usedTotal(String campaignId) {
        return service.queryQuota(campaignId, null, DAY).usedTotal();
    }

    private int usedVisitor(String campaignId, String visitorId) {
        return service.queryQuota(campaignId, visitorId, DAY).usedVisitor();
    }

    // ---- 同意版本与时间窗口 ----

    @Test
    @DisplayName("ALLOW 区间内预占成功并固化同意决定与版本快照")
    void allowWithinInterval_createsReservationWithSnapshot() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-allow", "v1", "news", "ALLOW", 1, T0, null));

        ReservationResponse r = service.apply(new ApplyExposureRequest("k-apply", "c1", "v1"));

        assertEquals("RESERVED", r.status().name());
        assertEquals("ALLOW", r.consentDecision());
        assertEquals(1, r.consentVersion());
        assertEquals(1, usedTotal("c1"));
        assertEquals(1, usedVisitor("c1", "v1"));

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("ALLOW", detail.consentDecision());
        assertEquals(1, detail.consentVersion());
    }

    @Test
    @DisplayName("缺少 ALLOW 同意返回 CONSENT_DENIED(403)，不创建预占且不扣频次预算")
    void missingAllow_returnsConsentDeniedAndNoLedgerChange() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));

        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-apply", "c1", "v1")));

        assertEquals(0, usedTotal("c1"));
        assertEquals(0, usedVisitor("c1", "v1"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class).intValue());
    }

    @Test
    @DisplayName("生效区间左闭右开：恰在起点生效，恰在终点不再生效")
    void interval_isLeftClosedRightOpen() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-allow", "v1", "news", "ALLOW", 1, T0, T0 + 1000L));

        // 恰在起点：生效
        ReservationResponse atStart = service.apply(
                new ApplyExposureRequest("k-at-start", "c1", "v1"));
        assertEquals("ALLOW", atStart.consentDecision());

        // 恰在终点：区间已失效，且没有其他 ALLOW → CONSENT_DENIED
        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 1000L));
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-at-end", "c1", "v1")));
        assertEquals(1, usedTotal("c1"), "被拒绝申请不得占用预算");
    }

    @Test
    @DisplayName("终点不晚于起点返回 400 CONSENT_INVALID_RANGE")
    void invalidRange_returns400() {
        expectFailure(400, "CONSENT_INVALID_RANGE", () -> service.submitConsent(
                consent("k-bad", "v1", "news", "ALLOW", 1, T0 + 100L, T0)));
        expectFailure(400, "CONSENT_INVALID_RANGE", () -> service.submitConsent(
                consent("k-bad2", "v1", "news", "ALLOW", 1, T0, T0)));
    }

    @Test
    @DisplayName("高版本 DENY 在重叠时刻覆盖低版本 ALLOW；非重叠时刻仍按 ALLOW")
    void higherVersionDeny_overridesLowerAllow() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));
        service.submitConsent(consent("k-d2", "v1", "news", "DENY", 2,
                T0 + 10_000L, T0 + 20_000L));

        ReservationResponse before = service.apply(
                new ApplyExposureRequest("k-before", "c1", "v1"));
        assertEquals("ALLOW", before.consentDecision());
        assertEquals(1, before.consentVersion());

        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 15_000L));
        ApiException denied = expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-during", "c1", "v1")));
        assertNotNull(denied.getMessage());

        mutableClock().setInstant(Instant.ofEpochMilli(T0 + 20_000L));
        ReservationResponse after = service.apply(
                new ApplyExposureRequest("k-after", "c1", "v1"));
        assertEquals("ALLOW", after.consentDecision());
        assertEquals(1, after.consentVersion());

        // 两次成功预占，DENY 时段的失败不扣预算
        assertEquals(2, usedTotal("c1"));
    }

    @Test
    @DisplayName("同版本有效区间重叠被拒 CONSENT_CONFLICT(409)；DENY 不可被低版本 ALLOW 覆盖")
    void sameVersionOverlapAndLowerVersionAllowOverDeny_areRejected() {
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, T0 + 10_000L));

        // 同版本重叠
        expectFailure(409, "CONSENT_CONFLICT", () -> service.submitConsent(
                consent("k-a1-overlap", "v1", "news", "ALLOW", 1,
                        T0 + 5_000L, T0 + 20_000L)));

        // 同版本但不重叠：允许
        service.submitConsent(consent("k-a1-adjacent", "v1", "news", "ALLOW", 1,
                T0 + 10_000L, T0 + 20_000L));

        // 高版本 DENY
        service.submitConsent(consent("k-d3", "v1", "news", "DENY", 3,
                T0, T0 + 100_000L));
        // 低版本 ALLOW 试图覆盖高版本 DENY：拒绝
        expectFailure(409, "CONSENT_CONFLICT", () -> service.submitConsent(
                consent("k-a2", "v1", "news", "ALLOW", 2, T0 + 1_000L, T0 + 2_000L)));
        // 更高版本 ALLOW 可以覆盖
        service.submitConsent(consent("k-a4", "v1", "news", "ALLOW", 4,
                T0 + 1_000L, T0 + 2_000L));

        List<ConsentResponse> intervals = service.queryConsents("v1", "news");
        assertEquals(4, intervals.size());
    }

    @Test
    @DisplayName("同意按访客+类别独立裁决：同类别的不同访客与不同类别互不影响")
    void consent_scopedByVisitorAndCategory() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a", "v1", "news", "ALLOW", 1, T0, null));

        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-other-visitor", "c1", "v2")));
        ReservationResponse ok = service.apply(
                new ApplyExposureRequest("k-ok", "c1", "v1"));
        assertEquals("ALLOW", ok.consentDecision());

        // 无类别公告（历史语义）不校验同意
        service.createCampaign(new CreateCampaignRequest("req-c2", "c2", 10, 5));
        ReservationResponse legacy = service.apply(
                new ApplyExposureRequest("k-legacy", "c2", "nobody"));
        assertNull(legacy.consentDecision());
        assertNull(legacy.consentVersion());
    }

    // ---- 静默时段 ----

    @Test
    @DisplayName("静默时段内返回 SILENT_HOURS 且不扣预算；窗口外正常；跨午夜窗口两段生效")
    void silentHours_blockedInside_allowedOutside() {
        // 10:00-11:00（UTC 分钟 600-660）静默；BASE 恰为 10:00（含起点）
        service.createCampaign(silentCampaign("req-c", "c1", 10, 5, "news", 600, 660));
        service.submitConsent(consent("k-a", "v1", "news", "ALLOW", 1, T0, null));

        expectFailure(429, "SILENT_HOURS",
                () -> service.apply(new ApplyExposureRequest("k-silent", "c1", "v1")));
        assertEquals(0, usedTotal("c1"));

        // 恰在终点（11:00）静默解除
        mutableClock().setInstant(Instant.parse("2026-09-26T11:00:00Z"));
        ReservationResponse ok = service.apply(
                new ApplyExposureRequest("k-ok", "c1", "v1"));
        assertEquals("RESERVED", ok.status().name());
        assertEquals(1, usedTotal("c1"));

        // 跨午夜窗口 23:00-01:00（1380 -> 60）：23:30 命中，02:00 不命中
        service.createCampaign(silentCampaign("req-c2", "c2", 10, 5, "news", 1380, 60));
        // 同意区间覆盖当日全天（版本 2，与既有 v1 区间允许重叠），保证凌晨场景裁决到静默而非缺同意
        service.submitConsent(consent("k-a2", "v1", "news", "ALLOW", 2,
                Instant.parse("2026-09-26T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-09-27T00:00:00Z").toEpochMilli()));
        mutableClock().setInstant(Instant.parse("2026-09-26T23:30:00Z"));
        expectFailure(429, "SILENT_HOURS",
                () -> service.apply(new ApplyExposureRequest("k-night", "c2", "v1")));
        mutableClock().setInstant(Instant.parse("2026-09-26T00:30:00Z"));
        expectFailure(429, "SILENT_HOURS",
                () -> service.apply(new ApplyExposureRequest("k-midnight", "c2", "v1")));
        mutableClock().setInstant(Instant.parse("2026-09-26T02:00:00Z"));
        assertEquals("RESERVED", service.apply(
                new ApplyExposureRequest("k-morning", "c2", "v1")).status().name());
    }

    // ---- 撤回与固化 ----

    @Test
    @DisplayName("同意撤回只影响之后的预占；撤回前预占按固化版本确认，快照不变")
    void withdraw_affectsOnlyFutureReservations_snapshotStaysFixed() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        ConsentResponse consent = service.submitConsent(
                consent("k-a", "v1", "news", "ALLOW", 1, T0, null));
        ReservationResponse first = service.apply(
                new ApplyExposureRequest("k-first", "c1", "v1"));
        assertEquals("ALLOW", first.consentDecision());

        ConsentResponse withdrawn = service.withdrawConsent(consent.consentId(),
                new WithdrawConsentRequest("k-wd"));
        assertEquals(Long.valueOf(T0), withdrawn.effectiveEndUtc(), "生效中区间终点截断为撤回时刻");

        // 撤回后新申请被拒，不扣预算
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-second", "c1", "v1")));
        assertEquals(1, usedTotal("c1"));

        // 撤回前预占仍可确认，同意快照保持 ALLOW v1
        ReservationResponse confirmed = service.confirm(first.reservationId(),
                new ReservationActionRequest("k-confirm"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals("ALLOW", confirmed.consentDecision());
        assertEquals(1, confirmed.consentVersion());

        // 重复撤回（幂等重放）返回同一截断结果；对已过期区间撤回返回 409
        ConsentResponse replay = service.withdrawConsent(consent.consentId(),
                new WithdrawConsentRequest("k-wd"));
        assertEquals(Long.valueOf(T0), replay.effectiveEndUtc());
    }

    @Test
    @DisplayName("撤回尚未生效的区间直接删除；该区间查询不再返回")
    void withdraw_futureInterval_isDeleted() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        ConsentResponse future = service.submitConsent(consent(
                "k-future", "v1", "news", "ALLOW", 3, T0 + 100_000L, T0 + 200_000L));
        service.withdrawConsent(future.consentId(), new WithdrawConsentRequest("k-wd"));
        assertEquals(0, service.queryConsents("v1", "news").size());

        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-apply", "c1", "v1")));
    }

    // ---- 类别修改 ----

    @Test
    @DisplayName("类别修改版本+1且不迁移旧同意；旧预占快照保留；同键在版本变更后异参 409")
    void categoryChange_bumpsVersion_noConsentMigration() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a", "v1", "news", "ALLOW", 1, T0, null));
        ReservationResponse oldReservation = service.apply(
                new ApplyExposureRequest("k-old", "c1", "v1"));

        var updated = service.updateCategory("c1",
                new UpdateCategoryRequest("k-cat", "c1", "promo"));
        assertEquals(2, updated.version());
        assertEquals("promo", updated.category());

        // 新类别没有同意 → CONSENT_DENIED，旧 news 同意不迁移
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-new", "c1", "v1")));

        // 旧预占的同意快照不变，仍可结算
        ReservationResponse detail = service.getReservation(oldReservation.reservationId());
        assertEquals("ALLOW", detail.consentDecision());
        assertEquals(1, detail.consentVersion());

        // 原申请键在活动版本变化后重放 → 异参 409
        expectFailure(409, "CONFLICT",
                () -> service.apply(new ApplyExposureRequest("k-old", "c1", "v1")));

        // 提交 promo 同意后申请成功，快照按新类别版本
        service.submitConsent(consent("k-ap", "v1", "promo", "ALLOW", 7, T0, null));
        ReservationResponse after = service.apply(
                new ApplyExposureRequest("k-after", "c1", "v1"));
        assertEquals("ALLOW", after.consentDecision());
        assertEquals(7, after.consentVersion());
    }

    // ---- 批量预占 ----

    @Test
    @DisplayName("批量预占全部成功：按入参顺序返回并占用两级账目")
    void batchApply_allSucceed() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));
        service.submitConsent(consent("k-a2", "v2", "news", "ALLOW", 1, T0, null));

        var response = service.batchApply(new BatchApplyRequest("k-batch", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v2"),
                new BatchApplyRequest.BatchApplyItem("c1", "v1"))));

        assertEquals(3, response.reservations().size());
        assertEquals(List.of("v1", "v2", "v1"),
                response.reservations().stream().map(ReservationResponse::visitorId).toList());
        assertEquals(3, usedTotal("c1"));
        assertEquals(2, usedVisitor("c1", "v1"));
        assertEquals(1, usedVisitor("c1", "v2"));
        response.reservations().forEach(r -> {
            assertEquals("ALLOW", r.consentDecision());
            assertEquals(1, r.consentVersion());
        });
    }

    @Test
    @DisplayName("批量按最终账目预校验：访客上限在批内被突破时整批回滚，不留任何账目与预占")
    void batchApply_finalVisitorLedgerExceeded_rollsBackEverything() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 1, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));
        service.submitConsent(consent("k-a2", "v2", "news", "ALLOW", 1, T0, null));

        expectFailure(429, "QUOTA_EXHAUSTED", () -> service.batchApply(new BatchApplyRequest(
                "k-batch-fail", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v2"),
                new BatchApplyRequest.BatchApplyItem("c1", "v2")))));

        assertEquals(0, usedTotal("c1"), "整批回滚后总预算不得被占用");
        assertEquals(0, usedVisitor("c1", "v1"));
        assertEquals(0, usedVisitor("c1", "v2"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class).intValue());
    }

    @Test
    @DisplayName("批量中任一访客缺 ALLOW/命中 DENY 或静默：整批 CONSENT_DENIED/SILENT 回滚")
    void batchApply_oneDeniedOrSilent_rollsBackWholeBatch() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));
        // v2 无同意；v3 明确 DENY
        service.submitConsent(consent("k-d3", "v3", "news", "DENY", 1, T0, null));

        expectFailure(403, "CONSENT_DENIED", () -> service.batchApply(new BatchApplyRequest(
                "k-batch-missing", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v2")))));
        assertEquals(0, usedTotal("c1"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class).intValue());

        expectFailure(403, "CONSENT_DENIED", () -> service.batchApply(new BatchApplyRequest(
                "k-batch-deny", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v3")))));
        assertEquals(0, usedTotal("c1"), "DENY 场景同样不得留下 v1 的半成品预占");

        // 静默公告参与批量：SILENT_HOURS，整批回滚
        service.createCampaign(silentCampaign("req-cs", "cs", 10, 5, "news", 600, 660));
        expectFailure(429, "SILENT_HOURS", () -> service.batchApply(new BatchApplyRequest(
                "k-batch-silent", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("cs", "v1")))));
        assertEquals(0, usedTotal("c1"));
        assertEquals(0, usedTotal("cs"));
    }

    @Test
    @DisplayName("批量最终总预算不足：占用其他公告的批次也全部回滚")
    void batchApply_totalCapAcrossCampaigns_rollsBack() {
        service.createCampaign(categorizedCampaign("req-c1", "c1", 1, 5, "news"));
        service.createCampaign(categorizedCampaign("req-c2", "c2", 10, 5, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));
        service.submitConsent(consent("k-a2", "v2", "news", "ALLOW", 1, T0, null));

        expectFailure(429, "QUOTA_EXHAUSTED", () -> service.batchApply(new BatchApplyRequest(
                "k-batch-total", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v2"),
                new BatchApplyRequest.BatchApplyItem("c2", "v1")))));
        assertEquals(0, usedTotal("c1"));
        assertEquals(0, usedTotal("c2"));
    }

    // ---- 幂等 ----

    @Test
    @DisplayName("CONSENT_DENIED 失败不占键：补授权后同键同参成功，且不产生任何遗留账")
    void consentDenied_failureDoesNotOccupyKey() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));

        // 首次：无同意 → 403 CONSENT_DENIED
        expectFailure(403, "CONSENT_DENIED",
                () -> service.apply(new ApplyExposureRequest("k-apply", "c1", "v1")));
        assertEquals(0, usedTotal("c1"));

        // 补充 ALLOW 后，同一 requestKey + 同参数必须成功（失败未占键）
        service.submitConsent(consent("k-a", "v1", "news", "ALLOW", 1, T0, null));
        ReservationResponse r = service.apply(
                new ApplyExposureRequest("k-apply", "c1", "v1"));
        assertEquals("RESERVED", r.status().name());
        assertEquals("ALLOW", r.consentDecision());
        assertEquals(1, usedTotal("c1"));

        // 此时同键重放返回该成功预占
        assertEquals(r.reservationId(), service.apply(
                new ApplyExposureRequest("k-apply", "c1", "v1")).reservationId());
    }

    @Test
    @DisplayName("预占同键重放返回最初同意判定：撤回同意后重放仍返回原预占，账目不重复扣")
    void apply_sameKeyReplaysOriginalDecision_afterWithdraw() {        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        ConsentResponse consent = service.submitConsent(
                consent("k-a", "v1", "news", "ALLOW", 1, T0, null));
        ReservationResponse first = service.apply(
                new ApplyExposureRequest("k-apply", "c1", "v1"));

        service.withdrawConsent(consent.consentId(), new WithdrawConsentRequest("k-wd"));

        ReservationResponse replay = service.apply(
                new ApplyExposureRequest("k-apply", "c1", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals("ALLOW", replay.consentDecision());
        assertEquals(1, replay.consentVersion());
        assertEquals(1, usedTotal("c1"), "重放不重复扣预算");
    }

    @Test
    @DisplayName("同意提交/撤回同键重放原结果，异参 409；失败不占键")
    void consent_idempotentReplay_conflictOnDifferentParams_failureDoesNotOccupy() {
        ConsentResponse first = service.submitConsent(
                consent("k-consent", "v1", "news", "ALLOW", 1, T0, null));
        ConsentResponse replay = service.submitConsent(
                consent("k-consent", "v1", "news", "ALLOW", 1, T0, null));
        assertEquals(first.consentId(), replay.consentId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM visitor_consent", Integer.class).intValue());

        expectFailure(409, "CONFLICT", () -> service.submitConsent(
                consent("k-consent", "v1", "news", "ALLOW", 2, T0, null)));
        // 同键用于其他操作也 409
        expectFailure(409, "CONFLICT",
                () -> service.withdrawConsent(first.consentId(),
                        new WithdrawConsentRequest("k-consent")));

        // 失败的提交（同版本重叠）不占键：同一 requestKey 改为合法区间后成功
        service.submitConsent(consent("k-other", "v9", "news", "ALLOW", 1, T0, T0 + 1000L));
        expectFailure(409, "CONSENT_CONFLICT", () -> service.submitConsent(
                consent("k-fail", "v9", "news", "ALLOW", 1, T0 + 500L, T0 + 2000L)));
        ConsentResponse retried = service.submitConsent(
                consent("k-fail", "v9", "news", "ALLOW", 2, T0 + 500L, T0 + 2000L));
        assertEquals(2, retried.consentVersion());
    }

    @Test
    @DisplayName("批量预占同键重放返回最初结果；异参 409")
    void batchApply_idempotentReplay() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 10, 5, "news"));
        service.submitConsent(consent("k-a1", "v1", "news", "ALLOW", 1, T0, null));

        var first = service.batchApply(new BatchApplyRequest("k-b", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"))));
        var replay = service.batchApply(new BatchApplyRequest("k-b", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"))));
        assertEquals(first.reservations().get(0).reservationId(),
                replay.reservations().get(0).reservationId());
        assertEquals(1, usedTotal("c1"));

        expectFailure(409, "CONFLICT", () -> service.batchApply(new BatchApplyRequest("k-b", List.of(
                new BatchApplyRequest.BatchApplyItem("c1", "v1"),
                new BatchApplyRequest.BatchApplyItem("c1", "v1")))));
    }

    // ---- 查询与 HTTP 语义 ----

    @Test
    @DisplayName("查询同意区间返回全部版本；额度视图字段完整")
    void queryConsents_returnsAllIntervals() {
        service.submitConsent(consent("k-1", "v1", "news", "ALLOW", 1, T0, T0 + 1000L));
        service.submitConsent(consent("k-2", "v1", "news", "DENY", 2, T0 + 2000L, null));

        List<ConsentResponse> intervals = service.queryConsents("v1", "news");
        assertEquals(2, intervals.size());
        assertEquals("ALLOW", intervals.get(0).decision());
        assertEquals("DENY", intervals.get(1).decision());
        assertNull(intervals.get(1).effectiveEndUtc());
        assertEquals(0, service.queryConsents("v1", "promo").size());
    }

    @Test
    @DisplayName("HTTP：CONSENT_DENIED 与 SILENT_HOURS 返回可区分错误码；批量失败同样可区分")
    void http_distinguishableErrorCodes() throws Exception {
        // 建带类别公告但不提交同意
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-c\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":5,\"category\":\"news\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.category").value("news"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-r\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("CONSENT_DENIED"));

        // 静默时段公告：先给 ALLOW，再在静默窗口内申请 → SILENT_HOURS
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cs\",\"campaignId\":\"cs\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":5,\"category\":\"news\","
                                + "\"silentStartMinute\":600,\"silentEndMinute\":660}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-a\",\"visitorId\":\"u1\",\"category\":\"news\","
                                + "\"decision\":\"ALLOW\",\"consentVersion\":1,"
                                + "\"effectiveStartUtc\":" + T0 + ",\"effectiveEndUtc\":null}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-rs\",\"campaignId\":\"cs\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SILENT_HOURS"));

        // 同意区间查询
        mockMvc.perform(get("/api/exposure/consents")
                        .param("visitorId", "u1")
                        .param("category", "news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decision").value("ALLOW"));
    }

    @Test
    @DisplayName("裁决预览：返回首个拒绝原因且只读；拒绝原因随同意/撤回与预算状态变化")
    void decisionPreview_reportsFirstReason_withoutSideEffects() {
        service.createCampaign(categorizedCampaign("req-c", "c1", 1, 5, "news"));

        // 初始：缺 ALLOW
        var missing = service.previewDecision("c1", "v1");
        assertEquals(false, missing.allowed());
        assertEquals("CONSENT_DENIED", missing.reason());
        assertEquals("news", missing.category());
        assertEquals(1, missing.campaignVersion());
        assertEquals(0, usedTotal("c1"), "预览不得创建预占或扣账");

        // 提交 ALLOW 后：ALLOWED
        service.submitConsent(consent("k-a", "v1", "news", "ALLOW", 1, T0, null));
        var allowed = service.previewDecision("c1", "v1");
        assertEquals(true, allowed.allowed());
        assertEquals("ALLOWED", allowed.reason());
        assertEquals("ALLOW", allowed.consentDecision());
        assertEquals(1, allowed.consentVersion());
        assertEquals(0, usedTotal("c1"));

        // 真实占用掉唯一总预算后：预览给 QUOTA_EXHAUSTED
        service.apply(new ApplyExposureRequest("k-real", "c1", "v1"));
        var exhausted = service.previewDecision("c1", "v1");
        assertEquals(false, exhausted.allowed());
        assertEquals("QUOTA_EXHAUSTED", exhausted.reason());
        assertEquals(1, exhausted.usedTotal());

        // 撤回同意后：首个原因回到 CONSENT_DENIED（裁决顺序优先于预算）
        var consent = service.queryConsents("v1", "news").get(0);
        service.withdrawConsent(consent.consentId(), new WithdrawConsentRequest("k-wd"));
        var withdrawn = service.previewDecision("c1", "v1");
        assertEquals("CONSENT_DENIED", withdrawn.reason());

        // 静默优先于预算、排在同意之后：给 v2 ALLOW + 静默公告，原因 SILENT_HOURS
        service.createCampaign(silentCampaign("req-cs", "cs", 1, 5, "news", 600, 660));
        service.submitConsent(consent("k-as", "v2", "news", "ALLOW", 1, T0, null));
        var silent = service.previewDecision("cs", "v2");
        assertEquals(false, silent.allowed());
        assertEquals("SILENT_HOURS", silent.reason());

        // HTTP：预览接口返回原因码
        try {
            mockMvc.perform(get("/api/exposure/campaigns/c1/decision").param("visitorId", "v1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reason").value("CONSENT_DENIED"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
