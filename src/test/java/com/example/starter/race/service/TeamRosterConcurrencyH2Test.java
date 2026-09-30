package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.SnapshotTeamRow;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 上的真实并发测试：名单锁定与封榜在 race 行锁上互斥，
 * 同成员跨队并发锁定由唯一约束保证至多一队成功，且榜单只引用完整版本。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private TeamRosterService teamRosterService;

    @Test
    void 锁定与封榜并发时互斥且榜单引用完整名单版本() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("jia", 1000L, 1, "req-jia"));
        // 当前版本2：锁定与封榜都以 expectedVersion=2 并发提交。
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger lockSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < runs; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        if (index % 2 == 0) {
                            teamRosterService.lockRoster(RACE, "A", new LockRosterRequest(
                                    List.of("jia"), 2, "req-lock-" + index));
                            lockSuccess.incrementAndGet();
                        } else {
                            raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal-" + index));
                            sealSuccess.incrementAndGet();
                        }
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 锁定与封榜互斥：恰好一个成功，其余全部409；版本总共只加一。
        assertThat(lockSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (lockSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            // 锁定先提交：尚无封榜团队快照；团队名单版本完整。
            assertThat(rosterRepository.findTeamSnapshotRows(RACE)).isEmpty();
            assertThat(rosterRepository.findRosterEntries(RACE, "A", 1)).hasSize(1);
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            // 封榜先提交：锁定被拒绝，榜单不引用任何未完成名单。
            assertThat(rosterRepository.findTeam(RACE, "A")).isEmpty();
            List<SnapshotTeamRow> teams = rosterRepository.findTeamSnapshotRows(RACE);
            assertThat(teams).isEmpty();
        }
    }

    @Test
    void 同成员跨队并发锁定至多一队成功() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("jia", 1000L, 1, "req-jia"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("yi", 2000L, 2, "req-yi"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        String teamId = index % 2 == 0 ? "A" : "B";
                        // 各队都想锁入甲；同队并发版本只有一个能推进成功。
                        teamRosterService.lockRoster(RACE, teamId, new LockRosterRequest(
                                List.of("jia"), 3, "req-lock-" + index));
                        success.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 甲只能归属一个团队且赛事版本只允许一个写者成功：恰好一次成功、只建一个队。
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(rosterRepository.findTeams(RACE)).hasSize(1);
        String ownerTeam = rosterRepository.findMembership(RACE, "jia").orElseThrow().teamId();
        assertThat(ownerTeam).isIn("A", "B");
        // 甲在归属队的版本内恰好出现一次，不存在半份名单，另一队未被创建。
        assertThat(rosterRepository.findRosterEntries(RACE, ownerTeam, 1)).hasSize(1);
        String other = ownerTeam.equals("A") ? "B" : "A";
        assertThat(rosterRepository.findTeam(RACE, other)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 同requestId并发锁定只产生一个名单版本() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("jia", 1000L, 1, "req-jia"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<ServiceResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        ServiceResult result = teamRosterService.lockRoster(RACE, "A",
                                new LockRosterRequest(List.of("jia"), 2, "req-lock-same"));
                        success.incrementAndGet();
                        return result;
                    } catch (ConflictException ex) {
                        errors.incrementAndGet();
                        throw ex;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
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
        assertThat(rosterRepository.findRosterLocks(RACE, "A")).hasSize(1);
        assertThat(rosterRepository.findRosterEntries(RACE, "A", 1)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }
}
