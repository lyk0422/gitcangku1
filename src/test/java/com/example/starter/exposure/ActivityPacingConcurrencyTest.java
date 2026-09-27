package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateActivityRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityBudgetRequest;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放真实多线程并发测试：跨公告申请不超卖小时预算、
 * 配置版本乐观锁并发下只有一次变更成功。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityPacingConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 小时，预占均保持 RESERVED。 */
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
        jdbc.update("DELETE FROM activity_day_snapshot");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private static List<Integer> budgets(int hour, int budget) {
        List<Integer> list = new ArrayList<>(Collections.nCopies(24, 0));
        list.set(hour, budget);
        return list;
    }

    @Test
    @DisplayName("并发跨公告申请同一活动小时：成功数恰好等于小时预算，三侧账目不超卖")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 20;
        int threads = 100;
        service.createActivity(new CreateActivityRequest("req-act", "A"));
        service.updateBudget("A", new UpdateActivityBudgetRequest(
                "req-b1", DAY, hourBudget, budgets(10, hourBudget), 0));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100, 100));
        service.createCampaign(new CreateCampaignRequest("req-c2", "c2", 100, 100));
        service.migrateCampaignActivity("c1", new MigrateActivityRequest("req-m1", "A"));
        service.migrateCampaignActivity("c2", new MigrateActivityRequest("req-m2", "A"));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final String campaignId = idx % 2 == 0 ? "c1" : "c2";
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(new ApplyExposureRequest(
                            "req-a-" + idx, campaignId, "visitor-" + idx));
                    if ("RESERVED".equals(r.status().name())) {
                        success.incrementAndGet();
                    }
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
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(hourBudget, success.get(), "成功预占数必须等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");

        ActivityDiagnosticsResponse diagnostics = service.getHourDiagnostics("A", DAY);
        var hour = diagnostics.hours().get(10);
        assertEquals(hourBudget, hour.reservedCount(), "活动小时占用不得超过预算");
        assertEquals(0, hour.remaining());

        int usedC1 = jdbc.queryForObject("SELECT used_total FROM quota_total_ledger "
                + "WHERE campaign_id = 'c1' AND utc_date = ?", Integer.class, java.sql.Date.valueOf(DAY));
        int usedC2 = jdbc.queryForObject("SELECT used_total FROM quota_total_ledger "
                + "WHERE campaign_id = 'c2' AND utc_date = ?", Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, usedC1 + usedC2, "两公告占用合计必须等于小时预算");
    }

    @Test
    @DisplayName("并发同版本配置变更：只有一次成功，版本号只前进一次")
    void concurrentBudgetUpdate_sameExpectedVersion_onlyOneWins() throws Exception {
        service.createActivity(new CreateActivityRequest("req-act", "A"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.updateBudget("A", new UpdateActivityBudgetRequest(
                            "req-b-" + idx, DAY, 5, budgets(10, 5), 0));
                    success.incrementAndGet();
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

        assertEquals(1, success.get(), "同一 expectedVersion 并发变更只能成功一次");
        assertEquals(threads - 1, conflicts.get(), "其余变更必须因版本不符返回 409");
        assertEquals(1, service.getActivity("A").version(), "版本号只前进一次");
        assertEquals(budgets(10, 5), service.getBudget("A", DAY).hourBudgets());
    }
}
