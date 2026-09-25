package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RelayConfigRequest;
import com.example.starter.race.api.RelayConfigResponse;
import com.example.starter.race.api.RelayFoulListResponse;
import com.example.starter.race.api.RelayHandoffRequest;
import com.example.starter.race.api.RelayHandoffResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RelaySnapshotRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.AdjustableClock;
import com.example.starter.race.support.AdjustableClockTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RelayServiceImpl} 的 H2 数据库测试：交接区窗口校验、犯规判定、
 * 团队完赛聚合、封榜快照与幂等边界。
 */
@SpringBootTest
@Import(AdjustableClockTestConfig.class)
class RelayServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "relay-1";
    private static final long LIMIT_MS = 100L;

    @Autowired
    private RelayService relayService;

    @Autowired
    private RaceService raceService;

    @Autowired
    private AdjustableClock clock;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void setupRelayRace() {
        createRace();
        relayService.configureRelay(RACE, new RelayConfigRequest(
                3, LIMIT_MS,
                List.of(
                        new RelayConfigRequest.TeamRunners("T1", List.of("r1", "r2", "r3")),
                        new RelayConfigRequest.TeamRunners("T2", List.of("s1", "s2", "s3"))),
                1, "req-config"));
    }

    private RelayHandoffResponse handoff(
            String team, int leg, String receiver, long elapsed, long zone,
            int expectedVersion, String requestId) {
        return (RelayHandoffResponse) relayService.submitHandoff(RACE,
                new RelayHandoffRequest(team, leg, receiver, elapsed, zone,
                        expectedVersion, requestId))
                .body();
    }

    @Test
    void 配置接力成功版本加一且配置后不可修改() {
        createRace();
        RelayConfigResponse response = (RelayConfigResponse) relayService.configureRelay(RACE,
                new RelayConfigRequest(3, LIMIT_MS,
                        List.of(new RelayConfigRequest.TeamRunners("T1", List.of("a", "b", "c"))),
                        1, "req-config"))
                .body();
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.legCount()).isEqualTo(3);
        assertThat(response.exchangeLimitMs()).isEqualTo(LIMIT_MS);
        assertThat(response.teams()).containsExactly("T1");
        assertThat(repository.findRelayConfig(RACE)).isPresent();
        assertThat(repository.findRelayTeamLegs(RACE)).hasSize(3);

        // 配置后不可修改：即使版本号匹配也被拒绝
        assertThatThrownBy(() -> relayService.configureRelay(RACE, new RelayConfigRequest(
                2, LIMIT_MS,
                List.of(new RelayConfigRequest.TeamRunners("T9", List.of("x", "y"))),
                2, "req-config-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void 配置校验失败分支() {
        createRace();
        // 选手数量须等于棒次数
        assertThatThrownBy(() -> relayService.configureRelay(RACE, new RelayConfigRequest(
                3, LIMIT_MS,
                List.of(new RelayConfigRequest.TeamRunners("T1", List.of("a", "b"))),
                1, "req-bad-size")))
                .isInstanceOf(BadRequestException.class);
        // 队伍标识重复
        assertThatThrownBy(() -> relayService.configureRelay(RACE, new RelayConfigRequest(
                2, LIMIT_MS,
                List.of(
                        new RelayConfigRequest.TeamRunners("T1", List.of("a", "b")),
                        new RelayConfigRequest.TeamRunners("T1", List.of("c", "d"))),
                1, "req-dup-team")))
                .isInstanceOf(BadRequestException.class);
        // 版本冲突
        assertThatThrownBy(() -> relayService.configureRelay(RACE, new RelayConfigRequest(
                2, LIMIT_MS,
                List.of(new RelayConfigRequest.TeamRunners("T1", List.of("a", "b"))),
                9, "req-bad-version")))
                .isInstanceOf(ConflictException.class);
        // 失败不占键：同一 requestId 修正参数后可成功
        relayService.configureRelay(RACE, new RelayConfigRequest(
                2, LIMIT_MS,
                List.of(new RelayConfigRequest.TeamRunners("T1", List.of("a", "b"))),
                1, "req-bad-version"));
        assertThat(repository.findRelayConfig(RACE)).isPresent();
    }

    @Test
    void 交接主流程_末棒自动生成完赛记录与逐棒明细() {
        setupRelayRace();
        // v2 -> v3: T1 第一交接（棒次2），交接区用时50ms未超限
        RelayHandoffResponse first = handoff("T1", 2, "r2", 1000L, 50L, 2, "req-h1");
        assertThat(first.foul()).isFalse();
        assertThat(first.finished()).isFalse();
        assertThat(first.version()).isEqualTo(3);

        clock.advanceMillis(10L);
        // v3 -> v4: T1 末棒交接，交接区用时150ms超限判犯规，但仍推进计时并生成完赛记录
        RelayHandoffResponse last = handoff("T1", 3, "r3", 2500L, 150L, 3, "req-h2");
        assertThat(last.foul()).isTrue();
        assertThat(last.teamFoulCount()).isEqualTo(1);
        assertThat(last.finished()).isTrue();
        assertThat(last.totalMillis()).isEqualTo(2500L);
        assertThat(last.teamStatus()).isEqualTo(EntryStatus.RANKED);

        // 完赛记录已固化
        assertThat(repository.findRelayFinish(RACE, "T1")).isPresent();
        // 犯规记录已写入且唯一
        assertThat(repository.findRelayFouls(RACE)).hasSize(1);

        // 逐棒明细：首棒1000、次棒1500、末棒0（完赛时刻即末棒交接时刻）
        RelayTeamDetailResponse detail = relayService.getTeamDetail(RACE, "T1");
        assertThat(detail.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(detail.totalMillis()).isEqualTo(2500L);
        assertThat(detail.foulCount()).isEqualTo(1);
        assertThat(detail.legs()).hasSize(3);
        assertThat(detail.legs().get(0).runner()).isEqualTo("r1");
        assertThat(detail.legs().get(0).elapsedMillis()).isEqualTo(1000L);
        assertThat(detail.legs().get(0).splitMillis()).isEqualTo(1000L);
        assertThat(detail.legs().get(0).zoneMillis()).isNull();
        assertThat(detail.legs().get(1).elapsedMillis()).isEqualTo(2500L);
        assertThat(detail.legs().get(1).splitMillis()).isEqualTo(1500L);
        assertThat(detail.legs().get(1).zoneMillis()).isEqualTo(50L);
        assertThat(detail.legs().get(1).foul()).isFalse();
        assertThat(detail.legs().get(2).zoneMillis()).isEqualTo(150L);
        assertThat(detail.legs().get(2).foul()).isTrue();

        // 即时排名：T1 完赛排第一且带犯规标注，T2 未完赛
        RelayStandingResponse standing = relayService.getRelayStanding(RACE);
        assertThat(standing.entries()).hasSize(2);
        assertThat(standing.entries().get(0).teamKey()).isEqualTo("T1");
        assertThat(standing.entries().get(0).rank()).isEqualTo(1);
        assertThat(standing.entries().get(0).totalMillis()).isEqualTo(2500L);
        assertThat(standing.entries().get(0).hasFouls()).isTrue();
        assertThat(standing.entries().get(1).teamKey()).isEqualTo("T2");
        assertThat(standing.entries().get(1).status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(standing.entries().get(1).rank()).isNull();

        // 犯规清单
        RelayFoulListResponse fouls = relayService.getFouls(RACE);
        assertThat(fouls.fouls()).hasSize(1);
        assertThat(fouls.fouls().getFirst().teamKey()).isEqualTo("T1");
        assertThat(fouls.fouls().getFirst().leg()).isEqualTo(3);
        assertThat(fouls.fouls().getFirst().zoneMillis()).isEqualTo(150L);
        assertThat(fouls.fouls().getFirst().limitMillis()).isEqualTo(LIMIT_MS);
    }

    @Test
    void 交接时序与数据校验失败均返回422或对应错误() {
        setupRelayRace();
        handoff("T1", 2, "r2", 1000L, 50L, 2, "req-h1");

        // 服务端时刻未晚于上一棒次交接完成时刻（时钟未推进）
        assertThatThrownBy(() -> handoff("T1", 3, "r3", 2500L, 50L, 3, "req-h2"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("晚于上一棒次");

        clock.advanceMillis(10L);
        // elapsedMillis 不大于上一棒次记录值
        assertThatThrownBy(() -> handoff("T1", 3, "r3", 1000L, 50L, 3, "req-h3"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("必须大于上一棒次");
        assertThatThrownBy(() -> handoff("T1", 3, "r3", 900L, 50L, 3, "req-h4"))
                .isInstanceOf(UnprocessableEntityException.class);

        // 上一棒次尚未交接
        assertThatThrownBy(() -> handoff("T2", 3, "s3", 2500L, 50L, 3, "req-h5"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("上一棒次尚未交接");

        // 接棒选手与登记不一致
        assertThatThrownBy(() -> handoff("T2", 2, "s9", 1200L, 50L, 3, "req-h6"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("与登记不一致");

        // 交接棒次超出配置棒次数
        assertThatThrownBy(() -> handoff("T2", 4, "s2", 1200L, 50L, 3, "req-h7"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("超出配置棒次数");

        // 未登记队伍
        assertThatThrownBy(() -> handoff("T9", 2, "x", 1200L, 50L, 3, "req-h8"))
                .isInstanceOf(NotFoundException.class);

        // 全部失败后赛事版本未被推进，仍可用原版本成功提交
        RelayHandoffResponse ok = handoff("T2", 2, "s2", 1200L, 50L, 3, "req-h9");
        assertThat(ok.version()).isEqualTo(4);
    }

    @Test
    void 同一交接重复提交409且同队同一交接只记一次犯规() {
        setupRelayRace();
        handoff("T1", 2, "r2", 1000L, 500L, 2, "req-h1");
        assertThat(repository.findRelayFouls(RACE)).hasSize(1);

        clock.advanceMillis(10L);
        // 同一交接重复提交（不同 requestId）被拒绝，犯规不重复记录
        assertThatThrownBy(() -> handoff("T1", 2, "r2", 1100L, 600L, 3, "req-h2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已提交");
        assertThat(repository.findRelayFouls(RACE)).hasSize(1);
        assertThat(repository.findRelayHandoffs(RACE, "T1")).hasSize(1);
    }

    @Test
    void 犯规达两次队伍自动转为取消资格() {
        setupRelayRace();
        // T1 两次交接均超上限
        handoff("T1", 2, "r2", 1000L, 200L, 2, "req-h1");
        clock.advanceMillis(10L);
        RelayHandoffResponse last = handoff("T1", 3, "r3", 2500L, 300L, 3, "req-h2");
        assertThat(last.teamFoulCount()).isEqualTo(2);
        assertThat(last.teamStatus()).isEqualTo(EntryStatus.DISQUALIFIED);

        RelayStandingResponse standing = relayService.getRelayStanding(RACE);
        RelayStandingResponse.Entry t1 = standing.entries().stream()
                .filter(e -> e.teamKey().equals("T1"))
                .findFirst()
                .orElseThrow();
        assertThat(t1.status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(t1.rank()).isNull();
        assertThat(t1.foulCount()).isEqualTo(2);
    }

    @Test
    void 接力模式下拒绝个人完赛计时提交() {
        setupRelayRace();
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, 2, "req-reg")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("接力赛事");
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 1000L, 2, "req-rev")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("接力赛事");
        // 个人成绩榜不适用于接力赛事
        assertThatThrownBy(() -> raceService.getResults(RACE))
                .isInstanceOf(ConflictException.class);
        // 非接力赛事不接受交接提交
        raceService.createRace(new CreateRaceRequest("plain-1", "req-create-plain"));
        assertThatThrownBy(() -> relayService.submitHandoff("plain-1",
                new RelayHandoffRequest("T1", 2, "r2", 1000L, 50L, 1, "req-h-plain")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("非接力赛事");
    }

    @Test
    void 封榜固化逐棒明细犯规与名次且封榜后禁止交接() {
        setupRelayRace();
        handoff("T1", 2, "r2", 1000L, 50L, 2, "req-h1");
        clock.advanceMillis(10L);
        handoff("T1", 3, "r3", 2500L, 150L, 3, "req-h2");
        clock.advanceMillis(10L);
        handoff("T2", 2, "s2", 1200L, 60L, 4, "req-h3");
        clock.advanceMillis(10L);
        handoff("T2", 3, "s3", 2000L, 70L, 5, "req-h4");

        // v6 -> v7 封榜
        RelayStandingResponse sealed = (RelayStandingResponse) raceService.sealRace(RACE,
                new SealRaceRequest(6, "req-seal")).body();
        assertThat(sealed.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealed.version()).isEqualTo(7);
        assertThat(sealed.entries()).extracting(RelayStandingResponse.Entry::teamKey)
                .containsExactly("T2", "T1");
        assertThat(sealed.entries().get(0).rank()).isEqualTo(1);
        assertThat(sealed.entries().get(1).rank()).isEqualTo(2);
        assertThat(sealed.entries().get(1).hasFouls()).isTrue();

        // 快照固化逐棒明细与犯规标记
        RelaySnapshotRow snapshot = repository.findRelaySnapshot(RACE).orElseThrow();
        assertThat(snapshot.teams()).hasSize(2);
        assertThat(snapshot.legs()).hasSize(6);
        assertThat(snapshot.legs().stream()
                .filter(l -> l.teamKey().equals("T1") && l.legNo() == 3)
                .findFirst()
                .orElseThrow()
                .foul()).isTrue();
        assertThat(snapshot.legs().stream()
                .filter(l -> l.teamKey().equals("T2") && l.legNo() == 2)
                .findFirst()
                .orElseThrow()
                .zoneMs()).isEqualTo(60L);

        // 封榜后查询走快照
        RelayStandingResponse snapshotView = relayService.getRelaySnapshot(RACE);
        assertThat(snapshotView.entries()).extracting(RelayStandingResponse.Entry::teamKey)
                .containsExactly("T2", "T1");
        RelayStandingResponse standing = relayService.getRelayStanding(RACE);
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);

        // 封榜后不可再提交交接
        assertThatThrownBy(() -> handoff("T1", 2, "r2", 3000L, 50L, 7, "req-h-after-seal"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已封榜");
    }

    @Test
    void 交接幂等_同键同参重放首次结果_异参409_失败不占键() {
        setupRelayRace();
        RelayHandoffResponse first = handoff("T1", 2, "r2", 1000L, 50L, 2, "req-idem");

        // 同键同参重放：返回首次结果，版本不再推进
        ServiceResult replayed = relayService.submitHandoff(RACE,
                new RelayHandoffRequest("T1", 2, "r2", 1000L, 50L, 2, "req-idem"));
        assertThat(replayed.status()).isEqualTo(201);
        JsonNode body = (JsonNode) replayed.body();
        assertThat(body.get("teamKey").asText()).isEqualTo("T1");
        assertThat(body.get("version").asInt()).isEqualTo(first.version());
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findRelayHandoffs(RACE, "T1")).hasSize(1);

        // 同键异参409
        assertThatThrownBy(() -> relayService.submitHandoff(RACE,
                new RelayHandoffRequest("T1", 2, "r2", 1000L, 60L, 2, "req-idem")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");

        // 失败不占键：422 失败后同一 requestId 可携带修正参数成功
        assertThatThrownBy(() -> handoff("T1", 3, "r3", 100L, 50L, 3, "req-fail"))
                .isInstanceOf(UnprocessableEntityException.class);
        clock.advanceMillis(10L);
        RelayHandoffResponse recovered = handoff("T1", 3, "r3", 2500L, 50L, 3, "req-fail");
        assertThat(recovered.finished()).isTrue();
    }
}
