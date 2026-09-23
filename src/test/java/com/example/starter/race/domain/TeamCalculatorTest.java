package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队计分纯逻辑测试：前3人入选与合计、INCOMPLETE、并列跳号与展示顺序。
 */
class TeamCalculatorTest {

    private static ResultEntry ranked(String bib, long totalTimeMs) {
        return new ResultEntry(bib, 1, EntryStatus.RANKED, totalTimeMs, 0L, totalTimeMs);
    }

    private static ResultEntry untimed(String bib) {
        return new ResultEntry(bib, null, EntryStatus.UNTIMED, null, 0L, null);
    }

    private record TeamDef(String teamCode, List<String> memberBibs)
            implements TeamCalculator.TeamView {
    }

    @Test
    void 完整团队取总耗时最小的3人合计并标记入选成员() {
        List<ResultEntry> entries = List.of(
                ranked("a", 100), ranked("b", 200), ranked("c", 300), ranked("d", 400));
        List<TeamDef> teams = List.of(new TeamDef("T1", List.of("a", "b", "c", "d")));

        List<TeamStanding> standings = TeamCalculator.compute(entries, teams);

        assertThat(standings).hasSize(1);
        TeamStanding standing = standings.getFirst();
        assertThat(standing.status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(standing.rank()).isEqualTo(1);
        assertThat(standing.totalTimeMs()).isEqualTo(600L);
        assertThat(standing.members()).containsExactly(
                new TeamMemberResult("a", true, 100L),
                new TeamMemberResult("b", true, 200L),
                new TeamMemberResult("c", true, 300L),
                new TeamMemberResult("d", false, null));
    }

    @Test
    void 入选同耗时按参赛号字典序决胜() {
        List<ResultEntry> entries = List.of(
                ranked("b", 100), ranked("a", 100), ranked("c", 100), ranked("d", 50));
        List<TeamDef> teams = List.of(new TeamDef("T1", List.of("a", "b", "c", "d")));

        List<TeamStanding> standings = TeamCalculator.compute(entries, teams);

        TeamStanding standing = standings.getFirst();
        // d(50) 必选；a/b/c 同耗时取字典序较小的 a、b
        assertThat(standing.totalTimeMs()).isEqualTo(250L);
        assertThat(standing.members()).containsExactly(
                new TeamMemberResult("a", true, 100L),
                new TeamMemberResult("b", true, 100L),
                new TeamMemberResult("c", false, null),
                new TeamMemberResult("d", true, 50L));
    }

    @Test
    void ranked成员不足3人时INCOMPLETE且名次与总耗时为空() {
        List<ResultEntry> entries = List.of(
                ranked("a", 100), ranked("b", 200), untimed("c"));
        List<TeamDef> teams = List.of(new TeamDef("T1", List.of("a", "b", "c")));

        List<TeamStanding> standings = TeamCalculator.compute(entries, teams);

        TeamStanding standing = standings.getFirst();
        assertThat(standing.status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(standing.rank()).isNull();
        assertThat(standing.totalTimeMs()).isNull();
        assertThat(standing.members()).containsExactly(
                new TeamMemberResult("a", false, null),
                new TeamMemberResult("b", false, null),
                new TeamMemberResult("c", false, null));
    }

    @Test
    void 完全同分并列同名次并跳号且展示按团队代码() {
        List<ResultEntry> entries = List.of(
                ranked("a1", 100), ranked("a2", 100), ranked("a3", 100),
                ranked("b1", 100), ranked("b2", 100), ranked("b3", 100),
                ranked("c1", 200), ranked("c2", 200), ranked("c3", 200));
        List<TeamDef> teams = List.of(
                new TeamDef("TB", List.of("b1", "b2", "b3")),
                new TeamDef("TA", List.of("a1", "a2", "a3")),
                new TeamDef("TC", List.of("c1", "c2", "c3")));

        List<TeamStanding> standings = TeamCalculator.compute(entries, teams);

        // TA/TB 同分 300 并列第1，TC 第3；并列展示按 teamCode
        assertThat(standings).extracting(TeamStanding::teamCode)
                .containsExactly("TA", "TB", "TC");
        assertThat(standings).extracting(TeamStanding::rank)
                .containsExactly(1, 1, 3);
        assertThat(standings).extracting(TeamStanding::totalTimeMs)
                .containsExactly(300L, 300L, 600L);
    }

    @Test
    void INCOMPLETE团队置于末尾并按团队代码排序() {
        List<ResultEntry> entries = List.of(
                ranked("a1", 100), ranked("a2", 100), ranked("a3", 100),
                ranked("z1", 50), untimed("z2"), untimed("y1"));
        List<TeamDef> teams = List.of(
                new TeamDef("TZ", List.of("z1", "z2", "y1")),
                new TeamDef("TA", List.of("a1", "a2", "a3")),
                new TeamDef("TY", List.of("y1", "z2", "z1")));

        List<TeamStanding> standings = TeamCalculator.compute(entries, teams);

        assertThat(standings).extracting(TeamStanding::teamCode)
                .containsExactly("TA", "TY", "TZ");
        assertThat(standings.get(0).status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(standings.get(1).status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(standings.get(2).status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(standings.get(1).rank()).isNull();
        assertThat(standings.get(2).rank()).isNull();
    }

    @Test
    void 无团队时返回空榜() {
        List<TeamStanding> standings = TeamCalculator.compute(
                List.of(ranked("a", 100)), List.of());
        assertThat(standings).isEmpty();
    }
}
