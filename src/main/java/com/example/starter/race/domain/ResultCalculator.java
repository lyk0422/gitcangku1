package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成绩排名纯逻辑：依据选手原始耗时、处罚列表、检查点覆盖情况与退赛状态计算榜单，
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
 *   <li>退赛状态（DNS 未出发、DNF 中途退赛）覆盖一切排名判定：
 *       不参与排名、不占名次，并单独成组列在榜单末尾（组内按参赛号字典序）；</li>
 *   <li>正常选手按总耗时升序，同耗时同名次，下一名次跳过并列人数（1、1、3），
 *       名次只在剩余选手上从1连续编号；</li>
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
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时、生效退赛状态）
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
                    new Aggregate(runner, orderedCheckpoints));
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
        List<Aggregate> withdrawals = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            EntryStatus withdrawalStatus = aggregate.runner.withdrawalStatus();
            if (withdrawalStatus == EntryStatus.DNS
                    || withdrawalStatus == EntryStatus.DNF) {
                // 退赛判定优先：即使存在处罚或计时残留，也不参与排名，单独成组。
                aggregate.status = withdrawalStatus;
                withdrawals.add(aggregate);
            } else if (aggregate.disqualified) {
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
        // 退赛选手单独列出：组内按参赛号字典序稳定展示，不占名次。
        withdrawals.sort(Comparator.comparing(a -> a.bib));

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
        for (Aggregate aggregate : withdrawals) {
            entries.add(aggregate.toEntry());
        }
        return entries;
    }

    /** 选手视图：参赛号、原始完赛耗时与生效退赛状态（null 表示未退赛）。 */
    public interface RunnerView {
        String bib();

        Long finishTimeMs();

        /** 生效中的退赛状态（DNS/DNF）；未退赛或退赛已撤销时返回 null。 */
        default EntryStatus withdrawalStatus() {
            return null;
        }

        /** 退赛登记时固化的最后通过检查点代码；非 DNF 退赛返回 null。 */
        default String lastCheckpointCode() {
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
        private final RunnerView runner;
        private final String bib;
        private final Long finishTimeMs;
        private final List<CheckpointView> checkpoints;
        private final Set<String> coveredCodes = new HashSet<>();
        private long penaltyMs;
        private boolean disqualified;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;

        private Aggregate(RunnerView runner, List<CheckpointView> checkpoints) {
            this.runner = runner;
            this.bib = runner.bib();
            this.finishTimeMs = runner.finishTimeMs();
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
            boolean withdrawn = status == EntryStatus.DNS || status == EntryStatus.DNF;
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
                    withdrawn ? runner.lastCheckpointCode() : null);
        }
    }
}
