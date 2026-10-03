package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityHoursResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignActivityRequest;
import com.example.starter.exposure.web.CampaignDetailResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySettlementResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * 活动匀速投放 H2（MODE=MySQL）集成测试：小时预算原子预占、三侧结算、
 * 配置版本与已开始小时保护、迁移固化、日结快照、幂等与并发不超卖。
 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityPacingIntegrationTest {

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

    /** 固定于 UTC 10:00，当前小时为 10。 */
    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

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
    Clock clock;
    @Autowired
    org.springframework.test.web.servlet.MockMvc mockMvc;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 构造 24 小时预算：hourBudgetPairs 按 (小时, 预算) 成对给出，其余小时为 0。 */
    private static List<Integer> budgets(int... hourBudgetPairs) {
        List<Integer> budgets = new ArrayList<>(Collections.nCopies(24, 0));
        for (int i = 0; i < hourBudgetPairs.length; i += 2) {
            budgets.set(hourBudgetPairs[i], hourBudgetPairs[i + 1]);
        }
        return budgets;
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private ActivityHoursResponse.HourEntry hourEntry(String activityId, LocalDate date, int hour) {
        return service.queryActivityHours(activityId, date).hours().get(hour);
    }

    @Test
    @DisplayName("创建活动：预算合计必须等于日总额，差额入错误明细；重复创建 409")
    void createActivity_validatesBudgetSum() {
        ApiException bad = assertThrows(ApiException.class, () -> service.createActivity(
                new CreateActivityRequest("req-act-x", "act-x", 3, budgets(10, 2, 11, 2))));
        assertEquals(400, bad.getStatus().value());
        assertEquals("HOURLY_BUDGET_SUM_MISMATCH", bad.getDetails().get("reason"));
        assertEquals(3, bad.getDetails().get("dailyConfirmCap"));
        assertEquals(4L, bad.getDetails().get("hourlyBudgetSum"));
        assertEquals(1L, bad.getDetails().get("difference"));

        ActivityResponse created = service.createActivity(
                new CreateActivityRequest("req-act-1", "act-1", 3, budgets(10, 2, 11, 1)));
        assertEquals("act-1", created.activityId());
        assertEquals(3, created.dailyConfirmCap());
        assertEquals(1L, created.version());
        assertEquals(24, created.hourlyBudgets().size());
        assertEquals(2, created.hourlyBudgets().get(10));
        assertEquals(1, created.hourlyBudgets().get(11));
        assertEquals(0, created.hourlyBudgets().get(0));

        // 同键同参重放首次结果
        ActivityResponse replay = service.createActivity(
                new CreateActivityRequest("req-act-1", "act-1", 3, budgets(10, 2, 11, 1)));
        assertEquals(1L, replay.version());
        // 同键异参 409；新键重复活动编号 409
        assert409(() -> service.createActivity(
                new CreateActivityRequest("req-act-1", "act-1", 4, budgets(10, 4))));
        assert409(() -> service.createActivity(
                new CreateActivityRequest("req-act-2", "act-1", 3, budgets(10, 3))));
    }

    @Test
    @DisplayName("未配置小时预算的活动：归入公告不得申请，公告与访客额度不消耗")
    void apply_withoutHourlyBudget_rejected() {
        ActivityResponse unconfigured = service.createActivity(
                new CreateActivityRequest("req-act-1", "act-1", 5, null));
        assertNull(unconfigured.hourlyBudgets());

        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));
        assertEquals(409, ex.getStatus().value());
        assertEquals("ACTIVITY_HOURLY_BUDGET_NOT_CONFIGURED", ex.getDetails().get("reason"));

        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
    }
    @Test
    @DisplayName("当前小时原子预占：跨公告共享小时预算，耗尽 429 给出下一可用小时且不消耗公告/访客名额")
    void apply_reservesActivityHourAtomically() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 3,
                budgets(10, 1, 11, 1, 12, 1)));
        createCampaign("req-c1", "c1", 100, 100);
        createCampaign("req-c2", "c2", 100, 100);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        service.assignCampaignActivity("c2", new AssignActivityRequest("req-m2", "act-1"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act-1", r1.activityId());
        assertEquals(10, r1.hourOfDay());
        assertEquals(DAY, r1.utcDate());

        ActivityHoursResponse.HourEntry hour10 = hourEntry("act-1", DAY, 10);
        assertEquals(1, hour10.budget());
        assertEquals(1, hour10.reserved());
        assertEquals(0, hour10.remaining());

        // 另一公告同一小时申请：小时预算耗尽 429，明细给出下一可用小时 11
        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "c2", "v2")));
        assertEquals(429, ex.getStatus().value());
        Map<String, Object> details = ex.getDetails();
        assertEquals("ACTIVITY_HOUR_BUDGET_EXHAUSTED", details.get("reason"));
        assertEquals("act-1", details.get("activityId"));
        assertEquals(10, details.get("hourOfDay"));
        assertEquals(1, details.get("hourBudget"));
        assertEquals(1, details.get("hourUsed"));
        assertEquals(1, details.get("requested"));
        assertEquals(DAY.toString(), details.get("nextAvailableUtcDate"));
        assertEquals(11, details.get("nextAvailableHourOfDay"));

        // 429 不消耗公告或访客侧名额
        assertEquals(0, service.queryQuota("c2", "v2", DAY).usedTotal());
        assertEquals(0, service.queryQuota("c2", "v2", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 推进到 11 点后可再申请
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a3", "c2", "v2"));
        assertEquals(11, r2.hourOfDay());
        assertEquals(1, hourEntry("act-1", DAY, 11).reserved());
    }

    @Test
    @DisplayName("确认同时结算三侧：活动小时占用转确认，公告/访客持续占用；重复终态不重复计数")
    void confirm_settlesActivitySide() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 2, budgets(10, 2)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());

        ActivityHoursResponse.HourEntry hour10 = hourEntry("act-1", DAY, 10);
        assertEquals(0, hour10.reserved());
        assertEquals(1, hour10.confirmed());
        assertEquals(0, hour10.cancelled());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());

        // 重复确认返回原状态，活动侧不重复结算
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(1, hourEntry("act-1", DAY, 10).confirmed());
    }

    @Test
    @DisplayName("取消与过期只释放同一小时未确认预占：释放数 +1，名额可再申请")
    void cancelAndExpire_releaseSameHourReservation() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 1, budgets(10, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ActivityHoursResponse.HourEntry afterCancel = hourEntry("act-1", DAY, 10);
        assertEquals(0, afterCancel.reserved());
        assertEquals(1, afterCancel.cancelled());
        // 重复取消不重复计数
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x2"));
        assertEquals(1, hourEntry("act-1", DAY, 10).cancelled());

        // 取消释放后同小时名额可再申请；第二单到期过期同样释放
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        mutableClock().advanceMillis(60_000L);
        assert409(() -> service.confirm(r2.reservationId(), new ReservationActionRequest("req-e1")));
        ActivityHoursResponse.HourEntry afterExpire = hourEntry("act-1", DAY, 10);
        assertEquals(0, afterExpire.reserved());
        assertEquals(2, afterExpire.cancelled());
        assertEquals(0, afterExpire.confirmed());
    }

    @Test
    @DisplayName("活动迁移只影响新预占：既有预占固化活动与小时，取消仍释放原活动账")
    void migration_onlyAffectsNewReservations() {
        service.createActivity(new CreateActivityRequest("req-act-a", "act-a", 1, budgets(10, 1)));
        service.createActivity(new CreateActivityRequest("req-act-b", "act-b", 1, budgets(10, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-a"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act-a", r1.activityId());

        // 迁移到 act-b：新预占占 act-b 当前小时；act-a 小时 10 仍被 r1 占用
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m2", "act-b"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals("act-b", r2.activityId());
        assertEquals(1, hourEntry("act-a", DAY, 10).reserved());
        assertEquals(1, hourEntry("act-b", DAY, 10).reserved());

        // 取消迁移前的预占：释放 act-a 同小时名额，act-b 不受影响
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(0, hourEntry("act-a", DAY, 10).reserved());
        assertEquals(1, hourEntry("act-a", DAY, 10).cancelled());
        assertEquals(1, hourEntry("act-b", DAY, 10).reserved());
        assertEquals(0, hourEntry("act-b", DAY, 10).cancelled());

        // 既有预占归属不被迁移改写
        ReservationResponse detail = service.getReservation(r1.reservationId());
        assertEquals("act-a", detail.activityId());
        assertEquals(10, detail.hourOfDay());
        assertEquals("CANCELLED", detail.status().name());
    }
    @Test
    @DisplayName("配置修改：expectedVersion 不符 409，已开始小时不可下调，未来小时可调整且版本递增")
    void updateActivity_versionAndStartedHourRules() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 3,
                budgets(9, 1, 10, 1, 11, 1)));

        // 版本不符 409，明细给出期望与实际版本
        ApiException versionConflict = assertThrows(ApiException.class, () -> service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u1", 3, budgets(9, 1, 10, 1, 11, 1), 5L)));
        assertEquals(409, versionConflict.getStatus().value());
        assertEquals("ACTIVITY_VERSION_CONFLICT", versionConflict.getDetails().get("reason"));
        assertEquals(5L, versionConflict.getDetails().get("expectedVersion"));
        assertEquals(1L, versionConflict.getDetails().get("actualVersion"));

        // 当前 UTC 10 点：小时 9（已过）与 10（进行中）不可下调
        ApiException pastHour = assertThrows(ApiException.class, () -> service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u2", 3, budgets(9, 0, 10, 2, 11, 1), 1L)));
        assertEquals(409, pastHour.getStatus().value());
        assertEquals("STARTED_HOUR_BUDGET_DECREASE", pastHour.getDetails().get("reason"));
        assertEquals(9, pastHour.getDetails().get("hourOfDay"));
        assert409(() -> service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u3", 3, budgets(9, 1, 10, 0, 11, 2), 1L)));

        // 未来小时下调（11 -> 0）并移到 12 时：成功，版本 +1
        ActivityResponse updated = service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u4", 3, budgets(9, 1, 10, 1, 12, 1), 1L));
        assertEquals(2L, updated.version());
        assertEquals(0, updated.hourlyBudgets().get(11));
        assertEquals(1, updated.hourlyBudgets().get(12));

        // 旧版本再次修改 409；同键重放首次结果
        assert409(() -> service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u5", 3, budgets(9, 1, 10, 1, 12, 1), 1L)));
        ActivityResponse replay = service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u4", 3, budgets(9, 1, 10, 1, 12, 1), 1L));
        assertEquals(2L, replay.version());

        // 合计不等于日总额 400
        ApiException badSum = assertThrows(ApiException.class, () -> service.updateActivity(
                "act-1", new UpdateActivityRequest("req-u6", 4, budgets(9, 1, 10, 1, 12, 1), 2L)));
        assertEquals(400, badSum.getStatus().value());
    }

    @Test
    @DisplayName("日结快照：固化每小时确认数与取消数，后续配置变更不改写已结算日")
    void dailySettlement_snapshotImmutableToConfigChange() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 3,
                budgets(10, 2, 11, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));

        DailySettlementResponse before = service.queryDailySettlement("act-1", DAY);
        assertEquals(1, before.totalConfirmed());
        assertEquals(1, before.totalCancelled());
        assertEquals(24, before.hours().size());
        assertEquals(1, before.hours().get(10).confirmedCount());
        assertEquals(1, before.hours().get(10).cancelledCount());
        assertEquals(0, before.hours().get(11).confirmedCount());

        // 配置变更（未来小时调整）不改写已结算日快照
        service.updateActivity("act-1",
                new UpdateActivityRequest("req-u1", 3, budgets(10, 2, 12, 1), 1L));
        DailySettlementResponse after = service.queryDailySettlement("act-1", DAY);
        assertEquals(before.totalConfirmed(), after.totalConfirmed());
        assertEquals(before.totalCancelled(), after.totalCancelled());
        assertEquals(before.hours(), after.hours());
    }

    @Test
    @DisplayName("跨日确认不迁移活动计数：预占固化申请日的 UTC 小时账")
    void confirmAcrossUtcDay_keepsOriginalActivityHourLedger() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 1, budgets(23, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(DAY, r.utcDate());
        assertEquals(23, r.hourOfDay());

        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));

        assertEquals(1, hourEntry("act-1", DAY, 23).confirmed());
        assertEquals(0, hourEntry("act-1", LocalDate.of(2026, 9, 23), 0).confirmed());
    }

    @Test
    @DisplayName("明细/历史/诊断查询只读：历史按创建时刻升序且固化归属，诊断返回 24 小时账目")
    void detailHistoryAndDiagnosticQueries_readOnly() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 2, budgets(10, 2)));
        createCampaign("req-c1", "c1", 10, 5);
        assertNull(service.getCampaign("c1").activityId());
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        CampaignDetailResponse detail = service.getCampaign("c1");
        assertEquals("act-1", detail.activityId());

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        mutableClock().advanceMillis(1_000L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));

        List<ReservationResponse> history = service.listReservations("c1", DAY);
        assertEquals(2, history.size());
        assertEquals(r1.reservationId(), history.get(0).reservationId());
        assertEquals(r2.reservationId(), history.get(1).reservationId());
        assertEquals("act-1", history.get(0).activityId());
        assertEquals(10, history.get(0).hourOfDay());
        assertTrue(history.get(0).createdAtUtc() < history.get(1).createdAtUtc());
        assertEquals(2, service.listReservations("c1", null).size());
        assertEquals(0, service.listReservations("c1", LocalDate.of(2026, 9, 23)).size());

        ActivityHoursResponse hours = service.queryActivityHours("act-1", DAY);
        assertEquals(24, hours.hours().size());
        assertEquals(2, hours.hours().get(10).reserved());
        assertEquals(0, hours.hours().get(10).remaining());
        assertEquals(0, hours.hours().get(0).budget());

        assert404(() -> service.queryActivityHours("nope", DAY));
        assert404(() -> service.queryDailySettlement("nope", DAY));
        assert404(() -> service.getActivity("nope"));
        assert404(() -> service.assignCampaignActivity("nope",
                new AssignActivityRequest("req-m9", "act-1")));
        assert404(() -> service.assignCampaignActivity("c1",
                new AssignActivityRequest("req-m8", "nope")));
    }
    @Test
    @DisplayName("失败不占幂等键：小时预算耗尽的 429 申请，释放后同键同参可成功")
    void failedApply_doesNotOccupyIdempotencyKey() {
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", 1, budgets(10, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        assert429(() -> service.apply(new ApplyExposureRequest("key-fail", "c1", "v2")));
        // 释放小时名额后同键同参成功，且只预占一次
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = service.apply(new ApplyExposureRequest("key-fail", "c1", "v2"));
        assertEquals("RESERVED", retried.status().name());
        assertEquals(1, hourEntry("act-1", DAY, 10).reserved());
        // 迁移操作同键异参 409
        service.assignCampaignActivity("c1", new AssignActivityRequest("key-m", "act-1"));
        assert409(() -> service.assignCampaignActivity("c1", new AssignActivityRequest("key-m", null)));
    }

    @Test
    @DisplayName("并发跨公告申请：成功数恰好等于小时预算，活动账不超卖、不变负")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 10;
        int threads = 40;
        service.createActivity(new CreateActivityRequest("req-act-1", "act-1", hourBudget,
                budgets(10, hourBudget)));
        createCampaign("req-c1", "c1", 100, 100);
        createCampaign("req-c2", "c2", 100, 100);
        service.assignCampaignActivity("c1", new AssignActivityRequest("req-m1", "act-1"));
        service.assignCampaignActivity("c2", new AssignActivityRequest("req-m2", "act-1"));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest(
                            "req-cc-" + idx, idx % 2 == 0 ? "c1" : "c2", "visitor-" + idx));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "只允许 429，不允许其他错误");
        assertEquals(hourBudget, success.get(), "成功预占数必须等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");

        ActivityHoursResponse.HourEntry hour10 = hourEntry("act-1", DAY, 10);
        assertEquals(hourBudget, hour10.reserved());
        assertEquals(0, hour10.remaining());
        Integer used = jdbc.queryForObject("SELECT reserved_count + confirmed_count "
                        + "FROM activity_hour_ledger WHERE activity_id = 'act-1' "
                        + "AND utc_date = ? AND hour_of_day = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertNotNull(used);
        assertEquals(hourBudget, used.intValue(), "活动小时账不得超卖");
    }

    @Test
    @DisplayName("HTTP 语义：活动创建 201、小时预算耗尽 429 带下一可用小时、日结快照 200")
    void httpSemantics_activityPacing() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/activities")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-act\",\"activityId\":\"act-http\","
                                + "\"dailyConfirmCap\":2,\"hourlyBudgets\":"
                                + "[0,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.hourlyBudgets[10]").value(1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-c\",\"campaignId\":\"c-http\","
                                + "\"dailyTotalCap\":10,\"perVisitorDailyCap\":5}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns/c-http/activity")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-m\",\"activityId\":\"act-http\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.activityId").value("act-http"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-a1\",\"campaignId\":\"c-http\","
                                + "\"visitorId\":\"u1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.activityId").value("act-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.hourOfDay").value(10));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-a2\",\"campaignId\":\"c-http\","
                                + "\"visitorId\":\"u2\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.details.reason").value("ACTIVITY_HOUR_BUDGET_EXHAUSTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.details.nextAvailableHourOfDay").value(11));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exposure/activities/act-http/daily-settlement")
                        .param("utcDate", "2026-09-22"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.hours.length()").value(24))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalConfirmed").value(0));
    }
    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
