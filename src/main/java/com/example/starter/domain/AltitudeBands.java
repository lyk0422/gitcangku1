package com.example.starter.domain;

import java.util.List;

/**
 * 高度带区间判定。高度带为左闭右开区间 [lowerM, upperM)，单位米；
 * 同一区域内高度带不得重叠，端点相接（一带上限等于另一带下限）合法。
 */
public final class AltitudeBands {

    private AltitudeBands() {
    }

    /**
     * 判断两个左闭右开高度带是否重叠（端点相接不算重叠）。
     *
     * @param lowerA 带 A 下限（含），米
     * @param upperA 带 A 上限（不含），米
     * @param lowerB 带 B 下限（含），米
     * @param upperB 带 B 上限（不含），米
     * @return true 表示两带存在共同高度
     */
    public static boolean overlaps(int lowerA, int upperA, int lowerB, int upperB) {
        return lowerA < upperB && lowerB < upperA;
    }

    /**
     * 判断巡航高度是否落入高度带（左闭右开）。
     *
     * @param altitudeM 巡航高度，米
     * @param lowerM    高度带下限（含），米
     * @param upperM    高度带上限（不含），米
     * @return true 表示高度在带内，消耗该带容量
     */
    public static boolean contains(int altitudeM, int lowerM, int upperM) {
        return altitudeM >= lowerM && altitudeM < upperM;
    }

    /**
     * 校验一组高度带两两不重叠（端点相接合法）。
     *
     * @param bands 高度带列表，元素为 [lowerM, upperM]
     * @return true 表示存在重叠
     */
    public static boolean anyOverlap(List<int[]> bands) {
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                int[] a = bands.get(i);
                int[] b = bands.get(j);
                if (overlaps(a[0], a[1], b[0], b[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 计算巡航高度与高度带的垂直间隔（米）。落入带内为 0；
     * 低于下限为 lowerM - altitude；高于或等于上限为 altitude - upperM。
     */
    public static int verticalSeparation(int altitudeM, int lowerM, int upperM) {
        if (contains(altitudeM, lowerM, upperM)) {
            return 0;
        }
        return altitudeM < lowerM ? lowerM - altitudeM : altitudeM - upperM;
    }
}
