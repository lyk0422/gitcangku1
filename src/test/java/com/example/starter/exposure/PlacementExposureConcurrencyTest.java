package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.QuotaResponse;
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
 * 多展示位真实多线程并发测试：H2 行锁下三层额度不超卖、访客跨展示位共享上限生效、
 * 新增展示位与申请按事务提交顺序处理、同键申请只执行一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlacementExposureConcurrencyTest {

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
    @DisplayName("同一访客在不同展示位并发申请：跨位共享访客上限不超卖，三层账目一致")
    void concurrentApplyAcrossPlacements_sharedVisitorCapNotOversold() throws Exception {
        int visitorCap = 5;
        // 公告总额、展示位额度都足够大，唯一瓶颈是访客跨位共享上限
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, visitorCap));
        service.createPlacement("cap", new CreatePlacementRequest("req-p1", "P1", 100_000, 1));
        service.createPlacement("cap", new CreatePlacementRequest("req-p2", "P2", 100_000, 2));

        int threads = 40;
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
                    String placement = idx % 2 == 0 ? "P1" : "P2";
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest("req-a-" + idx, "cap", placement, "shared-v"));
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

        assertEquals(visitorCap, success.get(), "成功数必须恰好等于访客跨位共享上限");
        assertEquals(threads - visitorCap, rejected.get(), "其余请求必须为 429");
        assertEquals(visitorCap, reservationIds.size(), "预占单编号必须唯一");

        QuotaResponse v = service.queryQuota("cap", "shared-v", null, DAY);
        assertEquals(visitorCap, v.usedVisitor(), "访客共享账目恰好占满");
        assertEquals(0, v.remainingVisitor());
        assertEquals(visitorCap, v.usedTotal(), "公告总账与访客共享占用一致");
        // 两个展示位账目之和必须等于访客共享占用（每个预占同时占三层）
        int p1 = service.queryQuota("cap", null, "P1", DAY).usedPlacement();
        int p2 = service.queryQuota("cap", null, "P2", DAY).usedPlacement();
        assertEquals(visitorCap, p1 + p2, "展示位账目之和必须等于成功预占数");
    }

    @Test
    @DisplayName("同一展示位并发申请：展示位额度不超卖，其余 429")
    void concurrentApply_samePlacementCapNotOversold() throws Exception {
        int placementCap = 15;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));
        service.createPlacement("cap", new CreatePlacementRequest("req-p1", "P1", placementCap, 1));

        int threads = 60;
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(placementCap, success.get(), "成功数必须恰好等于展示位额度");
        assertEquals(threads - placementCap, rejected.get(), "其余请求必须为 429");
        assertEquals(placementCap, service.queryQuota("cap", null, "P1", DAY).usedPlacement());
    }

    @Test
    @DisplayName("并发新增展示位与 DEFAULT 申请：按事务提交顺序处理，无死锁，账目与版本正确")
    void concurrentCreatePlacementAndApply_serializedByCommitOrder() throws Exception {
        int totalCap = 30;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", totalCap, 100_000));

        int appliers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(appliers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applySuccess = new AtomicInteger();
        AtomicInteger applyErrors = new AtomicInteger();

        // 与申请并发新增展示位（expectedConfigVersion=1）
        pool.submit(() -> {
            try {
                start.await();
                service.createPlacement("cap",
                        new CreatePlacementRequest("req-p1", "P1", 5, 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        for (int i = 0; i < appliers; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest("req-a-" + idx, "cap", "DEFAULT",
                                    "visitor-" + idx));
                    if ("DEFAULT".equals(r.placementCode())) {
                        applySuccess.incrementAndGet();
                    }
                } catch (Exception e) {
                    applyErrors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成（无死锁）");

        assertEquals(appliers, applySuccess.get(), "全部 DEFAULT 申请成功");
        assertEquals(0, applyErrors.get(), "申请不应报错");
        // 展示位创建已提交，版本递增到 2
        assertEquals(2, service.queryQuota("cap", null, null, DAY).configVersion());
        // 新展示位 P1 不受并发申请影响：账目为 0，DEFAULT 账目为全部申请数
        assertEquals(0, service.queryQuota("cap", null, "P1", DAY).usedPlacement());
        assertEquals(appliers, service.queryQuota("cap", null, "DEFAULT", DAY).usedPlacement());
        assertEquals(appliers, service.queryQuota("cap", null, null, DAY).usedTotal());
    }

    @Test
    @DisplayName("并发以相同 expectedConfigVersion 新增不同展示位：仅一个成功，另一个版本冲突 409")
    void concurrentCreatePlacement_sameExpectedVersion_onlyOneWins() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final String code = "P" + (i + 1);
            final String reqId = "req-p" + (i + 1);
            pool.submit(() -> {
                try {
                    start.await();
                    service.createPlacement("cap",
                            new CreatePlacementRequest(reqId, code, 10, 1));
                    created.incrementAndGet();
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

        assertEquals(1, created.get(), "相同期望版本下只能新增一个展示位");
        assertEquals(1, conflicts.get(), "另一个必须返回版本冲突 409");
        assertEquals(2, service.queryQuota("cap", null, null, DAY).configVersion());

        // 失败方以最新版本重试：成功，版本继续递增；原失败 requestId 不占键
        var retry = service.createPlacement("cap",
                new CreatePlacementRequest("req-retry", "P3", 10, 2));
        assertEquals(3, retry.configVersion());
    }

    @Test
    @DisplayName("同一 requestId 并发按展示位申请：业务只执行一次，三层账目不重复占用")
    void concurrentSameRequestIdPlacementApply_executesOnce() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));
        service.createPlacement("cap", new CreatePlacementRequest("req-p1", "P1", 100, 1));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest("same-key", "cap", "P1", "visitor-x"));
                    reservationIds.add(r.reservationId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, reservationIds.size(), "业务只执行一次");
        assertEquals(1, service.queryQuota("cap", "visitor-x", "P1", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("cap", "visitor-x", null, DAY).usedVisitor());
        assertEquals(1, service.queryQuota("cap", null, null, DAY).usedTotal());
    }
}
