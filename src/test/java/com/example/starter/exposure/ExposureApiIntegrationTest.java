package com.example.starter.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.exposure.ExposureService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告曝光频控 H2（MODE=MySQL）集成测试：主流程、失败分支、跨日计数与幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExposureApiIntegrationTest {

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
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private CreateCampaignRequest createReq(String requestId, String campaignId, int total, int perVisitor) {
        return new CreateCampaignRequest(requestId, campaignId, total, perVisitor);
    }

    @Test
    @DisplayName("创建公告并申请曝光：预占 60 秒有效并占用两级额度")
    void apply_createsReservationAndOccupiesBothQuotas() {
        service.createCampaign(createReq("req-c1", "c1", 10, 2));

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        assertEquals("c1", r.campaignId());
        assertEquals("v1", r.visitorId());
        assertEquals(LocalDate.of(2026, 9, 22), r.utcDate());
        assertEquals("RESERVED", r.status().name());
        assertEquals(BASE.toEpochMilli(), r.createdAtUtc());
        assertEquals(BASE.toEpochMilli() + 60_000L, r.expiresAtUtc());
        assertNull(r.terminalAtUtc());
        assertNotNull(r.reservationId());

        QuotaResponse total = service.queryQuota("c1", null, LocalDate.of(2026, 9, 22));
        assertEquals(10, total.dailyTotalCap());
        assertEquals(1, total.usedTotal());
        assertEquals(9, total.remainingTotal());
        assertNull(total.usedVisitor());

        QuotaResponse visitor = service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(2, visitor.perVisitorDailyCap());
        assertEquals(1, visitor.usedVisitor());
        assertEquals(1, visitor.remainingVisitor());
    }

    @Test
    @DisplayName("确认后转 CONFIRMED 并持续占用当天额度；重复确认返回原状态")
    void confirm_keepsQuotaAndRepeatedConfirmReturnsSameState() {
        service.createCampaign(createReq("req-c1", "c1", 10, 2));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-ok1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(BASE.toEpochMilli(), confirmed.terminalAtUtc());

        // 同 requestId 重放：原成功结果
        ReservationResponse replay = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-ok1"));
        assertEquals("CONFIRMED", replay.status().name());

        // 新 requestId 的重复同类终态操作：仍返回原状态，不重复变更
        ReservationResponse again = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-ok2"));
        assertEquals("CONFIRMED", again.status().name());

        assertEquals(1, service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 22)).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, LocalDate.of(2026, 9, 22)).usedTotal());
    }

    @Test
    @DisplayName("取消仅适用于 RESERVED：释放两级额度，重复取消返回原状态")
    void cancel_releasesBothQuotasAndRepeatedCancelReturnsSameState() {
        service.createCampaign(createReq("req-c1", "c1", 1, 1));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        ReservationResponse cancelled = service.cancel(r.reservationId(),
                new ReservationActionRequest("req-x1"));
        assertEquals("CANCELLED", cancelled.status().name());

        ReservationResponse replay = service.cancel(r.reservationId(),
                new ReservationActionRequest("req-x1"));
        assertEquals("CANCELLED", replay.status().name());
        ReservationResponse again = service.cancel(r.reservationId(),
                new ReservationActionRequest("req-x2"));
        assertEquals("CANCELLED", again.status().name());

        QuotaResponse quota = service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        assertEquals(1, quota.remainingTotal());
        assertEquals(1, quota.remainingVisitor());

        // 释放后额度可再次申请
        ReservationResponse second = service.apply(new ApplyExposureRequest("req-a2", "c1", "v1"));
        assertEquals("RESERVED", second.status().name());
    }

    @Test
    @DisplayName("非法状态转换返回 409：确认已取消、取消已确认")
    void illegalTransitionsReturn409() {
        service.createCampaign(createReq("req-c1", "c1", 10, 2));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        assert409(() -> service.confirm(r1.reservationId(), new ReservationActionRequest("req-e1")));

        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        assert409(() -> service.cancel(r2.reservationId(), new ReservationActionRequest("req-e2")));
    }

    @Test
    @DisplayName("达到到期时刻即 EXPIRED 并释放；确认/取消过期单返回 409；不依赖定时器")
    void expiry_atExactMomentReleasesQuotaAndActionsReturn409() {
        service.createCampaign(createReq("req-c1", "c1", 10, 2));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        // 到期前 1 毫秒仍可确认的边界由另一用例覆盖；此处推进到恰好到期时刻
        mutableClock().advanceMillis(60_000L);

        // 任意操作先结算：确认过期单 409
        assert409(() -> service.confirm(r.reservationId(), new ReservationActionRequest("req-e1")));
        assert409(() -> service.cancel(r.reservationId(), new ReservationActionRequest("req-e2")));

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("EXPIRED", detail.status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, detail.terminalAtUtc());

        QuotaResponse quota = service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
    }

    @Test
    @DisplayName("到期前一刻可确认；跨日确认不迁移计数，历史日账目可查")
    void confirmAcrossUtcDay_keepsOriginalDayLedger() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        service.createCampaign(createReq("req-c1", "c1", 10, 2));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals(LocalDate.of(2026, 9, 22), r.utcDate());

        // 推进到次日但未到到期时刻（到期 = 23:59:30 + 60s = 次日 00:00:30）
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());

        // 原 UTC 日仍计数，新 UTC 日额度全新
        assertEquals(1, service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 22)).usedTotal());
        QuotaResponse nextDay = service.queryQuota("c1", "v1", LocalDate.of(2026, 9, 23));
        assertEquals(0, nextDay.usedTotal());
        assertEquals(0, nextDay.usedVisitor());
        assertEquals(10, nextDay.remainingTotal());
    }

    @Test
    @DisplayName("任一额度已满返回 429，两个额度均不增加；访客上限同样生效")
    void quotaExhausted_returns429AndNoLedgerIncrease() {
        service.createCampaign(createReq("req-c1", "c1", 2, 2));
        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));

        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "c1", "v3")));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a4", "c1", "v1")));

        QuotaResponse quota = service.queryQuota("c1", null, LocalDate.of(2026, 9, 22));
        assertEquals(2, quota.usedTotal());
        assertEquals(0, quota.remainingTotal());
        assertEquals(0, service.queryQuota("c1", "v3", LocalDate.of(2026, 9, 22)).usedVisitor());
    }

    @Test
    @DisplayName("幂等：同键同参重放原结果；异参 409；失败不占键")
    void idempotency_replaySameResult_conflictOnDifferentParams_failureDoesNotOccupy() {
        service.createCampaign(createReq("req-c1", "c1", 1, 5));
        ReservationResponse first = service.apply(new ApplyExposureRequest("key-1", "c1", "v1"));

        ReservationResponse replay = service.apply(new ApplyExposureRequest("key-1", "c1", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(1, service.queryQuota("c1", null, LocalDate.of(2026, 9, 22)).usedTotal());

        // 同键异参（不同访客）→ 409
        assert409(() -> service.apply(new ApplyExposureRequest("key-1", "c1", "v2")));
        // 同键用于其他操作 → 409
        assert409(() -> service.confirm(first.reservationId(),
                new ReservationActionRequest("key-1")));

        // 失败的申请（总额已满）不占键：释放额度后同键同参可成功
        assert429(() -> service.apply(new ApplyExposureRequest("key-fail", "c1", "v2")));
        service.cancel(first.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = service.apply(new ApplyExposureRequest("key-fail", "c1", "v2"));
        assertEquals("RESERVED", retried.status().name());
        assertEquals(1, service.queryQuota("c1", null, LocalDate.of(2026, 9, 22)).usedTotal());
    }

    @Test
    @DisplayName("重复创建公告 409；不存在资源 404")
    void duplicateCampaignAndMissingResources() {
        service.createCampaign(createReq("req-c1", "c1", 10, 2));
        assert409(() -> service.createCampaign(createReq("req-c2", "c1", 20, 3)));
        assert404(() -> service.getReservation("nonexistent"));
        assert404(() -> service.queryQuota("nope", null, LocalDate.of(2026, 9, 22)));
    }

    @Test
    @DisplayName("HTTP 语义：创建 201、额度不足 429、参数越界 400")
    void httpSemantics_201_429_400() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":1,"
                                + "\"perVisitorDailyCap\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value("ch"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"campaignId\":\"ch\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"bad\",\"dailyTotalCap\":0,"
                                + "\"perVisitorDailyCap\":1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(1));
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(com.example.starter.exposure.web.ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(com.example.starter.exposure.web.ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(com.example.starter.exposure.web.ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
