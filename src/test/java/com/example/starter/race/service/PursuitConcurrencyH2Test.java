package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EnablePursuitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SubmitRulingRequest;
import com.example.starter.race.api.SubmitSamplesRequest;
import com.example.starter.race.domain.RulingStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追逐赛并发 H2 测试：裁定与轨迹更正互斥不混版本，
 * 同 requestId 并发只产生一次业务变更，同版本并发写仅一个成功。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PursuitConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit-concurrent";

    @Autowired
    private RaceService raceService;

    private SubmitSamplesRequest syntheticSamples(
            int expectedVersion, int expectedTrajectoryVersion, String requestId, long progressMm) {
        return new SubmitSamplesRequest(
                expectedVersion, expectedTrajectoryVersion,
                List.of(new SubmitSamplesRequest.SamplePointRequest(0L, 0L),
                        new SubmitSamplesRequest.SamplePointRequest(10_000L, progressMm)),
                "op-test", requestId);
    }

    /** 建赛、登记 a/b、启用 PURSUIT、提交双方合成轨迹，返回当前赛事版本6。 */
    private int seedPursuitWithTrajectories() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 1000L)),
                "op-test", "req-enable"));
        raceService.submitSamples(RACE, "a", syntheticSamples(4, 0, "req-sa", 3_000));
        raceService.submitSamples(RACE, "b", syntheticSamples(5, 0, "req-sb", 2_000));
        return 6;
    }

    @Test
    void 裁定与轨迹更正并发互斥且不混版本() throws Exception {
        int version = seedPursuitWithTrajectories();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rulingSuccess = new AtomicInteger();
        AtomicInteger correctionSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            Future<?> rulingFuture = pool.submit(() -> {
                await(start);
                try {
                    raceService.submitRuling(RACE, new SubmitRulingRequest(
                            "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling"));
                    rulingSuccess.incrementAndGet();
                } catch (ConflictException ex) {
                    conflicts.incrementAndGet();
                }
            });
            Future<?> correctionFuture = pool.submit(() -> {
                await(start);
                try {
                    raceService.submitSamples(RACE, "a",
                            syntheticSamples(version, 1, "req-sa2", 3_500));
                    correctionSuccess.incrementAndGet();
                } catch (ConflictException ex) {
                    conflicts.incrementAndGet();
                }
            });
            start.countDown();
            rulingFuture.get(30, TimeUnit.SECONDS);
            correctionFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 恰好一个成功：版本只推进一次，状态不混杂
        assertThat(rulingSuccess.get() + correctionSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
        if (rulingSuccess.get() == 1) {
            // 裁定先生效：轨迹仍是版本1，裁定为 CURRENT 且引用版本(1,1)
            assertThat(repository.findTrajectory(RACE, "a").orElseThrow().version()).isEqualTo(1);
            var ruling = repository.findCurrentRuling(RACE, "pair-1").orElseThrow();
            assertThat(ruling.pursuerTrajectoryVersion()).isEqualTo(1);
            assertThat(ruling.pursuedTrajectoryVersion()).isEqualTo(1);
        } else {
            // 更先生效：轨迹推进到版本2，不产生任何当前裁定
            assertThat(repository.findTrajectory(RACE, "a").orElseThrow().version()).isEqualTo(2);
            assertThat(repository.findCurrentRuling(RACE, "pair-1")).isEmpty();
        }
    }

    @Test
    void 同requestId并发提交裁定只产生一次变更且不泄漏唯一键异常() throws Exception {
        seedPursuitWithTrajectories();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        await(start);
                        try {
                            ServiceResult result = raceService.submitRuling(RACE,
                                    new SubmitRulingRequest(
                                            "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling"));
                            assertThat(result.status()).isEqualTo(201);
                            success.incrementAndGet();
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                        }
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同键同参并发：全部重放成功，业务只生效一次
        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(repository.findCurrentRulings(RACE)).hasSize(1);
        assertThat(repository.findCurrentRulings(RACE).getFirst().status())
                .isEqualTo(RulingStatus.CURRENT);
    }

    @Test
    void 同版本并发提交采样仅一个成功且轨迹版本只推进一次() throws Exception {
        seedPursuitWithTrajectories();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        await(start);
                        try {
                            raceService.submitSamples(RACE, "a",
                                    syntheticSamples(6, 1, "req-corr-" + i, 3_000 + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findTrajectory(RACE, "a").orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
