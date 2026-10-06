package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SetRankingModeRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RankingMode;
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
 * COUNTBACK 排名模式的 H2 服务测试：模式变更门禁、版本与幂等语义、
 * 合成场景端到端排名与封榜冻结、旧键重放返回首次结果。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RankingModeH2Test extends AbstractRaceH2Test {

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
    void 新建赛事默认NORMAL_设置COUNTBACK成功且版本加一() {
        RaceResponse created = (RaceResponse) raceService.createRace(
                new CreateRaceRequest(RACE, "req-create")).body();
        assertThat(created.rankingMode()).isEqualTo(RankingMode.NORMAL);

        ServiceResult result = raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 1, "official-1", "req-mode"));
        assertThat(result.status()).isEqualTo(200);
        RaceResponse body = (RaceResponse) result.body();
        assertThat(body.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(body.version()).isEqualTo(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findRace(RACE).orElseThrow().rankingMode())
                .isEqualTo(RankingMode.COUNTBACK);
    }

    @Test
    void 已有分段记录后改模式409() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1)),
                1, "req-cp"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100L, 2, "req-a"));
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "cp1", 40L, 3, "req-t1"));

        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 4, "official-1", "req-mode")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("分段记录");
        assertThat(repository.findRace(RACE).orElseThrow().rankingMode())
                .isEqualTo(RankingMode.NORMAL);
    }

    @Test
    void 封榜后与版本不匹配改模式均409() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100L, 1, "req-a"));
        raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal"));

        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 3, "official-1", "req-mode-sealed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        raceService.createRace(new CreateRaceRequest("race-open", "req-create-2"));
        assertThatThrownBy(() -> raceService.setRankingMode("race-open",
                new SetRankingModeRequest("COUNTBACK", 9, "official-1", "req-mode-stale")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findRace("race-open").orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void 空操作者与未知模式均400() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 1, "  ", "req-mode-blank")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("操作者");
        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("FANCY", 1, "official-1", "req-mode-unknown")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("排名模式");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void 模式变更幂等_同参重放首次结果_异参409_失败不占键() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        SetRankingModeRequest request =
                new SetRankingModeRequest("COUNTBACK", 1, "official-1", "req-mode");

        ServiceResult first = raceService.setRankingMode(RACE, request);
        ServiceResult replay = raceService.setRankingMode(RACE, request);
        assertThat(first.status()).isEqualTo(200);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不重复推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 同键异参（操作者不同）409
        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 1, "official-2", "req-mode")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：错误版本失败后，同键正确参数可成功
        assertThatThrownBy(() -> raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 99, "official-1", "req-mode-2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-mode-2")).isEmpty();
        ServiceResult retry = raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 2, "official-1", "req-mode-2"));
        assertThat(retry.status()).isEqualTo(200);
    }

    @Test
    void 合成场景端到端_并列跳号_封榜冻结段向量_后续写409() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                1, "req-cp"));
        raceService.setRankingMode(RACE,
                new SetRankingModeRequest("COUNTBACK", 2, "official-1", "req-mode"));
        // 题干合成数据（分段提交要求累计耗时严格小于完赛耗时，故 cp2 记 99）：
        // a 累计40/99，b 累计60/99，c 与 b 相同；三人完赛均为100，总成绩均100
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100L, 3, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100L, 4, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", 100L, 5, "req-c"));
        submitTiming("a", "cp1", 40L, 6);
        submitTiming("a", "cp2", 99L, 7);
        submitTiming("b", "cp1", 60L, 8);
        submitTiming("b", "cp2", 99L, 9);
        submitTiming("c", "cp1", 60L, 10);
        submitTiming("c", "cp2", 99L, 11);

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "c", "a");
        assertThat(live.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
        assertThat(live.entries().get(0).segmentMillisDesc()).containsExactly(39L, 60L);
        assertThat(live.entries().get(2).segmentMillisDesc()).containsExactly(59L, 40L);
        assertThat(live.entries().get(2).totalTimeMs()).isEqualTo(100L);

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(12, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(sealed.body());
        // 封榜冻结：倒序段向量、净成绩与名次固化在快照中
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
        assertThat(snapshot.entries().get(0).segmentMillisDesc()).containsExactly(39L, 60L);
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);

        // 封榜后新写409
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("late", 1L, 13, "req-late")))
                .isInstanceOf(ConflictException.class);
        // 已成功旧键重放仍返回首次封榜响应
        ServiceResult sealReplay = raceService.sealRace(RACE, new SealRaceRequest(12, "req-seal"));
        assertThat(json(sealReplay.body())).isEqualTo(json(sealed.body()));
    }

    @Test
    void 旧键重放返回首次成功结果而非最新状态() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        RegisterRunnerRequest register = new RegisterRunnerRequest("a", 100L, 1, "req-reg-a");
        ServiceResult first = raceService.registerRunner(RACE, register);
        // 后续计时修订改变了选手当前状态
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 500L, 2, "req-revise"));

        ServiceResult replay = raceService.registerRunner(RACE, register);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(json(replay.body())).contains("\"finishTimeMs\":100");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    private void submitTiming(String bib, String code, long elapsed, int expectedVersion) {
        raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                "t-" + bib + "-" + code, code, elapsed, expectedVersion,
                "req-t-" + bib + "-" + code));
    }
}
