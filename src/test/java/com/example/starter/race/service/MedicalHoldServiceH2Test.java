package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ExcludedTimingsResponse;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EligibilityStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.MedicalHoldStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClock;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 医疗暂停能力的 H2 数据库测试：暂停登记/恢复主流程、排除分段排名、
 * 失败分支（重复开始、结束时刻、同人确认、暂停期间终点计时、取消资格、封榜）、
 * 历史与诊断查询、封榜快照固化、requestId 幂等。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med";

    @Autowired
    private RaceService raceService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(MutableClockTestConfig.T0);
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int expectedVersion, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, reqId));
    }

    private void configure(int expectedVersion, String reqId, String... codes) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(codes[i], i + 1));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private CheckpointTimingResponse submit(
            String bib, String timingId, String code, long elapsed,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointTimingResponse) result.body();
    }

    private MedicalHoldResponse startHold(
            String bib, String holdId, long startAt, String role,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(holdId, startAt, "赛道医疗处置", role,
                        expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (MedicalHoldResponse) result.body();
    }

    private MedicalHoldResponse resume(
            String bib, String holdId, long endAt, String role,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.resumeMedicalHold(RACE, bib, holdId,
                new ResumeMedicalHoldRequest(endAt, "确认适赛", role, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(200);
        return (MedicalHoldResponse) result.body();
    }

    @Test
    void 暂停期间分段被排除恢复后新分段参与排名且原始记录不删除() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");       // v2
        registerRunner("b", 9_000L, 2, "req-b");        // v3
        configure(3, "req-cfg", "c1", "c2", "c3");      // v4
        submit("b", "tb1", "c1", 100L, 4, "req-b1");    // v5
        submit("b", "tb2", "c2", 200L, 5, "req-b2");    // v6
        submit("b", "tb3", "c3", 300L, 6, "req-b3");    // v7

        // 暂停前提交的分段：正常参与排名
        CheckpointTimingResponse before = submit("a", "ta1", "c1", 100L, 7, "req-a1"); // v8
        assertThat(before.excluded()).isFalse();
        assertThat(before.exclusionReason()).isNull();

        clock.advanceSeconds(1);
        long startAt = now();
        MedicalHoldResponse hold = startHold("a", "hold-1", startAt, "medic-1", 8, "req-h1"); // v9
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(hold.startAt()).isEqualTo(startAt);
        assertThat(hold.startedBy()).isEqualTo("medic-1");
        assertThat(hold.endAt()).isNull();
        assertThat(hold.endedBy()).isNull();

        // 暂停期间提交的分段：保存但标记 MEDICAL_HOLD，不参与排名
        CheckpointTimingResponse during = submit("a", "ta2", "c2", 200L, 9, "req-a2"); // v10
        assertThat(during.excluded()).isTrue();
        assertThat(during.exclusionReason()).isEqualTo("MEDICAL_HOLD");
        assertThat(during.holdId()).isEqualTo("hold-1");

        StandingResponse live = raceService.getResults(RACE);
        ResultEntryResponse aEntry = live.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        ResultEntryResponse bEntry = live.entries().stream()
                .filter(e -> e.bib().equals("b")).findFirst().orElseThrow();
        assertThat(bEntry.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(bEntry.rank()).isEqualTo(1);
        assertThat(bEntry.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        // a 的 c2 被排除不计入覆盖：3个检查点仅覆盖 c1
        assertThat(aEntry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(aEntry.coveredCheckpointCount()).isEqualTo(1);
        assertThat(aEntry.missingCheckpoints()).containsExactly("c2", "c3");
        assertThat(aEntry.eligibilityStatus()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);

        // 单选手分段明细：被排除分段仍可见且带原因
        RunnerTimingResponse aTimings = raceService.getRunnerTimings(RACE, "a");
        assertThat(aTimings.checkpoints()).extracting(CheckpointPassResponse::excluded)
                .containsExactly(false, true, false);
        assertThat(aTimings.checkpoints().get(1).exclusionReason()).isEqualTo("MEDICAL_HOLD");

        // 诊断查询：被排除分段及原因
        ExcludedTimingsResponse excluded = raceService.getExcludedTimings(RACE, "a");
        assertThat(excluded.excludedTimings()).hasSize(1);
        assertThat(excluded.excludedTimings().getFirst().timingId()).isEqualTo("ta2");
        assertThat(excluded.excludedTimings().getFirst().exclusionReason())
                .isEqualTo("MEDICAL_HOLD");
        assertThat(excluded.excludedTimings().getFirst().holdId()).isEqualTo("hold-1");

        // 不同医疗角色确认适赛恢复
        clock.advanceSeconds(1);
        long endAt = now();
        MedicalHoldResponse resumed =
                resume("a", "hold-1", endAt, "medic-2", 10, "req-r1"); // v11
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumed.endAt()).isEqualTo(endAt);
        assertThat(resumed.endedBy()).isEqualTo("medic-2");
        assertThat(resumed.fitnessConclusion()).isEqualTo("确认适赛");
        assertThat(resumed.endRecordedAt()).isEqualTo(endAt);

        // 恢复后新提交的分段正常参与排名
        CheckpointTimingResponse after = submit("a", "ta3", "c3", 300L, 11, "req-a3"); // v12
        assertThat(after.excluded()).isFalse();

        StandingResponse afterResume = raceService.getResults(RACE);
        ResultEntryResponse aAfter = afterResume.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aAfter.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(aAfter.coveredCheckpointCount()).isEqualTo(2);
        // 暂停期间的 c2 仍被排除（不回溯），仍计入缺失
        assertThat(aAfter.missingCheckpoints()).containsExactly("c2");

        // 原始记录不删除：被排除分段仍在，历史完整
        assertThat(raceService.getExcludedTimings(RACE, "a").excludedTimings()).hasSize(1);
        MedicalHoldHistoryResponse history = raceService.getMedicalHoldHistory(RACE, "a");
        assertThat(history.holds()).hasSize(1);
        assertThat(history.holds().getFirst().status()).isEqualTo(MedicalHoldStatus.RESUMED);
        // 只读查询不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(12);
    }

    @Test
    void 重复开始暂停409且holdId全局唯一() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        registerRunner("b", 9_000L, 2, "req-b"); // v3
        startHold("a", "hold-1", now(), "medic-1", 3, "req-h1"); // v4

        // 同一选手已有生效中暂停 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("hold-2", now(), "再次暂停", "medic-2", 4, "req-h2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("生效中");
        // holdId 全局唯一：其他选手也不能复用 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("hold-1", now(), "复用ID", "medic-1", 4, "req-h3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("医疗暂停ID已存在");
        // 失败不推进版本、不产生记录
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findMedicalHoldsForRunner(RACE, "b")).isEmpty();
    }

    @Test
    void 恢复结束时刻必须晚于开始时刻且响应携带差额() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        long startAt = now() + 1_000L;
        startHold("a", "hold-1", startAt, "medic-1", 2, "req-h1"); // v3

        // endAt == startAt -> 400，消息含实际值与差额
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(startAt, "确认适赛", "medic-2", 3, "req-r1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("晚于开始时刻")
                .hasMessageContaining("差额=0ms");
        // endAt < startAt -> 400，差额为负
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(startAt - 500L, "确认适赛", "medic-2", 3, "req-r2")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("差额=-500ms");
        // 暂停仍生效，版本未推进
        assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 恢复须由不同医疗角色确认否则422() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        clock.advanceSeconds(1); // 暂停区间晚于登记时刻，避免终点计时落入区间
        long startAt = now();
        startHold("a", "hold-1", startAt, "medic-1", 2, "req-h1"); // v3

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(startAt + 1_000L, "确认适赛", "medic-1", 3, "req-r1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不同医疗角色");
        assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();

        // 不同角色恢复成功
        MedicalHoldResponse resumed =
                resume("a", "hold-1", startAt + 1_000L, "medic-2", 3, "req-r2");
        assertThat(resumed.endedBy()).isEqualTo("medic-2");
        // 重复恢复 -> 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(startAt + 2_000L, "再次确认", "medic-3", 4, "req-r3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void 暂停期间存在终点计时恢复返回422裁定完赛状态后恢复成功() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2，updatedAt=T0
        clock.advanceSeconds(1);
        long startAt = now();                     // T0+1s
        startHold("a", "hold-1", startAt, "medic-1", 2, "req-h1"); // v3

        // 暂停期间修订终点计时（记录时刻落入暂停区间）
        clock.advanceSeconds(1);                  // T0+2s
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 12_000L, 3, "req-rev1")); // v4
        long finishRecordedAt = now();

        clock.advanceSeconds(1);                  // T0+3s
        long endAt = now();
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(endAt, "确认适赛", "medic-2", 4, "req-r1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("裁定完赛状态")
                .hasMessageContaining("finishRecordedAt=" + finishRecordedAt)
                .hasMessageContaining("[" + startAt + ", " + endAt + ")");
        // 422 不推进版本、暂停仍生效
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findActiveMedicalHold(RACE, "a")).isPresent();

        // 按既有规则裁定完赛状态：重新修订终点计时（记录时刻推进到 endAt，区间右开不含）
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 15_000L, 4, "req-rev2")); // v5
        MedicalHoldResponse resumed = resume("a", "hold-1", endAt, "medic-2", 5, "req-r2"); // v6
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumed.endAt()).isEqualTo(endAt);
    }

    @Test
    void 已取消资格选手不可开始或恢复暂停() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        registerRunner("b", 9_000L, 2, "req-b"); // v3
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "a", "DISQUALIFY", null, 3, "req-pen-a")); // v4

        // 已取消资格：不可开始
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("hold-a", now(), "处置", "medic-1", 4, "req-ha")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        // b 先开始暂停，再被取消资格：不可恢复
        clock.advanceSeconds(1); // 暂停区间晚于登记时刻
        startHold("b", "hold-b", now(), "medic-1", 4, "req-hb"); // v5
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-b", "b", "DISQUALIFY", null, 5, "req-pen-b")); // v6
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "b", "hold-b",
                new ResumeMedicalHoldRequest(now() + 1_000L, "确认适赛", "medic-2", 6, "req-rb")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        // 撤销取消资格处罚后可恢复
        raceService.revokePenalty(RACE, "pen-b", new RevokePenaltyRequest(6, "req-revoke")); // v7
        MedicalHoldResponse resumed = resume("b", "hold-b", now() + 1_000L, "medic-2", 7, "req-rb2");
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
    }

    @Test
    void 封榜快照固化资格状态与排除原因且后续医疗操作不可追溯改变() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        registerRunner("b", 9_000L, 2, "req-b"); // v3
        configure(3, "req-cfg", "c1");           // v4
        clock.advanceSeconds(1);
        startHold("a", "hold-1", now(), "medic-1", 4, "req-h1"); // v5
        submit("a", "ta1", "c1", 100L, 5, "req-a1");             // v6，被排除

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal")); // v7
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        ResultEntryResponse aEntry = standing.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        // 快照显示资格状态：a 封榜时仍有生效中暂停
        assertThat(aEntry.eligibilityStatus()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        // 被排除分段不计入覆盖：a 漏点不排名
        assertThat(aEntry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(aEntry.coveredCheckpointCount()).isEqualTo(0);
        assertThat(aEntry.missingCheckpoints()).containsExactly("c1");

        // 快照分段明细固化排除原因
        RunnerTimingResponse aTimings = raceService.getRunnerTimings(RACE, "a");
        assertThat(aTimings.checkpoints().getFirst().excluded()).isTrue();
        assertThat(aTimings.checkpoints().getFirst().exclusionReason()).isEqualTo("MEDICAL_HOLD");
        // 缺失汇总走快照：被排除分段视为缺失
        MissingCheckpointsResponse missing = raceService.getMissingCheckpoints(RACE);
        assertThat(missing.runners().stream()
                .filter(r -> r.bib().equals("a")).findFirst().orElseThrow()
                .missingCheckpoints()).containsExactly("c1");

        // 封榜后不可开始或恢复暂停
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("hold-2", now(), "处置", "medic-1", 7, "req-h2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(now() + 1_000L, "确认适赛", "medic-2", 7, "req-r1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 快照不被后续操作改变：内容保持一致
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.version()).isEqualTo(7);
        ResultEntryResponse aSnapshot = snapshot.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aSnapshot.eligibilityStatus()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        assertThat(aSnapshot.missingCheckpoints()).containsExactly("c1");
        // 医疗记录本身仍可查询（只读），但不影响封榜结果
        assertThat(raceService.getMedicalHold(RACE, "a", "hold-1").status())
                .isEqualTo(MedicalHoldStatus.ACTIVE);
    }

    @Test
    void requestId同参重放异参409且失败不占键() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2
        clock.advanceSeconds(1); // 暂停区间晚于登记时刻

        StartMedicalHoldRequest startReq =
                new StartMedicalHoldRequest("hold-1", now(), "处置", "medic-1", 2, "req-h1");
        ServiceResult first = raceService.startMedicalHold(RACE, "a", startReq);
        ServiceResult replay = raceService.startMedicalHold(RACE, "a", startReq);
        assertThat(replay.status()).isEqualTo(201);
        // 重放不重复推进版本、不新增记录
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("hold-9", now(), "异参", "medic-9", 2, "req-h1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：错误版本开始失败后可换参数重试
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("hold-2", now(), "处置", "medic-1", 999, "req-h2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-h2")).isEmpty();

        // 恢复幂等：同参重放返回原结果
        ServiceResult resumedFirst = raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(now() + 1_000L, "确认适赛", "medic-2", 3, "req-r1"));
        assertThat(resumedFirst.status()).isEqualTo(200);
        ServiceResult resumedReplay = raceService.resumeMedicalHold(RACE, "a", "hold-1",
                new ResumeMedicalHoldRequest(now() + 1_000L, "确认适赛", "medic-2", 3, "req-r1"));
        assertThat(resumedReplay.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);
    }

    @Test
    void 恢复后可再次开始新暂停且历史稳定排序() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2
        clock.advanceSeconds(1); // 暂停区间晚于登记时刻
        long startAt1 = now();
        startHold("a", "hold-1", startAt1, "medic-1", 2, "req-h1");      // v3
        resume("a", "hold-1", startAt1 + 1_000L, "medic-2", 3, "req-r1"); // v4

        clock.advanceSeconds(2);
        long startAt2 = now();
        startHold("a", "hold-2", startAt2, "medic-3", 4, "req-h2");      // v5

        MedicalHoldHistoryResponse history = raceService.getMedicalHoldHistory(RACE, "a");
        assertThat(history.holds()).extracting(MedicalHoldResponse::holdId)
                .containsExactly("hold-1", "hold-2");
        assertThat(history.holds()).extracting(MedicalHoldResponse::status)
                .containsExactly(MedicalHoldStatus.RESUMED, MedicalHoldStatus.ACTIVE);

        // 明细查询
        MedicalHoldResponse detail = raceService.getMedicalHold(RACE, "a", "hold-1");
        assertThat(detail.startedBy()).isEqualTo("medic-1");
        assertThat(detail.endedBy()).isEqualTo("medic-2");
        assertThat(detail.reason()).isEqualTo("赛道医疗处置");
        // 只读查询不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 暂停与恢复的未找到分支() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2

        // 赛事不存在
        assertThatThrownBy(() -> raceService.startMedicalHold("no-race", "a",
                new StartMedicalHoldRequest("hold-x", now(), "处置", "medic-1", 1, "req-x1")))
                .isInstanceOf(NotFoundException.class);
        // 选手不存在
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "ghost",
                new StartMedicalHoldRequest("hold-x", now(), "处置", "medic-1", 2, "req-x2")))
                .isInstanceOf(NotFoundException.class);
        // 暂停不存在
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "hold-ghost",
                new ResumeMedicalHoldRequest(now() + 1_000L, "确认适赛", "medic-2", 2, "req-x3")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("医疗暂停不存在");
        assertThatThrownBy(() -> raceService.getMedicalHold(RACE, "a", "hold-ghost"))
                .isInstanceOf(NotFoundException.class);
        // 暂停属于其他选手
        startHold("a", "hold-1", now(), "medic-1", 2, "req-h1"); // v3
        registerRunner("b", 9_000L, 3, "req-b");                 // v4
        assertThatThrownBy(() -> raceService.getMedicalHold(RACE, "b", "hold-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不属于该选手");
    }
}
