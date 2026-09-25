package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateEvidenceRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RevokeEvidenceRequest;
import com.example.starter.race.domain.EvidenceStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.FinishEvidenceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲线证据在 H2 上的真实并发测试：
 * 同键并发裁决只产生一次快照；裁决与撤回互斥且不留半成品；
 * 同 evidenceId 并发登记仅一条成功。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class FinishEvidenceConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-evidence-concurrent";
    private static final long T = 1000L;

    @Autowired
    private RaceService raceService;

    private void seed() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", T, 1, "req-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", T, 2, "req-b"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", T, 3, "req-c"));
    }

    @Test
    void 同requestId并发裁决仅产生一次快照且全部重放同一结果() throws Exception {
        seed();
        raceService.registerEvidence(RACE, new RegisterEvidenceRequest(
                "ev-1", T, List.of("a", "b", "c"), "j", 1_700_000_000_000L, 4, "req-ev1"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            ServiceResult result = raceService.adjudicateEvidence(RACE,
                                    new AdjudicateEvidenceRequest("ruling-1", T,
                                            List.of("ev-1"), List.of("c", "a", "b"),
                                            "j", 5, "req-same-ruling"));
                            success.incrementAndGet();
                            return result;
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(raceService.getRulings(RACE)).hasSize(1);
        FinishEvidenceRow evidence = repository.findEvidence("ev-1").orElseThrow();
        assertThat(evidence.status()).isEqualTo(EvidenceStatus.ADJUDICATED);
        assertThat(evidence.rulingId()).isEqualTo("ruling-1");
        // 裁决只推进一次版本：v5 -> v6
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 裁决与撤回并发时互斥且最终状态自洽() throws Exception {
        seed();
        raceService.registerEvidence(RACE, new RegisterEvidenceRequest(
                "ev-1", T, List.of("a", "b", "c"), "j", 1_700_000_000_000L, 4, "req-ev1"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger adjudicateOk = new AtomicInteger();
        AtomicInteger revokeOk = new AtomicInteger();
        try {
            Future<?> adjudicateFuture = pool.submit((java.util.concurrent.Callable<Void>) () -> {
                start.await();
                try {
                    raceService.adjudicateEvidence(RACE, new AdjudicateEvidenceRequest(
                            "ruling-1", T, List.of("ev-1"), List.of("a", "b", "c"),
                            "j", 5, "req-adjudicate"));
                    adjudicateOk.incrementAndGet();
                } catch (UnprocessableEntityException | ConflictException ex) {
                    // 撤回先提交时裁决 422；版本竞争时 409，均为预期互斥结果
                }
                return null;
            });
            Future<?> revokeFuture = pool.submit((java.util.concurrent.Callable<Void>) () -> {
                start.await();
                try {
                    raceService.revokeEvidence(RACE, "ev-1",
                            new RevokeEvidenceRequest("j", 5, "req-revoke"));
                    revokeOk.incrementAndGet();
                } catch (UnprocessableEntityException | ConflictException ex) {
                    // 裁决先提交时撤回 409；版本竞争时 409
                }
                return null;
            });
            start.countDown();
            adjudicateFuture.get(30, TimeUnit.SECONDS);
            revokeFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 两类操作互斥：恰好一类成功
        assertThat(adjudicateOk.get() + revokeOk.get()).isEqualTo(1);
        FinishEvidenceRow evidence = repository.findEvidence("ev-1").orElseThrow();
        if (adjudicateOk.get() == 1) {
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.ADJUDICATED);
            assertThat(raceService.getRulings(RACE)).hasSize(1);
            assertThat(repository.findWithdrawal("ev-1")).isEmpty();
        } else {
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.REVOKED);
            assertThat(raceService.getRulings(RACE)).isEmpty();
            assertThat(repository.findWithdrawal("ev-1")).isPresent();
        }
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.OPEN);
    }

    @Test
    void 同evidenceId并发登记仅一条成功且版本只加一() throws Exception {
        seed();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.registerEvidence(RACE, new RegisterEvidenceRequest(
                                    "ev-same", T, List.of("a", "b", "c"), "j",
                                    1_700_000_000_000L, 4, "req-ev-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findEvidences(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }
}
