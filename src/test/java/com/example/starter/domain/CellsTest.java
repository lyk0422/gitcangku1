package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空域单元网格与穿越序列计算的单元测试。
 */
class CellsTest {

    @Test
    void cellIdOfUsesFloorDivision() {
        assertEquals("C0_0", Cells.cellIdOf(0, 0));
        assertEquals("C0_0", Cells.cellIdOf(999, 999));
        assertEquals("C1_1", Cells.cellIdOf(1000, 1000));
        assertEquals("C-1_-1", Cells.cellIdOf(-1, -1));
        assertEquals("C-1_0", Cells.cellIdOf(-1000, 0));
    }

    @Test
    void cellIdValidation() {
        assertTrue(Cells.isValidCellId("C0_0"));
        assertTrue(Cells.isValidCellId("C-3_4"));
        assertFalse(Cells.isValidCellId("X0_0"));
        assertFalse(Cells.isValidCellId("C0"));
        assertFalse(Cells.isValidCellId("C0_"));
        assertFalse(Cells.isValidCellId("Ca_0"));
        assertFalse(Cells.isValidCellId(null));
    }

    @Test
    void bucketAlignment() {
        assertTrue(Cells.isAlignedBucketStart(0));
        assertTrue(Cells.isAlignedBucketStart(900000));
        assertTrue(Cells.isAlignedBucketStart(-900000));
        assertFalse(Cells.isAlignedBucketStart(1000));
        assertFalse(Cells.isAlignedBucketStart(-1));
    }

    @Test
    void adjacencyIsEightNeighbor() {
        assertTrue(Cells.adjacentOrSame("C0_0", "C0_0"));
        assertTrue(Cells.adjacentOrSame("C0_0", "C1_1"));
        assertTrue(Cells.adjacentOrSame("C0_0", "C-1_0"));
        assertFalse(Cells.adjacentOrSame("C0_0", "C2_0"));
        assertFalse(Cells.adjacentOrSame("C0_0", "C0_2"));
    }

    @Test
    void traversalOfHorizontalSegment() {
        List<String> cells = Cells.traversalCells(
                List.of(new Point(100, 100), new Point(2100, 100)));
        assertEquals(List.of("C0_0", "C1_0", "C2_0"), cells);
    }

    @Test
    void traversalOfDiagonalSegmentStepsDiagonallyOnExactCorner() {
        List<String> cells = Cells.traversalCells(
                List.of(new Point(0, 0), new Point(2000, 2000)));
        assertEquals(List.of("C0_0", "C1_1", "C2_2"), cells);
    }

    @Test
    void traversalDeduplicatesConsecutiveCellsAcrossSegments() {
        List<String> cells = Cells.traversalCells(
                List.of(new Point(100, 100), new Point(200, 200), new Point(1100, 200)));
        assertEquals(List.of("C0_0", "C1_0"), cells);
    }

    @Test
    void zeroLengthSegmentYieldsSingleCell() {
        List<String> cells = Cells.traversalCells(
                List.of(new Point(100, 100), new Point(100, 100), new Point(1100, 100)));
        assertEquals(List.of("C0_0", "C1_0"), cells);
    }

    @Test
    void cellRectangleIntersectionCountsBoundaryTouch() {
        // 单元 C0_0 为闭正方形 [0,1000]x[0,1000]
        assertTrue(Cells.cellIntersectsRectangle("C0_0", 500, 500, 1500, 1500));
        assertTrue(Cells.cellIntersectsRectangle("C0_0", 1000, 0, 2000, 1000));
        assertFalse(Cells.cellIntersectsRectangle("C0_0", 1001, 0, 2000, 1000));
        assertFalse(Cells.cellIntersectsRectangle("C0_0", 0, 1001, 1000, 2000));
    }
}
