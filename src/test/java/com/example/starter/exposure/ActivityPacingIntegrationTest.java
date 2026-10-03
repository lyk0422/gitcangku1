package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayCloseResponse;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignActivityResponse;
import com.example.starter.exposure.web.ConfigHistoryResponse;
import com.example.starter.exposure.web.ConfigureDayBudgetRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活动匀速投放 H2（MODE=MySQL）集成测试：小时预算、配置版本、迁移、日结快照与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityPacingIntegrationTest {

    /** 可控时钟：固定起点 2026-09-22T10:00:00Z，可按毫秒推进。 */
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
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_config_history");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM campaign_activity");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 生成 24 个小时预算：指定小时为给定值，其余为 0。 */
    private List<Integer> budgetsOf(int... hourBudgets) {
        List<Integer> budgets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            budgets.add(0);
        }
        for (int hour = 0; hour < hourBudgets.length; hour++) {
            budgets.set(hour, hourBudgets[hour]);
        }
        return budgets;
    }

    private ActivityDayConfigResponse configure(String requestId, String activityId, LocalDate date,
                                                int expectedVersion, List<Integer> budgets) {
        int total = budgets.stream().mapToInt(Integer::intValue).sum();
        return service.configureDayBudget(activityId, date,
                new ConfigureDayBudgetRequest(requestId, expectedVersion, total, budgets));
    }

    private void createActivity(String requestId, String activityId) {
        service.createActivity(new CreateActivityRequest(requestId, activityId));
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    @Test
    @DisplayName("配置日预算：合计不等 400、版本冲突 409、历史日不可改、历史快照可查")
    void configure_validatesSumVersionAndSettledDay() {
        createActivity("req-act", "act1");

        // 24 个小时预算合计必须等于日总额
        List<Integer> badSum = budgetsOf(1, 1);
        var ex400 = assertThrows(ApiException.class, () -> service.configureDayBudget("act1", DAY,
                new ConfigureDayBudgetRequest("req-bad", 0, 3, badSum)));
        assertEquals(400, ex400.getStatus().value());
        assertTrue(ex400.getMessage().contains("actual=2") && ex400.getMessage().contains("required=3"));

        // expectedVersion 与实际不一致（未配置过为 0）
        assert409(() -> configure("req-v1", "act1", DAY, 1, budgetsOf(1, 1)));

        // 首次配置：expectedVersion=0，成功后版本为 1
        ActivityDayConfigResponse created = configure("req-cfg1", "act1", DAY, 0, budgetsOf(2, 2));
        assertEquals(1, created.version());
        assertEquals(4, created.dailyTotalCap());
        assertEquals(24, created.hourlyBudgets().size());

        // 重复配置需携带新版本号
        assert409(() -> configure("req-cfg2", "act1", DAY, 0, budgetsOf(2, 2)));
        ActivityDayConfigResponse updated = configure("req-cfg3", "act1", DAY, 1, budgetsOf(3, 3));
        assertEquals(2, updated.version());

        // 历史日（已结算）不可修改
        assert409(() -> configure("req-past", "act1", LocalDate.of(2026, 9, 21), 0, budgetsOf(1, 1)));

        // 配置历史：两个版本快照，只读
        ConfigHistoryResponse history = service.queryConfigHistory("act1", DAY);
        assertEquals(2, history.entries().size());
        assertEquals(1, history.entries().get(0).version());
        assertEquals(4, history.entries().get(0).dailyTotalCap());
        assertEquals(2, history.entries().get(1).version());
        assertEquals("req-cfg3", history.entries().get(1).requestId());

        // 不存在的活动 404
        assert404(() -> configure("req-none", "nope", DAY, 0, budgetsOf(1, 1)));
        assert404(() -> service.queryConfigHistory("nope", DAY));
    }

    @Test
    @DisplayName("已开始小时不可下调，未开始小时可调整，版本递增")
    void configure_startedHourCannotBeDecreased() {
        createActivity("req-act", "act1");
        // 当前 10:00，小时 0~10 已开始；budget[10]=5, budget[11]=5
        configure("req-cfg1", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 5));

        // 下调已开始的小时 10 → 409
        assert409(() -> configure("req-cfg2", "act1", DAY, 1,
                budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 6)));

        // 上调小时 10、下调未开始的小时 11 → 成功，版本 2
        ActivityDayConfigResponse updated = configure("req-cfg3", "act1", DAY, 1,
                budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 4));
        assertEquals(2, updated.version());
        assertEquals(6, updated.hourlyBudgets().get(10));
        assertEquals(4, updated.hourlyBudgets().get(11));
    }
    @Test
    @DisplayName("归入活动后申请预占当前小时名额；小时耗尽 429 带下一可用小时且不消耗公告/访客额度")
    void apply_reservesActivityHourAnd429WithNextHour() {
        createActivity("req-act", "act1");
        // 小时 10 预算 2，小时 11 预算 1，其余 0
        configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1));
        createCampaign("req-c", "c1", 100, 100);
        CampaignActivityResponse mapping = service.migrateCampaign("c1",
                new MigrateCampaignRequest("req-m1", "act1"));
        assertNull(mapping.previousActivityId());
        assertEquals("act1", mapping.activityId());

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act1", r1.activityId());
        assertEquals(10, r1.hourOfDay());
        service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));

        // 小时 10 预算耗尽：429 且下一可用小时为 11；公告/访客额度不消耗
        ApiException exhausted = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a3", "c1", "v3")));
        assertEquals(429, exhausted.getStatus().value());
        assertEquals(11, exhausted.getNextAvailableHour());
        QuotaResponse quota = service.queryQuota("c1", "v3", DAY);
        assertEquals(2, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());

        // 过期前诊断：小时 10 占用 2 剩余 0
        ActivityDiagnosticsResponse before = service.queryDiagnostics("act1", DAY);
        assertEquals(2, before.hours().get(10).used());
        assertEquals(0, before.hours().get(10).remaining());

        // 推进到 11:00 后可申请，归属小时 11
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a4", "c1", "v3"));
        assertEquals(11, r3.hourOfDay());

        // 诊断：小时 10 的两笔预占已过期释放，小时 11 占用 1 剩余 0
        ActivityDiagnosticsResponse diag = service.queryDiagnostics("act1", DAY);
        assertEquals(0, diag.hours().get(10).used());
        assertEquals(2, diag.hours().get(10).cancelled());
        assertEquals(1, diag.hours().get(11).used());
        assertEquals(0, diag.hours().get(11).remaining());
        assertEquals(0, diag.hours().get(12).budget());
    }

    @Test
    @DisplayName("活动未配置小时预算时不得申请：409 且不消耗任何额度")
    void apply_withoutBudgetConfig_rejected() {
        createActivity("req-act", "act1");
        createCampaign("req-c", "c1", 100, 100);
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));

        assert409(() -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));

        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        // 失败不占幂等键：配置预算后同键同参可成功
        configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("RESERVED", r.status().name());
    }

    @Test
    @DisplayName("确认/取消/过期同时结算活动侧；日结快照固化，后续配置变更不改写")
    void settle_threeSidesAndDayCloseSnapshot() {
        createActivity("req-act", "act1");
        configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3));
        createCampaign("req-c", "c1", 100, 100);
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));

        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        // 推进 60 秒使 r3 过期，日结查询触发结算
        mutableClock().advanceMillis(60_000L);

        ActivityDayCloseResponse close = service.queryDayClose("act1", DAY);
        assertEquals(1, close.hours().get(10).confirmed());
        assertEquals(2, close.hours().get(10).cancelled());
        assertEquals(3, close.dailyTotalCap());

        ActivityDiagnosticsResponse diag = service.queryDiagnostics("act1", DAY);
        assertEquals(1, diag.hours().get(10).used(), "确认仍占用，取消与过期已释放");
        assertEquals(1, diag.hours().get(10).confirmed());
        assertEquals(2, diag.hours().get(10).cancelled());

        // 配置变更（上调未开始小时）不改写已结算快照
        configure("req-cfg2", "act1", DAY, 1, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 1));
        ActivityDayCloseResponse closeAfter = service.queryDayClose("act1", DAY);
        assertEquals(1, closeAfter.hours().get(10).confirmed());
        assertEquals(2, closeAfter.hours().get(10).cancelled());
        assertEquals(4, closeAfter.dailyTotalCap());
        assertEquals(2, closeAfter.version());
    }

    @Test
    @DisplayName("活动迁移只影响新预占：既有预占固化活动与小时归属，取消释放原活动额度")
    void migrate_onlyAffectsNewReservations() {
        createActivity("req-actA", "actA");
        createActivity("req-actB", "actB");
        configure("req-cfgA", "actA", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        configure("req-cfgB", "actB", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        createCampaign("req-c", "c1", 100, 100);
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "actA"));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("actA", r1.activityId());

        CampaignActivityResponse moved = service.migrateCampaign("c1",
                new MigrateCampaignRequest("req-m2", "actB"));
        assertEquals("actA", moved.previousActivityId());

        // 新预占归属 actB；既有预占仍固化 actA
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals("actB", r2.activityId());
        assertEquals("actA", service.getReservation(r1.reservationId()).activityId());

        // 取消既有预占释放原活动 actA 的小时额度，actB 不受影响
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ActivityDiagnosticsResponse diagA = service.queryDiagnostics("actA", DAY);
        ActivityDiagnosticsResponse diagB = service.queryDiagnostics("actB", DAY);
        assertEquals(0, diagA.hours().get(10).used());
        assertEquals(1, diagA.hours().get(10).cancelled());
        assertEquals(1, diagB.hours().get(10).used());
        assertEquals(0, diagB.hours().get(10).cancelled());
    }
    @Test
    @DisplayName("新写操作幂等：同键同参重放首次结果，异参 409")
    void idempotency_newOperations() {
        createActivity("req-act", "act1");
        // 创建活动重放
        var replay = service.createActivity(new CreateActivityRequest("req-act", "act1"));
        assertEquals("act1", replay.activityId());
        // 同键异参 409
        assert409(() -> service.createActivity(new CreateActivityRequest("req-act", "act2")));

        // 配置重放：版本不变更
        configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        ActivityDayConfigResponse replayed = configure("req-cfg", "act1", DAY, 0,
                budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        assertEquals(1, replayed.version());
        assertEquals(1, service.queryConfigHistory("act1", DAY).entries().size());
        assert409(() -> configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)));

        // 迁移重放
        createCampaign("req-c", "c1", 100, 100);
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));
        CampaignActivityResponse migratedReplay = service.migrateCampaign("c1",
                new MigrateCampaignRequest("req-m1", "act1"));
        assertEquals("act1", migratedReplay.activityId());
        createActivity("req-act2", "act2");
        assert409(() -> service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act2")));

        // 迁移到不存在的活动/公告 404
        assert404(() -> service.migrateCampaign("c1", new MigrateCampaignRequest("req-m9", "nope")));
        assert404(() -> service.migrateCampaign("nope", new MigrateCampaignRequest("req-m8", "act1")));
    }

    @Test
    @DisplayName("预占明细查询：按日列出、状态过滤、排序稳定、非法状态 400")
    void listReservations_detailQuery() {
        createCampaign("req-c", "c1", 100, 100);
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        mutableClock().advanceMillis(1L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        mutableClock().advanceMillis(1L);
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));

        List<ReservationResponse> all = service.listReservations("c1", DAY, null);
        assertEquals(3, all.size());
        assertEquals(r1.reservationId(), all.get(0).reservationId());
        assertEquals(r2.reservationId(), all.get(1).reservationId());
        assertEquals(r3.reservationId(), all.get(2).reservationId());

        List<ReservationResponse> confirmed = service.listReservations("c1", DAY, "CONFIRMED");
        assertEquals(1, confirmed.size());
        assertEquals(r1.reservationId(), confirmed.get(0).reservationId());
        assertEquals(1, service.listReservations("c1", DAY, "cancelled").size());
        assertEquals(0, service.listReservations("c1", DAY, "EXPIRED").size());
        assertEquals(0, service.listReservations("c1", LocalDate.of(2026, 9, 23), null).size());

        var ex400 = assertThrows(ApiException.class,
                () -> service.listReservations("c1", DAY, "bogus"));
        assertEquals(400, ex400.getStatus().value());
        assert404(() -> service.listReservations("nope", DAY, null));
    }

    @Test
    @DisplayName("HTTP 语义：429 响应携带下一可用小时，只读查询 200")
    void httpSemantics_nextAvailableHourInBody() throws Exception {
        createActivity("req-act", "act1");
        configure("req-cfg", "act1", DAY, 0, budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1));
        createCampaign("req-c", "c1", 100, 100);
        service.migrateCampaign("c1", new MigrateCampaignRequest("req-m1", "act1"));
        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-x1\",\"campaignId\":\"c1\",\"visitorId\":\"v2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.nextAvailableHour").value(11));

        mockMvc.perform(get("/api/exposure/activities/act1/days/2026-09-22/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours[10].budget").value(1))
                .andExpect(jsonPath("$.hours[10].confirmed").value(0));

        mockMvc.perform(get("/api/exposure/activities/act1/days/2026-09-22/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours[10].used").value(1))
                .andExpect(jsonPath("$.hours[10].remaining").value(0));

        mockMvc.perform(get("/api/exposure/campaigns/c1/reservations")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityId").value("act1"))
                .andExpect(jsonPath("$[0].hourOfDay").value(10));

        mockMvc.perform(get("/api/exposure/activities/nope/days/2026-09-22/close"))
                .andExpect(status().isNotFound());
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