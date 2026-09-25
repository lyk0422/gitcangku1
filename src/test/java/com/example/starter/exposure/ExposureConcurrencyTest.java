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
 * 真实多线程并发测试：验证 H2 行锁下不超卖、终态唯一、额度不重复释放/不变负。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExposureConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 日，预占均保持 RESERVED（不触发到期）。 */
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

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
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
        jdbc.update("DELETE FROM channel_reservation");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM channel_daily_ledger");
        jdbc.update("DELETE FROM channel_cap_config");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("并发申请超过总额度：成功数恰好等于总额度，账目不超卖")
    void concurrentApply_doesNotOversell() throws Exception {
        int totalCap = 20;
        int threads = 100;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", totalCap, 100_000));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-" + idx));
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
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

        assertEquals(totalCap, success.get(), "成功预占数必须等于总额度");
        assertEquals(threads - totalCap, rejected.get(), "其余请求必须为 429");
        assertEquals(totalCap, reservationIds.size(), "预占单编号必须唯一");

        QuotaResponse quota = service.queryQuota("cap", null, DAY);
        assertEquals(totalCap, quota.usedTotal());
        assertEquals(0, quota.remainingTotal());
    }

    @Test
    @DisplayName("同一预占并发确认/取消：只允许一个终态，额度不重复释放、不变负")
    void concurrentConfirmAndCancel_singleTerminal_noDoubleRelease() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 1, 1));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean confirm = i % 2 == 0;
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse result = confirm
                            ? service.confirm(r.reservationId(),
                                    new ReservationActionRequest("req-t-" + idx))
                            : service.cancel(r.reservationId(),
                                    new ReservationActionRequest("req-t-" + idx));
                    if (result.status().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    } else {
                        cancelled.incrementAndGet();
                    }
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

        // 确认与取消各 12 个线程；胜出方向的同向后继操作返回原状态，异向全部 409
        assertTrue(confirmed.get() == 0 || cancelled.get() == 0,
                "只允许存在一种终态，实际 confirmed=" + confirmed.get() + " cancelled=" + cancelled.get());
        assertEquals(12, confirmed.get() + cancelled.get(), "胜出方向的所有调用返回同一终态");
        assertEquals(12, conflicts.get(), "异向操作必须全部为 409");

        ReservationResponse detail = service.getReservation(r.reservationId());
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedVisitor = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'v1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertTrue(usedTotal >= 0 && usedVisitor >= 0, "账目不得为负");
        if (detail.status().name().equals("CANCELLED")) {
            assertEquals(0, usedTotal, "取消后总额度应恰好释放一次");
            assertEquals(0, usedVisitor, "取消后访客额度应恰好释放一次");
        } else {
            assertEquals(1, usedTotal, "确认后总额度保持占用");
            assertEquals(1, usedVisitor, "确认后访客额度保持占用");
        }
    }

    @Test
    @DisplayName("同一 requestId 并发重放：业务只执行一次，响应一致")
    void concurrentSameRequestId_executesOnce() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("same-key", "cap", "visitor-x"));
                    reservationIds.add(r.reservationId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, reservationIds.size(), "业务只执行一次");
        assertEquals(1, service.queryQuota("cap", "visitor-x", DAY).usedVisitor());
    }
}
