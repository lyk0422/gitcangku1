package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureRelayRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.HandoffResponse;
import com.example.starter.race.api.RegisterRelayTeamRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RelayMemberRequest;
import com.example.starter.race.api.RelayRankEntryResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitHandoffRequest;
import com.example.starter.race.domain.RelayTeamStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接力服务 H2 测试：交接区窗口校验、犯规判定、团队完赛聚合、排名、幂等与封榜。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class RelayServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "relay-1";
    private static final int LEGS = 4;
    private static final int LIMIT_MS = 2000;

    @Autowired
    private RaceService raceService;

    @Autowired
    private RelayService relayService;

    @BeforeEach
    void resetClock() {
        MutableClockTestConfig.CLOCK.reset();
    }

    /** 建赛 + 配置接力（4棒、上限2000ms），返回配置后的版本号。 */
    private int createConfiguredRelay(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        relayService.configureRelay(raceId,
                new ConfigureRelayRequest(LEGS, LIMIT_MS, 1, "req-cfg-" + raceId));
        return 2;
    }

    private void registerTeam(String raceId, String teamKey, int expectedVersion) {
        List<RelayMemberRequest> members = new ArrayList<>();
        for (int leg = 1; leg <= LEGS; leg++) {
            members.add(new RelayMemberRequest(leg, teamKey + "-r" + leg));
        }
        relayService.registerTeam(raceId, new RegisterRelayTeamRequest(
                teamKey, members, expectedVersion, "req-reg-" + raceId + "-" + teamKey));
    }

    private HandoffResponse handoff(
            String raceId, String teamKey, int leg, long elapsed, long handoffMs,
            int expectedVersion, String requestId) {
        return (HandoffResponse) relayService.submitHandoff(raceId, new SubmitHandoffRequest(
                teamKey, leg, elapsed, handoffMs, expectedVersion, requestId)).body();
    }

    @Test
    void 完整流程_交接犯规仍推进_末棒聚合完赛并排名() {
        int v = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v++); // v2 -> v3
        registerTeam(RACE, "B", v++); // v3 -> v4

        // A队：第2棒正常，第3棒超时犯规，第4棒正常完赛
        HandoffResponse leg2 = handoff(RACE, "A", 2, 1000, 1000, v++, "req-a2");
        assertThat(leg2.foul()).isFalse();
        assertThat(leg2.finished()).isFalse();
        assertThat(leg2.teamStatus()).isEqualTo(RelayTeamStatus.RACING.name());
        MutableClockTestConfig.CLOCK.advanceMillis(100);

        HandoffResponse leg3 = handoff(RACE, "A", 3, 2000, 2500, v++, "req-a3");
        assertThat(leg3.foul()).isTrue();
        assertThat(leg3.totalFouls()).isEqualTo(1);
        assertThat(leg3.finished()).isFalse();
        MutableClockTestConfig.CLOCK.advanceMillis(100);

        HandoffResponse leg4 = handoff(RACE, "A", 4, 3000, 1500, v++, "req-a4");
        assertThat(leg4.finished()).isTrue();
        assertThat(leg4.totalFouls()).isEqualTo(1);
        assertThat(leg4.totalElapsedMillis()).isEqualTo(3000L);
        assertThat(leg4.teamStatus()).isEqualTo(RelayTeamStatus.RANKED.name());
        MutableClockTestConfig.CLOCK.advanceMillis(100);

        // B队：三次交接全部正常，总用时2500，快于A队3000
        handoff(RACE, "B", 2, 800, 500, v++, "req-b2");
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        handoff(RACE, "B", 3, 1600, 600, v++, "req-b3");
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        HandoffResponse b4 = handoff(RACE, "B", 4, 2500, 700, v++, "req-b4");
        assertThat(b4.teamStatus()).isEqualTo(RelayTeamStatus.RANKED.name());

        // 即时排名：B第1、A第2（A有犯规标注但不取消资格）
        RelayStandingResponse standing = relayService.getStanding(RACE);
        assertThat(standing.status()).isEqualTo("OPEN");
        assertThat(standing.legCount()).isEqualTo(LEGS);
        assertThat(standing.teams()).extracting(RelayRankEntryResponse::teamKey)
                .containsExactly("B", "A");
        assertThat(standing.teams()).extracting(RelayRankEntryResponse::rank)
                .containsExactly(1, 2);
        RelayRankEntryResponse aEntry = standing.teams().get(1);
        assertThat(aEntry.foul()).isTrue();
        assertThat(aEntry.totalFouls()).isEqualTo(1);
        assertThat(aEntry.totalElapsedMillis()).isEqualTo(3000L);

        // 逐棒明细：固化各棒耗时、交接用时与犯规棒次
        RelayTeamDetailResponse detail = relayService.getTeamDetail(RACE, "A");
        assertThat(detail.status()).isEqualTo(RelayTeamStatus.RANKED.name());
        assertThat(detail.legs()).hasSize(4);
        assertThat(detail.legs()).extracting(l -> l.legNo())
                .containsExactly(1, 2, 3, 4);
        assertThat(detail.legs()).extracting(l -> l.elapsedMillis())
                .containsExactly(null, 1000L, 2000L, 3000L);
        assertThat(detail.legs()).extracting(l -> l.handoffMillis())
                .containsExactly(null, 1000L, 2500L, 1500L);
        assertThat(detail.legs()).extracting(l -> l.foul())
                .containsExactly(false, false, true, false);
        assertThat(detail.fouls()).hasSize(1);
        assertThat(detail.fouls().getFirst().legNo()).isEqualTo(3);
        assertThat(detail.fouls().getFirst().handoffMillis()).isEqualTo(2500L);
        assertThat(detail.fouls().getFirst().limitMillis()).isEqualTo(LIMIT_MS);
        assertThat(detail.totalElapsedMillis()).isEqualTo(3000L);
    }

    @Test
    void 累计两次犯规末棒自动取消资格且不排名() {
        int v = createConfiguredRelay(RACE);
        registerTeam(RACE, "DQ", v++);
        handoff(RACE, "DQ", 2, 1000, 2500, v++, "req-dq2"); // 犯规1
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        handoff(RACE, "DQ", 3, 2000, 2600, v++, "req-dq3"); // 犯规2
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        HandoffResponse fin = handoff(RACE, "DQ", 4, 3000, 500, v++, "req-dq4");
        assertThat(fin.finished()).isTrue();
        assertThat(fin.totalFouls()).isEqualTo(2);
        assertThat(fin.teamStatus()).isEqualTo(RelayTeamStatus.DISQUALIFIED.name());

        RelayStandingResponse standing = relayService.getStanding(RACE);
        RelayRankEntryResponse entry = standing.teams().getFirst();
        assertThat(entry.teamKey()).isEqualTo("DQ");
        assertThat(entry.status()).isEqualTo(RelayTeamStatus.DISQUALIFIED.name());
        assertThat(entry.rank()).isNull();
        // 完赛记录仍保留总用时
        assertThat(entry.totalElapsedMillis()).isEqualTo(3000L);
        assertThat(relayService.getTeamDetail(RACE, "DQ").fouls()).hasSize(2);
    }

    @Test
    void 累计耗时不增返回422且不推进不占键() {
        int v0 = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v0);
        final int v = v0 + 1;
        handoff(RACE, "A", 2, 1000, 1000, v, "req-a2");
        MutableClockTestConfig.CLOCK.advanceMillis(100);

        // 第3棒累计耗时等于上一棒 -> 422
        assertThatThrownBy(() -> handoff(RACE, "A", 3, 1000, 1000, v + 1, "req-a3-bad"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("累计耗时");
        // 版本不变、第3棒未交接
        assertThat(relayService.getStanding(RACE).version()).isEqualTo(v + 1);
        assertThat(relayService.getTeamDetail(RACE, "A").legs().get(2).elapsedMillis()).isNull();

        // 用更大耗时正常提交第3棒
        HandoffResponse ok = handoff(RACE, "A", 3, 2000, 1000, v + 1, "req-a3-ok");
        assertThat(ok.foul()).isFalse();
    }

    @Test
    void 服务端时刻不晚于上一棒完成时刻返回422() {
        int v0 = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v0);
        final int v = v0 + 1;
        long leg2CompletedAt = handoff(RACE, "A", 2, 1000, 1000, v, "req-a2").completedAt();
        // 不推进时钟，立即提交第3棒：当前时刻不晚于上一棒完成时刻
        assertThatThrownBy(() -> handoff(RACE, "A", 3, 2000, 1000, v + 1, "req-a3-time"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("晚于上一棒次交接完成时刻 " + leg2CompletedAt);
    }

    @Test
    void 交接必须按棒次顺序且不可重复() {
        int v0 = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v0);
        final int v = v0 + 1;
        // 越过第2棒直接第3棒 -> 422
        assertThatThrownBy(() -> handoff(RACE, "A", 3, 2000, 1000, v, "req-jump"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("顺序");
        handoff(RACE, "A", 2, 1000, 1000, v, "req-a2");
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        // 重复提交第2棒（新requestId）-> 409
        assertThatThrownBy(() -> handoff(RACE, "A", 2, 1500, 1000, v + 1, "req-a2-dup"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已完成交接");
        // 棒次超出赛事棒次数 -> 400
        assertThatThrownBy(() -> handoff(RACE, "A", 5, 5000, 1000, v + 1, "req-a5"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 交接区用时等于上限不判犯规_超过才判() {
        int v = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v++);
        HandoffResponse atLimit = handoff(RACE, "A", 2, 1000, LIMIT_MS, v, "req-a2");
        assertThat(atLimit.foul()).isFalse();
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        HandoffResponse overLimit = handoff(RACE, "A", 3, 2000, LIMIT_MS + 1, v + 1, "req-a3");
        assertThat(overLimit.foul()).isTrue();
    }

    @Test
    void 同键同参重放原结果_异参409_失败不占键() {
        int v = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v++);

        SubmitHandoffRequest req = new SubmitHandoffRequest(
                "A", 2, 1000L, 1000L, v, "req-same");
        ServiceResult first = relayService.submitHandoff(RACE, req);
        ServiceResult replay = relayService.submitHandoff(RACE, req);
        assertThat(replay.status()).isEqualTo(first.status());
        // 重放不重复推进版本、不产生第二条交接
        assertThat(relayService.getStanding(RACE).version()).isEqualTo(v + 1);
        assertThat(relayService.getTeamDetail(RACE, "A").legs().get(1).elapsedMillis())
                .isEqualTo(1000L);

        // 同键异参 -> 409（即使是第3棒合法内容）
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        SubmitHandoffRequest different = new SubmitHandoffRequest(
                "A", 3, 2000L, 1000L, v + 1, "req-same");
        assertThatThrownBy(() -> relayService.submitHandoff(RACE, different))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败（版本不符）不占用 requestId：先用错误版本失败，再用同键正确版本成功
        assertThatThrownBy(() -> handoff(RACE, "A", 3, 2000, 1000, 999, "req-freed"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-freed")).isEmpty();
        HandoffResponse retried = handoff(RACE, "A", 3, 2000, 1000, v + 1, "req-freed");
        assertThat(retried.legNo()).isEqualTo(3);
    }

    @Test
    void 接力配置不可修改_非接力赛不能交接() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        relayService.configureRelay(RACE,
                new ConfigureRelayRequest(LEGS, LIMIT_MS, 1, "req-cfg"));
        // 再次配置 -> 409
        assertThatThrownBy(() -> relayService.configureRelay(RACE,
                new ConfigureRelayRequest(3, 1000, 2, "req-cfg-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不可修改");

        // 个人赛不能登记队伍/交接
        String personal = "personal-race";
        raceService.createRace(new CreateRaceRequest(personal, "req-create-p"));
        assertThatThrownBy(() -> registerTeam(personal, "A", 1))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> handoff(personal, "A", 2, 1000, 1000, 1, "req-x"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 接力赛拒绝个人计时提交() {
        int v = createConfiguredRelay(RACE);
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("solo", 1000L, v, "req-solo")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("接力赛事不接受个人");
    }

    @Test
    void 登记队伍棒次校验() {
        int v = createConfiguredRelay(RACE);
        // 缺棒
        List<RelayMemberRequest> missing = List.of(
                new RelayMemberRequest(1, "a1"), new RelayMemberRequest(2, "a2"),
                new RelayMemberRequest(3, "a3"));
        assertThatThrownBy(() -> relayService.registerTeam(RACE,
                new RegisterRelayTeamRequest("A", missing, v, "req-reg-bad1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("各登记一名选手");
        // 重复棒次
        List<RelayMemberRequest> dup = new ArrayList<>();
        dup.add(new RelayMemberRequest(1, "a1"));
        dup.add(new RelayMemberRequest(1, "a1x"));
        for (int leg = 2; leg <= LEGS; leg++) {
            dup.add(new RelayMemberRequest(leg, "a" + leg));
        }
        assertThatThrownBy(() -> relayService.registerTeam(RACE,
                new RegisterRelayTeamRequest("B", dup, v, "req-reg-bad2")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("同一棒次只能登记一名选手");
        // 队伍标识重复 -> 409
        registerTeam(RACE, "C", v);
        assertThatThrownBy(() -> registerTeam(RACE, "C", v + 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜固化逐棒明细与名次_封榜后禁止交接() {
        int v = createConfiguredRelay(RACE);
        registerTeam(RACE, "A", v++);
        handoff(RACE, "A", 2, 1000, 2500, v++, "req-a2"); // 犯规
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        handoff(RACE, "A", 3, 2000, 500, v++, "req-a3");
        MutableClockTestConfig.CLOCK.advanceMillis(100);
        handoff(RACE, "A", 4, 3000, 500, v++, "req-a4");

        RelayTeamDetailResponse live = relayService.getTeamDetail(RACE, "A");
        RelayStandingResponse sealed = (RelayStandingResponse) relayService.getStanding(RACE);
        int sealVersion = sealed.version();

        raceService.sealRace(RACE, new SealRaceRequest(sealVersion, "req-seal"));

        RelayStandingResponse afterSeal = relayService.getStanding(RACE);
        assertThat(afterSeal.status()).isEqualTo("SEALED");
        assertThat(afterSeal.sealedAt()).isNotNull();
        assertThat(afterSeal.teams()).hasSize(1);
        assertThat(afterSeal.teams().getFirst().rank()).isEqualTo(1);
        assertThat(afterSeal.teams().getFirst().totalFouls()).isEqualTo(1);

        RelayTeamDetailResponse snapDetail = relayService.getTeamDetail(RACE, "A");
        assertThat(snapDetail.status()).isEqualTo(live.status());
        assertThat(snapDetail.legs()).hasSize(4);
        assertThat(snapDetail.legs()).extracting(l -> l.handoffMillis())
                .containsExactly(null, 2500L, 500L, 500L);
        assertThat(snapDetail.legs()).extracting(l -> l.elapsedMillis())
                .containsExactly(null, 1000L, 2000L, 3000L);
        assertThat(snapDetail.fouls()).hasSize(1);

        // 封榜后不能再提交交接
        assertThatThrownBy(() -> handoff(RACE, "A", 2, 999, 1000, sealVersion + 1, "req-late"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }
}
