package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateFinishEvidenceRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterFinishEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.EvidenceStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 冲线证据的 H2 真实并发测试：裁决与封榜互斥、同 finishKey 并发登记只落库一次、
 * 并发裁决同一组证据仅一个成功。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class FinishEvidenceConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-evidence-concurrent";
    private static final long CAPTURED = 1_759_000_000_000L;

    @Autowired
    private RaceService raceService;

    /** 建赛并登记 a/b/c 同计时 1000ms，再登记一条证据；返回当前版本6。 */
    private int createRaceWithEvidence() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", 1000L, 3, "req-c"));
        raceService.registerFinishEvidence(RACE, new RegisterFinishEvidenceRequest(
                "ev-1", 1000L, List.of("b", "a", "c"), CAPTURED, "photo-judge", 4, "req-ev-1"));
        return 5;
    }

    @Test
    void 并发裁决与封榜互斥且最终状态自洽() throws Exception {
        int version = createRaceWithEvidence();
        assertThat(version).isEqualTo(5);

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger adjudicateSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.adjudicateFinishEvidence(RACE,
                                        new AdjudicateFinishEvidenceRequest("adj-" + i,
                                                List.of("ev-1"), List.of("c", "a", "b"),
                                                "judge", 5, "req-adj-" + i));
                                adjudicateSuccess.incrementAndGet();
                            }
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两类操作互斥：恰好一类成功一次，其余全部冲突；版本总共只加一
        assertThat(sealSuccess.get() + adjudicateSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(6);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(raceService.listFinishAdjudications(RACE)).isEmpty();
            assertThat(raceService.listFinishEvidence(RACE).getFirst().status())
                    .isEqualTo(EvidenceStatus.PENDING);
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(raceService.listFinishAdjudications(RACE)).hasSize(1);
            assertThat(raceService.listFinishEvidence(RACE).getFirst().status())
                    .isEqualTo(EvidenceStatus.ADJUDICATED);
        }
    }

    @Test
    void 同requestId并发登记证据只落库一次() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 1000L, 2, "req-b"));

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
                            ServiceResult result = raceService.registerFinishEvidence(RACE,
                                    new RegisterFinishEvidenceRequest("ev-1", 1000L,
                                            List.of("b", "a"), CAPTURED, "photo-judge",
                                            3, "req-ev-same"));
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
                assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(raceService.listFinishEvidence(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 并发裁决同一组证据仅一个成功() throws Exception {
        createRaceWithEvidence();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.adjudicateFinishEvidence(RACE,
                                    new AdjudicateFinishEvidenceRequest("adj-" + i,
                                            List.of("ev-1"), List.of("c", "a", "b"),
                                            "judge", 5, "req-adj-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(raceService.listFinishAdjudications(RACE)).hasSize(1);
        assertThat(raceService.listFinishEvidence(RACE).getFirst().status())
                .isEqualTo(EvidenceStatus.ADJUDICATED);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }
}
