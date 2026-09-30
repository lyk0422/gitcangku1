package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.suppression.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionJoinRequest;
import com.example.starter.exposure.web.SuppressionResponse;
import com.example.starter.exposure.domain.ReservationStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抑制名单真实多线程并发测试（H2 MODE=MySQL）：加入名单与曝光确认竞争时
 * 不得“既取消预占又计入已确认曝光”；抑制中并发申请全部 409；同键并发重放只执行一次。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:suppression_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class SuppressionConcurrencyTest {

    /** 固定时钟：并发期间预占不到期，避免到期结算干扰终态竞争。 */
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

    static final Instant BASE = Instant.parse("2026-09-30T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    ExposureService exposureService;
    @Autowired
    SuppressionService suppressionService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM suppression_cancelled_reservation");
        jdbc.update("DELETE FROM suppression_period");
        jdbc.update("DELETE FROM suppression_list");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM visitor_lock");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("加入名单与确认同一预占并发：终态唯一，取消数/确认数/账目严格互斥一致")
    void joinCompetingWithConfirm_eitherCancelledOrConfirmed_neverBoth() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int iterations = 30;
        int joinWon = 0;
        int confirmWon = 0;
        for (int i = 0; i < iterations; i++) {
            final int iteration = i;
            ReservationResponse r = exposureService.apply(
                    new ApplyExposureRequest("req-a-" + iteration, "cap", "V"));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[2];
            AtomicInteger confirmStatus = new AtomicInteger(-1);
            futures[0] = pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse confirmed = exposureService.confirm(r.reservationId(),
                            new ReservationActionRequest("req-k-" + iteration));
                    confirmStatus.set(confirmed.status() == ReservationStatus.CONFIRMED ? 200 : -2);
                } catch (ApiException ex) {
                    // 加入名单胜出时，确认必为 409（终态竞争的预期结果）
                    if (ex.getStatus().value() == 409) {
                        confirmStatus.set(409);
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            AtomicInteger joinCancelled = new AtomicInteger(-1);
            futures[1] = pool.submit(() -> {
                try {
                    start.await();
                    SuppressionResponse resp = suppressionService.join(
                            new SuppressionJoinRequest("req-s-" + iteration, "V"));
                    joinCancelled.set(resp.cancelledCount());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            String finalStatus = exposureService.getReservation(r.reservationId()).status().name();
            int usedTotal = usedTotal();
            int usedVisitor = usedVisitor();
            int cancelledCount = jdbc.queryForObject(
                    "SELECT cancelled_count FROM suppression_list WHERE visitor_id = 'V'",
                    Integer.class);

            if ("CONFIRMED".equals(finalStatus)) {
                // 确认胜出：加入时已无 RESERVED 可取消，计数保持占用
                confirmWon++;
                assertEquals(200, confirmStatus.get(), "确认胜出方应返回 CONFIRMED");
                assertEquals(0, joinCancelled.get(), "确认胜出时加入取消数量必须为 0");
                assertEquals(0, cancelledCount);
                assertEquals(1, usedTotal, "已确认曝光计数不得回退");
                assertEquals(1, usedVisitor);
            } else {
                // 加入胜出：确认必为 409，预占取消并恰好释放一次
                joinWon++;
                assertEquals(409, confirmStatus.get(), "加入胜出时确认必须为 409");
                assertEquals("CANCELLED", finalStatus);
                assertEquals(1, joinCancelled.get(), "加入胜出时必须恰好取消 1 笔");
                assertEquals(1, cancelledCount);
                assertEquals(0, usedTotal, "取消预占必须返还额度");
                assertEquals(0, usedVisitor);
            }

            // 关键不变量：同一预占不能既被取消又计入已确认
            int states = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exposure_reservation WHERE reservation_id = ? "
                            + "AND status IN ('CONFIRMED','CANCELLED')",
                    Integer.class, r.reservationId());
            assertEquals(1, states);
            int auditRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM suppression_cancelled_reservation WHERE reservation_id = ?",
                    Integer.class, r.reservationId());
            if ("CONFIRMED".equals(finalStatus)) {
                assertEquals(0, auditRows, "已确认预占不得写入取消审计");
            } else {
                assertEquals(1, auditRows, "被取消预占必须有审计明细");
            }

            // 解除并清理，进入下一轮（解除只影响后续，不恢复历史，符合题意）
            suppressionService.release("V", new com.example.starter.exposure.web
                    .SuppressionReleaseRequest("req-r-" + iteration));
            jdbc.update("DELETE FROM idempotency_record");
            jdbc.update("DELETE FROM suppression_cancelled_reservation");
            jdbc.update("DELETE FROM suppression_period");
            jdbc.update("DELETE FROM suppression_list");
            jdbc.update("DELETE FROM exposure_reservation");
            jdbc.update("DELETE FROM quota_visitor_ledger");
            jdbc.update("DELETE FROM quota_total_ledger");
            jdbc.update("DELETE FROM visitor_lock");
        }

        // 30 轮中两种交错都应被实际覆盖；若调度极端偏向一侧，至少不能出现数据不一致
        assertTrue(joinWon + confirmWon == iterations, "每轮必须二选一收敛");
        assertTrue(joinWon > 0, "应至少观察到加入胜出的交错，实际 joinWon=" + joinWon);
        assertTrue(confirmWon > 0, "应至少观察到确认胜出的交错，实际 confirmWon=" + confirmWon);
    }

    @Test
    @DisplayName("抑制生效后并发新申请：全部 409，额度不增加")
    void concurrentApplyWhileSuppressed_all409NoQuotaUsed() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));
        suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    exposureService.apply(new ApplyExposureRequest("req-a-" + idx, "cap", "V"));
                    otherErrors.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads, conflicts.get());
        assertEquals(0, otherErrors.get());
        assertEquals(0, usedTotal());
        assertEquals(0, usedVisitor());
    }

    @Test
    @DisplayName("加入名单与并发申请竞争：每个预占要么确认/取消一致，要么 409；账目与取消数恒等")
    void joinCompetingWithApply_consistentOutcomes() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int applyThreads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(applyThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> createdIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = exposureService.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "V"));
                    created.incrementAndGet();
                    createdIds.add(r.reservationId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        rejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Future<SuppressionResponse> joinFuture = pool.submit(() -> {
            start.await();
            return suppressionService.join(new SuppressionJoinRequest("req-s1", "V"));
        });
        start.countDown();
        SuppressionResponse joined = joinFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(applyThreads, created.get() + rejected.get());
        assertEquals(created.get(), createdIds.size());

        // 加入取消数等于最终处于 CANCELLED 的预占数；所有申请产生的预占均已终态
        Integer cancelledRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE visitor_id = 'V' AND status = 'CANCELLED'",
                Integer.class);
        Integer reservedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE visitor_id = 'V' AND status = 'RESERVED'",
                Integer.class);
        assertNotNull(cancelledRows);
        assertEquals(cancelledRows, joined.cancelledCount());
        assertEquals(0, reservedRows, "加入完成后不得残留 RESERVED 预占");

        // 没有已确认预占，故占用额度必须为 0（被加入取消的全部返还）
        assertEquals(0, usedTotal());
        assertEquals(0, usedVisitor());
        // 取消审计行数与取消数一致
        assertEquals(joined.cancelledCount(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancelled_reservation", Integer.class));
    }

    @Test
    @DisplayName("同一 requestId 并发加入：业务只执行一次，取消数与周期数稳定")
    void concurrentSameJoinRequestId_executesOnce() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));
        exposureService.apply(new ApplyExposureRequest("req-a1", "cap", "V"));
        exposureService.apply(new ApplyExposureRequest("req-a2", "cap", "V"));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> suppressionIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger cancelledCount = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SuppressionResponse resp = suppressionService.join(
                            new SuppressionJoinRequest("same-join-key", "V"));
                    suppressionIds.add(resp.suppressionId());
                    cancelledCount.set(resp.cancelledCount());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get());
        assertEquals(1, suppressionIds.size(), "只开启一个抑制周期");
        assertEquals(2, cancelledCount.get(), "所有重放返回首次取消数量 2");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suppression_period", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_cancelled_reservation", Integer.class));
        assertEquals(0, usedTotal());
        assertEquals(0, usedVisitor());
    }

    private int usedTotal() {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(used_total), 0) FROM quota_total_ledger "
                        + "WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
    }

    private int usedVisitor() {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(used_visitor), 0) FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'V' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
    }
}
