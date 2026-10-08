package com.example.starter.domain;

import java.math.BigInteger;

/**
 * 线段参数 t∈[0,1] 的精确分数表示，分母恒正、不可变。
 * 用于切分线段与环边的交点，保证开区间中点归类不引入浮点误差。
 */
final class Fraction implements Comparable<Fraction> {

    static final Fraction ZERO = new Fraction(0L, 1L);
    static final Fraction ONE = new Fraction(1L, 1L);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private Fraction(long n, long d) {
        this(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    private Fraction(BigInteger n, BigInteger d) {
        if (d.signum() == 0) {
            throw new IllegalArgumentException("分数分母不能为 0");
        }
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        BigInteger g = n.gcd(d);
        if (g.signum() != 0) {
            n = n.divide(g);
            d = d.divide(g);
        }
        this.numerator = n;
        this.denominator = d;
    }

    /** 由 long 构造（乘积已在调用方算好，范围可能超过 long，故先转 BigInteger）。 */
    static Fraction of(long numerator, long denominator) {
        if (denominator == 0L) {
            // 理论不可达：零长度线段已提前处理
            return ZERO;
        }
        return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /** 与另一参数的算术中点 (this + other)/2。 */
    Fraction midpoint(Fraction other) {
        BigInteger num = numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator));
        BigInteger den = denominator.multiply(other.denominator).multiply(BigInteger.TWO);
        return new Fraction(num, den);
    }

    BigInteger numerator() {
        return numerator;
    }

    BigInteger denominator() {
        return denominator;
    }

    @Override
    public int compareTo(Fraction o) {
        return numerator.multiply(o.denominator).compareTo(o.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Fraction other)) {
            return false;
        }
        return numerator.equals(other.numerator) && denominator.equals(other.denominator);
    }

    @Override
    public int hashCode() {
        return numerator.hashCode() * 31 + denominator.hashCode();
    }
}
