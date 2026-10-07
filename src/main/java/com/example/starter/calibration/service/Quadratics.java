package com.example.starter.calibration.service;

import java.math.BigDecimal;

/**
 * 二次函数 y = a·x² + b·x + c 的精确求值工具（BigDecimal，不舍入）。
 */
final class Quadratics {

    private Quadratics() {
    }

    /** 函数值 f(x) = a·x² + b·x + c，精确计算。 */
    static BigDecimal eval(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal x) {
        return a.multiply(x).multiply(x).add(b.multiply(x)).add(c);
    }

    /** 导数值 f′(x) = 2a·x + b，精确计算。 */
    static BigDecimal derivative(BigDecimal a, BigDecimal b, BigDecimal x) {
        return a.add(a).multiply(x).add(b);
    }

    /**
     * 导数在整个闭区间 [lo, hi] 上是否严格同号（严格单调）。
     * 导数为一次函数，符号由端点唯一确定：两端点同号且均非零 ⟺ 全区间严格同号。
     */
    static boolean strictlyMonotone(BigDecimal a, BigDecimal b, BigDecimal lo, BigDecimal hi) {
        int atLo = derivative(a, b, lo).signum();
        int atHi = derivative(a, b, hi).signum();
        return atLo != 0 && atLo == atHi;
    }

    /** 区间 [lo, hi] 上函数是否严格递增（须已确认严格单调）。 */
    static boolean increasing(BigDecimal a, BigDecimal b, BigDecimal lo) {
        return derivative(a, b, lo).signum() > 0;
    }
}
