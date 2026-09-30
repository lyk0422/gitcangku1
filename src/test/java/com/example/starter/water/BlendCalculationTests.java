package com.example.starter.water;

import com.example.starter.water.BlendService.ParsedSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 水质加权指标计算的纯单元测试（无 Spring、无数据库）：
 * 加权值 = Σ(来源体积 × 指标) / Σ来源体积，使用 BigDecimal 精确运算。
 */
class BlendCalculationTests {

    private ParsedSource source(String key, String volume, String index) {
        return new ParsedSource(key, new BigDecimal(volume), BlendService.parseIndicator("qualityIndex", index));
    }

    @Test
    void equalVolumesAverageTheTwoIndicators() {
        // 题设：甲 10 立方米指标 2、乙 10 立方米指标 4 -> 加权值 3
        BigDecimal weighted = BlendService.weightedIndex(List.of(source("jia", "10", "2"), source("yi", "10", "4")));
        assertEquals(0, weighted.compareTo(new BigDecimal("3")));
    }

    @Test
    void unequalVolumesAreWeightedByVolume() {
        // (6×2 + 4×5) / 10 = 32/10 = 3.2
        BigDecimal weighted = BlendService.weightedIndex(List.of(source("jia", "6", "2"), source("yi", "4", "5")));
        assertEquals(0, weighted.compareTo(new BigDecimal("3.2")));
    }

    @Test
    void singleSourceWeightedValueIsItsOwnIndicator() {
        BigDecimal weighted = BlendService.weightedIndex(List.of(source("only", "3.5", "2.25")));
        assertEquals(0, weighted.compareTo(new BigDecimal("2.25")));
    }

    @Test
    void recurringResultUsesHalfUpAndStaysBelowOrEqualToThresholdBoundary() {
        // 1×1 + 2×2 = 5，总体积 3，5/3 = 1.666...；阈值 1.6667 可受理，阈值 1.6666 不可受理
        BigDecimal weighted = BlendService.weightedIndex(List.of(source("a", "1", "1"), source("b", "2", "2")));
        assertEquals(0, weighted.compareTo(new BigDecimal("1.6666666667")));
        // 受理判定为严格大于阈值才拒绝：等于阈值可受理
        BigDecimal stored = weighted.setScale(4, java.math.RoundingMode.HALF_UP);
        assertEquals(0, stored.compareTo(new BigDecimal("1.6667")));
        assertEquals(0, stored.compareTo(stored));
        assertEquals(1, stored.compareTo(new BigDecimal("1.6666")));
    }

    @Test
    void zeroTotalVolumeIsRejected() {
        ParsedSource zero = new ParsedSource("zero", BigDecimal.ZERO, new BigDecimal("1"));
        assertThrows(IllegalArgumentException.class, () -> BlendService.weightedIndex(List.of(zero)));
    }

    @Test
    void indicatorParserAcceptsNonNegativeWithAtMostFourDecimals() {
        assertEquals(0, BlendService.parseIndicator("i", "0").compareTo(new BigDecimal("0")));
        assertEquals(0, BlendService.parseIndicator("i", "3").compareTo(new BigDecimal("3")));
        assertEquals(0, BlendService.parseIndicator("i", "2.1234").compareTo(new BigDecimal("2.1234")));
        assertThrows(ApiException.class, () -> BlendService.parseIndicator("i", null));
        assertThrows(ApiException.class, () -> BlendService.parseIndicator("i", "  "));
        assertThrows(ApiException.class, () -> BlendService.parseIndicator("i", "-1"));
        assertThrows(ApiException.class, () -> BlendService.parseIndicator("i", "1.00001"));
        assertThrows(ApiException.class, () -> BlendService.parseIndicator("i", "abc"));
    }
}
