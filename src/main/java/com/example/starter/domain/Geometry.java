package com.example.starter.domain;

import java.util.List;

/**
 * 二维平面几何计算，仅用于本题模拟。所有中间量使用 long，避免叉积溢出。
 * 禁飞区为闭矩形：线段或点与之相交、位于其中或仅接触边界均视为命中。
 */
public final class Geometry {

    private Geometry() {
    }

    /**
     * 判断航线（有序点列，含相邻点连成的线段）是否命中闭矩形禁飞区。
     * 覆盖端点在区域外而线段穿越区域的情况，不依赖仅检查航点。
     */
    public static boolean routeIntersectsZone(List<Point> points, Zone zone) {
        for (Point p : points) {
            if (pointInClosedRect(p.x(), p.y(), zone.minX(), zone.minY(), zone.maxX(), zone.maxY())) {
                return true;
            }
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (segmentIntersectsClosedRect(a.x(), a.y(), b.x(), b.y(),
                    zone.minX(), zone.minY(), zone.maxX(), zone.maxY())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断点是否位于闭矩形内（含边界）。
     */
    public static boolean pointInClosedRect(int px, int py, int minX, int minY, int maxX, int maxY) {
        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }

    /**
     * 判断线段是否与闭矩形相交或接触（含端点在矩形内、仅接触边界、穿越矩形）。
     */
    public static boolean segmentIntersectsClosedRect(int ax, int ay, int bx, int by,
                                                      int minX, int minY, int maxX, int maxY) {
        if (pointInClosedRect(ax, ay, minX, minY, maxX, maxY)
                || pointInClosedRect(bx, by, minX, minY, maxX, maxY)) {
            return true;
        }
        // 端点均在矩形外时，检查线段是否与四条边（闭线段）相交。
        return segmentsIntersectClosed(ax, ay, bx, by, minX, minY, maxX, minY)
                || segmentsIntersectClosed(ax, ay, bx, by, maxX, minY, maxX, maxY)
                || segmentsIntersectClosed(ax, ay, bx, by, maxX, maxY, minX, maxY)
                || segmentsIntersectClosed(ax, ay, bx, by, minX, maxY, minX, minY);
    }

    /**
     * 判断两条闭线段是否相交或接触（含共线重叠与端点接触）。
     */
    public static boolean segmentsIntersectClosed(long ax, long ay, long bx, long by,
                                                  long cx, long cy, long dx, long dy) {
        long o1 = orient(ax, ay, bx, by, cx, cy);
        long o2 = orient(ax, ay, bx, by, dx, dy);
        long o3 = orient(cx, cy, dx, dy, ax, ay);
        long o4 = orient(cx, cy, dx, dy, bx, by);
        if (o1 == 0 && onSegment(ax, ay, bx, by, cx, cy)) {
            return true;
        }
        if (o2 == 0 && onSegment(ax, ay, bx, by, dx, dy)) {
            return true;
        }
        if (o3 == 0 && onSegment(cx, cy, dx, dy, ax, ay)) {
            return true;
        }
        if (o4 == 0 && onSegment(cx, cy, dx, dy, bx, by)) {
            return true;
        }
        return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0);
    }

    /**
     * 向量 ab 与 ac 的叉积符号量：&gt;0 表示 c 在 ab 左侧，&lt;0 右侧，0 共线。
     */
    private static long orient(long ax, long ay, long bx, long by, long cx, long cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * 判断与 ab 共线的点 p 是否落在闭线段 ab 上。
     */
    private static boolean onSegment(long ax, long ay, long bx, long by, long px, long py) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && py >= Math.min(ay, by) && py <= Math.max(ay, by);
    }
}
