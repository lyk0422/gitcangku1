package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferExecuteRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.TransferOrderRegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 跨案移交并发边界测试：同一证物并发移交最多一笔成功；移交与借出按提交顺序裁决；
 * 同键并发重放只产生一批链与快照；并发撤销最多一笔成功。真实 H2 行锁与事务验证。
 */
@SpringBootTest
class CaseTransferConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private CaseTransferController caseTransferController;

    @Autowired
    private CaseTransferService caseTransferService;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void registerOrder(String actor, String orderVersion, String sourceCase, String targetCase) {
        ResponseEntity<String> response = caseTransferController.registerOrder(actor,
                new TransferOrderRegisterRequest(uniqueKey("CMD"), orderVersion, sourceCase, targetCase));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private CaseTransferExecuteRequest transferRequest(String requestId, String sourceCase,
                                                       String targetCase, String orderVersion,
                                                       String srcCustodian, String tgtCustodian,
                                                       List<String> evidenceKeys) {
        return new CaseTransferExecuteRequest(requestId, sourceCase, targetCase, orderVersion,
                srcCustodian, tgtCustodian, evidenceKeys);
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
    void concurrentTransfersOfSameEvidenceExactlyOneWins() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceCase = "CASE-S-" + suffix;
        String targetCase1 = "CASE-T1-" + suffix;
        String targetCase2 = "CASE-T2-" + suffix;
        String order1 = "ORD1-" + suffix;
        String order2 = "ORD2-" + suffix;
        String src = "src-" + suffix;
        String tgt1 = "tgt1-" + suffix;
        String tgt2 = "tgt2-" + suffix;
        String ev = uniqueKey("EV");
        intake(src, ev, sourceCase);
        intake(tgt1, uniqueKey("EV-SEED"), targetCase1);
        intake(tgt2, uniqueKey("EV-SEED"), targetCase2);
        registerOrder("registrar", order1, sourceCase, targetCase1);
        registerOrder("registrar", order2, sourceCase, targetCase2);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("op",
                        transferRequest(uniqueKey("REQ"), sourceCase, targetCase1, order1,
                                src, tgt1, List.of(ev))),
                () -> caseTransferController.transfer("op",
                        transferRequest(uniqueKey("REQ"), sourceCase, targetCase2, order2,
                                src, tgt2, List.of(ev))));

        // 同一证物并发移交最多一笔成功；失败者看到证物已不属于来源案件（422）
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 最终状态一致：证物只进入一个目标案件，链与快照成对完整
        List<CaseTransferView> history = caseTransferService.listByEvidence(ev);
        assertThat(history).hasSize(1);
        CaseTransferView view = history.get(0);
        var chain = evidenceController.custodyChain(ev);
        assertThat(chain.evidence().caseKey()).isEqualTo(view.targetCaseKey());
        assertThat(chain.evidence().custodianId()).isEqualTo(view.items().get(0).targetCustodianId());
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        var diag = caseTransferService.diagnostics(view.transferId());
        assertThat(diag.consistent()).isTrue();
        assertThat(diag.balanced()).isTrue();
    }

    @Test
    void concurrentTransferAndBorrowExactlyOneWins() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceCase = "CASE-S-" + suffix;
        String targetCase = "CASE-T-" + suffix;
        String order = "ORD-" + suffix;
        String src = "src-" + suffix;
        String tgt = "tgt-" + suffix;
        String ev = uniqueKey("EV");
        intake(src, ev, sourceCase);
        intake(tgt, uniqueKey("EV-SEED"), targetCase);
        registerOrder("registrar", order, sourceCase, targetCase);
        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("op",
                        transferRequest(uniqueKey("REQ"), sourceCase, targetCase, order,
                                src, tgt, List.of(ev))),
                () -> evidenceController.borrow(src, ev,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-1",
                                "鉴定用", dueAt)));

        // 借出先提交则移交 422；移交先提交则借出 409（保管人已切换）
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        List<CaseTransferView> history = caseTransferService.listByEvidence(ev);
        var chain = evidenceController.custodyChain(ev);
        if (history.size() == 1) {
            // 移交先提交：证物在目标案件且未被借出
            assertThat(chain.evidence().caseKey()).isEqualTo(targetCase);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.loans()).isEmpty();
        } else {
            // 借出先提交：证物仍在来源案件且为借出状态，无跨案移交链
            assertThat(chain.evidence().caseKey()).isEqualTo(sourceCase);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.loans()).hasSize(1);
        }
    }

    @Test
    void concurrentDuplicateTransferWithSameRequestIdReplaysSingleBatch() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceCase = "CASE-S-" + suffix;
        String targetCase = "CASE-T-" + suffix;
        String order = "ORD-" + suffix;
        String src = "src-" + suffix;
        String tgt = "tgt-" + suffix;
        String ev = uniqueKey("EV");
        intake(src, ev, sourceCase);
        intake(tgt, uniqueKey("EV-SEED"), targetCase);
        registerOrder("registrar", order, sourceCase, targetCase);
        String requestId = uniqueKey("REQ");
        CaseTransferExecuteRequest request = transferRequest(requestId, sourceCase, targetCase,
                order, src, tgt, List.of(ev));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<ResponseEntity<String>> responses;
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> call = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return caseTransferController.transfer("op", request);
            };
            Future<ResponseEntity<String>> f1 = executor.submit(call);
            Future<ResponseEntity<String>> f2 = executor.submit(call);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            responses = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses).allMatch(r -> r.getStatusCode().value() == 200);
        // 同键同参并发：两次返回同一首次快照（同一 transferId）
        assertThat(responses.get(0).getBody()).isEqualTo(responses.get(1).getBody());
        JsonNode body = objectMapper.readTree(responses.get(0).getBody());
        String transferId = body.get("transferId").asText();
        // 只产生一批双案链与快照
        var diag = caseTransferService.diagnostics(transferId);
        assertThat(diag.snapshotCount()).isEqualTo(1);
        assertThat(diag.outCount()).isEqualTo(1);
        assertThat(diag.inCount()).isEqualTo(1);
        assertThat(diag.consistent()).isTrue();
        assertThat(caseTransferService.listByEvidence(ev)).hasSize(1);
    }

    @Test
    void concurrentRevokesExactlyOneWins() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceCase = "CASE-S-" + suffix;
        String targetCase = "CASE-T-" + suffix;
        String order = "ORD-" + suffix;
        String src = "src-" + suffix;
        String tgt = "tgt-" + suffix;
        String ev = uniqueKey("EV");
        intake(src, ev, sourceCase);
        intake(tgt, uniqueKey("EV-SEED"), targetCase);
        registerOrder("registrar", order, sourceCase, targetCase);
        ResponseEntity<String> transferred = caseTransferController.transfer("op",
                transferRequest(uniqueKey("REQ"), sourceCase, targetCase, order, src, tgt, List.of(ev)));
        assertThat(transferred.getStatusCode().value()).isEqualTo(200);
        String transferId = objectMapper.readTree(transferred.getBody()).get("transferId").asText();

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.revokeTransfer("op", transferId,
                        new CaseTransferRevokeRequest(uniqueKey("REQ"), src, tgt)),
                () -> caseTransferController.revokeTransfer("op", transferId,
                        new CaseTransferRevokeRequest(uniqueKey("REQ"), src, tgt)));

        // 并发撤销最多一笔成功
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        CaseTransferView view = caseTransferService.getTransfer(transferId);
        assertThat(view.status()).isEqualTo(CaseTransferService.STATUS_REVOKED);
        // 反向链只追加一次
        long reverseCount = view.chain().stream()
                .filter(entry -> entry.direction().startsWith("REVOKE")).count();
        assertThat(reverseCount).isEqualTo(2);
        // 证物退回来源案件
        var chain = evidenceController.custodyChain(ev);
        assertThat(chain.evidence().caseKey()).isEqualTo(sourceCase);
        assertThat(chain.evidence().custodianId()).isEqualTo(src);
    }
}
