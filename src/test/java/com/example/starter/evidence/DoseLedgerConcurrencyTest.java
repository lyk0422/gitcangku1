package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DoseLimitRegisterRequest;
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
 * 并发登记只成功一次、并发归还只累计一次、归还与借出按事务提交顺序联合裁决，
 * 竞争不泄露唯一键异常（不出现 500）。
 */
@SpringBootTest
class DoseLedgerConcurrencyTest {

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
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "PHOTO", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void registerLimit(String actor, String evidenceKey, String limit) {
        ResponseEntity<String> response = controller.registerDoseLimit(actor, evidenceKey,
                new DoseLimitRegisterRequest(uniqueKey("REQ"), new BigDecimal(limit), null));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 将业务异常转换为对应状态码的响应，便于并发断言（不得出现 500）。
     */
    private Callable<ResponseEntity<String>> guarding(Callable<ResponseEntity<String>> call) {
        return () -> {
            try {
                return call.call();
            } catch (ApiException e) {
                return ResponseEntity.status(e.status()).body(e.getMessage());
            }
        };
    }

    private Callable<ResponseEntity<String>> register(String actor, String evidenceKey,
                                                      String requestId, String limit) {
        return guarding(() -> controller.registerDoseLimit(actor, evidenceKey,
                new DoseLimitRegisterRequest(requestId, new BigDecimal(limit), null)));
    }

    private Callable<ResponseEntity<String>> borrow(String actor, String evidenceKey,
                                                    String loanKey, String borrower) {
        return guarding(() -> controller.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "感光鉴定",
                        utc(BASE.plusSeconds(3600)))));
    }

    private Callable<ResponseEntity<String>> returns(String actor, String evidenceKey,
                                                     String commandKey, String loanKey,
                                                     List<DoseSegmentRequest> segments) {
        return guarding(() -> controller.returnLoan(actor, evidenceKey,
                new LoanReturnRequest(commandKey, loanKey, true, "并发归还说明", segments)));
    }

    private List<DoseSegmentRequest> tenMinutesAt(String lux) {
        return List.of(new DoseSegmentRequest(utc(BASE), utc(BASE.plusSeconds(600)),
                new BigDecimal(lux)));
    }

    private List<ResponseEntity<String>> race(Callable<ResponseEntity<String>> first,
                                              Callable<ResponseEntity<String>> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<String>> coordinatedFirst = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return first.call();
            };
            Callable<ResponseEntity<String>> coordinatedSecond = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return second.call();
            };
            Future<ResponseEntity<String>> f1 = executor.submit(coordinatedFirst);
            Future<ResponseEntity<String>> f2 = executor.submit(coordinatedSecond);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<ResponseEntity<String>> responses) {
        return responses.stream().map(r -> r.getStatusCode().value()).toList();
    }

    @Test
    void concurrentRegistrationsOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<ResponseEntity<String>> responses = race(
                register("alice", evidenceKey, uniqueKey("REQ"), "100"),
                register("alice", evidenceKey, uniqueKey("REQ"), "100"));

        // 恰一个登记成功，另一个 409；不出现 500（唯一键异常不外泄）
        assertThat(statuses(responses)).containsExactlyInAnyOrder(201, 409);
        var ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.version()).isEqualTo(1);
        assertThat(ledger.limitLuxMinutes()).isEqualByComparingTo("100");
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo("0");
    }

    @Test
    void concurrentSameRequestIdRegistrationReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);

        List<ResponseEntity<String>> responses = race(
                register("alice", evidenceKey, requestId, "100"),
                register("alice", evidenceKey, requestId, "100"));

        assertThat(statuses(responses)).containsOnly(201);
        assertThat(responses.get(0).getBody()).isEqualTo(responses.get(1).getBody());
        assertThat(evidenceService.doseLedger("alice", evidenceKey).version()).isEqualTo(1);
    }

    @Test
    void concurrentDoseReturnsAccumulateOnlyOnce() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        registerLimit("alice", evidenceKey, "1000");
        ResponseEntity<String> borrowed = controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, "bob", "感光鉴定",
                        utc(BASE.plusSeconds(3600))));
        assertThat(borrowed.getStatusCode().value()).isEqualTo(200);
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(600), ZoneOffset.UTC));

        // 两个独立命令并发归还同一笔借出（不同 commandKey，非幂等重放）
        List<ResponseEntity<String>> responses = race(
                returns("alice", evidenceKey, uniqueKey("CMD"), loanKey, tenMinutesAt("6")),
                returns("alice", evidenceKey, uniqueKey("CMD"), loanKey, tenMinutesAt("6")));

        assertThat(statuses(responses)).containsExactlyInAnyOrder(200, 409);
        // 剂量只累计一次
        var ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo("60");
        assertThat(ledger.version()).isEqualTo(2);
        assertThat(ledger.returns()).hasSize(1);
        assertThat(ledger.returns().get(0).segments()).hasSize(1);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
    }

    @Test
    void concurrentSameReturnCommandReplaysSingleAccumulation() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        registerLimit("alice", evidenceKey, "1000");
        controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, "bob", "感光鉴定",
                        utc(BASE.plusSeconds(3600))));
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(600), ZoneOffset.UTC));

        List<ResponseEntity<String>> responses = race(
                returns("alice", evidenceKey, commandKey, loanKey, tenMinutesAt("6")),
                returns("alice", evidenceKey, commandKey, loanKey, tenMinutesAt("6")));

        assertThat(statuses(responses)).containsOnly(200);
        // 幂等重放不重复累计
        var ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo("60");
        assertThat(ledger.returns()).hasSize(1);
    }

    @Test
    void concurrentReturnAndBorrowAtLimitResolveConsistently() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String firstLoanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        registerLimit("alice", evidenceKey, "60");
        controller.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), firstLoanKey, "bob", "感光鉴定",
                        utc(BASE.plusSeconds(3600))));
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(600), ZoneOffset.UTC));

        // 归还（累计恰好达到上限 60）与新借出并发：借出只有在归还提交后才可能进入裁决，
        // 此时要么看到 BORROWED（409），要么看到 DOSE_BLOCKED（422）；归还必须成功。
        List<ResponseEntity<String>> responses = race(
                returns("alice", evidenceKey, uniqueKey("CMD"), firstLoanKey, tenMinutesAt("6")),
                borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol"));

        List<Integer> statuses = statuses(responses);
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(409, 422);

        // 最终状态一致：证物 SEALED、仅一笔已归还借出、累计 60 且 DOSE_BLOCKED
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
        var ledger = evidenceService.doseLedger("alice", evidenceKey);
        assertThat(ledger.cumulativeLuxMinutes()).isEqualByComparingTo("60");
        assertThat(ledger.status()).isEqualTo(DoseStatus.DOSE_BLOCKED);
    }
}
