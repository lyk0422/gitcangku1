package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.SuspensionStatus;
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
 * 中止恢复相关并发测试：并发恢复只成功一次且版本只推进一次，
 * 中止与封榜互斥且最终状态自洽，不会出现半重算中间态。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SuspensionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-susp-concurrent";

    @Autowired
    private RaceService raceService;

    @Test
    void 并发恢复仅一个成功且事件只恢复一次() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("p1", 1)),
                1, "req-cp"));
        raceService.suspendRace(RACE, new SuspendRaceRequest("e1", "p1", 1000L, 2, "req-s1"));
        // 当前版本3，赛事 SUSPENDED

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
                            raceService.resumeRace(RACE, "e1",
                                    new ResumeRaceRequest(2000L, 3, "req-r-" + i));
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
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(4);
        assertThat(repository.findSuspensionEvent("e1").orElseThrow().status())
                .isEqualTo(SuspensionStatus.RESUMED);
        assertThat(repository.findSuspensionEvent("e1").orElseThrow().resumeElapsedMs())
                .isEqualTo(2000L);
    }

    @Test
    void 中止与封榜并发互斥且最终状态自洽() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("p1", 1)),
                2, "req-cp"));
        // 当前版本3：中止与封榜都以 expectedVersion=3 并发提交

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger suspendSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.suspendRace(RACE, new SuspendRaceRequest(
                                        "e-" + i, "p1", 100L, 3, "req-sus-" + i));
                                suspendSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
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

        // 两类操作互斥：恰好一个成功，其余全部冲突；版本总共只推进一次
        assertThat(suspendSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findSuspensionEvents(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findSuspensionEvents(RACE)).hasSize(1);
        }
    }
}
