package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayCloseResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ConfigureDayBudgetRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateCampaignRequest;
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
 * 活动匀速投放真实多线程并发测试：跨公告申请不超卖小时预算，终态计数唯一。
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
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_config_history");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM campaign_activity");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private List<Integer> budgetsOf(int... hourBudgets) {
        List<Integer> budgets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            budgets.add(0);
        }
        for (int hour = 0; hour < hourBudgets.length; hour++) {
            budgets.set(hour, hourBudgets[hour]);
        }
        return budgets;
    }

    private void setupActivityWithCampaigns(String activityId, int hourBudget, String... campaignIds) {
        service.createActivity(new CreateActivityRequest("req-act", activityId));
        List<Integer> budgets = budgetsOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, hourBudget);
        service.configureDayBudget(activityId, DAY,
                new ConfigureDayBudgetRequest("req-cfg", 0, hourBudget, budgets));
        for (String campaignId : campaignIds) {
            service.createCampaign(new CreateCampaignRequest("req-c-" + campaignId, campaignId,
                    100_000, 100_000));
            service.migrateCampaign(campaignId,
                    new MigrateCampaignRequest("req-m-" + campaignId, activityId));
        }
    }

    @Test
    @DisplayName("并发跨公告申请：成功数恰好等于小时预算，活动小时账不超卖")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 10;
        int threads = 40;
        setupActivityWithCampaigns("act1", hourBudget, "c1", "c2");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String campaignId = idx % 2 == 0 ? "c1" : "c2";
                    service.apply(new ApplyExposureRequest(
                            "req-a-" + idx, campaignId, "visitor-" + idx));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.add("unexpected " + ex.getStatus().value() + ": " + ex.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.size(), "不应出现非 429 错误: " + errors);
        assertEquals(hourBudget, success.get(), "成功预占数必须等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");

        Integer used = jdbc.queryForObject(
                "SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND hour_of_day = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, used, "活动小时账占用必须等于预算，不超卖");

        Integer totalUsed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger WHERE utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, totalUsed, "两公告总账合计必须与活动占用一致");
    }

    @Test
    @DisplayName("并发确认/取消同一活动预占：终态唯一，日结确认+取消恰好为 1")
    void concurrentConfirmCancel_activityCountsExactlyOnce() throws Exception {
        setupActivityWithCampaigns("act1", 5, "c1");
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "c1", "v1"));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean confirm = i % 2 == 0;
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (confirm) {
                        service.confirm(r.reservationId(), new ReservationActionRequest("req-t-" + idx));
                    } else {
                        service.cancel(r.reservationId(), new ReservationActionRequest("req-t-" + idx));
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() != 409) {
                        throw ex;
                    }
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        ActivityDayCloseResponse close = service.queryDayClose("act1", DAY);
        int confirmed = close.hours().get(10).confirmed();
        int cancelled = close.hours().get(10).cancelled();
        assertEquals(1, confirmed + cancelled,
                "同一预占只允许一个终态，确认+取消必须恰好为 1，实际 confirmed="
                        + confirmed + " cancelled=" + cancelled);

        Integer used = jdbc.queryForObject(
                "SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND hour_of_day = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        if (cancelled == 1) {
            assertEquals(0, used, "取消胜出后活动小时占用应恰好释放一次");
        } else {
            assertEquals(1, used, "确认胜出后活动小时占用保持 1");
        }
    }
}