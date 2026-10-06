package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 轮换创意集成测试（H2，MODE=MySQL）：轮换选创意、占位互斥、序号推进、
 * 跨日确认、普通申请互斥、幂等重放与并发占位。
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
        jdbc.update("DELETE FROM rotation_active");
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

    private void createRotationCampaign(String requestId, String campaignId, int total, int perVisitor,
                                        String... creatives) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor,
                List.of(creatives)));
    }

    private ReservationResponse applyRotation(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId, true));
    }

    @Test
    @DisplayName("轮换主流程：按已确认数取模选创意并冻结，确认推进序号，环绕回第一个创意")
    void rotationCycle_rotatesByConfirmedCountAndWraps() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        CampaignResponse campaign = service.createCampaign(new CreateCampaignRequest(
                "req-c2", "rot2", 10, 10, List.of("A", "B", "C")));
        assertEquals(List.of("A", "B", "C"), campaign.creativeIds());

        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        assertEquals("ROTATION", r1.applyType().name());
        assertEquals("A", r1.creativeId());
        assertEquals(0, r1.rotationSeq());
        assertEquals(0, r1.version());

        ReservationResponse confirmed1 = service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed1.status().name());
        assertEquals(1, confirmed1.version());

        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertEquals("B", r2.creativeId());
        assertEquals(1, r2.rotationSeq());
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));

        // 已确认 2 次，对 2 个创意取模回到 A
        ReservationResponse r3 = applyRotation("req-a3", "rot", "v1");
        assertEquals("A", r3.creativeId());
        assertEquals(2, r3.rotationSeq());
    }

    @Test
    @DisplayName("取消不推进序号：新键再申请仍得同一创意；确认后才轮到下一个")
    void cancelKeepsSameCreativeAndSeq() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");

        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        assertEquals("A", r1.creativeId());
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));

        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertEquals("A", r2.creativeId());
        assertEquals(0, r2.rotationSeq());
        assertEquals(1, service.queryQuota("rot", "v1", DAY).usedVisitor());

        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse r3 = applyRotation("req-a3", "rot", "v1");
        assertEquals("B", r3.creativeId());
        assertEquals(1, r3.rotationSeq());
    }

    @Test
    @DisplayName("到期不推进序号：B 到期后下次仍得 B，过期单状态可查且额度已释放")
    void expiryKeepsSameCreativeAndSeq() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertEquals("B", r2.creativeId());

        mutableClock().advanceMillis(60_000L);
        ReservationResponse r3 = applyRotation("req-a3", "rot", "v1");
        assertEquals("B", r3.creativeId());
        assertEquals(1, r3.rotationSeq());

        ReservationResponse expired = service.getReservation(r2.reservationId());
        assertEquals("EXPIRED", expired.status().name());
        assertEquals("B", expired.creativeId());

        // 占用 = 已确认的 A + 新预占的 B
        QuotaResponse quota = service.queryQuota("rot", "v1", DAY);
        assertEquals(2, quota.usedTotal());
        assertEquals(2, quota.usedVisitor());
    }
    @Test
    @DisplayName("活跃轮换互斥：已有 RESERVED 轮换再申请 409；确认或取消后可再申请")
    void activeRotation_conflict409() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");

        assertStatus(409, () -> applyRotation("req-a2", "rot", "v1"));

        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertEquals("A", r2.creativeId());

        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse r3 = applyRotation("req-a3", "rot", "v1");
        assertEquals("B", r3.creativeId());
    }

    @Test
    @DisplayName("轮换开始后普通新申请 422；其他访客不受影响")
    void normalApplyAfterRotationStarted_422() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");

        // 活跃轮换预占期间，普通申请 422
        assertStatus(422, () -> service.apply(new ApplyExposureRequest("req-n1", "rot", "v1")));

        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        // 轮换流程已开始（含已取消记录），普通新申请仍 422
        assertStatus(422, () -> service.apply(new ApplyExposureRequest("req-n2", "rot", "v1")));

        ReservationResponse other = service.apply(new ApplyExposureRequest("req-n3", "rot", "v2"));
        assertEquals("NORMAL", other.applyType().name());
    }

    @Test
    @DisplayName("普通预占不参与轮换计数：先普通申请再轮换仍从序号 0 开始")
    void normalApplyBeforeRotation_doesNotCount() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse normal = service.apply(new ApplyExposureRequest("req-n1", "rot", "v1"));
        assertEquals("NORMAL", normal.applyType().name());
        assertNull(normal.creativeId());
        assertNull(normal.rotationSeq());

        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        assertEquals("A", r1.creativeId());
        assertEquals(0, r1.rotationSeq());
    }

    @Test
    @DisplayName("未配置轮换创意的公告：轮换申请 422，普通申请正常")
    void rotationOnPlainCampaign_422() {
        service.createCampaign(new CreateCampaignRequest("req-c", "plain", 10, 10));
        assertStatus(422, () -> applyRotation("req-a1", "plain", "v1"));
        ReservationResponse normal = service.apply(new ApplyExposureRequest("req-n1", "plain", "v1"));
        assertEquals("NORMAL", normal.applyType().name());
    }

    @Test
    @DisplayName("轮换申请额度不足 429 且不留占位：释放后可再申请，普通申请不被误判 422")
    void rotationQuotaExhausted_429_noPlaceholder() {
        createRotationCampaign("req-c", "rot", 1, 1, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");

        assertStatus(429, () -> applyRotation("req-a2", "rot", "v2"));
        // 429 不留轮换记录：v2 普通申请命中的是额度 429 而非轮换 422
        assertStatus(429, () -> service.apply(new ApplyExposureRequest("req-n1", "rot", "v2")));

        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = applyRotation("req-a3", "rot", "v2");
        assertEquals("A", r2.creativeId());
        assertEquals(0, r2.rotationSeq());
    }

    @Test
    @DisplayName("跨日确认：保留原申请日额度并推进同一序号，次日申请得下一创意")
    void crossDayConfirm_keepsOriginalDayQuotaAndAdvancesSequence() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        assertEquals("A", r1.creativeId());
        assertEquals(LocalDate.of(2026, 9, 22), r1.utcDate());

        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        ReservationResponse confirmed = service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());

        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertEquals("B", r2.creativeId());
        assertEquals(1, r2.rotationSeq());
        assertEquals(LocalDate.of(2026, 9, 23), r2.utcDate());

        assertEquals(1, service.queryQuota("rot", null, LocalDate.of(2026, 9, 22)).usedTotal());
        assertEquals(1, service.queryQuota("rot", null, LocalDate.of(2026, 9, 23)).usedTotal());
    }
    @Test
    @DisplayName("取消后旧申请键只重放首次预占响应，不复活占位；新键才产生新预占")
    void replayOldApplyKey_returnsFirstResponseWithoutReviving() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));

        ReservationResponse replay = applyRotation("req-a1", "rot", "v1");
        assertEquals(r1.reservationId(), replay.reservationId());
        assertEquals("RESERVED", replay.status().name());
        assertEquals("A", replay.creativeId());
        assertEquals(1, countRows("exposure_reservation"));
        assertEquals(0, countRows("rotation_active"));

        ReservationResponse r2 = applyRotation("req-a2", "rot", "v1");
        assertNotEquals(r1.reservationId(), r2.reservationId());
        assertEquals("A", r2.creativeId());
        assertEquals(2, countRows("exposure_reservation"));
        assertEquals(1, countRows("rotation_active"));
    }

    @Test
    @DisplayName("历史预占冻结创意：查询旧单不回写当前应轮换到的创意")
    void getReservation_returnsFrozenCreative() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        applyRotation("req-a2", "rot", "v1");

        ReservationResponse detail = service.getReservation(r1.reservationId());
        assertEquals("A", detail.creativeId());
        assertEquals(0, detail.rotationSeq());
        assertEquals("CONFIRMED", detail.status().name());
    }

    @Test
    @DisplayName("期望版本：不一致 409，一致才执行；终态后版本已递增")
    void expectedVersion_mismatch409_matchSucceeds() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-a1", "rot", "v1");

        assertStatus(409, () -> service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-k1", 3L)));
        ReservationResponse confirmed = service.confirm(r1.reservationId(),
                new ReservationActionRequest("req-k2", 0L));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, confirmed.version());

        // 版本已推进到 1，旧期望版本 0 的取消 409
        assertStatus(409, () -> service.cancel(r1.reservationId(),
                new ReservationActionRequest("req-x1", 0L)));
    }

    @Test
    @DisplayName("幂等身份含全部参数：同键改轮换标记 409，同参重放返回原单")
    void idempotency_sameKeyDifferentParams_409() {
        createRotationCampaign("req-c", "rot", 10, 10, "A", "B");
        ReservationResponse r1 = applyRotation("req-k", "rot", "v1");

        assertStatus(409, () -> service.apply(new ApplyExposureRequest("req-k", "rot", "v1", false)));
        assertStatus(409, () -> service.apply(new ApplyExposureRequest("req-k", "rot", "v1")));

        ReservationResponse replay = applyRotation("req-k", "rot", "v1");
        assertEquals(r1.reservationId(), replay.reservationId());

        // 同键异参（创意列表不同）创建公告 409
        assertStatus(409, () -> service.createCampaign(new CreateCampaignRequest(
                "req-c", "rot", 10, 10, List.of("A", "B", "C"))));
    }

    @Test
    @DisplayName("轮换创意个数校验：1 个或 6 个均 400")
    void creativeIdsValidation_400() {
        assertStatus(400, () -> service.createCampaign(new CreateCampaignRequest(
                "req-c1", "bad1", 10, 10, List.of("A"))));
        assertStatus(400, () -> service.createCampaign(new CreateCampaignRequest(
                "req-c2", "bad2", 10, 10, List.of("A", "B", "C", "D", "E", "F"))));
        assertStatus(400, () -> service.createCampaign(new CreateCampaignRequest(
                "req-c3", "bad3", 10, 10, List.of("A", " "))));
    }

    @Test
    @DisplayName("并发轮换申请：同访客同公告仅一条成功，其余 409，占位唯一")
    void concurrentRotationApply_singleActivePlaceholder() throws Exception {
        createRotationCampaign("req-c", "rot", 100, 100, "A", "B");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    applyRotation("req-a-" + idx, "rot", "v1");
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, success.get(), "同访客同公告至多一条轮换 RESERVED");
        assertEquals(threads - 1, conflicts.get(), "其余并发轮换申请必须为 409");
        assertEquals(1, countRows("rotation_active"));
        assertEquals(1, countRows("exposure_reservation"));
    }

    @Test
    @DisplayName("HTTP 轮换链路：申请冻结创意、版本校验 409、确认后下一创意")
    void httpRotationFlow() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"hrot\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10,\"creativeIds\":[\"A\",\"B\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creativeIds[0]").value("A"))
                .andExpect(jsonPath("$.creativeIds[1]").value("B"));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"hrot\",\"visitorId\":\"u1\","
                                + "\"rotation\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applyType").value("ROTATION"))
                .andExpect(jsonPath("$.creativeId").value("A"))
                .andExpect(jsonPath("$.rotationSeq").value(0))
                .andExpect(jsonPath("$.version").value(0));

        String reservationId = jdbc.queryForObject(
                "SELECT reservation_id FROM exposure_reservation WHERE campaign_id = 'hrot'",
                String.class);

        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"expectedVersion\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"hrot\",\"visitorId\":\"u1\","
                                + "\"rotation\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creativeId").value("B"))
                .andExpect(jsonPath("$.rotationSeq").value(1));

        // 轮换已开始：普通申请 422
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"hrot\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private void assertStatus(int expected, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value());
    }
}
