package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
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
 * 医疗暂停的 H2 真实并发测试：同一选手同时仅一条生效暂停由唯一约束与
 * 版本串行化保证；暂停与分段计时按提交顺序互斥裁决；同 requestId 并发重放只产生一次变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med-conc";
    private static final long START_AT = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli() + 60_000L;

    @Autowired
    private RaceService raceService;

    private void createRaceWithRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));
    }

    @Test
    void 并发开始同一选手暂停仅一个成功() throws Exception {
        createRaceWithRunner(); // 当前版本2

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
                                    "hold-" + i, START_AT, "处置", "medic-" + i, 2, "req-h-" + i));
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
        // 同一选手同时仅一条生效暂停
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 暂停与分段计时并发互斥且最终状态自洽() throws Exception {
        createRaceWithRunner(); // v2
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1)),
                2, "req-cfg")); // v3

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger holdOk = new AtomicInteger();
        AtomicInteger timingOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                        "hold-" + i, START_AT, "处置", "medic-" + i, 3, "req-h-" + i));
                                holdOk.incrementAndGet();
                            } else {
                                raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                                        "t-" + i, "c1", 100L, 3, "req-t-" + i));
                                timingOk.incrementAndGet();
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

        // 同一版本只允许一个写成功：要么暂停先生效，要么分段先按当时资格入榜
        assertThat(holdOk.get() + timingOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        if (holdOk.get() == 1) {
            // 暂停先提交：分段未写入
            assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();
            assertThat(repository.findAllTimings(RACE)).isEmpty();
        } else {
            // 分段先提交：按当时资格处理（无生效暂停，未排除），暂停未生效
            assertThat(repository.findActiveMedicalHold(RACE, "a")).isEmpty();
            assertThat(repository.findAllTimings(RACE)).hasSize(1);
            assertThat(repository.findAllTimings(RACE).getFirst().excluded()).isFalse();
        }
    }

    @Test
    void 同requestId并发开始暂停只产生一次变更() throws Exception {
        createRaceWithRunner(); // v2

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
                            ServiceResult result = raceService.startMedicalHold(RACE, "a",
                                    new StartMedicalHoldRequest(
                                            "hold-same", START_AT, "处置", "medic-1", 2, "req-same"));
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
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }
}
