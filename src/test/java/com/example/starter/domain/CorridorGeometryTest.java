package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应急走廊时空相交判定单元测试：闭矩形 × 左闭右开时间窗 × 左闭右开高度范围。
 */
class CorridorGeometryTest {

    private static final List<Point> ROUTE = List.of(new Point(0, 10), new Point(100, 10));

    /** 走廊矩形 x∈[40,60], y∈[5,15]，时间 [1000,2000)，高度 [0,1000)。 */
    private boolean hits(long s, long e, int aMin, int aMax) {
        return Geometry.polylineHitsCorridor(ROUTE, 40, 5, 60, 15,
                s, e, aMin, aMax,
                1000L, 2000L, 0, 1000);
    }

    @Test
    void fullyInsideHits() {
        assertTrue(hits(1000, 2000, 0, 1000));
        assertTrue(hits(1500, 1600, 500, 600));
    }

    @Test
    void timeHalfOpenBoundaryTouchDoesNotHit() {
        // 占用恰在走廊结束时刻开始：[2000,3000) 与 [1000,2000) 不相交
        assertFalse(hits(2000, 3000, 0, 1000));
        // 占用恰在走廊开始时刻结束：[0,1000) 不相交
        assertFalse(hits(0, 1000, 0, 1000));
        // 相差 1 毫秒即相交
        assertTrue(hits(1999, 3000, 0, 1000));
    }

    @Test
    void altitudeHalfOpenBoundaryTouchDoesNotHit() {
        // 高度下界恰为走廊上界：[1000,2000) 与 [0,1000) 不相交
        assertFalse(hits(1000, 2000, 1000, 2000));
        // 高度上界恰为走廊下界：[−1000,0) 不相交
        assertFalse(hits(1000, 2000, -1000, 0));
        // 重叠 1 米即命中
        assertTrue(hits(1000, 2000, 999, 1001));
    }

    @Test
    void routeOutsideRectangleDoesNotHit() {
        // 航线 y=10，矩形 y∈[50,60]：水平不相交，时间高度再重叠也不命中
        assertFalse(Geometry.polylineHitsCorridor(ROUTE, 40, 50, 60, 60,
                1000, 2000, 0, 1000,
                1000, 2000, 0, 1000));
    }

    @Test
    void halfOpenOverlapGeneric() {
        assertTrue(Geometry.halfOpenOverlap(1, 3, 2, 4));
        assertFalse(Geometry.halfOpenOverlap(1, 2, 2, 3));
        assertFalse(Geometry.halfOpenOverlap(2, 3, 1, 2));
    }
}
