package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.domain.CampaignCategory;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.QuietHoursSettingsRequest;
import com.example.starter.exposure.web.QuietHoursSettingsResponse;
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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM suppression_counter");
        jdbc.update("DELETE FROM visitor_quiet_hours");
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
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", totalCap, 100_000,
                CampaignCategory.MARKETING));

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
                    ApplyResponse a = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-" + idx));
                    assertEquals("RESERVED", a.outcome().name());
                    success.incrementAndGet();
                    reservationIds.add(a.reservationId());
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
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 1, 1,
                CampaignCategory.MARKETING));
        ApplyResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));
        assertEquals("RESERVED", r.outcome().name());

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
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100,
                CampaignCategory.MARKETING));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ApplyResponse a = service.apply(
                            new ApplyExposureRequest("same-key", "cap", "visitor-x"));
                    assertEquals("RESERVED", a.outcome().name());
                    reservationIds.add(a.reservationId());
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
    @DisplayName("静默内并发申请：全部 SUPPRESSED，抑制计数恰好等于线程数，额度与预占不留痕迹")
    void concurrentSuppressedApplies_counterExactNoQuotaTrace() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100,
                CampaignCategory.SERVICE));
        // 固定时钟为 UTC 10:00；偏移 0，静默 00:00-23:59，必在静默区间
        service.saveQuietHours(new QuietHoursSettingsRequest(
                "req-q", "vq", 0, 0, 1439, false, 0));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger suppressed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ApplyResponse a = service.apply(
                            new ApplyExposureRequest("req-s-" + idx, "cap", "vq"));
                    if ("SUPPRESSED".equals(a.outcome().name())) {
                        suppressed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "静默抑制不应产生异常");
        assertEquals(threads, suppressed.get(), "全部申请必须被抑制");
        assertEquals(threads, service.querySuppressionStats("cap", "vq", DAY).suppressedCount(),
                "抑制计数必须恰好累计，不重不漏");
        assertEquals(0, service.queryQuota("cap", "vq", DAY).usedVisitor(),
                "被抑制申请不占访客额度");
        assertEquals(0, service.queryQuota("cap", null, DAY).usedTotal(),
                "被抑制申请不占总额度");
        Integer reservationRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM exposure_reservation WHERE campaign_id = 'cap'", Integer.class);
        assertEquals(0, reservationRows, "被抑制申请不留预占");
    }

    @Test
    @DisplayName("并发修改静默设置（同一 expectedVersion）：仅一个提交成功，版本只加 1，其余 409")
    void concurrentQuietHoursUpdates_singleWinnerVersionOnce() throws Exception {
        service.saveQuietHours(new QuietHoursSettingsRequest(
                "req-q0", "vq", 0, 0, 1439, false, 0));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 全部基于版本 1，改各不相同的窗口
                    QuietHoursSettingsResponse resp = service.saveQuietHours(
                            new QuietHoursSettingsRequest("req-u-" + idx, "vq",
                                    0, 600 + idx, 601 + idx, idx % 2 == 0, 1));
                    if (resp.version() == 2) {
                        winners.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, otherErrors.get(), "除 409 外不应有其他错误");
        assertEquals(1, winners.get(), "只允许一个修改提交成功");
        assertEquals(threads - 1, conflicts.get(), "其余修改必须版本冲突 409");
        assertEquals(2, service.getQuietHours("vq").version(), "版本只能增加一次");
    }

    @Test
    @DisplayName("申请与设置修改并发：按提交顺序裁决，RESERVED 与 SUPPRESSED 数和额度/统计严格对账")
    void concurrentApplyAndSettingsUpdate_commitOrderArbitration() throws Exception {
        // 容量足够大，裁决只受静默设置影响
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000,
                CampaignCategory.MARKETING));
        service.saveQuietHours(new QuietHoursSettingsRequest(
                "req-q0", "vq", 0, 0, 1439, false, 0));

        int applyThreads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger suppressed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        // 单个修改线程：把静默窗口改到 20:00-21:40（UTC 10:00 不在区间），之后申请放行
        pool.submit(() -> {
            try {
                start.await();
                service.saveQuietHours(new QuietHoursSettingsRequest(
                        "req-u1", "vq", 0, 1200, 1300, false, 1));
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });
        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ApplyResponse a = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "vq"));
                    if ("RESERVED".equals(a.outcome().name())) {
                        reserved.incrementAndGet();
                    } else {
                        suppressed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "并发裁决不应产生异常");
        assertEquals(applyThreads, reserved.get() + suppressed.get(),
                "每个申请必须有明确裁决");
        assertEquals(reserved.get(), service.queryQuota("cap", "vq", DAY).usedVisitor(),
                "RESERVED 数必须等于占用的访客额度");
        assertEquals(reserved.get(), service.queryQuota("cap", null, DAY).usedTotal(),
                "RESERVED 数必须等于占用的总额度");
        assertEquals(suppressed.get(), service.querySuppressionStats("cap", "vq", DAY).suppressedCount(),
                "SUPPRESSED 数必须等于抑制统计");
        assertEquals(2, service.getQuietHours("vq").version(), "设置修改最终生效且只生效一次");
    }
}
