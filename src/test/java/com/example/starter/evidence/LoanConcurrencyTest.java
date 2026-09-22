package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
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
 * 借出并发与幂等边界测试（真实 H2 行锁）：
 * 借出与发起交接、归还与再次借出并发时按事务提交顺序串行生效；
 * 不允许同时存在 ACTIVE 借出与 PENDING 交接，也不允许归还记录成功而证物状态未变。
 */
@SpringBootTest
class LoanConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private ApplicationClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void fixClock() {
        clock.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private LoanCreateRequest loanRequest(String loanKey, String borrower) {
        return new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "lab analysis",
                LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));
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
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLoanAndTransferInitiateNeverOverlap() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String loanKey = uniqueKey("LOAN");

        List<Integer> statuses = runConcurrently(
                () -> controller.createLoan("alice", evidenceKey, loanRequest(loanKey, "bob")),
                () -> controller.initiateTransfer("alice", evidenceKey,
                        new TransferInitiateRequest(uniqueKey("CMD"), "carol")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        boolean hasActiveLoan = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        boolean hasPendingTransfer = chain.transfers().stream()
                .anyMatch(transfer -> transfer.status() == TransferStatus.PENDING);
        // 核心不变量：不得同时外借与待交接
        assertThat(hasActiveLoan && hasPendingTransfer).isFalse();

        if (chain.evidence().status() == EvidenceStatus.BORROWED) {
            assertThat(hasActiveLoan).isTrue();
            assertThat(hasPendingTransfer).isFalse();
            assertThat(chain.evidence().custodianId()).isEqualTo("alice");
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
            assertThat(hasPendingTransfer).isTrue();
            assertThat(hasActiveLoan).isFalse();
        }
    }

    @Test
    void concurrentReturnAndNewLoanSerializeByCommitOrder() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String loanKey1 = uniqueKey("LOAN");
        String loanKey2 = uniqueKey("LOAN");
        ResponseEntity<String> loaned = controller.createLoan("alice", evidenceKey,
                loanRequest(loanKey1, "bob"));
        assertThat(loaned.getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> controller.returnLoan("alice", evidenceKey,
                        new LoanReturnRequest(uniqueKey("CMD"), loanKey1, true, "returned intact")),
                () -> controller.createLoan("alice", evidenceKey, loanRequest(loanKey2, "carol")));

        // 归还先提交则两笔都 200（串行：归还后再借出）；再借出先提交则借出 409、归还 200
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isGreaterThanOrEqualTo(1);

        var chain = evidenceService.custodyChain(evidenceKey);
        var firstLoan = chain.loans().stream()
                .filter(loan -> loan.loanKey().equals(loanKey1)).findFirst().orElseThrow();
        // 第一笔借出必须已归还，归还封条核验记录同事务落库
        assertThat(firstLoan.status()).isEqualTo(LoanStatus.RETURNED);
        assertThat(firstLoan.sealIntact()).isTrue();
        assertThat(chain.inspections()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(chain.inspections().get(0).passed()).isTrue();

        boolean hasActiveLoan = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        // 核心不变量：不允许归还记录成功而证物状态未变——BORROWED 必须对应一笔 ACTIVE 借出
        if (chain.evidence().status() == EvidenceStatus.BORROWED) {
            assertThat(hasActiveLoan).isTrue();
            assertThat(chain.loans()).hasSize(2);
            assertThat(chain.loans().get(1).loanKey()).isEqualTo(loanKey2);
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(hasActiveLoan).isFalse();
        }
    }

    @Test
    void concurrentSameCommandKeyCreateLoanReplaysSingleResult() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        String loanKey = uniqueKey("LOAN");
        // 同 commandKey 同参（LoanCreateRequest 内含 commandKey）
        LoanCreateRequest sameKeyRequest = new LoanCreateRequest(commandKey, loanKey, "bob",
                "lab analysis", LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));

        List<Integer> statuses = runConcurrently(
                () -> controller.createLoan("alice", evidenceKey, sameKeyRequest),
                () -> controller.createLoan("alice", evidenceKey, sameKeyRequest));

        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).loanKey()).isEqualTo(loanKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
    }

    @Test
    void concurrentSameCommandKeyReturnReplaysWithoutSecondInspection() throws Exception {
        fixClock();
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String loanKey = uniqueKey("LOAN");
        controller.createLoan("alice", evidenceKey, loanRequest(loanKey, "bob"));
        String commandKey = uniqueKey("CMD");
        LoanReturnRequest request = new LoanReturnRequest(commandKey, loanKey, true, "ok");

        List<Integer> statuses = runConcurrently(
                () -> controller.returnLoan("alice", evidenceKey, request),
                () -> controller.returnLoan("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
        // 重放不得产生第二条封条核验记录
        assertThat(chain.inspections()).hasSize(1);
    }

    @Test
    void duplicateLoanKeyInsertRollsBackEvidenceStatus() throws Exception {
        fixClock();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String loanKey = uniqueKey("LOAN");
        assertThat(controller.createLoan("alice", ev1, loanRequest(loanKey, "bob"))
                .getStatusCode().value()).isEqualTo(200);

        // 跨证物复用 loanKey：唯一约束冲突导致整笔事务回滚，ev2 必须仍为 SEALED
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> controller.createLoan("alice", ev2, loanRequest(loanKey, "carol")));
        assertThat(ex.status().value()).isEqualTo(409);

        var chain = evidenceService.custodyChain(ev2);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).isEmpty();
        var chain1 = evidenceService.custodyChain(ev1);
        assertThat(chain1.loans()).hasSize(1);
        assertThat(chain1.loans().get(0).evidenceKey()).isEqualTo(ev1);
    }
}
