package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignAttributionResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateChannelRequest;
import com.example.starter.exposure.web.MigrateCampaignChannelRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateChannelCapRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道总量频控 H2（MODE=MySQL）业务测试：渠道配额、原子预占与结算、跨公告隔离、
 * 公告迁移固化、渠道配置版本裁决、日用量/明细/归属统计与幂等语义。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChannelCapIntegrationTest {

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
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM channel_daily_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM channel_config");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private ChannelResponse createChannel(String reqId, String key, int cap) {
        return service.createChannel(new CreateChannelRequest(reqId, key, cap));
    }

    private CampaignResponse createCampaign(String reqId, String id, int total, int perVisitor,
                                            String channelKey) {
        return service.createCampaign(
                new CreateCampaignRequest(reqId, id, total, perVisitor, channelKey));
    }

    private ReservationResponse apply(String reqId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(reqId, campaignId, visitorId));
    }

    @Test
    @DisplayName("渠道日额度占用与用量查询：申请同时占用渠道名额并固化渠道")
    void channelQuota_occupiedAndQueried() {
        createChannel("req-ch", "ch-a", 2);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");

        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        assertEquals("ch-a", r1.channelKey());
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        assertEquals("ch-a", r2.channelKey());

        ChannelUsageResponse usage = service.queryChannelUsage("ch-a", DAY);
        assertEquals(2, usage.dailyTotalCap());
        assertEquals(2, usage.used());
        assertEquals(0, usage.remaining());
        assertEquals(BASE.toEpochMilli(), usage.settledAtUtc());
    }

    @Test
    @DisplayName("渠道额度不足返回 429：公告与访客额度均不消耗，失败不创建预占")
    void channelQuotaExhausted_429AndNoSideConsumed() {
        createChannel("req-ch", "ch-a", 2);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        apply("req-a2", "c1", "v2");

        assert429(() -> apply("req-a3", "c1", "v3"));

        // 渠道账仍为 2，公告总账仍为 2，v3 访客账为 0，预占明细只有 2 条
        assertEquals(2, service.queryChannelUsage("ch-a", DAY).used());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, service.queryQuota("c1", "v3", DAY).usedVisitor());
        assertEquals(2, service.listReservations("c1", null, DAY).size());

        // 失败不占键：释放一个名额后同键同参可成功
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = apply("req-a3", "c1", "v3");
        assertEquals("RESERVED", retried.status().name());
        assertEquals(2, service.queryChannelUsage("ch-a", DAY).used());
    }

    @Test
    @DisplayName("未配置渠道或公告未归属渠道：不受渠道频控，预占渠道固化为 null")
    void noChannelConfig_ruleNotApplied() {
        // 公告未归属渠道
        createCampaign("req-c1", "c1", 100, 100, null);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        assertNull(r1.channelKey());

        // 公告归属渠道但渠道未配置：同样不受限，固化为 null
        createCampaign("req-c2", "c2", 100, 100, "ch-later");
        ReservationResponse r2 = apply("req-a2", "c2", "v2");
        assertNull(r2.channelKey());

        // 之后渠道才配置：仅影响之后的新申请，既有预占仍无渠道
        createChannel("req-ch", "ch-later", 1);
        ReservationResponse r3 = apply("req-a3", "c2", "v3");
        assertEquals("ch-later", r3.channelKey());
        assertNull(service.getReservation(r2.reservationId()).channelKey());

        Integer channelRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_daily_ledger WHERE channel_key = 'ch-later'",
                Integer.class);
        assertEquals(1, channelRows, "渠道配置后只应有新申请的一条渠道账");
    }

    @Test
    @DisplayName("确认保持渠道名额；取消/过期同时释放渠道与公告两侧名额，重复操作不重复释放")
    void confirmCancelExpire_settleBothSidesOnce() {
        createChannel("req-ch", "ch-a", 3);
        createCampaign("req-c1", "c1", 3, 3, "ch-a");

        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        assertEquals(3, service.queryChannelUsage("ch-a", DAY).used());

        // 确认：渠道名额保持占用；重复确认（含新 requestId）不重复扣减
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(3, service.queryChannelUsage("ch-a", DAY).used());
        assertEquals(3, service.queryQuota("c1", null, DAY).usedTotal());

        // 取消：渠道与公告名额同时释放；重复取消不重复释放
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x2"));
        assertEquals(2, service.queryChannelUsage("ch-a", DAY).used());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());

        // 过期：推进到到期时刻，任意查询触发结算，两侧名额释放且只释放一次
        mutableClock().advanceMillis(60_000L);
        assertEquals(1, service.queryChannelUsage("ch-a", DAY).used(), "r3 过期后仅剩已确认的 r1");
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        // 再次查询：结算幂等，名额不继续下降
        assertEquals(1, service.queryChannelUsage("ch-a", DAY).used());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("公告迁移渠道：既有预占固化原渠道，新申请走新渠道，取消旧预占只释放原渠道")
    void migrateCampaign_existingReservationsStayFrozen() {
        createChannel("req-ch-old", "ch-old", 5);
        createChannel("req-ch-new", "ch-new", 5);
        createCampaign("req-c1", "c1", 100, 100, "ch-old");

        ReservationResponse before = apply("req-a1", "c1", "v1");
        assertEquals("ch-old", before.channelKey());

        CampaignResponse migrated = service.migrateCampaignChannel("c1",
                new MigrateCampaignChannelRequest("req-m1", "ch-new"));
        assertEquals("ch-new", migrated.channelKey());

        // 既有预占仍固化原渠道
        assertEquals("ch-old", service.getReservation(before.reservationId()).channelKey());

        // 迁移后的新申请固化新渠道
        ReservationResponse after = apply("req-a2", "c1", "v2");
        assertEquals("ch-new", after.channelKey());
        assertEquals(1, service.queryChannelUsage("ch-old", DAY).used());
        assertEquals(1, service.queryChannelUsage("ch-new", DAY).used());

        // 取消旧预占：仅释放原渠道，新渠道名额不动
        service.cancel(before.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(0, service.queryChannelUsage("ch-old", DAY).used());
        assertEquals(1, service.queryChannelUsage("ch-new", DAY).used());

        // 公告总账两条预占一取消一在占
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("迁移后渠道配置变更不影响既有预占：旧渠道调额不改变旧预占结算渠道")
    void configChangeAfterMigrate_doesNotMoveFrozenReservations() {
        createChannel("req-ch-old", "ch-old", 5);
        createChannel("req-ch-new", "ch-new", 5);
        createCampaign("req-c1", "c1", 100, 100, "ch-old");
        ReservationResponse r = apply("req-a1", "c1", "v1");
        service.migrateCampaignChannel("c1",
                new MigrateCampaignChannelRequest("req-m1", "ch-new"));

        // 修改旧渠道额度，旧预占仍按旧渠道过期释放
        ChannelResponse updated = service.updateChannelCap("ch-old",
                new UpdateChannelCapRequest("req-u1", 10, 0));
        assertEquals(1, updated.version());

        mutableClock().advanceMillis(60_000L);
        assertEquals(0, service.queryChannelUsage("ch-old", DAY).used());
        assertEquals("ch-old", service.getReservation(r.reservationId()).channelKey());
    }

    @Test
    @DisplayName("渠道额度修改：版本冲突 409，不得下调到低于跨公告当前已确认数，下调到等于已确认数允许")
    void updateChannelCap_versionConflictAndConfirmedFloor() {
        createChannel("req-ch", "ch-a", 10);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        createCampaign("req-c2", "c2", 100, 100, "ch-a");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c2", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));
        // r3 仍为 RESERVED：当前已确认数为 2（跨两个公告）

        assert409(() -> service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("req-u-low", 1, 0)));
        // 下调失败不占键：同键改用合法参数（版本仍为 0，额度 2=已确认数）可成功
        ChannelResponse updated = service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("req-u-low", 2, 0));
        assertEquals(2, updated.dailyTotalCap());
        assertEquals(1, updated.version());

        // 版本 0 已过期：再用 0 修改 409
        assert409(() -> service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("req-u-stale", 3, 0)));

        // RESERVED 的 r3 取消后，已确认数仍为 2，额度 2 下新申请仍满额 429
        service.cancel(r3.reservationId(), new ReservationActionRequest("req-x1"));
        assert429(() -> apply("req-a4", "c1", "v4"));
    }

    @Test
    @DisplayName("渠道配置不存在 404；重复创建渠道 409；修改不存在渠道 404")
    void channelNotFoundAndDuplicate() {
        assert404(() -> service.queryChannelUsage("nope", DAY));
        createChannel("req-ch", "ch-a", 2);
        assert409(() -> createChannel("req-ch2", "ch-a", 3));
        assert404(() -> service.updateChannelCap("nope",
                new UpdateChannelCapRequest("req-u", 5, 0)));
    }

    @Test
    @DisplayName("渠道写操作幂等：同键同参重放，异参 409，失败不占键")
    void channelOps_idempotencySemantics() {
        ChannelResponse first = createChannel("key-ch", "ch-a", 5);
        ChannelResponse replay = createChannel("key-ch", "ch-a", 5);
        assertEquals(first.version(), replay.version());
        assert409(() -> createChannel("key-ch", "ch-a", 6));

        // 修改：版本冲突失败不占键，更正版本后同键同参成功
        assert409(() -> service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("key-up", 9, 7)));
        ChannelResponse updated = service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("key-up", 9, 0));
        assertEquals(9, updated.dailyTotalCap());
        assertEquals(1, updated.version());

        // 迁移：同键异参（目标渠道不同）409
        createChannel("req-ch-b", "ch-b", 5);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        service.migrateCampaignChannel("c1",
                new MigrateCampaignChannelRequest("key-m", "ch-b"));
        assert409(() -> service.migrateCampaignChannel("c1",
                new MigrateCampaignChannelRequest("key-m", null)));
    }

    @Test
    @DisplayName("跨公告渠道隔离：同渠道多公告共享渠道日额度，各自公告额度独立")
    void channelQuota_sharedAcrossCampaigns() {
        createChannel("req-ch", "ch-a", 2);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        createCampaign("req-c2", "c2", 100, 100, "ch-a");

        apply("req-a1", "c1", "v1");
        apply("req-a2", "c2", "v2");
        assert429(() -> apply("req-a3", "c1", "v3"));
        assert429(() -> apply("req-a4", "c2", "v4"));

        // 另一渠道不受影响
        createChannel("req-ch-b", "ch-b", 2);
        createCampaign("req-c3", "c3", 100, 100, "ch-b");
        ReservationResponse other = apply("req-a5", "c3", "v5");
        assertEquals("ch-b", other.channelKey());
        assertEquals(1, service.queryChannelUsage("ch-b", DAY).used());
    }

    @Test
    @DisplayName("跨公告到期释放：他公告过期预占释放渠道名额后本公告可申请")
    void expiredOnOtherCampaign_releasesChannelSlot() {
        createChannel("req-ch", "ch-a", 1);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        createCampaign("req-c2", "c2", 100, 100, "ch-a");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        assert429(() -> apply("req-a2", "c2", "v2"));

        // c1 的预占到期；c2 申请时先结算同渠道跨公告到期预占，释放渠道名额
        mutableClock().advanceMillis(60_000L);
        ReservationResponse retried = apply("req-a3", "c2", "v2");
        assertEquals("ch-a", retried.channelKey());
        assertEquals("c2", retried.campaignId());
        assertEquals(1, service.queryChannelUsage("ch-a", DAY).used());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c2", null, DAY).usedTotal());
        assertEquals("EXPIRED", service.getReservation(r1.reservationId()).status().name());
    }

    @Test
    @DisplayName("UTC 跨日：渠道额度按 UTC 自然日独立结算，历史日账目保留")
    void channelQuota_resetsOnUtcDayBoundary() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:50Z"));
        createChannel("req-ch", "ch-a", 1);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        ReservationResponse night = apply("req-a1", "c1", "v1");
        assertEquals(LocalDate.of(2026, 9, 22), night.utcDate());

        // 新 UTC 日：渠道新一天额度全新
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        ReservationResponse nextDay = apply("req-a2", "c1", "v1");
        assertEquals(LocalDate.of(2026, 9, 23), nextDay.utcDate());
        // 上一日仍占用 1（night 到期时刻 = 23:59:50 + 60s = 次日 00:00:50，尚未到期）
        assertEquals(1, service.queryChannelUsage("ch-a", LocalDate.of(2026, 9, 22)).used());
        assertEquals(1, service.queryChannelUsage("ch-a", LocalDate.of(2026, 9, 23)).used());
    }

    @Test
    @DisplayName("预占明细查询：按渠道/公告/UTC 日过滤，固化渠道与时刻正确返回")
    void reservationListing_filtersWork() {
        createChannel("req-ch-a", "ch-a", 10);
        createChannel("req-ch-b", "ch-b", 10);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        createCampaign("req-c2", "c2", 100, 100, "ch-b");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c2", "v2");

        List<ReservationResponse> byChannel = service.listReservations(null, "ch-a", null);
        assertEquals(1, byChannel.size());
        assertEquals(r1.reservationId(), byChannel.get(0).reservationId());
        assertEquals(BASE.toEpochMilli(), byChannel.get(0).createdAtUtc());

        List<ReservationResponse> byCampaignAndDay = service.listReservations("c2", null, DAY);
        assertEquals(1, byCampaignAndDay.size());
        assertEquals(r2.reservationId(), byCampaignAndDay.get(0).reservationId());

        assertEquals(2, service.listReservations(null, null, DAY).size());
        assertTrue(service.listReservations("c1", "ch-b", null).isEmpty());
    }

    @Test
    @DisplayName("按公告归属统计：以创建时固化渠道分组，迁移不重算，过期结算计入 EXPIRED")
    void attributionStats_groupedByFrozenChannel() {
        createChannel("req-ch-a", "ch-a", 10);
        createChannel("req-ch-b", "ch-b", 10);
        createCampaign("req-c1", "c1", 100, 100, "ch-a");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.migrateCampaignChannel("c1",
                new MigrateCampaignChannelRequest("req-m1", "ch-b"));
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        mutableClock().advanceMillis(60_000L);
        // r3 到期，查询时结算
        CampaignAttributionResponse stats = service.attributionStats("c1");

        CampaignAttributionResponse.Entry old = stats.entries().stream()
                .filter(e -> "ch-a".equals(e.channelKey())).findFirst().orElseThrow();
        CampaignAttributionResponse.Entry migrated = stats.entries().stream()
                .filter(e -> "ch-b".equals(e.channelKey())).findFirst().orElseThrow();
        // ch-a：r1 已确认、r2 已取消
        assertEquals(0, old.reserved());
        assertEquals(1, old.confirmed());
        assertEquals(1, old.cancelled());
        assertEquals(0, old.expired());
        // ch-b：r3 过期（迁移后新申请，仍固化 ch-b）
        assertEquals(0, migrated.reserved());
        assertEquals(0, migrated.confirmed());
        assertEquals(0, migrated.cancelled());
        assertEquals(1, migrated.expired());
    }

    private void assert409(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
