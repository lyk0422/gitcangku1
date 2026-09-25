package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelayStandingCalculator} 纯逻辑单测：排名、并列跳号、犯规标注与取消资格排序。
 */
class RelayStandingCalculatorTest {

    private RelayStandingCalculator.RelayTeamAggregate team(
            String key, RelayTeamStatus status, int fouls, Long total, Long finishedAt) {
        return new RelayStandingCalculator.RelayTeamAggregate(key, status, fouls, total, finishedAt);
    }

    @Test
    void 完赛队伍按总用时升序赋名次() {
        List<RelayStandingCalculator.RelayRankEntry> entries = RelayStandingCalculator.compute(List.of(
                team("c", RelayTeamStatus.RANKED, 0, 3000L, 30L),
                team("a", RelayTeamStatus.RANKED, 0, 1000L, 10L),
                team("b", RelayTeamStatus.RANKED, 0, 2000L, 20L)));

        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::teamKey)
                .containsExactly("a", "b", "c");
        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::rank)
                .containsExactly(1, 2, 3);
        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::displayOrder)
                .containsExactly(0, 1, 2);
    }

    @Test
    void 同用时并列同名次并跳号() {
        List<RelayStandingCalculator.RelayRankEntry> entries = RelayStandingCalculator.compute(List.of(
                team("b", RelayTeamStatus.RANKED, 0, 1000L, 10L),
                team("c", RelayTeamStatus.RANKED, 0, 1000L, 10L),
                team("a", RelayTeamStatus.RANKED, 0, 1000L, 10L),
                team("d", RelayTeamStatus.RANKED, 0, 2000L, 20L)));

        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::teamKey)
                .containsExactly("a", "b", "c", "d");
        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::rank)
                .containsExactly(1, 1, 1, 4);
    }

    @Test
    void 单次犯规仅标注仍参与排名() {
        List<RelayStandingCalculator.RelayRankEntry> entries = RelayStandingCalculator.compute(List.of(
                team("clean", RelayTeamStatus.RANKED, 0, 2000L, 20L),
                team("foul1", RelayTeamStatus.RANKED, 1, 1000L, 10L)));

        RelayStandingCalculator.RelayRankEntry foul = entries.getFirst();
        assertThat(foul.teamKey()).isEqualTo("foul1");
        assertThat(foul.rank()).isEqualTo(1);
        assertThat(foul.foul()).isTrue();
        assertThat(foul.status()).isEqualTo(RelayTeamStatus.RANKED);
        assertThat(entries.get(1).foul()).isFalse();
    }

    @Test
    void 两次犯规取消资格排在完赛队伍之后且不排名() {
        List<RelayStandingCalculator.RelayRankEntry> entries = RelayStandingCalculator.compute(List.of(
                team("dq-z", RelayTeamStatus.DISQUALIFIED, 2, 500L, 5L),
                team("ok", RelayTeamStatus.RANKED, 0, 9000L, 90L)));

        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::teamKey)
                .containsExactly("ok", "dq-z");
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).status()).isEqualTo(RelayTeamStatus.DISQUALIFIED);
    }

    @Test
    void 未完赛队伍排在最后且无总用时() {
        List<RelayStandingCalculator.RelayRankEntry> entries = RelayStandingCalculator.compute(List.of(
                team("racing-b", RelayTeamStatus.RACING, 0, null, null),
                team("ok", RelayTeamStatus.RANKED, 0, 9000L, 90L),
                team("dq", RelayTeamStatus.DISQUALIFIED, 2, 100L, 1L),
                team("racing-a", RelayTeamStatus.RACING, 1, null, null)));

        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::teamKey)
                .containsExactly("ok", "dq", "racing-a", "racing-b");
        assertThat(entries).extracting(RelayStandingCalculator.RelayRankEntry::status)
                .containsExactly(RelayTeamStatus.RANKED, RelayTeamStatus.DISQUALIFIED,
                        RelayTeamStatus.RACING, RelayTeamStatus.RACING);
        assertThat(entries.get(2).foul()).isTrue();
        assertThat(entries).filteredOn(e -> e.status() != RelayTeamStatus.RANKED)
                .allSatisfy(e -> assertThat(e.rank()).isNull());
    }
}
