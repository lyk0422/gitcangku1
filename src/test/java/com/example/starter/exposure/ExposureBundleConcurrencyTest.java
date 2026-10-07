package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleActionRequest;
import com.example.starter.exposure.web.BundleResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合预占真实多线程并发测试：组合与单条共用统一额度边界，
 * 竞争只留下完整终态、不超卖、不变负。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExposureBundleConcurrencyTest {

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
        jdbc.update("DELETE FROM exposure_bundle");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("组合与单条并发申请共用总额度：不超卖、失败不留半组、账目一致")
    void concurrentBundleAndSingleApply_noOversell() throws Exception {
        int totalCap = 20;
        service.createCampaign(new CreateCampaignRequest("req-ca", "A", totalCap, 100_000));
        service.createCampaign(new CreateCampaignRequest("req-cb", "B", totalCap, 100_000));

        int bundleThreads = 20;
        int singleThreads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger bundleSuccess = new AtomicInteger();
        AtomicInteger singleSuccess = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < bundleThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.applyBundle(new ApplyBundleRequest(
                            "req-b-" + idx, "tester", "bv-" + idx, List.of("A", "B"), 0L));
                    bundleSuccess.incrementAndGet();
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
        for (int i = 0; i < singleThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-s-" + idx, "A", "sv-" + idx));
                    singleSuccess.incrementAndGet();
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

        assertEquals(0, errors.get(), "并发申请不应出现 429 以外的错误");
        int bundles = bundleSuccess.get();
        int singles = singleSuccess.get();
        // A 是瓶颈：组合与单条在 A 上的占用合计恰好等于总额度
        assertEquals(totalCap, bundles + singles, "A 上成功占用数必须等于总额度");
        assertEquals(bundleThreads + singleThreads - totalCap, rejected.get());

        QuotaResponse qa = service.queryQuota("A", null, DAY);
        QuotaResponse qb = service.queryQuota("B", null, DAY);
        assertEquals(totalCap, qa.usedTotal());
        assertEquals(0, qa.remainingTotal());
        // B 只被成功的组合占用；失败的组合不留半组
        assertEquals(bundles, qb.usedTotal());
        int memberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE bundle_id IS NOT NULL", Integer.class);
        assertEquals(2 * bundles, memberRows, "每个成功组合恰好两条成员预占");
        int singleRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE bundle_id IS NULL", Integer.class);
        assertEquals(singles, singleRows);
    }

    @Test
    @DisplayName("同一组合并发确认/取消：只允许一个终态，额度不重复释放、不变负")
    void concurrentConfirmAndCancelBundle_singleTerminal() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-ca", "A", 10, 10));
        service.createCampaign(new CreateCampaignRequest("req-cb", "B", 10, 10));
        BundleResponse bundle = service.applyBundle(
                new ApplyBundleRequest("req-bd", "tester", "v1", List.of("A", "B"), 0L));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean confirm = i % 2 == 0;
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    BundleResponse result = confirm
                            ? service.confirmBundle(bundle.bundleId(),
                                    new BundleActionRequest("req-t-" + idx, "tester", 0L))
                            : service.cancelBundle(bundle.bundleId(),
                                    new BundleActionRequest("req-t-" + idx, "tester", 0L));
                    if (result.status().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    } else {
                        cancelled.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
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

        assertEquals(0, errors.get(), "并发终态竞争不应出现 409 以外的错误");
        assertTrue(confirmed.get() == 0 || cancelled.get() == 0,
                "只允许存在一种终态，实际 confirmed=" + confirmed.get() + " cancelled=" + cancelled.get());
        assertEquals(12, confirmed.get() + cancelled.get(), "胜出方向的所有调用返回同一终态");
        assertEquals(12, conflicts.get(), "异向操作必须全部为 409");

        BundleResponse detail = service.getBundle(bundle.bundleId());
        int usedA = service.queryQuota("A", "v1", DAY).usedTotal();
        int usedB = service.queryQuota("B", "v1", DAY).usedTotal();
        int visitorA = service.queryQuota("A", "v1", DAY).usedVisitor();
        assertTrue(usedA >= 0 && usedB >= 0 && visitorA >= 0, "账目不得为负");
        for (var member : detail.members()) {
            assertEquals(detail.status(), member.status(), "成员状态必须与组一致");
        }
        if (detail.status().name().equals("CANCELLED")) {
            assertEquals(0, usedA, "取消后 A 额度应恰好释放一次");
            assertEquals(0, usedB, "取消后 B 额度应恰好释放一次");
            assertEquals(0, visitorA, "取消后访客额度应恰好释放一次");
        } else {
            assertEquals("CONFIRMED", detail.status().name());
            assertEquals(1, usedA, "确认后 A 额度保持占用");
            assertEquals(1, usedB, "确认后 B 额度保持占用");
            assertEquals(1, visitorA, "确认后访客额度保持占用");
        }
    }
}
