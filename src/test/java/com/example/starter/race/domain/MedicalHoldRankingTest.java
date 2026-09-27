package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 医疗暂停与成绩排名交互的纯逻辑测试：
 * 区间内分段（excludedHoldId）保存但不计覆盖/排名、退赛状态、生效暂停标记与排除计数。
 */
class MedicalHoldRankingTest {

    private record Runner(String bib, Long finishTimeMs, boolean withdrawn)
            implements ResultCalculator.RunnerView {
    }

    private record Timing(
            String timingId, String bib, String checkpointCode, int position,
            long elapsedMillis, String excludedHoldId) implements TimingView {
    }

    private record Hold(String holdId, String bib, long startAt, Long endAt)
            implements MedicalHoldView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    private List<CheckpointView> checkpoints(String... codes) {
        List<CheckpointView> list = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            list.add(new Checkpoint(codes[i], i + 1));
        }
        return list;
    }

    private Timing timing(String bib, String code, int position, long elapsed, String holdId) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed, holdId);
    }

    @Test
    void 暂停区间内分段被排除不计覆盖且状态为漏点() {
        List<RunnerView> runners = List.of(new Runner("a", 10_000L, false));
        // 两个检查点：cp1 正常通过，cp2 在医疗暂停区间内被标记排除
        List<TimingView> timings = List.of(
                timing("a", "cp1", 1, 1_000, null),
                timing("a", "cp2", 2, 2_000, "hold-1"));
        List<MedicalHoldView> holds = List.of(new Hold("hold-1", "a", 5_000, 8_000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints("cp1", "cp2"), timings, holds);

        ResultEntry entry = entries.getFirst();
        assertThat(entry.bib()).isEqualTo("a");
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entry.rank()).isNull();
        assertThat(entry.coveredCheckpointCount()).isEqualTo(1);
        assertThat(entry.missingCheckpoints()).containsExactly("cp2");
        assertThat(entry.excludedTimingCount()).isEqualTo(1);
        // 已恢复的暂停不标 medicalHoldActive
        assertThat(entry.medicalHoldActive()).isFalse();
    }

    @Test
    void 暂停生效中标记active且排除分段不计覆盖() {
        List<RunnerView> runners = List.of(new Runner("a", 10_000L, false));
        // 三个检查点：cp1/cp3 正常通过，cp2 在暂停区间内被排除
        List<TimingView> timings = List.of(
                timing("a", "cp1", 1, 1_000, null),
                timing("a", "cp2", 2, 2_000, "hold-1"),
                timing("a", "cp3", 3, 3_000, null));
        List<MedicalHoldView> holds = List.of(new Hold("hold-1", "a", 5_000, null));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints("cp1", "cp2", "cp3"), timings, holds);

        ResultEntry entry = entries.getFirst();
        // cp2 无有效通过记录：漏点不排名，但暂停仍生效、排除计数为1
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entry.rank()).isNull();
        assertThat(entry.coveredCheckpointCount()).isEqualTo(2);
        assertThat(entry.missingCheckpoints()).containsExactly("cp2");
        assertThat(entry.medicalHoldActive()).isTrue();
        assertThat(entry.excludedTimingCount()).isEqualTo(1);
    }

    @Test
    void 暂停恢复后全部检查点均为区间外有效计时时正常排名() {
        List<RunnerView> runners = List.of(new Runner("a", 10_000L, false));
        // 暂停发生在所有分段计时之前，全部检查点均为暂停结束后新计时
        List<TimingView> timings = List.of(
                timing("a", "cp1", 1, 1_000, null),
                timing("a", "cp2", 2, 2_000, null));
        List<MedicalHoldView> holds = List.of(new Hold("hold-1", "a", 100, 500L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints("cp1", "cp2"), timings, holds);

        ResultEntry entry = entries.getFirst();
        assertThat(entry.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entry.rank()).isEqualTo(1);
        assertThat(entry.totalTimeMs()).isEqualTo(10_000L);
        assertThat(entry.medicalHoldActive()).isFalse();
        assertThat(entry.excludedTimingCount()).isZero();
    }

    @Test
    void 恢复后仅暂停结束后的新计时参与排名旧排除保持不变() {
        List<RunnerView> runners = List.of(new Runner("a", 10_000L, false));
        // hold-1 区间内的 cp2 标记已固化，即使暂停恢复、之后补了新记录，旧标记不被追溯改写
        List<TimingView> timings = List.of(
                timing("a", "cp1", 1, 1_000, null),
                timing("a", "cp2", 2, 2_000, "hold-1"));
        List<MedicalHoldView> holds = List.of(new Hold("hold-1", "a", 1_500, 3_000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints("cp1", "cp2"), timings, holds);

        // 被排除的 cp2 不会因为暂停已恢复而重新计覆盖：仍漏点
        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entries.getFirst().coveredCheckpointCount()).isEqualTo(1);
        assertThat(entries.getFirst().excludedTimingCount()).isEqualTo(1);
    }

    @Test
    void 退赛选手状态为withdrawn且不参与排名() {
        List<RunnerView> runners = List.of(
                new Runner("a", 1_000L, false),
                new Runner("b", 2_000L, true));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).totalTimeMs()).isNull();
    }

    @Test
    void 取消资格优先于退赛判定且暂停不改变取消资格() {
        List<RunnerView> runners = List.of(new Runner("a", 1_000L, true));
        List<ResultCalculator.PenaltyView> penalties = List.of(new ResultCalculator.PenaltyView() {
            @Override
            public String bib() {
                return "a";
            }

            @Override
            public PenaltyType type() {
                return PenaltyType.DISQUALIFY;
            }

            @Override
            public Long amountMs() {
                return null;
            }

            @Override
            public boolean revoked() {
                return false;
            }
        });

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(),
                List.of(new Hold("hold-1", "a", 100, null)));

        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(entries.getFirst().medicalHoldActive()).isTrue();
    }
}
