package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.domain.MedicalHoldStatus;
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
 * 医疗暂停的 H2 真实并发测试：同一选手生效暂停唯一、
 * 并发恢复仅一个成功、同 requestId 并发只产生一次暂停。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-mh-concurrent";
    private static final long FIXED = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    private void seedRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 1, "req-reg-a"));
    }

    @Test
    void 并发开始同一选手暂停仅一个生效() throws Exception {
        seedRunner();

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
                            raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                    "h-" + i, FIXED, "扭伤", "medic-" + i, 2, "req-start-" + i));
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
        // 仅一条暂停记录且生效中；版本只推进一次
        assertThat(raceService.getRunnerMedicalHolds(RACE, "a").holds()).hasSize(1);
        assertThat(repository.findActiveHold(RACE, "a")).isPresent();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 并发恢复同一暂停仅一个成功() throws Exception {
        seedRunner();
        raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", FIXED - 3_000L, "扭伤", "medic-1", 2,
                        "req-start-h-1"));

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
                            raceService.resumeMedicalHold(RACE, "a", "h-1",
                                    new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛",
                                            "medic-r" + i, 3, "req-resume-" + i));
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
        MedicalHoldResponse hold =
                raceService.getRunnerMedicalHolds(RACE, "a").holds().getFirst();
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(hold.durationMs()).isEqualTo(2_000L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 同requestId并发开始只产生一次暂停且结果一致() throws Exception {
        seedRunner();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.startMedicalHold(RACE, "a",
                                    new StartMedicalHoldRequest("h-same", FIXED, "扭伤",
                                            "medic-1", 2, "req-start-same"));
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
            assertThat(first.status()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(raceService.getRunnerMedicalHolds(RACE, "a").holds()).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }
}
