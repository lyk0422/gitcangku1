package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTC 毫秒时间窗口相交判定单元测试：区间左闭右开，端点相接不算相交，
 * 任一方全时必相交。
 */
class TimeWindowTest {

    @Test
    void allTimeAlwaysIntersects() {
        TimeWindow all = TimeWindow.ALL_TIME;
        assertTrue(all.intersects(TimeWindow.ALL_TIME));
        assertTrue(all.intersects(new TimeWindow(0L, 10L)));
        assertTrue(new TimeWindow(-100L, -50L).intersects(all));
        // 全时窗口的起止均为 null
        assertTrue(all.allTime());
        assertFalse(new TimeWindow(1L, 2L).allTime());
    }

    @Test
    void overlappingWindowsIntersect() {
        // 普通重叠
        assertTrue(new TimeWindow(0L, 100L).intersects(new TimeWindow(50L, 150L)));
        // 一方包含另一方
        assertTrue(new TimeWindow(0L, 100L).intersects(new TimeWindow(10L, 20L)));
        // 对称
        assertTrue(new TimeWindow(50L, 150L).intersects(new TimeWindow(0L, 100L)));
        // 负时刻也按数值比较
        assertTrue(new TimeWindow(-10L, 0L).intersects(new TimeWindow(-5L, 5L)));
    }

    @Test
    void endTouchingAtSinglePointDoesNotIntersect() {
        // 左闭右开：[0,10) 与 [10,20) 仅端点相接，不相交
        assertFalse(new TimeWindow(0L, 10L).intersects(new TimeWindow(10L, 20L)));
        assertFalse(new TimeWindow(10L, 20L).intersects(new TimeWindow(0L, 10L)));
    }

    @Test
    void disjointWindowsDoNotIntersect() {
        // 中间有间隔
        assertFalse(new TimeWindow(0L, 10L).intersects(new TimeWindow(20L, 30L)));
        assertFalse(new TimeWindow(20L, 30L).intersects(new TimeWindow(0L, 10L)));
    }
}
