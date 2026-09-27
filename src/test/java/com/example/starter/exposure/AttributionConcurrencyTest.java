package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.example.starter.exposure.web.ReceiptBatchResponse;
import com.example.starter.exposure.web.ReceiptItemResult;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因窗口真实多线程并发测试（H2 行锁）：同一预占只归因一次、
 * 确认与回执竞争只有一个终态、同 requestId 并发重放只执行一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class AttributionConcurrencyTest {

    /** 固定时钟：所有并发操作落在预占 TTL 与归因窗口内。 */
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

    static final Instant BASE = Instant.parse("2026-09-28T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 28);

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
        jdbc.update("DELETE FROM attribution_receipt");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_window_version");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("同一预占并发窗口内回执：恰好 1 次 ATTRIBUTED，其余全部 ALREADY_ATTRIBUTED")
    void concurrentReceipts_attributeExactlyOnce() throws Exception {
        service.createCampaign(new CreateCampaignRequest(
                "req-c", "cap", 100, 100, 60, "op"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));
        long start = BASE.toEpochMilli();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger attributed = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Set<String> attributedRequestIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    gate.await();
                    ReceiptBatchResponse resp = service.submitReceipts(new ReceiptBatchRequest(
                            "req-r-" + idx, "op-" + idx,
                            List.of(new ReceiptBatchRequest.ReceiptItem(
                                    r.reservationId(), "cap", "v1", start + 1_000L + idx))));
                    ReceiptItemResult item = resp.results().get(0);
                    if ("ATTRIBUTED".equals(item.result())) {
                        attributed.incrementAndGet();
                        attributedRequestIds.add("req-r-" + idx);
                    } else if ("ALREADY_ATTRIBUTED".equals(item.result())) {
                        already.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现异常结果");
        assertEquals(1, attributed.get(), "只允许归因一次");
        assertEquals(threads - 1, already.get(), "其余回执全部 ALREADY_ATTRIBUTED");
        assertEquals(1, attributedRequestIds.size());

        // 最终数据：恰好一条 ATTRIBUTED 流水，预占 ATTRIBUTED，额度恰好占用 1 不重放
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE reservation_id = ? "
                        + "AND result = 'ATTRIBUTED'", Integer.class, r.reservationId()));
        assertEquals(Integer.valueOf(threads), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE reservation_id = ?",
                Integer.class, r.reservationId()));
        assertEquals("ATTRIBUTED", service.getReservation(r.reservationId()).status());
        assertEquals(1, service.queryQuota("cap", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("确认与窗口内回执并发：只有一个终态胜出，额度不重复结算/不释放")
    void concurrentConfirmAndReceipt_singleTerminalWins() throws Exception {
        service.createCampaign(new CreateCampaignRequest(
                "req-c", "cap", 1, 1, 60, "op"));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "cap", "v1"));
        long start = BASE.toEpochMilli();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger attributed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean doConfirm = i % 2 == 0;
            final int idx = i;
            pool.submit(() -> {
                try {
                    gate.await();
                    if (doConfirm) {
                        ReservationResponse resp = service.confirm(r.reservationId(),
                                new ReservationActionRequest("req-t-" + idx));
                        if ("CONFIRMED".equals(resp.status())) {
                            confirmed.incrementAndGet();
                        }
                    } else {
                        ReceiptBatchResponse resp = service.submitReceipts(new ReceiptBatchRequest(
                                "req-t-" + idx, "op",
                                List.of(new ReceiptBatchRequest.ReceiptItem(
                                        r.reservationId(), "cap", "v1", start + idx))));
                        if ("ATTRIBUTED".equals(resp.results().get(0).result())) {
                            attributed.incrementAndGet();
                        }
                    }
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
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        // 12 个确认 + 12 个回执；只能有一种终态胜出
        assertTrue(confirmed.get() == 0 || attributed.get() == 0,
                "只允许一个终态，confirmed=" + confirmed.get() + " attributed=" + attributed.get());
        assertEquals(1, confirmed.get() + attributed.get(), "胜出操作恰好发生一次");
        // 输掉的方向：确认方 409，回执方得到 ALREADY_CONFIRMED（不计入 attributed）
        String finalStatus = service.getReservation(r.reservationId()).status();
        assertTrue("CONFIRMED".equals(finalStatus) || "ATTRIBUTED".equals(finalStatus));
        if ("CONFIRMED".equals(finalStatus)) {
            assertEquals(1, confirmed.get());
            assertEquals(0, attributed.get());
        } else {
            assertEquals(1, attributed.get());
            assertEquals(0, confirmed.get());
        }
        // 额度始终恰好占用 1（确认保持占用；归因结算也保持占用），无重复释放
        assertEquals(1, service.queryQuota("cap", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedVisitor());
        assertTrue(conflicts.get() >= 0);
    }

    @Test
    @DisplayName("同一批量 requestId 并发重放：只执行一次裁决，流水不重复")
    void concurrentSameBatchRequestId_executesOnce() throws Exception {
        service.createCampaign(new CreateCampaignRequest(
                "req-c", "cap", 100, 100, 60, "op"));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cap", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cap", "v2"));
        long start = BASE.toEpochMilli();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        Set<Long> receivedAts = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    ReceiptBatchResponse resp = service.submitReceipts(new ReceiptBatchRequest(
                            "same-batch-key", "op",
                            List.of(
                                    new ReceiptBatchRequest.ReceiptItem(
                                            r1.reservationId(), "cap", "v1", start + 1_000L),
                                    new ReceiptBatchRequest.ReceiptItem(
                                            r2.reservationId(), "cap", "v2", start + 1_000L))));
                    receivedAts.add(resp.receivedAtUtc());
                    if (resp.attributedCount() != 2) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, receivedAts.size(), "所有线程拿到同一首次快照");
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE request_id = 'same-batch-key'",
                Integer.class));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE result = 'ATTRIBUTED'",
                Integer.class));
        assertEquals(2, service.queryQuota("cap", null, DAY).usedTotal());
    }
}
