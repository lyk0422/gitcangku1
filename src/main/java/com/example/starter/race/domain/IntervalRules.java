package com.example.starter.race.domain;

import java.util.List;

/**
 * 区间计时提交/修订不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>规则（违反任一则写入被拒绝，不留半组证据）：
 * <ul>
 *   <li>区间端点为正整数毫秒，取值 1~86400000，且下界不大于上界（违反为400）；</li>
 *   <li>同一选手同一计时点最多一条区间记录；</li>
 *   <li>按计时点顺序查看时区间必须严格递增可分离：相邻点前者上界须严格小于后者下界，
 *       端点相等也不算保证递增（违反为422）。</li>
 * </ul>
 */
public final class IntervalRules {

    /** 完赛区间的保留计时点代码。 */
    public static final String FINISH_CODE = "FINISH";

    /** 区间端点上界（毫秒），含端点：1天。 */
    public static final long MAX_INTERVAL_MS = 86_400_000L;

    private IntervalRules() {
    }

    /**
     * 校验区间端点取值（对应 HTTP 400）。
     *
     * @return 非法时的错误信息；合法时为 null
     */
    public static String validateRange(long lowerMs, long upperMs) {
        if (lowerMs < 1L || lowerMs > MAX_INTERVAL_MS
                || upperMs < 1L || upperMs > MAX_INTERVAL_MS) {
            return "区间端点毫秒数必须在 1~86400000 之间";
        }
        if (lowerMs > upperMs) {
            return "区间下界不能大于上界";
        }
        return null;
    }

    /**
     * 校验一次区间提交/修订的相邻递增约束（对应 HTTP 422）。
     *
     * @param existingForRunner 该选手已存在的其它区间记录（任意顺序；修订时不含被修订记录）
     * @param targetPosition    本次写入计时点的顺序
     * @param lowerMs           本次写入的区间下界
     * @param upperMs           本次写入的区间上界
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validateAdjacency(
            List<? extends IntervalView> existingForRunner,
            int targetPosition,
            long lowerMs,
            long upperMs) {
        IntervalView predecessor = null;
        IntervalView successor = null;
        for (IntervalView interval : existingForRunner) {
            if (interval.position() == targetPosition) {
                return "同一选手同一计时点最多一条区间记录";
            }
            if (interval.position() < targetPosition) {
                if (predecessor == null || interval.position() > predecessor.position()) {
                    predecessor = interval;
                }
            } else {
                if (successor == null || interval.position() < successor.position()) {
                    successor = interval;
                }
            }
        }
        if (predecessor != null && lowerMs <= predecessor.upperMs()) {
            return "区间下界必须严格大于前一计时点区间上界: position=" + predecessor.position();
        }
        if (successor != null && upperMs >= successor.lowerMs()) {
            return "区间上界必须严格小于后一计时点区间下界: position=" + successor.position();
        }
        return null;
    }
}
