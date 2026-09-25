package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 退赛场景纯逻辑测试：
 * DNS/DNF 排除排名、名次从1连续重排、并列跳号、撤销恢复与最后通过检查点。
 */
class WithdrawalRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
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

    @Test
    void DNS与DNF不占名次且剩余选手从1连续编号并列跳号() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 2000L),
                new Runner("d", 500L),
                new Runner("e", 100L));
        List<Withdrawal> withdrawals = List.of(
                new Withdrawal("d", WithdrawalStatus.DNS, false),
                new Withdrawal("e", WithdrawalStatus.DNF, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), withdrawals);

        // 排名区：a、b 并列第1，c 第3；退赛区 d、e 按参赛号字典序单独列出且无名次
        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("a", "b", "c", "d", "e");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 3, null, null);
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.DNS, EntryStatus.DNF);
        assertThat(entries.get(3).totalTimeMs()).isNull();
        assertThat(entries.get(4).totalTimeMs()).isNull();
    }

    @Test
    void 撤销退赛后恢复按计时与检查点覆盖计算() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 2000L));
        List<Checkpoint> checkpoints = List.of(new Checkpoint("cp1", 1));
        List<Timing> timings = List.of(new Timing("t1", "b", "cp1", 1, 500L));

        List<ResultEntry> withdrawn = ResultCalculator.compute(
                runners, List.of(), checkpoints, timings,
                List.of(new Withdrawal("b", WithdrawalStatus.DNF, false)));
        // a 漏点不排名，b 退赛不排名，均按参赛号字典序列于榜尾
        assertThat(withdrawn).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(withdrawn).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT, EntryStatus.DNF);

        List<ResultEntry> restored = ResultCalculator.compute(
                runners, List.of(), checkpoints, timings,
                List.of(new Withdrawal("b", WithdrawalStatus.DNF, true)));
        // b 全覆盖检查点恢复排名；a 漏点不排名，排名区在前
        assertThat(restored).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(restored).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.MISSING_CHECKPOINT);
        assertThat(restored.get(0).rank()).isEqualTo(1);
    }

    @Test
    void 退赛状态优先于取消资格且生效加时仍累计展示() {
        List<Runner> runners = List.of(new Runner("a", 1000L), new Runner("b", 2000L));
        List<Penalty> penalties = List.of(
                new Penalty("b", PenaltyType.DISQUALIFY, null, false),
                new Penalty("b", PenaltyType.ADD_TIME, 100L, false));

        // 仅取消资格：DISQUALIFIED
        List<ResultEntry> dqOnly = ResultCalculator.compute(runners, penalties);
        assertThat(dqOnly.get(1).status()).isEqualTo(EntryStatus.DISQUALIFIED);

        // 退赛与取消资格并存：退赛状态优先
        List<ResultEntry> both = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(),
                List.of(new Withdrawal("b", WithdrawalStatus.DNF, false)));
        assertThat(both.get(1).status()).isEqualTo(EntryStatus.DNF);
        assertThat(both.get(1).rank()).isNull();
        assertThat(both.get(1).penaltyMs()).isEqualTo(100L);
    }

    @Test
    void 最后通过检查点取顺序最大的分段记录() {
        List<Runner> runners = List.of(new Runner("a", 10_000L), new Runner("b", null));
        List<Checkpoint> checkpoints = List.of(
                new Checkpoint("cp1", 1), new Checkpoint("cp2", 2), new Checkpoint("cp3", 3));
        List<Timing> timings = List.of(
                new Timing("t2", "a", "cp2", 2, 500L),
                new Timing("t1", "a", "cp1", 1, 100L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), checkpoints, timings, List.of());

        ResultEntry a = entries.stream().filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        assertThat(a.lastCheckpointCode()).isEqualTo("cp2");
        assertThat(a.coveredCheckpointCount()).isEqualTo(2);
        assertThat(a.missingCheckpoints()).containsExactly("cp3");
        ResultEntry b = entries.stream().filter(e -> e.bib().equals("b")).findFirst().orElseThrow();
        assertThat(b.lastCheckpointCode()).isNull();
    }

    @Test
    void 未配置检查点的赛事退赛选手同样被排除() {
        List<Runner> runners = List.of(
                new Runner("a", 100L),
                new Runner("b", 200L),
                new Runner("c", 300L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(),
                List.of(new Withdrawal("b", WithdrawalStatus.DNS, false)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "c", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2, null);
        assertThat(entries.get(2).status()).isEqualTo(EntryStatus.DNS);
    }
}
