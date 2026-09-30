package com.example.starter.billing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 奖励电量与金额的纯计算规则。
 */
public final class RewardCalculator {

    /** 承诺量上限，单位 kWh。 */
    public static final BigDecimal MAX_COMMITMENT_KWH = new BigDecimal("50");

    /** 每 kWh 奖励，单位分。 */
    public static final long REWARD_FEN_PER_KWH = 80L;

    /** 奖励电量截取粒度，单位 kWh（不足 0.1 kWh 向下截取）。 */
    public static final BigDecimal REWARD_STEP_KWH = new BigDecimal("0.1");

    private RewardCalculator() {
    }

    /**
     * 计算奖励电量：取承诺量与（基线减实际的正值）中的较小值，再按 0.1 kWh 向下截取。
     *
     * @param committedKwh 承诺量，kWh
     * @param baselineKwh  基线电量，kWh
     * @param actualKwh    实际电量，kWh
     * @return 奖励电量，kWh（非负，步长 0.1）
     */
    public static BigDecimal rewardKwh(BigDecimal committedKwh, BigDecimal baselineKwh,
                                       BigDecimal actualKwh) {
        BigDecimal reduction = baselineKwh.subtract(actualKwh);
        if (reduction.signum() < 0) {
            reduction = BigDecimal.ZERO.setScale(1, RoundingMode.UNNECESSARY);
        }
        BigDecimal raw = committedKwh.min(reduction);
        // 按 0.1 kWh 向下截取：先放大 10 倍向下取整，再缩回 1 位小数。
        long tenths = raw.multiply(BigDecimal.TEN).setScale(0, RoundingMode.FLOOR).longValueExact();
        if (tenths < 0) {
            tenths = 0;
        }
        return BigDecimal.valueOf(tenths, 1);
    }

    /**
     * 奖励电量折算金额。
     *
     * @param rewardKwh 奖励电量，kWh
     * @return 金额，单位分
     */
    public static long amountFen(BigDecimal rewardKwh) {
        return rewardKwh.multiply(BigDecimal.valueOf(REWARD_FEN_PER_KWH))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }
}
