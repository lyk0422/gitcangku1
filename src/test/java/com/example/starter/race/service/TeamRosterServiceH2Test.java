package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AddTeamMemberRequest;
import com.example.starter.race.api.BatchLockRosterRequest;
import com.example.starter.race.api.BatchLockRosterResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RemoveTeamMemberRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RosterLockResponse;
import com.example.starter.race.api.RunnerTeamResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.UnlockRosterRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 队伍名单锁定与团队成绩的 H2 数据库测试：主流程、校验分支、
 * 批量锁定原子性、解锁版本、同版本得分重算与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team";
    private static final long FIXED_MILLIS = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void register(String bib, Long finishMs, int expectedVersion) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishMs, expectedVersion, "req-reg-" + bib));
    }

    private void createTeam(String teamId, String captainBib, int expectedVersion) {
        raceService.createTeam(RACE,
                new CreateTeamRequest(teamId, captainBib, expectedVersion, "req-team-" + teamId));
    }

    @Test
    void 完整流程_锁定重算解锁重锁到封榜快照() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        register("d", 4000L, 4);
        createTeam("t1", "a", 5);
        raceService.addTeamMember(RACE, "t1", new AddTeamMemberRequest("a", 6, "req-m-a"));
        raceService.addTeamMember(RACE, "t1", new AddTeamMemberRequest("b", 7, "req-m-b"));

        // v9: 队长锁定名单（成员顺序乱序，响应为规范化排序）
        RosterLockResponse lock = (RosterLockResponse) raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("b", "a"), 8)).body();
        assertThat(lock.rosterVersion()).isEqualTo(1);
        assertThat(lock.raceVersion()).isEqualTo(9);
        assertThat(lock.members()).containsExactly("a", "b");
        assertThat(lock.lockedAt()).isEqualTo(FIXED_MILLIS);
        assertThat(lock.rosterKey()).startsWith("roster-lock:");

        // 锁定后即时重算：a=1000 + b=2000
        TeamStandingsResponse standings = raceService.getTeamStandings(RACE);
        assertThat(standings.version()).isEqualTo(9);
        assertThat(standings.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(standings.teams()).hasSize(1);
        assertThat(standings.teams().getFirst().rosterVersion()).isEqualTo(1);
        assertThat(standings.teams().getFirst().raceVersion()).isEqualTo(9);
        assertThat(standings.teams().getFirst().memberCount()).isEqualTo(2);
        assertThat(standings.teams().getFirst().rankedCount()).isEqualTo(2);
        assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(3000L);

        // v10: b 加时500 -> 团队得分按同一赛事版本重算为 1000+2500
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "b", "ADD_TIME", 500L, 9, "req-pen-b"));
        standings = raceService.getTeamStandings(RACE);
        assertThat(standings.teams().getFirst().raceVersion()).isEqualTo(10);
        assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(3500L);

        // v11: a 取消资格 -> 存在未排名成员，团队得分不完整（null）
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-2", "a", "DISQUALIFY", null, 10, "req-dq-a"));
        standings = raceService.getTeamStandings(RACE);
        assertThat(standings.teams().getFirst().rankedCount()).isEqualTo(1);
        assertThat(standings.teams().getFirst().totalTimeMs()).isNull();

        // v12: 撤销取消资格 -> 恢复 3500
        raceService.revokePenalty(RACE, "p-2", new RevokePenaltyRequest(11, "req-revoke"));
        standings = raceService.getTeamStandings(RACE);
        assertThat(standings.teams().getFirst().raceVersion()).isEqualTo(12);
        assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(3500L);

        // 个人归属查询
        RunnerTeamResponse membership = raceService.getRunnerTeam(RACE, "b");
        assertThat(membership.teamId()).isEqualTo("t1");
        assertThat(membership.teamStatus()).isEqualTo(TeamStatus.LOCKED);
        assertThat(membership.rosterVersion()).isEqualTo(1);
        RunnerTeamResponse noTeam = raceService.getRunnerTeam(RACE, "d");
        assertThat(noTeam.teamId()).isNull();
        assertThat(noTeam.teamStatus()).isNull();

        // v13: 裁判解锁，旧快照保留且记录原因
        TeamResponse unlocked = (TeamResponse) raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("裁判更正名单", 12, "req-unlock")).body();
        assertThat(unlocked.status()).isEqualTo(TeamStatus.OPEN);
        assertThat(unlocked.rosterVersion()).isEqualTo(1);
        assertThat(raceService.getTeamStandings(RACE).teams()).isEmpty();
        TeamRosterResponse roster = raceService.getTeamRoster(RACE, "t1");
        assertThat(roster.locks()).hasSize(1);
        assertThat(roster.locks().getFirst().unlocked()).isTrue();
        assertThat(roster.locks().getFirst().unlockReason()).isEqualTo("裁判更正名单");
        assertThat(roster.locks().getFirst().unlockedAt()).isEqualTo(FIXED_MILLIS);
        assertThat(roster.locks().getFirst().members()).containsExactly("a", "b");

        // v14: 重锁生成新名单版本
        RosterLockResponse relock = (RosterLockResponse) raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "b", "c"), 13)).body();
        assertThat(relock.rosterVersion()).isEqualTo(2);
        assertThat(relock.raceVersion()).isEqualTo(14);
        roster = raceService.getTeamRoster(RACE, "t1");
        assertThat(roster.status()).isEqualTo(TeamStatus.LOCKED);
        assertThat(roster.rosterVersion()).isEqualTo(2);
        assertThat(roster.locks()).hasSize(2);
        assertThat(roster.locks().get(0).unlocked()).isTrue();
        assertThat(roster.locks().get(1).unlocked()).isFalse();
        assertThat(roster.locks().get(1).members()).containsExactly("a", "b", "c");

        // 团队得分：1000 + 2500 + 3000
        standings = raceService.getTeamStandings(RACE);
        assertThat(standings.teams().getFirst().rosterVersion()).isEqualTo(2);
        assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(6500L);

        // v15: 封榜，团队快照固化名单版本、个人成绩版本与团队得分
        raceService.sealRace(RACE, new SealRaceRequest(14, "req-seal"));
        standings = raceService.getTeamStandings(RACE);
        assertThat(standings.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standings.version()).isEqualTo(15);
        assertThat(standings.teams()).hasSize(1);
        assertThat(standings.teams().getFirst().rosterVersion()).isEqualTo(2);
        assertThat(standings.teams().getFirst().raceVersion()).isEqualTo(15);
        assertThat(standings.teams().getFirst().totalTimeMs()).isEqualTo(6500L);

        // 封榜后禁止解锁、改名单、新增成员与再锁定，均409
        assertThatThrownBy(() -> raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("x", 15, "req-unlock-2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.addTeamMember(RACE, "t1",
                new AddTeamMemberRequest("d", 15, "req-m-d")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "b"), 15)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.removeTeamMember(RACE, "t1", "a",
                new RemoveTeamMemberRequest(15, "req-rm-a")))
                .isInstanceOf(ConflictException.class);

        // 封榜后名单与归属仍可查询
        roster = raceService.getTeamRoster(RACE, "t1");
        assertThat(roster.locks()).hasSize(2);
        assertThat(raceService.getRunnerTeam(RACE, "c").teamId()).isEqualTo("t1");
    }

    @Test
    void 成员唯一性_同一赛事一名参赛者只能属于一支队伍() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        createTeam("t1", "a", 4);
        createTeam("t2", "b", 5);

        raceService.addTeamMember(RACE, "t1", new AddTeamMemberRequest("a", 6, "req-m-a"));
        // 同一参赛者加入第二支队伍 -> 409
        assertThatThrownBy(() -> raceService.addTeamMember(RACE, "t2",
                new AddTeamMemberRequest("a", 7, "req-m-a2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("参赛者已加入队伍");

        // t1 锁定 [a, c] 后，t2 锁定包含 a 的名单 -> 422
        raceService.lockRoster(RACE, "t1", new LockRosterRequest("a", List.of("a", "c"), 7));
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t2",
                new LockRosterRequest("b", List.of("a", "b"), 8)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("成员已属于其他队伍");
    }

    @Test
    void 锁定校验_人数报名队长与锁定后增删分支() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        createTeam("t1", "a", 3);

        // 人数不足2 -> 422
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a"), 4)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("2~8");
        // 人数超过8 -> 422
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a",
                        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), 4)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("2~8");
        // 成员无有效个人报名 -> 422
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "ghost"), 4)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("无有效个人报名");
        // 非队长提交 -> 422
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("b", List.of("a", "b"), 4)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("仅队长");
        // 版本冲突 -> 409
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "b"), 99)))
                .isInstanceOf(ConflictException.class);

        raceService.lockRoster(RACE, "t1", new LockRosterRequest("a", List.of("a", "b"), 4));
        // 锁定后普通增删成员 -> 409
        assertThatThrownBy(() -> raceService.addTeamMember(RACE, "t1",
                new AddTeamMemberRequest("a", 5, "req-m-dup")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("禁止增删成员");
        assertThatThrownBy(() -> raceService.removeTeamMember(RACE, "t1", "a",
                new RemoveTeamMemberRequest(5, "req-rm")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("禁止增删成员");
        // 重复锁定 -> 409
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "b"), 5)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已锁定");
        // 未锁定时解锁 -> 409（先解锁再重复解锁）
        raceService.unlockRoster(RACE, "t1", new UnlockRosterRequest("原因", 5, "req-unlock"));
        assertThatThrownBy(() -> raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("再次", 6, "req-unlock-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未锁定");
    }

    @Test
    void 队伍与成员基础分支_重复队伍未报名队长缺失成员() {
        createRace();
        register("a", 1000L, 1);
        createTeam("t1", "a", 2);
        // 重复队伍 -> 409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("t1", "a", 3, "req-team-t1-dup")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("队伍已存在");
        // 队长未报名 -> 404
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("t2", "ghost", 3, "req-team-t2")))
                .isInstanceOf(NotFoundException.class);
        // 移除不存在的成员 -> 404
        assertThatThrownBy(() -> raceService.removeTeamMember(RACE, "t1", "ghost",
                new RemoveTeamMemberRequest(3, "req-rm-ghost")))
                .isInstanceOf(NotFoundException.class);
        // 队伍不存在 -> 404
        assertThatThrownBy(() -> raceService.getTeamRoster(RACE, "t-x"))
                .isInstanceOf(NotFoundException.class);
        // 移除成员主流程
        raceService.addTeamMember(RACE, "t1", new AddTeamMemberRequest("a", 3, "req-m-a"));
        TeamResponse afterRemove = (TeamResponse) raceService.removeTeamMember(
                RACE, "t1", "a", new RemoveTeamMemberRequest(4, "req-rm-a")).body();
        assertThat(afterRemove.members()).isEmpty();
        assertThat(afterRemove.raceVersion()).isEqualTo(5);
    }

    @Test
    void 批量锁定_整批校验一事务写入与团队得分() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        register("d", 4000L, 4);
        createTeam("t1", "a", 5);
        createTeam("t2", "c", 6);

        BatchLockRosterResponse batch = (BatchLockRosterResponse) raceService.batchLockRosters(
                RACE, new BatchLockRosterRequest(7, "req-batch", List.of(
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("b", "a")),
                        new BatchLockRosterRequest.TeamLockEntry("t2", "c", List.of("c", "d")))))
                .body();
        assertThat(batch.raceVersion()).isEqualTo(8);
        assertThat(batch.locks()).hasSize(2);
        assertThat(batch.locks()).extracting(RosterLockResponse::rosterVersion)
                .containsExactly(1, 1);
        assertThat(batch.locks()).extracting(RosterLockResponse::raceVersion)
                .containsExactly(8, 8);
        assertThat(batch.locks().get(0).members()).containsExactly("a", "b");

        // 两支队伍得分在同一赛事版本重算
        TeamStandingsResponse standings = raceService.getTeamStandings(RACE);
        assertThat(standings.teams()).hasSize(2);
        assertThat(standings.teams()).extracting(TeamStandingResponse::teamId)
                .containsExactly("t1", "t2");
        assertThat(standings.teams().get(0).totalTimeMs()).isEqualTo(3000L);
        assertThat(standings.teams().get(1).totalTimeMs()).isEqualTo(7000L);
        assertThat(standings.teams()).extracting(TeamStandingResponse::raceVersion)
                .containsExactly(8, 8);

        // 幂等重放：同 requestId 同参返回首次结果（重放体为JsonNode，转换后断言），不新增锁定版本
        ServiceResult replayResult = raceService.batchLockRosters(
                RACE, new BatchLockRosterRequest(7, "req-batch", List.of(
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("b", "a")),
                        new BatchLockRosterRequest.TeamLockEntry("t2", "c", List.of("c", "d")))));
        BatchLockRosterResponse replay = objectMapper.convertValue(
                replayResult.body(), BatchLockRosterResponse.class);
        assertThat(replay.locks()).extracting(RosterLockResponse::rosterVersion)
                .containsExactly(1, 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        assertThat(repository.findRosterLocks(RACE, "t1")).hasSize(1);
    }

    @Test
    void 批量锁定_任一失败整批422且不写入() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        createTeam("t1", "a", 4);
        createTeam("t2", "c", 5);

        // 成员跨队 -> 整批422
        assertThatThrownBy(() -> raceService.batchLockRosters(RACE,
                new BatchLockRosterRequest(6, "req-batch-cross", List.of(
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("a", "b")),
                        new BatchLockRosterRequest.TeamLockEntry("t2", "c", List.of("b", "c"))))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("跨队");
        // 含未报名成员 -> 整批422
        assertThatThrownBy(() -> raceService.batchLockRosters(RACE,
                new BatchLockRosterRequest(6, "req-batch-ghost", List.of(
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("a", "b")),
                        new BatchLockRosterRequest.TeamLockEntry("t2", "c", List.of("c", "ghost"))))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("无有效个人报名");
        // 批量内队伍重复 -> 整批422
        assertThatThrownBy(() -> raceService.batchLockRosters(RACE,
                new BatchLockRosterRequest(6, "req-batch-dup", List.of(
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("a", "b")),
                        new BatchLockRosterRequest.TeamLockEntry("t1", "a", List.of("a", "c"))))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("队伍重复");

        // 整批未写入：版本不变、无锁定快照、队伍仍可编辑
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findRosterLocks(RACE, "t1")).isEmpty();
        assertThat(repository.findRosterLocks(RACE, "t2")).isEmpty();
        assertThat(repository.findTeam(RACE, "t1").orElseThrow().status())
                .isEqualTo(TeamStatus.OPEN);
        assertThat(repository.findTeam(RACE, "t2").orElseThrow().status())
                .isEqualTo(TeamStatus.OPEN);
        assertThat(raceService.getTeamStandings(RACE).teams()).isEmpty();
    }

    @Test
    void 锁定幂等_同键同参重放首次结果且失败不占键() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        createTeam("t1", "a", 3);

        RosterLockResponse first = (RosterLockResponse) raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("a", "b"), 4)).body();
        // 同键同参重放：返回首次结果（重放体为JsonNode，转换后断言），版本与快照不重复推进
        ServiceResult replayResult = raceService.lockRoster(RACE, "t1",
                new LockRosterRequest("a", List.of("b", "a"), 4));
        RosterLockResponse replayed = objectMapper.convertValue(
                replayResult.body(), RosterLockResponse.class);
        assertThat(replayed.rosterKey()).isEqualTo(first.rosterKey());
        assertThat(replayed.rosterVersion()).isEqualTo(1);
        assertThat(replayed.raceVersion()).isEqualTo(5);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findRosterLocks(RACE, "t1")).hasSize(1);

        // 失败不占键：对未锁定的 t2 提交非法名单 -> 422，幂等记录数不变，同参重试仍为422而非409
        createTeam("t2", "b", 5);
        long idempotencyBefore = countIdempotencyRecords();
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t2",
                new LockRosterRequest("b", List.of("b"), 6)))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(countIdempotencyRecords()).isEqualTo(idempotencyBefore);
        assertThatThrownBy(() -> raceService.lockRoster(RACE, "t2",
                new LockRosterRequest("b", List.of("b"), 6)))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    private long countIdempotencyRecords() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class);
        return count == null ? 0 : count;
    }
}
