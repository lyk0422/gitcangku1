package com.example.starter.batch;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 压力曲线评估器纯单元测试：合成样例、逐段速率门禁、累计下降门禁、
 * 上升段不抵扣、精确计算（不提前舍入、大时刻差值）与语法 400 分支。
 */
class PressureCurveEvaluatorTest {

    private static PressureCurveEvaluator.Point p(long seconds, String kpa) {
        return new PressureCurveEvaluator.Point(seconds, new BigDecimal(kpa));
    }

    private static List<PressureCurveEvaluator.Point> curve(PressureCurveEvaluator.Point... points) {
        return List.of(points);
    }

    // ---------- 题干合成样例 ----------

    @Test
    void syntheticCurve_firstLastDropOnly0_6_butCumulative1_1_fails() {
        List<PressureCurveEvaluator.Point> points = curve(
                p(0, "10"), p(5, "9.5"), p(10, "10"), p(15, "9.4"));
        PressureCurveEvaluator.validateSyntax(points);
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(points);
        // 首末差仅 0.6，但累计下降 0.5 + 0.6 = 1.1 > 1，必须 FAIL
        assertEquals(TestOutcome.FAIL, evaluation.outcome());
        assertEquals(new BigDecimal("1.1"), evaluation.cumulativeDescentKpa());
        assertEquals(new BigDecimal("0.12"), evaluation.maxDescentRateKpaPerSec());
    }

    @Test
    void syntheticCurve_withoutLastDrop_passes() {
        List<PressureCurveEvaluator.Point> points = curve(
                p(0, "10"), p(5, "9.5"), p(10, "10"));
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(points);
        assertEquals(TestOutcome.PASS, evaluation.outcome());
        assertEquals(new BigDecimal("0.5"), evaluation.cumulativeDescentKpa());
        assertEquals(new BigDecimal("0.1"), evaluation.maxDescentRateKpaPerSec());
    }

    // ---------- 逐段速率门禁 ----------

    @Test
    void segmentRate_exactlyAtLimit_passes() {
        // 5 秒降 1.0 千帕，速率恰为 0.2 千帕/秒（边界含等号）
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(5, "9")));
        assertEquals(TestOutcome.PASS, evaluation.outcome());
        assertEquals(new BigDecimal("1"), evaluation.cumulativeDescentKpa());
    }

    @Test
    void segmentRate_aboveLimit_fails() {
        // 1 秒降 0.3 千帕，速率 0.3 > 0.2
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(1, "9.7"), p(10, "9.7")));
        assertEquals(TestOutcome.FAIL, evaluation.outcome());
    }

    @Test
    void segmentRate_exactComparison_noEarlyRounding() {
        // 3 秒降 0.6：精确速率恰为 0.2，通过；3 秒降 0.601：0.200333… > 0.2，失败。
        // 若提前把速率舍入到 1 位小数会误判，这里验证精确交叉乘判定。
        assertEquals(TestOutcome.PASS, PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(3, "9.4"))).outcome());
        assertEquals(TestOutcome.FAIL, PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(3, "9.399"))).outcome());
        // 非有限小数速率 1/3 秒降 1 千帕：精确判定 0.333… > 0.2
        assertEquals(TestOutcome.FAIL, PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(3, "9"))).outcome());
    }

    // ---------- 累计下降门禁 ----------

    @Test
    void cumulativeDescent_exactlyOne_passes() {
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(10, "9")));
        assertEquals(TestOutcome.PASS, evaluation.outcome());
        assertEquals(new BigDecimal("1"), evaluation.cumulativeDescentKpa());
    }

    @Test
    void cumulativeDescent_justAboveOne_fails() {
        assertEquals(TestOutcome.FAIL, PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(10, "8.999"))).outcome());
    }

    @Test
    void risingSegments_doNotOffsetCumulativeDescent() {
        // 首末差仅 0.5，但两段下降 0.6 + 1.0 = 1.6，上升段不抵扣
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(5, "9.4"), p(10, "10.5"), p(15, "9.5")));
        assertEquals(TestOutcome.FAIL, evaluation.outcome());
        assertEquals(new BigDecimal("1.6"), evaluation.cumulativeDescentKpa());
    }

    @Test
    void allRising_passesWithZeroDescent() {
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "9"), p(10, "10")));
        assertEquals(TestOutcome.PASS, evaluation.outcome());
        assertEquals(new BigDecimal("0"), evaluation.cumulativeDescentKpa());
        assertEquals(new BigDecimal("0"), evaluation.maxDescentRateKpaPerSec());
    }

    @Test
    void largeOffsets_exactComputation() {
        // 大时刻差值使用精确计算：10^12 秒量级，下降 0.5 千帕，速率远低于上限
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(
                curve(p(0, "10"), p(1_000_000_000_000L, "9.5"), p(2_000_000_000_000L, "10")));
        assertEquals(TestOutcome.PASS, evaluation.outcome());
        assertEquals(new BigDecimal("0.5"), evaluation.cumulativeDescentKpa());
    }

    // ---------- 语法校验（400） ----------

    @Test
    void validate_pointCountOutOfRange_rejected() {
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10"))));
        List<PressureCurveEvaluator.Point> eleven = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            eleven.add(p(i, "10"));
        }
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(eleven));
        // 边界：2 个与 10 个点合法（末点须 ≥ 10 秒）
        assertDoesNotThrow(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10"), p(10, "10"))));
        List<PressureCurveEvaluator.Point> ten = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            ten.add(p(i, "10"));
        }
        ten.add(p(10, "10"));
        assertDoesNotThrow(() -> PressureCurveEvaluator.validateSyntax(ten));
    }

    @Test
    void validate_firstAndLastOffsetRules_rejected() {
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(curve(p(1, "10"), p(10, "10"))));
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10"), p(9, "10"))));
        assertDoesNotThrow(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10"), p(10, "10"))));
    }

    @Test
    void validate_orderIsIdentity_outOfOrderNotSorted() {
        // 节点顺序属于身份：逆序/重复时刻判 400，不允许排序掩盖
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(
                curve(p(0, "10"), p(0, "9"), p(10, "10"))));
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(
                curve(p(0, "10"), p(9, "10"), p(5, "10"), p(10, "10"))));
    }

    @Test
    void validate_pressureRangeAndScale_rejected() {
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "-0.1"), p(10, "10"))));
        assertBadRequest(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10.0001"), p(10, "10"))));
        // 3 位小数合法；9.5000 按值即 9.5，合法
        assertDoesNotThrow(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "10.001"), p(10, "10"))));
        assertDoesNotThrow(() -> PressureCurveEvaluator.validateSyntax(curve(p(0, "9.5000"), p(10, "10"))));
    }

    private static void assertBadRequest(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }
}
