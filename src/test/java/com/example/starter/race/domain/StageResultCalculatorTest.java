package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StageResultCalculator} 的纯逻辑测试：关门线精确比较、F 的计算口径、
 * 阶段资格与总榜排名（处罚仅入总成绩、精确并列跳号）。
 */
class StageResultCalculatorTest {

    private record Cp(int stageNo, String checkpointCode, int position)
            implements StageCheckpointView {
    }

    private record Timing(String timingId, int stageNo, String bib, String checkpointCode,
                          int position, long elapsedMillis) implements StageTimingView {
    }

    private record Penalty(String bib, PenaltyType type, Long amountMs, boolean revoked)
            implements ResultCalculator.PenaltyView {
    }

    private static List<Cp> twoStagesOneCp() {
        return List.of(new Cp(1, "A", 1), new Cp(2, "A", 1));
    }

    @Test
    void 关门线精确比较_等于允许_严格大于出局() {
        // F=101：关门线 111.1，t=111 允许，t=112 OUT
        assertThat(StageResultCalculator.isBeyondCutoff(111, 101)).isFalse();
        assertThat(StageResultCalculator.isBeyondCutoff(112, 101)).isTrue();
        // F=100：关门线 110，t=110 允许，t=111 OUT
        assertThat(StageResultCalculator.isBeyondCutoff(110, 100)).isFalse();
        assertThat(StageResultCalculator.isBeyondCutoff(111, 100)).isTrue();
    }

    @Test
    void 合成场景_最快更正后原允许者变为OUT() {
        List<Cp> checkpoints = twoStagesOneCp();
        List<String> bibs = List.of("110", "111", "112");
        List<Timing> timings = List.of(
                new Timing("t1", 1, "110", "A", 1, 101),
                new Timing("t2", 1, "111", "A", 1, 111),
                new Timing("t3", 1, "112", "A", 1, 112),
                new Timing("t4", 2, "110", "A", 1, 200),
                new Timing("t5", 2, "111", "A", 1, 200),
                new Timing("t6", 2, "112", "A", 1, 200));

        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        StageResult stage1 = stages.getFirst();
        assertThat(stage1.fastestMs()).isEqualTo(101L);
        assertThat(stage1.entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.COMPLETE, StageStatus.OUT);

        // 最快者更正 101 -> 100：F 重算为 100，111 变为 OUT
        List<Timing> revised = timings.stream()
                .map(t -> t.timingId().equals("t1")
                        ? new Timing("t1", 1, "110", "A", 1, 100) : t)
                .toList();
        List<StageResult> afterRevise =
                StageResultCalculator.computeStages(bibs, checkpoints, revised);
        assertThat(afterRevise.getFirst().fastestMs()).isEqualTo(100L);
        assertThat(afterRevise.getFirst().entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.OUT, StageStatus.OUT);

        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                bibs, List.of(), checkpoints, revised, afterRevise);
        assertThat(overall).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.OUT, EntryStatus.OUT);
        assertThat(overall.get(0).bib()).isEqualTo("110");
        assertThat(overall.get(0).totalTimeMs()).isEqualTo(300L);
    }

    @Test
    void F来自该阶段全部完整选手_不剔除其他阶段OUT者() {
        List<Cp> checkpoints = twoStagesOneCp();
        List<String> bibs = List.of("a", "b");
        // b 在阶段2 OUT，但阶段1的 F 仍须包含 b 的完整成绩
        List<Timing> timings = List.of(
                new Timing("t1", 1, "a", "A", 1, 200),
                new Timing("t2", 1, "b", "A", 1, 100),
                new Timing("t3", 2, "a", "A", 1, 100),
                new Timing("t4", 2, "b", "A", 1, 500));
        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        // 阶段1：F=100（来自 b），a 的 200 > 110 故 OUT
        assertThat(stages.get(0).fastestMs()).isEqualTo(100L);
        assertThat(stages.get(0).entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.OUT, StageStatus.COMPLETE);
        // 阶段2：F=100（来自 a），b 的 500 OUT
        assertThat(stages.get(1).fastestMs()).isEqualTo(100L);
        assertThat(stages.get(1).entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.OUT);
    }

    @Test
    void 缺点者该阶段INCOMPLETE_无完整选手时F为null() {
        List<Cp> checkpoints = List.of(new Cp(1, "A", 1), new Cp(1, "B", 2));
        List<String> bibs = List.of("a", "b");
        List<Timing> timings = List.of(new Timing("t1", 1, "a", "A", 1, 100));
        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        StageResult stage1 = stages.getFirst();
        assertThat(stage1.fastestMs()).isNull();
        assertThat(stage1.completeCount()).isZero();
        assertThat(stage1.entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.INCOMPLETE, StageStatus.INCOMPLETE);
        assertThat(stage1.entries().get(0).rawTimeMs()).isNull();
        assertThat(stage1.entries().get(0).coveredCheckpointCount()).isEqualTo(1);
    }

    @Test
    void 处罚仅入总成绩_不改变F与关门判定() {
        List<Cp> checkpoints = twoStagesOneCp();
        List<String> bibs = List.of("a", "b");
        List<Timing> timings = List.of(
                new Timing("t1", 1, "a", "A", 1, 100),
                new Timing("t2", 1, "b", "A", 1, 110),
                new Timing("t3", 2, "a", "A", 1, 100),
                new Timing("t4", 2, "b", "A", 1, 100));
        List<Penalty> penalties = List.of(
                new Penalty("a", PenaltyType.ADD_TIME, 50L, false),
                new Penalty("b", PenaltyType.ADD_TIME, 5L, true));
        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        // 处罚不影响 F 与资格
        assertThat(stages.get(0).fastestMs()).isEqualTo(100L);
        assertThat(stages.get(0).entries()).extracting(StageEntry::status)
                .containsExactly(StageStatus.COMPLETE, StageStatus.COMPLETE);

        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                bibs, penalties, checkpoints, timings, stages);
        // a: 200+50=250 排第2；b: 200+0(已撤销)=200 排第1
        assertThat(overall).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(overall).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(overall.get(1).penaltyMs()).isEqualTo(50L);
        assertThat(overall.get(1).totalTimeMs()).isEqualTo(250L);
    }

    @Test
    void 取消资格不排名_精确并列跳号() {
        List<Cp> checkpoints = twoStagesOneCp();
        List<String> bibs = List.of("a", "b", "c", "d");
        List<Timing> timings = List.of(
                new Timing("t1", 1, "a", "A", 1, 100),
                new Timing("t2", 1, "b", "A", 1, 100),
                new Timing("t3", 1, "c", "A", 1, 105),
                new Timing("t4", 1, "d", "A", 1, 108),
                new Timing("t5", 2, "a", "A", 1, 100),
                new Timing("t6", 2, "b", "A", 1, 100),
                new Timing("t7", 2, "c", "A", 1, 100),
                new Timing("t8", 2, "d", "A", 1, 100));
        List<Penalty> penalties = List.of(new Penalty("c", PenaltyType.DISQUALIFY, null, false));
        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                bibs, penalties, checkpoints, timings, stages);
        // a、b 并列第1（总200），d 跳号第3（总208），c 取消资格不排名
        assertThat(overall).extracting(ResultEntry::bib).containsExactly("a", "b", "d", "c");
        assertThat(overall).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 3, null);
        assertThat(overall.get(3).status()).isEqualTo(EntryStatus.DISQUALIFIED);
    }

    @Test
    void 总榜缺失检查点按阶段与顺序排列() {
        List<Cp> checkpoints = List.of(
                new Cp(1, "A", 1), new Cp(2, "B", 1), new Cp(2, "C", 2));
        List<String> bibs = List.of("a");
        List<Timing> timings = List.of(new Timing("t1", 1, "a", "A", 1, 100));
        List<StageResult> stages = StageResultCalculator.computeStages(bibs, checkpoints, timings);
        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                bibs, List.of(), checkpoints, timings, stages);
        assertThat(overall.getFirst().status()).isEqualTo(EntryStatus.INCOMPLETE);
        assertThat(overall.getFirst().missingCheckpoints())
                .containsExactly("2:B", "2:C");
        assertThat(overall.getFirst().checkpointCount()).isEqualTo(3);
        assertThat(overall.getFirst().coveredCheckpointCount()).isEqualTo(1);
    }
}
