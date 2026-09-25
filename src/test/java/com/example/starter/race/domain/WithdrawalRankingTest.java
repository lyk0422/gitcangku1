package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 退赛相关纯逻辑测试：DNS/DNF 排除出名次、
 * 剩余选手从1连续编号、并列跳号、退赛选手单独列在末尾。
 */
class WithdrawalRankingTest {

    private record Runner(
            String bib,
            Long finishTimeMs,
            EntryStatus withdrawalStatus,
            String lastCheckpointCode
    ) implements ResultCalculator.RunnerView {
    }

    private static Runner ranked(String bib, long finish) {
        return new Runner(bib, finish, null, null);
    }

    private static Runner dns(String bib) {
        return new Runner(bib, null, EntryStatus.DNS, null);
    }

    private static Runner dnf(String bib, String lastCheckpoint) {
        return new Runner(bib, null, EntryStatus.DNF, lastCheckpoint);
    }

    @Test
    void 退赛选手不占名次且剩余选手从1连续编号() {
        List<Runner> runners = List.of(
                ranked("a", 3000L),
                ranked("b", 1000L),
                dns("c"),
                ranked("d", 2000L),
                dnf("e", "cp2"));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("b", "d", "a", "c", "e");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 2, 3, null, null);
        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(
                        EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.DNS, EntryStatus.DNF);
        assertThat(entries.get(4).lastCheckpointCode()).isEqualTo("cp2");
        assertThat(entries.get(3).lastCheckpointCode()).isNull();
    }

    @Test
    void 并列仍占同名次并跳过序号且退赛者不影响跳号() {
        List<Runner> runners = List.of(
                ranked("b1", 1000L),
                ranked("b2", 1000L),
                dnf("d", "cp1"),
                ranked("c", 1500L),
                dns("x"));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("b1", "b2", "c", "d", "x");
        assertThat(entries).extracting(ResultEntry::rank)
                .containsExactly(1, 1, 3, null, null);
    }

    @Test
    void 退赛状态优先于取消资格与缺点判定() {
        // 即便残留未撤销取消资格处罚，退赛选手仍以 DNS/DNF 单独展示。
        ResultCalculator.PenaltyView dqPenalty = new ResultCalculator.PenaltyView() {
            @Override
            public String bib() {
                return "z";
            }

            @Override
            public PenaltyType type() {
                return PenaltyType.DISQUALIFY;
            }

            @Override
            public Long amountMs() {
                return null;
            }

            @Override
            public boolean revoked() {
                return false;
            }
        };

        List<ResultEntry> entries = ResultCalculator.compute(
                List.of(ranked("a", 1000L), dnf("z", "cp1")),
                List.of(dqPenalty));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "z");
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.DNF);
        assertThat(entries.get(1).rank()).isNull();
    }

    @Test
    void 无退赛时沿用原排名与展示规则() {
        List<Runner> runners = List.of(
                ranked("a", 2000L),
                ranked("b", 1000L));

        List<ResultEntry> entries = ResultCalculator.compute(runners, List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(ResultEntry::lastCheckpointCode)
                .containsOnlyNulls();
    }
}
