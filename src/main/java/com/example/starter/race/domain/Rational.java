package com.example.starter.race.domain;

import java.math.BigInteger;

/**
 * 精确有理数：分子分母均为任意精度整数，分母恒为正且已约分。
 * 用于保存追逐赛最早交会时刻，禁止任何浮点舍入或取整。
 *
 * @param num 分子（可为负、零）
 * @param den 分母（恒为正整数）
 */
public record Rational(BigInteger num, BigInteger den) {

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    public Rational {
        if (den.signum() == 0) {
            throw new ArithmeticException("分母不能为0");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger gcd = num.gcd(den);
        if (!BigInteger.ONE.equals(gcd)) {
            num = num.divide(gcd);
            den = den.divide(gcd);
        }
    }

    public static Rational of(long value) {
        return new Rational(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static Rational of(BigInteger num, BigInteger den) {
        return new Rational(num, den);
    }

    public Rational add(Rational other) {
        return new Rational(
                num.multiply(other.den).add(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Rational subtract(Rational other) {
        return new Rational(
                num.multiply(other.den).subtract(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Rational multiply(Rational other) {
        return new Rational(num.multiply(other.num), den.multiply(other.den));
    }

    public Rational divide(Rational other) {
        if (other.num.signum() == 0) {
            throw new ArithmeticException("除以零");
        }
        return new Rational(num.multiply(other.den), den.multiply(other.num));
    }

    /** 与另一有理数比较大小。 */
    public int compareTo(Rational other) {
        return num.multiply(other.den).compareTo(other.num.multiply(den));
    }

    public boolean isNegative() {
        return num.signum() < 0;
    }

    public boolean isZero() {
        return num.signum() == 0;
    }
}
