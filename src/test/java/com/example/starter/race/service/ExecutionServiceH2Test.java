package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmExecutionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ExecutionResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.RegisterExecutionRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.ExecutionStatus;
import com.example.starter.race.persistence.ExecutionRow;
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
 * 执行凭证的 H2 服务测试：合成主流程、幂等重放、422/409 拒绝分支与抵扣上限。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ExecutionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-exec";
    private static final String BIB = "a";
    private static final String PENALTY = "p-1";

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

    /** 建赛（v1) + 登记选手（v2) + 加时处罚（v3)，返回当前版本3。 */
    private int seedRaceWithAddTimePenalty(long finishMs, long amountMs) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, finishMs, 1, "req-reg"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", amountMs, 2, "req-pen"));
        return 3;
    }

    private ServiceResult confirm(String voucherId, String confirmedBy, int version, String reqId) {
        return raceService.confirmExecution(RACE, PENALTY, voucherId,
                new ConfirmExecutionRequest(confirmedBy, version, reqId));
    }

    @Test
    void 合成场景_计时100000罚时10000确认执行6000成绩104000() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);

        ServiceResult registered = raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000_000L, 1_006_000L, "ref-a", 3, "req-ex-1"));
        assertThat(registered.status()).isEqualTo(201);
        ExecutionResponse voucher = (ExecutionResponse) registered.body();
        assertThat(voucher.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(voucher.creditedMs()).isZero();
        assertThat(voucher.referee()).isEqualTo("ref-a");

        ServiceResult confirmed = confirm("v-1", "ref-b", 4, "req-cf-1");
        assertThat(confirmed.status()).isEqualTo(200);
        ExecutionResponse confirmedVoucher = (ExecutionResponse) confirmed.body();
        assertThat(confirmedVoucher.status()).isEqualTo(ExecutionStatus.CONFIRMED);
        assertThat(confirmedVoucher.confirmedBy()).isEqualTo("ref-b");
        assertThat(confirmedVoucher.creditedMs()).isEqualTo(6_000L);
        assertThat(confirmedVoucher.confirmedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse entry = standing.entries().getFirst();
        // 原罚时不改写，已执行量独立返回，净成绩=100000+10000-6000
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.servedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);

        List<PenaltyResponse> penalties = raceService.getPenalties(RACE);
        assertThat(penalties).hasSize(1);
        assertThat(penalties.getFirst().amountMs()).isEqualTo(10_000L);
        assertThat(penalties.getFirst().servedMs()).isEqualTo(6_000L);

        ExecutionRow row = repository.findExecution("v-1").orElseThrow();
        assertThat(row.status()).isEqualTo(ExecutionStatus.CONFIRMED);
        assertThat(row.creditedMs()).isEqualTo(6_000L);
        assertThat(row.confirmedBy()).isEqualTo("ref-b");
    }

    @Test
    void 重复确认同requestId重放首次响应且不再扣6000() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000_000L, 1_006_000L, "ref-a", 3, "req-ex-1"));
        ConfirmExecutionRequest request = new ConfirmExecutionRequest("ref-b", 4, "req-cf-1");

        ServiceResult first = raceService.confirmExecution(RACE, PENALTY, "v-1", request);
        ServiceResult replay = raceService.confirmExecution(RACE, PENALTY, "v-1", request);

        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 抵扣只发生一次，版本只推进一次
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs()).isEqualTo(6_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(104_000L);
        assertThat(raceService.getResults(RACE).version()).isEqualTo(5);
    }

    @Test
    void 重复确认不同requestId返回409且抵扣不变() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000_000L, 1_006_000L, "ref-a", 3, "req-ex-1"));
        confirm("v-1", "ref-b", 4, "req-cf-1");

        assertThatThrownBy(() -> confirm("v-1", "ref-c", 5, "req-cf-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已确认");
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs()).isEqualTo(6_000L);
        assertThat(repository.findExecution("v-1").orElseThrow().confirmedBy()).isEqualTo("ref-b");
    }

    @Test
    void 同键异参返回409且失败不占键() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000_000L, 1_006_000L, "ref-a", 3, "req-ex-1"));

        // 同 requestId 不同参数 -> 409
        assertThatThrownBy(() -> raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-2", BIB, 1_000_000L, 1_006_000L, "ref-a", 4, "req-ex-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(repository.findExecution("v-2")).isEmpty();

        // 确认时版本错误 -> 业务失败回滚，不占 requestId 键
        assertThatThrownBy(() -> confirm("v-1", "ref-b", 99, "req-cf-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-cf-1")).isEmpty();
        assertThat(repository.findExecution("v-1").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);

        // 同一 requestId 以正确参数重试成功
        ServiceResult retry = confirm("v-1", "ref-b", 4, "req-cf-1");
        assertThat(retry.status()).isEqualTo(200);
        assertThat(repository.findExecution("v-1").orElseThrow().status())
                .isEqualTo(ExecutionStatus.CONFIRMED);
    }
    @Test
    void 跨选手引用_取消资格_无效区间返回422且不写入() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, 100_000L, 1, "req-reg"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100_000L, 2, "req-reg-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", 10_000L, 3, "req-pen"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", BIB, "DISQUALIFY", null, 4, "req-dq"));

        // 凭证选手与处罚对象不一致（跨选手引用）
        assertThatThrownBy(() -> raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-x1", "b", 1_000L, 2_000L, "ref-a", 5, "req-x1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不一致");
        // 取消资格处罚不能登记执行凭证
        assertThatThrownBy(() -> raceService.registerExecution(RACE, "p-dq",
                new RegisterExecutionRequest("v-x2", BIB, 1_000L, 2_000L, "ref-a", 5, "req-x2")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("取消资格");
        // 无效区间：end<=start、start为负
        assertThatThrownBy(() -> raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-x3", BIB, 2_000L, 2_000L, "ref-a", 5, "req-x3")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("区间");
        assertThatThrownBy(() -> raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-x4", BIB, -1L, 2_000L, "ref-a", 5, "req-x4")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 凭证与抵扣均不得部分写入：无任何凭证落库，版本未被推进
        assertThat(repository.findExecution("v-x1")).isEmpty();
        assertThat(repository.findExecution("v-x2")).isEmpty();
        assertThat(repository.findExecution("v-x3")).isEmpty();
        assertThat(repository.findExecution("v-x4")).isEmpty();
        assertThat(raceService.getResults(RACE).version()).isEqualTo(5);
    }

    @Test
    void 同裁判确认返回422且凭证不部分写入() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000L, 2_000L, "ref-a", 3, "req-ex-1"));

        assertThatThrownBy(() -> confirm("v-1", "ref-a", 4, "req-cf-same"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("登记裁判");

        ExecutionRow row = repository.findExecution("v-1").orElseThrow();
        assertThat(row.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(row.creditedMs()).isZero();
        assertThat(row.confirmedBy()).isNull();
        assertThat(raceService.getResults(RACE).version()).isEqualTo(4);
    }

    @Test
    void 已确认区间重叠返回409_相邻合法_不同选手互不影响() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, 100_000L, 1, "req-reg"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 100_000L, 2, "req-reg-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", 10_000L, 3, "req-pen"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-b", "b", "ADD_TIME", 10_000L, 4, "req-pen-b"));
        // v6: a 凭证 [1000,2000]；v7: 确认
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000L, 2_000L, "ref-a", 5, "req-ex-1"));
        confirm("v-1", "ref-b", 6, "req-cf-1");

        // v8 登记重叠区间 [1500,2500]：登记允许，确认时与已确认区间重叠 -> 409
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-2", BIB, 1_500L, 2_500L, "ref-a", 7, "req-ex-2"));
        assertThatThrownBy(() -> confirm("v-2", "ref-b", 8, "req-cf-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重叠");
        // 确认失败不部分写入：v-2 仍 PENDING 且抵扣为0
        ExecutionRow overlapping = repository.findExecution("v-2").orElseThrow();
        assertThat(overlapping.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(overlapping.creditedMs()).isZero();

        // 相邻区间 [2000,3000] 合法（左闭右开）
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-3", BIB, 2_000L, 3_000L, "ref-a", 8, "req-ex-3"));
        ServiceResult adjacent = confirm("v-3", "ref-b", 9, "req-cf-3");
        assertThat(adjacent.status()).isEqualTo(200);

        // 不同选手的已确认区间互不影响：b 的 [1500,2500] 可确认
        raceService.registerExecution(RACE, "p-b",
                new RegisterExecutionRequest("v-b1", "b", 1_500L, 2_500L, "ref-a", 10, "req-ex-b1"));
        ServiceResult otherRunner = raceService.confirmExecution(RACE, "p-b", "v-b1",
                new ConfirmExecutionRequest("ref-b", 11, "req-cf-b1"));
        assertThat(otherRunner.status()).isEqualTo(200);
    }

    @Test
    void 抵扣不超过处罚剩余量且不产生负处罚() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 0L, 6_000L, "ref-a", 3, "req-ex-1"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-2", BIB, 6_000L, 12_000L, "ref-a", 4, "req-ex-2"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-3", BIB, 12_000L, 13_000L, "ref-a", 5, "req-ex-3"));

        // v-1 抵扣 6000；v-2 区间长6000但剩余仅4000 -> 抵扣4000；v-3 剩余为0 -> 抵扣0
        assertThat(((ExecutionResponse) confirm("v-1", "ref-b", 6, "req-cf-1").body())
                .creditedMs()).isEqualTo(6_000L);
        assertThat(((ExecutionResponse) confirm("v-2", "ref-b", 7, "req-cf-2").body())
                .creditedMs()).isEqualTo(4_000L);
        assertThat(((ExecutionResponse) confirm("v-3", "ref-b", 8, "req-cf-3").body())
                .creditedMs()).isZero();

        ResultEntryResponse entry = raceService.getResults(RACE).entries().getFirst();
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.servedMs()).isEqualTo(10_000L);
        // 净处罚为0而非负数：总耗时=原始耗时
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);
    }

    @Test
    void 凭证或处罚不存在及跨赛事引用返回404() {
        seedRaceWithAddTimePenalty(100_000L, 10_000L);
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000L, 2_000L, "ref-a", 3, "req-ex-1"));

        assertThatThrownBy(() -> raceService.registerExecution(RACE, "p-ghost",
                new RegisterExecutionRequest("v-2", BIB, 1_000L, 2_000L, "ref-a", 4, "req-ex-2")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, PENALTY, "v-ghost",
                new ConfirmExecutionRequest("ref-b", 4, "req-cf-ghost")))
                .isInstanceOf(NotFoundException.class);
        // 凭证挂在其他处罚路径下
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, "p-other", "v-1",
                new ConfirmExecutionRequest("ref-b", 4, "req-cf-other")))
                .isInstanceOf(NotFoundException.class);
        // 凭证未因失败请求被确认
        assertThat(repository.findExecution("v-1").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);
    }
}