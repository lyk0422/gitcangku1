package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.error.ConflictException;
import com.example.starter.error.RejectionException;
import com.example.starter.web.dto.ApiResponses.HandoverResponse;
import com.example.starter.web.dto.ApiResponses.IntakeResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 并发边界：真实线程竞争，统一超时，核对业务结果与最终数据。 */
class ConcurrencyTest extends BaseIntegrationTest {

    private static final int TIMEOUT_SECONDS = 15;

    @Test
    void concurrentIntakeOfSameCellSucceedsAtMostOnce() throws Exception {
        String batchA = givenBatch();
        String batchB = givenBatch();
        givenCell("C-M1");
        String reqA = requestId();
        String reqB = requestId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> taskA = () -> intakeWhenGo(ready, go, reqA, batchA);
            Callable<Object> taskB = () -> intakeWhenGo(ready, go, reqB, batchB);
            Future<Object> futureA = pool.submit(taskA);
            Future<Object> futureB = pool.submit(taskB);
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            go.countDown();
            Object resultA = futureA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Object resultB = futureB.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int successes = (resultA instanceof IntakeResponse ? 1 : 0)
                    + (resultB instanceof IntakeResponse ? 1 : 0);
            assertEquals(1, successes, "同一电芯最多一个批次入批成功");
            assertTrue(resultA instanceof ConflictException
                    || resultB instanceof ConflictException);

            String owner = jdbc.queryForObject(
                    "SELECT batch_code FROM battery_cell WHERE cell_code = ?",
                    String.class, "C-M1");
            String expected = resultA instanceof IntakeResponse ? batchA : batchB;
            assertEquals(expected, owner);
            assertEquals(1, count("discharge_reading"));
            // 失败方不占请求键
            String loserReq = resultA instanceof IntakeResponse ? reqB : reqA;
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_key WHERE request_id = ?",
                    Long.class, loserReq).longValue());
        } finally {
            pool.shutdownNow();
        }
    }

    private Object intakeWhenGo(CountDownLatch ready, CountDownLatch go,
                                String req, String batch) {
        try {
            ready.countDown();
            assertTrue(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            return dischargeService.intake(req, batch, "C-M1", 3.7);
        } catch (ConflictException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void handoverUsesOnlyStillValidFinalWhenInvalidationCompetes() throws Exception {
        String batch = givenBatch();
        givenCell("C-N1");
        dischargeService.intake(requestId(), batch, "C-N1", 3.7);
        String reading = dischargeService.finalReading(requestId(), batch, "C-N1", 0.4,
                T0.plusSeconds(3600)).readingCode();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> invalidateTask = () -> {
                ready.countDown();
                go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                dischargeService.invalidateReading(reading);
                return "INVALIDATED";
            };
            Callable<Object> handoverTask = () -> {
                ready.countDown();
                go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    return dischargeService.handover(requestId(), batch, "A", "B");
                } catch (RejectionException e) {
                    return e;
                }
            };
            Future<Object> invalidateFuture = pool.submit(invalidateTask);
            Future<Object> handoverFuture = pool.submit(handoverTask);
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            go.countDown();
            Object invalidateResult = invalidateFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Object handoverResult = handoverFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("INVALIDATED", invalidateResult);
            if (handoverResult instanceof HandoverResponse response) {
                // 交接胜出：作废在交接提交后才生效，快照读数在交接决策时仍有效
                assertEquals(reading, response.cells().get(0).finalReadingCode());
                assertEquals("HANDED_OVER", jdbc.queryForObject(
                        "SELECT status FROM discharge_batch WHERE batch_code = ?",
                        String.class, batch));
                assertEquals(1, count("handover"));
            } else {
                // 作废先提交：交接只能看到已作废读数，必须按缺有效终测拒绝
                RejectionException rejection = (RejectionException) handoverResult;
                assertEquals(List.of("C-N1"), rejection.cellCodes());
                assertEquals(0, count("handover"));
                assertEquals(Boolean.FALSE, jdbc.queryForObject(
                        "SELECT valid FROM discharge_reading WHERE reading_code = ?",
                        Boolean.class, reading));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}