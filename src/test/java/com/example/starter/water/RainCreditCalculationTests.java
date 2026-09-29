package com.example.starter.water;

import com.example.starter.water.WaterRepository.RainRecordRow;
import com.example.starter.water.dto.Dtos.AppliedRainView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降雨抵扣纯计算单元测试：覆盖系数/雨量边界、封顶守恒、分摊稳定性与舍入。
 * 不接触数据库；数据库侧的事务、唯一约束、回滚与并发由其余测试在真实 H2 上验证。
 */
class RainCreditCalculationTests {

    private RainRecordRow rain(String key, String millis) {
        return new RainRecordRow(0, key, 1L, new BigDecimal(millis), 0L, "EFFECTIVE", "clerk",
                0L, 0L, null, null);
    }

    @Test
    void nullCoefficientOrNoRainYieldsZeroCredit() {
        WaterService.CreditCalc noCoefficient = WaterService.computeCredit(null,
                List.of(rain("r1", "10")), new BigDecimal("5"));
        assertEquals("0", WaterService.fmt(noCoefficient.credit()));
        assertEquals("5", WaterService.fmt(noCoefficient.quotaDeduction()));
        assertTrue(noCoefficient.applied().isEmpty());

        WaterService.CreditCalc noRain = WaterService.computeCredit(new BigDecimal("0.2"),
                List.of(), new BigDecimal("5"));
        assertEquals("0", WaterService.fmt(noRain.credit()));
        assertEquals("5", WaterService.fmt(noRain.quotaDeduction()));
        assertTrue(noRain.applied().isEmpty());
    }

    @Test
    void creditIsRainMillisTimesCoefficientAndConservationHolds() {
        // 10 毫米 * 0.2 立方米/毫米 = 2 立方米抵扣；核销 5 -> 配额扣减 3；2 + 3 = 5
        WaterService.CreditCalc calc = WaterService.computeCredit(new BigDecimal("0.200000"),
                List.of(rain("r1", "10.000")), new BigDecimal("5.000"));
        assertEquals(new BigDecimal("2.000"), calc.credit());
        assertEquals(new BigDecimal("3.000"), calc.quotaDeduction());
        assertEquals(0, calc.credit().add(calc.quotaDeduction()).compareTo(new BigDecimal("5.000")));
        assertEquals(1, calc.applied().size());
        AppliedRainView view = calc.applied().get(0);
        assertEquals("r1", view.rainKey());
        assertEquals("10", view.rainMillis());
        assertEquals("2", view.credit());
    }

    @Test
    void creditNeverExceedsWriteoffAmountAndQuotaStaysNonNegative() {
        // 系数很大：理论抵扣 1000 * 10 = 10000，封顶为本次核销量 5，配额扣减为 0
        WaterService.CreditCalc calc = WaterService.computeCredit(new BigDecimal("10"),
                List.of(rain("r1", "1000")), new BigDecimal("5"));
        assertEquals("5", WaterService.fmt(calc.credit()));
        assertEquals("0", WaterService.fmt(calc.quotaDeduction()));
        assertEquals(0, calc.credit().add(calc.quotaDeduction()).compareTo(new BigDecimal("5")));
        assertEquals("5", calc.applied().get(0).credit());
    }

    @Test
    void multipleRainsAreAppliedInStableRainKeyOrderAndSplitConsistently() {
        // r-after 20mm -> 4，r-before 15mm -> 3；核销 5：按 rainKey 字典序 r-after 先承担 4，r-before 再承担 1
        List<RainRecordRow> unordered = List.of(rain("r-after", "20"), rain("r-before", "15"));
        WaterService.CreditCalc calc = WaterService.computeCredit(new BigDecimal("0.2"),
                unordered, new BigDecimal("5"));
        assertEquals("5", WaterService.fmt(calc.credit()));
        assertEquals("0", WaterService.fmt(calc.quotaDeduction()));
        assertEquals(2, calc.applied().size());
        // 无论入参顺序如何，证据集合按 rainKey 稳定排序
        assertEquals("r-after", calc.applied().get(0).rainKey());
        assertEquals("4", calc.applied().get(0).credit());
        assertEquals("r-before", calc.applied().get(1).rainKey());
        assertEquals("1", calc.applied().get(1).credit());
        assertEquals(0, calc.credit().add(calc.quotaDeduction()).compareTo(new BigDecimal("5")));
    }

    @Test
    void roundingIsHalfUpToThreeDecimals() {
        // 3.333 毫米 * 0.5 = 1.6665 -> 四舍五入 1.667
        WaterService.CreditCalc calc = WaterService.computeCredit(new BigDecimal("0.5"),
                List.of(rain("r1", "3.333")), new BigDecimal("10"));
        assertEquals(new BigDecimal("1.667"), calc.credit());
        assertEquals(new BigDecimal("8.333"), calc.quotaDeduction());
    }

    @Test
    void zeroCoefficientProducesNoCreditAndNoEvidence() {
        WaterService.CreditCalc calc = WaterService.computeCredit(new BigDecimal("0.000000"),
                List.of(rain("r1", "10")), new BigDecimal("5"));
        assertEquals("0", WaterService.fmt(calc.credit()));
        assertEquals("5", WaterService.fmt(calc.quotaDeduction()));
        assertTrue(calc.applied().isEmpty());
    }
}
