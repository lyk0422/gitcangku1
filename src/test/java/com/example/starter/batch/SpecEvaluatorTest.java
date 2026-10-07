package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特性规范求值器纯单元测试：等号合格、精确判断、负系数、十进制规范化与精度边界。
 */
class SpecEvaluatorTest {

    private static SpecDefinition spec(List<SpecDefinition.LinearConstraint> constraints) {
        return new SpecDefinition("S", 1, List.of("x", "y"), constraints);
    }

    private static SpecDefinition.LinearConstraint le(int a, int b, String bound) {
        return new SpecDefinition.LinearConstraint(List.of(a, b), new BigDecimal(bound));
    }

    private static List<BigDecimal> vector(String x, String y) {
        return List.of(new BigDecimal(x), new BigDecimal(y));
    }

    @Test
    void equalToBound_passes() {
        // x+y<=15，x=7、y=8 恰好等号 → 合格
        SpecDefinition spec = spec(List.of(le(1, 1, "15")));
        assertTrue(SpecEvaluator.passes(spec, vector("7", "8")));
    }

    @Test
    void coupledConstraint_individuallyOkButSumExceeded_fails() {
        // x<=10、y<=10 单项均合格，x+y<=15 越界 → 整体 FAIL
        SpecDefinition spec = spec(List.of(le(1, 0, "10"), le(0, 1, "10"), le(1, 1, "15")));
        assertFalse(SpecEvaluator.passes(spec, vector("8", "8")));
        assertTrue(SpecEvaluator.passes(spec, vector("7", "8")));
    }

    @Test
    void exactDecimal_noDisplayRounding() {
        // 15.000001 > 15：不依赖显示舍入，最小越界即 FAIL
        SpecDefinition spec = spec(List.of(le(1, 1, "15")));
        assertFalse(SpecEvaluator.passes(spec, vector("7.000001", "8")));
        assertTrue(SpecEvaluator.passes(spec, vector("7.000000", "8")));
    }

    @Test
    void negativeCoefficients_evaluatedExactly() {
        // x - y <= 2：x=5、y=4 → 1 <= 2 合格；x=5、y=2 → 3 > 2 不合格
        SpecDefinition spec = spec(List.of(le(1, -1, "2")));
        assertTrue(SpecEvaluator.passes(spec, vector("5", "4")));
        assertFalse(SpecEvaluator.passes(spec, vector("5", "2")));
    }

    @Test
    void allConstraintsMustPass() {
        // 任一约束越界即 FAIL，与其余约束是否满足无关
        SpecDefinition spec = spec(List.of(le(1, 0, "10"), le(0, 1, "3")));
        assertFalse(SpecEvaluator.passes(spec, vector("5", "4")));
    }

    @Test
    void canonicalDecimal_sameValueSameForm() {
        assertEquals(SpecEvaluator.canonicalDecimal(new BigDecimal("1.0")),
                SpecEvaluator.canonicalDecimal(new BigDecimal("1.00")));
        assertEquals("0", SpecEvaluator.canonicalDecimal(new BigDecimal("0.000")));
        assertEquals("0", SpecEvaluator.canonicalDecimal(BigDecimal.ZERO));
        assertEquals("10", SpecEvaluator.canonicalDecimal(new BigDecimal("10.0")));
        assertEquals("0.000001", SpecEvaluator.canonicalDecimal(new BigDecimal("0.000001")));
    }

    @Test
    void withinScale_sixDecimalsBoundary() {
        assertTrue(SpecEvaluator.withinScale(new BigDecimal("1.123456")));
        assertFalse(SpecEvaluator.withinScale(new BigDecimal("1.1234567")));
        assertTrue(SpecEvaluator.withinScale(new BigDecimal("8")));
        assertFalse(SpecEvaluator.withinScale(new BigDecimal("1E-7")));
    }
}
