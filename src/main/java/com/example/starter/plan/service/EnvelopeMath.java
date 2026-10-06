package com.example.starter.plan.service;

import java.math.BigDecimal;

/**
 * 应急制动包络的精确判定：制动距离 v*t + v*v/(2*a) 不超过防护距离 d。
 * 比较用精确交叉乘积（两边同乘正数 2a）：2*a*v*t + v*v &lt;= 2*a*d，
 * 全程 BigDecimal 精确运算，不先舍入除法。
 */
public final class EnvelopeMath {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private EnvelopeMath() {
    }

    /**
     * 精确判定左值 2*a*v*t + v*v。
     */
    public static BigDecimal leftValue(BigDecimal v, BigDecimal t, BigDecimal a) {
        return TWO.multiply(a).multiply(v).multiply(t).add(v.multiply(v));
    }

    /**
     * 精确判定右值 2*a*d。
     */
    public static BigDecimal rightValue(BigDecimal a, BigDecimal d) {
        return TWO.multiply(a).multiply(d);
    }

    /**
     * 判定是否越限：左值大于右值即所需制动距离超过防护距离。
     */
    public static boolean exceeds(BigDecimal v, BigDecimal t, BigDecimal a, BigDecimal d) {
        return leftValue(v, t, a).compareTo(rightValue(a, d)) > 0;
    }
}
