package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityHourDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailyCloseResponse;
import com.example.starter.exposure.web.MigrateCampaignActivityRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 配置版本控制、迁移固化、日结快照与只读诊断。
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

    /** 固定于 UTC 10:00，当前小时为 10。 */
    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    static final int CURRENT_HOUR = 10;

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
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 构造 24 个小时预算：仅指定小时为 value，其余为 0。 */
    private static List<Integer> budgets(int hour, int value) {
        List<Integer> budgets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            budgets.add(i == hour ? value : 0);
        }
        return budgets;
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private CampaignResponse migrate(String requestId, String campaignId, String activityId) {
        return service.migrateCampaign(campaignId,
                new MigrateCampaignActivityRequest(requestId, activityId));
    }

    @Test
    @DisplayName("创建活动：预算合计不等返回 400 且不占位；重复创建 409；同键重放原结果")
    void createActivity_validationAndIdempotency() {
        // 合计不等于日总额 → 400，且失败不占幂等键
        ApiException bad = assertThrows(ApiException.class, () -> service.createActivity(
                new CreateActivityRequest("req-a1", "act1", 5, budgets(CURRENT_HOUR, 4))));
        assertEquals(400, bad.getStatus().value());
        assertTrue(bad.getMessage().contains("diff=1"), "响应必须给出差额: " + bad.getMessage());

        // 同键修正参数后成功（失败未占键）
        ActivityResponse created = service.createActivity(
                new CreateActivityRequest("req-a1", "act1", 5, budgets(CURRENT_HOUR, 5)));
        assertEquals("act1", created.activityId());
        assertEquals(5, created.dailyTotalCap());
        assertEquals(1, created.version());
        assertEquals(24, created.hourlyBudgets().size());
        assertEquals(5, created.hourlyBudgets().get(CURRENT_HOUR));
        assertEquals(BASE.toEpochMilli(), created.createdAtUtc());

        // 同键同参重放：返回首次结果，版本不变
        ActivityResponse replay = service.createActivity(
                new CreateActivityRequest("req-a1", "act1", 5, budgets(CURRENT_HOUR, 5)));
        assertEquals(1, replay.version());

        // 同键异参 → 409
        assert409(() -> service.createActivity(
                new CreateActivityRequest("req-a1", "act1", 6, budgets(CURRENT_HOUR, 6))));

        // 新键重复活动编号 → 409
        assert409(() -> service.createActivity(
                new CreateActivityRequest("req-a2", "act1", 5, budgets(CURRENT_HOUR, 5))));
    }

    @Test
    @DisplayName("申请为当前小时原子预占活动名额；小时耗尽返回 429 并给出下一可用小时，公告/访客名额不消耗")
    void apply_hourBudgetExhausted_returns429WithNextHourAndNoSideConsumption() {
        service.createActivity(new CreateActivityRequest("req-act", "act1", 2, budgets(CURRENT_HOUR, 2)));
        createCampaign("req-c1", "c1", 10, 5);
        migrate("req-m1", "c1", "act1");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act1", r1.activityId());
        assertEquals(CURRENT_HOUR, r1.hourOfDay());
        service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));

        // 第三笔：小时预算耗尽 → 429，下一可用小时为次日 10:00（仅该小时有预算）
        ApiException exhausted = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a3", "c1", "v3")));
        assertEquals(429, exhausted.getStatus().value());
        assertTrue(exhausted.getMessage().contains("exhausted"), exhausted.getMessage());
        assertEquals(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli(),
                exhausted.getNextAvailableHourStartUtc());

        // 公告与访客侧名额未被失败申请消耗
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v3", DAY).usedVisitor());

        // 活动小时账目占用恰好为 2
        ActivityHourDiagnosticsResponse diag = service.getHourDiagnostics("act1", DAY);
        assertEquals(2, diag.hours().get(CURRENT_HOUR).budget());
        assertEquals(2, diag.hours().get(CURRENT_HOUR).activeReserved());
    }

    @Test
    @DisplayName("当前小时未配置预算（为 0）不得申请：429 且给出下一可用小时")
    void apply_hourBudgetNotConfigured_rejected() {
        service.createActivity(new CreateActivityRequest("req-act", "act1", 1, budgets(11, 1)));
        createCampaign("req-c1", "c1", 10, 5);
        migrate("req-m1", "c1", "act1");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));
        assertEquals(429, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("not configured"), ex.getMessage());
        assertEquals(Instant.parse("2026-09-22T11:00:00Z").toEpochMilli(),
                ex.getNextAvailableHourStartUtc());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 推进到 11 点后可申请
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse ok = service.apply(new ApplyExposureRequest("req-a2", "c1", "v1"));
        assertEquals(11, ok.hourOfDay());
    }

    @Test
    @DisplayName("确认同时结算三侧名额；取消/过期只释放同一小时预占并固化计数，重复终态不重复计数")
    void confirmCancelExpire_settleThreeSidesAndDailyClose() {
        service.createActivity(new CreateActivityRequest("req-act", "act1", 5, budgets(CURRENT_HOUR, 5)));
        createCampaign("req-c1", "c1", 10, 10);
        migrate("req-m1", "c1", "act1");

        ReservationResponse confirmed = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse cancelled = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        ReservationResponse expiring = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));

        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x1"));
        // 重复取消不重复计数
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x2"));

        // 过期：推进 60 秒后由后续操作结算
        mutableClock().advanceMillis(60_000L);
        service.getReservation(expiring.reservationId());

        DailyCloseResponse close = service.getDailyClose("act1", DAY);
        assertEquals(1, close.hours().get(CURRENT_HOUR).confirmed());
        assertEquals(1, close.hours().get(CURRENT_HOUR).cancelled());
        assertEquals(1, close.hours().get(CURRENT_HOUR).expired());
        assertEquals(1, close.confirmedTotal());
        assertEquals(1, close.cancelledTotal());
        assertEquals(1, close.expiredTotal());

        // 三侧账目：确认占用保留，取消/过期释放
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        ActivityHourDiagnosticsResponse diag = service.getHourDiagnostics("act1", DAY);
        assertEquals(0, diag.hours().get(CURRENT_HOUR).activeReserved(),
                "三单均已终态，无有效预占");
        assertEquals(1, diag.hours().get(CURRENT_HOUR).confirmed());
        assertEquals(1, diag.hours().get(CURRENT_HOUR).cancelled());
        assertEquals(1, diag.hours().get(CURRENT_HOUR).expired());
    }

    @Test
    @DisplayName("活动迁移只影响新预占：既有预占固化活动与小时归属，取消释放原活动名额")
    void migrate_onlyAffectsNewReservations() {
        service.createActivity(new CreateActivityRequest("req-a1", "act1", 5, budgets(CURRENT_HOUR, 5)));
        service.createActivity(new CreateActivityRequest("req-a2", "act2", 5, budgets(CURRENT_HOUR, 5)));
        createCampaign("req-c1", "c1", 10, 10);
        migrate("req-m1", "c1", "act1");

        ReservationResponse before = service.apply(new ApplyExposureRequest("req-p1", "c1", "v1"));
        assertEquals("act1", before.activityId());

        CampaignResponse migrated = migrate("req-m2", "c1", "act2");
        assertEquals("act2", migrated.activityId());

        ReservationResponse after = service.apply(new ApplyExposureRequest("req-p2", "c1", "v2"));
        assertEquals("act2", after.activityId());

        // 既有预占归属固化
        ReservationResponse detail = service.getReservation(before.reservationId());
        assertEquals("act1", detail.activityId());
        assertEquals(CURRENT_HOUR, detail.hourOfDay());

        // 取消旧预占释放 act1 名额，act2 不受影响
        service.cancel(before.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(1, service.getDailyClose("act1", DAY).cancelledTotal());
        assertEquals(0, service.getDailyClose("act2", DAY).cancelledTotal());
        assertEquals(1, service.getHourDiagnostics("act2", DAY)
                .hours().get(CURRENT_HOUR).activeReserved());

        // 迁移到不存在的活动 404；迁移请求幂等
        assert404(() -> migrate("req-m3", "c1", "nope"));
        CampaignResponse replay = migrate("req-m2", "c1", "act2");
        assertEquals("act2", replay.activityId());
        assert409(() -> migrate("req-m2", "c1", "act1"));
    }

    @Test
    @DisplayName("配置修改：expectedVersion 不匹配 409；已开始小时不可下调；未来小时可调整；历史日结不改写")
    void updateActivity_versionAndStartedHourRules() {
        service.createActivity(new CreateActivityRequest("req-act", "act1", 10,
                mergeBudgets(CURRENT_HOUR, 5, 20, 5)));
        createCampaign("req-c1", "c1", 10, 10);
        migrate("req-m1", "c1", "act1");
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-p1", "c1", "v1"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));

        // 版本不匹配 → 409
        assert409(() -> service.updateActivity("act1",
                new UpdateActivityRequest("req-u1", 9, 10, mergeBudgets(CURRENT_HOUR, 5, 20, 5))));

        // 已开始小时（当前小时 10）下调 → 409
        assert409(() -> service.updateActivity("act1",
                new UpdateActivityRequest("req-u2", 1, 10, mergeBudgets(CURRENT_HOUR, 4, 20, 6))));

        // 合计不等 → 400
        ApiException bad = assertThrows(ApiException.class, () -> service.updateActivity("act1",
                new UpdateActivityRequest("req-u3", 1, 10, mergeBudgets(CURRENT_HOUR, 5, 20, 4))));
        assertEquals(400, bad.getStatus().value());

        // 未来小时下调、当前小时不下调 → 成功，版本 +1
        ActivityResponse updated = service.updateActivity("act1",
                new UpdateActivityRequest("req-u4", 1, 10, mergeBudgets(CURRENT_HOUR, 6, 20, 4)));
        assertEquals(2, updated.version());
        assertEquals(6, updated.hourlyBudgets().get(CURRENT_HOUR));
        assertEquals(4, updated.hourlyBudgets().get(20));

        // 同键重放返回首次结果，版本不重复推进
        ActivityResponse replay = service.updateActivity("act1",
                new UpdateActivityRequest("req-u4", 1, 10, mergeBudgets(CURRENT_HOUR, 6, 20, 4)));
        assertEquals(2, replay.version());
        assertEquals(2, service.getActivity("act1").version());

        // 配置变更不改写已结算日的日结快照
        DailyCloseResponse close = service.getDailyClose("act1", DAY);
        assertEquals(1, close.hours().get(CURRENT_HOUR).confirmed());
        assertEquals(1, close.confirmedTotal());
    }

    @Test
    @DisplayName("诊断与历史查询只读：不结算过期预占、不改变状态；历史按创建时刻稳定排序")
    void diagnosticsAndHistory_areReadOnly() {
        service.createActivity(new CreateActivityRequest("req-act", "act1", 5, budgets(CURRENT_HOUR, 5)));
        createCampaign("req-c1", "c1", 10, 10);
        migrate("req-m1", "c1", "act1");
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-p1", "c1", "v1"));
        // 推进 1 毫秒保证历史排序由创建时刻决定（同刻则按编号，随机编号不可断言）
        mutableClock().advanceMillis(1L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-p2", "c1", "v2"));

        // 过期后不触发任何写操作：诊断只读
        mutableClock().advanceMillis(61_000L);
        ActivityHourDiagnosticsResponse diag = service.getHourDiagnostics("act1", DAY);
        assertEquals(0, diag.hours().get(CURRENT_HOUR).activeReserved());
        assertEquals(2, diag.hours().get(CURRENT_HOUR).pendingExpired());
        assertEquals(0, diag.hours().get(CURRENT_HOUR).expired());

        // 数据库中状态未被读取改写
        String status1 = jdbc.queryForObject(
                "SELECT status FROM exposure_reservation WHERE reservation_id = ?",
                String.class, r1.reservationId());
        assertEquals("RESERVED", status1);
        // 日结未结算
        assertEquals(0, service.getDailyClose("act1", DAY).expiredTotal());

        // 历史查询：两单按创建时刻排序，字段完整
        List<ReservationResponse> history = service.getActivityReservations("act1", DAY);
        assertEquals(2, history.size());
        assertEquals(r1.reservationId(), history.get(0).reservationId());
        assertEquals(r2.reservationId(), history.get(1).reservationId());
        assertEquals("act1", history.get(0).activityId());
        assertEquals(CURRENT_HOUR, history.get(0).hourOfDay());
        assertNull(history.get(0).terminalAtUtc());

        // 再次诊断结果一致（读取不改变状态）
        ActivityHourDiagnosticsResponse diag2 = service.getHourDiagnostics("act1", DAY);
        assertEquals(2, diag2.hours().get(CURRENT_HOUR).pendingExpired());
        assertEquals(BASE.toEpochMilli() + 1L + 61_000L, diag2.diagnosedAtUtc());
    }

    @Test
    @DisplayName("未归入活动的公告按原有两级频控申请，不涉及活动侧")
    void apply_withoutActivity_behavesAsBefore() {
        createCampaign("req-c1", "c1", 1, 1);
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-p1", "c1", "v1"));
        assertNull(r.activityId());
        assertNull(r.hourOfDay());
        assert429(() -> service.apply(new ApplyExposureRequest("req-p2", "c1", "v2")));
    }

    @Test
    @DisplayName("HTTP 语义：活动创建 201、版本冲突 409、429 携带下一可用小时、查询端点 200")
    void httpSemantics_activityEndpoints() throws Exception {
        StringBuilder budgets = new StringBuilder("[");
        for (int i = 0; i < 24; i++) {
            budgets.append(i == CURRENT_HOUR ? "1" : "0");
            if (i < 23) {
                budgets.append(',');
            }
        }
        budgets.append(']');

        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"activityId\":\"act1\",\"dailyTotalCap\":1,"
                                + "\"hourlyBudgets\":" + budgets + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.hourlyBudgets[10]").value(1));

        // 预算合计不等 → 400
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"activityId\":\"act2\",\"dailyTotalCap\":2,"
                                + "\"hourlyBudgets\":" + budgets + "}"))
                .andExpect(status().isBadRequest());

        // 预算条目数不足 24 → 400
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"activityId\":\"act3\",\"dailyTotalCap\":0,"
                                + "\"hourlyBudgets\":[0,0]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"c1\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/exposure/campaigns/c1/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"activityId\":\"act1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value("act1"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"c1\",\"visitorId\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("act1"))
                .andExpect(jsonPath("$.hourOfDay").value(CURRENT_HOUR));

        // 小时预算耗尽 → 429 且响应携带下一可用小时
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"campaignId\":\"c1\",\"visitorId\":\"v2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.nextAvailableHourStartUtc")
                        .value(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli()));

        // 版本冲突 → 409
        mockMvc.perform(put("/api/exposure/activities/act1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h8\",\"expectedVersion\":7,\"dailyTotalCap\":1,"
                                + "\"hourlyBudgets\":" + budgets + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/exposure/activities/act1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTotalCap").value(1));

        mockMvc.perform(get("/api/exposure/activities/act1/daily-close")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours.length()").value(24));

        mockMvc.perform(get("/api/exposure/activities/act1/hours")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours[10].budget").value(1))
                .andExpect(jsonPath("$.hours[10].activeReserved").value(1));

        mockMvc.perform(get("/api/exposure/activities/act1/reservations")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/exposure/activities/nope"))
                .andExpect(status().isNotFound());
    }

    /** 构造 24 个小时预算：两个指定小时分别取值，其余为 0。 */
    private static List<Integer> mergeBudgets(int hour1, int value1, int hour2, int value2) {
        List<Integer> budgets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            budgets.add(i == hour1 ? value1 : i == hour2 ? value2 : 0);
        }
        return budgets;
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
