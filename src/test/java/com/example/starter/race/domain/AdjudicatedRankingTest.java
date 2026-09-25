package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 冲线证据裁决相关单元测试：
 * 已裁决计时组按裁决顺序排定不重复名次；未裁决组沿用并列同名次；
 * 裁决后新增处罚只按总耗时重排，不回写裁决快照。
 */
class AdjudicatedRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    private record Adjudication(
            long finishTimeMs,
            List<String> finalOrder
    ) implements ResultCalculator.AdjudicationView {
    }

    @Test
    void 已裁决计时组按裁决顺序排定不重复名次() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 1000L),
                new Runner("d", 2000L));
        List<Adjudication> adjudications = List.of(
                new Adjudication(1000L, List.of("c", "a", "b")));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), adjudications);

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("c", "a", "b", "d");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void 未裁决计时组沿用并列同名次跳号() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }

    @Test
    void 裁决后新增处罚按总耗时重排且不回写快照() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 1000L));
        List<Adjudication> adjudications = List.of(
                new Adjudication(1000L, List.of("c", "a", "b")));
        // c 加时 500ms：总耗时 1500ms 落到组外，a、b 仍按裁决顺序排定
        List<Penalty> penalties = List.of(
                new Penalty("c", PenaltyType.ADD_TIME, 500L, false));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(), adjudications);

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 3);
        assertThat(entries.get(2).totalTimeMs()).isEqualTo(1500L);
    }

    @Test
    void 总耗时相同但原始耗时不同的并列不受裁决影响() {
        // a 原始 1000ms 无处罚，b 原始 900ms 加时 100ms：总耗时相同但不同计时组
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 900L));
        List<Penalty> penalties = List.of(
                new Penalty("b", PenaltyType.ADD_TIME, 100L, false));
        List<Adjudication> adjudications = List.of(
                new Adjudication(1000L, List.of("a")));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(), adjudications);

        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1);
    }

    @Test
    void 裁决组内快照外选手排在裁决顺序之后() {
        // d 与 a、b 同计时但未被证据覆盖：排在裁决顺序之后，仍获不重复名次
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("d", 1000L));
        List<Adjudication> adjudications = List.of(
                new Adjudication(1000L, List.of("b", "a")));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), adjudications);

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("b", "a", "d");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 3);
    }
}
