package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
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
 * 证物跨案移交并发边界测试（真实 H2 行锁与事务，无 Docker/外部服务）：
 * 同一证物并发跨案移交最多一笔成功；跨案移交与借出并发互斥；
 * 同 requestId 同参并发重放只固化首次快照；撤销并发只成功一次。
 * 用 CountDownLatch 协调两个真实操作同时开始，Future 设超时核对最终业务数据。
 * 所有时间来自固定时钟，不依赖真实等待。
 */
@SpringBootTest
class CaseTransferConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-27T09:00:00Z");

    @Autowired
    private CaseTransferController caseTransferController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private CaseTransferService caseTransferService;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private CaseCustodianGrantRepository grantRepository;

    @Autowired
    private TransferOrderRepository orderRepository;

    @Autowired
    private EvidenceClock evidenceClock;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private record CaseContext(String fromCase, String toCase, String orderNo) {
    }

    private CaseContext setupCases(String sourceCustodian, String targetCustodian) {
        String fromCase = uniqueKey("CASE-F");
        String toCase = uniqueKey("CASE-T");
        String orderNo = uniqueKey("ORDER");
        LocalDateTime now = LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
        grantRepository.insert(fromCase, sourceCustodian, now);
        grantRepository.insert(toCase, targetCustodian, now);
        orderRepository.insert(orderNo, 1, fromCase, toCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null, now);
        return new CaseContext(fromCase, toCase, orderNo);
    }

    private void intake(String actor, String evidenceKey, String caseKey, String location) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT",
                        "SEAL-" + evidenceKey, location));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private CaseTransferRequest transferRequest(CaseContext ctx, String fromCust, String toCust,
                                                List<String> evidenceKeys) {
        return new CaseTransferRequest(uniqueKey("CMD"), ctx.orderNo(), 1, ctx.fromCase(),
                ctx.toCase(), fromCust, toCust, evidenceKeys);
    }

    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start,
                   Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCaseTransfersOfSameEvidenceOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        CaseContext ctx = setupCases("alice", "bob");
        intake("alice", ev, ctx.fromCase(), "VAULT-C1");

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice",
                        transferRequest(ctx, "alice", "bob", List.of(ev))),
                () -> caseTransferController.transfer("alice",
                        transferRequest(ctx, "alice", "bob", List.of(ev))));

        // 同证物并发移交：恰一笔成功，另一笔 422（持锁后发现已不属来源案/已在其他移交中）
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        var chain = evidenceService.custodyChain(ev);
        assertThat(chain.evidence().caseKey()).isEqualTo(ctx.toCase());
        assertThat(chain.evidence().custodianId()).isEqualTo("bob");
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidence().sealVersion()).isEqualTo(2);
        var history = caseTransferService.history(ev);
        assertThat(history.transfers()).hasSize(1);
        assertThat(history.events()).hasSize(2);
        assertThat(history.snapshots()).hasSize(2);
    }

    @Test
    void concurrentCaseTransferAndBorrowNeverCoexist() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        CaseContext ctx = setupCases("alice", "bob");
        intake("alice", ev, ctx.fromCase(), "VAULT-C2");

        Callable<ResponseEntity<String>> transfer = () -> caseTransferController.transfer("alice",
                transferRequest(ctx, "alice", "bob", List.of(ev)));
        Callable<ResponseEntity<String>> borrow = () -> evidenceController.borrow("alice", ev,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "dave", "鉴定",
                        LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC)));

        List<Integer> statuses = runConcurrently(transfer, borrow);

        // 任一先提交成功；另一笔按提交后状态被拒绝（422 或 409），绝不二者都成功
        long success = statuses.stream().filter(s -> s == 200).count();
        assertThat(success).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var chain = evidenceService.custodyChain(ev);
        boolean activeLoan = chain.loans().stream().anyMatch(l -> l.status() == LoanStatus.ACTIVE);
        var history = caseTransferService.history(ev);
        boolean transferred = chain.evidence().caseKey().equals(ctx.toCase());
        // 外借与跨案移交不可并存
        assertThat(activeLoan).isNotEqualTo(transferred);
        assertThat(history.transfers().isEmpty() || history.transfers().size() == 1).isTrue();
        if (transferred) {
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(history.events()).isEmpty();
        }
    }

    @Test
    void concurrentCaseTransferAndFailedInspectionNeverLeaveHalfDoneState() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        CaseContext ctx = setupCases("alice", "bob");
        intake("alice", ev, ctx.fromCase(), "VAULT-C5");

        Callable<ResponseEntity<String>> transfer = () -> caseTransferController.transfer("alice",
                transferRequest(ctx, "alice", "bob", List.of(ev)));
        Callable<ResponseEntity<String>> inspect = () -> evidenceController.inspectSeal("alice", ev,
                new com.example.starter.evidence.dto.SealInspectionRequest(
                        uniqueKey("CMD"), false, "并发复核封签破损"));

        List<Integer> statuses = runConcurrently(transfer, inspect);

        // 恰一笔成功；另一笔按提交后状态被拒绝（移交方 422，复核方 409）
        long success = statuses.stream().filter(s -> s == 200).count();
        assertThat(success).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);

        var chain = evidenceService.custodyChain(ev);
        var history = caseTransferService.history(ev);
        if (chain.evidence().caseKey().equals(ctx.toCase())) {
            // 移交先提交：封签复核因保管人已切换失败，证物在目标案 SEALED，无破损
            assertThat(chain.evidence().custodianId()).isEqualTo("bob");
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(history.transfers()).hasSize(1);
        } else {
            // 失败复核先提交：封签异常，移交被 422 拒绝，来源案不变，无任何跨案链
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(chain.evidence().caseKey()).isEqualTo(ctx.fromCase());
            assertThat(history.events()).isEmpty();
            assertThat(history.snapshots()).isEmpty();
        }
    }

    @Test
    void concurrentSameRequestIdReplaysSingleSnapshotForCaseTransfer() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        CaseContext ctx = setupCases("alice", "bob");
        intake("alice", ev, ctx.fromCase(), "VAULT-C3");
        String commandKey = uniqueKey("CMD");
        CaseTransferRequest request = new CaseTransferRequest(commandKey, ctx.orderNo(), 1,
                ctx.fromCase(), ctx.toCase(), "alice", "bob", List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> caseTransferController.transfer("alice", request),
                () -> caseTransferController.transfer("alice", request));

        assertThat(statuses).containsOnly(200);
        var history = caseTransferService.history(ev);
        assertThat(history.transfers()).hasSize(1);
        assertThat(history.events()).hasSize(2);
        assertThat(history.snapshots()).hasSize(2);
    }

    @Test
    void concurrentRevokeOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        CaseContext ctx = setupCases("alice", "bob");
        intake("alice", ev, ctx.fromCase(), "VAULT-C4");
        ResponseEntity<String> transferred = caseTransferController.transfer("alice",
                transferRequest(ctx, "alice", "bob", List.of(ev)));
        assertThat(transferred.getStatusCode().value()).isEqualTo(200);
        String transferId = caseTransferService.history(ev).transfers().get(0).transferId();

        Callable<ResponseEntity<String>> revoke1 = () -> caseTransferController.revoke("alice",
                transferId, new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob"));
        Callable<ResponseEntity<String>> revoke2 = () -> caseTransferController.revoke("alice",
                transferId, new CaseTransferRevokeRequest(uniqueKey("CMD"), "alice", "bob"));

        List<Integer> statuses = runConcurrently(revoke1, revoke2);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var history = caseTransferService.history(ev);
        assertThat(history.transfers()).hasSize(1);
        assertThat(history.transfers().get(0).status()).isEqualTo(CaseTransferStatus.REVOKED);
        // 原 2 条链 + 撤销 2 条反向链，不重复追加
        assertThat(history.events()).hasSize(4);
        assertThat(history.evidence().caseKey()).isEqualTo(ctx.fromCase());
        assertThat(history.evidence().custodianId()).isEqualTo("alice");
    }
}
