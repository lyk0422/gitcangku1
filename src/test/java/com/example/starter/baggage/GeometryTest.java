package com.example.starter.baggage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整数平面几何工具单测：旋转尺寸、越界、边界接触合法、内部重叠判定与 long 防溢出。
 */
class GeometryTest {

    @Test
    void rotation_swapsDimensions() {
        assertThat(Geometry.placedWidth(5, 10, false)).isEqualTo(5);
        assertThat(Geometry.placedHeight(5, 10, false)).isEqualTo(10);
        assertThat(Geometry.placedWidth(5, 10, true)).isEqualTo(10);
        assertThat(Geometry.placedHeight(5, 10, true)).isEqualTo(5);
    }

    @Test
    void inside_allowsBoundaryContactAndRejectsOverflow() {
        Geometry.Rect atEdge = new Geometry.Rect(5, 0, 5, 10);
        assertThat(atEdge.inside(10, 10)).isTrue();
        assertThat(new Geometry.Rect(0, 0, 10, 10).inside(10, 10)).isTrue();

        assertThat(new Geometry.Rect(6, 0, 5, 10).inside(10, 10)).isFalse();
        assertThat(new Geometry.Rect(0, 1, 10, 10).inside(10, 10)).isFalse();
        assertThat(new Geometry.Rect(-1, 0, 10, 10).inside(10, 10)).isFalse();
        assertThat(new Geometry.Rect(0, -1, 10, 10).inside(10, 10)).isFalse();
    }

    @Test
    void overlap_edgeAndPointContactIsLegal() {
        Geometry.Rect left = new Geometry.Rect(0, 0, 5, 10);
        Geometry.Rect right = new Geometry.Rect(5, 0, 5, 10);
        assertThat(left.overlaps(right)).isFalse();
        assertThat(right.overlaps(left)).isFalse();

        // 仅一个点接触
        Geometry.Rect lower = new Geometry.Rect(0, 0, 4, 4);
        Geometry.Rect upper = new Geometry.Rect(4, 4, 6, 6);
        assertThat(lower.overlaps(upper)).isFalse();

        // 内部相交 1 平方毫米即重叠
        Geometry.Rect intruding = new Geometry.Rect(4, 0, 5, 10);
        assertThat(left.overlaps(intruding)).isTrue();
        // 一者完全包含另一者
        assertThat(new Geometry.Rect(0, 0, 10, 10).overlaps(new Geometry.Rect(2, 2, 2, 2))).isTrue();
    }

    @Test
    void extremeCoordinatesUseLongArithmetic() {
        // 接近 int 上限的极端坐标与尺寸：x + 宽 = 2_000_000_000 必须按 long 计算而不溢出
        Geometry.Rect huge = Geometry.rect(1_000_000_000, 1_000_000_000, false,
                1_000_000_000, 1_000_000_000);
        assertThat(huge.right()).isEqualTo(2_000_000_000L);
        assertThat(huge.top()).isEqualTo(2_000_000_000L);
        assertThat(huge.inside(2_000_000_000L, 2_000_000_000L)).isTrue();
        assertThat(huge.inside(1_999_999_999L, 2_000_000_000L)).isFalse();
        assertThat(Geometry.area(huge)).isEqualTo(1_000_000_000_000_000_000L);
    }
}
