package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContactBatchCreateRequest;
import com.example.starter.evidence.dto.ContaminationCreateRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 接触批次与污染传播并发测试：批次登记与污染扩散共用事件序列串行边界，
 * 扩散提交后不允许插入漏管批次；同 requestId 并发重放只产生一条污染登记。
 */
@SpringBootTest
class ContactConcurrencyTest {

    @Autowired
    private ContactController contactController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ContactService contactService;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private ResponseEntity<String> registerBatch(String actor, String requestId, List<String> keys) {
        return contactController.registerBatch(actor, new ContactBatchCreateRequest(requestId, keys));
    }

    private ResponseEntity<String> contaminate(String actor, String requestId,
                                               String evidenceKey, long startSeq) {
        return contactController.registerContamination(actor,
                new ContaminationCreateRequest(requestId, evidenceKey, startSeq));
    }

    private long eventSeqOf(ResponseEntity<String> batchResponse) throws Exception {
        JsonNode body = objectMapper.readTree(batchResponse.getBody());
        return body.get("eventSeq").asLong();
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

    private EvidenceStatus statusOf(String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey).evidence().status();
    }

    @Test
    void concurrentBatchAndContaminationNeverLeaveUnmonitoredBatch() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String a = uniqueKey("EVA");
        String b = uniqueKey("EVB");
        String c = uniqueKey("EVC");
        intake(actor, a);
        intake(actor, b);
        intake(actor, c);
        // 已有批次{A,B}，其序号为污染起点
        long startSeq = eventSeqOf(registerBatch(actor, uniqueKey("REQ"), List.of(a, b)));

        String contaminationId = uniqueKey("REQ");
        String batchId = uniqueKey("REQ");
        List<Integer> statuses = runConcurrently(
                () -> contaminate(actor, contaminationId, a, startSeq),
                () -> registerBatch(actor, batchId, List.of(a, c)));

        // 污染登记不受并发批次影响，必定成功
        assertThat(statuses.get(0)).isEqualTo(201);
        assertThat(statusOf(a)).isEqualTo(EvidenceStatus.CONTAMINATED);
        assertThat(statusOf(b)).isEqualTo(EvidenceStatus.CONTAMINATED);

        int batchStatus = statuses.get(1);
        if (batchStatus == 201) {
            // 批次先提交：序号大于起点，污染扩散必须覆盖新批次成员 C
            assertThat(statusOf(c)).isEqualTo(EvidenceStatus.CONTAMINATED);
            assertThat(contactService.getBatch(batchId).evidenceKeys())
                    .containsExactlyInAnyOrder(a, c);
        } else {
            // 污染先提交：批次内已隔离成员使登记失败，C 不受影响
            assertThat(batchStatus).isEqualTo(409);
            assertThat(statusOf(c)).isEqualTo(EvidenceStatus.SEALED);
        }
    }

    @Test
    void concurrentSameContaminationRequestIdReplaysSingleResult() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String a = uniqueKey("EVA");
        String b = uniqueKey("EVB");
        String c = uniqueKey("EVC");
        intake(actor, a);
        intake(actor, b);
        intake(actor, c);
        long seq1 = eventSeqOf(registerBatch(actor, uniqueKey("REQ"), List.of(a, b)));
        registerBatch(actor, uniqueKey("REQ"), List.of(b, c));

        String requestId = uniqueKey("REQ");
        List<Integer> statuses = runConcurrently(
                () -> contaminate(actor, requestId, a, seq1),
                () -> contaminate(actor, requestId, a, seq1));

        assertThat(statuses).containsOnly(201);
        // 只产生一条污染登记，传播路径为 A、B、C 三条
        assertThat(contactService.getContamination(requestId).affected()).hasSize(3);
        assertThat(statusOf(a)).isEqualTo(EvidenceStatus.CONTAMINATED);
        assertThat(statusOf(b)).isEqualTo(EvidenceStatus.CONTAMINATED);
        assertThat(statusOf(c)).isEqualTo(EvidenceStatus.CONTAMINATED);
    }

    @Test
    void concurrentBatchRegistrationsGetDistinctSequentialEventSeqs() throws Exception {
        String actor = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String ev4 = uniqueKey("EV");
        intake(actor, ev1);
        intake(actor, ev2);
        intake(actor, ev3);
        intake(actor, ev4);

        String id1 = uniqueKey("REQ");
        String id2 = uniqueKey("REQ");
        List<Integer> statuses = runConcurrently(
                () -> registerBatch(actor, id1, List.of(ev1, ev2)),
                () -> registerBatch(actor, id2, List.of(ev3, ev4)));

        assertThat(statuses).containsOnly(201);
        long seq1 = contactService.getBatch(id1).eventSeq();
        long seq2 = contactService.getBatch(id2).eventSeq();
        assertThat(Math.abs(seq1 - seq2)).isEqualTo(1);
    }
}
