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
 *
 * <p>HANDICAP 让分模式附加规则：
 * <ul>
 *   <li>有效成绩=原始毫秒×p/q（精确分数）+未撤销加时，排名比较精确分数，不使用浮点，
 *       也不得以 HALF_UP 舍入后的展示值排序；精确相同才并列并跳号；</li>
 *   <li>已计时且覆盖全部检查点但缺少系数的选手状态 MISSING_FACTOR，不排名；
 *       取消资格、计时缺失与漏点判定优先于缺系数判定；</li>
 *   <li>STANDARD 赛事计算完全不变，系数被忽略。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录、STANDARD 模式。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of());
    }

    /** STANDARD 模式入口：无让分系数。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(runnerView, penalties, checkpoints, timings,
                ScoringMode.STANDARD, List.of());
    }

    /**
     * 计算即时成绩。
     *
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录
     * @param scoringMode 计分模式；HANDICAP 时按精确分数排名
     * @param factors     让分系数（仅 HANDICAP 模式使用；同一参赛号至多一条）
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings,
            ScoringMode scoringMode,
            List<? extends FactorView> factors) {
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

        boolean handicap = scoringMode == ScoringMode.HANDICAP;
        if (handicap) {
            for (FactorView factor : factors) {
                Aggregate aggregate = aggregates.get(factor.bib());
                if (aggregate != null) {
                    aggregate.factorP = factor.p();
                    aggregate.factorQ = factor.q();
                }
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
            } else if (handicap && aggregate.factorP == null) {
                // 取消资格、计时缺失与漏点优先沿原规则判定，此后才检查让分系数。
                aggregate.status = EntryStatus.MISSING_FACTOR;
                others.add(aggregate);
            } else {
                aggregate.status = EntryStatus.RANKED;
                aggregate.totalTimeMs = aggregate.finishTimeMs + aggregate.penaltyMs;
                if (handicap) {
                    aggregate.score = HandicapScore.of(
                            aggregate.finishTimeMs, aggregate.penaltyMs,
                            aggregate.factorP, aggregate.factorQ);
                }
                ranked.add(aggregate);
            }
        }

        if (handicap) {
            ranked.sort(Comparator
                    .comparing((Aggregate a) -> a.score)
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
            if (handicap) {
                // 仅精确分数相同才并列；展示值舍入相同不构成并列。
                while (groupEnd < ranked.size()
                        && ranked.get(groupEnd).score.compareTo(ranked.get(index).score) == 0) {
                    groupEnd++;
                }
            } else {
                while (groupEnd < ranked.size()
                        && ranked.get(groupEnd).totalTimeMs == ranked.get(index).totalTimeMs) {
                    groupEnd++;
                }
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

    /** 让分系数视图：p/q 均为 1~1000 的正整数。 */
    public interface FactorView {
        String bib();

        int p();

        int q();
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
        private Integer factorP;
        private Integer factorQ;
        private HandicapScore score;

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
            boolean ranked = status == EntryStatus.RANKED;
            return new ResultEntry(
                    bib,
                    ranked ? rank : null,
                    status,
                    finishTimeMs,
                    penaltyMs,
                    ranked ? totalTimeMs : null,
                    checkpoints.size(),
                    coveredCodes.size(),
                    missingCheckpoints(),
                    factorP,
                    factorQ,
                    ranked && score != null ? score.num().longValueExact() : null,
                    ranked && score != null ? score.den().longValueExact() : null,
                    ranked && score != null ? score.displayMs() : null);
        }
    }
}
