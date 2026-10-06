package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SetHandicapFactorRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HANDICAP 系数写入的 H2 真实并发测试：系数修改与封榜共享赛事版本竞争，
 * 同 requestId 并发只产生一次业务变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class HandicapConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-handicap-concurrent";

    @Autowired
    private RaceService raceService;

    private void seedHandicapRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create", "HANDICAP"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-reg-a"));
    }

    @Test
    void 系数修改与封榜并发互斥且版本只加一() throws Exception {
        seedHandicapRace();
        // 当前版本为2：系数修改与封榜都以 expectedVersion=2 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger factorSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.setHandicapFactor(RACE, "a",
                                        new SetHandicapFactorRequest(1, 2, 2, "req-f-" + i));
                                factorSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(2, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
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
        assertThat(factorSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findHandicapFactors(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findHandicapFactors(RACE)).hasSize(1);
        }
    }

    @Test
    void 同requestId并发系数写入只产生一次变更且结果一致() throws Exception {
        seedHandicapRace();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.setHandicapFactor(RACE, "a",
                                    new SetHandicapFactorRequest(2, 6, 2, "req-same"));
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
        // 只产生一次业务变更：版本只加一，系数仅一条且为约分后的 1/3
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findHandicapFactors(RACE)).hasSize(1);
        assertThat(repository.findHandicapFactors(RACE).getFirst().p()).isEqualTo(1);
        assertThat(repository.findHandicapFactors(RACE).getFirst().q()).isEqualTo(3);
    }
}
