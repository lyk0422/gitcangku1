package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.MedicalHoldStatus;
import com.example.starter.race.persistence.MedicalHoldRow;
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
 * 医疗暂停与退赛能力的 H2 数据库测试：暂停排除计时、恢复适赛、资格状态、
 * 失败分支（409/400/422/404）、requestId 幂等、并发裁决与封榜快照固化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-mh";
    /** 固定时钟当前时刻（所有写操作的提交时刻）。 */
    private static final long NOW = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

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

    private int v() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create-" + RACE));
    }

    private void register(String bib, Long finishTimeMs) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, v(), "req-reg-" + bib));
    }

    private void configure(String... codes) {
        java.util.List<ConfigureCheckpointsRequest.CheckpointDefinition> defs =
                new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(codes[i], i + 1));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, v(), "req-cfg-" + RACE));
        assertThat(result.status()).isEqualTo(201);
    }

    private CheckpointTimingResponse submit(String bib, String timingId, String code, long elapsed) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, v(), "req-" + timingId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointTimingResponse) result.body();
    }

    private MedicalHoldResponse startHold(String bib, String holdId, long startAt, String role) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(holdId, startAt, "受伤治疗", role, v(),
                        "req-start-" + holdId));
        assertThat(result.status()).isEqualTo(201);
        return (MedicalHoldResponse) result.body();
    }

    private MedicalHoldResponse resumeHold(String bib, String holdId, long endAt, String role) {
        ServiceResult result = raceService.resumeMedicalHold(RACE, bib, holdId,
                new ResumeMedicalHoldRequest(endAt, "确认适赛", role, v(),
                        "req-resume-" + holdId));
        assertThat(result.status()).isEqualTo(200);
        return (MedicalHoldResponse) result.body();
    }

    private ResultEntryResponse entryOf(StandingResponse standing, String bib) {
        return standing.entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 暂停期间分段被排除恢复后新计时参与排名且原始记录保留() {
        createRace();
        register("a", 10_000L);
        register("b", 9_000L);
        register("c", 8_000L);
        configure("c1", "c2", "c3");

        // a 赛前覆盖 c1/c2；b 全覆盖
        submit("a", "t-a1", "c1", 100L);
        submit("a", "t-a2", "c2", 200L);
        submit("b", "t-b1", "c1", 100L);
        submit("b", "t-b2", "c2", 200L);
        submit("b", "t-b3", "c3", 300L);

        // 登记暂停（开始时刻在未来，避开终点计时检查）
        MedicalHoldResponse hold = startHold("a", "h-1", NOW + 1_000L, "medic-1");
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(hold.startAt()).isEqualTo(NOW + 1_000L);
        assertThat(hold.endAt()).isNull();
        assertThat(hold.durationMs()).isNull();
        assertThat(hold.reason()).isEqualTo("受伤治疗");
        assertThat(hold.startedBy()).isEqualTo("medic-1");

        // 暂停生效：a 资格暂停不排名
        StandingResponse during = raceService.getResults(RACE);
        assertThat(entryOf(during, "a").status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        assertThat(entryOf(during, "a").rank()).isNull();
        assertThat(entryOf(during, "b").status()).isEqualTo(EntryStatus.RANKED);

        // 暂停期间分段仍可保存但标为 MEDICAL_HOLD
        CheckpointTimingResponse excluded = submit("a", "t-a3", "c3", 300L);
        assertThat(excluded.medicalHold()).isTrue();
        assertThat(excluded.holdId()).isEqualTo("h-1");

        // 明细查询显示排除原因
        RunnerTimingResponse aTimings = raceService.getRunnerTimings(RACE, "a");
        CheckpointPassResponse c3 = aTimings.checkpoints().get(2);
        assertThat(c3.checkpointCode()).isEqualTo("c3");
        assertThat(c3.elapsedMillis()).isEqualTo(300L);
        assertThat(c3.exclusionReason()).isEqualTo("MEDICAL_HOLD");

        // 不同医疗角色确认适赛恢复
        MedicalHoldResponse resumed = resumeHold("a", "h-1", NOW + 2_000L, "medic-2");
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumed.endAt()).isEqualTo(NOW + 2_000L);
        assertThat(resumed.durationMs()).isEqualTo(1_000L);
        assertThat(resumed.resumedBy()).isEqualTo("medic-2");
        assertThat(resumed.fitnessConclusion()).isEqualTo("确认适赛");

        // 恢复后：暂停期间的 c3 仍被排除（记录保留但不计入覆盖）→ a 漏 c3
        StandingResponse after = raceService.getResults(RACE);
        assertThat(entryOf(after, "a").status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entryOf(after, "a").missingCheckpoints()).containsExactly("c3");
        assertThat(repository.findTiming("t-a3").orElseThrow().medicalHold()).isTrue();

        // 恢复后的新计时正常参与排名：c 全程无暂停，d 先暂停恢复再提交
        startHold("c", "h-2", NOW + 1_000L, "medic-1");
        resumeHold("c", "h-2", NOW + 2_000L, "medic-2");
        CheckpointTimingResponse normal = submit("c", "t-c1", "c1", 100L);
        assertThat(normal.medicalHold()).isFalse();
        assertThat(normal.holdId()).isNull();
        submit("c", "t-c2", "c2", 200L);
        submit("c", "t-c3", "c3", 300L);
        StandingResponse finalStanding = raceService.getResults(RACE);
        assertThat(entryOf(finalStanding, "c").status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entryOf(finalStanding, "c").rank()).isEqualTo(1);
    }

    @Test
    void 重复开始409且requestId同参重放异参冲突() {
        createRace();
        register("a", 10_000L);
        startHold("a", "h-1", NOW + 1_000L, "medic-1");

        // 同一选手同时仅一条生效暂停
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-2", NOW + 1_000L, "再次受伤", "medic-1", v(),
                        "req-start-h-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("h-1");

        // 同 requestId 同参重放：返回首次结果且不推进版本
        register("r", 5_000L);
        StartMedicalHoldRequest replay = new StartMedicalHoldRequest(
                "h-9", NOW + 5_000L, "旧请求", "medic-9", v(), "req-replay");
        ServiceResult first = raceService.startMedicalHold(RACE, "r", replay);
        assertThat(first.status()).isEqualTo(201);
        int versionAfterFirst = v();
        ServiceResult replayed = raceService.startMedicalHold(RACE, "r", replay);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(v()).isEqualTo(versionAfterFirst);

        // 同 requestId 异参 → 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "r",
                new StartMedicalHoldRequest("h-9", NOW + 6_000L, "改参数", "medic-9", 1,
                        "req-replay")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 同 holdId 异参（新 requestId、其他选手）→ 全局唯一约束兜底 409
        register("r2", 6_000L);
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "r2",
                new StartMedicalHoldRequest("h-9", NOW + 7_000L, "改参数", "medic-8", v(),
                        "req-start-h-9-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("h-9");
    }

    @Test
    void 恢复校验分支与失败不占键() {
        createRace();
        register("a", 10_000L);
        startHold("a", "h-1", NOW + 1_000L, "medic-1");

        // 结束必须晚于开始：错误信息带实际值
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(NOW + 1_000L, "结论", "medic-2", v(),
                        "req-resume-eq")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("startAt=" + (NOW + 1_000L))
                .hasMessageContaining("endAt=" + (NOW + 1_000L));

        // 恢复须由不同医疗角色确认
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(NOW + 2_000L, "结论", "medic-1", v(),
                        "req-resume-same")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同医疗角色");

        // 暂停不存在 → 404
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-x",
                new ResumeMedicalHoldRequest(NOW + 2_000L, "结论", "medic-2", v(),
                        "req-resume-404")))
                .isInstanceOf(NotFoundException.class);

        // 失败不占 requestId 键：错误版本失败后同键重试成功
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(NOW + 2_000L, "结论", "medic-2", 999,
                        "req-resume-retry")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-resume-retry")).isEmpty();
        ServiceResult retry = raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(NOW + 2_000L, "结论", "medic-2", v(),
                        "req-resume-retry"));
        assertThat(retry.status()).isEqualTo(200);

        // 已恢复的暂停不可再恢复
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest(NOW + 3_000L, "结论", "medic-3", v(),
                        "req-resume-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void 暂停期间存在终点计时恢复返回422且可按左闭右开裁定() {
        createRace();
        // a 登记时携带完赛耗时：finishRecordedAt = NOW
        register("a", 10_000L);
        // b 无完赛耗时
        register("b", null);

        startHold("a", "h-a", NOW - 1_000L, "medic-1");
        startHold("b", "h-b", NOW - 1_000L, "medic-1");

        // a 的终点计时落在 [startAt, endAt) 内 → 422，错误信息带实际时刻
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-a",
                new ResumeMedicalHoldRequest(NOW + 1_000L, "结论", "medic-2", v(),
                        "req-resume-422")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("裁定完赛状态")
                .hasMessageContaining("finishRecordedAt=" + NOW);

        // 左闭右开：endAt 等于终点计时时刻即不在区间内 → 恢复成功
        MedicalHoldResponse resumed = resumeHold("a", "h-a", NOW, "medic-2");
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);

        // b 无终点计时：不受 422 约束
        MedicalHoldResponse bResumed = resumeHold("b", "h-b", NOW + 1_000L, "medic-2");
        assertThat(bResumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
    }

    @Test
    void 退赛取消资格或封榜后不可开始或恢复暂停() {
        createRace();
        register("a", 10_000L);
        register("b", 9_000L);
        register("c", 8_000L);
        register("d", 7_000L);

        // 退赛选手不可开始
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("受伤退赛", v(), "req-wd-a"));
        assertThatThrownBy(() -> startHold("a", "h-a", NOW + 1_000L, "medic-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");

        // 被取消资格选手不可开始
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "b", "DISQUALIFY", null, v(), "req-pen-1"));
        assertThatThrownBy(() -> startHold("b", "h-b", NOW + 1_000L, "medic-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        // 暂停生效后退赛：不可恢复
        startHold("c", "h-c", NOW + 1_000L, "medic-1");
        raceService.withdrawRunner(RACE, "c",
                new WithdrawRunnerRequest("退赛", v(), "req-wd-c"));
        assertThatThrownBy(() -> resumeHold("c", "h-c", NOW + 2_000L, "medic-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");

        // 取消资格撤销后可恢复资格：撤销 b 的处罚后可开始暂停
        raceService.revokePenalty(RACE, "p-1", new RevokePenaltyRequest(v(), "req-rev-p1"));
        MedicalHoldResponse bHold = startHold("b", "h-b", NOW + 1_000L, "medic-1");
        assertThat(bHold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);

        // 封榜后不可开始或恢复
        raceService.sealRace(RACE, new SealRaceRequest(v(), "req-seal"));
        assertThatThrownBy(() -> startHold("d", "h-d", NOW + 1_000L, "medic-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> resumeHold("b", "h-b", NOW + 2_000L, "medic-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 退赛后状态WITHDRAWN不排名且重复退赛409() {
        createRace();
        register("a", 1_000L);
        register("b", 2_000L);

        ServiceResult withdrawn = raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("受伤", v(), "req-wd-a"));
        assertThat(withdrawn.status()).isEqualTo(200);
        RunnerResponse body = (RunnerResponse) withdrawn.body();
        assertThat(body.withdrawn()).isTrue();
        assertThat(body.withdrawnAt()).isEqualTo(NOW);

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(entryOf(standing, "a").status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(entryOf(standing, "a").rank()).isNull();
        assertThat(entryOf(standing, "b").status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entryOf(standing, "b").rank()).isEqualTo(1);

        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("重复", v(), "req-wd-a2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 退赛记录未被改写
        assertThat(repository.findRunner(RACE, "a").orElseThrow().withdrawReason())
                .isEqualTo("受伤");
    }

    @Test
    void 封榜快照固化资格状态与排除原因且后续医疗记录不改写() {
        createRace();
        register("a", 10_000L);
        register("b", 9_000L);
        register("c", 8_000L);
        configure("c1", "c2");

        submit("a", "t-a1", "c1", 100L);
        submit("a", "t-a2", "c2", 200L);
        submit("b", "t-b1", "c1", 100L);
        // b 的 c2 在暂停期间提交 → 被排除
        startHold("b", "h-b", NOW + 1_000L, "medic-1");
        submit("b", "t-b2", "c2", 200L);
        resumeHold("b", "h-b", NOW + 2_000L, "medic-2");
        // c 封榜时仍有生效暂停
        startHold("c", "h-c", NOW + 1_000L, "medic-1");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(v(), "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(entryOf(standing, "a").status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entryOf(standing, "b").status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entryOf(standing, "c").status()).isEqualTo(EntryStatus.MEDICAL_HOLD);

        // 快照显示资格状态与排除计时原因
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(entryOf(snapshot, "c").status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        RunnerTimingResponse bTimings = raceService.getRunnerTimings(RACE, "b");
        assertThat(bTimings.checkpoints().get(1).exclusionReason()).isEqualTo("MEDICAL_HOLD");
        assertThat(bTimings.checkpoints().get(0).exclusionReason()).isNull();

        // 封榜后医疗写操作被拒绝，快照不被追溯改写
        assertThatThrownBy(() -> resumeHold("c", "h-c", NOW + 2_000L, "medic-2"))
                .isInstanceOf(ConflictException.class);
        StandingResponse snapshotAfter = raceService.getSnapshot(RACE);
        assertThat(json(snapshotAfter)).isEqualTo(json(snapshot));
    }

    @Test
    void 医疗暂停历史与诊断查询只读且顺序稳定() {
        createRace();
        register("a", 10_000L);
        register("b", 9_000L);

        startHold("a", "h-1", NOW + 1_000L, "medic-1");
        resumeHold("a", "h-1", NOW + 2_000L, "medic-2");
        startHold("a", "h-2", NOW + 3_000L, "medic-3");
        startHold("b", "h-3", NOW + 1_000L, "medic-1");

        int versionBefore = v();
        MedicalHoldHistoryResponse all = raceService.getMedicalHolds(RACE);
        assertThat(all.bib()).isNull();
        assertThat(all.version()).isEqualTo(versionBefore);
        assertThat(all.holds()).extracting(MedicalHoldResponse::holdId)
                .containsExactly("h-1", "h-2", "h-3");
        assertThat(all.holds().get(0).status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(all.holds().get(1).status()).isEqualTo(MedicalHoldStatus.ACTIVE);

        MedicalHoldHistoryResponse forA = raceService.getRunnerMedicalHolds(RACE, "a");
        assertThat(forA.bib()).isEqualTo("a");
        assertThat(forA.holds()).extracting(MedicalHoldResponse::holdId)
                .containsExactly("h-1", "h-2");

        // 只读：查询不推进版本
        assertThat(v()).isEqualTo(versionBefore);

        // 不存在的赛事/选手 → 404
        assertThatThrownBy(() -> raceService.getMedicalHolds("race-x"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRunnerMedicalHolds(RACE, "zz"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 并发开始暂停仅一条生效() throws Exception {
        createRace();
        register("a", 10_000L);
        int baseVersion = v();

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                "h-conc-" + index, NOW + 1_000L, "并发", "medic-" + index,
                                baseVersion, "req-conc-" + index));
                        success.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一版本仅一个写成功：一名选手最终只有一条生效暂停
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(v()).isEqualTo(baseVersion + 1);
        List<MedicalHoldRow> holds = repository.findMedicalHoldsForRunner(RACE, "a");
        assertThat(holds).hasSize(1);
        assertThat(holds.get(0).status()).isEqualTo(MedicalHoldStatus.ACTIVE);
    }

    @Test
    void 暂停与分段按提交顺序裁决() throws Exception {
        createRace();
        register("a", 10_000L);
        configure("c1");
        int baseVersion = v();

        // 同刻竞争：一个开始暂停、一个提交分段，同版本仅一个成功
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean holdWon = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean timingWon = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var holdFuture = pool.submit(() -> {
                start.await();
                try {
                    raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                            "h-race", NOW + 1_000L, "受伤", "medic-1", baseVersion,
                            "req-race-hold"));
                    holdWon.set(true);
                } catch (ConflictException ignored) {
                    // 版本被分段提交推进
                }
                return null;
            });
            var timingFuture = pool.submit(() -> {
                start.await();
                try {
                    raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                            "t-race", "c1", 100L, baseVersion, "req-race-timing"));
                    timingWon.set(true);
                } catch (ConflictException ignored) {
                    // 版本被暂停登记推进
                }
                return null;
            });
            start.countDown();
            holdFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
            timingFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(holdWon.get()).isNotEqualTo(timingWon.get());
        assertThat(v()).isEqualTo(baseVersion + 1);
        if (timingWon.get()) {
            // 计时先提交：按当时资格处理，无暂停 → 正常计时
            assertThat(repository.findTiming("t-race").orElseThrow().medicalHold()).isFalse();
            // 暂停随后仍可登记（新版本）
            startHold("a", "h-race", NOW + 1_000L, "medic-1");
        } else {
            // 暂停先提交：同刻后计时不可入榜（被排除）
            CheckpointTimingResponse after = submit("a", "t-race", "c1", 100L);
            assertThat(after.medicalHold()).isTrue();
            assertThat(after.holdId()).isEqualTo("h-race");
        }
    }
}
