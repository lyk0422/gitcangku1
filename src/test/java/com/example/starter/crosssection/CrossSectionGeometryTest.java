package com.example.starter.crosssection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 断面几何积分的纯单元测试：精确分数、跨水面交点、只积正深度、无外推与顺序校验。
 */
class CrossSectionGeometryTest {

    private static List<CrossSectionPoint> points(int... xz) {
        java.util.List<CrossSectionPoint> list = new java.util.ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            list.add(new CrossSectionPoint(xz[i], xz[i + 1]));
        }
        return list;
    }

    /**
     * 题干合成场景：横向 0/10、高程 0/10、水面 5，仅前半三角形有水，面积 25/2。
     * 整段梯形算 0（负深抵消）或 25（整段当有水）都必须不成立。
     */
    @Test
    void syntheticDiagonalGivesExactTriangle25Over2() {
        Fraction area = CrossSectionGeometry.positiveArea(points(0, 0, 10, 10), 5);

        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(25));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.TWO);
        assertThat(area.toScaledPlainString(3)).isEqualTo("12.500");
        // 显式排除两种错误算法
        assertThat(area).isNotEqualTo(Fraction.ZERO);
        assertThat(area.numerator().divide(area.denominator()).intValue()).isLessThan(25);
    }

    @Test
    void otherSideWetGivesSameTriangle() {
        Fraction area = CrossSectionGeometry.positiveArea(points(0, 10, 10, 0), 5);
        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(25));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.TWO);
    }

    @Test
    void fullySubmergedSegmentIsTrapezoid() {
        Fraction area = CrossSectionGeometry.positiveArea(points(0, 0, 10, 0), 5);
        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(50));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.ONE);
        assertThat(area.toScaledPlainString(3)).isEqualTo("50.000");
    }

    @Test
    void drySegmentGivesZeroAndNegativeDepthNeverCancels() {
        // 整段高于水面：0
        assertThat(CrossSectionGeometry.positiveArea(points(0, 10, 10, 10), 5)).isEqualTo(Fraction.ZERO);
        // 先湿后干的两段：干段不得抵消湿段
        Fraction twoSegments = CrossSectionGeometry.positiveArea(points(0, 0, 10, 10, 20, 0), 5);
        assertThat(twoSegments.numerator()).isEqualTo(java.math.BigInteger.valueOf(25));
        assertThat(twoSegments.denominator()).isEqualTo(java.math.BigInteger.ONE);
    }

    @Test
    void crossingPointIsComputedAsExactFraction() {
        // (0,0)->(3,10)，H=4：湿端水深 4，干端高出 6，面积 = 3*4^2/(2*10) = 12/5
        Fraction area = CrossSectionGeometry.positiveArea(points(0, 0, 3, 10), 4);
        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(12));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.valueOf(5));
        assertThat(area.toScaledPlainString(3)).isEqualTo("2.400");
    }

    @Test
    void endpointsExactlyAtWaterLineHaveZeroDepth() {
        assertThat(CrossSectionGeometry.positiveArea(points(0, 5, 10, 5), 5)).isEqualTo(Fraction.ZERO);
        // 一端恰在水面、另一端在水下：满宽三角形，宽 10、水深 0->5，面积 25
        Fraction area = CrossSectionGeometry.positiveArea(points(0, 5, 10, 0), 5);
        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(25));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.ONE);
        // 一端恰在水面、另一端在水上：仍为 0
        assertThat(CrossSectionGeometry.positiveArea(points(0, 5, 10, 10), 5)).isEqualTo(Fraction.ZERO);
    }

    @Test
    void halfUpRoundingAtThreeDecimals() {
        // 湿端水深 1、干端高 2、宽 1：面积 = 1/(2*3) = 1/6 ≈ 0.167（HALF_UP）
        Fraction sixth = CrossSectionGeometry.positiveArea(points(0, 4, 1, 7), 5);
        assertThat(sixth.numerator()).isEqualTo(java.math.BigInteger.ONE);
        assertThat(sixth.denominator()).isEqualTo(java.math.BigInteger.valueOf(6));
        assertThat(sixth.toScaledPlainString(3)).isEqualTo("0.167");
        // 2/3 场景：0.6666... -> 0.667
        assertThat(CrossSectionGeometry.positiveArea(points(0, 3, 1, 6), 5).toScaledPlainString(3))
                .isEqualTo("0.667");
    }

    @Test
    void noExtrapolationBeyondEndpoints() {
        // 首末点之外不积分：只有点间宽度 6 参与（(2,0)->(8,10)，H=5 => 6*25/20 = 15/2）
        Fraction area = CrossSectionGeometry.positiveArea(points(2, 0, 8, 10), 5);
        assertThat(area.numerator()).isEqualTo(java.math.BigInteger.valueOf(15));
        assertThat(area.denominator()).isEqualTo(java.math.BigInteger.TWO);
        // 水面低于全段地形：面积 0
        assertThat(CrossSectionGeometry.positiveArea(points(0, 10, 10, 20), 5)).isEqualTo(Fraction.ZERO);
    }

    @Test
    void rejectsNonIncreasingOrderAndTooFewPoints() {
        assertThatThrownBy(() -> CrossSectionGeometry.positiveArea(points(0, 0, 10, 0, 10, 5), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrossSectionGeometry.positiveArea(points(0, 0), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fractionNormalizesSignAndReduces() {
        Fraction f = new Fraction(java.math.BigInteger.valueOf(-6), java.math.BigInteger.valueOf(-4));
        assertThat(f.numerator()).isEqualTo(java.math.BigInteger.valueOf(3));
        assertThat(f.denominator()).isEqualTo(java.math.BigInteger.TWO);
        assertThat(new Fraction(java.math.BigInteger.valueOf(5), java.math.BigInteger.valueOf(-10)).numerator())
                .isEqualTo(java.math.BigInteger.valueOf(-1));
    }
}
