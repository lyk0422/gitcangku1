package com.example.starter.airspace.geom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Geometry} 单元测试：闭矩形、边界接触、端点在外而穿越等情形。
 */
class GeometryTest {

    private final Geometry.Rect r = new Geometry.Rect(0, 0, 10, 10);

    @Test
    void pointInsideIncludesBoundary() {
        assertTrue(Geometry.pointInside(new Geometry.Point(0, 0), r));
        assertTrue(Geometry.pointInside(new Geometry.Point(10, 10), r));
        assertTrue(Geometry.pointInside(new Geometry.Point(5, 0), r));
        assertFalse(Geometry.pointInside(new Geometry.Point(-1, 5), r));
        assertFalse(Geometry.pointInside(new Geometry.Point(11, 11), r));
    }

    static Stream<Arguments> segmentCases() {
        return Stream.of(
                // 端点在矩形内
                Arguments.of(new Geometry.Point(-5, 5), new Geometry.Point(5, 5), true),
                // 端点均在外、水平穿越
                Arguments.of(new Geometry.Point(-10, 5), new Geometry.Point(20, 5), true),
                // 端点均在外、斜向穿越
                Arguments.of(new Geometry.Point(-10, -10), new Geometry.Point(20, 20), true),
                // 包围盒仅在角点(0,10)重叠，但线段本身不经过该点（x=0时y=20）
                Arguments.of(new Geometry.Point(-10, 10), new Geometry.Point(0, 20), false),
                // 真正仅接触角点(0,10)的斜线（切点算相交）
                Arguments.of(new Geometry.Point(-10, 20), new Geometry.Point(0, 10), true),
                // 仅接触边
                Arguments.of(new Geometry.Point(-10, 0), new Geometry.Point(20, 0), true),
                // 沿边界
                Arguments.of(new Geometry.Point(0, 0), new Geometry.Point(10, 0), true),
                // 包围盒相交但线段在角外擦过（不接触）
                Arguments.of(new Geometry.Point(-10, -10), new Geometry.Point(-1, -1), false),
                Arguments.of(new Geometry.Point(20, -10), new Geometry.Point(11, -1), false),
                // 完全分离
                Arguments.of(new Geometry.Point(20, 20), new Geometry.Point(30, 30), false),
                Arguments.of(new Geometry.Point(5, 20), new Geometry.Point(5, 30), false),
                // 零长度点段在边界上
                Arguments.of(new Geometry.Point(0, 10), new Geometry.Point(0, 10), true),
                // 零长度点段在外部
                Arguments.of(new Geometry.Point(-1, 10), new Geometry.Point(-1, 10), false)
        );
    }

    @ParameterizedTest
    @MethodSource("segmentCases")
    void segmentIntersection(Geometry.Point a, Geometry.Point b, boolean expected) {
        assertEquals(expected, Geometry.segmentIntersects(a, b, r));
        assertEquals(expected, Geometry.segmentIntersects(b, a, r), "方向应无关");
    }

    @Test
    void polylineChecksEverySegmentNotOnlyWaypoints() {
        // 两个航点都在矩形外，但中间线段穿越矩形
        List<Geometry.Point> crossing = List.of(
                new Geometry.Point(-20, -20), new Geometry.Point(20, 20));
        assertTrue(Geometry.polylineIntersects(crossing, r));

        // 航点在外且整条折线不接触
        List<Geometry.Point> clear = List.of(
                new Geometry.Point(-20, -20), new Geometry.Point(-1, -1),
                new Geometry.Point(20, -20));
        assertFalse(Geometry.polylineIntersects(clear, r));

        // 仅某一中间线段接触边界
        List<Geometry.Point> touch = List.of(
                new Geometry.Point(-20, 0), new Geometry.Point(-11, 0),
                new Geometry.Point(0, 0), new Geometry.Point(-5, 20));
        assertTrue(Geometry.polylineIntersects(touch, r));
    }

    /**
     * 与按参数 t 细分枚举的朴素参考实现对拍，覆盖随机线段（含极端坐标）。
     */
    @Test
    void randomizedAgreesWithReference() {
        Random random = new Random(20260922L);
        Geometry.Rect big = new Geometry.Rect(-100000, -100000, 100000, 100000);
        Geometry.Rect small = new Geometry.Rect(-3, -3, 3, 3);
        for (int trial = 0; trial < 4000; trial++) {
            Geometry.Rect rect = trial % 2 == 0 ? big : small;
            Geometry.Point a = new Geometry.Point(
                    random.nextInt(200001) - 100000,
                    random.nextInt(200001) - 100000);
            Geometry.Point b = new Geometry.Point(
                    random.nextInt(200001) - 100000,
                    random.nextInt(200001) - 100000);
            assertEquals(referenceIntersects(a, b, rect),
                    Geometry.segmentIntersects(a, b, rect),
                    () -> "a=" + a + " b=" + b + " rect=" + rect);
        }
    }

    /** 朴素参考：细分 t 采样 + 端点包围，判定点是否落在闭矩形。 */
    private static boolean referenceIntersects(Geometry.Point a, Geometry.Point b, Geometry.Rect r) {
        if (a.x() == b.x() && a.y() == b.y()) {
            return Geometry.pointInside(a, r);
        }
        for (int i = 0; i <= 20000; i++) {
            double t = i / 20000.0;
            long x = Math.round(a.x() + t * (b.x() - a.x()));
            long y = Math.round(a.y() + t * (b.y() - a.y()));
            if (x >= r.xMin() && x <= r.xMax() && y >= r.yMin() && y <= r.yMax()) {
                return true;
            }
        }
        return false;
    }
}
