package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 团队得分纯逻辑：以锁定名单与个人成绩条目计算团队得分与名次，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>团队得分=锁定名单全部成员的总耗时（原始完赛耗时+生效加时）之和；</li>
 *   <li>任一成员非 RANKED（计时缺失/漏点/取消资格）时队伍不完整，得分与名次为 null；</li>
 *   <li>完整队伍按得分升序排名，并列同名次并跳号（1、1、3）；</li>
 *   <li>展示顺序：完整队伍按名次在前，不完整队伍按队伍ID字典序在后。</li>
 * </ul>
 */
public final class TeamScoreCalculator {

    private TeamScoreCalculator() {
    }

    /** 已锁定队伍视图：队伍ID、名单版本与锁定名单成员。 */
    public record LockedTeam(String teamId, int rosterVersion, List<String> members) {
    }

    /**
     * 计算团队成绩榜。
     *
     * @param lockedTeams 全部生效锁定的队伍（含锁定名单）
     * @param entries     同一赛事版本下的个人成绩条目
     * @return 按展示顺序排列的团队成绩
     */
    public static List<TeamStanding> compute(
            List<LockedTeam> lockedTeams, List<ResultEntry> entries) {
        Map<String, ResultEntry> entryByBib = entries.stream()
                .collect(Collectors.toMap(ResultEntry::bib, Function.identity()));

        List<TeamStanding> completeTeams = new ArrayList<>();
        List<TeamStanding> incompleteTeams = new ArrayList<>();
        for (LockedTeam team : lockedTeams) {
            List<String> members = team.members().stream().sorted().toList();
            boolean complete = true;
            long score = 0L;
            for (String bib : members) {
                ResultEntry entry = entryByBib.get(bib);
                if (entry == null || entry.status() != EntryStatus.RANKED
                        || entry.totalTimeMs() == null) {
                    complete = false;
                    break;
                }
                score += entry.totalTimeMs();
            }
            TeamStanding standing = new TeamStanding(
                    team.teamId(), team.rosterVersion(), members,
                    complete, complete ? score : null, null);
            (complete ? completeTeams : incompleteTeams).add(standing);
        }

        completeTeams.sort(Comparator
                .comparingLong(TeamStanding::teamScoreMs)
                .thenComparing(TeamStanding::teamId));
        incompleteTeams.sort(Comparator.comparing(TeamStanding::teamId));

        int index = 0;
        List<TeamStanding> ranked = new ArrayList<>(completeTeams.size());
        while (index < completeTeams.size()) {
            int groupEnd = index + 1;
            while (groupEnd < completeTeams.size()
                    && completeTeams.get(groupEnd).teamScoreMs()
                            .equals(completeTeams.get(index).teamScoreMs())) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                TeamStanding standing = completeTeams.get(groupIndex);
                ranked.add(new TeamStanding(
                        standing.teamId(), standing.rosterVersion(), standing.members(),
                        true, standing.teamScoreMs(), rank));
            }
            index = groupEnd;
        }

        List<TeamStanding> result = new ArrayList<>(ranked.size() + incompleteTeams.size());
        result.addAll(ranked);
        result.addAll(incompleteTeams);
        return result;
    }
}
