package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelConfigResponse;
import com.example.starter.exposure.web.ChannelReservationResponse;
import com.example.starter.exposure.web.ChannelStatsResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateChannelRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertChannelConfigRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道日总量频控 H2（MODE=MySQL）集成测试：渠道配额、预占结算、
 * 配置版本冲突、迁移隔离、跨公告共享额度与查询语义。
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
        jdbc.update("DELETE FROM channel_reservation");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM channel_daily_ledger");
        jdbc.update("DELETE FROM channel_cap_config");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createChannel(String requestId, String channelKey, int dailyCap) {
        service.upsertChannelConfig(channelKey, new UpsertChannelConfigRequest(requestId, dailyCap, null));
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor,
                                String channelKey) {
        service.createCampaign(
                new CreateCampaignRequest(requestId, campaignId, total, perVisitor, channelKey));
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    @Test
    @DisplayName("渠道配置创建与版本化修改：版本递增、expectedVersion 冲突 409、新建冲突 409")
    void channelConfig_versionedUpsert() {
        ChannelConfigResponse created = service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-cc1", 5, null));
        assertEquals("ch1", created.channelKey());
        assertEquals(5, created.dailyCap());
        assertEquals(1L, created.version());

        // 重复新建（无 expectedVersion）→ 409
        assert409(() -> service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-cc2", 8, null)));

        // 携带正确版本修改成功，版本 +1
        ChannelConfigResponse updated = service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-cc3", 8, 1L));
        assertEquals(8, updated.dailyCap());
        assertEquals(2L, updated.version());

        // 过期版本 → 409
        assert409(() -> service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-cc4", 9, 1L)));

        // 对未配置渠道携带 expectedVersion → 404
        assert404(() -> service.upsertChannelConfig("ghost",
                new UpsertChannelConfigRequest("req-cc5", 3, 1L)));
    }

    @Test
    @DisplayName("渠道配置不得下调到低于当前 UTC 日已确认数；等于已确认数允许")
    void channelConfig_cannotLowerBelowConfirmed() {
        createChannel("req-ch", "ch1", 3);
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));

        // 已确认 2，下调到 1 → 409
        assert409(() -> service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-d1", 1, 1L)));
        // 下调到等于已确认数允许
        ChannelConfigResponse ok = service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("req-d2", 2, 1L));
        assertEquals(2, ok.dailyCap());
    }

    @Test
    @DisplayName("渠道额度不足返回 429：不创建预占、公告侧额度不消耗、失败不占幂等键")
    void apply_channelExhausted_returns429AndConsumesNothing() {
        createChannel("req-ch", "ch1", 1);
        createCampaign("req-c1", "c1", 10, 10, "ch1");

        apply("req-a1", "c1", "v1");
        // 渠道已满（公告额度仍充足）→ 429
        assert429(() -> apply("req-a2", "c1", "v2"));

        // 公告侧额度未被消耗
        QuotaResponse quota = service.queryQuota("c1", "v2", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        // 渠道用量为 1
        ChannelUsageResponse usage = service.queryChannelUsage("ch1", DAY);
        assertEquals(1, usage.usedTotal());
        assertEquals(0, usage.remaining());

        // 失败的 429 请求不占幂等键：释放渠道名额后同键同参可成功
        List<ChannelReservationResponse> records = service.listChannelReservations("ch1", DAY);
        assertEquals(1, records.size(), "429 不得创建渠道预占记录");
        service.cancel(records.get(0).reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = apply("req-a2", "c1", "v2");
        assertEquals("RESERVED", retried.status().name());
        assertEquals(1, service.queryChannelUsage("ch1", DAY).usedTotal());
    }

    @Test
    @DisplayName("公告额度不足同样 429：渠道名额不被消耗")
    void apply_campaignExhausted_channelNotConsumed() {
        createChannel("req-ch", "ch1", 10);
        createCampaign("req-c1", "c1", 1, 5, "ch1");

        apply("req-a1", "c1", "v1");
        assert429(() -> apply("req-a2", "c1", "v2"));

        ChannelUsageResponse usage = service.queryChannelUsage("ch1", DAY);
        assertEquals(1, usage.usedTotal(), "公告侧 429 不得消耗渠道名额");
    }

    @Test
    @DisplayName("未配置渠道不受频控限制；未归属渠道的公告也不受限制")
    void unconfiguredChannel_notLimited() {
        // 公告归属一个从未配置的渠道：不受限制
        createCampaign("req-c1", "c1", 2, 2, "no-config-ch");
        apply("req-a1", "c1", "v1");
        apply("req-a2", "c1", "v2");
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
        assertTrue(service.listChannelReservations("no-config-ch", DAY).isEmpty(),
                "未配置渠道不产生渠道预占记录");

        // 未归属渠道的公告：不受限制
        createCampaign("req-c2", "c2", 2, 2, null);
        apply("req-b1", "c2", "v1");
        apply("req-b2", "c2", "v2");
        assertEquals(2, service.queryQuota("c2", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("确认同时结算公告与渠道名额；取消同时释放两侧；重复终态操作不重复结算")
    void confirmAndCancel_settleBothSides() {
        createChannel("req-ch", "ch1", 2);
        createCampaign("req-c1", "c1", 10, 10, "ch1");

        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");

        // 确认 r1：渠道已确认 +1，占用不变
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        ChannelUsageResponse afterConfirm = service.queryChannelUsage("ch1", DAY);
        assertEquals(2, afterConfirm.usedTotal());
        assertEquals(1, afterConfirm.confirmed());

        // 重复确认（新 requestId）：不重复结算
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k2"));
        assertEquals(1, service.queryChannelUsage("ch1", DAY).confirmed());

        // 取消 r2：渠道与公告两侧同时释放
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));
        ChannelUsageResponse afterCancel = service.queryChannelUsage("ch1", DAY);
        assertEquals(1, afterCancel.usedTotal());
        assertEquals(1, afterCancel.confirmed());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 重复取消（新 requestId）：不重复释放
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x2"));
        assertEquals(1, service.queryChannelUsage("ch1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("过期同时释放渠道与公告名额；过期结算幂等不重复释放")
    void expiry_releasesBothSidesExactlyOnce() {
        createChannel("req-ch", "ch1", 1);
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        apply("req-a1", "c1", "v1");

        mutableClock().advanceMillis(60_000L);

        // 第一次查询触发过期结算
        ChannelUsageResponse usage = service.queryChannelUsage("ch1", DAY);
        assertEquals(0, usage.usedTotal());
        assertEquals(0, usage.confirmed());
        assertEquals(0, service.queryQuota("c1", null, DAY).usedTotal());

        // 再次查询/操作不重复释放（CHECK 约束保证不变负）
        assertEquals(0, service.queryChannelUsage("ch1", DAY).usedTotal());
        ChannelReservationResponse record = service.listChannelReservations("ch1", DAY).get(0);
        assertEquals("EXPIRED", record.status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, record.terminalAtUtc());

        // 释放后渠道名额可再次申请
        ReservationResponse second = apply("req-a2", "c1", "v2");
        assertEquals("RESERVED", second.status().name());
    }

    @Test
    @DisplayName("公告迁移渠道只影响新申请：既有预占按创建时固化渠道结算")
    void migrateChannel_existingReservationsSettleOnFrozenChannel() {
        createChannel("req-chA", "chA", 2);
        createChannel("req-chB", "chB", 2);
        createCampaign("req-c1", "c1", 10, 10, "chA");

        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        assertEquals(1, service.queryChannelUsage("chA", DAY).usedTotal());

        // 迁移到 chB
        CampaignResponse migrated = service.migrateCampaignChannel("c1",
                new MigrateChannelRequest("req-m1", "chB"));
        assertEquals("chB", migrated.channelKey());

        // 新申请占用 chB
        apply("req-a2", "c1", "v2");
        assertEquals(1, service.queryChannelUsage("chB", DAY).usedTotal());
        assertEquals(1, service.queryChannelUsage("chA", DAY).usedTotal());

        // 既有预占取消：释放固化渠道 chA，不影响 chB
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(0, service.queryChannelUsage("chA", DAY).usedTotal());
        assertEquals(1, service.queryChannelUsage("chB", DAY).usedTotal());

        // 渠道预占记录固化的渠道不变
        List<ChannelReservationResponse> chARecords = service.listChannelReservations("chA", DAY);
        assertEquals(1, chARecords.size());
        assertEquals("chA", chARecords.get(0).channelKey());
        assertEquals("c1", chARecords.get(0).campaignId());
        assertEquals("v1", chARecords.get(0).visitorId());
        assertEquals(DAY, chARecords.get(0).utcDate());
        assertEquals(BASE.toEpochMilli(), chARecords.get(0).createdAtUtc());
    }

    @Test
    @DisplayName("同一渠道下多个公告共享日额度：先到先得，超额 429")
    void multipleCampaigns_shareChannelDailyCap() {
        createChannel("req-ch", "ch1", 2);
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        createCampaign("req-c2", "c2", 10, 10, "ch1");

        apply("req-a1", "c1", "v1");
        apply("req-a2", "c2", "v2");
        // 第三个申请无论归属哪个公告都 429
        assert429(() -> apply("req-a3", "c1", "v3"));
        assert429(() -> apply("req-a4", "c2", "v4"));

        ChannelUsageResponse usage = service.queryChannelUsage("ch1", DAY);
        assertEquals(2, usage.usedTotal());
        assertEquals(0, usage.remaining());
    }

    @Test
    @DisplayName("渠道日用量、预占明细与按公告归属统计查询")
    void channelQueries_usageReservationsStats() {
        createChannel("req-ch", "ch1", 5);
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        createCampaign("req-c2", "c2", 10, 10, "ch1");

        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c2", "v3");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        service.cancel(r3.reservationId(), new ReservationActionRequest("req-x1"));

        ChannelUsageResponse usage = service.queryChannelUsage("ch1", DAY);
        assertEquals(5, usage.dailyCap());
        assertEquals(2, usage.usedTotal());
        assertEquals(1, usage.confirmed());
        assertEquals(3, usage.remaining());

        List<ChannelReservationResponse> records = service.listChannelReservations("ch1", DAY);
        assertEquals(3, records.size());
        assertEquals("CONFIRMED", statusOf(records, r1.reservationId()));
        assertEquals("RESERVED", statusOf(records, r2.reservationId()));
        assertEquals("CANCELLED", statusOf(records, r3.reservationId()));

        ChannelStatsResponse stats = service.queryChannelStats("ch1", DAY);
        assertEquals("ch1", stats.channelKey());
        assertEquals(DAY, stats.utcDate());
        assertEquals(2, stats.items().size());
        ChannelStatsResponse.Item c1 = stats.items().get(0);
        assertEquals("c1", c1.campaignId());
        assertEquals(1, c1.confirmed());
        assertEquals(1, c1.reserved());
        assertEquals(0, c1.cancelled());
        ChannelStatsResponse.Item c2 = stats.items().get(1);
        assertEquals("c2", c2.campaignId());
        assertEquals(1, c2.cancelled());

        // 未配置渠道查询用量 → 404
        assert404(() -> service.queryChannelUsage("nope", DAY));
    }

    @Test
    @DisplayName("渠道额度按 UTC 自然日计算：跨日重置，历史日用量保留")
    void channelQuota_resetsOnUtcDayBoundary() {
        createChannel("req-ch", "ch1", 1);
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        // 次日：渠道额度重置
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        assertEquals(LocalDate.of(2026, 9, 23), r2.utcDate());

        assertEquals(1, service.queryChannelUsage("ch1", DAY).usedTotal());
        ChannelUsageResponse nextDay = service.queryChannelUsage("ch1", LocalDate.of(2026, 9, 23));
        assertEquals(1, nextDay.usedTotal());
        assertEquals(0, nextDay.remaining());
    }

    @Test
    @DisplayName("渠道写操作幂等：同键同参重放首次结果，异参 409，失败不占键")
    void channelWrites_idempotencySemantics() {
        createChannel("req-ch", "ch1", 2);

        // 配置创建幂等重放
        ChannelConfigResponse first = service.upsertChannelConfig("ch2",
                new UpsertChannelConfigRequest("key-cfg", 3, null));
        ChannelConfigResponse replay = service.upsertChannelConfig("ch2",
                new UpsertChannelConfigRequest("key-cfg", 3, null));
        assertEquals(first.version(), replay.version());
        // 同键异参 → 409
        assert409(() -> service.upsertChannelConfig("ch2",
                new UpsertChannelConfigRequest("key-cfg", 4, null)));

        // 迁移幂等
        createCampaign("req-c1", "c1", 10, 10, "ch1");
        CampaignResponse migrated = service.migrateCampaignChannel("c1",
                new MigrateChannelRequest("key-mg", "ch2"));
        CampaignResponse migratedReplay = service.migrateCampaignChannel("c1",
                new MigrateChannelRequest("key-mg", "ch2"));
        assertEquals(migrated.channelKey(), migratedReplay.channelKey());
        assert409(() -> service.migrateCampaignChannel("c1",
                new MigrateChannelRequest("key-mg", "ch1")));

        // 失败的修改不占键：版本冲突后同键修正参数可成功
        assert409(() -> service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("key-fail", 9, 99L)));
        ChannelConfigResponse retried = service.upsertChannelConfig("ch1",
                new UpsertChannelConfigRequest("key-fail", 9, 1L));
        assertEquals(9, retried.dailyCap());
    }

    private String statusOf(List<ChannelReservationResponse> records, String reservationId) {
        return records.stream()
                .filter(r -> r.reservationId().equals(reservationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing channel reservation " + reservationId))
                .status().name();
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
