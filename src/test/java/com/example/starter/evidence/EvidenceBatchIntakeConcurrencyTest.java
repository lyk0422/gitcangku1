package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.WeightReviewRequest;
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
 * 批量入库并发与幂等边界测试：同键并发重放不重复创建；
 * 批量与单件入库并发按事务提交顺序裁决，不出现同一 evidenceKey 部分创建；
 * 并发复核至多一次生效。
 */
@SpringBootTest
class EvidenceBatchIntakeConcurrencyTest {

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceRepository evidenceRepository;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private BatchIntakeRequest batchRequest(String intakeKey, String custodian,
                                            List<String> evidenceKeys) {
        List<BatchIntakeRequest.BatchIntakeItem> items = evidenceKeys.stream()
                .map(key -> new BatchIntakeRequest.BatchIntakeItem(key, "批量物证-" + key,
                        new BigDecimal("10.00")))
                .toList();
        List<BigDecimal> measured = evidenceKeys.stream()
                .map(key -> new BigDecimal("10.00"))
                .toList();
        return new BatchIntakeRequest(intakeKey, custodian, items, measured);
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
    void concurrentBatchIntakeWithSameKeyReplaysSingleResult() throws Exception {
        String intakeKey = uniqueKey("INTAKE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        BatchIntakeRequest request = batchRequest(intakeKey, "alice", List.of(ev1, ev2));

        List<Integer> statuses = runConcurrently(
                () -> controller.batchIntake(request),
                () -> controller.batchIntake(request));

        assertThat(statuses).containsOnly(201);
        // 重放未重复创建：批次恰为两件
        assertThat(evidenceService.batchView(intakeKey).items()).hasSize(2);
        assertThat(evidenceRepository.findByKey(ev1)).isPresent();
        assertThat(evidenceRepository.findByKey(ev2)).isPresent();
    }

    @Test
    void concurrentBatchAndSingleIntakeOnSameEvidenceKeyNoPartialCreation() throws Exception {
        String shared = uniqueKey("EV");
        String evOther = uniqueKey("EV");
        BatchIntakeRequest batch = batchRequest(uniqueKey("INTAKE"), "alice",
                List.of(shared, evOther));
        IntakeRequest single = new IntakeRequest(uniqueKey("CMD"), shared,
                "CASE-1", "DOCUMENT", "SEAL-1");

        List<Integer> statuses = runConcurrently(
                () -> controller.batchIntake(batch),
                () -> controller.intake("bob", single));

        // 按事务提交顺序裁决：恰有一个成功
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        if (statuses.get(0) == 201) {
            // 批量先提交：单件 409，批量两件全部存在
            assertThat(evidenceRepository.findByKey(evOther)).isPresent();
            assertThat(evidenceRepository.findByKey(shared).orElseThrow().custodianId())
                    .isEqualTo("alice");
        } else {
            // 单件先提交：批量整批回滚，另一件不得部分创建
            assertThat(evidenceRepository.findByKey(shared).orElseThrow().custodianId())
                    .isEqualTo("bob");
            assertThat(evidenceRepository.findByKey(evOther)).isEmpty();
        }
    }

    @Test
    void concurrentReviewClosesPendingExactlyOnce() throws Exception {
        String evBad = uniqueKey("EV");
        ResponseEntity<String> created = controller.batchIntake(
                new BatchIntakeRequest(uniqueKey("INTAKE"), "alice",
                        List.of(new BatchIntakeRequest.BatchIntakeItem(evBad, "偏轻",
                                new BigDecimal("10.00"))),
                        List.of(new BigDecimal("8.00"))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                () -> controller.reviewWeight("alice", evBad,
                        new WeightReviewRequest(uniqueKey("CMD"), "复称确认")),
                () -> controller.reviewWeight("alice", evBad,
                        new WeightReviewRequest(uniqueKey("CMD"), "复称确认")));

        // 复核不可逆：恰有一次生效，另一次冲突
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(evidenceRepository.findByKey(evBad).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.RESOLVED);
    }
}
