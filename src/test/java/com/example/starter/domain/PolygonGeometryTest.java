package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带孔洞多边形几何与环校验的单元测试。
 * 合成场景：外环正方形 [0,10]×[0,10]，孔洞正方形 [3,7]×[3,7]。
 */
class PolygonGeometryTest {

    /** 题设合成区域：外正方形 [0,10]，孔洞正方形 [3,7]。 */
    private static Polygon squareWithHole() {
        List<Point> outer = List.of(
                new Point(0, 0), new Point(10, 0),
                new Point(10, 10), new Point(0, 10));
        List<Point> hole = List.of(
                new Point(3, 3), new Point(7, 3),
                new Point(7, 7), new Point(3, 7));
        return new Polygon(outer, List.of(hole));
    }

    private static List<Point> route(int ax, int ay, int bx, int by) {
        return List.of(new Point(ax, ay), new Point(bx, by));
    }

    @Nested
    @DisplayName("题设可观察结果：[0,10] 外环 + [3,7] 孔洞")
    class ObservableResults {

        private final Polygon zone = squareWithHole();

        @Test
        @DisplayName("路线 (4,4)→(6,6) 完整位于孔洞内部：CLEAR")
        void fullyInsideHoleIsClear() {
            assertFalse(zone.polylineHits(route(4, 4, 6, 6)));
        }

        @Test
        @DisplayName("路线 (4,4)→(7,6) 端点触碰孔洞边界：BLOCKED")
        void endpointTouchingHoleEdgeIsBlocked() {
            assertTrue(zone.polylineHits(route(4, 4, 7, 6)));
        }

        @Test
        @DisplayName("路线 (2,5)→(8,5) 横穿孔洞两侧实体并跨越孔边：BLOCKED")
        void crossingSolidAndHoleIsBlocked() {
            assertTrue(zone.polylineHits(route(2, 5, 8, 5)));
        }
    }

    @Nested
    @DisplayName("实体、边界与合法通道的区分")
    class SolidBoundaryAndCorridor {

        private final Polygon zone = squareWithHole();

        @Test
        @DisplayName("完整位于孔洞上方实体带内的线段：BLOCKED")
        void segmentInsideSolidIsBlocked() {
            assertTrue(zone.polylineHits(route(2, 8, 8, 8)));
        }

        @Test
        @DisplayName("沿外环边的线段：BLOCKED（外环边界仍禁飞）")
        void outerBoundaryIsBlocked() {
            assertTrue(zone.polylineHits(route(0, 0, 10, 0)));
            assertTrue(zone.polylineHits(route(0, 2, 0, 8)));
        }

        @Test
        @DisplayName("端点恰在外环角点：BLOCKED")
        void outerCornerIsBlocked() {
            assertTrue(zone.polylineHits(route(-5, -5, 0, 0)));
            assertTrue(zone.polylineHits(route(0, 10, 0, 10)));
        }

        @Test
        @DisplayName("沿孔洞边界的线段：BLOCKED（孔洞边界仍禁飞）")
        void holeBoundaryIsBlocked() {
            assertTrue(zone.polylineHits(route(3, 3, 7, 3)));
            assertTrue(zone.polylineHits(route(3, 5, 3, 7)));
        }

        @Test
        @DisplayName("孔洞内零长度航点（重复航点）：CLEAR")
        void zeroLengthInsideHoleIsClear() {
            assertFalse(zone.polylineHits(route(5, 5, 5, 5)));
        }

        @Test
        @DisplayName("实体带内零长度航点：BLOCKED")
        void zeroLengthInsideSolidIsBlocked() {
            assertTrue(zone.polylineHits(route(5, 8, 5, 8)));
        }

        @Test
        @DisplayName("完全在外环之外的路线：CLEAR")
        void fullyOutsideIsClear() {
            assertFalse(zone.polylineHits(route(20, 20, 30, 30)));
            assertFalse(zone.polylineHits(route(-5, 5, -1, 5)));
        }

        @Test
        @DisplayName("从孔洞内部穿出到实体（与孔边交于非整数点 (7,5.8)）：BLOCKED，不依赖采样")
        void rationalIntersectionExitIsBlocked() {
            // (4,4)→(9,7)：与孔边 x=7 交于 (7,5.8)，端点 (9,7) 位于实体
            assertTrue(zone.polylineHits(route(4, 4, 9, 7)));
        }

        @Test
        @DisplayName("孔洞下方实体带内横穿：BLOCKED")
        void solidBandBelowHoleIsBlocked() {
            assertTrue(zone.polylineHits(route(2, 2, 8, 2)));
        }
    }

    @Nested
    @DisplayName("两个孔洞：分离通道与跨孔路线")
    class TwoHoles {

        private Polygon twoHoles() {
            List<Point> outer = List.of(
                    new Point(0, 0), new Point(20, 0),
                    new Point(20, 10), new Point(0, 10));
            List<Point> left = List.of(
                    new Point(2, 3), new Point(8, 3),
                    new Point(8, 7), new Point(2, 7));
            List<Point> right = List.of(
                    new Point(12, 3), new Point(18, 3),
                    new Point(18, 7), new Point(12, 7));
            return new Polygon(outer, List.of(left, right));
        }

        @Test
        @DisplayName("分别完整在两个孔洞内：CLEAR")
        void insideEitherHoleIsClear() {
            Polygon z = twoHoles();
            assertFalse(z.polylineHits(route(3, 4, 7, 6)));
            assertFalse(z.polylineHits(route(13, 4, 17, 6)));
        }

        @Test
        @DisplayName("从孔洞1内部穿中间实体带到孔洞2内部：BLOCKED（触孔边+穿实体）")
        void betweenHolesThroughSolidIsBlocked() {
            assertTrue(twoHoles().polylineHits(route(5, 5, 15, 5)));
        }

        @Test
        @DisplayName("多段折线：孔洞内合法段 + 穿出实体段，整体 BLOCKED（不只看航点）")
        void polylineWithOneBadSegmentIsBlocked() {
            List<Point> poly = List.of(
                    new Point(5, 5), new Point(5, 5),
                    new Point(10, 5), new Point(3, 4));
            assertTrue(twoHoles().polylineHits(poly));
        }
    }

    @Nested
    @DisplayName("无孔洞多边形退化为普通闭区域")
    class NoHoles {

        @Test
        @DisplayName("三角形区域：内部命中、外部 CLEAR、边接触 BLOCKED")
        void triangleWorks() {
            Polygon tri = new Polygon(List.of(
                    new Point(0, 0), new Point(10, 0), new Point(5, 10)), List.of());
            assertTrue(tri.polylineHits(route(0, 2, 10, 2)));
            assertFalse(tri.polylineHits(route(0, 20, 10, 20)));
            // 沿三角形左边
            assertTrue(tri.polylineHits(route(0, 0, 5, 10)));
        }
    }

    @Nested
    @DisplayName("环合法性校验：非法形状一律拒绝")
    class Validation {

        private final List<Point> squareOuter = List.of(
                new Point(0, 0), new Point(10, 0),
                new Point(10, 10), new Point(0, 10));

        @Test
        @DisplayName("合法区域通过校验")
        void validShapesAccepted() {
            assertDoesNotThrow(() -> PolygonValidator.validate(squareOuter, List.of()));
            assertDoesNotThrow(() -> PolygonValidator.validate(squareOuter, List.of(List.of(
                    new Point(3, 3), new Point(7, 3),
                    new Point(7, 7), new Point(3, 7)))));
        }

        @Test
        @DisplayName("首尾顶点重复：拒绝")
        void duplicatedFirstLastRejected() {
            List<Point> ring = List.of(
                    new Point(0, 0), new Point(10, 0),
                    new Point(10, 10), new Point(0, 10), new Point(0, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(ring, List.of()));
        }

        @Test
        @DisplayName("顶点数越界（外环 <3 或 >10，孔洞 >8）：拒绝")
        void vertexCountOutOfRangeRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(
                            List.of(new Point(0, 0), new Point(10, 0)), List.of()));
            List<Point> tooBig = java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> new Point(i, i))
                    .toList();
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(tooBig, List.of()));
            List<Point> bigHole = java.util.stream.IntStream.range(0, 9)
                    .mapToObj(i -> new Point(1 + i / 3, 1 + i % 3))
                    .distinct().toList();
            // 9 个不同点的孔洞超出 8 个上限
            if (bigHole.size() == 9) {
                assertThrows(IllegalArgumentException.class,
                        () -> PolygonValidator.validate(squareOuter, List.of(bigHole)));
            }
        }

        @Test
        @DisplayName("自交蝴蝶结四边形：拒绝")
        void selfIntersectingRingRejected() {
            List<Point> bowtie = List.of(
                    new Point(0, 0), new Point(10, 10),
                    new Point(0, 10), new Point(10, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(bowtie, List.of()));
        }

        @Test
        @DisplayName("孔洞顶点贴在外环边上：拒绝（必须严格内含）")
        void holeTouchingOuterEdgeRejected() {
            List<Point> hole = List.of(
                    new Point(3, 0), new Point(7, 0),
                    new Point(7, 5), new Point(3, 5));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(squareOuter, List.of(hole)));
        }

        @Test
        @DisplayName("孔洞整体越出外环：拒绝")
        void holeOutsideOuterRejected() {
            List<Point> hole = List.of(
                    new Point(8, 8), new Point(12, 8),
                    new Point(12, 12), new Point(8, 12));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(squareOuter, List.of(hole)));
        }

        @Test
        @DisplayName("两个孔洞互相接触：拒绝")
        void touchingHolesRejected() {
            List<Point> a = List.of(
                    new Point(1, 4), new Point(5, 4),
                    new Point(5, 8), new Point(1, 8));
            // b 的左边 x=5 与 a 的右边 x=5 重合一段
            List<Point> b = List.of(
                    new Point(5, 4), new Point(9, 4),
                    new Point(9, 8), new Point(5, 8));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(squareOuter, List.of(a, b)));
        }

        @Test
        @DisplayName("超过 2 个孔洞：拒绝")
        void tooManyHolesRejected() {
            List<Point> h1 = List.of(
                    new Point(1, 1), new Point(2, 1), new Point(2, 2), new Point(1, 2));
            List<Point> h2 = List.of(
                    new Point(4, 1), new Point(5, 1), new Point(5, 2), new Point(4, 2));
            List<Point> h3 = List.of(
                    new Point(7, 1), new Point(8, 1), new Point(8, 2), new Point(7, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> PolygonValidator.validate(squareOuter, List.of(h1, h2, h3)));
        }
    }
}
