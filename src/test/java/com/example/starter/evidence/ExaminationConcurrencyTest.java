package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ExaminationStartRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.ReservationCreateRequest;
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
 * 检验预约相关并发边界测试（真实 H2 行锁与事务）：
 * 同证物并发预约/预约与借出按事务提交顺序串行裁决；台位容量并发下不超限；
 * 同命令键并发重放只有一次副作用。所有用例以栅栏协调真实操作并设超时，最终核对业务数据。
 */
@SpringBootTest
class ExaminationConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private ExaminationController examinationController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private ExaminationService examinationService;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(int hour) {
        // 以 BASE 当天的绝对 UTC 小时时刻（2026-09-22Thour:00Z），不是相对 BASE 的偏移。
        return LocalDateTime.ofInstant(BASE, ZoneOffset.UTC)
                .withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void registerBench(String benchKey, int capacity) {
        ResponseEntity<String> response = examinationController.registerBench("alice",
                new com.example.starter.evidence.dto.BenchRegisterRequest(
                        uniqueKey("CMD"), benchKey, "台", capacity));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> reserve(String actor, String evidenceKey,
                                                     String reservationKey, String benchKey,
                                                     String examiner, LocalDateTime start,
                                                     LocalDateTime end) {
        return () -> examinationController.reserve(actor, evidenceKey,
                new ReservationCreateRequest(uniqueKey("CMD"), reservationKey, benchKey,
                        examiner, start, end));
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
    void concurrentReservationsOnSameEvidenceOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String bench1 = uniqueKey("BENCH");
        String bench2 = uniqueKey("BENCH");
        registerBench(bench1, 5);
        registerBench(bench2, 5);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // 不同检验台、不同时段，仍因证物行锁裁决只有一个成功
        List<Integer> statuses = runConcurrently(
                reserve("alice", evidenceKey, uniqueKey("RSV"), bench1, "bob", utc(9), utc(10)),
                reserve("alice", evidenceKey, uniqueKey("RSV"), bench2, "carol", utc(11), utc(12)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        var reservations = examinationService.listByEvidence(evidenceKey);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).status()).isEqualTo(ReservationStatus.SCHEDULED);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.examinations()).hasSize(1);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
    }

    @Test
    void concurrentReserveAndBorrowNeverCoexist() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 5);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        Callable<ResponseEntity<String>> borrowCall = () -> evidenceController.borrow(
                "alice", evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "bob", "外检", utc(20)));

        List<Integer> statuses = runConcurrently(
                reserve("alice", evidenceKey, uniqueKey("RSV"), benchKey, "carol",
                        utc(9), utc(10)),
                borrowCall);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        var chain = evidenceService.custodyChain(evidenceKey);
        boolean activeLoan = chain.loans().stream()
                .anyMatch(loan -> loan.status() == LoanStatus.ACTIVE);
        boolean activeReservation = chain.evidence().status() == EvidenceStatus.SEALED
                && examinationService.listByEvidence(evidenceKey).stream()
                .anyMatch(r -> r.status() == ReservationStatus.SCHEDULED);
        if (activeLoan) {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(activeReservation).isFalse();
            assertThat(examinationService.listByEvidence(evidenceKey)).isEmpty();
        } else {
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(examinationService.listByEvidence(evidenceKey)).hasSize(1);
        }
    }

    @Test
    void concurrentReservationsOnSameBenchRespectCapacity() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        List<Integer> statuses = runConcurrently(
                reserve("alice", ev1, uniqueKey("RSV"), benchKey, "bob", utc(9), utc(10)),
                reserve("alice", ev2, uniqueKey("RSV"), benchKey, "carol", utc(9), utc(10)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        long scheduledOnBench = examinationService.listByEvidence(ev1).stream()
                .filter(r -> r.status() == ReservationStatus.SCHEDULED
                        && r.benchKey().equals(benchKey)).count()
                + examinationService.listByEvidence(ev2).stream()
                .filter(r -> r.status() == ReservationStatus.SCHEDULED
                        && r.benchKey().equals(benchKey)).count();
        assertThat(scheduledOnBench).isEqualTo(1);
    }

    @Test
    void concurrentSameReservationCommandKeyReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        String commandKey = uniqueKey("CMD");
        String reservationKey = uniqueKey("RSV");
        ReservationCreateRequest request = new ReservationCreateRequest(
                commandKey, reservationKey, benchKey, "bob", utc(9), utc(10));
        List<Integer> statuses = runConcurrently(
                () -> examinationController.reserve("alice", evidenceKey, request),
                () -> examinationController.reserve("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(201);
        assertThat(examinationService.listByEvidence(evidenceKey)).hasSize(1);
        assertThat(evidenceService.custodyChain(evidenceKey).examinations()).hasSize(1);
    }

    @Test
    void concurrentStartWithSameCommandKeyLocksSealVersionOnce() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        ResponseEntity<String> reserved = examinationController.reserve("alice", evidenceKey,
                new ReservationCreateRequest(uniqueKey("CMD"), reservationKey, benchKey, "bob",
                        utc(8), utc(9)));
        assertThat(reserved.getStatusCode().value()).isEqualTo(201);

        String startCommandKey = uniqueKey("CMD");
        ExaminationStartRequest startRequest = new ExaminationStartRequest(startCommandKey);
        List<Integer> statuses = runConcurrently(
                () -> examinationController.start("bob", evidenceKey, reservationKey, startRequest),
                () -> examinationController.start("bob", evidenceKey, reservationKey, startRequest));

        assertThat(statuses).containsOnly(200);
        var chain = evidenceService.custodyChain(evidenceKey);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.EXAMINATION);
        // 原封签版本只被锁定一次，不存在重复 STARTED 事件或重复版本
        assertThat(chain.sealVersions()).hasSize(1);
        assertThat(chain.sealVersions().get(0).supersededAt()).isNotNull();
        long startedEvents = chain.examinations().stream()
                .filter(e -> e.eventType() == ExaminationEventType.STARTED).count();
        assertThat(startedEvents).isEqualTo(1);
        var reservation = examinationService.getReservation(reservationKey);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(reservation.sealVersionLocked()).isEqualTo(1);
    }

    @Test
    void concurrentStartAndFailedSealInspectionResolveByCommitOrderWithoutHalfDoneState()
            throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        examinationController.reserve("alice", evidenceKey,
                new ReservationCreateRequest(uniqueKey("CMD"), reservationKey, benchKey, "bob",
                        utc(8), utc(9)));

        Callable<ResponseEntity<String>> startCall = () -> examinationController.start(
                "bob", evidenceKey, reservationKey, new ExaminationStartRequest(uniqueKey("CMD")));
        Callable<ResponseEntity<String>> inspectCall = () -> evidenceController.inspectSeal(
                "alice", evidenceKey,
                new com.example.starter.evidence.dto.SealInspectionRequest(
                        uniqueKey("CMD"), false, "并发复核发现裂痕"));

        List<Integer> statuses = runConcurrently(startCall, inspectCall);
        assertThat(statuses).hasSize(2);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409 || s == 422);
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount).isEqualTo(1);

        var chain = evidenceService.custodyChain(evidenceKey);
        var reservation = examinationService.getReservation(reservationKey);
        if (reservation.status() == ReservationStatus.IN_PROGRESS) {
            // 开始先提交：证物检验中、原封签已锁定；随后复核返回 409，不得写入异常结论
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.EXAMINATION);
            assertThat(chain.sealVersions()).hasSize(1);
            assertThat(chain.sealVersions().get(0).supersededAt()).isNotNull();
            assertThat(chain.inspections()).isEmpty();
        } else {
            // 失败复核先提交：证物封签异常（终态）；开始返回 422，封签版本未锁定，无 STARTED 事件
            assertThat(reservation.status()).isEqualTo(ReservationStatus.SCHEDULED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEAL_BROKEN);
            assertThat(chain.sealVersions()).hasSize(1);
            assertThat(chain.sealVersions().get(0).supersededAt()).isNull();
            assertThat(chain.inspections()).hasSize(1);
            assertThat(chain.inspections().get(0).passed()).isFalse();
            assertThat(chain.examinations()).hasSize(1);
            assertThat(chain.examinations().get(0).eventType())
                    .isEqualTo(ExaminationEventType.RESERVED);
        }
    }

    @Test
    void concurrentStartAndCancelExactlyOneWinsWithoutHalfDoneState() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String benchKey = uniqueKey("BENCH");
        registerBench(benchKey, 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        examinationController.reserve("alice", evidenceKey,
                new ReservationCreateRequest(uniqueKey("CMD"), reservationKey, benchKey, "bob",
                        utc(8), utc(9)));

        Callable<ResponseEntity<String>> startCall = () -> examinationController.start(
                "bob", evidenceKey, reservationKey, new ExaminationStartRequest(uniqueKey("CMD")));
        Callable<ResponseEntity<String>> cancelCall = () -> examinationController.cancel(
                "alice", evidenceKey, reservationKey,
                new com.example.starter.evidence.dto.ExaminationCancelRequest(
                        uniqueKey("CMD"), "排期调整"));

        List<Integer> statuses = runConcurrently(startCall, cancelCall);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var reservation = examinationService.getReservation(reservationKey);
        var chain = evidenceService.custodyChain(evidenceKey);
        if (reservation.status() == ReservationStatus.IN_PROGRESS) {
            // 开始先提交：证物 EXAMINATION，原封签已锁定，取消被拒
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.EXAMINATION);
            assertThat(chain.sealVersions().get(0).supersededAt()).isNotNull();
        } else {
            // 取消先提交：证物保持 SEALED，封签未锁定，开始被拒，台位已释放
            assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(chain.sealVersions().get(0).supersededAt()).isNull();
            // 台位释放后同一时段可被新预约占用
            String ev2 = uniqueKey("EV");
            intake("alice", ev2);
            ResponseEntity<String> rebooked = examinationController.reserve("alice", ev2,
                    new ReservationCreateRequest(uniqueKey("CMD"), uniqueKey("RSV"), benchKey,
                            "carol", utc(8), utc(9)));
            assertThat(rebooked.getStatusCode().value()).isEqualTo(201);
        }
    }
}
