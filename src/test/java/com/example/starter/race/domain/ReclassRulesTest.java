package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReclassRules} 的纯逻辑单元测试：批次重复参赛号与组别容量投影。
 */
class ReclassRulesTest {

    @Test
    void 批次中无重复参赛号时返回null() {
        assertThat(ReclassRules.findDuplicateBib(List.of("a", "b", "c"))).isNull();
        assertThat(ReclassRules.findDuplicateBib(List.of())).isNull();
    }

    @Test
    void 批次中重复参赛号被检出且返回第一个重复项() {
        assertThat(ReclassRules.findDuplicateBib(List.of("a", "b", "a"))).isEqualTo("a");
        assertThat(ReclassRules.findDuplicateBib(List.of("x", "y", "y", "x"))).isEqualTo("y");
    }

    @Test
    void 容量投影扣除迁出并计入迁入() {
        // 当前2人，迁出1人，迁入2人 -> 3人
        assertThat(ReclassRules.projectedOccupancy(2, 1, 2)).isEqualTo(3);
        // 互换场景：迁出与迁入相同时在赛人数不变
        assertThat(ReclassRules.projectedOccupancy(2, 1, 1)).isEqualTo(2);
    }

    @Test
    void 容量边界_恰好等于容量不超_超过一人即超限() {
        assertThat(ReclassRules.exceedsCapacity(1, 0, 1, 2)).isFalse();
        assertThat(ReclassRules.exceedsCapacity(2, 0, 1, 2)).isTrue();
        // 同批迁出释放名额后可以迁入
        assertThat(ReclassRules.exceedsCapacity(2, 1, 2, 3)).isFalse();
    }
}