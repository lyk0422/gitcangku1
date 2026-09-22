package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecordSplitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
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
 * 分段计时的 H2 真实并发测试：分段提交与封榜互斥、
 * requestId+timingId 双重幂等并发、同版本相邻分段提交串行化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RaceSplitConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-split-concurrent";

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

    /** 建赛（v1）、配置 cp1/cp2（v2）、登记选手 a=10000ms（v3）。 */
    private void setupRaceWithCheckpoints() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1", "cp2"), 1, "req-cp"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
    }

    @Test
    void 分段提交与封榜并发互斥且最终状态自洽() throws Exception {
        setupRaceWithCheckpoints();
        // 当前版本3：封榜与分段提交都以 expectedVersion=3 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger splitSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.recordSplit(RACE, "a", new RecordSplitRequest(
                                        "cp1", 1000L, 3, "t-" + i, "req-split-" + i));
                                splitSuccess.incrementAndGet();
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

        // 两类写操作互斥：恰好一个成功，其余全部版本冲突；版本总共只加一
        assertThat(sealSuccess.get() + splitSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findSplitsForRace(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findSplitsForRace(RACE)).hasSize(1);
        }
    }

    @Test
    void 同requestId同timingId并发提交只产生一条分段记录() throws Exception {
        setupRaceWithCheckpoints();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.recordSplit(RACE, "a",
                                    new RecordSplitRequest(
                                            "cp1", 1000L, 3, "t-same", "req-same"));
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(201);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(201);
                // 重放线程返回 JsonNode，按 JSON 内容比较响应一致
                assertThat(json(result.body())).isEqualTo(json(first.body()));
            }
        } finally {
            pool.shutdownNow();
        }

        // 双重幂等（requestId + timingId）：并发重放全部返回原结果，仅一条记录，版本只加一
        assertThat(errors.get()).isZero();
        assertThat(repository.findSplitsForRace(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 同版本并发提交相邻分段仅一个成功且相邻约束不被破坏() throws Exception {
        setupRaceWithCheckpoints();

        // 同一选手同一版本并发提交 cp1=1000 与 cp2=2000：版本串行化后仅一个成功
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<Void>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        String checkpoint = i % 2 == 0 ? "cp1" : "cp2";
                        long elapsed = i % 2 == 0 ? 1000L : 2000L;
                        try {
                            raceService.recordSplit(RACE, "a", new RecordSplitRequest(
                                    checkpoint, elapsed, 3, "t-" + i, "req-" + i));
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
        assertThat(repository.findSplitsForRace(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }
}
