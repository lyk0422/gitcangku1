package com.example.starter.observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 圆周区间几何：全部使用整数（千分之一度），禁止浮点。
 *
 * <p>圆弧 (startAngle, arcLength) 覆盖从 startAngle 起正向 arcLength 个整数刻度点，
 * 即半开区间 [startAngle, startAngle + arcLength)，按模 360000 绕回；
 * 因此 359 度与 1 度相距 2 度而非 358 度。退化单点以弧长 1 表示。
 */
public final class ArcGeometry {

    /** 全圆刻度数：360 度 × 1000（千分之一度）。 */
    public static final int MODULUS = 360000;

    /** 起始角最大值（含）：359.999 度。 */
    public static final int MAX_START_ANGLE = 359999;

    /** 弧长最大值（含）：180.000 度，半圆。 */
    public static final int MAX_ARC_LENGTH = 180000;

    private ArcGeometry() {
    }

    /**
     * 半开线段 [start, end)，0 &lt;= start &lt; end &lt;= MODULUS。
     */
    record Segment(int start, int end) {
    }

    /**
     * 求多组圆弧的交集分量：相接的跨零片段合为一个圆弧，全部分量按起始角升序返回；
     * 无交集返回空列表（允许退化单点，弧长为 1）。
     */
    public static List<ArcComponent> intersectAll(List<List<ArcComponent>> arcSets) {
        List<Segment> current = null;
        for (List<ArcComponent> arcs : arcSets) {
            List<Segment> segments = normalizeSegments(arcs);
            if (current == null) {
                current = segments;
            } else if (!current.isEmpty()) {
                current = intersect(current, segments);
            }
        }
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        return toArcs(current);
    }

    /**
     * 将一组圆弧展开为 [0, MODULUS) 上的有序不重叠线段；重叠或相接的线段先行合并，
     * 保证交集结果唯一，与输入圆弧的拆分方式无关。
     */
    static List<Segment> normalizeSegments(List<ArcComponent> arcs) {
        List<Segment> raw = new ArrayList<>();
        for (ArcComponent arc : arcs) {
            int start = arc.startAngle();
            int end = start + arc.arcLength();
            if (end <= MODULUS) {
                raw.add(new Segment(start, end));
            } else {
                raw.add(new Segment(start, MODULUS));
                raw.add(new Segment(0, end - MODULUS));
            }
        }
        raw.sort(Comparator.comparingInt(Segment::start));
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : raw) {
            Segment last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && segment.start() <= last.end()) {
                merged.set(merged.size() - 1, new Segment(last.start(), Math.max(last.end(), segment.end())));
            } else {
                merged.add(segment);
            }
        }
        return merged;
    }

    /**
     * 两组线段两两求交，结果按起点升序。
     */
    private static List<Segment> intersect(List<Segment> left, List<Segment> right) {
        List<Segment> result = new ArrayList<>();
        for (Segment a : left) {
            for (Segment b : right) {
                int start = Math.max(a.start(), b.start());
                int end = Math.min(a.end(), b.end());
                if (start < end) {
                    result.add(new Segment(start, end));
                }
            }
        }
        result.sort(Comparator.comparingInt(Segment::start));
        return result;
    }

    /**
     * 线段集合转圆弧分量：末段抵 360000 且首段自 0 起时，两个相接的跨零片段合为一个圆弧；
     * 全部分量按起始角升序返回。
     */
    private static List<ArcComponent> toArcs(List<Segment> segments) {
        List<Segment> work = new ArrayList<>(segments);
        work.sort(Comparator.comparingInt(Segment::start));
        Segment first = work.get(0);
        Segment last = work.get(work.size() - 1);
        boolean wraps = work.size() > 1 && first.start() == 0 && last.end() == MODULUS;
        List<ArcComponent> arcs = new ArrayList<>();
        for (int i = wraps ? 1 : 0; i < (wraps ? work.size() - 1 : work.size()); i++) {
            Segment segment = work.get(i);
            arcs.add(new ArcComponent(segment.start(), segment.end() - segment.start()));
        }
        if (wraps) {
            arcs.add(new ArcComponent(last.start(), (MODULUS - last.start()) + first.end()));
        }
        arcs.sort(Comparator.comparingInt(ArcComponent::startAngle)
                .thenComparing(ArcComponent::arcLength));
        return arcs;
    }
}
