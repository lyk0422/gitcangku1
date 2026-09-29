package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * 合成不确定度计算：公开的 RSS（root-sum-of-squares）口径。
 * 假设各不确定度分量互不相关，u_c = sqrt(Σ u_i²)（平方和开根）。
 * 平方和用 BigDecimal 精确求和；开根在 50 位有效数字上下取，存储统一截取到 12 位小数（HALF_UP），
 * 对外返回值按四位小数 HALF_UP。阈值比较使用 12 位存储值，保证登记、放行、查询口径一致。
 */
public final class UncertaintyCalculator {

    /** 存储与判定基准精度：未舍入开根值保留 12 位小数。 */
    public static final int STORAGE_SCALE = 12;

    /** 对外返回精度：四位小数。 */
    public static final int DISPLAY_SCALE = 4;

    private static final MathContext SQRT_CONTEXT = new MathContext(50, RoundingMode.HALF_UP);

    private UncertaintyCalculator() {
    }

    /**
     * 按 RSS 口径计算合成不确定度的存储值（12 位小数，HALF_UP）。
     * 空集合视为空预算，合成不确定度为 0。
     */
    public static BigDecimal combineToStorage(List<BigDecimal> componentValues) {
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal value : componentValues) {
            sumSquares = sumSquares.add(value.multiply(value));
        }
        if (sumSquares.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(STORAGE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal sqrt = sumSquares.sqrt(SQRT_CONTEXT);
        return sqrt.setScale(STORAGE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 对外返回口径：合成不确定度按四位小数 HALF_UP。
     */
    public static BigDecimal toDisplay(BigDecimal combinedStorageValue) {
        return combinedStorageValue.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 阈值判定：合成不确定度不超过阈值（含端点，compareTo 精确比较，不做浮点误差放宽）。
     */
    public static boolean withinThreshold(BigDecimal combinedStorageValue, BigDecimal threshold) {
        return combinedStorageValue.compareTo(threshold) <= 0;
    }

    /**
     * 差额（余量）= 要求值 − 实际值，按存储精度给出；非负表示满足阈值，为负表示超出量。
     */
    public static BigDecimal margin(BigDecimal combinedStorageValue, BigDecimal threshold) {
        return threshold.subtract(combinedStorageValue).setScale(STORAGE_SCALE, RoundingMode.HALF_UP);
    }
}
