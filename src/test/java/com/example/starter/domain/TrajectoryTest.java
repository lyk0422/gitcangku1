package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 网格穿越序列与时间桶计算的纯单元测试。
 */
class TrajectoryTest {

    @Test
    void horizontalSegmentCrossesCellsInOrder() {
        // (100,500) → (3100,500)，速度 1 m/s：进入各单元的时刻为 0/900/1900/2900 秒
        List<Trajectory.Leg> legs = Trajectory.compute(
                List.of(new Point(100, 500), new Point(3100, 500)), 0L, 1.0d);
        assertEquals(4, legs.size());
        assertEquals("0:0", legs.get(0).cellId());
        assertEquals("1:0", legs.get(1).cellId());
        assertEquals("2:0", legs.get(2).cellId());
        assertEquals("3:0", legs.get(3).cellId());
        assertEquals(0L, legs.get(0).bucketStart());
        assertEquals(900L, legs.get(1).bucketStart());
        assertEquals(1800L, legs.get(2).bucketStart());
        assertEquals(2700L, legs.get(3).bucketStart());
    }

    @Test
    void diagonalSegmentCrossesCornerIntoDiagonalCell() {
        // (0,0) → (1500,1500)：同时穿越 x/y 边界，进入对角单元
        List<Trajectory.Leg> legs = Trajectory.compute(
                List.of(new Point(0, 0), new Point(1500, 1500)), 0L, 100.0d);
        assertEquals(2, legs.size());
        assertEquals("0:0", legs.get(0).cellId());
        assertEquals("1:1", legs.get(1).cellId());
    }

    @Test
    void segmentWithinSingleCellYieldsSingleLeg() {
        List<Trajectory.Leg> legs = Trajectory.compute(
                List.of(new Point(100, 100), new Point(200, 200)), 3600L, 50.0d);
        assertEquals(1, legs.size());
        assertEquals("0:0", legs.get(0).cellId());
        assertEquals(3600L, legs.get(0).bucketStart());
    }

    @Test
    void polylineAccumulatesDistanceAcrossSegments() {
        // 两段各 1000m，速度 1000 m/s：第二段进入新单元的时刻累计第一段耗时
        List<Trajectory.Leg> legs = Trajectory.compute(
                List.of(new Point(500, 500), new Point(1500, 500), new Point(1500, 1500)),
                0L, 1000.0d);
        assertEquals(List.of("0:0", "1:0", "1:1"),
                legs.stream().map(Trajectory.Leg::cellId).toList());
        // 进入 1:0 时刻 0.5s → 桶 0；进入 1:1 时刻 1.5s → 桶 0
        assertEquals(0L, legs.get(1).bucketStart());
        assertEquals(0L, legs.get(2).bucketStart());
    }

    @Test
    void negativeCoordinatesUseFloorDivision() {
        List<Trajectory.Leg> legs = Trajectory.compute(
                List.of(new Point(-1, -1), new Point(-2000, -1)), 0L, 10.0d);
        assertEquals("-1:-1", legs.get(0).cellId());
        assertEquals("-2:-1", legs.get(1).cellId());
    }

    @Test
    void bucketOfUsesFloorForNegativeAndExactBoundaries() {
        assertEquals(0L, BucketRef.bucketOf(0.0d));
        assertEquals(0L, BucketRef.bucketOf(899.9d));
        assertEquals(900L, BucketRef.bucketOf(900.0d));
        assertEquals(-900L, BucketRef.bucketOf(-0.1d));
    }

    @Test
    void nonPositiveSpeedRejected() {
        assertThrows(IllegalArgumentException.class, () -> Trajectory.compute(
                List.of(new Point(0, 0), new Point(1, 1)), 0L, 0.0d));
    }
}
