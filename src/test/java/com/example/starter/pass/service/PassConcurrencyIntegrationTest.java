package com.example.starter.pass.service;

import com.example.starter.pass.api.AllocateRequest;
import com.example.starter.pass.api.CancelRequest;
import com.example.starter.pass.api.CompleteRequest;
import com.example.starter.pass.api.PredictionUpsertRequest;
import com.example.starter.pass.domain.BusinessRuleException;
import com.example.starter.pass.domain.RejectReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2 上的并发边界测试：同天线并发起停、完成与取消竞争、预测修订与确认竞争、
 * 同 requestId 并发重放。用起跑栅栏协调真实竞争，Future 设超时，最终以 JDBC 数据核对业务结果。
 */
@SpringBootTest
@ActiveProfiles("test")
class PassConcurrencyIntegrationTest {

    private static final Instant T1200 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T1205 = Instant.parse("2026-10-01T12:05:00Z");
    private static final Instant T1210 = Instant.parse("2026-10-01T12:10:00Z");
    private static final Instant T1230 = Instant.parse("2026-10-01T12:30:00Z");
    private static final Instant T1240 = Instant.parse("2026-10-01T12:40:00Z");

    @Autowired
    private PassService passService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdSatellites = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (createdSatellites.isEmpty()) {
            return;
        }
        String in = String.join(",", createdSatellites.stream().map(s -> "?").toList());
        jdbcTemplate.update("DELETE FROM idempotent_request WHERE allocation_id IN "
                + "(SELECT id FROM slot_allocation WHERE satellite_code IN (" + in + "))",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM slot_allocation WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM pass_prediction WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM satellite WHERE code IN (" + in + ")",
                createdSatellites.toArray());
        createdSatellites.clear();
    }

    private String newSatellite() {
        String code = "C-" + UUID.randomUUID().toString().substring(0, 8);
        createdSatellites.add(code);
        return code;
    }

    private void predict(String sat, Instant start, Instant end, long bytes) {
        passService.upsertPrediction(new PredictionUpsertRequest(
                sat, start, end, new BigDecimal("15.00"), bytes));
    }

    /** 让所有工作线程在同一时刻发起，制造真实竞争。 */
    private static void runRace(int parties, Runnable... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            CountDownLatch ready = new CountDownLatch(parties);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(30, TimeUnit.SECONDS)) {
                            throw new AssertionError("start gate timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    task.run();
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentOverlappingAllocationsLeaveExactlyOneConfirmed() throws Exception {
        String sat = newSatellite();
        predict(sat, T1200, T1210, 100_000L);

        int parties = 8;
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Runnable[] tasks = new Runnable[parties];
        for (int i = 0; i < parties; i++) {
            AllocateRequest request = new AllocateRequest(
                    "RACE-" + UUID.randomUUID(), sat, "A", T1200, T1205);
            tasks[i] = () -> {
                try {
                    passService.allocate(request);
                    confirmed.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertEquals(RejectReason.ANTENNA_CONFLICT, e.reason());
                    conflicts.incrementAndGet();
                }
            };
        }
        runRace(parties, tasks);

        assertEquals(1, confirmed.get());
        assertEquals(parties - 1, conflicts.get());

        // JDBC 终态：该卫星在 ANT-A 12:00-12:05 窗口只有一条 CONFIRMED，无残留待重排/取消垃圾
        Integer held = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE satellite_code = ? AND antenna_code = 'ANT-A' "
                        + "AND status = 'CONFIRMED' AND window_start = ? AND window_end = ?",
                Integer.class, sat, java.sql.Timestamp.from(T1200), java.sql.Timestamp.from(T1205));
        assertEquals(1, held);
        Integer rolledBack = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request ir WHERE NOT EXISTS "
                        + "(SELECT 1 FROM slot_allocation sa WHERE sa.id = ir.allocation_id)",
                Integer.class);
        assertEquals(0, rolledBack);
    }

    @Test
    void concurrentSameRequestReplayAllReturnSameSlot() throws Exception {
        String sat = newSatellite();
        predict(sat, T1200, T1210, 100_000L);

        int parties = 6;
        String requestId = "IDEM-" + UUID.randomUUID();
        AtomicInteger ok = new AtomicInteger();
        Runnable[] tasks = new Runnable[parties];
        for (int i = 0; i < parties; i++) {
            AllocateRequest request = new AllocateRequest(requestId, sat, "A", T1200, T1205);
            tasks[i] = () -> {
                long id = passService.allocate(request).id();
                if (id > 0) {
                    ok.incrementAndGet();
                }
            };
        }
        runRace(parties, tasks);
        assertEquals(parties, ok.get());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE request_id = ?",
                Integer.class, requestId);
        assertEquals(1, rows);
        Integer idemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                Integer.class, requestId);
        assertEquals(1, idemRows);
    }

    @Test
    void completeAndCancelRaceYieldsExactlyOneTerminalState() throws Exception {
        int iterations = 30;
        int receiveWins = 0;
        int cancelWins = 0;

        for (int iter = 0; iter < iterations; iter++) {
            String sat = newSatellite();
            predict(sat, T1200, T1210, 100_000L);
            long slotId = passService.allocate(new AllocateRequest(
                    "CC-" + UUID.randomUUID(), sat, "A", T1200, T1205)).id();
            String completeReqId = "DONE-" + UUID.randomUUID();

            AtomicInteger received = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            Runnable completion = () -> {
                try {
                    passService.complete(slotId, new CompleteRequest(completeReqId, 80_000L));
                    received.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertEquals(RejectReason.SLOT_ALREADY_TERMINATED, e.reason());
                    rejected.incrementAndGet();
                }
            };
            Runnable cancellation = () -> {
                try {
                    passService.cancel(slotId, new CancelRequest("竞争取消"));
                    cancelled.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertEquals(RejectReason.SLOT_ALREADY_TERMINATED, e.reason());
                    rejected.incrementAndGet();
                }
            };
            runRace(2, completion, cancellation);

            // 恰好一个终态：成功一方与被拒一方各一
            assertEquals(1, received.get() + cancelled.get(), "iteration " + iter);
            assertEquals(1, rejected.get(), "iteration " + iter);

            // JDBC：终态标记互斥，绝不同时存在接收与取消标记
            Integer mixed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM slot_allocation WHERE id = ? "
                            + "AND ((received_at IS NOT NULL AND cancelled_at IS NOT NULL) "
                            + "OR (actual_bytes IS NOT NULL AND cancel_reason IS NOT NULL))",
                    Integer.class, slotId);
            assertEquals(0, mixed, "iteration " + iter);
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM slot_allocation WHERE id = ?", String.class, slotId);
            if ("RECEIVED".equals(status)) {
                receiveWins++;
                Long bytes = jdbcTemplate.queryForObject(
                        "SELECT actual_bytes FROM slot_allocation WHERE id = ?", Long.class, slotId);
                assertEquals(80_000L, bytes);
                assertNull(jdbcTemplate.queryForObject(
                        "SELECT cancel_reason FROM slot_allocation WHERE id = ?", String.class, slotId));
            } else {
                assertEquals("CANCELLED", status, "iteration " + iter);
                cancelWins++;
            }
        }
        // 30 次真实竞争中两种先后顺序都应被观察到（顺序本身不固定，但只允许一个终态）
        assertTrue(receiveWins > 0, "completion never won the race");
        assertTrue(cancelWins > 0, "cancel never won the race");
    }

    @Test
    void revisionAndAllocationRaceNeverLocksStalePrediction() throws Exception {
        int iterations = 20;
        int allocationFirst = 0;
        int revisionFirst = 0;

        for (int iter = 0; iter < iterations; iter++) {
            String sat = newSatellite();
            predict(sat, T1200, T1210, 100_000L);

            AllocateRequest request = new AllocateRequest(
                    "RV-" + UUID.randomUUID(), sat, "A", T1200, T1205);
            AtomicInteger allocated = new AtomicInteger();
            AtomicInteger windowRejected = new AtomicInteger();
            Runnable allocator = () -> {
                try {
                    passService.allocate(request);
                    allocated.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertEquals(RejectReason.WINDOW_GAP, e.reason());
                    windowRejected.incrementAndGet();
                }
            };
            Runnable revisor = () -> passService.upsertPrediction(new PredictionUpsertRequest(
                    sat, T1230, T1240, new BigDecimal("15.00"), 100_000L));
            runRace(2, allocator, revisor);

            // 最终不变量：有效预测为 v2(12:30-12:40)，不存在一个 CONFIRMED 时隙仍占着 12:00 的天线
            List<String> statuses = jdbcTemplate.queryForList(
                    "SELECT status FROM slot_allocation WHERE satellite_code = ?", String.class, sat);
            if (allocated.get() == 1) {
                allocationFirst++;
                // 分配先成功，随后修订必须把它打到待重排并释放天线
                assertEquals(List.of("PENDING_REASSIGN"), statuses, "iteration " + iter);
                Integer antennaFreed = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM slot_allocation WHERE satellite_code = ? "
                                + "AND status = 'PENDING_REASSIGN' AND antenna_code IS NULL "
                                + "AND confirmed_at IS NULL",
                        Integer.class, sat);
                assertEquals(1, antennaFreed, "iteration " + iter);
            } else {
                revisionFirst++;
                assertEquals(1, windowRejected.get(), "iteration " + iter);
                // 修订先落库，过期窗口申请被整体拒绝，不允许出现任何时隙行
                assertEquals(0, statuses.size(), "iteration " + iter);
            }
            Integer confirmedAgainstStale = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM slot_allocation sa "
                            + "JOIN pass_prediction pp ON pp.id = sa.prediction_id "
                            + "WHERE sa.satellite_code = ? AND sa.status = 'CONFIRMED' "
                            + "AND (sa.window_start < pp.visible_start OR sa.window_end > pp.visible_end)",
                    Integer.class, sat);
            assertEquals(0, confirmedAgainstStale, "iteration " + iter);
        }
        assertTrue(allocationFirst > 0, "allocation never won the race");
        assertTrue(revisionFirst > 0, "revision never won the race");
    }
}
