package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SelectScoringModeRequest;
import com.example.starter.race.api.UpsertHandicapFactorRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ScoringMode;
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
 * 让分赛写操作的 H2 真实并发测试：
 * 系数修改共享赛事版本竞争、与封榜互斥、同 requestId 并发只生效一次。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class HandicapConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-hc-concurrent";

    @Autowired
    private RaceService raceService;

    private void createHandicapRaceWithRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 999L, 2, "req-a"));
    }

    @Test
    void 并发同版本系数修改仅一个成功且版本只加一() throws Exception {
        createHandicapRaceWithRunner();

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
                            raceService.upsertHandicapFactor(RACE, "a",
                                    new UpsertHandicapFactorRequest(1, i + 1, 3, "req-f-" + i));
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
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().factorP()).isNotNull();
    }

    @Test
    void 系数修改与封榜并发互斥且最终状态自洽() throws Exception {
        createHandicapRaceWithRunner();
        // 当前版本3：系数修改与封榜都以 expectedVersion=3 并发提交
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
                                raceService.upsertHandicapFactor(RACE, "a",
                                        new UpsertHandicapFactorRequest(1, 3, 3, "req-f-" + i));
                                factorSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
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
        assertThat(race.version()).isEqualTo(4);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            // 封榜先于系数修改：快照中该选手缺系数不排名
            assertThat(repository.findSnapshot(RACE).orElseThrow().scoringMode())
                    .isEqualTo(ScoringMode.HANDICAP);
            assertThat(repository.findRunner(RACE, "a").orElseThrow().factorP()).isNull();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findRunner(RACE, "a").orElseThrow().factorP()).isEqualTo(1);
        }
    }

    @Test
    void 同requestId并发系数修改只产生一次变更且结果一致() throws Exception {
        createHandicapRaceWithRunner();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.upsertHandicapFactor(RACE, "a",
                                    new UpsertHandicapFactorRequest(2, 6, 3, "req-same"));
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
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().factorP()).isEqualTo(1);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().factorQ()).isEqualTo(3);
    }
}
