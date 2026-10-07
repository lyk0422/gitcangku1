package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleActionRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组合预占 H2（MODE=MySQL）集成测试：整组原子申请、状态机、幂等与过期回收。
 */
@SpringBootTest
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
        jdbc.update("DELETE FROM exposure_bundle");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private BundleResponse applyBundle(String requestId, String visitorId, String... campaignIds) {
        return service.applyBundle(
                new ApplyBundleRequest(requestId, "tester", visitorId, List.of(campaignIds), 0L));
    }

    private BundleActionRequest actionReq(String requestId, long expectedVersion) {
        return new BundleActionRequest(requestId, "tester", expectedVersion);
    }

    private int countRows(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("组合申请：整组 60 秒截止、共用 UTC 日，每成员各占两级额度")
    void applyBundle_mainFlow() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);

        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");

        assertNotNull(bundle.bundleId());
        assertEquals("v1", bundle.visitorId());
        assertEquals(DAY, bundle.utcDate());
        assertEquals("RESERVED", bundle.status().name());
        assertEquals(0L, bundle.version());
        assertEquals(BASE.toEpochMilli(), bundle.createdAtUtc());
        assertEquals(BASE.toEpochMilli() + 60_000L, bundle.expiresAtUtc());
        assertEquals(2, bundle.members().size());
        for (ReservationResponse member : bundle.members()) {
            assertEquals("RESERVED", member.status().name());
            assertEquals(bundle.bundleId(), member.bundleId());
            assertEquals(bundle.expiresAtUtc(), member.expiresAtUtc());
            assertEquals(DAY, member.utcDate());
        }
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("A余1、B余0：组合失败A仍余1；B释放后同失败键可申请；取消使两边各释放一次")
    void quotaShortfall_atomicFailure_keyReuse_thenCancelReleasesEachSideOnce() {
        createCampaign("req-ca", "A", 1, 10);
        createCampaign("req-cb", "B", 1, 10);
        // B 余 0：另一访客占满 B 当日总额度
        ReservationResponse other = service.apply(new ApplyExposureRequest("req-b0", "B", "other"));

        // 组合失败：429，A 仍余 1，整组不建预占、不占额度
        assert429(() -> applyBundle("req-bd", "v1", "A", "B"));
        QuotaResponse qa = service.queryQuota("A", "v1", DAY);
        assertEquals(0, qa.usedTotal());
        assertEquals(1, qa.remainingTotal());
        assertEquals(1, service.queryQuota("B", null, DAY).usedTotal());
        assertEquals(0, countRows("SELECT COUNT(*) FROM exposure_bundle"));
        assertEquals(0, countRows("SELECT COUNT(*) FROM exposure_reservation WHERE bundle_id IS NOT NULL"));

        // B 释放额度后，同失败键同参可申请（失败不占键）
        service.cancel(other.reservationId(), new ReservationActionRequest("req-xb"));
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");
        assertEquals("RESERVED", bundle.status().name());
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedTotal());

        // 整组取消：两边各释放一次
        BundleResponse cancelled = service.cancelBundle(bundle.bundleId(), actionReq("req-xbd", 0L));
        assertEquals("CANCELLED", cancelled.status().name());
        for (ReservationResponse member : cancelled.members()) {
            assertEquals("CANCELLED", member.status().name());
        }
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedVisitor());
        assertEquals(0, service.queryQuota("B", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("B", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("幂等：集合换序同参回首次完整响应；异参 409；重放不再建组")
    void idempotency_orderInsensitiveReplay_conflictOnDifferentParams() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);

        BundleResponse first = applyBundle("key-1", "v1", "A", "B");
        BundleResponse replay = applyBundle("key-1", "v1", "B", "A");
        assertEquals(first.bundleId(), replay.bundleId());
        assertEquals(first.members().size(), replay.members().size());
        assertEquals(1, countRows("SELECT COUNT(*) FROM exposure_bundle"));
        assertEquals(1, service.queryQuota("A", null, DAY).usedTotal());

        // 同键异参：不同访客 / 不同公告集合 / 不同期望版本 / 不同操作者均 409
        assert409(() -> applyBundle("key-1", "v2", "A", "B"));
        assert409(() -> applyBundle("key-1", "v1", "A", "C"));
        assert409(() -> service.applyBundle(
                new ApplyBundleRequest("key-1", "tester", "v1", List.of("A", "B"), 1L)));
        assert409(() -> service.applyBundle(
                new ApplyBundleRequest("key-1", "other-op", "v1", List.of("A", "B"), 0L)));
        // 同键用于其他操作 → 409
        assert409(() -> service.confirmBundle(first.bundleId(),
                new BundleActionRequest("key-1", "tester", 0L)));
        assertEquals(1, countRows("SELECT COUNT(*) FROM exposure_bundle"));
    }

    @Test
    @DisplayName("整组确认：截止前一次确认全部；重复确认回原状态；取消已确认 409；期望版本不符 409")
    void confirmBundle_stateMachine() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");

        BundleResponse confirmed = service.confirmBundle(bundle.bundleId(), actionReq("req-ok", 0L));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1L, confirmed.version());
        assertEquals(BASE.toEpochMilli(), confirmed.terminalAtUtc());
        for (ReservationResponse member : confirmed.members()) {
            assertEquals("CONFIRMED", member.status().name());
        }
        // 确认后持续占用当天额度
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedTotal());

        // 同键重放与新键重复确认：均返回原状态，不重复变更
        assertEquals("CONFIRMED", service.confirmBundle(bundle.bundleId(), actionReq("req-ok", 0L))
                .status().name());
        assertEquals("CONFIRMED", service.confirmBundle(bundle.bundleId(), actionReq("req-ok2", 0L))
                .status().name());
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());

        // 取消已确认 → 409
        assert409(() -> service.cancelBundle(bundle.bundleId(), actionReq("req-x", 1L)));

        // 期望版本不符 → 409，组保持 RESERVED
        BundleResponse second = applyBundle("req-bd2", "v2", "A", "B");
        assert409(() -> service.confirmBundle(second.bundleId(), actionReq("req-ok3", 5L)));
        assertEquals("RESERVED", service.getBundle(second.bundleId()).status().name());
    }

    @Test
    @DisplayName("整组取消：一次释放全部；重复取消回原状态；确认已取消 409")
    void cancelBundle_stateMachine() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");

        BundleResponse cancelled = service.cancelBundle(bundle.bundleId(), actionReq("req-x", 0L));
        assertEquals("CANCELLED", cancelled.status().name());
        assertEquals(1L, cancelled.version());
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("B", "v1", DAY).usedTotal());

        assertEquals("CANCELLED", service.cancelBundle(bundle.bundleId(), actionReq("req-x", 0L))
                .status().name());
        assertEquals("CANCELLED", service.cancelBundle(bundle.bundleId(), actionReq("req-x2", 0L))
                .status().name());
        // 不重复释放
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());

        assert409(() -> service.confirmBundle(bundle.bundleId(), actionReq("req-ok", 1L)));
    }

    @Test
    @DisplayName("达到截止整组过期并一次释放全部；确认/取消已过期 409；到期前一刻可确认")
    void expiry_wholeGroupExpiresAtDeadline() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");

        mutableClock().advanceMillis(60_000L);
        assert409(() -> service.confirmBundle(bundle.bundleId(), actionReq("req-e1", 0L)));
        assert409(() -> service.cancelBundle(bundle.bundleId(), actionReq("req-e2", 0L)));

        BundleResponse detail = service.getBundle(bundle.bundleId());
        assertEquals("EXPIRED", detail.status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, detail.terminalAtUtc());
        for (ReservationResponse member : detail.members()) {
            assertEquals("EXPIRED", member.status().name());
        }
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("B", "v1", DAY).usedTotal());

        // 到期前 1 毫秒仍可确认
        BundleResponse second = applyBundle("req-bd2", "v2", "A", "B");
        mutableClock().advanceMillis(59_999L);
        assertEquals("CONFIRMED", service.confirmBundle(second.bundleId(), actionReq("req-ok", 0L))
                .status().name());
    }

    @Test
    @DisplayName("组成员用单条确认/取消入口 409；单条查询兼容；普通单条预占仍兼容")
    void bundleMember_rejectedBySingleEndpoints() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");
        String memberId = bundle.members().get(0).reservationId();

        assert409(() -> service.confirm(memberId, new ReservationActionRequest("req-m1")));
        assert409(() -> service.cancel(memberId, new ReservationActionRequest("req-m2")));

        ReservationResponse member = service.getReservation(memberId);
        assertEquals("RESERVED", member.status().name());
        assertEquals(bundle.bundleId(), member.bundleId());

        // 普通单条预占全流程仍兼容
        ReservationResponse single = service.apply(new ApplyExposureRequest("req-s1", "A", "v9"));
        assertEquals("CONFIRMED", service.confirm(single.reservationId(),
                new ReservationActionRequest("req-s2")).status().name());
    }

    @Test
    @DisplayName("组合与普通申请共用统一额度边界：合计不超卖，失败不留半组")
    void unifiedQuotaBoundary_withSingleApply() {
        createCampaign("req-ca", "A", 2, 10);
        createCampaign("req-cb", "B", 10, 10);
        service.apply(new ApplyExposureRequest("req-s1", "A", "v1"));

        BundleResponse bundle = applyBundle("req-bd", "v2", "A", "B");
        assertEquals("RESERVED", bundle.status().name());
        assertEquals(2, service.queryQuota("A", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("B", null, DAY).usedTotal());

        // A 已满：单条与组合均 429，且 B 不被组合失败占用
        assert429(() -> service.apply(new ApplyExposureRequest("req-s2", "A", "v3")));
        assert429(() -> applyBundle("req-bd2", "v4", "A", "B"));
        assertEquals(1, service.queryQuota("B", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("B", "v4", DAY).usedVisitor());
    }

    @Test
    @DisplayName("跨日确认不迁移日账：计数留在申请 UTC 日")
    void confirmAcrossUtcDay_keepsOriginalDayLedger() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse bundle = applyBundle("req-bd", "v1", "A", "B");
        assertEquals(DAY, bundle.utcDate());

        // 推进到次日但未到截止（截止 = 23:59:30 + 60s = 次日 00:00:30）
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        assertEquals("CONFIRMED", service.confirmBundle(bundle.bundleId(), actionReq("req-ok", 0L))
                .status().name());

        LocalDate nextDay = LocalDate.of(2026, 9, 23);
        assertEquals(1, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("B", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("A", "v1", nextDay).usedTotal());
        assertEquals(0, service.queryQuota("B", "v1", nextDay).usedTotal());
    }

    @Test
    @DisplayName("旧成功重放只回原结果：不再建组、不复活已过期成员、不重新占额")
    void replayAfterExpiry_returnsOriginalResponseOnly() {
        createCampaign("req-ca", "A", 10, 5);
        createCampaign("req-cb", "B", 10, 5);
        BundleResponse first = applyBundle("key-r", "v1", "A", "B");

        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getBundle(first.bundleId()).status().name());
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());

        BundleResponse replay = applyBundle("key-r", "v1", "A", "B");
        assertEquals(first.bundleId(), replay.bundleId());
        assertEquals("RESERVED", replay.status().name());
        assertEquals(1, countRows("SELECT COUNT(*) FROM exposure_bundle"));
        assertEquals("EXPIRED", service.getBundle(first.bundleId()).status().name());
        assertEquals(0, service.queryQuota("A", "v1", DAY).usedTotal());
        assertEquals(0, service.queryQuota("B", "v1", DAY).usedTotal());
    }

    @Test
    @DisplayName("HTTP 语义：组合创建 201、参数非法 400、额度不足 429、终态冲突 409、不存在 404")
    void httpSemantics() throws Exception {
        createCampaign("req-c1", "ch1", 1, 10);
        createCampaign("req-c2", "ch2", 10, 10);

        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb1\",\"operator\":\"tester\",\"visitorId\":\"u1\","
                                + "\"campaignIds\":[\"ch1\",\"ch2\"],\"expectedVersion\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.visitorId").value("u1"))
                .andExpect(jsonPath("$.createdAtUtc").value(BASE.toEpochMilli()))
                .andExpect(jsonPath("$.expiresAtUtc").value(BASE.toEpochMilli() + 60_000L))
                .andExpect(jsonPath("$.members.length()").value(2));

        // 数量越界：1 个或 6 个公告 → 400；重复公告 → 400；缺操作者 → 400
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb2\",\"operator\":\"tester\",\"visitorId\":\"u1\","
                                + "\"campaignIds\":[\"ch1\"],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb3\",\"operator\":\"tester\",\"visitorId\":\"u1\","
                                + "\"campaignIds\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"],"
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb4\",\"operator\":\"tester\",\"visitorId\":\"u1\","
                                + "\"campaignIds\":[\"ch1\",\"ch1\"],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb5\",\"visitorId\":\"u1\","
                                + "\"campaignIds\":[\"ch1\",\"ch2\"],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        // ch1 总额度 1 已被首组占满 → 429
        mockMvc.perform(post("/api/exposure/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb6\",\"operator\":\"tester\",\"visitorId\":\"u2\","
                                + "\"campaignIds\":[\"ch1\",\"ch2\"],\"expectedVersion\":0}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // 整组确认后取消 → 409；组明细 200；不存在 404
        createCampaign("req-c3", "ch3", 10, 10);
        BundleResponse bundle = applyBundle("req-bd", "u3", "ch2", "ch3");
        mockMvc.perform(post("/api/exposure/bundles/{id}/confirm", bundle.bundleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb7\",\"operator\":\"tester\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.members[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.members[1].status").value("CONFIRMED"));

        mockMvc.perform(post("/api/exposure/bundles/{id}/cancel", bundle.bundleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hb8\",\"operator\":\"tester\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/exposure/bundles/{id}", bundle.bundleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.members.length()").value(2));

        mockMvc.perform(get("/api/exposure/bundles/nope"))
                .andExpect(status().isNotFound());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }
}
