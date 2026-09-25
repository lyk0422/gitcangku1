package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BatchIntakeItem;
import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量入库并发与幂等边界：与单件入库或其他批次并发时按事务提交顺序裁决，
 * 同一 evidenceKey 不得出现部分创建；同 requestId 并发重放只创建一次。
 */
@SpringBootTest
class BatchIntakeConcurrencyTest {

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private BatchIntakeRequest batchRequest(String requestId, List<String> evidenceKeys) {
        List<BatchIntakeItem> items = evidenceKeys.stream()
                .map(key -> new BatchIntakeItem(key, "证物-" + key, new BigDecimal("1.00")))
                .toList();
        List<BigDecimal> measured = evidenceKeys.stream()
                .map(key -> new BigDecimal("1.00"))
                .toList();
        return new BatchIntakeRequest(requestId, items, measured);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return call.call().getStatusCode().value();
            } catch (ApiException e) {
                return e.status().value();
            }
        }
    }

    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentBatchesWithOverlappingEvidenceKeyCreateExactlyOneBatch() throws Exception {
        String shared = uniqueKey("EV");
        String onlyA = uniqueKey("EV");
        String onlyB = uniqueKey("EV");
        BatchIntakeRequest batchA = batchRequest(uniqueKey("INTAKE"), List.of(shared, onlyA));
        BatchIntakeRequest batchB = batchRequest(uniqueKey("INTAKE"), List.of(shared, onlyB));

        List<Integer> statuses = runConcurrently(
                () -> controller.batchIntake("alice", batchA),
                () -> controller.batchIntake("bob", batchB));

        // 恰好一个批次整体成功；失败方整批回滚（预检 400 或并发冲突 400）
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);

        // 共享键只创建一次；失败方独有键不得部分创建
        assertThat(evidenceService.custodyChain(shared).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
        boolean aWon = statuses.get(0) == 201;
        String loserOnly = aWon ? onlyB : onlyA;
        String winnerOnly = aWon ? onlyA : onlyB;
        assertThat(evidenceService.custodyChain(winnerOnly).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
        try {
            evidenceService.custodyChain(loserOnly);
            throw new AssertionError("失败批次的独有证物不应被创建: " + loserOnly);
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(404);
        }
    }

    @Test
    void concurrentSameRequestIdReplaysSingleBatch() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        BatchIntakeRequest request = batchRequest(intakeKey, List.of(ev1, ev2));

        List<Integer> statuses = runConcurrently(
                () -> controller.batchIntake("alice", request),
                () -> controller.batchIntake("alice", request));

        assertThat(statuses).containsOnly(201);
        // 只创建一次：批次清单恰为 2 件
        assertThat(evidenceService.batchView(intakeKey).items()).hasSize(2);
    }

    @Test
    void concurrentBatchAndSingleIntakeOnSameKeyCreateExactlyOne() throws Exception {
        String shared = uniqueKey("EV");
        String batchOnly = uniqueKey("EV");
        BatchIntakeRequest batch = batchRequest(uniqueKey("INTAKE"), List.of(shared, batchOnly));
        IntakeRequest single = new IntakeRequest(uniqueKey("CMD"), shared,
                "CASE-1", "DOCUMENT", "SEAL-1");

        List<Integer> statuses = runConcurrently(
                () -> controller.batchIntake("alice", batch),
                () -> controller.intake("bob", single));

        // 恰好一方成功：单件 201 / 批量 201，失败方 400（批量预检或并发冲突）或 409（单件冲突）
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 400 || s == 409);

        // 共享键只存在一条记录；若批量失败，其独有键不得部分创建
        assertThat(evidenceService.custodyChain(shared).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
        if (statuses.get(0) != 201) {
            try {
                evidenceService.custodyChain(batchOnly);
                throw new AssertionError("失败批次的独有证物不应被创建: " + batchOnly);
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(404);
            }
        }
    }
}
