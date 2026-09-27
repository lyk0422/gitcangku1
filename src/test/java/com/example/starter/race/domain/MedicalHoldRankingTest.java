package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 医疗暂停与退赛裁决纯逻辑测试：
 * 生效暂停不排名、被排除计时不覆盖检查点、恢复后仅新计时参与排名、退赛与取消资格优先级。
 */
class MedicalHoldRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis,
            TimingExclusionReason exclusionReason
    ) implements TimingView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    private record Hold(String bib, long startAt, Long endAt) implements MedicalHoldView {
    }

    private record Withdrawal(String bib, long withdrawnAt) implements WithdrawalView {
    }

    private static List<Checkpoint> checkpoints() {
        return List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
    }

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed, null);
    }

    private static Timing excluded(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed,
                TimingExclusionReason.MEDICAL_HOLD);
    }

    @Test
    void 生效医疗暂停者不参与临时排名且资格为暂停() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L), new Runner("b", 2000L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L), timing("a", "cp2", 2, 200L),
                timing("b", "cp1", 1, 100L), timing("b", "cp2", 2, 200L));
        List<Hold> holds = List.of(new Hold("a", 1000L, null));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, List.of(), holds);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        ResultEntry a = entries.get(1);
        assertThat(a.status()).isEqualTo(EntryStatus.MEDICAL_HOLD);
        assertThat(a.rank()).isNull();
        assertThat(a.totalTimeMs()).isNull();
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.MEDICAL_HOLD);
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).rank()).isEqualTo(1);
    }

    @Test
    void 已恢复暂停不影响资格与排名() {
        List<Runner> runners = List.of(new Runner("a", 1000L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L), timing("a", "cp2", 2, 200L));
        List<Hold> holds = List.of(new Hold("a", 1000L, 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, List.of(), holds);

        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(entries.get(0).rank()).isEqualTo(1);
    }

    @Test
    void 暂停期间被排除计时不覆盖检查点且原始记录保留计数() {
        List<Runner> runners = List.of(new Runner("a", 1000L));
        // cp1 暂停前正常，cp2 暂停期间提交被排除
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L), excluded("a", "cp2", 2, 200L));
        // 暂停已恢复：区间内计时仍永久排除，只有结束后的新计时可参与排名
        List<Hold> holds = List.of(new Hold("a", 1000L, 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, List.of(), holds);

        ResultEntry a = entries.get(0);
        assertThat(a.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(a.coveredCheckpointCount()).isEqualTo(1);
        assertThat(a.excludedTimingCount()).isEqualTo(1);
        // 被排除计时仍在 cp2 留有原始记录，因此缺失列表里不含 cp2
        assertThat(a.missingCheckpoints()).isEmpty();
    }

    @Test
    void 恢复后结束时刻之后的新计时正常参与排名() {
        List<Checkpoint> three = List.of(
                new Checkpoint("cp1", 1), new Checkpoint("cp2", 2), new Checkpoint("cp3", 3));
        List<Runner> runners = List.of(new Runner("a", 1000L));
        // cp1 暂停前；暂停区间内无计时；恢复后 cp2、cp3 为新计时
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L),
                timing("a", "cp2", 2, 200L),
                timing("a", "cp3", 3, 300L));
        List<Hold> holds = List.of(new Hold("a", 50L, 150L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), three, timings, List.of(), holds);

        ResultEntry a = entries.get(0);
        assertThat(a.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(a.coveredCheckpointCount()).isEqualTo(3);
        assertThat(a.excludedTimingCount()).isZero();
        assertThat(a.rank()).isEqualTo(1);
    }

    @Test
    void 退赛者不排名且不可被生效暂停改变() {
        List<Runner> runners = List.of(new Runner("a", 1000L), new Runner("b", 2000L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L), timing("a", "cp2", 2, 200L),
                timing("b", "cp1", 1, 100L), timing("b", "cp2", 2, 200L));
        List<Hold> holds = List.of(new Hold("a", 1000L, null));
        List<Withdrawal> withdrawals = List.of(new Withdrawal("a", 500L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, withdrawals, holds);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        ResultEntry b = entries.get(0);
        assertThat(b.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(b.rank()).isEqualTo(1);
        ResultEntry a = entries.get(1);
        assertThat(a.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(a.eligibility()).isEqualTo(EligibilityStatus.WITHDRAWN);
        assertThat(a.rank()).isNull();
    }

    @Test
    void 取消资格优先于暂停与漏点状态() {
        List<Runner> runners = List.of(new Runner("a", 1000L));
        List<Timing> timings = List.of(excluded("a", "cp1", 1, 100L));
        List<Hold> holds = List.of(new Hold("a", 1000L, null));
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("a", PenaltyType.DISQUALIFY, null, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints(), timings, List.of(), holds);

        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(entries.get(0).excludedTimingCount()).isEqualTo(1);
    }
}
