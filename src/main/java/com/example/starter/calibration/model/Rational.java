package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 精确分数（有理数）。用于曲线插值结果的精确表示与比较：
 * 合格判定基于分数本身，不先转为有限小数。内部始终约分且分母为正。
 *
 * @param num 分子（可正可负可为零）
 * @param den 分母（正整数）
 */
public record Rational(BigInteger num, BigInteger den) {

    private static final BigInteger FIVE = BigInteger.valueOf(5);

    public Rational {
        if (den.signum() == 0) {
            throw new ArithmeticException("分母不能为零");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger gcd = num.gcd(den);
        num = num.divide(gcd);
        den = den.divide(gcd);
    }

    /** 由十进制小数构造精确分数。 */
    public static Rational of(BigDecimal value) {
        BigInteger unscaled = value.unscaledValue();
        int scale = value.scale();
        if (scale <= 0) {
            return new Rational(unscaled.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }
        return new Rational(unscaled, BigInteger.TEN.pow(scale));
    }

    public Rational add(Rational other) {
        return new Rational(num.multiply(other.den).add(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Rational subtract(Rational other) {
        return new Rational(num.multiply(other.den).subtract(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Rational multiply(Rational other) {
        return new Rational(num.multiply(other.num), den.multiply(other.den));
    }

    public Rational divide(Rational other) {
        return new Rational(num.multiply(other.den), den.multiply(other.num));
    }

    /** 精确比较：交叉相乘，无任何舍入。 */
    public int compareTo(Rational other) {
        return num.multiply(other.den).compareTo(other.num.multiply(den));
    }

    /** 显示值：HALF_UP 保留指定小数位；仅用于展示，不参与合格判定。 */
    public BigDecimal toDisplay(int scale) {
        return new BigDecimal(num).divide(new BigDecimal(den), scale, RoundingMode.HALF_UP);
    }

    /**
     * 精确值字符串：可表示为有限小数时输出十进制（去掉多余尾零），
     * 否则输出 "分子/分母" 分数形式（如 1/3）。
     */
    public String toExactString() {
        if (den.equals(BigInteger.ONE)) {
            return num.toString();
        }
        BigInteger rest = den;
        int twos = 0;
        while (rest.mod(BigInteger.TWO).signum() == 0) {
            rest = rest.divide(BigInteger.TWO);
            twos++;
        }
        int fives = 0;
        while (rest.mod(FIVE).signum() == 0) {
            rest = rest.divide(FIVE);
            fives++;
        }
        if (!rest.equals(BigInteger.ONE)) {
            return num + "/" + den;
        }
        int scale = Math.max(twos, fives);
        BigInteger scaled = num.multiply(BigInteger.TWO.pow(scale - twos))
                .multiply(FIVE.pow(scale - fives));
        BigDecimal decimal = new BigDecimal(scaled, scale).stripTrailingZeros();
        return decimal.toPlainString();
    }
}
