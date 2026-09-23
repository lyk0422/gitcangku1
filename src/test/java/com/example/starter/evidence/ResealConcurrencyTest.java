package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.ResealDecisionRequest;
import com.example.starter.evidence.dto.ResealRequest;
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
 * 重新封存确认/撤销竞争及确认与借出竞争的并发测试（真实 H2 行锁与事务）：
 * 确认与撤销仅一个终态成功；确认先提交后借出才可能成功；任何顺序下不得出现
 * 申请已 CONFIRMED 而证物仍异常，或半条保管链。
 */
@SpringBootTest
class ResealConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void assert200(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private String brokenEvidence(String custodian) {
        String evidenceKey = uniqueKey("EV");
        assert200(controller.intake(custodian, new com.example.starter.evidence.dto.IntakeRequest(
                uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1")));
        assert200(controller.inspectSeal(custodian, evidenceKey,
                new com.example.starter.evidence.dto.SealInspectionRequest(
                        uniqueKey("CMD"), false, "封条破损")));
        return evidenceKey;
    }

    private String pendingReseal(String evidenceKey, String witness) {
        String resealKey = uniqueKey("RS");
        assert200(controller.applyReseal("alice", evidenceKey,
                new ResealRequest(uniqueKey("CMD"), resealKey, "SEAL-2", witness, "重新封存原因")));
        return resealKey;
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
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
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConfirmAndCancelExactlyOneTerminalWins() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = pendingReseal(evidenceKey, "bob");

        List<Integer> statuses = runConcurrently(
                () -> controller.confirmReseal("bob", evidenceKey,
                        new ResealDecisionRequest(uniqueKey("CMD"), resealKey)),
                () -> controller.cancelReseal("alice", evidenceKey,
                        new ResealDecisionRequest(uniqueKey("CMD"), resealKey)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.reseals()).hasSize(1);
        var reseal = chain.reseals().get(0);
        if (reseal.status() == ResealStatus.CONFIRMED) {
            // 确认先提交：证物恢复 SEALED 并换用新封条，撤销落败
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-2");
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        } else {
            // 撤销先提交：仍异常、封条不变，确认落败
            assertThat(reseal.status()).isEqualTo(ResealStatus.CANCELLED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
            assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-1");
        }
    }

    @Test
    void concurrentSameConfirmCommandKeyReplaysSingleResult() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = pendingReseal(evidenceKey, "bob");
        String commandKey = uniqueKey("CMD");
        ResealDecisionRequest request = new ResealDecisionRequest(commandKey, resealKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.confirmReseal("bob", evidenceKey, request),
                () -> controller.confirmReseal("bob", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.reseals()).hasSize(1);
        assertThat(chain.reseals().get(0).status()).isEqualTo(ResealStatus.CONFIRMED);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-2");
    }

    @Test
    void concurrentConfirmAndBorrowResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        try {
            String evidenceKey = brokenEvidence("alice");
            String resealKey = pendingReseal(evidenceKey, "bob");
            LocalDateTime dueAt = LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC);

            List<Integer> statuses = runConcurrently(
                    () -> controller.confirmReseal("bob", evidenceKey,
                            new ResealDecisionRequest(uniqueKey("CMD"), resealKey)),
                    () -> controller.borrow("alice", evidenceKey,
                            new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"),
                                    "carol", "鉴定用", dueAt)));

            // 借出先拿锁时看到 SEAL_BROKEN 返回 422；确认先提交后借出看到 SEALED 成功（200,200）。
            assertThat(statuses).allMatch(s -> s == 200 || s == 422);
            assertThat(statuses.stream().filter(s -> s == 200).count()).isGreaterThanOrEqualTo(1);

            var chain = evidenceService.custodyChain(evidenceKey);
            assertThat(chain.reseals()).hasSize(1);
            assertThat(chain.reseals().get(0).status()).isEqualTo(ResealStatus.CONFIRMED);
            // 申请确认与证物恢复必须同事务：CONFIRMED 后封条一定已换为新封条。
            assertThat(chain.evidence().sealNo()).isEqualTo("SEAL-2");
            boolean activeLoan = chain.loans().stream()
                    .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
            // 确认先提交后借出才成功（证物随之 BORROWED）；借出先拿锁则看到异常被 422 拒绝。
            if (activeLoan) {
                assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
                assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            } else {
                assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            }
        } finally {
            evidenceClock.setClock(Clock.systemUTC());
        }
    }
}
