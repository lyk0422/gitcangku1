package com.example.starter.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 奖励电量截取与金额折算纯规则单元测试。
 */
class RewardCalculatorTest {

    private static BigDecimal kwh(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("合成主流程：承诺10、基线30、实际23，奖励7.0kWh即560分")
    void syntheticMainFlow() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("23"));
        assertEquals(0, reward.compareTo(new BigDecimal("7.0")), "奖励电量应为7.0kWh");
        assertEquals(560L, RewardCalculator.amountFen(reward), "金额应为560分");
    }

    @Test
    @DisplayName("实际压降超过承诺量时按承诺量封顶")
    void cappedByCommitment() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("5"), kwh("30"), kwh("20"));
        assertEquals(0, reward.compareTo(new BigDecimal("5.0")), "承诺5kWh应封顶为5.0kWh");
        assertEquals(400L, RewardCalculator.amountFen(reward));
    }

    @Test
    @DisplayName("不足0.1kWh的部分向下截取")
    void floorToTenth() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30.00"), kwh("22.01"));
        assertEquals(0, reward.compareTo(new BigDecimal("7.9")), "压降7.99应截取为7.9kWh");
        assertEquals(632L, RewardCalculator.amountFen(reward));
    }

    @Test
    @DisplayName("压降不足0.1kWh时奖励为0")
    void belowOneTenthIsZero() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30.00"), kwh("29.95"));
        assertEquals(0, reward.compareTo(new BigDecimal("0.0")), "压降0.05应截取为0.0kWh");
        assertEquals(0L, RewardCalculator.amountFen(reward));
    }

    @Test
    @DisplayName("实际电量不低于基线时奖励为0（不为负）")
    void noNegativeReward() {
        BigDecimal reward = RewardCalculator.rewardKwh(kwh("10"), kwh("30"), kwh("31"));
        assertEquals(0, reward.compareTo(new BigDecimal("0.0")), "反向压降按0处理");
        assertEquals(0L, RewardCalculator.amountFen(reward));
    }

    @Test
    @DisplayName("承诺上限常量为50kWh、单价80分/kWh")
    void constants() {
        assertEquals(0, new BigDecimal("50").compareTo(RewardCalculator.MAX_COMMITMENT_KWH));
        assertEquals(80L, RewardCalculator.REWARD_FEN_PER_KWH);
    }
}
