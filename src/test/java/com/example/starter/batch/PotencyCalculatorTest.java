package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效价线性衰减与数量加权纯函数单元测试：UTC 日界、左闭右开、秒级连续、HALF_UP 舍入、加权守恒。
 */
class PotencyCalculatorTest {

    private static final Instant ANCHOR = Instant.parse("2026-01-01T00:00:00Z");

    private PotencyModel model(String initial, String rate, String threshold) {
        return new PotencyModel(1, new BigDecimal(initial), ANCHOR, new BigDecimal(rate),
                new BigDecimal(threshold), "CK", ANCHOR);
    }

    @Test
    void elapsedDays_utcDayBoundary_halfOpenAndContinuous() {
        // 锚点当天经过 0 天（左闭）
        assertEquals(0, PotencyCalculator.elapsedDays(ANCHOR, ANCHOR).compareTo(BigDecimal.ZERO));
        // 锚点当天 12:00 到次日 00:00 为 0.5 个 UTC 日，不按本地时区取整
        assertEquals(0, PotencyCalculator.elapsedDays(Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z")).compareTo(new BigDecimal("0.5")));
        // 整 10 个 UTC 日
        assertEquals(0, PotencyCalculator.elapsedDays(ANCHOR,
                Instant.parse("2026-01-11T00:00:00Z")).compareTo(new BigDecimal("10")));
        // 跨月按真实秒数：1 月 31 天
        assertEquals(0, PotencyCalculator.elapsedDays(ANCHOR,
                Instant.parse("2026-02-01T00:00:00Z")).compareTo(new BigDecimal("31")));
    }

    @Test
    void currentPotency_linearDecay_halfUpSixDigits() {
        // 100 - 1/日 * 10 日 = 90
        assertEquals(new BigDecimal("90.000000"),
                PotencyCalculator.currentPotency(model("100", "1", "80"),
                        Instant.parse("2026-01-11T00:00:00Z")));
        // 半天：100 - 1 * 0.5 = 99.5
        assertEquals(new BigDecimal("99.500000"),
                PotencyCalculator.currentPotency(model("100", "1", "80"),
                        Instant.parse("2026-01-01T12:00:00Z")));
        // 1/3 日、率 0.1：100 - 0.033333333... = 99.966667（6 位 HALF_UP）
        assertEquals(new BigDecimal("99.966667"),
                PotencyCalculator.currentPotency(model("100", "0.1", "80"),
                        Instant.parse("2026-01-01T08:00:00Z")));
        // 零衰减率恒定
        assertEquals(new BigDecimal("100.000000"),
                PotencyCalculator.currentPotency(model("100", "0", "100"),
                        Instant.parse("2027-06-01T00:00:00Z")));
    }

    @Test
    void currentPotency_canFallBelowThresholdAndNegative() {
        // 线性模型不截断：衰减到阈值以下乃至为负，由放行门判定不合格
        BigDecimal decayed = PotencyCalculator.currentPotency(model("100", "1", "80"),
                Instant.parse("2026-05-01T00:00:00Z")); // 120 日 -> -20
        assertEquals(new BigDecimal("-20.000000"), decayed);
        assertFalse(PotencyCalculator.eligible(decayed, new BigDecimal("80")));
        BigDecimal borderline = new BigDecimal("79.999999");
        assertFalse(PotencyCalculator.eligible(borderline, new BigDecimal("80.000000")));
        assertTrue(PotencyCalculator.eligible(new BigDecimal("80.000000"), new BigDecimal("80.000000")));
    }

    @Test
    void weightedAverage_byQuantity_halfUpSixDigits() {
        // 30@90 + 70@80 => 83
        BigDecimal result = PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("30"), new BigDecimal("70")),
                List.of(new BigDecimal("90"), new BigDecimal("80")));
        assertEquals(new BigDecimal("83.000000"), result);
        // 1/3 权重除不尽：(90+80+70)/3 = 80 → 精确；换非整除 (91+80+70)/3 = 80.333333
        BigDecimal repeating = PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")),
                List.of(new BigDecimal("91"), new BigDecimal("80"), new BigDecimal("70")));
        assertEquals(new BigDecimal("80.333333"), repeating);
    }

    @Test
    void weightedAverage_rejectsEmptyOrNonPositiveTotalWeight() {
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weightedAverage(
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weightedAverage(
                List.of(BigDecimal.ZERO), List.of(new BigDecimal("90"))));
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weightedAverage(
                List.of(new BigDecimal("1")), List.of()));
    }

    @Test
    void margin_isActualMinusRequired() {
        assertEquals(new BigDecimal("-0.500000"),
                PotencyCalculator.margin(new BigDecimal("79.5"), new BigDecimal("80")));
        assertEquals(new BigDecimal("2.000000"),
                PotencyCalculator.margin(new BigDecimal("82"), new BigDecimal("80")));
    }
}
