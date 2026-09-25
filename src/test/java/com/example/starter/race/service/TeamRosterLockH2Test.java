package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AddTeamMemberRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRostersRequest;
import com.example.starter.race.api.LockRostersResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RemoveTeamMemberRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.RunnerTeamResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamSnapshotResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.UnlockRosterRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamLockStatus;
import com.example.starter.race.persistence.RosterLockRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 队伍名单锁定的 H2 数据库测试：成员唯一性、批量锁定、解锁版本、
 * 同版本团队得分重算、封榜固化与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterLockH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team";
    private static final long FIXED_NOW = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    private int requestSeq = 0;

    private String nextRequestId() {
        return "req-" + (++requestSeq);
    }

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, nextRequestId()));
    }

    private void register(String bib, Long finishTimeMs, int expectedVersion) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, nextRequestId()));
    }

    private void createTeam(String teamId, String captainBib, int expectedVersion) {
        raceService.createTeam(RACE,
                new CreateTeamRequest(teamId, captainBib, expectedVersion, nextRequestId()));
    }

    private LockRostersRequest.TeamLockRequest lockOf(
            String teamId, String captainBib, String... members) {
        return new LockRostersRequest.TeamLockRequest(teamId, captainBib, List.of(members));
    }

    private int raceVersion() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    @Test
    void 创建队伍_队长自动成为首位成员() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        createTeam("t1", "a", 3);

        TeamResponse team = (TeamResponse) raceService.createTeam(
                RACE, new CreateTeamRequest("t2", "b", 4, nextRequestId())).body();
        assertThat(team.status()).isEqualTo(TeamLockStatus.UNLOCKED);
        assertThat(team.rosterVersion()).isEqualTo(0);
        assertThat(team.raceVersion()).isEqualTo(5);

        RosterResponse roster = raceService.getRoster(RACE, "t2");
        assertThat(roster.members()).containsExactly("b");
        assertThat(roster.raceVersion()).isEqualTo(5);

        // 重复队伍ID 409；未报名选手不能担任队长 404
        assertThatThrownBy(() -> raceService.createTeam(
                RACE, new CreateTeamRequest("t2", "b", 5, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.createTeam(
                RACE, new CreateTeamRequest("t3", "ghost", 5, nextRequestId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 成员唯一性_同一参赛者在同一赛事最多属于一支队伍() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        createTeam("t1", "a", 4);
        createTeam("t2", "b", 5);

        // a 已在 t1，加入 t2 冲突
        assertThatThrownBy(() -> raceService.addTeamMember(
                RACE, "t2", new AddTeamMemberRequest("a", 6, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        // 已入队选手不能再作为其他队伍队长
        assertThatThrownBy(() -> raceService.createTeam(
                RACE, new CreateTeamRequest("t3", "a", 6, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        // 未报名选手不能入队
        assertThatThrownBy(() -> raceService.addTeamMember(
                RACE, "t2", new AddTeamMemberRequest("ghost", 6, nextRequestId())))
                .isInstanceOf(NotFoundException.class);

        // 正常新增与移除
        RosterResponse afterAdd = (RosterResponse) raceService.addTeamMember(
                RACE, "t2", new AddTeamMemberRequest("c", 6, nextRequestId())).body();
        assertThat(afterAdd.members()).containsExactly("b", "c");
        // 重复加入同队同样冲突
        assertThatThrownBy(() -> raceService.addTeamMember(
                RACE, "t2", new AddTeamMemberRequest("c", 7, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        // 队长不可移除
        assertThatThrownBy(() -> raceService.removeTeamMember(
                RACE, "t2", "b", new RemoveTeamMemberRequest(7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        RosterResponse afterRemove = (RosterResponse) raceService.removeTeamMember(
                RACE, "t2", "c", new RemoveTeamMemberRequest(7, nextRequestId())).body();
        assertThat(afterRemove.members()).containsExactly("b");
        // 移除不存在的成员
        assertThatThrownBy(() -> raceService.removeTeamMember(
                RACE, "t2", "c", new RemoveTeamMemberRequest(8, nextRequestId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 批量锁定成功_一事务写入全部快照且赛事版本只推进一次() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        register("d", null, 4);
        createTeam("t1", "a", 5);
        createTeam("t2", "c", 6);

        // 成员乱序提交，验证规范化（排序去重）后固化
        LockRostersResponse response = (LockRostersResponse) raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(
                        lockOf("t2", "c", "d", "c"),
                        lockOf("t1", "a", "b", "a")),
                        7, nextRequestId())).body();

        assertThat(response.version()).isEqualTo(8);
        assertThat(response.locks()).extracting(LockRostersResponse.RosterLockItem::teamId)
                .containsExactly("t1", "t2");
        assertThat(response.locks()).extracting(LockRostersResponse.RosterLockItem::rosterVersion)
                .containsExactly(1, 1);
        assertThat(response.locks().get(0).members()).containsExactly("a", "b");
        assertThat(response.locks().get(1).members()).containsExactly("c", "d");
        assertThat(response.locks().get(0).lockedAt()).isEqualTo(FIXED_NOW);
        assertThat(raceVersion()).isEqualTo(8);

        RosterResponse roster = raceService.getRoster(RACE, "t1");
        assertThat(roster.status()).isEqualTo(TeamLockStatus.LOCKED);
        assertThat(roster.rosterVersion()).isEqualTo(1);
        assertThat(roster.members()).containsExactly("a", "b");
        assertThat(roster.lockRaceVersion()).isEqualTo(8);
        assertThat(roster.lockedAt()).isEqualTo(FIXED_NOW);

        RunnerTeamResponse membership = raceService.getRunnerTeam(RACE, "b");
        assertThat(membership.teamId()).isEqualTo("t1");
        assertThat(membership.rosterVersion()).isEqualTo(1);
        assertThat(membership.locked()).isTrue();

        // 数据库层确认两支队伍的锁定快照均已写入
        assertThat(repository.findActiveLocks(RACE)).hasSize(2);
        assertThat(repository.findLockMembers(RACE, "t2", 1)).containsExactly("c", "d");
    }

    @Test
    void 批量锁定任一校验失败整批422且不写入任何快照() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        createTeam("t1", "a", 4);
        createTeam("t2", "c", 5);
        raceService.addTeamMember(RACE, "t1",
                new AddTeamMemberRequest("b", 6, nextRequestId()));

        // 成员无有效个人报名：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(
                        lockOf("t1", "a", "a", "b"),
                        lockOf("t2", "c", "c", "ghost")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 人数不足 2：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t2", "c", "c")), 7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 批内成员跨队：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(
                        lockOf("t1", "a", "a", "b"),
                        lockOf("t2", "c", "c", "b")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 成员已属于其他队伍：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t2", "c", "c", "b")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 队长与登记不一致：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "c", "a", "b")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 队长不在名单中：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t2", "c", "a", "b")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 名单内成员重复：整批 422
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "a")),
                        7, nextRequestId())))
                .isInstanceOf(UnprocessableEntityException.class);

        // 全部失败：赛事版本未推进、无任何锁定快照、队伍名单保持原样
        assertThat(raceVersion()).isEqualTo(7);
        assertThat(repository.findActiveLocks(RACE)).isEmpty();
        assertThat(raceService.getRoster(RACE, "t1").members()).containsExactly("a", "b");
        assertThat(raceService.getRoster(RACE, "t2").members()).containsExactly("c");
    }

    @Test
    void 锁定后禁止普通增删成员且不可重复锁定() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        createTeam("t1", "a", 4);
        raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b")), 5, nextRequestId()));

        assertThatThrownBy(() -> raceService.addTeamMember(
                RACE, "t1", new AddTeamMemberRequest("c", 6, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.removeTeamMember(
                RACE, "t1", "b", new RemoveTeamMemberRequest(6, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b")), 6, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThat(raceVersion()).isEqualTo(6);
    }

    @Test
    void 解锁保留旧快照_重锁生成新版本() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        createTeam("t1", "a", 4);
        raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b")), 5, nextRequestId()));

        RosterResponse unlocked = (RosterResponse) raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("裁判更正名单", 6, nextRequestId())).body();
        assertThat(unlocked.status()).isEqualTo(TeamLockStatus.UNLOCKED);
        assertThat(unlocked.rosterVersion()).isEqualTo(1);
        assertThat(unlocked.unlockReason()).isEqualTo("裁判更正名单");
        assertThat(unlocked.unlockedAt()).isEqualTo(FIXED_NOW);

        // 旧锁定快照保留，成员与原因可查
        RosterLockRow oldLock = repository.findLatestLock(RACE, "t1").orElseThrow();
        assertThat(oldLock.status()).isEqualTo(TeamLockStatus.UNLOCKED);
        assertThat(oldLock.unlockReason()).isEqualTo("裁判更正名单");
        assertThat(repository.findLockMembers(RACE, "t1", 1)).containsExactly("a", "b");

        // 解锁后可维护名单
        raceService.addTeamMember(RACE, "t1", new AddTeamMemberRequest("c", 7, nextRequestId()));
        // 重锁生成新版本
        LockRostersResponse relock = (LockRostersResponse) raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b", "c")),
                        8, nextRequestId())).body();
        assertThat(relock.locks().getFirst().rosterVersion()).isEqualTo(2);
        RosterResponse relocked = raceService.getRoster(RACE, "t1");
        assertThat(relocked.status()).isEqualTo(TeamLockStatus.LOCKED);
        assertThat(relocked.rosterVersion()).isEqualTo(2);
        assertThat(relocked.members()).containsExactly("a", "b", "c");
        // 旧版本快照仍可查
        assertThat(repository.findLockMembers(RACE, "t1", 1)).containsExactly("a", "b");
        assertThat(repository.findLockMembers(RACE, "t1", 2)).containsExactly("a", "b", "c");

        // 未锁定时解锁冲突
        raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("再次解锁", 9, nextRequestId()));
        assertThatThrownBy(() -> raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("重复解锁", 10, nextRequestId())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 个人成绩与处罚变更后团队得分按锁定名单与同一赛事版本重算() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        register("d", 4000L, 4);
        createTeam("t1", "a", 5);
        createTeam("t2", "c", 6);
        raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(
                        lockOf("t1", "a", "a", "b"),
                        lockOf("t2", "c", "c", "d")),
                        7, nextRequestId()));

        TeamStandingsResponse initial = raceService.getTeamStandings(RACE);
        assertThat(initial.version()).isEqualTo(8);
        assertThat(initial.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(initial.teams()).extracting(TeamStandingsResponse.TeamStandingEntry::teamId)
                .containsExactly("t1", "t2");
        assertThat(initial.teams()).extracting(TeamStandingsResponse.TeamStandingEntry::teamScoreMs)
                .containsExactly(3000L, 7000L);
        assertThat(initial.teams()).extracting(TeamStandingsResponse.TeamStandingEntry::teamRank)
                .containsExactly(1, 2);

        // 处罚变更后：得分以锁定名单与新赛事版本重算
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "b", "ADD_TIME", 500L, 8, nextRequestId()));
        TeamStandingsResponse afterPenalty = raceService.getTeamStandings(RACE);
        assertThat(afterPenalty.version()).isEqualTo(9);
        assertThat(afterPenalty.teams().getFirst().teamScoreMs()).isEqualTo(3500L);

        // 个人成绩登记（计时修订）后同样重算
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 1500L, 9, nextRequestId()));
        TeamStandingsResponse afterRevise = raceService.getTeamStandings(RACE);
        assertThat(afterRevise.version()).isEqualTo(10);
        assertThat(afterRevise.teams().getFirst().teamScoreMs()).isEqualTo(4000L);

        // 成员被取消资格：队伍不完整，得分与名次为空
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-2", "b", "DISQUALIFY", null, 10, nextRequestId()));
        TeamStandingsResponse incomplete = raceService.getTeamStandings(RACE);
        assertThat(incomplete.teams()).extracting(TeamStandingsResponse.TeamStandingEntry::teamId)
                .containsExactly("t2", "t1");
        TeamStandingsResponse.TeamStandingEntry t1 = incomplete.teams().get(1);
        assertThat(t1.complete()).isFalse();
        assertThat(t1.teamScoreMs()).isNull();
        assertThat(t1.teamRank()).isNull();

        // 撤销取消资格后恢复
        raceService.revokePenalty(RACE, "p-2", new RevokePenaltyRequest(11, nextRequestId()));
        TeamStandingsResponse restored = raceService.getTeamStandings(RACE);
        assertThat(restored.teams().getFirst().teamId()).isEqualTo("t1");
        assertThat(restored.teams().getFirst().teamScoreMs()).isEqualTo(4000L);
    }

    @Test
    void 封榜固化名单版本成绩版本与团队得分_封榜后禁止解锁与改名单() {
        createRace();
        register("a", 1000L, 1);
        register("b", 2000L, 2);
        register("c", 3000L, 3);
        register("d", 4000L, 4);
        createTeam("t1", "a", 5);
        createTeam("t2", "c", 6);
        raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(
                        lockOf("t1", "a", "a", "b"),
                        lockOf("t2", "c", "c", "d")),
                        7, nextRequestId()));
        raceService.sealRace(RACE, new SealRaceRequest(8, nextRequestId()));

        TeamSnapshotResponse snapshot = raceService.getTeamSnapshot(RACE);
        assertThat(snapshot.resultVersion()).isEqualTo(9);
        assertThat(snapshot.sealedAt()).isEqualTo(FIXED_NOW);
        assertThat(snapshot.teams()).extracting(TeamSnapshotResponse.TeamSnapshotEntry::teamId)
                .containsExactly("t1", "t2");
        TeamSnapshotResponse.TeamSnapshotEntry t1 = snapshot.teams().getFirst();
        assertThat(t1.rosterVersion()).isEqualTo(1);
        assertThat(t1.members()).containsExactly("a", "b");
        assertThat(t1.complete()).isTrue();
        assertThat(t1.teamScoreMs()).isEqualTo(3000L);
        assertThat(t1.teamRank()).isEqualTo(1);

        // 封榜后团队得分查询返回固化内容
        TeamStandingsResponse standings = raceService.getTeamStandings(RACE);
        assertThat(standings.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standings.version()).isEqualTo(9);
        assertThat(standings.teams().getFirst().teamScoreMs()).isEqualTo(3000L);

        // 封榜后：解锁、改名单、新增成员、重新锁定均 409
        assertThatThrownBy(() -> raceService.unlockRoster(RACE, "t1",
                new UnlockRosterRequest("封榜后解锁", 9, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b")),
                        9, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.addTeamMember(
                RACE, "t1", new AddTeamMemberRequest("c", 9, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.removeTeamMember(
                RACE, "t1", "b", new RemoveTeamMemberRequest(9, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.createTeam(
                RACE, new CreateTeamRequest("t3", "c", 9, nextRequestId())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 幂等_同键同参重放首次结果_异参409_失败不占键() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        register("d", null, 4);
        createTeam("t1", "a", 5);

        // 失败不占键：先以 req-lock 提交非法名单（422），版本不推进
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "ghost")),
                        6, "req-lock")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(raceVersion()).isEqualTo(6);

        // 同键修正参数后成功
        LockRostersRequest lockRequest = new LockRostersRequest(
                List.of(lockOf("t1", "a", "a", "b")), 6, "req-lock");
        LockRostersResponse first = (LockRostersResponse) raceService
                .lockRosters(RACE, lockRequest).body();
        assertThat(first.locks().getFirst().rosterVersion()).isEqualTo(1);
        int versionAfterLock = raceVersion();

        // 同键同参重放：返回首次结果（重放体为 JsonNode），版本不再推进
        Object replayedBody = raceService.lockRosters(RACE, lockRequest).body();
        assertThat(replayedBody).isInstanceOf(com.fasterxml.jackson.databind.JsonNode.class);
        com.fasterxml.jackson.databind.JsonNode replayed =
                (com.fasterxml.jackson.databind.JsonNode) replayedBody;
        assertThat(replayed.get("version").asInt()).isEqualTo(first.version());
        assertThat(replayed.get("locks").get(0).get("rosterVersion").asInt()).isEqualTo(1);
        assertThat(raceVersion()).isEqualTo(versionAfterLock);

        // 同键异参：409
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "c")),
                        6, "req-lock")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 版本冲突与未知对象返回对应错误() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        createTeam("t1", "a", 3);

        // 版本不匹配 409
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t1", "a", "a", "b")),
                        1, nextRequestId())))
                .isInstanceOf(ConflictException.class);
        // 未知队伍 404
        assertThatThrownBy(() -> raceService.lockRosters(RACE,
                new LockRostersRequest(List.of(lockOf("t9", "a", "a", "b")),
                        4, nextRequestId())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRoster(RACE, "t9"))
                .isInstanceOf(NotFoundException.class);
        // 未知选手归属查询 404；未入队选手归属为 null
        assertThatThrownBy(() -> raceService.getRunnerTeam(RACE, "ghost"))
                .isInstanceOf(NotFoundException.class);
        RunnerTeamResponse noTeam = raceService.getRunnerTeam(RACE, "b");
        assertThat(noTeam.teamId()).isNull();
        assertThat(noTeam.locked()).isNull();
        // 未封榜查询队伍快照 404
        assertThatThrownBy(() -> raceService.getTeamSnapshot(RACE))
                .isInstanceOf(NotFoundException.class);
    }
}
