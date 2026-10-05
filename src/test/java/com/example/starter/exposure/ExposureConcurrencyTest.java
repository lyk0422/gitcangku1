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

    /** 可控时钟：默认固定于 BASE，可按毫秒推进（用于过期回收并发场景）。 */
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

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
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
    void clean() {
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

    @Test
    @DisplayName("同访客跨公告并发申请：共享滚动权重边界，权重和不超过 10")
    void concurrentCrossCampaignApply_sharedWeightBoundary() throws Exception {
        // 5 个新类型公告各权重 4：10 上限下恰好允许 2 个成功（4+4<=10，再加 4 即超）
        for (int i = 0; i < 5; i++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c-" + i, "wc" + i, 1000, 1000, 4, null));
        }

        int threads = 25;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest(
                            "req-w-" + idx, "wc" + (idx % 5), "visitor-shared"));
                    success.incrementAndGet();
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

        assertEquals(2, success.get(), "权重和不超过 10：恰好 2 个权重 4 申请成功");
        assertEquals(threads - 2, rejected.get(), "其余请求必须为 429");
        QuotaResponse quota = service.queryQuota("wc0", "visitor-shared", DAY);
        assertEquals(8, quota.usedWeight(), "最终窗口权重和必须为 8");
        assertEquals(2, quota.remainingWeight());
    }

    @Test
    @DisplayName("过期回收与并发申请共享权重边界：过期预占不占权重，不超卖")
    void concurrentApplyWithExpiredReservation_sharedWeightBoundary() throws Exception {
        // 旧预占权重 5，推进时钟使其过期（60 秒 TTL）；随后并发申请权重 4
        service.createCampaign(new CreateCampaignRequest("req-old", "wc-old", 1000, 1000, 5, null));
        ReservationResponse old = service.apply(
                new ApplyExposureRequest("req-a-old", "wc-old", "visitor-shared"));
        mutableClock().advanceMillis(61_000L);

        for (int i = 0; i < 5; i++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c-" + i, "wc" + i, 1000, 1000, 4, null));
        }

        int threads = 25;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest(
                            "req-w-" + idx, "wc" + (idx % 5), "visitor-shared"));
                    success.incrementAndGet();
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

        assertEquals(2, success.get(), "过期预占已释放权重：恰好 2 个权重 4 申请成功");
        assertEquals(threads - 2, rejected.get(), "其余请求必须为 429");

        // 过期预占的日额度与权重均已释放
        QuotaResponse oldQuota = service.queryQuota("wc-old", "visitor-shared", DAY);
        assertEquals(0, oldQuota.usedTotal(), "过期预占的日额度应已释放");
        assertEquals("EXPIRED", service.getReservation(old.reservationId()).status().name());
        QuotaResponse quota = service.queryQuota("wc0", "visitor-shared", DAY);
        assertEquals(8, quota.usedWeight(), "最终窗口权重和必须为 8（不含过期预占）");
    }
}
