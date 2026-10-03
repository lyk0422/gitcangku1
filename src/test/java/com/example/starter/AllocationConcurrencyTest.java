package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.allocation.AllocationService;
import com.example.starter.allocation.AllocationStatus;
import com.example.starter.allocation.SlotAllocation;
import com.example.starter.allocation.dto.AllocationRequest;
import com.example.starter.allocation.dto.CancelRequest;
import com.example.starter.allocation.dto.CompletionRequest;
import com.example.starter.common.ApiException;
import com.example.starter.common.ErrorReason;
import com.example.starter.prediction.PredictionService;
import com.example.starter.prediction.dto.PredictionRequest;

/**
 * 并发边界测试：真实线程竞争同站天线、幂等键、完成/取消与预测修订，
 * 协调同时起跑并设置超时，核对业务结果与最终数据。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:conctest;MODE=MySQL;DB_CLOSE_DELAY=-1")
class AllocationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private PredictionService predictionService;

    @Autowired
    private AllocationService allocationService;

    private void createPrediction(String key) {
        predictionService.create(key, new PredictionRequest("SAT-1",
                Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T12:10:00Z"),
                new BigDecimal("10"), 500));
    }

    private AllocationRequest request(String requestId, String key) {
        return new AllocationRequest(requestId, key, null, "A",
                Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T12:10:00Z"));
    }

    private <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<T> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return task.apply(index);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    @Test
    void concurrentOverlappingAllocationsAllowExactlyOne() throws Exception {
        createPrediction("P-C1");
        List<Object> results = runConcurrently(8, i -> {
            try {
                return (Object) allocationService.allocate(request("req-c1-" + i, "P-C1"));
            } catch (ApiException e) {
                return e;
            }
        });
        long confirmed = results.stream().filter(r -> r instanceof SlotAllocation).count();
        long overlaps = results.stream().filter(r -> r instanceof ApiException e
                && e.reason() == ErrorReason.ANTENNA_OVERLAP).count();
        assertThat(confirmed).isEqualTo(1);
        assertThat(overlaps).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE status = 'CONFIRMED'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdYieldsSingleRecord() throws Exception {
        createPrediction("P-C2");
        List<Object> results = runConcurrently(8, i -> {
            try {
                return (Object) allocationService.allocate(request("req-shared", "P-C2"));
            } catch (ApiException e) {
                return e;
            }
        });
        assertThat(results).allMatch(r -> r instanceof SlotAllocation);
        long distinctIds = results.stream().map(r -> ((SlotAllocation) r).id()).distinct().count();
        assertThat(distinctIds).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class))
                .isEqualTo(1);
    }

    @Test
    void completeAndCancelRaceLeavesExactlyOneTerminalState() throws Exception {
        createPrediction("P-C3");
        SlotAllocation allocation = allocationService.allocate(request("req-c3", "P-C3"));
        List<Object> results = runConcurrently(2, i -> {
            try {
                if (i == 0) {
                    return (Object) allocationService.complete(allocation.id(),
                            new CompletionRequest("cmp-race", 2048));
                }
                return (Object) allocationService.cancel(allocation.id(),
                        new CancelRequest("任务取消"));
            } catch (ApiException e) {
                return e;
            }
        });
        long succeeded = results.stream().filter(r -> r instanceof SlotAllocation).count();
        long rejected = results.stream().filter(r -> r instanceof ApiException e
                && e.reason() == ErrorReason.INVALID_STATE).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        SlotAllocation reloaded = allocationService.findById(allocation.id());
        if (reloaded.status() == AllocationStatus.COMPLETED) {
            assertThat(reloaded.actualBytes()).isEqualTo(2048);
            assertThat(reloaded.cancelReason()).isNull();
        } else {
            assertThat(reloaded.status()).isEqualTo(AllocationStatus.CANCELLED);
            assertThat(reloaded.cancelReason()).isEqualTo("任务取消");
            assertThat(reloaded.actualBytes()).isNull();
        }
    }

    @Test
    void revisionAndStaleConfirmationNeverLockAntennaWithExpiredPrediction() throws Exception {
        createPrediction("P-C4");
        AllocationRequest staleRequest = new AllocationRequest("req-c4", "P-C4", 1, "A",
                Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T12:10:00Z"));
        List<Object> results = runConcurrently(2, i -> {
            try {
                if (i == 0) {
                    return (Object) allocationService.allocate(staleRequest);
                }
                return (Object) predictionService.revise("P-C4", new PredictionRequest("SAT-1",
                        Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T12:10:00Z"),
                        new BigDecimal("10"), 500));
            } catch (ApiException e) {
                return e;
            }
        });
        Integer currentVersion = jdbc.queryForObject(
                "SELECT current_version FROM prediction_series WHERE prediction_key = 'P-C4'",
                Integer.class);
        assertThat(currentVersion).isEqualTo(2);
        Object allocationResult = results.get(0);
        if (allocationResult instanceof ApiException e) {
            // 修订先提交：过期版本必须被拒绝
            assertThat(e.reason()).isEqualTo(ErrorReason.STALE_PREDICTION);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class))
                    .isZero();
        } else {
            // 分配先提交：确认的是当时生效版本，修订后记录仍指向合法快照
            SlotAllocation saved = allocationService.findById(
                    ((SlotAllocation) allocationResult).id());
            assertThat(saved.predictionVersion()).isEqualTo(1);
            assertThat(saved.status()).isEqualTo(AllocationStatus.CONFIRMED);
        }
    }
}