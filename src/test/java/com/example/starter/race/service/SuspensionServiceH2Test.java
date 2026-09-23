package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CompensationDetailResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.api.SuspensionEventResponse;
import com.example.starter.race.api.SuspensionHistoryResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.NetTimingCalculator;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.SuspensionStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SuspensionEventRow;
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
 * 中止恢复能力的 H2 数据库测试：主流程净值重算、中止期写入拒绝、
 * 恢复422整体回滚、幂等重放、多事件累积与封榜冻结。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SuspensionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-susp";

    @Autowired
    private RaceService raceService;

    private void createRace(String raceId, String reqId) {
        raceService.createRace(new CreateRaceRequest(raceId, reqId));
    }

    private void register(String raceId, String bib, long finishMs, int version, String reqId) {
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, finishMs, version, reqId));
    }

    private void configure(String raceId, int version, String reqId, String... codes) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(codes[i], i + 1));
        }
        raceService.configureCheckpoints(raceId,
                new ConfigureCheckpointsRequest(defs, version, reqId));
    }

    private void submit(String raceId, String bib, String timingId, String code,
                        long elapsed, int version, String reqId) {
        ServiceResult result = raceService.submitTiming(raceId, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private ServiceResult suspend(String raceId, String eventKey, String checkpointKey,
                                  long start, int version, String reqId) {
        return raceService.suspendRace(raceId,
                new SuspendRaceRequest(eventKey, checkpointKey, start, version, reqId));
    }

    private ServiceResult resume(String raceId, String eventKey,
                                 long resumeMs, int version, String reqId) {
        return raceService.resumeRace(raceId, eventKey,
                new ResumeRaceRequest(resumeMs, version, reqId));
    }

    @Test
    void 中止恢复主流程重算净值并给出补偿明细() {
        createRace(RACE, "req-create");
        register(RACE, "a", 10000L, 1, "req-a");
        register(RACE, "b", 20000L, 2, "req-b");
        register(RACE, "c", 30000L, 3, "req-c");
        configure(RACE, 4, "req-cp", "p1", "p2", "p3");
        submit(RACE, "a", "t-a1", "p1", 1000L, 5, "req-ta1");
        submit(RACE, "a", "t-a2", "p2", 2000L, 6, "req-ta2");
        submit(RACE, "a", "t-a3", "p3", 3000L, 7, "req-ta3");
        submit(RACE, "b", "t-b1", "p1", 1000L, 8, "req-tb1");

        // 登记中止：赛事转 SUSPENDED，版本推进到10
        ServiceResult suspended = suspend(RACE, "e1", "p2", 4000L, 9, "req-s1");
        assertThat(suspended.status()).isEqualTo(201);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
        assertThat(race.version()).isEqualTo(10);

        // 中止期间所有其它写操作均被拒绝
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "b",
                new SubmitTimingRequest("t-b2x", "p2", 6500L, 10, "req-blocked-1")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(10, "req-blocked-2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("z", 5000L, 10, "req-blocked-3")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-x", "a", "ADD_TIME", 100L, 10, "req-blocked-4")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> suspend(RACE, "e2", "p2", 5000L, 10, "req-blocked-5"))
                .isInstanceOf(ConflictException.class);

        // 恢复：版本推进到11，赛事回到 OPEN
        ServiceResult resumed = resume(RACE, "e1", 6000L, 10, "req-r1");
        assertThat(resumed.status()).isEqualTo(200);
        race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(11);

        // 恢复后 b 的记录落在恢复点之后，净值扣除中止时长2000
        submit(RACE, "b", "t-b2", "p2", 6500L, 11, "req-tb2");
        submit(RACE, "b", "t-b3", "p3", 7000L, 12, "req-tb3");

        RunnerTimingResponse bTimings = raceService.getRunnerTimings(RACE, "b");
        assertThat(bTimings.finishTimeMs()).isEqualTo(20000L);
        assertThat(bTimings.netFinishTimeMs()).isEqualTo(18000L);
        assertThat(bTimings.checkpoints()).hasSize(3);
        assertThat(bTimings.checkpoints().get(0).elapsedMillis()).isEqualTo(1000L);
        assertThat(bTimings.checkpoints().get(0).netElapsedMillis()).isEqualTo(1000L);
        assertThat(bTimings.checkpoints().get(1).elapsedMillis()).isEqualTo(6500L);
        assertThat(bTimings.checkpoints().get(1).netElapsedMillis()).isEqualTo(4500L);
        assertThat(bTimings.checkpoints().get(2).elapsedMillis()).isEqualTo(7000L);
        assertThat(bTimings.checkpoints().get(2).netElapsedMillis()).isEqualTo(5000L);

        // a 中止前已通过 p2，补偿为0，净值等于原始值
        RunnerTimingResponse aTimings = raceService.getRunnerTimings(RACE, "a");
        assertThat(aTimings.netFinishTimeMs()).isEqualTo(10000L);
        assertThat(aTimings.checkpoints().get(2).netElapsedMillis()).isEqualTo(3000L);

        // 排名以净总耗时为准：a=10000，b=18000；c 无分段记录漏点不排名
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries().get(0).bib()).isEqualTo("a");
        assertThat(standing.entries().get(0).rank()).isEqualTo(1);
        assertThat(standing.entries().get(0).netTotalTimeMs()).isEqualTo(10000L);
        assertThat(standing.entries().get(1).bib()).isEqualTo("b");
        assertThat(standing.entries().get(1).rank()).isEqualTo(2);
        assertThat(standing.entries().get(1).totalTimeMs()).isEqualTo(20000L);
        assertThat(standing.entries().get(1).netFinishTimeMs()).isEqualTo(18000L);
        assertThat(standing.entries().get(1).netTotalTimeMs()).isEqualTo(18000L);
        assertThat(standing.entries().get(2).bib()).isEqualTo("c");
        assertThat(standing.entries().get(2).status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);

        // 补偿明细：a 已通过指定检查点，b 受影响扣除2000，c 未起跑
        CompensationDetailResponse compensations =
                raceService.getCompensations(RACE, "e1");
        assertThat(compensations.compensations()).hasSize(3);
        assertThat(compensations.compensations())
                .anySatisfy(item -> {
                    assertThat(item.bib()).isEqualTo("a");
                    assertThat(item.compensationMs()).isZero();
                    assertThat(item.basis()).isEqualTo(
                            NetTimingCalculator.CompensationBasis.PASSED_CHECKPOINT);
                })
                .anySatisfy(item -> {
                    assertThat(item.bib()).isEqualTo("b");
                    assertThat(item.compensationMs()).isEqualTo(2000L);
                    assertThat(item.basis()).isEqualTo(
                            NetTimingCalculator.CompensationBasis.AFFECTED);
                })
                .anySatisfy(item -> {
                    assertThat(item.bib()).isEqualTo("c");
                    assertThat(item.compensationMs()).isZero();
                    assertThat(item.basis()).isEqualTo(
                            NetTimingCalculator.CompensationBasis.NOT_STARTED);
                });

        // 事件历史只读可查
        SuspensionHistoryResponse history = raceService.getSuspensionEvents(RACE);
        assertThat(history.events()).hasSize(1);
        SuspensionEventResponse event = history.events().getFirst();
        assertThat(event.eventKey()).isEqualTo("e1");
        assertThat(event.status()).isEqualTo(SuspensionStatus.RESUMED);
        assertThat(event.startElapsedMs()).isEqualTo(4000L);
        assertThat(event.resumeElapsedMs()).isEqualTo(6000L);

        // 落在中止窗口 [4000,6000) 内的新记录被拒绝（分段与完赛修订）
        register(RACE, "d", 40000L, 13, "req-d");
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "d",
                new SubmitTimingRequest("t-d1", "p1", 5000L, 14, "req-td1")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("b", 5000L, 14, "req-rev-b")))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void 恢复时净不变量违反则整体回滚且失败不占键() {
        createRace(RACE, "req-create");
        register(RACE, "d", 10000L, 1, "req-d");
        configure(RACE, 2, "req-cp", "p1", "p2");
        submit(RACE, "d", "t-d1", "p1", 1000L, 3, "req-td1");
        // 中止登记前已存在落在未来窗口内的分段记录
        submit(RACE, "d", "t-d2", "p2", 5000L, 4, "req-td2");

        ServiceResult suspended = suspend(RACE, "e1", "p2", 4000L, 5, "req-s1");
        assertThat(suspended.status()).isEqualTo(201);

        // resume=6000 时 p2=5000 落在窗口 [4000,6000) 内：422，事件不落库、版本不推进
        assertThatThrownBy(() -> resume(RACE, "e1", 6000L, 6, "req-r1"))
                .isInstanceOf(UnprocessableEntityException.class);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.SUSPENDED);
        assertThat(race.version()).isEqualTo(6);
        SuspensionEventRow event = repository.findSuspensionEvent("e1").orElseThrow();
        assertThat(event.status()).isEqualTo(SuspensionStatus.SUSPENDED);
        assertThat(event.resumeElapsedMs()).isNull();

        // 失败不占键：同一 requestId 换合法参数可继续提交成功
        ServiceResult resumed = resume(RACE, "e1", 4500L, 6, "req-r1");
        assertThat(resumed.status()).isEqualTo(200);
        race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(race.version()).isEqualTo(7);

        RunnerTimingResponse timings = raceService.getRunnerTimings(RACE, "d");
        assertThat(timings.checkpoints().get(1).elapsedMillis()).isEqualTo(5000L);
        assertThat(timings.checkpoints().get(1).netElapsedMillis()).isEqualTo(4500L);
        assertThat(timings.netFinishTimeMs()).isEqualTo(9500L);
    }

    @Test
    void 中止与恢复幂等重放且eventKey全局唯一() {
        createRace(RACE, "req-create");
        configure(RACE, 1, "req-cp", "p1");

        ServiceResult first = suspend(RACE, "e1", "p1", 1000L, 2, "req-s1");
        assertThat(first.status()).isEqualTo(201);
        // 同键同参重放：返回原结果，版本不再推进
        ServiceResult replayed = suspend(RACE, "e1", "p1", 1000L, 2, "req-s1");
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        // 同键异参：409
        assertThatThrownBy(() -> suspend(RACE, "e1", "p1", 2000L, 2, "req-s1"))
                .isInstanceOf(ConflictException.class);

        // eventKey 全局唯一：另一赛事复用同一 eventKey 拒绝
        createRace("race-susp-2", "req-create-2");
        configure("race-susp-2", 1, "req-cp-2", "p1");
        assertThatThrownBy(() -> suspend("race-susp-2", "e1", "p1", 1000L, 2, "req-s2"))
                .isInstanceOf(ConflictException.class);

        // 恢复幂等：同键同参重放原结果，异参409
        ServiceResult resumed = resume(RACE, "e1", 2000L, 3, "req-r1");
        assertThat(resumed.status()).isEqualTo(200);
        ServiceResult resumedReplay = resume(RACE, "e1", 2000L, 3, "req-r1");
        assertThat(resumedReplay.status()).isEqualTo(200);
        assertThat(json(resumedReplay.body())).isEqualTo(json(resumed.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThatThrownBy(() -> resume(RACE, "e1", 3000L, 3, "req-r1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 多个不重叠中止事件按序累积且重叠被拒绝() {
        createRace(RACE, "req-create");
        register(RACE, "e", 40000L, 1, "req-e");
        configure(RACE, 2, "req-cp", "p1", "p2", "p3");
        submit(RACE, "e", "t-e1", "p1", 1000L, 3, "req-te1");

        assertThat(suspend(RACE, "e1", "p2", 4000L, 4, "req-s1").status()).isEqualTo(201);
        assertThat(resume(RACE, "e1", 6000L, 5, "req-r1").status()).isEqualTo(200);
        submit(RACE, "e", "t-e2", "p2", 7000L, 6, "req-te2");

        // 新窗口与既有事件 [4000,6000) 重叠：422
        assertThatThrownBy(() -> suspend(RACE, "e2", "p3", 5000L, 7, "req-s2"))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 不重叠的第二事件：基于前次净结果判断，e 仍未通过 p3，继续受影响
        assertThat(suspend(RACE, "e2", "p3", 8000L, 7, "req-s2").status()).isEqualTo(201);
        assertThat(resume(RACE, "e2", 9000L, 8, "req-r2").status()).isEqualTo(200);
        submit(RACE, "e", "t-e3", "p3", 11000L, 9, "req-te3");

        RunnerTimingResponse timings = raceService.getRunnerTimings(RACE, "e");
        assertThat(timings.checkpoints().get(0).netElapsedMillis()).isEqualTo(1000L);
        assertThat(timings.checkpoints().get(1).netElapsedMillis()).isEqualTo(5000L);
        assertThat(timings.checkpoints().get(2).elapsedMillis()).isEqualTo(11000L);
        assertThat(timings.checkpoints().get(2).netElapsedMillis()).isEqualTo(8000L);
        assertThat(timings.netFinishTimeMs()).isEqualTo(37000L);

        CompensationDetailResponse first = raceService.getCompensations(RACE, "e1");
        assertThat(first.compensations().getFirst().compensationMs()).isEqualTo(2000L);
        CompensationDetailResponse second = raceService.getCompensations(RACE, "e2");
        assertThat(second.compensations().getFirst().compensationMs()).isEqualTo(1000L);

        SuspensionHistoryResponse history = raceService.getSuspensionEvents(RACE);
        assertThat(history.events()).hasSize(2);
        assertThat(history.events().get(0).eventKey()).isEqualTo("e1");
        assertThat(history.events().get(1).eventKey()).isEqualTo("e2");
    }

    @Test
    void 封榜快照冻结原始净值与完整事件版本() {
        createRace(RACE, "req-create");
        register(RACE, "a", 10000L, 1, "req-a");
        register(RACE, "b", 20000L, 2, "req-b");
        configure(RACE, 3, "req-cp", "p1", "p2");
        submit(RACE, "a", "t-a1", "p1", 1000L, 4, "req-ta1");
        submit(RACE, "a", "t-a2", "p2", 2000L, 5, "req-ta2");
        submit(RACE, "b", "t-b1", "p1", 1000L, 6, "req-tb1");
        assertThat(suspend(RACE, "e1", "p2", 4000L, 7, "req-s1").status()).isEqualTo(201);
        assertThat(resume(RACE, "e1", 6000L, 8, "req-r1").status()).isEqualTo(200);
        submit(RACE, "b", "t-b2", "p2", 6500L, 9, "req-tb2");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(10, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SEALED);

        // 封榜后读取快照：原始值与净值同时冻结
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries().get(1).bib()).isEqualTo("b");
        assertThat(standing.entries().get(1).finishTimeMs()).isEqualTo(20000L);
        assertThat(standing.entries().get(1).totalTimeMs()).isEqualTo(20000L);
        assertThat(standing.entries().get(1).netFinishTimeMs()).isEqualTo(18000L);
        assertThat(standing.entries().get(1).netTotalTimeMs()).isEqualTo(18000L);

        RunnerTimingResponse bTimings = raceService.getRunnerTimings(RACE, "b");
        assertThat(bTimings.checkpoints().get(1).elapsedMillis()).isEqualTo(6500L);
        assertThat(bTimings.checkpoints().get(1).netElapsedMillis()).isEqualTo(4500L);
        assertThat(bTimings.netFinishTimeMs()).isEqualTo(18000L);

        // 快照冻结完整事件版本
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.suspensions()).hasSize(1);
        assertThat(snapshot.suspensions().getFirst().eventKey()).isEqualTo("e1");
        assertThat(snapshot.suspensions().getFirst().startElapsedMs()).isEqualTo(4000L);
        assertThat(snapshot.suspensions().getFirst().resumeElapsedMs()).isEqualTo(6000L);

        // 封榜后禁止中止；补偿明细与事件历史仍只读可查
        assertThatThrownBy(() -> suspend(RACE, "e2", "p1", 8000L, 11, "req-s2"))
                .isInstanceOf(ConflictException.class);
        assertThat(raceService.getCompensations(RACE, "e1").compensations())
                .anySatisfy(item -> {
                    assertThat(item.bib()).isEqualTo("b");
                    assertThat(item.compensationMs()).isEqualTo(2000L);
                });
        assertThat(raceService.getSuspensionEvents(RACE).events()).hasSize(1);
    }

    private String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
