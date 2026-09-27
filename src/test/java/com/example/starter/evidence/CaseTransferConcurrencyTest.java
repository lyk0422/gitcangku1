package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferInitiateRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 跨案移交并发边界测试（真实 H2 行锁与事务，协调真实操作并设超时）：
 * 同一证物并发移交最多一笔成功；移交与借出、撤销与后续借出按提交顺序裁决；
 * 同 commandKey 并发返回同一首次结果。所有结论核对最终业务数据，不依赖打印或休眠。
 */
@SpringBootTest
class CaseTransferConcurrencyTest {

    @Autowired
    private CaseTransferController controller;
    @Autowired
    private EvidenceController evidenceController;
    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private CaseCustodianRepository caseCustodianRepository;
    @Autowired
    private TransferOrderRepository transferOrderRepository;

    private String caseA;
    private String caseB;

    @BeforeEach
    void setUp() {
        caseA = "CASE-A-" + UUID.randomUUID().toString().substring(0, 8);
        caseB = "CASE-B-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime wall = LocalDateTime.now();
        caseCustodianRepository.grant(caseA, "alice", wall);
        caseCustodianRepository.grant(caseB, "bob", wall);
        transferOrderRepository.register("ORDER-" + caseA, "v1", caseA, caseB,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1), null, wall);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String intake(String caseKey, String custodian) {
        String evidenceKey = uniqueKey("EV");
        ResponseEntity<String> response = evidenceController.intake(custodian,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1", "V-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return evidenceKey;
    }

    private Callable<ResponseEntity<String>> transfer(String fromCust, String toCust,
                                                      List<String> keys) {
        return () -> controller.initiate(fromCust, new CaseTransferInitiateRequest(
                uniqueKey("CMD"), caseA, caseB, "ORDER-" + caseA, "v1", fromCust, toCust, keys));
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
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        private StatusCall(CountDownLatch ready, CountDownLatch start,
                           Callable<ResponseEntity<String>> call) {
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

    @Test
    void concurrentTransfersOfSameEvidenceOnlyOneSucceeds() throws Exception {
        String ev = intake(caseA, "alice");
        List<Integer> statuses = runConcurrently(
                transfer("alice", "bob", List.of(ev)),
                transfer("alice", "bob", List.of(ev)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        var chain = evidenceService.custodyChain(ev);
        assertThat(chain.evidence().caseKey()).isEqualTo(caseB);
        assertThat(chain.evidence().custodianId()).isEqualTo("bob");
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        // 仅一次成功提交：一件证物两条链事件
        assertThat(chain.caseEvents()).hasSize(2);
    }

    @Test
    void concurrentTransferAndBorrowResolveByCommitOrder() throws Exception {
        String ev = intake(caseA, "alice");
        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);
        Callable<ResponseEntity<String>> borrow = () -> evidenceController.borrow("alice", ev,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "dave", "鉴定用", dueAt));

        List<Integer> statuses = runConcurrently(transfer("alice", "bob", List.of(ev)), borrow);

        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount).isEqualTo(1);

        var chain = evidenceService.custodyChain(ev);
        if (statuses.get(0) == 200 && chain.evidence().caseKey().equals(caseB)) {
            // 移交先提交：证物已到目标案，alice 的借出被拒
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.loans()).isEmpty();
        } else {
            // 借出先提交：证物 BORROWED 在来源案，移交被拒，后续归还后仍可移交
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.evidence().caseKey()).isEqualTo(caseA);
            assertThat(chain.caseEvents()).isEmpty();
        }
    }

    @Test
    void concurrentSameCommandKeyReturnsSingleTransfer() throws Exception {
        String ev = intake(caseA, "alice");
        String commandKey = uniqueKey("CMD");
        CaseTransferInitiateRequest request = new CaseTransferInitiateRequest(
                commandKey, caseA, caseB, "ORDER-" + caseA, "v1", "alice", "bob", List.of(ev));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> call = () -> controller.initiate("alice", request);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, call));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, call));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200);
        } finally {
            executor.shutdownNow();
        }

        var chain = evidenceService.custodyChain(ev);
        assertThat(chain.evidence().caseKey()).isEqualTo(caseB);
        assertThat(chain.caseEvents()).hasSize(2);
    }

    @Test
    void concurrentRevokeAndTargetBorrowResolveByCommitOrderWithoutHalfWrittenState() throws Exception {
        String ev = intake(caseA, "alice");
        ResponseEntity<String> transferred = controller.initiate("alice",
                new CaseTransferInitiateRequest(uniqueKey("CMD"), caseA, caseB,
                        "ORDER-" + caseA, "v1", "alice", "bob", List.of(ev)));
        assertThat(transferred.getStatusCode().value()).isEqualTo(200);
        long transferId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(transferred.getBody()).get("transferId").asLong();

        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);
        Callable<ResponseEntity<String>> revoke = () -> controller.revoke("bob", transferId,
                new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob"));
        Callable<ResponseEntity<String>> borrow = () -> evidenceController.borrow("bob", ev,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "dave", "鉴定用", dueAt));

        List<Integer> statuses = runConcurrently(revoke, borrow);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount).isEqualTo(1);

        var chain = evidenceService.custodyChain(ev);
        if (chain.evidence().caseKey().equals(caseA)) {
            // 撤销先提交：证物回到来源案，目标借出必须失败；反向链完整 4 条
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.loans()).isEmpty();
            assertThat(chain.caseEvents()).hasSize(4);
        } else {
            // 借出先提交：撤销被阻塞，证物 BORROWED 在目标案，无反向链半成品
            assertThat(chain.evidence().caseKey()).isEqualTo(caseB);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.caseEvents()).hasSize(2);
            assertThat(chain.loans()).hasSize(1);
        }
    }
}
