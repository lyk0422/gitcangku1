package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断面水域面积精确计算单元测试：跨水面交点精确求取、只积分正深度、两端之外不外推。
 */
class WaterAreaCalculatorTest {

    private static WaterAreaCalculator.Point point(long lateralMm, long elevationMm) {
        return new WaterAreaCalculator.Point(lateralMm, elevationMm);
    }

    private static void assertArea(List<WaterAreaCalculator.Point> points, long waterLevelMm,
                                   String numerator, String denominator) {
        WaterAreaCalculator.Fraction area = WaterAreaCalculator.computeArea(points, waterLevelMm);
        assertThat(area.numerator()).isEqualTo(new BigInteger(numerator));
        assertThat(area.denominator()).isEqualTo(new BigInteger(denominator));
    }

    @Test
    void syntheticCrossingSegmentYieldsHalfTriangle() {
        // 合成场景：横向 0 与 10 毫米、高程 0 与 10 毫米、水面 5 毫米，只有前半三角形有水
        assertArea(List.of(point(0, 0), point(10, 10)), 5, "25", "2");
    }

    @Test
    void fullySubmergedSegmentYieldsTrapezoid() {
        // 整段正深度：梯形 (5+5)*10/2 = 50，不向两端之外外推
        assertArea(List.of(point(0, 0), point(10, 0)), 5, "50", "1");
        // 变深梯形：(3+5)*10/2 = 40
        assertArea(List.of(point(0, 2), point(10, 0)), 5, "40", "1");
    }

    @Test
    void fullyDrySegmentYieldsZero() {
        assertArea(List.of(point(0, 10), point(10, 10)), 5, "0", "1");
        // 贴水（深度为 0）不产生面积
        assertArea(List.of(point(0, 5), point(10, 5)), 5, "0", "1");
    }

    @Test
    void risingCrossingIntegratesOnlyPositiveSide() {
        // 深度 (-5, 5)：仅右侧三角形 25/2，负深度不抵消
        assertArea(List.of(point(0, 10), point(10, 0)), 5, "25", "2");
    }

    @Test
    void multiSegmentWithPartialWaterSumsPositiveParts() {
        // V 形断面：两段各贡献 25/2，合计 25
        assertArea(List.of(point(0, 0), point(10, 10), point(20, 0)), 5, "25", "1");
    }

    @Test
    void noExtrapolationBeyondSectionEnds() {
        // 两端点之外不外推：水下段仅覆盖 [0,10]，端点外水面不计
        assertArea(List.of(point(0, 0), point(10, 0)), 5, "50", "1");
        // 中间有水、两端贴水：仅两端点之间的三角形（底 20、高 5）
        assertArea(List.of(point(0, 5), point(10, 0), point(20, 5)), 5, "50", "1");
    }

    @Test
    void zeroDepthEndpointFormsDegenerateTrapezoid() {
        // 深度 (0, 5)：直角三角形 (0+5)*10/2 = 25
        assertArea(List.of(point(0, 5), point(10, 0)), 5, "25", "1");
    }

    @Test
    void exactFractionForAsymmetricCrossing() {
        // 深度 (1, -2)，宽 3：交点在 1/3 处，三角形面积 = 1*1*3/(2*3) = 1/2
        assertArea(List.of(point(0, 0), point(3, 3)), 1, "1", "2");
    }

    @Test
    void negativeLateralCoordinatesSupported() {
        assertArea(List.of(point(-10, 0), point(0, 0)), 5, "50", "1");
    }

    @Test
    void fractionIsReduced() {
        // (2+2)*3/2 = 6，约分为 6/1
        assertArea(List.of(point(0, 0), point(3, 0)), 2, "6", "1");
    }
}
