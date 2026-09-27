package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BenchCreateRequest;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ExamCompleteRequest;
import com.example.starter.evidence.dto.ExamReservationCreateRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
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
 * 检验预约并发边界测试（真实 H2 行锁与事务）：
 * 同证物并发预约最多一个成功；台位容量不超卖；预约与借出按提交顺序裁决；
 * 同 commandKey 并发重放返回同一首次结果；并发完成只有一个生效。
 */
@SpringBootTest
class ExamConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ExamController examController;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private ExamService examService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private LocalDateTime utc(long seconds) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void createBench(String actor, String benchKey, int capacity) {
        ResponseEntity<String> response = examController.createBench(actor,
                new BenchCreateRequest(uniqueKey("CMD"), benchKey, capacity));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> reserve(String actor, String reservationKey,
                                                     String evidenceKey, String benchKey) {
        return () -> examController.reserve(actor,
                new ExamReservationCreateRequest(uniqueKey("CMD"), reservationKey,
                        evidenceKey, benchKey, utc(3600), utc(7200)));
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
    void concurrentReservationsOnSameEvidenceOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String bench1 = uniqueKey("BENCH");
        String bench2 = uniqueKey("BENCH");
        intake("alice", evidenceKey);
        createBench("lab", bench1, 1);
        createBench("lab", bench2, 1);

        List<Integer> statuses = runConcurrently(
                reserve("lab", uniqueKey("RES"), evidenceKey, bench1),
                reserve("lab", uniqueKey("RES"), evidenceKey, bench2));

        // 同证物并发预约最多一个成功
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        var exams = examService.listByEvidence(evidenceKey);
        assertThat(exams).hasSize(1);
        assertThat(exams.get(0).status()).isEqualTo(ExamReservationStatus.RESERVED);
        assertThat(evidenceService.custodyChain(evidenceKey).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentReservationsOnSameBenchNeverExceedCapacity() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createBench("lab", benchKey, 1);

        List<Integer> statuses = runConcurrently(
                reserve("lab", uniqueKey("RES"), ev1, benchKey),
                reserve("lab", uniqueKey("RES"), ev2, benchKey));

        // 容量 1：恰有一个成功，另一个 409，不超卖
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        var schedule = examService.benchSchedule(benchKey, utc(0), utc(10800));
        assertThat(schedule.activeInWindow()).isEqualTo(1);
        assertThat(schedule.remaining()).isEqualTo(0);
    }

    @Test
    void concurrentSameCommandKeyReserveReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);

        ExamReservationCreateRequest request = new ExamReservationCreateRequest(
                commandKey, reservationKey, evidenceKey, benchKey, utc(3600), utc(7200));
        List<Integer> statuses = runConcurrently(
                () -> examController.reserve("lab", request),
                () -> examController.reserve("lab", request));

        assertThat(statuses).containsOnly(201);
        assertThat(examService.listByEvidence(evidenceKey)).hasSize(1);
    }

    @Test
    void concurrentReserveAndBorrowResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);

        List<Integer> statuses = runConcurrently(
                reserve("lab", uniqueKey("RES"), evidenceKey, benchKey),
                () -> evidenceController.borrow("alice", evidenceKey,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "bob",
                                "鉴定用", utc(3600))));

        // 恰有一个先提交成功，另一个按提交后的状态被拒绝（409）
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        boolean reserved = !chain.exams().isEmpty();
        boolean borrowed = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        // 不得同时外借与持有进行中预约
        assertThat(reserved).isNotEqualTo(borrowed);
        if (borrowed) {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        }
    }

    @Test
    void concurrentCompletesOnlyOneTakesEffect() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        ResponseEntity<String> reserved = examController.reserve("lab",
                new ExamReservationCreateRequest(uniqueKey("CMD"), reservationKey,
                        evidenceKey, benchKey, utc(3600), utc(7200)));
        assertThat(reserved.getStatusCode().value()).isEqualTo(201);
        evidenceClock.setClock(Clock.fixed(BASE.plusSeconds(3600), ZoneOffset.UTC));
        ResponseEntity<String> started = examController.start("lab", reservationKey,
                new CommandRequest(uniqueKey("CMD")));
        assertThat(started.getStatusCode().value()).isEqualTo(200);

        // 两个独立命令并发完成同一预约（不同 commandKey，非幂等重放）
        List<Integer> statuses = runConcurrently(
                () -> examController.complete("lab", reservationKey,
                        new ExamCompleteRequest(uniqueKey("CMD"), "结果A", "SEAL-A", true, null)),
                () -> examController.complete("lab", reservationKey,
                        new ExamCompleteRequest(uniqueKey("CMD"), "结果B", "SEAL-B", true, null)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var chain = evidenceService.custodyChain(evidenceKey);
        // 完成记录成功则证物必须同事务回到 SEALED；只有一条预约、一个 COMPLETED 事件
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(chain.evidence().sealVersion()).isEqualTo(2);
        assertThat(chain.exams()).hasSize(1);
        assertThat(chain.exams().get(0).status()).isEqualTo(ExamReservationStatus.COMPLETED);
        assertThat(chain.examEvents().stream()
                .filter(e -> e.eventType().equals("COMPLETED"))).hasSize(1);
    }
}
