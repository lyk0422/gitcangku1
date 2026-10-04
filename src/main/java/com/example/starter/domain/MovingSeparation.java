package com.example.starter.domain;

/**
 * 两点匀速航线的运动间隔计算。
 *
 * <p>许可在有效区间 [startEpochSec, endEpochSec)（左闭右开，UTC 整秒）内
 * 沿起点到终点的直线匀速运动。两条许可的间隔判定只发生在共同有效区间
 * [max(startA,startB), min(endA,endB)) 上；端点相接（前一终点等于后一起点）
 * 没有共同占用。</p>
 *
 * <p>最小距离用相对位置与相对速度解析求解距离平方的最小值，
 * 不比较端点、不做每秒离散采样、不提前舍入；恰好等于最小间隔判为合法。</p>
 */
public final class MovingSeparation {

    /** 任意两许可在共同有效区间内必须保持的最小距离，单位米。 */
    public static final double MIN_SEPARATION_METERS = 10.0;

    /** 最小距离的平方（10 米），用于直接比较距离平方，避免开方舍入。 */
    public static final double MIN_SEPARATION_SQUARED = MIN_SEPARATION_METERS * MIN_SEPARATION_METERS;

    private MovingSeparation() {
    }

    /**
     * 计算两条两点匀速航线在共同有效区间内的最小距离平方。
     *
     * @param ax0 航线 A 起点 X（米） @param ay0 航线 A 起点 Y（米）
     * @param ax1 航线 A 终点 X（米） @param ay1 航线 A 终点 Y（米）
     * @param as  航线 A 有效起点（含，UTC epoch 秒） @param ae 航线 A 有效终点（不含）
     * @param bx0 航线 B 起点 X（米） @param by0 航线 B 起点 Y（米）
     * @param bx1 航线 B 终点 X（米） @param by1 航线 B 终点 Y（米）
     * @param bs  航线 B 有效起点（含，UTC epoch 秒） @param be 航线 B 有效终点（不含）
     * @return 共同有效区间内距离平方的最小值（平方米）；区间不相交（含端点相接）返回 null
     */
    public static Double minDistanceSquared(
            double ax0, double ay0, double ax1, double ay1, long as, long ae,
            double bx0, double by0, double bx1, double by1, long bs, long be) {
        long t0 = Math.max(as, bs);
        long t1 = Math.min(ae, be);
        // 左闭右开：t0 >= t1 时共同区间为空，端点相接无共同占用
        if (t0 >= t1) {
            return null;
        }
        // 各自匀速速度（米/秒），分母为正（起点 < 终点由调用方保证）
        double avx = (ax1 - ax0) / (ae - as);
        double avy = (ay1 - ay0) / (ae - as);
        double bvx = (bx1 - bx0) / (be - bs);
        double bvy = (by1 - by0) / (be - bs);
        // 相对运动：r(u) = r0 + rv * u，u 为相对 t0 的秒数，u ∈ [0, t1-t0]
        double r0x = (ax0 + avx * (t0 - as)) - (bx0 + bvx * (t0 - bs));
        double r0y = (ay0 + avy * (t0 - as)) - (by0 + bvy * (t0 - bs));
        double rvx = avx - bvx;
        double rvy = avy - bvy;
        double duration = t1 - t0;
        // |r(u)|^2 的无约束极小点 u* = -(r0·rv)/(rv·rv)，再夹取到 [0, duration]
        double vv = rvx * rvx + rvy * rvy;
        double uStar = vv == 0.0d ? 0.0d : -(r0x * rvx + r0y * rvy) / vv;
        double u = Math.min(Math.max(uStar, 0.0d), duration);
        double dx = r0x + rvx * u;
        double dy = r0y + rvy * u;
        return dx * dx + dy * dy;
    }

    /**
     * 判断共同有效区间内的最小距离是否满足最小间隔（恰好 10 米合法）。
     *
     * @return true 表示安全（无共同区间或最小距离平方 >= 100）
     */
    public static boolean isSafe(
            double ax0, double ay0, double ax1, double ay1, long as, long ae,
            double bx0, double by0, double bx1, double by1, long bs, long be) {
        Double minSquared = minDistanceSquared(ax0, ay0, ax1, ay1, as, ae,
                bx0, by0, bx1, by1, bs, be);
        return minSquared == null || minSquared >= MIN_SEPARATION_SQUARED;
    }
}