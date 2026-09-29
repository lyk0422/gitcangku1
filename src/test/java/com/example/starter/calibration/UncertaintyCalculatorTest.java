package com.example.starter.calibration;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.service.UncertaintyCalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成不确定度口径纯单元测试：sqrt(Σx²) 公开 RSS 口径、四位小数 HALF_UP、
 * 非负约束、含零、阈值含端点比较。
 */
class UncertaintyCalculatorTest {

    @Test
    void emptyOrNullBudgetCombinesToZeroWithFourDecimals() {
        assertEquals("0.0000", UncertaintyCalculator.combine(List.of()).toPlainString());
        assertEquals("0.0000", UncertaintyCalculator.combine(null).toPlainString());
    }

    @Test
    void singleComponentEqualsItsMagnitudeRoundedToFourDecimals() {
        // 单分量 sqrt(0.12^2) = 0.12
        assertEquals("0.1200",
                UncertaintyCalculator.combine(List.of(new BigDecimal("0.12"))).toPlainString());
    }

    @Test
    void rootSumOfSquaresOfThreeFourFiveEqualsSevenPointZeroSevenOneEight() {
        // sqrt(3² + 4² + 5²) = sqrt(50) = 7.071067... → HALF_UP 4 位 = 7.0711（差额实际值 vs 返回值）
        BigDecimal combined = UncertaintyCalculator.combine(
                List.of(new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("5")));
        assertEquals("7.0711", combined.toPlainString());
    }

    @Test
    void classicThreeAndFourCombinesToFive() {
        assertEquals("5.0000",
                UncertaintyCalculator.combine(List.of(new BigDecimal("3"), new BigDecimal("4")))
                        .toPlainString());
    }

    @Test
    void roundingIsHalfUpAtFourthDecimal() {
        // sqrt(0.0001^2 + 0.0002^2) = 0.000223606... → 0.0002（第五位 2 舍去）
        assertEquals("0.0002", UncertaintyCalculator.combine(
                List.of(new BigDecimal("0.0001"), new BigDecimal("0.0002"))).toPlainString());
        // 构造第五位为 5：sqrt(0.0003²+0.0004²)=0.0005 恰好 → 0.0005
        assertEquals("0.0005", UncertaintyCalculator.combine(
                List.of(new BigDecimal("0.0003"), new BigDecimal("0.0004"))).toPlainString());
    }

    @Test
    void zeroComponentsAreAllowedAndDoNotChangeResult() {
        assertEquals("5.0000", UncertaintyCalculator.combine(
                List.of(new BigDecimal("3"), new BigDecimal("0"), new BigDecimal("4"))).toPlainString());
    }

    @Test
    void orderDoesNotAffectResult() {
        BigDecimal a = UncertaintyCalculator.combine(List.of(
                new BigDecimal("1.1"), new BigDecimal("2.2"), new BigDecimal("3.3")));
        BigDecimal b = UncertaintyCalculator.combine(List.of(
                new BigDecimal("3.3"), new BigDecimal("1.1"), new BigDecimal("2.2")));
        assertEquals(a, b);
    }

    @Test
    void negativeOrNullMagnitudeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UncertaintyCalculator.combine(List.of(new BigDecimal("-0.0001"))));
        // List.of 不允许 null 元素，用可变集合承载以触达计算器自身的 null 防御
        assertThrows(IllegalArgumentException.class,
                () -> UncertaintyCalculator.combine(java.util.Arrays.asList((BigDecimal) null)));
    }

    @Test
    void combineFromComponentRecordsMatchesMagnitudes() {
        List<UncertaintyComponent> components = List.of(
                new UncertaintyComponent(1, 10, 1, "repeatability", new BigDecimal("3"), null),
                new UncertaintyComponent(2, 10, 1, "reference", new BigDecimal("4"), null));
        assertEquals("5.0000", UncertaintyCalculator.combineComponents(components).toPlainString());
    }

    @Test
    void thresholdComparisonIsInclusiveAtBoundary() {
        BigDecimal combined = new BigDecimal("5.0000");
        assertTrue(UncertaintyCalculator.withinThreshold(combined, new BigDecimal("5.0000")));
        assertTrue(UncertaintyCalculator.withinThreshold(combined, new BigDecimal("5.0001")));
        assertFalse(UncertaintyCalculator.withinThreshold(combined, new BigDecimal("4.9999")));
    }
}
