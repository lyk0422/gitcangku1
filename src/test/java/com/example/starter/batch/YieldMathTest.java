package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产率平衡计算纯单元测试：题干样例、恰好等于上限、三位小数舍入与绝对差边界。
 */
class YieldMathTest {

    private BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void givenExample_deviationExactlyHalfPassesAndScrapOneExceeds() {
        // 投料 100.000、合格 90.000、损耗 8.000、报废 1.500 → 差额 0.500
        BigDecimal onEdge = YieldMath.deviation(d("100.000"), d("90.000"), d("8.000"), d("1.500"));
        assertEquals(0, onEdge.compareTo(d("0.500")));
        assertEquals(3, onEdge.scale());
        // 上限 0.500，恰好等于上限视为合格
        assertTrue(YieldMath.withinLimit(onEdge, d("0.500")));

        // 报废改为 1.000 → 差额 1.000，超过 0.500 上限
        BigDecimal exceeded = YieldMath.deviation(d("100.000"), d("90.000"), d("8.000"), d("1.000"));
        assertEquals(0, exceeded.compareTo(d("1.000")));
        assertFalse(YieldMath.withinLimit(exceeded, d("0.500")));
    }

    @Test
    void exactlyAtLimit_isWithinLimit() {
        BigDecimal deviation = YieldMath.deviation(d("100"), d("99.4"), d("0.1"), d("0"));
        assertEquals(0, deviation.compareTo(d("0.500")));
        // 上限以不同 scale 给出也要按数值比较
        assertTrue(YieldMath.withinLimit(deviation, d("0.5")));
        assertFalse(YieldMath.withinLimit(d("0.501"), d("0.500")));
    }

    @Test
    void deviation_isAbsoluteDifference_whenOutputsExceedInput() {
        // 合格 + 损耗 + 报废 = 105，投料 100，绝对差 5
        BigDecimal deviation = YieldMath.deviation(d("100.000"), d("100.000"), d("5.000"), d("0.000"));
        assertEquals(0, deviation.compareTo(d("5.000")));
    }

    @Test
    void normalize_roundsHalfUpToThreeDecimals() {
        assertEquals("1.235", YieldMath.normalize(d("1.2345")).toPlainString());
        assertEquals("1.234", YieldMath.normalize(d("1.2344")).toPlainString());
        assertEquals("2.000", YieldMath.normalize(d("2")).toPlainString());
        assertEquals("0.000", YieldMath.normalize(d("0.0004")).toPlainString());
    }

    @Test
    void balancedZeroDeviation_passesAnyNonNegativeLimit() {
        BigDecimal deviation = YieldMath.deviation(d("100.000"), d("90.000"), d("8.000"), d("2.000"));
        assertEquals(0, deviation.compareTo(BigDecimal.ZERO.setScale(3)));
        assertTrue(YieldMath.withinLimit(deviation, BigDecimal.ZERO.setScale(3)));
    }
}
