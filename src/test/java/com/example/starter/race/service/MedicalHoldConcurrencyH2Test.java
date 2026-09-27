package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.persistence.CheckpointTimingRow;
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
 * H2 上医疗暂停相关的真实并发测试：
 * 同选手并发开始暂停仅一条生效；暂停开始与分段计时同版本互斥，恰一个成功且结果自洽；
 * 同 requestId 并发恢复只产生一次结果。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med-concurrent";
    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();
    private static final long START = T - 10_000L;
    private static final long END = T - 100L;

    @Autowired
    private RaceService raceService;

    @Test
    void 并发开始同一选手暂停仅一条生效() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 9000L, 1, "req-a"));

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
                                    "h-" + i, START, "cramp", "medic-" + i, 2, "req-hold-" + i));
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
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();
    }

    @Test
    void 暂停开始与分段计时同版本并发互斥且结果自洽() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 9000L, 1, "req-a"));
        // 配置 2 个检查点：create(v1) -> register(v2) -> configure(v3)
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(
                        List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                                new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                        2, "req-cps"));

        int runs = 2;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger holdWins = new AtomicInteger();
        AtomicInteger timingWins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i == 0) {
                                raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                        "h-race", START, "cramp", "medic-a", 3, "req-hold"));
                                holdWins.incrementAndGet();
                            } else {
                                ServiceResult result = raceService.submitTiming(RACE, "a",
                                        new SubmitTimingRequest(
                                                "t-race", "cp1", 100L, 3, "req-time"));
                                timingWins.incrementAndGet();
                                assertThat(result.status()).isEqualTo(201);
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

        // 同版本竞争 race 行锁：恰一个成功，另一个版本冲突；版本只加一
        assertThat(holdWins.get() + timingWins.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        if (holdWins.get() == 1) {
            // 暂停先提交：暂停生效，计时未写入
            assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();
            assertThat(repository.findTimingsForRunner(RACE, "a")).isEmpty();
        } else {
            // 计时先提交：cp1 按当时资格正常（无排除原因），暂停未建立
            assertThat(repository.findActiveMedicalHold(RACE, "a")).isEmpty();
            List<CheckpointTimingRow> rows = repository.findTimingsForRunner(RACE, "a");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().exclusionReason()).isNull();
        }
    }

    @Test
    void 同requestId并发恢复只产生一次结果() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 9000L, 1, "req-a"));
        raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                "h-1", START, "cramp", "medic-a", 2, "req-hold"));

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
                            ServiceResult result = raceService.resumeMedicalHold(RACE, "h-1",
                                    new ResumeMedicalHoldRequest(
                                            "h-1", END, "medic-b", "fit", 3, "req-resume-same"));
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
        assertThat(repository.findMedicalHold("h-1").orElseThrow().endAt()).isEqualTo(END);
    }
}
