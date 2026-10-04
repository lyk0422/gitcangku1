package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 绕圈赛事成绩排名纯逻辑：依据圈次当前记录、处罚列表与总圈数计算榜单，
 * 不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>选手所有圈（1~总圈数）的 A/B/FINISH 点位齐备且按（圈号, 点位）全序严格递增，
 *       才按末圈 FINISH 耗时 + 全部未撤销 ADD_TIME 加时排名；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不排名；</li>
 *   <li>一条圈次记录都没有的选手状态 UNTIMED（保留原未计时语义），不排名也不阻塞封榜；</li>
 *   <li>有部分圈次记录但不完整（漏圈/漏点）或逆序的选手状态 INCOMPLETE_LAPS，不排名，
 *       且封榜时整场 422 不封榜；</li>
 *   <li>正常选手按总耗时升序，同耗时同名次并跳号（1、1、3）；并列与未排名者按参赛号字典序。</li>
 * </ul>
 */
public final class LapResultCalculator {

    private LapResultCalculator() {
    }

    /**
     * 计算绕圈赛事即时成绩。
     *
     * @param runners   全部选手视图（参赛号）
     * @param penalties 全部处罚（含已撤销）
     * @param timings   全部选手的圈次当前记录
     * @param totalLaps 赛事总圈数（2~10）
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends ResultCalculator.RunnerView> runners,
            List<? extends ResultCalculator.PenaltyView> penalties,
            List<? extends LapTimingView> timings,
            int totalLaps) {
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (ResultCalculator.RunnerView runner : runners) {
            aggregates.put(runner.bib(), new Aggregate(runner.bib()));
        }
        for (LapTimingView timing : timings) {
            Aggregate aggregate = aggregates.get(timing.bib());
            if (aggregate != null) {
                aggregate.records.add(timing);
            }
        }
        for (ResultCalculator.PenaltyView penalty : penalties) {
            Aggregate aggregate = aggregates.get(penalty.bib());
            if (aggregate == null || penalty.revoked()) {
                continue;
            }
            if (penalty.type() == PenaltyType.DISQUALIFY) {
                aggregate.disqualified = true;
            } else if (penalty.type() == PenaltyType.ADD_TIME && penalty.amountMs() != null) {
                aggregate.penaltyMs += penalty.amountMs();
            }
        }

        int totalPoints = totalLaps * LapPoint.values().length;
        List<Aggregate> ranked = new ArrayList<>();
        List<Aggregate> others = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            aggregate.finishTimeMs = lastLapFinish(aggregate.records, totalLaps);
            if (aggregate.disqualified) {
                aggregate.status = EntryStatus.DISQUALIFIED;
                others.add(aggregate);
            } else if (aggregate.records.isEmpty()) {
                aggregate.status = EntryStatus.UNTIMED;
                others.add(aggregate);
            } else if (!isComplete(aggregate.records, totalLaps)
                    || LapRules.hasInversion(aggregate.records)) {
                aggregate.status = EntryStatus.INCOMPLETE_LAPS;
                others.add(aggregate);
            } else {
                aggregate.status = EntryStatus.RANKED;
                aggregate.totalTimeMs = aggregate.finishTimeMs + aggregate.penaltyMs;
                ranked.add(aggregate);
            }
        }

        ranked.sort(Comparator
                .comparingLong((Aggregate a) -> a.totalTimeMs)
                .thenComparing(a -> a.bib));
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && ranked.get(groupEnd).totalTimeMs == ranked.get(index).totalTimeMs) {
                groupEnd++;
            }
            int rank = index + 1;
            for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                ranked.get(groupIndex).rank = rank;
            }
            index = groupEnd;
        }

        List<ResultEntry> entries = new ArrayList<>(aggregates.size());
        for (Aggregate aggregate : ranked) {
            entries.add(aggregate.toEntry(totalLaps, totalPoints));
        }
        for (Aggregate aggregate : others) {
            entries.add(aggregate.toEntry(totalLaps, totalPoints));
        }
        return entries;
    }

    /**
     * 封榜前核验：找出有圈次记录但不完整或逆序的选手。
     *
     * @return 阻塞封榜的选手参赛号，按字典序排列；为空表示可以封榜
     */
    public static List<String> sealBlockers(
            List<? extends ResultCalculator.RunnerView> runners,
            List<? extends LapTimingView> timings,
            int totalLaps) {
        Map<String, List<LapTimingView>> byBib = new TreeMap<>();
        for (LapTimingView timing : timings) {
            byBib.computeIfAbsent(timing.bib(), key -> new ArrayList<>()).add(timing);
        }
        List<String> blockers = new ArrayList<>();
        for (ResultCalculator.RunnerView runner : runners) {
            List<LapTimingView> records = byBib.get(runner.bib());
            if (records == null || records.isEmpty()) {
                continue;
            }
            if (!isComplete(records, totalLaps) || LapRules.hasInversion(records)) {
                blockers.add(runner.bib());
            }
        }
        return blockers;
    }

    /** 选手圈次是否完整：1~总圈数每圈 A/B/FINISH 三个点位都有当前记录。 */
    public static boolean isComplete(List<? extends LapTimingView> records, int totalLaps) {
        boolean[][] covered = new boolean[totalLaps + 1][LapPoint.values().length + 1];
        int count = 0;
        for (LapTimingView record : records) {
            if (record.lapNumber() >= 1 && record.lapNumber() <= totalLaps) {
                covered[record.lapNumber()][record.point().sequence()] = true;
                count++;
            }
        }
        if (count != totalLaps * LapPoint.values().length) {
            return false;
        }
        for (int lap = 1; lap <= totalLaps; lap++) {
            for (LapPoint point : LapPoint.values()) {
                if (!covered[lap][point.sequence()]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 末圈 FINISH 的累计耗时；记录不完整时为 null。 */
    private static Long lastLapFinish(List<LapTimingView> records, int totalLaps) {
        for (LapTimingView record : records) {
            if (record.lapNumber() == totalLaps && record.point() == LapPoint.FINISH) {
                return record.elapsedMillis();
            }
        }
        return null;
    }

    /** 缺失点位标签，按（圈号, 点位顺序）排列，格式如 L1-B。 */
    private static List<String> missingLabels(List<LapTimingView> records, int totalLaps) {
        boolean[][] covered = new boolean[totalLaps + 1][LapPoint.values().length + 1];
        for (LapTimingView record : records) {
            if (record.lapNumber() >= 1 && record.lapNumber() <= totalLaps) {
                covered[record.lapNumber()][record.point().sequence()] = true;
            }
        }
        List<String> missing = new ArrayList<>();
        for (int lap = 1; lap <= totalLaps; lap++) {
            for (LapPoint point : LapPoint.values()) {
                if (!covered[lap][point.sequence()]) {
                    missing.add("L" + lap + "-" + point.name());
                }
            }
        }
        return missing;
    }

    /** 单选手聚合中间态。 */
    private static final class Aggregate {
        private final String bib;
        private final List<LapTimingView> records = new ArrayList<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private Long finishTimeMs;
        private long totalTimeMs;

        private Aggregate(String bib) {
            this.bib = bib;
        }

        private ResultEntry toEntry(int totalLaps, int totalPoints) {
            boolean ranked = status == EntryStatus.RANKED;
            return new ResultEntry(
                    bib,
                    ranked ? rank : null,
                    status,
                    finishTimeMs,
                    penaltyMs,
                    ranked ? totalTimeMs : null,
                    totalPoints,
                    records.size(),
                    missingLabels(records, totalLaps));
        }
    }
}