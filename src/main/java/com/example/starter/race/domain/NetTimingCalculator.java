package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中止恢复事件的净计时纯逻辑：基于原始分段与原始完赛耗时，按事件顺序重算
 * 每名选手的净分段、净完赛与补偿明细；原始计时永不改写，本类不涉及数据库。
 *
 * <p>规则：
 * <ul>
 *   <li>每名选手按“中止开始前最后一个已到检查点”（以此前事件重算后的净值判断）：
 *       位置不早于受影响起始检查点者补偿0（PASSED_CHECKPOINT）；
 *       无任何中止前记录者补偿0（NOT_STARTED）；
 *       其余已起跑者补偿中止时长（AFFECTED），其恢复后的全部分段与完赛净值扣除中止时长；</li>
 *   <li>落在 [start,resume) 窗口内的受影响记录为非法，恢复时整体拒绝（422）；</li>
 *   <li>多个不重叠事件按 startElapsedMs 升序依次作用，后一次基于前一次净结果；</li>
 *   <li>重算后任一净分段不再严格递增、净值为负或不小于净完赛，均为非法。</li>
 * </ul>
 */
public final class NetTimingCalculator {

    private NetTimingCalculator() {
    }

    /**
     * 单个已恢复中止事件视图。
     *
     * @param eventKey           中止事件Key
     * @param checkpointPosition 受影响起始检查点顺序
     * @param startElapsedMs     中止开始累计耗时（毫秒）
     * @param resumeElapsedMs    恢复累计耗时（毫秒），必须大于 startElapsedMs
     */
    public record SuspensionEvent(
            String eventKey,
            int checkpointPosition,
            long startElapsedMs,
            long resumeElapsedMs) {
    }

    /**
     * 补偿依据：PASSED_CHECKPOINT-中止前已通过指定检查点；
     * AFFECTED-已起跑且未通过指定检查点；NOT_STARTED-中止前未起跑。
     */
    public enum CompensationBasis {
        PASSED_CHECKPOINT,
        AFFECTED,
        NOT_STARTED
    }

    /**
     * 单选手单事件补偿明细。
     *
     * @param eventKey       中止事件Key
     * @param compensationMs 补偿毫秒数（受影响者为中止时长，其余为0）
     * @param basis          补偿依据
     */
    public record Compensation(String eventKey, long compensationMs, CompensationBasis basis) {
    }

    /**
     * 单选手净计时结果。
     *
     * @param netFinishTimeMs        净完赛耗时（毫秒）；原始完赛缺失为 null
     * @param netElapsedByCheckpoint 检查点代码到净分段耗时的映射（仅含已有记录的检查点）
     * @param compensations          每个事件一条补偿明细，顺序与事件一致
     */
    public record RunnerNet(
            Long netFinishTimeMs,
            Map<String, Long> netElapsedByCheckpoint,
            List<Compensation> compensations) {
    }

    /**
     * 净计时计算结果。
     *
     * @param net       无违规时的净计时结果；有违规时为 null
     * @param violation 违反净计时不变量时的错误信息；合法时为 null
     */
    public record NetOutcome(RunnerNet net, String violation) {

        /** 是否存在违规。 */
        public boolean hasViolation() {
            return violation != null;
        }
    }

    /**
     * 重算单选手净计时。
     *
     * @param timings      该选手全部原始分段记录（任意顺序）
     * @param finishTimeMs 原始完赛耗时；null 表示计时缺失
     * @param events       全部已恢复中止事件，按 startElapsedMs 升序
     * @return 净计时结果或违规信息
     */
    public static NetOutcome computeRunnerNet(
            List<? extends TimingView> timings,
            Long finishTimeMs,
            List<SuspensionEvent> events) {
        Map<String, Long> netByCode = new HashMap<>();
        Map<String, Integer> positionByCode = new HashMap<>();
        for (TimingView timing : timings) {
            netByCode.put(timing.checkpointCode(), timing.elapsedMillis());
            positionByCode.put(timing.checkpointCode(), timing.position());
        }
        Long netFinish = finishTimeMs;
        List<Compensation> compensations = new ArrayList<>(events.size());

        for (SuspensionEvent event : events) {
            long duration = event.resumeElapsedMs() - event.startElapsedMs();
            // 以当前净值（已应用此前事件）找中止开始前最后一个已到检查点
            int lastPosition = -1;
            for (TimingView timing : timings) {
                Long net = netByCode.get(timing.checkpointCode());
                if (net != null && net < event.startElapsedMs()
                        && timing.position() > lastPosition) {
                    lastPosition = timing.position();
                }
            }
            if (lastPosition >= event.checkpointPosition()) {
                compensations.add(new Compensation(
                        event.eventKey(), 0L, CompensationBasis.PASSED_CHECKPOINT));
                continue;
            }
            if (lastPosition < 0) {
                compensations.add(new Compensation(
                        event.eventKey(), 0L, CompensationBasis.NOT_STARTED));
                continue;
            }
            compensations.add(new Compensation(
                    event.eventKey(), duration, CompensationBasis.AFFECTED));
            for (Map.Entry<String, Long> entry : netByCode.entrySet()) {
                long net = entry.getValue();
                if (net >= event.resumeElapsedMs()) {
                    entry.setValue(net - duration);
                } else if (net >= event.startElapsedMs()) {
                    return new NetOutcome(null, "分段记录落在中止窗口内: checkpoint="
                            + entry.getKey() + ", elapsed=" + net);
                }
            }
            if (netFinish != null) {
                if (netFinish >= event.resumeElapsedMs()) {
                    netFinish -= duration;
                } else if (netFinish >= event.startElapsedMs()) {
                    return new NetOutcome(null, "完赛记录落在中止窗口内: finish=" + netFinish);
                }
            }
        }

        String violation = validateNetInvariants(timings, netByCode, positionByCode, netFinish);
        if (violation != null) {
            return new NetOutcome(null, violation);
        }
        return new NetOutcome(
                new RunnerNet(netFinish, Map.copyOf(netByCode), List.copyOf(compensations)),
                null);
    }

    /**
     * 校验新提交记录是否落在任一已恢复事件的 [start,resume) 窗口内。
     *
     * @param elapsedMillis 新记录的累计耗时（分段或完赛）
     * @param events        全部已恢复中止事件
     * @return 违规时的错误信息；合法时为 null
     */
    public static String windowViolation(long elapsedMillis, List<SuspensionEvent> events) {
        for (SuspensionEvent event : events) {
            if (elapsedMillis >= event.startElapsedMs()
                    && elapsedMillis < event.resumeElapsedMs()) {
                return "记录落在中止窗口 [" + event.startElapsedMs() + ","
                        + event.resumeElapsedMs() + ") 内，拒绝写入";
            }
        }
        return null;
    }

    /** 净计时不变量：净分段按检查点顺序严格递增、净值非负、净分段严格小于净完赛。 */
    private static String validateNetInvariants(
            List<? extends TimingView> timings,
            Map<String, Long> netByCode,
            Map<String, Integer> positionByCode,
            Long netFinish) {
        List<TimingView> ordered = new ArrayList<>(timings);
        ordered.sort(java.util.Comparator.comparingInt(TimingView::position));
        Long previous = null;
        for (TimingView timing : ordered) {
            long net = netByCode.get(timing.checkpointCode());
            if (net < 0L) {
                return "净分段耗时为负: checkpoint=" + timing.checkpointCode();
            }
            if (previous != null && net <= previous) {
                return "净分段耗时不再严格递增: position=" + positionByCode.get(timing.checkpointCode());
            }
            if (netFinish != null && net >= netFinish) {
                return "净分段耗时不小于净完赛耗时: checkpoint=" + timing.checkpointCode();
            }
            previous = net;
        }
        if (netFinish != null && netFinish < 0L) {
            return "净完赛耗时为负";
        }
        return null;
    }
}
