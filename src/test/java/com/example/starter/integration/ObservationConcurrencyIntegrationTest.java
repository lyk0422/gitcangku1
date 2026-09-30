package com.example.starter.integration;

import com.example.starter.ResetDatabase;
import com.example.starter.service.ConflictException;
import com.example.starter.service.ObservationService;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfig;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.RequestView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 并发测试：多线程真实提交事务，用栅栏协调起跑，
 * 按业务结果与最终表数据裁决，不依赖打印或休眠判断通过。
 */
@SpringBootTest
@ResetDatabase
class ObservationConcurrencyIntegrationTest {

    private static final Instant T2000 = Instant.parse("2026-09-30T20:00:00Z");
    private static final Instant T2100 = Instant.parse("2026-09-30T21:00:00Z");
    private static final Instant T2030 = Instant.parse("2026-09-30T20:30:00Z");
    private static final Instant T2130 = Instant.parse("2026-09-30T21:30:00Z");

    @Autowired
    private ObservationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private ApplyRequest req(String no, String project, String instrument,
                             Instant start, Instant end, int minutes) {
        return new ApplyRequest(no, project, instrument, start, end, minutes);
    }

    @Test
    @DisplayName("并发批准两个重叠窗口：恰好一个成功，时间轴只有一个占用")
    void concurrentOverlappingApprovesSerialised() throws Exception {
        service.apply(null, req("C-1", "P1", "T", T2000, T2100, 60));
        service.apply(null, req("C-2", "P2", "T", T2030, T2130, 60));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            Future<?> f1 = pool.submit(() -> attemptApprove("C-1", ready, start, success, conflict));
            Future<?> f2 = pool.submit(() -> attemptApprove("C-2", ready, start, success, conflict));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(service.timeline("T")).hasSize(1);
        Integer slotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instrument_slot WHERE instrument_id = 'T'", Integer.class);
        assertThat(slotCount).isEqualTo(1);
    }

    private void attemptApprove(String no, CountDownLatch ready, CountDownLatch start,
                                AtomicInteger success, AtomicInteger conflict) {
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            service.approve(null, no);
            success.incrementAndGet();
        } catch (ConflictException e) {
            conflict.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("并发撤销与重叠批准：按提交顺序裁决，最终无双重占用且状态自洽")
    void concurrentCancelAndApproveStayConsistent() throws Exception {
        service.apply(null, req("C-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "C-1");
        service.apply(null, req("C-2", "P2", "T", T2030, T2130, 60));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelSuccess = new AtomicInteger();
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger approveConflict = new AtomicInteger();
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    CancelResponse out = service.cancel(null, "C-1", "P1");
                    assertThat(out.cancelled().status()).isEqualTo("CANCELLED");
                    cancelSuccess.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    service.approve(null, "C-2");
                    approveSuccess.incrementAndGet();
                } catch (ConflictException e) {
                    approveConflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 撤销必成功；批准要么先提交而409，要么在撤销后提交而成功，二者恰好一种
        assertThat(cancelSuccess.get()).isEqualTo(1);
        assertThat(approveSuccess.get() + approveConflict.get()).isEqualTo(1);

        // C-1 最终已撤销，时间轴至多一个占用且无重叠
        RequestView cancelled = service.requests("T", "P1").get(0);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(service.timeline("T")).hasSize(approveSuccess.get());
        Integer slotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instrument_slot WHERE instrument_id = 'T'", Integer.class);
        assertThat(slotCount).isEqualTo(approveSuccess.get());
    }

    @Test
    @DisplayName("同一幂等键并发申请：只建一条申请与一条幂等记录，两端拿到相同结果")
    void concurrentSameIdempotencyKeyReplays() throws Exception {
        clock.setInstant(TestClockConfig.FIXED_NOW);
        ApplyRequest body = req("IDEM-C", "P1", "T", T2000, T2100, 60);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RequestView> f1 = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.apply("idem-concurrent-key", body);
            });
            Future<RequestView> f2 = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.apply("idem-concurrent-key", body);
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RequestView v1 = f1.get(30, TimeUnit.SECONDS);
            RequestView v2 = f2.get(30, TimeUnit.SECONDS);
            assertThat(v1).isEqualTo(v2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(service.requests("T", null)).hasSize(1);
        Integer idemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_call WHERE request_id = 'idem-concurrent-key'",
                Integer.class);
        assertThat(idemRows).isEqualTo(1);
    }
}
