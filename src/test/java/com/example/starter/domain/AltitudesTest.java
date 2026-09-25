package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高度带（左闭右开）与时段重叠判定单元测试。
 */
@DisplayName("高度层与时段重叠判断")
class AltitudesTest {

    @Test
    @DisplayName("巡航高度落入左闭右开高度带：下界含、上界不含")
    void bandIsLeftClosedRightOpen() {
        assertTrue(Altitudes.inBand(1000, 1000, 2000));
        assertTrue(Altitudes.inBand(1999, 1000, 2000));
        assertTrue(Altitudes.inBand(1500, 1000, 2000));
        assertFalse(Altitudes.inBand(999, 1000, 2000));
        assertFalse(Altitudes.inBand(2000, 1000, 2000));
    }

    @Test
    @DisplayName("高度带端点相接合法（不重叠），有正长度交集才算重叠")
    void touchingBandsDoNotOverlap() {
        // [1000,2000) 与 [2000,3000) 端点相接
        assertFalse(Altitudes.bandsOverlap(1000, 2000, 2000, 3000));
        assertFalse(Altitudes.bandsOverlap(2000, 3000, 1000, 2000));
        // 相隔
        assertFalse(Altitudes.bandsOverlap(1000, 2000, 3000, 4000));
        // 一单位重叠
        assertTrue(Altitudes.bandsOverlap(1000, 2001, 2000, 3000));
        // 包含
        assertTrue(Altitudes.bandsOverlap(1000, 4000, 2000, 3000));
        // 同带
        assertTrue(Altitudes.bandsOverlap(1000, 2000, 1000, 2000));
    }

    @Test
    @DisplayName("时段左闭右开：端点相接不重叠，正长度相交才重叠")
    void touchingTimeWindowsDoNotOverlap() {
        long t = 1_000_000L;
        // [t, t+100) 与 [t+100, t+200) 相接
        assertFalse(Altitudes.timeOverlaps(t, t + 100, t + 100, t + 200));
        // 相隔
        assertFalse(Altitudes.timeOverlaps(t, t + 100, t + 200, t + 300));
        // 部分重叠
        assertTrue(Altitudes.timeOverlaps(t, t + 100, t + 50, t + 150));
        // 包含
        assertTrue(Altitudes.timeOverlaps(t, t + 100, t + 10, t + 20));
        // 同时段
        assertTrue(Altitudes.timeOverlaps(t, t + 100, t, t + 100));
    }
}
