package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队成绩纯逻辑：由个人成绩条目与团队成员配置计算团队榜，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>团队从本队个人状态 RANKED 的选手中，按总耗时升序、参赛号字典序取前3人，
 *       合计整数毫秒为团队成绩；不足3人状态 INCOMPLETE，名次与总耗时为 null；</li>
 *   <li>COMPLETE 团队按总耗时升序排名，完全同分并列同名次并跳号（1、1、3），
 *       展示时并列再按 teamCode 字典序；</li>
 *   <li>INCOMPLETE 团队置于末尾，按 teamCode 字典序展示；</li>
 *   <li>计分依据实时派生自个人成绩，不另存可漂移的积分累计。</li>
 * </ul>
 */
public final class TeamCalculator {

    /** 团队计分入选人数。 */
    public static final int SCORING_SIZE = 3;

    private TeamCalculator() {
    }

    /** 团队定义视图：团队代码与成员参赛号。 */
    public interface TeamView {
        String teamCode();

        List<String> memberBibs();
    }

    /**
     * 计算团队成绩榜。
     *
     * @param entries 个人成绩条目（{@link ResultCalculator} 的输出，含状态与总耗时）
     * @param teams   团队定义（团队代码 + 成员参赛号）
     * @return 按展示顺序排列的团队成绩条目
     */
    public static List<TeamStanding> compute(
            List<ResultEntry> entries,
            List<? extends TeamView> teams) {
        Map<String, ResultEntry> rankedByBib = new HashMap<>();
        for (ResultEntry entry : entries) {
            if (entry.status() == EntryStatus.RANKED) {
                rankedByBib.put(entry.bib(), entry);
            }
        }

        List<TeamStanding> complete = new ArrayList<>();
        List<TeamStanding> incomplete = new ArrayList<>();
        for (TeamView team : teams) {
            List<ResultEntry> rankedMembers = new ArrayList<>();
            for (String bib : team.memberBibs()) {
                ResultEntry entry = rankedByBib.get(bib);
                if (entry != null) {
                    rankedMembers.add(entry);
                }
            }
            rankedMembers.sort(Comparator
                    .comparingLong(ResultEntry::totalTimeMs)
                    .thenComparing(ResultEntry::bib));

            List<ResultEntry> scoring = rankedMembers.stream()
                    .limit(SCORING_SIZE)
                    .toList();
            boolean isComplete = scoring.size() == SCORING_SIZE;
            // 仅 COMPLETE 团队存在入选成员；INCOMPLETE 团队不产生团队成绩，无人入选。
            List<String> scoringBibs = isComplete
                    ? scoring.stream().map(ResultEntry::bib).toList()
                    : List.of();

            List<String> orderedBibs = new ArrayList<>(team.memberBibs());
            orderedBibs.sort(String::compareTo);
            List<TeamMemberResult> members = orderedBibs.stream()
                    .map(bib -> {
                        int index = scoringBibs.indexOf(bib);
                        if (index < 0) {
                            return new TeamMemberResult(bib, false, null);
                        }
                        return new TeamMemberResult(bib, true, scoring.get(index).totalTimeMs());
                    })
                    .toList();

            if (scoring.size() < SCORING_SIZE) {
                incomplete.add(new TeamStanding(
                        team.teamCode(), null, TeamStatus.INCOMPLETE, null, members));
            } else {
                long total = scoring.stream().mapToLong(ResultEntry::totalTimeMs).sum();
                complete.add(new TeamStanding(
                        team.teamCode(), null, TeamStatus.COMPLETE, total, members));
            }
        }

        complete.sort(Comparator
                .comparingLong(TeamStanding::totalTimeMs)
                .thenComparing(TeamStanding::teamCode));
        incomplete.sort(Comparator.comparing(TeamStanding::teamCode));

        // 竞赛排名：完全同分同名次并跳号（1、1、3）；展示顺序已按 teamCode 稳定。
        List<TeamStanding> result = new ArrayList<>(complete.size() + incomplete.size());
        int index = 0;
        while (index < complete.size()) {
            int groupEnd = index + 1;
            while (groupEnd < complete.size()
                    && complete.get(groupEnd).totalTimeMs()
                            .equals(complete.get(index).totalTimeMs())) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                TeamStanding standing = complete.get(groupIndex);
                result.add(new TeamStanding(
                        standing.teamCode(), rank, standing.status(),
                        standing.totalTimeMs(), standing.members()));
            }
            index = groupEnd;
        }
        result.addAll(incomplete);
        return result;
    }
}
