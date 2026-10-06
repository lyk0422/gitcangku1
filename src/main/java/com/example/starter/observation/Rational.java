package com.example.starter.observation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 精确有理数：分子/分母（任意精度整数，分母恒正、已约分）。
 * 密度估计全程以有理数计算，仅在展示时按 HALF_UP 保留三位小数，舍入值不回灌计算。
 *
 * @param numerator   分子（十进制整数）
 * @param denominator 分母（正整数）
 */
record Rational(BigInteger numerator, BigInteger denominator) {

    static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    /**
     * 规范化：分母恒正并按最大公约数约分。
     */
    Rational {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("rational denominator must not be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if (gcd.compareTo(BigInteger.ONE) > 0) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /**
     * 乘以正整数因子（如层总体面积）。
     */
    Rational multiply(long factor) {
        return new Rational(numerator.multiply(BigInteger.valueOf(factor)), denominator);
    }

    Rational add(Rational other) {
        return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    /**
     * 精确值的规范文本：已约分；分母为 1 时只输出整数。
     */
    String toExactString() {
        return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
    }

    /**
     * 展示值：精确值按 HALF_UP 保留 scale 位小数；仅用于展示，不参与计算。
     */
    String toDisplay(int scale) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
