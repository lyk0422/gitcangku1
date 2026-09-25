package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RelayConfigRequest;
import com.example.starter.race.api.RelayHandoffRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.AdjustableClockTestConfig;
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
 * H2 上的接力真实并发测试：expectedVersion 条件 UPDATE 串行化交接与封榜，
 * 同 requestId 并发只产生一次交接记录。
 */
@SpringBootTest
@Import(AdjustableClockTestConfig.class)
class RelayConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "relay-concurrent";

    @Autowired
    private RelayService relayService;

    @Autowired
    private RaceService raceService;

    private void setupTwoLegRelay() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        relayService.configureRelay(RACE, new RelayConfigRequest(
                2, 100L,
                java.util.List.of(
                        new RelayConfigRequest.TeamRunners("T1", java.util.List.of("a1", "a2")),
                        new RelayConfigRequest.TeamRunners("T2", java.util.List.of("b1", "b2")),
                        new RelayConfigRequest.TeamRunners("T3", java.util.List.of("c1", "c2")),
                        new RelayConfigRequest.TeamRunners("T4", java.util.List.of("d1", "d2"))),
                1, "req-config"));
    }

    @Test
    void 并发交接与封榜同版本互斥且最终状态自洽() throws Exception {
        setupTwoLegRelay();
        // 当前版本2：4 个末棒交接 + 1 个封榜，全部以 expectedVersion=2 并发提交
        int runs = 5;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger handoffSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i == 0) {
                                raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal"));
                                sealSuccess.incrementAndGet();
                            } else {
                                String team = "T" + i;
                                String receiver = switch (i) {
                                    case 1 -> "a2";
                                    case 2 -> "b2";
                                    case 3 -> "c2";
                                    default -> "d2";
                                };
                                relayService.submitHandoff(RACE, new RelayHandoffRequest(
                                        team, 2, receiver, 1000L + i, 50L, 2, "req-h-" + i));
                                handoffSuccess.incrementAndGet();
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

        assertThat(sealSuccess.get() + handoffSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findRelaySnapshot(RACE)).isPresent();
            assertThat(repository.findRelayFinishes(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findRelaySnapshot(RACE)).isEmpty();
            assertThat(repository.findRelayFinishes(RACE)).hasSize(1);
        }
    }

    @Test
    void 同requestId并发交接只产生一条记录且重放结果一致() throws Exception {
        setupTwoLegRelay();

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
                            ServiceResult result = relayService.submitHandoff(RACE,
                                    new RelayHandoffRequest(
                                            "T1", 2, "a2", 1000L, 50L, 2, "req-same"));
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
        assertThat(repository.findRelayHandoffs(RACE, "T1")).hasSize(1);
        assertThat(repository.findRelayFinishes(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }
}
