package com.example.starter.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 奖励计算纯单元测试：奖励电量取承诺量与基线减实际正值的较小者，按 0.1 kWh 向下截取，
 * 每 kWh 80 分。
 */
class RewardCalculatorTest {

    private static final int RATE = 80;

    private static BigDecimal kwh(String value) {
        return new BigDecimal(value);
    }

    @Test
    void syntheticCaseCommit10Baseline30Actual23Gives7Kwh560Fen() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("23"));
        assertEquals(0, kwh("7.0").compareTo(reward), "合成案例奖励电量应为 7.0 kWh");
        assertEquals(560L, RewardCalculator.amountFen(reward, RATE), "7 kWh × 80 分应为 560 分");
    }

    @Test
    void savingBelowCommitmentUsesPositiveSaving() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("25"));
        assertEquals(0, kwh("5.0").compareTo(reward));
        assertEquals(400L, RewardCalculator.amountFen(reward, RATE));
    }

    @Test
    void savingAboveCommitmentIsCappedAtCommitment() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("10"));
        assertEquals(0, kwh("10.0").compareTo(reward), "奖励电量不超过承诺量");
        assertEquals(800L, RewardCalculator.amountFen(reward, RATE));
    }

    @Test
    void actualAboveBaselineGivesZero() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("31"));
        assertEquals(0, BigDecimal.ZERO.compareTo(reward));
        assertEquals(0L, RewardCalculator.amountFen(reward, RATE));
    }

    @Test
    void floorsDownToTenthKwh() {
        // 节省 7.19 → 截取 7.1 → 568 分
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("22.81"));
        assertEquals(0, kwh("7.1").compareTo(reward), "不足 0.1 kWh 部分应向下截取");
        assertEquals(568L, RewardCalculator.amountFen(reward, RATE));
    }

    @Test
    void floorToTenthNeverRoundsUp() {
        assertEquals(0, kwh("0.0").compareTo(RewardCalculator.floorToTenth(kwh("0.09"))));
        assertEquals(0, kwh("0.1").compareTo(RewardCalculator.floorToTenth(kwh("0.1999"))));
        assertEquals(0, kwh("49.9").compareTo(RewardCalculator.floorToTenth(kwh("49.99"))));
    }
}
