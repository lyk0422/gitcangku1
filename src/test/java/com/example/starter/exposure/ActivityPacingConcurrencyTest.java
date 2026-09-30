package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySnapshotResponse;
import com.example.starter.exposure.web.HourDiagnosticResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;
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
 * 活动匀速投放真实多线程并发测试（H2 行锁）：跨公告不超卖小时预算、
 * 并发配置修改按版本裁决、并发日结固化结果一致且只固化一份。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityPacingConcurrencyTest {

    /** 可控时钟：默认固定在同一 UTC 小时；日结用例推进到次日 00:01 之后。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
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
        jdbc.update("DELETE FROM quota_activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_daily_hour_snapshot");
        jdbc.update("DELETE FROM activity_hour_budget_freeze");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private List<Integer> budgetHour10Only(int total) {
        List<Integer> budgets = new ArrayList<>(Collections.nCopies(24, 0));
        budgets.set(10, total);
        return budgets;
    }

    private List<Integer> budgetOf(int total, int... hourValuePairs) {
        List<Integer> budgets = new ArrayList<>(Collections.nCopies(24, 0));
        int sum = 0;
        for (int i = 0; i < hourValuePairs.length; i += 2) {
            budgets.set(hourValuePairs[i], hourValuePairs[i + 1]);
            sum += hourValuePairs[i + 1];
        }
        assertEquals(total, sum);
        return budgets;
    }

    @Test
    @DisplayName("跨公告并发申请：活动小时预算不超卖，成功数恰好等于小时预算，账本精确")
    void concurrentApplyAcrossCampaigns_doesNotOversellHourBudget() throws Exception {
        int hourBudget = 30;
        int campaigns = 5;
        int threadsPerCampaign = 20;
        int threads = campaigns * threadsPerCampaign;

        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", hourBudget, budgetHour10Only(hourBudget)));
        for (int c = 0; c < campaigns; c++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c-" + c, "camp" + c, 100_000, 100_000, "act1"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    String campaignId = "camp" + (idx % campaigns);
                    ReservationResponse r = service.apply(new ApplyExposureRequest(
                            "req-a-" + idx, campaignId, "visitor-" + idx));
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

        assertEquals(hourBudget, success.get(), "成功数必须恰好等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余请求必须为 429");
        assertEquals(hourBudget, reservationIds.size(), "预占单必须唯一");

        HourDiagnosticResponse diag = service.queryHourDiagnostic("act1", DAY);
        assertEquals(hourBudget, diag.hours().get(10).used(), "活动小时占用必须精确等于预算");
        assertEquals(0, diag.hours().get(10).remaining());

        // 三侧合计一致：活动小时占用 == 各公告总账占用之和 == 预占单数
        Integer totalSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger WHERE utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, totalSum);
        Integer reservationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE status = 'RESERVED'", Integer.class);
        assertEquals(hourBudget, reservationCount);
    }

    @Test
    @DisplayName("并发修改同一活动配置：只有携带正确 expectedVersion 的一方成功，其余 409，版本只 +1")
    void concurrentUpdate_onlyOneWinsByVersion() throws Exception {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 3, budgetOf(3, 10, 1, 11, 1, 12, 1)));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 全部基于初始版本 0 修改：仅一方成功
                    service.updateActivity("act1", new UpdateActivityRequest(
                            "req-up-" + idx, 3, budgetOf(3, 10, 1, 11, 1, 12, 1), 0L));
                    ok.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflict.incrementAndGet();
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "只有一方修改成功");
        assertEquals(threads - 1, conflict.get(), "其余并发修改必须为 409");
        assertEquals(1, service.getActivity("act1").version(), "版本号只增加一次");
    }

    @Test
    @DisplayName("并发日结固化：日终后并发触发只固化一份，24 行计数一致且后续读不改写")
    void concurrentDailySnapshot_singleConsistentFreeze() throws Exception {
        service.createActivity(new CreateActivityRequest(
                "req-act", "act1", 2, budgetHour10Only(2)));
        service.createCampaign(new CreateCampaignRequest("req-c", "c1", 100, 100, "act1"));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        // 推进到次日 00:01 UTC：当日预占必然已到期，允许固化
        mutableClock().setInstant(Instant.parse("2026-09-23T00:01:00Z"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Boolean> frozenFlags = Collections.synchronizedSet(new HashSet<>());
        List<DailySnapshotResponse> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    DailySnapshotResponse resp = service.queryDailySnapshot("act1", DAY);
                    frozenFlags.add(resp.frozen());
                    responses.add(resp);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(Set.of(true), frozenFlags, "日终后所有并发查询都必须读到固化快照");
        for (DailySnapshotResponse resp : responses) {
            assertTrue(resp.frozen());
            assertEquals(24, resp.hours().size());
            assertEquals(1, resp.hours().get(10).confirmedCount());
            assertEquals(0, resp.hours().get(10).cancelledCount());
        }
        // 恰好 24 行，唯一一份
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_daily_hour_snapshot WHERE activity_id = 'act1'",
                Integer.class);
        assertEquals(24, rows);
    }
}
