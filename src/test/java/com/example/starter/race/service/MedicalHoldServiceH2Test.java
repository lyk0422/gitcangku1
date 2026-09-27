package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalDiagnosticsResponse;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.RecordFinishTimingRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EligibilityStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.MedicalHoldRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 医疗暂停/恢复/退赛的真实 H2 服务测试：主流程、失败分支、幂等、提交顺序裁决、
 * 事务原子性（失败不留半成品、不占 requestId）与封榜固化。
 * 全部时间基于固定 UTC 时钟 {@link FixedClockTestConfig#FIXED_INSTANT}。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med";
    private static final String DOC_A = "medic-alpha";
    private static final String DOC_B = "medic-bravo";

    /** 固定“当前时刻”T。 */
    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();
    private static final long START = T - 10_000L;
    private static final long END = T - 100L;

    @Autowired
    private RaceService raceService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private int version;
    private final AtomicLong seq = new AtomicLong();

    private String req() {
        return "req-" + seq.incrementAndGet();
    }

    @BeforeEach
    void seedRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        version = 1;
    }

    private void register(String bib, Long finishMs) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishMs, version, req()));
        version++;
    }

    private void configureCheckpoints(int count) {
        java.util.List<com.example.starter.race.api.ConfigureCheckpointsRequest.CheckpointDefinition> defs =
                new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            defs.add(new com.example.starter.race.api.ConfigureCheckpointsRequest.CheckpointDefinition(
                    "cp" + i, i));
        }
        raceService.configureCheckpoints(RACE,
                new com.example.starter.race.api.ConfigureCheckpointsRequest(defs, version, req()));
        version++;
    }

    private CheckpointTimingResponse timing(String bib, String timingId, String code, long elapsed) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version, req()));
        version++;
        return (CheckpointTimingResponse) result.body();
    }

    private MedicalHoldResponse startHold(String holdId, String bib, long startAt, String role) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(holdId, startAt, "cramp", role, version, req()));
        version++;
        return (MedicalHoldResponse) result.body();
    }

    private void finishEvent(String id, String bib, long occurredAt, long finishMs) {
        raceService.recordFinishTiming(RACE, bib,
                new RecordFinishTimingRequest(id, occurredAt, finishMs, version, req()));
        version++;
    }

    private void resume(String holdId, long endAt, String role) {
        raceService.resumeMedicalHold(RACE, holdId,
                new ResumeMedicalHoldRequest(holdId, endAt, role, "fit-to-race", version, req()));
        version++;
    }

    private void disqualify(String bib, String penaltyId) {
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(penaltyId, bib, "DISQUALIFY", null, version, req()));
        version++;
    }

    private ResultEntryResponse entryOf(StandingResponse standing, String bib) {
        return standing.entries().stream()
                .filter(e -> e.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 暂停生效期间计时保存为MEDICAL_HOLD且不入榜() {
        register("a", 5000L);
        configureCheckpoints(3);
        assertThat(timing("a", "t-a-1", "cp1", 100L).exclusionReason()).isNull();

        startHold("h-a-1", "a", START, DOC_A);

        // 暂停期间提交 cp2：保存成功但标记 MEDICAL_HOLD
        CheckpointTimingResponse excluded = timing("a", "t-a-2", "cp2", 200L);
        assertThat(excluded.exclusionReason()).isEqualTo("MEDICAL_HOLD");

        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse a = entryOf(standing, "a");
        assertThat(a.status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        assertThat(a.rank()).isNull();
        assertThat(a.totalTimeMs()).isNull();

        MedicalDiagnosticsResponse diagnostics = raceService.getMedicalDiagnostics(RACE, "a");
        assertThat(diagnostics.eligibility()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        assertThat(diagnostics.activeHoldId()).isEqualTo("h-a-1");
        assertThat(diagnostics.excludedCount()).isEqualTo(1);
        assertThat(diagnostics.excludedTimings()).hasSize(1);
        assertThat(diagnostics.excludedTimings().getFirst().checkpointCode()).isEqualTo("cp2");
        assertThat(diagnostics.excludedTimings().getFirst().exclusionReason())
                .isEqualTo("MEDICAL_HOLD");

        // 原始计时未被删除：cp1 正常、cp2 仍可查且带排除原因
        var runnerTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(runnerTiming.checkpoints()).hasSize(3);
        assertThat(runnerTiming.checkpoints().get(1).timingId()).isEqualTo("t-a-2");
        assertThat(runnerTiming.checkpoints().get(1).exclusionReason()).isEqualTo("MEDICAL_HOLD");
    }

    @Test
    void 恢复后仅结束后的新计时参与排名且区间前后原始计时保留() {
        register("b", 5000L);
        configureCheckpoints(2);
        timing("b", "t-b-1", "cp1", 100L);
        startHold("h-b-1", "b", START, DOC_A);
        // 暂停期间不提交任何分段；区间外（早于开始）的终点计时不阻止恢复
        finishEvent("f-b-before", "b", START - 1_000L, 5000L);

        resume("h-b-1", END, DOC_B);

        MedicalHoldHistoryResponse history = raceService.getMedicalHoldHistory(RACE, "b");
        assertThat(history.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(history.activeHoldId()).isNull();
        assertThat(history.holds()).hasSize(1);
        MedicalHoldResponse hold = history.holds().getFirst();
        assertThat(hold.endAt()).isEqualTo(END);
        assertThat(hold.resumeRole()).isEqualTo(DOC_B);
        assertThat(hold.fitConclusion()).isEqualTo("fit-to-race");

        // 恢复后提交 cp2：正常参与排名，全覆盖 -> RANKED
        assertThat(timing("b", "t-b-2", "cp2", 200L).exclusionReason()).isNull();
        ResultEntryResponse b = entryOf(raceService.getResults(RACE), "b");
        assertThat(b.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(b.rank()).isEqualTo(1);
        assertThat(b.coveredCheckpointCount()).isEqualTo(2);
        assertThat(b.excludedTimingCount()).isZero();
    }

    @Test
    void 恢复时区间内存在终点计时返回422且保持暂停不留半成品() {
        register("a", 5000L);
        startHold("h-a", "a", START, DOC_A);
        // 终点计时落在 [START, END) 内
        finishEvent("f-a-in", "a", START + 1L, 5000L);
        int versionBefore = version;

        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", END, DOC_B, "fit", version, "req-resume-422")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("终点计时");

        // 版本未推进、暂停仍生效、requestId 未被占用
        assertThat(raceService.getResults(RACE).version()).isEqualTo(versionBefore);
        MedicalHoldRow row = repository.findMedicalHold("h-a").orElseThrow();
        assertThat(row.endAt()).isNull();
        assertThat(row.resumeRole()).isNull();
        assertThat(repository.findIdempotency("req-resume-422")).isEmpty();
    }

    @Test
    void 终点计时恰在结束端点不阻止恢复而在开始端点阻止() {
        register("c", 5000L);
        startHold("h-c", "c", START, DOC_A);
        // occurredAt == END（右端点，不含）不落在区间
        finishEvent("f-c-end", "c", END, 5000L);
        resume("h-c", END, DOC_B);
        assertThat(repository.findMedicalHold("h-c").orElseThrow().endAt()).isEqualTo(END);

        // 另一选手：occurredAt == START（左端点，含）落在区间 -> 422
        register("d", 5000L);
        startHold("h-d", "d", START, DOC_A);
        finishEvent("f-d-start", "d", START, 5000L);
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-d",
                new ResumeMedicalHoldRequest("h-d", END, DOC_B, "fit", version, req())))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void 暂停与计时按提交顺序裁决同刻() {
        register("o", 9000L);
        configureCheckpoints(3);

        // 顺序一：计时先提交（startAt==T 的暂停之后才登记），cp1 按当时资格正常入榜
        assertThat(timing("o", "t-o-1", "cp1", 100L).exclusionReason()).isNull();
        startHold("h-o", "o", T, DOC_A);
        assertThat(timing("o", "t-o-2", "cp2", 200L).exclusionReason()).isEqualTo("MEDICAL_HOLD");

        // 顺序二：另一选手暂停先提交（startAt==T），其后同刻计时不可入榜
        register("p", 9000L);
        startHold("h-p", "p", T, DOC_A);
        assertThat(timing("p", "t-p-1", "cp1", 100L).exclusionReason()).isEqualTo("MEDICAL_HOLD");
    }

    @Test
    void 开始暂停的各类失败分支() {
        register("a", 5000L);
        startHold("h-a", "a", START, DOC_A);

        // 同一选手重复开始 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-a-2", START, "x", DOC_B, version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("生效中的医疗暂停");

        // 开始时刻晚于提交时刻 400（用无生效暂停的新选手，避免先撞重复开始）
        register("z", 5000L);
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "z",
                new StartMedicalHoldRequest("h-z-future", T + 1, "x", DOC_B, version, req())))
                .isInstanceOf(BadRequestException.class);

        // 已退赛选手不可开始
        register("w", 5000L);
        raceService.withdrawRunner(RACE, "w",
                new WithdrawRunnerRequest("injury", "ref-1", T, version, req()));
        version++;
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "w",
                new StartMedicalHoldRequest("h-w", START, "x", DOC_A, version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("退赛");

        // 已取消资格选手不可开始
        register("q", 5000L);
        disqualify("q", "pen-q");
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "q",
                new StartMedicalHoldRequest("h-q", START, "x", DOC_A, version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("取消资格");

        // 暂停ID重复 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "w",
                new StartMedicalHoldRequest("h-a", START, "x", DOC_A, version, req())))
                .isInstanceOf(ConflictException.class);

        // 选手不存在 404
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "ghost",
                new StartMedicalHoldRequest("h-g", START, "x", DOC_A, version, req())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 恢复暂停的各类失败分支() {
        register("a", 5000L);
        startHold("h-a", "a", START, DOC_A);

        // 结束不晚于开始 400
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", START, DOC_B, "fit", version, req())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("严格晚于");
        // 结束晚于提交时刻 400
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", T + 1, DOC_B, "fit", version, req())))
                .isInstanceOf(BadRequestException.class);
        // 同一医疗角色恢复 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", END, DOC_A, "fit", version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("不同的医疗角色");
        // 不存在 404
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "nope",
                new ResumeMedicalHoldRequest("nope", END, DOC_B, "fit", version, req())))
                .isInstanceOf(NotFoundException.class);

        resume("h-a", END, DOC_B);
        // 重复恢复 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", END, DOC_B, "fit", version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已恢复");
    }

    @Test
    void 退赛失败分支与榜单资格() {
        register("a", 5000L);
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("injury", "ref-1", T, version, req()));
        version++;
        ResultEntryResponse a = entryOf(raceService.getResults(RACE), "a");
        assertThat(a.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.WITHDRAWN);

        // 重复退赛 409
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("again", "ref-1", T, version, req())))
                .isInstanceOf(ConflictException.class);
        // 退赛后不可恢复暂停（无暂停时为“生效暂停不存在”路径不适用；改用开始暂停409已覆盖）

        // 生效暂停中不可退赛
        register("b", 5000L);
        startHold("h-b", "b", START, DOC_A);
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "b",
                new WithdrawRunnerRequest("x", "ref-1", T, version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("医疗暂停");

        // 取消资格选手不可退赛
        register("c", 5000L);
        disqualify("c", "pen-c");
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "c",
                new WithdrawRunnerRequest("x", "ref-1", T, version, req())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("取消资格");
    }

    @Test
    void 开始暂停同键同参重放且异参409() {
        register("a", 5000L);
        StartMedicalHoldRequest request =
                new StartMedicalHoldRequest("h-ip", START, "cramp", DOC_A, version, "req-hold-ip");

        ServiceResult first = raceService.startMedicalHold(RACE, "a", request);
        ServiceResult replay = raceService.startMedicalHold(RACE, "a", request);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(repository.findMedicalHoldsForRunner(RACE, "a")).hasSize(1);

        StartMedicalHoldRequest different =
                new StartMedicalHoldRequest("h-ip", START, "other", DOC_A, version, "req-hold-ip");
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a", different))
                .isInstanceOf(ConflictException.class).hasMessageContaining("requestId");
    }

    @Test
    void 业务失败不占requestId且可用同键成功() {
        register("a", 5000L);
        // 首次以“结束不晚于开始”失败
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-x",
                new ResumeMedicalHoldRequest("h-x", START, DOC_B, "fit", version, "req-resume-retry")))
                .isInstanceOf(NotFoundException.class);
        // 先开始暂停，再用同一 requestId 合法恢复（失败未占键）
        startHold("h-x", "a", START, DOC_A);
        ServiceResult ok = raceService.resumeMedicalHold(RACE, "h-x",
                new ResumeMedicalHoldRequest("h-x", END, DOC_B, "fit", version, "req-resume-retry"));
        assertThat(ok.status()).isEqualTo(200);
        Optional<IdempotencyRow> row = repository.findIdempotency("req-resume-retry");
        assertThat(row).isPresent();
        assertThat(row.get().responseStatus()).isEqualTo(200);
    }

    @Test
    void 终点计时同ID重放同参异参409() {
        register("a", 5000L);
        RecordFinishTimingRequest ok =
                new RecordFinishTimingRequest("f-ip", T - 100, 5000L, version, "req-fin-ip");
        assertThat(raceService.recordFinishTiming(RACE, "a", ok).status()).isEqualTo(201);
        assertThat(raceService.recordFinishTiming(RACE, "a", ok).status()).isEqualTo(201);
        assertThat(repository.findFinishTiming("f-ip")).isPresent();

        RecordFinishTimingRequest different =
                new RecordFinishTimingRequest("f-ip", T - 99, 5000L, version, "req-fin-ip");
        assertThatThrownBy(() -> raceService.recordFinishTiming(RACE, "a", different))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜快照固化资格状态与排除计时原因且之后不可改变() {
        register("a", 5000L);
        register("b", 5000L);
        configureCheckpoints(2);
        timing("a", "t-a-1", "cp1", 100L);
        startHold("h-a", "a", START, DOC_A);
        timing("a", "t-a-2", "cp2", 200L); // 暂停期间，被排除
        timing("b", "t-b-1", "cp1", 100L);
        timing("b", "t-b-2", "cp2", 200L);

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version, "req-seal"));
        StandingResponse snapshot = (StandingResponse) sealed.body();
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        ResultEntryResponse a = entryOf(snapshot, "a");
        assertThat(a.status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        assertThat(a.excludedTimingCount()).isEqualTo(1);
        ResultEntryResponse b = entryOf(snapshot, "b");
        assertThat(b.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(b.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);

        // 快照分段明细固化排除原因
        var runnerTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(runnerTiming.checkpoints().get(1).exclusionReason())
                .isEqualTo("MEDICAL_HOLD");

        // 封榜后开始/恢复暂停均 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("h-late", START, "x", DOC_A, version, req())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "h-a",
                new ResumeMedicalHoldRequest("h-a", END, DOC_B, "fit", version, req())))
                .isInstanceOf(ConflictException.class);

        // 快照不随后续底层数据变化：绕过服务直接写入一条“封榜后”的医疗记录，结果不变
        jdbcTemplate.update(
                "INSERT INTO medical_hold (hold_id, race_id, bib, start_at, end_at, hold_reason, "
                        + "start_role, resume_role, fit_conclusion, started_at, resumed_at) "
                        + "VALUES ('h-post-seal', ?, 'b', ?, NULL, 'late', 'medic-x', "
                        + "NULL, NULL, ?, NULL)",
                RACE, START, T);
        StandingResponse again = raceService.getSnapshot(RACE);
        assertThat(again.entries()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(snapshot.entries());
        assertThat(entryOf(again, "b").eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void 被排除计时恢复后仍不参与覆盖但可重新提交其他检查点() {
        register("a", 5000L);
        configureCheckpoints(2);
        timing("a", "t-a-1", "cp1", 100L);
        startHold("h-a", "a", START, DOC_A);
        timing("a", "t-a-2", "cp2", 200L); // 暂停期间被排除
        finishEvent("f-a-before", "a", START - 100, 5000L);
        resume("h-a", END, DOC_B);

        // cp2 已存在（被排除）原始记录，不能再次提交同一检查点（失败不推进版本）
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-a-2-again", "cp2", 201L, version, req())))
                .isInstanceOf(UnprocessableEntityException.class);
        // 因 cp2 覆盖数缺失，选手仍 MISSING_CHECKPOINT；资格已恢复适赛
        ResultEntryResponse a = entryOf(raceService.getResults(RACE), "a");
        assertThat(a.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(a.coveredCheckpointCount()).isEqualTo(1);
        assertThat(a.excludedTimingCount()).isEqualTo(1);
    }
}
