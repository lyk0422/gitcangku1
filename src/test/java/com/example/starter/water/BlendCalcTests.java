package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 水质混配纯计算单元测试：加权指标、四舍五入、阈值边界与指标解析。
 * 不启动 Spring、不访问数据库，与真实 H2 的 API 测试互补。
 */
class BlendCalcTests {

    private BigDecimal weighted(List<String> volumes, List<String> indexes) {
        List<BigDecimal> vs = volumes.stream().map(BigDecimal::new).toList();
        List<BigDecimal> is = indexes.stream().map(BigDecimal::new).toList();
        BigDecimal total = vs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return BlendService.weightedIndex(vs, is, total);
    }

    @Test
    void tenAtTwoAndTenAtFourWeightsExactlyThree() {
        // 题干场景：(10×2 + 10×4) / 20 = 3
        BigDecimal weighted = weighted(List.of("10", "10"), List.of("2", "4"));
        assertEquals(new BigDecimal("3.000000"), weighted);
        // 等于阈值可受理：compareTo == 0 不构成超限
        assertEquals(0, weighted.compareTo(new BigDecimal("3")));
    }

    @Test
    void weightedIndexRoundsHalfUpToSixDecimals() {
        // 5 / 3 = 1.6666666... -> 1.666667（HALF_UP）
        BigDecimal rounded = weighted(List.of("1", "2"), List.of("1", "2"));
        assertEquals(new BigDecimal("1.666667"), rounded);
        // 2 / 3 = 0.6666666... -> 0.666667
        BigDecimal other = weighted(List.of("1", "2"), List.of("0", "1"));
        assertEquals(new BigDecimal("0.666667"), other);
    }

    @Test
    void thresholdBoundaryUsesExactComparison() {
        // 舍入后的加权值恰等于阈值时受理；阈值低 0.000001 即超限
        BigDecimal weighted = weighted(List.of("1", "2"), List.of("1", "2"));
        assertEquals(0, weighted.compareTo(new BigDecimal("1.666667")));
        assertEquals(1, weighted.compareTo(new BigDecimal("1.666666")));
    }

    @Test
    void volumesWeightByProportionNotSimpleAverage() {
        // 1×2 与 3×4：(2 + 12) / 4 = 3.5，而非 (2+4)/2 = 3
        BigDecimal weighted = weighted(List.of("1", "3"), List.of("2", "4"));
        assertEquals(new BigDecimal("3.500000"), weighted);
    }

    @Test
    void parseIndexAcceptsUpToSixDecimalsAndRejectsBadInput() {
        assertEquals(new BigDecimal("3"), BlendService.parseIndex("threshold", "3"));
        assertEquals(new BigDecimal("3.123456"), BlendService.parseIndex("threshold", "3.123456"));
        assertThrows(ApiException.class, () -> BlendService.parseIndex("threshold", null));
        assertThrows(ApiException.class, () -> BlendService.parseIndex("threshold", "  "));
        assertThrows(ApiException.class, () -> BlendService.parseIndex("threshold", "abc"));
        // 超过 6 位小数
        assertThrows(ApiException.class, () -> BlendService.parseIndex("threshold", "3.1234567"));
        // 负数
        assertThrows(ApiException.class, () -> BlendService.parseIndex("threshold", "-1"));
    }

    @Test
    void zeroAndNegativeVolumesRejectedSoTotalCanNeverBeZero() {
        // 总体积为零/负的唯一入口是体积非法，入口处即 400
        assertThrows(ApiException.class, () -> WaterService.parseAmount("volume", "0"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("volume", "-10"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("volume", ""));
    }
}
