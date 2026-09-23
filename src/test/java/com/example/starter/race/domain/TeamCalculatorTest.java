package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamCalculator} 纯逻辑单元测试：取前3计分、整数合计、并列跳号、
 * INCOMPLETE 置底、成员入选标记与排序稳定性。
 */
class TeamCalculatorTest {

    /** 构造一个 RANKED 个人条目，团队计算只依赖 bib/rank/status/totalTimeMs。 */
    private static ResultEntry ranked(String bib, Integer rank, long totalTimeMs) {
        return new ResultEntry(bib, rank, EntryStatus.RANKED, totalTimeMs, 0L, totalTimeMs);
    }

    private static ResultEntry nonRanked(String bib, EntryStatus status) {
        return new ResultEntry(bib, null, status, null, 0L, null);
    }

    @Test
    void 取队内总耗时最少的前三人合计团队成绩() {
        List<ResultEntry> entries = List.of(
                ranked("a", 1, 1000L),
                ranked("b", 2, 1100L),
                ranked("c", 3, 1200L),
                ranked("d", 4, 1300L));
        List<TeamView> teams = List.of(new TeamView("T1", List.of("d", "b", "a", "c")));

        List<TeamResult> results = TeamCalculator.compute(teams, entries);

        assertThat(results).hasSize(1);
        TeamResult team = results.getFirst();
        assertThat(team.status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(team.rank()).isEqualTo(1);
        assertThat(team.totalTimeMs()).isEqualTo(1000L + 1100L + 1200L);
        assertThat(team.members()).extracting(TeamMemberScore::bib)
                .containsExactly("a", "b", "c", "d");
        assertThat(team.members()).extracting(TeamMemberScore::scored)
                .containsExactly(true, true, true, false);
    }

    @Test
    void 不足三个RANKED成员时团队为INCOMPLETE且名次耗时为null() {
        List<ResultEntry> entries = List.of(
                ranked("a", 1, 1000L),
                ranked("b", 2, 1100L),
                nonRanked("c", EntryStatus.UNTIMED),
                nonRanked("d", EntryStatus.DISQUALIFIED));
        List<TeamView> teams = List.of(new TeamView("T1", List.of("a", "b", "c", "d")));

        List<TeamResult> results = TeamCalculator.compute(teams, entries);

        TeamResult team = results.getFirst();
        assertThat(team.status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(team.rank()).isNull();
        assertThat(team.totalTimeMs()).isNull();
        assertThat(team.members()).extracting(TeamMemberScore::scored)
                .containsOnly(false);
    }

    @Test
    void 团队按总耗时排名同分并列跳号再按teamCode排序() {
        List<ResultEntry> entries = List.of(
                ranked("a1", 1, 100L), ranked("a2", 2, 100L), ranked("a3", 3, 100L),
                ranked("b1", 1, 100L), ranked("b2", 2, 100L), ranked("b3", 3, 100L),
                ranked("c1", 1, 200L), ranked("c2", 2, 200L), ranked("c3", 3, 200L));
        List<TeamView> teams = List.of(
                new TeamView("TB", List.of("b1", "b2", "b3")),
                new TeamView("TC", List.of("c1", "c2", "c3")),
                new TeamView("TA", List.of("a1", "a2", "a3")));

        List<TeamResult> results = TeamCalculator.compute(teams, entries);

        assertThat(results).extracting(TeamResult::teamCode).containsExactly("TA", "TB", "TC");
        assertThat(results).extracting(TeamResult::rank).containsExactly(1, 1, 3);
        assertThat(results).extracting(TeamResult::totalTimeMs)
                .containsExactly(300L, 300L, 600L);
    }

    @Test
    void INCOMPLETE团队置于末尾按teamCode排序() {
        List<ResultEntry> entries = List.of(
                ranked("a1", 1, 100L), ranked("a2", 2, 100L), ranked("a3", 3, 100L),
                ranked("z1", 1, 50L),
                ranked("m1", 1, 60L));
        List<TeamView> teams = List.of(
                new TeamView("TZ", List.of("z1")),
                new TeamView("TA", List.of("a1", "a2", "a3")),
                new TeamView("TM", List.of("m1")));

        List<TeamResult> results = TeamCalculator.compute(teams, entries);

        assertThat(results).extracting(TeamResult::teamCode).containsExactly("TA", "TM", "TZ");
        assertThat(results).extracting(TeamResult::status)
                .containsExactly(TeamStatus.COMPLETE, TeamStatus.INCOMPLETE, TeamStatus.INCOMPLETE);
    }

    @Test
    void 同队内总耗时并列时按参赛号字典序取前三人() {
        List<ResultEntry> entries = List.of(
                ranked("z", 1, 100L),
                ranked("a", 1, 100L),
                ranked("m", 1, 100L),
                ranked("b", 1, 100L));
        List<TeamView> teams = List.of(new TeamView("T1", List.of("z", "a", "m", "b")));

        TeamResult team = TeamCalculator.compute(teams, entries).getFirst();

        assertThat(team.totalTimeMs()).isEqualTo(300L);
        assertThat(team.members()).filteredOn(TeamMemberScore::scored)
                .extracting(TeamMemberScore::bib)
                .containsExactly("a", "b", "m");
    }
}
