package com.example.starter.race.service;

import com.example.starter.race.api.ChipReplacementDetailResponse;
import com.example.starter.race.api.ChipReplacementHistoryResponse;
import com.example.starter.race.api.ChipReplacementResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReplaceChipRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.RunnerChipRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 芯片更换能力的 H2 数据库测试：主流程、左闭右开归属边界、失败分支整批回滚、
 * 双重幂等、历史/明细只读查询与封榜快照芯片固化。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ChipReplacementServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-chip";
    /** 固定时钟对应的 UTC 毫秒时间戳。 */
    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void register(String bib, Long finishTimeMs, String chipCode, int version, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, chipCode, version, reqId));
    }

    private void configure(int version, String reqId) {
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)), version, reqId));
    }

    private void submit(String bib, String timingId, String code, long elapsed, int version,
                        String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, version, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private ServiceResult replace(String bib, String replacementId, String oldChip, String newChip,
                                  long effectiveAt, int version, String reqId) {
        return raceService.replaceChip(RACE, bib, new ReplaceChipRequest(
                replacementId, oldChip, newChip, effectiveAt, "ref-1", "op-1", version, reqId));
    }

    private int raceVersion() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    @Test
    void 未来生效的更换_生效前读数保持归旧芯片() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");

        ServiceResult result = replace("a", "repl-1", "CHIP-A", "CHIP-B", T + 1000, 4, "req-r1");

        assertThat(result.status()).isEqualTo(201);
        ChipReplacementResponse body = (ChipReplacementResponse) result.body();
        assertThat(body.oldChipCode()).isEqualTo("CHIP-A");
        assertThat(body.newChipCode()).isEqualTo("CHIP-B");
        assertThat(body.effectiveAt()).isEqualTo(T + 1000);
        assertThat(body.referee()).isEqualTo("ref-1");
        assertThat(body.operator()).isEqualTo("op-1");
        assertThat(body.reassignedCount()).isZero();
        assertThat(body.version()).isEqualTo(5);
        assertThat(body.createdAt()).isEqualTo(T);

        // 芯片区间：旧 [T, T+1000)，新 [T+1000, 开放)
        List<RunnerChipRow> periods = repository.findChipPeriods(RACE, "a");
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).chipCode()).isEqualTo("CHIP-A");
        assertThat(periods.get(0).effectiveFrom()).isEqualTo(T);
        assertThat(periods.get(0).effectiveTo()).isEqualTo(T + 1000);
        assertThat(periods.get(1).chipCode()).isEqualTo("CHIP-B");
        assertThat(periods.get(1).effectiveFrom()).isEqualTo(T + 1000);
        assertThat(periods.get(1).effectiveTo()).isNull();
        // 全局生效绑定切换到新芯片
        assertThat(repository.findChipActive("CHIP-A")).isEmpty();
        assertThat(repository.findChipActive("CHIP-B")).isPresent();
        // 生效前读数仍归旧芯片
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-A");
        // 更换登记后、生效时刻前到达的读数仍归旧芯片
        submit("a", "t2", "cp2", 2000L, 5, "req-t2");
        assertThat(repository.findTiming("t2").orElseThrow().chipCode()).isEqualTo("CHIP-A");
    }

    @Test
    void 生效时刻边界的读数按左闭右开归新芯片并重算() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");

        // 读数接收时刻恰好等于生效时刻：归新芯片，归属被重算
        ServiceResult result = replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 4, "req-r1");

        assertThat(result.status()).isEqualTo(201);
        assertThat(((ChipReplacementResponse) result.body()).reassignedCount()).isEqualTo(1);
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-B");
        // 明细固化重算前后归属
        ChipReplacementDetailResponse detail = raceService.getChipReplacement(RACE, "repl-1");
        assertThat(detail.details()).hasSize(1);
        ChipReplacementDetailResponse.Attribution attribution = detail.details().getFirst();
        assertThat(attribution.timingId()).isEqualTo("t1");
        assertThat(attribution.checkpointCode()).isEqualTo("cp1");
        assertThat(attribution.position()).isEqualTo(1);
        assertThat(attribution.chipBefore()).isEqualTo("CHIP-A");
        assertThat(attribution.chipAfter()).isEqualTo("CHIP-B");
    }

    @Test
    void 生效时刻起新读数归新芯片() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 3, "req-r1");

        submit("a", "t1", "cp1", 1000L, 4, "req-t1");

        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-B");
    }
    @Test
    void 未绑定芯片的选手首次更换时补登记旧芯片并回填归属() {
        createRace();
        register("a", 10_000L, null, 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isNull();

        ServiceResult result = replace("a", "repl-1", "CHIP-A", "CHIP-B", T + 1000, 4, "req-r1");

        assertThat(result.status()).isEqualTo(201);
        assertThat(((ChipReplacementResponse) result.body()).reassignedCount()).isEqualTo(1);
        // 生效前的历史读数回填为旧芯片
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-A");
        List<RunnerChipRow> periods = repository.findChipPeriods(RACE, "a");
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).chipCode()).isEqualTo("CHIP-A");
        assertThat(periods.get(0).effectiveFrom()).isEqualTo(T);
        ChipReplacementDetailResponse detail = raceService.getChipReplacement(RACE, "repl-1");
        assertThat(detail.details().getFirst().chipBefore()).isNull();
        assertThat(detail.details().getFirst().chipAfter()).isEqualTo("CHIP-A");
    }

    @Test
    void 新芯片已被其他未完的选手绑定时返回409() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        register("b", 20_000L, "CHIP-B", 2, "req-b");

        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 3, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("新芯片已绑定");

        assertThat(raceVersion()).isEqualTo(3);
        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
        assertThat(repository.findChipActive("CHIP-A")).isPresent();
        assertThat(repository.findChipActive("CHIP-B")).isPresent();
    }

@Test
    void 替换时刻早于已接收分段读数返回422且整批回滚() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");

        // 生效时刻比最晚读数早1毫秒：报出实际值、要求值与差额
        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-B", T - 1, 4, "req-r1"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("早于已接收分段读数")
                .hasMessageContaining("差额=1毫秒");

        // 整批回滚：版本、芯片绑定、读数归属与更换档案均无半成品
        assertThat(raceVersion()).isEqualTo(4);
        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
        assertThat(repository.findChipReplacementDetails("repl-1")).isEmpty();
        assertThat(repository.findChipActive("CHIP-A")).isPresent();
        assertThat(repository.findChipActive("CHIP-B")).isEmpty();
        assertThat(repository.findChipPeriods(RACE, "a")).hasSize(1);
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-A");
    }

    @Test
    void 旧芯片与当前生效芯片不一致返回422() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");

        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-X", "CHIP-B", T, 2, "req-r1"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不一致");

        assertThat(raceVersion()).isEqualTo(2);
        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
    }

    @Test
    void 已退赛或已取消资格的选手禁止更换芯片() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        register("b", 20_000L, "CHIP-B", 2, "req-b");
        // a 退赛（版本到4）
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(3, "op-1", "req-wd"));
        // b 取消资格（版本到5），其芯片同时被释放
        raceService.addPenalty(RACE, new com.example.starter.race.api.AddPenaltyRequest(
                "pen-1", "b", "DISQUALIFY", null, 4, "req-pen"));
        assertThat(repository.findChipActive("CHIP-B")).isEmpty();

        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-C", T, 5, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        assertThatThrownBy(() -> replace("b", "repl-2", "CHIP-B", "CHIP-C", T, 5, "req-r2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("取消资格");

        assertThat(raceVersion()).isEqualTo(5);
        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
        assertThat(repository.findChipReplacement("repl-2")).isEmpty();
    }

    @Test
    void 封榜后禁止更换芯片() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        raceService.sealRace(RACE, new com.example.starter.race.api.SealRaceRequest(2, "req-seal"));

        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 3, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已封榜");

        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
    }

    @Test
    void 读数已归属第三块芯片时无法唯一归属并整次回滚() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");
        // 直接落库一条归属第三块芯片的读数，模拟历史数据异常
        repository.insertTiming(new CheckpointTimingRow(
                "t-x", RACE, "a", "cp2", 2, 2000L, "CHIP-X", T));

        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-B", T + 1000, 4, "req-r1"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("CHIP-X");

        // 整次回滚：版本不变、无更换档案、读数归属保持原样
        assertThat(raceVersion()).isEqualTo(4);
        assertThat(repository.findChipReplacement("repl-1")).isEmpty();
        assertThat(repository.findChipReplacementDetails("repl-1")).isEmpty();
        assertThat(repository.findChipActive("CHIP-A")).isPresent();
        assertThat(repository.findChipActive("CHIP-B")).isEmpty();
        assertThat(repository.findTiming("t1").orElseThrow().chipCode()).isEqualTo("CHIP-A");
        assertThat(repository.findTiming("t-x").orElseThrow().chipCode()).isEqualTo("CHIP-X");
        assertThat(repository.findChipPeriods(RACE, "a")).hasSize(1);
    }

    @Test
    void requestId同参重放_异参409_失败不占键() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");

        // 失败不占键：版本错误被409拒绝后，同一 requestId 仍可成功
        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 99, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        ServiceResult first = replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 2, "req-r1");
        assertThat(first.status()).isEqualTo(201);

        // 同 requestId 同参重放：返回首次完整快照，不产生新变更
        ServiceResult replayed = replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 2, "req-r1");
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(objectMapper.valueToTree(first.body()));
        assertThat(raceVersion()).isEqualTo(3);
        assertThat(repository.findChipReplacementsForRunner(RACE, "a")).hasSize(1);

        // 同 requestId 异参：409
        assertThatThrownBy(() -> replace("a", "repl-2", "CHIP-A", "CHIP-C", T, 2, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(raceVersion()).isEqualTo(3);
    }

    @Test
    void replacementId第二层幂等_同参重放_异参409() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        ServiceResult first = replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 2, "req-r1");
        assertThat(first.status()).isEqualTo(201);

        // 不同 requestId 但 replacementId 与全部参数相同：重放首次结果
        ServiceResult replayed = replace("a", "repl-1", "CHIP-A", "CHIP-B", T, 3, "req-r2");
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(first.body());

        // replacementId 相同但参数不同：409
        assertThatThrownBy(() -> replace("a", "repl-1", "CHIP-A", "CHIP-C", T, 3, "req-r3"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("replacementId");

        assertThat(raceVersion()).isEqualTo(3);
        assertThat(repository.findChipReplacementsForRunner(RACE, "a")).hasSize(1);
    }

    @Test
    void 历史与明细查询只读且按生效时刻稳定排序() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        replace("a", "repl-1", "CHIP-A", "CHIP-B", T + 1000, 3, "req-r1");
        replace("a", "repl-2", "CHIP-B", "CHIP-C", T + 2000, 4, "req-r2");
        assertThat(raceVersion()).isEqualTo(5);

        ChipReplacementHistoryResponse history = raceService.getChipReplacements(RACE, "a");
        assertThat(history.version()).isEqualTo(5);
        assertThat(history.replacements())
                .extracting(ChipReplacementResponse::replacementId)
                .containsExactly("repl-1", "repl-2");
        assertThat(history.replacements())
                .extracting(ChipReplacementResponse::effectiveAt)
                .containsExactly(T + 1000, T + 2000);

        ChipReplacementDetailResponse detail = raceService.getChipReplacement(RACE, "repl-2");
        assertThat(detail.replacement().oldChipCode()).isEqualTo("CHIP-B");
        assertThat(detail.replacement().newChipCode()).isEqualTo("CHIP-C");
        assertThat(detail.details()).isEmpty();

        // 只读查询不改变版本与数据
        assertThat(raceVersion()).isEqualTo(5);
        assertThatThrownBy(() -> raceService.getChipReplacements(RACE, "nobody"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getChipReplacement(RACE, "no-such"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 封榜快照固化每段芯片且后续操作不改写() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        configure(2, "req-cfg");
        submit("a", "t1", "cp1", 1000L, 3, "req-t1");
        // 未来生效：t1 在封榜时仍归 CHIP-A
        replace("a", "repl-1", "CHIP-A", "CHIP-B", T + 1000, 4, "req-r1");
        raceService.sealRace(RACE, new com.example.starter.race.api.SealRaceRequest(5, "req-seal"));

        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.checkpoints())
                .filteredOn(row -> row.timingId() != null)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.timingId()).isEqualTo("t1");
                    assertThat(row.chipCode()).isEqualTo("CHIP-A");
                });
        // 封榜后分段查询返回快照中的芯片归属
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints().getFirst().chipCode())
                .isEqualTo("CHIP-A");
        // 封榜后禁止更换，快照不被改写
        assertThatThrownBy(() -> replace("a", "repl-2", "CHIP-B", "CHIP-C", T + 2000, 6, "req-r2"))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findSnapshot(RACE).orElseThrow().checkpoints())
                .filteredOn(row -> row.timingId() != null)
                .singleElement()
                .extracting(row -> row.chipCode())
                .isEqualTo("CHIP-A");
    }
}