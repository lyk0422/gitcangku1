package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带孔洞多边形命中判定单元测试：题干合成场景
 * （外正方形 [0,10]、孔洞正方形 [3,7]）及边界、窄实体、多孔洞等分支。
 * 全部整数精确判定，不做采样。
 */
class PolygonGeometryTest {

    /** 外正方形 [0,10]x[0,10]，孔洞正方形 [3,7]x[3,7]。 */
    private static PolygonWithHoles squareWithHole() {
        List<Point> outer = List.of(
                new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10));
        List<Point> hole = List.of(
                new Point(3, 3), new Point(7, 3), new Point(7, 7), new Point(3, 7));
        return new PolygonWithHoles(outer, List.of(hole));
    }

    private static boolean hits(int ax, int ay, int bx, int by, PolygonWithHoles p) {
        return PolygonGeometry.segmentHitsPolygon(new Point(ax, ay), new Point(bx, by), p);
    }

    @Nested
    @DisplayName("题干合成场景：外正方形[0,10]、孔洞[3,7]")
    class HeadlineScenario {

        private final PolygonWithHoles zone = squareWithHole();

        @Test
        @DisplayName("路线 (4,4)→(6,6) 完整位于孔洞内部：CLEAR")
        void segmentFullyInsideHoleIsClear() {
            assertFalse(hits(4, 4, 6, 6, zone));
            // 孔洞内水平/垂直线段同样不命中
            assertFalse(hits(4, 5, 6, 5, zone));
            assertFalse(hits(5, 3 + 1, 5, 7 - 1, zone));
        }

        @Test
        @DisplayName("路线 (4,4)→(7,6) 端点触碰孔洞边界：BLOCKED")
        void touchingHoleEdgeIsBlocked() {
            assertTrue(hits(4, 4, 7, 6, zone));
            // 仅端点落在孔边上
            assertTrue(hits(4, 4, 7, 4, zone));
            // 沿孔边重合一段
            assertTrue(hits(3, 3, 7, 3, zone));
        }

        @Test
        @DisplayName("路线 (2,5)→(8,5) 穿越孔洞两侧实体：BLOCKED")
        void crossingSolidOnBothSidesOfHoleIsBlocked() {
            assertTrue(hits(2, 5, 8, 5, zone));
            // 反向同样成立
            assertTrue(hits(8, 5, 2, 5, zone));
            // 从孔洞内部穿出到实体：必跨孔边
            assertTrue(hits(4, 5, 9, 5, zone));
            // 从孔洞内部一路穿出外环
            assertTrue(hits(4, 5, 11, 5, zone));
        }
    }

    @Test
    @DisplayName("外环边界仍禁飞：触边、擦角、沿边重合都 BLOCKED")
    void outerBoundaryIsSolid() {
        PolygonWithHoles zone = squareWithHole();
        assertTrue(hits(-1, -1, 0, 0, zone));
        assertTrue(hits(0, 5, 0, 9, zone));
        assertTrue(hits(0, 10, 10, 10, zone));
        assertTrue(hits(10, 0, 10, 10, zone));
        // 端点在外环边上
        assertTrue(hits(5, 10, 5, 20, zone));
    }

    @Test
    @DisplayName("完全在区域外：包围盒相交也不能误判")
    void fullyOutsideIsClear() {
        PolygonWithHoles zone = squareWithHole();
        assertFalse(hits(11, 11, 20, 20, zone));
        assertFalse(hits(-5, 5, -1, 5, zone));
        // 角点外侧斜线段
        assertFalse(hits(-1, 0, 0, -1, zone));
        // 零长度线段在孔洞内
        assertFalse(hits(5, 5, 5, 5, zone));
        // 零长度线段在区域外
        assertFalse(hits(20, 20, 20, 20, zone));
    }

    @Test
    @DisplayName("不能只检查航点：两个航点都在孔洞内，连线经凹口触顶点仍 BLOCKED")
    void concaveHoleSegmentBetweenTwoInteriorPointsCanHit() {
        // L 形凹孔洞（8 顶点内）：(4,4) 与 (6,6) 都在孔洞开内部，
        // 连线恰好经过凹口顶点 (5,5)（孔边边界）→ BLOCKED
        List<Point> outer = List.of(
                new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10));
        List<Point> concaveHole = List.of(
                new Point(3, 3), new Point(7, 3), new Point(7, 7),
                new Point(5, 7), new Point(5, 5), new Point(3, 5));
        PolygonWithHoles zone = new PolygonWithHoles(outer, List.of(concaveHole));
        assertTrue(hits(4, 4, 6, 6, zone));
        // 同形状下两点连线完整留在洞内则 CLEAR
        assertFalse(hits(4, 4, 6, 4, zone));
    }

    @Test
    @DisplayName("两个孔洞：端点分属不同孔洞、中间穿越实体 → BLOCKED")
    void segmentBetweenTwoHolesCrossesSolid() {
        List<Point> outer = List.of(
                new Point(0, 0), new Point(20, 0), new Point(20, 20), new Point(0, 20));
        List<Point> h1 = List.of(new Point(2, 8), new Point(4, 8),
                new Point(4, 12), new Point(2, 12));
        List<Point> h2 = List.of(new Point(16, 8), new Point(18, 8),
                new Point(18, 12), new Point(16, 12));
        PolygonWithHoles zone = new PolygonWithHoles(outer, List.of(h1, h2));
        // 两航点都在孔洞内，但连线经过实体
        assertTrue(hits(3, 10, 17, 10, zone));
        // 各自孔洞内部的线段 CLEAR
        assertFalse(hits(3, 10, 3, 11, zone));
        // 实体中的竖直线段 BLOCKED
        assertTrue(hits(10, 0, 10, 20, zone));
    }

    @Test
    @DisplayName("无孔洞多边形按普通闭区域判定")
    void polygonWithoutHolesBehavesAsClosedRegion() {
        List<Point> triangle = List.of(
                new Point(0, 0), new Point(10, 0), new Point(0, 10));
        PolygonWithHoles zone = new PolygonWithHoles(triangle, List.of());
        // 斜边 x+y=10 为边界：接触即 BLOCKED
        assertTrue(hits(0, 0, 10, 0, zone));
        assertTrue(hits(0, 10, 10, 10, zone));
        // 三角形外侧（x+y>10）不命中
        assertFalse(hits(6, 6, 9, 9, zone));
        // 穿入三角形内部
        assertTrue(hits(-1, 6, 6, -1, zone));
    }

    @Test
    @DisplayName("折线逐段判定，不只看航点")
    void polylineChecksEverySegment() {
        PolygonWithHoles zone = squareWithHole();
        // 航点全在孔洞内/外，中间连线穿实体
        List<Point> crossing = List.of(
                new Point(4, 4), new Point(6, 6), new Point(9, 9));
        assertTrue(PolygonGeometry.polylineHitsPolygon(crossing, zone));
        // 完整留在孔洞内的折线 CLEAR
        List<Point> inside = List.of(
                new Point(4, 4), new Point(6, 4), new Point(6, 6));
        assertFalse(PolygonGeometry.polylineHitsPolygon(inside, zone));
    }
}
