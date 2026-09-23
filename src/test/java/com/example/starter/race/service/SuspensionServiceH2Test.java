package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CompensationResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EventHistoryResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.api.SuspensionEventResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.EventStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceEventRow;
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
 * 分组中止恢复能力的 H2 数据库测试：主流程净计时重算、恢复整体回滚、
 * 参数与状态分支、多事件叠加、幂等、落窗拒绝与封榜冻结。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SuspensionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-susp";

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

    private void configure(int expectedVersion, String reqId, String... codeThenPosition) {
        java.util.List<ConfigureCheckpointsRequest.CheckpointDefinition> defs =
                new java.util.ArrayList<>();
        for (int i = 0; i < codeThenPosition.length; i += 2) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codeThenPosition[i], Integer.parseInt(codeThenPosition[i + 1])));
        }
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
    }

    private void submit(String bib, String timingId, String code, long elapsed,
                        int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private ServiceResult suspend(String eventKey, String checkpointKey, long startElapsedMs,
                                  int expectedVersion, String reqId) {
        return raceService.suspendRace(RACE, new SuspendRaceRequest(
                eventKey, checkpointKey, startElapsedMs, expectedVersion, reqId));
    }

    private ServiceResult resume(String eventKey, long resumeElapsedMs,
                                 int expectedVersion, String reqId) {
        return raceService.resumeRace(RACE, eventKey,
                new ResumeRaceRequest(resumeElapsedMs, expectedVersion, reqId));
    }

    @Test
    void 主流程_中止恢复后净分段净完赛漏点与排名版本全部重算() {
        createRace();                                                   // v1
        registerRunner("ahead", 20_000L, 1, "req-ahead");               // v2
        registerRunner("behind", 20_000L, 2, "req-behind");             // v3
        registerRunner("done", 5_000L, 3, "req-done");                  // v4
        registerRunner("idle", null, 4, "req-idle");                    // v5
        configure(5, "req-cfg", "k1", "1", "k2", "2");                  // v6
        submit("ahead", "t-a1", "k1", 3_000L, 6, "req-t-a1");           // v7
        submit("ahead", "t-a2", "k2", 8_000L, 7, "req-t-a2");           // v8
        submit("behind", "t-b1", "k1", 11_000L, 8, "req-t-b1");         // v9
        submit("behind", "t-b2", "k2", 16_000L, 9, "req-t-b2");         // v10

        // v11: 登记中止 [6000, ?)，受影响起始检查点 k1
        ServiceResult suspended = suspend("e1", "k1", 6_000L, 10, "req-suspend");
        assertThat(suspended.status()).isEqualTo(201);
        SuspensionEventResponse event = (SuspensionEventResponse) suspended.body();
        assertThat(event.status()).isEqualTo(EventStatus.SUSPENDED);
        assertThat(event.resumeElapsedMs()).isNull();
        assertThat(event.durationMs()).isNull();
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SUSPENDED);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(11);

        // 中止中：全部写操作 409（恢复除外）
        assertThatThrownBy(() -> submit("ahead", "t-x", "k2", 9_000L, 11, "req-t-x"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("中止");
        assertThatThrownBy(() -> registerRunner("late", 1_000L, 11, "req-late"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("ahead", 21_000L, 11, "req-rev-x")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new com.example.starter.race.api.AddPenaltyRequest(
                        "p-x", "ahead", "ADD_TIME", 100L, 11, "req-p-x")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(11, "req-seal-x")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("中止");
        assertThatThrownBy(() -> suspend("e2", "k1", 7_000L, 11, "req-suspend-2"))
                .isInstanceOf(ConflictException.class);

        // 中止中只读查询可用：事件未恢复，净值仍等于原始值
        StandingResponse duringSuspension = raceService.getResults(RACE);
        assertThat(duringSuspension.version()).isEqualTo(11);
        assertThat(duringSuspension.entries()).filteredOn(e -> e.bib().equals("behind"))
                .singleElement()
                .satisfies(e -> assertThat(e.netFinishTimeMs()).isEqualTo(20_000L));

        // v12: 恢复，中止时长 4000
        ServiceResult resumed = resume("e1", 10_000L, 11, "req-resume");
        assertThat(resumed.status()).isEqualTo(200);
        SuspensionEventResponse resumedEvent = (SuspensionEventResponse) resumed.body();
        assertThat(resumedEvent.status()).isEqualTo(EventStatus.RESUMED);
        assertThat(resumedEvent.durationMs()).isEqualTo(4_000L);
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(12);

        // 排名版本重算：behind 受影响扣 4000 反超 ahead；done 中止前已完赛不受影响但漏点
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("behind", "ahead", "done", "idle");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null, null);
        ResultEntryResponse behindEntry = standing.entries().get(0);
        assertThat(behindEntry.finishTimeMs()).isEqualTo(20_000L);
        assertThat(behindEntry.netFinishTimeMs()).isEqualTo(16_000L);
        assertThat(behindEntry.totalTimeMs()).isEqualTo(16_000L);
        assertThat(standing.entries().get(1).netFinishTimeMs()).isEqualTo(20_000L);
        assertThat(standing.entries().get(2).status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(standing.entries().get(2).missingCheckpoints())
                .containsExactly("k1", "k2");
        assertThat(standing.entries().get(3).status()).isEqualTo(EntryStatus.UNTIMED);

        // 单选手分段：原始值不变，净值扣除中止时长
        RunnerTimingResponse behindTiming = raceService.getRunnerTimings(RACE, "behind");
        assertThat(behindTiming.finishTimeMs()).isEqualTo(20_000L);
        assertThat(behindTiming.netFinishTimeMs()).isEqualTo(16_000L);
        assertThat(behindTiming.checkpoints())
                .extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(11_000L, 16_000L);
        assertThat(behindTiming.checkpoints())
                .extracting(CheckpointPassResponse::netElapsedMillis)
                .containsExactly(7_000L, 12_000L);

        // 补偿明细：behind 受影响 4000；ahead 已过 k1、done 已完赛、idle 未起跑均补偿0
        CompensationResponse compensations = raceService.getCompensations(RACE);
        assertThat(compensations.runners()).extracting(r -> r.bib())
                .containsExactly("ahead", "behind", "done", "idle");
        assertThat(compensations.runners().get(0).totalCompensationMs()).isEqualTo(0L);
        assertThat(compensations.runners().get(1).totalCompensationMs()).isEqualTo(4_000L);
        assertThat(compensations.runners().get(1).entries())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.eventKey()).isEqualTo("e1");
                    assertThat(e.affected()).isTrue();
                    assertThat(e.compensationMs()).isEqualTo(4_000L);
                });
        assertThat(compensations.runners().get(2).totalCompensationMs()).isEqualTo(0L);
        assertThat(compensations.runners().get(3).totalCompensationMs()).isEqualTo(0L);

        // 事件历史只读
        EventHistoryResponse events = raceService.getEvents(RACE);
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().getFirst().status()).isEqualTo(EventStatus.RESUMED);
        assertThat(events.events().getFirst().durationMs()).isEqualTo(4_000L);

        // 原始计时永不改写
        assertThat(repository.findTimingsForRunner(RACE, "behind"))
                .extracting(t -> t.elapsedMillis())
                .containsExactly(11_000L, 16_000L);
        assertThat(repository.findRunner(RACE, "behind").orElseThrow().finishTimeMs())
                .isEqualTo(20_000L);
    }

    @Test
    void 恢复时记录落窗整体回滚且失败不占requestId键() {
        createRace();                                               // v1
        registerRunner("a", 20_000L, 1, "req-a");                   // v2
        configure(2, "req-cfg", "k1", "1");                         // v3
        submit("a", "t-1", "k1", 7_000L, 3, "req-t1");              // v4
        suspend("e1", "k1", 6_000L, 4, "req-suspend");              // v5

        // 分段 7000 落在 [6000,10000) 内 -> 422，恢复事件不落库
        assertThatThrownBy(() -> resume("e1", 10_000L, 5, "req-resume-bad"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止窗口");
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SUSPENDED);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        RaceEventRow event = repository.findEvent("e1").orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.SUSPENDED);
        assertThat(event.resumeElapsedMs()).isNull();
        // 失败不占键：同 requestId 修正参数后可重试成功
        assertThat(repository.findIdempotency("req-resume-bad")).isEmpty();

        // 窗口缩小为 [6000,6500)：7000 不再落窗，恢复成功
        ServiceResult resumed = resume("e1", 6_500L, 5, "req-resume-bad");
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints().getFirst().netElapsedMillis()).isEqualTo(6_500L);
        assertThat(timing.netFinishTimeMs()).isEqualTo(19_500L);
    }

    @Test
    void 恢复与中止的参数与状态失败分支() {
        createRace();                                               // v1
        registerRunner("a", 20_000L, 1, "req-a");                   // v2
        configure(2, "req-cfg", "k1", "1");                         // v3

        // 未中止时恢复 -> 409
        assertThatThrownBy(() -> resume("e1", 9_000L, 3, "req-resume-early"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未处于中止");
        // 检查点不存在 -> 404
        assertThatThrownBy(() -> suspend("e0", "ghost", 6_000L, 3, "req-s-ghost"))
                .isInstanceOf(NotFoundException.class);
        // 版本冲突 -> 409
        assertThatThrownBy(() -> suspend("e0", "k1", 6_000L, 99, "req-s-ver"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");

        suspend("e1", "k1", 6_000L, 3, "req-suspend");              // v4

        // resumeElapsedMs 不大于 startElapsedMs -> 400
        assertThatThrownBy(() -> resume("e1", 6_000L, 4, "req-r-eq"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> resume("e1", 5_000L, 4, "req-r-lt"))
                .isInstanceOf(BadRequestException.class);
        // 事件不存在 -> 404
        assertThatThrownBy(() -> resume("nope", 9_000L, 4, "req-r-404"))
                .isInstanceOf(NotFoundException.class);
        // 版本冲突 -> 409
        assertThatThrownBy(() -> resume("e1", 9_000L, 99, "req-r-ver"))
                .isInstanceOf(ConflictException.class);

        resume("e1", 10_000L, 4, "req-resume");                     // v5

        // 事件不重叠：新中止开始点早于已有恢复点 -> 422
        assertThatThrownBy(() -> suspend("e2", "k1", 8_000L, 5, "req-s-overlap"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("恢复点");
        // 边界允许：开始点等于上一恢复点
        ServiceResult second = suspend("e2", "k1", 10_000L, 5, "req-s-e2");
        assertThat(second.status()).isEqualTo(201);                 // v6
        resume("e2", 12_000L, 6, "req-r-e2");                       // v7

        // 不存在赛事的只读查询 -> 404
        assertThatThrownBy(() -> raceService.getEvents("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getCompensations("missing"))
                .isInstanceOf(NotFoundException.class);

        // 封榜后禁止中止
        raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));
        assertThatThrownBy(() -> suspend("e3", "k1", 20_000L, 8, "req-s-sealed"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 多个不重叠中止事件基于前次净结果叠加且原始计时不改写() {
        createRace();                                               // v1
        registerRunner("y", 15_000L, 1, "req-y");                   // v2
        configure(2, "req-cfg", "k1", "1", "k2", "2");              // v3
        submit("y", "t-1", "k1", 3_500L, 3, "req-t1");              // v4
        submit("y", "t-2", "k2", 11_000L, 4, "req-t2");             // v5

        suspend("e1", "k1", 1_000L, 5, "req-s1");                   // v6
        resume("e1", 3_000L, 6, "req-r1");                          // v7
        suspend("e2", "k2", 5_000L, 7, "req-s2");                   // v8
        resume("e2", 9_000L, 8, "req-r2");                          // v9

        // E1 扣 2000、E2 扣 4000：净 k1=1500、k2=5000、净完赛=9000
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "y");
        assertThat(timing.checkpoints())
                .extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(3_500L, 11_000L);
        assertThat(timing.checkpoints())
                .extracting(CheckpointPassResponse::netElapsedMillis)
                .containsExactly(1_500L, 5_000L);
        assertThat(timing.finishTimeMs()).isEqualTo(15_000L);
        assertThat(timing.netFinishTimeMs()).isEqualTo(9_000L);

        CompensationResponse compensations = raceService.getCompensations(RACE);
        assertThat(compensations.runners()).singleElement().satisfies(runner -> {
            assertThat(runner.totalCompensationMs()).isEqualTo(6_000L);
            assertThat(runner.entries()).extracting(e -> e.eventKey() + ":" + e.compensationMs())
                    .containsExactly("e1:2000", "e2:4000");
        });

        EventHistoryResponse events = raceService.getEvents(RACE);
        assertThat(events.events()).extracting(SuspensionEventResponse::eventKey)
                .containsExactly("e1", "e2");
        assertThat(events.events()).extracting(SuspensionEventResponse::durationMs)
                .containsExactly(2_000L, 4_000L);

        // 原始计时永不改写
        assertThat(repository.findTimingsForRunner(RACE, "y"))
                .extracting(t -> t.elapsedMillis())
                .containsExactly(3_500L, 11_000L);
        assertThat(repository.findRunner(RACE, "y").orElseThrow().finishTimeMs())
                .isEqualTo(15_000L);
    }

    @Test
    void 中止与恢复的requestId幂等及eventKey唯一() {
        createRace();                                               // v1
        registerRunner("a", 20_000L, 1, "req-a");                   // v2
        configure(2, "req-cfg", "k1", "1");                         // v3

        SuspendRaceRequest suspendReq = new SuspendRaceRequest("e1", "k1", 6_000L, 3, "req-suspend");
        ServiceResult first = raceService.suspendRace(RACE, suspendReq);
        ServiceResult replay = raceService.suspendRace(RACE, suspendReq);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不重复推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findEvents(RACE)).hasSize(1);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.suspendRace(RACE,
                new SuspendRaceRequest("e1", "k1", 7_000L, 3, "req-suspend")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        ResumeRaceRequest resumeReq = new ResumeRaceRequest(10_000L, 4, "req-resume");
        ServiceResult resumedFirst = raceService.resumeRace(RACE, "e1", resumeReq);
        ServiceResult resumedReplay = raceService.resumeRace(RACE, "e1", resumeReq);
        assertThat(resumedFirst.status()).isEqualTo(200);
        assertThat(resumedReplay.status()).isEqualTo(200);
        assertThat(json(resumedReplay.body())).isEqualTo(json(resumedFirst.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 恢复同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.resumeRace(RACE, "e1",
                new ResumeRaceRequest(11_000L, 4, "req-resume")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // eventKey 全局唯一：新 requestId 复用 e1 -> 409
        assertThatThrownBy(() -> suspend("e1", "k1", 20_000L, 5, "req-s-dup"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("中止事件ID已存在");
    }

    @Test
    void 恢复后落在中止窗口内的记录拒绝() {
        createRace();                                               // v1
        registerRunner("a", 30_000L, 1, "req-a");                   // v2
        configure(2, "req-cfg", "k1", "1", "k2", "2");              // v3
        suspend("e1", "k1", 6_000L, 3, "req-suspend");              // v4
        resume("e1", 10_000L, 4, "req-resume");                     // v5

        // 分段落在 [6000,10000) -> 422
        assertThatThrownBy(() -> submit("a", "t-w", "k1", 7_000L, 5, "req-t-w"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止窗口");
        // 登记完赛耗时落窗 -> 422
        assertThatThrownBy(() -> registerRunner("b", 7_000L, 5, "req-b-w"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止窗口");
        // 计时修订落窗 -> 422
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 7_000L, 5, "req-rev-w")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止窗口");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 窗口边界与窗口外记录允许
        registerRunner("late", 30_000L, 5, "req-late");             // v6
        submit("late", "t-l1", "k1", 500L, 6, "req-t-l1");          // v7
        submit("late", "t-l2", "k2", 10_000L, 7, "req-t-l2");       // v8
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "late");
        // late 中止前已过 k1（500<6000）-> 补偿0，净值等于原始值
        assertThat(timing.checkpoints())
                .extracting(CheckpointPassResponse::netElapsedMillis)
                .containsExactly(500L, 10_000L);
    }

    @Test
    void 封榜快照冻结完整事件版本与原始净值() {
        createRace();                                               // v1
        registerRunner("ahead", 20_000L, 1, "req-ahead");           // v2
        registerRunner("behind", 20_000L, 2, "req-behind");         // v3
        configure(3, "req-cfg", "k1", "1", "k2", "2");              // v4
        submit("ahead", "t-a1", "k1", 3_000L, 4, "req-t-a1");       // v5
        submit("behind", "t-b1", "k1", 11_000L, 5, "req-t-b1");     // v6
        submit("behind", "t-b2", "k2", 16_000L, 6, "req-t-b2");     // v7
        suspend("e1", "k1", 6_000L, 7, "req-suspend");              // v8
        resume("e1", 10_000L, 8, "req-resume");                     // v9

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(9, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);                 // v10

        // 快照冻结完整事件版本
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.events().getFirst().eventKey()).isEqualTo("e1");
        assertThat(snapshot.events().getFirst().startElapsedMs()).isEqualTo(6_000L);
        assertThat(snapshot.events().getFirst().resumeElapsedMs()).isEqualTo(10_000L);
        // 快照条目冻结原始/净值
        assertThat(snapshot.entries()).filteredOn(e -> e.bib().equals("behind"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.finishTimeMs()).isEqualTo(20_000L);
                    assertThat(e.netFinishTimeMs()).isEqualTo(16_000L);
                    assertThat(e.totalTimeMs()).isEqualTo(16_000L);
                    assertThat(e.rank()).isEqualTo(1);
                });
        // 快照分段明细冻结原始/净值
        assertThat(snapshot.checkpoints()).filteredOn(
                        c -> c.bib().equals("behind") && c.checkpointCode().equals("k1"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.elapsedMillis()).isEqualTo(11_000L);
                    assertThat(c.netElapsedMillis()).isEqualTo(7_000L);
                });

        // 封榜后成绩与分段查询走快照且含净值
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standing.entries().get(0).bib()).isEqualTo("behind");
        assertThat(standing.entries().get(0).netFinishTimeMs()).isEqualTo(16_000L);
        RunnerTimingResponse behindTiming = raceService.getRunnerTimings(RACE, "behind");
        assertThat(behindTiming.netFinishTimeMs()).isEqualTo(16_000L);
        assertThat(behindTiming.checkpoints())
                .extracting(CheckpointPassResponse::netElapsedMillis)
                .containsExactly(7_000L, 12_000L);

        // 事件历史与补偿明细封榜后仍只读可查
        assertThat(raceService.getEvents(RACE).events()).hasSize(1);
        CompensationResponse compensations = raceService.getCompensations(RACE);
        assertThat(compensations.runners())
                .filteredOn(r -> r.bib().equals("behind"))
                .singleElement()
                .satisfies(r -> assertThat(r.totalCompensationMs()).isEqualTo(4_000L));
    }
}
