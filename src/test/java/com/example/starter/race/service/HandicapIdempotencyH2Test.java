package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.HandicapFactorResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SelectScoringModeRequest;
import com.example.starter.race.api.UpsertHandicapFactorRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 让分赛写操作的幂等边界 H2 测试：
 * 同键同参重放首次完整响应、等比例系数视为同参、异参409、失败不占键。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class HandicapIdempotencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-idem";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private void createHandicapRaceWithRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 999L, 2, "req-a"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void 同键同参重放首次完整响应且版本不再推进() {
        createHandicapRaceWithRunner();
        ServiceResult first = raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 3, "req-f"));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        ServiceResult replayed = raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 3, "req-f"));
        assertThat(replayed.status()).isEqualTo(first.status());
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 等比例系数按约分后的值视为同参() {
        createHandicapRaceWithRunner();
        // 首次以 2/6 登记，响应保留首次字段（约分后的 1/3）
        ServiceResult first = raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(2, 6, 3, "req-f"));
        HandicapFactorResponse body = (HandicapFactorResponse) first.body();
        assertThat(body.p()).isEqualTo(1);
        assertThat(body.q()).isEqualTo(3);

        // 同键以等比例 1/3 重放：视为同参，返回首次完整响应，版本不推进
        ServiceResult replayed = raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 3, "req-f"));
        assertThat(replayed.status()).isEqualTo(first.status());
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 同键异参（不同比例的系数）-> 409
        assertThatThrownBy(() -> raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 4, 3, "req-f")))
                .isInstanceOf(ConflictException.class);
        // 同键异参（不同期望版本）-> 409
        assertThatThrownBy(() -> raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(2, 6, 4, "req-f")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 失败不占键_同键可再次成功提交() {
        createHandicapRaceWithRunner();
        // 版本错误导致业务失败，requestId 不应被占用
        assertThatThrownBy(() -> raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 99, "req-f")))
                .isInstanceOf(ConflictException.class);

        ServiceResult succeeded = raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 3, "req-f"));
        assertThat(succeeded.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().factorP()).isEqualTo(1);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().factorQ()).isEqualTo(3);
    }

    @Test
    void 选择模式同样幂等() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        ServiceResult first = raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode"));
        ServiceResult replayed = raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode"));
        assertThat(replayed.status()).isEqualTo(first.status());
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 同键异参 -> 409
        assertThatThrownBy(() -> raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 2, "req-mode")))
                .isInstanceOf(ConflictException.class);
    }
}
