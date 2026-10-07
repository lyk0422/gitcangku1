package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContactBatchRequest;
import com.example.starter.evidence.dto.ContaminationRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

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
 * 污染传播与接触批次并发边界测试：污染登记与新接触登记在同一行锁串行边界内，
 * 不允许扩散后插入漏管批次；同键并发重放只产生一次污染事件。
 */
@SpringBootTest
class ContactContaminationConcurrencyTest {

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String uniqueActor() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private long registerBatch(String actor, String... evidenceKeys) {
        ResponseEntity<String> response = controller.registerContactBatch(actor,
                new ContactBatchRequest(uniqueKey("CMD"), List.of(evidenceKeys)));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getBody()).get("eventSeq").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private boolean contaminated(String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey).evidence().contaminated();
    }

    @Test
    void concurrentContaminationAndContactBatchNeverLeavesUnmanagedBatch() throws Exception {
        String actor = uniqueActor();
        String evX = uniqueKey("EV-X");
        String evY = uniqueKey("EV-Y");
        String evZ = uniqueKey("EV-Z");
        intake(actor, evX);
        intake(actor, evY);
        intake(actor, evZ);
        long batch1 = registerBatch(actor, evX, evY);

        // 并发：从 X 在批次一污染 vs 登记新接触批次 {Y, Z}
        List<Integer> statuses = runConcurrently(
                () -> controller.registerContamination(actor,
                        new ContaminationRequest(uniqueKey("CMD"), evX, batch1)),
                () -> controller.registerContactBatch(actor,
                        new ContactBatchRequest(uniqueKey("CMD"), List.of(evY, evZ))));

        int contaminationStatus = statuses.get(0);
        int batchStatus = statuses.get(1);
        assertThat(contaminationStatus).isEqualTo(201);
        assertThat(contaminated(evX)).isTrue();
        assertThat(contaminated(evY)).isTrue();
        if (batchStatus == 201) {
            // 批次先提交：污染扫描到该批次，Z 必须一并隔离，不允许漏管
            assertThat(contaminated(evZ)).isTrue();
        } else {
            // 污染先提交：批次登记持锁后重新核对，Y 已污染则拒绝
            assertThat(batchStatus).isEqualTo(409);
            assertThat(contaminated(evZ)).isFalse();
        }
    }

    @Test
    void concurrentDuplicateContaminationWithSameCommandKeyProducesSingleEvent() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);
        String commandKey = uniqueKey("CMD");
        ContaminationRequest request = new ContaminationRequest(commandKey, evA, batch);

        List<ResponseEntity<String>> responses = new java.util.ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> call = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controller.registerContamination(actor, request);
            };
            Future<ResponseEntity<String>> f1 = executor.submit(call);
            Future<ResponseEntity<String>> f2 = executor.submit(call);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            responses.add(f1.get(15, TimeUnit.SECONDS));
            responses.add(f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        // 并发同键同参：都返回首次结果（同一污染事件），不暴露唯一键 500
        assertThat(responses).allMatch(r -> r.getStatusCode().value() == 201);
        assertThat(responses.get(0).getBody()).isEqualTo(responses.get(1).getBody());
        long contaminationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(responses.get(0).getBody()).get("contaminationId").asLong();
        var detail = evidenceService.contaminationDetail(contaminationId);
        assertThat(detail.contaminatedKeys()).containsExactlyInAnyOrder(evA, evB);
        assertThat(contaminated(evA)).isTrue();
        assertThat(contaminated(evB)).isTrue();
    }

    @Test
    void concurrentContaminationAndBorrowResolveInCommitOrder() throws Exception {
        String actor = uniqueActor();
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake(actor, evA);
        intake(actor, evB);
        long batch = registerBatch(actor, evA, evB);

        // 并发：从 A 污染 vs 借出 B（同批次成员，污染传播目标）
        List<Integer> statuses = runConcurrently(
                () -> controller.registerContamination(actor,
                        new ContaminationRequest(uniqueKey("CMD"), evA, batch)),
                () -> controller.borrow(actor, evB,
                        new com.example.starter.evidence.dto.LoanCreateRequest(
                                uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-1", "analysis",
                                java.time.LocalDateTime.now().plusHours(24))));

        int contaminationStatus = statuses.get(0);
        int borrowStatus = statuses.get(1);
        assertThat(contaminationStatus).isEqualTo(201);
        assertThat(borrowStatus).isIn(200, 422);
        // 无论谁先提交，B 最终必须被隔离；借出成功说明借出时污染尚未提交
        assertThat(contaminated(evB)).isTrue();
        var chain = evidenceService.custodyChain(evB);
        if (borrowStatus == 200) {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        }
    }
}
