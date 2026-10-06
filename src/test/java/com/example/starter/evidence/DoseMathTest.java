package com.example.starter.evidence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 剂量换算单元测试：内部精确累计无舍入，展示 HALF_UP 四位不参与判定。
 */
class DoseMathTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 22, 8, 0, 0);

    @Test
    void segmentDoseMatchesExactLuxMinutes() {
        // 10 分钟 @6 lux = 60 勒克斯分钟
        BigDecimal dose = DoseMath.segmentDose(new BigDecimal("6"), T0, T0.plusMinutes(10));
        assertThat(dose).isEqualByComparingTo(DoseMath.limitToInternal(new BigDecimal("60")));
        // 内部单位为整数（毫勒克斯×纳秒），无舍入
        assertThat(dose.stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
    }

    @Test
    void fractionalLuxAndSecondsAccumulateExactly() {
        // 2 分钟 @0.125 lux = 0.25 勒克斯分钟
        BigDecimal dose = DoseMath.segmentDose(new BigDecimal("0.125"), T0, T0.plusMinutes(2));
        assertThat(dose).isEqualByComparingTo(DoseMath.limitToInternal(new BigDecimal("0.250")));
        // 90 秒 @2 lux = 3 勒克斯分钟
        BigDecimal ninetySeconds = DoseMath.segmentDose(new BigDecimal("2"), T0, T0.plusSeconds(90));
        assertThat(ninetySeconds).isEqualByComparingTo(DoseMath.limitToInternal(new BigDecimal("3")));
    }

    @Test
    void reachesLimitBoundaryIsExact() {
        BigDecimal limit = new BigDecimal("100");
        BigDecimal exact = DoseMath.limitToInternal(limit);
        assertThat(DoseMath.reachesLimit(exact, limit)).isTrue();
        assertThat(DoseMath.reachesLimit(exact.subtract(BigDecimal.ONE), limit)).isFalse();
        assertThat(DoseMath.reachesLimit(exact.add(BigDecimal.ONE), limit)).isTrue();
    }

    @Test
    void displayRoundsHalfUpButJudgmentStaysExact() {
        // 10 秒 @1 lux = 1/6 勒克斯分钟 = 0.16666...，展示 0.1667
        BigDecimal sixth = DoseMath.segmentDose(BigDecimal.ONE, T0, T0.plusSeconds(10));
        assertThat(DoseMath.toDisplayLuxMinutes(sixth)).isEqualByComparingTo(new BigDecimal("0.1667"));
        // 20009 秒 @0.002 lux = 0.666966... 勒克斯分钟，展示 0.6670，但精确值低于上限 0.667
        BigDecimal dose = DoseMath.segmentDose(new BigDecimal("0.002"), T0, T0.plusSeconds(20009));
        assertThat(DoseMath.toDisplayLuxMinutes(dose)).isEqualByComparingTo(new BigDecimal("0.6670"));
        assertThat(DoseMath.reachesLimit(dose, new BigDecimal("0.667"))).isFalse();
    }
}
