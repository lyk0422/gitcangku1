package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReclaimRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 追缴与归还、追缴与追缴并发时按事务提交顺序裁决，
 * 不得出现追缴与归还同时生效，或同一借出被追缴两次。
 */
@SpringBootTest
class ReclaimConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

    @Autowired
    private EvidenceController controller;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 各场景共用同一内存库，逐场景清理，避免顺序依赖。
     */
    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM reclaim_record");
        jdbc.update("DELETE FROM unfreeze_record");
        jdbc.update("DELETE FROM seal_inspection");
        jdbc.update("DELETE FROM transfer_record");
        jdbc.update("DELETE FROM loan_record");
        jdbc.update("DELETE FROM evidence");
    }

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

    private void borrow(String actor, String evidenceKey, String loanKey, String borrower) {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        ResponseEntity<String> response = controller.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "鉴定用",
                        utc(BASE.plusSeconds(3600))));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 准备一笔已逾期 30 分钟的借出。
     */
    private String[] overdueLoan(String borrower) {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, borrower);
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(5400), ZoneOffset.UTC));
        return new String[]{evidenceKey, loanKey};
    }

    private Callable<ResponseEntity<String>> reclaim(String actor, String evidenceKey,
                                                     String reclaimKey, String loanKey) {
        return () -> controller.reclaim(actor, evidenceKey,
                new LoanReclaimRequest(reclaimKey, loanKey, "并发追缴"));
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
    void concurrentReclaimAndReturnResolveByCommitOrder() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        List<Integer> statuses = runConcurrently(
                reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey),
                returns("alice", evidenceKey, loanKey));

        var chain = evidenceService.custodyChain(evidenceKey);
        if (statuses.get(1) == 200) {
            // 归还先提交：追缴返回 422；证物 SEALED，借出 RETURNED，无追缴记录
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.loans()).hasSize(1);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
            assertThat(chain.inspections()).hasSize(1);
            assertThat(evidenceService.listReclaims(null)).isEmpty();
        } else {
            // 追缴先提交：归还对已追缴记录返回 409；证物待核验，借出 RECLAIMED，追缴记录一条
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.PENDING_INSPECTION);
            assertThat(chain.loans()).hasSize(1);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RECLAIMED);
            assertThat(chain.inspections()).isEmpty();
            assertThat(evidenceService.listReclaims(null)).hasSize(1);
        }
    }

    @Test
    void concurrentTwoReclaimsOnSameLoanOnlyOneSucceeds() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        // 两个不同 reclaimKey 并发追缴同一借出（非幂等重放）
        List<Integer> statuses = runConcurrently(
                reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey),
                reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.PENDING_INSPECTION);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RECLAIMED);
        assertThat(evidenceService.listReclaims(null)).hasSize(1);
    }

    @Test
    void concurrentSameReclaimKeyReplaysSingleResult() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        String reclaimKey = uniqueKey("REC");

        List<Integer> statuses = runConcurrently(
                reclaim("alice", evidenceKey, reclaimKey, loanKey),
                reclaim("alice", evidenceKey, reclaimKey, loanKey));

        assertThat(statuses).containsOnly(200);
        assertThat(evidenceService.listReclaims(null)).hasSize(1);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RECLAIMED);
    }

    @Test
    void concurrentReclaimsForSameBorrowerFreezeAfterThreshold() throws Exception {
        String borrower = uniqueKey("bob");
        String[] first = overdueLoan(borrower);
        String[] second = overdueLoan(borrower);

        // 同一借出人两笔逾期借出并发追缴（不同证物，均可成功）
        List<Integer> statuses = runConcurrently(
                reclaim("alice", first[0], uniqueKey("REC"), first[1]),
                reclaim("alice", second[0], uniqueKey("REC"), second[1]));

        assertThat(statuses).containsOnly(200);
        assertThat(evidenceService.listReclaims(borrower)).hasSize(2);

        // 累计追缴达到 2 次：自动冻结
        var freeze = evidenceService.borrowerFreezeStatus(borrower);
        assertThat(freeze.frozen()).isTrue();
        assertThat(freeze.reclaimCount()).isEqualTo(2);
        assertThat(freeze.totalReclaimCount()).isEqualTo(2);
    }
}
