package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流量曲线数学单元测试：梯形积分、精确有理数叠加的流量与斜率上限。
 * 覆盖判断前不舍入的边界（如 10/3 + 20/3 = 10 恰好等于上限）。
 */
class FlowCurveTest {

    private static FlowCurve.Node node(long minute, String flow) {
        return new FlowCurve.Node(minute, new BigDecimal(flow));
    }

    @Test
    void integrateTrapezoidal() {
        // 0、10、0 三个相距一分钟节点：积分 = 5 + 5 = 10
        List<FlowCurve.Node> curve = List.of(node(0, "0"), node(1, "10"), node(2, "0"));
        assertEquals(0, FlowCurve.integrate(curve).compareTo(new BigDecimal("10")));
    }

    @Test
    void integrateFractionalMinutes() {
        // (0.5 + 1.5) / 2 * 3 = 3
        List<FlowCurve.Node> curve = List.of(node(0, "0.5"), node(3, "1.5"));
        assertEquals(0, FlowCurve.integrate(curve).compareTo(new BigDecimal("3")));
    }

    @Test
    void singleCurveWithinLimitsPasses() {
        List<FlowCurve.Node> curve = List.of(node(0, "0"), node(1, "10"), node(2, "0"));
        assertDoesNotThrow(() -> FlowCurve.assertWithinLimits(List.of(curve),
                new BigDecimal("10"), new BigDecimal("10")));
    }

    @Test
    void superimposedFlowAtSharedNodeExceedsLimit() {
        // 两条同形曲线在中间节点叠加为 20，超过最大流量 10
        List<FlowCurve.Node> a = List.of(node(0, "0"), node(1, "10"), node(2, "0"));
        List<FlowCurve.Node> b = List.of(node(0, "0"), node(1, "10"), node(2, "0"));
        ApiException ex = assertThrows(ApiException.class, () -> FlowCurve.assertWithinLimits(
                List.of(a, b), new BigDecimal("10"), new BigDecimal("100")));
        assertEquals("PUMP_FLOW_EXCEEDED", ex.code());
    }

    @Test
    void superimposedRampExceedsLimit() {
        // 两条曲线同段斜率各 10，叠加 20 超过每分钟最大变化量 15；流量 20 未超上限 30
        List<FlowCurve.Node> a = List.of(node(0, "0"), node(1, "10"));
        List<FlowCurve.Node> b = List.of(node(0, "0"), node(1, "10"));
        ApiException ex = assertThrows(ApiException.class, () -> FlowCurve.assertWithinLimits(
                List.of(a, b), new BigDecimal("30"), new BigDecimal("15")));
        assertEquals("PUMP_RAMP_EXCEEDED", ex.code());
    }

    @Test
    void exactRationalSumEqualToLimitPassesWithoutRounding() {
        // t=1 处两条曲线插值分别为 20/3 与 10/3，精确和恒为 10，恰好等于上限应通过；
        // 若判断前舍入（如各取 6.667 与 3.333）会得到错误结论
        List<FlowCurve.Node> a = List.of(node(0, "10"), node(3, "0"));
        List<FlowCurve.Node> b = List.of(node(0, "0"), node(3, "10"));
        assertDoesNotThrow(() -> FlowCurve.assertWithinLimits(List.of(a, b),
                new BigDecimal("10"), new BigDecimal("100")));
        ApiException ex = assertThrows(ApiException.class, () -> FlowCurve.assertWithinLimits(
                List.of(a, b), new BigDecimal("9.999"), new BigDecimal("100")));
        assertEquals("PUMP_FLOW_EXCEEDED", ex.code());
    }

    @Test
    void interpolationEvaluatedAtOtherCurveNodes() {
        // 曲线 A 节点在 0/10 分钟，曲线 B 节点在 5 分钟：t=5 处 A 插值 50 与 B 叠加
        List<FlowCurve.Node> a = List.of(node(0, "0"), node(10, "100"));
        List<FlowCurve.Node> b = List.of(node(5, "60"), node(6, "60"));
        ApiException ex = assertThrows(ApiException.class, () -> FlowCurve.assertWithinLimits(
                List.of(a, b), new BigDecimal("100"), new BigDecimal("1000")));
        assertEquals("PUMP_FLOW_EXCEEDED", ex.code());
        // 上限 110：t=5 处 50+60=110 恰好通过，t=10 处 100+0=100 通过
        List<FlowCurve.Node> b2 = List.of(node(5, "60"), node(6, "0"));
        assertDoesNotThrow(() -> FlowCurve.assertWithinLimits(List.of(a, b2),
                new BigDecimal("110"), new BigDecimal("1000")));
    }

    @Test
    void curveOutsideTimeRangeContributesZero() {
        // 两条曲线时间不重叠，各自独立校验
        List<FlowCurve.Node> a = List.of(node(0, "0"), node(1, "10"), node(2, "0"));
        List<FlowCurve.Node> b = List.of(node(10, "0"), node(11, "10"), node(12, "0"));
        assertDoesNotThrow(() -> FlowCurve.assertWithinLimits(List.of(a, b),
                new BigDecimal("10"), new BigDecimal("10")));
    }

    @Test
    void fallingSlopeAlsoLimited() {
        // 下降沿斜率 -10，绝对值超过 9.999 应拒绝
        List<FlowCurve.Node> curve = List.of(node(0, "10"), node(1, "0"));
        ApiException ex = assertThrows(ApiException.class, () -> FlowCurve.assertWithinLimits(
                List.of(curve), new BigDecimal("10"), new BigDecimal("9.999")));
        assertEquals("PUMP_RAMP_EXCEEDED", ex.code());
        assertTrue(ex.getMessage().contains("9.999"));
    }
}