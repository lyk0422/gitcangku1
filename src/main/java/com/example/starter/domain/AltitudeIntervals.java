package com.example.starter.domain;

/**
 * 高度/时间区间相交判定。本题高度带为左闭右开 {@code [lower, upper)}，
 * 占用时间窗同为半开区间；端点相接（前区间上限恰为后区间下限）不算重叠。
 *
 * <p>高度层相交语义：巡航高度点进入 {@code [lower, upper)}，
 * 即 {@code lower <= altitude < upper}；高度不相交的航线不消耗该高度带容量。</p>
 */
public final class AltitudeIntervals {

    private AltitudeIntervals() {
    }

    /**
     * 点高度是否进入左闭右开高度带。
     *
     * @param altitude 巡航高度（米）
     * @param lower    高度带下限（含，米）
     * @param upper    高度带上限（不含，米）
     * @return true 表示高度层相交
     */
    public static boolean contains(int altitude, int lower, int upper) {
        return altitude >= lower && altitude < upper;
    }

    /**
     * 两个左闭右开区间是否有正长度重叠；端点相接（aUpper == bLower 等）返回 false。
     *
     * @param aLower 区间 A 下限（含）
     * @param aUpper 区间 A 上限（不含）
     * @param bLower 区间 B 下限（含）
     * @param bUpper 区间 B 上限（不含）
     * @return true 表示存在公共内点
     */
    public static boolean overlaps(long aLower, long aUpper, long bLower, long bUpper) {
        return aLower < bUpper && bLower < aUpper;
    }
}
