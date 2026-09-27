package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.ActiveMedicalHoldRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
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
 * 医疗暂停在 H2 上的真实并发测试：生效暂停唯一约束、同 requestId 并发重放、
 * 暂停开始与封榜互斥；均协调真实写操作并设超时，最终核对业务数据而非打印判断。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class MedicalHoldConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med-concurrent";
    private static final long START_AT = 1_800_000_000_000L;

    @Autowired
    private RaceService raceService;

    @Test
    void 并发开始暂停仅一个成功且证据与生效行各一条() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));

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
                            // 全部以版本2并发提交，holdId 与 requestId 各不相同
                            raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                    "hold-" + i, START_AT, "reason", "medic-" + i, 2,
                                    "req-hold-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            // 版本冲突或生效暂停唯一冲突均为409
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
        assertThat(repository.findMedicalHolds(RACE)).hasSize(1);
        assertThat(repository.findActiveMedicalHolds(RACE)).hasSize(1);
        ActiveMedicalHoldRow active = repository.findActiveMedicalHold(RACE, "a").orElseThrow();
        assertThat(active.startAt()).isEqualTo(START_AT);
        // 版本只被推进一次
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 同requestId并发开始暂停全部重放首次结果且只有一条证据() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));

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
                                            "hold-same", START_AT, "reason", "medic-a", 2,
                                            "req-hold-same"));
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
        assertThat(repository.findMedicalHolds(RACE)).hasSize(1);
        assertThat(repository.findActiveMedicalHolds(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 暂停开始与封榜并发互斥且最终状态自洽() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));
        // 当前版本2：开始暂停与封榜都以 expectedVersion=2 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger holdSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                        "hold-" + i, START_AT, "reason", "medic-" + i, 2,
                                        "req-hold-" + i));
                                holdSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(2, "req-seal-" + i));
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

        assertThat(holdSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findMedicalHolds(RACE)).isEmpty();
            // 封榜后恢复必然被拒绝
            assertThat(repository.findActiveMedicalHolds(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findMedicalHolds(RACE)).hasSize(1);
            assertThat(repository.findActiveMedicalHolds(RACE)).hasSize(1);
        }
    }

    @Test
    void 并发重复恢复同一条暂停仅一个成功且恢复证据唯一() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));
        raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                "hold-1", START_AT, "reason", "medic-a", 2, "req-hold-1"));

        // 版本3：多个不同 requestId 的恢复并发，恢复事件主键与 race 行锁共同保证仅一个成功
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
                            raceService.resumeMedicalHold(RACE, "hold-1",
                                    new ResumeMedicalHoldRequest(
                                            START_AT + 60_000, "medic-b", "fit-" + i, 3,
                                            "req-resume-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            // 版本冲突或暂停已恢复均为409
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
        assertThat(repository.findResumption("hold-1")).isPresent();
        assertThat(repository.findActiveMedicalHolds(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }
}
