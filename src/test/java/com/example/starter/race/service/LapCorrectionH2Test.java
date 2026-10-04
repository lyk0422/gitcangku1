package com.example.starter.race.service;

import com.example.starter.race.api.CorrectLapRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LapHistoryEntryResponse;
import com.example.starter.race.api.LapHistoryResponse;
import com.example.starter.race.api.LapPassResponse;
import com.example.starter.race.api.LapPointResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RunnerLapsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitLapRequest;
import com.example.starter.race.domain.LapPoint;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 绕圈赛事圈次更正、历史与幂等重放的 H2 数据库测试。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class LapCorrectionH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-loop-correct";

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void createLoopAndRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "LOOP", 2, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, version(), "req-reg-a"));
    }

    private LapPassResponse submit(int lap, String point, long elapsed) {
        String id = "lp-" + lap + point;
        ServiceResult result = raceService.submitLap(RACE, "a",
                new SubmitLapRequest(id, lap, point, elapsed, "timer-1", version(), "req-" + id));
        assertThat(result.status()).isEqualTo(201);
        return (LapPassResponse) result.body();
    }

    @Test
    void 更正引用最近有效记录并追加历史不覆盖原读数() {
        createLoopAndRunner();
        LapPassResponse first = submit(1, "A", 10_000);
        submit(1, "B", 20_000);

        int expectedAtCorrection = version();
        ServiceResult corrected = raceService.correctLap(RACE, "a",
                new CorrectLapRequest(first.lapPassId(), "lp-1A-v2", 15_000L, "official-1",
                        expectedAtCorrection, "req-correct-1"));
        assertThat(corrected.status()).isEqualTo(201);
        LapPassResponse current = (LapPassResponse) corrected.body();
        assertThat(current.lapPassId()).isEqualTo("lp-1A-v2");
        assertThat(current.elapsedMillis()).isEqualTo(15_000L);
        assertThat(current.createdBy()).isEqualTo("official-1");

        // 每点只保留一条当前记录：更正后该点为新记录
        RunnerLapsResponse laps = raceService.getRunnerLaps(RACE, "a");
        assertThat(laps.laps())
                .filteredOn(lap -> lap.lapNo() == 1 && lap.point() == LapPoint.A)
                .singleElement()
                .satisfies(lap -> {
                    assertThat(lap.lapPassId()).isEqualTo("lp-1A-v2");
                    assertThat(lap.elapsedMillis()).isEqualTo(15_000L);
                });

        // 历史保留原读数，不被覆盖
        LapHistoryResponse history = raceService.getLapHistory(RACE, "a");
        assertThat(history.entries()).hasSize(1);
        LapHistoryEntryResponse entry = history.entries().getFirst();
        assertThat(entry.lapPassId()).isEqualTo(first.lapPassId());
        assertThat(entry.replacedBy()).isEqualTo("lp-1A-v2");
        assertThat(entry.elapsedMillis()).isEqualTo(10_000L);
        assertThat(entry.createdBy()).isEqualTo("timer-1");
        assertThat(entry.correctedBy()).isEqualTo("official-1");

        // 引用已被更正的旧记录 -> 409
        assertThatThrownBy(() -> raceService.correctLap(RACE, "a",
                new CorrectLapRequest(first.lapPassId(), "lp-1A-v3", 16_000L, "op",
                        version(), "req-correct-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("最近有效");
        // 引用不存在的记录 -> 404
        assertThatThrownBy(() -> raceService.correctLap(RACE, "a",
                new CorrectLapRequest("lp-ghost", "lp-1A-v3", 16_000L, "op",
                        version(), "req-correct-3")))
                .isInstanceOf(NotFoundException.class);
        // 更正造成逆序（须严格小于 B 的 20000）-> 422
        assertThatThrownBy(() -> raceService.correctLap(RACE, "a",
                new CorrectLapRequest("lp-1A-v2", "lp-1A-v3", 20_000L, "op",
                        version(), "req-correct-4")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 首次更正请求重放：返回当时响应，不附后来的任何变化
        ServiceResult replay = raceService.correctLap(RACE, "a",
                new CorrectLapRequest(first.lapPassId(), "lp-1A-v2", 15_000L, "official-1",
                        expectedAtCorrection, "req-correct-1"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(corrected.body()));
        // 重放未产生新的历史
        assertThat(raceService.getLapHistory(RACE, "a").entries()).hasSize(1);
    }

    @Test
    void 历史按圈号和点序可查() {
        createLoopAndRunner();
        LapPassResponse lap2A = submit(2, "A", 70_000);
        LapPassResponse lap1A = submit(1, "A", 40_000);
        // 先更正第2圈，再更正第1圈：历史仍按圈号与点序排列
        raceService.correctLap(RACE, "a",
                new CorrectLapRequest(lap2A.lapPassId(), "lp-2A-v2", 71_000L, "op",
                        version(), "req-c2"));
        raceService.correctLap(RACE, "a",
                new CorrectLapRequest(lap1A.lapPassId(), "lp-1A-v2", 41_000L, "op",
                        version(), "req-c1"));

        LapHistoryResponse history = raceService.getLapHistory(RACE, "a");
        assertThat(history.entries()).extracting(LapHistoryEntryResponse::lapNo)
                .containsExactly(1, 2);
        assertThat(history.entries()).extracting(LapHistoryEntryResponse::elapsedMillis)
                .containsExactly(40_000L, 70_000L);
        assertThat(history.entries()).extracting(LapHistoryEntryResponse::replacedBy)
                .containsExactly("lp-1A-v2", "lp-2A-v2");
    }

    @Test
    void 圈次提交幂等_同键同参重放异参409失败不占键() {
        createLoopAndRunner();
        SubmitLapRequest request = new SubmitLapRequest(
                "lp-1A", 1, "A", 10_000L, "timer-1", version(), "req-lap-1");
        ServiceResult first = raceService.submitLap(RACE, "a", request);
        ServiceResult replay = raceService.submitLap(RACE, "a", request);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(repository.findLapPassesForRunner(RACE, "a")).hasSize(1);

        // 同 requestId 异参（操作者不同也算异参）-> 409
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-1A", 1, "A", 10_000L, "other-op",
                        version(), "req-lap-1")))
                .isInstanceOf(ConflictException.class);
        // 同 lapPassId 异参（不同 requestId）-> 409
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-1A", 1, "A", 11_000L, "timer-1",
                        version(), "req-lap-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lapPassId");
        assertThat(repository.findLapPassesForRunner(RACE, "a")).hasSize(1);

        // 参数含分隔符样字符不造成身份碰撞：不同操作者各自成功
        ServiceResult op1 = raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-1B", 1, "B", 20_000L, "op|1:A",
                        version(), "req-op-1"));
        assertThat(op1.status()).isEqualTo(201);
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-1B", 1, "B", 20_000L, "op|1:B",
                        version(), "req-op-1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜后拒绝新圈记录与更正且旧请求重放当时状态() {
        createLoopAndRunner();
        SubmitLapRequest firstRequest = new SubmitLapRequest(
                "lp-1A", 1, "A", 40_000L, "timer-1", version(), "req-lap-first");
        ServiceResult firstResult = raceService.submitLap(RACE, "a", firstRequest);
        assertThat(firstResult.status()).isEqualTo(201);
        submit(1, "B", 50_000);
        submit(1, "FINISH", 60_000);
        submit(2, "A", 70_000);
        submit(2, "B", 80_000);
        submit(2, "FINISH", 120_000);
        raceService.sealRace(RACE, new SealRaceRequest(version(), "req-seal"));

        // 封榜后拒绝新圈记录与更正
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-new", 1, "A", 41_000L, "op", version(), "req-new")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.correctLap(RACE, "a",
                new CorrectLapRequest("lp-1A", "lp-1A-v2", 41_000L, "op",
                        version(), "req-corr")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 旧成功请求重放：原样返回首次响应，不附后来补齐的点
        ServiceResult replayed = raceService.submitLap(RACE, "a", firstRequest);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(firstResult.body()));
        LapPassResponse replayedBody =
                objectMapper.convertValue(replayed.body(), LapPassResponse.class);
        assertThat(replayedBody.lapPassId()).isEqualTo("lp-1A");
        assertThat(replayedBody.elapsedMillis()).isEqualTo(40_000L);
    }
}