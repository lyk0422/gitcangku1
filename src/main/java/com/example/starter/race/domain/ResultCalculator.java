package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 *   <li>并列者及未排名者内部均按参赛号字典序展示；</li>
 *   <li>冲线证据裁决：总耗时并列且原始完赛耗时相同的选手，若该计时组已有裁决快照，
 *       按裁决最终顺序排定互不重复的名次（1、2、3）；裁决后新增处罚只按既有规则
 *       改变总耗时，不回写裁决快照。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of(), List.of());
    }

    /** 无冲线证据裁决的兼容入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(runnerView, penalties, checkpoints, timings, List.of());
    }

    /**
     * 计算即时成绩。
     *
     * @param runnerView    全部选手视图（参赛号、原始完赛耗时）
     * @param penalties     全部处罚（含已撤销）
     * @param checkpoints   赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings       全部选手的分段通过记录
     * @param adjudications 每个计时组最新一份证据裁决快照（原始完赛耗时 → 最终名次顺序）
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings,
            List<? extends AdjudicationView> adjudications) {
        // 计时组 → 参赛号裁决名次位次；同一计时组只保留一份（调用方保证传入最新快照）。
        Map<Long, Map<String, Integer>> adjudicatedOrderByTime = new HashMap<>();
        for (AdjudicationView adjudication : adjudications) {
            Map<String, Integer> indexByBib = new HashMap<>();
            List<String> finalOrder = adjudication.finalOrder();
            for (int i = 0; i < finalOrder.size(); i++) {
                indexByBib.put(finalOrder.get(i), i);
            }
            adjudicatedOrderByTime.put(adjudication.finishTimeMs(), indexByBib);
        }
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
                .thenComparing(adjudicatedTieBreak(adjudicatedOrderByTime)));
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && ranked.get(groupEnd).totalTimeMs == ranked.get(index).totalTimeMs) {
                groupEnd++;
            }
            if (isAdjudicatedGroup(ranked, index, groupEnd, adjudicatedOrderByTime)) {
                // 已裁决计时组：名次互不重复，按裁决最终顺序排定。
                for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                    ranked.get(groupIndex).rank = groupIndex + 1;
                }
            } else {
                int rank = index + 1;
                for (int groupIndex = index; groupIndex < groupEnd; groupIndex++) {
                    ranked.get(groupIndex).rank = rank;
                }
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

    /** 证据裁决视图：一个计时组的最终名次顺序（不可变快照的最新一份）。 */
    public interface AdjudicationView {
        long finishTimeMs();

        List<String> finalOrder();
    }

    /**
     * 总耗时并列时的次排序：仅当两名选手原始完赛耗时相同且该计时组已有裁决快照时，
     * 按裁决最终名次位次比较（快照外选手排在最后，再按参赛号字典序）。
     */
    private static Comparator<Aggregate> adjudicatedTieBreak(
            Map<Long, Map<String, Integer>> adjudicatedOrderByTime) {
        return (left, right) -> {
            if (left.finishTimeMs != null && left.finishTimeMs.equals(right.finishTimeMs)) {
                Map<String, Integer> order = adjudicatedOrderByTime.get(left.finishTimeMs);
                if (order != null) {
                    int leftIndex = order.getOrDefault(left.bib, order.size());
                    int rightIndex = order.getOrDefault(right.bib, order.size());
                    if (leftIndex != rightIndex) {
                        return Integer.compare(leftIndex, rightIndex);
                    }
                }
            }
            return left.bib.compareTo(right.bib);
        };
    }

    /**
     * 判断一个总耗时并列组是否已被证据裁决覆盖：
     * 组内多于一人、原始完赛耗时全部相同且该计时组存在裁决快照。
     */
    private static boolean isAdjudicatedGroup(
            List<Aggregate> ranked,
            int from,
            int to,
            Map<Long, Map<String, Integer>> adjudicatedOrderByTime) {
        if (to - from < 2) {
            return false;
        }
        Long finishTimeMs = ranked.get(from).finishTimeMs;
        if (finishTimeMs == null || !adjudicatedOrderByTime.containsKey(finishTimeMs)) {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            if (!finishTimeMs.equals(ranked.get(i).finishTimeMs)) {
                return false;
            }
        }
        return true;
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
}
