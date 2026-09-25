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
 * 波次登记与封榜并发的 H2 真实并发测试：二者在赛事行锁上串行化，
 * 按事务提交顺序裁决——封榜先提交则波次变更 409，波次先提交则封榜 409，
 * 恰好一类成功，最终状态自洽。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WaveConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wave-concurrent";
    private static final long BASE = 1_760_000_000_000L;

    @Autowired
    private RaceService raceService;

    @Test
    void 封榜与波次登记并发按提交顺序互斥裁决() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, BASE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100_000L, 1, "req-a"));
        // 当前版本为2：封榜与波次登记都以 expectedVersion=2 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger waveSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(2, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                RegisterWavesRequest.WaveDefinition wave =
                                        new RegisterWavesRequest.WaveDefinition(
                                                "w" + i, BASE + 1_000L, List.of("a"));
                                raceService.registerWaves(RACE,
                                        new RegisterWavesRequest(List.of(wave), 2,
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
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两类操作互斥：恰好一类成功一次，其余全部冲突；版本总共只加一
        assertThat(sealSuccess.get() + waveSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            // 封榜先提交：波次变更全部 409，无波次写入
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findWaves(RACE)).isEmpty();
        } else {
            // 波次先提交：封榜 409，赛事仍 OPEN，恰有一个波次
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findWaves(RACE)).hasSize(1);
        }
    }
}
