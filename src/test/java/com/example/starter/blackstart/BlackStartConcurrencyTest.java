package com.example.starter.blackstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发与终态竞争测试：同一步两个确认只能提交一个读数；终止与第三步执行只允许一个终态。
 * 使用闩锁协调真实并发操作，全部设置超时。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class BlackStartConcurrencyTest {

    @Autowired
    private BlackStartService service;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM black_start_step_success");
        jdbc.update("DELETE FROM black_start_step_attempt");
        jdbc.update("DELETE FROM black_start_plan");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private ConfirmStepRequest ok(String requestId, Double powerKw) {
        return new ConfirmStepRequest(requestId, "NORMAL", 400.0, 50.0, powerKw);
    }

    /** 两个线程在同一起跑线提交，返回各自结果（异常原样上抛包装）。 */
    private <T> List<Future<T>> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<T> wrapFirst = () -> { ready.countDown(); go.await(10, TimeUnit.SECONDS); return first.call(); };
        Callable<T> wrapSecond = () -> { ready.countDown(); go.await(10, TimeUnit.SECONDS); return second.call(); };
        Future<T> f1 = pool.submit(wrapFirst);
        Future<T> f2 = pool.submit(wrapSecond);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        return List.of(f1, f2);
    }

    @Test
    @Timeout(30)
    void concurrentConfirmsOnSameStepOnlyOneReadingCommitted() throws Exception {
        PlanView plan = service.createPlan();

        List<Future<Object>> results = race(
                () -> service.confirmStep(plan.planId(), 1, ok("race-a", null)),
                () -> service.confirmStep(plan.planId(), 1, ok("race-b", null)));

        int successes = 0;
        int conflicts = 0;
        String loserRequestId = null;
        for (Future<Object> future : results) {
            try {
                ConfirmStepResponse response = (ConfirmStepResponse) future.get(10, TimeUnit.SECONDS);
                assertEquals("SUCCESS", response.outcome());
                successes++;
            } catch (java.util.concurrent.ExecutionException e) {
                BlackStartException cause = (BlackStartException) e.getCause();
                assertEquals(HttpStatus.CONFLICT, cause.httpStatus());
                conflicts++;
            }
        }
        assertEquals(1, successes);
        assertEquals(1, conflicts);

        PlanView view = service.getPlan(plan.planId());
        assertEquals(2, view.currentStep());
        assertEquals(1, view.steps().get(0).attempts().size());
        Integer successRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM black_start_step_success WHERE plan_id = ?",
                Integer.class, plan.planId());
        assertEquals(1, successRows);

        // 事务失败回滚后，失败方的原 requestId 可重试：此时步骤已推进，记录为跳步拒绝。
        for (String candidate : List.of("race-a", "race-b")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM black_start_step_attempt WHERE request_id = ?",
                    Integer.class, candidate);
            if (count == 0) {
                loserRequestId = candidate;
            }
        }
        ConfirmStepResponse retried = service.confirmStep(plan.planId(), 1, ok(loserRequestId, null));
        assertEquals("REJECTED", retried.outcome());
        assertTrue(retried.rejectReason().contains("跳步"));
    }

    @Test
    @Timeout(60)
    void terminateAndFinalStepRaceYieldExactlyOneTerminalState() throws Exception {
        for (int round = 0; round < 5; round++) {
            final int r = round;
            PlanView plan = service.createPlan();
            service.confirmStep(plan.planId(), 1, ok("pre1-" + r, null));
            service.confirmStep(plan.planId(), 2, ok("pre2-" + r, null));

            List<Future<Object>> results = race(
                    () -> service.confirmStep(plan.planId(), 3, ok("final-" + r, 100.0)),
                    () -> service.terminate(plan.planId(), new TerminateRequest("紧急终止")));
            for (Future<Object> future : results) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof BlackStartException);
                }
            }

            PlanView view = service.getPlan(plan.planId());
            boolean started = "STARTED".equals(view.status());
            boolean terminated = "TERMINATED".equals(view.status());
            assertNotEquals(started, terminated, "必须且只能有一个终态: " + view.status());
            if (terminated) {
                assertNull(view.completedAt());
                Integer step3Success = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM black_start_step_success WHERE plan_id = ? AND step_no = 3",
                        Integer.class, plan.planId());
                assertEquals(0, step3Success, "已终止计划不允许第三步成功记录");
            } else {
                assertNull(view.terminatedAt());
            }
        }
    }
}