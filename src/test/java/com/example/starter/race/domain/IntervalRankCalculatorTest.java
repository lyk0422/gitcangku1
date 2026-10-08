package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntervalRankCalculator} 纯逻辑单元测试：肯定先后关系与名次范围，
 * 覆盖题干合成样例（A净[100,110]、B净[105,115]、C净[120,130]）。
 */
class IntervalRankCalculatorTest {

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

    private record Interval(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long lowerMs,
            long upperMs
    ) implements IntervalView {
    }

    private static Interval finish(String bib, long lower, long upper) {
        return new Interval("t-" + bib + "-f", bib, IntervalRules.FINISH_CODE, 2, lower, upper);
    }

    @Test
    void 合成样例重叠者输出名次范围而非固定名次() {
        List<Runner> runners = List.of(
                new Runner("A", null), new Runner("B", null), new Runner("C", null));
        List<Interval> intervals = List.of(
                finish("A", 100L, 110L),
                finish("B", 105L, 115L),
                finish("C", 120L, 130L));

        List<IntervalResultEntry> entries =
                IntervalRankCalculator.compute(runners, List.of(), List.of(), intervals);

        // 展示顺序按参赛号，但不影响名次范围
        assertThat(entries).extracting(IntervalResultEntry::bib)
                .containsExactly("A", "B", "C");
        // A 与 B 重叠：均为 [1,2]，不能输出固定第一第二
        assertThat(entries.get(0).rankBest()).isEqualTo(1);
        assertThat(entries.get(0).rankWorst()).isEqualTo(2);
        assertThat(entries.get(1).rankBest()).isEqualTo(1);
        assertThat(entries.get(1).rankWorst()).isEqualTo(2);
        // C 肯定晚于 A、B：[3,3]
        assertThat(entries.get(2).rankBest()).isEqualTo(3);
        assertThat(entries.get(2).rankWorst()).isEqualTo(3);
        // 原区间与净区间一并返回
        assertThat(entries.get(0).finishLowerMs()).isEqualTo(100L);
        assertThat(entries.get(0).finishUpperMs()).isEqualTo(110L);
        assertThat(entries.get(0).netLowerMs()).isEqualTo(100L);
        assertThat(entries.get(0).netUpperMs()).isEqualTo(110L);
        assertThat(entries).extracting(IntervalResultEntry::status)
                .containsOnly(EntryStatus.RANKED);
    }

    @Test
    void 端点相等不算肯定领先() {
        List<Runner> runners = List.of(new Runner("A", null), new Runner("B", null));
        List<Interval> intervals = List.of(
                finish("A", 100L, 110L),
                finish("B", 110L, 120L));

        List<IntervalResultEntry> entries =
                IntervalRankCalculator.compute(runners, List.of(), List.of(), intervals);

        // A净U=110 等于 B净L=110：互不肯定领先，名次范围均为 [1,2]
        assertThat(entries.get(0).rankBest()).isEqualTo(1);
        assertThat(entries.get(0).rankWorst()).isEqualTo(2);
        assertThat(entries.get(1).rankBest()).isEqualTo(1);
        assertThat(entries.get(1).rankWorst()).isEqualTo(2);
    }

    @Test
    void 有效加时平移净区间并影响名次范围() {
        List<Runner> runners = List.of(new Runner("A", null), new Runner("B", null));
        List<Interval> intervals = List.of(
                finish("A", 100L, 110L),
                finish("B", 120L, 130L));
        List<Penalty> penalties = List.of(
                new Penalty("A", PenaltyType.ADD_TIME, 50L, false),
                // 已撤销的加时不计入
                new Penalty("B", PenaltyType.ADD_TIME, 500L, true));

        List<IntervalResultEntry> entries =
                IntervalRankCalculator.compute(runners, penalties, List.of(), intervals);

        // A 净区间 [150,160]，肯定晚于 B [120,130]
        assertThat(entries.get(0).netLowerMs()).isEqualTo(150L);
        assertThat(entries.get(0).netUpperMs()).isEqualTo(160L);
        assertThat(entries.get(0).rankBest()).isEqualTo(2);
        assertThat(entries.get(0).rankWorst()).isEqualTo(2);
        assertThat(entries.get(1).rankBest()).isEqualTo(1);
        assertThat(entries.get(1).rankWorst()).isEqualTo(1);
        assertThat(entries.get(1).penaltyMs()).isZero();
    }

    @Test
    void 缺点与无完赛区间与取消资格均不参与名次范围() {
        List<Checkpoint> checkpoints = List.of(new Checkpoint("c1", 1));
        List<Runner> runners = List.of(
                new Runner("full", null),
                new Runner("miss", null),
                new Runner("notime", null),
                new Runner("dq", null));
        List<Interval> intervals = List.of(
                new Interval("t-full-c1", "full", "c1", 1, 50L, 60L),
                finish("full", 100L, 110L),
                finish("miss", 90L, 95L),
                new Interval("t-dq-c1", "dq", "c1", 1, 50L, 60L),
                finish("dq", 80L, 85L));
        List<Penalty> penalties = List.of(
                new Penalty("dq", PenaltyType.DISQUALIFY, null, false));

        List<IntervalResultEntry> entries =
                IntervalRankCalculator.compute(runners, penalties, checkpoints, intervals);

        assertThat(entries).extracting(IntervalResultEntry::bib)
                .containsExactly("full", "dq", "miss", "notime");
        IntervalResultEntry full = entries.get(0);
        assertThat(full.status()).isEqualTo(EntryStatus.RANKED);
        // 完整人数只有 1：名次范围 [1,1]
        assertThat(full.rankBest()).isEqualTo(1);
        assertThat(full.rankWorst()).isEqualTo(1);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(entries.get(1).rankBest()).isNull();
        assertThat(entries.get(2).status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entries.get(2).missingCheckpoints()).containsExactly("c1");
        assertThat(entries.get(2).rankBest()).isNull();
        assertThat(entries.get(3).status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(entries.get(3).rankWorst()).isNull();
    }
}
