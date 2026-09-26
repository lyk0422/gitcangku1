package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 医疗暂停与退赛相关纯逻辑单元测试：
 * 被排除计时不计入覆盖、MEDICAL_HOLD/WITHDRAWN 状态与优先级。
 */
class MedicalHoldRankingTest {

    private record Runner(
            String bib,
            Long finishTimeMs,
            boolean withdrawn,
            boolean medicalHoldActive
    ) implements ResultCalculator.RunnerView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis,
            boolean medicalHold
    ) implements TimingView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    private static List<Checkpoint> checkpoints() {
        return List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
    }

    private static Runner runner(String bib, Long finishTimeMs) {
        return new Runner(bib, finishTimeMs, false, false);
    }

    @Test
    void 医疗暂停期间的分段不计入检查点覆盖() {
        List<Runner> runners = List.of(runner("a", 1000L));
        List<Timing> timings = List.of(
                new Timing("t1", "a", "cp1", 1, 100L, false),
                new Timing("t2", "a", "cp2", 2, 200L, true));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints(), timings);

        assertThat(entries).hasSize(1);
        ResultEntry entry = entries.get(0);
        assertThat(entry.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entry.coveredCheckpointCount()).isEqualTo(1);
        assertThat(entry.missingCheckpoints()).containsExactly("cp2");
    }

    @Test
    void 生效医疗暂停的选手状态为MEDICAL_HOLD且不排名() {
        List<Runner> runners = List.of(
                new Runner("held", 100L, false, true),
                runner("normal", 9000L));
        List<Timing> timings = List.of(
                new Timing("h1", "held", "cp1", 1, 10L, false),
                new Timing("h2", "held", "cp2", 2, 20L, false),
                new Timing("n1", "normal", "cp1", 1, 100L, false),
                new Timing("n2", "normal", "cp2", 2, 200L, false));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints(), timings);

        // held 虽全覆盖且更快，但暂停生效不排名；normal 第1
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("normal", "held");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).totalTimeMs()).isNull();
    }

    @Test
    void 退赛选手状态为WITHDRAWN且不排名() {
        List<Runner> runners = List.of(
                new Runner("out", 100L, true, false),
                runner("in", 9000L));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("in", "out");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(entries.get(1).rank()).isNull();
    }

    @Test
    void 状态优先级为取消资格高于退赛高于医疗暂停() {
        List<Runner> runners = List.of(
                new Runner("dq-out", 100L, true, true),
                new Runner("out-held", 100L, true, true),
                new Runner("held-untimed", null, false, true));
        List<Penalty> penalties = List.of(
                new Penalty("dq-out", PenaltyType.DISQUALIFY, null, false));

        List<ResultEntry> entries = ResultCalculator.compute(runners, penalties);

        // 未排名者按参赛号字典序展示：dq-out < held-untimed < out-held
        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("dq-out", "held-untimed", "out-held");
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.DISQUALIFIED, EntryStatus.MEDICAL_HOLD,
                        EntryStatus.WITHDRAWN);
        assertThat(entries).extracting(ResultEntry::rank)
                .containsOnlyNulls();
    }
}
