package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.model.AllocationStatus;
import com.example.starter.model.RejectReason;
import com.example.starter.model.SlotAllocation;
import com.example.starter.service.AllocationService;
import com.example.starter.service.BusinessException;
import com.example.starter.service.PredictionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 并发边界：同天线同时确认只有一个成功；完成与取消同时发生只有一个终态；
 * 修订与确认竞争时不会留下违反最新窗口的已确认占用。
 */
class AllocationConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant W_START = Instant.parse("2026-10-04T12:00:00Z");
    private static final Instant W_END = Instant.parse("2026-10-04T12:10:00Z");

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private PredictionService predictionService;

    private long publishDefault() {
        return predictionService.publish("SAT-1", W_START, W_END,
                new BigDecimal("8.0"), 5_000_000).id();
    }

    /** 两个任务在同一门闩后并发执行，各自等待至多 15 秒。 */
    private static List<Outcome> runConcurrently(Runnable first, Runnable second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Outcome[] outcomes = {new Outcome(), new Outcome()};
            Runnable[] tasks = {first, second};
            Future<?>[] futures = new Future<?>[2];
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        tasks[idx].run();
                        outcomes[idx].ok = true;
                    } catch (BusinessException e) {
                        outcomes[idx].reason = e.reason();
                    } catch (Exception e) {
                        outcomes[idx].error = e;
                    }
                });
            }
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            return List.of(outcomes);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static final class Outcome {
        boolean ok;
        RejectReason reason;
        Exception error;
    }

    @Test
    void concurrentOverlappingAllocationsOnlyOneWins() throws Exception {
        publishDefault();
        List<Outcome> outcomes = runConcurrently(
                () -> allocationService.allocate("req-x", "SAT-1", STATION_A, W_START, W_END, null),
                () -> allocationService.allocate("req-y", "SAT-1", STATION_A, W_START, W_END, null));

        long succeeded = outcomes.stream().filter(o -> o.ok).count();
        assertEquals(1, succeeded, "同一时隙只能确认一个分配");
        Outcome loser = outcomes.stream().filter(o -> !o.ok).findFirst().orElseThrow();
        assertEquals(RejectReason.ANTENNA_OVERLAP, loser.reason);

        Integer confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE status = 'CONFIRMED'", Integer.class);
        assertEquals(1, confirmed, "数据库中只能存在一条已确认占用");
    }

    @Test
    void completeAndCancelRaceLeavesSingleTerminalState() throws Exception {
        publishDefault();
        SlotAllocation allocation = allocationService.allocate(
                "req-1", "SAT-1", STATION_A, W_START, W_END, null);

        List<Outcome> outcomes = runConcurrently(
                () -> allocationService.complete(allocation.id(), "done-1", 4_000_000),
                () -> allocationService.cancel(allocation.id(), "任务取消"));

        long succeeded = outcomes.stream().filter(o -> o.ok).count();
        assertEquals(1, succeeded, "完成与取消只能有一个生效");
        Outcome loser = outcomes.stream().filter(o -> !o.ok).findFirst().orElseThrow();
        assertEquals(RejectReason.INVALID_STATE, loser.reason);

        AllocationStatus finalState = jdbc.query(
                "SELECT * FROM slot_allocation WHERE id = ?",
                (rs, n) -> AllocationStatus.valueOf(rs.getString("status")),
                allocation.id()).get(0);
        boolean completedWon = outcomes.get(0).ok;
        assertEquals(completedWon ? AllocationStatus.COMPLETED : AllocationStatus.CANCELLED,
                finalState);
        // 终态数据一致：完成则有实际字节数，取消则有取消原因，二者只居其一。
        Long actualBytes = jdbc.queryForObject(
                "SELECT actual_bytes FROM slot_allocation WHERE id = ?", Long.class, allocation.id());
        String cancelReason = jdbc.queryForObject(
                "SELECT cancel_reason FROM slot_allocation WHERE id = ?", String.class, allocation.id());
        assertEquals(completedWon, actualBytes != null);
        assertEquals(!completedWon, cancelReason != null);
    }

    @Test
    void revisionAndConfirmRaceNeverLocksAntennaWithStaleWindow() throws Exception {
        publishDefault();
        Instant revisedStart = Instant.parse("2026-10-04T12:05:00Z");
        List<Outcome> outcomes = runConcurrently(
                () -> allocationService.allocate("req-1", "SAT-1", STATION_A, W_START, W_END, null),
                () -> predictionService.publish("SAT-1", revisedStart, W_END,
                        new BigDecimal("8.0"), 2_000_000));

        for (Outcome outcome : outcomes) {
            assertTrue(outcome.error == null, "不应出现非业务异常: " + outcome.error);
        }
        // 不变量：不存在违反最新窗口的已确认占用。
        List<Instant[]> confirmed = jdbc.query(
                "SELECT slot_start_utc, slot_end_utc FROM slot_allocation WHERE status = 'CONFIRMED'",
                (rs, n) -> new Instant[]{
                        rs.getTimestamp("slot_start_utc").toInstant(),
                        rs.getTimestamp("slot_end_utc").toInstant()});
        for (Instant[] bounds : confirmed) {
            assertTrue(!bounds[0].isBefore(revisedStart) && !bounds[1].isAfter(W_END),
                    "已确认时隙必须落在最新预测窗口内");
        }
    }
}
