package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivityDayConfigRequest;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignCampaignActivityRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放并发测试：真实多线程 + H2 行锁，验证跨公告并发申请不超卖
 * 活动当前小时预算，且成功数、账目与预占单三者一致。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:exposure_cap_pacing_conc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CampaignPacingConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 小时（10 点），预占均保持 RESERVED。 */
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
        jdbc.update("DELETE FROM activity_hour_snapshot");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_activity_hour_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM activity_day_config");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM activity");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private List<Integer> hour10Budget(int budget) {
        Integer[] hours = new Integer[24];
        java.util.Arrays.fill(hours, 0);
        hours[10] = budget;
        return List.of(hours);
    }

    @Test
    @DisplayName("跨多个公告并发申请：活动当前小时成功数恰好等于小时预算，账目不超卖")
    void concurrentCrossCampaignApply_neverOversellsHourBudget() throws Exception {
        int hourBudget = 30;
        int campaignCount = 3;
        int threads = 120;

        service.createActivity(new CreateActivityRequest("req-act", "act1"));
        service.configureActivityDay("act1", DAY, new ActivityDayConfigRequest(
                "req-cfg", hourBudget, hour10Budget(hourBudget), null));
        for (int c = 0; c < campaignCount; c++) {
            String campaignId = "cmp" + c;
            service.createCampaign(new CreateCampaignRequest(
                    "req-c-" + c, campaignId, 100_000, 100_000));
            service.assignCampaignActivity(campaignId,
                    new AssignCampaignActivityRequest("req-assign-" + c, "act1"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String campaignId = "cmp" + (idx % campaignCount);
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, campaignId, "visitor-" + idx));
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
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
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, otherErrors.get(), "不应出现 429 以外的错误");
        assertEquals(hourBudget, success.get(), "活动小时成功预占数必须恰好等于小时预算");
        assertEquals(threads - hourBudget, rejected.get(), "其余申请必须全部 429");
        assertEquals(hourBudget, reservationIds.size(), "预占单编号必须唯一");

        int usedHour = jdbc.queryForObject(
                "SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND utc_hour = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(hourBudget, usedHour, "活动小时账目不得超卖");
        int reservedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE activity_id = 'act1' "
                        + "AND utc_hour = 10 AND status = 'RESERVED'", Integer.class);
        assertEquals(hourBudget, reservedCount, "成功预占必须全部落库且归属该活动小时");
    }

    @Test
    @DisplayName("并发日结首次执行：快照只固化一次，各线程返回一致结果，无 500")
    void concurrentSettlement_insertsSnapshotOnce() throws Exception {
        service.createActivity(new CreateActivityRequest("req-act", "act1"));
        Integer[] hours = new Integer[24];
        java.util.Arrays.fill(hours, 0);
        hours[10] = 3;
        service.configureActivityDay("act1", DAY, new ActivityDayConfigRequest(
                "req-cfg", 3, List.of(hours), null));
        service.createCampaign(new CreateCampaignRequest("req-c", "cmp1", 100, 100));
        service.assignCampaignActivity("cmp1",
                new AssignCampaignActivityRequest("req-assign", "act1"));
        service.apply(new ApplyExposureRequest("req-a1", "cmp1", "v1"));
        service.apply(new ApplyExposureRequest("req-a2", "cmp1", "v2"));
        // 仅 2 个 RESERVED 占名额；不确认/取消，日结计数应为全 0

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        Set<Integer> confirmedCounts = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var response = service.settleActivityDay("act1", DAY);
                    confirmedCounts.add(response.hours().get(10).confirmedCount());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "并发日结不应报错");
        assertEquals(Set.of(0), confirmedCounts);

        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_hour_snapshot "
                        + "WHERE activity_id = 'act1' AND utc_date = ? AND utc_hour = 10",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(1, snapshotRows, "每小时快照必须恰好一行");
        // 固化后再日结仍为冻结状态
        var again = service.settleActivityDay("act1", DAY);
        assertTrue(again.hours().get(10).frozen());
    }
}
