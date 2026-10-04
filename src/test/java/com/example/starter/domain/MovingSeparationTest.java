package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两点匀速航线运动间隔的解析式最小距离计算。
 * 覆盖对向相遇、恰好 10 米、区间端点夹取、端点相接无共同占用与相对静止。
 */
@DisplayName("运动间隔解析计算")
class MovingSeparationTest {

    @Test
    @DisplayName("对向直线在第 5.5 秒相遇：端点相隔 100 米仍判不安全")
    void opposingHeadOnIsUnsafe() {
        // A: (0,0)->(100,0) 于 [0,11)；B: (100,0)->(0,0) 于 [0,11)，t=5.5 时同点
        Double min = MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 11,
                100, 0, 0, 0, 0, 11);
        assertEquals(0.0, min, 1e-9);
        assertFalse(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 11,
                100, 0, 0, 0, 0, 11));
    }

    @Test
    @DisplayName("平行航线恒定相距恰好 10 米：合法")
    void exactlyTenMetersIsLegal() {
        Double min = MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                0, 10, 100, 10, 0, 10);
        assertEquals(100.0, min, 1e-9);
        assertTrue(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                0, 10, 100, 10, 0, 10));
    }

    @Test
    @DisplayName("相对运动在区间中点达到恰好 10 米：合法；9 米：不合法")
    void midIntervalMinimumRespected() {
        // B 与 A 对向、平行相距 10 米，t=5 时横向对齐，最小距离恰为 10
        assertTrue(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                100, 10, 0, 10, 0, 10));
        // 相距 9 米则拒绝
        assertFalse(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                100, 9, 0, 9, 0, 10));
    }

    @Test
    @DisplayName("端点相接的区间无共同占用；完全不相交区间返回 null")
    void touchingIntervalsHaveNoCommonOccupancy() {
        // 同一轨迹首尾相接：[0,10) 与 [10,20)，即使轨迹重合也不冲突
        assertNull(MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                0, 0, 100, 0, 10, 20));
        assertTrue(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                0, 0, 100, 0, 10, 20));
        // 完全不相交
        assertNull(MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                0, 0, 100, 0, 20, 30));
    }

    @Test
    @DisplayName("最近接近时刻落在共同区间之外时按区间端点夹取")
    void closestApproachClampedToCommonInterval() {
        // A 在 [0,10) 从 (0,0) 到 (100,0)；B 静止等价于极慢速，在 [8,20) 从 (50,3) 到 (50,3)
        // 最近点 t=5 不在共同区间 [8,10) 内，最小距离应在 t=8 处取得
        Double min = MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                50, 3, 50, 3, 8, 20);
        // t=8：A 在 (80,0)，B 在 (50,3)，距离平方 = 30^2 + 3^2 = 909
        assertEquals(909.0, min, 1e-9);
    }

    @Test
    @DisplayName("相对静止（速度差为零）时距离恒定")
    void zeroRelativeVelocity() {
        Double min = MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                0, 4, 100, 4, 0, 10);
        assertEquals(16.0, min, 1e-9);
        assertFalse(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                0, 4, 100, 4, 0, 10));
    }

    @Test
    @DisplayName("部分重叠区间只在重叠段内判定")
    void partialOverlapOnlyJudgedOnOverlap() {
        // A [0,10) 沿 x 轴速度 10；B [5,15) 在 y=20 平行同速同相：重叠 [5,10) 内距离恒为 20
        assertTrue(MovingSeparation.isSafe(
                0, 0, 100, 0, 0, 10,
                50, 20, 150, 20, 5, 15));
        Double min = MovingSeparation.minDistanceSquared(
                0, 0, 100, 0, 0, 10,
                50, 20, 150, 20, 5, 15);
        assertEquals(400.0, min, 1e-9);
    }
}