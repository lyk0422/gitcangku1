package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>COUNTBACK 模式下，总耗时相同的选手依次比较最后一段、倒数第二段直至第一段
 *       耗时，较小者优先；段耗时=相邻累计时刻差（起点为0，末段=完赛耗时-最后检查点
 *       累计耗时），处罚只加总成绩不改分段；全部比较值相同才并列，不以参赛号破并列；</li>
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录、TOTAL_TIME 模式。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of());
    }

    /** TOTAL_TIME 模式兼容入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(runnerView, penalties, checkpoints, timings, RankingMode.TOTAL_TIME);
    }

    /**
     * 计算即时成绩。
     *
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录
     * @param mode        排名模式；COUNTBACK 时总耗时相同用倒序段向量破同分
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings,
            RankingMode mode) {
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
        // 每个选手按检查点顺序排列的累计耗时，用于 COUNTBACK 段向量计算。
        Map<String, List<TimingView>> timingsByBib = new LinkedHashMap<>();
        for (TimingView timing : timings) {
            Aggregate aggregate = aggregates.get(timing.bib());
            if (aggregate == null || !configuredCodes.contains(timing.checkpointCode())) {
                continue;
            }
            aggregate.coveredCodes.add(timing.checkpointCode());
            timingsByBib.computeIfAbsent(timing.bib(), key -> new ArrayList<>()).add(timing);
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
                if (mode == RankingMode.COUNTBACK) {
                    aggregate.segments = computeSegments(
                            timingsByBib.getOrDefault(aggregate.bib, List.of()),
                            aggregate.finishTimeMs);
                }
                ranked.add(aggregate);
            }
        }

        ranked.sort((left, right) -> {
            int compared = Long.compare(left.totalTimeMs, right.totalTimeMs);
            if (compared != 0) {
                return compared;
            }
            if (mode == RankingMode.COUNTBACK) {
                // 从最后一段向第一段依次比较，段耗时较小者优先。
                for (int i = left.segments.length - 1; i >= 0; i--) {
                    compared = Long.compare(left.segments[i], right.segments[i]);
                    if (compared != 0) {
                        return compared;
                    }
                }
            }
            // 展示顺序兜底：实际并列（比较值全同）按参赛号字典序排列，不影响名次。
            return left.bib.compareTo(right.bib);
        });
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && sameRankKey(ranked.get(index), ranked.get(groupEnd), mode)) {
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

    /**
     * 由按检查点顺序排列的累计耗时计算分段耗时向量（正序，末段为完赛减最后检查点）。
     * 差值用 {@link Math#subtractExact} 精确计算，溢出直接抛错；提交侧不变量保证差值非负。
     */
    private static long[] computeSegments(List<TimingView> orderedTimings, long finishTimeMs) {
        List<TimingView> ordered = new ArrayList<>(orderedTimings);
        ordered.sort(Comparator.comparingInt(TimingView::position));
        long[] segments = new long[ordered.size() + 1];
        long previous = 0L;
        for (int i = 0; i < ordered.size(); i++) {
            long current = ordered.get(i).elapsedMillis();
            segments[i] = Math.subtractExact(current, previous);
            previous = current;
        }
        segments[ordered.size()] = Math.subtractExact(finishTimeMs, previous);
        return segments;
    }

    /** 名次分组键：总耗时相同；COUNTBACK 模式还要求全部分段耗时逐一相同。 */
    private static boolean sameRankKey(Aggregate left, Aggregate right, RankingMode mode) {
        if (left.totalTimeMs != right.totalTimeMs) {
            return false;
        }
        return mode != RankingMode.COUNTBACK || Arrays.equals(left.segments, right.segments);
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
        /** COUNTBACK 模式的正序分段耗时向量；其它情况为 null。 */
        private long[] segments;

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
                    missingCheckpoints(),
                    segmentMillisDesc());
        }

        /** 倒序段向量（末段在前）；无段向量时为空列表。 */
        private List<Long> segmentMillisDesc() {
            if (segments == null) {
                return List.of();
            }
            List<Long> desc = new ArrayList<>(segments.length);
            for (int i = segments.length - 1; i >= 0; i--) {
                desc.add(segments[i]);
            }
            return desc;
        }
    }
}
