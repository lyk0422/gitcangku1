package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CompensationResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.RunnerCompensationResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.api.SuspensionEventResponse;
import com.example.starter.race.api.SuspensionHistoryResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.SuspensionEventRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中止恢复与净计时重算的 H2 数据库测试：
 * 主流程补偿与排名、整体回滚（422不落库不半重算）、幂等/异参409/失败不占键、
 * eventKey唯一、中止窗口拒绝、多事件累计、封榜冻结、并发按提交顺序串行。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SuspensionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-susp";

    @Autowired
    private RaceService raceService;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void createRaceWithTwoRunnersAndThreeCheckpoints() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));      // v1
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 4_000L, 1, "req-a"));            // v2
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 4_000L, 2, "req-b"));            // v3
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                java.util.List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c2", 2),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c3", 3)),
                3, "req-cfg"));                                                  // v4
    }

    private void submit(String timingId, String bib, String code, long elapsed) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version(), "req-" + timingId));
        assertThat(result.status()).isEqualTo(201);
    }

    private ServiceResult suspend(String eventKey, String checkpointKey, long start) {
        return raceService.suspendRace(RACE, new SuspendRaceRequest(
                eventKey, checkpointKey, start, version(), "req-suspend-" + eventKey));
    }

    private ServiceResult resume(String eventKey, long resumeElapsed) {
        return raceService.resumeRace(RACE, new ResumeRaceRequest(
                eventKey, resumeElapsed, version(), "req-resume-" + eventKey));
    }

    @Test
    void 中止恢复主流程按受影响口径重算净分段净完赛漏点与排名() {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        // a 中止前仅过 c1@500（受影响）；b 中止前已过 c2@800（补偿0）
        submit("a1", "a", "c1", 500);   // v5
        submit("b1", "b", "c1", 600);   // v6
        submit("b2", "b", "c2", 800);   // v7

        // 中止：起始检查点 c2，窗口[1000,2000)
        ServiceResult suspended = suspend("ev1", "c2", 1_000L); // v8
        assertThat(suspended.status()).isEqualTo(201);
        SuspensionEventResponse event = (SuspensionEventResponse) suspended.body();
        assertThat(event.status()).isEqualTo("SUSPENDED");
        assertThat(event.versionAfterSuspend()).isEqualTo(8);
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SUSPENDED);

        // 中止期间普通写入与封榜一律409，只有恢复可写
        assertThatThrownBy(() -> submit("x", "a", "c2", 2_500))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(version(), "req-seal-early")))
                .isInstanceOf(ConflictException.class);

        // 恢复
        ServiceResult resumed = resume("ev1", 2_000L); // v9
        assertThat(resumed.status()).isEqualTo(200);
        SuspensionEventResponse resumedEvent = (SuspensionEventResponse) resumed.body();
        assertThat(resumedEvent.status()).isEqualTo("RESUMED");
        assertThat(resumedEvent.durationMs()).isEqualTo(1_000L);
        assertThat(resumedEvent.versionAfterResume()).isEqualTo(9);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(9);

        // 恢复后提交恢复时刻之后的分段：a 的 c2/c3 各扣1000
        submit("a2", "a", "c2", 2_500); // v10 -> 净1500
        // 落在窗口[1000,2000)的记录拒绝：b 的 c1@600/c2@800 均在窗口前，c3@1500 满足严格递增但落在窗口
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "b", new SubmitTimingRequest(
                        "b3win", "c3", 1_500L, version(), "req-b3win")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止区间");
        submit("a3", "a", "c3", 3_500); // v11 -> 净2500
        // b 的 c3 在恢复后但已过起始检查点：补偿0
        submit("b3", "b", "c3", 3_500); // v12 -> 净3500

        // 单选手净分段明细
        RunnerTimingResponse aTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(aTiming.finishTimeMs()).isEqualTo(4_000L);
        assertThat(aTiming.netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(aTiming.checkpoints()).extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(500L, 2_500L, 3_500L);
        assertThat(aTiming.checkpoints()).extracting(CheckpointPassResponse::netElapsedMs)
                .containsExactly(500L, 1_500L, 2_500L);
        assertThat(aTiming.checkpoints()).extracting(CheckpointPassResponse::compensationMs)
                .containsExactly(0L, 1_000L, 1_000L);

        RunnerTimingResponse bTiming = raceService.getRunnerTimings(RACE, "b");
        assertThat(bTiming.netFinishTimeMs()).isEqualTo(4_000L);
        assertThat(bTiming.checkpoints()).extracting(CheckpointPassResponse::compensationMs)
                .containsOnly(0L);

        // 排名基于净完赛：a 净3000 反超 b 净4000（原始完赛相同）
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries().get(0).rank()).isEqualTo(1);
        assertThat(standing.entries().get(0).netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(standing.entries().get(0).finishCompensationMs()).isEqualTo(1_000L);
        assertThat(standing.entries().get(0).totalTimeMs()).isEqualTo(3_000L);
        assertThat(standing.entries().get(1).netFinishTimeMs()).isEqualTo(4_000L);
        assertThat(standing.entries().get(1).rank()).isEqualTo(2);

        // 补偿明细只读接口
        CompensationResponse compensations = raceService.getCompensations(RACE);
        RunnerCompensationResponse aComp = compensations.runners().stream()
                .filter(r -> r.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aComp.affected()).isTrue();
        assertThat(aComp.finishCompensationMs()).isEqualTo(1_000L);
        assertThat(aComp.netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(aComp.checkpoints()).extracting(c -> c.compensationMs())
                .containsExactly(0L, 1_000L, 1_000L);
        RunnerCompensationResponse bComp = compensations.runners().stream()
                .filter(r -> r.bib().equals("b")).findFirst().orElseThrow();
        assertThat(bComp.affected()).isFalse();

        // 事件历史只读
        SuspensionHistoryResponse history = raceService.getSuspensionHistory(RACE);
        assertThat(history.events()).hasSize(1);
        assertThat(history.events().get(0).eventKey()).isEqualTo("ev1");
        assertThat(history.status()).isEqualTo(RaceStatus.OPEN);
    }

    @Test
    void 恢复时存在窗口内记录则422整体回滚事件不落库且不半重算() {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        // 中止登记前历史数据中已有一条落在未来窗口 [1000,2000) 的记录 c1@1500
        submit("a1", "a", "c1", 1_500); // v5
        submit("a2", "a", "c2", 2_500); // v6
        submit("b1", "b", "c1", 600);   // v7
        submit("b2", "b", "c2", 800);   // v8

        ServiceResult suspended = suspend("evbad", "c2", 1_000L);
        assertThat(suspended.status()).isEqualTo(201);
        int suspendedVersion = version();

        // 恢复：存在落在 [start,resume) 的原始记录 -> 422，整体回滚
        assertThatThrownBy(() -> raceService.resumeRace(RACE, new ResumeRaceRequest(
                        "evbad", 2_000L, suspendedVersion, "req-resume-evbad")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("中止区间");

        // 整体回滚：赛事仍 SUSPENDED、版本不变、事件仍未恢复
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
        assertThat(race.version()).isEqualTo(suspendedVersion);
        SuspensionEventRow eventRow = repository.findSuspension("evbad").orElseThrow();
        assertThat(eventRow.status()).isEqualTo("SUSPENDED");
        assertThat(eventRow.resumeElapsedMs()).isNull();
        assertThat(eventRow.versionAfterResume()).isNull();
        // 净值表无任何残留（未半重算）
        assertThat(repository.findAllCheckpointNets(RACE)).isEmpty();
        assertThat(repository.findRunnerNets(RACE)).isEmpty();
        // 失败不占 requestId 键；版本停留在中止后版本
        assertThat(repository.findIdempotency("req-resume-evbad")).isEmpty();
        assertThat(version()).isEqualTo(suspendedVersion);

        // 原始计时永不被改写：c1@1500 原样保留
        assertThat(raceService.getRunnerTimings(RACE, "a")
                .checkpoints().get(0).elapsedMillis()).isEqualTo(1_500L);
    }

    @Test
    void 中止恢复幂等同参重放异参409且eventKey唯一() {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        submit("a1", "a", "c1", 500);

        SuspendRaceRequest suspendReq =
                new SuspendRaceRequest("ev", "c2", 1_000L, version(), "req-sus");
        ServiceResult first = raceService.suspendRace(RACE, suspendReq);
        int versionAfterSuspend = version();
        // 同参重放
        ServiceResult replay = raceService.suspendRace(RACE, suspendReq);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(version()).isEqualTo(versionAfterSuspend);
        assertThat(repository.findSuspensions(RACE)).hasSize(1);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.suspendRace(RACE,
                new SuspendRaceRequest("ev", "c3", 1_000L, versionAfterSuspend, "req-sus")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // eventKey 唯一（新 requestId 同 eventKey）-> 409
        assertThatThrownBy(() -> raceService.suspendRace(RACE,
                new SuspendRaceRequest("ev", "c2", 1_000L, versionAfterSuspend, "req-sus-other")))
                .isInstanceOf(ConflictException.class);

        // 恢复失败（resume<=start，400）不占键，同键可用正确参数重试
        assertThatThrownBy(() -> raceService.resumeRace(RACE,
                new ResumeRaceRequest("ev", 1_000L, versionAfterSuspend, "req-res")))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findIdempotency("req-res")).isEmpty();
        ServiceResult resumed = raceService.resumeRace(RACE,
                new ResumeRaceRequest("ev", 2_000L, versionAfterSuspend, "req-res"));
        assertThat(resumed.status()).isEqualTo(200);
        int versionAfterResume = version();

        // 恢复同参重放：不重复推进版本、不重复重算
        ServiceResult resumeReplay = raceService.resumeRace(RACE,
                new ResumeRaceRequest("ev", 2_000L, versionAfterSuspend, "req-res"));
        assertThat(resumeReplay.status()).isEqualTo(200);
        assertThat(version()).isEqualTo(versionAfterResume);
    }

    @Test
    void 恢复参数与状态失败分支() {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        submit("a1", "a", "c1", 500);
        suspend("ev", "c2", 1_000L);
        int suspendedVersion = version();

        // 不存在的 eventKey -> 404
        assertThatThrownBy(() -> raceService.resumeRace(RACE, new ResumeRaceRequest(
                        "nope", 2_000L, suspendedVersion, "req-r-404")))
                .isInstanceOf(NotFoundException.class);
        // 赛事处于 OPEN 时登记中止：起始检查点不存在 -> 404
        // （构造另一场景：先恢复回 OPEN 再用错误检查点中止）
        raceService.resumeRace(RACE,
                new ResumeRaceRequest("ev", 2_000L, suspendedVersion, "req-r-ok"));
        assertThatThrownBy(() -> suspend("ev2", "cX", 2_500L))
                .isInstanceOf(NotFoundException.class);
        // 非 OPEN（此处 OPEN 但事件已恢复）重复恢复同一事件 -> 409
        assertThatThrownBy(() -> raceService.resumeRace(RACE, new ResumeRaceRequest(
                        "ev", 3_000L, version(), "req-r-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void 多个不重叠中止事件顺序生效补偿累计() {
        // 单独构造：完赛耗时6000，保证落在第二次恢复时刻4500之后
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));   // v1
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 6_000L, 1, "req-a"));         // v2
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                java.util.List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c2", 2),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("c3", 3)),
                2, "req-cfg"));                                               // v3
        submit("a1", "a", "c1", 500);   // v4
        suspend("e1", "c2", 1_000L);    // v5
        resume("e1", 2_000L);           // v6
        submit("a2", "a", "c2", 2_500); // v7 净1500（扣1000）

        // 第二次不重叠中止 [3000,4500) D=1500，起始检查点 c3
        suspend("e2", "c3", 3_000L);    // v8
        resume("e2", 4_500L);           // v9
        // c3 在事件2恢复之后：累计扣 1000+1500=2500
        submit("a3", "a", "c3", 5_000); // v10 净2500

        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.netFinishTimeMs()).isEqualTo(3_500L); // 6000-2500
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::netElapsedMs)
                .containsExactly(500L, 1_500L, 2_500L);
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::compensationMs)
                .containsExactly(0L, 1_000L, 2_500L);

        // 重叠的第三次中止（start 早于既有恢复时刻）-> 422
        assertThatThrownBy(() -> raceService.suspendRace(RACE, new SuspendRaceRequest(
                        "e3", "c3", 4_000L, version(), "req-sus-e3")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("重叠");

        SuspensionHistoryResponse history = raceService.getSuspensionHistory(RACE);
        assertThat(history.events()).extracting(SuspensionEventResponse::eventKey)
                .containsExactly("e1", "e2");
    }

    @Test
    void 封榜快照冻结净值与完整事件版本() {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        submit("a1", "a", "c1", 500);
        suspend("ev", "c2", 1_000L);
        int suspendedVersion = version();
        raceService.resumeRace(RACE,
                new ResumeRaceRequest("ev", 2_000L, suspendedVersion, "req-resume"));
        submit("a2", "a", "c2", 2_500);
        submit("a3", "a", "c3", 3_500);
        submit("b1", "b", "c1", 600);
        submit("b2", "b", "c2", 800);
        submit("b3", "b", "c3", 3_500);
        int currentVersion = version();

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(currentVersion, "req-seal"));
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries().get(0).netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(standing.entries().get(0).finishCompensationMs()).isEqualTo(1_000L);

        // 封榜后补偿查询走冻结快照
        CompensationResponse compensations = raceService.getCompensations(RACE);
        RunnerCompensationResponse aComp = compensations.runners().stream()
                .filter(r -> r.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aComp.netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(aComp.checkpoints()).extracting(c -> c.netElapsedMs())
                .containsExactly(500L, 1_500L, 2_500L);

        // 冻结的快照事件版本=1
        assertThat(repository.findSnapshot(RACE).orElseThrow().eventVersion()).isEqualTo(1);
        assertThat(repository.findSnapshot(RACE).orElseThrow().suspensions()).hasSize(1);
        assertThat(repository.findSnapshot(RACE).orElseThrow().suspensions().get(0).eventKey())
                .isEqualTo("ev");
    }

    @Test
    void 并发恢复按提交顺序仅一个成功且结果一致() throws Exception {
        createRaceWithTwoRunnersAndThreeCheckpoints();
        submit("a1", "a", "c1", 500);
        suspend("evc", "c2", 1_000L);
        int suspendedVersion = version();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.resumeRace(RACE, new ResumeRaceRequest(
                                    "evc", 2_000L, suspendedVersion, "req-resume-conc-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(suspendedVersion + 1);
        assertThat(repository.findSuspensions(RACE)).hasSize(1);
        assertThat(repository.findSuspension("evc").orElseThrow().status())
                .isEqualTo("RESUMED");
        // 未半重算：净值表只含 a 一条已恢复口径（a1@500 补偿0）
        assertThat(repository.findRunnerNets(RACE)).hasSize(2);
    }
}
