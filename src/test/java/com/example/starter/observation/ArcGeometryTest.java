package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 圆周区间求交纯单元测试：跨零绕回、相接跨零片段合并、退化单点、分量排序与空交集。
 * 全部整数断言，验证 359 度与 1 度相距 2 度而非 358 度，不引入浮点。
 */
class ArcGeometryTest {

    private static ArcComponent arc(int startAngle, int arcLength) {
        return new ArcComponent(startAngle, arcLength);
    }

    @Test
    void intersectsTwoArcsCrossingZero() {
        // 题干示例：起始 350000 弧长 20000 与起始 355000 弧长 10000，
        // 交集为起始 355000、弧长 10000 的跨零圆弧
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(350000, 20000)),
                List.of(arc(355000, 10000))));
        assertThat(result).containsExactly(arc(355000, 10000));
    }

    @Test
    void emptyIntersectionReturnsEmptyList() {
        // 题干示例续：再与起始 20000 弧长 1000 相交，无交集
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(350000, 20000)),
                List.of(arc(355000, 10000)),
                List.of(arc(20000, 1000))));
        assertThat(result).isEmpty();
    }

    @Test
    void treats359And1DegreeAs2DegreesApart() {
        // 起始 359000（359 度）弧长 3000 覆盖 359000..359999 与 0..1999（跨零）；
        // 与起始 0 弧长 1000（0 度起 1 度）相交得 [0, 1000)：359 度与 1 度相距 2 度而非 358 度
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(359000, 3000)),
                List.of(arc(0, 1000))));
        assertThat(result).containsExactly(arc(0, 1000));
        // 同一跨零圆弧与起始 100000（100 度）弧长 1000 不相交：圆周距离按模 360000 计算
        assertThat(ArcGeometry.intersectAll(List.of(
                List.of(arc(359000, 3000)),
                List.of(arc(100000, 1000))))).isEmpty();
    }

    @Test
    void mergesAdjacentCrossZeroFragmentsIntoSingleArc() {
        // 两条都跨零的圆弧相交，交集在零度两侧各一段，相接片段合为一个圆弧
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(350000, 30000)),
                List.of(arc(340000, 40000))));
        // 交集 [350000, 360000) 与 [0, 20000) 合并为起始 350000、弧长 30000
        assertThat(result).containsExactly(arc(350000, 30000));
    }

    @Test
    void degenerateSinglePointIntersectionIsAllowed() {
        // 两段仅在一个整数刻度点重叠：退化单点以弧长 1 表示
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(10000, 5000)),
                List.of(arc(14999, 8000))));
        assertThat(result).containsExactly(arc(14999, 1));
    }

    @Test
    void singlePointAtZeroWrapBoundary() {
        // 起始 359999 弧长 2 覆盖 359999 与 0 两点；与起始 0 的半圆相交只剩零点
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(359999, 2)),
                List.of(arc(0, 180000))));
        assertThat(result).containsExactly(arc(0, 1));
    }

    @Test
    void multipleComponentsReturnedSortedByStartAngle() {
        // 一条观测含两段圆弧，另一条覆盖其部分：交集两个分量按起始角升序
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(100000, 5000), arc(10000, 5000)),
                List.of(arc(0, 200000))));
        assertThat(result).containsExactly(arc(10000, 5000), arc(100000, 5000));
    }

    @Test
    void intersectionIsIndependentOfArcSplitting() {
        // 同一覆盖范围无论拆成几段输入，交集结果一致（输入先规范化合并）
        List<ArcComponent> split = ArcGeometry.intersectAll(List.of(
                List.of(arc(0, 90000), arc(90000, 90000)),
                List.of(arc(45000, 90000))));
        List<ArcComponent> whole = ArcGeometry.intersectAll(List.of(
                List.of(arc(0, 180000)),
                List.of(arc(45000, 90000))));
        assertThat(split).containsExactly(arc(45000, 90000));
        assertThat(whole).containsExactly(arc(45000, 90000));
    }

    @Test
    void disjointArcSetsYieldEmptyIntersection() {
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(0, 100000)),
                List.of(arc(180000, 100000))));
        assertThat(result).isEmpty();
    }

    @Test
    void intersectionAcrossThreeObservations() {
        // 三条观测逐层收窄，交集为三者共同覆盖的跨零片段
        List<ArcComponent> result = ArcGeometry.intersectAll(List.of(
                List.of(arc(300000, 120000)),
                List.of(arc(350000, 30000)),
                List.of(arc(355000, 10000))));
        assertThat(result).containsExactly(arc(355000, 10000));
    }
}
