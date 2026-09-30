package com.example.starter.germination.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发芽率规则纯单元测试：样本边界、发芽数约束、比例与 85% 判定。
 */
class GerminationRulesTest {

    @Test
    void sampleCountBoundary() {
        assertThat(GerminationRules.isSampleCountValid(99)).isFalse();
        assertThat(GerminationRules.isSampleCountValid(100)).isTrue();
        assertThat(GerminationRules.isSampleCountValid(400)).isTrue();
        assertThat(GerminationRules.isSampleCountValid(401)).isFalse();
    }

    @Test
    void germinatedCountMustNotExceedSample() {
        assertThat(GerminationRules.isGerminatedCountValid(100, -1)).isFalse();
        assertThat(GerminationRules.isGerminatedCountValid(100, 0)).isTrue();
        assertThat(GerminationRules.isGerminatedCountValid(100, 100)).isTrue();
        assertThat(GerminationRules.isGerminatedCountValid(100, 101)).isFalse();
    }

    @Test
    void ratioRoundedHalfUpToTwoDecimals() {
        assertThat(GerminationRules.ratioPercent(300, 255)).isEqualByComparingTo("85.00");
        // 84.666...% 四舍五入为 84.67，仍低于 85
        assertThat(GerminationRules.ratioPercent(300, 254)).isEqualByComparingTo("84.67");
        // 84.995 四舍五入为 85.00
        assertThat(GerminationRules.ratioPercent(200, 170)).isEqualByComparingTo(new BigDecimal("85.00"));
    }

    @Test
    void passThresholdIs85Percent() {
        assertThat(GerminationRules.isPassing(100, 85)).isTrue();
        assertThat(GerminationRules.isPassing(100, 84)).isFalse();
        assertThat(GerminationRules.isPassing(300, 254)).isFalse();
        assertThat(GerminationRules.isPassing(400, 340)).isTrue();
    }
}
