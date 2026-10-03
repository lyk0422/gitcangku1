package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.DeicingSlot;
import com.example.starter.domain.FluidType;
import com.example.starter.domain.SlotStatus;
import com.example.starter.service.ConflictException;
import com.example.starter.service.DeicingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** 并发测试：同一车辆重叠申请最多成功一个；完成与取消竞争只形成一个状态；重复确认不产生第二次放行。 */
@SpringBootTest
@Import(TestClockConfig.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DeicingConcurrencyTest {

    private static final Instant T_1000 = Instant.parse("2026-01-01T10:00:00Z");
    private static final int THREADS = 8;

    @Autowired
    private DeicingService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clock.setInstant(T_1000);
    }

    @Test
    void concurrentOverlappingApplyAtMostOneSucceeds() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String requestId = "race-apply-" + i;
            long flightId = (i % 3) + 1;
            tasks.add(() -> {
                try {
                    service.applySlot(requestId, flightId, 1, T_1000, T_1000.plusSeconds(1800));
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            });
        }
        List<Boolean> results = runConcurrently(tasks);
        long successes = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, successes, "同一车辆重叠时隙并发申请最多成功一个");
        Integer booked = jdbc.queryForObject("SELECT COUNT(*) FROM deicing_slot"
                + " WHERE vehicle_id = 1 AND status = 'BOOKED'", Integer.class);
        assertEquals(1, booked);
    }

    @Test
    void completeAndCancelRaceYieldsSingleState() throws Exception {
        for (int round = 0; round < 10; round++) {
            DeicingSlot slot = service.applySlot("race-slot-" + round, 1, 1,
                    T_1000.plusSeconds(3600L * (round + 1)),
                    T_1000.plusSeconds(3600L * (round + 1) + 1800));
            long slotId = slot.id();
            List<Callable<String>> tasks = List.of(
                    () -> {
                        try {
                            service.completeSlot(slotId, FluidType.A);
                            return "COMPLETED";
                        } catch (ConflictException e) {
                            return "LOST";
                        }
                    },
                    () -> {
                        try {
                            service.cancelSlot(slotId);
                            return "CANCELLED";
                        } catch (ConflictException e) {
                            return "LOST";
                        }
                    });
            List<String> results = runConcurrently(tasks);
            long winners = results.stream().filter(r -> !"LOST".equals(r)).count();
            assertEquals(1, winners, "完成与取消竞争只能形成一个状态");
            String status = jdbc.queryForObject("SELECT status FROM deicing_slot WHERE id = ?",
                    String.class, slotId);
            Integer completions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM deicing_completion WHERE slot_id = ?", Integer.class, slotId);
            if ("COMPLETED".equals(status)) {
                assertEquals(1, completions, "已完成时隙必须有完成记录");
            } else {
                assertEquals("CANCELLED", status);
                assertEquals(0, completions, "已取消时隙不能有完成记录");
            }
        }
    }

    @Test
    void concurrentTakeoffConfirmClearsOnlyOnce() throws Exception {
        DeicingSlot slot = service.applySlot("tk-slot", 1, 1, T_1000, T_1000.plusSeconds(1800));
        service.completeSlot(slot.id(), FluidType.A);
        clock.setInstant(T_1000.plusSeconds(300));

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String requestId = "race-takeoff-" + i;
            tasks.add(() -> {
                try {
                    return service.confirmTakeoff(requestId, 1).status() == ClearanceStatus.CLEARED;
                } catch (ConflictException e) {
                    return false;
                }
            });
        }
        List<Boolean> results = runConcurrently(tasks);
        long cleared = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, cleared, "并发起飞确认最多放行一次");
        Integer clearedRows = jdbc.queryForObject("SELECT COUNT(*) FROM takeoff_clearance"
                + " WHERE flight_id = 1 AND status = 'CLEARED'", Integer.class);
        assertEquals(1, clearedRows);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<T>> gated = tasks.stream().<Callable<T>>map(task -> () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "并发起始门超时");
                return task.call();
            }).toList();
            List<Future<T>> futures = gated.stream().map(pool::submit).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池关闭超时");
        }
    }
}