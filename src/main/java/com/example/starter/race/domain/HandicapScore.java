package com.example.starter.race.domain;

import java.math.BigInteger;

/**
 * 让分模式下的有效成绩：精确分数 num/den（毫秒，已约分，恒为正）。
 *
 * <p>有效成绩=原始毫秒×p/q+未撤销罚时，即 (finishMs×p+penaltyMs×q)/q。
 * 排名比较使用精确分数交叉相乘，不使用浮点，也不得以舍入后的展示值排序；
 * 展示值由 {@link #displayMs()} 按 HALF_UP 舍入到整数毫秒，展示相同不代表并列。
 *
 * @param num 约分后的精确分子（毫秒量纲）
 * @param den 约分后的精确分母（正数）
 */
public record HandicapScore(BigInteger num, BigInteger den) implements Comparable<HandicapScore> {

    public HandicapScore {
        if (num == null || den == null || den.signum() <= 0 || num.signum() < 0) {
            throw new IllegalArgumentException("非法分数: num=" + num + ", den=" + den);
        }
    }

    /** 由原始耗时、罚时与系数构造精确分数并约分。 */
    public static HandicapScore of(long finishMs, long penaltyMs, int p, int q) {
        BigInteger numerator = BigInteger.valueOf(finishMs)
                .multiply(BigInteger.valueOf(p))
                .add(BigInteger.valueOf(penaltyMs).multiply(BigInteger.valueOf(q)));
        BigInteger denominator = BigInteger.valueOf(q);
        BigInteger gcd = numerator.gcd(denominator);
        return new HandicapScore(numerator.divide(gcd), denominator.divide(gcd));
    }

    /** 展示值：精确分数按 HALF_UP 舍入到整数毫秒（仅用于展示，不参与排名）。 */
    public long displayMs() {
        BigInteger two = BigInteger.valueOf(2);
        return num.multiply(two).add(den).divide(den.multiply(two)).longValueExact();
    }

    @Override
    public int compareTo(HandicapScore other) {
        return num.multiply(other.den).compareTo(other.num.multiply(den));
    }
}
