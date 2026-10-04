package com.example.starter.domain;

import java.math.BigInteger;

/**
 * 航段速度判定：速度 = 欧氏距离 / 秒数，闭区间允许 [1, 50] 米每秒。
 *
 * <p>比较在平方域进行（平方距离 对 速度阈值与时间的平方），全程整数精确运算，
 * 不做开方，避免先开方再舍入把 50.0001 米每秒误判为 50.00 放行。</p>
 */
public final class SegmentSpeed {

    /** 最低速度 1 米每秒（含）。 */
    public static final long MIN_SPEED = 1L;
    /** 最高速度 50 米每秒（含）。 */
    public static final long MAX_SPEED = 50L;

    private SegmentSpeed() {
    }

    /**
     * 判断航段速度是否落在闭区间 [1, 50] 米每秒。
     *
     * @param dxMilli 两端点 X 差，千分之一米
     * @param dyMilli 两端点 Y 差，千分之一米
     * @param seconds 航段历时，秒（必须为正整数）
     * @return true 表示 1 &lt;= 速度 &lt;= 50（含边界）；同位置两点速度为 0，返回 false
     */
    public static boolean withinLimits(long dxMilli, long dyMilli, long seconds) {
        BigInteger distSq = squaredDistanceMilli(dxMilli, dyMilli);
        BigInteger t = BigInteger.valueOf(seconds);
        // 速度 >= 1  <=>  d^2 >= (1000*t)^2 ；速度 <= 50  <=>  d^2 <= (50000*t)^2
        BigInteger minSq = t.multiply(BigInteger.valueOf(MIN_SPEED * 1000L)).pow(2);
        BigInteger maxSq = t.multiply(BigInteger.valueOf(MAX_SPEED * 1000L)).pow(2);
        return distSq.compareTo(minSq) >= 0 && distSq.compareTo(maxSq) <= 0;
    }

    /**
     * 计算航段速度的未舍入值（米每秒），仅用于错误响应展示，不参与判定。
     */
    public static double speedMetersPerSecond(long dxMilli, long dyMilli, long seconds) {
        double distMilli = Math.sqrt((double) dxMilli * dxMilli + (double) dyMilli * dyMilli);
        return distMilli / 1000.0d / seconds;
    }

    /** 平方距离（单位：千分之一米的平方），BigInteger 避免大时间跨度下阈值平方溢出。 */
    private static BigInteger squaredDistanceMilli(long dxMilli, long dyMilli) {
        BigInteger dx = BigInteger.valueOf(dxMilli);
        BigInteger dy = BigInteger.valueOf(dyMilli);
        return dx.multiply(dx).add(dy.multiply(dy));
    }
}