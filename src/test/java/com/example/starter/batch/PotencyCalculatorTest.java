package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 效价衰减纯函数测试：UTC 日边界（左闭右开）、线性衰减舍入、加权平均。
 */
class PotencyCalculatorTest {

    @Test
    void elapsedUtcDays_dayBoundaryIsLeftClosedRightOpen() {
        Instant registered = Instant.parse("2026-06-01T12:00:00Z");
        // 同一 UTC 日内：0 天
        assertEquals(0, PotencyCalculator.elapsedUtcDays(registered,
                Instant.parse("2026-06-01T23:59:59Z")));
        // 跨入次日 00:00 即计入 1 天（左闭）
        assertEquals(1, PotencyCalculator.elapsedUtcDays(registered,
                Instant.parse("2026-06-02T00:00:00Z")));
        // 次日未结束仍计 1 天（右开：第三天未开始不计）
        assertEquals(1, PotencyCalculator.elapsedUtcDays(registered,
                Instant.parse("2026-06-02T23:59:59Z")));
        assertEquals(2, PotencyCalculator.elapsedUtcDays(registered,
                Instant.parse("2026-06-03T00:00:00Z")));
    }

    @Test
    void elapsedUtcDays_evaluationBeforeRegistration_clampsToZero() {
        Instant registered = Instant.parse("2026-06-10T00:00:00Z");
        assertEquals(0, PotencyCalculator.elapsedUtcDays(registered,
                Instant.parse("2026-06-01T00:00:00Z")));
    }

    @Test
    void currentPotency_linearDecayRoundedToFourDecimals() {
        assertEquals(0, new BigDecimal("95.0000").compareTo(PotencyCalculator.currentPotency(
                new BigDecimal("100"), new BigDecimal("1"), 5)));
        // 舍入：100 - 0.123456*3 = 99.629632 → 99.6296（HALF_UP 4 位）
        assertEquals(0, new BigDecimal("99.6296").compareTo(PotencyCalculator.currentPotency(
                new BigDecimal("100"), new BigDecimal("0.123456"), 3)));
        // 衰减至负值允许（必然低于阈值）
        assertEquals(0, new BigDecimal("-5.0000").compareTo(PotencyCalculator.currentPotency(
                new BigDecimal("100"), new BigDecimal("15"), 7)));
    }

    @Test
    void weightedAverage_roundsHalfUpToFourDecimals() {
        // (100*2 + 80*3) / 5 = 88
        assertEquals(0, new BigDecimal("88.0000").compareTo(PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("100"), new BigDecimal("80")),
                List.of(new BigDecimal("2"), new BigDecimal("3")))));
        // (10.00005*1 + 20*2) / 3 = 16.66668333... → 16.6667
        assertEquals(0, new BigDecimal("16.6667").compareTo(PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("10.00005"), new BigDecimal("20")),
                List.of(new BigDecimal("1"), new BigDecimal("2")))));
    }
}
