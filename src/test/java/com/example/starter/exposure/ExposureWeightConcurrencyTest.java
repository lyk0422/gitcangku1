package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滚动权重并发测试：同一访客跨公告并发申请共享同一权重边界，
 * 不能各公告独立超卖。固定时钟使全部申请落在同一窗口内。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_weight_conc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ExposureWeightConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一滚动窗口与同一 UTC 日。 */
    static class FixedClock extends Clock {
        private final Instant fixed;

        FixedClock(Instant fixed) {
            this.fixed = fixed;
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
            return fixed;
        }
    }

    static final Instant BASE = Instant.parse("2026-10-05T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 10, 5);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM visitor_weight_lock");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("跨公告并发申请：共享访客权重边界，权重4×3公告下恰好成功2次")
    void crossCampaignConcurrentApply_sharedWeightBoundary() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-w1", "w1", 1000, 1000, 4));
        service.createCampaign(new CreateCampaignRequest("req-w2", "w2", 1000, 1000, 4));
        service.createCampaign(new CreateCampaignRequest("req-w3", "w3", 1000, 1000, 4));

        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 同一访客跨三个权重4的公告并发申请
                    String campaignId = "w" + (idx % 3 + 1);
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, campaignId, "v1"));
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");

        // 权重上限 10：4+4 <= 10，第三次 12 > 10，恰好成功 2 次
        assertEquals(0, errors.get(), "不应出现 429 以外的异常");
        assertEquals(2, success.get(), "共享权重边界下恰好成功 2 次");
        assertEquals(threads - 2, rejected.get(), "其余请求必须为 429");
        assertEquals(2, reservationIds.size());

        QuotaResponse quota = service.queryQuota("w1", "v1", DAY);
        assertEquals(8, quota.usedWeight(), "窗口权重不得超过上限");
        assertEquals(2, quota.remainingWeight());
    }

    @Test
    @DisplayName("同公告同访客并发申请：权重5恰好成功2次，不超卖")
    void concurrentSameVisitorWeight5_exactlyTwoSucceed() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-cw", "cw", 1000, 1000, 5));

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-a-" + idx, "cw", "v1"));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get());
        assertEquals(2, success.get(), "权重5恰好成功2次");
        assertEquals(threads - 2, rejected.get());

        QuotaResponse quota = service.queryQuota("cw", "v1", DAY);
        assertEquals(10, quota.usedWeight());
        assertEquals(0, quota.remainingWeight());
        assertEquals(2, quota.usedVisitor());
    }
}
