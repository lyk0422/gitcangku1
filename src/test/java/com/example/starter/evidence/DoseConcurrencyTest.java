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
 * 光照剂量并发边界测试（真实 H2 行锁与事务）：
 * 并发登记只建一本账、并发剂量归还只累计一次、归还与借出按提交顺序联合裁决，
 * 竞争不泄露唯一键异常。
 */
@SpringBootTest
class DoseConcurrencyTest {

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

    private Callable<ResponseEntity<String>> register(String actor, String evidenceKey,
                                                      String requestId, String limit) {
        return () -> controller.registerDoseLimit(actor, evidenceKey,
                new DoseLimitRegisterRequest(requestId, new BigDecimal(limit), 0L));
    }

    private Callable<ResponseEntity<String>> borrow(String actor, String evidenceKey,
                                                    String loanKey, String borrower) {
        return () -> controller.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), loanKey, borrower, "光照实验",
                        utc(BASE.plusSeconds(3600))));
    }

    private Callable<ResponseEntity<String>> returnWithDose(String actor, String evidenceKey,
                                                            String commandKey, String loanKey,
                                                            long expectedVersion) {
        return () -> controller.returnLoan(actor, evidenceKey,
                new LoanReturnRequest(commandKey, loanKey, true, "并发剂量归还", expectedVersion,
                        List.of(new DoseSegmentRequest(utc(BASE), utc(BASE.plusSeconds(600)),
                                new BigDecimal("6")))));
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
    void concurrentRegisterCreatesSingleLedger() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // 两个独立 requestId 并发登记：恰一个成功，另一个 409，不泄露唯一键异常
        List<Integer> statuses = runConcurrently(
                register("alice", evidenceKey, uniqueKey("REQ"), "100"),
                register("alice", evidenceKey, uniqueKey("REQ"), "100"));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        var detail = evidenceService.doseDetail("alice", evidenceKey);
        assertThat(detail.limitLuxMinutes()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.doseBlocked()).isFalse();
    }

    @Test
    void concurrentSameRegisterRequestIdReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                register("alice", evidenceKey, requestId, "100"),
                register("alice", evidenceKey, requestId, "100"));
        assertThat(statuses).containsOnly(201);

        var detail = evidenceService.doseDetail("alice", evidenceKey);
        assertThat(detail.version()).isEqualTo(1);
    }

    @Test
    void concurrentDoseReturnsAccumulateOnlyOnce() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "1000").call()
                .getStatusCode().value()).isEqualTo(201);
        assertThat(borrow("alice", evidenceKey, loanKey, "bob").call()
                .getStatusCode().value()).isEqualTo(200);

        // 时钟推进到归还时刻；两个独立命令并发归还同一笔借出（10分钟×6=60）
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(600), ZoneOffset.UTC));
        List<Integer> statuses = runConcurrently(
                returnWithDose("alice", evidenceKey, uniqueKey("CMD"), loanKey, 1L),
                returnWithDose("alice", evidenceKey, uniqueKey("CMD"), loanKey, 1L));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 只累计一次：60，一条归还记录，账本版本 2
        var detail = evidenceService.doseDetail("alice", evidenceKey);
        assertThat(detail.cumulativeLuxMin()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(detail.version()).isEqualTo(2);
        assertThat(detail.returns()).hasSize(1);
        assertThat(detail.returns().get(0).segments()).hasSize(1);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.loans()).hasSize(1);
        assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
    }

    @Test
    void concurrentReturnAndBorrowWithDoseResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String firstLoanKey = uniqueKey("LOAN");
        String secondLoanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "1000").call()
                .getStatusCode().value()).isEqualTo(201);
        assertThat(borrow("alice", evidenceKey, firstLoanKey, "bob").call()
                .getStatusCode().value()).isEqualTo(200);

        // 归还（剂量 60，未达上限）与新一轮借出并发：按事务提交顺序裁决
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(600), ZoneOffset.UTC));
        List<Integer> statuses = runConcurrently(
                returnWithDose("alice", evidenceKey, uniqueKey("CMD"), firstLoanKey, 1L),
                borrow("alice", evidenceKey, secondLoanKey, "carol"));
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        var detail = evidenceService.doseDetail("alice", evidenceKey);
        long successCount = statuses.stream().filter(s -> s == 200).count();
        if (successCount == 2) {
            // 归还先提交：旧借出 RETURNED + 新借出 ACTIVE，剂量只记一次
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.loans()).hasSize(2);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
            assertThat(chain.loans().get(1).status()).isEqualTo(LoanStatus.ACTIVE);
        } else {
            // 借出先取锁看到 BORROWED 被拒：归还成功，证物 SEALED
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.loans()).hasSize(1);
            assertThat(chain.loans().get(0).status()).isEqualTo(LoanStatus.RETURNED);
        }
        assertThat(detail.cumulativeLuxMin()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(detail.version()).isEqualTo(2);
        assertThat(detail.returns()).hasSize(1);
        assertThat(detail.doseBlocked()).isFalse();
    }
}
