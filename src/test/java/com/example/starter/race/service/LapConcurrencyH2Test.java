package com.example.starter.race.service;

import com.example.starter.race.api.CorrectLapRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitLapRequest;
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
 * 绕圈赛事的 H2 真实并发测试：圈次提交、更正与封榜按同一赛事版本竞争，
 * 只能冻结一套自洽圈次；同 requestId 并发只产生一次业务变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class LapConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-loop-concurrent";

    @Autowired
    private RaceService raceService;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void createLoopAndRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "LOOP", 2, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, version(), "req-reg-a"));
    }

    private void submit(String id, int lap, String point, long elapsed) {
        raceService.submitLap(RACE, "a",
                new SubmitLapRequest(id, lap, point, elapsed, "timer-1", version(), "req-" + id));
    }

    @Test
    void 并发圈次提交同版本仅一个成功且版本只加一() throws Exception {
        createLoopAndRunner();
        int expected = version();

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
                            raceService.submitLap(RACE, "a", new SubmitLapRequest(
                                    "lp-c" + i, 1, "A", 1_000L + i, "op-" + i,
                                    expected, "req-c" + i));
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
        assertThat(version()).isEqualTo(expected + 1);
        assertThat(repository.findLapPassesForRunner(RACE, "a")).hasSize(1);
    }

    @Test
    void 更正与封榜并发互斥且最终状态自洽() throws Exception {
        createLoopAndRunner();
        submit("lp-1A", 1, "A", 40_000);
        submit("lp-1B", 1, "B", 50_000);
        submit("lp-1F", 1, "FINISH", 60_000);
        submit("lp-2A", 2, "A", 70_000);
        submit("lp-2B", 2, "B", 80_000);
        submit("lp-2F", 2, "FINISH", 120_000);
        int expected = version();

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger correctSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(expected, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.correctLap(RACE, "a", new CorrectLapRequest(
                                        "lp-1A", "lp-1A-v" + i, 41_000L, "op-" + i,
                                        expected, "req-correct-" + i));
                                correctSuccess.incrementAndGet();
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

        // 两类操作按同一版本竞争：恰好一个成功，其余全部冲突；版本总共只加一
        assertThat(sealSuccess.get() + correctSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(expected + 1);
        if (sealSuccess.get() == 1) {
            // 封榜冻结的是更正前的自洽圈次：无更正历史，原记录仍是当前记录
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findLapHistoryForRunner(RACE, "a")).isEmpty();
            assertThat(repository.findLapPass("lp-1A")).isPresent();
        } else {
            // 更正先成功：未封榜，历史恰一条，新记录成为当前记录
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findLapHistoryForRunner(RACE, "a")).hasSize(1);
            assertThat(repository.findLapPass("lp-1A")).isEmpty();
        }
    }

    @Test
    void 同requestId并发圈次提交只产生一次变更且结果一致() throws Exception {
        createLoopAndRunner();
        int expected = version();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.submitLap(RACE, "a", new SubmitLapRequest(
                                    "lp-same", 1, "A", 1_000L, "op",
                                    expected, "req-same"));
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
        assertThat(repository.findLapPassesForRunner(RACE, "a")).hasSize(1);
        assertThat(version()).isEqualTo(expected + 1);
    }
}