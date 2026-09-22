package com.example.starter;

import com.example.starter.domain.Geometry;
import com.example.starter.domain.Point;
import com.example.starter.domain.Zone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 几何计算纯单元测试：闭矩形与航线（点列+线段）的相交、包含与边界接触判定。
 */
class GeometryTest {

    private static final Zone ZONE = new Zone("z1", 0, 0, 10, 10, true);

    @Test
    void segmentCrossingZoneWithBothEndpointsOutsideHits() {
        // 线段 (-5,5)-(15,5) 两端均在区域外但横穿区域。
        List<Point> route = List.of(new Point(-5, 5), new Point(15, 5));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void diagonalCrossingZoneHits() {
        List<Point> route = List.of(new Point(-5, -5), new Point(15, 15));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void pointInsideZoneHits() {
        List<Point> route = List.of(new Point(5, 5), new Point(20, 20));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void pointOnBoundaryHits() {
        // 航点恰好落在区域边界上（闭矩形，接触即命中）。
        List<Point> route = List.of(new Point(0, 5), new Point(20, 20));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void segmentTouchingBoundaryHits() {
        // 线段 (-5,10)-(15,10) 与区域上边重合接触。
        List<Point> route = List.of(new Point(-5, 10), new Point(15, 10));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void segmentTouchingCornerHits() {
        // 线段 (-5,5)-(0,10) 恰好接触区域左上角 (0,10)。
        List<Point> route = List.of(new Point(-5, 5), new Point(0, 10));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void segmentCollinearWithEdgeExtensionHits() {
        // 线段 (12,0)-(20,0) 与底边共线但不相交：不命中。
        List<Point> outside = List.of(new Point(12, 0), new Point(20, 0));
        assertFalse(Geometry.routeIntersectsZone(outside, ZONE));
        // 线段 (8,0)-(20,0) 与底边共线且重叠：命中。
        List<Point> overlapping = List.of(new Point(8, 0), new Point(20, 0));
        assertTrue(Geometry.routeIntersectsZone(overlapping, ZONE));
    }

    @Test
    void routeFullyOutsideMisses() {
        List<Point> route = List.of(new Point(-5, -5), new Point(-1, -1), new Point(20, -3));
        assertFalse(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void routePassingNearCornerMisses() {
        // 线段 (-5,-1)-(-1,-5) 从区域左下角外侧掠过。
        List<Point> route = List.of(new Point(-5, -1), new Point(-1, -5));
        assertFalse(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void multiSegmentRouteHitsWhenMiddleSegmentCrosses() {
        // 三段航线，仅中间段穿越区域。
        List<Point> route = List.of(
                new Point(-20, 50), new Point(-5, 5), new Point(15, 5), new Point(30, 50));
        assertTrue(Geometry.routeIntersectsZone(route, ZONE));
    }

    @Test
    void largeCoordinatesDoNotOverflow() {
        // 极值坐标下的叉积计算不溢出。
        Zone far = new Zone("z2", -100000, -100000, -99990, -99990, true);
        List<Point> route = List.of(new Point(100000, 100000), new Point(99990, 99990));
        assertFalse(Geometry.routeIntersectsZone(route, far));
        List<Point> crossing = List.of(new Point(-100005, -99995), new Point(-99985, -99995));
        assertTrue(Geometry.routeIntersectsZone(crossing, far));
    }
}
