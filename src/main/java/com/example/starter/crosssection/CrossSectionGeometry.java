package com.example.starter.crosssection;

import java.math.BigInteger;
import java.util.List;

/**
 * 断面水域面积几何积分：相邻地形点线性连接，仅积分水面 H 以下的正深度部分，单位平方毫米。
 *
 * <p>跨水面交点必须精确求取：端点一湿一干（或恰在水面）时，按线性比例求交点横坐标，
 * 水深在交点处为零，截出三角形而不是整段丢弃，也不允许把负深度与正面积抵消。
 * 全部中间量（交点比例、三角面积）用 {@link Fraction} 精确分数保存，无浮点参与。
 *
 * <p>积分区间仅覆盖首末点之间，断面两端之外一律不外推。
 */
public final class CrossSectionGeometry {

    private CrossSectionGeometry() {
    }

    /**
     * 计算正深度面积（精确分数，平方毫米）。
     *
     * @param points     地形点，必须至少 2 个且横向坐标严格递增
     * @param waterLevel 整数水面高程 H（毫米）
     */
    public static Fraction positiveArea(List<CrossSectionPoint> points, int waterLevel) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("at least two points are required");
        }
        Fraction area = Fraction.ZERO;
        for (int i = 0; i + 1 < points.size(); i++) {
            CrossSectionPoint left = points.get(i);
            CrossSectionPoint right = points.get(i + 1);
            int dx = right.x() - left.x();
            if (dx <= 0) {
                throw new IllegalArgumentException("x coordinates must be strictly increasing");
            }
            area = area.add(segmentArea(left.z(), right.z(), dx, waterLevel));
        }
        return area;
    }

    /**
     * 单段正深度面积。端点相对水面的位置分为四种情形：
     * 两端都不高于水面按正水深梯形积分；两端都不低于水面面积为零；
     * 一湿一干时精确求交点，截出三角形（不整段丢弃、不负正抵消）。
     */
    private static Fraction segmentArea(int z1, int z2, int dx, int h) {
        BigInteger width = BigInteger.valueOf(dx);
        if (z1 <= h && z2 <= h) {
            // d1,d2 均为非负水深；线性地形在整段内不会高出水面
            BigInteger d1 = BigInteger.valueOf((long) h - z1);
            BigInteger d2 = BigInteger.valueOf((long) h - z2);
            return Fraction.of(width.multiply(d1.add(d2)), BigInteger.TWO);
        }
        if (z1 >= h && z2 >= h) {
            // 整段不低于水面；恰有端点等于水面时水深为零，仍无正面积
            return Fraction.ZERO;
        }
        // 一湿一干：a 为湿端正水深，b 为干端高出水面的高度，交点比例为 a/(a+b)
        BigInteger a = BigInteger.valueOf(Math.abs((long) h - (z1 < h ? z1 : z2)));
        BigInteger b = BigInteger.valueOf(Math.abs((z1 > h ? z1 : z2) - (long) h));
        // 三角形面积 = 宽 * a/(a+b) * a / 2 = 宽 * a^2 / (2(a+b))
        return Fraction.of(width.multiply(a).multiply(a),
                BigInteger.TWO.multiply(a.add(b)));
    }
}
