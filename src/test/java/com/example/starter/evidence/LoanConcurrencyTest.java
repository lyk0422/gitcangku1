package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import org.junit.jupiter.api.AfterEach;
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
 * 借出相关并发边界测试（真实 H2 行锁与事务）：
 * 借出与发起交接、归还与重复归还并发时按事务提交顺序处理，
 * 不得出现同时外借与待交接，或归还记录成功而证物状态未变。
 */
@SpringBootTest
class LoanConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private LocalDateTime due(long seconds) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new com.example.starter.evidence.dto.IntakeRequest(
                        uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> borrow(String actor, String evidenceKey,
                                                    String loanKey, String borrower) {
        return () -> controller.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "鉴定用", due(3600)));
    }

    private Callable<ResponseEntity<String>> initiate(String actor, String evidenceKey,
                                                      String toCustodian) {
        return () -> controller.initiateTransfer(actor, evidenceKey,
                new TransferInitiateRequest(uniqueKey("CMD"), toCustodian));
    }

    private Callable<ResponseEntity<String>> returns(String actor, String evidenceKey,
                                                     String loanKey, boolean sealIntact) {
        return () -> controller.returnLoan(actor, evidenceKey,
                new LoanReturnRequest(uniqueKey("CMD"), loanKey, sealIntact, "并发归还说明"));
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
    void concurrentBorrowAndTransferInitiateNeverCoexist() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob"),
                initiate("alice", evidenceKey, "carol"));

        // 恰有一个先提交成功，另一个按提交后的状态被拒绝（409）
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        // 不得同时外借与待交接：状态唯一，且无 ACTIVE 借出与 PENDING 交接并存
        boolean activeLoan = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        boolean pendingTransfer = chain.transfers().stream()
                .anyMatch(t -> t.status() == TransferStatus.PENDING);
        assertThat(activeLoan).isNotEqualTo(pendingTransfer);
        if (activeLoan) {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
        }
    }

    @Test
    void concurrentTwoBorrowsOnlyOneActive() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob"),
                borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol"));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void concurrentReturnsOnlyOneCompletesAndStateMatchesRecord() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        ResponseEntity<String> borrowed = controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, "bob", "鉴定用", due(3600)));
        assertThat(borrowed.getStatusCode().value()).isEqualTo(200);

        // 两个独立命令并发归还同一笔借出（不同 commandKey，非幂等重放）
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, loanKey, true),
                returns("alice", evidenceKey, loanKey, true));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        // 归还记录成功则证物必须同事务变为 SEALED；只有一笔借出、一条核验、一次归还
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
        assertThat(chain.loans().get(0).sealIntact()).isTrue();
        assertThat(chain.inspections()).hasSize(1);
    }

    @Test
    void returnAndNewBorrowResolveByCommitOrderWithoutInconsistentState() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String firstLoanKey = uniqueKey("LOAN");
        String secondLoanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        ResponseEntity<String> borrowed = controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), firstLoanKey, "bob", "鉴定用", due(3600)));
        assertThat(borrowed.getStatusCode().value()).isEqualTo(200);

        // 归还与再次借出并发（不同 commandKey，均为合法命令），按事务提交顺序处理：
        // 归还先提交 → 新借出看到 SEALED 成功；新借出先拿到锁 → 看到 BORROWED 返回 409。
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, firstLoanKey, true),
                borrow("alice", evidenceKey, secondLoanKey, "carol"));

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        long successCount = statuses.stream().filter(s -> s == 200).count();

        var chain = evidenceService.custodyChain(evidenceKey);
        if (successCount == 2) {
            // 归还先提交：旧借出 RETURNED + 新借出 ACTIVE，证物仍在外借，无丢记录
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.loans()).hasSize(2);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
            assertThat(chain.loans().get(1).status()).isEqualTo(LoanStatus.ACTIVE);
            assertThat(chain.loans().get(1).borrowerId()).isEqualTo("carol");
            assertThat(chain.inspections()).hasSize(1);
        } else {
            // 新借出先提交（正确拒绝 409）：归还随后成功，证物 SEALED，记录一致
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
            assertThat(chain.loans()).hasSize(1);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
            assertThat(chain.inspections()).hasSize(1);
        }
        // 任何顺序下都不允许出现归还记录成功而证物仍为 BORROWED 却无 ACTIVE 借出的错配
        boolean activeLoan = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        if (chain.evidence().status() == EvidenceStatus.BORROWED) {
            assertThat(activeLoan).isTrue();
        } else {
            assertThat(activeLoan).isFalse();
        }
    }

    @Test
    void concurrentSameReturnCommandKeyReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, "bob", "鉴定用", due(3600)));

        LoanReturnRequest request = new LoanReturnRequest(commandKey, loanKey, true, "幂等并发归还");
        List<Integer> statuses = runConcurrently(
                () -> controller.returnLoan("alice", evidenceKey, request),
                () -> controller.returnLoan("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(200);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.inspections()).hasSize(1);
    }
}
