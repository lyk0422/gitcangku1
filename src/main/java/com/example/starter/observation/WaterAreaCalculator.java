package com.example.starter.observation;

import java.math.BigInteger;
import java.util.List;

/**
 * 断面水域面积计算：相邻点线性连接，只积分水面高程 H 高于地形的正深度部分，
 * 跨水面交点精确求取（不整段丢弃、不用负深度抵消正面积），断面两端之外不外推。
 * 全程使用 BigInteger 精确分数运算，结果以最简分数保存。
 */
public final class WaterAreaCalculator {

    private static final BigInteger TWO = BigInteger.valueOf(2);

    private WaterAreaCalculator() {
    }

    /**
     * 断面点：横向坐标与地形高程，单位毫米。
     */
    public record Point(long lateralMm, long elevationMm) {
    }

    /**
     * 精确分数（最简），分母恒为正。
     *
     * @param numerator   分子
     * @param denominator 分母（正）
     */
    public record Fraction(BigInteger numerator, BigInteger denominator) {

        /**
         * 构造并约分为最简分数；分母为负时移号到分子，分母为零抛异常。
         */
        public Fraction {
            if (denominator.signum() == 0) {
                throw new ArithmeticException("fraction denominator must not be zero");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }

        /**
         * 精确加法，结果自动约分。
         */
        public Fraction add(Fraction other) {
            BigInteger numerator = this.numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(this.denominator));
            BigInteger denominator = this.denominator.multiply(other.denominator);
            return new Fraction(numerator, denominator);
        }
    }

    /**
     * 计算水域面积（平方毫米）的精确分数。
     *
     * @param points       断面点列，横向坐标严格递增（调用方保证）
     * @param waterLevelMm 整数水面高程 H，单位毫米
     * @return 精确面积最简分数；无正深度时为零
     */
    public static Fraction computeArea(List<Point> points, long waterLevelMm) {
        BigInteger waterLevel = BigInteger.valueOf(waterLevelMm);
        Fraction total = new Fraction(BigInteger.ZERO, BigInteger.ONE);
        for (int i = 0; i + 1 < points.size(); i++) {
            Point left = points.get(i);
            Point right = points.get(i + 1);
            BigInteger depthLeft = waterLevel.subtract(BigInteger.valueOf(left.elevationMm()));
            BigInteger depthRight = waterLevel.subtract(BigInteger.valueOf(right.elevationMm()));
            BigInteger width = BigInteger.valueOf(right.lateralMm() - left.lateralMm());
            if (depthLeft.signum() <= 0 && depthRight.signum() <= 0) {
                // 整段无正深度（含贴水端点）：不积分，不向断面两端之外外推
                continue;
            }
            if (depthLeft.signum() >= 0 && depthRight.signum() >= 0) {
                // 整段正深度：梯形面积 = (d0 + d1) * w / 2
                total = total.add(new Fraction(depthLeft.add(depthRight).multiply(width), TWO));
                continue;
            }
            // 跨水面段：精确求交点，仅积分正深度侧三角形面积 = d^2 * w / (2 * (d_pos - d_neg))
            if (depthLeft.signum() > 0) {
                total = total.add(new Fraction(depthLeft.multiply(depthLeft).multiply(width),
                        TWO.multiply(depthLeft.subtract(depthRight))));
            } else {
                total = total.add(new Fraction(depthRight.multiply(depthRight).multiply(width),
                        TWO.multiply(depthRight.subtract(depthLeft))));
            }
        }
        return total;
    }
}
