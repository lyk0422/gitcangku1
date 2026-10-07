package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 精确有理数（不可约分数）。DRIFT 模式的校正值以此保存与判定，
 * 避免线性插值产生无限循环小数时的精度丢失；展示时再按 HALF_UP 舍入。
 */
public record Rational(BigInteger numerator, BigInteger denominator) {

    /**
     * 构造不可约分数；分母必须为正，自动约分并规范符号。
     */
    public Rational {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("分母不能为零");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }

    /** 由整数值构造。 */
    public static Rational of(BigInteger value) {
        return new Rational(value, BigInteger.ONE);
    }

    /** 由精确十进制构造（不丢失精度）。 */
    public static Rational of(BigDecimal value) {
        BigInteger unscaled = value.unscaledValue();
        BigInteger den = BigInteger.TEN.pow(value.scale());
        return new Rational(unscaled, den);
    }

    /** 加法。 */
    public Rational add(Rational other) {
        BigInteger num = numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator));
        BigInteger den = denominator.multiply(other.denominator);
        return new Rational(num, den);
    }

    /** 减法。 */
    public Rational subtract(Rational other) {
        return add(new Rational(other.numerator.negate(), other.denominator));
    }

    /** 乘法。 */
    public Rational multiply(Rational other) {
        return new Rational(numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
    }

    /** 除以非零分数。 */
    public Rational divide(Rational other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        return new Rational(numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
    }

    /** 比较大小。 */
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }

    /** 是否落在 [lower, upper] 闭区间内（用于合格判定，未舍入）。 */
    public boolean within(BigDecimal lower, BigDecimal upper) {
        return compareTo(Rational.of(lower)) >= 0 && compareTo(Rational.of(upper)) <= 0;
    }

    /** 展示值：HALF_UP 保留 4 位小数；仅用于展示，不参与判定。 */
    public BigDecimal display(int scale) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP);
    }

    /**
     * 精确字符串：可整除或有限小数时输出十进制（去掉多余尾零），
     * 无限循环小数时输出 "分子/分母" 分数形式，保证不丢精度。
     */
    public String toExactString() {
        try {
            BigDecimal exact = new BigDecimal(numerator).divide(new BigDecimal(denominator));
            BigDecimal stripped = exact.stripTrailingZeros();
            if (stripped.compareTo(BigDecimal.ZERO) == 0) {
                return "0";
            }
            return stripped.toPlainString();
        } catch (ArithmeticException nonTerminating) {
            return numerator + "/" + denominator;
        }
    }
}
