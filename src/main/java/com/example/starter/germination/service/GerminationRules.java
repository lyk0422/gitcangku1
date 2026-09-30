package com.example.starter.germination.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 发芽率业务规则（无状态，便于单元测试）。
 */
public final class GerminationRules {

    /** 样本数下限，单位粒。 */
    public static final int MIN_SAMPLE = 100;
    /** 样本数上限，单位粒。 */
    public static final int MAX_SAMPLE = 400;
    /** 签发证书要求的发芽率下限，单位 %。 */
    public static final BigDecimal PASS_RATIO = new BigDecimal("85.00");

    private GerminationRules() {
    }

    /**
     * 样本数是否合法（100-400 粒）。
     */
    public static boolean isSampleCountValid(int sampleCount) {
        return sampleCount >= MIN_SAMPLE && sampleCount <= MAX_SAMPLE;
    }

    /**
     * 发芽粒数是否合法（非负且不超过样本数）。
     */
    public static boolean isGerminatedCountValid(int sampleCount, int germinatedCount) {
        return germinatedCount >= 0 && germinatedCount <= sampleCount;
    }

    /**
     * 计算发芽率百分数，保留两位小数，四舍五入。
     */
    public static BigDecimal ratioPercent(int sampleCount, int germinatedCount) {
        return BigDecimal.valueOf(germinatedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sampleCount), 2, RoundingMode.HALF_UP);
    }

    /**
     * 单次检测是否达到签发要求（发芽率 ≥ 85%）。
     */
    public static boolean isPassing(int sampleCount, int germinatedCount) {
        return ratioPercent(sampleCount, germinatedCount).compareTo(PASS_RATIO) >= 0;
    }
}
