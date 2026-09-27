package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ExamCancelRequest;
import com.example.starter.evidence.dto.ExamReserveRequest;
import com.example.starter.evidence.dto.ExamReserveRequest.ExamReservationItemRequest;
import com.example.starter.evidence.dto.ExamStartRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
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
 * 检验预约并发边界测试（真实 H2 行锁与事务，非 mock）：
 * 同证物并发预约最多一个成功；检验台容量按提交顺序裁决；预约与借出竞争按提交顺序；
 * 开始与取消竞争恰好一方胜出。所有用例核对最终业务数据，不依赖打印或睡眠判定。
 */
@SpringBootTest
class ExamConcurrencyTest {

    @Autowired
    private ExamReservationController controller;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ExamReservationService examService;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClockAndClearExamScenarioData() {
        evidenceClock.setClock(Clock.systemUTC());
        // 清理并发场景的预约与事件，保证下一个用例的台位占用从零开始。
        jdbcTemplate.execute("DELETE FROM exam_chain_event");
        jdbcTemplate.execute("DELETE FROM exam_reservation");
    }

    private LocalDateTime t(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 23, hour, minute, 0);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<Integer> reserve(String actor, String evidenceKey, String benchKey) {
        ExamReserveRequest request = new ExamReserveRequest(uniqueKey("CMD"),
                List.of(new ExamReservationItemRequest(
                        uniqueKey("RSV"), evidenceKey, benchKey, t(10, 0), t(11, 0))));
        // ApiException 不在此吞没：交由 invoke 统一捕获以保留可区分的失败明细。
        return () -> controller.reserve(actor, request).getStatusCode().value();
    }

    private record Outcome(int status, ApiException error) {
    }

    private List<Outcome> runConcurrently(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Outcome> wrapped1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return invoke(first);
            };
            Callable<Outcome> wrapped2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return invoke(second);
            };
            Future<Outcome> f1 = executor.submit(wrapped1);
            Future<Outcome> f2 = executor.submit(wrapped2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome invoke(Callable<Integer> call) {
        try {
            return new Outcome(call.call(), null);
        } catch (ApiException e) {
            return new Outcome(e.status().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentReservationsForSameEvidenceExactlyOneSucceeds() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        List<Outcome> outcomes = runConcurrently(
                reserve("lab-alice", evidenceKey, "BENCH-A"),
                reserve("lab-bob", evidenceKey, "BENCH-A"));

        assertThat(outcomes).extracting(Outcome::status)
                .containsExactlyInAnyOrder(201, 409);
        Outcome failure = outcomes.stream().filter(o -> o.status() == 409).findFirst().orElseThrow();
        assertThat(failure.error()).isNotNull();
        assertThat(failure.error().details()).isNotNull().isNotEmpty();

        // 最终数据：仅一条占位预约，证物仍 SEALED 且只有一个 RESERVED 事件。
        var history = examService.history(evidenceKey);
        assertThat(history.reservations()).hasSize(1);
        assertThat(history.reservations().get(0).status()).isEqualTo(ExamReservationStatus.RESERVED);
        assertThat(history.events()).hasSize(1);
        assertThat(evidenceService.custodyChain(evidenceKey).evidence().status())
                .isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentReservationsOnCapacityOneBenchExactlyOneSucceedsWithDeficit() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        List<Outcome> outcomes = runConcurrently(
                reserve("lab-alice", ev1, "BENCH-B"),
                reserve("lab-bob", ev2, "BENCH-B"));

        assertThat(outcomes).extracting(Outcome::status)
                .containsExactlyInAnyOrder(201, 409);
        Outcome failure = outcomes.stream().filter(o -> o.status() == 409).findFirst().orElseThrow();
        assertThat(failure.error()).isNotNull();
        assertThat(failure.error().details()).isNotNull().hasSize(1);
        com.example.starter.evidence.dto.ExamFailureDetail detail =
                (com.example.starter.evidence.dto.ExamFailureDetail) failure.error().details().get(0);
        assertThat(detail.reason()).isEqualTo("BENCH_CAPACITY_EXCEEDED");
        // 失败方视角：已提交占用 1 + 本批 1 = 实际最终占用 2，要求上限 1，差额 1。
        assertThat(detail.actual()).isEqualTo(2);
        assertThat(detail.required()).isEqualTo(1);
        assertThat(detail.deficit()).isEqualTo(1);

        // 最终数据：容量 1 的台位窗口内仅 1 条占位，另一证物无任何预约。
        var occ = examService.occupancy("BENCH-B", t(10, 0), t(11, 0));
        assertThat(occ.actualOccupancy()).isEqualTo(1);
        assertThat(occ.remaining()).isZero();
        assertThat(examService.history(ev1).reservations().size()
                + examService.history(ev2).reservations().size()).isEqualTo(1);
    }

    @Test
    void concurrentReservationAndBorrowAreAdjudicatedByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(
                java.time.Instant.parse("2026-09-23T08:00:00Z"), ZoneOffset.UTC));
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        Callable<Integer> borrowCall = () -> evidenceController.borrow("alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"),
                        "borrower-bob", "鉴定用", t(12, 0))).getStatusCode().value();

        List<Outcome> outcomes = runConcurrently(reserve("lab-alice", evidenceKey, "BENCH-A"),
                borrowCall);

        long successCount = outcomes.stream().filter(o -> o.status() == 200 || o.status() == 201).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.status() == 200 || o.status() == 201
                || o.status() == 409 || o.status() == 422);

        var chain = evidenceService.custodyChain(evidenceKey);
        if (chain.evidence().status() == EvidenceStatus.BORROWED) {
            // 借出先提交：预约必须被拒，无预约、无检验事件。
            assertThat(examService.history(evidenceKey).reservations()).isEmpty();
            assertThat(chain.examEvents()).isEmpty();
        } else {
            // 预约先提交：借出被拒，证物在库且存在唯一占位预约。
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            var history = examService.history(evidenceKey);
            assertThat(history.reservations()).hasSize(1);
            assertThat(history.reservations().get(0).status()).isEqualTo(ExamReservationStatus.RESERVED);
        }
    }

    @Test
    void concurrentStartAndCancelExactlyOneWinsAndHistoryStaysConsistent() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        ResponseEntity<String> created = controller.reserve("lab-alice",
                new ExamReserveRequest(uniqueKey("CMD"),
                        List.of(new ExamReservationItemRequest(
                                reservationKey, evidenceKey, "BENCH-A", t(10, 0), t(11, 0)))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Callable<Integer> startCall = () -> controller.start("lab-alice", reservationKey,
                new ExamStartRequest(uniqueKey("CMD"))).getStatusCode().value();
        Callable<Integer> cancelCall = () -> controller.cancel("lab-alice", reservationKey,
                new ExamCancelRequest(uniqueKey("CMD"))).getStatusCode().value();

        List<Outcome> outcomes = runConcurrently(startCall, cancelCall);
        assertThat(outcomes).extracting(Outcome::status)
                .containsExactlyInAnyOrder(200, 409);

        var history = examService.history(evidenceKey);
        assertThat(history.reservations()).hasSize(1);
        var reservation = history.reservations().get(0);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.examEvents()).hasSize(2);
        if (reservation.status() == ExamReservationStatus.IN_PROGRESS) {
            // 开始先提交：证物检验中，取消被拒，锁定封签版本 1。
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.EXAMINATION);
            assertThat(reservation.lockedSealVersion()).isEqualTo(1);
            assertThat(chain.examEvents().get(1).eventType()).isEqualTo(ExamChainEventType.STARTED);
        } else {
            // 取消先提交：证物仍在库，开始被拒，台位已释放。
            assertThat(reservation.status()).isEqualTo(ExamReservationStatus.CANCELLED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.examEvents().get(1).eventType()).isEqualTo(ExamChainEventType.CANCELLED);
            assertThat(examService.occupancy("BENCH-A", t(9, 0), t(12, 0)).actualOccupancy())
                    .isZero();
        }
    }
}
