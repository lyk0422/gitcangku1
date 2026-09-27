package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseCustodianGrantRequest;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.TransferOrderCreateRequest;
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
 * 并发撤销最多一笔成功；同 requestId 并发重放返回同一快照。
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

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 标准场景：caseA（alice 保管，一件证物）、caseB（bob 保管）、有效移交令。
     */
    private record Scenario(String caseA, String caseB, String orderVersion, String evidenceKey) {
    }

    private Scenario newScenario() {
        String caseA = uniqueKey("CASE-A");
        String caseB = uniqueKey("CASE-B");
        String orderVersion = uniqueKey("ORD");
        String evidenceKey = uniqueKey("EV");
        ResponseEntity<String> intake = evidenceController.intake("alice",
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseA, "DOCUMENT", "SEAL-1"));
        assertThat(intake.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> grant = caseTransferController.grantCustodian("bob",
                new CaseCustodianGrantRequest(uniqueKey("CMD"), caseB, "bob"));
        assertThat(grant.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> order = caseTransferController.createOrder("alice",
                new TransferOrderCreateRequest(uniqueKey("CMD"), orderVersion, caseA, caseB,
                        utcNow().minusHours(1), utcNow().plusHours(1)));
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        return new Scenario(caseA, caseB, orderVersion, evidenceKey);
    }

    private CaseTransferRequest transferRequest(Scenario s, String commandKey) {
        return new CaseTransferRequest(commandKey, s.caseA(), s.caseB(), s.orderVersion(),
                "alice", "bob", List.of(s.evidenceKey()));
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
        Scenario s = newScenario();

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice", transferRequest(s, uniqueKey("CMD"))),
                () -> caseTransferController.transfer("alice", transferRequest(s, uniqueKey("CMD"))));

        // 先提交者 201；后提交者持锁后发现证物已不属于来源案件，422
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        // 仅一笔移交落库
        assertThat(caseTransferService.listByCase(s.caseA())).hasSize(1);
        var chain = caseTransferService.chainByEvidence(s.evidenceKey());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).caseKey()).isEqualTo(s.caseA());
        assertThat(chain.get(1).caseKey()).isEqualTo(s.caseB());
    }

    @Test
    void concurrentSameCommandKeyTransferReplaysSingleSnapshot() throws Exception {
        Scenario s = newScenario();
        String commandKey = uniqueKey("CMD");
        CaseTransferRequest request = transferRequest(s, commandKey);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice", request),
                () -> caseTransferController.transfer("alice", request));

        assertThat(statuses).containsOnly(201);
        // 同一 transferId，仅一笔移交、两条链
        var transfers = caseTransferService.listByCase(s.caseA());
        assertThat(transfers).hasSize(1);
        assertThat(caseTransferService.chainByEvidence(s.evidenceKey())).hasSize(2);
    }

    @Test
    void concurrentRevokeExactlyOneSucceeds() throws Exception {
        Scenario s = newScenario();
        ResponseEntity<String> transferred = caseTransferController.transfer("alice",
                transferRequest(s, uniqueKey("CMD")));
        assertThat(transferred.getStatusCode().value()).isEqualTo(201);
        String transferKey = caseTransferService.listByCase(s.caseA()).get(0).transferKey();

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.revoke("alice", transferKey,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob")),
                () -> caseTransferController.revoke("bob", transferKey,
                        new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var detail = caseTransferService.detail(transferKey);
        assertThat(detail.transfer().status()).isEqualTo(CaseTransferStatus.REVOKED);
        // 反向链只追加一轮：2 条移交链 + 2 条反向链
        assertThat(detail.chain()).hasSize(4);
    }

    @Test
    void concurrentTransferAndBorrowExactlyOneWins() throws Exception {
        Scenario s = newScenario();

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice", transferRequest(s, uniqueKey("CMD"))),
                () -> evidenceController.borrow("alice", s.evidenceKey(),
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "carol",
                                "examination", utcNow().plusHours(2))));

        // 移交先提交则借出得到 409（保管人已切换）；借出先提交则移交得到 422（借出未归还）
        assertThat(statuses.stream().filter(s2 -> s2 == 200 || s2 == 201).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s2 -> s2 == 200 || s2 == 201 || s2 == 409 || s2 == 422);

        var chain = caseTransferService.chainByEvidence(s.evidenceKey());
        var evidence = evidenceController.custodyChain(s.evidenceKey()).evidence();
        if (chain.isEmpty()) {
            // 借出先提交：证物仍在来源案件且为借出状态
            assertThat(evidence.caseKey()).isEqualTo(s.caseA());
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.BORROWED);
        } else {
            // 移交先提交：证物归属目标案件，借出未发生
            assertThat(chain).hasSize(2);
            assertThat(evidence.caseKey()).isEqualTo(s.caseB());
            assertThat(evidence.custodianId()).isEqualTo("bob");
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.SEALED);
        }
    }
}
