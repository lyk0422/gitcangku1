package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicatePursuitRequest;
import com.example.starter.race.api.AdjudicationResponse;
import com.example.starter.race.api.ConfigurePursuitRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.PursuitSnapshotResponse;
import com.example.starter.race.api.PursuitStateResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitTrajectoryRequest;
import com.example.starter.race.api.TrajectoryResponse;
import com.example.starter.race.domain.RaceMode;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.PursuitRepository;
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
 * 追逐赛（PURSUIT）的 H2 数据库测试：启用、采样更正、裁定、封榜冻结与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PursuitServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit";

    @Autowired
    private RaceService raceService;

    @Autowired
    private PursuitRepository pursuitRepository;

    /** 建赛并登记 a、b 两名选手（无完赛耗时），返回当前版本3。 */
    private int createRaceWithRunners() {
        return createRaceWithRunners(RACE);
    }

    private int createRaceWithRunners(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b-" + raceId));
        return 3;
    }

    /** 启用 PURSUIT：追逐对1 = a 追 b，起始 0 / 1000 毫米。 */
    private int configurePursuit(String raceId, int expectedVersion) {
        raceService.configurePursuit(raceId, new ConfigurePursuitRequest(
                List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                        1, "a", "b", 0L, 1000L)),
                expectedVersion, "req-pursuit-" + raceId, "op-config"));
        return expectedVersion + 1;
    }

    private int submitTrajectory(String raceId, String bib, int trajectoryVersion,
                                 int expectedVersion, long distanceMm, String requestId) {
        ServiceResult result = raceService.submitTrajectory(raceId, bib,
                new SubmitTrajectoryRequest(
                        List.of(new SubmitTrajectoryRequest.SamplePoint(10_000L, distanceMm)),
                        trajectoryVersion, expectedVersion, requestId, "op-traj"));
        assertThat(result.status()).isEqualTo(201);
        return expectedVersion + 1;
    }

    @Test
    void 主流程_启用采样裁定到封榜冻结() {
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.mode()).isEqualTo(RaceMode.PURSUIT);

        // 合成用例：A 起始0、B 起始1000毫米；10秒时 A 前进3000、B 前进2000。
        version = submitTrajectory(RACE, "a", 0, version, 3_000L, "req-traj-a");
        version = submitTrajectory(RACE, "b", 0, version, 2_000L, "req-traj-b");

        ServiceResult adjudicated = raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, version, "req-adj", "op-judge"));
        assertThat(adjudicated.status()).isEqualTo(201);
        AdjudicationResponse adjudication = (AdjudicationResponse) adjudicated.body();
        // 最早交会 10 秒，精确有理数 10000/1。
        assertThat(adjudication.catchTimeNum()).isEqualTo(10_000L);
        assertThat(adjudication.catchTimeDen()).isEqualTo(1L);
        assertThat(adjudication.chaserTrajectoryVersion()).isEqualTo(1);
        assertThat(adjudication.chasedTrajectoryVersion()).isEqualTo(1);
        version++;

        PursuitStateResponse state = raceService.getPursuitState(RACE);
        assertThat(state.pairs()).hasSize(1);
        assertThat(state.trajectories()).hasSize(2);
        assertThat(state.adjudications()).hasSize(1);

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);

        // 封榜冻结：完整轨迹点与交会分数。
        PursuitSnapshotResponse snapshot = raceService.getPursuitSnapshot(RACE);
        assertThat(snapshot.pairs()).hasSize(1);
        assertThat(snapshot.pairs().getFirst().catchTimeNum()).isEqualTo(10_000L);
        assertThat(snapshot.pairs().getFirst().catchTimeDen()).isEqualTo(1L);
        assertThat(snapshot.pairs().getFirst().chaserStartMm()).isEqualTo(0L);
        assertThat(snapshot.pairs().getFirst().chasedStartMm()).isEqualTo(1000L);
        assertThat(snapshot.samples()).hasSize(2);
        assertThat(snapshot.samples())
                .anySatisfy(sample -> {
                    assertThat(sample.bib()).isEqualTo("a");
                    assertThat(sample.timeMs()).isEqualTo(10_000L);
                    assertThat(sample.distanceMm()).isEqualTo(3_000L);
                })
                .anySatisfy(sample -> {
                    assertThat(sample.bib()).isEqualTo("b");
                    assertThat(sample.distanceMm()).isEqualTo(2_000L);
                });
    }

    @Test
    void 未交会返回422且不写入裁定() {
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);
        // A 在10秒时仅前进2999毫米：公共区间内追不上。
        version = submitTrajectory(RACE, "a", 0, version, 2_999L, "req-traj-a");
        version = submitTrajectory(RACE, "b", 0, version, 2_000L, "req-traj-b");

        int finalVersion = version;
        assertThatThrownBy(() -> raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, finalVersion, "req-adj", "op-judge")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未交会");

        assertThat(pursuitRepository.findAdjudications(RACE)).isEmpty();
        // 失败请求随事务回滚：赛事版本不变，且 requestId 不占键。
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(finalVersion);
        assertThat(repository.findIdempotency("req-adj")).isEmpty();
    }

    @Test
    void 轨迹更正使旧裁定失效且封榜须重新裁定() {
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);
        version = submitTrajectory(RACE, "a", 0, version, 3_000L, "req-traj-a");
        version = submitTrajectory(RACE, "b", 0, version, 2_000L, "req-traj-b");
        raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, version, "req-adj", "op-judge"));
        version++;
        assertThat(pursuitRepository.findAdjudications(RACE)).hasSize(1);

        // 更正追赶者轨迹：轨迹版本 1 -> 2，旧裁定同事务失效。
        version = submitTrajectory(RACE, "a", 1, version, 3_100L, "req-traj-a2");
        assertThat(pursuitRepository.findAdjudications(RACE)).isEmpty();
        TrajectoryResponse corrected = (TrajectoryResponse) raceService.getPursuitState(RACE)
                .trajectories().stream().filter(t -> t.bib().equals("a")).findFirst().orElseThrow();
        assertThat(corrected.trajectoryVersion()).isEqualTo(2);

        // 缺少当前裁定不能封榜。
        int sealVersion = version;
        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(sealVersion, "req-seal-early")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("缺少当前裁定");

        // 用旧轨迹版本裁定：409 不混版本。
        int adjVersion = version;
        assertThatThrownBy(() -> raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, adjVersion, "req-adj-stale", "op-judge")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("轨迹版本冲突");

        // 按当前轨迹版本重新裁定后即可封榜。
        raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 2, 1, version, "req-adj-2", "op-judge"));
        version++;
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        PursuitSnapshotResponse snapshot = raceService.getPursuitSnapshot(RACE);
        assertThat(snapshot.pairs().getFirst().chaserTrajectoryVersion()).isEqualTo(2);
    }
// APPEND_MARKER
    @Test
    void 幂等_同参重放原响应_异参409_失败不占键() {
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);
        version = submitTrajectory(RACE, "a", 0, version, 3_000L, "req-traj-a");
        version = submitTrajectory(RACE, "b", 0, version, 2_000L, "req-traj-b");

        ServiceResult first = raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, version, "req-adj", "op-judge"));
        // 同参重放：返回首次完整响应，赛事版本不再推进。
        ServiceResult replayed = raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, version, "req-adj", "op-judge"));
        assertThat(replayed.status()).isEqualTo(first.status());
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);

        // 同键异参：409。
        int adjudicatedVersion = version + 1;
        assertThatThrownBy(() -> raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, adjudicatedVersion, "req-adj", "op-judge")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");

        // 失败不占键：更正 A 轨迹为 2999 后，用 "req-fail" 提交未交会的裁定。
        version++;
        version = submitTrajectory(RACE, "a", 1, version, 2_999L, "req-traj-a2");
        int failVersion = version;
        assertThatThrownBy(() -> raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 2, 1, failVersion, "req-fail", "op-judge")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findIdempotency("req-fail")).isEmpty();
        // 更正回可交会轨迹后，同一 requestId 可成功使用。
        version = submitTrajectory(RACE, "a", 2, version, 3_000L, "req-traj-a3");
        ServiceResult reused = raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 3, 1, version, "req-fail", "op-judge"));
        assertThat(reused.status()).isEqualTo(201);
    }

    @Test
    void 追逐赛禁止原单条成绩入口() {
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);

        int finalVersion = version;
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", 1_000L, finalVersion, "req-reg-c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("禁止直接录入完赛耗时");
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 1_000L, finalVersion, "req-revise-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("禁止直接修订完赛耗时");
        // 无耗时登记选手仍允许（追逐对需要已登记选手）。
        ServiceResult registered = raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", null, finalVersion, "req-reg-c2"));
        assertThat(registered.status()).isEqualTo(201);
    }

    @Test
    void 启用前置_已开始计时或重复启用均409() {
        String raceId = "race-pursuit-late";
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-late"));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", 5_000L, 1, "req-reg-a-late"));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b-late"));
        // 已有完赛耗时：不能启用 PURSUIT。
        assertThatThrownBy(() -> raceService.configurePursuit(raceId,
                new ConfigurePursuitRequest(
                        List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                                1, "a", "b", 0L, 1000L)),
                        3, "req-pursuit-late", "op-config")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已开始计时");

        // 重复启用：409。
        int version = createRaceWithRunners();
        version = configurePursuit(RACE, version);
        int finalVersion = version;
        assertThatThrownBy(() -> raceService.configurePursuit(RACE,
                new ConfigurePursuitRequest(
                        List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                                1, "a", "b", 0L, 1000L)),
                        finalVersion, "req-pursuit-again", "op-config")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已启用PURSUIT");
    }

    @Test
    void 追逐对与采样参数校验() {
        int version = createRaceWithRunners();
        // 起始距离为负：400。
        int finalVersion = version;
        assertThatThrownBy(() -> raceService.configurePursuit(RACE,
                new ConfigurePursuitRequest(
                        List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                                1, "a", "b", -1L, 1000L)),
                        finalVersion, "req-pursuit-neg", "op-config")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("非负");
        // 同一追逐对两人相同：400。
        assertThatThrownBy(() -> raceService.configurePursuit(RACE,
                new ConfigurePursuitRequest(
                        List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                                1, "a", "a", 0L, 1000L)),
                        finalVersion, "req-pursuit-same", "op-config")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不能相同");
        // 未登记选手：404。
        assertThatThrownBy(() -> raceService.configurePursuit(RACE,
                new ConfigurePursuitRequest(
                        List.of(new ConfigurePursuitRequest.PursuitPairDefinition(
                                1, "a", "ghost", 0L, 1000L)),
                        finalVersion, "req-pursuit-ghost", "op-config")))
                .isInstanceOf(NotFoundException.class);

        version = configurePursuit(RACE, version);
        // 首点时刻必须为正（起点固定为(0,0)）。
        int trajVersion = version;
        assertThatThrownBy(() -> raceService.submitTrajectory(RACE, "a",
                new SubmitTrajectoryRequest(
                        List.of(new SubmitTrajectoryRequest.SamplePoint(0L, 0L)),
                        0, trajVersion, "req-traj-zero", "op-traj")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("严格递增");
        // 距离不减。
        assertThatThrownBy(() -> raceService.submitTrajectory(RACE, "a",
                new SubmitTrajectoryRequest(
                        List.of(new SubmitTrajectoryRequest.SamplePoint(1_000L, 500L),
                                new SubmitTrajectoryRequest.SamplePoint(2_000L, 400L)),
                        0, trajVersion, "req-traj-dec", "op-traj")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不减");
        // 超过10点。
        assertThatThrownBy(() -> raceService.submitTrajectory(RACE, "a",
                new SubmitTrajectoryRequest(
                        java.util.stream.IntStream.rangeClosed(1, 11)
                                .mapToObj(i -> new SubmitTrajectoryRequest.SamplePoint(
                                        (long) i * 1_000L, (long) i))
                                .toList(),
                        0, trajVersion, "req-traj-many", "op-traj")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1~10");
    }

    @Test
    void 多个追逐对须全部裁定才能封榜() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", null, 3, "req-c"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("d", null, 4, "req-d"));
        int version = 5;
        raceService.configurePursuit(RACE, new ConfigurePursuitRequest(
                List.of(new ConfigurePursuitRequest.PursuitPairDefinition(1, "a", "b", 0L, 1000L),
                        new ConfigurePursuitRequest.PursuitPairDefinition(2, "c", "d", 0L, 500L)),
                version, "req-pursuit", "op-config"));
        version++;
        version = submitTrajectory(RACE, "a", 0, version, 3_000L, "req-ta");
        version = submitTrajectory(RACE, "b", 0, version, 2_000L, "req-tb");
        version = submitTrajectory(RACE, "c", 0, version, 1_000L, "req-tc");
        version = submitTrajectory(RACE, "d", 0, version, 500L, "req-td");
        raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(1, 1, 1, version, "req-adj-1", "op-judge"));
        version++;

        // 追逐对2未裁定：封榜422。
        int sealVersion = version;
        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(sealVersion, "req-seal")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("缺少当前裁定");

        raceService.adjudicatePursuit(RACE,
                new AdjudicatePursuitRequest(2, 1, 1, version, "req-adj-2", "op-judge"));
        version++;
        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version, "req-seal-2"));
        assertThat(sealed.status()).isEqualTo(200);
        PursuitSnapshotResponse snapshot = raceService.getPursuitSnapshot(RACE);
        assertThat(snapshot.pairs()).hasSize(2);
        // 追逐对2：c 每毫秒0.1毫米，d 每毫秒0.05毫米，起始差500 -> t = 500/0.05 = 10000。
        assertThat(snapshot.pairs().get(1).catchTimeNum()).isEqualTo(10_000L);
        assertThat(snapshot.pairs().get(1).catchTimeDen()).isEqualTo(1L);
    }

    private String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
