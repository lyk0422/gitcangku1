package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayQuotaResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
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
import com.example.starter.exposure.web.UpsertActivityDayConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 公告活动匀速投放 H2（MODE=MySQL）集成测试：小时预占、429 下一可用小时、
 * 三侧结算、迁移固化、配置版本与已开始小时约束、日结快照与幂等语义。
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

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    static final LocalDate NEXT_DAY = LocalDate.of(2026, 9, 23);
    static final long HOUR_10 = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli();
    static final long HOUR_11 = Instant.parse("2026-09-22T11:00:00Z").toEpochMilli();

    /** 以可控时钟覆盖生产系统时钟。 */
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
    ObjectMapper objectMapper;
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
        jdbc.update("DELETE FROM activity_day_settlement");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /** 构造 24 小时预算：hourBudgets 为 (小时, 预算) 对，其余小时为 0。 */
    private static List<Integer> budgets(int... hourBudgets) {
        List<Integer> list = new ArrayList<>(Collections.nCopies(24, 0));
        for (int i = 0; i < hourBudgets.length; i += 2) {
            list.set(hourBudgets[i], hourBudgets[i + 1]);
        }
        return list;
    }

    private static int sum(List<Integer> budgets) {
        return budgets.stream().mapToInt(Integer::intValue).sum();
    }

    private void createActivity(String requestId, String activityId) {
        service.createActivity(new CreateActivityRequest(requestId, activityId));
    }

    private ActivityDayConfigResponse upsert(String requestId, String activityId, LocalDate date,
                                             long expectedVersion, List<Integer> hourlyBudgets) {
        return service.upsertDayConfig(activityId, date,
                new UpsertActivityDayConfigRequest(requestId, expectedVersion,
                        sum(hourlyBudgets), hourlyBudgets));
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor,
                                String activityId) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor,
                activityId));
    }

    @Test
    @DisplayName("归入活动的申请预占当前小时名额；小时耗尽 429 给出下一可用小时且不消耗公告/访客额度")
    void applyWithActivity_hourExhausted_returns429WithNextHourAndNoSideEffect() {
        createActivity("req-act", "act1");
        upsert("req-cfg", "act1", DAY, 0L, budgets(10, 1, 11, 2));
        createCampaign("req-c1", "c1", 100, 100, "act1");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act1", r1.activityId());
        assertEquals(HOUR_10, r1.hourStartUtc());

        ActivityDayQuotaResponse hours = service.queryActivityDayQuota("act1", DAY);
        assertTrue(hours.configured());
        assertEquals(1, hours.usedTotal());
        assertEquals(1, hours.hours().get(10).used());
        assertEquals(0, hours.hours().get(10).remaining());
        assertEquals(1, hours.hours().get(10).budget());

        // 小时 10 预算耗尽：429 + 下一可用小时为 11 点
        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v2")));
        assertEquals(429, ex.getStatus().value());
        assertEquals(HOUR_11, ex.getNextAvailableHourStartUtc());

        // 失败不消耗公告与访客侧名额
        QuotaResponse quota = service.queryQuota("c1", "v2", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());

        // 推进到 11 点后可申请，归属小时 11
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v2"));
        assertEquals(HOUR_11, r2.hourStartUtc());
        assertEquals(1, service.queryActivityDayQuota("act1", DAY).hours().get(11).used());
    }

    @Test
    @DisplayName("未配置小时预算不得申请：409 且不消耗任何额度；无活动公告保持原行为")
    void applyWithoutDayConfig_rejected_andCampaignWithoutActivityUnchanged() {
        createActivity("req-act", "act1");
        createCampaign("req-c1", "c1", 10, 10, "act1");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a1", "c1", "v1")));
        assertEquals(409, ex.getStatus().value());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedVisitor());

        // 未归入活动的公告不受活动约束
        createCampaign("req-c2", "c2", 10, 10, null);
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a2", "c2", "v1"));
        assertNull(r.activityId());
        assertNull(r.hourStartUtc());
        assertEquals(1, service.queryQuota("c2", "v1", DAY).usedTotal());
    }

    @Test
    @DisplayName("确认保持三侧占用；取消释放同一小时活动名额；重复终态不重复计数")
    void confirmAndCancel_settleActivitySide() {
        createActivity("req-act", "act1");
        upsert("req-cfg", "act1", DAY, 0L, budgets(10, 2));
        createCampaign("req-c1", "c1", 10, 10, "act1");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals(2, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());

        // 确认：三侧名额转为确认占用，活动小时占用不变
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(2, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());

        // 取消：释放同一小时活动名额与公告/访客额度
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(1, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 重复取消不重复释放
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x2"));
        assertEquals(1, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());
    }

    @Test
    @DisplayName("过期释放同一小时活动名额：同小时与跨小时边界")
    void expiry_releasesActivityHourQuota() {
        createActivity("req-act", "act1");
        upsert("req-cfg", "act1", DAY, 0L, budgets(10, 1, 11, 1));
        createCampaign("req-c1", "c1", 10, 10, "act1");

        // 同小时过期
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getReservation(r1.reservationId()).status().name());
        assertEquals(0, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());

        // 跨小时过期：10:59:30 申请，11:00:30 到期；下一次申请触发结算
        mutableClock().setInstant(Instant.parse("2026-09-22T10:59:30Z"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        assertEquals(HOUR_10, r2.hourStartUtc());
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:30Z"));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));
        assertEquals(HOUR_11, r3.hourStartUtc());

        ActivityDayQuotaResponse hours = service.queryActivityDayQuota("act1", DAY);
        assertEquals(0, hours.hours().get(10).used(), "过期预占应释放小时 10 名额");
        assertEquals(1, hours.hours().get(11).used());
        assertEquals("EXPIRED", service.getReservation(r2.reservationId()).status().name());
    }

    @Test
    @DisplayName("活动迁移只影响迁移后的新预占；既有预占固化活动与小时归属")
    void migration_affectsOnlyNewReservations() {
        createActivity("req-act1", "act1");
        createActivity("req-act2", "act2");
        upsert("req-cfg1", "act1", DAY, 0L, budgets(10, 1));
        upsert("req-cfg2", "act2", DAY, 0L, budgets(10, 1));
        createCampaign("req-c1", "c1", 10, 10, "act1");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("act1", r1.activityId());
        assert429(() -> service.apply(new ApplyExposureRequest("req-a2", "c1", "v2")));

        // 迁移到 act2：新预占占用 act2 小时名额
        CampaignResponse migrated = service.migrateCampaignActivity("c1",
                new MigrateActivityRequest("req-m1", "act2"));
        assertEquals("act2", migrated.activityId());
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v2"));
        assertEquals("act2", r2.activityId());
        assertEquals(1, service.queryActivityDayQuota("act2", DAY).hours().get(10).used());
        assertEquals(1, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());

        // 取消迁移前的预占：释放固化的 act1 小时名额
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(0, service.queryActivityDayQuota("act1", DAY).hours().get(10).used());
        assertEquals(1, service.queryActivityDayQuota("act2", DAY).hours().get(10).used());

        // 迁移幂等：同键同参重放原结果，异参 409
        CampaignResponse replay = service.migrateCampaignActivity("c1",
                new MigrateActivityRequest("req-m1", "act2"));
        assertEquals("act2", replay.activityId());
        assert409(() -> service.migrateCampaignActivity("c1",
                new MigrateActivityRequest("req-m1", "act1")));

        // 移出活动后申请不再占用活动名额
        service.migrateCampaignActivity("c1", new MigrateActivityRequest("req-m2", null));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a4", "c1", "v3"));
        assertNull(r3.activityId());

        assert404(() -> service.migrateCampaignActivity("nope",
                new MigrateActivityRequest("req-m3", "act1")));
        assert404(() -> service.migrateCampaignActivity("c1",
                new MigrateActivityRequest("req-m4", "nope")));
    }

    @Test
    @DisplayName("配置校验：合计不等于日总额/负数/非 24 项 400；版本不符与已开始小时下调 409；失败不占键")
    void dayConfig_validationAndVersionRules() {
        createActivity("req-act", "act1");

        // 合计不等于日总额 → 400，报实际值/要求值/差额
        List<Integer> mismatch = budgets(10, 2);
        ApiException sumEx = assertThrows(ApiException.class, () -> service.upsertDayConfig("act1",
                DAY, new UpsertActivityDayConfigRequest("req-v1", 0L, 24, mismatch)));
        assertEquals(400, sumEx.getStatus().value());
        assertTrue(sumEx.getMessage().contains("actual=2"));
        assertTrue(sumEx.getMessage().contains("required=24"));
        assertTrue(sumEx.getMessage().contains("diff=-22"));

        // 负数预算 → 400
        List<Integer> negative = budgets(10, -1, 11, 25);
        assert400(() -> service.upsertDayConfig("act1", DAY,
                new UpsertActivityDayConfigRequest("req-v2", 0L, 24, negative)));

        // 非 24 项 → 400
        assert400(() -> service.upsertDayConfig("act1", DAY,
                new UpsertActivityDayConfigRequest("req-v3", 0L, 0, budgets(10, 0)
                        .subList(0, 23))));

        // 配置不存在时 expectedVersion 必须为 0
        assert409(() -> upsert("req-v4", "act1", DAY, 5L, budgets(10, 2)));

        // 创建成功：版本 1
        ActivityDayConfigResponse created = upsert("req-v5", "act1", DAY, 0L,
                budgets(10, 2, 15, 24));
        assertEquals(1L, created.version());
        assertEquals(26, created.dailyTotalCap());

        // 版本不符 → 409，报实际/要求版本
        ApiException verEx = assertThrows(ApiException.class,
                () -> upsert("req-v6", "act1", DAY, 0L, budgets(10, 2, 15, 24)));
        assertEquals(409, verEx.getStatus().value());
        assertTrue(verEx.getMessage().contains("actual=1"));

        // 当前小时 10 已开始（10:00:00，左闭右开），不可下调
        assert409(() -> upsert("req-v7", "act1", DAY, 1L, budgets(10, 1, 15, 25)));

        // 未来小时可下调、已开始小时可上调 → 成功，版本 2
        ActivityDayConfigResponse updated = upsert("req-v8", "act1", DAY, 1L,
                budgets(10, 6, 15, 20));
        assertEquals(2L, updated.version());
        assertEquals(6, updated.hourlyBudgets().get(10));
        assertEquals(20, updated.hourlyBudgets().get(15));

        // 同键同参重放原结果
        ActivityDayConfigResponse replay = upsert("req-v8", "act1", DAY, 1L,
                budgets(10, 6, 15, 20));
        assertEquals(2L, replay.version());

        // 失败的 req-v7 未占键：修正参数后可成功
        ActivityDayConfigResponse retried = upsert("req-v7", "act1", DAY, 2L,
                budgets(10, 8, 15, 18));
        assertEquals(3L, retried.version());
    }

    @Test
    @DisplayName("日结查询固化每小时确认/取消/过期快照，后续配置变更不改写已结算日")
    void settlement_snapshotFrozenAfterDayEnds() {
        createActivity("req-act", "act1");
        upsert("req-cfg", "act1", DAY, 0L, budgets(10, 3));
        createCampaign("req-c1", "c1", 10, 10, "act1");

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        // 一笔任其自然过期
        service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));

        // 进入次日：结算 09-22
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:00Z"));
        ActivityDaySettlementResponse settlement = service.queryDaySettlement("act1", DAY);
        assertTrue(settlement.settled());
        assertNotNull(settlement.settledAtUtc());
        ActivityDaySettlementResponse.ActivityHourSettlement hour10 = settlement.hours().get(10);
        assertEquals(1, hour10.confirmedCount());
        assertEquals(1, hour10.cancelledCount());
        assertEquals(1, hour10.expiredCount());
        assertEquals(0, hour10.reservedCount());
        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_day_settlement WHERE activity_id = 'act1'",
                Integer.class);
        assertEquals(24, snapshotRows);

        // 已结算日配置变更（只允许上调已开始小时）不改写快照
        upsert("req-cfg2", "act1", DAY, 1L, budgets(10, 4));
        ActivityDaySettlementResponse after = service.queryDaySettlement("act1", DAY);
        assertEquals(1, after.hours().get(10).confirmedCount());
        assertEquals(1, after.hours().get(10).cancelledCount());
        assertEquals(1, after.hours().get(10).expiredCount());

        // 当日返回实时统计，不固化
        ActivityDaySettlementResponse live = service.queryDaySettlement("act1", NEXT_DAY);
        assertEquals(false, live.settled());
        assertNull(live.settledAtUtc());
        assertEquals(0, live.hours().get(0).confirmedCount());
    }

    @Test
    @DisplayName("新写操作幂等：同键同参重放原结果，异参 409；重复创建活动 409；不存在资源 404")
    void idempotencyAndMissingResources() {
        createActivity("req-i1", "act1");
        ActivityResponse replay = service.createActivity(new CreateActivityRequest("req-i1", "act1"));
        assertEquals("act1", replay.activityId());
        assert409(() -> service.createActivity(new CreateActivityRequest("req-i1", "act2")));
        assert409(() -> service.createActivity(new CreateActivityRequest("req-i2", "act1")));

        // 创建公告归入不存在的活动 → 404
        assert404(() -> createCampaign("req-c9", "c9", 1, 1, "nope"));

        assert404(() -> service.getActivity("nope"));
        assert404(() -> service.getDayConfig("act1", DAY));
        assert404(() -> service.queryActivityDayQuota("nope", DAY));
        assert404(() -> service.queryDaySettlement("nope", DAY));
    }

    @Test
    @DisplayName("预占历史查询：按创建时刻与编号升序，只读；其他日期为空；未知公告 404")
    void historyQuery_orderedStableAndReadOnly() {
        createCampaign("req-c1", "c1", 10, 10, null);
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        mutableClock().advanceMillis(1_000L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        mutableClock().advanceMillis(1_000L);
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v3"));

        List<ReservationResponse> history = service.listReservations("c1", DAY);
        assertEquals(3, history.size());
        assertEquals(r1.reservationId(), history.get(0).reservationId());
        assertEquals(r2.reservationId(), history.get(1).reservationId());
        assertEquals(r3.reservationId(), history.get(2).reservationId());
        assertTrue(history.get(0).createdAtUtc() < history.get(1).createdAtUtc());
        assertTrue(history.get(1).createdAtUtc() < history.get(2).createdAtUtc());

        assertEquals(0, service.listReservations("c1", NEXT_DAY).size());
        assert404(() -> service.listReservations("nope", DAY));

        // 只读：历史查询不改变额度
        assertEquals(3, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("HTTP 语义：活动/配置/申请状态码与 429 响应体携带下一可用小时")
    void httpSemantics_activityEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-act\",\"activityId\":\"actH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("actH"));

        // 非 24 项预算 → 400
        mockMvc.perform(put("/api/exposure/activities/actH/days/2026-09-22/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-bad\",\"expectedVersion\":0,"
                                + "\"dailyTotalCap\":0,\"hourlyBudgets\":[0,0,0]}"))
                .andExpect(status().isBadRequest());

        // 合法配置：小时 10 预算 1
        StringBuilder budgetsJson = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            budgetsJson.append(h == 10 ? "1" : "0");
            if (h < 23) {
                budgetsJson.append(',');
            }
        }
        mockMvc.perform(put("/api/exposure/activities/actH/days/2026-09-22/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cfg\",\"expectedVersion\":0,"
                                + "\"dailyTotalCap\":1,\"hourlyBudgets\":[" + budgetsJson + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.hourlyBudgets[10]").value(1));

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-c1\",\"campaignId\":\"ch\","
                                + "\"dailyTotalCap\":10,\"perVisitorDailyCap\":10,"
                                + "\"activityId\":\"actH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("actH"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-a1\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value("actH"))
                .andExpect(jsonPath("$.hourStartUtc").value(HOUR_10));

        // 小时预算耗尽：429 且响应体携带下一可用小时（无可用小时则为缺省/null）
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-a2\",\"campaignId\":\"ch\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.nextAvailableHourStartUtc").doesNotExist());

        mockMvc.perform(get("/api/exposure/activities/actH/days/2026-09-22/hours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.usedTotal").value(1))
                .andExpect(jsonPath("$.hours[10].used").value(1))
                .andExpect(jsonPath("$.hours[10].remaining").value(0));

        mockMvc.perform(get("/api/exposure/activities/actH/days/2026-09-22/budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTotalCap").value(1));

        mockMvc.perform(get("/api/exposure/activities/actH/days/2026-09-22/settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.hours[10].reservedCount").value(1));

        mockMvc.perform(get("/api/exposure/activities/actH/days/2026-09-25/budget"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/exposure/campaigns/ch/reservations")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert400(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(400, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }
}
