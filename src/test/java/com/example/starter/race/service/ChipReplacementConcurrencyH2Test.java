package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReplaceChipRequest;
import com.example.starter.race.api.SealRaceRequest;
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
 * 芯片绑定与更换的 H2 真实并发测试：同芯片并发绑定最多一次成功、
 * 更换与封榜按提交顺序互斥、同 requestId 并发重放只产生一次变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ChipReplacementConcurrencyH2Test extends AbstractRaceH2Test {

    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    @Test
    void 同芯片并发绑定最多一次成功() throws Exception {
        raceService.createRace(new CreateRaceRequest("race-c1", "req-c1"));
        raceService.createRace(new CreateRaceRequest("race-c2", "req-c2"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        String raceId = i % 2 == 0 ? "race-c1" : "race-c2";
                        try {
                            raceService.registerRunner(raceId, new RegisterRunnerRequest(
                                    "bib-" + i, 1000L, "CHIP-X", 1, "req-reg-" + i));
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

        // 同芯片全局最多一条生效绑定：跨赛事也只有一人成功
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findChipActive("CHIP-X")).isPresent();
        int totalRunners = repository.findRunners("race-c1").size()
                + repository.findRunners("race-c2").size();
        assertThat(totalRunners).isEqualTo(1);
    }

    @Test
    void 更换与封榜并发时互斥且最终状态自洽() throws Exception {
        String race = "race-cs";
        raceService.createRace(new CreateRaceRequest(race, "req-create"));
        raceService.registerRunner(race,
                new RegisterRunnerRequest("a", 1000L, "CHIP-A", 1, "req-a"));
        // 当前版本为2：更换与封榜都以 expectedVersion=2 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger replaceSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.replaceChip(race, "a", new ReplaceChipRequest(
                                        "repl-" + i, "CHIP-A", "CHIP-B" + i, T,
                                        "ref-1", "op-1", 2, "req-repl-" + i));
                                replaceSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(race, new SealRaceRequest(2, "req-seal-" + i));
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

        // 两类写操作互斥：恰好一个成功，其余全部版本冲突；版本只推进一次
        assertThat(replaceSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow raceRow = repository.findRace(race).orElseThrow();
        assertThat(raceRow.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            assertThat(raceRow.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(race)).isPresent();
            assertThat(repository.findChipReplacementsForRunner(race, "a")).isEmpty();
            assertThat(repository.findChipActive("CHIP-A")).isPresent();
        } else {
            assertThat(raceRow.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(race)).isEmpty();
            assertThat(repository.findChipReplacementsForRunner(race, "a")).hasSize(1);
            assertThat(repository.findChipActive("CHIP-A")).isEmpty();
        }
    }

    @Test
    void 同requestId并发更换只产生一次变更且结果一致() throws Exception {
        String race = "race-cr";
        raceService.createRace(new CreateRaceRequest(race, "req-create"));
        raceService.registerRunner(race,
                new RegisterRunnerRequest("a", 1000L, "CHIP-A", 1, "req-a"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.replaceChip(race, "a", new ReplaceChipRequest(
                                    "repl-1", "CHIP-A", "CHIP-B", T,
                                    "ref-1", "op-1", 2, "req-same"));
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
        assertThat(repository.findChipReplacementsForRunner(race, "a")).hasSize(1);
        assertThat(repository.findRace(race).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findChipActive("CHIP-B")).isPresent();
        assertThat(repository.findChipActive("CHIP-A")).isEmpty();
    }
}