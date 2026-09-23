package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
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
 * 团队写操作的 H2 真实并发测试：同版本创建团队串行化、
 * 同 requestId 并发只产生一次变更、封榜与创建团队互斥。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team-concurrent";

    @Autowired
    private RaceService raceService;

    private void prepareRaceWithRunners() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        for (int i = 0; i < 6; i++) {
            raceService.registerRunner(RACE, new RegisterRunnerRequest(
                    "bib-" + i, null, i + 1, "req-reg-" + i));
        }
        // 准备完成时版本为 7
    }

    @Test
    void 并发同版本创建团队仅一个成功且版本只加一() throws Exception {
        prepareRaceWithRunners();

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
                            raceService.createTeam(RACE, new CreateTeamRequest(
                                    "T" + i,
                                    List.of("bib-0", "bib-1", "bib-" + (2 + i % 4)),
                                    7, "req-team-" + i));
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
        assertThat(race.version()).isEqualTo(8);
        assertThat(repository.findTeams(RACE)).hasSize(1);
        assertThat(repository.findTeamMembers(RACE)).hasSize(3);
    }

    @Test
    void 同requestId并发创建团队只产生一次变更且结果一致() throws Exception {
        prepareRaceWithRunners();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.createTeam(RACE, new CreateTeamRequest(
                                    "T1", List.of("bib-0", "bib-1", "bib-2"),
                                    7, "req-team-same"));
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
            assertThat(first.status()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(repository.findTeams(RACE)).hasSize(1);
        assertThat(repository.findTeamMembers(RACE)).hasSize(3);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
    }

    @Test
    void 封榜与创建团队并发互斥且最终状态自洽() throws Exception {
        prepareRaceWithRunners();

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger teamSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(7, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.createTeam(RACE, new CreateTeamRequest(
                                        "T" + i, List.of("bib-0", "bib-1", "bib-2"),
                                        7, "req-team-" + i));
                                teamSuccess.incrementAndGet();
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

        // 两类操作互斥：恰好一类成功一次，其余全部冲突；版本总共只加一
        assertThat(sealSuccess.get() + teamSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(8);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findTeams(RACE)).isEmpty();
            assertThat(raceService.getTeamStandings(RACE).teams()).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findTeams(RACE)).hasSize(1);
        }
    }
}
