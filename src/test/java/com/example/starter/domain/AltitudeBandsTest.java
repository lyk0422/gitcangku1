package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高度带区间判定单元测试：左闭右开、端点相接合法、垂直间隔计算。
 */
class AltitudeBandsTest {

    @Test
    void overlapDetectionTreatsTouchingEndpointsAsLegal() {
        // 相交
        assertTrue(AltitudeBands.overlaps(100, 200, 150, 250));
        assertTrue(AltitudeBands.overlaps(100, 200, 100, 200));
        assertTrue(AltitudeBands.overlaps(100, 300, 150, 250));
        assertTrue(AltitudeBands.overlaps(150, 250, 100, 300));
        // 端点相接不重叠（左闭右开）
        assertFalse(AltitudeBands.overlaps(100, 200, 200, 300));
        assertFalse(AltitudeBands.overlaps(200, 300, 100, 200));
        // 完全分离
        assertFalse(AltitudeBands.overlaps(100, 200, 300, 400));
    }

    @Test
    void containsUsesLeftClosedRightOpen() {
        assertTrue(AltitudeBands.contains(100, 100, 200));
        assertTrue(AltitudeBands.contains(150, 100, 200));
        assertTrue(AltitudeBands.contains(199, 100, 200));
        // 上限不含
        assertFalse(AltitudeBands.contains(200, 100, 200));
        assertFalse(AltitudeBands.contains(99, 100, 200));
        assertFalse(AltitudeBands.contains(0, 100, 200));
    }

    @Test
    void anyOverlapChecksAllPairs() {
        assertFalse(AltitudeBands.anyOverlap(List.of()));
        assertFalse(AltitudeBands.anyOverlap(List.of(new int[]{0, 100})));
        // 端点相接合法
        assertFalse(AltitudeBands.anyOverlap(List.of(
                new int[]{0, 100}, new int[]{100, 200}, new int[]{200, 300})));
        // 任意一对重叠即检出
        assertTrue(AltitudeBands.anyOverlap(List.of(
                new int[]{0, 100}, new int[]{200, 300}, new int[]{50, 250})));
    }

    @Test
    void verticalSeparationMeasuresDistanceToBand() {
        // 带内为 0
        assertEquals(0, AltitudeBands.verticalSeparation(150, 100, 200));
        assertEquals(0, AltitudeBands.verticalSeparation(100, 100, 200));
        // 低于下限
        assertEquals(40, AltitudeBands.verticalSeparation(60, 100, 200));
        // 高于或等于上限
        assertEquals(50, AltitudeBands.verticalSeparation(250, 100, 200));
        assertEquals(0, AltitudeBands.verticalSeparation(199, 100, 200));
    }
}
