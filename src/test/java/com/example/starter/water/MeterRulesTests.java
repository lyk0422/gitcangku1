package com.example.starter.water;

import com.example.starter.water.dto.Dtos.ReadingItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeterRules} 纯函数单元测试：规则参数、序列校验、三类异常的数量/时长/比例/差额口径，
 * 以及替代读数校验。不访问数据库，时间直接给定点，不依赖真实时钟。
 */
class MeterRulesTests {

    private MeterRules.Rule rule(String maxRate, int maxIntervalMinutes) {
        return MeterRules.validateRule(7, maxRate, maxIntervalMinutes);
    }

    private MeterRules.Point point(String utc, String cumulative) {
        return new MeterRules.Point(WaterService.parseInstant("t", utc), new BigDecimal(cumulative));
    }

    // ------------------------------------------------------------------
    // 规则参数非法 -> 422
    // ------------------------------------------------------------------

    @Test
    void invalidRuleParamsAreUnprocessable() {
        assertUnprocessable(() -> MeterRules.validateRule(1, null, 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "  ", 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "0", 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "-1", 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "abc", 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "1.0000001", 10));
        assertUnprocessable(() -> MeterRules.validateRule(1, "1", null));
        assertUnprocessable(() -> MeterRules.validateRule(1, "1", 0));
        assertUnprocessable(() -> MeterRules.validateRule(1, "1", -3));
    }

    @Test
    void validRuleParsesExactValues() {
        MeterRules.Rule rule = rule("0.900000", 15);
        assertEquals(0, new BigDecimal("0.900000").compareTo(rule.maxFlowRate()));
        assertEquals(15, rule.maxIntervalMinutes());
        // 边界值合法：允许 6 位小数
        rule("9999999999999999.999999", 1);
    }

    // ------------------------------------------------------------------
    // 批次序列完整校验：任一错误不解析通过
    // ------------------------------------------------------------------

    @Test
    void batchMustBeNonEmptyAndHaveValidTimeAndVolume() {
        assertUnprocessable(() -> MeterRules.validateBatch(null));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of()));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of(new ReadingItem(null, "1"))));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of(new ReadingItem("not-time", "1"))));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of(new ReadingItem("2026-10-01T00:00:00Z", null))));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of(new ReadingItem("2026-10-01T00:00:00Z", "-1"))));
        assertUnprocessable(() -> MeterRules.validateBatch(List.of(new ReadingItem("2026-10-01T00:00:00Z", "1.0001"))));
        // 0 本身是合法累计读数（计量点起点）
        List<MeterRules.Point> zero = MeterRules.validateBatch(
                List.of(new ReadingItem("2026-10-01T00:00:00Z", "0")));
        assertEquals(0, zero.get(0).cumulative().compareTo(BigDecimal.ZERO));
    }

    @Test
    void batchTimesMustBeStrictlyIncreasing() {
        // 相等时刻 -> 422（左闭右开，相等亦非法），即使是最后一条错误也整批拒绝
        List<ReadingItem> equal = List.of(
                new ReadingItem("2026-10-01T00:00:00Z", "1"),
                new ReadingItem("2026-10-01T00:01:00Z", "2"),
                new ReadingItem("2026-10-01T00:01:00Z", "3"));
        assertUnprocessable(() -> MeterRules.validateBatch(equal));
        // 倒退时刻 -> 422
        List<ReadingItem> backwards = List.of(
                new ReadingItem("2026-10-01T00:02:00Z", "1"),
                new ReadingItem("2026-10-01T00:01:00Z", "2"));
        ApiException ex = assertThrows(ApiException.class, () -> MeterRules.validateBatch(backwards));
        assertEquals(422, ex.status().value());
        assertEquals("READING_NOT_INCREMENTAL", ex.code());
    }

    // ------------------------------------------------------------------
    // 相邻读数评估：实际值 / 要求值 / 差额
    // ------------------------------------------------------------------

    @Test
    void normalPairHasNoFinding() {
        MeterRules.Rule rule = rule("1.000000", 10);
        // 10 立方米 / 10 分钟 = 1.0，恰等于上限；间隔恰等于上限：均不触发
        List<MeterRules.Finding> findings = MeterRules.evaluate(
                point("2026-10-01T00:00:00Z", "10"), point("2026-10-01T00:10:00Z", "20"), rule);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flowRateOverLimitRecordsActualRequiredAndExcess() {
        MeterRules.Rule rule = rule("0.900000", 60);
        List<MeterRules.Finding> findings = MeterRules.evaluate(
                point("2026-10-01T00:00:00Z", "10"), point("2026-10-01T00:10:00Z", "20"), rule);
        assertEquals(1, findings.size());
        MeterRules.Finding f = findings.get(0);
        assertEquals("FLOW_RATE", f.type());
        assertEquals("10.000", f.volumeDelta().toPlainString());
        assertEquals("10.000000000", f.durationMinutes().toPlainString());
        assertEquals("1.000000000", f.actualFlowRate().toPlainString());
        assertEquals("0.100000000", f.flowRateExcess().toPlainString());
        assertNull(f.regressionAmount());
        assertNull(f.intervalExcessMinutes());
    }

    @Test
    void regressionRecordsNegativeDeltaAndRegressionAmount() {
        MeterRules.Rule rule = rule("999", 60);
        List<MeterRules.Finding> findings = MeterRules.evaluate(
                point("2026-10-01T00:00:00Z", "10"), point("2026-10-01T00:10:00Z", "4.5"), rule);
        assertEquals(1, findings.size());
        MeterRules.Finding f = findings.get(0);
        assertEquals("REGRESSION", f.type());
        assertEquals("-5.500", f.volumeDelta().toPlainString());
        assertEquals("5.500", f.regressionAmount().toPlainString());
        assertEquals("-0.550000000", f.actualFlowRate().toPlainString());
        assertNull(f.flowRateExcess());
        assertNull(f.intervalExcessMinutes());
    }

    @Test
    void intervalGapUsesNanosecondBoundaryAndRecordsExcess() {
        MeterRules.Rule rule = rule("999", 10);
        // 恰 10 分钟不触发；超出 1 纳秒触发，差额约 0.000000017 分钟
        MeterRules.Point p = point("2026-10-01T00:00:00Z", "0");
        MeterRules.Point exact = new MeterRules.Point(p.utcNanos() + 10L * 60 * 1_000_000_000L,
                new BigDecimal("1"));
        assertTrue(MeterRules.evaluate(p, exact, rule).isEmpty());

        MeterRules.Point over = new MeterRules.Point(exact.utcNanos() + 60L, new BigDecimal("1"));
        List<MeterRules.Finding> findings = MeterRules.evaluate(p, over, rule);
        assertEquals(1, findings.size());
        assertEquals("INTERVAL_GAP", findings.get(0).type());
        // 60 纳秒 = 0.000000001 分钟
        assertEquals("0.000000001", findings.get(0).intervalExcessMinutes().toPlainString());
        assertNull(findings.get(0).regressionAmount());
        assertNull(findings.get(0).flowRateExcess());
    }

    @Test
    void onePairCanHitRegressionAndIntervalGapAndAnotherCanHitFlowAndGap() {
        // 倒退 + 间隔超上限（间隔 60 分钟，上限 10 分钟）
        List<MeterRules.Finding> both = MeterRules.evaluate(
                point("2026-10-01T00:00:00Z", "10"), point("2026-10-01T01:00:00Z", "4"),
                rule("999", 10));
        assertEquals(2, both.size());
        assertEquals("REGRESSION", both.get(0).type());
        assertEquals("INTERVAL_GAP", both.get(1).type());

        // 超速 + 间隔超上限
        List<MeterRules.Finding> flowAndGap = MeterRules.evaluate(
                point("2026-10-01T00:00:00Z", "0"), point("2026-10-01T00:20:00Z", "100"),
                rule("1", 10));
        assertEquals(2, flowAndGap.size());
        assertEquals("FLOW_RATE", flowAndGap.get(0).type());
        assertEquals("INTERVAL_GAP", flowAndGap.get(1).type());
        assertEquals("10.000000000", flowAndGap.get(1).intervalExcessMinutes().toPlainString());
    }

    // ------------------------------------------------------------------
    // 替代读数
    // ------------------------------------------------------------------

    @Test
    void replacementMustSitBetweenAndActuallyClearAnomaly() {
        MeterRules.Rule rule = rule("1.000000", 60);
        MeterRules.Point previous = point("2026-10-01T00:00:00Z", "10");
        // 异常读数：倒退到 4
        MeterRules.Point anomalous = point("2026-10-01T00:30:00Z", "4");

        // 合法：时刻居中、累计不倒退且流量 0.2 <= 1.0、间隔 15 分钟
        MeterRules.Point fixed = MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:15:00Z", "13", rule);
        assertEquals(13, fixed.cumulative().intValue());

        // 时刻不居中 -> 422
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:00:00Z", "12", rule));
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:30:00Z", "12", rule));
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-09-30T23:59:59Z", "12", rule));
        // 仍然倒退 -> 422
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:15:00Z", "8", rule));
        // 仍超速（5 分钟涨 10，速率 2 > 1）-> 422
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:05:00Z", "20", rule));
        // 间隔仍超上限（上限 60 时不触发，换 10 分钟规则）-> 422
        MeterRules.Rule shortInterval = rule("999", 10);
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:20:00Z", "11", shortInterval));
        // 非法数值/时刻 -> 422
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "2026-10-01T00:15:00Z", "-1", rule));
        assertUnprocessable(() -> MeterRules.validateReplacement(previous, anomalous,
                "bad-time", "11", rule));
    }

    private static void assertUnprocessable(Runnable runnable) {
        ApiException ex = assertThrows(ApiException.class, runnable::run);
        assertEquals(422, ex.status().value());
    }
}
