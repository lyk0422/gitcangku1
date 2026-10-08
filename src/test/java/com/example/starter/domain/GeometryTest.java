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

    // ============================ 二次贝塞尔曲线 ============================

    /** 题干合成曲线：P0=(0,0), P1=(5,10), P2=(10,0)，顶点 (5,5)。 */
    private static CurveSegment arch() {
        return new CurveSegment(new Point(0, 0), new Point(5, 10), new Point(10, 0));
    }

    @Test
    void quadraticSinglePointTangencyAtApexIsBlocked() {
        // 曲线在 (5,5) 与矩形 [4,6]×[5,6] 单点相切（顶点恰落在下边界）
        assertTrue(Geometry.quadraticHitsRectangle(arch(), 4, 5, 6, 6));
    }

    @Test
    void quadraticLoweringRectangleBelowApexClears() {
        // 矩形下界改为 6：曲线最高点仅 5，全程在矩形下方
        assertFalse(Geometry.quadraticHitsRectangle(arch(), 4, 6, 6, 7));
        // 退化高度的边界情形 yMin=yMax=6 同样不相交（直接几何判定，不经过业务校验）
        assertFalse(Geometry.quadraticHitsRectangle(arch(), 4, 6, 6, 6));
    }

    @Test
    void quadraticDistinguishesGrazingFromTinyMiss() {
        // P1=(5,10)：最大高度恰为 5，与 y=5 边界擦边 → 命中
        assertTrue(Geometry.quadraticHitsRectangle(arch(), -100, 5, 100, 100));
        // P1=(5,9)：最大高度仅 4.5，与 y=5 存在 0.5 米真实间隙 → 不得误判相切
        CurveSegment slightlyLower = new CurveSegment(
                new Point(0, 0), new Point(5, 9), new Point(10, 0));
        assertFalse(Geometry.quadraticHitsRectangle(slightlyLower, -100, 5, 100, 100));
        // P1=(10,19)：最大高度 9.5 米，与 y=10 存在 0.5 米真实间隙 → 不命中
        CurveSegment halfMeterGap = new CurveSegment(
                new Point(0, 0), new Point(10, 19), new Point(20, 0));
        assertFalse(Geometry.quadraticHitsRectangle(halfMeterGap, -100, 10, 100, 100));
        // P1=(10,20)：顶点恰 10，相切命中
        CurveSegment exactTen = new CurveSegment(
                new Point(0, 0), new Point(10, 20), new Point(20, 0));
        assertTrue(Geometry.quadraticHitsRectangle(exactTen, -100, 10, 100, 100));
    }

    @Test
    void controlPointBBoxIntersectionMustNotCauseBlock() {
        // 控制点 (50,50) 位于矩形 [49,51]×[49,51] 内，但曲线本身最大高度只有 25：
        // 以控制点包围盒判阻断会误报，真实曲线不相交
        CurveSegment seg = new CurveSegment(
                new Point(0, 0), new Point(50, 50), new Point(100, 0));
        assertFalse(Geometry.quadraticHitsRectangle(seg, 49, 49, 51, 51));
        // 而曲线真实弧顶区域 [49,51]×[24,26] 与曲线相交
        assertTrue(Geometry.quadraticHitsRectangle(seg, 49, 24, 51, 26));
    }

    @Test
    void quadraticDoesNotRelyOnDiscreteSampling() {
        // 高而窄的穿越：曲线仅在 t≈0.5 附近极窄区间穿过小矩形，
        // 稀疏离散采样会漏掉，解析法必须判命中
        CurveSegment seg = new CurveSegment(
                new Point(0, 0), new Point(50, 200), new Point(100, 0));
        // 弧顶 (50,100) 附近 1 米宽小窗
        assertTrue(Geometry.quadraticHitsRectangle(seg, 50, 99, 51, 100));
        // 弧顶正上方 1 米：曲线最高恰 100，不命中
        assertFalse(Geometry.quadraticHitsRectangle(seg, 50, 101, 51, 102));
    }

    @Test
    void quadraticDegenerateControlPointBehavesAsLine() {
        // 控制点与端点共线（恰在中点）：曲线退化为 y=10 直线
        CurveSegment horizontal = new CurveSegment(
                new Point(0, 10), new Point(50, 10), new Point(100, 10));
        assertTrue(Geometry.quadraticHitsRectangle(horizontal, 40, 5, 60, 15));
        // 与矩形底边 y=5 不相交
        assertFalse(Geometry.quadraticHitsRectangle(horizontal, 40, 0, 60, 5 - 1));
        // 控制点等于起点：曲线仍是一条直线（向 P2）
        CurveSegment collapsed = new CurveSegment(
                new Point(0, 0), new Point(0, 0), new Point(100, 20));
        assertTrue(Geometry.quadraticHitsRectangle(collapsed, 40, 5, 60, 15));
        assertFalse(Geometry.quadraticHitsRectangle(collapsed, -50, -50, -40, -40));
    }

    @Test
    void quadraticEndpointsAndBoundaryContact() {
        CurveSegment seg = arch();
        // 起点 (0,0) 落在矩形内
        assertTrue(Geometry.quadraticHitsRectangle(seg, -1, -1, 1, 1));
        // 终点 (10,0) 恰在矩形角点
        assertTrue(Geometry.quadraticHitsRectangle(seg, 10, 0, 11, 1));
        // 曲线从矩形上方绕过：矩形悬在弧顶之上
        assertFalse(Geometry.quadraticHitsRectangle(seg, 4, 6, 6, 8));
        // 矩形在曲线左侧之外
        assertFalse(Geometry.quadraticHitsRectangle(seg, -10, -10, -1, 100));
    }

    @Test
    void quadraticSideTangencyCountsAsHit() {
        // 拱形关于 x=5 对称：竖直边 x=4 与曲线交于 (4,4.8)，落在 y∈[0,5] 内 → 命中
        assertTrue(Geometry.quadraticHitsRectangle(arch(), 4, 0, 4, 5));
        // 微小未交：曲线满足 y<=4 的横向范围为 x∈[0,2.764]∪[7.236,10]，
        // 矩形 x∈[3,7] 在两侧均留有约 0.236 米真实间隙，且不含端点 → 不得命中
        assertFalse(Geometry.quadraticHitsRectangle(arch(), 3, 0, 7, 4));
        // 左边放宽到 x=2 即真实穿越 → 命中
        assertTrue(Geometry.quadraticHitsRectangle(arch(), 2, 0, 7, 4));
    }

    @Test
    void multiSegmentCurveChecksEverySegment() {
        // 两段：第一段拱在远处，第二段经过矩形；段间端点相接于 (10,0)
        CurveSegment s1 = new CurveSegment(
                new Point(0, 0), new Point(5, 10), new Point(10, 0));
        CurveSegment s2 = new CurveSegment(
                new Point(10, 0), new Point(20, 20), new Point(30, 0));
        List<CurveSegment> both = List.of(s1, s2);
        // 仅第二段弧顶 (20,10) 处的矩形命中
        assertTrue(Geometry.curveHitsRectangle(both, 19, 10, 21, 12));
        // 两段都够不到的矩形
        assertFalse(Geometry.curveHitsRectangle(both, 14, 11, 16, 12));
    }
}
