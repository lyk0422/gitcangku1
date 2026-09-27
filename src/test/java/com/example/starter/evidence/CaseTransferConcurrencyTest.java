package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.TransferOrderRequest;
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
 * 跨案移交并发与幂等边界测试：同一证物并发移交最多一笔成功；
 * 移交与借出按提交顺序裁决；并发撤销最多一笔成功；并发同键重放结果一致。
 */
@SpringBootTest
class CaseTransferConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private CaseTransferController caseTransferController;

    @Autowired
    private CaseTransferService caseTransferService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String registerValidOrder(String sourceCase, String targetCase) {
        String orderVersion = uniqueKey("ORD");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        ResponseEntity<String> response = caseTransferController.registerOrder("admin",
                new TransferOrderRequest(uniqueKey("CMD"), orderVersion, sourceCase, targetCase,
                        nowUtc.minusHours(1), nowUtc.plusHours(1)));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return orderVersion;
    }

    private CaseTransferRequest transferRequest(String commandKey, String sourceCase,
                                                String targetCase, String orderVersion,
                                                String sourceCustodian, String targetCustodian,
                                                List<String> evidenceKeys) {
        return new CaseTransferRequest(commandKey, sourceCase, targetCase, orderVersion,
                sourceCustodian, targetCustodian, evidenceKeys);
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
    void concurrentTransfersOfSameEvidenceExactlyOneSucceeds() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String caseC = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderB = registerValidOrder(caseA, caseB);
        String orderC = registerValidOrder(caseA, caseC);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice",
                        transferRequest(uniqueKey("CMD"), caseA, caseB, orderB, "alice", "bob",
                                List.of(ev))),
                () -> caseTransferController.transfer("alice",
                        transferRequest(uniqueKey("CMD"), caseA, caseC, orderC, "alice", "carol",
                                List.of(ev))));

        // 同一证物并发移交最多一笔成功；失败方因证物已不属于来源案件得到 422
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 最终归属唯一且链完整：只有一个目标案件存在移入链
        var state = evidenceController.custodyChain(ev).evidence();
        String finalCase = state.caseKey();
        assertThat(finalCase).isIn(caseB, caseC);
        assertThat(caseTransferService.chainByCase(finalCase)).hasSize(1);
        String loserCase = finalCase.equals(caseB) ? caseC : caseB;
        assertThat(caseTransferService.chainByCase(loserCase)).isEmpty();
        assertThat(caseTransferService.historyByEvidence(ev)).hasSize(1);
    }

    @Test
    void concurrentTransferAndBorrowAdjudicatedInSubmissionOrder() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String order = registerValidOrder(caseA, caseB);
        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice",
                        transferRequest(uniqueKey("CMD"), caseA, caseB, order, "alice", "bob",
                                List.of(ev))),
                () -> evidenceController.borrow("alice", ev,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-9",
                                "review", dueAt)));

        // 恰有一个成功：借出先提交则移交 422；移交先提交则借出 409（保管人已切换）
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var state = evidenceController.custodyChain(ev).evidence();
        if (state.status() == EvidenceStatus.BORROWED) {
            // 借出先提交：证物仍在来源案件，移交未写入任何链
            assertThat(state.caseKey()).isEqualTo(caseA);
            assertThat(caseTransferService.chainByCase(caseB)).isEmpty();
        } else {
            // 移交先提交：证物在目标案件且在库，无借出记录
            assertThat(state.caseKey()).isEqualTo(caseB);
            assertThat(state.status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(caseTransferService.chainByCase(caseB)).hasSize(1);
        }
    }

    @Test
    void concurrentDuplicateTransferWithSameCommandKeyReplaysSingleResult() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String order = registerValidOrder(caseA, caseB);
        String commandKey = uniqueKey("CMD");
        CaseTransferRequest request = transferRequest(commandKey, caseA, caseB, order,
                "alice", "bob", List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice", request),
                () -> caseTransferController.transfer("alice", request));

        assertThat(statuses).containsOnly(200);
        // 只写入一笔移交
        assertThat(caseTransferService.historyByEvidence(ev)).hasSize(1);
        assertThat(caseTransferService.chainByCase(caseB)).hasSize(1);
    }

    @Test
    void concurrentRevokeExactlyOneSucceeds() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String order = registerValidOrder(caseA, caseB);
        ResponseEntity<String> transferred = caseTransferController.transfer("alice",
                transferRequest(uniqueKey("CMD"), caseA, caseB, order, "alice", "bob", List.of(ev)));
        assertThat(transferred.getStatusCode().value()).isEqualTo(200);
        String transferId = caseTransferService.historyByEvidence(ev).get(0).transferId();

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.revoke("alice", transferId,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob")),
                () -> caseTransferController.revoke("alice", transferId,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 反向链只追加一次；证物归属切回来源案件
        assertThat(caseTransferService.chainByCase(caseA).stream()
                .map(e -> e.entryType()).toList())
                .containsExactly(ChainEntryType.MOVE_OUT, ChainEntryType.REVOKE_IN);
        assertThat(caseTransferService.chainByCase(caseB).stream()
                .map(e -> e.entryType()).toList())
                .containsExactly(ChainEntryType.MOVE_IN, ChainEntryType.REVOKE_OUT);
        var state = evidenceController.custodyChain(ev).evidence();
        assertThat(state.caseKey()).isEqualTo(caseA);
        assertThat(state.custodianId()).isEqualTo("alice");
        assertThat(caseTransferService.detail(transferId).transfer().status())
                .isEqualTo(CaseTransferStatus.REVOKED);
    }
}
