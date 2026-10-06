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
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 *
 * <p>COUNTBACK 模式追加规则（NORMAL 模式算法完全不变）：
 * <ul>
 *   <li>可排名前提：全部必经检查点完整覆盖且累计耗时按检查点顺序严格递增，
 *       不完整或不满足严格递增继续按漏点规则记 MISSING_CHECKPOINT；</li>
 *   <li>先按总耗时升序；相同再依次比较最后一段、倒数第二段……第一段的段耗时，
 *       较小者优先；段耗时=相邻检查点累计时刻之差（起点为0），处罚只加总成绩、不改分段；</li>
 *   <li>总耗时与全部段耗时都相同才并列并跳号，不以参赛号破实际并列，
 *       参赛号字典序仅用于并列者的展示顺序。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录、NORMAL 模式。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(RankingMode.NORMAL, runnerView, penalties, List.of(), List.of());
    }

    /** NORMAL 模式兼容入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(RankingMode.NORMAL, runnerView, penalties, checkpoints, timings);
    }

    /**
     * 计算即时成绩。
     *
     * @param mode        排名模式；COUNTBACK 时同分按倒序段耗时向量破同分
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            RankingMode mode,
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
            aggregate.elapsedByPosition.put(timing.position(), timing.elapsedMillis());
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
            } else if (mode == RankingMode.COUNTBACK
                    && !aggregate.buildSegmentVectorDesc(orderedCheckpoints)) {
                // COUNTBACK 要求累计耗时按检查点顺序严格递增；数据异常时按漏点规则不排名。
                aggregate.status = EntryStatus.MISSING_CHECKPOINT;
                others.add(aggregate);
            } else {
                aggregate.status = EntryStatus.RANKED;
                aggregate.totalTimeMs = aggregate.finishTimeMs + aggregate.penaltyMs;
                ranked.add(aggregate);
            }
        }

        if (mode == RankingMode.COUNTBACK) {
            ranked.sort(Comparator
                    .comparingLong((Aggregate a) -> a.totalTimeMs)
                    .thenComparing(ResultCalculator::compareSegmentVectorsDesc)
                    .thenComparing(a -> a.bib));
        } else {
            ranked.sort(Comparator
                    .comparingLong((Aggregate a) -> a.totalTimeMs)
                    .thenComparing(a -> a.bib));
        }
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && sameRankingKey(mode, ranked.get(groupEnd), ranked.get(index))) {
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

    /**
     * 比较两条倒序段耗时向量：从最后一段开始逐段比较，较小者优先；
     * 全部相等返回0。向量长度一致（同一赛事检查点数相同）。
     */
    private static int compareSegmentVectorsDesc(Aggregate left, Aggregate right) {
        List<Long> leftVector = left.segmentMillisDesc;
        List<Long> rightVector = right.segmentMillisDesc;
        for (int i = 0; i < leftVector.size(); i++) {
            int compared = Long.compare(leftVector.get(i), rightVector.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /** 判断两条已排名记录是否真正并列：总耗时相同，COUNTBACK 模式还要求全部段耗时相同。 */
    private static boolean sameRankingKey(RankingMode mode, Aggregate next, Aggregate current) {
        if (next.totalTimeMs != current.totalTimeMs) {
            return false;
        }
        return mode != RankingMode.COUNTBACK
                || next.segmentMillisDesc.equals(current.segmentMillisDesc);
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
        private final Map<Integer, Long> elapsedByPosition = new HashMap<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;
        private List<Long> segmentMillisDesc;

        private Aggregate(String bib, Long finishTimeMs, List<CheckpointView> checkpoints) {
            this.bib = bib;
            this.finishTimeMs = finishTimeMs;
            this.checkpoints = checkpoints;
        }

        /**
         * 由按顺序排列的检查点累计耗时构造倒序段耗时向量（从最后一段到第一段）。
         * 段耗时=相邻累计时刻之差（起点为0），用 {@link Math#subtractExact} 精确计算，
         * 必须严格为正（即累计耗时严格递增）；违反时返回 false，调用方按漏点规则处理。
         */
        private boolean buildSegmentVectorDesc(List<CheckpointView> orderedCheckpoints) {
            List<Long> segmentsAsc = new ArrayList<>(orderedCheckpoints.size());
            long previous = 0L;
            for (CheckpointView checkpoint : orderedCheckpoints) {
                Long elapsed = elapsedByPosition.get(checkpoint.position());
                if (elapsed == null) {
                    return false;
                }
                long segment;
                try {
                    segment = Math.subtractExact(elapsed, previous);
                } catch (ArithmeticException ex) {
                    return false;
                }
                if (segment <= 0L) {
                    return false;
                }
                segmentsAsc.add(segment);
                previous = elapsed;
            }
            List<Long> desc = new ArrayList<>(segmentsAsc.size());
            for (int i = segmentsAsc.size() - 1; i >= 0; i--) {
                desc.add(segmentsAsc.get(i));
            }
            this.segmentMillisDesc = desc;
            return true;
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
                    missingCheckpoints(),
                    status == EntryStatus.RANKED ? segmentMillisDesc : null);
        }
    }
}
