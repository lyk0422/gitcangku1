package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 纯逻辑单元测试：加时、取消资格、撤销恢复、并列跳号与展示顺序。
 */
class ResultCalculatorTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    @Test
    void 按总耗时升序排名并累加全部未撤销加时() {
        List<Runner> runners = List.of(
                new Runner("A", 1000L),
                new Runner("B", 1000L),
                new Runner("C", 2000L));
        List<Penalty> penalties = List.of(
                new Penalty("A", PenaltyType.ADD_TIME, 500L, false),
                new Penalty("B", PenaltyType.ADD_TIME, 100L, false),
                new Penalty("B", PenaltyType.ADD_TIME, 200L, false));

        List<ResultEntry> entries = ResultCalculator.compute(runners, penalties);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A", "C");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2, 3);
        assertThat(entries.get(0).penaltyMs()).isEqualTo(300L);
        assertThat(entries.get(0).totalTimeMs()).isEqualTo(1300L);
        assertThat(entries.get(1).penaltyMs()).isEqualTo(500L);
        assertThat(entries.get(1).totalTimeMs()).isEqualTo(1500L);
        assertThat(entries.get(2).penaltyMs()).isZero();
        assertThat(entries.get(2).totalTimeMs()).isEqualTo(2000L);
        assertThat(entries).extracting(ResultEntry::status)
                .containsOnly(EntryStatus.RANKED);
    }

    @Test
    void 同耗时同名次并跳号且并列按参赛号字典序展示() {
        List<Runner> runners = List.of(
                new Runner("b10", 1000L),
                new Runner("b2", 1000L),
                new Runner("b1", 1000L),
                new Runner("c", 1500L),
                new Runner("d", 1500L),
                new Runner("e", 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("b1", "b10", "b2", "c", "d", "e");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 1, 4, 4, 6);
    }

    @Test
    void 计时缺失者为UNTIMED且不参与排名() {
        List<Runner> runners = List.of(
                new Runner("x", null),
                new Runner("a", 100L),
                new Runner("m", null));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "m", "x");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.UNTIMED, EntryStatus.UNTIMED);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).totalTimeMs()).isNull();
    }

    @Test
    void 存在生效取消资格处罚时不排名且全部撤销后恢复计算() {
        List<Runner> runners = List.of(
                new Runner("a", 100L),
                new Runner("b", 200L));
        List<Penalty> disqualified = List.of(
                new Penalty("a", PenaltyType.DISQUALIFY, null, false));

        List<ResultEntry> dqEntries = ResultCalculator.compute(runners, disqualified);
        assertThat(dqEntries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(dqEntries.get(0).rank()).isEqualTo(1);
        assertThat(dqEntries.get(1).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(dqEntries.get(1).rank()).isNull();
        assertThat(dqEntries.get(1).totalTimeMs()).isNull();

        List<Penalty> revoked = List.of(
                new Penalty("a", PenaltyType.DISQUALIFY, null, true));
        List<ResultEntry> restored = ResultCalculator.compute(runners, revoked);
        assertThat(restored).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(restored.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(restored.get(0).rank()).isEqualTo(1);
    }

    @Test
    void 已撤销加时不计入总耗时() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 200L));
        List<Penalty> penalties = List.of(
                new Penalty("b", PenaltyType.ADD_TIME, 5000L, true),
                new Penalty("b", PenaltyType.ADD_TIME, 50L, false));

        List<ResultEntry> entries = ResultCalculator.compute(runners, penalties);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries.get(1).penaltyMs()).isEqualTo(50L);
        assertThat(entries.get(1).totalTimeMs()).isEqualTo(250L);
    }
}
