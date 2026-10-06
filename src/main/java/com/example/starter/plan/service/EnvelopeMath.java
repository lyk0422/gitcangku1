package com.example.starter.plan.service;

import java.math.BigDecimal;

/**
 * 应急制动包络精确判定：制动距离 = v*t + v^2/(2a)，必须不超过 d。
 * 比较采用精确交叉乘积 2*a*v*t + v^2 &lt;= 2*a*d，不先舍入除法；
 * 输入均为最多3位小数的精确十进制，乘积仍为精确值。
 */
public final class EnvelopeMath {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private EnvelopeMath() {
    }

    /**
     * 交叉乘积左侧：2*a*v*t + v^2（精确值，未舍入）。
     */
    public static BigDecimal lhs(BigDecimal speed, BigDecimal reactionTime,
                                 BigDecimal baseDeceleration) {
        return TWO.multiply(baseDeceleration).multiply(speed).multiply(reactionTime)
                .add(speed.multiply(speed));
    }

    /**
     * 交叉乘积右侧：2*a*d（精确值，未舍入）。
     */
    public static BigDecimal rhs(BigDecimal baseDeceleration, BigDecimal protectionDistance) {
        return TWO.multiply(baseDeceleration).multiply(protectionDistance);
    }

    /**
     * 判定制动距离是否不超过防护距离（边界相等为不越限）。
     */
    public static boolean withinLimit(BigDecimal speed, BigDecimal reactionTime,
                                      BigDecimal baseDeceleration,
                                      BigDecimal protectionDistance) {
        return lhs(speed, reactionTime, baseDeceleration)
                .compareTo(rhs(baseDeceleration, protectionDistance)) <= 0;
    }
}
