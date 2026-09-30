package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamRankCalculator} 纯逻辑单测：名次积分、团队积分汇总、并列跳号与稳定排序。
 */
class TeamRankCalculatorTest {

    @Test
    void 名次积分按名次递减且第十名后为零() {
        assertThat(TeamRankCalculator.pointsForRank(1)).isEqualTo(10);
        assertThat(TeamRankCalculator.pointsForRank(2)).isEqualTo(9);
        assertThat(TeamRankCalculator.pointsForRank(10)).isEqualTo(1);
        assertThat(TeamRankCalculator.pointsForRank(11)).isZero();
        assertThat(TeamRankCalculator.pointsForRank(99)).isZero();
        // 非 RANKED（名次数缺失）得 0 分
        assertThat(TeamRankCalculator.pointsForRank(null)).isZero();
    }

    @Test
    void 团队按积分降序排名() {
        List<TeamStanding> standings = TeamRankCalculator.rank(List.of(
                new TeamRankCalculator.TeamScore("B", 1, 8, 1),
                new TeamRankCalculator.TeamScore("A", 2, 19, 2)));

        assertThat(standings).extracting(TeamStanding::teamId).containsExactly("A", "B");
        assertThat(standings).extracting(TeamStanding::rankNo).containsExactly(1, 2);
        assertThat(standings).extracting(TeamStanding::teamPoints).containsExactly(19, 8);
        assertThat(standings).extracting(TeamStanding::rosterVersionNo).containsExactly(2, 1);
    }

    @Test
    void 积分并列同名次并跳号并列者按团队标识字典序() {
        List<TeamStanding> standings = TeamRankCalculator.rank(List.of(
                new TeamRankCalculator.TeamScore("C", 1, 10, 1),
                new TeamRankCalculator.TeamScore("B", 1, 10, 1),
                new TeamRankCalculator.TeamScore("A", 1, 5, 1)));

        assertThat(standings).extracting(TeamStanding::teamId).containsExactly("B", "C", "A");
        assertThat(standings).extracting(TeamStanding::rankNo).containsExactly(1, 1, 3);
    }

    @Test
    void 空团队列表排名为空() {
        assertThat(TeamRankCalculator.rank(List.of())).isEmpty();
    }
}
