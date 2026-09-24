package com.example.starter.race.service;

import com.example.starter.race.api.AssignGroupsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.GenerateAdvancementRequest;
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
 * 分组晋级的 H2 真实并发测试：生成与封榜以 expectedVersion 定序互斥，
 * 同一 advancementKey 并发生成只成功一次。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AdvancementConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-adv-concurrent";

    @Autowired
    private RaceService raceService;

    /** 建赛并登记 4 名选手、划分两个分组，返回当前版本。 */
    private int prepareGroupedRace(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                "a1", 100L, 1, "req-a1-" + raceId));
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                "a2", 200L, 2, "req-a2-" + raceId));
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                "b1", 150L, 3, "req-b1-" + raceId));
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                "b2", 250L, 4, "req-b2-" + raceId));
        raceService.assignGroups(raceId, new AssignGroupsRequest(
                5, "req-groups-" + raceId,
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        return 6;
    }

    @Test
    void 生成名单与封榜并发时互斥且最终状态自洽() throws Exception {
        int version = prepareGroupedRace(RACE);

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger generateSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.generateAdvancement(RACE,
                                        new GenerateAdvancementRequest(
                                                "adv-c" + i, 1, 0, version, "req-gen-" + i));
                                generateSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(version, "req-seal-" + i));
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

        // 两类写操作互斥：恰好一类成功一次，其余全部版本冲突；版本总共只加一
        assertThat(generateSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(version + 1);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findActiveAdvancement(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findActiveAdvancement(RACE)).isPresent();
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }

    @Test
    void 同一advancementKey并发生成只成功一次() throws Exception {
        int version = prepareGroupedRace(RACE);

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
                            raceService.generateAdvancement(RACE,
                                    new GenerateAdvancementRequest(
                                            "adv-same", 1, 0, version, "req-gen-same-" + i));
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

        // 同键并发：恰好一次成功（版本冲突或键冲突兜底），名单只有一份
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findActiveAdvancement(RACE)).isPresent();
        assertThat(repository.findAdvancement("adv-same").orElseThrow().entries()).hasSize(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
    }
}
