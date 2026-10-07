package com.example.starter.calibration.service;

import java.math.BigDecimal;

/**
 * 二次单调分支上的反解求解器：二分夹逼 f(x)=signal 在 [lo, hi] 内的唯一根。
 * 仅用于展示与近似存储；合格判定不使用该近似根，只使用端点函数值的精确比较。
 */
final class InverseSolver {

    /** 夹逼区间目标宽度：严于题干上限 0.000001，留出存储舍入余量。 */
    private static final BigDecimal TARGET_WIDTH = new BigDecimal("0.0000005");

    private static final BigDecimal TWO = new BigDecimal("2");

    private InverseSolver() {
    }

    /**
     * 在严格单调分支 [lo, hi] 上夹逼 f(x)=signal 的唯一根。
     * 前置条件：f 在 [lo, hi] 严格单调，且 signal 位于两端点函数值之间（含端点）。
     *
     * @return 二元数组 [下界, 上界]，宽度不超过 0.000001；精确命中时两端相等
     */
    static BigDecimal[] bracket(BigDecimal a, BigDecimal b, BigDecimal c,
                                BigDecimal lo, BigDecimal hi, BigDecimal signal) {
        boolean increasing = Quadratics.increasing(a, b, lo);
        BigDecimal lower = lo;
        BigDecimal upper = hi;
        while (upper.subtract(lower).compareTo(TARGET_WIDTH) > 0) {
            // 除以 2 在十进制下必精确，不会抛出非终止异常
            BigDecimal mid = lower.add(upper).divide(TWO);
            int cmp = Quadratics.eval(a, b, c, mid).compareTo(signal);
            if (cmp == 0) {
                return new BigDecimal[]{mid, mid};
            }
            boolean rootAbove = increasing ? cmp < 0 : cmp > 0;
            if (rootAbove) {
                lower = mid;
            } else {
                upper = mid;
            }
        }
        return new BigDecimal[]{lower, upper};
    }
}
