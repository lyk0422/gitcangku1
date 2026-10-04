package com.example.snowpack;

import com.example.snowpack.error.BusinessException;
import com.example.snowpack.service.ReadingService;
import com.example.snowpack.service.ReviewService;
import com.example.snowpack.service.ReviewService.ReviewResult;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发审核与异常读数竞争测试：真实 H2 行锁串行化，超时保护。 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReadingService readingService;

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "executor did not stop");
        }
    }

    /** 通过服务层准备封闭区域与三条合格读数。 */
    private void prepareClosedZone(String zoneId) {
        readingService.postClosure(zoneId, "c-1", "patrol closure");
        readingService.postReading(zoneId, "r-08", Instant.parse("2026-10-04T08:00:00Z"), 110, 4);
        readingService.postReading(zoneId, "r-09", Instant.parse("2026-10-04T09:00:00Z"), 115, 5);
        readingService.postReading(zoneId, "r-10", Instant.parse("2026-10-04T10:00:00Z"), 120, 5);
    }

    @Test
    void concurrentSecondConfirmationsCreateExactlyOneReleaseVersion() throws Exception {
        prepareClosedZone("cc-a");
        reviewService.confirm("cc-a", "v-1", "alice");

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<ReviewResult> bob = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return reviewService.confirm("cc-a", "v-bob", "bob");
        });
        Future<ReviewResult> carol = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return reviewService.confirm("cc-a", "v-carol", "carol");
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready");
        start.countDown();

        Outcome first = await(bob);
        Outcome second = await(carol);

        // 恰好一个确认成功，另一个因审核已完成而冲突；解除版本唯一
        assertEquals(1, (first.ok ? 1 : 0) + (second.ok ? 1 : 0),
                "exactly one confirmation should succeed: " + first + " / " + second);
        assertEquals(1, countRows("release_decision", "cc-a"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version_no FROM release_decision WHERE zone_id = 'cc-a'", Integer.class));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM zone_state WHERE zone_id = 'cc-a'", String.class));
        if (!first.ok) {
            assertEquals(409, first.status);
        }
        if (!second.ok) {
            assertEquals(409, second.status);
        }
    }

    @Test
    void secondConfirmAndOverLimitReadingRaceStaysConsistent() throws Exception {
        prepareClosedZone("cc-b");
        reviewService.confirm("cc-b", "v-1", "alice");

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<ReviewResult> confirm = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return reviewService.confirm("cc-b", "v-2", "bob");
        });
        Future<?> reading = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            readingService.postReading("cc-b", "r-11",
                    Instant.parse("2026-10-04T11:00:00Z"), 130, 9);
            return null;
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready");
        start.countDown();

        Outcome confirmOutcome = await(confirm);
        reading.get(15, TimeUnit.SECONDS);

        int decisions = countRows("release_decision", "cc-b");
        int closures = countRows("closure_command", "cc-b");
        String zoneStatus = jdbc.queryForObject(
                "SELECT status FROM zone_state WHERE zone_id = 'cc-b'", String.class);
        String reviewStatus = jdbc.queryForObject(
                "SELECT status FROM release_review WHERE zone_id = 'cc-b'", String.class);

        if (confirmOutcome.ok) {
            // 确认先完成：解除版本生成，随后超限读数重新封闭，但解除决定保留
            assertEquals(1, decisions);
            assertEquals(2, closures);
            assertEquals("CLOSED", zoneStatus);
            assertEquals("COMPLETED", reviewStatus);
        } else {
            // 超限读数先落库：审核失效，确认被拒绝，区域保持封闭且无解除版本
            assertEquals(0, decisions);
            assertEquals(1, closures);
            assertEquals("CLOSED", zoneStatus);
            assertEquals("INVALIDATED", reviewStatus);
            assertTrue(confirmOutcome.status == 409 || confirmOutcome.status == 422,
                    "unexpected failure status: " + confirmOutcome);
        }
    }

    private record Outcome(boolean ok, int status, ReviewResult result, String error) {
        @Override
        public String toString() {
            return ok ? "OK(" + result.status() + ")" : "FAIL(" + status + "," + error + ")";
        }
    }

    private Outcome await(Future<ReviewResult> future) throws Exception {
        try {
            return new Outcome(true, 200, future.get(15, TimeUnit.SECONDS), null);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BusinessException be) {
                return new Outcome(false, be.status().value(), null, be.code());
            }
            throw ex;
        }
    }
}