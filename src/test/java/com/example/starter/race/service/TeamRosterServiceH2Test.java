package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RosterMemberResponse;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TeamMemberScoreResponse;
import com.example.starter.race.api.TeamScoreResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.WithdrawMemberRequest;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RosterLockRow;
import com.example.starter.race.persistence.SnapshotTeamMemberRow;
import com.example.starter.race.persistence.SnapshotTeamRow;
import com.example.starter.race.persistence.WithdrawalRow;
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
 * 团队名单锁定的 H2 数据库测试：主流程、拒绝分支、成绩固化、退赛边界、
 * 封榜只读性与 requestId 幂等。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-E";

    @Autowired
    private RaceService raceService;

    @Autowired
    private TeamRosterService teamRosterService;

    /** 建赛并登记选手，返回登记完最后一名选手后的赛事版本。 */
    private int seedRace(String raceId, String... bibsWithTimes) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        int version = 1;
        for (int i = 0; i < bibsWithTimes.length; i += 2) {
            String bib = bibsWithTimes[i];
            Long time = Long.valueOf(bibsWithTimes[i + 1]);
            raceService.registerRunner(raceId,
                    new RegisterRunnerRequest(bib, time, version, "req-reg-" + bib + "-" + raceId));
            version++;
        }
        return version;
    }

    private LockRosterRequest lock(List<String> members, int expectedVersion, String requestId) {
        return new LockRosterRequest(members, expectedVersion, requestId);
    }

    @Test
    void 锁定时固化个人成绩且后续计时修订不改变团队汇总() {
        int version = seedRace(RACE, "jia", "1000", "yi", "2000", "bing", "4000");
        assertThat(version).isEqualTo(4);

        ServiceResult result = teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia", "yi"), 4, "req-lock-a1"));
        assertThat(result.status()).isEqualTo(201);
        RosterResponse roster = (RosterResponse) result.body();
        assertThat(roster.rosterVersion()).isEqualTo(1);
        assertThat(roster.current()).isTrue();
        assertThat(roster.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("jia", "yi");
        assertThat(roster.members()).extracting(RosterMemberResponse::totalTimeMs)
                .containsExactly(1000L, 2000L);

        // 锁定后修订甲的成绩为8000：版本内固化成绩仍是1000。
        raceService.reviseTime(RACE, new ReviseTimeRequest("jia", 8000L, 5, "req-revise-jia"));

        TeamStandingResponse standing = teamRosterService.getTeamResults(RACE, 1);
        assertThat(standing.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(standing.teams()).hasSize(1);
        TeamScoreResponse teamA = standing.teams().getFirst();
        assertThat(teamA.teamId()).isEqualTo("A");
        assertThat(teamA.rosterVersion()).isEqualTo(1);
        // 团队总分按锁定版本固化成绩汇总：1000+2000=3000，而非 8000+2000。
        assertThat(teamA.totalTimeMs()).isEqualTo(3000L);
        assertThat(teamA.scoringCount()).isEqualTo(2);
        assertThat(teamA.members()).extracting(TeamMemberScoreResponse::totalTimeMs)
                .containsExactly(1000L, 2000L);

        // 当前名单与历史版本查询都显示锁定时刻成绩快照。
        RosterResponse current = teamRosterService.getCurrentRoster(RACE, "A");
        assertThat(current.members()).extracting(RosterMemberResponse::finishTimeMs)
                .containsExactly(1000L, 2000L);
        RosterResponse version1 = teamRosterService.getRosterVersion(RACE, "A", 1);
        assertThat(version1.current()).isTrue();
        assertThat(version1.withdrawals()).isEmpty();
        assertThat(version1.members()).extracting(RosterMemberResponse::finishTimeMs)
                .containsExactly(1000L, 2000L);
    }

    @Test
    void 跨队锁定与重复入队及锁定后替换成员均返回409() {
        seedRace(RACE, "jia", "1000", "yi", "2000", "bing", "3000");

        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia"), 4, "req-a1"));
        // 甲已属队A，队B再锁入甲返回409。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "B",
                lock(List.of("jia"), 5, "req-b-jia")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已属于团队");
        // 队B未被创建，不存在半份名单。
        assertThat(rosterRepository.findTeam(RACE, "B")).isEmpty();
        assertThat(rosterRepository.findRosterLocks(RACE, "B")).isEmpty();

        // 队B锁入乙成功。
        teamRosterService.lockRoster(RACE, "B", lock(List.of("yi"), 5, "req-b1"));

        // 队A版本2试图去掉甲（替换为丙）：409，版本仍是1、赛事版本不变。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("bing"), 6, "req-a2-replace")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不允许替换或移除成员");
        assertThat(rosterRepository.findLatestRosterLock(RACE, "A").orElseThrow().rosterVersion())
                .isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);

        // 同一份名单内成员重复：409。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia", "jia"), 6, "req-a2-dup")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重复出现");

        // 版本2锁入已属队B的乙：跨队409。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia", "yi"), 6, "req-a2-cross")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已属于团队");
        // 甲与乙的归属均未被改写。
        assertThat(rosterRepository.findMembership(RACE, "jia").orElseThrow().teamId())
                .isEqualTo("A");
        assertThat(rosterRepository.findMembership(RACE, "yi").orElseThrow().teamId())
                .isEqualTo("B");
    }

    @Test
    void 任一名单项不合法时整份名单不生成且成绩归属不变() {
        seedRace(RACE, "jia", "1000");
        // 丙未登记：404，事务整体回滚，团队与归属都不存在，赛事版本不变。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia", "ghost"), 2, "req-a-bad")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("尚未在赛事中登记");
        assertThat(rosterRepository.findTeam(RACE, "A")).isEmpty();
        assertThat(rosterRepository.findMemberships(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 用正确参数重试同一业务（不同 requestId）成功，甲归属队A。
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia"), 2, "req-a-ok"));
        assertThat(rosterRepository.findMembership(RACE, "jia").orElseThrow().teamId())
                .isEqualTo("A");
    }

    @Test
    void 后续版本只能增员且历史版本不随退赛改写() {
        seedRace(RACE, "jia", "1000", "yi", "2000", "bing", "3000");
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 4, "req-a1"));
        // 版本2：在保留甲乙的基础上增加丙。
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi", "bing"), 5, "req-a2"));

        RosterResponse current = teamRosterService.getCurrentRoster(RACE, "A");
        assertThat(current.rosterVersion()).isEqualTo(2);
        // 成员按参赛号字典序返回：bing、jia、yi。
        assertThat(current.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("bing", "jia", "yi");
        // 丙首次进队版本为2，甲乙为1。
        assertThat(current.members()).extracting(RosterMemberResponse::rosterVersion)
                .containsExactly(2, 1, 1);

        // 甲退赛：记录生效场次与原因，锁定行不删除。
        ServiceResult withdrawal = teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(3, "伤病退赛", "req-wd-jia"));
        assertThat(withdrawal.status()).isEqualTo(201);
        assertThat(((WithdrawalResponse) withdrawal.body()).effectiveSession()).isEqualTo(3);

        // 当前名单反映退赛状态。
        RosterResponse afterWithdraw = teamRosterService.getCurrentRoster(RACE, "A");
        RosterMemberResponse jia = afterWithdraw.members().stream()
                .filter(member -> member.bib().equals("jia")).findFirst().orElseThrow();
        assertThat(jia.withdrawn()).isTrue();
        assertThat(jia.effectiveSession()).isEqualTo(3);
        assertThat(jia.reason()).isEqualTo("伤病退赛");
        assertThat(afterWithdraw.withdrawals()).hasSize(1);

        // 历史版本1与版本2原样返回：成员仍在、不标退赛、成绩快照不变。
        RosterResponse v1 = teamRosterService.getRosterVersion(RACE, "A", 1);
        assertThat(v1.current()).isFalse();
        assertThat(v1.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("jia", "yi");
        assertThat(v1.members()).extracting(RosterMemberResponse::withdrawn)
                .containsExactly(false, false);
        assertThat(v1.withdrawals()).isEmpty();
        RosterResponse v2 = teamRosterService.getRosterVersion(RACE, "A", 2);
        assertThat(v2.current()).isTrue();
        assertThat(v2.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("bing", "jia", "yi");
        assertThat(v2.members()).extracting(RosterMemberResponse::withdrawn)
                .containsExactly(false, false, false);

        // 退赛不推进赛事版本。
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 退赛按生效场次只影响后续未封榜计分() {
        seedRace(RACE, "jia", "1000", "yi", "2000");
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 3, "req-a1"));
        teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(3, "退赛", "req-wd"));

        // 场次2：退赛第3场才生效，甲仍计分，总分3000。
        TeamStandingResponse session2 = teamRosterService.getTeamResults(RACE, 2);
        assertThat(session2.teams().getFirst().totalTimeMs()).isEqualTo(3000L);
        assertThat(session2.teams().getFirst().scoringCount()).isEqualTo(2);
        // 场次3及之后：甲不计分，总分2000。
        TeamStandingResponse session3 = teamRosterService.getTeamResults(RACE, 3);
        assertThat(session3.teams().getFirst().totalTimeMs()).isEqualTo(2000L);
        assertThat(session3.teams().getFirst().scoringCount()).isEqualTo(1);
        TeamMemberScoreResponse jia = session3.teams().getFirst().members().getFirst();
        assertThat(jia.bib()).isEqualTo("jia");
        assertThat(jia.scoring()).isFalse();
        assertThat(jia.withdrawn()).isTrue();
        // 不传场次视为全部已登记退赛均生效。
        assertThat(teamRosterService.getTeamResults(RACE, null).teams().getFirst().totalTimeMs())
                .isEqualTo(2000L);

        // 重复退赛409；非本队成员退赛404；未入队成员退赛404。
        assertThatThrownBy(() -> teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(4, "再次退赛", "req-wd-again")))
                .isInstanceOf(ConflictException.class);
        seedRace("race-other", "k", "100");
        assertThatThrownBy(() -> teamRosterService.withdrawMember(RACE, "B", "jia",
                new WithdrawMemberRequest(1, "错队", "req-wd-wrong-team")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 封榜固化版本2团队积分且封榜后退赛不重算() {
        seedRace(RACE, "jia", "1000", "yi", "2000");
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia"), 3, "req-a1"));
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 4, "req-a2"));
        // 封榜时版本2：甲1000+乙2000=3000，两人都计分。
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);

        List<SnapshotTeamRow> snapshotTeams = rosterRepository.findTeamSnapshotRows(RACE);
        assertThat(snapshotTeams).hasSize(1);
        assertThat(snapshotTeams.getFirst().rosterVersion()).isEqualTo(2);
        assertThat(snapshotTeams.getFirst().totalTimeMs()).isEqualTo(3000L);
        assertThat(snapshotTeams.getFirst().scoringCount()).isEqualTo(2);

        // 封榜后甲退赛：允许登记，但已封榜版本2积分不重算。
        ServiceResult withdrawal = teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(1, "封榜后退赛", "req-wd-after"));
        assertThat(withdrawal.status()).isEqualTo(201);

        TeamStandingResponse snapshot = teamRosterService.getTeamSnapshot(RACE);
        TeamScoreResponse teamA = snapshot.teams().getFirst();
        assertThat(teamA.rosterVersion()).isEqualTo(2);
        assertThat(teamA.totalTimeMs()).isEqualTo(3000L);
        assertThat(teamA.scoringCount()).isEqualTo(2);
        assertThat(teamA.members()).extracting(TeamMemberScoreResponse::bib)
                .containsExactly("jia", "yi");
        assertThat(teamA.members()).extracting(TeamMemberScoreResponse::scoring)
                .containsExactly(true, true);
        assertThat(teamA.members()).extracting(TeamMemberScoreResponse::withdrawn)
                .containsExactly(false, false);
        // 封榜后查询团队成绩返回只读快照，忽略场次参数。
        TeamStandingResponse results = teamRosterService.getTeamResults(RACE, 9);
        assertThat(results.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(results.teams().getFirst().totalTimeMs()).isEqualTo(3000L);
    }

    @Test
    void 封榜前已生效退赛计入封榜快照且历史名单不改写() {
        seedRace(RACE, "jia", "1000", "yi", "2000");
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia"), 3, "req-a1"));
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 4, "req-a2"));
        teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(2, "封榜前退赛", "req-wd-before"));
        raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));

        SnapshotTeamRow team = rosterRepository.findTeamSnapshotRows(RACE).getFirst();
        // 封榜时刻已登记退赛：甲不计分，版本2总分只算乙2000。
        assertThat(team.totalTimeMs()).isEqualTo(2000L);
        assertThat(team.scoringCount()).isEqualTo(1);
        List<SnapshotTeamMemberRow> members = rosterRepository.findTeamSnapshotMembers(RACE);
        assertThat(members).hasSize(2);
        assertThat(members).anySatisfy(member -> {
            assertThat(member.bib()).isEqualTo("jia");
            assertThat(member.scoring()).isFalse();
            assertThat(member.withdrawn()).isTrue();
            assertThat(member.effectiveSession()).isEqualTo(2);
        });
        // 历史版本2查询不随退赛改写。
        RosterResponse v2 = teamRosterService.getRosterVersion(RACE, "A", 2);
        assertThat(v2.members()).extracting(RosterMemberResponse::withdrawn)
                .containsExactly(false, false);
    }

    @Test
    void 未封榜查询快照404封榜后锁定409() {
        seedRace(RACE, "jia", "1000");
        assertThatThrownBy(() -> teamRosterService.getTeamSnapshot(RACE))
                .isInstanceOf(NotFoundException.class);

        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia"), 2, "req-a1"));
        raceService.sealRace(RACE, new SealRaceRequest(3, "req-seal"));

        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia"), 4, "req-a-after-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        // 不存在的团队与版本查询为404。
        assertThatThrownBy(() -> teamRosterService.getCurrentRoster(RACE, "X"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> teamRosterService.getRosterVersion(RACE, "A", 9))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 锁定同键重放首次成员与成绩快照换名单返回409() {
        int version = seedRace(RACE, "jia", "1000", "yi", "2000");
        LockRosterRequest first = lock(List.of("jia"), version, "req-lock-same");

        ServiceResult r1 = teamRosterService.lockRoster(RACE, "A", first);
        ServiceResult r2 = teamRosterService.lockRoster(RACE, "A", first);
        assertThat(r2.status()).isEqualTo(r1.status());
        assertThat(rosterRepository.findRosterLocks(RACE, "A")).hasSize(1);
        // 锁定只推进一次赛事版本。
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);

        // 同 requestId 换名单（增员）返回409，版本2不得生成。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia", "yi"), version, "req-lock-same")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(rosterRepository.findLatestRosterLock(RACE, "A").orElseThrow().rosterVersion())
                .isEqualTo(1);

        // 退赛同键异参（原因不同）同样409。
        teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(1, "原因一", "req-wd-same"));
        assertThatThrownBy(() -> teamRosterService.withdrawMember(RACE, "A", "jia",
                new WithdrawMemberRequest(2, "原因二", "req-wd-same")))
                .isInstanceOf(ConflictException.class);
        WithdrawalRow row = rosterRepository.findWithdrawal(RACE, "jia").orElseThrow();
        assertThat(row.effectiveSession()).isEqualTo(1);
        assertThat(row.reason()).isEqualTo("原因一");
    }

    @Test
    void 锁定业务失败不占用requestId() {
        seedRace(RACE, "jia", "1000");
        // 错误版本：业务失败回滚，不占用 requestId。
        assertThatThrownBy(() -> teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia"), 99, "req-lock-retry")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-lock-retry")).isEmpty();
        // 同键以正确版本重试成功。
        ServiceResult retry = teamRosterService.lockRoster(RACE, "A",
                lock(List.of("jia"), 2, "req-lock-retry"));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(repository.findIdempotency("req-lock-retry")).isPresent();
    }

    @Test
    void 多队封榜时榜单各引用一个完整名单版本() {
        seedRace(RACE, "jia", "1000", "yi", "2000", "bing", "500");
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 4, "req-a1"));
        teamRosterService.lockRoster(RACE, "B", lock(List.of("bing"), 5, "req-b1"));
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));

        List<SnapshotTeamRow> teams = rosterRepository.findTeamSnapshotRows(RACE);
        // B=500 在前，A=3000 在后；各队只引用自己的一个完整版本。
        assertThat(teams).extracting(SnapshotTeamRow::teamId).containsExactly("B", "A");
        assertThat(teams).extracting(SnapshotTeamRow::rosterVersion).containsExactly(1, 1);
        assertThat(teams).extracting(SnapshotTeamRow::totalTimeMs).containsExactly(500L, 3000L);

        TeamStandingResponse standing = teamRosterService.getTeamSnapshot(RACE);
        assertThat(standing.teams()).extracting(TeamScoreResponse::teamId)
                .containsExactly("B", "A");
        assertThat(standing.teams().get(0).members()).hasSize(1);
        assertThat(standing.teams().get(1).members()).hasSize(2);
    }

    @Test
    void 未排名成员锁定状态固化且不计团队总分() {
        // 甲有处罚取消资格、乙无计时：锁定时都不是RANKED，团队总分为0，后续撤销处罚不改变版本。
        seedRace(RACE, "jia", "1000");
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("yi", null, 2, "req-reg-yi-extra"));
        raceService.addPenalty(RACE, new com.example.starter.race.api.AddPenaltyRequest(
                "p-dq", "jia", "DISQUALIFY", null, 3, "req-pen-dq"));
        teamRosterService.lockRoster(RACE, "A", lock(List.of("jia", "yi"), 4, "req-a1"));
        TeamScoreResponse team = teamRosterService.getTeamResults(RACE, 1).teams().getFirst();
        assertThat(team.totalTimeMs()).isZero();
        assertThat(team.scoringCount()).isZero();
        assertThat(team.members()).extracting(TeamMemberScoreResponse::status)
                .containsExactly(EntryStatus.DISQUALIFIED, EntryStatus.UNTIMED);

        // 锁定后撤销甲的取消资格：版本固化状态仍是 DISQUALIFIED，团队总分仍为0。
        raceService.revokePenalty(RACE, "p-dq",
                new com.example.starter.race.api.RevokePenaltyRequest(5, "req-revoke"));
        TeamScoreResponse afterRevoke =
                teamRosterService.getTeamResults(RACE, 1).teams().getFirst();
        assertThat(afterRevoke.totalTimeMs()).isZero();
        assertThat(afterRevoke.members()).extracting(TeamMemberScoreResponse::status)
                .containsExactly(EntryStatus.DISQUALIFIED, EntryStatus.UNTIMED);
    }
}
