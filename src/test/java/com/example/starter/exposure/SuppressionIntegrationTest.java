package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.suppression.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionActionRequest;
import com.example.starter.exposure.web.SuppressionCancellationItem;
import com.example.starter.exposure.web.SuppressionQuotaChange;
import com.example.starter.exposure.web.SuppressionResponse;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客受众抑制名单 H2（MODE=MySQL）集成测试：
 * 主流程（原子取消并释放、生效后 409）、失败分支、幂等与状态边界、明细与额度变化查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuppressionIntegrationTest {

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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    SuppressionService suppressionService;
    @Autowired
    ExposureService exposureService;
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
        jdbc.update("DELETE FROM suppression_cancellation");
        jdbc.update("DELETE FROM visitor_suppression");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @Test
    @DisplayName("题干主流程：两笔未确认预占各占1次，加入名单后均取消并返还2次，再申请409且不占额度")
    void suppress_cancelsAllReserved_releasesQuota_thenApply409() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        ReservationResponse r2 = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));

        SuppressionResponse suppressed = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s1"));

        assertTrue(suppressed.active());
        assertEquals(BASE.toEpochMilli(), suppressed.suppressedAtUtc());
        assertNull(suppressed.releasedAtUtc());
        assertEquals(2, suppressed.cancelledCount(), "首次取消数量必须为2");
        assertEquals(2, suppressed.quotaReleased(), "返还两级额度次数各为2");
        Set<String> cancelledIds = suppressed.cancellations().stream()
                .map(SuppressionCancellationItem::reservationId).collect(Collectors.toSet());
        assertEquals(Set.of(r1.reservationId(), r2.reservationId()), cancelledIds);
        List<SuppressionQuotaChange> changes = suppressed.quotaChanges();
        assertEquals(1, changes.size());
        assertEquals("c1", changes.get(0).campaignId());
        assertEquals(DAY, changes.get(0).utcDate());
        assertEquals(2, changes.get(0).releasedTotal());
        assertEquals(2, changes.get(0).releasedVisitor());

        // 两笔预占均为 CANCELLED，两级额度恰好返还
        assertEquals("CANCELLED", exposureService.getReservation(r1.reservationId()).status().name());
        assertEquals("CANCELLED", exposureService.getReservation(r2.reservationId()).status().name());
        QuotaResponse quota = exposureService.queryQuota("c1", "V", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        assertEquals(10, quota.remainingTotal());
        assertEquals(10, quota.remainingVisitor());

        // 抑制生效后新预占一律 409，且不占额度
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a3", "c1", "V")));
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("已确认曝光保留历史不回退计数，不被取消；历史仍可审计且不产生新预占")
    void suppress_keepsConfirmedHistoryAndDoesNotRollbackCount() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        ReservationResponse confirmed = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        exposureService.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse reserved = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));

        SuppressionResponse result = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s1"));

        // 仅取消未确认那笔；已确认的保留 CONFIRMED，计数不回退
        assertEquals(1, result.cancelledCount());
        Set<String> cancelledIds = result.cancellations().stream()
                .map(SuppressionCancellationItem::reservationId).collect(Collectors.toSet());
        assertEquals(Set.of(reserved.reservationId()), cancelledIds);
        assertEquals("CONFIRMED", exposureService.getReservation(confirmed.reservationId()).status().name(),
                "已确认曝光必须保留历史");
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal(),
                "已确认计数不得回退");
        assertEquals(1, exposureService.queryQuota("c1", "V", DAY).usedVisitor());

        // 生效中仍不能凭历史确认再申请新预占
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a3", "c1", "V")));
    }

    @Test
    @DisplayName("取消跨公告预占：额度变化按公告/UTC日分别聚合返还")
    void suppress_acrossCampaigns_aggregatesQuotaChanges() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 5, 5));
        exposureService.createCampaign(new CreateCampaignRequest("req-c2", "c2", 5, 5));
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        ReservationResponse r2 = exposureService.apply(new ApplyExposureRequest("req-a2", "c2", "V"));

        SuppressionResponse result = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s1"));

        assertEquals(2, result.cancelledCount());
        Set<String> changedCampaigns = result.quotaChanges().stream()
                .map(SuppressionQuotaChange::campaignId).collect(Collectors.toSet());
        assertEquals(Set.of("c1", "c2"), changedCampaigns);
        assertTrue(result.quotaChanges().stream().allMatch(c -> c.releasedTotal() == 1 && c.releasedVisitor() == 1));
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, exposureService.queryQuota("c2", null, DAY).usedTotal());
        assertEquals(Set.of(r1.reservationId(), r2.reservationId()),
                result.cancellations().stream().map(SuppressionCancellationItem::reservationId)
                        .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("重复加入稳定返回首次结果，不重复取消、不重复释放")
    void repeatedSuppress_returnsStableStateWithoutDoubleRelease() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        SuppressionResponse first = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s1"));
        assertEquals(1, first.cancelledCount());

        // 新 requestId 的重复加入：当前仍生效，稳定返回，取消数量不重复累计
        SuppressionResponse again = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s2"));
        assertTrue(again.active());
        assertEquals(1, again.cancelledCount());
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancellation WHERE visitor_id = 'V'", Integer.class));
    }

    @Test
    @DisplayName("解除只放行后续申请，不恢复已取消预占；重复解除稳定；解除后重新加入生效")
    void release_allowsFutureApplyOnly_andRepeatedReleaseStable() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        suppressionService.suppress("V", new SuppressionActionRequest("req-s1"));

        mutableClock().advanceMillis(5_000L);
        SuppressionResponse released = suppressionService.release(
                "V", new SuppressionActionRequest("req-r1"));
        assertFalse(released.active());
        assertEquals(BASE.toEpochMilli() + 5_000L, released.releasedAtUtc());
        assertEquals(BASE.toEpochMilli(), released.suppressedAtUtc());

        // 已取消预占不恢复
        assertEquals("CANCELLED", exposureService.getReservation(r1.reservationId()).status().name());

        // 只允许后续新申请
        ReservationResponse later = exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));
        assertEquals("RESERVED", later.status().name());

        // 重复解除：当前已非生效，稳定返回同一解除时刻
        SuppressionResponse again = suppressionService.release(
                "V", new SuppressionActionRequest("req-r2"));
        assertFalse(again.active());
        assertEquals(BASE.toEpochMilli() + 5_000L, again.releasedAtUtc());

        // 重新加入：再次生效并取消新预占；解除时刻清空
        SuppressionResponse reSuppressed = suppressionService.suppress(
                "V", new SuppressionActionRequest("req-s2"));
        assertTrue(reSuppressed.active());
        assertNull(reSuppressed.releasedAtUtc());
        assertEquals(1, reSuppressed.cancelledCount());
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a3", "c1", "V")));
    }

    @Test
    @DisplayName("无未确认预占时加入名单：取消数量为0，生效后申请409")
    void suppressWithNoReservations_zeroCancelled() {
        SuppressionResponse result = suppressionService.suppress(
            "ghost", new SuppressionActionRequest("req-s1"));
        assertTrue(result.active());
        assertEquals(0, result.cancelledCount());
        assertEquals(0, result.quotaReleased());
        assertTrue(result.cancellations().isEmpty());
        assertTrue(result.quotaChanges().isEmpty());

        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        assert409(() -> exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "ghost")));
    }

    @Test
    @DisplayName("幂等：同名单变更requestId重放首次取消数量；换访客或动作返回409")
    void idempotency_replayFirstCancelledCount_conflictOnVisitorOrAction() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        exposureService.apply(new ApplyExposureRequest("req-a2", "c1", "V"));

        SuppressionResponse first = suppressionService.suppress(
                "V", new SuppressionActionRequest("same-key"));
        assertEquals(2, first.cancelledCount());

        // 同键同访客同动作重放：首次结果（取消数量仍为2，不再次执行）
        SuppressionResponse replay = suppressionService.suppress(
                "V", new SuppressionActionRequest("same-key"));
        assertEquals(2, replay.cancelledCount());
        assertTrue(replay.active());

        // 同键换访客 → 409
        assert409(() -> suppressionService.suppress(
                "other", new SuppressionActionRequest("same-key")));
        // 同键换动作（SUPPRESS -> RELEASE）→ 409
        assert409(() -> suppressionService.release(
                "V", new SuppressionActionRequest("same-key")));
        // 同键用于曝光申请 → 409（全局幂等表）
        assert409(() -> exposureService.apply(new ApplyExposureRequest("same-key", "c1", "V")));

        // 解除的幂等重放
        suppressionService.release("V", new SuppressionActionRequest("rel-key"));
        SuppressionResponse relReplay = suppressionService.release(
                "V", new SuppressionActionRequest("rel-key"));
        assertFalse(relReplay.active());
        // 解除键用于加入 → 409
        assert409(() -> suppressionService.suppress(
                "V", new SuppressionActionRequest("rel-key")));

        // 取消数量与账目未因重放而变化
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancellation WHERE visitor_id = 'V'", Integer.class));
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("查询：展示抑制起止、取消明细与额度变化；未知访客404")
    void query_showsWindowCancellationsAndChanges_unknown404() {
        exposureService.createCampaign(new CreateCampaignRequest("req-c1", "c1", 10, 10));
        ReservationResponse r1 = exposureService.apply(new ApplyExposureRequest("req-a1", "c1", "V"));
        suppressionService.suppress("V", new SuppressionActionRequest("req-s1"));

        SuppressionResponse view = suppressionService.getSuppression("V");
        assertTrue(view.active());
        assertEquals(BASE.toEpochMilli(), view.suppressedAtUtc());
        assertNull(view.releasedAtUtc());
        assertEquals(1, view.cancelledCount());
        SuppressionCancellationItem item = view.cancellations().get(0);
        assertEquals(r1.reservationId(), item.reservationId());
        assertEquals("c1", item.campaignId());
        assertEquals(DAY, item.utcDate());
        assertEquals(BASE.toEpochMilli(), item.suppressedAtUtc());
        assertEquals(1, view.quotaChanges().get(0).releasedTotal());

        assert404(() -> suppressionService.getSuppression("nobody"));
    }

    @Test
    @DisplayName("HTTP 语义：加入名单200并取消，生效中申请曝光409，查询200")
    void httpSemantics_suppress201_apply409_query200() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/suppression/visitors/u1/suppress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.cancelledCount").value(1))
                .andExpect(jsonPath("$.cancellations[0].campaignId").value("ch"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/suppression/visitors/u1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/suppression/visitors/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.cancelledCount").value(1));
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
