package com.example.starter.domain;

import java.math.BigInteger;
import java.util.List;

/**
 * 二维平面几何判定。本题只模拟二维平面，不用于真实飞行。
 * 禁飞区为非退化轴对齐闭矩形，边界接触也算命中。
 * 圆形障碍按水平包络判定：线段到圆心最小距离不大于两半径之和即命中，
 * 接触也算命中；全部比较使用精确平方距离，不开根取整。
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
        // 1. 任一端点在闭矩形内（含边界）即命中
        if (inRectangle(ax, ay, xMin, yMin, xMax, yMax)
                || inRectangle(bx, by, xMin, yMin, xMax, yMax)) {
            return true;
        }
        // 2. 端点均在矩形外：用 Liang-Barsky 直线段裁剪算法，
        //    判断线段参数区间 t∈[0,1] 是否与矩形的四个半空间交集非空。
        //    端点已在矩形外，交集非空即意味着线段穿过矩形内部或边界，
        //    包括仅擦过边界（接触边界）的情况。
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
     * 判断点是否位于闭矩形内（含边界）。
     */
    public static boolean inRectangle(long x, long y,
                                      int xMin, int yMin, int xMax, int yMax) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
    }

    /**
     * 判断线段（含端点与内部投影）到圆心的最小距离是否不大于 radiusSum。
     *
     * <p>用于水平包络审查：radiusSum 为障碍半径与航线包络半径之和，
     * 距离不大于该和（含恰好接触）即命中。比较全程使用精确平方距离：
     * 端点情形用 long 平方距离，内部投影情形用向量叉积比较
     * cross² ≤ radiusSum² · len²，以 BigInteger 承载避免 long 溢出，
     * 不对任何开根结果取整。</p>
     *
     * @param ax        端点 A 的 X（米）
     * @param ay        端点 A 的 Y（米）
     * @param bx        端点 B 的 X（米）
     * @param by        端点 B 的 Y（米）
     * @param cx        圆心 X（米）
     * @param cy        圆心 Y（米）
     * @param radiusSum 两半径之和（米），非负
     * @return true 表示线段与半径为 radiusSum 的圆相交或接触
     */
    public static boolean segmentWithinCircle(long ax, long ay, long bx, long by,
                                              long cx, long cy, long radiusSum) {
        long radius2 = radiusSum * radiusSum;
        // 1. 任一端点到圆心的平方距离不超过半径平方即命中（含接触）
        long dax = cx - ax;
        long day = cy - ay;
        if (dax * dax + day * day <= radius2) {
            return true;
        }
        long dbx = cx - bx;
        long dby = cy - by;
        if (dbx * dbx + dby * dby <= radius2) {
            return true;
        }
        // 2. 圆心在线段上的投影落在线段内部时，还需比较垂距
        long dx = bx - ax;
        long dy = by - ay;
        long len2 = dx * dx + dy * dy;
        if (len2 == 0L) {
            // 零长度线段：两个端点（同一点）已在上面判定
            return false;
        }
        long tNum = dax * dx + day * dy;
        if (tNum <= 0L || tNum >= len2) {
            // 最近点是端点，已在上面判定
            return false;
        }
        // 内部投影：垂距平方 = cross² / len²，精确比较 cross² <= radiusSum² · len²
        long cross = dax * dy - day * dx;
        BigInteger cross2 = BigInteger.valueOf(cross).multiply(BigInteger.valueOf(cross));
        BigInteger limit = BigInteger.valueOf(radius2).multiply(BigInteger.valueOf(len2));
        return cross2.compareTo(limit) <= 0;
    }
}
