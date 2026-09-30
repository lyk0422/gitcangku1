package com.example.starter.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.po.TideSegmentPo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 潮高校核工具纯单元测试：区间缺口判定与最低有效潮高。
 */
class TideCalculatorTest {

    private static final LocalDateTime T06 = LocalDateTime.of(2026, 10, 1, 6, 0);
    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);
    private static final LocalDateTime T09 = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime T12 = LocalDateTime.of(2026, 10, 1, 12, 0);

    private TideSegmentPo segment(LocalDateTime start, LocalDateTime end, String height) {
        return new TideSegmentPo(1L, 1L, start, end, new BigDecimal(height));
    }

    @Test
    void coversSingleSegmentSpanningWindow() {
        List<TideSegmentPo> segments = List.of(segment(T06, T12, "0.70"));
        assertTrue(TideCalculator.covers(segments, T08, T10));
    }

    @Test
    void coversAdjacentSegmentsWithoutGap() {
        List<TideSegmentPo> segments = List.of(
                segment(T06, T09, "0.90"), segment(T09, T12, "0.70"));
        assertTrue(TideCalculator.covers(segments, T08, T10));
    }

    @Test
    void detectsGapInsideWindow() {
        List<TideSegmentPo> segments = List.of(
                segment(T06, T09, "0.90"), segment(T09.plusHours(1), T12, "0.70"));
        assertFalse(TideCalculator.covers(segments, T08, T12));
    }

    @Test
    void detectsWindowBeyondCoverage() {
        List<TideSegmentPo> segments = List.of(segment(T06, T10, "0.70"));
        assertFalse(TideCalculator.covers(segments, T08, T12));
        assertFalse(TideCalculator.covers(segments, T06.minusHours(1), T08));
    }

    @Test
    void minHeightAcrossWholeWindowUsesLowestSegment() {
        List<TideSegmentPo> segments = List.of(
                segment(T06, T09, "0.90"), segment(T09, T12, "0.70"));
        assertEquals(0, new BigDecimal("0.70").compareTo(
                TideCalculator.minHeight(segments, T08, T10)));
    }

    @Test
    void minHeightSupportsNegativeTide() {
        List<TideSegmentPo> segments = List.of(
                segment(T06, T09, "-0.30"), segment(T09, T12, "0.70"));
        assertEquals(0, new BigDecimal("-0.30").compareTo(
                TideCalculator.minHeight(segments, T08, T10)));
    }
}
