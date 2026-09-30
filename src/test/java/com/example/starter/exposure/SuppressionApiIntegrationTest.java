package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.suppression.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CancelledReservationView;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionJoinRequest;
import com.example.starter.exposure.web.SuppressionReleaseRequest;
import com.example.starter.exposure.web.SuppressionResponse;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客受众抑制名单 H2（MODE=MySQL）集成测试：主流程、失败分支、状态与幂等边界。
 * 使用独立命名内存库 suppression_it，与其他测试类隔离；每例清理场景数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:suppression_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class SuppressionApiIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-09-30T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService exposureService;
    @Autowired
    SuppressionService suppressionService;
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
        jdbc.update("DELETE FROM suppression_cancelled_reservation");
        jdbc.update("DELETE FROM suppression_period");
        jdbc.update("DELETE FROM suppression_list");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM visitor_lock");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        exposureService.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    @Test
    @DisplayName("主流程：两笔未确认预占各占1次，加入后均取消并返还2次，再申请409且不占额度")
    void join_cancelsAllReserved_releasesTwoQuota_futureApply409() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        ReservationResponse r2 = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));
        assertQuota("c1", "V", 2, 2);

        SuppressionResponse joined = suppressionService.join(
                new SuppressionJoinRequest("req-s1", "V"));

        assertEquals("ACTIVE", joined.status().name());
        assertEquals(BASE.toEpochMilli(), joined.effectiveFromUtc());
        assertNull(joined.effectiveToUtc());
        assertEquals(2, joined.cancelledCount(), "重放/查询必须返回首次取消数量 2");
        assertEquals(2, joined.releasedQuota());
        assertEquals(1, joined.periods().size());
        assertEquals("req-s1", joined.periods().get(0).joinRequestId());
        List<CancelledReservationView> cancelled = joined.periods().get(0).cancelledReservations();
        assertEquals(2, cancelled.size());
        assertEquals(1, cancelled.get(0).releasedQuota());
        assertEquals(1, cancelled.get(1).releasedQuota());
        assertNotNull(joined.suppressionId());

        // 两笔预占均已取消，终态时刻为加入时刻
        assertEquals("CANCELLED", exposureService.getReservation(r1.reservationId()).status().name());
        assertEquals("CANCELLED", exposureService.getReservation(r2.reservationId()).status().name());
        assertEquals(BASE.toEpochMilli(), exposureService.getReservation(r1.reservationId()).terminalAtUtc());
        // 两级额度各返还 2 次
        assertQuota("c1", "V", 0, 0);

        // 抑制生效后新申请 409 且不占额度
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a3", "c1", "V")));
        assertQuota("c1", "V", 0, 0);
    }

    @Test
    @DisplayName("跨公告取消全部未确认预占；已确认曝光保留历史且计数不回退，仍可审计")
    void join_cancelsAcrossCampaigns_keepsConfirmedHistoryAndCount() {
        createCampaign("req-c1", "c1", 10, 10);
        createCampaign("req-c2", "c2", 10, 10);
        ReservationResponse confirmed = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        ReservationResponse reservedC1 = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));
        ReservationResponse reservedC2 = exposureService.apply(new ApplyExposureRequest("req-a3", "c2", "V"));
        exposureService.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));

        SuppressionResponse joined = suppressionService.join(
                new SuppressionJoinRequest("req-s1", "V"));

        assertEquals(2, joined.cancelledCount());
        assertEquals("CANCELLED", exposureService.getReservation(reservedC1.reservationId()).status().name());
        assertEquals("CANCELLED", exposureService.getReservation(reservedC2.reservationId()).status().name());
        // 已确认曝光保留，不回退已发生计数
        ReservationResponse stillConfirmed = exposureService.getReservation(confirmed.reservationId());
        assertEquals("CONFIRMED", stillConfirmed.status().name());

        QuotaResponse c1 = exposureService.queryQuota("c1", "V", DAY);
        assertEquals(1, c1.usedTotal(), "已确认的 1 次持续占用");
        assertEquals(1, c1.usedVisitor());
        QuotaResponse c2 = exposureService.queryQuota("c2", "V", DAY);
        assertEquals(0, c2.usedTotal());
        assertEquals(0, c2.usedVisitor());

        // 查询中历史已确认曝光仍可审计
        SuppressionResponse view = suppressionService.getSuppression("V");
        assertEquals(1, view.confirmedReservations().size());
        assertEquals(confirmed.reservationId(), view.confirmedReservations().get(0).reservationId());
        assertEquals(2, view.periods().get(0).cancelledReservations().size());
    }

    @Test
    @DisplayName("幂等：同 requestId 重放首次取消数量，不重复取消/释放/写周期")
    void join_sameRequestIdReplaysFirstResult() {
        createCampaign("req-c1", "c1", 10, 10);
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));

        SuppressionResponse first = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        SuppressionResponse replay = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));

        assertEquals(first.suppressionId(), replay.suppressionId());
        assertEquals(2, replay.cancelledCount());
        assertEquals(2, replay.releasedQuota());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suppression_period", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancelled_reservation", Integer.class));
        assertQuota("c1", "V", 0, 0);
    }

    @Test
    @DisplayName("幂等冲突：同 requestId 换访客、换动作（解除/申请/确认）均 409")
    void sameRequestId_differentVisitorOrAction_returns409() {
        createCampaign("req-c1", "c1", 10, 10);
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));

        assert409(() -> suppressionService.join(new SuppressionJoinRequest("req-s1", "V2")));
        assert409(() -> suppressionService.release("V", new SuppressionReleaseRequest("req-s1")));
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-s1", "c1", "V")));

        // 解除键同样不得换动作/换访客
        suppressionService.release("V", new SuppressionReleaseRequest("req-r1"));
        assert409(() -> suppressionService.join(new SuppressionJoinRequest("req-r1", "V")));
        assert409(() -> suppressionService.release("V2", new SuppressionReleaseRequest("req-r1")));
    }

    @Test
    @DisplayName("重复加入（ACTIVE，新 requestId）返回稳定结果，不重复取消、不新增周期")
    void repeatedJoinWhileActive_returnsStableState() {
        createCampaign("req-c1", "c1", 10, 10);
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        SuppressionResponse first = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));

        SuppressionResponse again = suppressionService.join(new SuppressionJoinRequest("req-s2", "V"));

        assertEquals("ACTIVE", again.status().name());
        assertEquals(first.suppressionId(), again.suppressionId());
        assertEquals(1, again.cancelledCount());
        assertEquals(1, again.periods().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suppression_period", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancelled_reservation", Integer.class));
        assertQuota("c1", "V", 0, 0);
    }

    @Test
    @DisplayName("解除只允许后续申请：已取消预占不恢复；重复解除与同键重放返回稳定结果")
    void release_allowsOnlyFutureApply_cancelledNotRestored() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        ReservationResponse r2 = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));
        suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));

        mutableClock().advanceMillis(5_000L);
        SuppressionResponse released = suppressionService.release(
                "V", new SuppressionReleaseRequest("req-r1"));
        assertEquals("RELEASED", released.status().name());
        assertEquals(BASE.toEpochMilli() + 5_000L, released.effectiveToUtc());
        assertEquals("req-r1", released.periods().get(0).releaseRequestId());

        // 同键重放返回首次解除结果
        SuppressionResponse replay = suppressionService.release(
                "V", new SuppressionReleaseRequest("req-r1"));
        assertEquals(released.effectiveToUtc(), replay.effectiveToUtc());
        // 新 requestId 重复解除：稳定返回 RELEASED
        SuppressionResponse again = suppressionService.release(
                "V", new SuppressionReleaseRequest("req-r2"));
        assertEquals("RELEASED", again.status().name());

        // 已取消预占不恢复
        assertEquals("CANCELLED", exposureService.getReservation(r1.reservationId()).status().name());
        assertEquals("CANCELLED", exposureService.getReservation(r2.reservationId()).status().name());

        // 解除后新申请成功并占额度
        ReservationResponse future = exposureService.apply(new ApplyExposureRequest("req-a4", "c1", "V"));
        assertEquals("RESERVED", future.status().name());
        assertQuota("c1", "V", 1, 1);
    }

    @Test
    @DisplayName("解除后再次加入开启新周期：新周期取消当下未确认预占，历史周期保留")
    void rejoinAfterRelease_createsNewPeriodAndCancelsAgain() {
        createCampaign("req-c1", "c1", 10, 10);
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        SuppressionResponse first = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        assertEquals(1, first.cancelledCount());
        suppressionService.release("V", new SuppressionReleaseRequest("req-r1"));
        ReservationResponse later = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));

        SuppressionResponse second = suppressionService.join(new SuppressionJoinRequest("req-s2", "V"));
        assertEquals("ACTIVE", second.status().name());
        assertEquals(1, second.cancelledCount());
        assertEquals(2, second.periods().size(), "两个抑制周期");
        assertEquals(second.suppressionId(), second.periods().get(0).suppressionId(),
                "周期按生效时刻倒序，新周期在前");
        assertEquals(first.suppressionId(), second.periods().get(1).suppressionId());
        assertEquals(1, second.periods().get(0).cancelledReservations().size());
        assertEquals(1, second.periods().get(1).cancelledReservations().size());
        assertEquals("CANCELLED", exposureService.getReservation(later.reservationId()).status().name());
        assertQuota("c1", "V", 0, 0);
    }

    @Test
    @DisplayName("失败不占键：抑制中被 409 拒绝的申请键，解除后同键同参可成功")
    void rejectedApplyWhileSuppressed_doesNotOccupyIdempotencyKey() {
        createCampaign("req-c1", "c1", 10, 10);
        suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-x", "c1", "V")));

        suppressionService.release("V", new SuppressionReleaseRequest("req-r1"));
        ReservationResponse ok = exposureService.apply(new ApplyExposureRequest("req-x", "c1", "V"));
        assertEquals("RESERVED", ok.status().name());
    }

    @Test
    @DisplayName("失败分支：未加入过的访客解除/查询 404；抑制中申请 409 不占额度")
    void unknownVisitorAndSuppressedApply_failures() {
        createCampaign("req-c1", "c1", 10, 10);
        assert404(() -> suppressionService.release("nobody", new SuppressionReleaseRequest("req-r1")));
        assert404(() -> suppressionService.getSuppression("nobody"));

        suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a9", "c1", "V")));
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("加入时无未确认预占：取消数量 0；抑制起止与审计字段正确")
    void join_withoutReservations_cancelsZero() {
        createCampaign("req-c1", "c1", 10, 10);
        SuppressionResponse joined = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        assertEquals(0, joined.cancelledCount());
        assertEquals(0, joined.releasedQuota());
        assertEquals(1, joined.periods().size());
        assertEquals(0, joined.periods().get(0).cancelledReservations().size());
        assertNull(joined.periods().get(0).effectiveToUtc());
        assertEquals(BASE.toEpochMilli(), joined.queriedAtUtc());
    }

    @Test
    @DisplayName("HTTP 语义：加入/查询/解除 200，抑制中申请 409，参数缺失 400")
    void httpSemantics() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h0\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"visitorId\":\"uv\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/exposure/suppressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"visitorId\":\"uv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cancelledCount").value(1))
                .andExpect(jsonPath("$.releasedQuota").value(1));

        mockMvc.perform(get("/api/exposure/suppressions/uv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.periods[0].cancelledReservations[0].releasedQuota").value(1));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"campaignId\":\"ch\",\"visitorId\":\"uv\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/exposure/suppressions/uv/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(post("/api/exposure/suppressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("抑制取消只针对 RESERVED：已到期未确认单同样被取消并释放，账目不为负")
    void join_cancelsExpiredButUnconfirmedToo() {
        createCampaign("req-c1", "c1", 10, 10);
        ReservationResponse r = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        mutableClock().advanceMillis(60_000L);

        SuppressionResponse joined = suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        assertEquals(1, joined.cancelledCount());
        assertEquals("CANCELLED", exposureService.getReservation(r.reservationId()).status().name());
        assertQuota("c1", "V", 0, 0);
        assertTrue(exposureService.queryQuota("c1", "V", DAY).remainingVisitor() >= 0);
    }

    private void assertQuota(String campaignId, String visitorId, int usedTotal, int usedVisitor) {
        QuotaResponse quota = exposureService.queryQuota(campaignId, visitorId, DAY);
        assertEquals(usedTotal, quota.usedTotal());
        assertEquals(usedVisitor, quota.usedVisitor());
    }

    private void assert409(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
