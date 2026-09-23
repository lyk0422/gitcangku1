package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.domain.EventStatus;
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
 * 中止/恢复与其他写操作的 H2 真实并发测试：
 * 按提交顺序串行化，恢复重算原子完成，不存在半重算中间态。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SuspensionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-susp-conc";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void seedSuspendedRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 20_000L, 1, "req-a"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("k1", 1)),
                2, "req-cfg"));
        raceService.suspendRace(RACE,
                new SuspendRaceRequest("e1", "k1", 6_000L, 3, "req-suspend"));
        // 当前版本 4，状态 SUSPENDED
    }

    @Test
    void 恢复与分段提交并发互斥且最终状态自洽() throws Exception {
        seedSuspendedRace();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger resumeOk = new AtomicInteger();
        AtomicInteger timingOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.resumeRace(RACE, "e1",
                                        new ResumeRaceRequest(10_000L, 4, "req-resume-" + i));
                                resumeOk.incrementAndGet();
                            } else {
                                raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                                        "t-" + i, "k1", 500L, 4, "req-t-" + i));
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

        // 恢复成功恰一次；分段在中止中提交全部 409；版本只推进一次
        assertThat(resumeOk.get()).isEqualTo(1);
        assertThat(timingOk.get()).isEqualTo(0);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(5);
        // 重算完整落库：事件已恢复，无半重算中间态
        assertThat(repository.findEvent("e1").orElseThrow().status())
                .isEqualTo(EventStatus.RESUMED);
        assertThat(repository.findAllTimings(RACE)).isEmpty();
    }

    @Test
    void 中止与封榜并发按提交顺序只有一个成功() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 20_000L, 1, "req-a"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("k1", 1)),
                2, "req-cfg"));
        // 当前版本 3：中止与封榜都以 expectedVersion=3 并发提交
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger suspendOk = new AtomicInteger();
        AtomicInteger sealOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.suspendRace(RACE, new SuspendRaceRequest(
                                        "e-" + i, "k1", 6_000L, 3, "req-susp-" + i));
                                suspendOk.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
                                sealOk.incrementAndGet();
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

        assertThat(suspendOk.get() + sealOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealOk.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findEvents(RACE)).isEmpty();
            assertThat(repository.findSnapshot(RACE)).isPresent();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
            assertThat(repository.findEvents(RACE)).hasSize(1);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }

    @Test
    void 恢复与计时修订并发按提交顺序不半重算() throws Exception {
        seedSuspendedRace();
        // 先恢复使赛事回到 OPEN（v5），再并发修订与再次中止
        raceService.resumeRace(RACE, "e1", new ResumeRaceRequest(10_000L, 4, "req-resume"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reviseOk = new AtomicInteger();
        AtomicInteger suspendOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.reviseTime(RACE,
                                        new ReviseTimeRequest("a", 25_000L, 5, "req-rev-" + i));
                                reviseOk.incrementAndGet();
                            } else {
                                raceService.suspendRace(RACE, new SuspendRaceRequest(
                                        "e2-" + i, "k1", 12_000L, 5, "req-susp2-" + i));
                                suspendOk.incrementAndGet();
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

        // 同一版本只允许一个写成功；最终状态与已提交结果一致
        assertThat(reviseOk.get() + suspendOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(6);
        if (reviseOk.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findRunner(RACE, "a").orElseThrow().finishTimeMs())
                    .isEqualTo(25_000L);
            assertThat(repository.findEvents(RACE)).hasSize(1);
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
            assertThat(repository.findRunner(RACE, "a").orElseThrow().finishTimeMs())
                    .isEqualTo(20_000L);
            assertThat(repository.findEvents(RACE)).hasSize(2);
        }
    }

    @Test
    void 同requestId并发恢复只产生一次变更且结果一致() throws Exception {
        seedSuspendedRace();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.resumeRace(RACE, "e1",
                                    new ResumeRaceRequest(10_000L, 4, "req-resume-same"));
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
                assertThat(json(result.body())).isEqualTo(json(first.body()));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(5);
        assertThat(repository.findEvents(RACE)).hasSize(1);
        assertThat(repository.findEvent("e1").orElseThrow().resumeElapsedMs())
                .isEqualTo(10_000L);
    }
}
