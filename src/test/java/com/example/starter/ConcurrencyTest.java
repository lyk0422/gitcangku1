package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.model.FluidType;
import com.example.starter.model.SlotStatus;
import com.example.starter.service.ConflictException;
import com.example.starter.service.DeicingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发竞争测试：并发重叠申请最多成功一个，完成与取消竞争只形成一个状态。
 */
@SpringBootTest
class ConcurrencyTest {

    private static final Instant T0950 = Instant.parse("2026-01-01T09:50:00Z");
    private static final Instant T1000 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0950);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM takeoff_rejection");
        jdbc.update("DELETE FROM takeoff_clearance");
        jdbc.update("DELETE FROM deicing_completion");
        jdbc.update("DELETE FROM deicing_slot");
        clock.set(T0950);
    }

    @Test
    void concurrentOverlappingApplyAtMostOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    deicingService.applySlot("req-conc-" + n, 1L, 1L + (n % 3),
                            Instant.parse("2026-01-01T09:00:00Z"), T1000);
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(15, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdownNow();
        assertThat(successes).isEqualTo(1);
        Integer slots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deicing_slot WHERE status <> 'CANCELLED'", Integer.class);
        assertThat(slots).isEqualTo(1);
    }

    @Test
    void completeAndCancelRaceYieldsSingleTerminalState() throws Exception {
        var slot = deicingService.applySlot("req-race", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Boolean> completeFuture = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            try {
                deicingService.completeSlot(slot.id(), FluidType.A);
                return true;
            } catch (ConflictException e) {
                return false;
            }
        });
        Future<Boolean> cancelFuture = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            try {
                deicingService.cancelSlot(slot.id());
                return true;
            } catch (ConflictException e) {
                return false;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        boolean completed = completeFuture.get(15, TimeUnit.SECONDS);
        boolean cancelled = cancelFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(completed).isNotEqualTo(cancelled);
        String status = jdbc.queryForObject(
                "SELECT status FROM deicing_slot WHERE id = ?", String.class, slot.id());
        assertThat(status).isEqualTo(completed
                ? SlotStatus.COMPLETED.name() : SlotStatus.CANCELLED.name());
        Integer completions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deicing_completion WHERE slot_id = ?", Integer.class, slot.id());
        assertThat(completions).isEqualTo(completed ? 1 : 0);
    }
}