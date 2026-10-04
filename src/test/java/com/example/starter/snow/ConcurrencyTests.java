package com.example.starter.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.example.starter.snow.SnowDtos.ConfirmationRequest;
import com.example.starter.snow.SnowDtos.ReadingRequest;
import com.example.starter.snow.SnowDtos.ReviewCreateRequest;

/**
 * 并发场景：并发确认只形成一个解除版本；第二人确认与新异常读数竞争时结果一致。
 */
class ConcurrencyTests extends BaseSnowTest {

    private long preparePendingReview(String area) {
        seedArea(area);
        seedClosure(area, "历史异常");
        seedWindow(area, 3, 4, 5);
        return service.createReview(area, new ReviewCreateRequest("r-1", "alice")).body().reviewId();
    }

    private static <T> T race(Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<?> fa = pool.submit(() -> {
                await(gate);
                a.run();
            });
            Future<?> fb = pool.submit(() -> {
                await(gate);
                b.run();
            });
            gate.countDown();
            fa.get(15, TimeUnit.SECONDS);
            fb.get(15, TimeUnit.SECONDS);
            return null;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 并发第二人确认只形成一个解除版本() throws Exception {
        long reviewId = preparePendingReview("C-1");
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        race(
                () -> run(errors, () -> service.confirm("C-1", reviewId, new ConfirmationRequest("r-2", "bob"))),
                () -> run(errors, () -> service.confirm("C-1", reviewId, new ConfirmationRequest("r-3", "carol"))));
        assertEquals(1, errors.size(), "应恰有一个确认失败: " + errors);
        assertTrue(errors.get(0) instanceof ApiException);
        assertEquals(409, ((ApiException) errors.get(0)).getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'C-1'"));
        assertEquals(2, count("SELECT COUNT(*) FROM lift_confirmation WHERE review_id = " + reviewId));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM lift_review WHERE id = ?", String.class, reviewId));
    }

    @Test
    void 确认与异常读数竞争时不能凭旧读数解除() throws Exception {
        long reviewId = preparePendingReview("C-2");
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        race(
                () -> run(errors, () -> service.confirm("C-2", reviewId, new ConfirmationRequest("r-2", "bob"))),
                () -> run(errors, () -> service.submitReading("C-2",
                        new ReadingRequest("r-x", "2026-01-10T12:00:00Z", 100, 9, "SYNTHETIC"))));
        long decisions = count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'C-2'");
        String reviewStatus = jdbc.queryForObject(
                "SELECT status FROM lift_review WHERE id = ?", String.class, reviewId);
        long openClosures = count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'C-2' AND status = 'OPEN'");
        if (decisions == 1) {
            assertEquals("CONFIRMED", reviewStatus);
            assertEquals(1, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'C-2' AND status = 'CLOSED'"),
                    "解除后新异常必须创建新封闭命令");
        } else {
            assertEquals(0, decisions);
            assertEquals("INVALIDATED", reviewStatus);
            assertEquals(1, errors.size(), "异常先提交时确认必须失败: " + errors);
        }
        assertEquals(1, openClosures, "无论竞争结果如何区域最终必须处于封闭状态");
    }

    private static void run(List<Throwable> errors, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            errors.add(t);
        }
    }
}