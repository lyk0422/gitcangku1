package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChipTimelineRules} 纯逻辑单元测试：UTC 左闭右开时间线、
 * 边界归新芯片、读取时刻回退与归属冲突原因。
 */
class ChipTimelineRulesTest {

    private static final long T0 = 1_000L;
    private static final long T1 = 2_000L;
    private static final long T2 = 3_000L;

    private List<ChipTimelineRules.Interval> timeline() {
        return ChipTimelineRules.buildTimeline("chip-A", T0, List.of(
                new ChipTimelineRules.Boundary("chip-B", T1),
                new ChipTimelineRules.Boundary("chip-C", T2)));
    }

    @Test
    void 时间线左闭右开且边界时刻归新生效芯片() {
        List<ChipTimelineRules.Interval> timeline = timeline();

        assertThat(timeline).hasSize(3);
        // 初始区间 [T0,T1)
        assertThat(ChipTimelineRules.chipAt(timeline, T0)).isEqualTo("chip-A");
        assertThat(ChipTimelineRules.chipAt(timeline, T1 - 1)).isEqualTo("chip-A");
        // 边界 T1 本身归新芯片 B
        assertThat(ChipTimelineRules.chipAt(timeline, T1)).isEqualTo("chip-B");
        assertThat(ChipTimelineRules.chipAt(timeline, T2 - 1)).isEqualTo("chip-B");
        // 边界 T2 归 C，并一直开放到无限远
        assertThat(ChipTimelineRules.chipAt(timeline, T2)).isEqualTo("chip-C");
        assertThat(ChipTimelineRules.chipAt(timeline, T2 + 999_999L)).isEqualTo("chip-C");
    }

    @Test
    void 早于初始绑定时刻无法归属() {
        assertThat(ChipTimelineRules.chipAt(timeline(), T0 - 1)).isNull();
    }

    @Test
    void 乱序登记的更换边界也按生效时刻构造区间() {
        List<ChipTimelineRules.Interval> timeline = ChipTimelineRules.buildTimeline("A", T0, List.of(
                new ChipTimelineRules.Boundary("C", T2),
                new ChipTimelineRules.Boundary("B", T1)));
        assertThat(timeline).extracting(ChipTimelineRules.Interval::chipCode)
                .containsExactly("A", "B", "C");
    }

    @Test
    void 未上报读取时刻时回退为服务端接收时刻() {
        assertThat(ChipTimelineRules.receivedAt(null, 5_000L)).isEqualTo(5_000L);
        assertThat(ChipTimelineRules.receivedAt(2_500L, 5_000L)).isEqualTo(2_500L);
    }

    @Test
    void 归属校验给出可区分原因() {
        List<ChipTimelineRules.Interval> timeline = timeline();

        // 芯片与区间匹配 -> 无错误
        assertThat(ChipTimelineRules.attributionError(
                timeline, "chip-A", T0, T0, "c1")).isNull();
        assertThat(ChipTimelineRules.attributionError(
                timeline, "chip-B", T1, T1, "c2")).isNull();

        // 芯片不匹配：T1 应归 B，却用 A
        String mismatch = ChipTimelineRules.attributionError(
                timeline, "chip-A", T1, T1, "c2");
        assertThat(mismatch).contains("chip-A").contains("chip-B").contains("c2");

        // 时刻过早
        assertThat(ChipTimelineRules.attributionError(
                timeline, "chip-A", T0 - 1, T0 - 1, "c0"))
                .contains("早于");

        // 未记录芯片
        assertThat(ChipTimelineRules.attributionError(
                timeline, null, T0, T0, "c1"))
                .contains("未记录芯片");
    }
}
