package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
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
 * 归因窗口真实多线程并发测试：同一预占的并发回执只结算一次，
 * 回执与取消竞争只允许一个终态，额度不重复释放/不变负。
 */
@SpringBootTest
@ActiveProfiles("test")
class AttributionWindowConcurrencyTest {

    /** 固定时钟：所有操作落在同一 UTC 日，预占不触发到期。 */
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
        jdbc.update("DELETE FROM exposure_receipt");
        jdbc.update("DELETE FROM campaign_window_history");
        jdbc.update("DELETE FROM idempotency_record");
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
    @DisplayName("同一预占并发窗口内回执：全部记录为 ATTRIBUTED，但只结算一次、额度保持 1")
    void concurrentReceipts_settleOnlyOnce() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100, 60));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger attributed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Set<String> receiptIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReceiptResponse receipt = service.submitReceipt(r.reservationId(),
                            new ReceiptRequest("req-r-" + idx, "op-" + idx,
                                    BASE.toEpochMilli() + 1_000L));
                    if (receipt.result().name().equals("ATTRIBUTED")) {
                        attributed.incrementAndGet();
                        receiptIds.add(receipt.receiptId());
                    }
                } catch (ApiException e) {
                    errors.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "窗口内并发回执不应报错");
        assertEquals(threads, attributed.get(), "全部回执均计入归因");
        assertEquals(threads, receiptIds.size(), "回执编号必须唯一");

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("CONFIRMED", detail.status().name());
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedTotal(), "只结算一次，额度保持 1");
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedVisitor());
        Integer receiptRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_receipt WHERE reservation_id = ?",
                Integer.class, r.reservationId());
        assertEquals(threads, receiptRows);
    }

    @Test
    @DisplayName("同一预占并发回执与取消：只允许一个终态，额度不重复释放、不变负")
    void concurrentReceiptAndCancel_singleTerminal() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 1, 1, 60));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger receiptAttributed = new AtomicInteger();
        AtomicInteger receiptRejected = new AtomicInteger();
        AtomicInteger cancelSucceeded = new AtomicInteger();
        AtomicInteger cancelConflicted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean isReceipt = i % 2 == 0;
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (isReceipt) {
                        ReceiptResponse receipt = service.submitReceipt(r.reservationId(),
                                new ReceiptRequest("req-t-" + idx, "op-" + idx,
                                        BASE.toEpochMilli() + 1_000L));
                        if (receipt.result().name().equals("ATTRIBUTED")) {
                            receiptAttributed.incrementAndGet();
                        }
                    } else {
                        service.cancel(r.reservationId(), new ReservationActionRequest("req-t-" + idx));
                        cancelSucceeded.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    int status = ex.getStatus().value();
                    if (isReceipt && (status == 422 || status == 409)) {
                        receiptRejected.incrementAndGet();
                    } else if (!isReceipt && status == 409) {
                        cancelConflicted.incrementAndGet();
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

        ReservationResponse detail = service.getReservation(r.reservationId());
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int usedVisitor = jdbc.queryForObject(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'v1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertTrue(usedTotal >= 0 && usedVisitor >= 0, "账目不得为负");

        if (detail.status().name().equals("CONFIRMED")) {
            // 回执先结算：全部回执计入归因，全部取消 409，额度保持占用
            assertEquals(12, receiptAttributed.get());
            assertEquals(0, receiptRejected.get());
            assertEquals(0, cancelSucceeded.get());
            assertEquals(12, cancelConflicted.get());
            assertEquals(1, usedTotal);
            assertEquals(1, usedVisitor);
        } else {
            // 取消先裁决：全部取消返回同一终态，全部回执 422，额度恰好释放一次
            assertEquals("CANCELLED", detail.status().name());
            assertEquals(0, receiptAttributed.get());
            assertEquals(12, receiptRejected.get());
            assertEquals(12, cancelSucceeded.get());
            assertEquals(0, cancelConflicted.get());
            assertEquals(0, usedTotal);
            assertEquals(0, usedVisitor);
        }
    }
}
