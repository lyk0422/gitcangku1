package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityBudgetResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettlementResponse;
import com.example.starter.exposure.web.UpdateActivityBudgetRequest;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活动匀速投放 H2（MODE=MySQL）集成测试：小时预算原子预占、三侧结算、
 * 配置版本与已开始小时保护、活动迁移固化、日结快照与幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
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

    /** 起点为 UTC 10:00，当前小时为 10。 */
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
    MockMvc mockMvc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM activity_day_snapshot");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM activity");
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

    // ---- 测试辅助 ----

    /** 构造 24 小时预算；hourAndBudget 为 (小时, 预算) 对，其余小时为 0。 */
    private static List<Integer> budgets(int... hourAndBudget) {
        List<Integer> list = new ArrayList<>(Collections.nCopies(24, 0));
        for (int i = 0; i < hourAndBudget.length; i += 2) {
            list.set(hourAndBudget[i], hourAndBudget[i + 1]);
        }
        return list;
    }

    private static int sum(List<Integer> budgets) {
        return budgets.stream().mapToInt(Integer::intValue).sum();
    }

    private void createActivity(String requestId, String activityId) {
        service.createActivity(new CreateActivityRequest(requestId, activityId));
    }

    private ActivityBudgetResponse configureBudget(String requestId, String activityId,
                                                   LocalDate date, List<Integer> budgets,
                                                   int expectedVersion) {
        return service.updateBudget(activityId, new UpdateActivityBudgetRequest(
                requestId, date, sum(budgets), budgets, expectedVersion));
    }

    private void createCampaign(String requestId, String campaignId) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, 100, 100));
    }

    private void migrate(String requestId, String campaignId, String activityId) {
        service.migrateCampaignActivity(campaignId, new MigrateActivityRequest(requestId, activityId));
    }

    private ActivityDiagnosticsResponse diagnostics(String activityId) {
        return service.getHourDiagnostics(activityId, DAY);
    }

    private void assertStatus(Runnable action, int expected) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value(), "期望状态码 " + expected + "，实际信息: " + ex.getMessage());
    }

    // ---- 主流程 ----

    @Test
    @DisplayName("归入活动后申请：为当前小时预占活动名额并固化活动与小时归属")
    void apply_withActivity_reservesCurrentHourSlot() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 2), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        assertEquals("A", r.activityId());
        assertEquals(10, r.hourOfDay());
        assertEquals("RESERVED", r.status().name());

        var hour = diagnostics("A").hours().get(10);
        assertEquals(2, hour.budget());
        assertEquals(1, hour.reservedCount());
        assertEquals(0, hour.confirmedCount());
        assertEquals(1, hour.remaining());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("确认同时结算三侧：活动 reserved 转 confirmed，公告与访客额度保持占用")
    void confirm_settlesActivitySide() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 2), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));

        var hour = diagnostics("A").hours().get(10);
        assertEquals(0, hour.reservedCount());
        assertEquals(1, hour.confirmedCount());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("取消释放三侧：活动 reserved 转 cancelled，公告与访客额度释放")
    void cancel_releasesActivitySide() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 1), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        service.cancel(r.reservationId(), new ReservationActionRequest("req-x1"));

        var hour = diagnostics("A").hours().get(10);
        assertEquals(0, hour.reservedCount());
        assertEquals(1, hour.cancelledCount());
        assertEquals(0, hour.expiredCount());
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());

        // 活动小时名额释放后可再次申请
        ReservationResponse second = service.apply(new ApplyExposureRequest("req-a2", "c1", "v1"));
        assertEquals("RESERVED", second.status().name());
    }

    @Test
    @DisplayName("过期释放同一小时预占：活动 reserved 转 expired，重复终态不重复计数")
    void expire_releasesSameHourSlot() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 1), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        mutableClock().advanceMillis(60_000L);
        // 任意操作触发结算：确认过期单 409（该失败事务回滚，不完成结算）
        assertStatus(() -> service.confirm(r.reservationId(), new ReservationActionRequest("req-e1")), 409);
        // 明细查询完成过期结算：活动 reserved 转 expired
        assertEquals("EXPIRED", service.getReservation(r.reservationId()).status().name());

        var hour = diagnostics("A").hours().get(10);
        assertEquals(0, hour.reservedCount());
        assertEquals(1, hour.expiredCount());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedTotal());

        // 重复结算不重复计数
        service.getReservation(r.reservationId());
        assertEquals(1, diagnostics("A").hours().get(10).expiredCount());
    }

    // ---- 失败分支 ----

    @Test
    @DisplayName("小时预算耗尽返回 429 并给出下一可用小时，不消耗公告与访客名额；失败不占幂等键")
    void hourExhausted_429WithNextAvailableHour_noSideEffect_noKeyOccupied() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 1, 11, 2), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v2")));
        assertEquals(429, ex.getStatus().value());
        Map<String, Object> details = ex.getDetails();
        assertNotNull(details);
        assertEquals(10, details.get("hourOfDay"));
        assertEquals(1, details.get("hourBudget"));
        assertEquals(1, details.get("hourOccupied"));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) details.get("nextAvailableHour");
        assertNotNull(next);
        assertEquals("2026-09-22", next.get("utcDate"));
        assertEquals(11, next.get("hourOfDay"));
        assertEquals(2, next.get("remaining"));

        // 公告与访客两侧名额未被消耗
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v2", DAY).usedVisitor());
        assertEquals(1, diagnostics("A").hours().get(10).reservedCount());

        // 失败不占键：上调当前小时预算后同键同参重试成功
        configureBudget("req-b2", "A", DAY, budgets(10, 2, 11, 2), 1);
        ReservationResponse retried = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals("RESERVED", retried.status().name());
    }

    @Test
    @DisplayName("当日及后续均无可用小时时 429 的 nextAvailableHour 为 null")
    void hourExhausted_noNextAvailableHour_returnsNull() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 1), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v2")));
        assertEquals(429, ex.getStatus().value());
        assertTrue(ex.getDetails().containsKey("nextAvailableHour"));
        assertNull(ex.getDetails().get("nextAvailableHour"));
    }

    @Test
    @DisplayName("活动未配置当日小时预算时不得申请：409 且不消耗任何名额")
    void unconfiguredDay_applyRejected409() {
        createActivity("req-act", "A");
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));
        assertEquals(409, ex.getStatus().value());
        assertEquals("A", ex.getDetails().get("activityId"));
        assertEquals("2026-09-22", ex.getDetails().get("utcDate"));

        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("预算校验：合计不等于日总额 400 且给出实际值与要求值；负数与长度非法 400；活动不存在 404")
    void budgetValidation_failures() {
        createActivity("req-act", "A");

        // 合计 5 != 日总额 6
        ApiException sumEx = assertThrows(ApiException.class, () -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b1", DAY, 6, budgets(10, 5), 0)));
        assertEquals(400, sumEx.getStatus().value());
        assertEquals(6, sumEx.getDetails().get("requiredDailyTotal"));
        assertEquals(5, sumEx.getDetails().get("actualHourBudgetSum"));
        assertEquals(-1, sumEx.getDetails().get("difference"));

        // 负数预算 → 400 并指出具体小时；长度不为 24 → 400 并给出实际长度
        List<Integer> negative = budgets(10, 1);
        negative.set(11, -1);
        ApiException negEx = assertThrows(ApiException.class, () -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b2", DAY, 1, negative, 0)));
        assertEquals(400, negEx.getStatus().value());
        assertEquals(11, negEx.getDetails().get("hourOfDay"));

        ApiException sizeEx = assertThrows(ApiException.class, () -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b3", DAY, 0, List.of(0, 0), 0)));
        assertEquals(400, sizeEx.getStatus().value());
        assertEquals(24, sizeEx.getDetails().get("requiredSize"));
        assertEquals(2, sizeEx.getDetails().get("actualSize"));

        // 不存在的活动 404
        assertStatus(() -> service.updateBudget("nope",
                new UpdateActivityBudgetRequest("req-b4", DAY, 0, budgets(), 0)), 404);

        // 失败不占键：修正合计后同键成功
        ActivityBudgetResponse ok = service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b1", DAY, 5, budgets(10, 5), 0));
        assertEquals(5, ok.dailyTotal());
        assertEquals(1, ok.version());
    }

    @Test
    @DisplayName("expectedVersion 乐观锁：版本不符 409；变更成功版本 +1；同键重放不重复加版本")
    void expectedVersion_optimisticLocking() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 5), 0);

        ApiException ex = assertThrows(ApiException.class, () -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b2", DAY, 5, budgets(10, 5), 0)));
        assertEquals(409, ex.getStatus().value());
        assertEquals(0, ex.getDetails().get("expectedVersion"));
        assertEquals(1, ex.getDetails().get("currentVersion"));

        ActivityBudgetResponse updated = configureBudget("req-b2", "A", DAY, budgets(10, 5), 1);
        assertEquals(2, updated.version());
        assertEquals(2, service.getActivity("A").version());

        // 同键同参重放：返回首次结果，版本不再增加
        ActivityBudgetResponse replay = service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b2", DAY, 5, budgets(10, 5), 1));
        assertEquals(2, replay.version());
        assertEquals(2, service.getActivity("A").version());

        // 同键异参 409
        assertStatus(() -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b2", DAY, 6, budgets(10, 6), 2)), 409);
    }

    @Test
    @DisplayName("已开始小时不可下调：过去小时与当前小时下调 409，未来小时可下调，当前小时可上调；失败不产生半成品")
    void startedHourCannotDecrease_andNoPartialUpdate() {
        createActivity("req-act", "A");
        // 当前 UTC 10 点：hour9 已过去、hour10 当前、hour11 未来
        configureBudget("req-b1", "A", DAY, budgets(9, 2, 10, 5, 11, 3), 0);

        // 过去小时下调 409
        ApiException past = assertThrows(ApiException.class, () -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b2", DAY, 9, budgets(9, 1, 10, 5, 11, 3), 1)));
        assertEquals(409, past.getStatus().value());
        assertEquals(9, past.getDetails().get("hourOfDay"));
        assertEquals(2, past.getDetails().get("oldBudget"));
        assertEquals(1, past.getDetails().get("newBudget"));

        // 当前小时下调 409
        assertStatus(() -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b3", DAY, 9, budgets(9, 2, 10, 4, 11, 3), 1)), 409);

        // 失败不产生半成品：配置保持原值，版本未变
        ActivityBudgetResponse current = service.getBudget("A", DAY);
        assertEquals(budgets(9, 2, 10, 5, 11, 3), current.hourBudgets());
        assertEquals(10, current.dailyTotal());
        assertEquals(1, service.getActivity("A").version());

        // 当前小时上调允许
        ActivityBudgetResponse up = configureBudget("req-b4", "A", DAY, budgets(9, 2, 10, 6, 11, 3), 1);
        assertEquals(2, up.version());

        // 未来小时下调允许
        ActivityBudgetResponse down = configureBudget("req-b5", "A", DAY, budgets(9, 2, 10, 6, 11, 1), 2);
        assertEquals(3, down.version());
        assertEquals(budgets(9, 2, 10, 6, 11, 1), service.getBudget("A", DAY).hourBudgets());

        // 过去日全部小时视为已开始：首次配置后任何下调 409
        LocalDate yesterday = DAY.minusDays(1);
        configureBudget("req-b6", "A", yesterday, budgets(0, 5), 3);
        assertStatus(() -> service.updateBudget("A",
                new UpdateActivityBudgetRequest("req-b7", yesterday, 4, budgets(0, 4), 4)), 409);
    }

    // ---- 活动迁移 ----

    @Test
    @DisplayName("活动迁移只影响新预占：既有预占固化原活动与小时，确认仍结算到原活动")
    void migration_freezesExistingReservations() {
        createActivity("req-a", "A");
        createActivity("req-b", "B");
        configureBudget("req-ba", "A", DAY, budgets(10, 1), 0);
        configureBudget("req-bb", "B", DAY, budgets(10, 1), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("A", r1.activityId());

        migrate("req-m2", "c1", "B");
        assertEquals("B", service.getCampaign("c1").activityId());

        // 新预占进入活动 B 的小时账目
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals("B", r2.activityId());
        assertEquals(1, diagnostics("A").hours().get(10).reservedCount());
        assertEquals(1, diagnostics("B").hours().get(10).reservedCount());

        // 既有预占确认仍结算到原活动 A
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(1, diagnostics("A").hours().get(10).confirmedCount());
        assertEquals(0, diagnostics("B").hours().get(10).confirmedCount());

        // 迁出活动后申请不再占用活动名额
        migrate("req-m3", "c1", null);
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));
        assertNull(r3.activityId());
        assertNull(r3.hourOfDay());

        // 迁移到不存在的活动 404；迁移不存在的公告 404
        assertStatus(() -> migrate("req-m4", "c1", "nope"), 404);
        assertStatus(() -> migrate("req-m5", "nope", "A"), 404);

        // 迁移幂等：同键同参重放，异参 409
        service.migrateCampaignActivity("c1", new MigrateActivityRequest("req-m3", null));
        assertStatus(() -> migrate("req-m3", "c1", "A"), 409);
    }

    // ---- 日结快照 ----

    @Test
    @DisplayName("日结固化每小时确认/取消/过期快照：后续配置变更不改写；未终日 409；无活动日记零")
    void settlement_snapshotStableAcrossConfigChanges() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 3), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        service.apply(new ApplyExposureRequest("req-a3", "c1", "v3")); // 将过期

        // 未终日：当日与日终后 59 秒均 409，并给出可结算时刻
        assertStatus(() -> service.getSettlement("A", DAY), 409);
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:59Z"));
        ApiException early = assertThrows(ApiException.class,
                () -> service.getSettlement("A", DAY));
        assertEquals(409, early.getStatus().value());
        assertEquals(Instant.parse("2026-09-23T00:01:00Z").toEpochMilli(),
                early.getDetails().get("settleableAfterUtc"));

        // 到达可结算时刻：固化快照，过期单在日结时结算
        mutableClock().setInstant(Instant.parse("2026-09-24T00:00:00Z"));
        SettlementResponse settlement = service.getSettlement("A", DAY);
        assertEquals(24, settlement.hours().size());
        var hour10 = settlement.hours().get(10);
        assertEquals(1, hour10.confirmedCount());
        assertEquals(1, hour10.cancelledCount());
        assertEquals(1, hour10.expiredCount());
        assertEquals(0, settlement.hours().get(0).confirmedCount());
        long snapshottedAt = settlement.snapshottedAtUtc();
        assertEquals(mutableClock().instant().toEpochMilli(), snapshottedAt);

        // 过期释放后公告侧额度只剩已确认的 1
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 配置变更（过去日仅可上调）不改写已结算快照
        configureBudget("req-b2", "A", DAY, budgets(10, 9), 1);
        SettlementResponse again = service.getSettlement("A", DAY);
        assertEquals(1, again.hours().get(10).confirmedCount());
        assertEquals(1, again.hours().get(10).cancelledCount());
        assertEquals(1, again.hours().get(10).expiredCount());
        assertEquals(snapshottedAt, again.snapshottedAtUtc());

        // 无活动的日期：24 小时全零快照
        SettlementResponse empty = service.getSettlement("A", DAY.minusDays(1));
        assertEquals(24, empty.hours().size());
        assertEquals(0, empty.hours().get(10).confirmedCount());

        // 当日未结束不可日结
        assertStatus(() -> service.getSettlement("A", DAY.plusDays(2)), 409);
        // 不存在的活动 404
        assertStatus(() -> service.getSettlement("nope", DAY), 404);
    }

    // ---- 历史与诊断查询 ----

    @Test
    @DisplayName("历史查询按创建时刻与编号稳定升序；诊断与历史为纯读取，不改变状态")
    void historyAndDiagnostics_areStableAndReadOnly() {
        createActivity("req-act", "A");
        configureBudget("req-b1", "A", DAY, budgets(10, 3), 0);
        createCampaign("req-c1", "c1");
        migrate("req-m1", "c1", "A");
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        List<ReservationResponse> history = service.listActivityReservations("A", DAY, null);
        assertEquals(2, history.size());
        assertEquals(r1.reservationId(), history.get(0).reservationId());
        assertEquals(r2.reservationId(), history.get(1).reservationId());
        assertEquals("CONFIRMED", history.get(0).status().name());
        assertEquals("RESERVED", history.get(1).status().name());

        List<ReservationResponse> hourOnly = service.listActivityReservations("A", DAY, 10);
        assertEquals(2, hourOnly.size());
        assertEquals(0, service.listActivityReservations("A", DAY, 11).size());

        ActivityDiagnosticsResponse before = diagnostics("A");
        // 纯读取：重复诊断、历史、明细查询后账目不变
        service.getActivity("A");
        service.getBudget("A", DAY);
        service.getCampaign("c1");
        service.listActivityReservations("A", DAY, null);
        ActivityDiagnosticsResponse after = diagnostics("A");
        assertEquals(before.hours().get(10).reservedCount(), after.hours().get(10).reservedCount());
        assertEquals(before.hours().get(10).confirmedCount(), after.hours().get(10).confirmedCount());
        assertEquals(1, after.hours().get(10).reservedCount());
        assertEquals(1, after.hours().get(10).confirmedCount());
        assertTrue(after.configured());
        assertEquals(3, after.dailyTotal());

        assertStatus(() -> service.listActivityReservations("nope", DAY, null), 404);
        assertStatus(() -> service.getHourDiagnostics("nope", DAY), 404);
        assertStatus(() -> service.getBudget("A", DAY.plusDays(1)), 404);
    }

    // ---- 幂等 ----

    @Test
    @DisplayName("活动与迁移幂等：同键同参重放首次结果，异参 409")
    void idempotency_activityOperations() {
        ActivityResponse a1 = service.createActivity(new CreateActivityRequest("req-act", "A"));
        ActivityResponse a1Replay = service.createActivity(new CreateActivityRequest("req-act", "A"));
        assertEquals(a1.activityId(), a1Replay.activityId());
        assertEquals(a1.createdAtUtc(), a1Replay.createdAtUtc());
        assertStatus(() -> service.createActivity(new CreateActivityRequest("req-act", "B")), 409);
        assertStatus(() -> service.createActivity(new CreateActivityRequest("req-act2", "A")), 409);
    }

    // ---- HTTP 语义 ----

    @Test
    @DisplayName("HTTP 语义：活动创建 201、预算长度/合计非法 400、版本不符 409、小时耗尽 429 含下一可用小时")
    void httpSemantics_activityEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"activityId\":\"A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("A"))
                .andExpect(jsonPath("$.version").value(0));

        // 小时预算长度不为 24 → 400
        mockMvc.perform(put("/api/exposure/activities/A/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"utcDate\":\"2026-09-22\",\"dailyTotal\":0,"
                                + "\"hourBudgets\":[0,0],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        // 合计不等于日总额 → 400，且返回实际值与要求值
        StringBuilder budgetsJson = new StringBuilder("[");
        for (int i = 0; i < 24; i++) {
            budgetsJson.append(i == 10 ? 5 : 0).append(i < 23 ? "," : "");
        }
        budgetsJson.append("]");
        mockMvc.perform(put("/api/exposure/activities/A/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"utcDate\":\"2026-09-22\",\"dailyTotal\":6,"
                                + "\"hourBudgets\":" + budgetsJson + ",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.requiredDailyTotal").value(6))
                .andExpect(jsonPath("$.details.actualHourBudgetSum").value(5));

        // 合法配置 → 200，版本 1
        mockMvc.perform(put("/api/exposure/activities/A/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"utcDate\":\"2026-09-22\",\"dailyTotal\":1,"
                                + "\"hourBudgets\":[0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0],"
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dailyTotal").value(1));

        // 版本不符 → 409
        mockMvc.perform(put("/api/exposure/activities/A/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"utcDate\":\"2026-09-22\",\"dailyTotal\":1,"
                                + "\"hourBudgets\":[0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0],"
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.currentVersion").value(1));

        // 缺少 utcDate 参数 → 400
        mockMvc.perform(get("/api/exposure/activities/A/budget"))
                .andExpect(status().isBadRequest());

        // 公告归入活动并申请：第一次 201，小时预算耗尽第二次 429 且含下一可用小时（null）
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"c1\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/campaigns/c1/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"activityId\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value("A"));
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h8\",\"campaignId\":\"c1\",\"visitorId\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("A"))
                .andExpect(jsonPath("$.hourOfDay").value(10));
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h9\",\"campaignId\":\"c1\",\"visitorId\":\"v2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.details.hourOfDay").value(10))
                .andExpect(jsonPath("$.details.nextAvailableHour").doesNotExist());

        // 诊断与历史查询
        mockMvc.perform(get("/api/exposure/activities/A/hours").param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.hours[10].reservedCount").value(1));
        mockMvc.perform(get("/api/exposure/activities/A/reservations").param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 不存在的活动 → 404
        mockMvc.perform(get("/api/exposure/activities/nope"))
                .andExpect(status().isNotFound());
    }
}
