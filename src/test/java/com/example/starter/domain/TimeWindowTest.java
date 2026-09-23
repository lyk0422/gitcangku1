package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTC 毫秒半开时间窗口相交判定单元测试。
 * 区间左闭右开：仅端点相接不算相交；任一方全时（null）必相交。
 */
@DisplayName("半开时间窗口相交语义")
class TimeWindowTest {

    private static TimeWindow w(long start, long end) {
        return new TimeWindow(start, end);
    }

    @Test
    @DisplayName("正长度重叠相交")
    void overlappingWindowsIntersect() {
        assertTrue(TimeWindow.intersects(w(0, 100), w(50, 150)));
        assertTrue(TimeWindow.intersects(w(50, 150), w(0, 100)));
        // 包含关系
        assertTrue(TimeWindow.intersects(w(0, 100), w(20, 30)));
        assertTrue(TimeWindow.intersects(w(20, 30), w(0, 100)));
        // 起点相同
        assertTrue(TimeWindow.intersects(w(10, 20), w(10, 30)));
    }

    @Test
    @DisplayName("仅端点相接（前尾=后头）不相交")
    void endpointTouchDoesNotIntersect() {
        assertFalse(TimeWindow.intersects(w(0, 100), w(100, 200)));
        assertFalse(TimeWindow.intersects(w(100, 200), w(0, 100)));
    }

    @Test
    @DisplayName("完全分离的窗口不相交")
    void disjointWindowsDoNotIntersect() {
        assertFalse(TimeWindow.intersects(w(0, 10), w(20, 30)));
        assertFalse(TimeWindow.intersects(w(20, 30), w(0, 10)));
    }

    @Test
    @DisplayName("任一方全时（null）必相交")
    void allTimeAlwaysIntersects() {
        assertTrue(TimeWindow.intersects(null, w(0, 100)));
        assertTrue(TimeWindow.intersects(w(0, 100), null));
        assertTrue(TimeWindow.intersects(null, null));
    }

    @Test
    @DisplayName("一窗口恰在另一窗口内部且仅差一毫秒也算相交（左闭右开）")
    void oneMillisOverlapIntersects() {
        assertTrue(TimeWindow.intersects(w(0, 101), w(100, 200)));
        // [0,100) 与 [99,100) 在 99 这一毫秒相交
        assertTrue(TimeWindow.intersects(w(0, 100), w(99, 100)));
    }
}
