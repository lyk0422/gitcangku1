package com.example.starter.race.domain;

import java.math.BigInteger;

/**
 * 让分赛精确分数工具：全程使用整数与 BigInteger，不引入浮点。
 *
 * <p>有效成绩为精确分数 (原始毫秒×p + 生效加时×q) / q，排名按交叉相乘精确比较；
 * 展示值为该分数 HALF_UP 舍入到整数毫秒的结果，展示相同不代表精确相等。
 */
public final class HandicapScore {

    private static final BigInteger TWO = BigInteger.valueOf(2L);

    private HandicapScore() {
    }

    /**
     * 将系数 p/q 约分为最简形式；等比例系数（如 2/6 与 1/3）约分后相同，
     * 用于幂等参数比较与存储。
     *
     * @return 长度为2的数组：[约分后p, 约分后q]
     */
    public static int[] reduceFactor(int p, int q) {
        int gcd = gcd(p, q);
        return new int[]{p / gcd, q / gcd};
    }

    /**
     * 计算精确有效成绩并约分：分子=原始毫秒×p+生效加时×q，分母=q。
     *
     * @return 长度为2的数组：[约分后分子, 约分后分母]，均为正数
     */
    public static long[] score(long finishMs, long penaltyMs, int p, int q) {
        BigInteger numerator = BigInteger.valueOf(finishMs).multiply(BigInteger.valueOf(p))
                .add(BigInteger.valueOf(penaltyMs).multiply(BigInteger.valueOf(q)));
        BigInteger denominator = BigInteger.valueOf(q);
        BigInteger gcd = numerator.gcd(denominator);
        return new long[]{
                numerator.divide(gcd).longValueExact(),
                denominator.divide(gcd).longValueExact()};
    }

    /**
     * 精确比较两个分数 num1/den1 与 num2/den2（交叉相乘，BigInteger 防溢出）。
     *
     * @return 负数/0/正数，分别表示小于/等于/大于
     */
    public static int compare(long num1, long den1, long num2, long den2) {
        return BigInteger.valueOf(num1).multiply(BigInteger.valueOf(den2))
                .compareTo(BigInteger.valueOf(num2).multiply(BigInteger.valueOf(den1)));
    }

    /**
     * 展示值：精确分数 HALF_UP 舍入到整数毫秒（分数恒为正）。
     */
    public static long displayMs(long numerator, long denominator) {
        return BigInteger.valueOf(numerator).multiply(TWO)
                .add(BigInteger.valueOf(denominator))
                .divide(BigInteger.valueOf(denominator).multiply(TWO))
                .longValueExact();
    }

    private static int gcd(int a, int b) {
        int x = a;
        int y = b;
        while (y != 0) {
            int r = x % y;
            x = y;
            y = r;
        }
        return x;
    }
}
