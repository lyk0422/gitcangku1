package com.example.starter.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.bridge.dto.ApplyPermitRequest;

/**
 * 并发边界测试：同一窗口最多一个获准、同键并发重放、进桥与撤销竞争只产生一个终态。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class PermitConcurrencyTest {

    @Autowired
    private PermitService permitService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bridge_permit");
        jdbcTemplate.update("DELETE FROM bridge_rejection");
        clock.set(TestClockConfiguration.BASE_TIME);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 两个任务在同一发令枪后并发执行，各自带 15 秒超时返回结果或异常。 */
    private <T> List<Outcome<T>> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Callable<T> gatedFirst = () -> {
            gate.await();
            return first.call();
        };
        Callable<T> gatedSecond = () -> {
            gate.await();
            return second.call();
        };
        Future<Outcome<T>> firstFuture = executor.submit(() -> capture(gatedFirst));
        Future<Outcome<T>> secondFuture = executor.submit(() -> capture(gatedSecond));
        gate.countDown();
        return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
    }

    private static <T> Outcome<T> capture(Callable<T> task) {
        try {
            return new Outcome<>(task.call(), null);
        } catch (Exception e) {
            return new Outcome<>(null, e);
        }
    }

    private record Outcome<T>(T value, Exception error) {
    }

    private static ApplyPermitRequest request(String requestId, String plate) {
        return new ApplyPermitRequest(requestId, plate, 30000, Instant.parse("2026-10-04T09:00:00Z"));
    }

    @Test
    void concurrentApplicationsForSameWindowAllowAtMostOnePermit() throws Exception {
        List<Outcome<BridgePermit>> outcomes = runConcurrently(
                () -> permitService.apply(request("req-a", "CAR-A")),
                () -> permitService.apply(request("req-b", "CAR-B")));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long overlaps = outcomes.stream()
                .filter(o -> o.error() instanceof PermitConflictException
                        && "WINDOW_OVERLAP".equals(((PermitConflictException) o.error()).code()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(overlaps).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bridge_permit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameKeyReplayCreatesSinglePermit() throws Exception {
        List<Outcome<BridgePermit>> outcomes = runConcurrently(
                () -> permitService.apply(request("req-a", "CAR-A")),
                () -> permitService.apply(request("req-a", "CAR-A")));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.get(0).value().id()).isEqualTo(outcomes.get(1).value().id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bridge_permit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void entryAndCancelRaceProducesExactlyOneTerminalState() throws Exception {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A"));
        clock.set(Instant.parse("2026-10-04T09:05:00Z"));

        List<Outcome<BridgePermit>> outcomes = runConcurrently(
                () -> permitService.confirmEntry(permit.id(), "op-entry"),
                () -> permitService.cancel(permit.id(), "op-cancel"));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error() instanceof PermitConflictException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        BridgePermit current = permitService.getPermit(permit.id());
        assertThat(current.status()).isIn(PermitStatus.ENTERED, PermitStatus.CANCELLED);
        String expectedStatus = outcomes.get(0).error() == null ? "ENTERED" : "CANCELLED";
        assertThat(current.status().name()).isEqualTo(expectedStatus);
    }
}