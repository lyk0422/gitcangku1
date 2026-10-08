package com.example.starter.observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 圆周区间交集运算：求全部圆弧的交集，返回按起始角升序的分量列表。
 *
 * <p>全程整数运算：圆弧先展开为 [0, 359999] 内的闭区间（跨零弧拆成两段），
 * 逐条求交后按"相接即连续"归并（含跨零片段合为一个圆弧），允许退化单点。
 */
public final class CircularArcIntersection {

    private static final int MOD = CircularArc.MODULO;
    private static final int MAX_POINT = MOD - 1;

    private CircularArcIntersection() {
    }

    /**
     * 求全部圆弧的交集；无交集返回空列表。结果分量按起始角升序，相接片段已归并。
     */
    public static List<CircularArc> intersectAll(List<CircularArc> arcs) {
        if (arcs.isEmpty()) {
            return List.of();
        }
        List<int[]> current = toIntervals(arcs.get(0));
        for (int i = 1; i < arcs.size() && !current.isEmpty(); i++) {
            current = intersect(current, toIntervals(arcs.get(i)));
        }
        return toArcs(current);
    }

    /**
     * 将圆弧展开为 [0, 359999] 内的闭区间列表；跨零弧拆为 [start, 359999] 与 [0, end-MOD] 两段。
     */
    private static List<int[]> toIntervals(CircularArc arc) {
        int end = arc.startAngle() + arc.arcLength();
        if (end < MOD) {
            return List.of(new int[]{arc.startAngle(), end});
        }
        return List.of(new int[]{arc.startAngle(), MAX_POINT}, new int[]{0, end - MOD});
    }

    /**
     * 两组闭区间两两求交并归一化。
     */
    private static List<int[]> intersect(List<int[]> left, List<int[]> right) {
        List<int[]> overlaps = new ArrayList<>();
        for (int[] a : left) {
            for (int[] b : right) {
                int lo = Math.max(a[0], b[0]);
                int hi = Math.min(a[1], b[1]);
                if (lo <= hi) {
                    overlaps.add(new int[]{lo, hi});
                }
            }
        }
        return normalize(overlaps);
    }

    /**
     * 归一化：按起点升序排序，合并重叠或相接（整数闭区间相邻）的区间。
     */
    private static List<int[]> normalize(List<int[]> intervals) {
        List<int[]> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(interval -> interval[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] interval : sorted) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && interval[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new int[]{interval[0], interval[1]});
            }
        }
        return merged;
    }

    /**
     * 将归一化区间列表转为圆弧分量：跨零相接片段（首段起于 0、末段止于 359999）合为一个圆弧，
     * 其余区间直接换算；结果按起始角升序。
     */
    private static List<CircularArc> toArcs(List<int[]> intervals) {
        List<CircularArc> arcs = new ArrayList<>();
        int begin = 0;
        int end = intervals.size();
        if (intervals.size() >= 2) {
            int[] first = intervals.get(0);
            int[] last = intervals.get(intervals.size() - 1);
            if (first[0] == 0 && last[1] == MAX_POINT) {
                // 跨零相接：末段与首段属于同一连续弧，合并为一个跨零圆弧
                arcs.add(new CircularArc(last[0], (MOD - last[0]) + first[1]));
                begin = 1;
                end = intervals.size() - 1;
            }
        }
        for (int i = begin; i < end; i++) {
            int[] interval = intervals.get(i);
            arcs.add(new CircularArc(interval[0], interval[1] - interval[0]));
        }
        arcs.sort(Comparator.comparingInt(CircularArc::startAngle));
        return arcs;
    }
}
