package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 的医疗暂停排除纯逻辑测试：
 * MEDICAL_HOLD 分段不参与检查点覆盖统计，仅计入排除数量。
 */
class MedicalHoldExclusionTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis,
            TimingHoldStatus holdStatus
    ) implements TimingView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    @Test
    void 医疗暂停分段不参与覆盖但计入排除数量() {
        List<Runner> runners = List.of(new Runner("a", 100_000L));
        List<Checkpoint> checkpoints = List.of(
                new Checkpoint("cp1", 1), new Checkpoint("cp2", 2), new Checkpoint("cp3", 3));
        List<Timing> timings = List.of(
                new Timing("t-1", "a", "cp1", 1, 1_000L, TimingHoldStatus.COUNTED),
                new Timing("t-2", "a", "cp2", 2, 2_000L, TimingHoldStatus.MEDICAL_HOLD),
                new Timing("t-3", "a", "cp3", 3, 3_000L, TimingHoldStatus.COUNTED));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints, timings);

        assertThat(entries).hasSize(1);
        ResultEntry entry = entries.getFirst();
        assertThat(entry.coveredCheckpointCount()).isEqualTo(2);
        assertThat(entry.excludedTimingCount()).isEqualTo(1);
        assertThat(entry.missingCheckpoints()).containsExactly("cp2");
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
    }

    @Test
    void 全部为COUNTED时排除数量为零且正常排名() {
        List<Runner> runners = List.of(new Runner("a", 100_000L));
        List<Checkpoint> checkpoints = List.of(new Checkpoint("cp1", 1));
        List<Timing> timings = List.of(
                new Timing("t-1", "a", "cp1", 1, 1_000L, TimingHoldStatus.COUNTED));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints, timings);

        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.getFirst().excludedTimingCount()).isZero();
    }
}
