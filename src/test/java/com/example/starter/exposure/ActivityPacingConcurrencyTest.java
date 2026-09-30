package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayQuotaResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertActivityDayConfigRequest;
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
 * 同版本并发配置修改只有一个胜出。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityPacingConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 小时，预占均保持 RESERVED（不触发到期）。 */
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
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_day_settlement");
        jdbc.update("DELETE FROM activity_hour_ledger");
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private static List<Integer> budgets(int... hourBudgets) {
        List<Integer> list = new ArrayList<>(Collections.nCopies(24, 0));
        for (int i = 0; i < hourBudgets.length; i += 2) {
            list.set(hourBudgets[i], hourBudgets[i + 1]);
        }
        return list;
    }

    private static int sum(List<Integer> budgets) {
        return budgets.stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    @DisplayName("并发跨公告申请：成功数恰好等于小时预算，活动小时账目不超卖")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 10;
        int threads = 40;
        List<Integer> budgets = budgets(10, hourBudget);
        service.createActivity(new CreateActivityRequest("req-act", "act1"));
        service.upsertDayConfig("act1", DAY,
                new UpsertActivityDayConfigRequest("req-cfg", 0L, sum(budgets), budgets));
        for (int c = 0; c < 4; c++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c" + c, "camp" + c, 100, 1_000, "act1"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(new ApplyExposureRequest(
                            "req-a-" + idx, "camp" + (idx % 4), "visitor-" + idx));
                    if (r.status().name().equals("RESERVED")) {
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(hourBudget, success.get(), "成功预占数必须等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");

        ActivityDayQuotaResponse quota = service.queryActivityDayQuota("act1", DAY);
        assertEquals(hourBudget, quota.usedTotal());
        assertEquals(hourBudget, quota.hours().get(10).used());
        assertEquals(0, quota.hours().get(10).remaining());

        Integer used = jdbc.queryForObject(
                "SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND hour_of_day = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, used, "活动小时账目不得超卖");
    }

    @Test
    @DisplayName("同版本并发修改配置：只有一个胜出，其余 409，版本只前进一次")
    void concurrentConfigUpdate_sameExpectedVersion_singleWinner() throws Exception {
        List<Integer> initial = budgets(10, 5);
        service.createActivity(new CreateActivityRequest("req-act", "act1"));
        service.upsertDayConfig("act1", DAY,
                new UpsertActivityDayConfigRequest("req-cfg", 0L, sum(initial), initial));

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
                    List<Integer> budgets = budgets(10, 6);
                    ActivityDayConfigResponse response = service.upsertDayConfig("act1", DAY,
                            new UpsertActivityDayConfigRequest(
                                    "req-u-" + idx, 1L, sum(budgets), budgets));
                    if (response.version() == 2L) {
                        success.incrementAndGet();
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

        assertEquals(1, success.get(), "同一 expectedVersion 只能有一个修改胜出");
        assertEquals(threads - 1, conflicts.get(), "其余并发修改必须为 409");

        ActivityDayConfigResponse current = service.getDayConfig("act1", DAY);
        assertEquals(2L, current.version(), "版本只应前进一次");
        assertEquals(6, current.hourlyBudgets().get(10));
    }
}
