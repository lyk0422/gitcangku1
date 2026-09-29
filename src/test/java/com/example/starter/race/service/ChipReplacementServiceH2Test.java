package com.example.starter.race.service;

import com.example.starter.race.api.ChipBindingsResponse;
import com.example.starter.race.api.ChipHistoryResponse;
import com.example.starter.race.api.ChipReplacementResponse;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReplaceChipRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.ChipBindingRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 赛事芯片更换能力的真实 H2 数据库测试：UTC 左闭右开归属、更换重算与整事务回滚、
 * 新芯片占用/退赛/取消资格/封榜冲突、同芯片并发唯一绑定、requestId 幂等、
 * 退赛状态与封榜快照固化每段芯片。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ChipReplacementServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-chip";
    private static final String OLD = "CHIP-OLD";
    private static final String NEW = "CHIP-NEW";
    private static final long BASE = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    private void createRace(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
    }

    private void setupRaceWithThreeCheckpoints() {
        createRace(RACE);
        // v2：登记 a，发放旧芯片
        raceService.registerRunner(RACE, new RegisterRunnerRequest(
                "a", 10_000L, OLD, 1, "req-reg-a"));
        // v3：配置三个检查点
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1),
                new ConfigureCheckpointsRequest.CheckpointDefinition("c2", 2),
                new ConfigureCheckpointsRequest.CheckpointDefinition("c3", 3));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, 2, "req-cfg"));
    }

    private void submit(String timingId, String bib, String code, long elapsed,
                        String chip, Long readAtMs, int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, chip, readAtMs,
                        expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private ChipReplacementResponse replace(
            String bib, String replacementId, String oldChip, String newChip,
            long effectiveAt, String referee, int expectedVersion, String reqId) {
        ServiceResult result = raceService.replaceChip(RACE, bib, new ReplaceChipRequest(
                replacementId, oldChip, newChip, effectiveAt, referee,
                expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (ChipReplacementResponse) result.body();
    }

    @Test
    void 更换生效前后读数按UTC左闭右开归属且边界归新芯片() {
        setupRaceWithThreeCheckpoints();
        // 生效前两条读数（时刻 BASE+1000 / BASE+2000）均由旧芯片读取 -> v4,v5
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 3, "req-t1");
        submit("t-2", "a", "c2", 200L, OLD, BASE + 2_000L, 4, "req-t2");

        long effectiveAt = BASE + 5_000L;
        ChipReplacementResponse response = replace(
                "a", "rep-1", OLD, NEW, effectiveAt, "judge-1", 5, "req-rep-1");

        // 固化字段：旧/新芯片、UTC 生效时刻、裁判
        assertThat(response.oldChipCode()).isEqualTo(OLD);
        assertThat(response.newChipCode()).isEqualTo(NEW);
        assertThat(response.effectiveAtMs()).isEqualTo(effectiveAt);
        assertThat(response.referee()).isEqualTo("judge-1");
        // 更换不改变历史读数：重算前后归属完全一致，均为旧芯片
        assertThat(response.beforeAttribution()).extracting(i -> i.chipCode())
                .containsExactly(OLD, OLD);
        assertThat(response.afterAttribution()).extracting(i -> i.chipCode())
                .containsExactly(OLD, OLD);
        assertThat(response.afterAttribution()).extracting(i -> i.readAtMs())
                .containsExactly(BASE + 1_000L, BASE + 2_000L);

        // 版本推进到 6；选手当前芯片已切换；旧芯片绑定释放
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().chipCode()).isEqualTo(NEW);
        assertThat(repository.findChipBinding(OLD)).isEmpty();
        assertThat(repository.findChipBinding(NEW)).isPresent();

        // 生效边界时刻本身（左闭）读数只能归新芯片：用旧芯片提交 -> 422
        assertThatThrownBy(() -> submit("t-3-bad", "a", "c3", 300L, OLD,
                effectiveAt, 6, "req-t3-bad"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining(NEW);
        // 失败不写入、不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findTimingsForRunner(RACE, "a")).hasSize(2);

        // 用新芯片在边界时刻提交成功 -> v7
        submit("t-3", "a", "c3", 300L, NEW, effectiveAt, 6, "req-t3");
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::chipCode)
                .containsExactly(OLD, OLD, NEW);
    }

    @Test
    void 新芯片已绑定其他未完赛选手返回409且整次回滚不留半成品() {
        setupRaceWithThreeCheckpoints();
        // 登记第二名选手 b 并占用芯片 SHARED（v4）
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 9_000L, "SHARED", 3, "req-reg-b"));
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 4, "req-t1"); // v5

        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-bad", OLD, "SHARED", BASE + 5_000L, "judge-1", 5, "req-rep-bad")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SHARED");

        // 整事务回滚：版本不变、选手仍是旧芯片、无更换历史、旧绑定仍在、新读数归属不变、失败不占 requestId
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().chipCode()).isEqualTo(OLD);
        assertThat(repository.findChipReplacementsForRunner(RACE, "a")).isEmpty();
        assertThat(repository.findChipBinding(OLD)).isPresent();
        assertThat(repository.findIdempotency("req-rep-bad")).isEmpty();
        assertThat(repository.findTimingsForRunner(RACE, "a").getFirst().chipCode())
                .isEqualTo(OLD);

        // 同一 requestId 随后可用于一次合法更换
        ServiceResult retry = raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-ok", OLD, NEW, BASE + 5_000L, "judge-1", 5, "req-rep-bad"));
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void 生效时刻早于已接收读数导致无法唯一归属返回422且整次回滚() {
        setupRaceWithThreeCheckpoints();
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 3, "req-t1");
        submit("t-2", "a", "c2", 200L, OLD, BASE + 2_000L, 4, "req-t2");

        // 生效时刻 BASE+1500 早于 c2 的已接收时刻 BASE+2000：c2 在新时间线应归新芯片，
        // 但实际由旧芯片读取，无法唯一归属 -> 422，整次回滚。
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-early", OLD, NEW, BASE + 1_500L, "judge-1", 5, "req-rep-early")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("c2")
                .hasMessageContaining("回滚");

        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().chipCode()).isEqualTo(OLD);
        assertThat(repository.findChipBinding(NEW)).isEmpty();
        assertThat(repository.findChipReplacementsForRunner(RACE, "a")).isEmpty();
    }

    @Test
    void 旧芯片不一致与相同芯片返回可区分的409或422() {
        setupRaceWithThreeCheckpoints();
        // 旧芯片填错 -> 409
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-x", "OTHER", NEW, BASE + 5_000L, "judge-1", 3, "req-rep-x")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("旧芯片");
        // 新旧相同 -> 422
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-same", OLD, OLD, BASE + 5_000L, "judge-1", 3, "req-rep-same")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不得与旧芯片相同");
        // 生效时刻不晚于上一生效时刻（这里早于登记时刻基准附近的读数场景由专门用例覆盖）
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 退赛取消资格与封榜后禁止更换芯片和读数() {
        setupRaceWithThreeCheckpoints();
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 3, "req-t1"); // v4

        // 退赛 -> v5，释放芯片，榜单 WITHDRAWN
        ServiceResult withdrawn = raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("judge-9", 4, "req-withdraw"));
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().status().name())
                .isEqualTo("WITHDRAWN");
        assertThat(repository.findChipBinding(OLD)).isEmpty();
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.WITHDRAWN);

        // 退赛后读数 / 更换芯片 / 再次退赛 均被拒绝
        assertThatThrownBy(() -> submit("t-2", "a", "c2", 200L, OLD,
                BASE + 2_000L, 5, "req-t2"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("退赛");
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-after-wd", OLD, NEW, BASE + 5_000L, "judge-1", 5, "req-rep-wd")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("退赛");
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("judge-9", 5, "req-withdraw-again")))
                .isInstanceOf(ConflictException.class);
        // 拒绝的写操作不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 封榜后只读：先恢复一个在赛选手 b 并封榜
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 8_000L, "CHIP-B", 5, "req-reg-b")); // v6
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "b", new ReplaceChipRequest(
                "rep-sealed", "CHIP-B", NEW, BASE + 9_000L, "judge-1", 7, "req-rep-sealed")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("封榜");
    }

    @Test
    void 更换历史与绑定诊断只读且排序稳定() {
        setupRaceWithThreeCheckpoints();
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 3, "req-t1"); // v4
        replace("a", "rep-1", OLD, NEW, BASE + 5_000L, "judge-1", 4, "req-rep-1"); // v5
        replace("a", "rep-2", NEW, "CHIP-3", BASE + 9_000L, "judge-2", 5, "req-rep-2"); // v6

        ChipHistoryResponse history = raceService.getChipHistory(RACE, "a");
        assertThat(history.currentChipCode()).isEqualTo("CHIP-3");
        assertThat(history.replacements()).hasSize(2);
        // 按提交顺序稳定；历史固化不随后续更换改写：rep-1 的新芯片仍为 NEW
        assertThat(history.replacements()).extracting(ChipReplacementResponse::replacementId)
                .containsExactly("rep-1", "rep-2");
        assertThat(history.replacements().get(0).newChipCode()).isEqualTo(NEW);
        assertThat(history.replacements().get(0).referee()).isEqualTo("judge-1");

        // 第二次更换后，第一次更换时刻之后若再来读数必须归 CHIP-3
        submit("t-2", "a", "c2", 200L, "CHIP-3", BASE + 9_000L, 6, "req-t2"); // v7
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::chipCode)
                .containsExactly(OLD, "CHIP-3", null);

        // 诊断只读：当前仅 CHIP-3 绑定 a，旧芯片全部释放；读取不推进版本
        int versionBefore = repository.findRace(RACE).orElseThrow().version();
        ChipBindingsResponse bindings = raceService.getChipBindings(RACE);
        assertThat(bindings.bindings()).extracting(ChipBindingsResponse.Binding::chipCode)
                .containsExactly("CHIP-3");
        assertThat(bindings.bindings().getFirst().bib()).isEqualTo("a");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(versionBefore);
    }

    @Test
    void 封榜快照固化每段芯片且后续无法更换改写() {
        setupRaceWithThreeCheckpoints();
        submit("t-1", "a", "c1", 100L, OLD, BASE + 1_000L, 3, "req-t1"); // v4
        submit("t-2", "a", "c2", 200L, OLD, BASE + 2_000L, 4, "req-t2"); // v5
        replace("a", "rep-1", OLD, NEW, BASE + 5_000L, "judge-1", 5, "req-rep-1"); // v6
        submit("t-3", "a", "c3", 300L, NEW, BASE + 6_000L, 6, "req-t3"); // v7

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);

        // 快照中每段使用芯片固化为 [OLD, OLD, NEW]
        RunnerTimingResponse snapshotTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(snapshotTiming.checkpoints()).extracting(CheckpointPassResponse::chipCode)
                .containsExactly(OLD, OLD, NEW);

        // 封榜后更换芯片被拒（409），快照芯片不被改写
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-late", NEW, "CHIP-LATE", BASE + 9_000L, "judge-1", 8, "req-rep-late")))
                .isInstanceOf(ConflictException.class);
        RunnerTimingResponse unchanged = raceService.getRunnerTimings(RACE, "a");
        assertThat(unchanged.checkpoints()).extracting(CheckpointPassResponse::chipCode)
                .containsExactly(OLD, OLD, NEW);
        assertThat(raceService.getSnapshot(RACE).status()).isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void requestId同参重放异参409且裁判参与指纹() {
        setupRaceWithThreeCheckpoints();
        ReplaceChipRequest req = new ReplaceChipRequest(
                "rep-1", OLD, NEW, BASE + 5_000L, "judge-1", 3, "req-rep");
        ServiceResult first = raceService.replaceChip(RACE, "a", req);
        ServiceResult replay = raceService.replaceChip(RACE, "a", req);
        assertThat(replay.status()).isEqualTo(first.status());
        // 重放不重复推进版本（仍为 v4）
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findChipReplacementsForRunner(RACE, "a")).hasSize(1);

        // 同 requestId 异参（新芯片不同）-> 409
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-1", OLD, "OTHER", BASE + 5_000L, "judge-1", 3, "req-rep")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("requestId");
        // 同 requestId 仅裁判不同（操作者差异）也必须 409，指纹覆盖操作者
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "rep-1", OLD, NEW, BASE + 5_000L, "judge-2", 3, "req-rep")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("requestId");
    }

    @Test
    void 读数时刻早于芯片绑定起始返回422() {
        setupRaceWithThreeCheckpoints();
        // readAtMs 早于选手登记（绑定起始）时刻 BASE -> 无法唯一归属
        assertThatThrownBy(() -> submit("t-early", "a", "c1", 100L, OLD,
                BASE - 1L, 3, "req-early"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("无法唯一归属");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 跨赛事同芯片并发更换至多一次成功() throws Exception {
        createRace("rx");
        createRace("ry");
        raceService.registerRunner("rx",
                new RegisterRunnerRequest("x1", 10_000L, "X-CHIP", 1, "req-x1"));
        raceService.registerRunner("ry",
                new RegisterRunnerRequest("y1", 10_000L, "Y-CHIP", 1, "req-y1"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<java.util.concurrent.Callable<Void>> tasks = List.of(
                    () -> {
                        start.await();
                        try {
                            raceService.replaceChip("rx", "x1", new ReplaceChipRequest(
                                    "rep-x", "X-CHIP", "SHARED-NEW", BASE + 5_000L,
                                    "judge-x", 2, "req-rep-x"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    },
                    () -> {
                        start.await();
                        try {
                            raceService.replaceChip("ry", "y1", new ReplaceChipRequest(
                                    "rep-y", "Y-CHIP", "SHARED-NEW", BASE + 5_000L,
                                    "judge-y", 2, "req-rep-y"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    });
            List<Future<Void>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一新芯片跨赛事并发绑定：恰好一个成功、一个冲突；芯片主键全局唯一
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        ChipBindingRow holder = repository.findChipBinding("SHARED-NEW").orElseThrow();
        String other = holder.bib().equals("x1") ? "y1" : "x1";
        String otherRace = holder.bib().equals("x1") ? "ry" : "rx";
        String otherOriginalChip = other.equals("x1") ? "X-CHIP" : "Y-CHIP";
        assertThat(repository.findRunner(otherRace, other).orElseThrow().chipCode())
                .isEqualTo(otherOriginalChip);
        // 两名选手的更换历史中只有成功者追加一条
        int totalReplacements = repository.findChipReplacementsForRunner("rx", "x1").size()
                + repository.findChipReplacementsForRunner("ry", "y1").size();
        assertThat(totalReplacements).isEqualTo(1);
    }
}
