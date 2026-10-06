package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HandicapScore} 纯逻辑单元测试：精确分数构造、约分、比较与 HALF_UP 展示值。
 */
class HandicapScoreTest {

    @Test
    void 有效成绩为原始毫秒乘p除q再加罚时且约分() {
        // 1000*1/3 + 0 = 1000/3，不可约
        HandicapScore a = HandicapScore.of(1000L, 0L, 1, 3);
        assertThat(a.num()).isEqualTo(BigInteger.valueOf(1000));
        assertThat(a.den()).isEqualTo(BigInteger.valueOf(3));

        // 999*1/3 = 333 = 333/1
        HandicapScore b = HandicapScore.of(999L, 0L, 1, 3);
        assertThat(b.num()).isEqualTo(BigInteger.valueOf(333));
        assertThat(b.den()).isEqualTo(BigInteger.ONE);

        // 1000*2/6 + 罚时 1 => (2000+6)/6 = 2006/6 = 1003/3
        HandicapScore c = HandicapScore.of(1000L, 1L, 2, 6);
        assertThat(c.num()).isEqualTo(BigInteger.valueOf(1003));
        assertThat(c.den()).isEqualTo(BigInteger.valueOf(3));
    }

    @Test
    void 比较使用精确分数而非舍入值() {
        // 1000/3 ≈ 333.33 与 999/3 = 333：展示值均为 333，但精确比较 999/3 更小
        HandicapScore a = HandicapScore.of(1000L, 0L, 1, 3);
        HandicapScore b = HandicapScore.of(999L, 0L, 1, 3);
        assertThat(a.displayMs()).isEqualTo(333L);
        assertThat(b.displayMs()).isEqualTo(333L);
        assertThat(b).isLessThan(a);

        // 等比例系数精确相等：1/3 与 2/6
        assertThat(HandicapScore.of(1000L, 0L, 1, 3))
                .isEqualByComparingTo(HandicapScore.of(1000L, 0L, 2, 6));
    }

    @Test
    void 展示值按HALF_UP舍入到整数毫秒() {
        assertThat(HandicapScore.of(1L, 0L, 1, 2).displayMs()).isEqualTo(1L);   // 0.5 -> 1
        assertThat(HandicapScore.of(3L, 0L, 1, 2).displayMs()).isEqualTo(2L);   // 1.5 -> 2
        assertThat(HandicapScore.of(5L, 0L, 1, 2).displayMs()).isEqualTo(3L);   // 2.5 -> 3
        assertThat(HandicapScore.of(1000L, 0L, 1, 3).displayMs()).isEqualTo(333L); // 333.33 -> 333
        assertThat(HandicapScore.of(2000L, 0L, 1, 3).displayMs()).isEqualTo(667L); // 666.67 -> 667
    }
}
