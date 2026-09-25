package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 接力即时排名纯逻辑：依据各队完赛聚合结果排序并赋名次，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>仅 RANKED（已完赛且犯规少于2次）队伍按总用时升序参与排名，同用时同名次并跳号（1、1、3）；</li>
 *   <li>DISQUALIFIED（累计2次犯规）与 RACING（未完赛）队伍不排名，依次排在榜单后部；</li>
 *   <li>同档内部按队伍标识字典序；犯规只标注（foul=true），不足2次不取消资格。</li>
 * </ul>
 */
public final class RelayStandingCalculator {

    private RelayStandingCalculator() {
    }

    /**
     * 计算接力排名。
     *
     * @param teams 全部已登记队伍的完赛聚合视图
     * @return 按展示顺序排列、已赋名次与展示序号的排名条目
     */
    public static List<RelayRankEntry> compute(List<RelayTeamAggregate> teams) {
        List<RelayTeamAggregate> ranked = new ArrayList<>();
        List<RelayTeamAggregate> disqualified = new ArrayList<>();
        List<RelayTeamAggregate> racing = new ArrayList<>();
        for (RelayTeamAggregate team : teams) {
            switch (team.status()) {
                case RANKED -> ranked.add(team);
                case DISQUALIFIED -> disqualified.add(team);
                case RACING -> racing.add(team);
            }
        }

        ranked.sort(Comparator
                .comparingLong((RelayTeamAggregate t) -> t.totalElapsedMillis() == null
                        ? Long.MAX_VALUE : t.totalElapsedMillis())
                .thenComparing(RelayTeamAggregate::teamKey));
        disqualified.sort(Comparator.comparing(RelayTeamAggregate::teamKey));
        racing.sort(Comparator.comparing(RelayTeamAggregate::teamKey));

        List<RelayRankEntry> result = new ArrayList<>(teams.size());
        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            long current = ranked.get(index).totalElapsedMillis();
            while (groupEnd < ranked.size()
                    && ranked.get(groupEnd).totalElapsedMillis() == current) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                result.add(toEntry(ranked.get(groupIndex), rank, result.size()));
            }
            index = groupEnd;
        }
        for (RelayTeamAggregate team : disqualified) {
            result.add(toEntry(team, null, result.size()));
        }
        for (RelayTeamAggregate team : racing) {
            result.add(toEntry(team, null, result.size()));
        }
        return result;
    }

    private static RelayRankEntry toEntry(RelayTeamAggregate team, Integer rank, int displayOrder) {
        return new RelayRankEntry(
                team.teamKey(),
                rank,
                team.status(),
                team.totalFouls() > 0,
                team.totalFouls(),
                team.totalElapsedMillis(),
                team.finishedAt(),
                displayOrder);
    }

    /** 队伍完赛聚合输入视图。 */
    public record RelayTeamAggregate(
            String teamKey,
            RelayTeamStatus status,
            int totalFouls,
            Long totalElapsedMillis,
            Long finishedAt) {
    }

    /** 排名计算输出条目。 */
    public record RelayRankEntry(
            String teamKey,
            Integer rank,
            RelayTeamStatus status,
            boolean foul,
            int totalFouls,
            Long totalElapsedMillis,
            Long finishedAt,
            int displayOrder) {
    }
}
