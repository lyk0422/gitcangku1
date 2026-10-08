package com.example.starter.domain;

import java.util.List;

/**
 * 带孔洞多边形的合法性校验（整数精确运算）。
 *
 * <p>规则：顶点首尾不重复；各环至少 3 个顶点；环内顶点不重复；
 * 任意不相邻边不相交（无自交，首尾相邻边只允许在公共顶点相接）；
 * 孔洞必须严格位于外环内部（孔洞的每个顶点都在其开内部，孔边不接触外环边）；
 * 孔洞之间严格互不相交且不接触（顶点互不在对方闭区域内、边互不相交）。</p>
 */
public final class PolygonValidator {

    private PolygonValidator() {
    }

    /** 校验并构造多边形；任一规则违反抛 {@link InvalidPolygonException}。 */
    public static PolygonWithHoles validate(List<Point> outer, List<List<Point>> holes) {
        if (outer == null || outer.size() < 3 || outer.size() > 10) {
            throw new InvalidPolygonException("外环顶点数必须在 3~10 之间");
        }
        if (holes == null || holes.size() > 2) {
            throw new InvalidPolygonException("孔洞数量至多为 2 个");
        }
        validateRing(outer, "外环", 10);
        for (int h = 0; h < holes.size(); h++) {
            List<Point> hole = holes.get(h);
            if (hole == null || hole.size() < 3 || hole.size() > 8) {
                throw new InvalidPolygonException("孔洞 " + (h + 1) + " 顶点数必须在 3~8 之间");
            }
            validateRing(hole, "孔洞 " + (h + 1), 8);
        }
        // 孔洞严格在外环内部：全部顶点在开内部，且孔边与外环边不相交/不接触
        for (int h = 0; h < holes.size(); h++) {
            List<Point> hole = holes.get(h);
            for (Point v : hole) {
                if (!PolygonGeometry.pointStrictlyInside(outer, v)) {
                    throw new InvalidPolygonException(
                            "孔洞 " + (h + 1) + " 的顶点 " + v.x() + "," + v.y()
                                    + " 必须严格位于外环内部且不能落在外环边上");
                }
            }
            if (ringsIntersect(outer, hole)) {
                throw new InvalidPolygonException(
                        "孔洞 " + (h + 1) + " 的边与外环边相交或接触");
            }
        }
        // 孔洞之间严格互不相交且不接触
        for (int i = 0; i < holes.size(); i++) {
            for (int j = i + 1; j < holes.size(); j++) {
                if (ringsIntersect(holes.get(i), holes.get(j))
                        || anyVertexInsideOrOn(holes.get(i), holes.get(j))
                        || anyVertexInsideOrOn(holes.get(j), holes.get(i))) {
                    throw new InvalidPolygonException(
                            "孔洞 " + (i + 1) + " 与孔洞 " + (j + 1)
                                    + " 必须互不相交且不能相互接触");
                }
            }
        }
        return new PolygonWithHoles(List.copyOf(outer),
                holes.stream().map(List::copyOf).toList());
    }

    private static void validateRing(List<Point> ring, String label, int maxVertices) {
        if (ring.size() > maxVertices) {
            throw new InvalidPolygonException(label + " 顶点数超出上限 " + maxVertices);
        }
        int n = ring.size();
        if (signedArea2(ring) == 0L) {
            throw new InvalidPolygonException(label + " 面积为零（共线顶点导致的退化环）");
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Point a = ring.get(i);
                Point b = ring.get(j);
                if (a.x() == b.x() && a.y() == b.y()) {
                    throw new InvalidPolygonException(
                            label + " 存在重复顶点（顶点首尾不用重复）: " + a.x() + "," + a.y());
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1) % n);
            if (a.x() == b.x() && a.y() == b.y()) {
                throw new InvalidPolygonException(label + " 存在零长度边（退化环）");
            }
        }
        // 自交检查：不相邻的边不能相交或接触；相邻边只允许在公共顶点相接
        for (int i = 0; i < n; i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                Point c = ring.get(j);
                Point d = ring.get((j + 1) % n);
                boolean adjacent = (j == i + 1) || (i == 0 && j == n - 1);
                if (adjacent) {
                    Point shared = (j == i + 1) ? b : a;
                    if (intersectBesidesSharedVertex(a, b, c, d, shared)) {
                        throw new InvalidPolygonException(label + " 相邻边在公共顶点之外相交（自交）");
                    }
                } else if (PolygonGeometry.segmentsIntersect(a, b, c, d)) {
                    throw new InvalidPolygonException(label + " 的不相邻边相交（自交）");
                }
            }
        }
    }

    /** 相邻边除公共顶点外是否还有交点（含共线重叠）。 */
    private static boolean intersectBesidesSharedVertex(
            Point a, Point b, Point c, Point d, Point shared) {
        if (PolygonGeometry.onSegment(a, b, c) && !c.equals(shared)) {
            return true;
        }
        if (PolygonGeometry.onSegment(a, b, d) && !d.equals(shared)) {
            return true;
        }
        if (PolygonGeometry.onSegment(c, d, a) && !a.equals(shared)) {
            return true;
        }
        if (PolygonGeometry.onSegment(c, d, b) && !b.equals(shared)) {
            return true;
        }
        // 非相邻端点不可能在对方线上（共线重叠时上面已覆盖）；
        // 再排除严格穿越（相邻边共享端点时叉积判定不会穿越，除非共线）
        return false;
    }

    /** 二倍有向面积（long 精确）；0 表示退化环。 */
    private static long signedArea2(List<Point> ring) {
        long area2 = 0L;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            Point a = ring.get(i);
            Point b = ring.get((i + 1) % n);
            area2 += (long) a.x() * b.y() - (long) a.y() * b.x();
        }
        return area2;
    }

    /** 两个环的任意边是否相交或接触。 */
    private static boolean ringsIntersect(List<Point> r1, List<Point> r2) {
        int n1 = r1.size();
        int n2 = r2.size();
        for (int i = 0; i < n1; i++) {
            Point a = r1.get(i);
            Point b = r1.get((i + 1) % n1);
            for (int j = 0; j < n2; j++) {
                if (PolygonGeometry.segmentsIntersect(a, b, r2.get(j), r2.get((j + 1) % n2))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** r1 是否有顶点位于 r2 的闭区域内（内部或边界上）。 */
    private static boolean anyVertexInsideOrOn(List<Point> r1, List<Point> r2) {
        for (Point v : r1) {
            if (pointInsideOrBoundary(r2, v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointInsideOrBoundary(List<Point> ring, Point p) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            if (PolygonGeometry.onSegment(ring.get(i), ring.get((i + 1) % n), p)) {
                return true;
            }
        }
        return PolygonGeometry.pointStrictlyInside(ring, p);
    }
}
