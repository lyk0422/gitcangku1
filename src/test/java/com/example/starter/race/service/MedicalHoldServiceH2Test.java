package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MedicalHoldResumptionResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RunnerMedicalDiagnosisResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import com.example.starter.race.support.MutableClockTestConfig.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 医疗暂停能力的真实 H2 数据库测试：
 * 主流程（暂停-排除分段-恢复-新计时入榜-封榜固化）、左闭右开边界、
 * 失败分支（409/422/400/404）、退赛/取消资格/封榜约束、终点计时422、
 * requestId 幂等与原始证据保留。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med";
    private static final String MED_A = "medic-a";
    private static final String MED_B = "medic-b";

    @Autowired
    private RaceService raceService;

    @Autowired
    private MutableClock clock;

    /** 当前赛事版本，辅助构造 expectedVersion。 */
    private int version;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        version = 1;
    }

    private void register(String bib, Long finishTimeMs, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, version, reqId));
        version++;
    }

    private void configureCheckpoints(String... codes) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(codes[i], i + 1));
        }
        CheckpointsConfigResponse response = (CheckpointsConfigResponse) raceService
                .configureCheckpoints(RACE,
                        new ConfigureCheckpointsRequest(defs, version, "req-cfg"))
                .body();
        assertThat(response.version()).isEqualTo(version + 1);
        version++;
    }

    private CheckpointTimingResponse submit(
            String bib, String timingId, String code, long elapsed, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version, reqId));
        assertThat(result.status()).isEqualTo(201);
        version++;
        return (CheckpointTimingResponse) result.body();
    }

    private long holdStart(long offsetMs) {
        return clock.instant().toEpochMilli() + offsetMs;
    }

    private void startHold(String holdId, String bib, long startAt, String officer, String reqId) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(
                        holdId, startAt, "cramp-treatment", officer, version, reqId));
        assertThat(result.status()).isEqualTo(201);
        version++;
    }

    private MedicalHoldResumptionResponse resume(
            String holdId, long endAt, String officer, String conclusion, String reqId) {
        ServiceResult result = raceService.resumeMedicalHold(RACE, holdId,
                new ResumeMedicalHoldRequest(endAt, officer, conclusion, version, reqId));
        assertThat(result.status()).isEqualTo(200);
        version++;
        return (MedicalHoldResumptionResponse) result.body();
    }

    private ResultEntryResponse entryOf(StandingResponse standing, String bib) {
        return standing.entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 完整主流程_暂停排除分段恢复后新计时入榜且封榜固化() {
        createRace();
        register("a", 10_000L, "req-a");
        configureCheckpoints("cp1", "cp2", "cp3");

        // 暂停前 cp1 正常计时
        CheckpointTimingResponse cp1 = submit("a", "t-cp1", "cp1", 1_000, "req-t1");
        assertThat(cp1.medicalHold()).isFalse();

        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-hold-start");

        // 暂停开始后提交的 cp2 可保存但标 MEDICAL_HOLD，不参与临时排名
        clock.advance(Duration.ofSeconds(60));
        CheckpointTimingResponse cp2 = submit("a", "t-cp2", "cp2", 2_000, "req-t2");
        assertThat(cp2.medicalHold()).isTrue();
        assertThat(cp2.excludedHoldId()).isEqualTo("hold-1");

        StandingResponse duringHold = raceService.getResults(RACE);
        ResultEntryResponse entryDuring = entryOf(duringHold, "a");
        assertThat(entryDuring.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entryDuring.rank()).isNull();
        assertThat(entryDuring.coveredCheckpointCount()).isEqualTo(1);
        assertThat(entryDuring.medicalHoldActive()).isTrue();
        assertThat(entryDuring.excludedTimingCount()).isEqualTo(1);

        // 不同医疗角色确认适赛恢复，返回实际时长
        clock.advance(Duration.ofSeconds(30));
        long endAt = startAt + 60_000;
        MedicalHoldResumptionResponse resumption = resume(
                "hold-1", endAt, MED_B, "fit-to-race", "req-hold-resume");
        assertThat(resumption.durationMs()).isEqualTo(60_000L);
        assertThat(resumption.resumedBy()).isEqualTo(MED_B);

        // 恢复后新计时正常参与排名；区间内 cp2 永久排除，cp2 无有效覆盖故仍漏点不排名
        clock.advance(Duration.ofSeconds(30));
        CheckpointTimingResponse cp3 = submit("a", "t-cp3", "cp3", 3_000, "req-t3");
        assertThat(cp3.medicalHold()).isFalse();

        StandingResponse afterResume = raceService.getResults(RACE);
        ResultEntryResponse entryAfter = entryOf(afterResume, "a");
        assertThat(entryAfter.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entryAfter.rank()).isNull();
        assertThat(entryAfter.coveredCheckpointCount()).isEqualTo(2);
        assertThat(entryAfter.missingCheckpoints()).containsExactly("cp2");
        assertThat(entryAfter.medicalHoldActive()).isFalse();
        assertThat(entryAfter.excludedTimingCount()).isEqualTo(1);

        // 原始证据不删除：明细仍含 cp2，且带排除暂停ID与原因
        RunnerTimingResponse timingDetail = raceService.getRunnerTimings(RACE, "a");
        CheckpointPassResponse cp2Detail = timingDetail.checkpoints().stream()
                .filter(pass -> pass.checkpointCode().equals("cp2")).findFirst().orElseThrow();
        assertThat(cp2Detail.elapsedMillis()).isEqualTo(2_000);
        assertThat(cp2Detail.timingId()).isEqualTo("t-cp2");
        assertThat(cp2Detail.medicalHold()).isTrue();
        assertThat(cp2Detail.excludedHoldId()).isEqualTo("hold-1");
        assertThat(cp2Detail.excludedReason()).isEqualTo("cramp-treatment");

        // 历史与诊断查询只读
        MedicalHoldHistoryResponse history = raceService.getMedicalHoldHistory(RACE);
        assertThat(history.holds()).hasSize(1);
        assertThat(history.holds().getFirst().active()).isFalse();
        assertThat(history.holds().getFirst().durationMs()).isEqualTo(60_000);
        assertThat(history.holds().getFirst().excludedTimingCount()).isEqualTo(1);
        assertThat(history.holds().getFirst().fitConclusion()).isEqualTo("fit-to-race");

        RunnerMedicalDiagnosisResponse diagnosis =
                raceService.getRunnerMedicalDiagnosis(RACE, "a");
        assertThat(diagnosis.entryStatus()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(diagnosis.activeHoldId()).isNull();
        assertThat(diagnosis.excludedTimings()).hasSize(1);
        assertThat(diagnosis.excludedTimings().getFirst().timingId()).isEqualTo("t-cp2");
        assertThat(diagnosis.finishEventsInHold()).isEmpty();

        // 封榜快照固化资格状态与排除原因
        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        version++;
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        ResultEntryResponse snapshotEntry = entryOf(snapshot, "a");
        assertThat(snapshotEntry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(snapshotEntry.excludedTimingCount()).isEqualTo(1);
        assertThat(snapshotEntry.medicalHoldActive()).isFalse();
        RunnerTimingResponse snapshotTiming = raceService.getRunnerTimings(RACE, "a");
        CheckpointPassResponse snapshotCp2 = snapshotTiming.checkpoints().stream()
                .filter(pass -> pass.checkpointCode().equals("cp2")).findFirst().orElseThrow();
        assertThat(snapshotCp2.medicalHold()).isTrue();
        assertThat(snapshotCp2.excludedReason()).isEqualTo("cramp-treatment");
    }

    @Test
    void 暂停先提交则同刻后计时不入榜_终点事件恰在右端点不阻断恢复() {
        createRace();
        register("a", 10_000L, "req-a");
        configureCheckpoints("cp1", "cp2", "cp3", "cp4");

        // cp1 在暂停开始之前提交，按当时资格正常入榜
        submit("a", "t-cp1", "cp1", 1_000, "req-t1");

        long startAt = holdStart(60_000);
        long endAt = startAt + 60_000;
        startHold("hold-1", "a", startAt, MED_A, "req-hold-start");

        // 恰在 startAt 同刻提交分段：暂停先提交，同刻后计时不可入榜，标 MEDICAL_HOLD
        clock.advance(Duration.ofSeconds(60));
        assertThat(clock.instant().toEpochMilli()).isEqualTo(startAt);
        CheckpointTimingResponse cp2 = submit("a", "t-cp2", "cp2", 2_000, "req-t2");
        assertThat(cp2.excludedHoldId()).isEqualTo("hold-1");

        // 时钟走到恰为 endAt 时修订一次终点计时：区间右开，createdAt==endAt 不属于暂停期间
        clock.advance(Duration.ofSeconds(60));
        assertThat(clock.instant().toEpochMilli()).isEqualTo(endAt);
        raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 9_500L, version, "req-revise-at-end"));
        version++;

        // 恢复成功：右端点同刻的终点事件不触发422
        resume("hold-1", endAt, MED_B, "fit", "req-hold-resume");

        // 恢复后新计时正常入榜；区间内 cp2 保持排除
        CheckpointTimingResponse cp3 = submit("a", "t-cp3", "cp3", 3_000, "req-t3");
        assertThat(cp3.medicalHold()).isFalse();

        ResultEntryResponse entry = entryOf(raceService.getResults(RACE), "a");
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entry.coveredCheckpointCount()).isEqualTo(2);
        // cp2 在区间内被排除（无有效覆盖），cp4 尚未通过
        assertThat(entry.missingCheckpoints()).containsExactly("cp2", "cp4");
        assertThat(entry.excludedTimingCount()).isEqualTo(1);
    }

    @Test
    void 终点计时恰在左闭端点阻断恢复要求先裁定() {
        createRace();
        register("a", 10_000L, "req-a");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");

        // 时钟恰走到 startAt（左闭端点）时修订终点计时，createdAt==startAt 属于暂停期间
        clock.advance(Duration.ofSeconds(60));
        raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 9_000L, version, "req-revise-at-start"));
        version++;

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 60_000, MED_B, "fit", version, "req-resume")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("1 条终点计时");
    }

    @Test
    void 暂停期间无分段恢复后通过全部检查点可正常排名() {
        createRace();
        register("a", 10_000L, "req-a");
        register("b", 12_000L, "req-b");
        configureCheckpoints("cp1", "cp2");

        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");
        clock.advance(Duration.ofSeconds(90));
        // 暂停期间不提交任何分段，直接恢复（区间内也无终点计时）
        resume("hold-1", startAt + 60_000, MED_B, "fit", "req-resume");

        // 暂停结束后的新计时全部参与排名
        submit("a", "t-a1", "cp1", 1_000, "req-a1");
        submit("a", "t-a2", "cp2", 2_000, "req-a2");
        submit("b", "t-b1", "cp1", 1_000, "req-b1");
        submit("b", "t-b2", "cp2", 2_000, "req-b2");

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        ResultEntryResponse a = entryOf(standing, "a");
        assertThat(a.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(a.rank()).isEqualTo(1);
        assertThat(a.coveredCheckpointCount()).isEqualTo(2);
        assertThat(a.excludedTimingCount()).isZero();
        assertThat(a.medicalHoldActive()).isFalse();
    }

    @Test
    void 重复开始生效暂停409恢复后可再开始新暂停() {
        createRace();
        register("a", 10_000L, "req-a");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start-1");

        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest(
                        "hold-2", startAt, "again", MED_B, version, "req-start-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("生效中的医疗暂停");
        // 失败不留半成品：第二条暂停证据不存在
        assertThat(repository.findMedicalHold("hold-2")).isEmpty();

        resume("hold-1", startAt + 1_000, MED_B, "fit", "req-resume-1");

        // 恢复后同一选手可开始新的暂停区间
        clock.advance(Duration.ofSeconds(5));
        startHold("hold-3", "a", holdStart(1_000), MED_A, "req-start-3");
        assertThat(raceService.getMedicalHoldHistory(RACE).holds()).hasSize(2);
    }

    @Test
    void 开始暂停_已取消资格409_已退赛409_暂停ID重复409() {
        createRace();
        register("a", 10_000L, "req-a");
        register("b", 10_000L, "req-b");
        register("c", 10_000L, "req-c");
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "b", "DISQUALIFY", null, version, "req-dq"));
        version++;
        raceService.withdrawRunner(RACE, "c",
                new WithdrawRunnerRequest("injury", version, "req-wd-c"));
        version++;

        long startAt = holdStart(60_000);
        // 已取消资格
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest(
                        "hold-b", startAt, "x", MED_A, version, "req-hold-b")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");
        // 已退赛
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "c",
                new StartMedicalHoldRequest(
                        "hold-c", startAt, "x", MED_A, version, "req-hold-c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        // 选手不存在
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "ghost",
                new StartMedicalHoldRequest(
                        "hold-g", startAt, "x", MED_A, version, "req-hold-g")))
                .isInstanceOf(NotFoundException.class);

        // a 正常开始
        startHold("hold-a", "a", startAt, MED_A, "req-hold-a");
        // 生效期间同选手再次开始（无论ID）先因“同时仅一条生效暂停”409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest(
                        "hold-a", startAt, "x", MED_B, version, "req-hold-a-dup")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("生效中的医疗暂停");

        // 恢复后同一 holdId 不得复用：撞开始事件主键409
        resume("hold-a", startAt + 1_000, MED_B, "fit", "req-resume-a");
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest(
                        "hold-a", holdStart(1_000), "x", MED_A, version, "req-hold-a-reuse")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("医疗暂停ID已存在");
    }

    @Test
    void 恢复失败_同角色422_结束不晚于开始400_不存在404_重复恢复409() {
        createRace();
        register("a", 10_000L, "req-a");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");

        // endAt 不晚于 startAt：400，信息含实际/要求/差额
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt, MED_B, "fit", version, "req-resume-bad-time")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("差额=0毫秒");

        // 同一医疗角色确认：422
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_A, "fit", version, "req-resume-same")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不同于登记人");

        // 暂停不存在：404
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-x",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_B, "fit", version, "req-resume-missing")))
                .isInstanceOf(NotFoundException.class);

        // 正常恢复
        resume("hold-1", startAt + 1_000, MED_B, "fit", "req-resume-ok");

        // 重复恢复：409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 2_000, MED_B, "fit-again", version, "req-resume-twice")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void 恢复时暂停期间存在终点计时返回422要求先裁定() {
        createRace();
        register("a", 10_000L, "req-a");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");

        // 暂停期间修订终点完赛耗时（既有终点计时事件）
        clock.advance(Duration.ofSeconds(70));
        raceService.reviseTime(RACE, new ReviseTimeRequest(
                "a", 9_000L, version, "req-revise-in-hold"));
        version++;

        long endAt = startAt + 120_000;
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        endAt, MED_B, "fit", version, "req-resume-blocked")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("终点计时")
                .hasMessageContaining("裁定完赛状态");

        // 诊断能看到该区间内终点事件
        RunnerMedicalDiagnosisResponse diagnosis =
                raceService.getRunnerMedicalDiagnosis(RACE, "a");
        assertThat(diagnosis.finishEventsInHold()).hasSize(1);
        assertThat(diagnosis.finishEventsInHold().getFirst().finishTimeMs()).isEqualTo(9_000L);
        assertThat(diagnosis.finishEventsInHold().getFirst().requestId())
                .isEqualTo("req-revise-in-hold");

        // 按既有规则裁定为退赛后，不可再恢复（资格终态），选手状态 WITHDRAWN
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("adjudicated-dnf", version, "req-adjudicate"));
        version++;
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        endAt, MED_B, "fit", version, "req-resume-after-adjudicate")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        assertThat(entryOf(raceService.getResults(RACE), "a").status())
                .isEqualTo(EntryStatus.WITHDRAWN);
    }

    @Test
    void 退赛及封榜后不可恢复暂停() {
        createRace();
        register("a", 10_000L, "req-a");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");

        // 封榜：暂停中也允许封榜，快照固化 active
        raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal"));
        version++;
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_B, "fit", version, "req-resume-sealed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 退赛主流程与约束() {
        createRace();
        register("a", 10_000L, "req-a");
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("injury", version, "req-wd"));
        version++;
        assertThat(entryOf(raceService.getResults(RACE), "a").status())
                .isEqualTo(EntryStatus.WITHDRAWN);

        // 重复退赛409
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("again", version, "req-wd-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 退赛后不可提交分段、修订计时
        configureCheckpoints("cp1");
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest(
                        "t1", "cp1", 1_000L, version, "req-t-blocked")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 9_000L, version, "req-rev-blocked")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
    }

    @Test
    void 暂停开始恢复与分段的requestId幂等() {
        createRace();
        register("a", 10_000L, "req-a");
        configureCheckpoints("cp1", "cp2");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-hold");

        clock.advance(Duration.ofSeconds(60));
        SubmitTimingRequest timingRequest = new SubmitTimingRequest(
                "t-cp", "cp1", 1_000L, version, "req-timing");
        ServiceResult first = raceService.submitTiming(RACE, "a", timingRequest);
        version++;
        // 同参重放首次结果（含 MEDICAL_HOLD 标记），不产生第二条计时
        ServiceResult replay = raceService.submitTiming(RACE, "a", timingRequest);
        assertThat(replay.status()).isEqualTo(first.status());
        // 重放返回的是固化响应体（JsonNode），字段与首次结果一致
        com.fasterxml.jackson.databind.JsonNode replayed =
                (com.fasterxml.jackson.databind.JsonNode) replay.body();
        assertThat(replayed.get("timingId").asText()).isEqualTo("t-cp");
        assertThat(replayed.get("medicalHold").asBoolean()).isTrue();
        assertThat(replayed.get("excludedHoldId").asText()).isEqualTo("hold-1");
        assertThat(repository.findTimingsForRunner(RACE, "a")).hasSize(1);

        // 异参重放同 requestId：409
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest(
                        "t-cp", "cp2", 2_000L, version, "req-timing")))
                .isInstanceOf(ConflictException.class);

        // 恢复失败（同角色422）不占用 requestId：同键改正参数后成功
        String resumeReqId = "req-resume";
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_A, "fit", version, resumeReqId)))
                .isInstanceOf(UnprocessableEntityException.class);
        ServiceResult resumedOk = raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_B, "fit", version, resumeReqId));
        assertThat(resumedOk.status()).isEqualTo(200);
        version++;

        // 恢复成功后同键异参：409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 2_000, MED_B, "fit2", version, resumeReqId)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜后新增医疗记录不改变快照结果() {
        createRace();
        register("a", 10_000L, "req-a");
        configureCheckpoints("cp1");
        long startAt = holdStart(60_000);
        startHold("hold-1", "a", startAt, MED_A, "req-start");
        clock.advance(Duration.ofSeconds(60));
        submit("a", "t-cp1", "cp1", 1_000, "req-t1");

        raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal"));
        version++;
        StandingResponse snapshotBefore = raceService.getSnapshot(RACE);
        ResultEntryResponse before = entryOf(snapshotBefore, "a");
        assertThat(before.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(before.medicalHoldActive()).isTrue();
        assertThat(before.excludedTimingCount()).isEqualTo(1);

        // 封榜后任何医疗写操作都被拒绝，快照不可变
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "hold-1",
                new ResumeMedicalHoldRequest(
                        startAt + 1_000, MED_B, "fit", version, "req-resume")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest(
                        "hold-2", startAt + 2_000, "x", MED_A, version, "req-start2")))
                .isInstanceOf(ConflictException.class);

        StandingResponse snapshotAfter = raceService.getSnapshot(RACE);
        assertThat(entryOf(snapshotAfter, "a").medicalHoldActive()).isTrue();
        assertThat(entryOf(snapshotAfter, "a").excludedTimingCount()).isEqualTo(1);
    }
}
