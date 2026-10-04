package com.example.starter.race.service;

import com.example.starter.race.api.AddExecutionRequest;
import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmExecutionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ExecutionResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.ExecutionStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 罚时执行凭证的 H2 数据库测试：合成主流程、抵扣封顶、撤销/恢复、
 * 重叠区间、封榜冻结与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ExecutionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-exec";

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

    private int version(String raceId) {
        return repository.findRace(raceId).orElseThrow().version();
    }

    private void createRaceWithRunner(String raceId, String bib, long finishMs) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-" + raceId + "-create"));
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                bib, finishMs, version(raceId), "req-" + raceId + "-reg-" + bib));
    }

    private void addTimePenalty(String raceId, String penaltyId, String bib, long amountMs) {
        raceService.addPenalty(raceId, new AddPenaltyRequest(
                penaltyId, bib, "ADD_TIME", amountMs, version(raceId), "req-" + penaltyId));
    }

    private ExecutionResponse registerExecution(
            String raceId, String executionId, String bib,
            String penaltyId, long startMs, long endMs) {
        ServiceResult result = raceService.addExecution(raceId, new AddExecutionRequest(
                executionId, bib, penaltyId, startMs, endMs, "judge-1",
                version(raceId), "req-" + executionId));
        assertThat(result.status()).isEqualTo(201);
        return (ExecutionResponse) result.body();
    }

    private ExecutionResponse confirm(String raceId, String executionId, String requestId) {
        ServiceResult result = raceService.confirmExecution(raceId, executionId,
                new ConfirmExecutionRequest("judge-2", version(raceId), requestId));
        assertThat(result.status()).isEqualTo(200);
        return (ExecutionResponse) result.body();
    }

    private ResultEntryResponse entryOf(String raceId, String bib) {
        return raceService.getResults(raceId).entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 合成场景_计时100000罚时10000确认执行6000后净成绩104000() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);

        ExecutionResponse created = registerExecution(
                RACE, "ex-1", "a", "p-1", 1_000_000L, 1_006_000L);
        assertThat(created.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(created.creditedMs()).isNull();
        assertThat(created.judge()).isEqualTo("judge-1");

        // 确认前：原罚时全额计入，无抵扣
        ResultEntryResponse before = entryOf(RACE, "a");
        assertThat(before.penaltyMs()).isEqualTo(10_000L);
        assertThat(before.executedMs()).isZero();
        assertThat(before.totalTimeMs()).isEqualTo(110_000L);

        ExecutionResponse confirmed = confirm(RACE, "ex-1", "req-ex-1-confirm");
        assertThat(confirmed.status()).isEqualTo(ExecutionStatus.CONFIRMED);
        assertThat(confirmed.creditedMs()).isEqualTo(6_000L);
        assertThat(confirmed.confirmedBy()).isEqualTo("judge-2");

        // 确认后：原罚时不改写，已执行量6000，净成绩=100000+10000-6000
        ResultEntryResponse after = entryOf(RACE, "a");
        assertThat(after.penaltyMs()).isEqualTo(10_000L);
        assertThat(after.executedMs()).isEqualTo(6_000L);
        assertThat(after.totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 重复确认不得再次抵扣6000() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        ExecutionResponse first = confirm(RACE, "ex-1", "req-ex-1-confirm");
        int versionAfterConfirm = version(RACE);

        // 同键同参重放：返回首次完整响应，不产生新版本也不重复抵扣
        ServiceResult replayed = raceService.confirmExecution(RACE, "ex-1",
                new ConfirmExecutionRequest("judge-2", 4, "req-ex-1-confirm"));
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(json(replayed.body())).isEqualTo(json(first));
        assertThat(version(RACE)).isEqualTo(versionAfterConfirm);

        // 异键重复确认：409 且不再抵扣
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "ex-1",
                new ConfirmExecutionRequest("judge-3", version(RACE), "req-ex-1-again")))
                .isInstanceOf(ConflictException.class);
        assertThat(version(RACE)).isEqualTo(versionAfterConfirm);

        ResultEntryResponse entry = entryOf(RACE, "a");
        assertThat(entry.executedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 抵扣超出处罚剩余量时封顶且不产生负处罚() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        // 停留15000毫秒，超过罚时10000
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 15_000L);
        ExecutionResponse confirmed = confirm(RACE, "ex-1", "req-ex-1-confirm");

        assertThat(confirmed.creditedMs()).isEqualTo(10_000L);
        ResultEntryResponse entry = entryOf(RACE, "a");
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.executedMs()).isEqualTo(10_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);
    }

    @Test
    void 分次确认累计封顶且相邻区间合法() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        // 相邻区间 [0,6000) 与 [6000,12000)：端点相接不视为重叠
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        registerExecution(RACE, "ex-2", "a", "p-1", 6_000L, 12_000L);

        assertThat(confirm(RACE, "ex-1", "req-c-1").creditedMs()).isEqualTo(6_000L);
        // 第二次确认时剩余量仅4000，超出部分不产生负处罚
        assertThat(confirm(RACE, "ex-2", "req-c-2").creditedMs()).isEqualTo(4_000L);

        ResultEntryResponse entry = entryOf(RACE, "a");
        assertThat(entry.executedMs()).isEqualTo(10_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);
    }

    @Test
    void 重叠区间确认拒绝422且凭证与抵扣不部分写入() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        registerExecution(RACE, "ex-2", "a", "p-1", 3_000L, 9_000L);
        confirm(RACE, "ex-1", "req-c-1");
        int versionBefore = version(RACE);

        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "ex-2",
                new ConfirmExecutionRequest("judge-2", version(RACE), "req-c-2")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 无部分写入：ex-2 仍为待确认、无抵扣值，版本与成绩不变
        var ex2 = repository.findExecution("ex-2").orElseThrow();
        assertThat(ex2.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(ex2.creditedMs()).isNull();
        assertThat(version(RACE)).isEqualTo(versionBefore);
        assertThat(entryOf(RACE, "a").executedMs()).isEqualTo(6_000L);
    }
    @Test
    void 撤销后未确认凭证不能确认_已确认凭证保留但净处罚为0() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        registerExecution(RACE, "ex-2", "a", "p-1", 6_000L, 12_000L);
        confirm(RACE, "ex-1", "req-c-1");

        ServiceResult revoked = raceService.revokePenalty(RACE, "p-1",
                new RevokePenaltyRequest(version(RACE), "req-revoke-p1"));
        assertThat(revoked.status()).isEqualTo(200);
        // 撤销响应仍携带已确认抵扣量，执行证据不被抹除
        PenaltyResponse penalty = (PenaltyResponse) revoked.body();
        assertThat(penalty.revoked()).isTrue();
        assertThat(penalty.amountMs()).isEqualTo(10_000L);
        assertThat(penalty.executedMs()).isEqualTo(6_000L);

        // 净处罚为0：原罚时与抵扣均不再计入成绩
        ResultEntryResponse entry = entryOf(RACE, "a");
        assertThat(entry.penaltyMs()).isZero();
        assertThat(entry.executedMs()).isZero();
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);

        // 已确认凭证保留
        var ex1 = repository.findExecution("ex-1").orElseThrow();
        assertThat(ex1.status()).isEqualTo(ExecutionStatus.CONFIRMED);
        assertThat(ex1.creditedMs()).isEqualTo(6_000L);

        // 未确认凭证在撤销后不能确认
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "ex-2",
                new ConfirmExecutionRequest("judge-2", version(RACE), "req-c-2")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findExecution("ex-2").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void 恢复处罚只能新增处罚记录且旧凭证不复活() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        confirm(RACE, "ex-1", "req-c-1");
        raceService.revokePenalty(RACE, "p-1",
                new RevokePenaltyRequest(version(RACE), "req-revoke-p1"));

        // 恢复=新增一条处罚记录 p-2；绑定 p-1 的旧凭证不作用于 p-2
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-2", "a", "ADD_TIME", 10_000L, version(RACE), "req-pen-p2"));
        ResultEntryResponse entry = entryOf(RACE, "a");
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.executedMs()).isZero();
        assertThat(entry.totalTimeMs()).isEqualTo(110_000L);
    }

    @Test
    void 封榜冻结抵扣且之后禁止新确认() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);
        registerExecution(RACE, "ex-2", "a", "p-1", 6_000L, 12_000L);
        confirm(RACE, "ex-1", "req-c-1");

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version(RACE), "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        ResultEntryResponse entry = standing.entries().get(0);
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.executedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);

        // 封榜后禁止新确认（与撤销、封榜共用同一状态裁决：409）
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "ex-2",
                new ConfirmExecutionRequest("judge-2", version(RACE), "req-c-2")))
                .isInstanceOf(ConflictException.class);

        // 快照只读：抵扣值保持冻结
        StandingResponse snapshot = raceService.getResults(RACE);
        assertThat(snapshot.entries().get(0).executedMs()).isEqualTo(6_000L);
        assertThat(snapshot.entries().get(0).totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 跨选手引用取消资格与无效区间均422且凭证不写入() {
        createRaceWithRunner(RACE, "a", 100_000L);
        raceService.registerRunner(RACE, new RegisterRunnerRequest(
                "b", 90_000L, version(RACE), "req-reg-b"));
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "a", "DISQUALIFY", null, version(RACE), "req-pen-dq"));
        int versionBefore = version(RACE);

        // 跨选手引用：b 引用 a 的处罚
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-x1", "b", "p-1", 0L, 6_000L, "judge-1", version(RACE), "req-x1")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 取消资格处罚不得登记执行凭证
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-x2", "a", "p-dq", 0L, 6_000L, "judge-1", version(RACE), "req-x2")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 无效区间：endMs <= startMs
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-x3", "a", "p-1", 6_000L, 6_000L, "judge-1", version(RACE), "req-x3")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-x4", "a", "p-1", 7_000L, 6_000L, "judge-1", version(RACE), "req-x4")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 无部分写入：凭证不存在、版本未推进
        assertThat(repository.findExecution("ex-x1")).isEmpty();
        assertThat(repository.findExecution("ex-x2")).isEmpty();
        assertThat(repository.findExecution("ex-x3")).isEmpty();
        assertThat(repository.findExecution("ex-x4")).isEmpty();
        assertThat(version(RACE)).isEqualTo(versionBefore);
    }

    @Test
    void 确认裁判必须与登记裁判不同() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);
        registerExecution(RACE, "ex-1", "a", "p-1", 0L, 6_000L);

        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "ex-1",
                new ConfirmExecutionRequest("judge-1", version(RACE), "req-c-self")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findExecution("ex-1").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void 幂等_同键同参重放_异参409_失败不占键() {
        createRaceWithRunner(RACE, "a", 100_000L);
        addTimePenalty(RACE, "p-1", "a", 10_000L);

        // 失败请求不占键：无效区间422后同 requestId 可携带合法参数成功
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-1", "a", "p-1", 5_000L, 5_000L, "judge-1", version(RACE), "req-ex-1")))
                .isInstanceOf(UnprocessableEntityException.class);
        ServiceResult created = raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-1", "a", "p-1", 0L, 6_000L, "judge-1", version(RACE), "req-ex-1"));
        assertThat(created.status()).isEqualTo(201);

        // 同键同参重放首次完整响应
        ServiceResult replayed = raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-1", "a", "p-1", 0L, 6_000L, "judge-1", 3, "req-ex-1"));
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(created.body()));

        // 同键异参409
        assertThatThrownBy(() -> raceService.addExecution(RACE, new AddExecutionRequest(
                "ex-1", "a", "p-1", 0L, 7_000L, "judge-1", 3, "req-ex-1")))
                .isInstanceOf(ConflictException.class);
    }
}
