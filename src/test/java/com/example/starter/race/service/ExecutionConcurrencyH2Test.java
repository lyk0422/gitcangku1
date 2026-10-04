package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmExecutionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterExecutionRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
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
 * 执行凭证的 H2 真实并发测试：同凭证并发确认只成功一次、
 * 同处罚并发确认的抵扣总额不超过处罚量、同 requestId 并发重放一致。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ExecutionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-exec-concurrent";
    private static final String BIB = "a";
    private static final String PENALTY = "p-1";

    @Autowired
    private RaceService raceService;

    /** 建赛（v1) + 选手（v2) + 处罚10000(v3) + 登记凭证（v4)。 */
    private void seedWithVoucher(String voucherId, long start, long end) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, 100_000L, 1, "req-reg"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", 10_000L, 2, "req-pen"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest(voucherId, BIB, start, end, "ref-a", 3, "req-ex-1"));
    }

    @Test
    void 并发确认同一凭证仅一个成功且只抵扣一次() throws Exception {
        seedWithVoucher("v-1", 1_000L, 7_000L);

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
                            raceService.confirmExecution(RACE, PENALTY, "v-1",
                                    new ConfirmExecutionRequest("ref-b", 4, "req-cf-" + i));
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
        // 6000 只被抵扣一次，版本只推进一次
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(104_000L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 并发确认不同凭证抵扣总额不超过处罚量() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, 100_000L, 1, "req-reg"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", 10_000L, 2, "req-pen"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 0L, 8_000L, "ref-a", 3, "req-ex-1"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-2", BIB, 8_000L, 16_000L, "ref-a", 4, "req-ex-2"));

        // 两个凭证区间长度各8000，处罚仅10000：并发确认后总抵扣必须等于10000而非16000
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = List.of("v-1", "v-2").stream()
                    .map(voucherId -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        confirmWithCurrentVersion(voucherId);
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

        assertThat(repository.findExecution("v-1").orElseThrow().status().name())
                .isEqualTo("CONFIRMED");
        assertThat(repository.findExecution("v-2").orElseThrow().status().name())
                .isEqualTo("CONFIRMED");
        long totalServed = raceService.getResults(RACE).entries().getFirst().servedMs();
        assertThat(totalServed).isEqualTo(10_000L);
        // 净处罚不为负：总耗时=原始耗时
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(100_000L);
    }

    /** 读取当前版本后确认；版本竞争冲突时以最新版本重试，直到成功。 */
    private void confirmWithCurrentVersion(String voucherId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int version = raceService.getResults(RACE).version();
            try {
                raceService.confirmExecution(RACE, PENALTY, voucherId,
                        new ConfirmExecutionRequest("ref-b", version, "req-cf-" + voucherId));
                return;
            } catch (ConflictException ex) {
                // 版本竞争：以最新版本重试
            }
        }
        throw new IllegalStateException("确认重试次数耗尽: " + voucherId);
    }

    @Test
    void 并发同requestId确认重放一致且只确认一次() throws Exception {
        seedWithVoucher("v-1", 1_000L, 7_000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.confirmExecution(RACE, PENALTY, "v-1",
                                    new ConfirmExecutionRequest("ref-b", 4, "req-cf-same"));
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
            assertThat(first.status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }
}