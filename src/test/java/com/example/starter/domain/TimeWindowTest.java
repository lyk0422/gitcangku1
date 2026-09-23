package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间窗口（UTC 毫秒，左闭右开）相交判定单元测试。
 * 覆盖全时、正长度相交、仅端点相接不相交、完全分离与 null 旧记录语义。
 */
@DisplayName("时间窗口左闭右开相交语义")
class TimeWindowTest {

    private static TimeWindow bounded(long start, long end) {
        return new TimeWindow(start, end);
    }

    @Test
    @DisplayName("全时窗口与任意窗口必相交（含 null 旧记录）")
    void alwaysOverlapsAnything() {
        assertTrue(TimeWindow.overlaps(TimeWindow.ALWAYS, bounded(100, 200)));
        assertTrue(TimeWindow.overlaps(bounded(100, 200), TimeWindow.ALWAYS));
        assertTrue(TimeWindow.overlaps(TimeWindow.ALWAYS, TimeWindow.ALWAYS));
        assertTrue(TimeWindow.overlaps(null, bounded(100, 200)));
        assertTrue(TimeWindow.overlaps(bounded(100, 200), null));
    }

    @Test
    @DisplayName("存在正长度时间交集时相交（含包含与部分交叠）")
    void positiveLengthIntersectionOverlaps() {
        // 部分交叠 [1500,2000)
        assertTrue(TimeWindow.overlaps(bounded(1000, 2000), bounded(1500, 2500)));
        // 参数顺序不影响
        assertTrue(TimeWindow.overlaps(bounded(1500, 2500), bounded(1000, 2000)));
        // 一方包含另一方
        assertTrue(TimeWindow.overlaps(bounded(1000, 5000), bounded(2000, 3000)));
        // 仅相差 1 毫秒的交叠也算相交
        assertTrue(TimeWindow.overlaps(bounded(1000, 2001), bounded(2000, 3000)));
    }

    @Test
    @DisplayName("时间仅端点相接不相交（左闭右开）")
    void endpointTouchDoesNotOverlap() {
        // 一方终点等于另一方起点
        assertFalse(TimeWindow.overlaps(bounded(1000, 2000), bounded(2000, 3000)));
        assertFalse(TimeWindow.overlaps(bounded(2000, 3000), bounded(1000, 2000)));
    }

    @Test
    @DisplayName("完全分离的窗口不相交")
    void disjointWindowsDoNotOverlap() {
        assertFalse(TimeWindow.overlaps(bounded(1000, 2000), bounded(3000, 4000)));
        // 间隔 1 毫秒
        assertFalse(TimeWindow.overlaps(bounded(1000, 2000), bounded(2001, 3000)));
    }
}
