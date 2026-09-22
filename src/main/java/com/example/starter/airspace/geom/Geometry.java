package com.example.starter.airspace.geom;

import java.util.List;

/**
 * 二维平面几何计算（整数坐标，单位米）。仅用于本题模拟，不用于真实飞行。
 * 所有判定均按闭区间处理：点落在边界上、线段仅接触边界都算相交。
 */
public final class Geometry {

    private Geometry() {
    }

    /** 平面整数点。 */
    public record Point(int x, int y) {
    }

    /** 非退化轴对齐闭矩形。 */
    public record Rect(int xMin, int yMin, int xMax, int yMax) {
    }

    /** 点是否位于闭矩形内（含边界）。 */
    public static boolean pointInside(Point p, Rect r) {
        return p.x >= r.xMin && p.x <= r.xMax && p.y >= r.yMin && p.y <= r.yMax;
    }

    /**
     * 线段 p0-p1 是否与闭矩形相交。
     *
     * <p>覆盖：端点在矩形内、线段穿越矩形（端点均在矩形外）、仅接触边界（切点）。
     * 采用整数精确版 Liang-Barsky 线段裁剪：把两轴约束折算为参数 t∈[0,1] 的
     * 闭区间，全程用分数交叉相乘比较，不引入浮点误差；可行区间非空（含缩为一点）
     * 即判定相交。</p>
     */
    public static boolean segmentIntersects(Point p0, Point p1, Rect r) {
        // 快速排除：线段包围盒与矩形不相交
        int boxXMin = Math.min(p0.x, p1.x);
        int boxXMax = Math.max(p0.x, p1.x);
        int boxYMin = Math.min(p0.y, p1.y);
        int boxYMax = Math.max(p0.y, p1.y);
        if (boxXMax < r.xMin || boxXMin > r.xMax
                || boxYMax < r.yMin || boxYMin > r.yMax) {
            return false;
        }

        // t 可行区间，用正分母分数表示；初始 [0/1, 1/1]
        long loNum = 0;
        long loDen = 1;
        long hiNum = 1;
        long hiDen = 1;

        int dx = p1.x - p0.x;
        int dy = p1.y - p0.y;

        if (dx == 0) {
            if (p0.x < r.xMin || p0.x > r.xMax) {
                return false;
            }
        } else if (dx > 0) {
            // 进入边 x=xMin：t=(xMin-x0)/dx；离开边 x=xMax：t=(xMax-x0)/dx
            long enterNum = (long) r.xMin - p0.x;
            long exitNum = (long) r.xMax - p0.x;
            if (enterNum > 0 && greater(enterNum, dx, loNum, loDen)) {
                loNum = enterNum;
                loDen = dx;
            }
            if (exitNum < dx && less(exitNum, dx, hiNum, hiDen)) {
                hiNum = exitNum;
                hiDen = dx;
            }
        } else {
            // dx<0，分母取正：进入边 x=xMax
            long den = -dx;
            long enterNum = (long) p0.x - r.xMax;
            long exitNum = (long) p0.x - r.xMin;
            if (enterNum > 0 && greater(enterNum, den, loNum, loDen)) {
                loNum = enterNum;
                loDen = den;
            }
            if (exitNum < den && less(exitNum, den, hiNum, hiDen)) {
                hiNum = exitNum;
                hiDen = den;
            }
        }

        if (dy == 0) {
            if (p0.y < r.yMin || p0.y > r.yMax) {
                return false;
            }
        } else if (dy > 0) {
            long enterNum = (long) r.yMin - p0.y;
            long exitNum = (long) r.yMax - p0.y;
            if (enterNum > 0 && greater(enterNum, dy, loNum, loDen)) {
                loNum = enterNum;
                loDen = dy;
            }
            if (exitNum < dy && less(exitNum, dy, hiNum, hiDen)) {
                hiNum = exitNum;
                hiDen = dy;
            }
        } else {
            long den = -dy;
            long enterNum = (long) p0.y - r.yMax;
            long exitNum = (long) p0.y - r.yMin;
            if (enterNum > 0 && greater(enterNum, den, loNum, loDen)) {
                loNum = enterNum;
                loDen = den;
            }
            if (exitNum < den && less(exitNum, den, hiNum, hiDen)) {
                hiNum = exitNum;
                hiDen = den;
            }
        }

        // 闭区间：lo <= hi（含相等，即仅一点接触）即相交
        return loNum * hiDen <= hiNum * loDen;
    }

    /**
     * 折线（按顺序连接的点列）是否与闭矩形相交：
     * 任一端点位于矩形内（含边界）或任一线段与矩形相交（含穿越、仅接触边界）。
     */
    public static boolean polylineIntersects(List<Point> points, Rect r) {
        for (Point p : points) {
            if (pointInside(p, r)) {
                return true;
            }
        }
        for (int i = 1; i < points.size(); i++) {
            if (segmentIntersects(points.get(i - 1), points.get(i), r)) {
                return true;
            }
        }
        return false;
    }

    /** a/b > c/d，b、d 均为正数。 */
    private static boolean greater(long a, long b, long c, long d) {
        return a * d > c * b;
    }

    /** a/b < c/d，b、d 均为正数。 */
    private static boolean less(long a, long b, long c, long d) {
        return a * d < c * b;
    }
}
