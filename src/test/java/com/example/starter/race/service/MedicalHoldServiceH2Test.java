package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ExcludedTimingsResponse;
import com.example.starter.race.api.MedicalHoldListResponse;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.MedicalHoldStatus;
import com.example.starter.race.domain.TimingHoldStatus;
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
 * 医疗暂停与成绩资格恢复的 H2 数据库测试：
 * 主流程、失败分支、幂等与提交顺序裁决、封榜快照固化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-mh";
    private static final long FIXED = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

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

    /** 建赛事 + 3 个检查点（cp1/cp2/cp3），返回当前版本。 */
    private int seedRaceWithCheckpoints() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2),
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp3", 3)), 1, "req-cps"));
        return 2;
    }

    private CheckpointTimingResponse submitTiming(
            String bib, String timingId, String checkpointCode, long elapsed, int version) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, checkpointCode, elapsed, version,
                        "req-timing-" + timingId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointTimingResponse) result.body();
    }

    private MedicalHoldResponse startHold(
            String bib, String holdId, long startMs, String role, int version) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(holdId, startMs, "扭伤", role, version,
                        "req-start-" + holdId));
        assertThat(result.status()).isEqualTo(201);
        return (MedicalHoldResponse) result.body();
    }

    private ResultEntryResponse entryOf(StandingResponse standing, String bib) {
        return standing.entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 主流程_暂停期间分段被排除_恢复后新计时重新参与排名() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100_000L, 3, "req-reg-b"));

        // v5: 暂停前分段正常计入
        CheckpointTimingResponse before = submitTiming("a", "t-1", "cp1", 1_000L, 4);
        assertThat(before.holdStatus()).isEqualTo(TimingHoldStatus.COUNTED);
        assertThat(before.excludedByHoldId()).isNull();

        // v6: 登记医疗暂停（声明区间 [FIXED-3000, FIXED-1000)，不含完赛登记时刻 FIXED）
        MedicalHoldResponse hold = startHold("a", "h-1", FIXED - 3_000L, "medic-1", 5);
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(hold.endMs()).isNull();
        assertThat(hold.durationMs()).isNull();
        assertThat(hold.reason()).isEqualTo("扭伤");
        assertThat(hold.startedBy()).isEqualTo("medic-1");
        assertThat(hold.startedAt()).isEqualTo(FIXED);

        // v7: 暂停生效期间的分段仍保存但标记 MEDICAL_HOLD，不参与排名
        CheckpointTimingResponse during = submitTiming("a", "t-2", "cp2", 2_000L, 6);
        assertThat(during.holdStatus()).isEqualTo(TimingHoldStatus.MEDICAL_HOLD);
        assertThat(during.excludedByHoldId()).isEqualTo("h-1");

        StandingResponse duringStanding = raceService.getResults(RACE);
        ResultEntryResponse aDuring = entryOf(duringStanding, "a");
        assertThat(aDuring.coveredCheckpointCount()).isEqualTo(1);
        assertThat(aDuring.excludedTimingCount()).isEqualTo(1);
        assertThat(aDuring.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(aDuring.missingCheckpoints()).containsExactly("cp2", "cp3");

        // v8: 不同医疗角色确认适赛，恢复成功并返回实际时长
        ServiceResult resumed = raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 7,
                        "req-resume-h-1"));
        assertThat(resumed.status()).isEqualTo(200);
        MedicalHoldResponse resumedHold = (MedicalHoldResponse) resumed.body();
        assertThat(resumedHold.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumedHold.endMs()).isEqualTo(FIXED - 1_000L);
        assertThat(resumedHold.durationMs()).isEqualTo(2_000L);
        assertThat(resumedHold.resumedBy()).isEqualTo("medic-2");
        assertThat(resumedHold.resumedAt()).isEqualTo(FIXED);
        assertThat(resumedHold.fitnessNote()).isEqualTo("确认适赛");

        // v9: 恢复后的新分段重新参与排名；暂停期分段仍被排除且不被删除
        CheckpointTimingResponse after = submitTiming("a", "t-3", "cp3", 3_000L, 8);
        assertThat(after.holdStatus()).isEqualTo(TimingHoldStatus.COUNTED);

        StandingResponse afterStanding = raceService.getResults(RACE);
        ResultEntryResponse aAfter = entryOf(afterStanding, "a");
        assertThat(aAfter.coveredCheckpointCount()).isEqualTo(2);
        assertThat(aAfter.excludedTimingCount()).isEqualTo(1);
        assertThat(aAfter.missingCheckpoints()).containsExactly("cp2");

        // 明细查询：三条分段都保留，资格标记稳定
        RunnerTimingResponse timings = raceService.getRunnerTimings(RACE, "a");
        assertThat(timings.checkpoints()).extracting(CheckpointPassResponse::holdStatus)
                .containsExactly(
                        TimingHoldStatus.COUNTED,
                        TimingHoldStatus.MEDICAL_HOLD,
                        TimingHoldStatus.COUNTED);

        // 历史查询：恢复记录完整固化
        MedicalHoldListResponse history = raceService.getRunnerMedicalHolds(RACE, "a");
        assertThat(history.holds()).hasSize(1);
        assertThat(history.holds().getFirst().status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(history.holds().getFirst().durationMs()).isEqualTo(2_000L);

        // 诊断查询：被排除分段及原因
        ExcludedTimingsResponse excluded = raceService.getExcludedTimings(RACE);
        assertThat(excluded.excludedTimings()).hasSize(1);
        assertThat(excluded.excludedTimings().getFirst().timingId()).isEqualTo("t-2");
        assertThat(excluded.excludedTimings().getFirst().holdId()).isEqualTo("h-1");
        assertThat(excluded.excludedTimings().getFirst().reason()).isEqualTo("扭伤");

        // 查询不改变状态：版本保持 9
        assertThat(raceService.getResults(RACE).version()).isEqualTo(9);
    }

    @Test
    void 重复开始生效中暂停返回409且不带入新记录() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        startHold("a", "h-1", FIXED - 1_000L, "medic-1", 3);

        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-2", FIXED, "抽筋", "medic-2", 4, "req-start-h-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("h-1");

        // 未写入第二条暂停，版本未推进
        assertThat(raceService.getRunnerMedicalHolds(RACE, "a").holds()).hasSize(1);
        assertThat(raceService.getResults(RACE).version()).isEqualTo(4);
    }

    @Test
    void 恢复校验_结束必须晚于开始且须不同医疗角色() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        startHold("a", "h-1", FIXED - 1_000L, "medic-1", 3);

        // 结束等于开始 -> 400
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 4, "req-r-1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("晚于开始");
        // 结束早于开始 -> 400
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 2_000L, "确认适赛", "medic-2", 4, "req-r-2")))
                .isInstanceOf(BadRequestException.class);
        // 同一医疗角色 -> 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 1_000L, "确认适赛", "medic-1", 4, "req-r-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同医疗角色");

        // 失败均不占键、不改状态：暂停仍生效，版本未推进
        MedicalHoldResponse hold = raceService.getRunnerMedicalHolds(RACE, "a").holds().getFirst();
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(raceService.getResults(RACE).version()).isEqualTo(4);
    }

    @Test
    void 恢复不存在或已恢复的暂停返回对应错误() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "ghost",
                new ResumeMedicalHoldRequest(FIXED + 1_000L, "确认适赛", "medic-2", 3, "req-r-g")))
                .isInstanceOf(NotFoundException.class);

        startHold("a", "h-1", FIXED - 3_000L, "medic-1", 3);
        raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 4, "req-r-1"));

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 2_000L, "再次确认", "medic-3", 5, "req-r-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void 已取消资格选手不可开始或恢复暂停() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100_000L, 3, "req-reg-b"));
        startHold("a", "h-1", FIXED - 1_000L, "medic-1", 4);

        // v6: a 被取消资格
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "a", "DISQUALIFY", null, 5, "req-dq-a"));

        // 恢复 -> 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 1_000L, "确认适赛", "medic-2", 6, "req-r-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        // v7: b 被取消资格后开始暂停 -> 409
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq-b", "b", "DISQUALIFY", null, 6, "req-dq-b"));
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("h-2", FIXED, "扭伤", "medic-1", 7, "req-start-h-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");
    }

    @Test
    void 封榜后不可开始或恢复暂停() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        startHold("a", "h-1", FIXED - 1_000L, "medic-1", 3);
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));

        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-2", FIXED, "扭伤", "medic-1", 5, "req-start-h-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 1_000L, "确认适赛", "medic-2", 5, "req-r-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 恢复时暂停区间内存在终点计时返回422且状态不变() {
        seedRaceWithCheckpoints();
        // c 登记时无完赛耗时（finishTimeMs 为 null 不触发终点计时检查）
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", null, 2, "req-reg-c"));
        startHold("c", "h-1", FIXED - 1_000L, "medic-1", 3);

        // 暂停期间登记终点计时（updatedAt=FIXED，落在 [FIXED-1000, FIXED+1000) 内）
        raceService.reviseTime(RACE, new ReviseTimeRequest("c", 90_000L, 4, "req-time-c"));

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "c", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 1_000L, "确认适赛", "medic-2", 5, "req-r-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("终点计时");

        // 422 不改变状态：暂停仍生效，版本未推进，幂等键未占用
        MedicalHoldResponse hold = raceService.getRunnerMedicalHolds(RACE, "c").holds().getFirst();
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(raceService.getResults(RACE).version()).isEqualTo(5);
        assertThat(repository.findIdempotency("req-r-1")).isEmpty();

        // 区间右开：结束时刻等于终点计时登记时刻时不视为“暂停期间”
        ServiceResult resumed = raceService.resumeMedicalHold(RACE, "c", "h-1",
                new ResumeMedicalHoldRequest(FIXED, "确认适赛", "medic-2", 5, "req-r-2"));
        assertThat(resumed.status()).isEqualTo(200);
    }

    @Test
    void 暂停开始前已有的终点计时不阻断恢复() {
        seedRaceWithCheckpoints();
        // 完赛耗时在暂停开始前已登记（updatedAt=FIXED < startMs）
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        startHold("a", "h-1", FIXED + 1_000L, "medic-1", 3);

        ServiceResult resumed = raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED + 2_000L, "确认适赛", "medic-2", 4, "req-r-1"));
        assertThat(resumed.status()).isEqualTo(200);
    }

    @Test
    void 提交顺序裁决_计时先提交按当时资格处理_暂停先提交则同刻后计时排除() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));

        // 计时先提交：无生效暂停，COUNTED
        CheckpointTimingResponse first = submitTiming("a", "t-1", "cp1", 1_000L, 3);
        assertThat(first.holdStatus()).isEqualTo(TimingHoldStatus.COUNTED);

        // 暂停先提交：同刻（固定时钟）后续计时标 MEDICAL_HOLD
        startHold("a", "h-1", FIXED, "medic-1", 4);
        CheckpointTimingResponse second = submitTiming("a", "t-2", "cp2", 2_000L, 5);
        assertThat(second.holdStatus()).isEqualTo(TimingHoldStatus.MEDICAL_HOLD);

        // 先提交的计时资格不被后续暂停追溯改写
        RunnerTimingResponse timings = raceService.getRunnerTimings(RACE, "a");
        assertThat(timings.checkpoints()).extracting(CheckpointPassResponse::holdStatus)
                .containsExactly(TimingHoldStatus.COUNTED, TimingHoldStatus.MEDICAL_HOLD, null);
    }

    @Test
    void 幂等_同键同参重放首次结果_异参409_失败不占键() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));

        // 失败不占键：版本冲突失败后同 requestId 可重试成功
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", FIXED, "扭伤", "medic-1", 99, "req-start-h-1")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-start-h-1")).isEmpty();

        StartMedicalHoldRequest start =
                new StartMedicalHoldRequest("h-1", FIXED - 2_000L, "扭伤", "medic-1", 3,
                        "req-start-h-1");
        ServiceResult first = raceService.startMedicalHold(RACE, "a", start);
        ServiceResult replay = raceService.startMedicalHold(RACE, "a", start);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(raceService.getRunnerMedicalHolds(RACE, "a").holds()).hasSize(1);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-9", FIXED - 2_000L, "扭伤", "medic-1", 3,
                        "req-start-h-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // holdId 第二层幂等：同参（不同 requestId）重放原结果
        ServiceResult holdReplay = raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", FIXED - 2_000L, "扭伤", "medic-1", 4,
                        "req-start-h-1b"));
        assertThat(holdReplay.status()).isEqualTo(201);
        assertThat(json(holdReplay.body())).isEqualTo(json(first.body()));
        // holdId 异参 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", FIXED + 5_000L, "抽筋", "medic-2", 4,
                        "req-start-h-1c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("holdId");

        // 恢复幂等：同键同参重放
        raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 4, "req-r-1"));
        ServiceResult resumeReplay = raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 4, "req-r-1"));
        assertThat(resumeReplay.status()).isEqualTo(200);
        assertThat(raceService.getRunnerMedicalHolds(RACE, "a").holds()).hasSize(1);
    }

    @Test
    void 封榜快照固化资格状态与排除原因且不随后续变化() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100_000L, 3, "req-reg-b"));
        submitTiming("a", "t-1", "cp1", 1_000L, 4);
        startHold("a", "h-1", FIXED - 3_000L, "medic-1", 5);
        submitTiming("a", "t-2", "cp2", 2_000L, 6);
        raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(FIXED - 1_000L, "确认适赛", "medic-2", 7, "req-r-1"));
        submitTiming("a", "t-3", "cp3", 3_000L, 8);
        submitTiming("b", "t-4", "cp1", 1_000L, 9);
        submitTiming("b", "t-5", "cp2", 2_000L, 10);
        submitTiming("b", "t-6", "cp3", 3_000L, 11);

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(12, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = raceService.getSnapshot(RACE);

        // 快照条目：a 缺 cp2（被排除），b 完整覆盖
        ResultEntryResponse aEntry = entryOf(snapshot, "a");
        assertThat(aEntry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(aEntry.excludedTimingCount()).isEqualTo(1);
        assertThat(aEntry.coveredCheckpointCount()).isEqualTo(2);
        ResultEntryResponse bEntry = entryOf(snapshot, "b");
        assertThat(bEntry.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(bEntry.excludedTimingCount()).isZero();

        // 快照分段明细：排除原因与来源暂停固化
        RunnerTimingResponse aTimings = raceService.getRunnerTimings(RACE, "a");
        CheckpointPassResponse cp2 = aTimings.checkpoints().stream()
                .filter(pass -> pass.checkpointCode().equals("cp2"))
                .findFirst()
                .orElseThrow();
        assertThat(cp2.holdStatus()).isEqualTo(TimingHoldStatus.MEDICAL_HOLD);
        assertThat(cp2.excludedByHoldId()).isEqualTo("h-1");
        assertThat(cp2.exclusionReason()).isEqualTo("扭伤");

        // 封榜结果不受后续任何查询影响，重复读取一致
        assertThat(raceService.getResults(RACE)).usingRecursiveComparison().isEqualTo(snapshot);
        assertThat(raceService.getSnapshot(RACE)).usingRecursiveComparison().isEqualTo(snapshot);
        MedicalHoldListResponse history = raceService.getRaceMedicalHolds(RACE);
        assertThat(history.holds()).hasSize(1);
        assertThat(history.holds().getFirst().status()).isEqualTo(MedicalHoldStatus.RESUMED);
    }

    @Test
    void 查询接口对不存在对象返回404() {
        seedRaceWithCheckpoints();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 100_000L, 2, "req-reg-a"));

        assertThatThrownBy(() -> raceService.getRunnerMedicalHolds(RACE, "ghost"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRaceMedicalHolds("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getExcludedTimings("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "ghost",
                new StartMedicalHoldRequest("h-1", FIXED, "扭伤", "medic-1", 3, "req-start-h-1")))
                .isInstanceOf(NotFoundException.class);
    }
}
