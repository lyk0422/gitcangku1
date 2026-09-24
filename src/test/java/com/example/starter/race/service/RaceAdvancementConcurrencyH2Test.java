package com.example.starter.race.service;

import com.example.starter.race.api.AdvancementResponse;
import com.example.starter.race.api.AssignGroupsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.GenerateAdvancementRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.AdvancementListStatus;
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
 * 晋级名单在真实 H2 上的并发测试：expectedVersion 定序，
 * 同版本并发生成仅一份成功，名单生成与封榜互斥，同 requestId 并发重放只产生一份名单。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RaceAdvancementConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-adv-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.example.starter.race.persistence.AdvancementRepository advancementRepository;

    /** 建赛、登记4名选手并划分为 A/B 两组，返回分组后的赛事版本。 */
    private int setupGroupedRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        int version = 1;
        for (String bib : List.of("a1", "a2", "b1", "b2")) {
            Long finish = switch (bib) {
                case "a1" -> 1000L;
                case "a2" -> 1100L;
                case "b1" -> 1050L;
                default -> 1150L;
            };
            raceService.registerRunner(RACE,
                    new RegisterRunnerRequest(bib, finish, version, "req-reg-" + bib));
            version++;
        }
        raceService.assignGroups(RACE, new AssignGroupsRequest(version, "req-groups",
                List.of(new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        return version + 1;
    }

    @Test
    void 同版本并发生成名单仅一个成功且只有一份生效名单() throws Exception {
        int version = setupGroupedRace();

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
                            raceService.generateAdvancement(RACE,
                                    new GenerateAdvancementRequest(
                                            "adv-c-" + i, 1, 1, version, "req-adv-c-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
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
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(version + 1);
        assertThat(advancementRepository.findActiveAdvancement(RACE)).isPresent();
        assertThat(advancementRepository.findAdvancementHistory(RACE)).hasSize(1);
    }

    @Test
    void 名单生成与封榜并发时互斥且最终状态自洽() throws Exception {
        int version = setupGroupedRace();

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger advancementSuccess = new AtomicInteger();
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
                                                "adv-seal-c-" + i, 1, 1, version,
                                                "req-adv-seal-c-" + i));
                                advancementSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(version, "req-seal-c-" + i));
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
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两类操作互斥：恰好一类成功一次，其余冲突；版本总共只加一。
        assertThat(advancementSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(version + 1);
        if (sealSuccess.get() == 1) {
            // 封榜先提交：OPEN 名单生成机会永久关闭，赛事 SEALED 且无名单。
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(advancementRepository.findActiveAdvancement(RACE)).isEmpty();
        } else {
            // 名单先生成：赛事仍 OPEN，存在一份 ACTIVE 名单且无封榜快照。
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            AdvancementResponse active = raceService.getActiveAdvancement(RACE);
            assertThat(active.status()).isEqualTo(AdvancementListStatus.ACTIVE);
            assertThat(active.entries()).hasSize(3);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }

    @Test
    void 同requestId并发生成重放只产生一份名单() throws Exception {
        int version = setupGroupedRace();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        try {
            List<Future<ServiceResult>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        ServiceResult result = raceService.generateAdvancement(RACE,
                                new GenerateAdvancementRequest(
                                        "adv-replay", 1, 1, version, "req-adv-replay"));
                        done.incrementAndGet();
                        return result;
                    }))
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

        assertThat(done.get()).isEqualTo(threads);
        assertThat(advancementRepository.findAdvancementHistory(RACE)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
    }
}
