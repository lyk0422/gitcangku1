package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 滚动窗口权重限制 H2（MODE=MySQL）集成测试：新类型公告固定权重 1～5，
 * 同访客跨公告最近 600 秒确认与有效预占权重和不超过 10，与原每日额度共同裁决。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExposureWeightWindowTest {

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
        jdbc.update("DELETE FROM visitor_weight_guard");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createWeighted(String requestId, String campaignId, int weight) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, 100, 100, weight, null));
    }

    @Test
    @DisplayName("合成场景：两次权重5确认后第三次权重1返回429；首次申请恰好600秒后权重释放可再申请")
    void twoConfirmedWeight5_thenWeight1Rejected_untilExactly600SecondsPass() {
        createWeighted("req-ca", "cA", 5);
        createWeighted("req-cb", "cB", 5);
        createWeighted("req-cc", "cC", 1);

        // t0：权重5申请并确认；t0+1s：再一个权重5申请并确认，窗口权重和=10
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cA", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        mutableClock().advanceMillis(1_000L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cB", "v1"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));

        QuotaResponse atFull = service.queryQuota("cC", "v1", DAY);
        assertEquals(10, atFull.usedWeight());
        assertEquals(0, atFull.remainingWeight());
        assertEquals(600, atFull.weightWindowSeconds());
        assertEquals(10, atFull.weightCap());

        // 第三次权重1：10+1>10 → 429，且三套账都不增加
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "cC", "v1")));
        QuotaResponse afterReject = service.queryQuota("cC", "v1", DAY);
        assertEquals(0, afterReject.usedTotal(), "被拒绝的申请不得增加公告日总额");
        assertEquals(0, afterReject.usedVisitor(), "被拒绝的申请不得增加访客日额度");
        assertEquals(10, afterReject.usedWeight(), "被拒绝的申请不得增加窗口权重");

        // 首次申请恰好过去 600 秒：归属时刻固定为首次申请，确认不刷新，权重释放 5
        mutableClock().setInstant(BASE.plusMillis(600_000L));
        QuotaResponse released = service.queryQuota("cC", "v1", DAY);
        assertEquals(5, released.usedWeight(), "恰好 600 秒后首次申请移出窗口");
        // 移出权重窗口不释放已确认的日额度
        assertEquals(1, service.queryQuota("cA", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("cB", "v1", DAY).usedTotal());

        // 权重释放后可再申请：5+1=6 <= 10
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a4", "cC", "v1"));
        assertEquals("RESERVED", r3.status().name());
        assertEquals(6, service.queryQuota("cC", "v1", DAY).usedWeight());
    }

    @Test
    @DisplayName("有效预占计入权重；取消同时释放权重与日额度")
    void reservedCountsAndCancelReleasesWeightAndDaily() {
        createWeighted("req-ca", "cA", 5);
        createWeighted("req-cb", "cB", 5);
        createWeighted("req-cc", "cC", 1);

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cA", "v1"));
        service.apply(new ApplyExposureRequest("req-a2", "cB", "v1"));
        assertEquals(10, service.queryQuota("cC", "v1", DAY).usedWeight());

        // 未确认的有效预占同样计入：权重和 10 时权重1申请 429
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "cC", "v1")));

        // 取消释放权重与日额度
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        QuotaResponse quota = service.queryQuota("cA", "v1", DAY);
        assertEquals(5, quota.usedWeight());
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());

        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a4", "cC", "v1"));
        assertEquals("RESERVED", r3.status().name());
    }

    @Test
    @DisplayName("过期预占释放权重与日额度；确认保持权重直到移出窗口")
    void expiryReleasesWeight_confirmKeepsWeightUntilWindowExit() {
        createWeighted("req-ca", "cA", 5);
        createWeighted("req-cb", "cB", 5);

        // 预占未确认，60 秒 TTL 到期后权重释放
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cA", "v1"));
        assertEquals(5, service.queryQuota("cB", "v1", DAY).usedWeight());
        mutableClock().advanceMillis(60_000L);
        QuotaResponse afterExpiry = service.queryQuota("cA", "v1", DAY);
        assertEquals(0, afterExpiry.usedWeight(), "过期预占不再计入权重");
        assertEquals(0, afterExpiry.usedTotal(), "过期预占释放日额度");
        assertEquals("EXPIRED", service.getReservation(r1.reservationId()).status().name());

        // 确认保持权重直到移出窗口：t1 申请并确认，599 秒后仍计入，600 秒后移出
        mutableClock().setInstant(BASE.plusMillis(120_000L));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cA", "v1"));
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));
        mutableClock().setInstant(BASE.plusMillis(120_000L + 599_000L));
        assertEquals(5, service.queryQuota("cB", "v1", DAY).usedWeight(),
                "确认后 599 秒权重仍保持");
        mutableClock().setInstant(BASE.plusMillis(120_000L + 600_000L));
        QuotaResponse exited = service.queryQuota("cA", "v1", DAY);
        assertEquals(0, exited.usedWeight(), "恰好 600 秒后确认单移出窗口");
        assertEquals(1, exited.usedTotal(), "移出权重窗口不释放已确认的日额度");
    }

    @Test
    @DisplayName("跨 UTC 日确认：权重仍归属申请时刻，日额度仍计申请日")
    void confirmAcrossUtcDay_weightAnchoredToApplicationTime() {
        createWeighted("req-ca", "cA", 5);
        createWeighted("req-cb", "cB", 5);
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:30Z"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "cA", "v1"));
        assertEquals(LocalDate.of(2026, 9, 22), r.utcDate());

        // 次日确认（未超 60 秒 TTL）：权重归属时刻固定为首次申请，确认不刷新
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));
        assertEquals(5, service.queryQuota("cB", "v1", LocalDate.of(2026, 9, 23)).usedWeight());
        assertEquals(1, service.queryQuota("cA", "v1", LocalDate.of(2026, 9, 22)).usedTotal());
        assertEquals(0, service.queryQuota("cA", "v1", LocalDate.of(2026, 9, 23)).usedTotal());

        // 申请时刻 +600 秒后（次日 00:09:30）权重移出窗口，申请日日额度仍保留
        mutableClock().setInstant(Instant.parse("2026-09-23T00:09:30Z"));
        assertEquals(0, service.queryQuota("cB", "v1", LocalDate.of(2026, 9, 23)).usedWeight());
        assertEquals(1, service.queryQuota("cA", "v1", LocalDate.of(2026, 9, 22)).usedTotal());
    }

    @Test
    @DisplayName("首次响应冻结申请时额度：窗口变化后同键重放仍返回冻结值")
    void applyResponseFreezesQuota_replayReturnsFrozenSnapshot() {
        createWeighted("req-ca", "cA", 5);
        createWeighted("req-cb", "cB", 5);

        ReservationResponse first = service.apply(new ApplyExposureRequest("key-frozen", "cA", "v1"));
        assertEquals(Integer.valueOf(99), first.remainingTotal());
        assertEquals(Integer.valueOf(99), first.remainingVisitor());
        assertEquals(Integer.valueOf(5), first.remainingWeight());

        // 窗口后来变化：首次申请移出窗口，当前剩余权重回到 10
        mutableClock().advanceMillis(600_000L);
        assertEquals(10, service.queryQuota("cB", "v1", DAY).remainingWeight());

        // 旧键重放：必须返回首次冻结的剩余值，而非当前剩余值
        ReservationResponse replay = service.apply(new ApplyExposureRequest("key-frozen", "cA", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(Integer.valueOf(5), replay.remainingWeight(), "重放必须返回冻结的权重快照");
        assertEquals(Integer.valueOf(99), replay.remainingTotal(), "重放必须返回冻结的日额度快照");
        assertEquals(Integer.valueOf(99), replay.remainingVisitor());
    }

    @Test
    @DisplayName("旧类型公告（无权重）不受滚动窗口限制，也不计入他人权重")
    void oldTypeCampaignExemptFromWeightWindow() {
        // 旧类型：不传 attentionWeight
        service.createCampaign(new CreateCampaignRequest("req-old", "oldC", 100, 20));
        createWeighted("req-cn", "newC", 5);

        // 同访客 600 秒内连续申请 15 次旧类型公告：仅受日额度约束
        for (int i = 0; i < 15; i++) {
            ReservationResponse r = service.apply(
                    new ApplyExposureRequest("req-o-" + i, "oldC", "v1"));
            assertEquals("RESERVED", r.status().name());
            assertNull(r.remainingWeight(), "旧类型公告不返回权重快照");
        }
        QuotaResponse quota = service.queryQuota("oldC", "v1", DAY);
        assertEquals(15, quota.usedVisitor());
        assertEquals(0, quota.usedWeight(), "旧类型公告预占不计入窗口权重");

        // 新类型公告权重裁决不受旧类型预占影响
        service.apply(new ApplyExposureRequest("req-n-1", "newC", "v1"));
        service.apply(new ApplyExposureRequest("req-n-2", "newC", "v1"));
        assertEquals(10, service.queryQuota("newC", "v1", DAY).usedWeight());
    }

    @Test
    @DisplayName("幂等身份结构化比较：参数含分隔符不碰撞，期望版本不同即异参 409")
    void structuredFingerprint_noSeparatorCollision_expectedVersionInIdentity() {
        createWeighted("req-c1", "c|1", 1);
        createWeighted("req-c2", "c", 1);

        // 旧式 "|" 拼接下 ("c|1","1") 与 ("c","1|1") 指纹相同；结构化比较必须区分
        ReservationResponse first = service.apply(new ApplyExposureRequest("key-s", "c|1", "1"));
        assertEquals("c|1", first.campaignId());
        assert409(() -> service.apply(new ApplyExposureRequest("key-s", "c", "1|1")));

        // 同键同参（含期望版本）重放原响应；期望版本不同即异参 409
        service.createCampaign(new CreateCampaignRequest("key-v", "cV", 10, 10, 1, 7L));
        assert409(() -> service.createCampaign(new CreateCampaignRequest("key-v", "cV", 10, 10, 1, 8L)));
        assert409(() -> service.createCampaign(new CreateCampaignRequest("key-v", "cV", 10, 10, 1, null)));

        ReservationResponse applied = service.apply(
                new ApplyExposureRequest("key-av", "cV", "v1", 3L));
        ReservationResponse replayed = service.apply(
                new ApplyExposureRequest("key-av", "cV", "v1", 3L));
        assertEquals(applied.reservationId(), replayed.reservationId());
        assert409(() -> service.apply(new ApplyExposureRequest("key-av", "cV", "v1", 4L)));
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }
}
