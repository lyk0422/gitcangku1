package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureStagesRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseStageTimingRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitStageTimingRequest;
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
 * 阶段赛事的 H2 真实并发测试：条件 UPDATE 串行化同赛事写操作，
 * 封榜与阶段计时更正互斥，同 requestId 并发只产生一次业务变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class StageConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "stage-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private StageService stageService;

    /** 建赛、登记两名选手并配置两阶段（各一个检查点A），返回当前版本。 */
    private int seedStagesRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-b"));
        stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-stages", "referee", 3,
                List.of(
                        new ConfigureStagesRequest.StageDefinition(1, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1))),
                        new ConfigureStagesRequest.StageDefinition(2, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1))))));
        return 4;
    }

    @Test
    void 并发同版本阶段提交仅一个成功且版本只加一() throws Exception {
        int version = seedStagesRace();

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
                            stageService.submitStageTiming(RACE, 1, "a",
                                    new SubmitStageTimingRequest(
                                            "req-t-" + i, "timer", "t-" + i, "A",
                                            100L + i, version));
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
        assertThat(race.version()).isEqualTo(version + 1);
        assertThat(repository.findStageTimingsForRunner(RACE, 1, "a")).hasSize(1);
    }

    @Test
    void 封榜与阶段计时更正并发时互斥且最终状态自洽() throws Exception {
        int version = seedStagesRace();
        // 两阶段均有完整记录，使封榜可行
        stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-s1a", "timer", "t-s1-a", "A", 100L, version));
        stageService.submitStageTiming(RACE, 2, "a",
                new SubmitStageTimingRequest("req-s2a", "timer", "t-s2-a", "A", 100L, version + 1));
        stageService.submitStageTiming(RACE, 1, "b",
                new SubmitStageTimingRequest("req-s1b", "timer", "t-s1-b", "A", 105L, version + 2));
        stageService.submitStageTiming(RACE, 2, "b",
                new SubmitStageTimingRequest("req-s2b", "timer", "t-s2-b", "A", 105L, version + 3));
        int currentVersion = version + 4;

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger reviseSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(currentVersion, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                stageService.reviseStageTiming(RACE, 1, "t-s1-a",
                                        new ReviseStageTimingRequest(
                                                "req-rev-" + i, "timer", 101L, currentVersion));
                                reviseSuccess.incrementAndGet();
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
        assertThat(sealSuccess.get() + reviseSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(currentVersion + 1);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findSnapshotStages(RACE)).hasSize(2);
            // 封榜快照固化的是更正前的原始点100
            assertThat(repository.findStageTiming("t-s1-a").orElseThrow().elapsedMillis())
                    .isEqualTo(100L);
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findStageTiming("t-s1-a").orElseThrow().elapsedMillis())
                    .isEqualTo(101L);
        }
    }

    @Test
    void 同requestId并发阶段提交只产生一次变更且结果一致() throws Exception {
        int version = seedStagesRace();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        return stageService.submitStageTiming(RACE, 1, "a",
                                new SubmitStageTimingRequest(
                                        "req-same", "timer", "t-same", "A", 100L, version));
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                try {
                    ServiceResult result = future.get(30, TimeUnit.SECONDS);
                    assertThat(result.status()).isEqualTo(first.status());
                } catch (java.util.concurrent.ExecutionException ex) {
                    errors.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(repository.findStageTimingsForRunner(RACE, 1, "a")).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
    }
}
