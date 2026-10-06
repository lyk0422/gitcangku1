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
 *   <li>标准赛：总耗时=原始完赛耗时+全部未撤销 ADD_TIME 加时之和；</li>
 *   <li>让分赛（HANDICAP）：有效成绩为精确分数 (原始毫秒×p+生效加时×q)/q，
 *       排名按精确分数比较（交叉相乘），精确相同才并列；展示值 HALF_UP 到整数毫秒，
 *       展示相同不代表并列；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不排名，全部撤销后恢复计算；</li>
 *   <li>原始耗时缺失（null）时状态 UNTIMED，不排名；</li>
 *   <li>赛事配置了检查点时：已有完赛耗时但未覆盖全部检查点的选手状态
 *       MISSING_CHECKPOINT，不排名；全部覆盖后恢复加时与并列排名规则；
 *       未配置检查点的赛事沿用原排名规则；</li>
 *   <li>让分赛中已有完赛计时且覆盖全部检查点、但未登记让分系数的选手状态
 *       MISSING_FACTOR，不排名；取消资格与漏点判定优先于缺系数；</li>
 *   <li>正常选手按有效成绩升序，相同成绩同名次，下一名次跳过并列人数（1、1、3）；</li>
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的标准赛事使用的兼容入口：无检查点、无分段记录。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(ScoringMode.STANDARD, runnerView, penalties, List.of(), List.of());
    }

    /** 标准计分赛事使用的兼容入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(ScoringMode.STANDARD, runnerView, penalties, checkpoints, timings);
    }

    /**
     * 计算即时成绩。
     *
     * @param scoringMode 计分模式：STANDARD 标准计时；HANDICAP 让分精确分数
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时、让分系数）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            ScoringMode scoringMode,
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
                    new Aggregate(runner.bib(), runner.finishTimeMs(),
                            runner.factorP(), runner.factorQ(), orderedCheckpoints));
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
            } else if (handicap && (aggregate.factorP == null || aggregate.factorQ == null)) {
                aggregate.status = EntryStatus.MISSING_FACTOR;
                others.add(aggregate);
            } else {
                aggregate.status = EntryStatus.RANKED;
                if (handicap) {
                    long[] score = HandicapScore.score(
                            aggregate.finishTimeMs, aggregate.penaltyMs,
                            aggregate.factorP, aggregate.factorQ);
                    aggregate.scoreNumerator = score[0];
                    aggregate.scoreDenominator = score[1];
                    aggregate.totalTimeMs =
                            HandicapScore.displayMs(score[0], score[1]);
                } else {
                    aggregate.totalTimeMs = aggregate.finishTimeMs + aggregate.penaltyMs;
                }
                ranked.add(aggregate);
            }
        }

        ranked.sort((left, right) -> {
            int byScore = handicap
                    ? HandicapScore.compare(left.scoreNumerator, left.scoreDenominator,
                            right.scoreNumerator, right.scoreDenominator)
                    : Long.compare(left.totalTimeMs, right.totalTimeMs);
            return byScore != 0 ? byScore : left.bib.compareTo(right.bib);
        });
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && sameScore(ranked.get(groupEnd), ranked.get(index), handicap)) {
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
            entries.add(aggregate.toEntry(handicap));
        }
        for (Aggregate aggregate : others) {
            entries.add(aggregate.toEntry(handicap));
        }
        return entries;
    }

    /** 并列判定：让分赛按精确分数相等，标准赛按总耗时相等。 */
    private static boolean sameScore(Aggregate left, Aggregate right, boolean handicap) {
        if (handicap) {
            return HandicapScore.compare(left.scoreNumerator, left.scoreDenominator,
                    right.scoreNumerator, right.scoreDenominator) == 0;
        }
        return left.totalTimeMs == right.totalTimeMs;
    }

    /** 选手视图：参赛号、原始完赛耗时（null 表示计时缺失）与让分系数。 */
    public interface RunnerView {
        String bib();

        Long finishTimeMs();

        /** 让分系数分子 p（1~1000）；未登记或非让分赛为 null。 */
        default Integer factorP() {
            return null;
        }

        /** 让分系数分母 q（1~1000）；未登记或非让分赛为 null。 */
        default Integer factorQ() {
            return null;
        }
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
        private final Integer factorP;
        private final Integer factorQ;
        private final List<CheckpointView> checkpoints;
        private final Set<String> coveredCodes = new HashSet<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;
        private long scoreNumerator;
        private long scoreDenominator;

        private Aggregate(
                String bib,
                Long finishTimeMs,
                Integer factorP,
                Integer factorQ,
                List<CheckpointView> checkpoints) {
            this.bib = bib;
            this.finishTimeMs = finishTimeMs;
            this.factorP = factorP;
            this.factorQ = factorQ;
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

        private ResultEntry toEntry(boolean handicap) {
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
                    handicap ? factorP : null,
                    handicap ? factorQ : null,
                    handicap && ranked ? scoreNumerator : null,
                    handicap && ranked ? scoreDenominator : null);
        }
    }
}
