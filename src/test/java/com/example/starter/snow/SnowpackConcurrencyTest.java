package com.example.starter.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.snow.dto.ClosureRequest;
import com.example.starter.snow.dto.ConfirmationRequest;
import com.example.starter.snow.dto.ReadingRequest;
import com.example.starter.snow.dto.ReviewCreateRequest;
import com.example.starter.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * 并发与幂等测试：真实 H2 上协调并发操作，验证只形成一个解除版本、
 * 确认与超限读数竞争时状态一致、同请求并发重放只落一行。全部设置超时。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SnowpackConcurrencyTest {

    private static final String H09 = "2026-10-04T09:00:00Z";
    private static final String H10 = "2026-10-04T10:00:00Z";
    private static final String H11 = "2026-10-04T11:00:00Z";
    private static final String H12 = "2026-10-04T12:00:00Z";

    @Autowired
    private SnowpackService snowpackService;
    @Autowired
    private UnsealService unsealService;
    @Autowired
    private StatusService statusService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM unseal_decision");
        jdbc.update("DELETE FROM unseal_confirmation");
        jdbc.update("DELETE FROM unseal_review");
        jdbc.update("DELETE FROM reading_errata");
        jdbc.update("DELETE FROM snow_reading");
        jdbc.update("DELETE FROM closure_command");
        jdbc.update("DELETE FROM region_state");
        clock.setInstant(TestClockConfig.T0);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private String reqId() {
        return UUID.randomUUID().toString();
    }

    private long preparePendingReview(String region) {
        snowpackService.createClosure(region, new ClosureRequest(reqId(), "test closure"));
        snowpackService.reportReading(region, new ReadingRequest(reqId(), H09, 100, 2));
        snowpackService.reportReading(region, new ReadingRequest(reqId(), H10, 100, 3));
        snowpackService.reportReading(region, new ReadingRequest(reqId(), H11, 100, 4));
        Map<String, Object> view = unsealService.createReview(region, new ReviewCreateRequest(reqId()));
        return ((Number) view.get("reviewId")).longValue();
    }

    private List<Future<?>> startConcurrently(Runnable... tasks) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        return futures;
    }

    @Test
    void concurrentConfirmationsFormSingleDecision() throws Exception {
        String region = "RC-" + reqId().substring(0, 8);
        long reviewId = preparePendingReview(region);

        List<Future<?>> futures = startConcurrently(
                () -> unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice")),
                () -> unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "bob")));
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unseal_decision WHERE region_id = ?", Integer.class, region));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM unseal_review WHERE id = ?", String.class, reviewId));
        assertEquals("OPEN", statusService.getStatus(region).get("status"));
    }

    @Test
    void confirmRacingOverLimitReadingStaysConsistent() throws Exception {
        int rounds = 8;
        for (int i = 0; i < rounds; i++) {
            String region = "RR" + i + "-" + reqId().substring(0, 6);
            long reviewId = preparePendingReview(region);
            unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice"));

            List<Future<?>> futures = startConcurrently(
                    () -> {
                        try {
                            unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "bob"));
                        } catch (ApiException expected) {
                            // 读数先提交时审核已失效，确认允许失败
                        }
                    },
                    () -> snowpackService.reportReading(region,
                            new ReadingRequest(reqId(), H12, 100, 9)));
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }

            Integer decisions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM unseal_decision WHERE region_id = ?",
                    Integer.class, region);
            String reviewStatus = jdbc.queryForObject(
                    "SELECT status FROM unseal_review WHERE id = ?", String.class, reviewId);
            assertEquals("CLOSED", statusService.getStatus(region).get("status"));
            if (decisions == 1) {
                // 确认先完成：解除版本保留，超限读数随后生成新的自动封闭命令
                assertEquals("COMPLETED", reviewStatus);
                Integer autoClosures = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM closure_command WHERE region_id = ? AND source = 'AUTO'",
                        Integer.class, region);
                assertEquals(1, autoClosures);
            } else {
                // 读数先提交：审核失效，不能凭旧读数解除
                assertEquals(0, decisions);
                assertEquals("INVALIDATED", reviewStatus);
            }
        }
    }

    @Test
    void concurrentSameRequestIdInsertsSingleReading() throws Exception {
        String region = "RI-" + reqId().substring(0, 8);
        String requestId = reqId();
        ReadingRequest req = new ReadingRequest(requestId, H09, 100, 3);

        List<Future<?>> futures = startConcurrently(
                () -> snowpackService.reportReading(region, req),
                () -> snowpackService.reportReading(region, req),
                () -> snowpackService.reportReading(region, req));
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM snow_reading WHERE region_id = ?", Integer.class, region));
    }
}