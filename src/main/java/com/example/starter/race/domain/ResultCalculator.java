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
 * 成绩排名纯逻辑：依据选手原始耗时、处罚列表、检查点覆盖情况与分批起跑波次计算榜单，
 * 不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>枪声总耗时=原始完赛耗时+全部未撤销 ADD_TIME 加时之和；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不排名，全部撤销后恢复计算；</li>
 *   <li>原始耗时缺失（null）时状态 UNTIMED，不排名；</li>
 *   <li>赛事配置了检查点时：已有完赛耗时但未覆盖全部检查点的选手状态
 *       MISSING_CHECKPOINT，不排名；全部覆盖后恢复加时与并列排名规则；
 *       未配置检查点的赛事沿用原排名规则；</li>
 *   <li>波次参赛者净计时=枪声总耗时-(波次起跑时刻-赛事基准起跑时刻)（毫秒差）；
 *       无波次参赛者净计时=枪声总耗时；净计时为负时状态 INVALID_WAVE，不参与排名，
 *       原始枪声计时与波次归属保留；</li>
 *   <li>正常选手按净计时升序，同净计时同名次，下一名次跳过并列人数（1、1、3）；
 *       并列者按参赛号字典序稳定裁决；</li>
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点、无波次的赛事使用的兼容入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of());
    }

    /**
     * 无波次的即时成绩入口：净计时沿用枪声总耗时。
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
        return compute(runnerView, penalties, checkpoints, timings, List.of(), null);
    }

    /**
     * 计算即时成绩（含分批起跑净计时校正）。
     *
     * @param runnerView      全部选手视图（参赛号、原始完赛耗时）
     * @param penalties       全部处罚（含已撤销）
     * @param checkpoints     赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings         全部选手的分段通过记录
     * @param waveAssignments 全部波次归属（参赛号唯一）；无波次参赛者不在其中
     * @param baseStartMs     赛事基准（枪声）起跑时刻（Unix 毫秒时间戳）；无基准为 null
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings,
            List<? extends WaveAssignmentView> waveAssignments,
            Long baseStartMs) {
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

        // 固化每名参赛者的波次归属；同一参赛号至多一条，重复归属由服务层与数据库约束拦截。
        Map<String, WaveAssignmentView> waveByBib = new HashMap<>();
        for (WaveAssignmentView assignment : waveAssignments) {
            waveByBib.put(assignment.bib(), assignment);
        }

        List<Aggregate> ranked = new ArrayList<>();
        List<Aggregate> others = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            WaveAssignmentView wave = waveByBib.get(aggregate.bib);
            if (wave != null) {
                aggregate.waveKey = wave.waveKey();
                aggregate.waveStartMs = wave.waveStartMs();
                aggregate.baseStartMs = baseStartMs;
            }
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
                aggregate.totalTimeMs = aggregate.finishTimeMs + aggregate.penaltyMs;
                long netTimeMs = aggregate.totalTimeMs - aggregate.waveOffsetMs();
                if (netTimeMs < 0) {
                    // 净计时为负：不参与排名，原始枪声计时与波次归属原样保留。
                    aggregate.status = EntryStatus.INVALID_WAVE;
                    aggregate.invalidReason = EntryStatus.INVALID_WAVE.name();
                    others.add(aggregate);
                } else {
                    aggregate.status = EntryStatus.RANKED;
                    aggregate.netTimeMs = netTimeMs;
                    ranked.add(aggregate);
                }
            }
        }

        // 排行榜按净计时排序；同净计时沿用原有“参赛号字典序”的稳定裁决口径。
        ranked.sort(Comparator
                .comparingLong((Aggregate a) -> a.netTimeMs)
                .thenComparing(a -> a.bib));
        others.sort(Comparator.comparing(a -> a.bib));

        int index = 0;
        while (index < ranked.size()) {
            int groupEnd = index + 1;
            while (groupEnd < ranked.size()
                    && ranked.get(groupEnd).netTimeMs == ranked.get(index).netTimeMs) {
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
        private long netTimeMs;
        private String waveKey;
        private Long waveStartMs;
        private Long baseStartMs;
        private String invalidReason;

        private Aggregate(String bib, Long finishTimeMs, List<CheckpointView> checkpoints) {
            this.bib = bib;
            this.finishTimeMs = finishTimeMs;
            this.checkpoints = checkpoints;
        }

        /** 波次起跑相对赛事基准起跑的毫秒差；无波次或基准缺失时为 0（净计时沿用枪声计时）。 */
        private long waveOffsetMs() {
            if (waveStartMs == null || baseStartMs == null) {
                return 0L;
            }
            return waveStartMs - baseStartMs;
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
            Long rankedTotal = ranked ? totalTimeMs : null;
            return new ResultEntry(
                    bib,
                    ranked ? rank : null,
                    status,
                    finishTimeMs,
                    penaltyMs,
                    rankedTotal,
                    waveKey,
                    waveStartMs,
                    baseStartMs,
                    ranked ? netTimeMs : null,
                    ranked ? null : invalidReason,
                    checkpoints.size(),
                    coveredCodes.size(),
                    missingCheckpoints());
        }
    }
}
