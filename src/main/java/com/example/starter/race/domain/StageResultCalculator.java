package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阶段（STAGES）赛事成绩纯逻辑：由阶段配置、阶段分段记录与处罚列表计算
 * 各阶段最快原始用时F、阶段资格与总榜，不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>每阶段完整（覆盖该阶段全部检查点）选手的原始用时=最后一个检查点的累计耗时，
 *       阶段从零计时；缺点者该阶段 INCOMPLETE；</li>
 *   <li>每阶段 F=该阶段全部完整选手的最快原始用时（不先剔除其他阶段OUT者），
 *       关门线=11F/10；原始用时严格大于关门线者该阶段 OUT，等于允许；
 *       比较以 10*t 与 11*F 的整数运算完成，不先舍入；</li>
 *   <li>处罚仅加入总成绩，不改变 F 或关门判定；存在生效 DISQUALIFY 者 DISQUALIFIED；</li>
 *   <li>只有所有阶段完整且均未OUT者参与排名：总耗时=各阶段原始用时之和+生效加时，
 *       精确并列同名次并跳号；其余按 OUT 优先于 INCOMPLETE 归类；</li>
 *   <li>未排名者内部按参赛号字典序展示。</li>
 * </ul>
 */
public final class StageResultCalculator {

    private StageResultCalculator() {
    }

    /**
     * 计算各阶段结果。
     *
     * @param bibs        全部选手参赛号
     * @param checkpoints 全部阶段的检查点配置（任意顺序，此处按阶段与顺序归并）
     * @param timings     全部阶段分段记录
     * @return 按阶段序号升序排列的阶段结果
     */
    public static List<StageResult> computeStages(
            List<String> bibs,
            List<? extends StageCheckpointView> checkpoints,
            List<? extends StageTimingView> timings) {
        Map<Integer, List<StageCheckpointView>> checkpointsByStage = new TreeMap<>();
        for (StageCheckpointView checkpoint : checkpoints) {
            checkpointsByStage
                    .computeIfAbsent(checkpoint.stageNo(), key -> new ArrayList<>())
                    .add(checkpoint);
        }
        Map<Integer, Map<String, List<StageTimingView>>> timingsByStageAndBib = new TreeMap<>();
        for (StageTimingView timing : timings) {
            timingsByStageAndBib
                    .computeIfAbsent(timing.stageNo(), key -> new TreeMap<>())
                    .computeIfAbsent(timing.bib(), key -> new ArrayList<>())
                    .add(timing);
        }

        List<StageResult> results = new ArrayList<>();
        for (Map.Entry<Integer, List<StageCheckpointView>> stageEntry : checkpointsByStage.entrySet()) {
            int stageNo = stageEntry.getKey();
            List<StageCheckpointView> stageCheckpoints = new ArrayList<>(stageEntry.getValue());
            stageCheckpoints.sort(Comparator.comparingInt(StageCheckpointView::position));
            int checkpointCount = stageCheckpoints.size();
            int lastPosition = stageCheckpoints.getLast().position();
            Map<String, List<StageTimingView>> timingsByBib =
                    timingsByStageAndBib.getOrDefault(stageNo, Map.of());

            // 第一遍：确定完整选手与其原始用时，求该阶段最快值F。
            Map<String, Long> rawTimeByBib = new TreeMap<>();
            for (String bib : bibs) {
                List<StageTimingView> runnerTimings = timingsByBib.getOrDefault(bib, List.of());
                if (runnerTimings.size() < checkpointCount) {
                    continue;
                }
                Long rawTime = null;
                for (StageTimingView timing : runnerTimings) {
                    if (timing.position() == lastPosition) {
                        rawTime = timing.elapsedMillis();
                    }
                }
                if (rawTime != null) {
                    rawTimeByBib.put(bib, rawTime);
                }
            }
            Long fastestMs = null;
            for (long rawTime : rawTimeByBib.values()) {
                if (fastestMs == null || rawTime < fastestMs) {
                    fastestMs = rawTime;
                }
            }

            // 第二遍：按关门线 11F/10 判定资格；10*t > 11*F 为OUT，等于允许。
            List<StageEntry> entries = new ArrayList<>(bibs.size());
            for (String bib : bibs) {
                int covered = timingsByBib.getOrDefault(bib, List.of()).size();
                Long rawTime = rawTimeByBib.get(bib);
                StageStatus status;
                if (rawTime == null) {
                    status = StageStatus.INCOMPLETE;
                } else if (isBeyondCutoff(rawTime, fastestMs)) {
                    status = StageStatus.OUT;
                } else {
                    status = StageStatus.COMPLETE;
                }
                entries.add(new StageEntry(bib, stageNo, status, rawTime,
                        checkpointCount, covered));
            }
            results.add(new StageResult(stageNo, fastestMs, entries));
        }
        return results;
    }

    /**
     * 精确关门判定：原始用时严格大于 11F/10 时为 OUT。
     * 以整数运算 10*t &gt; 11*F 比较，不先舍入比例。
     */
    public static boolean isBeyondCutoff(long rawTimeMs, long fastestMs) {
        return 10L * rawTimeMs > 11L * fastestMs;
    }

    /**
     * 计算阶段赛事总榜。
     *
     * @param bibs         全部选手参赛号
     * @param penalties    全部处罚（含已撤销）
     * @param checkpoints  全部阶段的检查点配置
     * @param timings      全部阶段分段记录（用于推导缺失检查点明细）
     * @param stageResults 各阶段计算结果
     * @return 按展示顺序排列的总榜条目（RANKED/OUT/INCOMPLETE/DISQUALIFIED）
     */
    public static List<ResultEntry> computeOverall(
            List<String> bibs,
            List<? extends ResultCalculator.PenaltyView> penalties,
            List<? extends StageCheckpointView> checkpoints,
            List<? extends StageTimingView> timings,
            List<StageResult> stageResults) {
        Map<String, Long> penaltyMsByBib = new LinkedHashMap<>();
        Map<String, Boolean> disqualifiedByBib = new LinkedHashMap<>();
        for (String bib : bibs) {
            penaltyMsByBib.put(bib, 0L);
            disqualifiedByBib.put(bib, Boolean.FALSE);
        }
        for (ResultCalculator.PenaltyView penalty : penalties) {
            if (!penaltyMsByBib.containsKey(penalty.bib()) || penalty.revoked()) {
                continue;
            }
            if (penalty.type() == PenaltyType.DISQUALIFY) {
                disqualifiedByBib.put(penalty.bib(), Boolean.TRUE);
            } else if (penalty.type() == PenaltyType.ADD_TIME && penalty.amountMs() != null) {
                penaltyMsByBib.merge(penalty.bib(), penalty.amountMs(), Long::sum);
            }
        }

        int totalCheckpointCount = checkpoints.size();
        Map<String, Map<Integer, StageEntry>> entriesByBibAndStage = new LinkedHashMap<>();
        for (StageResult stageResult : stageResults) {
            for (StageEntry entry : stageResult.entries()) {
                entriesByBibAndStage
                        .computeIfAbsent(entry.bib(), key -> new TreeMap<>())
                        .put(stageResult.stageNo(), entry);
            }
        }

        List<Aggregate> ranked = new ArrayList<>();
        List<Aggregate> others = new ArrayList<>();
        for (String bib : bibs) {
            Aggregate aggregate = new Aggregate(bib);
            aggregate.penaltyMs = penaltyMsByBib.get(bib);
            Map<Integer, StageEntry> byStage =
                    entriesByBibAndStage.getOrDefault(bib, Map.of());
            boolean anyOut = false;
            boolean anyIncomplete = false;
            long rawSum = 0L;
            int coveredSum = 0;
            for (StageResult stageResult : stageResults) {
                StageEntry entry = byStage.get(stageResult.stageNo());
                if (entry == null || entry.status() == StageStatus.INCOMPLETE) {
                    anyIncomplete = true;
                    coveredSum += entry == null ? 0 : entry.coveredCheckpointCount();
                } else {
                    rawSum += entry.rawTimeMs();
                    coveredSum += entry.coveredCheckpointCount();
                    if (entry.status() == StageStatus.OUT) {
                        anyOut = true;
                    }
                }
            }
            aggregate.coveredCheckpointCount = coveredSum;
            if (disqualifiedByBib.get(bib)) {
                aggregate.status = EntryStatus.DISQUALIFIED;
            } else if (anyOut) {
                aggregate.status = EntryStatus.OUT;
                aggregate.rawSumMs = rawSum;
            } else if (anyIncomplete) {
                aggregate.status = EntryStatus.INCOMPLETE;
            } else {
                aggregate.status = EntryStatus.RANKED;
                aggregate.rawSumMs = rawSum;
                aggregate.totalTimeMs = rawSum + aggregate.penaltyMs;
            }
            aggregate.missingCheckpoints =
                    missingCheckpoints(bib, checkpoints, timings);
            aggregate.checkpointCount = totalCheckpointCount;
            if (aggregate.status == EntryStatus.RANKED) {
                ranked.add(aggregate);
            } else {
                others.add(aggregate);
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

        List<ResultEntry> entries = new ArrayList<>(bibs.size());
        for (Aggregate aggregate : ranked) {
            entries.add(aggregate.toEntry());
        }
        for (Aggregate aggregate : others) {
            entries.add(aggregate.toEntry());
        }
        return entries;
    }

    /** 缺失检查点列表：按阶段序号与阶段内顺序排列，元素格式为 "阶段序号:检查点代码"。 */
    private static List<String> missingCheckpoints(
            String bib,
            List<? extends StageCheckpointView> checkpoints,
            List<? extends StageTimingView> timings) {
        Map<Integer, java.util.Set<String>> coveredByStage = new TreeMap<>();
        for (StageTimingView timing : timings) {
            if (timing.bib().equals(bib)) {
                coveredByStage
                        .computeIfAbsent(timing.stageNo(), key -> new java.util.HashSet<>())
                        .add(timing.checkpointCode());
            }
        }
        List<StageCheckpointView> ordered = new ArrayList<>(checkpoints);
        ordered.sort(Comparator
                .comparingInt(StageCheckpointView::stageNo)
                .thenComparingInt(StageCheckpointView::position));
        List<String> missing = new ArrayList<>();
        for (StageCheckpointView checkpoint : ordered) {
            java.util.Set<String> covered =
                    coveredByStage.getOrDefault(checkpoint.stageNo(), java.util.Set.of());
            if (!covered.contains(checkpoint.checkpointCode())) {
                missing.add(checkpoint.stageNo() + ":" + checkpoint.checkpointCode());
            }
        }
        return missing;
    }

    /** 单选手聚合中间态。 */
    private static final class Aggregate {
        private final String bib;
        private long penaltyMs;
        private long rawSumMs;
        private long totalTimeMs;
        private int rank;
        private int checkpointCount;
        private int coveredCheckpointCount;
        private EntryStatus status;
        private List<String> missingCheckpoints = List.of();

        private Aggregate(String bib) {
            this.bib = bib;
        }

        private ResultEntry toEntry() {
            boolean allStagesComplete = status == EntryStatus.RANKED || status == EntryStatus.OUT;
            return new ResultEntry(
                    bib,
                    status == EntryStatus.RANKED ? rank : null,
                    status,
                    allStagesComplete ? rawSumMs : null,
                    penaltyMs,
                    status == EntryStatus.RANKED ? totalTimeMs : null,
                    checkpointCount,
                    coveredCheckpointCount,
                    missingCheckpoints);
        }
    }
}
