package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CurrentRosterMemberResponse;
import com.example.starter.race.api.CurrentRosterResponse;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.MemberWithdrawalResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RosterMemberResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.WithdrawRosterMemberRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 团队名单锁定的 H2 数据库测试：覆盖题干赛事E主流程、跨队/重复/替换 409、
 * 整单失败无半份名单、退赛不改写已锁定与已封榜版本、requestId 幂等等状态边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "E";
    private static final String TEAM_A = "A";
    private static final String TEAM_B = "B";

    @Autowired
    private RaceService raceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 建赛事并登记 甲~戊，成绩 1000~5000ms，名次依次1~5（名次积分10~6）；结束时赛事版本为6。 */
    private void seedFiveRunners() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("jia", 1000L, 1, "req-reg-jia"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("yi", 2000L, 2, "req-reg-yi"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("bing", 3000L, 3, "req-reg-bing"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("ding", 4000L, 4, "req-reg-ding"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("wu", 5000L, 5, "req-reg-wu"));
    }

    private Integer teamPoints(String teamId) {
        return raceService.getTeamResults(RACE).teams().stream()
                .filter(t -> t.teamId().equals(teamId))
                .findFirst().orElseThrow()
                .teamPoints();
    }

    @Test
    void 赛事E_跨队拒绝_版本固化_退赛不改写已封榜版本() {
        seedFiveRunners();

        // v7：A 锁定版本1=[甲,乙]，固化名次积分 10+9=19
        ServiceResult lockA1 = raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "yi"), 6, "req-lock-a1"));
        assertThat(lockA1.status()).isEqualTo(201);
        TeamRosterResponse a1 = (TeamRosterResponse) lockA1.body();
        assertThat(a1.versionNo()).isEqualTo(1);
        assertThat(a1.teamPoints()).isEqualTo(19);
        assertThat(a1.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("jia", "yi");
        assertThat(a1.members()).extracting(RosterMemberResponse::memberPoints)
                .containsExactly(10, 9);
        assertThat(a1.members().getFirst().rank()).isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 甲已属 A，B 再锁入甲返回409
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_B,
                new LockRosterRequest(List.of("jia"), 7, "req-lock-b-jia")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已属于其它团队");
        // 失败不留任何 B 名单/归属，赛事版本不变
        assertThat(raceService.getRosterVersions(RACE, TEAM_B)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // v8：B 锁定版本1=[丙,丁]，积分 8+7=15
        raceService.lockRoster(RACE, TEAM_B,
                new LockRosterRequest(List.of("bing", "ding"), 7, "req-lock-b1"));
        TeamStandingsResponse liveBeforeV2 = raceService.getTeamResults(RACE);
        assertThat(liveBeforeV2.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(liveBeforeV2.teams()).extracting(TeamStandingsResponse.TeamStandingResponse::teamId)
                .containsExactly(TEAM_A, TEAM_B);
        assertThat(liveBeforeV2.teams()).extracting(TeamStandingsResponse.TeamStandingResponse::rank)
                .containsExactly(1, 2);
        assertThat(teamPoints(TEAM_A)).isEqualTo(19);
        assertThat(teamPoints(TEAM_B)).isEqualTo(15);

        // v9：A 锁定版本2=[甲,乙,戊]，积分 10+9+6=25
        ServiceResult lockA2 = raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("wu", "jia", "yi"), 8, "req-lock-a2"));
        TeamRosterResponse a2 = (TeamRosterResponse) lockA2.body();
        assertThat(a2.versionNo()).isEqualTo(2);
        assertThat(a2.teamPoints()).isEqualTo(25);
        // 提交顺序不影响固化顺序（按参赛号字典序）
        assertThat(a2.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("jia", "wu", "yi");

        // 历史版本1不随版本2改写
        TeamRosterResponse a1Again = raceService.getRosterVersion(RACE, TEAM_A, 1);
        assertThat(a1Again.teamPoints()).isEqualTo(19);
        assertThat(a1Again.members()).extracting(RosterMemberResponse::bib)
                .containsExactly("jia", "yi");
        assertThat(raceService.getRosterVersions(RACE, TEAM_A)).hasSize(2);

        // v10：甲在版本2锁定后退赛（生效场次 S3、原因），OPEN 下影响未封榜实时计分
        ServiceResult withdrawal = raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("jia", "S3", "受伤退赛", 9, "req-withdraw-jia"));
        assertThat(withdrawal.status()).isEqualTo(200);
        MemberWithdrawalResponse withdrawalBody = (MemberWithdrawalResponse) withdrawal.body();
        assertThat(withdrawalBody.state()).isEqualTo("WITHDRAWN");
        assertThat(withdrawalBody.effectiveSession()).isEqualTo("S3");
        assertThat(withdrawalBody.reason()).isEqualTo("受伤退赛");
        assertThat(withdrawalBody.withdrawnAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        // 已锁定版本2不随退赛重算，且成员行未被删除
        assertThat(raceService.getRosterVersion(RACE, TEAM_A, 2).teamPoints()).isEqualTo(25);
        Integer memberRowsV2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_roster_member WHERE race_id=? AND team_id=? AND version_no=?",
                Integer.class, RACE, TEAM_A, 2);
        assertThat(memberRowsV2).isEqualTo(3);

        // 当前名单反映退赛状态但不删人
        CurrentRosterResponse currentA = raceService.getCurrentRoster(RACE, TEAM_A);
        assertThat(currentA.currentVersionNo()).isEqualTo(2);
        assertThat(currentA.members()).extracting(CurrentRosterMemberResponse::bib)
                .containsExactly("jia", "wu", "yi");
        CurrentRosterMemberResponse jiaCurrent = currentA.members().stream()
                .filter(m -> m.bib().equals("jia")).findFirst().orElseThrow();
        assertThat(jiaCurrent.state()).isEqualTo("WITHDRAWN");
        assertThat(jiaCurrent.effectiveSession()).isEqualTo("S3");
        assertThat(jiaCurrent.firstVersionNo()).isEqualTo(1);

        // 未封榜实时计分剔除退赛的甲：A=9+6=15 与 B=15 并列第1
        TeamStandingsResponse liveAfterWithdraw = raceService.getTeamResults(RACE);
        assertThat(liveAfterWithdraw.teams()).extracting(t -> t.teamId() + ":" + t.teamPoints())
                .containsExactly("A:15", "B:15");
        assertThat(liveAfterWithdraw.teams()).extracting(TeamStandingsResponse.TeamStandingResponse::rank)
                .containsExactly(1, 1);
        assertThat(liveAfterWithdraw.teams().stream()
                .filter(t -> t.teamId().equals(TEAM_A)).findFirst().orElseThrow().memberCount())
                .isEqualTo(2);

        // v11：封榜，团队榜同事务固化（引用 A 完整版本2、B 版本1，各剔除当时已退赛成员）
        ServiceResult seal = raceService.sealRace(RACE,
                new SealRaceRequest(10, "req-seal"));
        assertThat(seal.status()).isEqualTo(200);
        TeamStandingsResponse sealedStandings = raceService.getTeamResults(RACE);
        assertThat(sealedStandings.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStandings.version()).isEqualTo(11);
        assertThat(sealedStandings.teams()).extracting(t -> t.teamId() + "@" + t.rosterVersionNo()
                        + ":" + t.teamPoints())
                .containsExactly("A@2:15", "B@1:15");
        assertThat(raceService.getTeamSnapshot(RACE)).usingRecursiveComparison()
                .isEqualTo(sealedStandings);

        // 封榜后乙退赛（留痕：版本不推进、快照与版本2积分均不重算）
        ServiceResult withdrawYiAfterSeal = raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("yi", "S4", "赛后退赛", 11, "req-withdraw-yi"));
        assertThat(withdrawYiAfterSeal.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(11);
        // 已封榜的版本2积分仍为剔除甲后的15，不因乙退赛变成6
        TeamStandingsResponse snapshotAfter = raceService.getTeamSnapshot(RACE);
        assertThat(snapshotAfter.teams()).extracting(t -> t.teamId() + ":" + t.teamPoints())
                .containsExactly("A:15", "B:15");
        // 已锁定版本2自身积分恒为25
        assertThat(raceService.getRosterVersion(RACE, TEAM_A, 2).teamPoints()).isEqualTo(25);
    }

    @Test
    void 同单成员重复返回409且无半份名单() {
        seedFiveRunners();
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "jia"), 6, "req-dup-in-request")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重复入队");
        assertThat(raceService.getRosterVersions(RACE, TEAM_A)).isEmpty();
        assertThat(countTable("team_roster")).isZero();
        assertThat(countTable("team_membership")).isZero();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 空名单与未登记成员分别400和422且不生成半份名单() {
        seedFiveRunners();
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of(), 6, "req-empty")))
                .isInstanceOf(BadRequestException.class);

        // 名单中任一成员未登记：整单422，丙合法也不写入，无半份名单，成绩归属不变
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_B,
                new LockRosterRequest(List.of("bing", "ghost"), 6, "req-ghost")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ghost");
        assertThat(raceService.getRosterVersions(RACE, TEAM_B)).isEmpty();
        assertThat(countTable("team_roster")).isZero();
        assertThat(countTable("team_membership")).isZero();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 封榜后锁定名单返回409() {
        seedFiveRunners();
        raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia"), 6, "req-a1"));
        raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));

        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_B,
                new LockRosterRequest(List.of("yi"), 8, "req-b-after-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThat(raceService.getRosterVersions(RACE, TEAM_B)).isEmpty();
    }

    @Test
    void 已锁定成员替换到其它团队返回409() {
        seedFiveRunners();
        raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "yi"), 6, "req-a1"));
        // B 用乙替换本队成员同样409（成员赛事内唯一归属）
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_B,
                new LockRosterRequest(List.of("yi", "bing"), 7, "req-b-steal-yi")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已属于其它团队");
        assertThat(raceService.getRosterVersions(RACE, TEAM_B)).isEmpty();
        // 甲、乙归属仍只在 A
        assertThat(repository.findMembership(RACE, "jia").orElseThrow().teamId()).isEqualTo(TEAM_A);
        assertThat(repository.findMembership(RACE, "yi").orElseThrow().teamId()).isEqualTo(TEAM_A);
    }

    @Test
    void 已退赛成员不得进入新名单版本() {
        seedFiveRunners();
        raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "yi"), 6, "req-a1"));
        raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("jia", "S2", "伤退", 7, "req-wd"));
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "yi"), 8, "req-a2-jia-back")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 同队不带甲的新版本允许
        ServiceResult a2 = raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("yi", "wu"), 8, "req-a2"));
        assertThat(((TeamRosterResponse) a2.body()).versionNo()).isEqualTo(2);
    }

    @Test
    void 退赛失败分支_未入队_错队_重复退赛() {
        seedFiveRunners();
        raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia", "yi"), 6, "req-a1"));

        // 成员从未进入任何已锁定名单
        assertThatThrownBy(() -> raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("bing", "S1", "未入队", 7, "req-wd-bing")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 成员属于 A，对 B 操作409
        assertThatThrownBy(() -> raceService.withdrawRosterMember(RACE, TEAM_B,
                new WithdrawRosterMemberRequest("jia", "S1", "错队", 7, "req-wd-wrong-team")))
                .isInstanceOf(ConflictException.class);
        // 正常退赛一次
        raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("jia", "S3", "伤退", 7, "req-wd-jia"));
        // 重复退赛409
        assertThatThrownBy(() -> raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("jia", "S4", "再次退赛", 8, "req-wd-jia-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
    }

    @Test
    void 锁定requestId同参重放快照换名单返回409() {
        seedFiveRunners();
        LockRosterRequest first =
                new LockRosterRequest(List.of("jia", "yi"), 6, "req-lock-idem");
        ServiceResult r1 = raceService.lockRoster(RACE, TEAM_A, first);
        // 成员顺序不同但名单相同视为同参，重放首次快照
        ServiceResult r2 = raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("yi", "jia"), 6, "req-lock-idem"));
        assertThat(r2.status()).isEqualTo(r1.status());
        assertThat(serialize(r2.body())).isEqualTo(serialize(r1.body()));
        assertThat(raceService.getRosterVersions(RACE, TEAM_A)).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 同一 requestId 换名单返回409，不产生版本2
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia"), 6, "req-lock-idem")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(raceService.getRosterVersions(RACE, TEAM_A)).hasSize(1);
    }

    @Test
    void 锁定业务失败不占用requestId键() {
        seedFiveRunners();
        // 首次含未登记成员失败，requestId 随事务回滚释放
        assertThatThrownBy(() -> raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("ghost"), 6, "req-retry")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findIdempotency("req-retry")).isEmpty();
        // 同 requestId 以合法参数重试成功
        ServiceResult retry = raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia"), 6, "req-retry"));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(repository.findIdempotency("req-retry")).isPresent();
    }

    @Test
    void 退赛requestId同参重放且异参409() {
        seedFiveRunners();
        raceService.lockRoster(RACE, TEAM_A,
                new LockRosterRequest(List.of("jia"), 6, "req-a1"));
        WithdrawRosterMemberRequest wd =
                new WithdrawRosterMemberRequest("jia", "S3", "伤退", 7, "req-wd-idem");
        ServiceResult w1 = raceService.withdrawRosterMember(RACE, TEAM_A, wd);
        ServiceResult w2 = raceService.withdrawRosterMember(RACE, TEAM_A, wd);
        assertThat(serialize(w2.body())).isEqualTo(serialize(w1.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);

        assertThatThrownBy(() -> raceService.withdrawRosterMember(RACE, TEAM_A,
                new WithdrawRosterMemberRequest("jia", "S9", "换场次", 8, "req-wd-idem")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 从未锁定的团队当前名单版本为零且历史版本404() {
        seedFiveRunners();
        CurrentRosterResponse current = raceService.getCurrentRoster(RACE, TEAM_B);
        assertThat(current.currentVersionNo()).isZero();
        assertThat(current.members()).isEmpty();
        assertThatThrownBy(() -> raceService.getRosterVersion(RACE, TEAM_B, 1))
                .isInstanceOf(NotFoundException.class);
    }

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int countTable(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
