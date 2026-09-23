package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamStandingEntryResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 团队计分的 H2 业务测试：主流程、冻结规则、成员冲突、幂等重放与封榜快照。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team";

    @Autowired
    private RaceService raceService;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void register(String bib, Long finishTimeMs, int expectedVersion) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, "req-reg-" + bib));
    }

    private void revise(String bib, long finishTimeMs, int expectedVersion) {
        raceService.reviseTime(RACE,
                new ReviseTimeRequest(bib, finishTimeMs, expectedVersion, "req-rev-" + bib));
    }

    @Test
    void 团队主流程_创建计分处罚封榜快照() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        register("d", null, 4);
        register("e", null, 5);
        register("f", null, 6);

        ServiceResult t1 = raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("c", "a", "b"), 7, "req-team-1"));
        assertThat(t1.status()).isEqualTo(201);
        TeamResponse t1Body = (TeamResponse) t1.body();
        assertThat(t1Body.version()).isEqualTo(8);
        assertThat(t1Body.members()).containsExactly("a", "b", "c");

        ServiceResult t2 = raceService.createTeam(RACE,
                new CreateTeamRequest("T2", List.of("d", "e", "f"), 8, "req-team-2"));
        assertThat(t2.status()).isEqualTo(201);

        revise("a", 300, 9);
        revise("b", 100, 10);
        revise("c", 200, 11);
        revise("d", 400, 12);
        revise("e", 500, 13);
        revise("f", 600, 14);

        TeamStandingsResponse live = raceService.getTeamStandings(RACE);
        assertThat(live.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(live.version()).isEqualTo(15);
        assertThat(live.teams()).hasSize(2);
        TeamStandingEntryResponse first = live.teams().get(0);
        assertThat(first.teamCode()).isEqualTo("T1");
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(first.totalTimeMs()).isEqualTo(600L);
        assertThat(first.members()).hasSize(3);
        assertThat(first.members().get(0).bib()).isEqualTo("a");
        assertThat(first.members().get(0).scoring()).isTrue();
        assertThat(first.members().get(0).scoringTimeMs()).isEqualTo(300L);
        assertThat(live.teams().get(1).teamCode()).isEqualTo("T2");
        assertThat(live.teams().get(1).rank()).isEqualTo(2);
        assertThat(live.teams().get(1).totalTimeMs()).isEqualTo(1500L);

        // 加时处罚立即影响未封榜团队成绩：a 总耗时 1300，T1 合计 1600 跌至第2
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-1", "a", "ADD_TIME", 1000L, 15, "req-pen-1"));
        TeamStandingsResponse penalized = raceService.getTeamStandings(RACE);
        assertThat(penalized.teams().get(0).teamCode()).isEqualTo("T2");
        assertThat(penalized.teams().get(1).teamCode()).isEqualTo("T1");
        assertThat(penalized.teams().get(1).totalTimeMs()).isEqualTo(1600L);

        // 撤销处罚后团队成绩恢复
        raceService.revokePenalty(RACE, "pen-1", new RevokePenaltyRequest(16, "req-revoke-1"));
        TeamStandingsResponse restored = raceService.getTeamStandings(RACE);
        assertThat(restored.teams().get(0).teamCode()).isEqualTo("T1");
        assertThat(restored.teams().get(0).totalTimeMs()).isEqualTo(600L);

        // 封榜：团队与个人结果同事务固化，版本一致
        raceService.sealRace(RACE, new SealRaceRequest(17, "req-seal"));
        TeamStandingsResponse sealed = raceService.getTeamStandings(RACE);
        assertThat(sealed.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealed.version()).isEqualTo(18);
        assertThat(sealed.sealedAt()).isNotNull();
        assertThat(sealed.teams()).hasSize(2);
        assertThat(sealed.teams().get(0).teamCode()).isEqualTo("T1");
        assertThat(sealed.teams().get(0).rank()).isEqualTo(1);
        assertThat(sealed.teams().get(0).totalTimeMs()).isEqualTo(600L);
        assertThat(sealed.teams().get(1).teamCode()).isEqualTo("T2");
        assertThat(sealed.teams().get(1).rank()).isEqualTo(2);

        // 个人排名不受入队影响
        StandingResponse personal = raceService.getResults(RACE);
        assertThat(personal.entries()).hasSize(6);
        assertThat(personal.entries().get(0).bib()).isEqualTo("b");
        assertThat(personal.entries().get(0).rank()).isEqualTo(1);
        assertThat(personal.entries().get(0).status()).isEqualTo(EntryStatus.RANKED);
    }

    @Test
    void 出现完赛计时后团队配置冻结且撤销处罚不解除() {
        createRace();
        register("x", 1000L, 1);
        register("y", null, 2);
        register("z", null, 3);
        register("w", null, 4);

        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("y", "z", "w"), 5, "req-team-frozen")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");

        // 撤销处罚不重新开放：处罚生效再撤销后，完赛计时仍存在，配置仍冻结
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-x", "x", "ADD_TIME", 100L, 5, "req-pen-x"));
        raceService.revokePenalty(RACE, "pen-x", new RevokePenaltyRequest(6, "req-revoke-x"));
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("y", "z", "w"), 7, "req-team-frozen-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        assertThat(repository.findTeams(RACE)).isEmpty();
    }

    @Test
    void 出现分段记录后团队配置冻结() {
        createRace();
        register("x", 10_000L, 1);
        register("y", null, 2);
        register("z", null, 3);
        register("w", null, 4);
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("CP1", 1)),
                5, "req-cp"));
        raceService.submitTiming(RACE, "x",
                new SubmitTimingRequest("timing-1", "CP1", 5000L, 6, "req-timing-1"));

        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("y", "z", "w"), 7, "req-team-frozen")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");
        assertThat(repository.findTeams(RACE)).isEmpty();
    }

    @Test
    void 成员冲突_重复团队代码_未登记成员_非法参数() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        register("d", null, 4);
        raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("a", "b", "c"), 5, "req-team-1"));

        // 同一选手最多属于一队：409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T2", List.of("a", "d", "b"), 6, "req-team-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已属于团队");

        // 团队代码重复：409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("d", "b", "c"), 6, "req-team-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("团队代码已存在");

        // 未登记参赛号：404
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T3", List.of("d", "ghost", "b"), 6, "req-team-4")))
                .isInstanceOf(NotFoundException.class);

        // 成员数量越界：400
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T4", List.of("b", "c"), 6, "req-team-5")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T4", List.of("a", "b", "c", "d", "e", "f"), 6, "req-team-6")))
                .isInstanceOf(BadRequestException.class);

        // 成员重复：400
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T4", List.of("b", "b", "c"), 6, "req-team-7")))
                .isInstanceOf(BadRequestException.class);

        // 版本冲突：409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T5", List.of("b", "c", "d"), 1, "req-team-8")))
                .isInstanceOf(ConflictException.class);

        // 全部失败不改变成员与版本
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(6);
        assertThat(repository.findTeams(RACE)).hasSize(1);
        assertThat(repository.findTeamMembers(RACE)).hasSize(3);
    }

    @Test
    void 幂等_同键同参换序重放_异参409_失败不占键() {
        createRace();
        register("a", null, 1);
        register("b", null, 2);
        register("c", null, 3);
        register("d", null, 4);
        register("e", null, 5);
        register("f", null, 6);

        ServiceResult first = raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("c", "a", "b"), 7, "req-team-idem"));
        assertThat(first.status()).isEqualTo(201);

        // 同键同参（成员集合换序）：重放首次结果，不产生新变更
        ServiceResult replayed = raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("b", "c", "a"), 7, "req-team-idem"));
        assertThat(replayed.status()).isEqualTo(201);
        com.fasterxml.jackson.databind.JsonNode replayedBody =
                (com.fasterxml.jackson.databind.JsonNode) replayed.body();
        assertThat(replayedBody.get("teamCode").asText()).isEqualTo("T1");
        assertThat(replayedBody.get("version").asInt()).isEqualTo(8);
        assertThat(replayedBody.get("members").toString()).isEqualTo("[\"a\",\"b\",\"c\"]");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        assertThat(repository.findTeams(RACE)).hasSize(1);

        // 同键异参（成员集合不同）：409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("a", "b", "d"), 7, "req-team-idem")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：非法参数失败后，同键修正参数可成功
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T2", List.of("d", "e"), 8, "req-team-retry")))
                .isInstanceOf(BadRequestException.class);
        ServiceResult retried = raceService.createTeam(RACE,
                new CreateTeamRequest("T2", List.of("d", "e", "f"), 8, "req-team-retry"));
        assertThat(retried.status()).isEqualTo(201);
        assertThat(repository.findTeams(RACE)).hasSize(2);
    }

    @Test
    void RANKED不足3人的团队显示INCOMPLETE且置于末尾() {
        String race2 = "race-team-incomplete";
        raceService.createRace(new CreateRaceRequest(race2, "req-create-2"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("p", null, 1, "req-p"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("q", null, 2, "req-q"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("r", null, 3, "req-r"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("s", null, 4, "req-s"));
        raceService.createTeam(race2,
                new CreateTeamRequest("TZ", List.of("p", "q", "r", "s"), 5, "req-team-z"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("u", null, 6, "req-u"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("v", null, 7, "req-v"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("w", null, 8, "req-w"));
        raceService.createTeam(race2,
                new CreateTeamRequest("TA", List.of("u", "v", "w"), 9, "req-team-a"));
        // TA 三名成员全部完赛；TZ 仅 p 完赛，RANKED 不足3人
        raceService.reviseTime(race2, new ReviseTimeRequest("u", 100L, 10, "req-rt-u"));
        raceService.reviseTime(race2, new ReviseTimeRequest("v", 200L, 11, "req-rt-v"));
        raceService.reviseTime(race2, new ReviseTimeRequest("w", 300L, 12, "req-rt-w"));
        raceService.reviseTime(race2, new ReviseTimeRequest("p", 50L, 13, "req-rt-p"));

        TeamStandingsResponse standings = raceService.getTeamStandings(race2);
        assertThat(standings.teams()).hasSize(2);
        assertThat(standings.teams().get(0).teamCode()).isEqualTo("TA");
        assertThat(standings.teams().get(0).status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(standings.teams().get(0).totalTimeMs()).isEqualTo(600L);
        assertThat(standings.teams().get(1).teamCode()).isEqualTo("TZ");
        assertThat(standings.teams().get(1).status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(standings.teams().get(1).rank()).isNull();
        assertThat(standings.teams().get(1).totalTimeMs()).isNull();
    }

    @Test
    void 封榜后创建团队409且已封榜无团队赛事返回空团队列表() {
        createRace();
        register("a", 100L, 1);
        raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal"));

        // 已封榜赛事不补造队伍：创建团队 409
        assertThatThrownBy(() -> raceService.createTeam(RACE,
                new CreateTeamRequest("T1", List.of("a", "b", "c"), 3, "req-team-after-seal")))
                .isInstanceOf(ConflictException.class);

        // 封榜前无团队：返回空团队列表而非错误
        TeamStandingsResponse standings = raceService.getTeamStandings(RACE);
        assertThat(standings.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standings.teams()).isEmpty();
    }

    @Test
    void 查询团队成绩赛事不存在返回404() {
        assertThatThrownBy(() -> raceService.getTeamStandings("missing-race"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.createTeam("missing-race",
                new CreateTeamRequest("T1", List.of("a", "b", "c"), 1, "req-team-404")))
                .isInstanceOf(NotFoundException.class);
    }
}
