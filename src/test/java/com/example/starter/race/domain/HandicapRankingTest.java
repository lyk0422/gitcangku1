package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 让分赛（HANDICAP）排名单元测试：
 * 精确分数排名、展示值相同不代表并列、缺系数不排名及状态优先级。
 */
class HandicapRankingTest {

    private record Runner(
            String bib,
            Long finishTimeMs,
            Integer factorP,
            Integer factorQ
    ) implements ResultCalculator.RunnerView {
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

    @Test
    void 展示值相同但精确分数不同则不并列() {
        // 合成场景：A 原时1000、B 原时999，系数均1/3，展示均333毫秒但B先A后
        List<Runner> runners = List.of(
                new Runner("A", 1000L, 1, 3),
                new Runner("B", 999L, 1, 3));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.HANDICAP, runners, List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        // 展示值均为 333，但不并列
        assertThat(entries).extracting(ResultEntry::totalTimeMs).containsExactly(333L, 333L);
        assertThat(entries.get(0).scoreNumerator()).isEqualTo(333L);
        assertThat(entries.get(0).scoreDenominator()).isEqualTo(1L);
        assertThat(entries.get(1).scoreNumerator()).isEqualTo(1000L);
        assertThat(entries.get(1).scoreDenominator()).isEqualTo(3L);
    }

    @Test
    void 精确分数相同才并列并跳号() {
        // A 也999：两者精确相等并列1、1，下一名次3
        List<Runner> runners = List.of(
                new Runner("A", 999L, 1, 3),
                new Runner("B", 999L, 2, 6),
                new Runner("C", 1000L, 1, 3));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.HANDICAP, runners, List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("A", "B", "C");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }

    @Test
    void 缺系数选手不排名且状态为MISSING_FACTOR() {
        List<Runner> runners = List.of(
                new Runner("a", 999L, 1, 3),
                new Runner("b", 500L, null, null),
                new Runner("c", null, 1, 3));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.HANDICAP, runners, List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.MISSING_FACTOR,
                        EntryStatus.UNTIMED);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).totalTimeMs()).isNull();
    }

    @Test
    void 取消资格与漏点优先于缺系数() {
        List<Runner> runners = List.of(
                new Runner("dq", 999L, null, null),
                new Runner("mc", 999L, null, null),
                new Runner("ok", 999L, 1, 3));
        List<Penalty> penalties = List.of(
                new Penalty("dq", PenaltyType.DISQUALIFY, null, false));
        List<Checkpoint> checkpoints = List.of(new Checkpoint("cp1", 1));
        List<Timing> timings = List.of(new Timing("t-1", "ok", "cp1", 1, 500L));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.HANDICAP, runners, penalties, checkpoints, timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("ok", "dq", "mc");
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.DISQUALIFIED,
                        EntryStatus.MISSING_CHECKPOINT);
    }

    @Test
    void 让分赛加时并入精确分数() {
        // 999ms、1/3、加时300ms -> (999+900)/3 = 633
        List<Runner> runners = List.of(new Runner("a", 999L, 1, 3));
        List<Penalty> penalties = List.of(
                new Penalty("a", PenaltyType.ADD_TIME, 300L, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.HANDICAP, runners, penalties, List.of(), List.of());

        assertThat(entries.get(0).scoreNumerator()).isEqualTo(633L);
        assertThat(entries.get(0).scoreDenominator()).isEqualTo(1L);
        assertThat(entries.get(0).totalTimeMs()).isEqualTo(633L);
        assertThat(entries.get(0).penaltyMs()).isEqualTo(300L);
    }

    @Test
    void 标准赛事计算不变且无让分字段() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L, null, null),
                new Runner("b", 999L, null, null));

        List<ResultEntry> entries = ResultCalculator.compute(
                ScoringMode.STANDARD, runners, List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).totalTimeMs()).isEqualTo(999L);
        assertThat(entries.get(0).factorP()).isNull();
        assertThat(entries.get(0).factorQ()).isNull();
        assertThat(entries.get(0).scoreNumerator()).isNull();
        assertThat(entries.get(0).scoreDenominator()).isNull();
    }
}
