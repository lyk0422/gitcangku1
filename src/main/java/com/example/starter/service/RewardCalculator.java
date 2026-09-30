package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 奖励计算纯函数：奖励电量取承诺量与（基线-实际）正值中的较小者，并按 0.1 kWh 向下截取；
 * 金额 = 截取后奖励电量 × 每 kWh 单价（分），结果为整数分。
 */
public final class RewardCalculator {

    /** 奖励电量截取粒度：0.1 kWh。 */
    public static final BigDecimal STEP_KWH = new BigDecimal("0.1");

    private RewardCalculator() {
    }

    /**
     * 计算截取后的奖励电量（kWh）。
     *
     * @param committedReduction 承诺减少电量（kWh）
     * @param baselineKwh        基线电量（kWh）
     * @param actualKwh          实际电量（kWh）
     * @return min(承诺, max(基线-实际, 0))，按 0.1 kWh 向下截取
     */
    public static BigDecimal rewardKwh(BigDecimal committedReduction,
                                       BigDecimal baselineKwh,
                                       BigDecimal actualKwh) {
        BigDecimal saved = baselineKwh.subtract(actualKwh);
        if (saved.signum() < 0) {
            saved = BigDecimal.ZERO;
        }
        BigDecimal raw = committedReduction.min(saved);
        return floorToTenth(raw);
    }

    /**
     * 将任意非负电量按 0.1 kWh 向下截取，例如 7.19 → 7.1。
     */
    public static BigDecimal floorToTenth(BigDecimal value) {
        if (value.signum() <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.UNNECESSARY);
        }
        BigDecimal stepped = value.divide(STEP_KWH, 0, RoundingMode.DOWN).multiply(STEP_KWH);
        return stepped.setScale(1, RoundingMode.UNNECESSARY);
    }

    /**
     * 奖励金额（分）。
     */
    public static long amountFen(BigDecimal rewardKwh, int rateFenPerKwh) {
        return rewardKwh.multiply(BigDecimal.valueOf(rateFenPerKwh))
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }
}
