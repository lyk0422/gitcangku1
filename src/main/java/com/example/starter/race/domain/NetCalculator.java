package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 中止恢复净计时纯逻辑：原始计时永不改写，仅依据已恢复（不重叠）中止事件
 * 逐选手推导逐点补偿、净分段、净完赛，并校验净时间不变量。不涉及数据库与事务。
 *
 * <p>补偿规则（对每个已恢复事件，中止时长 D=resume-start）：
 * <ul>
 *   <li>以中止开始前该选手最后一个已到检查点判断是否受影响；</li>
 *   <li>已通过事件指定起始检查点（存在 position &gt;= 事件检查点 position 的记录）者补偿0；</li>
 *   <li>其他已起跑者（至少有一条分段记录）：对其 resume 之后（原始 elapsed &gt;= resume）
 *       的所有检查点补偿 D，完赛净时间同样补偿 D；落在 [start,resume) 的记录由提交阶段拒绝；</li>
 *   <li>尚无任何分段记录（未起跑）者补偿0。</li>
 * </ul>
 *
 * <p>多次不重叠中止顺序生效：后一次基于前次的净结果，但本类始终以原始时间与事件列表推导，
 * 各事件区间互不重叠，与“逐次累计”数学等价，且不依赖任何历史净值落库。
 *
 * <p>净不变量（任一违反抛出 {@link NetTimeInvalidException}，恢复事务整体回滚不落库）：
 * 净检查点严格递增、净检查点非负、净检查点严格小于净完赛（若有完赛净时间）。
 */
public final class NetCalculator {

    private NetCalculator() {
    }

    /**
     * 计算单个选手的净计时结果。
     *
     * @param finishTimeMs 该选手原始完赛耗时；null 表示计时缺失
     * @param timings      该选手已存在的全部分段记录（任意顺序，方法内按 position 排序）
     * @param suspensions  赛事全部已恢复中止事件（调用方须保证不重叠；未恢复事件不计入）
     * @return 净分段（按 position 升序，含逐点补偿）、完赛补偿与净完赛
     */
    public static NetRunnerResult computeRunner(
            Long finishTimeMs,
            List<? extends TimingView> timings,
            List<? extends SuspensionView> suspensions) {
        List<TimingView> ordered = new ArrayList<>(timings);
        ordered.sort(Comparator.comparingInt(TimingView::position));

        boolean started = !ordered.isEmpty();
        List<NetCheckpoint> netCheckpoints = new ArrayList<>(ordered.size());
        for (TimingView timing : ordered) {
            long compensationMs = compensationForRecord(timing, ordered, suspensions);
            netCheckpoints.add(new NetCheckpoint(
                    timing.checkpointCode(), timing.position(),
                    timing.elapsedMillis(), timing.elapsedMillis() - compensationMs,
                    compensationMs, timing.timingId()));
        }

        // 完赛补偿：已起跑（有分段）、中止开始前未通过事件起始检查点，且完赛发生在恢复之后
        // （原始完赛>=resume）时补偿事件时长；中止开始前已完赛者不在此列，避免净完赛被误扣为负。
        long finishCompensationMs = 0L;
        for (SuspensionView suspension : suspensions) {
            if (started
                    && finishTimeMs != null
                    && !passedBeforeSuspension(ordered, suspension)
                    && finishTimeMs >= suspension.resumeElapsedMs()) {
                finishCompensationMs += suspension.durationMs();
            }
        }
        Long netFinishTimeMs = finishTimeMs == null ? null : finishTimeMs - finishCompensationMs;

        validate(netCheckpoints, netFinishTimeMs);
        return new NetRunnerResult(netCheckpoints, finishCompensationMs, netFinishTimeMs);
    }

    /**
     * 某条分段记录在全部已恢复事件下的补偿时长：
     * 该选手中止开始前未通过事件起始检查点，且记录位于恢复时刻之后（elapsed &gt;= resume）时
     * 补偿事件时长。
     */
    private static long compensationForRecord(
            TimingView timing,
            List<TimingView> ordered,
            List<? extends SuspensionView> suspensions) {
        long compensation = 0L;
        for (SuspensionView suspension : suspensions) {
            if (!passedBeforeSuspension(ordered, suspension)
                    && timing.elapsedMillis() >= suspension.resumeElapsedMs()) {
                compensation += suspension.durationMs();
            }
        }
        return compensation;
    }

    /**
     * 以中止开始前最后一个已到检查点判断：是否存在 position 不小于事件起始检查点 position、
     * 且原始耗时严格小于中止开始时刻的已到记录（落在 [start,resume) 的记录已在提交阶段拒绝）。
     */
    private static boolean passedBeforeSuspension(
            List<TimingView> ordered, SuspensionView suspension) {
        for (TimingView timing : ordered) {
            if (timing.position() >= suspension.checkpointPosition()
                    && timing.elapsedMillis() < suspension.startElapsedMs()) {
                return true;
            }
        }
        return false;
    }

    /** 校验净分段严格递增、非负，且严格小于净完赛（若有）；净完赛本身也不得为负。 */
    private static void validate(List<NetCheckpoint> netCheckpoints, Long netFinishTimeMs) {
        if (netFinishTimeMs != null && netFinishTimeMs < 0L) {
            throw new NetTimeInvalidException("净完赛耗时为负");
        }
        long previous = Long.MIN_VALUE;
        for (NetCheckpoint checkpoint : netCheckpoints) {
            long net = checkpoint.netElapsedMs();
            if (net < 0L) {
                throw new NetTimeInvalidException(
                        "净分段耗时为负: checkpoint=" + checkpoint.checkpointCode());
            }
            if (net <= previous) {
                throw new NetTimeInvalidException(
                        "净分段耗时未严格递增: checkpoint=" + checkpoint.checkpointCode());
            }
            if (netFinishTimeMs != null && net >= netFinishTimeMs) {
                throw new NetTimeInvalidException(
                        "净分段耗时不小于净完赛耗时: checkpoint=" + checkpoint.checkpointCode());
            }
            previous = net;
        }
    }

    /** 单个选手的净计时结果。 */
    public record NetRunnerResult(
            List<NetCheckpoint> checkpoints,
            long finishCompensationMs,
            Long netFinishTimeMs
    ) {
    }

    /** 单个检查点的净值与补偿。 */
    public record NetCheckpoint(
            String checkpointCode,
            int position,
            long rawElapsedMs,
            long netElapsedMs,
            long compensationMs,
            String timingId
    ) {
    }
}
