package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.SetRankingModeRequest;
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
 * 排名模式变更的 H2 并发测试：同版本并发仅一个成功，
 * 同 requestId 并发只产生一次变更且结果一致（不报唯一键500）。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RankingModeConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-mode-concurrent";

    @Autowired
    private RaceService raceService;

    @Test
    void 并发同版本设模式仅一个成功且版本只加一() throws Exception {
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
                            raceService.setRankingMode(RACE, new SetRankingModeRequest(
                                    "COUNTBACK", 1, "official-" + i, "req-mode-" + i));
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
    void 同requestId并发设模式只一次变更且结果一致() throws Exception {
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
                            return raceService.setRankingMode(RACE, new SetRankingModeRequest(
                                    "COUNTBACK", 1, "official-1", "req-same-mode"));
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(200);
            for (Future<ServiceResult> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
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
