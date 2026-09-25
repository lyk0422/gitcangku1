package com.example.starter.domain;

/**
 * 高度层与时段判定工具。高度单位米，高度带为左闭右开区间；
 * 时段为 epoch 毫秒（UTC），同样左闭右开。
 */
public final class Altitudes {

    private Altitudes() {
    }

    /**
     * 判断高度点是否落入高度带（左闭右开）：lower &lt;= altitude &lt; upper。
     *
     * @param altitude 巡航高度（米）
     * @param lower    高度带下限（含，米）
     * @param upper    高度带上限（不含，米）
     * @return true 表示高度相交
     */
    public static boolean inBand(int altitude, int lower, int upper) {
        return altitude >= lower && altitude < upper;
    }

    /**
     * 判断两个左闭右开高度带是否重叠。端点相接（aUpper == bLower 或反之）
     * 不算重叠，返回 false；必须满足 lower &lt; upper。
     *
     * @return true 表示存在正长度交集
     */
    public static boolean bandsOverlap(int aLower, int aUpper, int bLower, int bUpper) {
        return aLower < bUpper && bLower < aUpper;
    }

    /**
     * 判断两个左闭右开时段是否重叠（epoch 毫秒）。端点相接不算重叠。
     *
     * @return true 表示存在正长度时间交集
     */
    public static boolean timeOverlaps(long startA, long endA, long startB, long endB) {
        return startA < endB && startB < endA;
    }
}
