package com.example.starter.calibration.service;

import java.math.BigDecimal;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.Branch;

/**
 * 二次证书函数 y = a×x² + b×x + c 的精确计算与分支内反解。
 * 全部判定使用 BigDecimal 精确比较；数值反解仅用于展示，并给出宽度不大于 0.000001 的夹逼区间。
 */
public final class Quadratic {

    /** 反解夹逼区间最大宽度。 */
    public static final BigDecimal BRACKET_WIDTH = new BigDecimal("0.000001");

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private Quadratic() {
    }

    /** 精确求值 f(x) = a×x² + b×x + c。 */
    public static BigDecimal eval(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal x) {
        return a.multiply(x).multiply(x).add(b.multiply(x)).add(c);
    }

    /** 精确求导数值 f′(x) = 2ax + b。 */
    public static BigDecimal derivative(BigDecimal a, BigDecimal b, BigDecimal x) {
        return a.multiply(TWO).multiply(x).add(b);
    }

    /**
     * 判定分支单调方向。导数为线性函数，端点导数值严格同号即全区间严格同号；
     * 任一端点为 0 或两端异号（非单调）抛出 400。
     */
    public static Branch requireMonotonic(BigDecimal a, BigDecimal b, BigDecimal lo, BigDecimal hi) {
        BigDecimal dLo = derivative(a, b, lo);
        BigDecimal dHi = derivative(a, b, hi);
        if (dLo.signum() == 0 || dHi.signum() == 0 || dLo.signum() != dHi.signum()) {
            throw ApiException.badRequest("二次证书导数在输入闭区间内非严格同号（非单调）");
        }
        return dLo.signum() > 0 ? Branch.INCREASING : Branch.DECREASING;
    }

    /**
     * 在单调分支 [lo, hi] 内二分反解 f(x) = y，返回宽度不大于 0.000001 的夹逼区间 [lower, upper]。
     * 调用前须保证 y 位于两端点函数值之间（含端点），保证根唯一存在。
     */
    public static BigDecimal[] bracket(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal y,
                                       BigDecimal lo, BigDecimal hi, Branch branch) {
        BigDecimal lower = lo;
        BigDecimal upper = hi;
        while (upper.subtract(lower).compareTo(BRACKET_WIDTH) > 0) {
            BigDecimal mid = lower.add(upper).divide(TWO);
            int cmp = eval(a, b, c, mid).compareTo(y);
            if (cmp == 0) {
                return new BigDecimal[]{mid, mid};
            }
            boolean rootAbove = branch == Branch.INCREASING ? cmp < 0 : cmp > 0;
            if (rootAbove) {
                lower = mid;
            } else {
                upper = mid;
            }
        }
        return new BigDecimal[]{lower, upper};
    }
}
