package com.example.starter.race.service;

import com.example.starter.race.api.ChangeRankingModeRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.domain.RankingMode;
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
 * 排名模式变更的 H2 真实并发测试：同版本并发变更仅一个成功且版本只加一；
 * 同 requestId 并发重放只产生一次变更且结果一致。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CountbackConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-cb-concurrent";

    @Autowired
    private RaceService raceService;

    @Test
    void 并发同版本变更模式仅一个成功且版本只加一() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

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
                            raceService.changeRankingMode(RACE, new ChangeRankingModeRequest(
                                    "COUNTBACK", 1, "req-mode-" + i));
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
        assertThat(race.version()).isEqualTo(2);
        assertThat(race.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
    }

    @Test
    void 同requestId并发变更只产生一次变更且结果一致() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.changeRankingMode(RACE,
                                    new ChangeRankingModeRequest(
                                            "COUNTBACK", 1, "req-same"));
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
        assertThat(race.version()).isEqualTo(2);
        assertThat(race.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
    }
}
