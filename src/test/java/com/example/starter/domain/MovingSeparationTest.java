package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两点匀速运动间隔判定的纯单元测试。
 * 坐标以毫米（米×1000）整数传入，验证解析最小值而非端点或每秒采样。
 */
class MovingSeparationTest {

    private static long m(double meters) {
        return Math.round(meters * 1000.0);
    }

    @Test
    @DisplayName("对向直线在第 5.5 秒相遇：端点与整秒采样都安全仍判冲突")
    void opposingLinesMeetingAtHalfSecond() {
        // 运动1：(0,0)→(100,0)，t∈[0,10)，速度 10 m/s
        // 运动2：(100,0)→(0,0)，t∈[1,11)，速度 -10 m/s
        // 两者在 t=5.5 相遇于 (55,0)；端点相距 90~100 米，整秒时刻相距恰好 10 米
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(100), m(0), m(0), m(0), 1, 11);
        assertEquals(0.0, d2, 1e-9, "5.5 秒时两者位置相同，距离平方必须为 0");
        assertTrue(MovingSeparation.violatesSeparation(d2));

        // 对照：逐整秒采样的最小距离平方是 100（恰好 10 米），采样会误判为合法
        for (int t = 1; t <= 10; t++) {
            double x1 = 10.0 * t;
            double x2 = 100.0 - 10.0 * (t - 1);
            double sample = (x1 - x2) * (x1 - x2);
            assertTrue(sample >= 100.0 - 1e-9, "整秒采样最小距离恰好 10 米");
        }
    }

    @Test
    @DisplayName("恰好 10 米的平行匀速运动合法")
    void exactlyTenMetersIsLegal() {
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(0), m(10), m(100), m(10), 0, 10);
        assertEquals(100.0, d2, 1e-9);
        assertFalse(MovingSeparation.violatesSeparation(d2), "恰好 10 米必须合法");
    }

    @Test
    @DisplayName("9.999 米（3 位小数）的平行运动冲突")
    void ninePointNineNineMetersIsViolation() {
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(0), m(9.999), m(100), m(9.999), 0, 10);
        assertEquals(99.980001, d2, 1e-6);
        assertTrue(MovingSeparation.violatesSeparation(d2));
    }

    @Test
    @DisplayName("有效区间左闭右开：端点相接无共同占用")
    void touchingIntervalsHaveNoCommonOccupancy() {
        // 同一轨迹，前者 [0,10)，后者 [10,20)：t=10 不属于前者区间
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(0), m(0), m(100), m(0), 10, 20);
        assertEquals(Double.POSITIVE_INFINITY, d2);
        assertFalse(MovingSeparation.violatesSeparation(d2));
    }

    @Test
    @DisplayName("区间不相交（间隔大于零）无共同占用")
    void disjointIntervalsHaveNoOccupancy() {
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(0), m(0), m(100), m(0), 15, 25);
        assertEquals(Double.POSITIVE_INFINITY, d2);
    }

    @Test
    @DisplayName("最近时刻落在共同区间之外时按区间端点裁剪")
    void closestApproachOutsideOverlapIsClamped() {
        // 运动1：(0,0)→(100,0)，t∈[0,10)；运动2 同向同速但领先 50 米、横向偏移 5 米，
        // 共同区间 [5,10) 内相对位置恒定（相对速度为 0），距离恒为 5 米 → 冲突
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(50), m(5), m(150), m(5), 5, 15);
        assertEquals(25.0, d2, 1e-9);
        assertTrue(MovingSeparation.violatesSeparation(d2));

        // 运动2 区间改为 [20,30)：无共同占用
        double none = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(50), m(5), m(150), m(5), 20, 30);
        assertEquals(Double.POSITIVE_INFINITY, none);
    }

    @Test
    @DisplayName("同向不同速：最近点在共同区间内部解析求解")
    void sameDirectionDifferentSpeeds() {
        // 运动1：(0,0)→(100,0)，t∈[0,10)，10 m/s
        // 运动2：(0,30)→(50,30)，t∈[0,10)，5 m/s
        // 相对位置 (10t-30, -30)？x: 10t-5t=5t 起点差 0 → r(t)=(5t, -30)，
        // 顶点 t=0 在区间内：最小距离平方 = 900
        double d2 = MovingSeparation.minDistanceSquared(
                m(0), m(0), m(100), m(0), 0, 10,
                m(0), m(30), m(50), m(30), 0, 10);
        assertEquals(900.0, d2, 1e-9);
        assertFalse(MovingSeparation.violatesSeparation(d2));
    }
}