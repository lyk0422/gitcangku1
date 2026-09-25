package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointsConfigResponse;
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
import com.example.starter.race.domain.WithdrawalStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退赛登记/撤销的 H2 数据库测试：前置校验、名次重排、撤销语义、
 * 封榜快照固化、退赛后写禁止与 requestId/withdrawalKey 双重幂等。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WithdrawalServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wd";

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
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new ArrayList<>();
        for (int i = 0; i < codeThenPosition.length; i += 2) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codeThenPosition[i], Integer.parseInt(codeThenPosition[i + 1])));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private void submit(String bib, String timingId, String code, long elapsed,
                        int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private WithdrawalResponse withdraw(String key, String bib, String status, String reason,
                                        String lastCp, int expectedVersion, String reqId) {
        ServiceResult result = raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest(key, bib, status, reason, lastCp,
                        expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (WithdrawalResponse) result.body();
    }

    private WithdrawalResponse revoke(String key, int expectedVersion, String reqId) {
        ServiceResult result = raceService.revokeWithdrawal(RACE, key,
                new RevokeWithdrawalRequest(expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(200);
        return (WithdrawalResponse) result.body();
    }

    private int currentVersion() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    @Test
    void DNS登记主流程_版本加一_排名排除_清单与状态查询() {
        createRace();
        registerRunner("a", 1000L, 1, "req-a");   // v2
        registerRunner("b", 2000L, 2, "req-b");   // v3
        registerRunner("c", null, 3, "req-c");    // v4

        WithdrawalResponse response = withdraw("w-c", "c", "DNS", "未到场", null, 4, "req-w-c");
        assertThat(response.withdrawalKey()).isEqualTo("w-c");
        assertThat(response.status()).isEqualTo(WithdrawalStatus.DNS);
        assertThat(response.reason()).isEqualTo("未到场");
        assertThat(response.revoked()).isFalse();
        assertThat(response.lastCheckpointCode()).isNull();
        assertThat(response.createdAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        assertThat(currentVersion()).isEqualTo(5);

        // 名次在剩余选手上从1连续编号；退赛选手单独列出且不占名次
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);
        assertThat(standing.entries().get(2).status()).isEqualTo(EntryStatus.DNS);
        assertThat(standing.entries().get(2).totalTimeMs()).isNull();

        // 退赛清单
        WithdrawalListResponse list = raceService.getWithdrawals(RACE);
        assertThat(list.version()).isEqualTo(5);
        assertThat(list.withdrawals()).extracting(WithdrawalResponse::withdrawalKey)
                .containsExactly("w-c");

        // 选手状态查询
        RunnerStatusResponse cStatus = raceService.getRunnerStatus(RACE, "c");
        assertThat(cStatus.status()).isEqualTo(EntryStatus.DNS);
        assertThat(cStatus.withdrawal().withdrawalKey()).isEqualTo("w-c");
        assertThat(cStatus.withdrawal().revoked()).isFalse();
        RunnerStatusResponse aStatus = raceService.getRunnerStatus(RACE, "a");
        assertThat(aStatus.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(aStatus.withdrawal()).isNull();
    }

    @Test
    void 同状态重复登记幂等返回首次结果_DNS与DNF相互改写409() {
        createRace();
        registerRunner("c", null, 1, "req-c");    // v2
        withdraw("w-c", "c", "DNS", "未到场", null, 2, "req-w-c"); // v3

        // 同状态重复登记（不同 withdrawalKey）：按幂等返回首次结果，版本不变、清单不增
        ServiceResult replay = raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-c-2", "c", "DNS", "其他原因", null,
                        3, "req-w-c-2"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(((WithdrawalResponse) replay.body()).withdrawalKey()).isEqualTo("w-c");
        assertThat(currentVersion()).isEqualTo(3);
        assertThat(raceService.getWithdrawals(RACE).withdrawals()).hasSize(1);

        // DNS -> DNF 改写一律409
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-c-3", "c", "DNF", "想改", "cp1",
                        3, "req-w-c-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("相互改写");
        assertThat(currentVersion()).isEqualTo(3);
    }

    @Test
    void DNF改写DNS同样409() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "cp1", "1");       // v3
        submit("a", "t-1", "cp1", 100L, 3, "req-t1"); // v4
        // 清除完赛计时，使“有分段、无完赛”前置成立
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", null, 4, "req-clear")); // v5
        withdraw("w-a", "a", "DNF", "受伤", "cp1", 5, "req-w-a"); // v6

        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a-2", "a", "DNS", "想改", null,
                        6, "req-w-a-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("相互改写");
    }

    @Test
    void withdrawalKey同参重放首次结果_异参409() {
        createRace();
        registerRunner("a", null, 1, "req-a");     // v2
        WithdrawalResponse first = withdraw("w-a", "a", "DNS", "未到场", null, 2, "req-w-a");

        // 同 withdrawalKey 同参数（不同 requestId）：重放首次结果
        ServiceResult replay = raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "未到场", null,
                        2, "req-w-a-replay"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first));
        assertThat(currentVersion()).isEqualTo(3);

        // 同 withdrawalKey 异参：409
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "别的原因", null,
                        3, "req-w-a-diff")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("withdrawalKey");
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "b", "DNS", "未到场", null,
                        3, "req-w-a-diff2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void DNF主流程_最后通过检查点校验_退赛后禁止分段完赛与加时() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "cp1", "1", "cp2", "2", "cp3", "3"); // v3
        submit("a", "t-1", "cp1", 100L, 3, "req-t1"); // v4
        submit("a", "t-2", "cp2", 200L, 4, "req-t2"); // v5
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", null, 5, "req-clear")); // v6

        // lastPassedCheckpoint 非顺序最大者 -> 422
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-1", "a", "DNF", "受伤", "cp1", 6, "req-w-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("顺序最大");
        // 未通过的检查点 -> 422
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-2", "a", "DNF", "受伤", "cp3", 6, "req-w-2")))
                .isInstanceOf(UnprocessableEntityException.class);
        // DNF 缺少 lastPassedCheckpoint -> 400
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-3", "a", "DNF", "受伤", null, 6, "req-w-3")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("lastPassedCheckpoint");

        WithdrawalResponse dnf = withdraw("w-a", "a", "DNF", "受伤", "cp2", 6, "req-w-a"); // v7
        assertThat(dnf.status()).isEqualTo(WithdrawalStatus.DNF);
        assertThat(dnf.lastCheckpointCode()).isEqualTo("cp2");

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries().get(0).status()).isEqualTo(EntryStatus.DNF);
        assertThat(standing.entries().get(0).rank()).isNull();
        assertThat(standing.entries().get(0).lastCheckpointCode()).isEqualTo("cp2");

        // 退赛后禁止新增分段、提交完赛计时、处罚加时
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-3", "cp3", 300L, 7, "req-t3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 9_999L, 7, "req-rev")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-add", "a", "ADD_TIME", 100L, 7, "req-p-add")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        // 取消资格不受退赛限制
        ServiceResult dq = raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", "a", "DISQUALIFY", null, 7, "req-p-dq"));
        assertThat(dq.status()).isEqualTo(201);
        // 退赛状态优先于取消资格展示
        assertThat(raceService.getResults(RACE).entries().get(0).status())
                .isEqualTo(EntryStatus.DNF);
        assertThat(currentVersion()).isEqualTo(8);
    }

    @Test
    void 退赛前置校验_无分段DNF422_有分段DNS422_完赛与取消资格409() {
        createRace();
        registerRunner("a", null, 1, "req-a");      // v2
        registerRunner("b", 5_000L, 2, "req-b");    // v3

        // DNF 要求至少一条分段记录 -> 422
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-1", "a", "DNF", "受伤", "cp1", 3, "req-w-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("至少一条分段记录");
        // 已有完赛计时 -> 409
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-2", "b", "DNS", "未到场", null, 3, "req-w-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("完赛计时");
        // DNS 不得携带 lastPassedCheckpoint -> 400
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-3", "a", "DNS", "未到场", "cp1", 3, "req-w-3")))
                .isInstanceOf(BadRequestException.class);
        // 未知退赛状态 -> 400
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-4", "a", "QUIT", "未到场", null, 3, "req-w-4")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("未知退赛状态");
        // 已取消资格 -> 409
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", "a", "DISQUALIFY", null, 3, "req-p-dq")); // v4
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-5", "a", "DNS", "未到场", null, 4, "req-w-5")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");
        // 选手不存在 -> 404
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-6", "ghost", "DNS", "未到场", null, 4, "req-w-6")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void DNS已有分段记录返回422() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "cp1", "1");       // v3
        submit("a", "t-1", "cp1", 100L, 3, "req-t1"); // v4
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", null, 4, "req-clear")); // v5

        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "未到场", null, 5, "req-w-a")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("尚无任何分段记录");
    }

    @Test
    void 撤销退赛恢复UNTIMED_不可再次撤销_撤销后可重新登记与提交完赛() {
        createRace();
        registerRunner("a", null, 1, "req-a");      // v2
        withdraw("w-1", "a", "DNS", "未到场", null, 2, "req-w-1"); // v3
        assertThat(raceService.getRunnerStatus(RACE, "a").status()).isEqualTo(EntryStatus.DNS);

        WithdrawalResponse revoked = revoke("w-1", 3, "req-rv-1"); // v4
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        // 撤销后回到 UNTIMED，撤销记录不可变地保留
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(status.withdrawal().withdrawalKey()).isEqualTo("w-1");
        assertThat(status.withdrawal().revoked()).isTrue();

        // 已撤销的退赛不能再次撤销
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-1",
                new RevokeWithdrawalRequest(4, "req-rv-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤销");
        // 撤销不存在的退赛 -> 404
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-ghost",
                new RevokeWithdrawalRequest(4, "req-rv-ghost")))
                .isInstanceOf(NotFoundException.class);

        // 撤销后可用新 withdrawalKey 重新登记，撤销后也可再次撤销新登记
        withdraw("w-2", "a", "DNS", "再次未到场", null, 4, "req-w-2"); // v5
        revoke("w-2", 5, "req-rv-2"); // v6
        // 彻底恢复：可提交完赛计时
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 5_000L, 6, "req-time")); // v7
        assertThat(raceService.getRunnerStatus(RACE, "a").status()).isEqualTo(EntryStatus.RANKED);
        // 退赛清单保留全部历史（含已撤销）
        assertThat(raceService.getWithdrawals(RACE).withdrawals())
                .extracting(WithdrawalResponse::withdrawalKey)
                .containsExactly("w-1", "w-2");
    }

    @Test
    void 撤销DNF后回到UNTIMED_补完赛后为MISSING_CHECKPOINT() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "cp1", "1", "cp2", "2"); // v3
        submit("a", "t-1", "cp1", 100L, 3, "req-t1"); // v4
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", null, 4, "req-clear")); // v5
        withdraw("w-a", "a", "DNF", "受伤", "cp1", 5, "req-w-a"); // v6
        assertThat(raceService.getRunnerStatus(RACE, "a").status()).isEqualTo(EntryStatus.DNF);

        revoke("w-a", 6, "req-rv-a"); // v7
        // 撤销后无完赛计时 -> UNTIMED
        assertThat(raceService.getRunnerStatus(RACE, "a").status()).isEqualTo(EntryStatus.UNTIMED);
        // 重新提交完赛计时（既有分段保留且只读，新完赛耗时须大于分段耗时）
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 5_000L, 7, "req-time")); // v8
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(status.missingCheckpoints()).containsExactly("cp2");
        assertThat(status.lastCheckpointCode()).isEqualTo("cp1");
    }

    @Test
    void 名次重排_退赛选手不参与并列跳号() {
        createRace();
        registerRunner("a", 1000L, 1, "req-a");   // v2
        registerRunner("b", 1000L, 2, "req-b");   // v3
        registerRunner("c", 2000L, 3, "req-c");   // v4
        registerRunner("d", 500L, 4, "req-d");    // v5

        // d 原本第1，DNS 后名次在剩余选手上重排：a、b 并列第1，c 第3
        // d 有完赛计时不能登记 -> 先清除计时使其满足 DNS 前置
        raceService.reviseTime(RACE, new ReviseTimeRequest("d", null, 5, "req-clear-d")); // v6
        withdraw("w-d", "d", "DNS", "未到场", null, 6, "req-w-d"); // v7

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c", "d");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3, null);
        assertThat(standing.entries().get(3).status()).isEqualTo(EntryStatus.DNS);
    }

    @Test
    void 封榜快照固化退赛状态与名次_封榜后禁止新增或撤销退赛() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        registerRunner("b", 20_000L, 2, "req-b");   // v3
        registerRunner("c", null, 3, "req-c");      // v4
        registerRunner("d", 30_000L, 4, "req-d");   // v5
        configure(5, "req-cfg", "cp1", "1", "cp2", "2", "cp3", "3"); // v6
        submit("a", "t-a1", "cp1", 100L, 6, "req-ta1"); // v7
        submit("a", "t-a2", "cp2", 200L, 7, "req-ta2"); // v8
        submit("a", "t-a3", "cp3", 300L, 8, "req-ta3"); // v9
        submit("b", "t-b1", "cp1", 100L, 9, "req-tb1"); // v10
        submit("b", "t-b2", "cp2", 200L, 10, "req-tb2"); // v11
        raceService.reviseTime(RACE, new ReviseTimeRequest("b", null, 11, "req-clear-b")); // v12
        withdraw("w-b", "b", "DNF", "受伤", "cp2", 12, "req-w-b"); // v13
        withdraw("w-c", "c", "DNS", "未到场", null, 13, "req-w-c"); // v14
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", "d", "DISQUALIFY", null, 14, "req-p-dq")); // v15

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(15, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        // 展示顺序：排名区 a，其后按参赛号字典序 b(DNF)、c(DNS)、d(DISQUALIFIED)
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c", "d");
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.DNF,
                        EntryStatus.DNS, EntryStatus.DISQUALIFIED);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, null, null, null);
        // 封榜固化最后通过检查点与缺失检查点
        assertThat(snapshot.entries().get(0).lastCheckpointCode()).isEqualTo("cp3");
        assertThat(snapshot.entries().get(1).lastCheckpointCode()).isEqualTo("cp2");
        assertThat(snapshot.entries().get(1).missingCheckpoints()).containsExactly("cp3");
        assertThat(snapshot.entries().get(2).missingCheckpoints())
                .containsExactly("cp1", "cp2", "cp3");

        // 封榜后禁止新增或撤销退赛
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-d", "d", "DNS", "未到场", null, 16, "req-w-d")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "w-b",
                new RevokeWithdrawalRequest(16, "req-rv-b")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 封榜后清单与选手状态仍可读，且来自一致快照
        assertThat(raceService.getWithdrawals(RACE).withdrawals())
                .extracting(WithdrawalResponse::withdrawalKey)
                .containsExactly("w-b", "w-c");
        RunnerStatusResponse bStatus = raceService.getRunnerStatus(RACE, "b");
        assertThat(bStatus.status()).isEqualTo(EntryStatus.DNF);
        assertThat(bStatus.lastCheckpointCode()).isEqualTo("cp2");
        assertThat(bStatus.missingCheckpoints()).containsExactly("cp3");
        assertThat(bStatus.withdrawal().withdrawalKey()).isEqualTo("w-b");
        assertThat(raceService.getResults(RACE)).usingRecursiveComparison().isEqualTo(snapshot);
    }

    @Test
    void 退赛requestId幂等_同参重放异参409失败不占键() {
        createRace();
        registerRunner("a", null, 1, "req-a");      // v2

        // 业务失败（版本冲突）不占 requestId 键
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "未到场", null,
                        999, "req-w-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-w-a")).isEmpty();

        // 同 requestId 正确参数重试成功
        ServiceResult first = raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "未到场", null,
                        2, "req-w-a"));
        assertThat(first.status()).isEqualTo(201);
        // 同 requestId 同参重放首次结果
        ServiceResult replay = raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "未到场", null,
                        2, "req-w-a"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 同 requestId 异参 409
        assertThatThrownBy(() -> raceService.registerWithdrawal(RACE,
                new RegisterWithdrawalRequest("w-a", "a", "DNS", "改原因", null,
                        2, "req-w-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(currentVersion()).isEqualTo(3);
    }

    @Test
    void 清除完赛计时后回到UNTIMED且既有分段保留() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");  // v2
        configure(2, "req-cfg", "cp1", "1");       // v3
        submit("a", "t-1", "cp1", 100L, 3, "req-t1"); // v4

        raceService.reviseTime(RACE, new ReviseTimeRequest("a", null, 4, "req-clear")); // v5
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(status.finishTimeMs()).isNull();
        // 既有分段记录保留
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .filteredOn(p -> p.elapsedMillis() != null).hasSize(1);
        // 清除后不可再提交分段（无完赛耗时）
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-2", "cp1", 200L, 5, "req-t2")))
                .isInstanceOf(UnprocessableEntityException.class);
    }
}
