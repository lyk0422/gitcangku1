package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力曲线评估器纯单元测试：题给合成数据、门禁边界、累计正下降、逆序身份与精确十进制。
 */
class PressureCurveEvaluatorTest {

    private static PressureCurveEvaluator.Point p(long second, String pressure) {
        return PressureCurveEvaluator.raw(second, pressure);
    }

    private static List<PressureCurveEvaluator.Point> points(Object... pairs) {
        List<PressureCurveEvaluator.Point> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(p((Long) pairs[i], (String) pairs[i + 1]));
        }
        return list;
    }

    // ---------- 题给合成数据 ----------

    @Test
    void givenSyntheticCurve_firstLastOnlyDrops06_butCumulative11_mustFail() {
        // (0,10)、(5,9.5)、(10,10)、(15,9.4)：首末仅降 0.6，但两段正下降 0.5+0.6=1.1
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9.5", 10L, "10", 15L, "9.4"));
        assertFalse(e.passed());
        assertEquals(0, new BigDecimal("1.1").compareTo(e.totalDecline()),
                "累计下降必须为各段正下降之和 1.1，而非首末差 0.6");
        // 每段速率：0.5/5=0.1、上升段 0、0.6/5=0.12，均不超 0.2；FAIL 仅因累计下降
        assertFalse(e.segments().get(0).rateExceeded());
        assertFalse(e.segments().get(2).rateExceeded());
        assertEquals(0, new BigDecimal("0.12").compareTo(e.maxSegmentRate()));
        assertTrue(e.rejectReason().contains("累计"));
    }

    @Test
    void removingLastDecline_cumulative05_passes() {
        // 去掉最后下降：(0,10)、(5,9.5)、(10,10)，累计正下降 0.5
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9.5", 10L, "10"));
        assertTrue(e.passed());
        assertEquals(0, new BigDecimal("0.5").compareTo(e.totalDecline()));
        assertEquals(0, new BigDecimal("0.1").compareTo(e.maxSegmentRate()));
    }

    // ---------- 速率门禁 ----------

    @Test
    void segmentRateExactly02_andTotalExactly1_pass_boundaryInclusive() {
        // 前 5 秒恰好下降 1.0：速率恰为 0.2/秒、累计恰为 1，随后持平到末点 10 秒；边界含等于
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10.000", 5L, "9.000", 10L, "9.000"));
        assertTrue(e.passed(), "门禁为不超过，边界值必须放行");
        assertEquals(0, BigDecimal.ONE.compareTo(e.totalDecline()));
        assertEquals(0, new BigDecimal("0.2").compareTo(e.maxSegmentRate()));
    }

    @Test
    void segmentRateAbove02_fails_evenWhenTotalBelow1() {
        // 前 4 秒下降 0.9：速率 0.225 > 0.2，但累计 0.9 <= 1，仅速率门禁 FAIL；随后持平到末点
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10", 4L, "9.1", 10L, "9.1"));
        assertFalse(e.passed());
        assertTrue(e.segments().get(0).rateExceeded());
        assertEquals(0, new BigDecimal("0.9").compareTo(e.totalDecline()));
        assertTrue(e.rejectReason().contains("速率"));
        assertFalse(e.rejectReason().contains("累计"));
    }

    @Test
    void multipleSlowDeclines_totalAbove1_fails_risesDoNotOffset() {
        // 每段 0.5/5s=0.1 不超速，三个下降段被两个上升段隔开，上升不抵消，累计 1.5 > 1
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9.5", 10L, "10", 15L, "9.5", 20L, "10", 25L, "9.5"));
        assertFalse(e.passed());
        assertEquals(0, new BigDecimal("1.5").compareTo(e.totalDecline()));
        assertTrue(e.segments().stream().noneMatch(PressureCurveEvaluator.Segment::rateExceeded));
        assertTrue(e.rejectReason().contains("累计"));
    }

    @Test
    void rateGateUsesCrossMultiplication_noPrematureRounding() {
        // dt=4：允许下降恰为 0.8（0.2*4 精确）；下降 0.800 放行（累计 0.8 亦不超限），
        // 0.801 拒绝；门禁走交叉相乘而非先除法舍入速率，末点补到 10 秒持平
        assertTrue(PressureCurveEvaluator.evaluate(
                points(0L, "1.000", 4L, "0.200", 10L, "0.200")).passed());
        PressureCurveEvaluator.Evaluation exceeded = PressureCurveEvaluator.evaluate(
                points(0L, "1.000", 4L, "0.199", 10L, "0.199"));
        assertFalse(exceeded.passed());
        assertTrue(exceeded.segments().get(0).rateExceeded());
        assertFalse(exceeded.rejectReason().contains("累计"),
                "0.801 未超累计 1 千帕，仅速率门禁失败");
        // 反方向验证交叉相乘：更大秒数下相同下降量不超速（0.801/5=0.1602）
        PressureCurveEvaluator.Evaluation slower = PressureCurveEvaluator.evaluate(
                points(0L, "1.000", 5L, "0.199", 10L, "0.199"));
        assertTrue(slower.passed());
    }

    // ---------- 精确十进制与大时刻差 ----------

    @Test
    void hugeSecondDelta_exactArithmetic() {
        // dt=10^12 秒、恰下降 1 千帕（受累计门禁约束）：时刻差 long 精确，速率精确为 1E-12
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "1.000", 1_000_000_000_000L, "0.000"));
        assertTrue(e.passed());
        assertEquals(1_000_000_000_000L, e.segments().get(0).toSecond());
        assertEquals(0, BigDecimal.ONE.compareTo(e.totalDecline()));
        assertEquals(0, new BigDecimal("0.000000000001").compareTo(e.maxSegmentRate()));
    }

    @Test
    void decimalValuesComparedByValue_rawTextPreserved() {
        // 9.50 与 9.5 数值相等：0.5/5=0.1 不超速；原文按提交身份保留
        PressureCurveEvaluator.Evaluation e = PressureCurveEvaluator.evaluate(
                points(0L, "10.000", 5L, "9.50", 10L, "10.000"));
        assertTrue(e.passed());
        assertEquals("10.000", e.points().get(0).pressureRaw());
        assertEquals("9.50", e.points().get(1).pressureRaw());
        assertEquals(0, e.points().get(1).pressure().compareTo(new BigDecimal("9.5")));
    }

    // ---------- 非法输入 400 ----------

    @Test
    void invalidShapes_throwBadRequest_andNoSemanticRecord() {
        ApiException ex;
        ex = assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(List.of(p(0, "10"))));
        assertEquals(400, ex.status().value());
        ex = assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9", 10L, "10", 15L, "9", 20L, "10",
                        25L, "9", 30L, "10", 35L, "9", 40L, "10", 45L, "9", 50L, "10")));
        assertEquals(400, ex.status().value(), "最多 10 个节点");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(1L, "10", 5L, "9")), "首点必须为 0");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 9L, "9")), "末点至少 10");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9", 5L, "9")), "相邻秒数相等非法");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 6L, "9", 5L, "9")), "逆序提交非法，顺序属于身份不可排序掩盖");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "9.1234")), "压力最多 3 位小数");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "-0.001")), "压力必须非负");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "1e1")), "不接受科学计数法");
        assertThrows(ApiException.class, () -> PressureCurveEvaluator.evaluate(
                points(0L, "10", 5L, "abc")), "非数字非法");
    }

    @Test
    void flatAndRisingCurves_passZeroDecline() {
        assertTrue(PressureCurveEvaluator.evaluate(points(0L, "10", 10L, "10")).passed());
        PressureCurveEvaluator.Evaluation rising =
                PressureCurveEvaluator.evaluate(points(0L, "9.4", 5L, "10", 10L, "10.5"));
        assertTrue(rising.passed());
        assertEquals(0, BigDecimal.ZERO.compareTo(rising.totalDecline()));
        assertEquals(0, BigDecimal.ZERO.compareTo(rising.maxSegmentRate()));
    }
}
