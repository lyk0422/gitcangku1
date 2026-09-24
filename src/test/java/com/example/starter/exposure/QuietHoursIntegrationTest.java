package com.example.starter.exposure;

import com.example.starter.exposure.domain.CampaignCategory;
import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuietHoursSettingsRequest;
import com.example.starter.exposure.web.QuietHoursSettingsResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.SuppressionStatsResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客静默时段与公告类别抑制 H2（MODE=MySQL）集成测试：
 * 跨零点静默、抑制先于额度、CRITICAL/allowCritical、设置版本冲突、抑制统计与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuietHoursIntegrationTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
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
        jdbc.update("DELETE FROM suppression_counter");
        jdbc.update("DELETE FROM visitor_quiet_hours");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor,
                                CampaignCategory category) {
        service.createCampaign(new CreateCampaignRequest(
                requestId, campaignId, total, perVisitor, category));
    }

    private QuietHoursSettingsResponse register(String requestId, String visitorId, int offset,
                                                int start, int end, boolean allowCritical) {
        return service.saveQuietHours(new QuietHoursSettingsRequest(
                requestId, visitorId, offset, start, end, allowCritical, 0));
    }

    private QuietHoursSettingsResponse update(String requestId, String visitorId, int offset,
                                              int start, int end, boolean allowCritical, int version) {
        return service.saveQuietHours(new QuietHoursSettingsRequest(
                requestId, visitorId, offset, start, end, allowCritical, version));
    }

    @Test
    @DisplayName("跨零点静默（UTC+8，22:00-06:00 本地）：UTC 22:30 本地 06:30 放行；UTC 15:00 本地 23:00 抑制")
    void crossMidnightQuietHours_suppressesAndReleases() {
        createCampaign("req-c", "cm", 10, 10, CampaignCategory.MARKETING);
        register("req-q", "v1", 480, 1320, 360, false);

        // UTC 15:00 = 本地 23:00，落在跨零点静默区间
        mutableClock().setInstant(Instant.parse("2026-09-22T15:00:00Z"));
        ApplyResponse suppressed = service.apply(new ApplyExposureRequest("req-a1", "cm", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        assertNull(suppressed.reservationId());
        assertNull(suppressed.status());
        // 静默结束 = 本地次日 06:00 = UTC 22:00
        assertEquals(Instant.parse("2026-09-22T22:00:00Z").toEpochMilli(),
                suppressed.quietUntilUtc());
        assertEquals(LocalDate.of(2026, 9, 22), suppressed.utcDate());
        assertEquals(CampaignCategory.MARKETING, suppressed.category());

        // 推进到 UTC 21:59 = 本地 05:59，仍静默
        mutableClock().setInstant(Instant.parse("2026-09-22T21:59:00Z"));
        ApplyResponse stillQuiet = service.apply(new ApplyExposureRequest("req-a2", "cm", "v1"));
        assertEquals("SUPPRESSED", stillQuiet.outcome().name());
        assertEquals(Instant.parse("2026-09-22T22:00:00Z").toEpochMilli(),
                stillQuiet.quietUntilUtc());

        // UTC 22:00 = 本地 06:00（结束边界排他），放行
        mutableClock().setInstant(Instant.parse("2026-09-22T22:00:00Z"));
        ApplyResponse reserved = service.apply(new ApplyExposureRequest("req-a3", "cm", "v1"));
        assertEquals("RESERVED", reserved.outcome().name());
        assertNull(reserved.quietUntilUtc());
        assertNotNull(reserved.reservationId());

        // 抑制不留预占、不占额度
        QuotaResponse quota = service.queryQuota("cm", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());
        assertEquals(2, service.querySuppressionStats("cm", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());
        // 两次抑制 + 一次放行：仅放行产生 1 张预占单
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM exposure_reservation", Integer.class));
    }

    @Test
    @DisplayName("抑制先于额度：总额度为 0 容量被占满时静默申请返回 SUPPRESSED 而非 429")
    void suppressionPrecedesQuota_suppressedEvenWhenFull() {
        createCampaign("req-c", "cf", 1, 1, CampaignCategory.SERVICE);
        register("req-q", "v1", 0, 0, 1439, false); // 本地全天静默（00:00-23:59）

        // 另一访客在额度满之前占掉唯一额度（v2 未登记静默）
        ApplyResponse filler = service.apply(new ApplyExposureRequest("req-fill", "cf", "v2"));
        assertEquals("RESERVED", filler.outcome().name());

        // v1 处于静默：即使额度已满也返回 SUPPRESSED，不是 429
        ApplyResponse suppressed = service.apply(new ApplyExposureRequest("req-a1", "cf", "v1"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
        assertNotNull(suppressed.quietUntilUtc());

        // 两级额度仍为 1（未写抑制申请的账目）
        QuotaResponse total = service.queryQuota("cf", null, LocalDate.of(2026, 9, 22));
        assertEquals(1, total.usedTotal());
        assertEquals(0, total.remainingTotal());
        assertEquals(0, service.queryQuota("cf", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
    }

    @Test
    @DisplayName("非静默时段额度已满仍 429，且抑制统计与两级额度均不变")
    void outsideQuietHours_quotaFullStill429() {
        createCampaign("req-c", "cf", 1, 1, CampaignCategory.SERVICE);
        register("req-q", "v1", 0, 1200, 1300, false); // 本地 20:00-21:40 静默，BASE 10:00 不在内

        ApplyResponse ok = service.apply(new ApplyExposureRequest("req-a1", "cf", "v1"));
        assertEquals("RESERVED", ok.outcome().name());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "cf", "v1")));
        assertEquals(429, ex.getStatus().value());

        assertEquals(0, service.querySuppressionStats("cf", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());
        assertEquals(1, service.queryQuota("cf", null, LocalDate.of(2026, 9, 22)).usedTotal());
    }

    @Test
    @DisplayName("CRITICAL：allowCritical=true 静默内照常预占；改 false 后抑制；SERVICE/MARKETING 一律抑制")
    void criticalRespectsAllowCriticalFlag() {
        createCampaign("req-cc", "cc", 10, 10, CampaignCategory.CRITICAL);
        createCampaign("req-cs", "cs", 10, 10, CampaignCategory.SERVICE);
        register("req-q", "v1", 0, 0, 1439, true); // 本地 00:00-23:59 静默，允许 CRITICAL

        // 静默内 + allowCritical=true：CRITICAL 放行
        ApplyResponse criticalAllowed = service.apply(new ApplyExposureRequest("req-a1", "cc", "v1"));
        assertEquals("RESERVED", criticalAllowed.outcome().name());

        // SERVICE 静默内一律抑制
        ApplyResponse serviceSuppressed = service.apply(new ApplyExposureRequest("req-a2", "cs", "v1"));
        assertEquals("SUPPRESSED", serviceSuppressed.outcome().name());

        // 关闭 allowCritical（当前版本 1）后：CRITICAL 同样抑制
        QuietHoursSettingsResponse v2 = update("req-q3", "v1", 0, 0, 1439, false, 1);
        assertEquals(2, v2.version());
        ApplyResponse criticalBlocked = service.apply(new ApplyExposureRequest("req-a3", "cc", "v1"));
        assertEquals("SUPPRESSED", criticalBlocked.outcome().name());

        // 抑制计数按公告+访客+UTC 日分别累计
        assertEquals(1, service.querySuppressionStats("cs", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());
        assertEquals(1, service.querySuppressionStats("cc", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());
        // 两个公告总额度：cc 被占用 1 次，cs 未被占用
        assertEquals(1, service.queryQuota("cc", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
        assertEquals(0, service.queryQuota("cs", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
    }

    @Test
    @DisplayName("未登记访客视为无静默：各类别均正常预占")
    void unregisteredVisitorHasNoQuietHours() {
        createCampaign("req-c", "cm", 5, 5, CampaignCategory.MARKETING);
        ApplyResponse r = service.apply(new ApplyExposureRequest("req-a1", "cm", "ghost"));
        assertEquals("RESERVED", r.outcome().name());
        assertThrows(ApiException.class, () -> service.getQuietHours("ghost"));
    }

    @Test
    @DisplayName("静默设置：登记版本为 1，错误 expectedVersion 冲突 409，失败不改变版本")
    void quietHoursVersionConflict_returns409() {
        QuietHoursSettingsResponse v1 = register("req-q1", "v1", 0, 1200, 1300, true);
        assertEquals(1, v1.version());

        // 首次登记时对已存在访客再传 expectedVersion=0：409
        ApiException registerAgain = assertThrows(ApiException.class,
                () -> register("req-q2", "v1", 0, 1200, 1300, true));
        assertEquals(409, registerAgain.getStatus().value());

        // 过期版本修改：409
        ApiException stale = assertThrows(ApiException.class,
                () -> update("req-q3", "v1", 60, 1200, 1300, true, 5));
        assertEquals(409, stale.getStatus().value());

        // 设置未被失败修改
        QuietHoursSettingsResponse unchanged = service.getQuietHours("v1");
        assertEquals(1, unchanged.version());
        assertEquals(0, unchanged.utcOffsetMinutes());

        // 正确版本修改成功，只影响后续申请
        QuietHoursSettingsResponse v2 = update("req-q4", "v1", 0, 1200, 1300, true, 1);
        assertEquals(2, v2.version());

        // 同键同参重放：返回首次结果，版本不重复增加
        QuietHoursSettingsResponse replay = update("req-q4", "v1", 0, 1200, 1300, true, 1);
        assertEquals(2, replay.version());

        // 同键异参（不同窗口）409
        ApiException differentParams = assertThrows(ApiException.class,
                () -> service.saveQuietHours(new QuietHoursSettingsRequest(
                        "req-q4", "v1", 0, 600, 700, true, 2)));
        assertEquals(409, differentParams.getStatus().value());
    }

    @Test
    @DisplayName("起止分钟相同：服务层直接返回 400")
    void equalStartEndMinutes_400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.saveQuietHours(new QuietHoursSettingsRequest(
                        "req-bad1", "v1", 0, 600, 600, true, 0)));
        assertEquals(400, ex.getStatus().value());
        // 失败不占键、不登记设置
        assertThrows(ApiException.class, () -> service.getQuietHours("v1"));
    }

    @Test
    @DisplayName("偏移与分钟越界：Bean Validation 在 HTTP 层统一 400")
    void outOfRangeParameters_http400() throws Exception {
        String[] bodies = {
                "{\"requestId\":\"b1\",\"visitorId\":\"v1\",\"utcOffsetMinutes\":-721,"
                        + "\"quietStartMinute\":600,\"quietEndMinute\":601,\"allowCritical\":true,"
                        + "\"expectedVersion\":0}",
                "{\"requestId\":\"b2\",\"visitorId\":\"v1\",\"utcOffsetMinutes\":841,"
                        + "\"quietStartMinute\":600,\"quietEndMinute\":601,\"allowCritical\":true,"
                        + "\"expectedVersion\":0}",
                "{\"requestId\":\"b3\",\"visitorId\":\"v1\",\"utcOffsetMinutes\":0,"
                        + "\"quietStartMinute\":-1,\"quietEndMinute\":601,\"allowCritical\":true,"
                        + "\"expectedVersion\":0}",
                "{\"requestId\":\"b4\",\"visitorId\":\"v1\",\"utcOffsetMinutes\":0,"
                        + "\"quietStartMinute\":600,\"quietEndMinute\":1440,\"allowCritical\":true,"
                        + "\"expectedVersion\":0}",
                "{\"requestId\":\"b5\",\"visitorId\":\"v1\",\"utcOffsetMinutes\":0,"
                        + "\"quietStartMinute\":600,\"quietEndMinute\":601,\"allowCritical\":true,"
                        + "\"expectedVersion\":-1}"
        };
        for (String body : bodies) {
            mockMvc.perform(put("/api/exposure/visitors/v1/quiet-hours")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("静默只影响新申请：登记静默前创建的 RESERVED 仍可确认/取消，过期结算照常")
    void quietHoursDoNotAffectExistingReservations() {
        createCampaign("req-c1", "cm", 1, 1, CampaignCategory.MARKETING);
        // 初始无静默，申请成功
        ApplyResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cm", "v1"));
        assertEquals("RESERVED", r1.outcome().name());

        // 申请后再登记静默，不影响已有预占的确认
        register("req-q1", "v1", 0, 0, 1439, false);
        var confirmed = service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        // 确认后额度仍占用，未被静默改写
        assertEquals(1, service.queryQuota("cm", "v1", LocalDate.of(2026, 9, 22)).usedTotal());

        // 另一公告：登记静默前申请，之后取消仍可释放两级额度
        createCampaign("req-c2", "cm2", 1, 1, CampaignCategory.SERVICE);
        ApplyResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cm2", "v2"));
        assertEquals("RESERVED", r2.outcome().name());
        register("req-q2", "v2", 0, 0, 1439, false);
        var cancelled = service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals("CANCELLED", cancelled.status().name());
        assertEquals(0, service.queryQuota("cm2", "v2", LocalDate.of(2026, 9, 22)).usedTotal());

        // 登记静默后新申请被抑制，不产生新预占
        ApplyResponse suppressed = service.apply(new ApplyExposureRequest("req-a3", "cm2", "v2"));
        assertEquals("SUPPRESSED", suppressed.outcome().name());
    }

    @Test
    @DisplayName("抑制申请幂等：同键重放同一 SUPPRESSED（含 quietUntilUtc），异参 409；额度日固定为申请 UTC 日")
    void suppressionIdempotency_replaysSuppressedResult() {
        createCampaign("req-c", "cm", 10, 10, CampaignCategory.MARKETING);
        register("req-q", "v1", 0, 0, 1439, false);

        ApplyResponse first = service.apply(new ApplyExposureRequest("key-s", "cm", "v1"));
        assertEquals("SUPPRESSED", first.outcome().name());

        // 推进时钟后重放：仍返回首次结果（时刻不随重放改变），且不重复计数
        mutableClock().setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        ApplyResponse replay = service.apply(new ApplyExposureRequest("key-s", "cm", "v1"));
        assertEquals("SUPPRESSED", replay.outcome().name());
        assertEquals(first.quietUntilUtc(), replay.quietUntilUtc());
        assertEquals(1, service.querySuppressionStats("cm", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());

        // 同键异参（不同访客）409
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("key-s", "cm", "v2")));
        assertEquals(409, conflict.getStatus().value());

        // 同键用于其他操作类型 409
        ApiException opConflict = assertThrows(ApiException.class,
                () -> service.saveQuietHours(new QuietHoursSettingsRequest(
                        "key-s", "v1", 0, 0, 1439, false, 1)));
        assertEquals(409, opConflict.getStatus().value());
    }

    @Test
    @DisplayName("修改设置后只影响后续申请：静默改非静默后立即放行，历史账目不改写")
    void settingsChangeOnlyAffectsFutureApplications() {
        createCampaign("req-c", "cm", 2, 2, CampaignCategory.MARKETING);
        register("req-q1", "v1", 0, 0, 1439, false);

        ApplyResponse s1 = service.apply(new ApplyExposureRequest("req-a1", "cm", "v1"));
        assertEquals("SUPPRESSED", s1.outcome().name());

        // 改为非静默（20:00-21:40），当前 10:00 放行
        update("req-q2", "v1", 0, 1200, 1300, false, 1);
        ApplyResponse r1 = service.apply(new ApplyExposureRequest("req-a2", "cm", "v1"));
        assertEquals("RESERVED", r1.outcome().name());

        // 历史抑制统计保留，额度仅放行申请占用 1 次
        assertEquals(1, service.querySuppressionStats("cm", "v1", LocalDate.of(2026, 9, 22))
                .suppressedCount());
        assertEquals(1, service.queryQuota("cm", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
    }

    @Test
    @DisplayName("抑制统计与额度查询：无抑制记录时次数为 0；统计不存在公告 404")
    void statsAndQuotaQueries() {
        createCampaign("req-c", "cm", 5, 5, CampaignCategory.MARKETING);
        SuppressionStatsResponse zero =
                service.querySuppressionStats("cm", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(0, zero.suppressedCount());

        assertThrows(ApiException.class,
                () -> service.querySuppressionStats("nope", "v1", LocalDate.of(2026, 9, 22)));
    }

    @Test
    @DisplayName("HTTP 语义：抑制 200 且携带 quietUntilUtc；设置登记 200、版本冲突 409、统计与额度可查")
    void httpSemantics_suppressed200_settingsAndStats() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":2,"
                                + "\"perVisitorDailyCap\":2,\"category\":\"MARKETING\"}"))
                .andExpect(status().isCreated());

        // 登记静默：UTC+0 本地 00:00-23:59
        mockMvc.perform(put("/api/exposure/visitors/u1/quiet-hours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"visitorId\":\"u1\",\"utcOffsetMinutes\":0,"
                                + "\"quietStartMinute\":0,\"quietEndMinute\":1439,"
                                + "\"allowCritical\":false,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 静默内申请：200 SUPPRESSED
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUPPRESSED"))
                .andExpect(jsonPath("$.reservationId").doesNotExist())
                .andExpect(jsonPath("$.quietUntilUtc").exists());

        // 版本冲突：仍用 expectedVersion=0
        mockMvc.perform(put("/api/exposure/visitors/u1/quiet-hours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"visitorId\":\"u1\",\"utcOffsetMinutes\":0,"
                                + "\"quietStartMinute\":0,\"quietEndMinute\":1439,"
                                + "\"allowCritical\":false,\"expectedVersion\":0}"))
                .andExpect(status().isConflict());

        // 抑制统计 1 次
        mockMvc.perform(get("/api/exposure/campaigns/ch/suppressions")
                        .param("visitorId", "u1")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressedCount").value(1));

        // 额度未被抑制申请占用
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(0))
                .andExpect(jsonPath("$.usedVisitor").value(0));

        // 路径与 body 访客不一致 400
        mockMvc.perform(put("/api/exposure/visitors/u9/quiet-hours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"visitorId\":\"u1\",\"utcOffsetMinutes\":0,"
                                + "\"quietStartMinute\":0,\"quietEndMinute\":1439,"
                                + "\"allowCritical\":false,\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
    }
}
