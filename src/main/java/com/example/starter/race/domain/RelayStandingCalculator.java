package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接力排名纯逻辑：依据登记队伍、完赛记录与各队犯规次数计算即时/封榜名次，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>完赛队伍按总用时（末棒 elapsedMillis）升序排名，同用时同名次并跳号（1、1、3）；</li>
 *   <li>犯规次数达到 {@value #DISQUALIFY_FOUL_COUNT} 次的队伍状态 DISQUALIFIED，不排名，
 *       未达次数的犯规队伍正常排名，仅以 foulCount 单独标注；</li>
 *   <li>未生成完赛记录且犯规未达次数的队伍状态 UNTIMED，不排名；</li>
 *   <li>未排名队伍内部按队伍标识字典序展示。</li>
 * </ul>
 */
public final class RelayStandingCalculator {

    /** 触发取消资格的犯规次数阈值。 */
    public static final int DISQUALIFY_FOUL_COUNT = 2;

    private RelayStandingCalculator() {
    }

    /**
     * 计算接力排名。
     *
     * @param teamKeys   全部登记队伍标识
     * @param finishes   已生成的完赛记录视图（队伍 -> 总用时）
     * @param foulCounts 各队累计犯规次数（未完赛队伍也计入）
     * @return 按展示顺序排列的排名条目
     */
    public static List<RelayStanding> compute(
            List<String> teamKeys,
            List<? extends FinishView> finishes,
            Map<String, Integer> foulCounts) {
        Map<String, FinishView> finishByTeam = new LinkedHashMap<>();
        for (FinishView finish : finishes) {
            finishByTeam.put(finish.teamKey(), finish);
        }

        List<RelayStanding> ranked = new ArrayList<>();
        List<RelayStanding> others = new ArrayList<>();
        for (String teamKey : teamKeys) {
            int foulCount = foulCounts.getOrDefault(teamKey, 0);
            FinishView finish = finishByTeam.get(teamKey);
            if (foulCount >= DISQUALIFY_FOUL_COUNT) {
                others.add(new RelayStanding(
                        teamKey, null, EntryStatus.DISQUALIFIED, null, foulCount));
            } else if (finish == null) {
                others.add(new RelayStanding(
                        teamKey, null, EntryStatus.UNTIMED, null, foulCount));
            } else {
                ranked.add(new RelayStanding(
                        teamKey, null, EntryStatus.RANKED, finish.totalMs(), foulCount));
            }
        }

        ranked.sort(Comparator
                .comparingLong(RelayStanding::totalMs)
                .thenComparing(RelayStanding::teamKey));
        others.sort(Comparator.comparing(RelayStanding::teamKey));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && ranked.get(groupEnd).totalMs().equals(ranked.get(index).totalMs())) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                RelayStanding entry = ranked.get(groupIndex);
                ranked.set(groupIndex, new RelayStanding(
                        entry.teamKey(), rank, entry.status(), entry.totalMs(), entry.foulCount()));
            }
            index = groupEnd;
        }

        List<RelayStanding> entries = new ArrayList<>(ranked.size() + others.size());
        entries.addAll(ranked);
        entries.addAll(others);
        return entries;
    }

    /** 队伍完赛记录视图：总用时（末棒 elapsedMillis）。 */
    public interface FinishView {
        String teamKey();

        long totalMs();
    }
}
