package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} COUNTBACK 模式单元测试：总耗时相同按倒序段耗时破同分、
 * 全比较值相同才并列跳号、漏点与非法分段不排名、NORMAL 模式算法不变。
 */
class CountbackRankingTest {

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

    private static List<Checkpoint> twoCheckpoints() {
        return List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
    }

    private static Timing timing(String bib, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + position, bib, "cp" + position, position, elapsed);
    }

    /** 题干合成场景：A累计40/100，B累计60/100，C与B相同；三人完赛均为100。 */
    private static List<Runner> abcRunners() {
        return List.of(
                new Runner("a", 100L),
                new Runner("b", 100L),
                new Runner("c", 100L));
    }

    private static List<Timing> abcTimings() {
        return List.of(
                timing("a", 1, 40L), timing("a", 2, 100L),
                timing("b", 1, 60L), timing("b", 2, 100L),
                timing("c", 1, 60L), timing("c", 2, 100L));
    }

    @Test
    void 总成绩相同以后程段耗时破同分_全相同才并列并跳号() {
        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, abcRunners(), List.of(), twoCheckpoints(), abcTimings());

        // 总成绩均100；最后段 b/c=40 优于 a=60，b 与 c 各段全同并列第1，a 跳号为3
        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("b", "c", "a");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 3);
        assertThat(entries).extracting(ResultEntry::status)
                .containsOnly(EntryStatus.RANKED);
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 60L);
        assertThat(entries.get(2).segmentMillisDesc()).containsExactly(60L, 40L);
        assertThat(entries.get(2).totalTimeMs()).isEqualTo(100L);
    }

    @Test
    void 两人场景最后段较小者第一() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 100L));
        List<Timing> timings = List.of(
                timing("a", 1, 40L), timing("a", 2, 100L),
                timing("b", 1, 60L), timing("b", 2, 100L));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, List.of(), twoCheckpoints(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
    }

    @Test
    void 最后段相同再比较倒数第二段() {
        List<Checkpoint> checkpoints = List.of(
                new Checkpoint("cp1", 1), new Checkpoint("cp2", 2), new Checkpoint("cp3", 3));
        // m 段 [30,30,40]，n 段 [10,50,40]：最后段均40，倒数第二段 30<50，m 优先
        List<Runner> runners = List.of(new Runner("m", 100L), new Runner("n", 100L));
        List<Timing> timings = List.of(
                timing("m", 1, 30L), timing("m", 2, 60L), timing("m", 3, 100L),
                timing("n", 1, 10L), timing("n", 2, 60L), timing("n", 3, 100L));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, List.of(), checkpoints, timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("m", "n");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 30L, 30L);
        assertThat(entries.get(1).segmentMillisDesc()).containsExactly(40L, 50L, 10L);
    }

    @Test
    void 处罚只加总成绩不改分段() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 100L));
        List<Timing> timings = List.of(
                timing("a", 1, 40L), timing("a", 2, 100L),
                timing("b", 1, 60L), timing("b", 2, 100L));
        // b 加时5ms：总成绩 105 落后 a 的 100，尽管 b 后程更快；段向量不含处罚
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("b", PenaltyType.ADD_TIME, 5L, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, penalties, twoCheckpoints(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(1).totalTimeMs()).isEqualTo(105L);
        assertThat(entries.get(1).segmentMillisDesc()).containsExactly(40L, 60L);
    }

    @Test
    void 漏点选手继续按原漏点规则不排名() {
        List<Runner> runners = List.of(
                new Runner("full", 100L),
                new Runner("partial", 90L));
        List<Timing> timings = List.of(
                timing("full", 1, 40L), timing("full", 2, 100L),
                timing("partial", 1, 30L));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, List.of(), twoCheckpoints(), timings);

        assertThat(entries.get(0).bib()).isEqualTo("full");
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries.get(1).bib()).isEqualTo("partial");
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).segmentMillisDesc()).isNull();
    }

    @Test
    void 累计耗时不严格递增视为不可排名() {
        // 绕过提交校验的异常数据：cp2 累计不大于 cp1，差值非正，按漏点规则处理
        List<Runner> runners = List.of(new Runner("x", 100L));
        List<Timing> timings = List.of(
                timing("x", 1, 50L), timing("x", 2, 50L));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, List.of(), twoCheckpoints(), timings);

        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entries.get(0).rank()).isNull();
    }

    @Test
    void NORMAL模式同数据仍按旧算法并列() {
        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.NORMAL, abcRunners(), List.of(), twoCheckpoints(), abcTimings());

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 1);
        assertThat(entries).extracting(ResultEntry::segmentMillisDesc)
                .containsOnlyNulls();
    }

    @Test
    void 未配置检查点的COUNTBACK赛事退化为总耗时并列() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 100L));

        List<ResultEntry> entries = ResultCalculator.compute(
                RankingMode.COUNTBACK, runners, List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1);
        assertThat(entries.get(0).segmentMillisDesc()).isEmpty();
    }
}
