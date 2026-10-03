package com.example.starter.well;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 并发场景的 H2 真实库测试：作废与审核竞争、并发审核只产生一个后继段、
 * 并发同编号申请的幂等与异参冲突。全部用真实线程与事务验证，并设超时。
 */
class ConcurrencyTest extends WellTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static <T> T join(Future<T> future) {
        try {
            return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("并发任务未在超时内完成或失败", e);
        }
    }

    @Test
    void voidAndReviewCompeteOnSameCommittedValidSet() throws Exception {
        newInjectionSegment("W-CC-1");
        seedFourValidSamples("W-CC-1");
        SwitchRequest request = switchService.apply("W-CC-1", "cc-1", WellSegment.MODE_PRODUCTION);
        Long sampleId = jdbc.queryForObject(
                "SELECT id FROM pressure_sample WHERE well_no = 'W-CC-1' AND request_id = 'W-CC-1-s2'",
                Long.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<SwitchRequest> reviewTask = pool.submit(() -> {
                ready.countDown();
                go.await();
                return switchService.review(request.id(), true, null, T0.plus(Duration.ofMinutes(1)));
            });
            Future<PressureSample> voidTask = pool.submit(() -> {
                ready.countDown();
                go.await();
                return wellService.voidSample(sampleId, "并发作废", "cc-1-fix",
                        new BigDecimal("10.0"), T0.minus(Duration.ofMinutes(40)));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            SwitchRequest decided = join(reviewTask);
            PressureSample voided = join(voidTask);
            assertTrue(voided.voided());

            if (SwitchRequest.STATUS_APPROVED.equals(decided.status())) {
                // 审核先提交：作废发生在审核之后，快照必须完整冻结 4 个原始读数。
                List<SampleSnapshot> snapshot = switchService.snapshotsOf(request.id());
                assertEquals(4, snapshot.size());
                assertTrue(snapshot.stream().anyMatch(s -> s.sampleId() == sampleId));
            } else {
                // 作废先提交：审核只能基于已提交有效集合，窗口内仅剩 3 个有效采样，必须拒绝。
                assertEquals(SwitchRequest.STATUS_REJECTED, decided.status());
                assertNotNull(decided.rejectReason());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReviewsCreateAtMostOneSuccessorSegment() throws Exception {
        newInjectionSegment("W-CC-2");
        seedFourValidSamples("W-CC-2");
        SwitchRequest first = switchService.apply("W-CC-2", "cc-2a", WellSegment.MODE_PRODUCTION);
        SwitchRequest second = switchService.apply("W-CC-2", "cc-2b", WellSegment.MODE_PRODUCTION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<String> taskA = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return switchService.review(first.id(), true, null,
                            T0.plus(Duration.ofMinutes(1))).status();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    return "CONFLICT";
                }
            });
            Future<String> taskB = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return switchService.review(second.id(), true, null,
                            T0.plus(Duration.ofMinutes(1))).status();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    return "CONFLICT";
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<String> outcomes = List.of(join(taskA), join(taskB));

            assertTrue(outcomes.contains(SwitchRequest.STATUS_APPROVED));
            assertTrue(outcomes.contains("CONFLICT"));
            assertEquals(2, wellService.timeline("W-CC-2").size());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM well_segment WHERE well_no = 'W-CC-2' AND end_utc IS NULL",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentApplyWithSameRequestIdStaysIdempotent() throws Exception {
        newInjectionSegment("W-CC-3");
        seedFourValidSamples("W-CC-3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Long> taskA = pool.submit(() -> {
                ready.countDown();
                go.await();
                return switchService.apply("W-CC-3", "cc-3", WellSegment.MODE_PRODUCTION).id();
            });
            Future<Long> taskB = pool.submit(() -> {
                ready.countDown();
                go.await();
                return switchService.apply("W-CC-3", "cc-3", WellSegment.MODE_PRODUCTION).id();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(join(taskA), join(taskB));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM switch_request WHERE well_no = 'W-CC-3'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}