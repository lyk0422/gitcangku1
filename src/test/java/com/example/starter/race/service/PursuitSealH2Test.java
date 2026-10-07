package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EnablePursuitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RulingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitRulingRequest;
import com.example.starter.race.api.SubmitSamplesRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.SnapshotRulingRow;
import com.example.starter.race.persistence.SnapshotTrajectoryRow;
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
 * 追逐赛封榜与幂等的 H2 测试：封榜要求全部追逐对有当前裁定并冻结完整点与
 * 交会分数；写操作幂等重放、异参409、失败不占键。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PursuitSealH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit-seal";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SubmitSamplesRequest syntheticSamples(
            int expectedVersion, String requestId, long progressMm) {
        return new SubmitSamplesRequest(
                expectedVersion, 0,
                List.of(new SubmitSamplesRequest.SamplePointRequest(0L, 0L),
                        new SubmitSamplesRequest.SamplePointRequest(10_000L, progressMm)),
                "op-test", requestId);
    }

    /** 建赛、登记 a/b、启用 PURSUIT（版本到4）、提交双方合成轨迹（版本到6）。 */
    private int seedPursuitWithTrajectories() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 1000L)),
                "op-test", "req-enable"));
        raceService.submitSamples(RACE, "a", syntheticSamples(4, "req-sa", 3_000));
        raceService.submitSamples(RACE, "b", syntheticSamples(5, "req-sb", 2_000));
        return 6;
    }

    @Test
    void 封榜要求全部追逐对有当前裁定并冻结完整点与交会分数() {
        int version = seedPursuitWithTrajectories();
        // 缺少裁定 -> 409，赛事仍为 OPEN
        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("缺少当前有效裁定");
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);

        raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling"));
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(version + 1, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);

        // 完整点冻结：a/b 各2个采样点
        List<SnapshotTrajectoryRow> frozenTrajectories = repository.findSnapshotTrajectories(RACE);
        assertThat(frozenTrajectories).hasSize(4);
        assertThat(frozenTrajectories)
                .filteredOn(row -> row.bib().equals("a") && row.seq() == 1)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.trajectoryVersion()).isEqualTo(1);
                    assertThat(row.elapsedMs()).isEqualTo(10_000L);
                    assertThat(row.progressMm()).isEqualTo(3_000L);
                });
        // 交会分数冻结：精确有理数 10000/1
        List<SnapshotRulingRow> frozenRulings = repository.findSnapshotRulings(RACE);
        assertThat(frozenRulings).hasSize(1);
        SnapshotRulingRow frozen = frozenRulings.getFirst();
        assertThat(frozen.pairId()).isEqualTo("pair-1");
        assertThat(frozen.rulingId()).isEqualTo("ruling-1");
        assertThat(frozen.catchTimeNum()).isEqualTo("10000");
        assertThat(frozen.catchTimeDen()).isEqualTo("1");

        // 封榜后写入口全部关闭
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                syntheticSamples(version + 2, "req-sa2", 3_500)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-2", "pair-1", 1, 1, "op-test", "req-ruling-2")))
                .isInstanceOf(ConflictException.class);
        // 封榜后裁定仍可查询
        assertThat(raceService.getPursuitRulings(RACE).rulings()).hasSize(1);
    }

    @Test
    void 同键同参重放首次完整响应且不重复变更() {
        int version = seedPursuitWithTrajectories();
        SubmitRulingRequest rulingRequest = new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling");
        ServiceResult first = raceService.submitRuling(RACE, rulingRequest);
        ServiceResult replay = raceService.submitRuling(RACE, rulingRequest);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 版本只推进一次
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
        assertThat(raceService.getPursuitRulings(RACE).rulings()).hasSize(1);

        // 同键异参 -> 409
        assertThatThrownBy(() -> raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-other", "req-ruling")))
                .isInstanceOf(ConflictException.class);

        // rulingId 第二层幂等：不同 requestId 同 rulingId 同参 -> 重放原裁定
        ServiceResult replayByRulingId = raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling-other"));
        assertThat(replayByRulingId.status()).isEqualTo(201);
        assertThat(((RulingResponse) replayByRulingId.body()).rulingId()).isEqualTo("ruling-1");
        // 同 rulingId 异参 -> 409
        assertThatThrownBy(() -> raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 2, "op-test", "req-ruling-third")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 启用与采样同键异参409且失败不占键() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));

        // 启用失败（版本错误）不占 requestId
        EnablePursuitRequest enable = new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 1000L)),
                "op-test", "req-enable");
        EnablePursuitRequest wrongVersion = new EnablePursuitRequest(
                99, enable.pairs(), "op-test", "req-enable");
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, wrongVersion))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-enable")).isEmpty();
        ServiceResult enabled = raceService.enablePursuit(RACE, enable);
        assertThat(enabled.status()).isEqualTo(201);

        // 启用同键异参 -> 409
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 2_000L)),
                "op-test", "req-enable")))
                .isInstanceOf(ConflictException.class);

        // 采样同键异参 -> 409
        raceService.submitSamples(RACE, "a", syntheticSamples(4, "req-sa", 3_000));
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                syntheticSamples(4, "req-sa", 4_000)))
                .isInstanceOf(ConflictException.class);
    }
}
