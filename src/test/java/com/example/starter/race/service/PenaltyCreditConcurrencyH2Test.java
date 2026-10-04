package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmPenaltyCreditRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterPenaltyCreditRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.domain.PenaltyCreditStatus;
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
 * 执行凭证的 H2 真实并发测试：同键并发确认只抵扣一次；
 * 同处罚并发确认由赛事版本裁决串行化，抵扣合计不超过剩余量。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PenaltyCreditConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-credit-concurrent";

    @Autowired
    private RaceService raceService;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void seedRaceWithPenalty(long finishMs, long penaltyMs) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", finishMs, 1, "req-reg-a"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", penaltyMs, 2, "req-pen-1"));
    }

    @Test
    void 同requestId并发确认同一凭证只抵扣一次() throws Exception {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 6_000L, "ref-1", version(), "req-c1"));
        ConfirmPenaltyCreditRequest confirm =
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf-same");

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
                            ServiceResult result =
                                    raceService.confirmPenaltyCredit(RACE, "c-1", confirm);
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
                assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        // 6000 只抵扣一次
        assertThat(repository.findCredit("c-1").orElseThrow().status())
                .isEqualTo(PenaltyCreditStatus.CONFIRMED);
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(104_000L);
    }

    @Test
    void 并发确认同一处罚的不同凭证时抵扣合计不超过剩余量() throws Exception {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 8_000L, "ref-1", version(), "req-c1"));
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-2", "p-1", "a", 10_000L, 18_000L, "ref-1", version(), "req-c2"));
        int expectedVersion = version();

        // 两个确认以相同预期版本并发提交：版本裁决下恰好一个成功
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        String[] creditIds = {"c-1", "c-2"};
        try {
            var futures = IntStream.range(0, 2)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) () -> {
                        start.await();
                        try {
                            raceService.confirmPenaltyCredit(RACE, creditIds[i],
                                    new ConfirmPenaltyCreditRequest(
                                            "ref-2", expectedVersion, "req-cf-" + i));
                            success.incrementAndGet();
                            return null;
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                            return creditIds[i];
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            String loser = null;
            for (Future<String> future : futures) {
                String failed = future.get(30, TimeUnit.SECONDS);
                if (failed != null) {
                    loser = failed;
                }
            }
            assertThat(success.get()).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(1);

            // 失败方以当前版本重试：剩余量封顶，合计不超处罚
            raceService.confirmPenaltyCredit(RACE, loser,
                    new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf-retry"));
        } finally {
            pool.shutdownNow();
        }

        long totalCredited = repository.sumCreditedMsForPenalty("p-1");
        assertThat(totalCredited).isEqualTo(10_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(10_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(100_000L);
    }
}