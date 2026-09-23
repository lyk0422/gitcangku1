package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaResponse;
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
 * 多展示位真实多线程并发测试：跨展示位共享访客上限不超卖、展示位层不超卖、
 * 展示位新增 configVersion 竞争、配置新增与申请并发下已建预占不受影响。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlacementConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 日且预占保持 RESERVED。 */
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
        jdbc.update("DELETE FROM quota_placement_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_placement");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    /**
     * 同一访客在多个不同展示位上并发申请：访客上限跨展示位共享，
     * 成功数必须恰好等于访客上限；三层账目一致、不超卖、不为负。
     */
    @Test
    @DisplayName("同一访客跨不同展示位并发申请：共享访客上限不超卖")
    void sameVisitorConcurrentAcrossPlacements_visitorCapNotOversold() throws Exception {
        int visitorCap = 5;
        int placementCount = 10;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, visitorCap));
        for (int p = 1; p <= placementCount; p++) {
            service.createPlacement("cap",
                    new CreatePlacementRequest("req-p-" + p, "P" + p, 100_000, p));
        }

        int threads = 50;
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
                    String placement = "P" + (idx % placementCount + 1);
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest(
                                    "req-a-" + idx, "cap", placement, "visitor-shared"));
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

        assertEquals(visitorCap, success.get(), "跨展示位共享访客上限决定成功数");
        assertEquals(threads - visitorCap, rejected.get(), "其余请求必须为 429");
        assertEquals(visitorCap, reservationIds.size(), "预占单编号必须唯一");

        // 访客共享账恰好占用 visitorCap；各展示位账之和 = 成功数；总账 = 成功数
        QuotaResponse visitor = service.queryQuota("cap", "visitor-shared", null, DAY);
        assertEquals(visitorCap, visitor.usedVisitor());
        int sumPlacement = 0;
        for (int p = 1; p <= placementCount; p++) {
            sumPlacement += service.queryQuota("cap", null, "P" + p, DAY).usedPlacement();
        }
        assertEquals(visitorCap, sumPlacement, "各展示位占用之和必须等于成功数");
        assertEquals(visitorCap, service.queryQuota("cap", null, null, DAY).usedTotal());
    }

    /**
     * 多个不同访客并发冲击同一展示位：该展示位容量与公告总容量同时生效，
     * 成功数恰好等于较小容量；展示位账不超卖。
     */
    @Test
    @DisplayName("多访客并发冲击同一展示位：展示位层不超卖")
    void manyVisitorsConcurrentSinglePlacement_placementCapNotOversold() throws Exception {
        int placementCap = 8;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));
        service.createPlacement("cap",
                new CreatePlacementRequest("req-p1", "P1", placementCap, 1));

        int threads = 40;
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

        assertEquals(placementCap, success.get(), "展示位容量决定成功数");
        assertEquals(threads - placementCap, rejected.get());
        QuotaResponse quota = service.queryQuota("cap", null, "P1", DAY);
        assertEquals(placementCap, quota.usedPlacement());
        assertEquals(0, quota.remainingPlacement());
        assertEquals(placementCap, quota.usedTotal());
    }

    /**
     * 并发新增不同 code 的展示位，全部基于同一 expectedConfigVersion：
     * 按事务提交顺序恰好一个成功，其余因版本推进返回 409；最终只新增一个展示位，
     * 版本号严格 +1，无空洞无重复。
     */
    @Test
    @DisplayName("并发新增展示位（相同 expectedConfigVersion）：按提交顺序仅一个成功")
    void concurrentCreatePlacements_oneWinsVersionRace() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger versionConflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    PlacementResponse created = service.createPlacement("cap",
                            new CreatePlacementRequest(
                                    "req-p-" + idx, "P" + idx, 1, 1));
                    success.incrementAndGet();
                    assertEquals(2, created.configVersion());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        versionConflict.incrementAndGet();
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

        assertEquals(1, success.get(), "同一期望版本下只允许一个展示位落库");
        assertEquals(threads - 1, versionConflict.get(), "其余必须为 409");
        List<PlacementResponse> placements = service.listPlacements("cap");
        assertEquals(2, placements.size(), "DEFAULT + 一个新展示位");
        assertEquals(2, placements.get(placements.size() - 1).configVersion());
    }

    /**
     * 展示位新增与对 DEFAULT 的申请并发进行：无论提交顺序如何，
     * 已建预占固定在 DEFAULT，配置推进不影响其状态与三层账目；
     * 最终 DEFAULT 用量等于成功申请数。
     */
    @Test
    @DisplayName("新增展示位与 DEFAULT 申请并发：已建预占不受配置变更影响")
    void concurrentPlacementCreateAndApply_reservationsUnaffected() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));

        int applyThreads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applySuccess = new AtomicInteger();
        AtomicInteger configSuccess = new AtomicInteger();
        AtomicInteger configConflict = new AtomicInteger();

        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new com.example.starter.exposure.web.ApplyExposureRequest(
                                    "req-a-" + idx, "cap", "visitor-" + idx));
                    if ("DEFAULT".equals(r.placementCode())) {
                        applySuccess.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 两个基于版本 1 的并发配置新增：至多一个成功
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.createPlacement("cap",
                            new CreatePlacementRequest(
                                    "req-cfg-" + idx, "NEW" + idx, 1, 1));
                    configSuccess.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        configConflict.incrementAndGet();
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

        assertEquals(applyThreads, applySuccess.get(), "全部申请固定落在 DEFAULT");
        assertEquals(2, configSuccess.get() + configConflict.get(), "两个配置竞争都应有确定结果");
        assertTrue(configSuccess.get() <= 1, "配置版本不得重复递增");

        List<PlacementResponse> placements = service.listPlacements("cap");
        int finalVersion = placements.get(placements.size() - 1).configVersion();
        assertTrue(finalVersion == 1 || finalVersion == 2,
                "配置版本只可能停留在 1 或推进到 2");
        assertEquals(applyThreads, service.queryQuota("cap", null, "DEFAULT", DAY).usedPlacement(),
                "DEFAULT 预占与账目不受新增展示位影响");
        assertEquals(applyThreads, service.queryQuota("cap", null, null, DAY).usedTotal());
    }
}
