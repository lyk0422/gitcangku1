package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySettlementResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放 H2（MODE=MySQL）集成测试：小时原子预占、429 下一可用小时、
 * 改配乐观锁与已开始小时保护、活动迁移固化、三侧结算、日结快照固化、幂等与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityPacingIntegrationTest {

    /** 可控时钟：固定起点，可设置/推进。 */
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
    MockMvc mockMvc;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM activity_hour_snapshot");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 构造 24 小时预算：默认全 0，按 (小时, 预算) 覆盖。 */
    private static List<Integer> budgets(Object... hourBudget) {
        List<Integer> list = new ArrayList<>(java.util.Collections.nCopies(24, 0));
        for (int i = 0; i < hourBudget.length; i += 2) {
            list.set((Integer) hourBudget[i], (Integer) hourBudget[i + 1]);
        }
        return list;
    }

    private static int sum(List<Integer> budgets) {
        return budgets.stream().mapToInt(Integer::intValue).sum();
    }

    private ActivityResponse createActivity(String reqId, String id, List<Integer> budgets) {
        return service.createActivity(new CreateActivityRequest(reqId, id, sum(budgets), budgets));
    }

    private CampaignResponse createCampaignInActivity(String reqId, String campaignId,
                                                      String activityId) {
        return service.createCampaign(
                new CreateCampaignRequest(reqId, campaignId, 100_000, 100_000, activityId));
    }

    @Test
    @DisplayName("主流程：申请占用活动当前小时并固化归属；确认同时结算三侧名额")
    void applyAndConfirm_settlesAllThreeSides() {
        List<Integer> budgets = budgets(10, 5, 11, 3);
        createActivity("req-a", "act", budgets);
        createCampaignInActivity("req-c", "c1", "act");

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-r1", "c1", "v1"));
        assertEquals("act", r.activityId());
        assertEquals(10, r.utcHour());
        assertEquals(DAY, r.utcDate());

        // 活动小时账：占用 1、未确认
        assertHourLedger("act", DAY, 10, 1, 0, 0, 0);
        // 公告/访客账各占用 1
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());

        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());

        // 确认：活动小时占用保持 1、确认数 1；公告/访客额度仍占用
        assertHourLedger("act", DAY, 10, 1, 1, 0, 0);
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());

        // 明细查询返回固化归属与小时
        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("act", detail.activityId());
        assertEquals(10, detail.utcHour());
        assertEquals(BASE.toEpochMilli(), detail.createdAtUtc());
        assertEquals(BASE.toEpochMilli() + 60_000L, detail.expiresAtUtc());
    }

    @Test
    @DisplayName("小时额度耗尽：429 给出原因、实际值/要求值/差额与下一可用小时，且不消耗另两侧名额")
    void hourBudgetExhausted_429WithNextHourAndNoSideEffects() {
        // h10=2，h11=1，其余 0，日总 3
        createActivity("req-a", "act", budgets(10, 2, 11, 1));
        createCampaignInActivity("req-c", "c1", "act");

        ReservationResponse first = service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        ReservationResponse second = service.apply(new ApplyExposureRequest("r2", "c1", "v2"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("r3", "c1", "v3")));
        assertEquals(429, ex.getStatus().value());
        assertEquals("ACTIVITY_HOUR_BUDGET_EXHAUSTED", ex.getReason());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = ex.getDetails();
        assertEquals("act", details.get("activityId"));
        assertEquals(10, ((Number) details.get("utcHour")).intValue());
        assertEquals(2, ((Number) details.get("hourBudget")).intValue());
        assertEquals(2, ((Number) details.get("reservedCount")).intValue());
        assertEquals(1, ((Number) details.get("requestedCount")).intValue());
        assertEquals(1, ((Number) details.get("shortfall")).intValue());
        assertEquals(Instant.parse("2026-09-22T11:00:00Z").toEpochMilli(),
                ((Number) details.get("nextAvailableHourUtc")).longValue());
        assertEquals(3_600_000L, ((Number) details.get("retryAfterMillis")).longValue());

        // 公告侧仍只占用 2（失败申请未消耗公告名额）
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
        // 访客侧无 v3 账目
        assertEquals(0, service.queryQuota("c1", "v3", DAY).usedVisitor());
        assertHourLedger("act", DAY, 10, 2, 0, 0, 0);

        // 先把两个预占确认（推进到 11 点后仍持续占用旧小时），再在下一小时申请成功
        service.confirm(first.reservationId(), new ReservationActionRequest("k1"));
        service.confirm(second.reservationId(), new ReservationActionRequest("k2"));
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse next = service.apply(new ApplyExposureRequest("r4", "c1", "v4"));
        assertEquals(11, next.utcHour());
        assertHourLedger("act", DAY, 10, 2, 2, 0, 0);
        assertHourLedger("act", DAY, 11, 1, 0, 0, 0);
    }

    @Test
    @DisplayName("下一可用小时可跨日：当日后续无预算时返回次日首个正预算小时")
    void nextAvailableHour_crossesUtcDay() {
        // 只有次日意义上的 h0 有预算（今天 23 点之后）
        mutableClock().setInstant(Instant.parse("2026-09-22T23:15:00Z"));
        createActivity("req-a", "act", budgets(0, 4));
        createCampaignInActivity("req-c", "c1", "act");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("r1", "c1", "v1")));
        assertEquals(429, ex.getStatus().value());
        assertEquals(Instant.parse("2026-09-23T00:00:00Z").toEpochMilli(),
                ((Number) ex.getDetails().get("nextAvailableHourUtc")).longValue());
        assertEquals(45 * 60_000L, ((Number) ex.getDetails().get("retryAfterMillis")).longValue());
    }

    @Test
    @DisplayName("日总额为 0（未配置任何小时预算）：申请 429 且下一可用小时为 null")
    void allZeroBudget_429WithNullNextHour() {
        createActivity("req-a", "act", budgets());
        createCampaignInActivity("req-c", "c1", "act");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("r1", "c1", "v1")));
        assertEquals(429, ex.getStatus().value());
        assertNull(ex.getDetails().get("nextAvailableHourUtc"));
        assertNull(ex.getDetails().get("retryAfterMillis"));
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("公告未归入活动：不得申请，返回 409 可区分原因；迁移后可申请")
    void campaignWithoutActivity_cannotApply_thenMigrated() {
        createActivity("req-a", "act", budgets(10, 2));
        service.createCampaign(new CreateCampaignRequest("req-c", "c0", 10, 10, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("r1", "c0", "v1")));
        assertEquals(409, ex.getStatus().value());
        assertEquals("CAMPAIGN_ACTIVITY_NOT_CONFIGURED", ex.getReason());
        assertEquals(0, service.queryQuota("c0", null, DAY).usedTotal());

        CampaignResponse migrated = service.migrateCampaign("c0",
                new MigrateCampaignRequest("req-m", "act"));
        assertEquals("act", migrated.activityId());

        ReservationResponse r = service.apply(new ApplyExposureRequest("r2", "c0", "v1"));
        assertEquals("act", r.activityId());
        assertEquals(10, r.utcHour());
    }

    @Test
    @DisplayName("活动改配：版本不符 409；已开始小时下调 409；合规修改版本递增并只影响新预占")
    void updateActivity_versionAndStartedHourRules() {
        createActivity("req-a", "act", budgets(10, 2, 11, 1));
        createCampaignInActivity("req-c", "c1", "act");
        ReservationResponse before = service.apply(new ApplyExposureRequest("r0", "c1", "v0"));
        assertEquals(10, before.utcHour());

        // 版本不符：期望 99，实际 1
        ApiException versionEx = assertThrows(ApiException.class,
                () -> service.updateActivity("act",
                        new UpdateActivityRequest("u-bad", 99, 3, budgets(10, 2, 11, 1))));
        assertEquals(409, versionEx.getStatus().value());
        assertEquals("ACTIVITY_VERSION_MISMATCH", versionEx.getReason());
        assertEquals(1, ((Number) versionEx.getDetails().get("actualVersion")).intValue());
        assertEquals(99, ((Number) versionEx.getDetails().get("expectedVersion")).intValue());

        // 已开始小时（10 时）下调：409，给出要求值/实际值/差额
        ApiException startedEx = assertThrows(ApiException.class,
                () -> service.updateActivity("act",
                        new UpdateActivityRequest("u-started", 1, 2, budgets(10, 1, 11, 1))));
        assertEquals(409, startedEx.getStatus().value());
        assertEquals("STARTED_HOUR_BUDGET_REDUCED", startedEx.getReason());
        assertEquals(10, ((Number) startedEx.getDetails().get("utcHour")).intValue());
        assertEquals(2, ((Number) startedEx.getDetails().get("requiredHourBudget")).intValue());
        assertEquals(1, ((Number) startedEx.getDetails().get("actualHourBudget")).intValue());
        assertEquals(1, ((Number) startedEx.getDetails().get("shortfall")).intValue());

        // 合规修改：未来小时 11 由 1 提到 5（日总 2→6），已开始小时保持不变
        List<Integer> newBudgets = budgets(10, 2, 11, 5);
        ActivityResponse updated = service.updateActivity("act",
                new UpdateActivityRequest("u-ok", 1, sum(newBudgets), newBudgets));
        assertEquals(2, updated.version());
        assertEquals(5, updated.hourlyBudgets().get(11));

        // 旧版本号再次修改：409，实际版本已是 2
        ApiException stale = assertThrows(ApiException.class,
                () -> service.updateActivity("act",
                        new UpdateActivityRequest("u-stale", 1, sum(newBudgets), newBudgets)));
        assertEquals(409, stale.getStatus().value());
        assertEquals(2, ((Number) stale.getDetails().get("actualVersion")).intValue());

        // 历史预占不改写：r0 仍固化 10 时；当前小时预算仍为 2，再申请 1 个后第 3 个 429
        assertEquals(10, service.getReservation(before.reservationId()).utcHour());
        service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        ApiException exhausted = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("r2", "c1", "v2")));
        assertEquals(429, exhausted.getStatus().value());
    }

    @Test
    @DisplayName("活动迁移：既有预占固化旧活动与小时，迁移后新预占归新活动，结算互不串账")
    void campaignMigration_freezesExistingReservations() {
        createActivity("req-old", "old", budgets(10, 5));
        createActivity("req-new", "new", budgets(10, 5));
        createCampaignInActivity("req-c", "c1", "old");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        assertEquals("old", r1.activityId());

        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m", "new"));

        ReservationResponse r2 = service.apply(new ApplyExposureRequest("r2", "c1", "v2"));
        assertEquals("new", r2.activityId());
        assertEquals(10, r2.utcHour());

        // 确认旧单：旧活动小时确认 +1，新活动账不变
        service.confirm(r1.reservationId(), new ReservationActionRequest("k1"));
        assertHourLedger("old", DAY, 10, 1, 1, 0, 0);
        assertHourLedger("new", DAY, 10, 1, 0, 0, 0);

        // 取消新单：新活动小时释放并计取消，旧活动账不变
        service.cancel(r2.reservationId(), new ReservationActionRequest("x1"));
        assertHourLedger("old", DAY, 10, 1, 1, 0, 0);
        assertHourLedger("new", DAY, 10, 0, 0, 1, 0);

        // 公告/访客侧：旧单确认保持占用 1，新单取消释放
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(0, service.queryQuota("c1", "v2", DAY).usedVisitor());
    }

    @Test
    @DisplayName("取消与过期只释放同一小时预占：活动账 reserved 减、取消/过期计数增；重复终态不重复计数")
    void cancelAndExpire_releaseSameHourOnly_andTerminalIsIdempotent() {
        createActivity("req-a", "act", budgets(10, 5));
        createCampaignInActivity("req-c", "c1", "act");

        // 取消路径
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        service.cancel(r1.reservationId(), new ReservationActionRequest("x1"));
        assertHourLedger("act", DAY, 10, 0, 0, 1, 0);
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 重复取消（新 requestId）返回原状态，不重复计数；确认已取消单 409
        ReservationResponse repeatCancel = service.cancel(r1.reservationId(),
                new ReservationActionRequest("x2"));
        assertEquals("CANCELLED", repeatCancel.status().name());
        assertThrows(ApiException.class, () -> service.confirm(r1.reservationId(),
                new ReservationActionRequest("k-bad")));
        assertHourLedger("act", DAY, 10, 0, 0, 1, 0);

        // 取消释放后名额可再用
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("r2", "c1", "v2"));

        // 过期路径：推进到恰好到期时刻，确认触发惰性结算
        mutableClock().advanceMillis(60_000L);
        ApiException confirmExpired = assertThrows(ApiException.class,
                () -> service.confirm(r2.reservationId(), new ReservationActionRequest("k2")));
        assertEquals(409, confirmExpired.getStatus().value());
        // r1 取消占 1 个取消计数；r2 过期占 1 个过期计数，占用归零
        assertHourLedger("act", DAY, 10, 0, 0, 1, 1);
        assertEquals("EXPIRED", service.getReservation(r2.reservationId()).status().name());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 重复过期后的取消 409，不计数
        assertThrows(ApiException.class, () -> service.cancel(r2.reservationId(),
                new ReservationActionRequest("x3")));
        assertHourLedger("act", DAY, 10, 0, 0, 1, 1);
    }

    @Test
    @DisplayName("日结快照：往日首次查询固化确认/取消数，之后改配不改写；当日为实时视图")
    void dailySettlement_freezesAndDoesNotRewrite() {
        // h9 预算 1、h10 预算 3
        createActivity("req-a", "act", budgets(9, 1, 10, 3));
        createCampaignInActivity("req-c", "c1", "act");

        // 9 点：1 单确认
        mutableClock().setInstant(Instant.parse("2026-09-22T09:00:00Z"));
        ReservationResponse r9 = service.apply(new ApplyExposureRequest("r9", "c1", "v9"));
        service.confirm(r9.reservationId(), new ReservationActionRequest("k9"));

        // 10 点：2 单，1 确认 1 取消
        mutableClock().setInstant(Instant.parse("2026-09-22T10:00:00Z"));
        ReservationResponse r10a = service.apply(new ApplyExposureRequest("ra", "c1", "va"));
        ReservationResponse r10b = service.apply(new ApplyExposureRequest("rb", "c1", "vb"));
        service.confirm(r10a.reservationId(), new ReservationActionRequest("ka"));
        service.cancel(r10b.reservationId(), new ReservationActionRequest("xb"));

        // 当日查询：实时、不固化
        DailySettlementResponse today = service.dailySettlement("act", DAY);
        assertFalse(today.frozen());
        assertNull(today.settledAtUtc());
        assertEquals(1, today.hours().get(9).confirmedCount());
        assertEquals(1, today.hours().get(10).confirmedCount());
        assertEquals(1, today.hours().get(10).cancelledCount());
        assertEquals(2, today.dailyConfirmedCount());
        assertEquals(1, today.dailyCancelledCount());

        // 推进到次日，首次查询 09-22：固化
        mutableClock().setInstant(Instant.parse("2026-09-23T00:01:00Z"));
        DailySettlementResponse frozen1 = service.dailySettlement("act", DAY);
        assertTrue(frozen1.frozen());
        assertNotNull(frozen1.settledAtUtc());
        assertEquals(1, frozen1.hours().get(9).confirmedCount());
        assertEquals(0, frozen1.hours().get(9).cancelledCount());
        assertEquals(1, frozen1.hours().get(10).confirmedCount());
        assertEquals(1, frozen1.hours().get(10).cancelledCount());
        long settledAt = frozen1.settledAtUtc();

        // 改配活动（次日 00:01，10 时属未来小时，可下调）后再查：快照不改写
        List<Integer> newBudgets = budgets(9, 0, 10, 0);
        service.updateActivity("act",
                new UpdateActivityRequest("u1", 1, sum(newBudgets), newBudgets));
        DailySettlementResponse frozen2 = service.dailySettlement("act", DAY);
        assertTrue(frozen2.frozen());
        assertEquals(settledAt, frozen2.settledAtUtc(), "固化时刻必须保持不变");
        assertEquals(1, frozen2.hours().get(9).confirmedCount());
        assertEquals(1, frozen2.hours().get(10).confirmedCount());
        assertEquals(1, frozen2.hours().get(10).cancelledCount());

        // 无数据的往日：固化 24 小时全 0 快照
        DailySettlementResponse emptyDay = service.dailySettlement(
                "act", LocalDate.of(2026, 9, 20));
        assertTrue(emptyDay.frozen());
        assertEquals(0, emptyDay.dailyConfirmedCount());
        assertEquals(0, emptyDay.dailyCancelledCount());
        assertEquals(24, emptyDay.hours().size());
    }

    @Test
    @DisplayName("诊断查询：24 小时预算/实际占用/确认/取消/过期与剩余差额，只读")
    void activityDayDiagnostics_readonly() {
        createActivity("req-a", "act", budgets(10, 2, 11, 1));
        createCampaignInActivity("req-c", "c1", "act");
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("k1"));
        service.apply(new ApplyExposureRequest("r2", "c1", "v2"));

        ActivityDayResponse day = service.activityDay("act", DAY);
        assertEquals(3, day.dailyTotalCap());
        assertEquals(10, day.currentUtcHour());
        ActivityDayResponse.Hour h10 = day.hours().get(10);
        assertEquals(2, h10.hourBudget());
        assertEquals(2, h10.reservedCount());
        assertEquals(1, h10.confirmedCount());
        assertEquals(0, h10.cancelledCount());
        assertEquals(0, h10.expiredCount());
        assertEquals(0, h10.remaining());
        ActivityDayResponse.Hour h11 = day.hours().get(11);
        assertEquals(1, h11.hourBudget());
        assertEquals(0, h11.reservedCount());
        assertEquals(1, h11.remaining());
        assertEquals(24, day.hours().size());
    }

    @Test
    @DisplayName("历史查询：按创建时刻倒序、状态过滤、limit 钳制，只读")
    void reservationHistory_sortingFilterAndLimit() {
        createActivity("req-a", "act", budgets(10, 5));
        createCampaignInActivity("req-c", "c1", "act");
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("r1", "c1", "v1"));
        mutableClock().advanceMillis(10);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("r2", "c1", "v2"));
        mutableClock().advanceMillis(10);
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("r3", "c1", "v3"));
        service.cancel(r3.reservationId(), new ReservationActionRequest("x3"));

        List<ReservationResponse> all = service.listReservations("c1", null, null, 100);
        assertEquals(List.of(r3.reservationId(), r2.reservationId(), r1.reservationId()),
                all.stream().map(ReservationResponse::reservationId).toList());

        List<ReservationResponse> cancelled = service.listReservations("c1", DAY, "CANCELLED", 100);
        assertEquals(1, cancelled.size());
        assertEquals(r3.reservationId(), cancelled.get(0).reservationId());

        List<ReservationResponse> limited = service.listReservations("c1", null, null, 2);
        assertEquals(2, limited.size());
        // limit 超上限钳制为 200，不报错
        assertEquals(3, service.listReservations("c1", null, null, 10_000).size());

        ApiException badStatus = assertThrows(ApiException.class,
                () -> service.listReservations("c1", null, "BOGUS", 100));
        assertEquals(400, badStatus.getStatus().value());
        assertEquals("INVALID_STATUS_FILTER", badStatus.getReason());
    }

    @Test
    @DisplayName("创建活动预算非法：项数错误或合计不等返回 400，明细给出实际合计/要求值/差额")
    void createActivity_invalidBudgets_400() {
        // 合计不等于日总额
        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.createActivity(new CreateActivityRequest(
                        "a-bad", "act1", 10, budgets(10, 3))));
        assertEquals(400, mismatch.getStatus().value());
        assertEquals("BUDGET_SUM_MISMATCH", mismatch.getReason());
        assertEquals(10, ((Number) mismatch.getDetails().get("requiredDailyTotalCap")).intValue());
        assertEquals(3, ((Number) mismatch.getDetails().get("actualHourlyBudgetSum")).intValue());
        assertEquals(7, ((Number) mismatch.getDetails().get("shortfall")).intValue());

        // 23 项
        List<Integer> shortList = new ArrayList<>(java.util.Collections.nCopies(23, 0));
        ApiException wrongCount = assertThrows(ApiException.class,
                () -> service.createActivity(new CreateActivityRequest(
                        "a-bad2", "act2", 0, shortList)));
        assertEquals(400, wrongCount.getStatus().value());
        assertEquals(23, ((Number) wrongCount.getDetails().get("actualHourCount")).intValue());
        assertEquals(24, ((Number) wrongCount.getDetails().get("requiredHourCount")).intValue());

        // 失败不占键：同一 requestId 之后可成功（活动编号换新）
        List<Integer> valid = budgets(10, 3);
        ActivityResponse ok = service.createActivity(new CreateActivityRequest(
                "a-bad", "act3", sum(valid), valid));
        assertEquals(1, ok.version());
    }

    @Test
    @DisplayName("活动与迁移写操作的幂等：同键同参重放，异参 409，失败不占键")
    void activityWrites_idempotency() {
        List<Integer> budgets = budgets(10, 5);
        ActivityResponse first = createActivity("key-a", "act", budgets);
        ActivityResponse replay = createActivity("key-a", "act", budgets);
        assertEquals(first.version(), replay.version());
        assertEquals(first.createdAtUtc(), replay.createdAtUtc());

        // 同键异参（合法但不同的预算分布）：409，而非 400
        ApiException diffParams = assertThrows(ApiException.class,
                () -> service.createActivity(new CreateActivityRequest(
                        "key-a", "act", 5, budgets(10, 4, 11, 1))));
        assertEquals(409, diffParams.getStatus().value());
        assertEquals("IDEMPOTENCY_PARAMS_MISMATCH", diffParams.getReason());

        createCampaignInActivity("req-c", "c1", "act");
        createActivity("req-a2", "act2", budgets);

        // 迁移幂等：同键同参重放同一结果
        CampaignResponse m1 = service.migrateCampaign("c1",
                new MigrateCampaignRequest("key-m", "act2"));
        CampaignResponse m2 = service.migrateCampaign("c1",
                new MigrateCampaignRequest("key-m", "act2"));
        assertEquals(m1.activityId(), m2.activityId());
        // 同键异参（迁到别的活动）：409
        createActivity("req-a3", "act3", budgets);
        ApiException migrateConflict = assertThrows(ApiException.class,
                () -> service.migrateCampaign("c1",
                        new MigrateCampaignRequest("key-m", "act3")));
        assertEquals(409, migrateConflict.getStatus().value());

        // 失败的迁移（目标活动不存在）不占键：创建活动后同键同参可成功
        ApiException missingTarget = assertThrows(ApiException.class,
                () -> service.migrateCampaign("c1",
                        new MigrateCampaignRequest("key-m2", "ghost")));
        assertEquals(404, missingTarget.getStatus().value());
        createActivity("req-ghost", "ghost", budgets);
        CampaignResponse retried = service.migrateCampaign("c1",
                new MigrateCampaignRequest("key-m2", "ghost"));
        assertEquals("ghost", retried.activityId());
    }

    @Test
    @DisplayName("资源缺失：活动/公告/迁移目标不存在返回 404")
    void missingResources_404() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.getActivity("nope")).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.dailySettlement("nope", DAY)).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.migrateCampaign("ghost-campaign",
                        new MigrateCampaignRequest("m1", "ghost-activity")))
                .getStatus().value());

        createActivity("req-a", "act", budgets(10, 1));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.createCampaign(new CreateCampaignRequest(
                        "req-c", "c1", 10, 10, "ghost"))).getStatus().value());
    }

    @Test
    @DisplayName("活动配置视图完整返回 24 小时预算，保持顺序与数值")
    void activityResponse_contains24BudgetsInOrder() {
        List<Integer> budgets = budgets(0, 2, 23, 7);
        ActivityResponse activity = createActivity("req-a", "act", budgets);
        assertEquals(24, activity.hourlyBudgets().size());
        assertEquals(2, activity.hourlyBudgets().get(0));
        assertEquals(7, activity.hourlyBudgets().get(23));
        assertEquals(9, activity.dailyTotalCap());
        assertArrayEquals(budgets.toArray(), activity.hourlyBudgets().toArray());
    }

    @Test
    @DisplayName("HTTP 层：小时耗尽 429 响应体带出原因码与下一可用小时等明细；未归活动 409")
    void http_429CarriesReasonAndNextHour() throws Exception {
        // h10=1、h12=2，日总 3
        String budgetsJson = "[0,0,0,0,0,0,0,0,0,0,1,0,2,0,0,0,0,0,0,0,0,0,0,0]";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"ha\",\"activityId\":\"act\",\"dailyTotalCap\":3,"
                                + "\"hourlyBudgets\":" + budgetsJson + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hc\",\"campaignId\":\"c1\",\"dailyTotalCap\":100000,"
                                + "\"perVisitorDailyCap\":100000,\"activityId\":\"act\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hr1\",\"campaignId\":\"c1\",\"visitorId\":\"v1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hr2\",\"campaignId\":\"c1\",\"visitorId\":\"v2\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value(429))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reason")
                        .value("ACTIVITY_HOUR_BUDGET_EXHAUSTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.utcHour").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.hourBudget").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.reservedCount").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.shortfall").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details.nextAvailableHourUtc")
                        .value(Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()));

        // 未归入活动的公告：409 + 原因码
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hc2\",\"campaignId\":\"c2\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hr3\",\"campaignId\":\"c2\",\"visitorId\":\"v3\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reason")
                        .value("CAMPAIGN_ACTIVITY_NOT_CONFIGURED"));
    }

    private void assertHourLedger(String activityId, LocalDate utcDate, int hour,
                                  int reserved, int confirmed, int cancelled, int expired) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reserved_count, confirmed_count, cancelled_count, expired_count "
                        + "FROM activity_hour_ledger WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), hour);
        assertEquals(reserved, ((Number) row.get("reserved_count")).intValue(), "reserved_count");
        assertEquals(confirmed, ((Number) row.get("confirmed_count")).intValue(), "confirmed_count");
        assertEquals(cancelled, ((Number) row.get("cancelled_count")).intValue(), "cancelled_count");
        assertEquals(expired, ((Number) row.get("expired_count")).intValue(), "expired_count");
    }
}
