package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.web.ConfirmOperationRequest;
import com.example.starter.web.ConfirmOperationRequest.ConfirmResult;
import com.example.starter.web.CreateOperationRequest;
import com.example.starter.web.InflorescenceView;
import com.example.starter.web.OperationView;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 并发与幂等竞争测试：真实线程在同一 H2 库上竞争，验证唯一约束与条件更新保证单一终态。
 */
class PollinationConcurrencyTest extends ServiceTestBase {

    /** 并发执行两个任务并等待结果（带超时），返回结果或抛出的业务异常。 */
    private List<Object> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Object> gateFirst = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return first.call();
            };
            Callable<Object> gateSecond = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return second.call();
            };
            Future<Object> firstFuture = pool.submit(gateFirst);
            Future<Object> secondFuture = pool.submit(gateSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(get(firstFuture), get(secondFuture));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Object get(Future<Object> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static long successes(List<Object> results) {
        return results.stream().filter(OperationView.class::isInstance).count();
    }

    private static long conflicts(List<Object> results) {
        return results.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(error -> error.status() == HttpStatus.CONFLICT)
                .count();
    }

    @Test
    void twoTechniciansConcurrentOnlyOneEffectiveOperation() throws Exception {
        InflorescenceView flower = newInflorescence("INF-21", "2026-02-01T10:00:00Z");
        newBatch("PB-21");

        List<Object> results = runConcurrently(
                () -> service.createOperation(flower.id(),
                        new CreateOperationRequest("req-a", "PB-21", "tech-a")),
                () -> service.createOperation(flower.id(),
                        new CreateOperationRequest("req-b", "PB-21", "tech-b")));

        assertThat(successes(results)).isEqualTo(1);
        assertThat(conflicts(results)).isEqualTo(1);
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);
    }

    @Test
    void sameRequestIdConcurrentRetryBothSucceedWithSameRecord() throws Exception {
        InflorescenceView flower = newInflorescence("INF-22", "2026-02-01T10:00:00Z");
        newBatch("PB-22");

        List<Object> results = runConcurrently(
                () -> service.createOperation(flower.id(),
                        new CreateOperationRequest("req-same", "PB-22", "tech-a")),
                () -> service.createOperation(flower.id(),
                        new CreateOperationRequest("req-same", "PB-22", "tech-a")));

        assertThat(successes(results)).isEqualTo(2);
        OperationView first = (OperationView) results.get(0);
        OperationView second = (OperationView) results.get(1);
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);
    }

    @Test
    void revokeAndConfirmRaceAllowsOnlyOneTerminalState() throws Exception {
        InflorescenceView flower = newInflorescence("INF-23", "2026-02-01T10:00:00Z");
        newBatch("PB-23");
        OperationView created = service.createOperation(flower.id(),
                new CreateOperationRequest("req-c", "PB-23", "tech-a"));

        List<Object> results = runConcurrently(
                () -> service.revokeOperation(created.id()),
                () -> service.confirmOperation(created.id(),
                        new ConfirmOperationRequest("cfm-race", ConfirmResult.SUCCESS)));

        assertThat(successes(results)).isEqualTo(1);
        assertThat(conflicts(results)).isEqualTo(1);
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM pollination_operation WHERE id = ?", String.class, created.id());
        assertThat(finalStatus).isIn("REVOKED", "CONFIRMED_SUCCESS");
    }
}