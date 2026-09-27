package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.DwellRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 站停裁决纯逻辑单元测试：实际停留分钟换算与左闭右开站台占用峰值扫描。
 * 时间使用 UTC 毫秒常量，不依赖系统时钟。
 */
class DwellRulesTest {

    private static final long T08 = 8 * 3_600_000L;
    private static final long T08_10 = T08 + 10 * 60_000L;
    private static final long T08_20 = T08 + 20 * 60_000L;
    private static final long T08_30 = T08 + 30 * 60_000L;
    private static final long T09 = 9 * 3_600_000L;

    @Test
    void actualDwellMinutesIsWholeMinuteDifference() {
        assertThat(DwellRules.actualDwellMinutes(T08, T08_20)).isEqualTo(20);
        assertThat(DwellRules.actualDwellMinutes(T08_10, T08_30)).isEqualTo(20);
    }

    @Test
    void reversedArrivalDepartureYieldsNegativeMinutes() {
        // 到发倒置时实际分钟为负值，由上层按 422 裁决
        assertThat(DwellRules.actualDwellMinutes(T08_20, T08)).isEqualTo(-20);
        assertThat(DwellRules.actualDwellMinutes(T08, T08)).isZero();
    }

    @Test
    void emptyIntervalsHaveZeroPeak() {
        DwellRules.Peak peak = DwellRules.peakConcurrent(List.of());
        assertThat(peak.concurrent()).isZero();
    }

    @Test
    void singleIntervalPeakIsOne() {
        DwellRules.Peak peak = DwellRules.peakConcurrent(
                List.of(new DwellRules.Interval(T08, T08_20)));
        assertThat(peak.concurrent()).isEqualTo(1);
        assertThat(peak.startUtc()).isEqualTo(T08);
        assertThat(peak.endUtc()).isEqualTo(T08_20);
    }

    @Test
    void halfOpenAdjacentIntervalsDoNotOverlap() {
        // 左闭右开：[08:00,09:00) 与 [09:00,10:00) 相邻，峰值必须为 1；
        // 换车瞬间占用连续为 1，峰值水平区间为 [08:00,10:00)
        DwellRules.Peak peak = DwellRules.peakConcurrent(List.of(
                new DwellRules.Interval(T08, T09),
                new DwellRules.Interval(T09, 10 * 3_600_000L)));
        assertThat(peak.concurrent()).isEqualTo(1);
        assertThat(peak.startUtc()).isEqualTo(T08);
        assertThat(peak.endUtc()).isEqualTo(10 * 3_600_000L);
    }

    @Test
    void overlappingIntervalsReportPeakAndEarliestWindow() {
        // G1 [08:00,08:20)，G2 [08:10,08:30)，峰值 2 落在 [08:10,08:20)
        DwellRules.Peak peak = DwellRules.peakConcurrent(List.of(
                new DwellRules.Interval(T08, T08_20),
                new DwellRules.Interval(T08_10, T08_30)));
        assertThat(peak.concurrent()).isEqualTo(2);
        assertThat(peak.startUtc()).isEqualTo(T08_10);
        assertThat(peak.endUtc()).isEqualTo(T08_20);
    }

    @Test
    void threeWayOverlapReportsPeakThree() {
        DwellRules.Peak peak = DwellRules.peakConcurrent(List.of(
                new DwellRules.Interval(T08, T09),
                new DwellRules.Interval(T08_10, T08_30),
                new DwellRules.Interval(T08_20, T09)));
        // [08:20,08:30) 三车同时在站
        assertThat(peak.concurrent()).isEqualTo(3);
        assertThat(peak.startUtc()).isEqualTo(T08_20);
        assertThat(peak.endUtc()).isEqualTo(T08_30);
    }

    @Test
    void arrivalAndDepartureAtSameInstantDoNotStack() {
        // G1 08:20 发车、G2 08:20 到达：发车事件先处理，峰值不叠加
        DwellRules.Peak peak = DwellRules.peakConcurrent(List.of(
                new DwellRules.Interval(T08, T08_20),
                new DwellRules.Interval(T08_20, T08_30),
                new DwellRules.Interval(T08_10, T08_20)));
        assertThat(peak.concurrent()).isEqualTo(2);
    }
}
