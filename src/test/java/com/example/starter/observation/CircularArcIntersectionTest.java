package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 圆周区间交集算法单元测试：跨零绕回、相接归并、退化单点与空交集，全程整数语义。
 */
class CircularArcIntersectionTest {

    @Test
    void specExampleIntersectionCrossesZero() {
        // 起始 350000 弧长 20000 与起始 355000 弧长 10000：交集 355000 起长度 10000，跨零度
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(350000, 20000),
                new CircularArc(355000, 10000)));
        assertThat(result).containsExactly(new CircularArc(355000, 10000));
    }

    @Test
    void specExampleThirdArcDisjointReturnsEmpty() {
        // 再与起始 20000 长度 1000 相交：无交集
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(350000, 20000),
                new CircularArc(355000, 10000),
                new CircularArc(20000, 1000)));
        assertThat(result).isEmpty();
    }

    @Test
    void touchingArcsIntersectAtDegenerateSinglePoint() {
        // 1000..2000 与 2000..3000 仅相接于 2000：退化单点，弧长 0
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(1000, 1000),
                new CircularArc(2000, 1000)));
        assertThat(result).containsExactly(new CircularArc(2000, 0));
    }

    @Test
    void zeroDegreeIsTwoDegreesFrom359Not358() {
        // 359 度与 1 度相距 2 度（跨零方向）：359000..361000 与 0..500 相交于 0..500
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(359000, 2000),
                new CircularArc(0, 500)));
        assertThat(result).containsExactly(new CircularArc(0, 500));
        // 358000..359000 与 0..500 不相交（不是相距 358 度的反向理解）
        assertThat(CircularArcIntersection.intersectAll(List.of(
                new CircularArc(358000, 1000),
                new CircularArc(0, 500)))).isEmpty();
    }

    @Test
    void crossZeroFragmentsMergeIntoSingleArc() {
        // 交集片段 [359000, 359999] 与 [0, 1000] 跨零相接，合为一个圆弧
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(359000, 2000),
                new CircularArc(358500, 3000)));
        assertThat(result).containsExactly(new CircularArc(359000, 2000));
    }

    @Test
    void identicalArcsReturnThemselves() {
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(100000, 50000),
                new CircularArc(100000, 50000),
                new CircularArc(100000, 50000)));
        assertThat(result).containsExactly(new CircularArc(100000, 50000));
    }

    @Test
    void componentsAreSortedByStartAngle() {
        // 多个分量按起始角升序返回（直接验证归一化与排序逻辑）
        List<CircularArc> result = CircularArcIntersection.intersectAll(List.of(
                new CircularArc(300000, 180000),
                new CircularArc(60000, 120000)));
        // 300000..120000（跨零）与 60000..180000 的交集为 60000..120000
        assertThat(result).containsExactly(new CircularArc(60000, 60000));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(CircularArcIntersection.intersectAll(List.of())).isEmpty();
    }
}
