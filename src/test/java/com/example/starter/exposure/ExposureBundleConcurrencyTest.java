package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合预占真实多线程并发测试：H2 行锁下整组不超卖、终态唯一、
 * 组合与单条申请共用统一额度边界。使用独立命名库 exposure_bundle_cc。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_bundle_cc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
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
        jdbc.update("DELETE FROM exposure_group");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private int usedTotal(String campaignId) {
        return jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = ? AND utc_date = ?",
                Integer.class, campaignId, java.sql.Date.valueOf(DAY));
    }

    @Test
    @DisplayName("并发组合申请竞争同一组公告：仅一组成功，其余 429，账目不超卖")
    void concurrentBundleApply_singleWinner_noOversell() throws Exception {
        service.createCampaign(new CreateCampaignRequest("rc-a", "ba", 1, 100));
        service.createCampaign(new CreateCampaignRequest("rc-b", "bb", 1, 100));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.applyBundle(new ApplyBundleRequest(
                            "bk-" + idx, "visitor-" + idx, List.of("ba", "bb")));
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

        assertEquals(1, success.get(), "总额度各为 1，只允许一个整组成功");
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, usedTotal("ba"));
        assertEquals(1, usedTotal("bb"));
        Integer groups = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_group", Integer.class);
        assertEquals(1, groups, "只应留下一个完整组合组");
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE group_id IS NOT NULL", Integer.class);
        assertEquals(2, members, "成功组恰好两条成员，不留半组");
    }

    @Test
    @DisplayName("同一组合并发确认/取消：整组只允许一个终态，成员与组一致，额度不重复释放")
    void concurrentBundleConfirmAndCancel_singleTerminal() throws Exception {
        service.createCampaign(new CreateCampaignRequest("rc-a", "ba", 5, 5));
        service.createCampaign(new CreateCampaignRequest("rc-b", "bb", 5, 5));
        BundleResponse bundle = service.applyBundle(new ApplyBundleRequest("bk-0", "v1", List.of("ba", "bb")));

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
                    BundleResponse result = confirm
                            ? service.confirmBundle(bundle.groupId(), new ReservationActionRequest("bt-" + idx))
                            : service.cancelBundle(bundle.groupId(), new ReservationActionRequest("bt-" + idx));
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

        assertTrue(confirmed.get() == 0 || cancelled.get() == 0,
                "整组只允许一种终态，实际 confirmed=" + confirmed.get() + " cancelled=" + cancelled.get());
        assertEquals(12, confirmed.get() + cancelled.get(), "胜出方向的调用返回同一终态");
        assertEquals(12, conflicts.get(), "异向操作必须全部为 409");

        BundleResponse detail = service.getBundle(bundle.groupId());
        assertTrue(detail.members().stream()
                        .allMatch(m -> m.status() == detail.status()),
                "成员终态必须与组一致");
        if (detail.status().name().equals("CANCELLED")) {
            assertEquals(0, usedTotal("ba"), "取消后 ba 恰好释放一次");
            assertEquals(0, usedTotal("bb"), "取消后 bb 恰好释放一次");
        } else {
            assertEquals(1, usedTotal("ba"));
            assertEquals(1, usedTotal("bb"));
        }
    }

    @Test
    @DisplayName("组合与单条申请并发竞争同一公告：统一额度边界，不超卖、不变负")
    void concurrentSingleAndBundleApply_sharedQuotaBoundary() throws Exception {
        int totalCap = 10;
        service.createCampaign(new CreateCampaignRequest("rc-a", "ba", totalCap, 100));
        service.createCampaign(new CreateCampaignRequest("rc-b", "bb", 100, 100));

        int singles = 20;
        int bundles = 20;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < singles; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("rs-" + idx, "ba", "sv-" + idx));
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
        for (int i = 0; i < bundles; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.applyBundle(new ApplyBundleRequest("rb-" + idx, "bv-" + idx, List.of("ba", "bb")));
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

        assertEquals(totalCap, success.get(), "单条与组合共享 ba 总额度，成功总数必须等于额度");
        assertEquals(singles + bundles - totalCap, rejected.get());
        assertEquals(totalCap, usedTotal("ba"), "ba 不超卖");
        int bbUsed = service.queryQuota("bb", null, DAY).usedTotal();
        Integer bundleGroups = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_group", Integer.class);
        assertEquals(bundleGroups, bbUsed, "bb 占用数必须等于成功组合数，不留半组");
        assertTrue(usedTotal("ba") >= 0 && bbUsed >= 0, "账目不得为负");
    }
}
