package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RosterLockResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.domain.TeamStatus;
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
 * 名单锁定的真实并发测试（H2）：同 rosterKey 并发重放、
 * 锁定与个人成绩变更按事务提交顺序裁决、并发争用同一成员仅一队成功。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private void setupRaceWithTeams() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 2000L, 2, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", 3000L, 3, "req-c"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("d", 4000L, 4, "req-d"));
        raceService.createTeam(RACE, new CreateTeamRequest("t1", "a", 5, "req-team-t1"));
        raceService.createTeam(RACE, new CreateTeamRequest("t2", "c", 6, "req-team-t2"));
        // 当前赛事版本为7
    }

    @Test
    void 并发相同锁定请求_同键重放只产生一个名单版本() throws Exception {
        setupRaceWithTeams();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        return raceService.lockRoster(RACE, "t1",
                                new LockRosterRequest("a", List.of("a", "b"), 7));
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            RosterLockResponse first = null;
            for (Future<ServiceResult> future : futures) {
                try {
                    ServiceResult result = future.get(30, TimeUnit.SECONDS);
                    success.incrementAndGet();
                    // 重放的响应体为 JsonNode，统一转换后比较
                    RosterLockResponse actual = objectMapper.convertValue(
                            result.body(), RosterLockResponse.class);
                    if (first == null) {
                        first = actual;
                    } else {
                        assertThat(actual.rosterKey()).isEqualTo(first.rosterKey());
                        assertThat(actual.rosterVersion()).isEqualTo(first.rosterVersion());
                        assertThat(actual.raceVersion()).isEqualTo(first.raceVersion());
                    }
                } catch (java.util.concurrent.ExecutionException ex) {
                    errors.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        // 同键重放：只写入一个锁定版本，赛事版本只推进一次
        assertThat(repository.findRosterLocks(RACE, "t1")).hasSize(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(8);
        assertThat(repository.findTeam(RACE, "t1").orElseThrow().status())
                .isEqualTo(TeamStatus.LOCKED);
    }

    @Test
    void 锁定与处罚并发_按事务提交顺序裁决且状态自洽() throws Exception {
        setupRaceWithTeams();
        // 先锁定 t1，使团队得分依赖个人成绩
        raceService.lockRoster(RACE, "t1", new LockRosterRequest("a", List.of("a", "b"), 7));
        // 当前版本8：t2 锁定与对 a 的加时处罚以 expectedVersion=8 并发提交
        int penaltyThreads = 5;
        int total = penaltyThreads + 1;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger lockSuccess = new AtomicInteger();
        AtomicInteger penaltySuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, total)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i == 0) {
                                raceService.lockRoster(RACE, "t2",
                                        new LockRosterRequest("c", List.of("c", "d"), 8));
                                lockSuccess.incrementAndGet();
                            } else {
                                raceService.addPenalty(RACE, new AddPenaltyRequest(
                                        "pen-" + i, "a", "ADD_TIME", 100L, 8, "req-pen-" + i));
                                penaltySuccess.incrementAndGet();
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

        // 同一赛事版本上互斥：恰好一个写成功，其余全部409，版本只推进一次
        assertThat(lockSuccess.get() + penaltySuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(total - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(9);
        TeamStandingsResponse standings = raceService.getTeamStandings(RACE);
        if (lockSuccess.get() == 1) {
            // 锁定胜出：t2 已锁定且无处罚生效，两队得分按版本9重算
            assertThat(repository.findPenalties(RACE)).isEmpty();
            assertThat(standings.teams()).hasSize(2);
            assertThat(standings.teams()).allMatch(team -> team.raceVersion() == 9);
        } else {
            // 处罚胜出：t2 未锁定，t1 得分含100ms加时
            assertThat(repository.findPenalties(RACE)).hasSize(1);
            assertThat(standings.teams()).hasSize(1);
            assertThat(standings.teams().getFirst().teamId()).isEqualTo("t1");
            assertThat(standings.teams().getFirst().raceVersion()).isEqualTo(9);
            assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(3100L);
        }
    }

    @Test
    void 并发争用同一成员_仅一支队伍锁定成功() throws Exception {
        setupRaceWithTeams();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger t1Success = new AtomicInteger();
        AtomicInteger t2Success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = List.of(
                    pool.submit((java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.lockRoster(RACE, "t1",
                                    new LockRosterRequest("a", List.of("a", "b"), 7));
                            t1Success.incrementAndGet();
                        } catch (ConflictException | UnprocessableEntityException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }),
                    pool.submit((java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.lockRoster(RACE, "t2",
                                    new LockRosterRequest("c", List.of("b", "c"), 7));
                            t2Success.incrementAndGet();
                        } catch (ConflictException | UnprocessableEntityException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }));
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一版本上只有一个锁定提交成功；成员 b 最终只属于胜出队伍
        assertThat(t1Success.get() + t2Success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        String owner = repository.findMembership(RACE, "b").orElseThrow().teamId();
        if (t1Success.get() == 1) {
            assertThat(owner).isEqualTo("t1");
            assertThat(repository.findTeam(RACE, "t2").orElseThrow().status())
                    .isEqualTo(TeamStatus.OPEN);
        } else {
            assertThat(owner).isEqualTo("t2");
            assertThat(repository.findTeam(RACE, "t1").orElseThrow().status())
                    .isEqualTo(TeamStatus.OPEN);
        }
    }
}
