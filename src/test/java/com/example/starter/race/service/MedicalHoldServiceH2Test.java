package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalHoldResponse;
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
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.ExclusionReason;
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
 * 医疗暂停能力的 H2 数据库测试：暂停登记/恢复主流程、暂停期间计时排除与恢复后恢复排名、
 * 终点计时冲突422、资格与封榜边界、requestId/holdId 双重幂等、并发竞争与快照固化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class MedicalHoldServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-med";
    /** 固定时钟时刻（所有服务端写入时间均等于该值）。 */
    private static final long BASE = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

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
            String bib, String holdId, String medicRole, long startedAt,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.startMedicalHold(RACE, bib,
                new StartMedicalHoldRequest(
                        holdId, medicRole, startedAt, "赛中受伤", expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (MedicalHoldResponse) result.body();
    }

    private MedicalHoldResponse resumeHold(
            String bib, String holdId, String confirmRole, long endedAt,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.resumeMedicalHold(RACE, bib, holdId,
                new ResumeMedicalHoldRequest(
                        confirmRole, endedAt, "FIT", expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(200);
        return (MedicalHoldResponse) result.body();
    }

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    @Test
    void 暂停期间计时被排除恢复后新计时参与排名且原始记录保留() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");       // v2
        registerRunner("b", 9_000L, 2, "req-b");        // v3
        configure(3, "req-cfg", "c1", "c2", "c3");      // v4

        CheckpointTimingResponse t1 = submit("a", "t-1", "c1", 100L, 4, "req-t1"); // v5
        assertThat(t1.exclusionReason()).isNull();

        MedicalHoldResponse hold = startHold("a", "h-1", "medic-1", BASE + 1_000L, 5, "req-h1"); // v6
        assertThat(hold.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(hold.medicRole()).isEqualTo("medic-1");
        assertThat(hold.startedAt()).isEqualTo(BASE + 1_000L);
        assertThat(hold.createdAt()).isEqualTo(BASE);
        assertThat(hold.confirmRole()).isNull();
        assertThat(hold.endedAt()).isNull();
        assertThat(hold.resumedAt()).isNull();

        // 暂停生效期间提交的分段：保存但标记 MEDICAL_HOLD，不参与临时排名
        CheckpointTimingResponse t2 = submit("a", "t-2", "c2", 200L, 6, "req-t2"); // v7
        assertThat(t2.exclusionReason()).isEqualTo(ExclusionReason.MEDICAL_HOLD);

        StandingResponse during = raceService.getResults(RACE);
        ResultEntryResponse aDuring = during.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aDuring.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(aDuring.coveredCheckpointCount()).isEqualTo(1);
        assertThat(aDuring.missingCheckpoints()).containsExactly("c2", "c3");

        // 不同医疗角色确认适赛后恢复
        MedicalHoldResponse resumed =
                resumeHold("a", "h-1", "medic-2", BASE + 2_000L, 7, "req-r1"); // v8
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumed.confirmRole()).isEqualTo("medic-2");
        assertThat(resumed.endedAt()).isEqualTo(BASE + 2_000L);
        assertThat(resumed.fitnessConclusion()).isEqualTo("FIT");
        assertThat(resumed.resumedAt()).isEqualTo(BASE);

        // 恢复后新提交的分段正常参与排名
        CheckpointTimingResponse t3 = submit("a", "t-3", "c3", 300L, 8, "req-t3"); // v9
        assertThat(t3.exclusionReason()).isNull();
        StandingResponse after = raceService.getResults(RACE);
        ResultEntryResponse aAfter = after.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aAfter.coveredCheckpointCount()).isEqualTo(2);
        assertThat(aAfter.missingCheckpoints()).containsExactly("c2");

        // 暂停前后已有原始计时不被删除：三条分段全部保留，c2 仍标 MEDICAL_HOLD
        assertThat(repository.findAllTimings(RACE)).hasSize(3);
        RunnerTimingResponse detail = raceService.getRunnerTimings(RACE, "a");
        assertThat(detail.checkpoints()).extracting(CheckpointPassResponse::checkpointCode)
                .containsExactly("c1", "c2", "c3");
        assertThat(detail.checkpoints()).extracting(CheckpointPassResponse::exclusionReason)
                .containsExactly(null, ExclusionReason.MEDICAL_HOLD, null);
        assertThat(detail.checkpoints().get(1).elapsedMillis()).isEqualTo(200L);

        // 明细/历史/诊断查询：字段完整且只读不改版本
        List<MedicalHoldResponse> history = raceService.getMedicalHoldHistory(RACE, "a");
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(history.getFirst().reason()).isEqualTo("赛中受伤");
        assertThat(raceService.getRaceMedicalHolds(RACE)).hasSize(1);
        assertThat(version()).isEqualTo(9);
    }

    @Test
    void 生效中暂停重复开始返回409且不推进版本() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        startHold("a", "h-1", "medic-1", BASE + 1_000L, 2, "req-h1"); // v3

        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest(
                        "h-2", "medic-2", BASE + 2_000L, "重复", 3, "req-h2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("h-1");
        assertThat(version()).isEqualTo(3);
        assertThat(repository.findActiveHold(RACE, "a").orElseThrow().holdId()).isEqualTo("h-1");
    }

    @Test
    void 同角色恢复与结束不晚于开始均返回422恢复后可再次暂停() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        configure(2, "req-cfg", "c1");              // v3
        startHold("a", "h-1", "medic-1", BASE + 1_000L, 3, "req-h1"); // v4

        // 同一医疗角色确认 -> 422
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-1", BASE + 2_000L, "FIT", 4, "req-r-same")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不同医疗角色");

        // 结束等于开始 -> 422，错误信息携带实际值
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 1_000L, "FIT", 4, "req-r-eq")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("startedAt=" + (BASE + 1_000L))
                .hasMessageContaining("endedAt=" + (BASE + 1_000L));

        // 结束早于开始 -> 422
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 999L, "FIT", 4, "req-r-lt")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 失败不推进版本、暂停仍生效
        assertThat(version()).isEqualTo(4);
        assertThat(repository.findActiveHold(RACE, "a")).isPresent();

        resumeHold("a", "h-1", "medic-2", BASE + 2_000L, 4, "req-r1"); // v5
        // 已恢复的暂停再次恢复 -> 409
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-3", BASE + 3_000L, "FIT", 5, "req-r-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");

        // 恢复后可再次登记新暂停，新暂停期间计时再次被排除
        startHold("a", "h-2", "medic-1", BASE + 4_000L, 5, "req-h2"); // v6
        CheckpointTimingResponse t1 = submit("a", "t-1", "c1", 100L, 6, "req-t1"); // v7
        assertThat(t1.exclusionReason()).isEqualTo(ExclusionReason.MEDICAL_HOLD);
        assertThat(raceService.getMedicalHoldHistory(RACE, "a"))
                .extracting(MedicalHoldResponse::holdId)
                .containsExactly("h-1", "h-2");
    }

    @Test
    void 暂停期间存在终点计时恢复返回422须先裁定完赛状态() {
        createRace();
        // 登记即带完赛耗时：finishRecordedAt = updatedAt = BASE
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        configure(2, "req-cfg", "c1");              // v3
        // 声明暂停区间覆盖 BASE 时刻
        startHold("a", "h-1", "medic-1", BASE - 1_000L, 3, "req-h1"); // v4

        // 区间 [BASE-1000, BASE+1000) 内存在终点计时 -> 422，信息携带实际时刻与区间
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 1_000L, "FIT", 4, "req-r-bad")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("裁定完赛状态")
                .hasMessageContaining("finishRecordedAt=" + BASE);
        // 422 失败不占 requestId 键
        assertThat(repository.findIdempotency("req-r-bad")).isEmpty();

        // 按既有规则裁定完赛状态（计时修订）；固定时钟下 updatedAt 仍为 BASE
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 20_000L, 4, "req-revise")); // v5
        // 同一声明区间仍命中终点计时 -> 仍 422
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 1_000L, "FIT", 5, "req-r-bad2")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 区间右开：声明结束时刻取 BASE 时 [BASE-1000, BASE) 不含终点计时 -> 恢复成功
        MedicalHoldResponse resumed = resumeHold("a", "h-1", "medic-2", BASE, 5, "req-r-ok"); // v6
        assertThat(resumed.status()).isEqualTo(MedicalHoldStatus.RESUMED);
        assertThat(resumed.endedAt()).isEqualTo(BASE);
    }

    @Test
    void 已取消资格选手不可开始或恢复暂停() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-1", "a", "DISQUALIFY", null, 2, "req-pen1")); // v3

        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", "medic-1", BASE, "受伤", 3, "req-h1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        // 撤销取消资格后可登记
        raceService.revokePenalty(RACE, "pen-1", new RevokePenaltyRequest(3, "req-rev1")); // v4
        startHold("a", "h-1", "medic-1", BASE + 1_000L, 4, "req-h1b"); // v5

        // 暂停生效期间被取消资格：不可恢复
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-2", "a", "DISQUALIFY", null, 5, "req-pen2")); // v6
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 2_000L, "FIT", 6, "req-r1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");
        assertThat(version()).isEqualTo(6);
    }

    @Test
    void 封榜后不可开始或恢复暂停且快照固化排除原因不被改写() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        configure(2, "req-cfg", "c1", "c2");        // v3
        submit("a", "t-1", "c1", 100L, 3, "req-t1"); // v4
        startHold("a", "h-1", "medic-1", BASE + 1_000L, 4, "req-h1"); // v5
        submit("a", "t-2", "c2", 200L, 5, "req-t2"); // v6（被排除）

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal")); // v7
        assertThat(sealed.status()).isEqualTo(200);

        // 封榜快照显示资格状态：c2 被排除 -> 漏点不排名
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.entries()).hasSize(1);
        ResultEntryResponse entry = snapshot.entries().getFirst();
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entry.coveredCheckpointCount()).isEqualTo(1);
        assertThat(entry.missingCheckpoints()).containsExactly("c2");

        // 快照分段明细固化排除原因，原始耗时保留
        RunnerTimingResponse detail = raceService.getRunnerTimings(RACE, "a");
        assertThat(detail.checkpoints().get(1).exclusionReason())
                .isEqualTo(ExclusionReason.MEDICAL_HOLD);
        assertThat(detail.checkpoints().get(1).elapsedMillis()).isEqualTo(200L);

        // 封榜后不可开始或恢复暂停
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-2", "medic-1", BASE, "受伤", 7, "req-h2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 2_000L, "FIT", 7, "req-r1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 后续医疗记录不得追溯改变封榜结果：快照两次读取一致，历史记录保持 ACTIVE
        assertThat(json(raceService.getSnapshot(RACE).entries()))
                .isEqualTo(json(snapshot.entries()));
        assertThat(raceService.getMedicalHoldHistory(RACE, "a").getFirst().status())
                .isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(version()).isEqualTo(7);
    }

    @Test
    void requestId同参重放异参409失败不占键且holdId双重幂等() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        registerRunner("b", 9_000L, 2, "req-b");    // v3

        StartMedicalHoldRequest startReq = new StartMedicalHoldRequest(
                "h-1", "medic-1", BASE + 1_000L, "受伤", 3, "req-h1");
        ServiceResult first = raceService.startMedicalHold(RACE, "a", startReq); // v4
        ServiceResult replay = raceService.startMedicalHold(RACE, "a", startReq);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(version()).isEqualTo(4);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-9", "medic-1", BASE, "异参", 3, "req-h1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：错误版本登记失败后，同 requestId 可用正确参数重试
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("h-2", "medic-1", BASE, "受伤", 999, "req-h2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-h2")).isEmpty();
        ServiceResult retry = raceService.startMedicalHold(RACE, "b",
                new StartMedicalHoldRequest("h-2", "medic-1", BASE, "受伤", 4, "req-h2")); // v5
        assertThat(retry.status()).isEqualTo(201);

        // 同 holdId 异参（新 requestId）-> 409
        assertThatThrownBy(() -> raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", "medic-9", BASE, "异参", 5, "req-h1c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("holdId");

        // 恢复后同 holdId 同参重放：返回首次登记时的 ACTIVE 视图，不暴露后续恢复
        resumeHold("a", "h-1", "medic-2", BASE + 2_000L, 5, "req-r1"); // v6
        ServiceResult holdReplay = raceService.startMedicalHold(RACE, "a",
                new StartMedicalHoldRequest("h-1", "medic-1", BASE + 1_000L, "受伤", 6, "req-h1d"));
        assertThat(holdReplay.status()).isEqualTo(201);
        assertThat(((MedicalHoldResponse) holdReplay.body()).status())
                .isEqualTo(MedicalHoldStatus.ACTIVE);
        assertThat(version()).isEqualTo(6);

        // 恢复 requestId 重放：返回首次成功响应，不重复推进版本
        ServiceResult resumeReplay = raceService.resumeMedicalHold(RACE, "a", "h-1",
                new ResumeMedicalHoldRequest("medic-2", BASE + 2_000L, "FIT", 5, "req-r1"));
        assertThat(resumeReplay.status()).isEqualTo(200);
        assertThat(version()).isEqualTo(6);
        assertThat(repository.findHoldsForRace(RACE)).hasSize(2);
    }

    @Test
    void 恢复不存在的暂停与查询不存在选手返回404() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        assertThatThrownBy(() -> raceService.resumeMedicalHold(RACE, "a", "h-x",
                new ResumeMedicalHoldRequest("medic-2", BASE, "FIT", 2, "req-rx")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getMedicalHoldHistory(RACE, "ghost"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRaceMedicalHolds("ghost-race"))
                .isInstanceOf(NotFoundException.class);
        // 无任何暂停时历史为空列表
        assertThat(raceService.getMedicalHoldHistory(RACE, "a")).isEmpty();
    }

    @Test
    void 暂停与分段计时同版本并发竞争仅一个成功且后续计时按当时资格处理() throws Exception {
        createRace();
        registerRunner("a", 100_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "c1", "c2");        // v3

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger holdOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger timingOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        if (index % 2 == 0) {
                            raceService.startMedicalHold(RACE, "a", new StartMedicalHoldRequest(
                                    "h-conc-" + index, "medic-1", BASE + 1_000L, "受伤",
                                    3, "req-h-conc-" + index));
                            holdOk.incrementAndGet();
                        } else {
                            raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                                    "t-conc-" + index, "c1", 100L + index, 3,
                                    "req-t-conc-" + index));
                            timingOk.incrementAndGet();
                        }
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

        // 同一版本只允许一个写成功
        assertThat(holdOk.get() + timingOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(version()).isEqualTo(4);

        if (holdOk.get() == 1) {
            // 暂停先提交：同刻后计时标记 MEDICAL_HOLD 不可入榜
            assertThat(repository.findActiveHold(RACE, "a")).isPresent();
            CheckpointTimingResponse t = submit("a", "t-after", "c1", 100L, 4, "req-t-after"); // v5
            assertThat(t.exclusionReason()).isEqualTo(ExclusionReason.MEDICAL_HOLD);
        } else {
            // 计时先提交：按当时资格正常入榜；随后暂停生效，再后的计时被排除
            assertThat(repository.findAllTimings(RACE)).hasSize(1);
            assertThat(repository.findAllTimings(RACE).getFirst().exclusionReason()).isNull();
            startHold("a", "h-after", "medic-1", BASE + 1_000L, 4, "req-h-after"); // v5
            CheckpointTimingResponse t2 = submit("a", "t-after2", "c2", 200L, 5, "req-t-after2"); // v6
            assertThat(t2.exclusionReason()).isEqualTo(ExclusionReason.MEDICAL_HOLD);
        }
    }

    @Test
    void 并发重复开始同一选手暂停仅一个生效() throws Exception {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2

        int threads = 6;
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
                                "h-dup-" + index, "medic-1", BASE + 1_000L, "受伤",
                                2, "req-h-dup-" + index));
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

        // 版本竞争 + 生效暂停唯一：恰好一个登记成功，其余全部冲突
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(version()).isEqualTo(3);
        assertThat(repository.findHoldsForRunner(RACE, "a")).hasSize(1);
        MedicalHoldRow active = repository.findActiveHold(RACE, "a").orElseThrow();
        assertThat(active.status()).isEqualTo(MedicalHoldStatus.ACTIVE);
    }
}
