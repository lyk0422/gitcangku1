package com.example.starter.exposure;

import com.example.starter.exposure.domain.CampaignCategory;
import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResultResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.QuietSettingsRequest;
import com.example.starter.exposure.web.QuietSettingsResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionStatsResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访客静默时段 H2（MODE=MySQL）测试：跨零点静默、抑制先于额度、类别/allowCritical
 * 裁决、抑制统计 UTC 日归属、设置版本冲突与幂等，以及设置修改只影响后续申请。
 */
@SpringBootTest
@ActiveProfiles("test")
class QuietHoursIntegrationTest {

    /** 可控时钟：固定起点，可随时设置。 */
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

    static final Instant BASE = Instant.parse("2026-09-22T12:00:00Z");

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
        jdbc.update("DELETE FROM suppression_stats");
        jdbc.update("DELETE FROM visitor_quiet_settings");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String campaignId, CampaignCategory category, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(
                "req-c-" + campaignId, campaignId, category, total, perVisitor));
    }

    /** 登记东零区、22:00～06:00 跨零点静默的访客。 */
    private QuietSettingsResponse registerCrossMidnightQuiet(String visitorId, boolean allowCritical) {
        return service.putQuietSettings(new QuietSettingsRequest(
                "qs-" + visitorId, visitorId, 0, 1320, 360, allowCritical, 0));
    }

    @Test
    @DisplayName("跨零点静默：本地 23:00 与本地 01:00 被抑制，静默结束 UTC 时刻分别为次日/当日 06:00")
    void crossMidnightWindow_suppressesWithCorrectQuietEndUtc() {
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        registerCrossMidnightQuiet("v1", false);

        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        ApplyResultResponse night = service.apply(new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals("SUPPRESSED", night.outcome().name());
        assertNull(night.reservation());
        assertEquals(Instant.parse("2026-09-23T06:00:00Z").toEpochMilli(), night.quietEndsAtUtc());

        mutableClock().setInstant(Instant.parse("2026-09-22T01:00:00Z"));
        ApplyResultResponse earlyMorning = service.apply(new ApplyExposureRequest("a2", "mkt", "v1"));
        assertEquals("SUPPRESSED", earlyMorning.outcome().name());
        assertEquals(Instant.parse("2026-09-22T06:00:00Z").toEpochMilli(),
                earlyMorning.quietEndsAtUtc());
    }

    @Test
    @DisplayName("区间边界：起始分钟含、结束分钟不含；区间外正常预占")
    void windowBoundaries_startInclusiveEndExclusive() {
        createCampaign("svc", CampaignCategory.SERVICE, 10, 10);
        registerCrossMidnightQuiet("v1", false);

        // 22:00 整（含）抑制
        mutableClock().setInstant(Instant.parse("2026-09-22T22:00:00Z"));
        assertEquals("SUPPRESSED",
                service.apply(new ApplyExposureRequest("a1", "svc", "v1")).outcome().name());

        // 06:00 整（不含）放行
        mutableClock().setInstant(Instant.parse("2026-09-22T06:00:00Z"));
        ApplyResultResponse atEnd = service.apply(new ApplyExposureRequest("a2", "svc", "v1"));
        assertEquals("RESERVED", atEnd.outcome().name());

        // 21:59 区间外放行
        mutableClock().setInstant(Instant.parse("2026-09-22T21:59:00Z"));
        assertEquals("RESERVED",
                service.apply(new ApplyExposureRequest("a3", "svc", "v1")).outcome().name());
    }

    @Test
    @DisplayName("非跨零点区间：10:00～12:00 内抑制，区间外放行，结束时刻为当日 12:00")
    void sameDayWindow_suppressesInsideOnly() {
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        service.putQuietSettings(new QuietSettingsRequest("qs-v1", "v1", 0, 600, 720, false, 0));

        mutableClock().setInstant(Instant.parse("2026-09-22T11:30:00Z"));
        ApplyResultResponse suppressed = service.apply(
                new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        assertEquals(Instant.parse("2026-09-22T12:00:00Z").toEpochMilli(),
                suppressed.quietEndsAtUtc());

        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:01Z"));
        assertEquals("RESERVED",
                service.apply(new ApplyExposureRequest("a2", "mkt", "v1")).outcome().name());
    }

    @Test
    @DisplayName("UTC 偏移换算：UTC+8 的本地凌晨按 UTC 前一日 20:00 结束，统计归属 UTC 日")
    void positiveOffset_localNight_suppressedAndStatsKeyedByUtcDay() {
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        // UTC+8，静默 22:00～06:00（本地）
        service.putQuietSettings(new QuietSettingsRequest("qs-v1", "v1", 480, 1320, 360, false, 0));

        // UTC 2026-09-22T20:00Z = 本地 09-23 04:00，落在静默区间
        mutableClock().setInstant(Instant.parse("2026-09-22T20:00:00Z"));
        ApplyResultResponse suppressed = service.apply(
                new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        // 静默结束 = 本地 09-23 06:00 = UTC 09-22 22:00
        assertEquals(Instant.parse("2026-09-22T22:00:00Z").toEpochMilli(),
                suppressed.quietEndsAtUtc());

        SuppressionStatsResponse onUtcDay = service.querySuppressionStats(
                "mkt", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(1L, onUtcDay.count());
        assertEquals(CampaignCategory.MARKETING, onUtcDay.category());
        assertEquals(0L, service.querySuppressionStats(
                "mkt", "v1", LocalDate.of(2026, 9, 23)).count());
    }

    @Test
    @DisplayName("负偏移：UTC-8 本地 22:00 抑制，结束为 UTC 次日 14:00")
    void negativeOffset_quietEndComputedAcrossUtcDay() {
        createCampaign("svc", CampaignCategory.SERVICE, 10, 10);
        // UTC-8，静默 22:00～06:00（本地）
        service.putQuietSettings(new QuietSettingsRequest("qs-v1", "v1", -480, 1320, 360, false, 0));

        // UTC 09-23 06:00 = 本地 09-22 22:00
        mutableClock().setInstant(Instant.parse("2026-09-23T06:00:00Z"));
        ApplyResultResponse suppressed = service.apply(
                new ApplyExposureRequest("a1", "svc", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        // 本地 09-23 06:00 = UTC 09-23 14:00
        assertEquals(Instant.parse("2026-09-23T14:00:00Z").toEpochMilli(),
                suppressed.quietEndsAtUtc());
    }

    @Test
    @DisplayName("类别裁决：静默内 CRITICAL 受 allowCritical 控制，SERVICE/MARKETING 一律抑制")
    void categoryAndAllowCritical_decideSuppression() {
        createCampaign("crit-yes", CampaignCategory.CRITICAL, 10, 10);
        createCampaign("crit-no", CampaignCategory.CRITICAL, 10, 10);
        createCampaign("svc", CampaignCategory.SERVICE, 10, 10);
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        service.putQuietSettings(new QuietSettingsRequest("qs-yes", "vYes", 0, 1320, 360, true, 0));
        service.putQuietSettings(new QuietSettingsRequest("qs-no", "vNo", 0, 1320, 360, false, 0));

        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));

        assertEquals("RESERVED", service.apply(new ApplyExposureRequest("a1", "crit-yes", "vYes"))
                .outcome().name());
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a2", "crit-no", "vNo"))
                .outcome().name());
        // allowCritical=true 也只放行 CRITICAL
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a3", "svc", "vYes"))
                .outcome().name());
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a4", "mkt", "vYes"))
                .outcome().name());
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a5", "svc", "vNo"))
                .outcome().name());
    }

    @Test
    @DisplayName("抑制先于额度：总额已满时静默申请返回 SUPPRESSED 而非 429，两级额度不变")
    void suppressionPrecedesQuota_no429AndLedgersUntouched() {
        createCampaign("mkt", CampaignCategory.MARKETING, 1, 10);
        registerCrossMidnightQuiet("v1", false);

        // v2 未登记静默，在静默时刻占掉唯一总额度
        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        assertEquals("RESERVED", service.apply(new ApplyExposureRequest("a0", "mkt", "v2"))
                .outcome().name());

        // v1 在静默时段申请：即便总额已满也抑制，不报 429
        ApplyResultResponse suppressed = service.apply(
                new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        assertNotNull(suppressed.quietEndsAtUtc());

        QuotaResponse total = service.queryQuota("mkt", null, LocalDate.of(2026, 9, 22));
        assertEquals(1, total.usedTotal(), "总额度仍只被 v2 占用一次");
        assertEquals(0, service.queryQuota("mkt", "v1", LocalDate.of(2026, 9, 22)).usedVisitor(),
                "被抑制访客不留访客账目");

        // 不留预占痕迹
        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE visitor_id = 'v1'", Integer.class);
        assertEquals(0, reservations);
    }

    @Test
    @DisplayName("静默结束、额度未满后同一访客可正常预占并占用额度")
    void afterQuietEnd_applyReservesAndOccupiesQuota() {
        createCampaign("svc", CampaignCategory.SERVICE, 2, 5);
        registerCrossMidnightQuiet("v1", false);

        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a1", "svc", "v1"))
                .outcome().name());

        mutableClock().setInstant(Instant.parse("2026-09-22T06:00:00Z"));
        ApplyResultResponse reserved = service.apply(new ApplyExposureRequest("a2", "svc", "v1"));
        assertEquals("RESERVED", reserved.outcome().name());
        assertEquals(1, service.queryQuota("svc", null, LocalDate.of(2026, 9, 22)).usedTotal());
        assertEquals(1, service.queryQuota("svc", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
    }

    @Test
    @DisplayName("抑制次数按公告、访客与 UTC 日累计；幂等重放不重复累计")
    void suppressionCount_accumulatesAndIdempotentReplayDoesNotDoubleCount() {
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        registerCrossMidnightQuiet("v1", false);
        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));

        ApplyResultResponse first = service.apply(
                new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals("SUPPRESSED", first.outcome().name());
        // 不同 requestId 的两次抑制
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a2", "mkt", "v1"))
                .outcome().name());
        // 同 requestId 同参重放：首次结果，不重复累计
        ApplyResultResponse replay = service.apply(
                new ApplyExposureRequest("a1", "mkt", "v1"));
        assertEquals(first.quietEndsAtUtc(), replay.quietEndsAtUtc());

        assertEquals(2L, service.querySuppressionStats(
                "mkt", "v1", LocalDate.of(2026, 9, 22)).count());

        // 同键异参 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("a1", "mkt", "v2")));
        assertEquals(409, conflict.getStatus().value());
    }

    @Test
    @DisplayName("静默设置：首次版本为 1；expectedVersion 冲突 409；同键重放不产生新版本")
    void quietSettings_versioningConflictAndIdempotentReplay() {
        QuietSettingsResponse created = service.putQuietSettings(
                new QuietSettingsRequest("qs-1", "v1", 0, 1320, 360, false, 0));
        assertEquals(1, created.version());

        // 同键同参重放：返回首次结果，版本仍为 1
        QuietSettingsResponse replay = service.putQuietSettings(
                new QuietSettingsRequest("qs-1", "v1", 0, 1320, 360, false, 0));
        assertEquals(1, replay.version());

        // 过期 expectedVersion 修改 → 409
        ApiException stale = assertThrows(ApiException.class, () -> service.putQuietSettings(
                new QuietSettingsRequest("qs-stale", "v1", 0, 1320, 420, false, 0)));
        assertEquals(409, stale.getStatus().value());

        // 未登记访客传非 0 版本 → 409
        ApiException noRow = assertThrows(ApiException.class, () -> service.putQuietSettings(
                new QuietSettingsRequest("qs-new", "vX", 0, 1320, 420, false, 1)));
        assertEquals(409, noRow.getStatus().value());

        // 正确版本修改 → 版本 2
        QuietSettingsResponse updated = service.putQuietSettings(
                new QuietSettingsRequest("qs-2", "v1", 0, 1320, 420, true, 1));
        assertEquals(2, updated.version());
        assertEquals(420, updated.quietEndMinute());
        assertTrue(updated.allowCritical());

        QuietSettingsResponse fetched = service.getQuietSettings("v1");
        assertEquals(2, fetched.version());

        assertEquals(404, assertThrows(ApiException.class, () -> service.getQuietSettings("nobody"))
                .getStatus().value());
    }

    @Test
    @DisplayName("起止相同的静默区间 400；越界偏移由参数校验 400")
    void invalidQuietWindow_rejected() {
        ApiException same = assertThrows(ApiException.class, () -> service.putQuietSettings(
                new QuietSettingsRequest("qs-bad", "v1", 0, 600, 600, false, 0)));
        assertEquals(400, same.getStatus().value());
    }

    @Test
    @DisplayName("设置修改只影响后续申请：历史 RESERVED 仍可确认；确认不受静默影响")
    void settingsChange_onlyAffectsFutureApplications() {
        createCampaign("svc", CampaignCategory.SERVICE, 10, 10);

        // 无静默时申请，得到 RESERVED
        ReservationResponse reservation = service.apply(
                new ApplyExposureRequest("a1", "svc", "v1")).reservation();

        // 随后登记静默并把时钟拨入静默区间（仍在 60 秒预占有效期内）
        registerCrossMidnightQuiet("v1", false);
        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:30Z"));

        // 历史预占仍可确认，不被静默拦截，账目保持占用
        ReservationResponse confirmed = service.confirm(reservation.reservationId(),
                new ReservationActionRequest("k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, service.queryQuota("svc", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());

        // 时钟拨到夜间：新申请才被抑制
        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a2", "svc", "v1"))
                .outcome().name());
    }

    @Test
    @DisplayName("静默时段内历史 RESERVED 仍可取消并释放额度、可被过期结算")
    void existingReservation_cancelAndExpiryWorkDuringQuietHours() {
        createCampaign("svc", CampaignCategory.SERVICE, 1, 1);
        ReservationResponse reservation = service.apply(
                new ApplyExposureRequest("a1", "svc", "v1")).reservation();

        // 申请后再登记覆盖 12:00 的静默区间（本地 11:00～13:00），只影响后续申请
        service.putQuietSettings(new QuietSettingsRequest("qs-v1", "v1", 0, 660, 780, false, 0));
        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:30Z"));

        // 历史预占仍在 60 秒有效期内：静默时段内取消照常并释放额度
        ReservationResponse cancelled = service.cancel(reservation.reservationId(),
                new ReservationActionRequest("x1"));
        assertEquals("CANCELLED", cancelled.status().name());
        assertEquals(0, service.queryQuota("svc", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
        // 取消释放后，静默时段内新申请仍被抑制而不会复用额度
        assertEquals("SUPPRESSED", service.apply(new ApplyExposureRequest("a2", "svc", "v1"))
                .outcome().name());

        // 过期结算路径：静默结束（13:00 不含）后再占一单，拨到夜间，查询触发过期结算并释放
        mutableClock().setInstant(Instant.parse("2026-09-22T13:00:00Z"));
        ReservationResponse second = service.apply(
                new ApplyExposureRequest("a3", "svc", "v1")).reservation();
        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        ReservationResponse detail = service.getReservation(second.reservationId());
        assertEquals("EXPIRED", detail.status().name());
        assertEquals(0, service.queryQuota("svc", "v1", LocalDate.of(2026, 9, 22)).usedTotal());
    }

    @Test
    @DisplayName("未登记访客无静默：任何类别任何时刻照常预占")
    void unregisteredVisitor_neverSuppressed() {
        createCampaign("mkt", CampaignCategory.MARKETING, 10, 10);
        mutableClock().setInstant(Instant.parse("2026-09-22T23:00:00Z"));
        assertEquals("RESERVED", service.apply(new ApplyExposureRequest("a1", "mkt", "ghost"))
                .outcome().name());
    }

    @Test
    @DisplayName("并发修改同一访客设置：同一期望版本只有一个成功，其余 409，版本恰好 +1")
    void concurrentSettingsUpdate_singleWinnerByVersion() throws Exception {
        registerCrossMidnightQuiet("v1", false);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.putQuietSettings(new QuietSettingsRequest(
                            "qs-c-" + idx, "v1", 0, 1320, 300 + idx, idx % 2 == 0, 1));
                    ok.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, ok.get(), "同一版本只允许一个修改成功");
        assertEquals(threads - 1, conflicts.get(), "其余修改必须版本冲突 409");
        assertEquals(2, service.getQuietSettings("v1").version(), "版本恰好增加一次");
    }
}
