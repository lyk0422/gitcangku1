package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RegisterWavesRequest;
import com.example.starter.race.api.SealRaceRequest;
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
 * 波次登记与封榜、计时并发的真实 H2 测试：按事务提交顺序裁决，
 * 封榜先提交则波次变更 409；同 requestId 并发重放只产生一次变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WaveConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wave-concurrent";
    private static final long BASE = 1_767_225_600_000L;

    @Autowired
    private RaceService raceService;

    private RegisterWavesRequest wavesRequest() {
        return new RegisterWavesRequest(
                3,
                List.of(new RegisterWavesRequest.WaveDefinition("w1", BASE, List.of("a"))),
                "req-waves");
    }

    @Test
    void 封榜与波次登记并发按提交顺序裁决恰好一方成功() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, BASE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 5000L, 1, "reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 5000L, 2, "reg-b"));
        // 当前版本3：封榜与波次登记都以 expectedVersion=3 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger waveSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<Void>> futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE, new SealRaceRequest(3, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.registerWaves(RACE, new RegisterWavesRequest(
                                        3,
                                        List.of(new RegisterWavesRequest.WaveDefinition(
                                                "w" + i, BASE, List.of("a"))),
                                        "req-wave-" + i));
                                waveSuccess.incrementAndGet();
                            }
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

        assertThat(sealSuccess.get() + waveSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealSuccess.get() == 1) {
            // 封榜先提交：波次变更全部 409，无波次且存在快照
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findWaves(RACE)).isEmpty();
            assertThat(repository.findSnapshot(RACE)).isPresent();
        } else {
            // 波次先提交：封榜 409，赛事仍 OPEN 且有1个波次
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findWaves(RACE)).hasSize(1);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }

    @Test
    void 同requestId并发波次登记重放只产生一次变更() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, BASE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 5000L, 1, "reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 5000L, 2, "reg-b"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<ServiceResult>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            ServiceResult result = raceService.registerWaves(
                                    RACE, wavesRequest());
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
                assertThat(future.get(30, TimeUnit.SECONDS).status())
                        .isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(repository.findWaves(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }
}
