package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二次贝塞尔曲线与闭矩形精确求交的单元测试。
 *
 * <p>覆盖题干合成曲线单点相切（BLOCKED）、下界抬高后 CLEAR、控制点落在矩形内
 * 但曲线不到（不得以包围盒判阻断）、退化直线、端点/边角接触、穿入内部、
 * 侧边相切、多段连续性，以及对随机整数场景用独立密集采样交叉验证不漏判。</p>
 */
class QuadraticGeometryTest {

    private static QuadraticCurve curve(int x0, int y0, int x1, int y1, int x2, int y2) {
        return new QuadraticCurve(new Point(x0, y0), new Point(x1, y1), new Point(x2, y2));
    }

    private static boolean hits(QuadraticCurve c, int xMin, int yMin, int xMax, int yMax) {
        return Geometry.quadraticHitsRectangle(c, xMin, yMin, xMax, yMax);
    }

    @Test
    void syntheticCurveTouchesTopTangentiallyAtFiveFive() {
        // P0=(0,0),P1=(5,10),P2=(10,0)：t=0.5 到达 (5,5)
        QuadraticCurve c = curve(0, 0, 5, 10, 10, 0);
        // 与矩形 [4,6]×[5,6] 底边单点相切 → BLOCKED（内部、边界、单点相切都算）
        assertTrue(hits(c, 4, 5, 6, 6));
        // 矩形下界改为 6：曲线最高点 y=5，无实际相交 → CLEAR
        assertFalse(hits(c, 4, 6, 6, 8));
        // 下界 5 但 x 窗口错开相切点 x=5：[6,8]，切点不在窗口内 → CLEAR
        assertFalse(hits(c, 6, 5, 8, 6));
    }

    @Test
    void nearMissByHalfMeterIsReliablyDistinguished() {
        // 顶点 y=4.5：距 y=5 的边界差 0.5 米，必须 CLEAR，不能误判擦边
        QuadraticCurve below = curve(0, 0, 5, 9, 10, 0);
        assertFalse(hits(below, 0, 5, 10, 10));
        // 同曲线对下界 4：顶点 4.5 在矩形内（x=5∈[0,10]）→ BLOCKED
        assertTrue(hits(below, 0, 4, 10, 10));
    }

    @Test
    void controlPointInsideRectangleButCurveOutsideMustNotBlock() {
        // 控制点 (50,100) 在矩形内，但曲线真实顶点只有 y=50：包围盒相交不能判阻断
        QuadraticCurve c = curve(0, 0, 50, 100, 100, 0);
        assertFalse(hits(c, 45, 60, 55, 90));
        // 覆盖顶点真实位置 y=50 的矩形则应 BLOCKED
        assertTrue(hits(c, 45, 40, 55, 60));
        // 控制点远离矩形、曲线穿过矩形：不能因控制点不在包围盒内而漏判
        QuadraticCurve crossing = curve(0, 0, 50, -100, 100, 0);
        assertTrue(hits(crossing, 45, -60, 55, -40));
    }

    @Test
    void degenerateControlPointBehavesAsStraightLine() {
        // P1 为中点，三点共线：退化为 (0,10)→(100,10) 的直线段
        QuadraticCurve line = curve(0, 10, 50, 10, 100, 10);
        assertTrue(hits(line, 40, 5, 60, 15));
        assertFalse(hits(line, 40, 11, 60, 20));
        // P1 与起点重合：曲线退化，终点落在矩形内
        QuadraticCurve collapsed = curve(0, 0, 0, 0, 50, 50);
        assertTrue(hits(collapsed, 40, 40, 60, 60));
    }

    @Test
    void endpointsBoundaryAndCornerContactCountAsHit() {
        // 终点恰落在矩形左下角
        assertTrue(hits(curve(0, 0, 2, 2, 10, 10), 10, 10, 20, 20));
        // 起点在矩形内
        assertTrue(hits(curve(10, 10, 0, 0, -10, -10), 5, 5, 20, 20));
        // 曲线最高点恰好擦过矩形左下角同一高度但 x 不到 → CLEAR
        assertFalse(hits(curve(0, 0, 5, 10, 10, 0), 6, 5, 8, 8));
    }

    @Test
    void curveCrossingInteriorIsBlocked() {
        // 对称下凹弧穿过 y∈[-60,-40] 带状区域内部
        QuadraticCurve c = curve(0, 0, 50, -100, 100, 0);
        assertTrue(hits(c, 40, -60, 60, -40));
        // 同一弧在两端高处不与下方矩形相交
        assertFalse(hits(c, 0, -60, 5, -40));
    }

    @Test
    void sideEdgeTangencyAndVerticalOrientation() {
        // x 方向为凸起二次式：端点 P0/P2 都在 x=0，P1=(20,5) 时曲线最右点 x=10（t=0.5），
        // 该点 y=5；矩形左边 x=10、y 窗口含 5 → 侧边单点相切 BLOCKED
        QuadraticCurve c = curve(0, 5, 20, 5, 0, 5);
        assertTrue(hits(c, 10, 0, 20, 10));
        // y 窗口不含相切点高度 5 → CLEAR
        assertFalse(hits(c, 10, 6, 20, 10));
        // 控制点 x=20 在矩形内但曲线只到 x=10：矩形 [11,20] 不相交 → CLEAR
        assertFalse(hits(c, 11, 0, 20, 10));
    }

    @Test
    void multiSegmentRouteChecksEachSegment() {
        RouteCurve joined = new RouteCurve(List.of(
                curve(0, 0, 5, 10, 10, 0),
                curve(10, 0, 15, -10, 20, 0)));
        // 命中第一段顶点
        assertTrue(Geometry.curveHitsRectangle(joined, 4, 5, 6, 6));
        // 命中第二段下凹顶点 (15,-5)
        assertTrue(Geometry.curveHitsRectangle(joined, 14, -8, 16, -4));
        // 两段都不经过的矩形
        assertFalse(Geometry.curveHitsRectangle(joined, 40, 40, 50, 50));
    }

    @Test
    void exactJudgementAgreesWithDenseSamplingOnRandomCases() {
        // 独立的双精度密集采样只作为测试预言：采样点落入闭矩形（含 1e-9 容差）
        // 时，精确判定绝不能判 CLEAR（捕获漏判/数值擦边错误）。
        Random random = new Random(20261008L);
        int checked = 0;
        for (int iter = 0; iter < 4000; iter++) {
            int[] v = new int[6];
            for (int k = 0; k < 6; k++) {
                v[k] = random.nextInt(21) - 10; // [-10,10]
            }
            QuadraticCurve c = curve(v[0], v[1], v[2], v[3], v[4], v[5]);
            int xMin = random.nextInt(21) - 12;
            int xMax = xMin + 1 + random.nextInt(8);
            int yMin = random.nextInt(21) - 12;
            int yMax = yMin + 1 + random.nextInt(8);
            if (sampledInside(c, xMin, yMin, xMax, yMax)) {
                checked++;
                assertTrue(Geometry.quadraticHitsRectangle(c, xMin, yMin, xMax, yMax),
                        String.format("采样点已在矩形内却判 CLEAR: curve=%s rect=[%d,%d]x[%d,%d]",
                                c, xMin, xMax, yMin, yMax));
            }
        }
        assertTrue(checked > 100, "随机用例应产生足够多的正例交叉校验，实际 " + checked);
    }

    /** 独立的密集采样：是否存在 t 使点落入闭矩形（边界比较留 1e-9 数值余量）。 */
    private static boolean sampledInside(QuadraticCurve c,
                                         int xMin, int yMin, int xMax, int yMax) {
        int x0 = c.p0().x(), y0 = c.p0().y();
        int x1 = c.p1().x(), y1 = c.p1().y();
        int x2 = c.p2().x(), y2 = c.p2().y();
        for (int i = 0; i <= 20000; i++) {
            double t = i / 20000.0d;
            double u = 1.0d - t;
            double x = u * u * x0 + 2 * t * u * x1 + t * t * x2;
            double y = u * u * y0 + 2 * t * u * y1 + t * t * y2;
            if (x >= xMin - 1e-9 && x <= xMax + 1e-9
                    && y >= yMin - 1e-9 && y <= yMax + 1e-9) {
                return true;
            }
        }
        return false;
    }
}
