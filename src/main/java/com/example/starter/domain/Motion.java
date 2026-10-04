package com.example.starter.domain;

import java.util.OptionalDouble;

/**
 * 两点匀速直线运动的间隔计算。
 *
 * <p>许可在 [startSecond, endSecond)（左闭右开，UTC 整秒）内沿两点连线匀速运动；
 * 端点相接的两个区间没有共同占用。最小安全间隔为 10 米，恰好 10 米合法。</p>
 *
 * <p>共同有效区间内的最小距离用相对位置与相对速度求二次函数最小值，
 * 不做端点比较或离散每秒采样，计算过程不提前舍入。</p>
 */
public final class Motion {

    /** 最小安全间隔（米）。 */
    public static final double MIN_SEPARATION_METERS = 10.0d;

    /** 最小安全间隔的平方（平方米），用于与距离平方直接比较。 */
    public static final double MIN_SEPARATION_SQ = MIN_SEPARATION_METERS * MIN_SEPARATION_METERS;

    private Motion() {
    }

    /**
     * 计算两条匀速直线运动在共同有效区间内的最小距离平方（平方米）。
     *
     * <p>运动 1 在 [s1,e1) 内从 (ax,ay) 匀速到 (bx,by)，运动 2 在 [s2,e2) 内
     * 从 (cx,cy) 匀速到 (dx,dy)。共同区间为 [max(s1,s2), min(e1,e2))；
     * 若为空（含端点相接）则没有共同占用。</p>
     *
     * <p>共同区间右开：t1 时刻不被占用，但若极小值在 t1 处取得，开区间内的取值
     * 可任意接近该下确界，因此按闭区间端点评估与开区间下确界一致。</p>
     *
     * @return 共同区间内的最小距离平方；无共同占用时返回 {@link OptionalDouble#empty()}
     */
    public static OptionalDouble minDistanceSqOverOverlap(
            double ax, double ay, double bx, double by, long s1, long e1,
            double cx, double cy, double dx, double dy, long s2, long e2) {
        long t0 = Math.max(s1, s2);
        long t1 = Math.min(e1, e2);
        if (t0 >= t1) {
            return OptionalDouble.empty();
        }
        // 各自速度（米/秒）
        double v1x = (bx - ax) / (double) (e1 - s1);
        double v1y = (by - ay) / (double) (e1 - s1);
        double v2x = (dx - cx) / (double) (e2 - s2);
        double v2y = (dy - cy) / (double) (e2 - s2);
        // 相对运动：r(t) = r0 + rv * (t - t0)，t0 为共同区间起点
        double r0x = (ax + v1x * (t0 - s1)) - (cx + v2x * (t0 - s2));
        double r0y = (ay + v1y * (t0 - s1)) - (cy + v2y * (t0 - s2));
        double rvx = v1x - v2x;
        double rvy = v1y - v2y;
        double denom = rvx * rvx + rvy * rvy;
        double dt = 0.0d;
        if (denom > 0.0d) {
            // 二次函数 |r0 + rv*dt|^2 的无约束极小点
            dt = -(r0x * rvx + r0y * rvy) / denom;
            double maxDt = (double) (t1 - t0);
            if (dt < 0.0d) {
                dt = 0.0d;
            } else if (dt > maxDt) {
                dt = maxDt;
            }
        }
        double rx = r0x + rvx * dt;
        double ry = r0y + rvy * dt;
        return OptionalDouble.of(rx * rx + ry * ry);
    }

    /**
     * 判断两条运动是否违反最小安全间隔：共同区间内的最小距离平方小于 100
     * （即距离不足 10 米）时冲突；恰好 10 米合法；无共同占用不冲突。
     */
    public static boolean conflicts(
            double ax, double ay, double bx, double by, long s1, long e1,
            double cx, double cy, double dx, double dy, long s2, long e2) {
        OptionalDouble minSq = minDistanceSqOverOverlap(ax, ay, bx, by, s1, e1,
                cx, cy, dx, dy, s2, e2);
        return minSq.isPresent() && minSq.getAsDouble() < MIN_SEPARATION_SQ;
    }
}