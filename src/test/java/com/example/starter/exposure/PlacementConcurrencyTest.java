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
 * 多展示位真实多线程并发测试：跨展示位访客共享上限不超卖、展示位账目不变负、
 * 新增展示位按 configVersion 与事务提交顺序串行处理。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlacementConcurrencyTest {

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
        jdbc.update("DELETE FROM placement");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("同一访客跨不同展示位并发申请：成功数恰好等于访客共享上限，三层不超卖")
    void sameVisitorConcurrentAcrossPlacements_doesNotOversellVisitorCap() throws Exception {
        int visitorCap = 10;
        int placementCount = 4;
        // 总额度与各展示位额度都给足，唯一瓶颈是访客共享上限
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, visitorCap));
        for (int p = 0; p < placementCount; p++) {
            service.createPlacement("cap",
                    new CreatePlacementRequest("req-p" + p, "PL" + p, 100_000, p + 1));
        }

        int threads = 80;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String placement = "PL" + (idx % placementCount);
                    ReservationResponse r = service.applyPlacement(
                            new ApplyPlacementExposureRequest("req-a-" + idx, "cap", placement, "visitor-x"));
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

        assertEquals(visitorCap, success.get(), "跨展示位成功数必须等于访客共享上限");
        assertEquals(threads - visitorCap, rejected.get(), "其余请求必须为 429");
        assertEquals(visitorCap, reservationIds.size(), "预占单编号必须唯一");

        // 访客共享账恰好为上限
        QuotaResponse visitorQuota = service.queryQuota("cap", "visitor-x", null, DAY);
        assertEquals(visitorCap, visitorQuota.usedVisitor());
        assertEquals(0, visitorQuota.remainingVisitor());

        // 各展示位账目之和等于访客占用（该访客是唯一访客），且无账目为负
        int sumPlacement = 0;
        for (int p = 0; p < placementCount; p++) {
            int used = jdbc.queryForObject(
                    "SELECT used_placement FROM quota_placement_ledger "
                            + "WHERE campaign_id = 'cap' AND placement_code = ? AND utc_date = ?",
                    Integer.class, "PL" + p, java.sql.Date.valueOf(DAY));
            assertTrue(used >= 0, "展示位账目不得为负");
            sumPlacement += used;
        }
        assertEquals(visitorCap, sumPlacement, "各展示位占用之和应等于访客共享占用");
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(visitorCap, usedTotal, "公告总账占用应等于访客共享占用");
    }

    @Test
    @DisplayName("展示位额度并发：多访客争抢同一小额度展示位，成功数恰好等于展示位额度")
    void placementQuotaConcurrent_exactlyPlacementCap() throws Exception {
        int placementCap = 5;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));
        service.createPlacement("cap", new CreatePlacementRequest("req-p", "P", placementCap, 1));

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.applyPlacement(new ApplyPlacementExposureRequest(
                            "req-a-" + idx, "cap", "P", "visitor-" + idx));
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
        assertEquals(placementCap, service.queryQuota("cap", null, "P", DAY).usedPlacement());
    }

    @Test
    @DisplayName("并发新增展示位：configVersion 串行递增，唯一 code 各创建一次，最终数量与版本连续")
    void concurrentCreatePlacement_serializedByConfigVersion() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100_000, 100_000));

        // 每个线程创建一个唯一 code；并发下以“读取当前版本 -> 提交”有界重试，
        // 版本过期得到 409 后重读最新版本再试，最终每个 code 恰好创建一次。
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Set<Integer> versions = Collections.synchronizedSet(new HashSet<>());

        for (int i = 1; i <= threads; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 业务 409 回滚不占幂等键，故同一 requestId 可以携带更新后的版本重试
                    for (int attempt = 0; attempt < 30; attempt++) {
                        int current = service.listPlacements("cap").stream()
                                .mapToInt(PlacementResponse::configVersion).max().orElse(1);
                        try {
                            PlacementResponse resp = service.createPlacement("cap",
                                    new CreatePlacementRequest("req-p-" + seq, "P" + seq, 10, current));
                            created.incrementAndGet();
                            versions.add(resp.configVersion());
                            return;
                        } catch (ApiException ex) {
                            if (ex.getStatus().value() != 409) {
                                throw ex;
                            }
                            // 版本被并发抢先：重读后重试
                        }
                    }
                    failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, failures.get(), "有界重试后不应有最终失败");
        assertEquals(threads, created.get(), "8 个唯一展示位各创建一次");
        // DEFAULT + 8 = 9 个展示位，版本集合为 2..9 连续不重复
        assertEquals(9, service.listPlacements("cap").size());
        assertEquals(threads, versions.size(), "每个展示位版本唯一");
        for (int v = 2; v <= 9; v++) {
            assertTrue(versions.contains(v), "版本 " + v + " 应存在");
        }
    }
}
