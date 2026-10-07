package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组合预占 H2（MODE=MySQL）集成测试：整组原子占用/释放、失败不留半组、
 * 幂等重放、整组过期、成员单条入口拒绝。使用独立命名库 exposure_bundle_it。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_bundle_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExposureBundleIntegrationTest {

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
        jdbc.update("DELETE FROM exposure_group");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest("rc-" + campaignId, campaignId, total, perVisitor));
    }

    private QuotaResponse quota(String campaignId, String visitorId) {
        return service.queryQuota(campaignId, visitorId, DAY);
    }

    private int groupCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exposure_group", Integer.class);
    }

    @Test
    @DisplayName("A余1、B余0：组合失败A仍余1；B释放后同失败键可申请；随后取消两边各释放一次")
    void bundleFailureLeavesNoPartialHold_retryWithSameKey_thenCancelReleasesBothOnce() {
        createCampaign("ba", 2, 5);
        createCampaign("bb", 1, 5);
        // 占用 A 一次（余 1）、占满 B（余 0）
        service.apply(new ApplyExposureRequest("ra-1", "ba", "v0"));
        ReservationResponse bRes = service.apply(new ApplyExposureRequest("rb-1", "bb", "v0"));
        assertEquals(1, quota("ba", null).remainingTotal());
        assertEquals(0, quota("bb", null).remainingTotal());

        // 组合申请 [ba,bb]：B 不足 → 429，整组不建预占、不占额度
        assertStatus(429, () -> service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb"))));
        assertEquals(1, quota("ba", null).remainingTotal(), "失败后 A 必须仍余 1，不留一半展示");
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());
        assertEquals(0, groupCount(), "失败不得留下组合组");
        assertEquals(0, quota("ba", "v1").usedVisitor());
        assertEquals(0, quota("bb", "v1").usedVisitor());

        // B 释放额度后，同一失败键同参可成功（失败不占键）
        service.cancel(bRes.reservationId(), new ReservationActionRequest("rx-1"));
        assertEquals(1, quota("bb", null).remainingTotal());
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        assertEquals("RESERVED", bundle.status().name());
        assertEquals(2, bundle.members().size());
        assertNotNull(bundle.groupId());
        assertEquals(BASE.toEpochMilli() + 60_000L, bundle.expiresAtUtc());
        assertEquals(DAY, bundle.utcDate());
        assertEquals(2, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());
        assertEquals(1, quota("ba", "v1").usedVisitor());
        assertEquals(1, quota("bb", "v1").usedVisitor());

        // 整组取消：两边各释放一次
        BundleResponse cancelled = service.cancelBundle(bundle.groupId(), new ReservationActionRequest("bx-1"));
        assertEquals("CANCELLED", cancelled.status().name());
        assertTrue(cancelled.members().stream().allMatch(m -> m.status().name().equals("CANCELLED")));
        assertEquals(1, quota("ba", null).usedTotal(), "A 恰好释放一次回到 1");
        assertEquals(0, quota("bb", null).usedTotal(), "B 恰好释放一次回到 0");
        assertEquals(0, quota("ba", "v1").usedVisitor());
        assertEquals(0, quota("bb", "v1").usedVisitor());
        assertEquals("CANCELLED", service.getBundle(bundle.groupId()).status().name());
    }

    @Test
    @DisplayName("幂等：集合换序同参重放原响应；异参 409；过期后重放只回原结果不复活成员")
    void bundleIdempotency_orderInsensitiveReplay_conflictOnDifferentParams_noRevival() {
        createCampaign("ba", 5, 5);
        createCampaign("bb", 5, 5);
        createCampaign("bc", 5, 5);
        BundleResponse first = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));

        // 换序同参重放：返回首次完整响应，不新建组、不重复占额度
        BundleResponse replay = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("bb", "ba")));
        assertEquals(first.groupId(), replay.groupId());
        assertEquals(first.members().stream().map(ReservationResponse::reservationId).sorted().toList(),
                replay.members().stream().map(ReservationResponse::reservationId).sorted().toList());
        assertEquals(1, groupCount());
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());

        // 同键异参（不同集合、不同访客）→ 409
        assertStatus(409, () -> service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bc"))));
        assertStatus(409, () -> service.applyBundle(new ApplyBundleRequest("bk-1", "v2", List.of("ba", "bb"))));

        // 整组过期后重放旧成功键：只回原结果，不建组、不复活已过期成员
        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getBundle(first.groupId()).status().name());
        BundleResponse staleReplay = service.applyBundle(
                new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        assertEquals(first.groupId(), staleReplay.groupId());
        assertEquals(1, groupCount());
        assertEquals("EXPIRED", service.getBundle(first.groupId()).status().name());
        assertEquals(0, quota("ba", null).usedTotal());
        assertEquals(0, quota("bb", null).usedTotal());
    }

    @Test
    @DisplayName("整组确认一次全部生效；确认后取消 409；取消后确认 409；重复同向返回原状态")
    void bundleConfirmAndCancel_terminalGuards() {
        createCampaign("ba", 5, 5);
        createCampaign("bb", 5, 5);
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));

        BundleResponse confirmed = service.confirmBundle(bundle.groupId(), new ReservationActionRequest("bk-ok"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(BASE.toEpochMilli(), confirmed.terminalAtUtc());
        assertTrue(confirmed.members().stream().allMatch(m -> m.status().name().equals("CONFIRMED")));
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());

        // 同键重放与新键同向重复：返回原状态
        assertEquals("CONFIRMED", service.confirmBundle(bundle.groupId(),
                new ReservationActionRequest("bk-ok")).status().name());
        assertEquals("CONFIRMED", service.confirmBundle(bundle.groupId(),
                new ReservationActionRequest("bk-ok2")).status().name());
        // 确认已确认组的取消 → 409
        assertStatus(409, () -> service.cancelBundle(bundle.groupId(), new ReservationActionRequest("bk-x")));

        BundleResponse second = service.applyBundle(new ApplyBundleRequest("bk-2", "v2", List.of("ba", "bb")));
        service.cancelBundle(second.groupId(), new ReservationActionRequest("bx-2"));
        assertStatus(409, () -> service.confirmBundle(second.groupId(), new ReservationActionRequest("bk-e")));
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());
    }

    @Test
    @DisplayName("达到截止整组过期并一次释放全部；确认/取消已过期组 409")
    void bundleExpiry_releasesAllMembersAtOnce() {
        createCampaign("ba", 5, 5);
        createCampaign("bb", 5, 5);
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());

        mutableClock().advanceMillis(60_000L);
        assertStatus(409, () -> service.confirmBundle(bundle.groupId(), new ReservationActionRequest("bk-e1")));
        assertStatus(409, () -> service.cancelBundle(bundle.groupId(), new ReservationActionRequest("bk-e2")));

        BundleResponse expired = service.getBundle(bundle.groupId());
        assertEquals("EXPIRED", expired.status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, expired.terminalAtUtc());
        assertTrue(expired.members().stream().allMatch(m -> m.status().name().equals("EXPIRED")));
        assertEquals(0, quota("ba", null).usedTotal());
        assertEquals(0, quota("bb", null).usedTotal());
        assertEquals(0, quota("ba", "v1").usedVisitor());
        assertEquals(0, quota("bb", "v1").usedVisitor());
    }

    @Test
    @DisplayName("组内成员走单条确认/取消入口 409；单条查询可见成员及其组编号")
    void memberSingleActionEndpointsRejected() {
        createCampaign("ba", 5, 5);
        createCampaign("bb", 5, 5);
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        String memberId = bundle.members().get(0).reservationId();

        assertStatus(409, () -> service.confirm(memberId, new ReservationActionRequest("rm-c")));
        assertStatus(409, () -> service.cancel(memberId, new ReservationActionRequest("rm-x")));

        ReservationResponse member = service.getReservation(memberId);
        assertEquals("RESERVED", member.status().name());
        assertEquals(bundle.groupId(), member.groupId());
        assertEquals("RESERVED", service.getBundle(bundle.groupId()).status().name());
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());
    }

    @Test
    @DisplayName("组合申请先结算成员相关到期项：过期单释放后整组可成功")
    void bundleApply_settlesExpiredReservationsFirst() {
        createCampaign("ba", 1, 5);
        createCampaign("bb", 1, 5);
        service.apply(new ApplyExposureRequest("ra-1", "ba", "v0"));
        assertEquals(0, quota("ba", null).remainingTotal());

        mutableClock().advanceMillis(60_000L);
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        assertEquals("RESERVED", bundle.status().name());
        assertEquals(1, quota("ba", null).usedTotal());
        assertEquals(1, quota("bb", null).usedTotal());
    }

    @Test
    @DisplayName("跨日确认不迁移日账：成员额度仍计在申请 UTC 日")
    void bundleConfirmAcrossUtcDay_keepsOriginalDayLedger() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        createCampaign("ba", 5, 5);
        createCampaign("bb", 5, 5);
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-1", "v1", List.of("ba", "bb")));
        assertEquals(DAY, bundle.utcDate());

        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        assertEquals("CONFIRMED", service.confirmBundle(bundle.groupId(),
                new ReservationActionRequest("bk-ok")).status().name());

        assertEquals(1, quota("ba", null).usedTotal());
        LocalDate nextDay = LocalDate.of(2026, 9, 23);
        assertEquals(0, service.queryQuota("ba", null, nextDay).usedTotal());
        assertEquals(0, service.queryQuota("bb", "v1", nextDay).usedVisitor());
    }

    @Test
    @DisplayName("HTTP 语义：组合创建 201、查询 200、集合越界/重复 400、未知公告 404、额度不足 429")
    void bundleHttpSemantics() throws Exception {
        createCampaign("ba", 1, 5);
        createCampaign("bb", 5, 5);

        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-1\",\"visitorId\":\"v1\","
                                + "\"campaignIds\":[\"ba\",\"bb\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.groupId").isNotEmpty());

        // 额度不足（ba 总额度 1 已占）→ 429
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-2\",\"visitorId\":\"v2\","
                                + "\"campaignIds\":[\"ba\",\"bb\"]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // 集合过小、过大、元素重复 → 400
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-3\",\"visitorId\":\"v1\",\"campaignIds\":[\"ba\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-4\",\"visitorId\":\"v1\","
                                + "\"campaignIds\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-5\",\"visitorId\":\"v1\","
                                + "\"campaignIds\":[\"ba\",\"ba\"]}"))
                .andExpect(status().isBadRequest());

        // 未知公告 → 404
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bh-6\",\"visitorId\":\"v1\","
                                + "\"campaignIds\":[\"nope\",\"bb\"]}"))
                .andExpect(status().isNotFound());

        // 查询组明细 200
        String groupId = jdbc.queryForObject("SELECT group_id FROM exposure_group", String.class);
        mockMvc.perform(get("/api/exposure/bundles/" + groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    private void assertStatus(int expected, Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value());
    }
}
