package com.example.starter.race.service;

import com.example.starter.race.api.ChangeRankingModeRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.RankingMode;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排名模式变更的 H2 数据库测试：成功加一、计时后409、封榜后409、
 * 非法模式400、版本冲突409，以及 requestId 幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CountbackServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-countback";

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

    @Test
    void 新建赛事默认TOTAL_TIME且可显式指定COUNTBACK() {
        RaceResponse plain = (RaceResponse) raceService.createRace(
                new CreateRaceRequest(RACE, "req-create")).body();
        assertThat(plain.rankingMode()).isEqualTo(RankingMode.TOTAL_TIME);
        assertThat(raceService.getResults(RACE).rankingMode()).isEqualTo(RankingMode.TOTAL_TIME);

        RaceResponse countback = (RaceResponse) raceService.createRace(
                new CreateRaceRequest("race-cb", "COUNTBACK", "req-create-cb")).body();
        assertThat(countback.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(raceService.getResults("race-cb").rankingMode())
                .isEqualTo(RankingMode.COUNTBACK);

        // 非法模式 400，且不占用 requestId
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("race-bad", "FASTEST", "req-create-bad")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("排名模式");
        assertThat(repository.findIdempotency("req-create-bad")).isEmpty();
    }

    @Test
    void 变更排名模式成功且版本加一() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        ServiceResult changed = raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("COUNTBACK", 1, "req-mode-1"));
        assertThat(changed.status()).isEqualTo(200);
        RaceResponse body = (RaceResponse) changed.body();
        assertThat(body.version()).isEqualTo(2);
        assertThat(body.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 改回 TOTAL_TIME
        ServiceResult back = raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("TOTAL_TIME", 2, "req-mode-2"));
        assertThat(((RaceResponse) back.body()).version()).isEqualTo(3);
        assertThat(raceService.getResults(RACE).rankingMode()).isEqualTo(RankingMode.TOTAL_TIME);
    }

    @Test
    void 变更排名模式失败分支() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        // 非法模式 400
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("FASTEST", 1, "req-m-bad")))
                .isInstanceOf(BadRequestException.class);
        // 版本不匹配 409
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("COUNTBACK", 9, "req-m-stale")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        // 赛事不存在 404
        assertThatThrownBy(() -> raceService.changeRankingMode("ghost",
                new ChangeRankingModeRequest("COUNTBACK", 1, "req-m-ghost")))
                .isInstanceOf(NotFoundException.class);

        // 已有计时数据（选手已登记完赛耗时）后变更 409
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("COUNTBACK", 2, "req-m-timed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("计时数据");

        // 封榜后变更 409
        raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal"));
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("COUNTBACK", 3, "req-m-sealed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        // 全部失败均未推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 模式变更幂等_同参重放异参409失败不占键() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        ChangeRankingModeRequest request =
                new ChangeRankingModeRequest("COUNTBACK", 1, "req-mode");

        ServiceResult first = raceService.changeRankingMode(RACE, request);
        ServiceResult replay = raceService.changeRankingMode(RACE, request);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不重复推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 同键异参 409
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("TOTAL_TIME", 2, "req-mode")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：先以错误版本失败，再用同键正确参数成功
        assertThatThrownBy(() -> raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("TOTAL_TIME", 99, "req-mode-retry")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-mode-retry")).isEmpty();
        ServiceResult retry = raceService.changeRankingMode(RACE,
                new ChangeRankingModeRequest("TOTAL_TIME", 2, "req-mode-retry"));
        assertThat(retry.status()).isEqualTo(200);
        Optional<IdempotencyRow> row = repository.findIdempotency("req-mode-retry");
        assertThat(row).isPresent();
        assertThat(row.get().responseStatus()).isEqualTo(200);
        assertThat(row.get().operation()).isEqualTo("CHANGE_RANKING_MODE");
    }
}
