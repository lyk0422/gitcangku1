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
 * 滚动窗口注意力权重 H2（MODE=MySQL）集成测试：三重裁决、窗口边界、
 * 取消/过期/确认对权重的影响、冻结响应与结构化幂等指纹。使用独立命名库。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_weight_window_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ExposureWeightedWindowTest {

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

    static final Instant BASE = Instant.parse("2026-10-05T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 10, 5);

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
        jdbc.update("DELETE FROM visitor_weight_lock");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("合成场景：两次权重5确认后第三次权重1返回429；首次申请恰好600秒后权重释放5可再申请")
    void synthetic_twoWeight5Confirmed_thirdWeight1Rejected_releaseAfterExactly600s() {
        service.createCampaign(new CreateCampaignRequest("req-c5a", "c5a", 100, 100, 5));
        service.createCampaign(new CreateCampaignRequest("req-c5b", "c5b", 100, 100, 5));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100, 1));

        // t0：第一次权重5申请并确认
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c5a", "v1"));
        assertEquals(5, r1.remainingWeight());
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        // t0+10s：第二次权重5申请并确认，窗口权重达到上限 10
        mutableClock().advanceMillis(10_000L);
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "c5b", "v1"));
        assertEquals(0, r2.remainingWeight());
        service.confirm(r2.reservationId(), new ReservationActionRequest("req-k2"));

        // 查询分别展示滚动权重与每日额度
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(10, quota.usedWeight());
        assertEquals(0, quota.remainingWeight());
        assertEquals(600, quota.windowSeconds());
        assertEquals(10, quota.windowWeightCap());
        assertEquals(1, quota.attentionWeight());
        assertEquals(0, quota.usedTotal());

        // 第三次权重1申请：5+5+1 > 10 → 429，三套账都不增加
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "c1", "v1")));
        QuotaResponse after = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, after.usedTotal());
        assertEquals(0, after.usedVisitor());
        assertEquals(10, after.usedWeight());

        // 推进到首次申请恰好过去 600 秒：r1 移出窗口释放权重5，同键重试成功（失败不占键）
        mutableClock().setInstant(BASE.plusMillis(600_000L));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "c1", "v1"));
        assertEquals("RESERVED", r3.status().name());
        assertEquals(4, r3.remainingWeight());
        assertEquals(6, service.queryQuota("c1", "v1", DAY).usedWeight());
    }

    @Test
    @DisplayName("未过期预占计入权重；取消与过期即时释放权重")
    void reservedCounts_cancelAndExpiryReleaseWeight() {
        service.createCampaign(new CreateCampaignRequest("req-cw", "cw", 100, 100, 5));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cw", "v1"));
        assertEquals(5, service.queryQuota("cw", "v1", DAY).usedWeight());

        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cw", "v1"));
        assertEquals(10, service.queryQuota("cw", "v1", DAY).usedWeight());
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "cw", "v1")));

        // 取消释放权重与日额度
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x2"));
        QuotaResponse afterCancel = service.queryQuota("cw", "v1", DAY);
        assertEquals(5, afterCancel.usedWeight());
        assertEquals(1, afterCancel.usedVisitor());

        // 60 秒预占规则不变：r1 到期后窗口权重清空
        mutableClock().advanceMillis(60_000L);
        assertEquals(0, service.queryQuota("cw", "v1", DAY).usedWeight());
        assertEquals("EXPIRED", service.getReservation(r1.reservationId()).status().name());
        ReservationResponse r4 = service.apply(new ApplyExposureRequest("req-a4", "cw", "v1"));
        assertEquals("RESERVED", r4.status().name());
    }

    @Test
    @DisplayName("权重归属时刻固定为首次申请，确认不刷新；恰好600秒移出窗口且不释放已确认日额度")
    void confirmDoesNotRefreshWindowAttribution() {
        service.createCampaign(new CreateCampaignRequest("req-cw", "cw", 100, 100, 5));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cw", "v1"));

        mutableClock().advanceMillis(30_000L);
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        // t0+590s：确认后仍在窗口内（归属时刻为 t0）
        mutableClock().setInstant(BASE.plusMillis(590_000L));
        assertEquals(5, service.queryQuota("cw", "v1", DAY).usedWeight());

        // t0+600s：归属时刻恰好 600 秒前移出窗口；若按确认时刻刷新则仍会计权
        mutableClock().setInstant(BASE.plusMillis(600_000L));
        QuotaResponse quota = service.queryQuota("cw", "v1", DAY);
        assertEquals(0, quota.usedWeight());

        // 移出权重窗口不释放已确认的日额度
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());
    }

    @Test
    @DisplayName("旧类型公告不参与滚动权重：可连续申请且窗口权重为零")
    void legacyCampaign_notWeightLimited() {
        service.createCampaign(new CreateCampaignRequest("req-cl", "legacy", 100, 100));
        for (int i = 0; i < 15; i++) {
            service.apply(new ApplyExposureRequest("req-a" + i, "legacy", "v1"));
        }
        QuotaResponse quota = service.queryQuota("legacy", "v1", DAY);
        assertEquals(15, quota.usedVisitor());
        assertEquals(0, quota.usedWeight());
        assertNull(quota.attentionWeight());

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a15", "legacy", "v1"));
        assertNull(r.remainingWeight());
    }

    @Test
    @DisplayName("首次响应冻结申请时额度：窗口变化后旧键重放仍返回冻结快照")
    void frozenFirstResponse_replayReturnsFrozenSnapshot() {
        service.createCampaign(new CreateCampaignRequest("req-cw", "cw", 10, 10, 5));
        ReservationResponse first = service.apply(new ApplyExposureRequest("key-f", "cw", "v1"));
        assertEquals(9, first.remainingTotal());
        assertEquals(9, first.remainingVisitor());
        assertEquals(5, first.remainingWeight());

        // 后续申请改变窗口权重与日额度
        service.apply(new ApplyExposureRequest("key-g", "cw", "v1"));
        assertEquals(10, service.queryQuota("cw", "v1", DAY).usedWeight());

        // 旧键重放：返回首次冻结响应，而非当前剩余值
        ReservationResponse replay = service.apply(new ApplyExposureRequest("key-f", "cw", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(9, replay.remainingTotal());
        assertEquals(9, replay.remainingVisitor());
        assertEquals(5, replay.remainingWeight());

        // 窗口完全推移后重放仍冻结
        mutableClock().advanceMillis(600_000L);
        ReservationResponse replayLater = service.apply(new ApplyExposureRequest("key-f", "cw", "v1"));
        assertEquals(5, replayLater.remainingWeight());
        assertEquals(9, replayLater.remainingTotal());
    }

    @Test
    @DisplayName("结构化指纹：参数含分隔符不碰撞，异参重放 409，同参重放成功")
    void structuredFingerprint_delimiterInParamsNoCollision() {
        service.createCampaign(new CreateCampaignRequest("req-ca", "a", 10, 10));
        service.createCampaign(new CreateCampaignRequest("req-cab", "a|b", 10, 10));

        ReservationResponse first = service.apply(new ApplyExposureRequest("key-1", "a", "b|c"));
        assertEquals("RESERVED", first.status().name());

        // 旧式 "campaign|visitor" 拼接下两者指纹同为 "a|b|c"，结构化比较必须判为异参 409
        assert409(() -> service.apply(new ApplyExposureRequest("key-1", "a|b", "c")));

        ReservationResponse replay = service.apply(new ApplyExposureRequest("key-1", "a", "b|c"));
        assertEquals(first.reservationId(), replay.reservationId());
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
