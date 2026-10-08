package com.example.starter.crosssection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 精确分数：跨水面交点产生的坐标与面积必须以分数精确保存，禁止用整段梯形近似或浮点累计。
 *
 * <p>内部以 {@link BigInteger} 分子分母表示，分母恒为正并约分至互质；
 * 展示时再按 HALF_UP 保留三位小数。
 *
 * @param numerator   分子（可为负或零）
 * @param denominator 分母（恒为正）
 */
public record Fraction(BigInteger numerator, BigInteger denominator) {

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);

    public Fraction {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("fraction denominator must not be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.abs().gcd(denominator);
        if (gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    /**
     * 由整数构造分数（分母 1）。
     */
    public static Fraction of(long value) {
        return new Fraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    /**
     * 构造 n/d。
     */
    public static Fraction of(BigInteger numerator, BigInteger denominator) {
        return new Fraction(numerator, denominator);
    }

    /**
     * 加法：a/b + c/d = (ad + cb)/(bd)，构造时约分。
     */
    public Fraction add(Fraction other) {
        return new Fraction(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    /**
     * 展示值：按 HALF_UP 保留三位小数的十进制原文；面积非负，不会出现负零。
     */
    public String toScaledPlainString(int scale) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
