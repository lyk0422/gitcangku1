package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HandicapScore} 精确分数工具单元测试：约分、精确比较与 HALF_UP 展示值。
 */
class HandicapScoreTest {

    @Test
    void 等比例系数约分后相同() {
        assertThat(HandicapScore.reduceFactor(2, 6)).containsExactly(1, 3);
        assertThat(HandicapScore.reduceFactor(1, 3)).containsExactly(1, 3);
        assertThat(HandicapScore.reduceFactor(1000, 1000)).containsExactly(1, 1);
        assertThat(HandicapScore.reduceFactor(7, 13)).containsExactly(7, 13);
        assertThat(HandicapScore.reduceFactor(4, 2)).containsExactly(2, 1);
    }

    @Test
    void 有效成绩为原始毫秒乘p除q再加加时且约分() {
        // 1000ms、1/3、无加时 -> 1000/3
        assertThat(HandicapScore.score(1000L, 0L, 1, 3)).containsExactly(1000L, 3L);
        // 999ms、1/3 -> 333/1
        assertThat(HandicapScore.score(999L, 0L, 1, 3)).containsExactly(333L, 1L);
        // 999ms、1/3、加时300ms -> (999+900)/3 = 633/1
        assertThat(HandicapScore.score(999L, 300L, 1, 3)).containsExactly(633L, 1L);
        // 1000ms、2/6 与 1/3 等效
        assertThat(HandicapScore.score(1000L, 0L, 2, 6)).containsExactly(1000L, 3L);
    }

    @Test
    void 精确比较区分展示值相同的分数() {
        // 1000/3 = 333.33... 严格大于 333/1，尽管展示值都是 333
        assertThat(HandicapScore.compare(1000L, 3L, 333L, 1L)).isPositive();
        assertThat(HandicapScore.compare(333L, 1L, 1000L, 3L)).isNegative();
        // 等比例分数精确相等
        assertThat(HandicapScore.compare(1L, 3L, 2L, 6L)).isZero();
        assertThat(HandicapScore.compare(333L, 1L, 333L, 1L)).isZero();
    }

    @Test
    void 展示值按HALF_UP舍入到整数毫秒() {
        assertThat(HandicapScore.displayMs(1000L, 3L)).isEqualTo(333L);
        assertThat(HandicapScore.displayMs(999L, 3L)).isEqualTo(333L);
        // 0.5 向上进位
        assertThat(HandicapScore.displayMs(1L, 2L)).isEqualTo(1L);
        assertThat(HandicapScore.displayMs(3L, 2L)).isEqualTo(2L);
        // 小于 0.5 舍去
        assertThat(HandicapScore.displayMs(5L, 2L)).isEqualTo(3L);
        assertThat(HandicapScore.displayMs(7L, 4L)).isEqualTo(2L);
    }
}
