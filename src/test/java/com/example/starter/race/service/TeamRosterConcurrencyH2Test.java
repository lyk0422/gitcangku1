package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRostersRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 名单锁定的真实并发测试（H2）：同事务版本下并发锁定按提交顺序裁决，
 * 同 rosterKey（requestId）并发只产生一次锁定快照。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team-concurrent";

    @Autowired
    private RaceService raceService;

    private void setupRaceWithTeam() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        raceService.createTeam(RACE, new CreateTeamRequest("t1", "a", 3, "req-team"));
        // 当前赛事版本为 4
    }

    private static LockRostersRequest lockRequest(String requestId) {
        return new LockRostersRequest(
                List.of(new LockRostersRequest.TeamLockRequest("t1", "a", List.of("a", "b"))),
                4, requestId);
    }

    @Test
    void 并发同版本批量锁定仅一个成功且版本只加一() throws Exception {
        setupRaceWithTeam();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.lockRosters(RACE, lockRequest("req-lock-" + i));
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
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findActiveLocks(RACE)).hasSize(1);
        assertThat(repository.findLockMembers(RACE, "t1", 1)).containsExactly("a", "b");
    }

    @Test
    void 并发同rosterKey重放首次结果且只写入一次快照() throws Exception {
        setupRaceWithTeam();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            ServiceResult result =
                                    raceService.lockRosters(RACE, lockRequest("req-lock-same"));
                            // 首次执行与重放均返回 201；重放体为 JsonNode
                            if (result.status() == 201) {
                                success.incrementAndGet();
                            }
                        } catch (RuntimeException ex) {
                            failures.incrementAndGet();
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

        // 同键同参：全部重放首次结果，无失败；业务变更只发生一次
        assertThat(failures.get()).isEqualTo(0);
        assertThat(success.get()).isEqualTo(threads);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findActiveLocks(RACE)).hasSize(1);
        assertThat(raceService.getRoster(RACE, "t1").rosterVersion()).isEqualTo(1);
    }
}
