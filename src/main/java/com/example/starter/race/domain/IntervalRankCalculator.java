package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 区间计时模式的名次范围纯逻辑：只输出可证明的结论，不虚构确定顺序。
 *
 * <p>规则：
 * <ul>
 *   <li>净区间=原始完赛区间整体平移全部未撤销 ADD_TIME 加时之和；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不参与；</li>
 *   <li>无完赛区间时状态 UNTIMED；有完赛区间但未覆盖全部检查点时状态
 *       MISSING_CHECKPOINT，均不参与名次范围；</li>
 *   <li>A 肯定早于 B 当且仅当 A 净区间上界严格小于 B 净区间下界（端点相等不算）；</li>
 *   <li>完整选手保证最好名次=1+肯定早于自己的人数，
 *       可能最差名次=完整人数-自己肯定早于的人数；</li>
 *   <li>区间重叠者不视为精确并列，也不按参赛号推断成绩顺序；
 *       展示顺序按参赛号字典序，仅影响列表排列，不影响名次范围。</li>
 * </ul>
 */
public final class IntervalRankCalculator {

    private IntervalRankCalculator() {
    }

    /**
     * 计算区间模式即时成绩。
     *
     * @param runners     全部选手视图（仅使用参赛号）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置；为空表示未配置检查点
     * @param intervals   全部选手的区间记录（含完赛区间）
     * @return 按展示顺序（参赛号字典序）排列的成绩条目
     */
    public static List<IntervalResultEntry> compute(
            List<? extends ResultCalculator.RunnerView> runners,
            List<? extends ResultCalculator.PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends IntervalView> intervals) {
        List<CheckpointView> orderedCheckpoints = new ArrayList<>(checkpoints);
        orderedCheckpoints.sort(Comparator.comparingInt(CheckpointView::position)
                .thenComparing(CheckpointView::checkpointCode));
        int checkpointCount = orderedCheckpoints.size();

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (ResultCalculator.RunnerView runner : runners) {
            aggregates.put(runner.bib(), new Aggregate(runner.bib(), orderedCheckpoints));
        }

        Set<String> configuredCodes = new HashSet<>();
        for (CheckpointView checkpoint : orderedCheckpoints) {
            configuredCodes.add(checkpoint.checkpointCode());
        }
        for (IntervalView interval : intervals) {
            Aggregate aggregate = aggregates.get(interval.bib());
            if (aggregate == null) {
                continue;
            }
            if (IntervalRules.FINISH_CODE.equals(interval.checkpointCode())) {
                aggregate.finishLowerMs = interval.lowerMs();
                aggregate.finishUpperMs = interval.upperMs();
            } else if (configuredCodes.contains(interval.checkpointCode())) {
                aggregate.coveredCodes.add(interval.checkpointCode());
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

        List<Aggregate> complete = new ArrayList<>();
        List<Aggregate> others = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            if (aggregate.disqualified) {
                aggregate.status = EntryStatus.DISQUALIFIED;
                others.add(aggregate);
            } else if (aggregate.finishLowerMs == null) {
                aggregate.status = EntryStatus.UNTIMED;
                others.add(aggregate);
            } else if (checkpointCount > 0 && aggregate.coveredCodes.size() < checkpointCount) {
                aggregate.status = EntryStatus.MISSING_CHECKPOINT;
                others.add(aggregate);
            } else {
                aggregate.status = EntryStatus.RANKED;
                aggregate.netLowerMs = aggregate.finishLowerMs + aggregate.penaltyMs;
                aggregate.netUpperMs = aggregate.finishUpperMs + aggregate.penaltyMs;
                complete.add(aggregate);
            }
        }

        int total = complete.size();
        for (Aggregate self : complete) {
            int earlierThanSelf = 0;
            int selfEarlierThan = 0;
            for (Aggregate other : complete) {
                if (other == self) {
                    continue;
                }
                if (other.netUpperMs < self.netLowerMs) {
                    earlierThanSelf++;
                }
                if (self.netUpperMs < other.netLowerMs) {
                    selfEarlierThan++;
                }
            }
            self.rankBest = earlierThanSelf + 1;
            self.rankWorst = total - selfEarlierThan;
        }

        List<Aggregate> ordered = new ArrayList<>(aggregates.size());
        complete.sort(Comparator.comparing(a -> a.bib));
        others.sort(Comparator.comparing(a -> a.bib));
        ordered.addAll(complete);
        ordered.addAll(others);
        List<IntervalResultEntry> entries = new ArrayList<>(ordered.size());
        for (Aggregate aggregate : ordered) {
            entries.add(aggregate.toEntry());
        }
        return entries;
    }

    /** 单选手聚合中间态。 */
    private static final class Aggregate {
        private final String bib;
        private final List<CheckpointView> checkpoints;
        private final Set<String> coveredCodes = new HashSet<>();
        private Long finishLowerMs;
        private Long finishUpperMs;
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private Long netLowerMs;
        private Long netUpperMs;
        private int rankBest;
        private int rankWorst;

        private Aggregate(String bib, List<CheckpointView> checkpoints) {
            this.bib = bib;
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

        private IntervalResultEntry toEntry() {
            boolean ranked = status == EntryStatus.RANKED;
            return new IntervalResultEntry(
                    bib,
                    status,
                    ranked ? rankBest : null,
                    ranked ? rankWorst : null,
                    finishLowerMs,
                    finishUpperMs,
                    penaltyMs,
                    ranked ? netLowerMs : null,
                    ranked ? netUpperMs : null,
                    checkpoints.size(),
                    coveredCodes.size(),
                    missingCheckpoints());
        }
    }
}
