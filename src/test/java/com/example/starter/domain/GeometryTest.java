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

    // ============================ 线段到圆心包络距离 ============================

    @Test
    void interiorProjectionDistanceTouchAndMiss() {
        // 题干合成场景：线段 (0,0)-(10,0)，圆心 (5,3)，距离恰为 3
        assertTrue(Geometry.segmentWithinDistance(0, 0, 10, 0, 5, 3, 3), "接触（距离==半径和）也阻断");
        assertFalse(Geometry.segmentWithinDistance(0, 0, 10, 0, 5, 3, 2));
        // 圆心改 (5,4)：距离 4 > 3
        assertFalse(Geometry.segmentWithinDistance(0, 0, 10, 0, 5, 4, 3));
        assertTrue(Geometry.segmentWithinDistance(0, 0, 10, 0, 5, 4, 4));
    }

    @Test
    void endpointDistanceIsConsidered() {
        // 最近点为端点 (10,0)：圆心 (12,0) 距离 2
        assertTrue(Geometry.segmentWithinDistance(0, 0, 10, 0, 12, 0, 2));
        assertFalse(Geometry.segmentWithinDistance(0, 0, 10, 0, 12, 0, 1));
        // 圆心 (12,3)：到端点距离²=13，半径 3（平方 9）不够，半径 4（平方 16）命中
        assertFalse(Geometry.segmentWithinDistance(0, 0, 10, 0, 12, 3, 3));
        assertTrue(Geometry.segmentWithinDistance(0, 0, 10, 0, 12, 3, 4));
        // 反向线段同样判定（最近点为 A 端）
        assertTrue(Geometry.segmentWithinDistance(10, 0, 0, 0, -2, 0, 2));
        assertFalse(Geometry.segmentWithinDistance(10, 0, 0, 0, -2, 0, 1));
    }

    @Test
    void zeroLengthSegmentFallsBackToPointDistance() {
        assertTrue(Geometry.segmentWithinDistance(5, 5, 5, 5, 5, 8, 3));
        assertFalse(Geometry.segmentWithinDistance(5, 5, 5, 5, 5, 8, 2));
        assertTrue(Geometry.segmentWithinDistance(5, 5, 5, 5, 5, 5, 0));
    }

    @Test
    void exactSquaredDistanceWithoutSqrtRounding() {
        // 距离²=20（sqrt≈4.472），半径 4：精确比较不命中；
        // 若先开根再取整（4.472→4）会误判为命中
        assertFalse(Geometry.segmentWithinDistance(0, 0, 2, 0, 4, 4, 4));
        // 距离²=16 恰为 4²：接触命中
        assertTrue(Geometry.segmentWithinDistance(0, 0, 2, 0, 4, 0, 4));
        // 距离²=2（sqrt≈1.414），半径 1：精确不命中；取整（→1）会误判
        assertFalse(Geometry.segmentWithinDistance(0, 0, 0, 0, 1, 1, 1));
    }

    @Test
    void largeCoordinatesRemainExact() {
        // 坐标上限附近的大数：线段 (-100000,0)-(100000,0)，圆心 (0,100000)，距离恰 100000
        assertTrue(Geometry.segmentWithinDistance(-100000, 0, 100000, 0, 0, 100000, 100000));
        assertFalse(Geometry.segmentWithinDistance(-100000, 0, 100000, 0, 0, 100000, 99999));
        // 内部投影大数分支（cross² 超出 long 精度风险区）：斜线 + 远点
        // 线段 (-100000,-100000)-(100000,100000)，圆心 (100000,-100000)，
        // 距离² = (cross²)/len2 = (200000*200000)²... 精确值 2*100000²，半径 141421 不足、141422 命中
        assertFalse(Geometry.segmentWithinDistance(-100000, -100000, 100000, 100000,
                100000, -100000, 141421));
        assertTrue(Geometry.segmentWithinDistance(-100000, -100000, 100000, 100000,
                100000, -100000, 141422));
    }
}
