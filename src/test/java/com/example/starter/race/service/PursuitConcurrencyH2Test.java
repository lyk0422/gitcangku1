package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicatePursuitRequest;
import com.example.starter.race.api.ConfigurePursuitRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SubmitTrajectoryRequest;
import com.example.starter.race.persistence.PursuitRepository;
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
 * 追逐赛并发测试：裁定与轨迹更互不混版本，同 requestId 并发只产生一次业务变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PursuitConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private PursuitRepository pursuitRepository;

    /** 建赛、登记 a/b、启用 PURSUIT、提交双方轨迹，返回当前赛事版本。 */
    private int prepareAdjudicableRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-b"));
        raceService.configurePursuit(RACE, new ConfigurePursuitRequest(
                List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                        1, "a", "b", 0L, 1000L)),
                3, "req-pursuit", "op-config"));
        raceService.submitTrajectory(RACE, "a", new SubmitTrajectoryRequest(
                List.of(new SubmitTrajectoryRequest.SamplePoint(10_000L, 3_000L)),
                0, 4, "req-ta", "op-traj"));
        raceService.submitTrajectory(RACE, "b", new SubmitTrajectoryRequest(
                List.of(new SubmitTrajectoryRequest.SamplePoint(10_000L, 2_000L)),
                0, 5, "req-tb", "op-traj"));
        return 6;
    }

    @Test
    void 裁定与更正并发不混版本且最终状态自洽() throws Exception {
        int version = prepareAdjudicableRace();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger adjudicated = new AtomicInteger();
        AtomicInteger corrected = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                // 基于当前赛事版本与双方轨迹版本1裁定。
                                raceService.adjudicatePursuit(RACE, new AdjudicatePursuitRequest(
                                        1, 1, 1, version, "req-adj-" + i, "op-judge"));
                                adjudicated.incrementAndGet();
                            } else {
                                // 并发更正追赶者轨迹（轨迹版本1 -> 2）。
                                raceService.submitTrajectory(RACE, "a",
                                        new SubmitTrajectoryRequest(
                                                List.of(new SubmitTrajectoryRequest.SamplePoint(
                                                        10_000L, 3_100L)),
                                                1, version, "req-ta-" + i, "op-traj"));
                                corrected.incrementAndGet();
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

        // 同一赛事版本上只会有一个写成功，其余全部版本冲突。
        assertThat(adjudicated.get() + corrected.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(version + 1);
        if (adjudicated.get() == 1) {
            // 裁定成功：当前裁定对应轨迹版本1，轨迹未被更正。
            assertThat(pursuitRepository.findAdjudications(RACE)).hasSize(1);
            assertThat(pursuitRepository.findTrajectory(RACE, "a").orElseThrow().version())
                    .isEqualTo(1);
        } else {
            // 更正成功：旧裁定基础不存在，裁定表为空，轨迹版本推进到2。
            assertThat(pursuitRepository.findAdjudications(RACE)).isEmpty();
            assertThat(pursuitRepository.findTrajectory(RACE, "a").orElseThrow().version())
                    .isEqualTo(2);
        }
    }

    @Test
    void 同requestId并发裁定只产生一次变更且结果一致() throws Exception {
        int version = prepareAdjudicableRace();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.adjudicatePursuit(RACE, new AdjudicatePursuitRequest(
                                    1, 1, 1, version, "req-adj-same", "op-judge"));
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
        // 只裁定一次：版本只推进一次，裁定表一行，无唯一键异常泄漏。
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
        assertThat(pursuitRepository.findAdjudications(RACE)).hasSize(1);
        assertThat(pursuitRepository.findAdjudication(RACE, 1).orElseThrow().catchTimeNum())
                .isEqualTo(10_000L);
    }
}
