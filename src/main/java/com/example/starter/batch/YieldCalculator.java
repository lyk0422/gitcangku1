package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 产率平衡核算纯计算：投料、合格产出、损耗、报废与允许偏差上限统一三位小数（HALF_UP）。
 * 偏差 = |投料 - (合格产出 + 损耗 + 报废)|；偏差恰好等于上限视为合格。
 */
public final class YieldCalculator {

    /**
     * 全部数量统一保留的小数位数。
     */
    public static final int SCALE = 3;

    private static final BigDecimal ZERO = new BigDecimal("0.000");

    private YieldCalculator() {
    }

    /**
     * 归一化为三位小数；数量单位与批次一致，空值由入参校验拦截。
     */
    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 三位小数的零值，供默认配置与比较使用。
     */
    public static BigDecimal zero() {
        return ZERO;
    }

    /**
     * 任一数量为负（不含零）即拒绝。
     */
    public static boolean anyNegative(BigDecimal input, BigDecimal qualified,
                                      BigDecimal loss, BigDecimal scrap) {
        return input.signum() < 0 || qualified.signum() < 0
                || loss.signum() < 0 || scrap.signum() < 0;
    }

    /**
     * 偏差 = |投料 - (合格产出 + 损耗 + 报废)|，三位小数非负。
     */
    public static BigDecimal deviation(BigDecimal input, BigDecimal qualified,
                                       BigDecimal loss, BigDecimal scrap) {
        BigDecimal accounted = qualified.add(loss).add(scrap);
        return input.subtract(accounted).abs().setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 偏差是否在上限内：偏差小于或恰好等于上限均为合格。
     */
    public static boolean withinLimit(BigDecimal deviation, BigDecimal toleranceLimit) {
        return deviation.compareTo(toleranceLimit) <= 0;
    }
}
