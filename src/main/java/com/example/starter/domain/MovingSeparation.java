package com.example.starter.domain;

/**
 * 两点匀速直线运动的间隔判定。本题只模拟二维平面，不用于真实飞行。
 *
 * <p>每条许可在有效区间 [startSecond, endSecond)（左闭右开，UTC epoch 整秒）内
 * 沿两航点连线匀速运动。两条运动的相对位置对时间是线性函数，距离平方是
 * 开口向上的二次函数，因此在共同有效区间内直接求解析最小值：
 * 不做端点比较、不做每秒离散采样，也不提前舍入。</p>
 */
public final class MovingSeparation {

    /** 最小间隔距离（米）。 */
    public static final double MIN_SEPARATION_METERS = 10.0;
    /** 最小间隔距离的平方（平方米）；恰好等于该值（恰好 10 米）合法。 */
    public static final double MIN_SEPARATION_SQUARED =
            MIN_SEPARATION_METERS * MIN_SEPARATION_METERS;

    private MovingSeparation() {
    }

    /**
     * 计算两条匀速直线运动在共同有效区间内的距离平方最小值。
     *
     * <p>端点坐标以毫米（米×1000）整数传入，精确保留最多 3 位小数；
     * 计算全程使用双精度浮点，不在中间步骤舍入。</p>
     *
     * @param axMilli1 运动 1 起点 X（毫米）
     * @param ayMilli1 运动 1 起点 Y（毫米）
     * @param bxMilli1 运动 1 终点 X（毫米）
     * @param byMilli1 运动 1 终点 Y（毫米）
     * @param start1   运动 1 有效起点（含），epoch 秒
     * @param end1     运动 1 有效终点（不含），epoch 秒
     * @param axMilli2 运动 2 起点 X（毫米）
     * @param ayMilli2 运动 2 起点 Y（毫米）
     * @param bxMilli2 运动 2 终点 X（毫米）
     * @param byMilli2 运动 2 终点 Y（毫米）
     * @param start2   运动 2 有效起点（含），epoch 秒
     * @param end2     运动 2 有效终点（不含），epoch 秒
     * @return 共同有效区间内距离平方的最小值（平方米）；区间不相交（含端点相接）返回
     *         {@link Double#POSITIVE_INFINITY}
     */
    public static double minDistanceSquared(
            long axMilli1, long ayMilli1, long bxMilli1, long byMilli1,
            long start1, long end1,
            long axMilli2, long ayMilli2, long bxMilli2, long byMilli2,
            long start2, long end2) {
        long lo = Math.max(start1, start2);
        long hi = Math.min(end1, end2);
        if (lo >= hi) {
            // 左闭右开区间无共同占用（端点相接也不算重叠）
            return Double.POSITIVE_INFINITY;
        }
        // 各运动速度（米/秒）
        double v1x = (bxMilli1 - axMilli1) / 1000.0 / (end1 - start1);
        double v1y = (byMilli1 - ayMilli1) / 1000.0 / (end1 - start1);
        double v2x = (bxMilli2 - axMilli2) / 1000.0 / (end2 - start2);
        double v2y = (byMilli2 - ayMilli2) / 1000.0 / (end2 - start2);
        // 相对位置 r(t) = r0 + w * t（米），t 为 epoch 秒
        double wx = v1x - v2x;
        double wy = v1y - v2y;
        double r0x = axMilli1 / 1000.0 - v1x * start1 - (axMilli2 / 1000.0 - v2x * start2);
        double r0y = ayMilli1 / 1000.0 - v1y * start1 - (ayMilli2 / 1000.0 - v2y * start2);
        double ww = wx * wx + wy * wy;
        double t;
        if (ww == 0.0) {
            // 相对静止：共同区间内距离恒定
            t = lo;
        } else {
            // 二次函数顶点，裁剪到共同区间
            t = -(r0x * wx + r0y * wy) / ww;
            if (t < lo) {
                t = lo;
            }
            if (t > hi) {
                t = hi;
            }
        }
        double dx = r0x + wx * t;
        double dy = r0y + wy * t;
        return dx * dx + dy * dy;
    }

    /**
     * 判断是否违反最小间隔：共同区间内距离平方严格小于 100 平方米为冲突；
     * 恰好 10 米（距离平方等于 100）合法。
     */
    public static boolean violatesSeparation(double distanceSquared) {
        return distanceSquared < MIN_SEPARATION_SQUARED;
    }
}