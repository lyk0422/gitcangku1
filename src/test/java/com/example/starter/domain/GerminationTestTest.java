package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检测记录发芽率计算的纯单元测试。
 */
class GerminationTestTest {

    @Test
    void ratio_isGerminatedDividedBySample_roundedHalfUpToFourDecimals() {
        GerminationTest test = new GerminationTest(
                1L, "T-1", 1L, 100, 86, TestStatus.VALID,
                LocalDateTime.of(2026, 9, 15, 9, 0), null,
                LocalDateTime.of(2026, 9, 15, 9, 0));
        assertEquals(new BigDecimal("0.8600"), test.ratio());
    }

    @Test
    void ratio_forLargerSample_keepsFourDecimals() {
        GerminationTest test = new GerminationTest(
                1L, "T-2", 1L, 200, 180, TestStatus.VALID,
                LocalDateTime.of(2026, 9, 15, 9, 0), null,
                LocalDateTime.of(2026, 9, 15, 9, 0));
        assertEquals(new BigDecimal("0.9000"), test.ratio());

        GerminationTest boundary = new GerminationTest(
                2L, "T-3", 1L, 400, 340, TestStatus.VALID,
                LocalDateTime.of(2026, 9, 15, 9, 0), null,
                LocalDateTime.of(2026, 9, 15, 9, 0));
        // 340/400 = 0.85，正好达到合格线。
        assertEquals(new BigDecimal("0.8500"), boundary.ratio());
    }
}
