package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmPenaltyCreditRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.PenaltyCreditResponse;
import com.example.starter.race.api.RegisterPenaltyCreditRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.PenaltyCreditStatus;
import com.example.starter.race.persistence.PenaltyCreditRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 罚时执行凭证的 H2 数据库测试：合成主流程、拒绝分支、撤销与封榜状态裁决、幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PenaltyCreditH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-credit";

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

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    /** 播种：赛事 + 选手 a（finishMs）+ 加时处罚 p-1（penaltyMs），结束后版本为4。 */
    private void seedRaceWithPenalty(long finishMs, long penaltyMs) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", finishMs, 1, "req-reg-a"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", penaltyMs, 2, "req-pen-1"));
    }

    @Test
    void 合成场景_确认执行6000后净成绩104000且查询返回原罚时与已执行量() {
        seedRaceWithPenalty(100_000L, 10_000L);

        ServiceResult registered = raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-1", "p-1", "a", 1_000L, 7_000L, "ref-1", version(), "req-credit-1"));
        assertThat(registered.status()).isEqualTo(201);
        PenaltyCreditResponse credit = (PenaltyCreditResponse) registered.body();
        assertThat(credit.status()).isEqualTo(PenaltyCreditStatus.PENDING);
        assertThat(credit.durationMs()).isEqualTo(6_000L);
        assertThat(credit.creditedMs()).isNull();
        assertThat(credit.registeredBy()).isEqualTo("ref-1");

        // 确认前：全额罚时生效
        ResultEntryResponse before = raceService.getResults(RACE).entries().getFirst();
        assertThat(before.penaltyMs()).isEqualTo(10_000L);
        assertThat(before.servedMs()).isZero();
        assertThat(before.totalTimeMs()).isEqualTo(110_000L);

        ServiceResult confirmed = raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-confirm-1"));
        assertThat(confirmed.status()).isEqualTo(200);
        PenaltyCreditResponse confirmedCredit = (PenaltyCreditResponse) confirmed.body();
        assertThat(confirmedCredit.status()).isEqualTo(PenaltyCreditStatus.CONFIRMED);
        assertThat(confirmedCredit.creditedMs()).isEqualTo(6_000L);
        assertThat(confirmedCredit.confirmedBy()).isEqualTo("ref-2");
        assertThat(confirmedCredit.confirmedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        // 原罚时不改写，已执行量单独返回，净成绩=100000+10000-6000
        ResultEntryResponse after = raceService.getResults(RACE).entries().getFirst();
        assertThat(after.penaltyMs()).isEqualTo(10_000L);
        assertThat(after.servedMs()).isEqualTo(6_000L);
        assertThat(after.totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 重复确认不得再次抵扣() {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 1_000L, 7_000L, "ref-1", version(), "req-credit-1"));
        ConfirmPenaltyCreditRequest confirm =
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-confirm-1");
        ServiceResult first = raceService.confirmPenaltyCredit(RACE, "c-1", confirm);
        int versionAfterConfirm = version();

        // 同键同参重放：返回首次完整响应，不再抵扣、不推进版本
        ServiceResult replay = raceService.confirmPenaltyCredit(RACE, "c-1", confirm);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(version()).isEqualTo(versionAfterConfirm);

        // 不同 requestId 的重复确认：409
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-confirm-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已确认");

        ResultEntryResponse entry = raceService.getResults(RACE).entries().getFirst();
        assertThat(entry.servedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);
    }

    @Test
    void 跨选手引用_取消资格处罚_无效区间均422且不写入() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100_000L, 1, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 100_000L, 2, "req-reg-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-time", "a", "ADD_TIME", 10_000L, 3, "req-pen-time"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", "b", "DISQUALIFY", null, 4, "req-pen-dq"));
        int versionBefore = version();

        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-x", "p-time", "b", 0L, 1_000L, "ref-1", version(), "req-cx")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-y", "p-dq", "b", 0L, 1_000L, "ref-1", version(), "req-cy")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-z1", "p-time", "a", 1_000L, 1_000L, "ref-1", version(), "req-cz1")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-z2", "p-time", "a", 2_000L, 1_000L, "ref-1", version(), "req-cz2")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 凭证与抵扣均不得部分写入：无凭证落库、版本不变
        assertThat(repository.findCredits(RACE)).isEmpty();
        assertThat(version()).isEqualTo(versionBefore);
    }
    @Test
    void 撤销后未确认凭证不能确认_已确认凭证保留但净处罚为0_恢复不复活旧凭证() {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 6_000L, "ref-1", version(), "req-c1"));
        raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf1"));
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-2", "p-1", "a", 10_000L, 12_000L, "ref-1", version(), "req-c2"));

        raceService.revokePenalty(RACE, "p-1",
                new RevokePenaltyRequest(version(), "req-revoke"));

        // 已确认凭证保留
        PenaltyCreditRow kept = repository.findCredit("c-1").orElseThrow();
        assertThat(kept.status()).isEqualTo(PenaltyCreditStatus.CONFIRMED);
        assertThat(kept.creditedMs()).isEqualTo(6_000L);

        // 处罚撤销后净处罚为0
        ResultEntryResponse revoked = raceService.getResults(RACE).entries().getFirst();
        assertThat(revoked.penaltyMs()).isZero();
        assertThat(revoked.servedMs()).isZero();
        assertThat(revoked.totalTimeMs()).isEqualTo(100_000L);

        // 未确认凭证不能确认
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-2",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("撤销");
        assertThat(repository.findCredit("c-2").orElseThrow().status())
                .isEqualTo(PenaltyCreditStatus.PENDING);

        // 恢复处罚只允许新增处罚记录：新处罚不复活旧凭证的抵扣
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "a", "ADD_TIME", 10_000L, version(), "req-pen-2"));
        ResultEntryResponse restored = raceService.getResults(RACE).entries().getFirst();
        assertThat(restored.penaltyMs()).isEqualTo(10_000L);
        assertThat(restored.servedMs()).isZero();
        assertThat(restored.totalTimeMs()).isEqualTo(110_000L);
    }

    @Test
    void 同选手已确认区间不得重叠_相邻合法() {
        seedRaceWithPenalty(100_000L, 20_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 5_000L, "ref-1", version(), "req-c1"));
        raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf1"));

        // 相邻区间 [5000, 9000) 合法
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-2", "p-1", "a", 5_000L, 9_000L, "ref-1", version(), "req-c2"));
        ServiceResult adjacent = raceService.confirmPenaltyCredit(RACE, "c-2",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf2"));
        assertThat(adjacent.status()).isEqualTo(200);

        // 重叠区间 [4000, 6000) 拒绝，凭证保持待确认
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-3", "p-1", "a", 4_000L, 6_000L, "ref-1", version(), "req-c3"));
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-3",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重叠");
        assertThat(repository.findCredit("c-3").orElseThrow().status())
                .isEqualTo(PenaltyCreditStatus.PENDING);
    }

    @Test
    void 抵扣超出剩余量时按剩余量封顶不产生负处罚() {
        seedRaceWithPenalty(100_000L, 10_000L);
        // 凭证时长15000超过罚时10000：抵扣封顶为10000
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 15_000L, "ref-1", version(), "req-c1"));
        ServiceResult confirmed = raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf1"));
        assertThat(((PenaltyCreditResponse) confirmed.body()).creditedMs()).isEqualTo(10_000L);

        ResultEntryResponse entry = raceService.getResults(RACE).entries().getFirst();
        assertThat(entry.servedMs()).isEqualTo(10_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(100_000L);

        // 剩余量为0时再确认新凭证：抵扣为0，成绩不变
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-2", "p-1", "a", 20_000L, 25_000L, "ref-1", version(), "req-c2"));
        ServiceResult second = raceService.confirmPenaltyCredit(RACE, "c-2",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf2"));
        assertThat(((PenaltyCreditResponse) second.body()).creditedMs()).isZero();
        assertThat(raceService.getResults(RACE).entries().getFirst().totalTimeMs())
                .isEqualTo(100_000L);
    }

    @Test
    void 确认裁判必须与登记裁判不同() {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 6_000L, "ref-1", version(), "req-c1"));
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-1", version(), "req-cf1")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findCredit("c-1").orElseThrow().status())
                .isEqualTo(PenaltyCreditStatus.PENDING);
    }
    @Test
    void 封榜冻结抵扣且之后禁止新确认() {
        seedRaceWithPenalty(100_000L, 10_000L);
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 6_000L, "ref-1", version(), "req-c1"));
        raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf1"));
        raceService.registerPenaltyCredit(RACE, new RegisterPenaltyCreditRequest(
                "c-2", "p-1", "a", 10_000L, 12_000L, "ref-1", version(), "req-c2"));

        raceService.sealRace(RACE, new SealRaceRequest(version(), "req-seal"));

        // 快照冻结原罚时、已执行量与净成绩
        ResultEntryResponse entry = raceService.getSnapshot(RACE).entries().getFirst();
        assertThat(entry.penaltyMs()).isEqualTo(10_000L);
        assertThat(entry.servedMs()).isEqualTo(6_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(104_000L);
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);

        // 封榜后禁止新确认与登记
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-2",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-3", "p-1", "a", 20_000L, 21_000L, "ref-1", version(), "req-c3")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 登记与确认幂等_同键同参重放_异参409_失败不占键() {
        seedRaceWithPenalty(100_000L, 10_000L);

        // 失败不占键：无效区间业务失败后同 requestId 以正确参数重试成功
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-1", "p-1", "a", 5_000L, 5_000L, "ref-1", version(), "req-c1")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findIdempotency("req-c1")).isEmpty();

        RegisterPenaltyCreditRequest register = new RegisterPenaltyCreditRequest(
                "c-1", "p-1", "a", 0L, 6_000L, "ref-1", version(), "req-c1");
        ServiceResult first = raceService.registerPenaltyCredit(RACE, register);
        ServiceResult replay = raceService.registerPenaltyCredit(RACE, register);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(repository.findCredits(RACE)).hasSize(1);

        // 同键异参409：参数不同（含操作者）即冲突，不产生新凭证
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-1", "p-1", "a", 0L, 7_000L, "ref-1", version(), "req-c1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThatThrownBy(() -> raceService.registerPenaltyCredit(RACE,
                new RegisterPenaltyCreditRequest(
                        "c-1", "p-1", "a", 0L, 6_000L, "ref-9", version(), "req-c1")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findCredits(RACE)).hasSize(1);

        // 确认同样幂等：同键异参409
        raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-2", version(), "req-cf1"));
        assertThatThrownBy(() -> raceService.confirmPenaltyCredit(RACE, "c-1",
                new ConfirmPenaltyCreditRequest("ref-3", version(), "req-cf1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(raceService.getResults(RACE).entries().getFirst().servedMs())
                .isEqualTo(6_000L);
    }
}