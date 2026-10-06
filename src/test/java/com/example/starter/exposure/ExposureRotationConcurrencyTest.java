package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyRotationRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创意轮换真实多线程并发测试：活跃轮换唯一、序号只推进一次、多访客各自独立轮换。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExposureRotationConcurrencyTest {

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
        jdbc.update("DELETE FROM rotation_state");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_creative");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createRotationCampaign(String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(
                "req-c-" + campaignId, campaignId, total, perVisitor, List.of("A", "B")));
    }

    private int confirmedCount(String campaignId, String visitorId) {
        Integer value = jdbc.query("SELECT confirmed_count FROM rotation_state "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, visitorId);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("同访客并发轮换申请：恰好一条活跃预占，其余 409，额度只占用一次")
    void concurrentRotationApply_sameVisitor_singleActiveReservation() throws Exception {
        createRotationCampaign("rc1", 100, 100);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> reservationIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.applyRotation(
                            new ApplyRotationRequest("req-r-" + idx, "rc1", "v1"));
                    assertEquals("A", r.creativeId());
                    assertEquals(0, r.rotationSeq());
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
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

        assertEquals(1, success.get(), "同访客至多一条活跃轮换预占");
        assertEquals(threads - 1, conflicts.get(), "其余并发轮换申请必须为 409");
        assertEquals(1, reservationIds.size());
        Integer usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'rc1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(1, usedTotal, "额度只被占用一次");
        assertEquals(0, confirmedCount("rc1", "v1"), "未确认不推进序号");
    }

    @Test
    @DisplayName("并发确认同一轮换预占：序号只推进一次，下一申请选下一创意")
    void concurrentConfirm_rotationSequenceAdvancesOnce() throws Exception {
        createRotationCampaign("rc2", 100, 100);
        ReservationResponse r = service.applyRotation(new ApplyRotationRequest("req-r", "rc2", "v1"));
        assertEquals("A", r.creativeId());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse result = service.confirm(r.reservationId(),
                            new ReservationActionRequest("req-k-" + idx));
                    if (result.status().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    }
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

        assertEquals(0, errors.get(), "重复同向确认应返回原状态而非报错");
        assertEquals(threads, confirmed.get());
        assertEquals(1, confirmedCount("rc2", "v1"), "并发确认只推进一次轮换序号");

        // 序号推进一次：下一轮换申请选 B、序号 1
        ReservationResponse next = service.applyRotation(new ApplyRotationRequest("req-r2", "rc2", "v1"));
        assertEquals("B", next.creativeId());
        assertEquals(1, next.rotationSeq());
    }

    @Test
    @DisplayName("多访客并发轮换申请：各自独立从序号 0 的 A 开始，互不影响")
    void concurrentRotationApply_multipleVisitors_independentSequences() throws Exception {
        createRotationCampaign("rc3", 100, 100);
        int visitors = 12;
        ExecutorService pool = Executors.newFixedThreadPool(visitors);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> creatives = ConcurrentHashMap.newKeySet();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < visitors; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.applyRotation(
                            new ApplyRotationRequest("req-r-" + idx, "rc3", "visitor-" + idx));
                    assertEquals(0, r.rotationSeq());
                    creatives.add(r.creativeId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get());
        assertEquals(Set.of("A"), creatives, "所有访客首次轮换都应选 A");
        Integer usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'rc3' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(visitors, usedTotal, "每个访客各占用一次总额度");
    }
}
