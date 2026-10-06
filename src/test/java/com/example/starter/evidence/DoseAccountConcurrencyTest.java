package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DoseCapRegisterRequest;
import com.example.starter.evidence.dto.DoseLedgerView;
import com.example.starter.evidence.dto.DoseSegmentRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
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
 * 剂量账本并发边界测试（真实 H2 行锁与事务）：
 * 并发归还只累计一次、并发登记只成功一次、归还达上限与新增借出按提交顺序裁决。
 */
@SpringBootTest
class DoseAccountConcurrencyTest {

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

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = controller.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "PHOTO", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void registerCap(String actor, String evidenceKey, String cap) {
        ResponseEntity<String> response = controller.registerDoseCap(actor, evidenceKey,
                new DoseCapRegisterRequest(uniqueKey("REQ"), new BigDecimal(cap), 0));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void borrow(String actor, String evidenceKey, String loanKey, String borrower,
                        LocalDateTime dueAt) {
        ResponseEntity<String> response = controller.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "感光鉴定", dueAt));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private List<DoseSegmentRequest> segments(Instant start, Instant end, String lux) {
        return List.of(new DoseSegmentRequest(utc(start), utc(end), new BigDecimal(lux)));
    }

    private Callable<ResponseEntity<String>> returns(String actor, String evidenceKey,
                                                     String commandKey, String loanKey,
                                                     List<DoseSegmentRequest> segments) {
        return () -> controller.returnLoan(actor, evidenceKey,
                new LoanReturnRequest(commandKey, loanKey, true, "并发归还说明", segments));
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
                                          Callable<ResponseEntity<String>> second)
            throws Exception {
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
    void concurrentReturnsAccumulateDoseOnlyOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "1000");
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 两个独立命令并发归还同一笔借出（不同 commandKey，非幂等重放）
        List<DoseSegmentRequest> segments = segments(BASE, BASE.plusSeconds(600), "6");
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, uniqueKey("CMD"), loanKey, segments),
                returns("alice", evidenceKey, uniqueKey("CMD"), loanKey, segments));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 剂量只累计一次：一条剂量归还、一段剂量段、累计 60
        DoseLedgerView ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(ledger.returns()).hasSize(1);
        assertThat(ledger.returns().get(0).segments()).hasSize(1);
        assertThat(ledger.version()).isEqualTo(2);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
    }

    @Test
    void concurrentSameReturnCommandReplaysWithoutDoubleAccumulation() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "1000");
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        List<DoseSegmentRequest> segments = segments(BASE, BASE.plusSeconds(600), "6");
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, commandKey, loanKey, segments),
                returns("alice", evidenceKey, commandKey, loanKey, segments));

        assertThat(statuses).containsOnly(200);
        DoseLedgerView ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(ledger.returns()).hasSize(1);
    }

    @Test
    void concurrentRegisterCapOnlyOneSucceeds() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.registerDoseCap("alice", evidenceKey,
                        new DoseCapRegisterRequest(uniqueKey("REQ"), new BigDecimal("100"), 0)),
                () -> controller.registerDoseCap("alice", evidenceKey,
                        new DoseCapRegisterRequest(uniqueKey("REQ"), new BigDecimal("200"), 0)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        DoseLedgerView ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.version()).isEqualTo(1);
        // 只有一份账本，上限为两次并发登记之一（BigDecimal 比较忽略标度）
        boolean capIsOneOf = ledger.capLuxMinutes().compareTo(new BigDecimal("100")) == 0
                || ledger.capLuxMinutes().compareTo(new BigDecimal("200")) == 0;
        assertThat(capIsOneOf).isTrue();
    }

    @Test
    void concurrentSameRegisterRequestReplaysSingleAccount() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);
        DoseCapRegisterRequest request =
                new DoseCapRegisterRequest(requestId, new BigDecimal("100"), 0);

        List<Integer> statuses = runConcurrently(
                () -> controller.registerDoseCap("alice", evidenceKey, request),
                () -> controller.registerDoseCap("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(201);
        DoseLedgerView ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.version()).isEqualTo(1);
        assertThat(ledger.capLuxMinutes()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void returnReachingCapAndNewBorrowResolveByCommitOrder() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 归还（当次剂量 100 达到上限）与新增借出并发，按事务提交顺序联合裁决：
        // 借出先拿锁 → 看到 BORROWED 拒绝 409；归还先提交 → 借出看到 SEALED 但 DOSE_BLOCKED 422。
        List<Integer> statuses = runConcurrently(
                returns("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                        segments(BASE, BASE.plusSeconds(600), "10")),
                () -> controller.borrow("alice", evidenceKey,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "carol",
                                "感光鉴定", utc(BASE.plusSeconds(4200)))));

        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(409, 422);

        // 最终状态一致：证物 SEALED，旧借出已归还，无新增借出，累计 100 且 DOSE_BLOCKED
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
        DoseLedgerView ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(ledger.doseBlocked()).isTrue();
        assertThat(ledger.returns()).hasSize(1);
    }
}
