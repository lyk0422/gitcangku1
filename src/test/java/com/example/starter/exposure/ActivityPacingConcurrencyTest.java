package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityHourDiagnosticsResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateCampaignActivityRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放并发测试：真实多线程跨公告申请，验证 H2 行锁下小时预算不超卖。
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
    static final int CURRENT_HOUR = 10;

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
        jdbc.update("DELETE FROM activity_hour_budget");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private static List<Integer> budgets(int hour, int value) {
        List<Integer> budgets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            budgets.add(i == hour ? value : 0);
        }
        return budgets;
    }

    @Test
    @DisplayName("并发跨公告申请：成功数恰好等于小时预算，活动小时账目不超卖")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 20;
        int threads = 100;
        service.createActivity(new CreateActivityRequest("req-act", "act1", hourBudget,
                budgets(CURRENT_HOUR, hourBudget)));
        // 两个公告共享同一活动；公告侧额度足够大，瓶颈只在活动小时预算
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 1000, 1000));
        service.createCampaign(new CreateCampaignRequest("req-c2", "c2", 1000, 1000));
        service.migrateCampaign("c1", new MigrateCampaignActivityRequest("req-m1", "act1"));
        service.migrateCampaign("c2", new MigrateCampaignActivityRequest("req-m2", "act1"));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final String campaignId = idx % 2 == 0 ? "c1" : "c2";
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, campaignId, "visitor-" + idx));
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

        assertEquals(hourBudget, success.get(), "成功预占数必须等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");
        assertEquals(hourBudget, reservationIds.size(), "预占单编号必须唯一");

        Integer used = jdbc.queryForObject(
                "SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND hour_of_day = ?",
                Integer.class, java.sql.Date.valueOf(DAY), CURRENT_HOUR);
        assertEquals(hourBudget, used, "活动小时账目占用必须恰好等于预算");

        ActivityHourDiagnosticsResponse diag = service.getHourDiagnostics("act1", DAY);
        assertEquals(hourBudget, diag.hours().get(CURRENT_HOUR).activeReserved());
    }
}
