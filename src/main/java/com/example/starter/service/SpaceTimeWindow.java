package com.example.starter.service;

/**
 * 左闭右开时空窗：UTC 时间区间 [startMs, endMs) 与高度范围 [altMin, altMax)。
 *
 * @param startMs 时间窗起点（含），epoch 毫秒 UTC
 * @param endMs   时间窗终点（不含），必须大于 startMs
 * @param altMin  高度下界（含），单位米
 * @param altMax  高度上界（不含），单位米，必须大于 altMin
 */
public record SpaceTimeWindow(long startMs, long endMs, int altMin, int altMax) {

    /** 校验时间窗合法（左闭右开、起止严格递增），不合法抛 IllegalArgumentException。 */
    public SpaceTimeWindow {
        if (endMs <= startMs) {
            throw new IllegalArgumentException("时间窗终点必须大于起点（左闭右开）");
        }
        if (altMax <= altMin) {
            throw new IllegalArgumentException("高度范围上界必须大于下界（左闭右开）");
        }
    }
}
