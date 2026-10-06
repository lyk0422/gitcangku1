package com.example.starter.domain;

import java.util.List;

/**
 * 一次通信覆盖审查的完整结论：各段合并并集与全部连续失联区间。
 *
 * @param union 各航段覆盖合并并集（按段序）
 * @param gaps  全部正时长的连续失联区间（按出现顺序；跨段缺口已连成一段）
 */
public record LinkAssessment(List<SegmentUnion> union, List<LinkGap> gaps) {

    /** 位置刻度总量：每段参数区间为整数刻度 [0,10000]。 */
    public static final int SCALE = 10000;
    /** 允许的最大连续失联：严格超过 5000 毫秒即 LINK_BLOCKED，等于允许。 */
    public static final long MAX_CONTINUOUS_LOSS_MS = 5000;
    /** 阈值换算到失联分子（毫秒 × 刻度），用于精确有理数比较。 */
    public static final long MAX_LOSS_TICK_MILLIS = MAX_CONTINUOUS_LOSS_MS * SCALE;

    /** 任一连续失联严格超过 5000 毫秒（精确比较，不提前取整）。 */
    public boolean anyLossExceedsLimit() {
        for (LinkGap gap : gaps) {
            if (gap.lossTickMillis() > MAX_LOSS_TICK_MILLIS) {
                return true;
            }
        }
        return false;
    }
}
