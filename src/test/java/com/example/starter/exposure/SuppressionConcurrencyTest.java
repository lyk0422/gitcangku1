package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.suppression.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionActionRequest;
import com.example.starter.exposure.web.SuppressionResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抑制名单真实多线程并发测试（H2 行锁）：
 * 加入名单与曝光确认竞争时一笔预占只能有一种终态，账目精确不重不漏；
 * 生效后并发申请全部 409；同 requestId 并发加入只执行一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class SuppressionConcurrencyTest {

    /** 固定时钟：预占均不过期，排除到期结算干扰。 */
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

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    ExposureService exposureService;
    @Autowired
    SuppressionService suppressionService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM suppression_cancellation");
        jdbc.update("DELETE FROM visitor_suppression");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @Test
    @DisplayName("加入名单与确认并发竞争：每笔预占要么CONFIRMED占额要么CANCELLED释放，不重不漏")
    void suppressRacingWithConfirm_exactlyOneOutcomePerReservation() throws Exception {
        int reservations = 30;
        exposureService.createCampaign(
                new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));
        List<String> reservationIds = new java.util.ArrayList<>();
        for (int i = 0; i < reservations; i++) {
            ReservationResponse r = exposureService.apply(
                    new ApplyExposureRequest("req-a-" + i, "cap", "V"));
            reservationIds.add(r.reservationId());
        }

        int threads = reservations + 1;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 16));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmErrors = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<Throwable> unexpectedErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // 每笔预占一个确认线程
        for (int i = 0; i < reservations; i++) {
            final String reservationId = reservationIds.get(i);
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    exposureService.confirm(reservationId,
                            new ReservationActionRequest("req-k-" + idx));
                } catch (ApiException ex) {
                    // 被抑制抢先取消的确认得到 409
                    if (ex.getStatus().value() != 409) {
                        unexpected.incrementAndGet();
                        unexpectedErrors.add(ex);
                    } else {
                        confirmErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                    unexpectedErrors.add(e);
                }
            });
        }

        // 唯一的加入名单线程
        pool.submit(() -> {
            try {
                start.await();
                suppressionService.suppress("V", new SuppressionActionRequest("req-s-1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                unexpected.incrementAndGet();
                unexpectedErrors.add(e);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");
        assertTrue(unexpected.get() == 0, "不应出现非预期异常: " + unexpectedErrors);

        int confirmed = 0;
        int cancelled = 0;
        for (String id : reservationIds) {
            String status = exposureService.getReservation(id).status().name();
            assertTrue(status.equals("CONFIRMED") || status.equals("CANCELLED"),
                    "每笔预占必须是 CONFIRMED 或 CANCELLED，实际: " + status);
            if ("CONFIRMED".equals(status)) {
                confirmed++;
            } else {
                cancelled++;
            }
        }
        assertEquals(reservations, confirmed + cancelled, "终态笔数之和必须等于预占总数");

        SuppressionResponse view = suppressionService.getSuppression("V");
        assertTrue(view.active(), "竞争结束后抑制应保持生效");
        assertEquals(cancelled, view.cancelledCount(), "返回的取消数量必须等于实际 CANCELLED 笔数");
        assertEquals(cancelled, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancellation WHERE visitor_id = 'V'", Integer.class),
                "取消明细笔数必须与状态一致");

        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedVisitor = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'V' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(confirmed, usedTotal, "总额度占用必须恰好等于 CONFIRMED 笔数");
        assertEquals(confirmed, usedVisitor, "访客额度占用必须恰好等于 CONFIRMED 笔数");
        assertEquals(confirmErrors.get(), cancelled, "被抢先取消的确认必须全部 409");
    }

    @Test
    @DisplayName("抑制生效后并发新申请：全部409且额度不增加")
    void applyWhileSuppressed_concurrentAll409NoQuota() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));
        suppressionService.suppress("V", new SuppressionActionRequest("req-s-1"));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    exposureService.apply(new ApplyExposureRequest("req-a-" + idx, "cap", "V"));
                    other.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads, conflicts.get(), "生效期间所有申请必须 409");
        assertEquals(0, other.get());
        Integer usedTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(0, usedTotal, "409 申请不得产生任何账目行");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE visitor_id = 'V'", Integer.class),
                "409 申请不得创建预占单");
    }

    @Test
    @DisplayName("同一requestId并发加入名单：取消只执行一次，响应一致")
    void concurrentSameSuppressRequestId_cancelsOnce() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));
        for (int i = 0; i < 5; i++) {
            exposureService.apply(new ApplyExposureRequest("req-a-" + i, "cap", "V"));
        }

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    suppressionService.suppress("V", new SuppressionActionRequest("same-suppress-key"));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        SuppressionResponse view = suppressionService.getSuppression("V");
        assertTrue(view.active());
        assertEquals(5, view.cancelledCount());
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancellation WHERE visitor_id = 'V'", Integer.class));
        assertEquals(0, exposureService.queryQuota("cap", "V", DAY).usedVisitor());
    }
}
