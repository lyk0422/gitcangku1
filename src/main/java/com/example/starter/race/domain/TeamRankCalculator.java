package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 团队积分与名次纯逻辑，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>成员名次积分按锁定时刻固化的全场名次计算：第1名10分，第2名9分，……第10名1分，
 *       第11名及以后0分；非 RANKED（UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED，名次数为 null）0分；</li>
 *   <li>团队积分为名单版本内“当前仍在队（未退赛）”成员固化名次积分之和；</li>
 *   <li>团队按积分降序排名，同积分同名次并跳号（1、1、3），并列者按团队ID字典序展示。</li>
 * </ul>
 */
public final class TeamRankCalculator {

    private TeamRankCalculator() {
    }

    /** 按固化名次计算成员名次积分；非 RANKED（rank 为 null）得 0 分。 */
    public static int pointsForRank(Integer rank) {
        if (rank == null) {
            return 0;
        }
        return Math.max(0, 11 - rank);
    }

    /**
     * 计算团队榜。
     *
     * @param teams 各团队的积分汇总（引用版本、积分、计分人数）
     * @return 按展示顺序排列的团队榜
     */
    public static List<TeamStanding> rank(List<TeamScore> teams) {
        List<TeamScore> ordered = new ArrayList<>(teams);
        ordered.sort(Comparator
                .comparingInt(TeamScore::teamPoints).reversed()
                .thenComparing(TeamScore::teamId));
        List<TeamStanding> standings = new ArrayList<>(ordered.size());
        int index = 0;
        while (index < ordered.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ordered.size()
                    && ordered.get(groupEnd).teamPoints() == ordered.get(index).teamPoints()) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                TeamScore team = ordered.get(groupIndex);
                standings.add(new TeamStanding(
                        team.teamId(), team.rosterVersionNo(),
                        team.teamPoints(), team.memberCount(), rank));
            }
            index = groupEnd;
        }
        return standings;
    }

    /**
     * 单个团队的积分汇总输入。
     *
     * @param teamId          团队ID
     * @param rosterVersionNo 引用的完整名单版本号
     * @param teamPoints      团队积分（剔除退赛成员后）
     * @param memberCount     计分成员人数（剔除退赛成员后）
     */
    public record TeamScore(
            String teamId,
            int rosterVersionNo,
            int teamPoints,
            int memberCount
    ) {
    }
}
