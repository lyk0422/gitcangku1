package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyRotationRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创意轮换 H2（MODE=MySQL）集成测试：轮换选创意、序号推进、互斥、幂等与跨日确认。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExposureRotationTest {

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
        jdbc.update("DELETE FROM rotation_state");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_creative");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createRotationCampaign(String requestId, String campaignId,
                                        int total, int perVisitor, String... creativeIds) {
        service.createCampaign(new CreateCampaignRequest(
                requestId, campaignId, total, perVisitor, List.of(creativeIds)));
    }

    private int confirmedCount(String campaignId, String visitorId) {
        Integer value = jdbc.query("SELECT confirmed_count FROM rotation_state "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, visitorId);
        return value == null ? 0 : value;
    }

    private int reservationCount(String campaignId, String visitorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = ? AND visitor_id = ?",
                Integer.class, campaignId, visitorId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("轮换链 [A,B]：首次A取消后仍A；确认A后得B；B到期后仍B")
    void rotationChain_cancelKeepsCreative_confirmAdvances_expiryKeepsCreative() {
        createRotationCampaign("req-c1", "rot1", 10, 10, "A", "B");

        // 首次轮换申请：序号 0，选 A
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("req-r1", "rot1", "v1"));
        assertEquals("A", r1.creativeId());
        assertEquals(0, r1.rotationSeq());
        assertEquals(BASE.toEpochMilli() + 60_000L, r1.expiresAtUtc());

        // 取消不推进序号：新键再申请仍得 A、序号 0
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = service.applyRotation(new ApplyRotationRequest("req-r2", "rot1", "v1"));
        assertEquals("A", r2.creativeId());
        assertEquals(0, r2.rotationSeq());
        assertEquals(0, confirmedCount("rot1", "v1"));

        // 确认 A 推进序号：下一次得 B、序号 1
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(1, confirmedCount("rot1", "v1"));
        ReservationResponse r3 = service.applyRotation(new ApplyRotationRequest("req-r3", "rot1", "v1"));
        assertEquals("B", r3.creativeId());
        assertEquals(1, r3.rotationSeq());

        // B 到期不推进序号：下次仍得 B、序号 1
        mutableClock().advanceMillis(60_000L);
        ReservationResponse r4 = service.applyRotation(new ApplyRotationRequest("req-r4", "rot1", "v1"));
        assertEquals("B", r4.creativeId());
        assertEquals(1, r4.rotationSeq());
        assertEquals(1, confirmedCount("rot1", "v1"));
        assertEquals("EXPIRED", service.getReservation(r3.reservationId()).status().name());
    }

    @Test
    @DisplayName("活跃轮换互斥：同访客轮换/普通新申请均 409，其他访客不受影响")
    void activeRotation_blocksRotationAndNormalApplyWith409() {
        createRotationCampaign("req-c1", "rot2", 10, 10, "A", "B");
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("req-r1", "rot2", "v1"));
        assertEquals("A", r1.creativeId());

        // 另一个活跃轮换预占 409
        assertStatus(409, () -> service.applyRotation(new ApplyRotationRequest("req-r2", "rot2", "v1")));
        // 普通申请遵守活跃轮换互斥 409
        assertStatus(409, () -> service.apply(new ApplyExposureRequest("req-n1", "rot2", "v1")));

        // 互斥失败不留占位、不推进序号
        assertEquals(1, reservationCount("rot2", "v1"));
        assertEquals(0, confirmedCount("rot2", "v1"));

        // 其他访客正常轮换
        ReservationResponse other = service.applyRotation(new ApplyRotationRequest("req-r3", "rot2", "v2"));
        assertEquals("A", other.creativeId());
        assertEquals(0, other.rotationSeq());
    }

    @Test
    @DisplayName("轮换开始后该访客普通新申请 422；普通申请不参与轮换计数")
    void rotationStarted_normalApply422_normalApplyDoesNotCount() {
        createRotationCampaign("req-c1", "rot3", 10, 10, "A", "B");

        // v1 轮换确认后（无活跃预占）：普通新申请 422
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("req-r1", "rot3", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        assertStatus(422, () -> service.apply(new ApplyExposureRequest("req-n1", "rot3", "v1")));

        // v2 普通申请并确认：不参与轮换计数，之后轮换仍从序号 0 的 A 开始
        ReservationResponse normal = service.apply(new ApplyExposureRequest("req-n2", "rot3", "v2"));
        assertNull(normal.creativeId());
        assertNull(normal.rotationSeq());
        service.confirm(normal.reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(0, confirmedCount("rot3", "v2"));

        ReservationResponse rot = service.applyRotation(new ApplyRotationRequest("req-r2", "rot3", "v2"));
        assertEquals("A", rot.creativeId());
        assertEquals(0, rot.rotationSeq());
    }

    @Test
    @DisplayName("轮换申请额度不足 429：不留占位、不推进序号、失败不占幂等键")
    void rotationApply_quotaExhausted429_noPlaceholderNoSequenceNoKey() {
        createRotationCampaign("req-c1", "rot4", 1, 10, "A", "B");
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("req-r1", "rot4", "v1"));
        assertEquals("A", r1.creativeId());

        // 总额度已满：429，且不留任何占位
        assertStatus(429, () -> service.applyRotation(new ApplyRotationRequest("req-r2", "rot4", "v2")));
        assertEquals(0, reservationCount("rot4", "v2"));
        assertEquals(0, confirmedCount("rot4", "v2"));
        assertEquals(1, service.queryQuota("rot4", null, DAY).usedTotal());

        // 失败不占键：释放额度后同键同参可成功
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = service.applyRotation(new ApplyRotationRequest("req-r2", "rot4", "v2"));
        assertEquals("A", r2.creativeId());
        assertEquals(0, r2.rotationSeq());
    }

    @Test
    @DisplayName("跨日确认：保留原申请日额度并推进同一连续序号，次日申请选下一创意")
    void confirmAcrossUtcDay_keepsOriginalDayQuotaAndAdvancesSameSequence() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        createRotationCampaign("req-c1", "rot5", 10, 10, "A", "B");
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("req-r1", "rot5", "v1"));
        assertEquals("A", r1.creativeId());
        assertEquals(LocalDate.of(2026, 9, 22), r1.utcDate());

        // 推进到次日但未到期（到期 = 23:59:30 + 60s = 次日 00:00:30）
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        ReservationResponse confirmed = service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, confirmedCount("rot5", "v1"));

        // 原申请日额度保留，次日申请按推进后的序号选 B 并计在新日
        assertEquals(1, service.queryQuota("rot5", null, LocalDate.of(2026, 9, 22)).usedTotal());
        ReservationResponse r2 = service.applyRotation(new ApplyRotationRequest("req-r2", "rot5", "v1"));
        assertEquals("B", r2.creativeId());
        assertEquals(1, r2.rotationSeq());
        assertEquals(LocalDate.of(2026, 9, 23), r2.utcDate());
        assertEquals(1, service.queryQuota("rot5", null, LocalDate.of(2026, 9, 23)).usedTotal());
    }

    @Test
    @DisplayName("幂等：同键重放首次预占（取消后不复活占位）；异参 409；历史冻结创意不回写")
    void idempotency_replayDoesNotRevive_conflictOnDifferentParams_frozenCreativeNotRewritten() {
        createRotationCampaign("req-c1", "rot6", 10, 10, "A", "B");
        ReservationResponse r1 = service.applyRotation(new ApplyRotationRequest("key-1", "rot6", "v1"));
        assertEquals("A", r1.creativeId());

        // 同键同参重放：返回首次完整响应
        ReservationResponse replay = service.applyRotation(new ApplyRotationRequest("key-1", "rot6", "v1"));
        assertEquals(r1.reservationId(), replay.reservationId());
        assertEquals(1, reservationCount("rot6", "v1"));

        // 同键异参 409
        assertStatus(409, () -> service.applyRotation(new ApplyRotationRequest("key-1", "rot6", "v2")));

        // 取消后旧申请键只重放首次预占，不复活占位
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse replayAfterCancel =
                service.applyRotation(new ApplyRotationRequest("key-1", "rot6", "v1"));
        assertEquals(r1.reservationId(), replayAfterCancel.reservationId());
        assertEquals(1, reservationCount("rot6", "v1"));
        assertEquals("CANCELLED", service.getReservation(r1.reservationId()).status().name());

        // 占位未复活：新键可再申请，仍得 A（取消不推进序号）
        ReservationResponse r2 = service.applyRotation(new ApplyRotationRequest("key-2", "rot6", "v1"));
        assertEquals("A", r2.creativeId());
        assertEquals(0, r2.rotationSeq());

        // 确认推进后，查询旧记录仍冻结原创意与序号，不回写当前下一创意
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse detail = service.getReservation(r1.reservationId());
        assertEquals("A", detail.creativeId());
        assertEquals(0, detail.rotationSeq());
        ReservationResponse current = service.getReservation(r2.reservationId());
        assertEquals("A", current.creativeId());
        assertEquals(0, current.rotationSeq());
    }

    @Test
    @DisplayName("创意配置校验：2~5 个且互不相同，否则 400；未配置创意的公告轮换申请 422")
    void creativeValidation_andRotationOnCampaignWithoutCreatives() {
        assertStatus(400, () -> service.createCampaign(
                new CreateCampaignRequest("req-b1", "bad1", 10, 10, List.of("A"))));
        assertStatus(400, () -> service.createCampaign(
                new CreateCampaignRequest("req-b2", "bad2", 10, 10,
                        List.of("A", "B", "C", "D", "E", "F"))));
        assertStatus(400, () -> service.createCampaign(
                new CreateCampaignRequest("req-b3", "bad3", 10, 10, List.of("A", "A"))));

        // 校验失败不占键：修正参数后同键可成功
        service.createCampaign(new CreateCampaignRequest("req-b1", "bad1", 10, 10, List.of("A", "B")));
        assertEquals(List.of("A", "B"), jdbc.queryForList(
                "SELECT creative_id FROM campaign_creative WHERE campaign_id = 'bad1' "
                        + "ORDER BY position ASC", String.class));

        // 未配置轮换创意的公告：轮换申请 422，普通申请不受影响
        service.createCampaign(new CreateCampaignRequest("req-p1", "plain1", 10, 10));
        assertStatus(422, () -> service.applyRotation(new ApplyRotationRequest("req-r1", "plain1", "v1")));
        ReservationResponse normal = service.apply(new ApplyExposureRequest("req-n1", "plain1", "v1"));
        assertEquals("RESERVED", normal.status().name());
    }

    @Test
    @DisplayName("HTTP 语义：轮换申请 201 返回冻结创意与序号，活跃冲突 409")
    void httpSemantics_rotationApply201_conflict409() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"roth\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5,\"creativeIds\":[\"A\",\"B\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creativeIds[0]").value("A"))
                .andExpect(jsonPath("$.creativeIds[1]").value("B"));

        mockMvc.perform(post("/api/exposure/rotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"roth\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creativeId").value("A"))
                .andExpect(jsonPath("$.rotationSeq").value(0))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/exposure/rotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"campaignId\":\"roth\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    private void assertStatus(int expected, Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value());
    }
}
