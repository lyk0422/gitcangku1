package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成绩排名纯逻辑：依据选手原始耗时、处罚列表与检查点覆盖情况计算榜单，
 * 不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>总耗时=原始完赛耗时+全部未撤销 ADD_TIME 加时之和；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不排名，全部撤销后恢复计算；</li>
 *   <li>原始耗时缺失（null）时状态 UNTIMED，不排名；</li>
 *   <li>赛事配置了检查点时：已有完赛耗时但未覆盖全部检查点的选手状态
 *       MISSING_CHECKPOINT，不排名；全部覆盖后恢复加时与并列排名规则；
 *       未配置检查点的赛事沿用原排名规则；</li>
 *   <li>正常选手按总耗时升序，同耗时同名次，下一名次跳过并列人数（1、1、3）；</li>
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of());
    }

    /**
     * 计算即时成绩。
     *
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        List<CheckpointView> orderedCheckpoints = new ArrayList<>(checkpoints);
        orderedCheckpoints.sort(Comparator.comparingInt(CheckpointView::position)
                .thenComparing(CheckpointView::checkpointCode));
        int checkpointCount = orderedCheckpoints.size();

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RunnerView runner : runnerView) {
            aggregates.put(runner.bib(),
                    new Aggregate(runner.bib(), runner.finishTimeMs(), orderedCheckpoints));
        }

        // 仅统计属于已配置检查点的分段（数据库外键已保证，这里做防御性过滤）。
        Set<String> configuredCodes = new HashSet<>();
        for (CheckpointView checkpoint : orderedCheckpoints) {
            configuredCodes.add(checkpoint.checkpointCode());
        }
        for (TimingView timing : timings) {
            Aggregate aggregate = aggregates.get(timing.bib());
            if (aggregate == null || !configuredCodes.contains(timing.checkpointCode())) {
                continue;
            }
            aggregate.coveredCodes.add(timing.checkpointCode());
        }

        for (PenaltyView penalty : penalties) {
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

        List<Aggregate> ranked = new ArrayList<>();
        List<Aggregate> others = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            if (aggregate.disqualified) {
                aggregate.status = EntryStatus.DISQUALIFIED;
                others.add(aggregate);
            } else if (aggregate.finishTimeMs == null) {
                aggregate.status = EntryStatus.UNTIMED;
                others.add(aggregate);
            } else if (checkpointCount > 0 && aggregate.coveredCodes.size() < checkpointCount) {
                aggregate.status = EntryStatus.MISSING_CHECKPOINT;
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
            entries.add(aggregate.toEntry());
        }
        for (Aggregate aggregate : others) {
            entries.add(aggregate.toEntry());
        }
        return entries;
    }

    /** 选手视图：参赛号与原始完赛耗时（null 表示计时缺失）。 */
    public interface RunnerView {
        String bib();

        Long finishTimeMs();
    }

    /** 处罚视图。 */
    public interface PenaltyView {
        String bib();

        PenaltyType type();

        Long amountMs();

        boolean revoked();
    }

    /** 单选手聚合中间态。 */
    private static final class Aggregate {
        private final String bib;
        private final Long finishTimeMs;
        private final List<CheckpointView> checkpoints;
        private final Set<String> coveredCodes = new HashSet<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;

        private Aggregate(String bib, Long finishTimeMs, List<CheckpointView> checkpoints) {
            this.bib = bib;
            this.finishTimeMs = finishTimeMs;
            this.checkpoints = checkpoints;
        }

        private List<String> missingCheckpoints() {
            List<String> missing = new ArrayList<>();
            for (CheckpointView checkpoint : checkpoints) {
                if (!coveredCodes.contains(checkpoint.checkpointCode())) {
                    missing.add(checkpoint.checkpointCode());
                }
            }
            return missing;
        }

        private ResultEntry toEntry() {
            return new ResultEntry(
                    bib,
                    status == EntryStatus.RANKED ? rank : null,
                    status,
                    finishTimeMs,
                    penaltyMs,
                    status == EntryStatus.RANKED ? totalTimeMs : null,
                    checkpoints.size(),
                    coveredCodes.size(),
                    missingCheckpoints());
        }
    }

    /**
     * 计算绕圈赛事即时成绩。
     *
     * <p>规则：
     * <ul>
     *   <li>每名选手须覆盖 总圈数×3 个点（每圈A、B、FINISH）且全赛程耗时严格递增，
     *       才按末圈FINISH耗时+生效加时排名；</li>
     *   <li>尚无任何圈次记录的选手状态 UNTIMED；有部分记录但漏圈或逆序的状态
     *       LAP_INCOMPLETE，不排名且阻止整场封榜；</li>
     *   <li>取消资格与并列排名规则与标准赛事一致；</li>
     *   <li>条目的 checkpointCount 为总点数（总圈数×3），coveredCheckpointCount
     *       为已覆盖点数，missingCheckpoints 为缺失点标签（形如 "1:B"），按圈号与点序排列。</li>
     * </ul>
     *
     * @param runnerView 全部选手视图（绕圈赛事不使用其原始完赛耗时）
     * @param penalties  全部处罚（含已撤销）
     * @param totalLaps  赛事总圈数（2~10）
     * @param lapPasses  全部选手的当前圈次通过记录
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> computeLoop(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            int totalLaps,
            List<? extends LapPassView> lapPasses) {
        Map<String, LoopAggregate> aggregates = new LinkedHashMap<>();
        for (RunnerView runner : runnerView) {
            aggregates.put(runner.bib(), new LoopAggregate(runner.bib(), totalLaps));
        }
        for (LapPassView pass : lapPasses) {
            LoopAggregate aggregate = aggregates.get(pass.bib());
            if (aggregate != null) {
                aggregate.add(pass);
            }
        }
        for (PenaltyView penalty : penalties) {
            LoopAggregate aggregate = aggregates.get(penalty.bib());
            if (aggregate == null || penalty.revoked()) {
                continue;
            }
            if (penalty.type() == PenaltyType.DISQUALIFY) {
                aggregate.disqualified = true;
            } else if (penalty.type() == PenaltyType.ADD_TIME && penalty.amountMs() != null) {
                aggregate.penaltyMs += penalty.amountMs();
            }
        }

        List<LoopAggregate> ranked = new ArrayList<>();
        List<LoopAggregate> others = new ArrayList<>();
        for (LoopAggregate aggregate : aggregates.values()) {
            aggregate.classify();
            if (aggregate.status == EntryStatus.RANKED) {
                ranked.add(aggregate);
            } else {
                others.add(aggregate);
            }
        }
        ranked.sort(Comparator
                .comparingLong((LoopAggregate a) -> a.totalTimeMs)
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
        for (LoopAggregate aggregate : ranked) {
            entries.add(aggregate.toEntry());
        }
        for (LoopAggregate aggregate : others) {
            entries.add(aggregate.toEntry());
        }
        return entries;
    }

    /** 绕圈赛事单选手聚合中间态。 */
    private static final class LoopAggregate {
        private final String bib;
        private final int totalLaps;
        /** 全赛程序列位置 -> 累计耗时；按序列位置升序。 */
        private final Map<Integer, Long> elapsedBySequence = new java.util.TreeMap<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;
        private Long lastLapFinishMs;

        private LoopAggregate(String bib, int totalLaps) {
            this.bib = bib;
            this.totalLaps = totalLaps;
        }

        private void add(LapPassView pass) {
            elapsedBySequence.put(LapPoint.sequence(pass.lapNo(), pass.point()),
                    pass.elapsedMillis());
        }

        private int requiredPoints() {
            return totalLaps * LapPoint.POINTS_PER_LAP;
        }

        /** 全赛程序列上耗时是否严格递增（同圈A<B<FINISH，下一圈A大于上一圈FINISH）。 */
        private boolean strictlyIncreasing() {
            long previous = -1L;
            for (long elapsed : elapsedBySequence.values()) {
                if (elapsed <= previous) {
                    return false;
                }
                previous = elapsed;
            }
            return true;
        }

        private void classify() {
            if (disqualified) {
                status = EntryStatus.DISQUALIFIED;
                return;
            }
            if (elapsedBySequence.isEmpty()) {
                status = EntryStatus.UNTIMED;
                return;
            }
            if (elapsedBySequence.size() < requiredPoints() || !strictlyIncreasing()) {
                status = EntryStatus.LAP_INCOMPLETE;
                return;
            }
            status = EntryStatus.RANKED;
            lastLapFinishMs = elapsedBySequence.get(LapPoint.sequence(totalLaps, LapPoint.FINISH));
            totalTimeMs = lastLapFinishMs + penaltyMs;
        }

        private List<String> missingPoints() {
            List<String> missing = new ArrayList<>();
            for (int lap = 1; lap <= totalLaps; lap++) {
                for (LapPoint point : LapPoint.values()) {
                    if (!elapsedBySequence.containsKey(LapPoint.sequence(lap, point))) {
                        missing.add(lap + ":" + point.name());
                    }
                }
            }
            return missing;
        }

        private ResultEntry toEntry() {
            return new ResultEntry(
                    bib,
                    status == EntryStatus.RANKED ? rank : null,
                    status,
                    status == EntryStatus.RANKED ? lastLapFinishMs : null,
                    penaltyMs,
                    status == EntryStatus.RANKED ? totalTimeMs : null,
                    requiredPoints(),
                    elapsedBySequence.size(),
                    missingPoints());
        }
    }
}