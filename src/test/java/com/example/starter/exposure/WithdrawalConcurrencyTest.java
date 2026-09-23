package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SettleWithdrawalRequest.ReservationVersionKey;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalResponse;
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
 * 撤回结算真实多线程并发测试：回执/到期释放/结算竞争下每个预占只进入一个终态，
 * 公告/访客计数守恒且不因重放重复变化。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:exposure_withdrawal_conc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class WithdrawalConcurrencyTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
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
    static final long BASE_MS = BASE.toEpochMilli();
    static final long TTL = 60_000L;
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
        jdbc.update("DELETE FROM exposure_receipt");
        jdbc.update("DELETE FROM withdrawal_item");
        jdbc.update("DELETE FROM withdrawal");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private ReservationResponse setupSnapshotReservation(String campaignId, String visitorId) {
        service.createCampaign(new CreateCampaignRequest("req-c", campaignId, 100, 100));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", campaignId, visitorId));
        service.withdraw(new WithdrawCampaignRequest("req-w", "w1", campaignId, 1, BASE_MS + 30_000L));
        return r;
    }

    private int usedTotal(String campaignId) {
        Integer value = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = ? AND utc_date = ?",
                Integer.class, campaignId, java.sql.Date.valueOf(DAY));
        return value == null ? 0 : value;
    }

    private int usedVisitor(String campaignId, String visitorId) {
        Integer value = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                Integer.class, campaignId, visitorId, java.sql.Date.valueOf(DAY));
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("并发不同回执键决议同一预占：只有一个决议成功，其余 409，额度恰好释放/保留一次")
    void concurrentReceipts_singleDecision() throws Exception {
        ReservationResponse r = setupSnapshotReservation("cap", "v1");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReceiptResponse response = service.submitReceipt(new ReceiptRequest(
                            "req-rc-" + idx, "rk-" + idx, r.reservationId(), BASE_MS + 1_000L));
                    if (response.decision().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现非 409 异常");
        assertEquals(1, confirmed.get(), "同一预占只能被一个回执确认");
        assertEquals(threads - 1, conflicts.get(), "其余回执必须 409");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM exposure_receipt", Integer.class),
                "只应记录一条回执");
        assertEquals("CONFIRMED", service.getReservation(r.reservationId()).status().name());
        assertEquals(1, usedTotal("cap"), "确认后总额度保持恰好一次占用");
        assertEquals(1, usedVisitor("cap", "v1"));
        assertEquals("COMPLETED", service.getWithdrawal("w1").status().name());
    }

    @Test
    @DisplayName("并发相同回执键同参：决议只执行一次，全部重放同一结果")
    void concurrentSameReceiptKey_replaysDecision() throws Exception {
        ReservationResponse r = setupSnapshotReservation("cap", "v1");

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
                    ReceiptResponse response = service.submitReceipt(new ReceiptRequest(
                            "req-rc-" + idx, "rk-same", r.reservationId(), BASE_MS + 1_000L));
                    if (response.decision().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "同键同参并发不应报错");
        assertEquals(threads, confirmed.get(), "全部重放同一确认决议");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM exposure_receipt", Integer.class));
        assertEquals(1, usedTotal("cap"), "决议只执行一次，额度不重复变化");
        assertEquals(1, usedVisitor("cap", "v1"));
    }

    @Test
    @DisplayName("回执与到期释放并发：每个预占只进入一个终态，额度恰好释放一次")
    void concurrentReceiptAndExpiry_singleTerminal() throws Exception {
        ReservationResponse r = setupSnapshotReservation("cap", "v1");
        // 推进到到期时刻：回执（将判 REJECTED）与到期释放（EXPIRED）竞争
        mutableClock().setInstant(BASE.plusMillis(TTL));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger receiptRejected = new AtomicInteger();
        AtomicInteger receiptConflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final boolean receipt = idx % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (receipt) {
                        try {
                            ReceiptResponse response = service.submitReceipt(new ReceiptRequest(
                                    "req-rc-" + idx, "rk-" + idx, r.reservationId(), BASE_MS + 10_000L));
                            if (response.decision().name().equals("REJECTED")) {
                                receiptRejected.incrementAndGet();
                            }
                        } catch (ApiException ex) {
                            if (ex.getStatus().value() == 409) {
                                receiptConflicts.incrementAndGet();
                            } else {
                                errors.incrementAndGet();
                            }
                        }
                    } else {
                        // 触发到期结算的只读入口
                        service.getReservation(r.reservationId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现非 409 异常");
        String finalStatus = service.getReservation(r.reservationId()).status().name();
        assertTrue(finalStatus.equals("REJECTED") || finalStatus.equals("EXPIRED"),
                "终态只能是 REJECTED 或 EXPIRED，实际 " + finalStatus);
        // 无论哪个方向胜出：额度恰好释放一次，不变负、不重复释放
        assertEquals(0, usedTotal("cap"));
        assertEquals(0, usedVisitor("cap", "v1"));
        // 快照项终态与预占终态一致，撤回收口
        WithdrawalResponse view = service.getWithdrawal("w1");
        assertEquals("COMPLETED", view.status().name());
        assertEquals(finalStatus, view.items().get(0).status().name());
        if (finalStatus.equals("REJECTED")) {
            assertEquals(1, receiptRejected.get(), "REJECTED 胜出时恰好一个回执生效");
        }
    }

    @Test
    @DisplayName("并发显式结算：释放只执行一次，计数守恒，最终 COMPLETED")
    void concurrentSettles_singleRelease() throws Exception {
        ReservationResponse r = setupSnapshotReservation("cap", "v1");
        List<ReservationVersionKey> fullSet =
                List.of(new ReservationVersionKey(r.reservationId(), 1));
        // 推进到到期时刻：未回执项已无法合法确认，结算可释放
        mutableClock().setInstant(BASE.plusMillis(TTL));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    WithdrawalResponse response = service.settle("w1",
                            new SettleWithdrawalRequest("req-s-" + idx, fullSet));
                    if (response.status().name().equals("COMPLETED")) {
                        completed.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现非 409 异常");
        assertTrue(completed.get() >= 1, "至少一个结算成功收口");
        assertEquals("COMPLETED", service.getWithdrawal("w1").status().name());
        assertEquals("EXPIRED", service.getReservation(r.reservationId()).status().name());
        // 释放只执行一次：账目为 0 而非负数
        assertEquals(0, usedTotal("cap"));
        assertEquals(0, usedVisitor("cap", "v1"));
    }

    @Test
    @DisplayName("撤回与申请并发：公告行串行化，成功申请全部进入快照，新预占被原子禁止")
    void concurrentWithdrawAndApply_snapshotConsistent() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int applies = 20;
        ExecutorService pool = Executors.newFixedThreadPool(applies + 1);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> successIds = ConcurrentHashMap.newKeySet();
        AtomicInteger banned = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < applies; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-" + idx));
                    successIds.add(r.reservationId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        banned.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                service.withdraw(new WithdrawCampaignRequest(
                        "req-w", "w1", "cap", 1, BASE_MS + 30_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "申请只应成功或被撤回禁止(409)");
        WithdrawalResponse view = service.getWithdrawal("w1");
        Set<String> snapshotIds = ConcurrentHashMap.newKeySet();
        view.items().forEach(item -> snapshotIds.add(item.reservationId()));
        assertEquals(successIds, snapshotIds,
                "撤回前成功的申请必须全部冻结进快照，撤回后的申请必须被禁止");
        assertEquals(successIds.size(), usedTotal("cap"), "总额度占用数必须等于成功申请数");
    }
}
