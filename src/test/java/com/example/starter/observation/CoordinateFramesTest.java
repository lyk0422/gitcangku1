package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 坐标基准转换纯逻辑单元测试：UTC 左闭右开生效区间、六位小数 HALF_UP、站点矩形与边界点。
 */
class CoordinateFramesTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

    private CoordinateRule rule(int version, BigDecimal offsetX, BigDecimal offsetY,
                                Instant from, Instant to) {
        return new CoordinateRule(version, "TARGET", offsetX, offsetY, from, to);
    }

    @Test
    void effectiveWindowIsLeftClosedRightOpen() {
        CoordinateRule rule = rule(1, BigDecimal.ONE, BigDecimal.ONE, T0, T1);

        assertThat(CoordinateFrames.isEffective(rule, T0.minusSeconds(1))).isFalse();
        // 左边界：等于起点时生效
        assertThat(CoordinateFrames.isEffective(rule, T0)).isTrue();
        assertThat(CoordinateFrames.isEffective(rule, T0.plusSeconds(1))).isTrue();
        // 右边界：等于终点时不生效
        assertThat(CoordinateFrames.isEffective(rule, T1)).isFalse();
        assertThat(CoordinateFrames.isEffective(rule, T1.plusSeconds(1))).isFalse();
    }

    @Test
    void nullEffectiveToMeansOpenEnded() {
        CoordinateRule rule = rule(1, BigDecimal.ZERO, BigDecimal.ZERO, T0, null);

        assertThat(CoordinateFrames.isEffective(rule, T0)).isTrue();
        assertThat(CoordinateFrames.isEffective(rule, Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
        assertThat(CoordinateFrames.isEffective(rule, T0.minusSeconds(1))).isFalse();
    }

    @Test
    void effectiveRulesSelectsUniqueRuleAtBoundaryInstants() {
        // V1 [T0,T1)，V2 [T1,T2)：T1 恰好只命中 V2
        List<CoordinateRule> rules = List.of(
                rule(1, new BigDecimal("0.1"), new BigDecimal("0.1"), T0, T1),
                rule(2, new BigDecimal("0.5"), new BigDecimal("-0.5"), T1, T2));

        assertThat(CoordinateFrames.effectiveRules(rules, T0)).extracting(CoordinateRule::ruleVersion)
                .containsExactly(1);
        assertThat(CoordinateFrames.effectiveRules(rules, T1.minusSeconds(1))).extracting(CoordinateRule::ruleVersion)
                .containsExactly(1);
        assertThat(CoordinateFrames.effectiveRules(rules, T1)).extracting(CoordinateRule::ruleVersion)
                .containsExactly(2);
        assertThat(CoordinateFrames.effectiveRules(rules, T2)).isEmpty();
        assertThat(CoordinateFrames.effectiveRules(rules, T0.minusSeconds(1))).isEmpty();
    }

    @Test
    void effectiveRulesReturnsAllOverlappingRules() {
        List<CoordinateRule> rules = List.of(
                rule(1, BigDecimal.ZERO, BigDecimal.ZERO, T0, null),
                rule(2, BigDecimal.ZERO, BigDecimal.ZERO, T1, null));

        // T1 起两条同时生效（重叠），交由业务层返回 422
        assertThat(CoordinateFrames.effectiveRules(rules, T1)).extracting(CoordinateRule::ruleVersion)
                .containsExactly(1, 2);
    }

    @Test
    void convertAddsOffsetsAndRoundsHalfUpToSixDigits() {
        // 题目主例：(10,20) + (0.5,-0.5) = (10.5,19.5)，按六位小数保存
        assertThat(CoordinateFrames.convert(new BigDecimal("10"), new BigDecimal("0.5")))
                .isEqualByComparingTo("10.500000");
        assertThat(CoordinateFrames.convert(new BigDecimal("20"), new BigDecimal("-0.5")))
                .isEqualByComparingTo("19.500000");
        // 结果必须保留六位小数的刻度
        assertThat(CoordinateFrames.convert(new BigDecimal("10"), new BigDecimal("0.5")).scale()).isEqualTo(6);

        // HALF_UP：等距远离零（区别于 HALF_EVEN 的奇进偶舍）
        assertThat(CoordinateFrames.convert(new BigDecimal("0.0000005"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.000001");
        assertThat(CoordinateFrames.convert(new BigDecimal("0.0000015"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.000002");
        assertThat(CoordinateFrames.convert(new BigDecimal("-0.0000005"), BigDecimal.ZERO))
                .isEqualByComparingTo("-0.000001");
        assertThat(CoordinateFrames.convert(new BigDecimal("1.2345665"), BigDecimal.ZERO))
                .isEqualByComparingTo("1.234567");
    }

    @Test
    void siteBoundaryPointsAreInside() {
        // 矩形 X ∈ [0,10]、Y ∈ [0,30]，四个角与各边中点均视为站内
        assertThat(CoordinateFrames.withinSite(new BigDecimal("0"), new BigDecimal("0"))).isTrue();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("10"), new BigDecimal("30"))).isTrue();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("0"), new BigDecimal("30"))).isTrue();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("10"), new BigDecimal("0"))).isTrue();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("10.000000"), new BigDecimal("30.000000"))).isTrue();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("5"), new BigDecimal("30"))).isTrue();

        // 题目主例：(10.5,19.5) 因 X 超出上界落在站外
        assertThat(CoordinateFrames.withinSite(new BigDecimal("10.5"), new BigDecimal("19.5"))).isFalse();
    }

    @Test
    void pointsOutsideRectAreOutside() {
        assertThat(CoordinateFrames.withinSite(new BigDecimal("10.000001"), new BigDecimal("20"))).isFalse();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("-0.000001"), new BigDecimal("20"))).isFalse();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("5"), new BigDecimal("30.000001"))).isFalse();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("5"), new BigDecimal("-0.000001"))).isFalse();
        assertThat(CoordinateFrames.withinSite(new BigDecimal("-0.000001"), new BigDecimal("30.000001")))
                .isFalse();
    }
}
