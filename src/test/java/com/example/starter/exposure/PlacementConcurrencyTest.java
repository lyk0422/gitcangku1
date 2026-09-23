package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.ReservationResponse;
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
 * 多展示位真实多线程并发测试：跨展示位共享访客上限不超卖、展示位层不超卖、
 * 新增展示位与申请并发按 configVersion/提交顺序串行、同版本并发只成功一个。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlacementConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一 UTC 日，预占均保持 RESERVED（不触发到期）。 */
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

    static final Instant BASE = Instant.parse("2026-09-23T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 23);

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
        jdbc.update("DELETE FROM quota_placement_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM placement");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("同一访客在不同展示位并发申请：成功数恰好等于共享访客上限，三层不超卖")
    void sameVisitorConcurrentAcrossPlacements_doesNotOversellVisitorCap() throws Exception {
        int visitorCap = 10;
        int threads = 40;
        String[] placements = {"P1", "P2", "P3", "P4"};
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, visitorCap));
        for (int i = 0; i < placements.length; i++) {
            service.createPlacement("cap", new CreatePlacementRequest(
                    "req-p-" + i, placements[i], 100_000, 1 + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final String placementCode = placements[i % placements.length];
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest(
                                    "req-a-" + idx, "cap", placementCode, "visitor-shared"));
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

        assertEquals(visitorCap, success.get(), "成功数必须等于共享访客上限");
        assertEquals(threads - visitorCap, rejected.get(), "其余请求必须为 429");
        assertEquals(visitorCap, reservationIds.size(), "预占单必须唯一");

        int usedVisitor = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'visitor-shared' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedPlacementSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_placement), 0) FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(visitorCap, usedVisitor, "访客共享账不得超卖");
        assertEquals(visitorCap, usedTotal, "总账应等于成功数");
        assertEquals(visitorCap, usedPlacementSum, "各展示位账之和应等于成功数");
    }

    @Test
    @DisplayName("并发冲击单个展示位：成功数恰好等于该展示位额度，展示位账不超卖")
    void concurrentApplyOnSinglePlacement_doesNotOversellPlacementCap() throws Exception {
        int placementCap = 5;
        int threads = 50;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));
        service.createPlacement("cap",
                new CreatePlacementRequest("req-p1", "P1", placementCap, 1));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.applyPlacement(new ApplyPlacementExposureRequest(
                            "req-a-" + idx, "cap", "P1", "visitor-" + idx));
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(placementCap, success.get());
        assertEquals(threads - placementCap, rejected.get());
        int usedPlacement = jdbc.queryForObject(
                "SELECT used_placement FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'cap' AND placement_code = 'P1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(placementCap, usedPlacement);
    }

    @Test
    @DisplayName("同一 expectedConfigVersion 并发新增展示位：只有一个成功，其余 409，版本只递增一次")
    void concurrentCreatePlacement_sameExpectedVersion_onlyOneWins() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 10));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> createdCodes = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    PlacementResponse response = service.createPlacement("cap",
                            new CreatePlacementRequest(
                                    "req-p-" + idx, "P" + idx, 10, 1));
                    success.incrementAndGet();
                    createdCodes.add(response.placementCode());
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "同一版本只允许一个展示位创建成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为版本冲突 409");
        assertEquals(2, service.listPlacements("cap").size(), "DEFAULT + 一个新展示位");
        Integer configVersion = jdbc.queryForObject(
                "SELECT config_version FROM campaign WHERE campaign_id = 'cap'", Integer.class);
        assertEquals(2, configVersion, "配置版本只递增一次");
    }

    @Test
    @DisplayName("新增展示位与 DEFAULT 申请并发：申请不超卖、配置版本正确、旧预占不受影响")
    void createPlacementConcurrentWithApply_serializedAndConsistent() throws Exception {
        int totalCap = 20;
        int applyThreads = 30;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", totalCap, 100_000));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger placementCreated = new AtomicInteger();

        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-" + idx));
                    assertEquals("DEFAULT", r.placementCode());
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
        pool.submit(() -> {
            try {
                start.await();
                PlacementResponse p = service.createPlacement("cap",
                        new CreatePlacementRequest("req-p1", "P1", 10, 1));
                if (p.placementCode().equals("P1")) {
                    placementCreated.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(totalCap, success.get(), "DEFAULT 申请成功数必须等于公告总额度");
        assertEquals(applyThreads - totalCap, rejected.get());
        assertEquals(1, placementCreated.get());
        assertEquals(2, service.listPlacements("cap").size());

        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedDefault = jdbc.queryForObject(
                "SELECT used_placement FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'cap' AND placement_code = 'DEFAULT' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedP1 = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_placement), 0) FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'cap' AND placement_code = 'P1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(totalCap, usedTotal);
        assertEquals(totalCap, usedDefault, "旧预占全部固定在 DEFAULT");
        assertEquals(0, usedP1, "新增展示位不影响已有预占");
    }
}
