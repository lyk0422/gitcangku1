package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 分辨率格点量化：仪器只能报告格点上的值。
 * k 为 (x-o)/s 的最近整数，正好半格时取偶数整数；量化值为 o+k×s。
 * 全程使用定点整数运算，不做任何先舍入再判断的近似。
 */
public final class GridQuantizer {

    private GridQuantizer() {
    }

    /**
     * 量化结果。
     *
     * @param k         格点整数 k（可负）
     * @param quantized 量化值 o+k×s，精确十进制
     */
    public record Result(BigInteger k, BigDecimal quantized) {
    }

    /**
     * 将精确值 x 量化到格点。x、o、s 均为有限小数（x 最多 12 位，o/s 最多 6 位），
     * 通过同乘 10^N 转为整数后做带余除法，半格比较精确无误差。
     */
    public static Result quantize(BigDecimal x, BigDecimal origin, BigDecimal step) {
        if (step.signum() <= 0) {
            throw new IllegalArgumentException("步长 s 必须为正");
        }
        BigDecimal shifted = x.subtract(origin);
        int scale = Math.max(shifted.scale(), step.scale());
        // 同乘 10^scale 转为整数（两者小数位均不超过 scale，乘法精确）
        BigInteger numerator = shifted.scaleByPowerOfTen(scale).toBigIntegerExact();
        BigInteger denominator = step.scaleByPowerOfTen(scale).toBigIntegerExact();

        // 向零取整的商与余数，随后按最近整数（半格取偶）修正
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        BigInteger k = qr[0];
        BigInteger twiceRemainder = qr[1].abs().shiftLeft(1);
        int cmp = twiceRemainder.compareTo(denominator);
        BigInteger direction = numerator.signum() >= 0 ? BigInteger.ONE : BigInteger.ONE.negate();
        if (cmp > 0 || (cmp == 0 && k.testBit(0))) {
            // 超过半格向远离零进一格；正好半格且商为奇数时进到偶数
            k = k.add(direction);
        }
        return new Result(k, origin.add(step.multiply(new BigDecimal(k))));
    }
}
