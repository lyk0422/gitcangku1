package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间窗口相交判定纯单元测试：左闭右开区间，仅端点相接不相交，任一方全时必相交。
 */
@DisplayName("时间窗口相交判定")
class TimeWindowsTest {

    @Test
    @DisplayName("任一方全时（null 对）必相交")
    void alwaysValidIntersectsEverything() {
        assertTrue(TimeWindows.overlaps(null, null, null, null));
        assertTrue(TimeWindows.overlaps(null, null, 100L, 200L));
        assertTrue(TimeWindows.overlaps(100L, 200L, null, null));
    }

    @Test
    @DisplayName("部分重叠与包含关系均相交")
    void partialOverlapAndContainment() {
        assertTrue(TimeWindows.overlaps(100L, 200L, 150L, 250L));
        assertTrue(TimeWindows.overlaps(150L, 250L, 100L, 200L));
        assertTrue(TimeWindows.overlaps(100L, 400L, 150L, 250L));
        assertTrue(TimeWindows.overlaps(150L, 250L, 100L, 400L));
        // 单点共同时刻之外的重叠：终点落入对方区间
        assertTrue(TimeWindows.overlaps(100L, 200L, 199L, 300L));
    }

    @Test
    @DisplayName("仅端点相接不相交（左闭右开）")
    void endpointTouchDoesNotIntersect() {
        assertFalse(TimeWindows.overlaps(100L, 200L, 200L, 300L));
        assertFalse(TimeWindows.overlaps(200L, 300L, 100L, 200L));
    }

    @Test
    @DisplayName("完全分离不相交")
    void disjointDoesNotIntersect() {
        assertFalse(TimeWindows.overlaps(100L, 200L, 300L, 400L));
        assertFalse(TimeWindows.overlaps(300L, 400L, 100L, 200L));
    }
}
