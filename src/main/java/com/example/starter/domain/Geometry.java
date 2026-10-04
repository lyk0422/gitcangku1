package com.example.starter.domain;

import java.util.List;

/**
 * 二维平面几何判定。本题只模拟二维平面，不用于真实飞行。
 * 禁飞区为非退化轴对齐闭矩形，边界接触也算命中。
 */
public final class Geometry {

    private Geometry() {
    }

    /**
     * 判断一条线段（含端点）是否与轴对齐闭矩形相交。
     * 覆盖端点在外而线段穿越矩形内部、以及仅接触边界的情况。
     *
     * @param ax 端点 A 的 X（米）
     * @param ay 端点 A 的 Y（米）
     * @param bx 端点 B 的 X（米）
     * @param by 端点 B 的 Y（米）
     * @param xMin 矩形左边界（含）
     * @param yMin 矩形下边界（含）
     * @param xMax 矩形右边界（含）
     * @param yMax 矩形上边界（含）
     * @return true 表示线段有点位于矩形内或边界上
     */
    public static boolean segmentHitsRectangle(long ax, long ay, long bx, long by,
                                               int xMin, int yMin, int xMax, int yMax) {
        return segmentHitsRectangle(ax, ay, bx, by,
                (long) xMin, (long) yMin, (long) xMax, (long) yMax);
    }

    /**
     * 判断一条线段（含端点）是否与轴对齐闭矩形相交（长整型边界版本）。
     *
     * <p>供千分之一米精度坐标使用：坐标与矩形边界须在同一缩放单位下传入。
     * 算法与整数边界版本一致：端点在闭矩形内即命中；否则用 Liang-Barsky
     * 直线段裁剪判断线段参数区间 t&isin;[0,1] 与矩形四个半空间的交集是否非空。</p>
     *
     * @return true 表示线段有点位于矩形内或边界上
     */
    public static boolean segmentHitsRectangle(long ax, long ay, long bx, long by,
                                               long xMin, long yMin, long xMax, long yMax) {
        // 1. 任一端点在闭矩形内（含边界）即命中
        if (inRectangle(ax, ay, xMin, yMin, xMax, yMax)
                || inRectangle(bx, by, xMin, yMin, xMax, yMax)) {
            return true;
        }
        // 2. 端点均在矩形外：Liang-Barsky 裁剪，交集非空即意味着线段
        //    穿过矩形内部或边界，包括仅擦过边界（接触边界）的情况。
        long dx = bx - ax;
        long dy = by - ay;
        double t0 = 0.0d;
        double t1 = 1.0d;
        // p*x <= q 形式：左 x>=xMin、右 x<=xMax、下 y>=yMin、上 y<=yMax
        long[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - xMin, xMax - ax, ay - yMin, yMax - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0L) {
                // 线段与该边界平行且在外侧，则不可能相交
                if (q[i] < 0.0d) {
                    return false;
                }
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0L) {
                    if (r > t1) {
                        return false;
                    }
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    if (r < t0) {
                        return false;
                    }
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        return t0 <= t1;
    }

    /**
     * 判断有序航点组成的折线（含每条线段及端点）是否与闭矩形相交。
     *
     * @param points 按顺序连接的航点
     * @param xMin   矩形左边界（含）
     * @param yMin   矩形下边界（含）
     * @param xMax   矩形右边界（含）
     * @param yMax   矩形上边界（含）
     * @return true 表示相交、位于其中或仅接触边界
     */
    public static boolean polylineHitsRectangle(List<Point> points,
                                                int xMin, int yMin, int xMax, int yMax) {
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (segmentHitsRectangle(a.x(), a.y(), b.x(), b.y(), xMin, yMin, xMax, yMax)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断定时航线折线（千分之一米精度的平面坐标）是否与闭矩形禁飞区相交。
     * 矩形整数边界（米）放大 1000 倍后在同一精度下比较，边界接触算命中。
     *
     * @param points 按顺序连接的定时航点（坐标最多 3 位小数）
     * @param xMin   矩形左边界（含），米
     * @param yMin   矩形下边界（含），米
     * @param xMax   矩形右边界（含），米
     * @param yMax   矩形上边界（含），米
     * @return true 表示相交、位于其中或仅接触边界
     */
    public static boolean timedPolylineHitsRectangle(List<TimedPoint> points,
                                                     int xMin, int yMin, int xMax, int yMax) {
        long xMinMilli = xMin * 1000L;
        long yMinMilli = yMin * 1000L;
        long xMaxMilli = xMax * 1000L;
        long yMaxMilli = yMax * 1000L;
        for (int i = 0; i + 1 < points.size(); i++) {
            TimedPoint a = points.get(i);
            TimedPoint b = points.get(i + 1);
            if (segmentHitsRectangle(a.xMilli(), a.yMilli(), b.xMilli(), b.yMilli(),
                    xMinMilli, yMinMilli, xMaxMilli, yMaxMilli)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断点是否位于闭矩形内（含边界）。
     */
    public static boolean inRectangle(long x, long y,
                                      long xMin, long yMin, long xMax, long yMax) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
    }
}