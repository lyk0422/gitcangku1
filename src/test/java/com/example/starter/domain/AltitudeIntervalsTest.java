package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高度带左闭右开与时间窗半开重叠判定单元测试。
 */
@DisplayName("高度层区间判定")
class AltitudeIntervalsTest {

    @Test
    @DisplayName("点高度进入左闭右开高度带：下限含、上限不含")
    void containsIsLeftClosedRightOpen() {
        assertTrue(AltitudeIntervals.contains(0, 0, 1000));
        assertTrue(AltitudeIntervals.contains(500, 0, 1000));
        assertTrue(AltitudeIntervals.contains(999, 0, 1000));
        // 上限不含
        assertFalse(AltitudeIntervals.contains(1000, 0, 1000));
        // 低于下限
        assertFalse(AltitudeIntervals.contains(-1, 0, 1000));
        // 相邻带端点：500 属于上一带而不属于下一带
        assertFalse(AltitudeIntervals.contains(500, 0, 500));
        assertTrue(AltitudeIntervals.contains(500, 500, 1000));
    }

    @Test
    @DisplayName("半开区间重叠：有公共内点才重叠，端点相接不重叠")
    void overlapsIsHalfOpen() {
        // 正常重叠
        assertTrue(AltitudeIntervals.overlaps(0, 100, 50, 150));
        assertTrue(AltitudeIntervals.overlaps(0, 100, 0, 100));
        // 包含关系
        assertTrue(AltitudeIntervals.overlaps(0, 100, 20, 80));
        // 端点相接（前区间上限恰为后区间下限）不算重叠
        assertFalse(AltitudeIntervals.overlaps(0, 100, 100, 200));
        assertFalse(AltitudeIntervals.overlaps(100, 200, 0, 100));
        // 完全分离
        assertFalse(AltitudeIntervals.overlaps(0, 100, 200, 300));
    }
}
