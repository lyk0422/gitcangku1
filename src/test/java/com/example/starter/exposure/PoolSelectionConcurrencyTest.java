package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.CreatePoolRequest.PoolMemberRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.PoolResponse.PoolMemberView;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.SelectExposureRequest;
import com.example.starter.exposure.web.SelectExposureResponse;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺额轮转池真实多线程并发测试：同池同时只占一轮、缺额只提交一次、
 * 同键重放只执行一次，均不超卖。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_pool_ct;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PoolSelectionConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一 UTC 日，预占均保持 RESERVED。 */
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
        jdbc.update("DELETE FROM rotation_pool_pending_deficit");
        jdbc.update("DELETE FROM rotation_pool_member");
        jdbc.update("DELETE FROM rotation_pool");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createPoolWithTwoMembers() {
        service.createCampaign(new CreateCampaignRequest("req-ca", "A", 100, 100));
        service.createCampaign(new CreateCampaignRequest("req-cb", "B", 100, 100));
        service.createPool(new CreatePoolRequest("req-p", "tester", "pool", List.of(
                new PoolMemberRequest("A", 2), new PoolMemberRequest("B", 1))));
    }

    private Map<String, Long> committedDeficits(String poolId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (PoolMemberView member : service.getPool(poolId).members()) {
            result.put(member.campaignId(), member.committedDeficit());
        }
        return result;
    }

    @Test
    @DisplayName("并发选择同一池：只有一个胜出，其余 409，同池不同时占两轮")
    void concurrentSelect_singleWinnerOthersConflict() throws Exception {
        createPoolWithTwoMembers();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    SelectExposureResponse r = service.selectFromPool("pool",
                            new SelectExposureRequest("req-s-" + idx, "tester", "v" + idx, 1));
                    success.incrementAndGet();
                    reservationIds.add(r.reservation().reservationId());
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

        assertEquals(1, success.get(), "同池同时只允许一轮选择胜出");
        assertEquals(threads - 1, conflicts.get(), "其余并发选择必须为 409");
        assertEquals(1, reservationIds.size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE pool_id = 'pool' AND status = 'RESERVED'",
                Integer.class));
        PoolResponse state = service.getPool("pool");
        assertNotNull(state.pendingReservationId());
        // 胜出者恰好占用一个额度，不超卖
        int usedTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger", Integer.class);
        assertEquals(1, usedTotal);
    }

    @Test
    @DisplayName("并发确认同一池预占：缺额恰好提交一次")
    void concurrentConfirm_commitsDeficitsExactlyOnce() throws Exception {
        createPoolWithTwoMembers();
        SelectExposureResponse selected = service.selectFromPool("pool",
                new SelectExposureRequest("req-s", "tester", "v1", 1));
        assertEquals("A", selected.campaignId());
        String reservationId = selected.reservation().reservationId();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.confirm(reservationId, new ReservationActionRequest("req-k-" + idx));
                    confirmed.incrementAndGet();
                } catch (ApiException ex) {
                    errors.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(threads, confirmed.get(), "重复确认均返回原终态");
        assertEquals(0, errors.get());
        // 缺额恰好提交一次：A=-1、B=1
        assertEquals(Map.of("A", -1L, "B", 1L), committedDeficits("pool"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_pool_pending_deficit WHERE pool_id = 'pool'",
                Integer.class));
    }

    @Test
    @DisplayName("并发取消同一池预占：临时缺额丢弃、额度恰好释放一次")
    void concurrentCancel_discardsPendingAndReleasesOnce() throws Exception {
        createPoolWithTwoMembers();
        SelectExposureResponse selected = service.selectFromPool("pool",
                new SelectExposureRequest("req-s", "tester", "v1", 1));
        String reservationId = selected.reservation().reservationId();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.cancel(reservationId, new ReservationActionRequest("req-x-" + idx));
                    cancelled.incrementAndGet();
                } catch (ApiException ex) {
                    errors.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(threads, cancelled.get(), "重复取消均返回原终态");
        assertEquals(0, errors.get());
        // 已提交缺额不变，临时缺额已丢弃，额度恰好释放一次
        assertEquals(Map.of("A", 0L, "B", 0L), committedDeficits("pool"));
        int usedTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger", Integer.class);
        assertEquals(0, usedTotal);

        // 取消后可立即重选同一位置
        SelectExposureResponse again = service.selectFromPool("pool",
                new SelectExposureRequest("req-s2", "tester", "v2", 1));
        assertEquals("A", again.campaignId());
    }

    @Test
    @DisplayName("同一 requestId 并发选择：业务只执行一次，响应一致")
    void concurrentSameRequestId_selectExecutesOnce() throws Exception {
        createPoolWithTwoMembers();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SelectExposureResponse r = service.selectFromPool("pool",
                            new SelectExposureRequest("same-key", "tester", "v1", 1));
                    reservationIds.add(r.reservation().reservationId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, reservationIds.size(), "选择只执行一次");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE pool_id = 'pool'",
                Integer.class));
        int usedTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger", Integer.class);
        assertEquals(1, usedTotal);
    }
}
