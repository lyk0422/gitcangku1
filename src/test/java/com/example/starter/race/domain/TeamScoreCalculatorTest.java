package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamScoreCalculator} 的纯逻辑单元测试：得分合计、并列跳号与不完整队伍。
 */
class TeamScoreCalculatorTest {

    private static ResultEntry ranked(String bib, long totalTimeMs) {
        return new ResultEntry(bib, 1, EntryStatus.RANKED, totalTimeMs, 0L, totalTimeMs);
    }

    private static ResultEntry untimed(String bib) {
        return new ResultEntry(bib, null, EntryStatus.UNTIMED, null, 0L, null);
    }

    @Test
    void 完整队伍按成员总耗时之和排名() {
        List<TeamStanding> standings = TeamScoreCalculator.compute(
                List.of(
                        new TeamScoreCalculator.LockedTeam("t1", 1, List.of("a", "b")),
                        new TeamScoreCalculator.LockedTeam("t2", 2, List.of("c", "d"))),
                List.of(
                        ranked("a", 1000L), ranked("b", 2000L),
                        ranked("c", 1500L), ranked("d", 1000L)));

        assertThat(standings).extracting(TeamStanding::teamId)
                .containsExactly("t2", "t1");
        assertThat(standings).extracting(TeamStanding::teamScoreMs)
                .containsExactly(2500L, 3000L);
        assertThat(standings).extracting(TeamStanding::teamRank)
                .containsExactly(1, 2);
        assertThat(standings).allMatch(TeamStanding::complete);
    }

    @Test
    void 得分并列时同名次并跳号() {
        List<TeamStanding> standings = TeamScoreCalculator.compute(
                List.of(
                        new TeamScoreCalculator.LockedTeam("t1", 1, List.of("a")),
                        new TeamScoreCalculator.LockedTeam("t2", 1, List.of("b")),
                        new TeamScoreCalculator.LockedTeam("t3", 1, List.of("c"))),
                List.of(ranked("a", 1000L), ranked("b", 1000L), ranked("c", 2000L)));

        assertThat(standings).extracting(TeamStanding::teamRank)
                .containsExactly(1, 1, 3);
    }

    @Test
    void 存在未排名成员的队伍不完整且排在完整队伍之后() {
        List<TeamStanding> standings = TeamScoreCalculator.compute(
                List.of(
                        new TeamScoreCalculator.LockedTeam("t1", 1, List.of("a", "b")),
                        new TeamScoreCalculator.LockedTeam("t2", 1, List.of("c", "d"))),
                List.of(ranked("a", 1000L), untimed("b"),
                        ranked("c", 5000L), ranked("d", 5000L)));

        assertThat(standings).extracting(TeamStanding::teamId)
                .containsExactly("t2", "t1");
        TeamStanding incomplete = standings.get(1);
        assertThat(incomplete.complete()).isFalse();
        assertThat(incomplete.teamScoreMs()).isNull();
        assertThat(incomplete.teamRank()).isNull();
    }

    @Test
    void 成员按参赛号字典序规范化返回() {
        List<TeamStanding> standings = TeamScoreCalculator.compute(
                List.of(new TeamScoreCalculator.LockedTeam("t1", 3, List.of("b", "a", "c"))),
                List.of(ranked("a", 1000L), ranked("b", 1000L), ranked("c", 1000L)));

        assertThat(standings.getFirst().members()).containsExactly("a", "b", "c");
        assertThat(standings.getFirst().rosterVersion()).isEqualTo(3);
    }
}
