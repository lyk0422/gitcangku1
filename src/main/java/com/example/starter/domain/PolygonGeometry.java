package com.example.starter.domain;

import java.util.List;

/**
 * 带孔洞多边形的二维平面精确几何判定（整数坐标，单位米）。
 *
 * <p>禁飞实体 = 外环闭区域内部减去各孔洞闭区域内部；外环边与孔洞边仍禁飞。
 * 全部计算使用 long/double 精确比较：线段与环边求交使用叉积参数化判定，
 * 点在多边形内使用射线法，不做固定步长采样，因而不会跳过任意窄的禁飞实体。</p>
 *
 * <p>顶点首尾不重复；环的实际连接关系为 v[i] 连 v[i+1]，末点连回首点。
 * 本类不做环方向（顺/逆时针）要求，内/外按拓扑包含关系判定。</p>
 */
public final class PolygonGeometry {

    private PolygonGeometry() {
    }

    /** 二维向量叉积 (b-a) × (c-a)：正为 c 在 ab 左侧，负为右侧，0 为共线。 */
    private static long cross(Point a, Point b, Point c) {
        return (long) (b.x() - a.x()) * (c.y() - a.y())
                - (long) (b.y() - a.y()) * (c.x() - a.x());
    }

    /** 点 p 是否位于闭线段 ab 上（共线且在包围盒内）。 */
    public static boolean pointOnSegment(Point p, Point a, Point b) {
        return cross(a, b, p) == 0L
                && Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= p.y() && p.y() <= Math.max(a.y(), b.y());
    }

    /** 点是否落在环的任一条边上（含端点）。 */
    public static boolean pointOnRingBoundary(Point p, List<Point> ring) {
        for (int i = 0; i < ring.size(); i++) {
            if (pointOnSegment(p, ring.get(i), ring.get(nextIndex(i, ring.size())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点是否严格位于环内部（不含环边）。射线法：水平向右射线与边的奇偶数交点，
     * 环边上的点一律视为“不在内部”（边界由调用方单独按禁飞处理）。
     */
    public static boolean pointStrictlyInsideRing(Point p, List<Point> ring) {
        boolean inside = false;
        for (int i = 0; i < ring.size(); i++) {
            Point a = ring.get(i);
            Point b = ring.get(nextIndex(i, ring.size()));
            // 端点恰好水平的边界情况：按上端点 y 归属计数，避免重复/遗漏
            boolean crosses = (a.y() > p.y()) != (b.y() > p.y());
            if (crosses) {
                // 边与 y=p.y() 交点的 x（双精度；输入为整数，仅与 p.x 做不等比较）
                double xIntersect = a.x()
                        + (double) (p.y() - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (xIntersect > p.x()) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * 闭线段 ab 与闭线段 cd 是否相交（含端点接触、重叠与擦边）。
     * 先做叉积跨立判定，再处理共线重叠。
     */
    public static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        long d1 = cross(c, d, a);
        long d2 = cross(c, d, b);
        long d3 = cross(a, b, c);
        long d4 = cross(a, b, d);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        // 一端点落在另一线段上（含 T 型接触与共线端点接触）
        if (d1 == 0L && pointOnSegment(a, c, d)) {
            return true;
        }
        if (d2 == 0L && pointOnSegment(b, c, d)) {
            return true;
        }
        if (d3 == 0L && pointOnSegment(c, a, b)) {
            return true;
        }
        if (d4 == 0L && pointOnSegment(d, a, b)) {
            return true;
        }
        return false;
    }

    /** 线段 ab 是否与环的任一边相交（含端点接触）。 */
    public static boolean segmentTouchesRingBoundary(Point a, Point b, List<Point> ring) {
        for (int i = 0; i < ring.size(); i++) {
            Point c = ring.get(i);
            Point d = ring.get(nextIndex(i, ring.size()));
            if (segmentsIntersect(a, b, c, d)) {
                return true;
            }
        }
        return false;
    }

    private static int nextIndex(int i, int size) {
        return i + 1 == size ? 0 : i + 1;
    }
}
