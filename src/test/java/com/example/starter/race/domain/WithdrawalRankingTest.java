package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 退赛相关单元测试：DNS/DNF 不占名次、
 * 名次在剩余选手上从1连续编号且并列跳号、撤销退赛后恢复计算、
 * 最后通过检查点随分段记录固化。
 */
class WithdrawalRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis
    ) implements TimingView {
    }

    private record Withdrawal(
            String bib,
            WithdrawalStatus status,
            boolean revoked
    ) implements ResultCalculator.WithdrawalView {
    }

    private static List<Checkpoint> checkpoints() {
        return List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
    }

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed);
    }

    @Test
    void 退赛选手不占名次且名次在剩余选手上从1连续编号() {
        List<Runner> runners = List.of(
                new Runner("fast", 1000L),
                new Runner("tied1", 2000L),
                new Runner("tied2", 2000L),
                new Runner("slow", 3000L),
                new Runner("dns-runner", null),
                new Runner("dnf-runner", null));
        List<Timing> timings = List.of(
                timing("fast", "cp1", 1, 100L), timing("fast", "cp2", 2, 200L),
                timing("tied1", "cp1", 1, 100L), timing("tied1", "cp2", 2, 200L),
                timing("tied2", "cp1", 1, 100L), timing("tied2", "cp2", 2, 200L),
                timing("slow", "cp1", 1, 100L), timing("slow", "cp2", 2, 200L),
                timing("dnf-runner", "cp1", 1, 150L));
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("dns-runner", WithdrawalStatus.DNS, false),
                new Withdrawal("dnf-runner", WithdrawalStatus.DNF, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, withdrawals);

        // 退赛选手单独列在最后且不占名次；并列仍跳号（1、2、2、4）
        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("fast", "tied1", "tied2", "slow", "dnf-runner", "dns-runner");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 2, 4, null, null);
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.RANKED, EntryStatus.DNF, EntryStatus.DNS);
    }

    @Test
    void 撤销退赛后选手恢复原有状态计算() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 2000L));
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("b", WithdrawalStatus.DNF, true));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), withdrawals);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED);
    }

    @Test
    void 最后通过检查点取已有分段中顺序最大者() {
        List<Runner> runners = List.of(new Runner("a", null));
        List<Timing> timings = List.of(
                timing("a", "cp2", 2, 300L),
                timing("a", "cp1", 1, 100L));
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("a", WithdrawalStatus.DNF, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), timings, withdrawals);

        assertThat(entries).hasSize(1);
        ResultEntry entry = entries.getFirst();
        assertThat(entry.status()).isEqualTo(EntryStatus.DNF);
        assertThat(entry.lastCheckpointCode()).isEqualTo("cp2");
        assertThat(entry.coveredCheckpointCount()).isEqualTo(2);
        assertThat(entry.missingCheckpoints()).isEmpty();
    }

    @Test
    void 无分段记录的选手最后通过检查点为null() {
        List<Runner> runners = List.of(new Runner("a", null));
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("a", WithdrawalStatus.DNS, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints(), List.of(), withdrawals);

        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.DNS);
        assertThat(entries.getFirst().lastCheckpointCode()).isNull();
        assertThat(entries.getFirst().missingCheckpoints()).containsExactly("cp1", "cp2");
    }

    @Test
    void 退赛与取消资格并存时退赛状态优先展示且均不排名() {
        List<Runner> runners = List.of(
                new Runner("raced", 1000L),
                new Runner("withdrawn", null));
        List<ResultCalculator.PenaltyView> penalties = List.of();
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("withdrawn", WithdrawalStatus.DNS, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(), withdrawals);

        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, null);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.DNS);
    }
}
