package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RankingMode;
import com.example.starter.race.persistence.SnapshotRow;
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
 * COUNTBACK 排名主流程的 H2 数据库测试：合成 A/B/C 场景、封榜固化倒序段向量、
 * 封榜后写入409、旧键重放返回首次完整排名。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CountbackSealH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-cb-seal";

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

    /**
     * 合成场景：单检查点 cp1；A 累计40完赛100，B/C 累计60完赛100。
     * 版本推进：创建v1、配置v2、登记A/B/C到v5、分段A/B/C到v8。
     */
    private void seedCountbackRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "COUNTBACK", "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1)),
                1, "req-cfg"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 100L, 2, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("B", 100L, 3, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("C", 100L, 4, "req-c"));
        raceService.submitTiming(RACE, "A",
                new SubmitTimingRequest("t-a", "cp1", 40L, 5, "req-ta"));
        raceService.submitTiming(RACE, "B",
                new SubmitTimingRequest("t-b", "cp1", 60L, 6, "req-tb"));
        raceService.submitTiming(RACE, "C",
                new SubmitTimingRequest("t-c", "cp1", 60L, 7, "req-tc"));
    }

    @Test
    void 合成场景_B与C并列第一A第三_段向量正确() {
        seedCountbackRace();

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(standing.version()).isEqualTo(8);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "C", "A");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.RANKED);
        assertThat(standing.entries()).extracting(ResultEntryResponse::totalTimeMs)
                .containsExactly(100L, 100L, 100L);
        // 倒序段向量：末段在前；B/C 末段40首段60，A 末段60首段40
        assertThat(standing.entries().get(0).segmentMillisDesc())
                .containsExactly(40L, 60L);
        assertThat(standing.entries().get(1).segmentMillisDesc())
                .containsExactly(40L, 60L);
        assertThat(standing.entries().get(2).segmentMillisDesc())
                .containsExactly(60L, 40L);
    }

    @Test
    void 封榜冻结段向量与名次_后来写入409_旧键重放首次结果() {
        seedCountbackRace();

        SealRaceRequest seal = new SealRaceRequest(8, "req-seal");
        ServiceResult sealed = raceService.sealRace(RACE, seal);
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse sealedStanding = (StandingResponse) sealed.body();
        assertThat(sealedStanding.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStanding.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(sealedStanding.version()).isEqualTo(9);
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "C", "A");
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);

        // 快照库行固化了排名模式与倒序段向量
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.rankingMode()).isEqualTo(RankingMode.COUNTBACK);
        assertThat(snapshot.entries()).extracting(e -> e.bib() + ":" + e.rank())
                .containsExactly("B:1", "C:1", "A:3");
        assertThat(snapshot.entries().get(0).segmentMillisDesc())
                .containsExactly(40L, 60L);
        assertThat(snapshot.entries().get(2).segmentMillisDesc())
                .containsExactly(60L, 40L);
        // 快照查询与封榜响应一致
        assertThat(raceService.getSnapshot(RACE))
                .usingRecursiveComparison().isEqualTo(sealedStanding);
        assertThat(raceService.getResults(RACE))
                .usingRecursiveComparison().isEqualTo(sealedStanding);

        // 封榜后新写入 409
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("A", 50L, 9, "req-revise-late")))
                .isInstanceOf(ConflictException.class);

        // 已成功旧键重放仍给首次完整排名，不重新计算
        ServiceResult replay = raceService.sealRace(RACE, seal);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(json(replay.body())).isEqualTo(json(sealedStanding));
    }

    @Test
    void 默认TOTAL_TIME赛事算法不变_同耗时即并列且无段向量() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1)),
                1, "req-cfg"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 100L, 2, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("B", 100L, 3, "req-b"));
        raceService.submitTiming(RACE, "A",
                new SubmitTimingRequest("t-a", "cp1", 40L, 4, "req-ta"));
        raceService.submitTiming(RACE, "B",
                new SubmitTimingRequest("t-b", "cp1", 60L, 5, "req-tb"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.rankingMode()).isEqualTo(RankingMode.TOTAL_TIME);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1);
        assertThat(standing.entries()).extracting(ResultEntryResponse::segmentMillisDesc)
                .containsOnlyNulls();
    }
}
