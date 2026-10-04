package com.example.starter.race.service;

import com.example.starter.race.api.AddExecutionRequest;
import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmExecutionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
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
 * 执行凭证的 H2 真实并发测试：同一凭证并发确认只抵扣一次，
 * 重叠区间并发确认只有一个成功，同 requestId 并发重放同一响应。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ExecutionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-exec-concurrent";

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

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void setupRaceWithExecution() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100_000L, version(), "req-reg-a"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "a", "ADD_TIME", 10_000L, version(), "req-pen-p1"));
        raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-1", "a", "p-1", 0L, 6_000L, "judge-1", version(), "req-ex-1"));
    }

    private ResultEntryResponse entryOf(String bib) {
        return raceService.getResults(RACE).entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 并发确认同一凭证仅一个成功且只抵扣一次() throws Exception {
        setupRaceWithExecution();
        int expectedVersion = version();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<Void>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.confirmExecution(RACE, "ex-1",
                                    new ConfirmExecutionRequest(
                                            "judge-2", expectedVersion, "req-c-" + i));
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
        // 6000 只被抵扣一次
        assertThat(entryOf("a").executedMs()).isEqualTo(6_000L);
        assertThat(entryOf("a").totalTimeMs()).isEqualTo(104_000L);
        assertThat(repository.sumCreditedForPenalty("p-1")).isEqualTo(6_000L);
    }

    @Test
    void 并发同requestId确认重放同一响应且只抵扣一次() throws Exception {
        setupRaceWithExecution();
        int expectedVersion = version();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<ServiceResult>();
        try {
            List<Future<Void>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        results.add(raceService.confirmExecution(RACE, "ex-1",
                                new ConfirmExecutionRequest(
                                        "judge-2", expectedVersion, "req-c-same")));
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

        assertThat(results).hasSize(threads);
        assertThat(results).allMatch(result -> result.status() == 200);
        assertThat(results.stream()
                .map(result -> json(result.body()))
                .distinct()
                .count()).isEqualTo(1);
        assertThat(entryOf("a").executedMs()).isEqualTo(6_000L);
        assertThat(entryOf("a").totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 并发确认重叠区间仅一个成功() throws Exception {
        setupRaceWithExecution();
        raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-2", "a", "p-1", 3_000L, 9_000L, "judge-1", version(), "req-ex-2"));
        int expectedVersion = version();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            List<Future<Void>> futures = List.of("ex-1", "ex-2").stream()
                    .map(executionId -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.confirmExecution(RACE, executionId,
                                    new ConfirmExecutionRequest(
                                            "judge-2", expectedVersion, "req-c-" + executionId));
                            success.incrementAndGet();
                        } catch (ConflictException | UnprocessableEntityException ex) {
                            // 版本冲突或区间重叠拒绝
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
        // 无论哪条凭证胜出，抵扣恰好一次6000
        assertThat(entryOf("a").executedMs()).isEqualTo(6_000L);
        assertThat(entryOf("a").totalTimeMs()).isEqualTo(104_000L);
    }
}