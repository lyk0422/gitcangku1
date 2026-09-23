package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队成绩排名纯逻辑：在已计算好的个人成绩条目与团队配置之上派生团队榜，
 * 不另存任何可漂移的积分累计。
 *
 * <p>规则：
 * <ul>
 *   <li>每个团队从其个人状态为 RANKED 的成员中，按含有效处罚的总耗时升序、
 *       参赛号字典序取前3人，三人总耗时的整数毫秒合计为团队成绩；</li>
 *   <li>RANKED 成员不足3人时团队为 INCOMPLETE，名次与总耗时为 null；</li>
 *   <li>COMPLETE 团队按总耗时升序排名，完全同分采用竞赛排名（1、1、3），
 *       同分展示再按 teamCode 排序；</li>
 *   <li>INCOMPLETE 团队放在末尾，按 teamCode 排序。</li>
 * </ul>
 */
public final class TeamCalculator {

    /** 计入团队成绩所需的最少 RANKED 成员数。 */
    public static final int SCORED_MEMBERS = 3;

    private TeamCalculator() {
    }

    /**
     * 计算团队榜。
     *
     * @param teams   全部团队配置（团队代码 -> 成员参赛号集合）
     * @param entries 已按个人规则计算好的全部成绩条目
     * @return 按展示顺序排列的团队成绩
     */
    public static List<TeamResult> compute(
            List<TeamView> teams,
            List<? extends ResultEntryLike> entries) {
        Map<String, ResultEntryLike> entryByBib = new LinkedHashMap<>();
        for (ResultEntryLike entry : entries) {
            entryByBib.put(entry.bib(), entry);
        }

        List<TeamResult> complete = new ArrayList<>();
        List<TeamResult> incomplete = new ArrayList<>();
        for (TeamView team : teams) {
            List<String> orderedBibs = new ArrayList<>(team.bibs());
            orderedBibs.sort(Comparator.naturalOrder());

            // RANKED 成员按总耗时升序、参赛号字典序取前3。
            List<String> rankedBibs = new ArrayList<>();
            for (String bib : orderedBibs) {
                ResultEntryLike entry = entryByBib.get(bib);
                if (entry != null && entry.status() == EntryStatus.RANKED
                        && entry.totalTimeMs() != null) {
                    rankedBibs.add(bib);
                }
            }
            rankedBibs.sort(Comparator
                    .comparing((String bib) -> entryByBib.get(bib).totalTimeMs())
                    .thenComparing(bib -> bib));
            List<String> scoredBibs =
                    rankedBibs.size() >= SCORED_MEMBERS
                            ? rankedBibs.subList(0, SCORED_MEMBERS)
                            : List.of();

            List<TeamMemberScore> members = new ArrayList<>(orderedBibs.size());
            for (String bib : orderedBibs) {
                ResultEntryLike entry = entryByBib.get(bib);
                members.add(new TeamMemberScore(
                        bib,
                        entry == null ? null : entry.rank(),
                        entry == null ? null : entry.status(),
                        entry == null ? null : entry.totalTimeMs(),
                        scoredBibs.contains(bib)));
            }

            if (rankedBibs.size() < SCORED_MEMBERS) {
                incomplete.add(new TeamResult(
                        team.teamCode(), null, TeamStatus.INCOMPLETE, null, members));
            } else {
                long total = 0L;
                for (String bib : scoredBibs) {
                    total += entryByBib.get(bib).totalTimeMs();
                }
                complete.add(new TeamResult(
                        team.teamCode(), null, TeamStatus.COMPLETE, total, members));
            }
        }

        // 总耗时升序；同分按 teamCode 排序，再赋竞赛名次（1、1、3）。
        complete.sort(Comparator
                .comparingLong(TeamResult::totalTimeMs)
                .thenComparing(TeamResult::teamCode));
        List<TeamResult> rankedTeams = new ArrayList<>(complete.size());
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
                TeamResult team = complete.get(groupIndex);
                rankedTeams.add(new TeamResult(
                        team.teamCode(), rank, team.status(),
                        team.totalTimeMs(), team.members()));
            }
            index = groupEnd;
        }

        incomplete.sort(Comparator.comparing(TeamResult::teamCode));
        List<TeamResult> results = new ArrayList<>(rankedTeams.size() + incomplete.size());
        results.addAll(rankedTeams);
        results.addAll(incomplete);
        return results;
    }

    /** 团队计算所需的个人成绩条目只读视图。 */
    public interface ResultEntryLike {
        String bib();

        Integer rank();

        EntryStatus status();

        Long totalTimeMs();
    }
}
