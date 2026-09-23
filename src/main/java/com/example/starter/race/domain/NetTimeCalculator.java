package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 中止恢复后的净计时重算纯逻辑：依据原始分段、原始完赛耗时与已恢复中止事件
 * 推导每名选手的净分段、净完赛与逐事件补偿，不涉及数据库与事务。
 *
 * <p>规则：
 * <ul>
 *   <li>事件按中止开始点升序处理；后一次事件基于前一次事件的净结果继续扣减，
 *       原始计时空不被改写；</li>
 *   <li>受影响判定：以中止开始前（净耗时 &lt; startElapsedMs）最后一个已到检查点判断，
 *       已通过受影响起始检查点（position 不小于事件固化 position）者补偿0；
 *       中止开始前已完赛（净完赛 &lt; startElapsedMs）者不受影响；
 *       其余已起跑（有任一分段或完赛耗时）者受影响；</li>
 *   <li>受影响选手：净耗时落在 [start, resume) 的记录为非法（恢复时整体422）；
 *       净耗时 &ge; resume 的分段与完赛扣除该事件中止时长；</li>
 *   <li>一致性校验：净分段按检查点顺序严格递增、净值不为负、
 *       净分段严格小于净完赛；任一违反则恢复失败且事件不落库。</li>
 * </ul>
 */
public final class NetTimeCalculator {

    private NetTimeCalculator() {
    }

    /** 中止事件视图（仅已恢复事件参与净计时）。 */
    public interface SuspensionEventView {
        /** 中止事件ID。 */
        String eventKey();

        /** 受影响起始检查点顺序（登记时固化）。 */
        int checkpointPosition();

        /** 中止开始累计耗时点（毫秒）。 */
        long startElapsedMs();

        /** 恢复累计耗时点（毫秒），必须大于开始点。 */
        long resumeElapsedMs();
    }

    /** 单选手在单个事件上的补偿结果。 */
    public record CompensationEntry(String eventKey, boolean affected, long compensationMs) {
    }

    /** 单选手净计时结果。 */
    public record NetRunnerResult(
            Long netFinishMs,
            Map<String, Long> netElapsedByCheckpoint,
            List<CompensationEntry> compensations) {
    }

    /** 净计时不变量违反（恢复时由服务层转换为422并整体回滚）。 */
    public static final class NetTimeViolationException extends RuntimeException {
        public NetTimeViolationException(String message) {
            super(message);
        }
    }

    /**
     * 重算全部选手净计时。
     *
     * @param events  已恢复中止事件（任意顺序，内部按开始点排序）；为空时净值等于原始值
     * @param runners 全部选手视图（参赛号、原始完赛耗时）
     * @param timings 全部原始分段记录
     * @return 参赛号 → 净计时结果
     * @throws NetTimeViolationException 任一净不变量被破坏（记录落窗、净分段非严格递增、
     *                                   净值为负、净分段不小于净完赛）
     */
    public static Map<String, NetRunnerResult> computeAll(
            List<? extends SuspensionEventView> events,
            List<? extends ResultCalculator.RunnerView> runners,
            List<? extends TimingView> timings) {
        List<SuspensionEventView> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(Comparator.comparingLong(SuspensionEventView::startElapsedMs));

        Map<String, List<TimingView>> timingsByBib = new HashMap<>();
        for (TimingView timing : timings) {
            timingsByBib.computeIfAbsent(timing.bib(), key -> new ArrayList<>()).add(timing);
        }

        Map<String, NetRunnerResult> results = new TreeMap<>();
        for (ResultCalculator.RunnerView runner : runners) {
            List<TimingView> runnerTimings =
                    timingsByBib.getOrDefault(runner.bib(), List.of());
            results.put(runner.bib(),
                    computeForRunner(runner.bib(), runner.finishTimeMs(), runnerTimings,
                            orderedEvents));
        }
        return results;
    }

    /** 重算单名选手：逐事件判定受影响并扣减，最后校验净不变量。 */
    private static NetRunnerResult computeForRunner(
            String bib,
            Long rawFinishMs,
            List<TimingView> rawTimings,
            List<SuspensionEventView> orderedEvents) {
        // 当前净分段：position 升序的可变中间态
        List<TimingView> ordered = new ArrayList<>(rawTimings);
        ordered.sort(Comparator.comparingInt(TimingView::position));
        long[] netValues = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            netValues[i] = ordered.get(i).elapsedMillis();
        }
        Long netFinish = rawFinishMs;

        List<CompensationEntry> compensations = new ArrayList<>(orderedEvents.size());
        for (SuspensionEventView event : orderedEvents) {
            long duration = event.resumeElapsedMs() - event.startElapsedMs();
            boolean started = !ordered.isEmpty() || netFinish != null;
            boolean passedKeyCheckpoint = false;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).position() >= event.checkpointPosition()
                        && netValues[i] < event.startElapsedMs()) {
                    passedKeyCheckpoint = true;
                    break;
                }
            }
            boolean finishedBeforeStart =
                    netFinish != null && netFinish < event.startElapsedMs();
            boolean affected = started && !passedKeyCheckpoint && !finishedBeforeStart;
            compensations.add(new CompensationEntry(
                    event.eventKey(), affected, affected ? duration : 0L));
            if (!affected) {
                continue;
            }
            for (int i = 0; i < ordered.size(); i++) {
                long net = netValues[i];
                if (net >= event.startElapsedMs() && net < event.resumeElapsedMs()) {
                    throw new NetTimeViolationException(
                            "选手 " + bib + " 的检查点 " + ordered.get(i).checkpointCode()
                                    + " 净记录落在中止窗口 [" + event.startElapsedMs() + ","
                                    + event.resumeElapsedMs() + ") 内");
                }
                if (net >= event.resumeElapsedMs()) {
                    netValues[i] = net - duration;
                }
            }
            if (netFinish != null) {
                // 受影响且未在中止前完赛，故 netFinish >= start；落在窗口内即非法
                if (netFinish < event.resumeElapsedMs()) {
                    throw new NetTimeViolationException(
                            "选手 " + bib + " 的净完赛记录落在中止窗口 ["
                                    + event.startElapsedMs() + "," + event.resumeElapsedMs()
                                    + ") 内");
                }
                netFinish = netFinish - duration;
            }
        }

        validateNetInvariants(bib, ordered, netValues, netFinish);

        Map<String, Long> netByCheckpoint = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            netByCheckpoint.put(ordered.get(i).checkpointCode(), netValues[i]);
        }
        return new NetRunnerResult(netFinish, netByCheckpoint, compensations);
    }

    /** 净不变量：净分段严格递增、净值不为负、净分段严格小于净完赛。 */
    private static void validateNetInvariants(
            String bib, List<TimingView> ordered, long[] netValues, Long netFinish) {
        for (int i = 0; i < netValues.length; i++) {
            if (netValues[i] < 0) {
                throw new NetTimeViolationException(
                        "选手 " + bib + " 的检查点 " + ordered.get(i).checkpointCode()
                                + " 净耗时为负");
            }
            if (i > 0 && netValues[i] <= netValues[i - 1]) {
                throw new NetTimeViolationException(
                        "选手 " + bib + " 的净分段不再严格递增: "
                                + ordered.get(i - 1).checkpointCode() + "=" + netValues[i - 1]
                                + ", " + ordered.get(i).checkpointCode() + "=" + netValues[i]);
            }
            if (netFinish != null && netValues[i] >= netFinish) {
                throw new NetTimeViolationException(
                        "选手 " + bib + " 的净分段 " + ordered.get(i).checkpointCode()
                                + "=" + netValues[i] + " 不小于净完赛 " + netFinish);
            }
        }
        if (netFinish != null && netFinish < 0) {
            throw new NetTimeViolationException("选手 " + bib + " 的净完赛耗时为负");
        }
    }
}
