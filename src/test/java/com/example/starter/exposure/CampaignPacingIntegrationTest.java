package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayConfigRequest;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignCampaignActivityRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaExceededException;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
import com.example.starter.exposure.web.ReservationResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
 * 活动匀速投放 H2（MODE=MySQL）集成测试：小时预占、429 下一可用小时、
 * expectedVersion 配置、活动迁移固化、三侧结算、日结快照冻结、历史查询与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:exposure_cap_pacing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CampaignPacingIntegrationTest {

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
    @Autowired
    MockMvc mockMvc;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM activity_hour_snapshot");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 构造辅助 ----

    /** 构造 24 小时预算：按 hourBudgets（成对：小时, 预算）赋值，其余为 0。 */
    private List<Integer> budgets(Object... hourBudgets) {
        int[] hours = new int[24];
        for (int i = 0; i < hourBudgets.length; i += 2) {
            hours[(Integer) hourBudgets[i]] = (Integer) hourBudgets[i + 1];
        }
        List<Integer> list = new ArrayList<>(24);
        for (int v : hours) {
            list.add(v);
        }
        return list;
    }

    private int total(List<Integer> hourlyBudgets) {
        return hourlyBudgets.stream().mapToInt(Integer::intValue).sum();
    }

    private void createActivity(String requestId, String activityId) {
        service.createActivity(new CreateActivityRequest(requestId, activityId));
    }

    private ActivityDayConfigResponse configure(String requestId, String activityId, LocalDate date,
                                                List<Integer> hourlyBudgets, Integer expectedVersion) {
        return service.configureActivityDay(activityId, date,
                new ActivityDayConfigRequest(requestId, total(hourlyBudgets),
                        hourlyBudgets, expectedVersion));
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private void assign(String requestId, String campaignId, String activityId) {
        service.assignCampaignActivity(campaignId,
                new AssignCampaignActivityRequest(requestId, activityId));
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    private ApiException assertApiError(int httpStatus, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(httpStatus, ex.getStatus().value());
        return ex;
    }

    private int hourLedgerUsed(String activityId, LocalDate date, int hour) {
        Integer value = jdbc.queryForObject(
                "SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                Integer.class, activityId, java.sql.Date.valueOf(date), hour);
        return value == null ? 0 : value;
    }

    // ---- 主流程 ----

    @Test
    @DisplayName("主流程：活动小时名额原子预占，三侧额度同时占用并在响应中固化活动与小时")
    void apply_reservesActivityHourAndAllThreeSides() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 2), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");

        ReservationResponse r1 = apply("req-apply-1", "cmp1", "v1");
        assertEquals("act1", r1.activityId());
        assertEquals(10, r1.utcHour());
        assertEquals(DAY, r1.utcDate());
        ReservationResponse r2 = apply("req-apply-2", "cmp1", "v2");
        assertNotNull(r2.reservationId());

        assertEquals(2, hourLedgerUsed("act1", DAY, 10));
        QuotaResponse quota = service.queryQuota("cmp1", null, DAY);
        assertEquals(2, quota.usedTotal());
        assertEquals("act1", quota.activityId());
        assertEquals(10, quota.utcHour());
        assertEquals(2, quota.activityHourCap());
        assertEquals(2, quota.usedActivityHour());
        assertEquals(0, quota.remainingActivityHour());

        ActivityDayDiagnosticsResponse diag = service.activityDayDiagnostics("act1", DAY);
        assertEquals(24, diag.hours().size());
        assertEquals(2, diag.hours().get(10).used());
        assertEquals(0, diag.hours().get(10).remaining());
        // 当日存在配置，因此每个小时 configured=true；未分配预算的小时 budget=0
        assertTrue(diag.hours().get(11).configured());
        assertEquals(0, diag.hours().get(11).budget());
        assertEquals(DAY.atStartOfDay().plusHours(10).toInstant(ZoneOffset.UTC).toEpochMilli(),
                diag.hours().get(10).startAtUtc());
        assertEquals(DAY.atStartOfDay().plusHours(11).toInstant(ZoneOffset.UTC).toEpochMilli(),
                diag.hours().get(10).endAtUtc());
    }

    @Test
    @DisplayName("小时额度耗尽返回 429：公告与访客侧名额不消耗，给出下一可用小时与实际/要求/差额")
    void hourExhausted_returns429WithNextHourAndConsumesNothing() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 1, 12, 1), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");

        apply("req-apply-1", "cmp1", "v1");
        QuotaExceededException ex = (QuotaExceededException) assertApiError(429,
                () -> apply("req-apply-2", "cmp1", "v2"));
        assertEquals(QuotaExceededException.Reason.ACTIVITY_HOUR_EXHAUSTED, ex.getReason());
        assertEquals(1L, ex.getActualValue());
        assertEquals(1L, ex.getRequiredValue());
        assertEquals(0L, ex.getDifferenceValue());
        long hour12Start = DAY.atStartOfDay().plusHours(12).toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals(hour12Start, ex.getNextAvailableAtUtc());

        // 活动小时仍为 1；公告总额与访客侧均未增加；无第二张预占单
        assertEquals(1, hourLedgerUsed("act1", DAY, 10));
        assertEquals(1, service.queryQuota("cmp1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("cmp1", "v2", DAY).usedVisitor());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class));
    }

    @Test
    @DisplayName("当日后续小时均无余量时下一可用时刻为 null（不臆测次日配置）")
    void hourExhausted_withoutLaterBudget_nextAvailableNull() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 1), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");
        apply("req-apply-1", "cmp1", "v1");

        QuotaExceededException ex = (QuotaExceededException) assertApiError(429,
                () -> apply("req-apply-2", "cmp1", "v2"));
        assertEquals(QuotaExceededException.Reason.ACTIVITY_HOUR_EXHAUSTED, ex.getReason());
        assertNull(ex.getNextAvailableAtUtc());
    }

    @Test
    @DisplayName("未配置当日小时预算不得申请：429 且三侧均不消耗，失败不占幂等键")
    void dayNotConfigured_applyRejectedAndKeyReusable() {
        createActivity("req-a", "act1");
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");

        QuotaExceededException ex = (QuotaExceededException) assertApiError(429,
                () -> apply("key-apply", "cmp1", "v1"));
        assertEquals(QuotaExceededException.Reason.ACTIVITY_DAY_NOT_CONFIGURED, ex.getReason());
        assertEquals(0, service.queryQuota("cmp1", null, DAY).usedTotal());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class));

        // 补齐配置后同一 requestId 同参可成功（失败不占键）
        configure("req-cfg", "act1", DAY, budgets(10, 1), null);
        ReservationResponse r = apply("key-apply", "cmp1", "v1");
        assertEquals("RESERVED", r.status().name());
        assertEquals(1, hourLedgerUsed("act1", DAY, 10));
    }

    @Test
    @DisplayName("公告日总额先耗尽：429 区分原因，活动小时名额不被消耗")
    void campaignTotalExhausted_doesNotConsumeActivityHour() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 5), null);
        createCampaign("req-c", "cmp1", 1, 100);
        assign("req-assign", "cmp1", "act1");

        apply("req-apply-1", "cmp1", "v1");
        QuotaExceededException ex = (QuotaExceededException) assertApiError(429,
                () -> apply("req-apply-2", "cmp1", "v2"));
        assertEquals(QuotaExceededException.Reason.CAMPAIGN_DAILY_TOTAL_EXHAUSTED,
                ex.getReason());
        assertEquals(1, hourLedgerUsed("act1", DAY, 10), "活动小时不应被失败申请消耗");
    }

    @Test
    @DisplayName("时钟推进到下一有预算小时后可继续申请，小时归属随申请时刻固化")
    void nextHourBecomesAvailableAsClockAdvances() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 1, 12, 1), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");
        apply("req-apply-1", "cmp1", "v1");
        assertApiError(429, () -> apply("req-apply-2", "cmp1", "v2"));

        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:00Z"));
        // 10 点的预占此时已过期，申请前自动结算释放 h10；新预占固化到 h12
        ReservationResponse r = apply("req-apply-3", "cmp1", "v3");
        assertEquals(12, r.utcHour());
        assertEquals(0, hourLedgerUsed("act1", DAY, 10));
        assertEquals(1, hourLedgerUsed("act1", DAY, 12));
    }

    // ---- 确认/取消/过期的三侧结算 ----

    @Test
    @DisplayName("确认保持三侧占用；取消只释放同一固化小时；重复终态不重复计数")
    void confirmAndCancel_settleThreeSidesOnce() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 2), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");

        ReservationResponse r1 = apply("req-apply-1", "cmp1", "v1");
        ReservationResponse r2 = apply("req-apply-2", "cmp1", "v2");
        assertEquals("CONFIRMED", service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-ok-1")).status().name());
        assertEquals("CONFIRMED", service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-ok-1")).status().name());
        assertEquals("CANCELLED", service.cancel(r2.reservationId(),
                new ReservationActionRequest("req-x-1")).status().name());
        assertEquals("CANCELLED", service.cancel(r2.reservationId(),
                new ReservationActionRequest("req-x-2")).status().name());

        // 确认单持续占用 1，取消单释放 1
        assertEquals(1, hourLedgerUsed("act1", DAY, 10));
        assertEquals(1, service.queryQuota("cmp1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("cmp1", "v1", DAY).usedVisitor());
        assertEquals(0, service.queryQuota("cmp1", "v2", DAY).usedVisitor());

        // 取消腾出的名额可再次申请
        ReservationResponse r3 = apply("req-apply-3", "cmp1", "v3");
        assertEquals("RESERVED", r3.status().name());
        assertEquals(2, hourLedgerUsed("act1", DAY, 10));
    }

    @Test
    @DisplayName("过期释放预占固化的活动小时名额，结算后可重新申请")
    void expiry_releasesFrozenActivityHour() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 1), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");
        ReservationResponse r = apply("req-apply-1", "cmp1", "v1");

        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getReservation(r.reservationId()).status().name());
        assertEquals(0, hourLedgerUsed("act1", DAY, 10));
        ReservationResponse again = apply("req-apply-2", "cmp1", "v2");
        assertEquals("RESERVED", again.status().name());
        assertEquals(1, hourLedgerUsed("act1", DAY, 10));
    }

    // ---- 配置与 expectedVersion ----

    @Test
    @DisplayName("24 小时预算合计不等于日总额返回 400，错误含实际值/要求值/差额")
    void configure_sumMismatch_returns400() {
        createActivity("req-a", "act1");
        ApiException ex = assertApiError(400,
                () -> service.configureActivityDay("act1", DAY,
                        new ActivityDayConfigRequest("req-cfg", 10,
                                budgets(10, 4, 11, 3), null)));
        // 日总额取列表合计之外的值：dailyTotalCap=10 而列表合计 7
        assertTrue(ex.getMessage().contains("actual=7"));
        assertTrue(ex.getMessage().contains("required=10"));
        assertTrue(ex.getMessage().contains("difference=3"));
    }

    @Test
    @DisplayName("expectedVersion：版本不符 409；已开始小时不可下调，未来小时可下调；版本递增")
    void configure_versionCasAndStartedHourRule() {
        createActivity("req-a", "act1");
        ActivityDayConfigResponse v1 = configure("req-cfg-1", "act1", DAY,
                budgets(10, 2, 11, 2), null);
        assertEquals(1, v1.version());

        // 已存在却不传 expectedVersion → 409；错误版本 → 409
        assertApiError(409, () -> configure("req-cfg-2", "act1", DAY,
                budgets(10, 2, 11, 2), null));
        ApiException mismatch = assertApiError(409, () -> configure("req-cfg-3", "act1", DAY,
                budgets(10, 3, 11, 1), 99));
        assertTrue(mismatch.getMessage().contains("actual=1")
                && mismatch.getMessage().contains("required=99"));

        // 当前 10:00，h10 已开始不可下调；h11 未开始可下调（总额保持 4）
        assertApiError(409, () -> configure("req-cfg-4", "act1", DAY,
                budgets(10, 1, 11, 3), 1));
        ActivityDayConfigResponse v2 = configure("req-cfg-5", "act1", DAY,
                budgets(10, 2, 12, 2), 1);
        assertEquals(2, v2.version());
        assertEquals(0, v2.hourlyBudgets().get(11));
        assertEquals(2, v2.hourlyBudgets().get(12));

        // 同 requestId 同参重放首次结果（版本仍为 2，不再次递增）
        ActivityDayConfigResponse replay = configure("req-cfg-5", "act1", DAY,
                budgets(10, 2, 12, 2), 1);
        assertEquals(2, replay.version());
    }

    @Test
    @DisplayName("配置幂等：同键异参 409；不存在的活动/配置返回 404")
    void configure_idempotencyAndNotFound() {
        createActivity("req-a", "act1");
        configure("req-cfg", "act1", DAY, budgets(10, 1), null);
        assertApiError(409, () -> configure("req-cfg", "act1", DAY,
                budgets(10, 2), null));
        assertApiError(404, () -> service.getActivityDayConfig("act1", DAY.plusDays(1)));
        assertApiError(404, () -> service.activityDayDiagnostics("nope", DAY));
    }

    // ---- 活动迁移 ----

    @Test
    @DisplayName("活动迁移只影响新预占：既有预占固化原活动与小时，取消各释放各的固化小时")
    void campaignMigration_onlyAffectsNewReservations() {
        createActivity("req-a1", "act1");
        createActivity("req-a2", "act2");
        configure("req-cfg-1", "act1", DAY, budgets(10, 2), null);
        configure("req-cfg-2", "act2", DAY, budgets(10, 2), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign-1", "cmp1", "act1");

        ReservationResponse r1 = apply("req-apply-1", "cmp1", "v1");
        assertEquals("act1", r1.activityId());
        assertEquals(10, r1.utcHour());

        assign("req-assign-2", "cmp1", "act2");
        ReservationResponse r2 = apply("req-apply-2", "cmp1", "v2");
        assertEquals("act2", r2.activityId());
        assertEquals(10, r2.utcHour());
        // 历史单的固化归属不变
        assertEquals("act1", service.getReservation(r1.reservationId()).activityId());
        assertEquals(1, hourLedgerUsed("act1", DAY, 10));
        assertEquals(1, hourLedgerUsed("act2", DAY, 10));

        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x-1"));
        assertEquals(0, hourLedgerUsed("act1", DAY, 10));
        assertEquals(1, hourLedgerUsed("act2", DAY, 10), "另一活动小时不受影响");
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x-2"));
        assertEquals(0, hourLedgerUsed("act2", DAY, 10));

        // 移出活动后新预占不占活动名额，活动/小时字段为 null
        assign("req-assign-3", "cmp1", null);
        ReservationResponse r3 = apply("req-apply-3", "cmp1", "v3");
        assertNull(r3.activityId());
        assertNull(r3.utcHour());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_activity_hour_ledger WHERE used_hour > 0",
                Integer.class));
    }

    @Test
    @DisplayName("迁移到不存在活动 404 且不留半成品；迁移幂等同参重放、异参 409")
    void migrationValidationAndIdempotency() {
        createCampaign("req-c", "cmp1", 100, 100);
        assertApiError(404, () -> assign("req-assign-1", "cmp1", "ghost"));
        assertNull(service.queryQuota("cmp1", null, DAY).activityId());

        createActivity("req-a1", "act1");
        assign("req-assign-2", "cmp1", "act1");
        assign("req-assign-2", "cmp1", "act1"); // 同参重放
        assertApiError(409, () -> assign("req-assign-2", "cmp1", null)); // 异参
    }

    // ---- 日结快照 ----

    @Test
    @DisplayName("日结固化每小时确认/取消/过期数；已固化快照不随后续操作与配置变更改写")
    void dailySettlement_freezesSnapshot() {
        createActivity("req-a", "act1");
        configure("req-cfg-1", "act1", DAY, budgets(10, 5), null);
        createCampaign("req-c", "cmp1", 100, 100);
        assign("req-assign", "cmp1", "act1");

        ReservationResponse r1 = apply("req-apply-1", "cmp1", "v1");
        ReservationResponse r2 = apply("req-apply-2", "cmp1", "v2");
        ReservationResponse r3 = apply("req-apply-3", "cmp1", "v3");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-ok-1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x-1"));
        // r3 保持 RESERVED

        ActivityDaySettlementResponse s1 = service.settleActivityDay("act1", DAY);
        var h10first = s1.hours().get(10);
        assertFalse(h10first.frozen());
        assertEquals(1, h10first.confirmedCount());
        assertEquals(1, h10first.cancelledCount());
        assertEquals(0, h10first.expiredCount());
        assertEquals(1, h10first.configVersion());

        // 推进时间使 r3 过期，再次日结：快照已固化，计数不变，frozen=true
        mutableClock().advanceMillis(60_000L);
        ActivityDaySettlementResponse s2 = service.settleActivityDay("act1", DAY);
        var h10frozen = s2.hours().get(10);
        assertTrue(h10frozen.frozen());
        assertEquals(1, h10frozen.confirmedCount());
        assertEquals(1, h10frozen.cancelledCount());
        assertEquals(0, h10frozen.expiredCount(), "过期发生在固化之后，不改写快照");
        assertEquals("EXPIRED", service.getReservation(r3.reservationId()).status().name());

        // 配置上调（h10 已开始只能上调），版本变化；快照版本仍固化为 1，计数不变
        configure("req-cfg-2", "act1", DAY, budgets(10, 6), 1);
        ActivityDaySettlementResponse s3 = service.settleActivityDay("act1", DAY);
        var h10afterConfigChange = s3.hours().get(10);
        assertEquals(1, h10afterConfigChange.configVersion(), "配置版本快照不改写");
        assertEquals(6, h10afterConfigChange.budget(), "预算为展示值，取当前配置");
        assertEquals(1, h10afterConfigChange.confirmedCount());
    }

    @Test
    @DisplayName("未配置日的日结：24 小时均存在、configured 诊断为 false、快照 configVersion 为 null")
    void settlementWithoutConfig_allHoursPresent() {
        createActivity("req-a", "act1");
        ActivityDaySettlementResponse settlement = service.settleActivityDay("act1", DAY);
        assertEquals(24, settlement.hours().size());
        assertTrue(settlement.hours().stream().allMatch(h ->
                h.confirmedCount() == 0 && h.cancelledCount() == 0 && h.expiredCount() == 0
                        && h.configVersion() == null && !h.frozen()));

        ActivityDayDiagnosticsResponse diag = service.activityDayDiagnostics("act1", DAY);
        assertTrue(diag.hours().stream().allMatch(h ->
                !h.configured() && h.budget() == 0 && h.used() == 0 && h.remaining() == 0));
    }

    // ---- 历史查询 ----

    @Test
    @DisplayName("历史查询：按活动/访客与左闭右开申请时刻区间过滤，按申请时刻降序，limit 生效，只读")
    void history_filtersHalfOpenWindowAndOrdersDesc() {
        createActivity("req-a1", "act1");
        createActivity("req-a2", "act2");
        configure("req-cfg-1", "act1", DAY, budgets(10, 5, 11, 5), null);
        configure("req-cfg-2", "act2", DAY, budgets(10, 5, 11, 5, 12, 5), null);
        createCampaign("req-c", "cmp1", 100, 100);

        assign("req-assign-1", "cmp1", "act1");
        ReservationResponse ten = apply("req-apply-1", "cmp1", "v1");
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse eleven = apply("req-apply-2", "cmp1", "v2");
        assign("req-assign-2", "cmp1", "act2");
        mutableClock().setInstant(Instant.parse("2026-09-22T12:00:00Z"));
        ReservationResponse twelve = apply("req-apply-3", "cmp1", "v1");

        long t10 = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli();
        long t11 = Instant.parse("2026-09-22T11:00:00Z").toEpochMilli();
        long t12 = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli();

        // 左闭右开 [10:00, 12:00)：含 10 点与 11 点，不含 12 点（act2）
        ReservationHistoryResponse window = service.findReservations(
                null, "cmp1", null, t10, t12, 50);
        assertEquals(2, window.totalReturned());
        assertEquals(eleven.reservationId(), window.reservations().get(0).reservationId());
        assertEquals(ten.reservationId(), window.reservations().get(1).reservationId());

        // 活动过滤只返回固化到 act1 的预占（迁移前两张）
        ReservationHistoryResponse byActivity = service.findReservations(
                "act1", null, null, null, null, 50);
        assertEquals(2, byActivity.totalReturned());
        assertTrue(byActivity.reservations().stream()
                .allMatch(r -> "act1".equals(r.activityId())));

        // 边界 [11:00, 11:00) 非法 → 400
        ApiException bad = assertApiError(400,
                () -> service.findReservations(null, null, null, t11, t10, 50));
        assertTrue(bad.getMessage().contains("fromCreatedAtUtc"));

        // limit=1 返回最新一张
        ReservationHistoryResponse limited = service.findReservations(
                null, "cmp1", null, null, null, 1);
        assertEquals(1, limited.totalReturned());
        assertEquals(twelve.reservationId(), limited.reservations().get(0).reservationId());
    }

    // ---- 创建活动幂等与重复 ----

    @Test
    @DisplayName("创建活动：重复 409；同键同参重放首次结果")
    void createActivity_duplicateAndReplay() {
        createActivity("req-a1", "act1");
        createActivity("req-a1", "act1");
        assertApiError(409, () -> createActivity("req-a2", "act1"));
        assertApiError(409, () -> createActivity("req-a1", "act2"));
    }

    // ---- HTTP 语义 ----

    @Test
    @DisplayName("HTTP：活动配置 201、迁移与申请 200/201、小时耗尽 429 含原因与下一可用小时、预算合计不符 400")
    void httpSemantics_pacingEndpointsAnd429Body() throws Exception {
        long hour12Start = DAY.atStartOfDay().plusHours(12).toInstant(ZoneOffset.UTC).toEpochMilli();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-act\",\"activityId\":\"hact\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.activityId").value("hact"));

        String budgets = new StringBuilder("[")
                .append("0,0,0,0,0,0,0,0,0,0,1,0,1")
                .append(",0,0,0,0,0,0,0,0,0,0,0]").toString();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/activities/hact/day-configs/2026-09-22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cfg\",\"dailyTotalCap\":2,\"hourlyBudgets\":"
                                + budgets + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value(1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cmp\",\"campaignId\":\"hcmp\",\"dailyTotalCap\":100,"
                                + "\"perVisitorDailyCap\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/campaigns/hcmp/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-assign\",\"activityId\":\"hact\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.activityId").value("hact"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-apply-1\",\"campaignId\":\"hcmp\",\"visitorId\":\"u1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.utcHour").value(10));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-apply-2\",\"campaignId\":\"hcmp\",\"visitorId\":\"u2\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.reason").value("ACTIVITY_HOUR_EXHAUSTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.actualValue").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.requiredValue").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.differenceValue").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.nextAvailableAtUtc").value(hour12Start));

        // 24 小时预算合计不符 → 400（4 个 1 合计 4，日总额 5）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/exposure/activities/hact/day-configs/2026-09-23")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-bad\",\"dailyTotalCap\":5,\"hourlyBudgets\":"
                                + "[1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
}
