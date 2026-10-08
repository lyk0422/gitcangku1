package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带孔洞多边形合法性校验单元测试：顶点数量、退化、自交、
 * 孔洞严格内含、孔洞互不相交不接触等 400 前置条件。
 */
class PolygonValidatorTest {

    private static Point p(int x, int y) {
        return new Point(x, y);
    }

    private static List<Point> square(int x0, int y0, int x1, int y1) {
        return List.of(p(x0, y0), p(x1, y0), p(x1, y1), p(x0, y1));
    }

    @Test
    @DisplayName("题干合成形状合法：外环10以内、孔洞8以内")
    void headlineShapeIsValid() {
        assertDoesNotThrow(() -> PolygonValidator.validate(
                square(0, 0, 10, 10), List.of(square(3, 3, 7, 7))));
    }

    @Test
    @DisplayName("无孔洞、3 顶点三角形合法；顶点与孔洞被不可变拷贝")
    void triangleWithoutHolesIsValid() {
        PolygonWithHoles polygon = assertDoesNotThrow(
                () -> PolygonValidator.validate(
                        List.of(p(0, 0), p(10, 0), p(0, 10)), List.of()));
        assertEquals(3, polygon.outer().size());
        assertTrue(polygon.holes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> polygon.outer().add(p(1, 1)));
    }

    @Test
    @DisplayName("顶点数越界（外环>10、孔洞>8、不足3）非法")
    void vertexCountBounds() {
        List<Point> eleven = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> p(i, i)).toList();
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(eleven, List.of()));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(List.of(p(0, 0), p(1, 0)), List.of()));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10),
                        List.of(List.of(p(3, 3), p(4, 3)))));
        // 孔洞 9 顶点
        List<Point> nine = List.of(
                p(1, 1), p(8, 1), p(8, 8), p(1, 8),
                p(1, 7), p(7, 7), p(7, 2), p(2, 2), p(2, 1));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10), List.of(nine)));
        // 3 个孔洞
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 20, 20),
                        List.of(square(1, 1, 2, 2), square(5, 5, 6, 6),
                                square(9, 9, 10, 10))));
    }

    @Test
    @DisplayName("重复顶点（含相邻）非法")
    void duplicateVertexRejected() {
        List<Point> dup = List.of(p(0, 0), p(10, 0), p(10, 10), p(10, 10));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(dup, List.of()));
    }

    @Test
    @DisplayName("共线退化环（零面积）非法")
    void collinearDegenerateRingRejected() {
        List<Point> line = List.of(p(0, 0), p(5, 0), p(10, 0));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(line, List.of()));
        // 面积为零但边不全共线的蝴蝶结同样拒绝（面积零或自交二者必中）
        List<Point> bowtie = List.of(p(0, 0), p(10, 10), p(10, 0), p(0, 10));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(bowtie, List.of()));
    }

    @Test
    @DisplayName("非相邻边自交非法")
    void selfIntersectingRingRejected() {
        // 凹多边形的边穿入内部
        List<Point> selfCross = List.of(
                p(0, 0), p(10, 0), p(0, 10), p(10, 10), p(5, 5));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(selfCross, List.of()));
    }

    @Test
    @DisplayName("孔洞顶点落在外环边上或外部非法（必须严格内含）")
    void holeMustBeStrictlyInside() {
        // 顶点恰在外环边上
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10),
                        List.of(square(0, 1, 5, 9))));
        // 孔洞整体在外
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10),
                        List.of(square(11, 11, 15, 15))));
        // 孔洞跨出外环（部分顶点在外）
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10),
                        List.of(square(8, 8, 12, 12))));
    }

    @Test
    @DisplayName("孔洞与外环边接触非法（非顶点的边接触）")
    void holeEdgeTouchingOuterEdgeRejected() {
        // 孔洞顶点严格在内，但一条边与外环边共线接触不可能在顶点全在内时发生；
        // 改用孔边穿过外环边的情形（已有跨出覆盖），这里验证孔边与外环边交叉
        List<Point> crossing = List.of(p(-1, 5), p(5, 5), p(5, 6), p(-1, 6));
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 10, 10), List.of(crossing)));
    }

    @Test
    @DisplayName("孔洞之间相交、接触、包含均非法；分离合法")
    void holesMustNotIntersectOrTouch() {
        // 边相交
        List<Point> h1 = square(2, 2, 8, 8);
        List<Point> h2 = square(6, 6, 12, 12).stream()
                .map(q -> p(q.x() - 2, q.y() - 2)).toList(); // (4,4)-(10,10) 与 h1 相交
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 20, 20), List.of(h1, h2)));
        // 边接触（共享 x=8 边）
        List<Point> touch1 = square(2, 2, 8, 8);
        List<Point> touch2 = square(8, 2, 12, 8);
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 20, 20),
                        List.of(touch1, touch2)));
        // 一个孔洞包含另一个
        assertThrows(InvalidPolygonException.class,
                () -> PolygonValidator.validate(square(0, 0, 20, 20),
                        List.of(square(2, 2, 16, 16), square(5, 5, 8, 8))));
        // 严格分离合法
        assertDoesNotThrow(() -> PolygonValidator.validate(square(0, 0, 20, 20),
                List.of(square(2, 2, 6, 6), square(12, 12, 16, 16))));
    }

    @Test
    @DisplayName("反向环绕顺序同样合法（方向不限，顺序即连接关系）")
    void reversedWindingIsValid() {
        List<Point> outer = List.of(p(0, 0), p(0, 10), p(10, 10), p(10, 0));
        List<Point> hole = List.of(p(3, 3), p(3, 7), p(7, 7), p(7, 3));
        PolygonWithHoles polygon = assertDoesNotThrow(
                () -> PolygonValidator.validate(outer, List.of(hole)));
        assertEquals(List.of(p(0, 0), p(0, 10), p(10, 10), p(10, 0)),
                polygon.outer());
    }
}
