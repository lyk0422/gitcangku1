package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntervalRules} 纯逻辑单元测试：区间端点合法性与相邻严格递增约束。
 */
class IntervalRulesTest {

    private record Interval(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long lowerMs,
            long upperMs
    ) implements IntervalView {
    }

    @Test
    void 端点取值非法时拒绝() {
        assertThat(IntervalRules.validateRange(0L, 10L)).contains("1~86400000");
        assertThat(IntervalRules.validateRange(1L, 86_400_001L)).contains("1~86400000");
        assertThat(IntervalRules.validateRange(-5L, 100L)).contains("1~86400000");
        assertThat(IntervalRules.validateRange(200L, 100L)).contains("下界不能大于上界");
        assertThat(IntervalRules.validateRange(1L, 86_400_000L)).isNull();
        assertThat(IntervalRules.validateRange(100L, 100L)).isNull();
    }

    @Test
    void 相邻区间端点相等不算保证递增() {
        List<Interval> existing = List.of(
                new Interval("t1", "a", "c1", 1, 100L, 110L),
                new Interval("t3", "a", "c3", 3, 200L, 210L));

        // 新区间下界等于前序上界 -> 拒绝
        assertThat(IntervalRules.validateAdjacency(existing, 2, 110L, 150L))
                .contains("严格大于前一计时点");
        // 新区间上界等于后序下界 -> 拒绝
        assertThat(IntervalRules.validateAdjacency(existing, 2, 150L, 200L))
                .contains("严格小于后一计时点");
        // 严格介于两者之间 -> 合法
        assertThat(IntervalRules.validateAdjacency(existing, 2, 111L, 199L)).isNull();
    }

    @Test
    void 同一计时点重复与乱序合法写入() {
        List<Interval> existing = List.of(
                new Interval("t3", "a", "c3", 3, 500L, 600L));

        assertThat(IntervalRules.validateAdjacency(existing, 3, 100L, 200L))
                .contains("同一选手同一计时点");
        // 乱序：在 c3 之前补 c1，合法
        assertThat(IntervalRules.validateAdjacency(existing, 1, 100L, 400L)).isNull();
        // 与后序重叠 -> 拒绝
        assertThat(IntervalRules.validateAdjacency(existing, 1, 100L, 500L)).isNotNull();
        // 在 c3 之后写完赛点，合法
        assertThat(IntervalRules.validateAdjacency(existing, 4, 601L, 700L)).isNull();
    }
}
