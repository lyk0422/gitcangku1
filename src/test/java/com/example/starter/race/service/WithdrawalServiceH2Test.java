package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokeWithdrawalRequest;
import com.example.starter.race.api.RunnerStatusResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawalListResponse;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 退赛登记/撤销的 H2 数据库测试：DNS/DNF 前置校验、名次重排、只读阻断、
 * 撤销语义、封榜快照固化、requestId/withdrawalKey 双重幂等等关键边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WithdrawalServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wd";

    @Autowired
    private RaceService raceService;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void register(String bib, Long finishTimeMs, int version, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, version, reqId));
    }

    private void configureCheckpoints(int version, String reqId, String... codeThenPosition) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new ArrayList<>();
        for (int i = 0; i < codeThenPosition.length; i += 2) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codeThenPosition[i], Integer.parseInt(codeThenPosition[i + 1])));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, version, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private void submitTiming(String bib, String timingId, String code, long elapsed,
                              int version, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private WithdrawalResponse dns(String bib, String key, int version, String reqId) {
        return withdraw(bib, key, "DNS", "not started", null, version, reqId);
    }

    private WithdrawalResponse dnf(String bib, String key, String lastCheckpoint,
                                   int version, String reqId) {
        return withdraw(bib, key, "DNF", "retired mid-race", lastCheckpoint, version, reqId);
    }

    private WithdrawalResponse withdraw(String bib, String key, String status, String reason,
                                        String lastCheckpoint, int version, String reqId) {
        ServiceResult result = raceService.withdrawRunner(RACE, bib,
                new WithdrawRunnerRequest(key, status, reason, lastCheckpoint, version, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (WithdrawalResponse) result.body();
    }

    @Test
    void DNS登记要求无分段无完赛并排除出排名() {
        createRace();
        register("a", 1000L, 1, "req-a");   // v2
        register("b", 2000L, 2, "req-b");   // v3
        register("c", null, 3, "req-c");    // v4

        WithdrawalResponse response = dns("c", "key-c", 4, "req-wd-c"); // v5

        assertThat(response.status()).isEqualTo("DNS");
        assertThat(response.revoked()).isFalse();
        assertThat(response.lastCheckpointCode()).isNull();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);
        assertThat(standing.entries().get(2).status()).isEqualTo(EntryStatus.DNS);
    }

    @Test
    void DNS在已有分段时返回422且不推进版本() {
        createRace();
        register("a", null, 1, "req-a");
        configureCheckpoints(2, "req-cfg", "c1", "1", "c2", "2");
        // 无完赛计时也可先提交途中分段
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));

        assertThatThrownBy(() -> dns("a", "key-a", 4, "req-wd-a"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("DNS");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void DNF登记要求至少一条分段且最后检查点为顺序最大者否则422() {
        createRace();
        register("a", null, 1, "req-a");
        configureCheckpoints(2, "req-cfg", "c1", "1", "c2", "2", "c3", "3");
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-3", "c3", 300L, 4, "req-t3"));

        // 指定非最大顺序检查点 c1 -> 422
        assertThatThrownBy(() -> dnf("a", "key-a-bad", "c1", 5, "req-wd-bad"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("顺序最大");
        // 无分段的选手 DNF -> 422
        register("b", null, 5, "req-b");
        assertThatThrownBy(() -> dnf("b", "key-b", "c1", 6, "req-wd-b"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("至少已有一条分段");

        // 指定顺序最大者 c3 -> 成功，版本仅加一
        WithdrawalResponse response = dnf("a", "key-a", "c3", 6, "req-wd-a");
        assertThat(response.lastCheckpointCode()).isEqualTo("c3");
        assertThat(response.lastCheckpointPosition()).isEqualTo(3);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void DNF缺少最后检查点返回400() {
        createRace();
        register("a", null, 1, "req-a");
        configureCheckpoints(2, "req-cfg", "c1", "1");
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));

        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("key-a", "DNF", "reason", null, 4, "req-wd")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("最后通过的检查点");
    }

    @Test
    void 已取消资格或已有完赛计时登记退赛返回409() {
        createRace();
        register("a", 1000L, 1, "req-a");
        register("b", null, 2, "req-b");
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-dq", "b", "DISQUALIFY", null, 3, "req-dq"));

        // 已有完赛计时 -> 409
        assertThatThrownBy(() -> dns("a", "key-a", 4, "req-wd-a"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("完赛计时");
        // 已取消资格 -> 409
        assertThatThrownBy(() -> dns("b", "key-b", 4, "req-wd-b"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");
    }

    @Test
    void DNS与DNF相互改写一律409() {
        createRace();
        register("a", null, 1, "req-a");

        dns("a", "key-dns", 2, "req-dns"); // v3
        // 同选手改登记 DNF（即使使用新键）-> 409
        assertThatThrownBy(() -> dnf("a", "key-dnf", "c1", 3, "req-dnf"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不能改写");
    }

    @Test
    void 同状态同键重复登记幂等返回首次结果且版本不再增加() {
        createRace();
        register("a", null, 1, "req-a");
        WithdrawalResponse first = dns("a", "key-a", 2, "req-wd-1");

        // 同 withdrawalKey 同参，但使用不同 requestId：第二层键幂等返回首次结果
        WithdrawalResponse again = dns("a", "key-a", 99, "req-wd-2");
        assertThat(again).usingRecursiveComparison().isEqualTo(first);
        // 同一 requestId 重放：requestId 层幂等，同样返回 201
        ServiceResult replayed = raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("key-a", "DNS", "not started", null, 2, "req-wd-1"));
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findWithdrawals(RACE)).hasSize(1);
    }

    @Test
    void 同withdrawalKey异参返回409且失败不占requestId() {
        createRace();
        register("a", null, 1, "req-a");
        dns("a", "key-a", 2, "req-wd");

        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("key-a", "DNS", "different reason", null, 3,
                        "req-wd-diff")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");

        // 业务失败不占 requestId：该 requestId 可用于另一成功操作（此处撤销）
        raceService.revokeWithdrawal(RACE, "a",
                new RevokeWithdrawalRequest("key-a", 3, "req-wd-diff"));
        assertThat(repository.findWithdrawalByKey("key-a").orElseThrow().revoked()).isTrue();
    }

    @Test
    void 退赛后禁止新增分段提交完赛计时与处罚() {
        createRace();
        register("a", null, 1, "req-a");
        configureCheckpoints(2, "req-cfg", "c1", "1");
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));
        dnf("a", "key-a", "c1", 4, "req-wd"); // v5

        // 禁止新增分段（退赛只读守卫先于分段校验，返回409）
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-x", "c1", 200L, 5, "req-tx")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("只读");
        // 禁止提交完赛计时
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 500L, 5, "req-finish")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("只读");
        // 禁止处罚加时
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("pen-1", "a", "ADD_TIME", 100L, 5, "req-pen")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("只读");
        // 既有分段记录保留且可读
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .hasSize(1);
    }

    @Test
    void 撤销退赛后回到UNTIMED且记录保留不可变可重新登记() {
        createRace();
        register("a", null, 1, "req-a");
        dns("a", "key-a", 2, "req-wd"); // v3

        ServiceResult revoked = raceService.revokeWithdrawal(RACE, "a",
                new RevokeWithdrawalRequest("key-a", 3, "req-revoke")); // v4
        assertThat(revoked.status()).isEqualTo(200);
        WithdrawalResponse revokedResponse = (WithdrawalResponse) revoked.body();
        assertThat(revokedResponse.revoked()).isTrue();
        assertThat(revokedResponse.revokedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        // 回到 UNTIMED，不占名次
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(status.withdrawal()).isNull();

        // 已撤销的退赛不能再次撤销
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "a",
                new RevokeWithdrawalRequest("key-a", 4, "req-revoke-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不能再次撤销");

        // 撤销后可用新键重新登记 DNS；历史行仍保留
        dns("a", "key-a-2", 4, "req-wd-2");
        WithdrawalListResponse list = raceService.getWithdrawals(RACE);
        assertThat(list.withdrawals()).hasSize(2);
        assertThat(list.withdrawals()).extracting(WithdrawalResponse::revoked)
                .containsExactly(true, false);
    }

    @Test
    void 撤销版本不匹配返回409且不撤销() {
        createRace();
        register("a", null, 1, "req-a");
        dns("a", "key-a", 2, "req-wd"); // v3

        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "a",
                new RevokeWithdrawalRequest("key-a", 2, "req-revoke-stale")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findWithdrawalByKey("key-a").orElseThrow().revoked()).isFalse();
    }

    @Test
    void 退赛后退赛者单独列出且剩余选手名次保持连续() {
        createRace();
        register("b1", 1000L, 1, "req-b1");
        register("b2", 1000L, 2, "req-b2");
        register("c", 1500L, 3, "req-c");
        register("d", null, 4, "req-d");
        register("e", null, 5, "req-e");
        configureCheckpoints(6, "req-cfg", "c1", "1");
        // 已完赛选手覆盖 c1 才能保持 RANKED；e 只有途中分段、无完赛计时
        raceService.submitTiming(RACE, "b1",
                new SubmitTimingRequest("t-b1", "c1", 100L, 7, "req-tb1"));
        raceService.submitTiming(RACE, "b2",
                new SubmitTimingRequest("t-b2", "c1", 100L, 8, "req-tb2"));
        raceService.submitTiming(RACE, "c",
                new SubmitTimingRequest("t-c", "c1", 100L, 9, "req-tc"));
        raceService.submitTiming(RACE, "e",
                new SubmitTimingRequest("t-e", "c1", 100L, 10, "req-te"));

        StandingResponse before = raceService.getResults(RACE);
        assertThat(before.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3, null, null);

        dns("d", "key-d", 11, "req-wd-d");   // v12
        dnf("e", "key-e", "c1", 12, "req-wd-e"); // v13
        StandingResponse after = raceService.getResults(RACE);
        // 剩余选手 b1/b2/c 名次仍从1连续（1、1、3），退赛者单独列在末尾不占名次
        assertThat(after.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b1", "b2", "c", "d", "e");
        assertThat(after.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3, null, null);
        assertThat(after.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.DNS, EntryStatus.DNF);
        assertThat(after.entries().get(4).lastCheckpointCode()).isEqualTo("c1");

        // 撤销 DNS 后 d 回到 UNTIMED，仍不占名次，但不再处于退赛分组语义
        raceService.revokeWithdrawal(RACE, "d",
                new RevokeWithdrawalRequest("key-d", 13, "req-revoke-d"));
        StandingResponse afterRevoke = raceService.getResults(RACE);
        assertThat(afterRevoke.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b1", "b2", "c", "d", "e");
        assertThat(afterRevoke.entries().get(3).status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(afterRevoke.entries().get(4).status()).isEqualTo(EntryStatus.DNF);
    }

    @Test
    void 封榜快照固化退赛状态最后检查点与缺失检查点且封榜后禁止退赛变更() {
        createRace();
        register("a", null, 1, "req-a");
        register("b", 2000L, 2, "req-b");
        configureCheckpoints(3, "req-cfg", "c1", "1", "c2", "2");
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 4, "req-t1"));
        dnf("a", "key-a", "c1", 5, "req-wd-a"); // v6

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(6, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = (StandingResponse) sealed.body();
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.version()).isEqualTo(7);
        // b 第1；a 为 DNF 单独列后，固化最后检查点与缺失检查点
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        ResultEntryResponse dnfEntry = snapshot.entries().get(1);
        assertThat(dnfEntry.status()).isEqualTo(EntryStatus.DNF);
        assertThat(dnfEntry.rank()).isNull();
        assertThat(dnfEntry.lastCheckpointCode()).isEqualTo("c1");
        assertThat(dnfEntry.missingCheckpoints()).containsExactly("c2");

        // 封榜后禁止新增或撤销退赛
        assertThatThrownBy(() -> dns("b", "key-b", 7, "req-wd-b"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.revokeWithdrawal(RACE, "a",
                new RevokeWithdrawalRequest("key-a", 7, "req-revoke")))
                .isInstanceOf(ConflictException.class);

        // 选手状态查询走快照
        RunnerStatusResponse status = raceService.getRunnerStatus(RACE, "a");
        assertThat(status.status()).isEqualTo(EntryStatus.DNF);
        assertThat(status.lastCheckpointCode()).isEqualTo("c1");
        assertThat(status.missingCheckpoints()).containsExactly("c2");
        assertThat(status.withdrawal().withdrawalKey()).isEqualTo("key-a");
    }

    @Test
    void 选手状态查询返回最后通过检查点与缺失检查点() {
        createRace();
        register("a", null, 1, "req-a");
        configureCheckpoints(2, "req-cfg", "c1", "1", "c2", "2", "c3", "3");
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-3", "c3", 300L, 4, "req-t3"));

        RunnerStatusResponse openStatus = raceService.getRunnerStatus(RACE, "a");
        assertThat(openStatus.status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(openStatus.lastCheckpointCode()).isEqualTo("c3");
        assertThat(openStatus.missingCheckpoints()).containsExactly("c2");
    }

    @Test
    void 并发同版本登记退赛仅一个成功() throws Exception {
        createRace();
        register("a", null, 1, "req-a");

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger success =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            // 不同 withdrawalKey：纯版本竞争，仅一个提交成功
                            dns("a", "key-a-" + i, 2, "req-wd-" + i);
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (var future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        assertThat(repository.findWithdrawals(RACE)).hasSize(1);
    }
}
