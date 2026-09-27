package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成绩排名纯逻辑：依据选手原始耗时、处罚列表、检查点覆盖、退赛与医疗暂停情况计算榜单，
 * 不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>总耗时=原始完赛耗时+全部未撤销 ADD_TIME 加时之和；</li>
 *   <li>存在未撤销 DISQUALIFY 处罚时状态 DISQUALIFIED，不排名，全部撤销后恢复计算；</li>
 *   <li>已退赛选手状态 WITHDRAWN、资格 WITHDRAWN，不排名（退赛为不可变事实）；</li>
 *   <li>医疗暂停生效中（存在 endAt 为 null 的区间）状态 MEDICAL_HOLD、资格 MEDICAL_HOLD，
 *       不参与临时排名；已恢复区间不影响资格；</li>
 *   <li>暂停生效期间提交、带排除原因（MEDICAL_HOLD）的计时仍保留原始记录，
 *       但不覆盖检查点、不参与排名，恢复后仅暂停结束后的新计时参与排名；</li>
 *   <li>原始耗时缺失（null）时状态 UNTIMED，不排名；</li>
 *   <li>赛事配置了检查点时：已有完赛耗时但未覆盖全部（未被排除的）检查点的选手状态
 *       MISSING_CHECKPOINT，不排名；全部覆盖后恢复加时与并列排名规则；
 *       未配置检查点的赛事沿用原排名规则；</li>
 *   <li>正常选手按总耗时升序，同耗时同名次，下一名次跳过并列人数（1、1、3）；</li>
 *   <li>并列者及未排名者内部均按参赛号字典序展示。</li>
 * </ul>
 */
public final class ResultCalculator {

    private ResultCalculator() {
    }

    /** 未配置检查点的赛事使用的兼容入口：无检查点、无分段记录、无退赛、无暂停。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties) {
        return compute(runnerView, penalties, List.of(), List.of());
    }

    /** 无医疗暂停/退赛场景的入口。 */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings) {
        return compute(runnerView, penalties, checkpoints, timings, List.of(), List.of());
    }

    /**
     * 计算即时成绩。
     *
     * @param runnerView  全部选手视图（参赛号、原始完赛耗时）
     * @param penalties   全部处罚（含已撤销）
     * @param checkpoints 赛事检查点配置（按 position 排序后使用）；为空表示赛事未配置检查点
     * @param timings     全部选手的分段通过记录（含被医疗暂停排除的记录）
     * @param withdrawals 全部退赛记录（不可变事实）
     * @param holds       全部医疗暂停区间；endAt 为 null 表示生效中
     * @return 按展示顺序排列的成绩条目
     */
    public static List<ResultEntry> compute(
            List<? extends RunnerView> runnerView,
            List<? extends PenaltyView> penalties,
            List<? extends CheckpointView> checkpoints,
            List<? extends TimingView> timings,
            List<? extends WithdrawalView> withdrawals,
            List<? extends MedicalHoldView> holds) {
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
            // 任何原始记录都占用该检查点（缺失明细据此不产生 null 行）；
            // 被医疗暂停排除的计时不覆盖检查点、不参与排名。
            aggregate.presentCodes.add(timing.checkpointCode());
            if (timing.exclusionReason() == null) {
                aggregate.coveredCodes.add(timing.checkpointCode());
            } else {
                aggregate.excludedTimingCount++;
            }
        }

        for (WithdrawalView withdrawal : withdrawals) {
            Aggregate aggregate = aggregates.get(withdrawal.bib());
            if (aggregate != null) {
                aggregate.withdrawn = true;
            }
        }

        for (MedicalHoldView hold : holds) {
            Aggregate aggregate = aggregates.get(hold.bib());
            if (aggregate != null && hold.endAt() == null) {
                aggregate.activeHold = true;
            }
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
            } else if (aggregate.withdrawn) {
                aggregate.status = EntryStatus.WITHDRAWN;
                others.add(aggregate);
            } else if (aggregate.activeHold) {
                aggregate.status = EntryStatus.MEDICAL_HOLD;
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
        private final Set<String> presentCodes = new HashSet<>();
        private final Set<String> coveredCodes = new HashSet<>();
        private long penaltyMs;
        private boolean disqualified;
        private boolean withdrawn;
        private boolean activeHold;
        private int excludedTimingCount;
        private EntryStatus status;
        private int rank;
        private long totalTimeMs;

        private Aggregate(String bib, Long finishTimeMs, List<CheckpointView> checkpoints) {
            this.bib = bib;
            this.finishTimeMs = finishTimeMs;
            this.checkpoints = checkpoints;
        }

        private EligibilityStatus eligibility() {
            if (withdrawn) {
                return EligibilityStatus.WITHDRAWN;
            }
            if (activeHold) {
                return EligibilityStatus.MEDICAL_HOLD;
            }
            return EligibilityStatus.ELIGIBLE;
        }

        private List<String> missingCheckpoints() {
            List<String> missing = new ArrayList<>();
            for (CheckpointView checkpoint : checkpoints) {
                // 被排除计时仍在该检查点留有原始记录，因此不算“缺失”，只是不计入覆盖数。
                if (!presentCodes.contains(checkpoint.checkpointCode())) {
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
                    eligibility(),
                    excludedTimingCount);
        }
    }
}
