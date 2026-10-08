package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureStagesRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseStageTimingRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StageResultsResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitStageTimingRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.StageStatus;
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
 * 阶段（STAGES）赛事的 H2 数据库测试：合成关门场景主流程、封榜冻结、
 * 拒绝分支、幂等与处罚共用版本。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class StageServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "stage-race";

    @Autowired
    private RaceService raceService;

    @Autowired
    private StageService stageService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 建赛并登记若干无单日计时的选手，返回当前版本。 */
    private int createRaceWithRunners(String raceId, String... bibs) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        int version = 1;
        for (String bib : bibs) {
            raceService.registerRunner(raceId,
                    new RegisterRunnerRequest(bib, null, version, "req-reg-" + raceId + "-" + bib));
            version++;
        }
        return version;
    }

    /** 配置两个各含一个检查点 A 的阶段，返回配置后版本。 */
    private int configureTwoStages(String raceId, int expectedVersion) {
        ServiceResult result = stageService.configureStages(raceId, new ConfigureStagesRequest(
                "req-stages-" + raceId, "referee", expectedVersion,
                List.of(
                        new ConfigureStagesRequest.StageDefinition(1, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1))),
                        new ConfigureStagesRequest.StageDefinition(2, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1))))));
        assertThat(result.status()).isEqualTo(201);
        return expectedVersion + 1;
    }

    private void submitStageTiming(
            String raceId, int stageNo, String bib, String timingId, long elapsed, int version) {
        submitStageTiming(raceId, stageNo, bib, timingId, "A", elapsed, version);
    }

    private void submitStageTiming(
            String raceId, int stageNo, String bib, String timingId,
            String checkpointCode, long elapsed, int version) {
        ServiceResult result = stageService.submitStageTiming(raceId, stageNo, bib,
                new SubmitStageTimingRequest(
                        "req-" + timingId, "timer", timingId, checkpointCode, elapsed, version));
        assertThat(result.status()).isEqualTo(201);
    }

    @Test
    void 合成场景_关门线与最快更正重算_封榜冻结() {
        int version = createRaceWithRunners(RACE, "110", "111", "112");
        version = configureTwoStages(RACE, version);

        // 阶段1：110=101(F)、111=111(允许)、112=112(OUT)
        submitStageTiming(RACE, 1, "110", "t-s1-110", 101L, version++);
        submitStageTiming(RACE, 1, "111", "t-s1-111", 111L, version++);
        submitStageTiming(RACE, 1, "112", "t-s1-112", 112L, version++);
        // 阶段2：三人同为200，均完整
        submitStageTiming(RACE, 2, "110", "t-s2-110", 200L, version++);
        submitStageTiming(RACE, 2, "111", "t-s2-111", 200L, version++);
        submitStageTiming(RACE, 2, "112", "t-s2-112", 200L, version++);

        StageResultsResponse stages = stageService.getStageResults(RACE);
        assertThat(stages.stages()).hasSize(2);
        StageResultsResponse.StageResultView stage1 = stages.stages().getFirst();
        assertThat(stage1.fastestMs()).isEqualTo(101L);
        assertThat(stage1.entries()).extracting(StageResultsResponse.StageEntryView::bib)
                .containsExactly("110", "111", "112");
        assertThat(stage1.entries()).extracting(StageResultsResponse.StageEntryView::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.COMPLETE, StageStatus.OUT);

        StandingResponse overall = raceService.getResults(RACE);
        assertThat(overall.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("110", "111", "112");
        assertThat(overall.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.OUT);
        assertThat(overall.entries().get(0).totalTimeMs()).isEqualTo(301L);
        assertThat(overall.entries().get(1).totalTimeMs()).isEqualTo(311L);

        // 最快者阶段1计时更正 101 -> 100：F 重算为100，111 失去资格变为 OUT
        ServiceResult revised = stageService.reviseStageTiming(RACE, 1, "t-s1-110",
                new ReviseStageTimingRequest("req-revise-s1-110", "timer", 100L, version++));
        assertThat(revised.status()).isEqualTo(200);

        StageResultsResponse afterRevise = stageService.getStageResults(RACE);
        assertThat(afterRevise.stages().getFirst().fastestMs()).isEqualTo(100L);
        assertThat(afterRevise.stages().getFirst().entries())
                .extracting(StageResultsResponse.StageEntryView::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.OUT, StageStatus.OUT);

        StandingResponse overallAfterRevise = raceService.getResults(RACE);
        assertThat(overallAfterRevise.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.OUT, EntryStatus.OUT);
        assertThat(overallAfterRevise.entries().get(0).bib()).isEqualTo("110");
        assertThat(overallAfterRevise.entries().get(0).totalTimeMs()).isEqualTo(300L);

        // 封榜：冻结每阶段F、资格与总成绩
        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version++, "req-seal-" + RACE));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse sealedStanding = (StandingResponse) sealed.body();
        assertThat(sealedStanding.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStanding.sealedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.OUT, EntryStatus.OUT);

        StageResultsResponse sealedStages = stageService.getStageResults(RACE);
        assertThat(sealedStages.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStages.stages().getFirst().fastestMs()).isEqualTo(100L);
        assertThat(raceService.getResults(RACE))
                .usingRecursiveComparison().isEqualTo(sealedStanding);
        assertThat(raceService.getSnapshot(RACE))
                .usingRecursiveComparison().isEqualTo(sealedStanding);

        // 封榜后阶段写入口全部409
        int sealedVersion = version;
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "110",
                new SubmitStageTimingRequest("req-late", "timer", "t-late", "A", 1L,
                        sealedVersion)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stageService.reviseStageTiming(RACE, 1, "t-s1-110",
                new ReviseStageTimingRequest("req-late-2", "timer", 99L, sealedVersion)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 阶段无完整选手不能封榜422且版本不变() {
        int version = createRaceWithRunners(RACE, "a");
        version = configureTwoStages(RACE, version);
        // 仅阶段1有完整记录，阶段2无完整选手
        submitStageTiming(RACE, 1, "a", "t-s1-a", 100L, version++);
        int versionBeforeSeal = version;

        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(versionBeforeSeal, "req-seal-" + RACE)))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("stageNo=2");

        assertThat(raceService.getResults(RACE).version()).isEqualTo(versionBeforeSeal);
        assertThat(raceService.getResults(RACE).status()).isEqualTo(RaceStatus.OPEN);
    }

    @Test
    void 阶段配置参数非法返回400() {
        int version = createRaceWithRunners(RACE, "a");
        // 阶段数越界：1个与6个
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-c1", "referee", version, List.of(singleStage(1)))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-c2", "referee", version,
                List.of(singleStage(1), singleStage(2), singleStage(3),
                        singleStage(4), singleStage(5), singleStage(6)))))
                .isInstanceOf(BadRequestException.class);
        // 阶段序号缺口
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-c3", "referee", version, List.of(singleStage(1), singleStage(3)))))
                .isInstanceOf(BadRequestException.class);
        // 阶段内检查点代码重复
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-c4", "referee", version, List.of(
                        new ConfigureStagesRequest.StageDefinition(1, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1),
                                new ConfigureStagesRequest.CheckpointDefinition("A", 2))),
                        singleStage(2)))))
                .isInstanceOf(BadRequestException.class);
        // 阶段内检查点顺序缺口
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-c5", "referee", version, List.of(
                        new ConfigureStagesRequest.StageDefinition(1, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 2))),
                        singleStage(2)))))
                .isInstanceOf(BadRequestException.class);
        assertThat(raceService.getResults(RACE).version()).isEqualTo(version);
    }

    private static ConfigureStagesRequest.StageDefinition singleStage(int stageNo) {
        return new ConfigureStagesRequest.StageDefinition(stageNo, List.of(
                new ConfigureStagesRequest.CheckpointDefinition("A", 1)));
    }

    @Test
    void 已有计时数据或单日配置的赛事不能再配置阶段() {
        // 单日赛事已有分段记录
        int version = createRaceWithRunners(RACE, "a");
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 1000L, version++, "req-time-a"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("A", 1)),
                version++, "req-cp-" + RACE));
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "A", 100L, version++, "req-t-1"));

        int currentVersion = version;
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-stages-late", "referee", currentVersion,
                List.of(singleStage(1), singleStage(2)))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 重复配置阶段与跨模式入口互斥() {
        int version = createRaceWithRunners(RACE, "a");
        version = configureTwoStages(RACE, version);

        // 重复配置阶段
        int stagesVersion = version;
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-stages-again", "referee", stagesVersion,
                List.of(singleStage(1), singleStage(2)))))
                .isInstanceOf(ConflictException.class);
        // 单日入口在阶段赛事上被拒绝
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(
                        List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("A", 1)),
                        stagesVersion, "req-cp-single")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-x", "A", 100L, stagesVersion, "req-t-x")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 1000L, stagesVersion, "req-rev-single")))
                .isInstanceOf(ConflictException.class);

        // 阶段入口在单日赛事上被拒绝
        String singleRace = "single-race";
        int singleVersion = createRaceWithRunners(singleRace, "a");
        assertThatThrownBy(() -> stageService.submitStageTiming(singleRace, 1, "a",
                new SubmitStageTimingRequest("req-st-x", "timer", "t-st-x", "A", 100L,
                        singleVersion)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stageService.getStageResults(singleRace))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 阶段提交与更正的存在性与顺序校验() {
        int version = createRaceWithRunners(RACE, "a");
        // 两阶段：阶段1含两个检查点 A、B
        stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-stages-" + RACE, "referee", version++,
                List.of(
                        new ConfigureStagesRequest.StageDefinition(1, List.of(
                                new ConfigureStagesRequest.CheckpointDefinition("A", 1),
                                new ConfigureStagesRequest.CheckpointDefinition("B", 2))),
                        singleStage(2))));

        // 阶段不存在 / 检查点不存在 / 选手不存在
        int v1 = version;
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 9, "a",
                new SubmitStageTimingRequest("req-s9", "timer", "t-s9", "A", 1L, v1)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-badcp", "timer", "t-badcp", "ZZ", 1L, v1)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "ghost",
                new SubmitStageTimingRequest("req-ghost", "timer", "t-ghost", "A", 1L, v1)))
                .isInstanceOf(NotFoundException.class);

        // 乱序允许但耗时必须严格递增：先提交 B=200，再提交 A=300 违反顺序
        submitStageTiming(RACE, 1, "a", "t-b", "B", 200L, version++);
        int v2 = version;
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-a300", "timer", "t-a300", "A", 300L, v2)))
                .isInstanceOf(UnprocessableEntityException.class);
        // 同一检查点重复提交
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-b2", "timer", "t-b2", "B", 250L, v2)))
                .isInstanceOf(UnprocessableEntityException.class);
        // 更正破坏邻序：先补 A=100，再将 B 更正为 50 则违反递增
        submitStageTiming(RACE, 1, "a", "t-a", "A", 100L, version++);
        int v3 = version;
        assertThatThrownBy(() -> stageService.reviseStageTiming(RACE, 1, "t-b",
                new ReviseStageTimingRequest("req-rev-b50", "timer", 50L, v3)))
                .isInstanceOf(UnprocessableEntityException.class);
        // 更正不存在的记录
        assertThatThrownBy(() -> stageService.reviseStageTiming(RACE, 1, "t-nope",
                new ReviseStageTimingRequest("req-rev-nope", "timer", 500L, v3)))
                .isInstanceOf(NotFoundException.class);
        // 合法更正成功
        ServiceResult ok = stageService.reviseStageTiming(RACE, 1, "t-b",
                new ReviseStageTimingRequest("req-rev-b300", "timer", 300L, version++));
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    void 幂等_同参重放异参409失败不占键() {
        int version = createRaceWithRunners(RACE, "a");
        ConfigureStagesRequest configure = new ConfigureStagesRequest(
                "req-stages-idem", "referee", version, List.of(singleStage(1), singleStage(2)));
        ServiceResult first = stageService.configureStages(RACE, configure);
        ServiceResult replay = stageService.configureStages(RACE, configure);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        version++;
        assertThat(raceService.getResults(RACE).version()).isEqualTo(version);

        // 同键异参409（操作者不同也算异参）
        int configuredVersion = version;
        assertThatThrownBy(() -> stageService.configureStages(RACE, new ConfigureStagesRequest(
                "req-stages-idem", "other", configuredVersion,
                List.of(singleStage(1), singleStage(2)))))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：先以错误版本提交分段，再同键正确重试
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-t-idem", "timer", "t-idem", "A", 100L, 999)))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-t-idem")).isEmpty();
        ServiceResult retried = stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-t-idem", "timer", "t-idem", "A", 100L, version));
        assertThat(retried.status()).isEqualTo(201);
        version++;

        // timingId 第二层幂等：同参重放（不同 requestId），异参409
        ServiceResult replayed = stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-t-idem-2", "timer", "t-idem", "A", 100L,
                        version));
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(retried.body()));
        int afterSubmit = version;
        assertThatThrownBy(() -> stageService.submitStageTiming(RACE, 1, "a",
                new SubmitStageTimingRequest("req-t-idem-3", "timer", "t-idem", "A", 101L,
                        afterSubmit)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 处罚与撤销共用版本_仅影响总成绩不影响F() {
        int version = createRaceWithRunners(RACE, "a", "b");
        version = configureTwoStages(RACE, version);
        submitStageTiming(RACE, 1, "a", "t-s1-a", 100L, version++);
        submitStageTiming(RACE, 1, "b", "t-s1-b", 105L, version++);
        submitStageTiming(RACE, 2, "a", "t-s2-a", 100L, version++);
        submitStageTiming(RACE, 2, "b", "t-s2-b", 100L, version++);

        // 加时50：a 总成绩 200->250，b 升为第1；F 与阶段资格不变
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", 50L, version++, "req-p1"));
        StandingResponse afterPenalty = raceService.getResults(RACE);
        assertThat(afterPenalty.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(afterPenalty.entries().get(1).totalTimeMs()).isEqualTo(250L);
        assertThat(stageService.getStageResults(RACE).stages().getFirst().fastestMs())
                .isEqualTo(100L);

        // 撤销后恢复
        raceService.revokePenalty(RACE, "p-1", new RevokePenaltyRequest(version++, "req-rv1"));
        StandingResponse restored = raceService.getResults(RACE);
        assertThat(restored.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(restored.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);

        // 取消资格不排名，撤销后恢复
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "a", "DISQUALIFY", null, version++, "req-p2"));
        StandingResponse dq = raceService.getResults(RACE);
        assertThat(dq.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.DISQUALIFIED);
        raceService.revokePenalty(RACE, "p-2", new RevokePenaltyRequest(version++, "req-rv2"));
        assertThat(raceService.getResults(RACE).entries())
                .extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED);
    }

    @Test
    void 总榜精确并列跳号() {
        int version = createRaceWithRunners(RACE, "a", "b", "c");
        version = configureTwoStages(RACE, version);
        submitStageTiming(RACE, 1, "a", "t-s1-a", 100L, version++);
        submitStageTiming(RACE, 1, "b", "t-s1-b", 100L, version++);
        submitStageTiming(RACE, 1, "c", "t-s1-c", 105L, version++);
        submitStageTiming(RACE, 2, "a", "t-s2-a", 100L, version++);
        submitStageTiming(RACE, 2, "b", "t-s2-b", 100L, version++);
        submitStageTiming(RACE, 2, "c", "t-s2-c", 100L, version++);

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
    }
}
