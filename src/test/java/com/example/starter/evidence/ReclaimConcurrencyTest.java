package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReclaimRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
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
 * 追缴相关并发边界测试（真实 H2 行锁与事务）：
 * 归还与追缴、追缴与追缴并发时按事务提交顺序裁决，恰有一个成功；
 * 同一借出人并发追缴的冻结计数不丢失。
 */
@SpringBootTest
class ReclaimConcurrencyTest {

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

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 在 BASE 时刻入库并借出（应还 BASE+1h），随后时钟推到 BASE+2h 形成逾期。
     */
    private String[] overdueLoan(String borrower) {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        ResponseEntity<String> borrowed = controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "鉴定用",
                        utc(BASE.plusSeconds(3600))));
        assertThat(borrowed.getStatusCode().value()).isEqualTo(200);
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(7200), ZoneOffset.UTC));
        return new String[]{evidenceKey, loanKey};
    }

    private Callable<ResponseEntity<String>> reclaim(String actor, String evidenceKey,
                                                     String commandKey, String reclaimKey,
                                                     String loanKey) {
        return () -> controller.reclaimLoan(actor, evidenceKey,
                new LoanReclaimRequest(commandKey, reclaimKey, loanKey, "并发追缴"));
    }

    private Callable<ResponseEntity<String>> returns(String actor, String evidenceKey,
                                                     String loanKey) {
        return () -> controller.returnLoan(actor, evidenceKey,
                new LoanReturnRequest(uniqueKey("CMD"), loanKey, true, "并发归还"));
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
    void concurrentReturnAndReclaimResolveByCommitOrder() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        // 归还与追缴并发（不同 commandKey，均为合法命令），按事务提交顺序裁决：
        // 归还先提交 → 追缴看到已归还返回 409；追缴先提交 → 归还看到已追缴返回 409。
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, loanKey),
                reclaim("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RCL"), loanKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.loans()).hasSize(1);
        var loan = chain.loans().get(0);
        if (loan.status() == LoanStatus.RETURNED) {
            // 归还先提交：证物回 SEALED，无追缴记录
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(evidenceService.listReclaimsByBorrower(loan.borrowerId())).isEmpty();
        } else {
            // 追缴先提交：借出 RECLAIMED 终态，证物在库待核验，追缴记录完整
            assertThat(loan.status()).isEqualTo(LoanStatus.RECLAIMED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.PENDING_INSPECTION);
            assertThat(evidenceService.listReclaimsByBorrower(loan.borrowerId())).hasSize(1);
        }
    }

    @Test
    void concurrentReclaimsOnSameLoanOnlyOneSucceeds() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        // 两个不同 reclaimKey 的追缴并发：恰有一个成功，只有一条追缴记录
        List<Integer> statuses = runConcurrently(
                reclaim("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RCL"), loanKey),
                reclaim("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RCL"), loanKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RECLAIMED);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.PENDING_INSPECTION);
        assertThat(evidenceService.listReclaimsByBorrower(borrower)).hasSize(1);
        // 冻结计数恰好为 1，不重复累计
        assertThat(evidenceService.borrowerFreezeStatus(borrower).reclaimCount()).isEqualTo(1);
    }

    @Test
    void concurrentSameReclaimCommandReplaysSingleResult() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        String commandKey = uniqueKey("CMD");
        String reclaimKey = uniqueKey("RCL");

        // 同一命令（同 commandKey 同参数）并发重放：均返回首次结果，只追缴一次
        List<Integer> statuses = runConcurrently(
                reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey),
                reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey));

        assertThat(statuses).containsOnly(200);
        assertThat(evidenceService.listReclaimsByBorrower(borrower)).hasSize(1);
        assertThat(evidenceService.borrowerFreezeStatus(borrower).reclaimCount()).isEqualTo(1);
    }

    @Test
    void concurrentReclaimsForSameBorrowerFreezeWithoutLostCount() throws Exception {
        String borrower = uniqueKey("bob");
        String[] first = overdueLoan(borrower);
        String[] second = overdueLoan(borrower);

        // 同一借出人两笔逾期借出并发追缴：均成功，计数不丢失，达到阈值自动冻结
        List<Integer> statuses = runConcurrently(
                reclaim("alice", first[0], uniqueKey("CMD"), uniqueKey("RCL"), first[1]),
                reclaim("alice", second[0], uniqueKey("CMD"), uniqueKey("RCL"), second[1]));

        assertThat(statuses).containsOnly(200);
        assertThat(evidenceService.listReclaimsByBorrower(borrower)).hasSize(2);

        var freeze = evidenceService.borrowerFreezeStatus(borrower);
        assertThat(freeze.reclaimCount()).isEqualTo(2);
        assertThat(freeze.frozen()).isTrue();
        assertThat(freeze.frozenBy()).isEqualTo("alice");
    }
}
