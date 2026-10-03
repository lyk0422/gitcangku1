package com.example.starter.blackstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 并发竞争测试：同一步骤两个确认竞争只提交一个读数；终止与第三步执行竞争只允许一个终态。
 * 使用真实线程与真实 H2 事务，通过门闩协调同时进入，并设置超时。
 */
@SpringBootTest
class BlackStartConcurrencyTest {

    @Autowired
    private BlackStartService service;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM step_attempt");
        jdbcTemplate.update("DELETE FROM black_start_plan");
        when(clock.instant()).thenReturn(Instant.parse("2026-03-01T00:00:00Z"));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ConfirmStepRequest reading(String requestId, String powerKw) {
        return new ConfirmStepRequest(requestId, new BigDecimal("400"), new BigDecimal("50.0"),
                powerKw == null ? null : new BigDecimal(powerKw), "NORMAL");
    }

    @Test
    void concurrentConfirmationsOfSameStepCommitOnlyOneReading() throws Exception {
        long planId = service.createPlan("并发确认").planId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Runnable taskA = () -> awaitAndConfirm(ready, go, planId, "c-a");
        Runnable taskB = () -> awaitAndConfirm(ready, go, planId, "c-b");
        Future<?> futureA = executor.submit(taskA);
        Future<?> futureB = executor.submit(taskB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        futureA.get(15, TimeUnit.SECONDS);
        futureB.get(15, TimeUnit.SECONDS);

        PlanView view = service.getPlan(planId);
        assertEquals(2, view.currentStep());
        assertEquals(1, attemptRepository.countSuccess(planId, 1));
        assertEquals("IN_PROGRESS", view.status());

        String loserRequestId = "c-a".equals(view.steps().get(0).requestId()) ? "c-b" : "c-a";
        BlackStartService.ConfirmOutcome retried = service.confirmStep(planId, 2, reading(loserRequestId, null));
        assertEquals("SUCCESS", retried.response().outcome());
        assertEquals(3, service.getPlan(planId).currentStep());
    }

    private void awaitAndConfirm(CountDownLatch ready, CountDownLatch go, long planId, String requestId) {
        try {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS));
            service.confirmStep(planId, 1, reading(requestId, null));
        } catch (ApiException expected) {
            // 竞争失败者收到 409（步骤已完成或并发修改），属于预期结果。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void terminateRacingWithFinalStepYieldsSingleTerminalState() throws Exception {
        long planId = service.createPlan("终止竞争").planId();
        service.confirmStep(planId, 1, reading("p1", null));
        service.confirmStep(planId, 2, reading("p2", null));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> confirmFuture = executor.submit(() -> {
            try {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                service.confirmStep(planId, 3, reading("p3", "150"));
            } catch (ApiException expected) {
                // 终止先提交时，第三步确认收到 409，属于预期结果。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        Future<?> terminateFuture = executor.submit(() -> {
            try {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                service.terminatePlan(planId, "紧急终止");
            } catch (ApiException expected) {
                // 第三步先完成时，终止收到 409，属于预期结果。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        confirmFuture.get(15, TimeUnit.SECONDS);
        terminateFuture.get(15, TimeUnit.SECONDS);

        PlanView view = service.getPlan(planId);
        List<PlanView.StepRecord> steps = view.steps();
        if ("STARTED".equals(view.status())) {
            assertNull(view.terminatedAt());
            assertEquals(1, attemptRepository.countSuccess(planId, 3));
            assertTrue(steps.stream().noneMatch(s -> s.stepNo() == 3 && "REJECTED".equals(s.outcome())));
        } else {
            assertEquals("TERMINATED", view.status());
            assertEquals(Instant.parse("2026-03-01T00:00:00Z"), view.terminatedAt());
            assertEquals(0, attemptRepository.countSuccess(planId, 3));
            assertTrue(steps.stream().noneMatch(s -> s.stepNo() == 3));
        }
    }
}