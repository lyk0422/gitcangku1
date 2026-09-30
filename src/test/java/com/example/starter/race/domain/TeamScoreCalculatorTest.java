package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamScoreCalculator} 纯逻辑测试：固化成绩汇总、退赛场次边界与排序。
 */
class TeamScoreCalculatorTest {

    private record RosterMember(
            String bib, EntryStatus status, Long finish, long penalty, Long total)
            implements TeamScoreCalculator.RosterMemberView {
        @Override
        public Long finishTimeMs() {
            return finish;
        }

        @Override
        public long penaltyMs() {
            return penalty;
        }

        @Override
        public Long totalTimeMs() {
            return total;
        }
    }

    private record Roster(String teamId, int rosterVersion, List<RosterMember> members)
            implements TeamScoreCalculator.TeamRosterView {
    }

    private record Withdrawal(String bib, int effectiveSession)
            implements TeamScoreCalculator.WithdrawalView {
    }

    private static RosterMember ranked(String bib, long total) {
        return new RosterMember(bib, EntryStatus.RANKED, total, 0L, total);
    }

    private static RosterMember untimed(String bib) {
        return new RosterMember(bib, EntryStatus.UNTIMED, null, 0L, null);
    }

    private static RosterMember disqualified(String bib) {
        return new RosterMember(bib, EntryStatus.DISQUALIFIED, 100L, 0L, null);
    }

    @Test
    void 仅汇总名单版本固化成绩且只统计排名成员() {
        Roster teamA = new Roster("A", 2, List.of(
                ranked("jia", 1000L), ranked("yi", 2000L), untimed("bing")));
        Roster teamB = new Roster("B", 1, List.of(
                ranked("ding", 1500L), disqualified("wu")));

        List<TeamScoreCalculator.TeamScore> scores =
                TeamScoreCalculator.compute(List.of(teamA, teamB), Map.of(), 1);

        // A=1000+2000=3000，未计时成员计分0人；B=1500，取消资格成员不计分。
        assertThat(scores).extracting(TeamScoreCalculator.TeamScore::teamId)
                .containsExactly("B", "A");
        assertThat(scores.get(0).totalTimeMs()).isEqualTo(1500L);
        assertThat(scores.get(0).scoringCount()).isEqualTo(1);
        assertThat(scores.get(0).memberCount()).isEqualTo(2);
        assertThat(scores.get(1).totalTimeMs()).isEqualTo(3000L);
        assertThat(scores.get(1).scoringCount()).isEqualTo(2);
        assertThat(scores.get(1).memberCount()).isEqualTo(3);
    }

    @Test
    void 同分团队按团队ID字典序成员按参赛号字典序() {
        Roster teamB = new Roster("B", 1, List.of(ranked("b2", 2000L), ranked("b1", 1000L)));
        Roster teamA = new Roster("A", 1, List.of(ranked("a2", 2000L), ranked("a1", 1000L)));

        List<TeamScoreCalculator.TeamScore> scores =
                TeamScoreCalculator.compute(List.of(teamB, teamA), Map.of(), 1);

        assertThat(scores).extracting(TeamScoreCalculator.TeamScore::teamId)
                .containsExactly("A", "B");
        assertThat(scores.get(0).members()).extracting(TeamScoreCalculator.MemberScore::bib)
                .containsExactly("a1", "a2");
    }

    @Test
    void 退赛生效场次不晚于当前场次时不计分但保留在明细中() {
        Roster team = new Roster("A", 1, List.of(
                ranked("jia", 1000L), ranked("yi", 2000L), ranked("bing", 3000L)));
        Map<String, TeamScoreCalculator.WithdrawalView> withdrawals =
                TeamScoreCalculator.indexByBib(List.of(new Withdrawal("jia", 3)));

        // 当前场次2：退赛3场起生效，尚不计入；团队总分仍为6000。
        List<TeamScoreCalculator.TeamScore> before =
                TeamScoreCalculator.compute(List.of(team), withdrawals, 2);
        assertThat(before.getFirst().totalTimeMs()).isEqualTo(6000L);
        assertThat(before.getFirst().scoringCount()).isEqualTo(3);
        // 成员按参赛号字典序排列：bing、jia、yi，按参赛号取甲校验“已登记但未生效”。
        TeamScoreCalculator.MemberScore jiaBefore = before.getFirst().members().stream()
                .filter(member -> member.bib().equals("jia")).findFirst().orElseThrow();
        assertThat(jiaBefore.scoring()).isTrue();
        assertThat(jiaBefore.withdrawn()).isTrue();
        assertThat(jiaBefore.effectiveSession()).isEqualTo(3);

        // 当前场次3：退赛生效，甲不计分；团队总分变为5000（后续未封榜计分受影响）。
        List<TeamScoreCalculator.TeamScore> effective =
                TeamScoreCalculator.compute(List.of(team), withdrawals, 3);
        assertThat(effective.getFirst().totalTimeMs()).isEqualTo(5000L);
        assertThat(effective.getFirst().scoringCount()).isEqualTo(2);
        assertThat(effective.getFirst().members())
                .anySatisfy(member -> {
                    assertThat(member.bib()).isEqualTo("jia");
                    assertThat(member.scoring()).isFalse();
                    assertThat(member.withdrawn()).isTrue();
                });
    }

    @Test
    void 固化成绩不随后续变化传入即汇总值不依赖外部数据() {
        // 成员锁定时刻成绩为1000：即使其真实当前成绩已被修订，计算器无从得知也不应读取。
        Roster team = new Roster("A", 1, List.of(ranked("jia", 1000L)));
        List<TeamScoreCalculator.TeamScore> scores =
                TeamScoreCalculator.compute(List.of(team), Map.of(), 1);
        assertThat(scores.getFirst().totalTimeMs()).isEqualTo(1000L);
        assertThat(scores.getFirst().rosterVersion()).isEqualTo(1);
    }
}
