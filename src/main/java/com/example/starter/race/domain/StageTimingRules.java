package com.example.starter.race.domain;

import java.util.List;

/**
 * 阶段分段提交/更正不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>阶段从零计时，无独立完赛耗时上界校验；规则（违反任一则拒绝，不写入）：
 * <ul>
 *   <li>elapsedMillis 取值 1~86400000；</li>
 *   <li>同一选手同一阶段同一检查点最多一条；</li>
 *   <li>允许乱序到达，但按检查点顺序查看时耗时必须严格递增。</li>
 * </ul>
 */
public final class StageTimingRules {

    /** 阶段分段耗时上界（毫秒），含端点：1天。 */
    public static final long MAX_ELAPSED_MILLIS = 86_400_000L;

    private StageTimingRules() {
    }

    /**
     * 校验一次阶段分段提交。
     *
     * @param existingForRunner 该选手该阶段已存在的全部分段记录（任意顺序）
     * @param targetPosition    本次提交检查点的阶段内顺序
     * @param elapsedMillis     本次提交的阶段内累计耗时
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validateSubmit(
            List<? extends TimingView> existingForRunner,
            int targetPosition,
            long elapsedMillis) {
        if (elapsedMillis < 1L || elapsedMillis > MAX_ELAPSED_MILLIS) {
            return "阶段分段耗时毫秒数必须在 1~86400000 之间";
        }
        return checkOrdering(existingForRunner, targetPosition, elapsedMillis, true);
    }

    /**
     * 校验一次阶段分段更正（被更正记录自身不参与重复与邻序比较）。
     *
     * @param existingForRunner 该选手该阶段已存在的全部分段记录（含被更正记录）
     * @param targetTimingId    被更正的记录ID
     * @param targetPosition    被更正记录的检查点顺序
     * @param elapsedMillis     更正后的阶段内累计耗时
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validateRevision(
            List<? extends TimingView> existingForRunner,
            String targetTimingId,
            int targetPosition,
            long elapsedMillis) {
        if (elapsedMillis < 1L || elapsedMillis > MAX_ELAPSED_MILLIS) {
            return "阶段分段耗时毫秒数必须在 1~86400000 之间";
        }
        List<? extends TimingView> others = existingForRunner.stream()
                .filter(timing -> !timing.timingId().equals(targetTimingId))
                .toList();
        return checkOrdering(others, targetPosition, elapsedMillis, false);
    }

    private static String checkOrdering(
            List<? extends TimingView> existing,
            int targetPosition,
            long elapsedMillis,
            boolean rejectSamePosition) {
        TimingView predecessor = null;
        TimingView successor = null;
        for (TimingView timing : existing) {
            if (timing.position() == targetPosition) {
                if (rejectSamePosition) {
                    return "同一选手同一阶段同一检查点最多一条分段记录";
                }
                continue;
            }
            if (timing.position() < targetPosition) {
                if (predecessor == null || timing.position() > predecessor.position()) {
                    predecessor = timing;
                }
            } else {
                if (successor == null || timing.position() < successor.position()) {
                    successor = timing;
                }
            }
        }
        if (predecessor != null && elapsedMillis <= predecessor.elapsedMillis()) {
            return "阶段分段耗时必须严格大于前一检查点耗时: position=" + predecessor.position();
        }
        if (successor != null && elapsedMillis >= successor.elapsedMillis()) {
            return "阶段分段耗时必须严格小于后一检查点耗时: position=" + successor.position();
        }
        return null;
    }
}
