package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SettleWithdrawalRequest.SettleItem;
import com.example.starter.exposure.web.SnapshotItemResponse;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤回结算真实多线程并发测试：撤回与申请互斥、回执/结算/到期并发下
 * 每个预占只进入一个终态、公告与访客计数守恒。
 */
@SpringBootTest
@ActiveProfiles("test")
class WithdrawalConcurrencyTest {

    /** 固定时钟：所有预占落在同一 UTC 日且不到期，occurredAt 窗口由截点参数控制。 */
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
    static final long T0 = BASE.toEpochMilli();
    static final long CUTOFF = T0 + 30_000L;

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
        jdbc.update("DELETE FROM exposure_snapshot_item");
        jdbc.update("DELETE FROM exposure_withdrawal");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("撤回与申请并发：撤回提交后原子禁止新预占，成功申请全部进入快照，计数守恒")
    void concurrentWithdrawAndApply_atomicFreeze() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100_000));

        int applies = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < applies; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-" + idx));
                    applied.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        blocked.incrementAndGet();
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
                service.withdraw(new WithdrawCampaignRequest("req-w", "w1", "cap", 1, CUTOFF));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(applies, applied.get() + blocked.get(), "申请只能成功或因撤回被 409 拒绝");

        int reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'cap'", Integer.class);
        int settling = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'cap' AND status = 'SETTLING'",
                Integer.class);
        int snapshotItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_snapshot_item WHERE withdrawal_key = 'w1'", Integer.class);
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));

        assertEquals(applied.get(), reservations, "成功的申请都应留下预占单");
        assertEquals(applied.get(), settling, "成功申请的在途预占应全部被冻结为 SETTLING");
        assertEquals(applied.get(), snapshotItems, "每个被冻结预占对应一个快照项");
        assertEquals(applied.get(), usedTotal, "SETTLING 预占持续占用额度，计数守恒");
    }

    @Test
    @DisplayName("同一快照项并发合法/非法回执：只允许一个终态，额度恰好变化一次")
    void concurrentReceipts_sameItem_singleTerminal() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 10, 10));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));
        service.withdraw(new WithdrawCampaignRequest("req-w", "w1", "cap", 1, CUTOFF));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger decided = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            // 一半合法回执（occurredAt 在窗口内），一半非法（不早于截点）
            final long occurredAt = idx % 2 == 0 ? T0 + 10_000L : T0 + 50_000L;
            pool.submit(() -> {
                try {
                    start.await();
                    SnapshotItemResponse response = service.receipt(r.reservationId(),
                            new ReceiptRequest("req-rc-" + idx, "rk-" + idx, occurredAt));
                    assertTrue(response.decision().name().equals("CONFIRMED")
                            || response.decision().name().equals("REJECTED"));
                    decided.incrementAndGet();
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

        assertEquals(1, decided.get(), "同一预占只能有一个回执胜出");
        assertEquals(threads - 1, conflicts.get(), "其余回执必须 409");

        WithdrawalResponse w = service.getWithdrawal("w1");
        assertEquals("COMPLETED", w.status().name());
        SnapshotItemResponse item = w.items().get(0);
        String reservationStatus = service.getReservation(r.reservationId()).status().name();
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedVisitor = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'v1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        if (item.decision().name().equals("CONFIRMED")) {
            assertEquals("CONFIRMED", reservationStatus);
            assertEquals(1, usedTotal, "确认保持占用");
            assertEquals(1, usedVisitor, "确认保持占用");
        } else {
            assertEquals("REJECTED", reservationStatus);
            assertEquals(0, usedTotal, "驳回恰好释放一次");
            assertEquals(0, usedVisitor, "驳回恰好释放一次");
        }
    }

    @Test
    @DisplayName("回执与显式结算并发：每项只进入一个终态，最终全部 CONFIRMED 且计数守恒")
    void concurrentReceiptsAndSettle_eventualCompletion() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 10, 10));
        int items = 5;
        String[] reservationIds = new String[items];
        for (int i = 0; i < items; i++) {
            reservationIds[i] = service.apply(
                    new ApplyExposureRequest("req-a-" + i, "cap", "v" + i)).reservationId();
        }
        WithdrawalResponse w = service.withdraw(
                new WithdrawCampaignRequest("req-w", "w1", "cap", 1, CUTOFF));
        List<SettleItem> fullSet = w.items().stream()
                .map(i -> new SettleItem(i.reservationId(), i.campaignVersion()))
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger settleOk = new AtomicInteger();
        AtomicInteger settleConflict = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < items; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    SnapshotItemResponse response = service.receipt(reservationIds[idx],
                            new ReceiptRequest("req-rc-" + idx, "rk-" + idx, T0 + 10_000L));
                    if (response.decision().name().equals("CONFIRMED")) {
                        confirmed.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    unexpected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.settle("w1", new SettleWithdrawalRequest("req-s-" + idx, fullSet));
                    settleOk.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        settleConflict.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, unexpected.get(), "不应出现 409 结算冲突之外的异常");
        assertEquals(items, confirmed.get(), "固定时钟下合法回执必须全部确认，结算不得抢先驳回可确认项");

        // 全部回执确认后，结算必然完成（此前 409 的结算未占键，可重试）
        WithdrawalResponse settled = service.settle("w1",
                new SettleWithdrawalRequest("req-s-final", fullSet));
        assertEquals("COMPLETED", settled.status().name());
        assertTrue(settled.items().stream().allMatch(i -> i.decision().name().equals("CONFIRMED")));

        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(items, usedTotal, "全部确认后额度保持占用，计数守恒");
    }
}
