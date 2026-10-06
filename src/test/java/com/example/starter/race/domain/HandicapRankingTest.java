package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} HANDICAP 让分模式纯逻辑单元测试：
 * 精确分数排名、展示值不参与排序、缺系数不排名、并列跳号与优先级。
 */
class HandicapRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    private record Factor(String bib, int p, int q) implements ResultCalculator.FactorView {
    }

    private static List<ResultEntry> handicap(
            List<Runner> runners, List<Penalty> penalties, List<Factor> factors) {
        return ResultCalculator.compute(runners, penalties, List.of(), List.of(),
                ScoringMode.HANDICAP, factors);
    }

    @Test
    void 合成场景_展示相同但精确分数更小者在前() {
        // A 原时 1000、B 原时 999，系数均 1/3：展示均 333 毫秒，但 B 精确 333 < A 精确 1000/3
        List<ResultEntry> entries = handicap(
                List.of(new Runner("A", 1000L), new Runner("B", 999L)),
                List.of(),
                List.of(new Factor("A", 1, 3), new Factor("B", 1, 3)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(ResultEntry::effectiveDisplayMs)
                .containsExactly(333L, 333L);
        assertThat(entries.get(0).effectiveNum()).isEqualTo(333L);
        assertThat(entries.get(0).effectiveDen()).isEqualTo(1L);
        assertThat(entries.get(1).effectiveNum()).isEqualTo(1000L);
        assertThat(entries.get(1).effectiveDen()).isEqualTo(3L);
    }

    @Test
    void 合成场景_精确相同才并列且跳号() {
        // A 也改为 999：两者精确相同并列 1、1，下一名次 3
        List<ResultEntry> entries = handicap(
                List.of(new Runner("A", 999L), new Runner("B", 999L), new Runner("C", 1000L)),
                List.of(),
                List.of(new Factor("A", 1, 3), new Factor("B", 2, 6), new Factor("C", 1, 3)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("A", "B", "C");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }

    @Test
    void 缺系数选手不排名且状态为MISSING_FACTOR() {
        List<ResultEntry> entries = handicap(
                List.of(new Runner("a", 100L), new Runner("b", 200L)),
                List.of(),
                List.of(new Factor("a", 1, 2)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.MISSING_FACTOR);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).effectiveNum()).isNull();
        assertThat(entries.get(1).effectiveDisplayMs()).isNull();
    }

    @Test
    void 取消资格与计时缺失优先于缺系数判定() {
        List<ResultEntry> entries = handicap(
                List.of(new Runner("dq", 100L), new Runner("untimed", null), new Runner("ok", 200L)),
                List.of(new Penalty("dq", PenaltyType.DISQUALIFY, null, false)),
                List.of(new Factor("ok", 1, 1)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("ok", "dq", "untimed");
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(entries.get(2).status()).isEqualTo(EntryStatus.UNTIMED);
    }

    @Test
    void 罚时计入有效成绩的精确分数() {
        // a: 1000*1/2 + 100 = 600；b: 1100*1/2 = 550 -> b 在前
        List<ResultEntry> entries = handicap(
                List.of(new Runner("a", 1000L), new Runner("b", 1100L)),
                List.of(new Penalty("a", PenaltyType.ADD_TIME, 100L, false)),
                List.of(new Factor("a", 1, 2), new Factor("b", 1, 2)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries.get(1).effectiveNum()).isEqualTo(600L);
        assertThat(entries.get(1).effectiveDen()).isEqualTo(1L);
        assertThat(entries.get(1).penaltyMs()).isEqualTo(100L);
        // totalTimeMs 保持原语义：原始耗时+加时
        assertThat(entries.get(1).totalTimeMs()).isEqualTo(1100L);
    }

    @Test
    void 标准模式忽略系数且计算不变() {
        List<ResultEntry> entries = ResultCalculator.compute(
                List.of(new Runner("a", 1000L), new Runner("b", 999L)),
                List.of(), List.of(), List.of(),
                ScoringMode.STANDARD,
                List.of(new Factor("a", 1, 1000), new Factor("b", 1, 1000)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).factorP()).isNull();
        assertThat(entries.get(0).effectiveNum()).isNull();
    }
}
