package com.example.starter.domain;

import java.util.List;

/**
 * 带孔洞多边形与线段/折线的精确相交判定（整数坐标，全部使用 long 精确运算，
 * 不做固定采样：窄禁飞实体不会因采样步长被跳过）。
 *
 * <p>禁飞实体 = 外环闭区域（含外环边界）减去各孔洞开内部；孔洞边界仍属于禁飞实体。
 * 命中条件（任一成立）：</p>
 * <ol>
 *   <li>线段端点位于实体内（外环闭区域内且不在孔洞严格内部）；</li>
 *   <li>线段与外环任一边相交或接触——孔洞严格在外环内部且不接触外环边，
 *       外环边上的点必为实体点；</li>
 *   <li>线段与任一孔洞边相交或接触——孔洞边界本身即禁飞实体，线段想在
 *       孔洞开内部与实体之间穿行必跨越孔边。</li>
 * </ol>
 * <p>线段完整位于孔洞开内部（不触边）时不命中。</p>
 */
public final class PolygonGeometry {

    private PolygonGeometry() {
    }

    /** 叉积 (b-a) × (c-a)：正表示 c 在有向边 a→b 左侧。坐标差先转 long 防溢出。 */
    private static long cross(Point a, Point b, Point c) {
        return (long) (b.x() - a.x()) * (c.y() - a.y())
                - (long) (b.y() - a.y()) * (c.x() - a.x());
    }

    /** 点 c 是否落在闭线段 ab 上（共线且在包围盒内）。 */
    static boolean onSegment(Point a, Point b, Point c) {
        return cross(a, b, c) == 0L
                && Math.min(a.x(), b.x()) <= c.x() && c.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= c.y() && c.y() <= Math.max(a.y(), b.y());
    }

    /**
     * 两条闭线段是否相交（含端点相接、交叉、部分重叠或共线接触）。
     */
    public static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        if (onSegment(a, b, c) || onSegment(a, b, d)
                || onSegment(c, d, a) || onSegment(c, d, b)) {
            return true;
        }
        long c1 = cross(a, b, c);
        long c2 = cross(a, b, d);
        long c3 = cross(c, d, a);
        long c4 = cross(c, d, b);
        return (c1 > 0L) != (c2 > 0L) && (c3 > 0L) != (c4 > 0L)
                && c1 != 0L && c2 != 0L && c3 != 0L && c4 != 0L;
    }

    /** 点是否严格位于简单环内部（不含边界），环方向不限。 */
    public static boolean pointStrictlyInside(List<Point> ring, Point p) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1) % n);
            if (onSegment(a, b, p)) {
                return false;
            }
            // 水平射线法（半开边约定，端点高度等于 p.y 时不重复计数）
            if (a.y() > p.y() != b.y() > p.y()) {
                long cr = cross(a, b, p);
                if (b.y() > a.y()) {
                    if (cr > 0L) {
                        inside = !inside;
                    }
                } else if (cr < 0L) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** 点是否位于环的闭区域内（内部或边界上）。 */
    static boolean pointInsideOrBoundary(List<Point> ring, Point p) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            if (onSegment(ring.get(i), ring.get((i + 1) % n), p)) {
                return true;
            }
        }
        return pointStrictlyInside(ring, p);
    }

    /** 点是否为禁飞实体点：外环闭区域内且不在任一孔洞严格内部（孔边仍是实体）。 */
    private static boolean solidAt(PolygonWithHoles polygon, Point p) {
        if (!pointInsideOrBoundary(polygon.outer(), p)) {
            return false;
        }
        for (List<Point> hole : polygon.holes()) {
            if (pointStrictlyInside(hole, p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断一条线段（含端点）是否与带孔洞多边形的禁飞实体相交。
     * 覆盖：端点在实体内、线段穿越实体、只触碰外环边或孔洞边；
     * 线段完整位于孔洞开内部、仅在孔洞内穿行不命中。
     */
    public static boolean segmentHitsPolygon(Point a, Point b, PolygonWithHoles polygon) {
        if (solidAt(polygon, a) || solidAt(polygon, b)) {
            return true;
        }
        if (intersectsRing(a, b, polygon.outer())) {
            return true;
        }
        for (List<Point> hole : polygon.holes()) {
            if (intersectsRing(a, b, hole)) {
                return true;
            }
        }
        return false;
    }

    /** 线段是否与环的任一边（闭线段）相交或接触。 */
    private static boolean intersectsRing(Point a, Point b, List<Point> ring) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            if (segmentsIntersect(a, b, ring.get(i), ring.get((i + 1) % n))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 有序航点折线（含每条线段及端点）是否与禁飞实体相交。
     */
    public static boolean polylineHitsPolygon(List<Point> points, PolygonWithHoles polygon) {
        for (int i = 0; i + 1 < points.size(); i++) {
            if (segmentHitsPolygon(points.get(i), points.get(i + 1), polygon)) {
                return true;
            }
        }
        return false;
    }
}
