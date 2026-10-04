package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两点匀速运动间隔计算单元测试：连续时间最小距离，不做端点比较或每秒采样。
 */
class MotionTest {

    @Test
    void headOnOpposingLinesMeetAtFractionalSecond() {
        // 两条对向直线：A (0,0)->(100,0)，B (100,0)->(0,0)，区间都是 [0,11)
        // 速度 100/11 m/s，t=5.5 秒在 (50,0) 相遇；端点两两相隔 100 米
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 11,
                100, 0, 0, 0, 0, 11);
        assertTrue(minSq.isPresent());
        assertEquals(0.0, minSq.getAsDouble(), 1e-9);
        assertTrue(Motion.conflicts(0, 0, 100, 0, 0, 11, 100, 0, 0, 0, 0, 11));
    }

    @Test
    void exactlyTenMetersIsLegal() {
        // 同样对向但横向错开恰好 10 米：最小距离恰好 10 米，合法
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 11,
                100, 10, 0, 10, 0, 11);
        assertTrue(minSq.isPresent());
        assertEquals(100.0, minSq.getAsDouble(), 1e-9);
        assertFalse(Motion.conflicts(0, 0, 100, 0, 0, 11, 100, 10, 0, 10, 0, 11));
    }

    @Test
    void justBelowTenMetersConflicts() {
        // 错开 9.999 米：不足 10 米，冲突
        assertTrue(Motion.conflicts(0, 0, 100, 0, 0, 11, 100, 9.999, 0, 9.999, 0, 11));
    }

    @Test
    void touchingIntervalsHaveNoCommonOccupancy() {
        // 有效区间左闭右开：[0,11) 与 [11,22) 端点相接，无共同占用
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 11,
                100, 0, 0, 0, 11, 22);
        assertTrue(minSq.isEmpty());
        assertFalse(Motion.conflicts(0, 0, 100, 0, 0, 11, 100, 0, 0, 0, 11, 22));
    }

    @Test
    void disjointIntervalsHaveNoCommonOccupancy() {
        assertTrue(Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 10,
                0, 0, 100, 0, 20, 30).isEmpty());
    }

    @Test
    void constantRelativeDistanceWhenVelocitiesEqual() {
        // 同向同速：相对速度为零，距离恒为初始距离
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 10,
                0, 30, 100, 30, 0, 10);
        assertTrue(minSq.isPresent());
        assertEquals(900.0, minSq.getAsDouble(), 1e-9);
    }

    @Test
    void minimumInsideOverlapNotAtEndpoints() {
        // A [0,10) 从 (0,0)->(100,0)；B 静止效果：同速但区间 [4,6) 从 (45,3)->(55,3)
        // 最近点在 t=5（A 位于 (50,0)，B 位于 (50,3)），距离 3 米，端点距离都更大
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 10,
                45, 3, 55, 3, 4, 6);
        assertTrue(minSq.isPresent());
        assertEquals(9.0, minSq.getAsDouble(), 1e-9);
        assertTrue(Motion.conflicts(0, 0, 100, 0, 0, 10, 45, 3, 55, 3, 4, 6));
    }

    @Test
    void closestApproachAfterOverlapUsesOverlapEnd() {
        // 最接近点在共同区间结束之后：共同区间 [0,5) 内距离单调下降，
        // 最小值在 t->5 处取得（下确界），A (0,0)->(100,0) [0,5)，B (200,5)->(0,5) [0,20)
        OptionalDouble minSq = Motion.minDistanceSqOverOverlap(
                0, 0, 100, 0, 0, 5,
                200, 5, 0, 5, 0, 20);
        assertTrue(minSq.isPresent());
        // t=5：A 在 (100,0)，B 在 (150,5)，距离平方 2500+25
        assertEquals(2525.0, minSq.getAsDouble(), 1e-9);
    }

    @Test
    void partialOverlapStartsAtLaterIntervalStart() {
        // A [0,10) (0,0)->(100,0)；B [8,20) (0,10)->(100,10)：共同区间 [8,10)
        // 同向同速，距离恒 10 米，恰好合法
        assertFalse(Motion.conflicts(0, 0, 100, 0, 0, 10, 0, 10, 100, 10, 8, 20));
    }
}