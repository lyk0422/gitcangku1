package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmExecutionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.RegisterExecutionRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.ExecutionStatus;
import com.example.starter.race.domain.RaceStatus;
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
 * 执行凭证与处罚撤销、恢复、封榜的交互语义：撤销后未确认凭证不能确认，
 * 已确认凭证保留但净处罚为0；恢复只允许新增处罚记录；封榜冻结抵扣并禁止新确认。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ExecutionRevokeSealH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-exec-rs";
    private static final String BIB = "a";
    private static final String PENALTY = "p-1";

    @Autowired
    private RaceService raceService;

    /** 建赛（v1) + 选手（v2) + 处罚10000(v3) + 凭证v-1登记（v4) + 确认6000(v5) + 凭证v-2登记（v6)。 */
    private void seedWithConfirmedAndPendingVoucher() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest(BIB, 100_000L, 1, "req-reg"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest(PENALTY, BIB, "ADD_TIME", 10_000L, 2, "req-pen"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-1", BIB, 1_000L, 7_000L, "ref-a", 3, "req-ex-1"));
        raceService.confirmExecution(RACE, PENALTY, "v-1",
                new ConfirmExecutionRequest("ref-b", 4, "req-cf-1"));
        raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-2", BIB, 8_000L, 9_000L, "ref-a", 5, "req-ex-2"));
    }

    @Test
    void 撤销后未确认凭证不能确认_已确认凭证保留但净处罚为0() {
        seedWithConfirmedAndPendingVoucher();

        // v7: 撤销处罚；响应保留已确认抵扣证据
        ServiceResult revoked = raceService.revokePenalty(RACE, PENALTY,
                new RevokePenaltyRequest(6, "req-revoke"));
        assertThat(revoked.status()).isEqualTo(200);
        PenaltyResponse revokedPenalty = (PenaltyResponse) revoked.body();
        assertThat(revokedPenalty.revoked()).isTrue();
        assertThat(revokedPenalty.amountMs()).isEqualTo(10_000L);
        assertThat(revokedPenalty.servedMs()).isEqualTo(6_000L);

        // 未确认凭证 v-2 不能确认
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, PENALTY, "v-2",
                new ConfirmExecutionRequest("ref-b", 7, "req-cf-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("撤销");
        assertThat(repository.findExecution("v-2").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);

        // 已确认凭证保留在库，但净处罚为0：成绩不再含该处罚与其抵扣
        assertThat(repository.findExecution("v-1").orElseThrow().status())
                .isEqualTo(ExecutionStatus.CONFIRMED);
        ResultEntryResponse entry = raceService.getResults(RACE).entries().getFirst();
        assertThat(entry.penaltyMs()).isZero();
        assertThat(entry.servedMs()).isZero();
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);

        // 查询仍返回原罚时与已执行量（证据不掩盖）
        List<PenaltyResponse> penalties = raceService.getPenalties(RACE);
        assertThat(penalties).hasSize(1);
        assertThat(penalties.getFirst().revoked()).isTrue();
        assertThat(penalties.getFirst().amountMs()).isEqualTo(10_000L);
        assertThat(penalties.getFirst().servedMs()).isEqualTo(6_000L);
    }

    @Test
    void 恢复处罚只允许新增记录_旧凭证不复活() {
        seedWithConfirmedAndPendingVoucher();
        raceService.revokePenalty(RACE, PENALTY, new RevokePenaltyRequest(6, "req-revoke"));

        // v8: 以新处罚ID恢复同等加时；旧凭证的抵扣不作用于新处罚
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", BIB, "ADD_TIME", 10_000L, 7, "req-pen-2"));

        ResultEntryResponse entry = raceService.getResults(RACE).entries().getFirst();
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.servedMs()).isZero();
        assertThat(entry.totalTimeMs()).isEqualTo(110_000L);

        List<PenaltyResponse> penalties = raceService.getPenalties(RACE);
        assertThat(penalties).hasSize(2);
        PenaltyResponse restored = penalties.stream()
                .filter(p -> p.penaltyId().equals("p-2")).findFirst().orElseThrow();
        assertThat(restored.servedMs()).isZero();
        PenaltyResponse old = penalties.stream()
                .filter(p -> p.penaltyId().equals(PENALTY)).findFirst().orElseThrow();
        assertThat(old.servedMs()).isEqualTo(6_000L);
    }

    @Test
    void 封榜冻结执行与抵扣_之后禁止新登记与新确认() {
        seedWithConfirmedAndPendingVoucher();

        // v7: 封榜，快照冻结原罚时10000、已执行6000、净成绩104000
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        ResultEntryResponse entry = snapshot.entries().getFirst();
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.servedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);

        // 封榜后禁止新确认与新登记
        assertThatThrownBy(() -> raceService.confirmExecution(RACE, PENALTY, "v-2",
                new ConfirmExecutionRequest("ref-b", 7, "req-cf-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.registerExecution(RACE, PENALTY,
                new RegisterExecutionRequest("v-3", BIB, 9_000L, 10_000L, "ref-a", 7, "req-ex-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 冻结值不随后续变化：快照与处罚查询仍返回封榜时的抵扣
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(104_000L);
        assertThat(raceService.getPenalties(RACE).getFirst().servedMs()).isEqualTo(6_000L);
        assertThat(repository.findExecution("v-2").orElseThrow().status())
                .isEqualTo(ExecutionStatus.PENDING);
    }
}