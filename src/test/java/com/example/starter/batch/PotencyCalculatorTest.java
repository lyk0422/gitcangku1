package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 效价衰减纯计算单元测试：UTC 日左闭右开边界、线性衰减、数量加权平均与舍入。
 */
class PotencyCalculatorTest {

    @Test
    void elapsedUtcDays_halfOpenIntervalCountsWholeUtcDays() {
        Instant epoch = Instant.parse("2026-01-01T12:00:00Z");
        // 同一 UTC 日内：0 天
        assertEquals(0, PotencyCalculator.elapsedUtcDays(epoch,
                Instant.parse("2026-01-01T23:59:59Z")));
        // 进入下一 UTC 日 00:00（左闭右开）：1 天
        assertEquals(1, PotencyCalculator.elapsedUtcDays(epoch,
                Instant.parse("2026-01-02T00:00:00Z")));
        // 下一 UTC 日内任意时刻仍为 1 天
        assertEquals(1, PotencyCalculator.elapsedUtcDays(epoch,
                Instant.parse("2026-01-02T11:59:59Z")));
        assertEquals(10, PotencyCalculator.elapsedUtcDays(epoch,
                Instant.parse("2026-01-11T12:00:00Z")));
    }

    @Test
    void currentPotency_linearDecayPerUtcDay() {
        BigDecimal potency = PotencyCalculator.currentPotency(
                new BigDecimal("100"), new BigDecimal("2"),
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-04T00:00:00Z"));
        assertEquals(0, new BigDecimal("94").compareTo(potency));
        // 零衰减率保持初始效价
        BigDecimal constant = PotencyCalculator.currentPotency(
                new BigDecimal("80.5"), new BigDecimal("0"),
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        assertEquals(0, new BigDecimal("80.5").compareTo(constant));
    }

    @Test
    void weightedAverage_byQuantity() {
        BigDecimal avg = PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("100"), new BigDecimal("80")),
                List.of(new BigDecimal("10"), new BigDecimal("30")));
        assertEquals(0, new BigDecimal("85").compareTo(avg));
    }

    @Test
    void weightedAverage_rejectsMismatchedOrEmptyInputs() {
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("1")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weightedAverage(
                List.of(), List.of()));
    }

    @Test
    void round_appliesModelScaleAndMode() {
        assertEquals(new BigDecimal("98"),
                PotencyCalculator.round(new BigDecimal("97.65"), 0, "HALF_UP"));
        assertEquals(new BigDecimal("97.6500"),
                PotencyCalculator.round(new BigDecimal("97.65"), 4, "HALF_UP"));
        assertEquals(new BigDecimal("97.6"),
                PotencyCalculator.round(new BigDecimal("97.65"), 1, "DOWN"));
        assertEquals(new BigDecimal("97.7"),
                PotencyCalculator.round(new BigDecimal("97.61"), 1, "UP"));
        assertEquals(new BigDecimal("97.6"),
                PotencyCalculator.round(new BigDecimal("97.65"), 1, "HALF_EVEN"));
    }
}
