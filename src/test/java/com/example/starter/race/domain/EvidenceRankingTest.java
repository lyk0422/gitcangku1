package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 冲线证据裁决顺序与退赛相关单元测试：
 * 已裁决组按证据位置赋不并列名次；部分裁决回退并列；退赛者不排名。
 */
class EvidenceRankingTest {

    private record Runner(String bib, Long finishTimeMs, RunnerStatus status)
            implements ResultCalculator.RunnerView {
        private Runner(String bib, Long finishTimeMs) {
            this(bib, finishTimeMs, RunnerStatus.ACTIVE);
        }

        @Override
        public RunnerStatus entryStatus() {
            return status;
        }
    }

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    @Test
    void 已裁决同计时组按证据顺序赋互不相同名次() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 1000L),
                new Runner("d", 2000L));
        // 证据裁决：c 第一、a 第二、b 第三
        Map<String, Integer> order = Map.of("c", 0, "a", 1, "b", 2);

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), List.of(), List.of(), order);

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("c", "a", "b", "d");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 3, 4);
        assertThat(entries).extracting(ResultEntry::status)
                .containsOnly(EntryStatus.RANKED);
    }

    @Test
    void 无证据顺序时并列同名次并跳号() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }

    @Test
    void 同计时组仅部分持有裁决顺序时回退并列且按参赛号展示() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L),
                new Runner("c", 1000L));
        // 只有 a、b 被旧证据裁决，c 未经裁决（例如裁决后新加入同计时选手）
        Map<String, Integer> order = Map.of("b", 0, "a", 1);

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, List.of(), List.of(), List.of(), order);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 1);
    }

    @Test
    void 加时打破同总耗时后裁决顺序不再作用于跨组排名() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L));
        // 证据裁决 a 在 b 前；但随后 b 的加时被撤销、a 被加时 500ms，a 总耗时反超
        List<Penalty> penalties = List.of(
                new Penalty("a", PenaltyType.ADD_TIME, 500L, false));
        Map<String, Integer> order = Map.of("a", 0, "b", 1);

        List<ResultEntry> entries =
                ResultCalculator.compute(runners, penalties, List.of(), List.of(), order);

        // b=1000 第一，a=1500 第二；两者已不同总耗时，各自独立名次
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
    }

    @Test
    void 退赛者不排名且状态为WITHDRAWN() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L, RunnerStatus.WITHDRAWN),
                new Runner("c", 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "c", "b");
        ResultEntry withdrawn = entries.get(2);
        assertThat(withdrawn.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(withdrawn.rank()).isNull();
        assertThat(withdrawn.totalTimeMs()).isNull();
    }

    @Test
    void 退赛者即使有取消资格处罚也展示为退赛状态() {
        List<Runner> runners = List.of(
                new Runner("a", 1000L),
                new Runner("b", 1000L, RunnerStatus.WITHDRAWN));
        List<Penalty> penalties = List.of(
                new Penalty("b", PenaltyType.DISQUALIFY, null, false));

        List<ResultEntry> entries = ResultCalculator.compute(runners, penalties);

        assertThat(entries.get(1).bib()).isEqualTo("b");
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.WITHDRAWN);
    }
}
