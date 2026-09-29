package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成不确定度 RSS 口径单元测试：平方和开根、四位小数返回口径、
 * 阈值含端点判定、余量差额、空预算与零分量语义。
 */
class UncertaintyCalculatorTest {

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void combinesByRootSumOfSquares() {
        // sqrt(3² + 4²) = 5
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of(bd("3"), bd("4")));
        assertEquals(new BigDecimal("5.000000000000"), combined);
        assertEquals("5.0000", UncertaintyCalculator.toDisplay(combined).toPlainString());
    }

    @Test
    void combinesIrrationalResultToStorageThenFourDecimals() {
        // sqrt(1² + 1²) = sqrt(2) ≈ 1.414213562373...；存储 12 位 HALF_UP，返回四位 HALF_UP
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of(bd("1"), bd("1")));
        assertEquals(new BigDecimal("1.414213562373"), combined);
        assertEquals("1.4142", UncertaintyCalculator.toDisplay(combined).toPlainString());
    }

    @Test
    void displayRoundsHalfUpAtFourthDecimal() {
        // sqrt(1² + 0.5²) = sqrt(1.25) ≈ 1.11803398875 → 1.1180
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of(bd("1"), bd("0.5")));
        assertEquals("1.1180", UncertaintyCalculator.toDisplay(combined).toPlainString());

        // 构造第五位恰好为 5 的进位场景：0.10005 由 0.10005 单分量开根不现实，
        // 直接验证显示口径对存储值做 HALF_UP：1.23455 → 1.2346，1.23454 → 1.2345
        assertEquals("1.2346",
                UncertaintyCalculator.toDisplay(new BigDecimal("1.234550000000")).toPlainString());
        assertEquals("1.2345",
                UncertaintyCalculator.toDisplay(new BigDecimal("1.234549999999")).toPlainString());
    }

    @Test
    void emptyBudgetIsZero() {
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of());
        assertEquals(new BigDecimal("0.000000000000"), combined);
        assertEquals("0.0000", UncertaintyCalculator.toDisplay(combined).toPlainString());
        assertTrue(UncertaintyCalculator.withinThreshold(combined, BigDecimal.ZERO));
    }

    @Test
    void thresholdComparisonIsInclusiveAndExact() {
        // 合成值与阈值相等（含端点）→ 满足
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of(bd("0.3"), bd("0.4")));
        assertEquals(new BigDecimal("0.500000000000"), combined);
        assertTrue(UncertaintyCalculator.withinThreshold(combined, new BigDecimal("0.500000")));
        // 阈值小 0.000001 → 不满足（按未舍入存储值比较，不按四位显示值）
        assertFalse(UncertaintyCalculator.withinThreshold(combined, new BigDecimal("0.499999")));
    }

    @Test
    void marginIsRequiredMinusActual() {
        BigDecimal combined = UncertaintyCalculator.combineToStorage(List.of(bd("3"), bd("4")));
        assertEquals(new BigDecimal("0.200000000000"),
                UncertaintyCalculator.margin(combined, new BigDecimal("5.2")));
        // 超出时差额为负数，表示超出量
        assertEquals(new BigDecimal("-0.100000000000"),
                UncertaintyCalculator.margin(combined, new BigDecimal("4.9")));
    }

    @Test
    void componentOrderDoesNotChangeResult() {
        BigDecimal a = UncertaintyCalculator.combineToStorage(
                List.of(bd("1.1"), bd("2.2"), bd("3.3")));
        BigDecimal b = UncertaintyCalculator.combineToStorage(
                List.of(bd("3.3"), bd("1.1"), bd("2.2")));
        assertEquals(0, a.compareTo(b));
    }
}
