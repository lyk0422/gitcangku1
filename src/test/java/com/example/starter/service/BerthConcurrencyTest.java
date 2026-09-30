package com.example.starter.service;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.AllocationStatus;
import com.example.starter.repository.AllocationRepository;
import com.example.starter.support.BusinessTime;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.PublishTideRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 上的并发测试：潮高修订与靠泊确认并发、同窗口竞争、同 requestId 并发重放。
 * 线程由 CountDownLatch 同时放行，所有断言核对最终业务数据，不依赖打印或休眠。
 */
@SpringBootTest
class BerthConcurrencyTest {

    private static final LocalDateTime CLOCK_BEFORE = LocalDateTime.of(2026, 9, 30, 12, 0);

    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired
    private BerthService berthService;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        setClock(CLOCK_BEFORE);
        jdbcTemplate.update("DELETE FROM berth_allocation");
        jdbcTemplate.update("DELETE FROM tide_window");
        jdbcTemplate.update("DELETE FROM tide_version");
        jdbcTemplate.update("INSERT INTO tide_version (id, version_no, published_at, remark) "
                + "VALUES (1, 1, ?, '初始版本 0.7 米')", java.sql.Timestamp.valueOf(CLOCK_BEFORE));
        jdbcTemplate.execute("ALTER TABLE tide_version ALTER COLUMN id RESTART WITH 2");
        jdbcTemplate.batchUpdate(
                "INSERT INTO tide_window (version_id, start_time, end_time, tide_height_m) "
                        + "VALUES (1, ?, ?, ?)",
                List.of(
                        new Object[]{LocalDateTime.of(2026, 10, 1, 0, 0), T08, new BigDecimal("1.20")},
                        new Object[]{T08, T10, new BigDecimal("0.70")},
                        new Object[]{T10, LocalDateTime.of(2026, 10, 2, 0, 0),
                                new BigDecimal("0.70")}));
    }

    @AfterEach
    void tearDown() {
        BusinessTime.resetClock();
        jdbcTemplate.update("DELETE FROM berth_allocation");
    }

    /**
     * 潮高修订（0.7→0.5）与靠泊申请并发：无论两者谁先拿到全局锁，
     * 都不允许在新版本下保留一个不合格的已确认占用。
     */
    @Test
    void revisionAndApplyRunConcurrentlyWithoutStaleOccupancy() throws Exception {
        for (int i = 0; i < 8; i++) {
            // 每轮恢复版本 1，制造完全相同的竞争条件。
            resetToVersion1();
            String requestId = "req-conc-" + i;

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> applyError = new AtomicReference<>();
            AtomicReference<Throwable> publishError = new AtomicReference<>();
            try {
                Future<?> applyFuture = pool.submit(() -> {
                    awaitQuietly(start);
                    try {
                        berthService.apply(applyRequest(requestId, "9.20", "0.20"));
                    } catch (Throwable t) {
                        applyError.set(t);
                    }
                });
                Future<?> publishFuture = pool.submit(() -> {
                    awaitQuietly(start);
                    try {
                        berthService.publishTide(revisionRequest(2, "0.50"));
                    } catch (Throwable t) {
                        publishError.set(t);
                    }
                });

                start.countDown();
                applyFuture.get(20, TimeUnit.SECONDS);
                publishFuture.get(20, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(applyError.get()).isNull();
            assertThat(publishError.get()).isNull();

            Allocation result = allocationRepository.findByRequestId(requestId).orElseThrow();
            assertThat(result.getStatus()).isIn(AllocationStatus.PENDING_REVIEW, AllocationStatus.REJECTED);
            if (result.getStatus() == AllocationStatus.PENDING_REVIEW) {
                // 申请先确认、随后被发布事务挂起：必须记录修订原因并已释放原窗口。
                assertThat(result.getRejectReason()).isEqualTo("DEPTH_INSUFFICIENT");
                assertThat(result.getReviewMinTideHeightM()).isEqualByComparingTo("0.50");
                assertThat(allocationRepository.existsConfirmedOverlap(
                        result.getBerthId(), T08, T10, requestId)).isFalse();
                // 释放后，新版本下恰好合格（需要 9.3）的船可以占用该窗口。
                Allocation takeover = berthService.apply(applyRequest("req-conc-take-" + i, "9.10", "0.20"));
                assertThat(takeover.getStatus()).isEqualTo(AllocationStatus.CONFIRMED);
            } else {
                // 发布先提交：申请只能按 0.5 米新版本校核并拒绝，不能沿用旧校核结果占用。
                assertThat(result.getRejectReason()).isEqualTo("DEPTH_INSUFFICIENT");
            }
        }
    }

    /**
     * 同一窗口两个不同申请并发：恰好一个确认占用，另一个因冲突拒绝。
     */
    @Test
    void concurrentApplicationsForSameWindowProduceExactlyOneConfirmation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitQuietly(start);
                berthService.apply(applyRequest("req-win-a", "9.20", "0.20"));
            });
            Future<?> f2 = pool.submit(() -> {
                awaitQuietly(start);
                berthService.apply(applyRequest("req-win-b", "9.20", "0.20"));
            });
            start.countDown();
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Allocation a = allocationRepository.findByRequestId("req-win-a").orElseThrow();
        Allocation b = allocationRepository.findByRequestId("req-win-b").orElseThrow();
        long confirmed = List.of(a, b).stream()
                .filter(x -> x.getStatus() == AllocationStatus.CONFIRMED).count();
        long conflicted = List.of(a, b).stream()
                .filter(x -> x.getStatus() == AllocationStatus.REJECTED
                        && "BERTH_CONFLICT".equals(x.getRejectReason())).count();
        assertThat(confirmed).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);
    }

    /**
     * 同一 requestId 同参并发提交：只落一条记录，两边拿到同一首次结果。
     */
    @Test
    void concurrentSameRequestIdCommitsSingleRecord() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Allocation> r1 = new AtomicReference<>();
        AtomicReference<Allocation> r2 = new AtomicReference<>();
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitQuietly(start);
                r1.set(berthService.apply(applyRequest("req-same", "9.20", "0.20")));
            });
            Future<?> f2 = pool.submit(() -> {
                awaitQuietly(start);
                r2.set(berthService.apply(applyRequest("req-same", "9.20", "0.20")));
            });
            start.countDown();
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(r1.get()).isNotNull();
        assertThat(r2.get()).isNotNull();
        assertThat(r1.get().getId()).isEqualTo(r2.get().getId());
        assertThat(r1.get().getStatus()).isEqualTo(AllocationStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM berth_allocation WHERE request_id = 'req-same'",
                Integer.class)).isEqualTo(1);
    }

    private void resetToVersion1() {
        jdbcTemplate.update("DELETE FROM berth_allocation");
        jdbcTemplate.update("DELETE FROM tide_window");
        jdbcTemplate.update("DELETE FROM tide_version");
        jdbcTemplate.update("INSERT INTO tide_version (id, version_no, published_at, remark) "
                + "VALUES (1, 1, ?, '初始版本 0.7 米')", java.sql.Timestamp.valueOf(CLOCK_BEFORE));
        jdbcTemplate.execute("ALTER TABLE tide_version ALTER COLUMN id RESTART WITH 2");
        jdbcTemplate.update("INSERT INTO tide_window (version_id, start_time, end_time, tide_height_m) "
                        + "VALUES (1, ?, ?, ?)",
                java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 0, 0)),
                java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 10, 2, 0, 0)),
                new BigDecimal("0.70"));
    }

    private ApplyRequest applyRequest(String requestId, String draft, String margin) {
        return new ApplyRequest(requestId, "合成船-" + requestId,
                new BigDecimal(draft), new BigDecimal(margin), "B-01", T08, T10);
    }

    private PublishTideRequest revisionRequest(int versionNo, String tideHeight) {
        PublishTideRequest.WindowDto window = new PublishTideRequest.WindowDto(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 2, 0, 0),
                new BigDecimal(tideHeight));
        return new PublishTideRequest(versionNo, CLOCK_BEFORE, "修订潮高 " + tideHeight + " 米",
                List.of(window));
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void setClock(LocalDateTime time) {
        BusinessTime.setClock(Clock.fixed(
                Instant.ofEpochMilli(time.toInstant(ZoneOffset.ofHours(8)).toEpochMilli()),
                BusinessTime.ZONE));
    }
}
