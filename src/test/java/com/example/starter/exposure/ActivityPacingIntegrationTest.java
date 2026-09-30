package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySnapshotResponse;
import com.example.starter.exposure.web.HourDiagnosticResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放 H2（MODE=MySQL）集成测试：三侧原子预占、429 诊断、配置版本/冻结、
 * 迁移固化、确认/取消/过期结算、日结固化与只读查询。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityPacingIntegrationTest {

    /** 可控时钟：固定起点，可设置到任意 UTC 时刻。 */
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

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_daily_hour_snapshot");
        jdbc.update("DELETE FROM activity_hour_budget_freeze");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 构造合计等于 total 的 24 小时预算：只在 hour 放置 total，其余为 0。 */
    private List<Integer> singleHourBudget(int hour, int total) {
        List<Integer> budgets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            budgets.add(h == hour ? total : 0);
        }
        return budgets;
    }

    /** 多小时分配预算。 */
    private List<Integer> budgetsOf(int total, int... hourValuePairs) {
        List<Integer> budgets = new ArrayList<>(java.util.Collections.nCopies(24, 0));
        int sum = 0;
        for (int i = 0; i < hourValuePairs.length; i += 2) {
            budgets.set(hourValuePairs[i], hourValuePairs[i + 1]);
            sum += hourValuePairs[i + 1];
        }
        assertEquals(total, sum, "测试预算构造必须合计等于日总额");
        return budgets;
    }

    private ApiException assertApi(int status, String reason, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(status, ex.getStatus().value());
        if (reason != null) {
            assertEquals(reason, ex.getReason());
        }
        return ex;
    }

    @Test
    @DisplayName("创建活动：预算合计必须等于日总额，失败给出实际值/要求值/差额，且不占活动与幂等键")
    void createActivity_budgetSumMustEqualDailyTotal() {
        List<Integer> wrong = singleHourBudget(10, 5); // 合计 5
        ApiException ex = assertApi(400, "INVALID_HOURLY_BUDGET",
                () -> service.createActivity(new CreateActivityRequest(
                        "req-a-bad", "act-bad", 10, wrong)));
        assertEquals(5L, ex.getDetails().get("actualSum"));
        assertEquals(10, ex.getDetails().get("requiredSum"));
        assertEquals(-5L, ex.getDetails().get("difference"));
        assertApi(404, null, () -> service.getActivity("act-bad"));

        ActivityResponse activity = service.createActivity(new CreateActivityRequest(
                "req-a1", "act1", 5, singleHourBudget(10, 5)));
        assertEquals(0, activity.version());
        assertEquals(5, activity.hourlyBudget().get(10));
        assertEquals(5, service.getActivity("act1").dailyTotalCap());
    }

    @Test
    @DisplayName("归入活动的申请：固化活动与小时，原子占用公告/活动小时/访客三侧名额")
    void apply_withActivity_occupiesThreeSides() {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 5, singleHourBudget(10, 5)));
        service.createCampaign(new CreateCampaignRequest(
                "req-camp", "c1", 100, 100, "act1"));

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-apply1", "c1", "v1"));
        assertEquals("act1", r.activityId());
        assertEquals(10, r.utcHour());
        assertEquals(DAY, r.utcDate());

        int usedHour = jdbc.queryForObject(
                "SELECT used_hour FROM quota_activity_hour_ledger WHERE activity_id = 'act1' "
                        + "AND utc_date = ? AND utc_hour = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(1, usedHour);
        QuotaResponse total = service.queryQuota("c1", null, DAY);
        assertEquals(1, total.usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());

        HourDiagnosticResponse diag = service.queryHourDiagnostic("act1", DAY);
        HourDiagnosticResponse.HourStatus h10 = diag.hours().get(10);
        assertEquals(5, h10.budget());
        assertEquals(1, h10.used());
        assertEquals(1, h10.reservedCount());
        assertEquals(4, h10.remaining());
        assertEquals(0, h10.confirmedCount());
    }

    @Test
    @DisplayName("小时额度耗尽：429 并给出下一可用小时，公告与访客侧名额不消耗")
    void hourBudgetExhausted_returns429WithNextHourAndNoOtherConsumption() {
        // 10 点预算 1，12 点预算 1，日总额 2；两个公告共用活动
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 2, budgetsOf(2, 10, 1, 12, 1)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));
        service.createCampaign(new CreateCampaignRequest("req-c2", "c2", 100, 100, "act1"));

        ReservationResponse first = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(10, first.utcHour());

        ApiException rejected = assertApi(429, "HOUR_BUDGET_EXHAUSTED",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c2", "v2")));
        assertEquals(10, rejected.getDetails().get("currentHour"));
        assertEquals(1, rejected.getDetails().get("actualUsed"));
        assertEquals(1, rejected.getDetails().get("requiredBudget"));
        assertEquals(1, rejected.getDetails().get("difference"));
        assertEquals(12, rejected.getDetails().get("nextAvailableHour"));
        assertEquals(DAY.toString(), rejected.getDetails().get("nextAvailableUtcDate"));
        long expectedRetry = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli() - BASE.toEpochMilli();
        assertEquals(expectedRetry, rejected.getDetails().get("retryAfterMillis"));

        // 被拒申请没有消耗公告或访客侧名额
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c2", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c2", "v2", DAY).usedVisitor());
        // 活动小时账仍只占用 1
        assertEquals(1, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());

        // 推进到 12 点：新小时预算生效，申请成功
        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:00Z"));
        ReservationResponse second = service.apply(new ApplyExposureRequest("req-a3", "c2", "v3"));
        assertEquals(12, second.utcHour());
        assertEquals(1, service.queryQuota("c2", null, DAY).usedTotal());

        // 12 点也耗尽后，当天无正预算，下一可用小时为次日首个正预算小时（10 点）
        ApiException endOfDay = assertApi(429, "HOUR_BUDGET_EXHAUSTED",
                () -> service.apply(new ApplyExposureRequest("req-a4", "c2", "v4")));
        assertEquals(10, endOfDay.getDetails().get("nextAvailableHour"));
        assertEquals(DAY.plusDays(1).toString(), endOfDay.getDetails().get("nextAvailableUtcDate"));
    }

    @Test
    @DisplayName("当前小时未配置预算（预算为 0）：429 且不消耗任何侧名额，进入下一正预算小时可申请")
    void hourBudgetNotConfigured_returns429AndNothingConsumed() {
        // 全部预算集中在 11 点；10 点预算为 0
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 3, singleHourBudget(11, 3)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));

        ApiException ex = assertApi(429, "HOUR_BUDGET_NOT_CONFIGURED",
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));
        assertEquals(11, ex.getDetails().get("nextAvailableHour"));
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedVisitor());
        // 诊断行的小时起点为左闭区间端点（10:00 UTC 的 epoch 毫秒）
        assertEquals(Instant.parse("2026-09-22T10:00:00Z").toEpochMilli(),
                service.queryHourDiagnostic("act1", DAY).hours().get(10).hourStartUtc());

        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a2", "c1", "v1"));
        assertEquals(11, r.utcHour());
    }

    @Test
    @DisplayName("确认保持三侧占用；取消/过期只释放未确认的同小时预占；重复终态不重复计数")
    void confirmCancelExpire_settleThreeSidesAndRepeatedTerminalNoDoubleCount() {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 3, singleHourBudget(10, 3)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));

        ReservationResponse confirmed = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse cancelled = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        ReservationResponse expired = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));

        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x1"));

        // 取消释放三侧各 1：小时占用 3 -> 2
        assertEquals(2, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v2", DAY).usedVisitor());

        // 重复取消不重复释放；异向操作 409
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x1"));
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x2"));
        assertApi(409, null,
                () -> service.confirm(cancelled.reservationId(), new ReservationActionRequest("req-e1")));
        assertEquals(2, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());

        // 推进到到期时刻：过期单释放，确认单保持占用
        mutableClock().setInstant(Instant.parse("2026-09-22T10:01:00Z"));
        assertApi(409, null,
                () -> service.cancel(expired.reservationId(), new ReservationActionRequest("req-e2")));
        assertEquals("EXPIRED", service.getReservation(expired.reservationId()).status().name());
        assertEquals(1, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(0, service.queryQuota("c1", "v3", DAY).usedVisitor());

        // 小时名额释放后可再申请（仍在 10 点）
        ReservationResponse again = service.apply(new ApplyExposureRequest("req-a4", "c1", "v4"));
        assertEquals(10, again.utcHour());
        assertEquals(2, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());
    }

    @Test
    @DisplayName("公告活动迁移：只影响迁移后的新预占，既有预占固化活动与小时归属")
    void migrateCampaign_onlyAffectsNewReservations() {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 2, singleHourBudget(10, 2)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100));

        ReservationResponse before = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertNull(before.activityId());
        assertNull(before.utcHour());

        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));
        assertEquals("act1", service.getCampaign("c1").activityId());

        ReservationResponse after = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals("act1", after.activityId());
        assertEquals(10, after.utcHour());

        // 历史预占固化为无活动；其取消不触碰活动小时账
        service.cancel(before.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(1, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());

        // 迁移幂等：同键同参重放；异参 409；迁移到不存在的活动 404
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));
        assertApi(409, null,
                () -> service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", null)));
        assertApi(404, null,
                () -> service.migrateCampaign("c1", new MigrateCampaignRequest("req-m2", "nope")));

        // 移出活动后新预占不再占用小时名额
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m3", null));
        ReservationResponse detached = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));
        assertNull(detached.activityId());
    }

    @Test
    @DisplayName("活动配置修改：expectedVersion 裁决，已开始小时不可下调，历史确认记录不迁移")
    void updateActivity_versionAndStartedHourFreeze_historyNotMigrated() {
        // 初始：10 点 1、11 点 1、12 点 1，合计 3
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 3, budgetsOf(3, 10, 1, 11, 1, 12, 1)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));

        // 版本不匹配 409，明细给出实际版本与要求版本
        ApiException versionEx = assertApi(409, "ACTIVITY_VERSION_MISMATCH",
                () -> service.updateActivity("act1", new UpdateActivityRequest(
                        "req-up-bad", 3, budgetsOf(3, 10, 1, 11, 1, 12, 1), 99L)));
        assertEquals(0L, versionEx.getDetails().get("actualVersion"));
        assertEquals(99L, versionEx.getDetails().get("requiredVersion"));

        // 已开始小时（10 点）下调 409，差额明确
        ApiException freezeEx = assertApi(409, "STARTED_HOUR_BUDGET_CANNOT_DECREASE",
                () -> service.updateActivity("act1", new UpdateActivityRequest(
                        "req-up1", 3, budgetsOf(3, 10, 0, 11, 1, 12, 2), 0L)));
        assertEquals(10, freezeEx.getDetails().get("hour"));
        assertEquals(0, freezeEx.getDetails().get("actualBudget"));
        assertEquals(1, freezeEx.getDetails().get("requiredBudget"));
        assertEquals(1, freezeEx.getDetails().get("difference"));
        assertEquals(0, service.getActivity("act1").version(), "失败修改不得改变版本");

        // 已开始小时允许上调（预算从未来小时 12 点挪入），版本 +1
        ActivityResponse increased = service.updateActivity("act1", new UpdateActivityRequest(
                "req-up2", 3, budgetsOf(3, 10, 2, 11, 1, 12, 0), 0L));
        assertEquals(1, increased.version());

        // 进行中上调后再下调回 1 也被拒绝（当天生效过的最高预算为 2）
        assertApi(409, "STARTED_HOUR_BUDGET_CANNOT_DECREASE",
                () -> service.updateActivity("act1", new UpdateActivityRequest(
                        "req-up3", 3, budgetsOf(3, 10, 1, 11, 2, 12, 0), 1L)));

        // 历史确认记录固化在 10 点，配置修改不迁移
        DailySnapshotResponse live = service.queryDailySnapshot("act1", DAY);
        assertFalse(live.frozen());
        assertEquals(1, live.hours().get(10).confirmedCount());

        // 修改操作幂等：同键同参重放同一结果
        ActivityResponse replay = service.updateActivity("act1", new UpdateActivityRequest(
                "req-up2", 3, budgetsOf(3, 10, 2, 11, 1, 12, 0), 0L));
        assertEquals(1, replay.version());
    }

    @Test
    @DisplayName("日结：次日 00:01 UTC 后固化每小时确认/取消数，之后配置变更不改写已结算日")
    void dailySnapshot_freezesAfterDayEndAndConfigChangeDoesNotRewrite() {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 4, budgetsOf(4, 10, 2, 11, 2)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));

        ReservationResponse confirmed = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse cancelled = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x1"));

        // 跨到 11 点再产生一确认（固化在 11 点）
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse later = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));
        service.confirm(later.reservationId(), new ReservationActionRequest("req-k2"));

        // 次日 00:00:30：23:59 创建的预占最晚 00:00:59 才到期，尚不能固化
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:30Z"));
        assertFalse(service.queryDailySnapshot("act1", DAY).frozen());

        // 次日 00:01 UTC：彻底终结，一次固化
        mutableClock().setInstant(Instant.parse("2026-09-23T00:01:00Z"));
        DailySnapshotResponse frozen = service.queryDailySnapshot("act1", DAY);
        assertTrue(frozen.frozen());
        assertEquals(1, frozen.hours().get(10).confirmedCount());
        assertEquals(1, frozen.hours().get(10).cancelledCount());
        assertEquals(1, frozen.hours().get(11).confirmedCount());
        assertEquals(0, frozen.hours().get(11).cancelledCount());
        assertEquals(24, frozen.hours().size());

        // 固化后修改活动配置，已结算快照不变化
        ActivityResponse current = service.getActivity("act1");
        service.updateActivity("act1", new UpdateActivityRequest(
                "req-up-after-freeze", 4, budgetsOf(4, 0, 0, 10, 4), current.version()));
        DailySnapshotResponse reread = service.queryDailySnapshot("act1", DAY);
        assertTrue(reread.frozen());
        assertEquals(1, reread.hours().get(10).confirmedCount());
        assertEquals(1, reread.hours().get(10).cancelledCount());
        assertEquals(1, reread.hours().get(11).confirmedCount());
    }

    @Test
    @DisplayName("预占历史：区间左闭右开，按创建时刻与编号稳定排序；null 字段语义稳定")
    void historyQuery_halfOpenRangeStableSort() {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 5, singleHourBudget(10, 5)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, "act1"));
        service.createCampaign(new CreateCampaignRequest("req-c2", "c2", 100, 100));

        ReservationResponse a = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        mutableClock().setInstant(Instant.parse("2026-09-22T10:00:01Z"));
        ReservationResponse b = service.apply(new ApplyExposureRequest("req-a2", "c2", "v9"));

        ReservationHistoryResponse page = service.queryHistory(null, null, DAY, DAY.plusDays(1), 100);
        assertEquals(2, page.total());
        assertEquals(100, page.limit());
        assertEquals(a.reservationId(), page.items().get(0).reservationId());
        assertEquals(b.reservationId(), page.items().get(1).reservationId());
        assertEquals("act1", page.items().get(0).activityId());
        assertEquals(10, page.items().get(0).utcHour());
        assertNull(page.items().get(1).activityId());
        assertNull(page.items().get(1).utcHour());

        // 按公告过滤；上界（次日）左闭右开不含次日数据
        ReservationHistoryResponse onlyC1 = service.queryHistory("c1", null, DAY, DAY.plusDays(1), 100);
        assertEquals(1, onlyC1.total());
        assertEquals("c1", onlyC1.items().get(0).campaignId());
    }

    @Test
    @DisplayName("公告侧总额度先于小时额度判满：任一失败整批回滚，不留半成品预占")
    void totalCapFullBeforeHour_wholeBatchRollsBack() {
        // 活动小时预算充足，但公告当日总额只有 1
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 10, singleHourBudget(10, 10)));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 1, 100, "act1"));

        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ApiException ex = assertApi(429, "TOTAL_QUOTA_EXHAUSTED",
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v2")));
        assertEquals(2, ex.getDetails().get("actualUsed"));
        assertEquals(1, ex.getDetails().get("requiredCap"));
        assertEquals(1, ex.getDetails().get("difference"));

        // 公告满额没有多占活动小时名额，也没有残留预占
        assertEquals(1, service.queryHourDiagnostic("act1", DAY).hours().get(10).used());
        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'c1'", Integer.class);
        assertEquals(1, reservations);
        assertNotNull(service.getReservation(service.queryHistory("c1", null, null, null, 10)
                .items().get(0).reservationId()));
    }
}
