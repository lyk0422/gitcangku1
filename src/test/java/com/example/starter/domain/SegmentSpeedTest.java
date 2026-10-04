package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航段速度判定单元测试：闭区间 [1,50] 米每秒，平方距离对时间阈值平方精确比较，
 * 不允许先开方舍入把 50.0001 米每秒当成 50.00 放行。
 */
@DisplayName("航段速度平方域精确判定")
class SegmentSpeedTest {

    @Test
    @DisplayName("边界值：恰好 1 与 50 米每秒均放行")
    void boundariesAreInclusive() {
        // 100 米 / 2 秒 = 50 米每秒
        assertTrue(SegmentSpeed.withinLimits(100_000L, 0L, 2L));
        // 50 米 / 1 秒 = 50 米每秒
        assertTrue(SegmentSpeed.withinLimits(50_000L, 0L, 1L));
        // 2 米 / 2 秒 = 1 米每秒
        assertTrue(SegmentSpeed.withinLimits(2_000L, 0L, 2L));
        // 3-4-5 直角边：50 米 / 1 秒 = 50 米每秒
        assertTrue(SegmentSpeed.withinLimits(30_000L, 40_000L, 1L));
    }

    @Test
    @DisplayName("100.001 米 / 2 秒 = 50.0005 米每秒，必须判超速且返回未舍入速度")
    void barelyOverMaxIsRejected() {
        assertFalse(SegmentSpeed.withinLimits(100_001L, 0L, 2L));
        // 展示用速度不得舍入成 50.00
        assertEquals(50.0005d, SegmentSpeed.speedMetersPerSecond(100_001L, 0L, 2L), 1e-9);
    }

    @Test
    @DisplayName("低于 1 米每秒与同位置两点（速度 0）均不合格")
    void belowMinAndZeroAreRejected() {
        // 1.999 米 / 2 秒 = 0.9995 米每秒
        assertFalse(SegmentSpeed.withinLimits(1_999L, 0L, 2L));
        // 同位置两点速度 0
        assertFalse(SegmentSpeed.withinLimits(0L, 0L, 5L));
    }

    @Test
    @DisplayName("平方域比较：贴近边界的千分之一米差异不被舍入放过，大时间跨度不溢出")
    void squaredComparisonKeepsMilliPrecision() {
        // 2500 米 / 50 秒 = 50 米每秒，放行
        assertTrue(SegmentSpeed.withinLimits(2_500_000L, 0L, 50L));
        // 2500.001 米 / 50 秒 = 50.00002 米每秒，超速
        assertFalse(SegmentSpeed.withinLimits(2_500_001L, 0L, 50L));
        // 1 米每秒走 100000 秒 = 100 公里，阈值平方不溢出
        assertTrue(SegmentSpeed.withinLimits(100_000_000L, 0L, 100_000L));
        // 99999.999 米 / 2000 秒 = 49.9999995 米每秒，贴近 50 上界仍放行
        assertTrue(SegmentSpeed.withinLimits(99_999_999L, 0L, 2_000L));
        // 100000.001 米 / 2000 秒 = 50.0000005 米每秒，越界
        assertFalse(SegmentSpeed.withinLimits(100_000_001L, 0L, 2_000L));
    }
}