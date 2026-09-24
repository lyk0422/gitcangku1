package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RegisterWithdrawalRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokeWithdrawalRequest;
import com.example.starter.race.api.RunnerStatusResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawalListResponse;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.WithdrawalStatus;
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
 * 退赛登记与撤销的 H2 数据库测试：前置校验、幂等语义、名次重排、
 * 退赛后写禁令、撤销恢复与封榜快照固化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WithdrawalServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wd";

    @Autowired
    private RaceService raceService;

    private void createRaceWithCheckpoints() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                1, "req-cps"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int expectedVersion) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, "req-reg-" + bib));
    }

    private void submitTiming(String bib, String code, long elapsed, int expectedVersion) {
        raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                "t-" + bib + "-" + code, code, elapsed, expectedVersion,
                "req-t-" + bib + "-" + code));
    }

    private ServiceResult registerWithdrawal(
            String bib, String status, String lastCheckpointCode,
            int expectedVersion, String requestId, String withdrawalKey) {
        return raceService.registerWithdrawal(RACE, new RegisterWithdrawalRequest(
                bib, status, "受伤退赛", lastCheckpointCode,
                expectedVersion, requestId, withdrawalKey));
    }

    @Test
    void DNS登记主流程_版本加一且退赛选手不占名次() {
        createRaceWithCheckpoints();
        registerRunner("a", 1000L, 2);
        registerRunner("b", 2000L, 3);
        registerRunner("c", null, 4);
        submitTiming("a", "cp1", 100L, 5);
        submitTiming("a", "cp2", 200L, 6);
        submitTiming("b", "cp1", 300L, 7);
        submitTiming("b", "cp2", 400L, 8);

        StandingResponse before = raceService.getResults(RACE);
        assertThat(before.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);

        ServiceResult result = registerWithdrawal("c", "DNS", null, 9, "req-wd-c", "w-c");
        assertThat(result.status()).isEqualTo(201);
        WithdrawalResponse body = (WithdrawalResponse) result.body();
        assertThat(body.withdrawalKey()).isEqualTo("w-c");
        assertThat(body.status()).isEqualTo(WithdrawalStatus.DNS);
        assertThat(body.reason()).isEqualTo("受伤退赛");
        assertThat(body.revoked()).isFalse();
        assertThat(body.lastCheckpointCode()).isNull();

        // 登记成功赛事版本加一；退赛选手单独列出且不占名次，名次序列不受影响
        StandingResponse after = raceService.getResults(RACE);
        assertThat(after.version()).isEqualTo(10);
        assertThat(after.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(after.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);
        assertThat(after.entries().get(2).status()).isEqualTo(EntryStatus.DNS);

        WithdrawalListResponse list = raceService.getWithdrawals(RACE);
        assertThat(list.withdrawals()).hasSize(1);
        assertThat(list.withdrawals().getFirst().withdrawalKey()).isEqualTo("w-c");

        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "c");
        assertThat(status.status()).isEqualTo(EntryStatus.DNS);
        assertThat(status.activeWithdrawal().withdrawalKey()).isEqualTo("w-c");
        assertThat(status.missingCheckpoints()).containsExactly("cp1", "cp2");
    }

    @Test
    void DNF登记要求最后通过检查点为已有记录中顺序最大者() {
        createRaceWithCheckpoints();
        registerRunner("a", null, 2);
        submitTiming("a", "cp1", 100L, 3);
        submitTiming("a", "cp2", 200L, 4);

        // 指定非顺序最大检查点 -> 422
        assertThatThrownBy(() -> registerWithdrawal("a", "DNF", "cp1", 5, "req-wd-1", "w-1"))
                .isInstanceOf(UnprocessableEntityException.class);
        // 未携带最后通过检查点 -> 400
        assertThatThrownBy(() -> registerWithdrawal("a", "DNF", null, 5, "req-wd-2", "w-2"))
                .isInstanceOf(BadRequestException.class);
        // 指定顺序最大检查点 -> 201，版本加一
        ServiceResult result = registerWithdrawal("a", "DNF", "cp2", 5, "req-wd-3", "w-3");
        assertThat(result.status()).isEqualTo(201);
        WithdrawalResponse body = (WithdrawalResponse) result.body();
        assertThat(body.status()).isEqualTo(WithdrawalStatus.DNF);
        assertThat(body.lastCheckpointCode()).isEqualTo("cp2");
        assertThat(raceService.getResults(RACE).version()).isEqualTo(6);

        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.DNF);
        assertThat(status.lastCheckpointCode()).isEqualTo("cp2");
        // 既有分段记录保留且只读
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .extracting("checkpointCode")
                .containsExactly("cp1", "cp2");
    }

    @Test
    void 退赛前置校验失败分支() {
        createRaceWithCheckpoints();
        registerRunner("timed", null, 2);
        submitTiming("timed", "cp1", 100L, 3);
        registerRunner("finished", 1000L, 4);
        registerRunner("dq", null, 5);
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "dq", "DISQUALIFY", null, 6, "req-pen-dq"));

        // DNS 要求无任何分段记录 -> 422
        assertThatThrownBy(() -> registerWithdrawal("timed", "DNS", null, 7, "req-1", "w-1"))
                .isInstanceOf(UnprocessableEntityException.class);
        // DNF 要求至少一条分段记录 -> 422
        assertThatThrownBy(() -> registerWithdrawal("dq", "DNF", "cp1", 7, "req-2", "w-2"))
                .isInstanceOf(ConflictException.class); // dq 先命中取消资格409
        // 已有完赛计时 -> 409
        assertThatThrownBy(() -> registerWithdrawal("finished", "DNS", null, 7, "req-3", "w-3"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> registerWithdrawal("finished", "DNF", "cp1", 7, "req-4", "w-4"))
                .isInstanceOf(ConflictException.class);
        // 不存在的选手 -> 404
        assertThatThrownBy(() -> registerWithdrawal("ghost", "DNS", null, 7, "req-4b", "w-4b"))
                .isInstanceOf(NotFoundException.class);
        // 已取消资格 -> 409
        assertThatThrownBy(() -> registerWithdrawal("dq", "DNS", null, 7, "req-5", "w-5"))
                .isInstanceOf(ConflictException.class);
        // DNS 不得携带最后通过检查点 -> 400
        assertThatThrownBy(() -> registerWithdrawal("timed", "DNS", "cp1", 7, "req-6", "w-6"))
                .isInstanceOf(BadRequestException.class);
        // 空白原因 -> 400
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("timed", "DNF", "  ", "cp1", 7, "req-7", "w-7")))
                .isInstanceOf(BadRequestException.class);
        // 未知退赛状态 -> 400
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("timed", "QUIT", "受伤", "cp1", 7, "req-8", "w-8")))
                .isInstanceOf(BadRequestException.class);
        // 失败不占键：版本冲突修正后可复用同一 requestId
        assertThatThrownBy(() -> registerWithdrawal("timed", "DNF", "cp1", 99, "req-9", "w-9"))
                .isInstanceOf(ConflictException.class);
        ServiceResult ok = registerWithdrawal("timed", "DNF", "cp1", 7, "req-9", "w-9");
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    void 同状态重复登记幂等返回首次结果_DNS与DNF相互改写409() {
        createRaceWithCheckpoints();
        registerRunner("a", null, 2);
        registerRunner("b", null, 3);
        submitTiming("b", "cp1", 100L, 4);

        ServiceResult first = registerWithdrawal("a", "DNS", null, 5, "req-wd-a1", "w-a1");
        assertThat(first.status()).isEqualTo(201);
        int versionAfterFirst = raceService.getResults(RACE).version();

        // 同状态重复登记（不同 withdrawalKey/requestId）：幂等返回首次结果，版本不再推进
        ServiceResult repeated = registerWithdrawal("a", "DNS", null, 6, "req-wd-a2", "w-a2");
        assertThat(repeated.status()).isEqualTo(201);
        WithdrawalResponse repeatedBody = (WithdrawalResponse) repeated.body();
        assertThat(repeatedBody.withdrawalKey()).isEqualTo("w-a1");
        assertThat(raceService.getResults(RACE).version()).isEqualTo(versionAfterFirst);
        assertThat(raceService.getWithdrawals(RACE).withdrawals()).hasSize(1);

        // DNS -> DNF 改写一律409
        assertThatThrownBy(() -> registerWithdrawal("a", "DNF", "cp1", 6, "req-wd-a3", "w-a3"))
                .isInstanceOf(ConflictException.class);

        // DNF -> DNS 改写一律409
        registerWithdrawal("b", "DNF", "cp1", 6, "req-wd-b1", "w-b1");
        assertThatThrownBy(() -> registerWithdrawal("b", "DNS", null, 7, "req-wd-b2", "w-b2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void withdrawalKey与requestId幂等语义() {
        createRaceWithCheckpoints();
        registerRunner("a", null, 2);

        ServiceResult first = registerWithdrawal("a", "DNS", null, 3, "req-w1", "w-1");
        assertThat(first.status()).isEqualTo(201);

        // 相同 requestId 同参重放首次结果
        ServiceResult replay = registerWithdrawal("a", "DNS", null, 3, "req-w1", "w-1");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(jsonOf(replay.body())).isEqualTo(jsonOf(first.body()));

        // 相同 requestId 异参 -> 409
        assertThatThrownBy(() -> registerWithdrawal("a", "DNS", null, 3, "req-w1", "w-other"))
                .isInstanceOf(ConflictException.class);

        // 相同 withdrawalKey 同参（不同 requestId）-> 重放首次结果
        ServiceResult keyReplay = registerWithdrawal("a", "DNS", null, 4, "req-w2", "w-1");
        assertThat(keyReplay.status()).isEqualTo(201);
        assertThat(jsonOf(keyReplay.body())).isEqualTo(jsonOf(first.body()));

        // 相同 withdrawalKey 异参 -> 409
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest(
                        "a", "DNS", "另一个原因", null, 4, "req-w3", "w-1")))
                .isInstanceOf(ConflictException.class);

        // 幂等重放不推进版本
        assertThat(raceService.getResults(RACE).version()).isEqualTo(4);
        assertThat(raceService.getWithdrawals(RACE).withdrawals()).hasSize(1);
    }

    @Test
    void 退赛后禁止新增分段提交完赛计时和处罚加时() {
        createRaceWithCheckpoints();
        registerRunner("a", null, 2);
        submitTiming("a", "cp1", 100L, 3);
        registerWithdrawal("a", "DNF", "cp1", 4, "req-wd-a", "w-a");
        int version = raceService.getResults(RACE).version();

        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-a-cp2", "cp2", 200L, version, "req-t-a2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 1000L, version, "req-rev-a")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-a", "a", "ADD_TIME", 100L, version, "req-pen-a")))
                .isInstanceOf(ConflictException.class);

        // 禁令不写入数据，版本保持不变
        assertThat(raceService.getResults(RACE).version()).isEqualTo(version);
        assertThat(repository.findTimingsForRunner(RACE, "a")).hasSize(1);
    }

    @Test
    void 撤销退赛恢复状态且撤销记录不可变() {
        createRaceWithCheckpoints();
        registerRunner("a", null, 2);
        registerWithdrawal("a", "DNS", null, 3, "req-wd-a", "w-a");
        assertThat(raceService.getRunnerStatus(RACE, "a").status()).isEqualTo(EntryStatus.DNS);

        ServiceResult revoked = raceService.revokeWithdrawal(RACE, "w-a",
                new RevokeWithdrawalRequest(4, "req-revoke-a"));
        assertThat(revoked.status()).isEqualTo(200);
        WithdrawalResponse body = (WithdrawalResponse) revoked.body();
        assertThat(body.revoked()).isTrue();
        assertThat(body.revokedAt()).isNotNull();

        // 撤销后回到 UNTIMED，版本加一，可重新提交计时
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(status.activeWithdrawal()).isNull();
        assertThat(raceService.getResults(RACE).version()).isEqualTo(5);
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 1500L, 5, "req-rev-time"));
        assertThat(raceService.getRunnerStatus(RACE, "a").status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);

        // 已撤销的退赛不能再次撤销 -> 409
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-a",
                new RevokeWithdrawalRequest(6, "req-revoke-a2")))
                .isInstanceOf(ConflictException.class);
        // 不存在的退赛键 -> 404
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-none",
                new RevokeWithdrawalRequest(6, "req-revoke-none")))
                .isInstanceOf(NotFoundException.class);
        // 撤销记录保留在退赛清单中
        assertThat(raceService.getWithdrawals(RACE).withdrawals())
                .extracting(WithdrawalResponse::revoked)
                .containsExactly(true);
    }

    @Test
    void 封榜快照固化退赛状态与最后通过检查点_封榜后禁止退赛写操作() {
        createRaceWithCheckpoints();
        registerRunner("a", 1000L, 2);
        registerRunner("b", null, 3);
        registerRunner("c", null, 4);
        submitTiming("a", "cp1", 100L, 5);
        submitTiming("a", "cp2", 200L, 6);
        submitTiming("b", "cp1", 150L, 7);
        registerWithdrawal("b", "DNF", "cp1", 8, "req-wd-b", "w-b");
        registerWithdrawal("c", "DNS", null, 9, "req-wd-c", "w-c");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(10, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.DNF, EntryStatus.DNS);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, null, null);
        // 最后通过检查点与缺失检查点在一一致快照中固化
        assertThat(snapshot.entries().get(0).lastCheckpointCode()).isEqualTo("cp2");
        assertThat(snapshot.entries().get(1).lastCheckpointCode()).isEqualTo("cp1");
        assertThat(snapshot.entries().get(1).missingCheckpoints()).containsExactly("cp2");
        assertThat(snapshot.entries().get(2).lastCheckpointCode()).isNull();
        assertThat(snapshot.entries().get(2).missingCheckpoints()).containsExactly("cp1", "cp2");

        // 封榜后禁止新增或撤销退赛
        assertThatThrownBy(() -> registerWithdrawal("c", "DNS", null, 11, "req-wd-late", "w-late"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-b",
                new RevokeWithdrawalRequest(11, "req-revoke-late")))
                .isInstanceOf(ConflictException.class);
    }

    private String jsonOf(Object body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
