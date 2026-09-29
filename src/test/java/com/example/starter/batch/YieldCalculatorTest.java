package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产率平衡核算纯计算单元测试：三位小数归一化、偏差计算、恰好等于上限合格、负数判定边界。
 */
class YieldCalculatorTest {

    private BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void normalize_keepsThreeScale_halfUp() {
        assertEquals(new BigDecimal("100.000"), YieldCalculator.normalize(d("100")));
        assertEquals(new BigDecimal("90.005"), YieldCalculator.normalize(d("90.0046")));
        assertEquals(new BigDecimal("90.004"), YieldCalculator.normalize(d("90.0044")));
        assertEquals(new BigDecimal("0.000"), YieldCalculator.normalize(d("0")));
    }

    @Test
    void deviation_absoluteBalanceGap_threeDecimals() {
        // 投料100 - (90 + 8 + 1.5) = 0.5
        assertEquals(new BigDecimal("0.500"),
                YieldCalculator.deviation(d("100.000"), d("90.000"), d("8.000"), d("1.500")));
        // 反向（产出之和大于投料）取绝对值
        assertEquals(new BigDecimal("0.300"),
                YieldCalculator.deviation(d("100.000"), d("95.000"), d("5.000"), d("0.300")));
        // 完全平衡
        assertEquals(new BigDecimal("0.000"),
                YieldCalculator.deviation(d("100.000"), d("90.000"), d("8.000"), d("2.000")));
    }

    @Test
    void deviation_sampleBusinessCase_scrapOneIsOverLimit() {
        // 题干：报废 1.000 时偏差 = |100-(90+8+1)| = 1.000，超过上限 0.500
        BigDecimal deviation = YieldCalculator.deviation(
                d("100.000"), d("90.000"), d("8.000"), d("1.000"));
        assertEquals(new BigDecimal("1.000"), deviation);
        assertFalse(YieldCalculator.withinLimit(deviation, d("0.500")));
    }

    @Test
    void withinLimit_equalToLimitIsQualified() {
        // 恰好等于上限视为合格
        assertTrue(YieldCalculator.withinLimit(d("0.500"), d("0.500")));
        assertTrue(YieldCalculator.withinLimit(d("0.499"), d("0.500")));
        assertTrue(YieldCalculator.withinLimit(d("0.000"), d("0.500")));
        assertFalse(YieldCalculator.withinLimit(d("0.501"), d("0.500")));
    }

    @Test
    void anyNegative_detectsEachQuantity_zeroAllowed() {
        assertFalse(YieldCalculator.anyNegative(d("0"), d("0"), d("0"), d("0")));
        assertFalse(YieldCalculator.anyNegative(
                d("100.000"), d("90.000"), d("8.000"), d("1.500")));
        assertTrue(YieldCalculator.anyNegative(
                d("-0.001"), d("90.000"), d("8.000"), d("1.500")));
        assertTrue(YieldCalculator.anyNegative(
                d("100.000"), d("-1.000"), d("8.000"), d("1.500")));
        assertTrue(YieldCalculator.anyNegative(
                d("100.000"), d("90.000"), d("-8.000"), d("1.500")));
        assertTrue(YieldCalculator.anyNegative(
                d("100.000"), d("90.000"), d("8.000"), d("-1.500")));
    }
}
