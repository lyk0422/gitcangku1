package com.example.starter.excursion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 越限区间计算的纯逻辑单元测试：覆盖题干合成样例、阈值相等、缺分钟与不足 N 的边界。
 */
class ExcursionIntervalsTest {

    private static ExcursionIntervals.Point point(String minuteUtc, String reading) {
        return new ExcursionIntervals.Point(Instant.parse(minuteUtc), new BigDecimal(reading));
    }

    @Test
    void syntheticGapThenFilledFormsInterval() {
        // 题干合成：N=3、H=10，10:00、10:01、10:03 值都 11，缺 10:02 不成区间
        List<ExcursionIntervals.Interval> gapped = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "11"),
                point("2026-10-07T10:01:00Z", "11"),
                point("2026-10-07T10:03:00Z", "11")), new BigDecimal("10"), 3);
        assertThat(gapped).isEmpty();

        // 补上 10:02 后形成 [10:00, 10:04)；10:04 值 10 不超限，区间右端仍为 10:04
        List<ExcursionIntervals.Interval> filled = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "11"),
                point("2026-10-07T10:01:00Z", "11"),
                point("2026-10-07T10:02:00Z", "11"),
                point("2026-10-07T10:03:00Z", "11"),
                point("2026-10-07T10:04:00Z", "10")), new BigDecimal("10"), 3);
        assertThat(filled).containsExactly(
                new ExcursionIntervals.Interval("2026-10-07T10:00:00Z", "2026-10-07T10:04:00Z"));
    }

    @Test
    void thresholdEqualityIsNotExceeding() {
        List<ExcursionIntervals.Interval> intervals = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "10"),
                point("2026-10-07T10:01:00Z", "10.000"),
                point("2026-10-07T10:02:00Z", "10")), new BigDecimal("10"), 2);
        assertThat(intervals).isEmpty();
    }

    @Test
    void runShorterThanNProducesNoInterval() {
        List<ExcursionIntervals.Interval> intervals = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "11"),
                point("2026-10-07T10:01:00Z", "12")), new BigDecimal("10"), 3);
        assertThat(intervals).isEmpty();
    }

    @Test
    void unsortedInputIsSortedBeforeComputation() {
        List<ExcursionIntervals.Interval> intervals = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:02:00Z", "11"),
                point("2026-10-07T10:00:00Z", "11"),
                point("2026-10-07T10:01:00Z", "11")), new BigDecimal("10"), 3);
        assertThat(intervals).containsExactly(
                new ExcursionIntervals.Interval("2026-10-07T10:00:00Z", "2026-10-07T10:03:00Z"));
    }

    @Test
    void separateRunsProduceSeparateIntervals() {
        List<ExcursionIntervals.Interval> intervals = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "11"),
                point("2026-10-07T10:01:00Z", "11"),
                point("2026-10-07T10:02:00Z", "9"),
                point("2026-10-07T10:03:00Z", "11"),
                point("2026-10-07T10:04:00Z", "11")), new BigDecimal("10"), 2);
        assertThat(intervals).containsExactly(
                new ExcursionIntervals.Interval("2026-10-07T10:00:00Z", "2026-10-07T10:02:00Z"),
                new ExcursionIntervals.Interval("2026-10-07T10:03:00Z", "2026-10-07T10:05:00Z"));
    }

    @Test
    void decimalValuesComparedByValue() {
        // 读数 10.001 严格大于 10.00；右端为最后超限时刻加 1 分钟
        List<ExcursionIntervals.Interval> intervals = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "10.001"),
                point("2026-10-07T10:01:00Z", "10.000")), new BigDecimal("10.00"), 2);
        assertThat(intervals).isEmpty();

        List<ExcursionIntervals.Interval> exceeded = ExcursionIntervals.compute(List.of(
                point("2026-10-07T10:00:00Z", "10.001"),
                point("2026-10-07T10:01:00Z", "10.002")), new BigDecimal("10.00"), 2);
        assertThat(exceeded).containsExactly(
                new ExcursionIntervals.Interval("2026-10-07T10:00:00Z", "2026-10-07T10:02:00Z"));
    }
}
