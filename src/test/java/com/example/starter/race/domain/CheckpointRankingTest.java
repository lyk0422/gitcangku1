package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 检查点覆盖相关单元测试：漏点不排名、全覆盖恢复、
 * 未配置检查点沿用旧规则，以及取消资格优先级。
 */
class CheckpointRankingTest {

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

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed);
    }

    @Test
    void 有完赛但未覆盖全部检查点者不排名并列出缺失项() {
        List<Runner> runners = List.of(
                new Runner("full", 1000L),
                new Runner("partial", 900L),
                new Runner("none", 800L));
        List<Timing> timings = List.of(
                timing("full", "cp1", 1, 100L),
                timing("full", "cp2", 2, 200L),
                timing("partial", "cp1", 1, 100L));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints(), timings);

        // partial 与 none 虽完赛耗时更短，但漏点不排名；只有 full 上榜
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("full", "none", "partial");
        ResultEntry full = entries.get(0);
        assertThat(full.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(full.rank()).isEqualTo(1);
        assertThat(full.checkpointCount()).isEqualTo(2);
        assertThat(full.coveredCheckpointCount()).isEqualTo(2);
        assertThat(full.missingCheckpoints()).isEmpty();

        ResultEntry none = entries.get(1);
        assertThat(none.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(none.rank()).isNull();
        assertThat(none.totalTimeMs()).isNull();
        assertThat(none.coveredCheckpointCount()).isZero();
        assertThat(none.missingCheckpoints()).containsExactly("cp1", "cp2");

        ResultEntry partial = entries.get(2);
        assertThat(partial.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(partial.missingCheckpoints()).containsExactly("cp2");
    }

    @Test
    void 全部覆盖后恢复加时与并列排名规则() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 100L), timing("a", "cp2", 2, 200L),
                timing("b", "cp1", 1, 100L), timing("b", "cp2", 2, 200L));
        // b 加时 500ms
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("b", PenaltyType.ADD_TIME, 500L, false));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, penalties, checkpoints(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::status).containsOnly(EntryStatus.RANKED);
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(1).totalTimeMs()).isEqualTo(1500L);
    }

    @Test
    void 无完赛计时仍为UNTIMED而非漏点() {
        List<Runner> runners = List.of(new Runner("u", null));
        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), checkpoints(), List.of());
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(entries.get(0).missingCheckpoints()).containsExactly("cp1", "cp2");
    }

    @Test
    void 未配置检查点的赛事沿用原排名规则() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 200L));
        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), List.of(), List.of());
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(ResultEntry::checkpointCount).containsOnly(0);
    }

    @Test
    void 取消资格优先于漏点状态() {
        List<Runner> runners = List.of(new Runner("a", 1000L));
        List<Timing> timings = List.of(timing("a", "cp1", 1, 100L));
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("a", PenaltyType.DISQUALIFY, null, false));

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, penalties, checkpoints(), timings);

        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(entries.get(0).missingCheckpoints()).containsExactly("cp2");
    }
}
