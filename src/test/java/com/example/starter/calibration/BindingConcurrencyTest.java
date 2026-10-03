package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 绑定并发测试：真实线程并发 + 真实 H2 事务，协调同时发起并设超时，
 * 核对业务结果与最终数据。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:calibration-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class BindingConcurrencyTest {

    @Autowired
    private BindingService bindingService;

    @Autowired
    private CalibrationService calibrationService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanScenarioData() {
        jdbc.update("DELETE FROM task_binding");
        jdbc.update("DELETE FROM receive_task");
        jdbc.update("DELETE FROM antenna_calibration");
        clock.set(TestClockConfig.BASE_NOW);
    }

    private long newCalibration(String antenna, double az, double el) {
        return calibrationService.createCalibration(new CreateCalibrationRequest(
                antenna, Instant.parse("2026-10-05T08:00:00Z"),
                BigDecimal.valueOf(az), BigDecimal.valueOf(el))).id();
    }

    private long newTask(String code, String antenna, String start, String end) {
        return taskService.createTask(new CreateTaskRequest(
                code, antenna, Instant.parse(start), Instant.parse(end))).id();
    }

    @Test
    void 并发重叠绑定最多成功一件且失败不占键() throws Exception {
        long calId = newCalibration("ANT-01", 0.1, 0.1);
        int threads = 4;
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            taskIds.add(newTask("T-C" + i, "ANT-01",
                    "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long taskId = taskIds.get(i);
            String requestId = "req-c" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return bindingService.bind(new CreateBindingRequest(requestId, taskId, calId));
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int successes = 0;
        int conflicts = 0;
        for (Future<Object> future : futures) {
            Object outcome = future.get(15, TimeUnit.SECONDS);
            if (outcome instanceof BindingView) {
                successes++;
            } else {
                ApiException ex = (ApiException) outcome;
                assertEquals(HttpStatus.CONFLICT, ex.status());
                assertEquals("ANTENNA_OCCUPIED", ex.code());
                conflicts++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, successes);
        assertEquals(threads - 1, conflicts);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_binding WHERE status = 'BOUND'", Integer.class));
    }

    @Test
    void 同幂等键同参并发只产生一条绑定() throws Exception {
        long calId = newCalibration("ANT-01", 0.1, 0.1);
        long taskId = newTask("T-SAME", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return bindingService.bind(new CreateBindingRequest("req-same", taskId, calId));
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<Long> bindingIds = new ArrayList<>();
        for (Future<Object> future : futures) {
            Object outcome = future.get(15, TimeUnit.SECONDS);
            assertTrue(outcome instanceof BindingView, "同参并发应都成功: " + outcome);
            bindingIds.add(((BindingView) outcome).id());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(bindingIds.get(0), bindingIds.get(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 作废与绑定竞争按已提交校准状态裁决() throws Exception {
        long calId = newCalibration("ANT-01", 0.1, 0.1);
        long taskId = newTask("T-RACE", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> bindFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                return bindingService.bind(new CreateBindingRequest("req-race", taskId, calId));
            } catch (ApiException ex) {
                return ex;
            }
        });
        Future<Object> invalidateFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                return calibrationService.invalidate(calId, new InvalidateRequest("并发复核作废"));
            } catch (ApiException ex) {
                return ex;
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        Object bindOutcome = bindFuture.get(15, TimeUnit.SECONDS);
        Object invalidateOutcome = invalidateFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(invalidateOutcome instanceof InvalidateResult, "作废应成功: " + invalidateOutcome);
        Integer boundCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_binding WHERE status = 'BOUND'", Integer.class);
        if (bindOutcome instanceof ApiException ex) {
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
            assertEquals("CALIBRATION_INVALIDATED", ex.code());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
        } else {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class);
            assertEquals(1, total);
            Integer flagged = jdbc.queryForObject("SELECT COUNT(*) FROM task_binding"
                    + " WHERE calibration_invalidated = TRUE", Integer.class);
            assertEquals(1, flagged, "已提交绑定必须被作废影响标记");
        }
        assertEquals(0, boundCount, "作废后不应存在未标记的生效绑定");
    }
}