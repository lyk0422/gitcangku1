package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二维闭矩形相交判定单元测试：覆盖端点在内、线段穿越、边界/角接触与擦边未中。
 */
class GeometryTest {

    /** 矩形 x∈[40,60], y∈[5,15]。 */
    private static final int X_MIN = 40;
    private static final int X_MAX = 60;
    private static final int Y_MIN = 5;
    private static final int Y_MAX = 15;

    private static boolean hits(int ax, int ay, int bx, int by) {
        return Geometry.segmentHitsRectangle(ax, ay, bx, by, X_MIN, Y_MIN, X_MAX, Y_MAX);
    }

    @Test
    void endpointInsideRectangleHits() {
        assertTrue(hits(0, 10, 50, 10));
        assertTrue(hits(50, 10, 0, 10));
        // 零长度线段（重复航点）落在矩形内
        assertTrue(hits(50, 10, 50, 10));
    }

    @Test
    void segmentCrossesRectangleWithBothEndpointsOutside() {
        // 水平穿越，两端都在矩形外
        assertTrue(hits(0, 10, 100, 10));
        // 垂直穿越
        assertTrue(hits(50, 0, 50, 100));
        // 斜穿
        assertTrue(hits(0, 0, 100, 20));
    }

    @Test
    void segmentOutsideDoesNotHit() {
        // 完全在矩形下方
        assertFalse(hits(0, 0, 100, 0));
        // 上方斜线段，距离最近点仍在矩形外
        assertFalse(hits(0, 30, 100, 100));
        // 水平线段在矩形左侧即终止，未到达边界
        assertFalse(hits(0, 10, 39, 10));
        // 零长度线段在矩形外
        assertFalse(hits(0, 0, 0, 0));
    }

    @Test
    void boundaryContactCountsAsHit() {
        // 与底边重合的一段（含边界即命中）
        assertTrue(hits(0, Y_MIN, 100, Y_MIN));
        // 仅接触左下角 (40,5)：线段沿底边延长线止于角点
        assertTrue(hits(0, Y_MIN, X_MIN, Y_MIN));
        // 端点恰好在边界上
        assertTrue(hits(0, 0, X_MIN, Y_MIN));
        // 沿顶边擦过
        assertTrue(hits(45, Y_MAX, 55, Y_MAX));
        // 与右边接触的竖直线段
        assertTrue(hits(X_MAX, 0, X_MAX, 5));
    }

    @Test
    void nearMissDoesNotHit() {
        // 距边界差 1 米，不命中
        assertFalse(hits(0, Y_MIN - 1, 100, Y_MIN - 1));
        assertFalse(hits(0, 10, X_MIN - 1, 10));
        // 斜线段从角点外侧 1 米处经过：从 (0,4) 到 (39,5)，全程 x<40
        assertFalse(hits(0, 4, 39, 5));
    }

    @Test
    void polylineChecksAllSegmentsNotJustWaypoints() {
        // 两个航点都在矩形外，但唯一的连线穿越矩形
        List<Point> crossing = List.of(new Point(0, 10), new Point(100, 10));
        assertTrue(Geometry.polylineHitsRectangle(crossing, X_MIN, Y_MIN, X_MAX, Y_MAX));

        // 航点在外、连线也不经过
        List<Point> missing = List.of(new Point(0, 0), new Point(100, 0));
        assertFalse(Geometry.polylineHitsRectangle(missing, X_MIN, Y_MIN, X_MAX, Y_MAX));

        // 多段折线：首段不经过，后续线段穿越
        List<Point> laterSegment = List.of(
                new Point(0, 0), new Point(10, 10), new Point(100, 10));
        assertTrue(Geometry.polylineHitsRectangle(laterSegment, X_MIN, Y_MIN, X_MAX, Y_MAX));
    }

    // ============================ 线段-圆水平包络判定 ============================

    private static boolean withinCircle(int ax, int ay, int bx, int by,
                                        int cx, int cy, long radiusSum) {
        return Geometry.segmentWithinCircle(ax, ay, bx, by, cx, cy, radiusSum);
    }

    @Test
    void interiorProjectionExactlyAtRadiusSumHits() {
        // 题干合成场景：线段 (0,0)-(10,0)，圆心 (5,3)，半径和 3，垂距恰为 3 → 接触即命中
        assertTrue(withinCircle(0, 0, 10, 0, 5, 3, 3));
        // 圆心移到 (5,4)，垂距 4 > 3 → 不命中
        assertFalse(withinCircle(0, 0, 10, 0, 5, 4, 3));
    }

    @Test
    void interiorProjectionBeyondRadiusSumMisses() {
        // 垂距 3，半径和 2 → 不命中；半径和 4 → 命中
        assertFalse(withinCircle(0, 0, 10, 0, 5, 3, 2));
        assertTrue(withinCircle(0, 0, 10, 0, 5, 3, 4));
        // 圆心在线段下方同样判定
        assertTrue(withinCircle(0, 0, 10, 0, 5, -3, 3));
        assertFalse(withinCircle(0, 0, 10, 0, 5, -4, 3));
    }

    @Test
    void endpointDistanceCountsIncludingContact() {
        // 圆心 (-3,0) 到端点 (0,0) 距离恰为 3：接触命中；半径和 2 不命中
        assertTrue(withinCircle(0, 0, 10, 0, -3, 0, 3));
        assertFalse(withinCircle(0, 0, 10, 0, -3, 0, 2));
        // 圆心 coincides 端点：半径和 0 也命中
        assertTrue(withinCircle(0, 0, 10, 0, 0, 0, 0));
        // 最近点是端点（投影落在线段外）：(20,1) 到 (10,0) 距离平方 101
        assertFalse(withinCircle(0, 0, 10, 0, 20, 1, 10));
        assertTrue(withinCircle(0, 0, 10, 0, 20, 1, 11));
    }

    @Test
    void zeroLengthSegmentUsesPointDistance() {
        // 零长度线段（重复航点）按点到圆心距离判定
        assertTrue(withinCircle(5, 5, 5, 5, 5, 8, 3));
        assertFalse(withinCircle(5, 5, 5, 5, 5, 8, 2));
    }

    @Test
    void exactSquaredDistanceBoundaryNotRounded() {
        // 垂距平方 = cross²/len²：线段 (0,0)-(3,0)，圆心 (1,2)，垂距恰为 2
        // 半径和 2 命中（接触），1 不命中；若先开根取整会误判边界
        assertTrue(withinCircle(0, 0, 3, 0, 1, 2, 2));
        assertFalse(withinCircle(0, 0, 3, 0, 1, 2, 1));
        // 非整数垂距：线段 (0,0)-(10,0)，圆心 (5,1)，垂距 1；半径和 0 不命中
        assertFalse(withinCircle(0, 0, 10, 0, 5, 1, 0));
        assertTrue(withinCircle(0, 0, 10, 0, 5, 1, 1));
    }
}
