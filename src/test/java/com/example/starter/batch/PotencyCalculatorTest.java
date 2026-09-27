package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效价计算核心单元测试：UTC 连续日线性衰减、阈值边界（左闭右开、严格小于才不合格）、
 * 合批数量加权与权重守恒、HALF_UP 舍入。
 */
class PotencyCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private PotencyCalculator.Model model(String initial, String min, String decay, Instant at) {
        return new PotencyCalculator.Model(1, new BigDecimal(initial), new BigDecimal(min),
                new BigDecimal(decay), at);
    }

    @Test
    void elapsedDays_continuousUtcDays_notWholeDays() {
        // 12 小时 = 0.5 个 UTC 日；90 天整
        assertEquals("0.500000", PotencyCalculator.elapsedDays(
                T0, Instant.parse("2026-03-01T12:00:00Z")).toPlainString());
        assertEquals("90.000000", PotencyCalculator.elapsedDays(
                T0, Instant.parse("2026-05-30T00:00:00Z")).toPlainString());
        // 1 小时 = 1/24，6 位 HALF_UP
        assertEquals("0.041667", PotencyCalculator.elapsedDays(
                T0, Instant.parse("2026-03-01T01:00:00Z")).toPlainString());
    }

    @Test
    void evaluate_linearDecay_overContinuousDays() {
        // 初始 100，日衰减 0.5；2.5 个 UTC 日后当前效价 = 98.75
        PotencyCalculator.Computed c = PotencyCalculator.evaluate(
                model("100", "90", "0.5", T0), Instant.parse("2026-03-03T12:00:00Z"));
        assertEquals("2.500000", c.elapsedDays().toPlainString());
        assertEquals("1.250000", c.decayedPotency().toPlainString());
        assertEquals("98.750000", c.currentPotency().toPlainString());
        assertEquals("8.750000", c.difference().toPlainString());
        assertTrue(c.pass());
    }

    @Test
    void evaluate_thresholdBoundary_equalPasses_strictlyBelowFails() {
        // 1 个 UTC 日后：100 - 10 = 90，恰好等于阈值 90 → 合格（左闭）
        PotencyCalculator.Computed atBoundary = PotencyCalculator.evaluate(
                model("100", "90", "10", T0), Instant.parse("2026-03-02T00:00:00Z"));
        assertEquals("90.000000", atBoundary.currentPotency().toPlainString());
        assertEquals("0.000000", atBoundary.difference().toPlainString());
        assertTrue(atBoundary.pass());

        // 再晚 1 分钟（右开：超过 1 日即落入下一区间），舍入后严格小于 → 不合格
        PotencyCalculator.Computed below = PotencyCalculator.evaluate(
                model("100", "90", "10", T0), Instant.parse("2026-03-02T00:01:00Z"));
        assertEquals("89.993060", below.currentPotency().toPlainString());
        assertFalse(below.pass());
        assertTrue(below.difference().signum() < 0);
    }

    @Test
    void evaluate_zeroDecay_keepsInitialPotency() {
        PotencyCalculator.Computed c = PotencyCalculator.evaluate(
                model("100", "100", "0", T0), Instant.parse("2027-01-01T00:00:00Z"));
        assertEquals("100.000000", c.currentPotency().toPlainString());
        assertTrue(c.pass());
    }

    @Test
    void weight_quantitiesWeightPotencyAndConserveWeights() {
        // 父 A 数量 30 效价 100 阈值 90；父 B 数量 70 效价 80 阈值 80
        List<PotencyCalculator.Contribution> cs = List.of(
                new PotencyCalculator.Contribution("A", new BigDecimal("30"),
                        new BigDecimal("100.000000"), new BigDecimal("90")),
                new PotencyCalculator.Contribution("B", new BigDecimal("70"),
                        new BigDecimal("80.000000"), new BigDecimal("80")));
        PotencyCalculator.Weighted w = PotencyCalculator.weight(cs,
                List.of(new BigDecimal("0.5"), new BigDecimal("0.2")));

        assertEquals("0.3000000000", w.weights().get(0).toPlainString());
        assertEquals("0.7000000000", w.weights().get(1).toPlainString());
        // 权重严格守恒为 1
        assertEquals(BigDecimal.ONE.setScale(PotencyCalculator.WEIGHT_SCALE),
                w.weights().get(0).add(w.weights().get(1)));
        // 100*0.3 + 80*0.7 = 86
        assertEquals("86.000000", w.weightedPotency().toPlainString());
        // 子批阈值取父批最严格（最大）阈值 90
        assertEquals("90.000000", w.strictestMin().toPlainString());
        // 子批衰减率数量加权：0.5*0.3 + 0.2*0.7 = 0.29
        assertEquals("0.290000", w.weightedDecay().toPlainString());
    }

    @Test
    void weight_roundingResidualAbsorbedByLastWeight_andStillConserves() {
        // 三等分数量：单权重 1/3 舍入到 10 位，末位吸收残差，合计严格为 1
        List<PotencyCalculator.Contribution> cs = List.of(
                new PotencyCalculator.Contribution("A", new BigDecimal("1"),
                        new BigDecimal("100"), new BigDecimal("90")),
                new PotencyCalculator.Contribution("B", new BigDecimal("1"),
                        new BigDecimal("100"), new BigDecimal("90")),
                new PotencyCalculator.Contribution("C", new BigDecimal("1"),
                        new BigDecimal("100"), new BigDecimal("90")));
        PotencyCalculator.Weighted w = PotencyCalculator.weight(cs,
                List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals("0.3333333333", w.weights().get(0).toPlainString());
        assertEquals("0.3333333333", w.weights().get(1).toPlainString());
        assertEquals("0.3333333334", w.weights().get(2).toPlainString());
        BigDecimal sum = w.weights().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(BigDecimal.ONE.setScale(PotencyCalculator.WEIGHT_SCALE), sum);
        assertEquals("100.000000", w.weightedPotency().toPlainString());
    }

    @Test
    void weight_rejectsNonPositiveTotal() {
        List<PotencyCalculator.Contribution> cs = List.of(
                new PotencyCalculator.Contribution("A", BigDecimal.ZERO,
                        new BigDecimal("100"), new BigDecimal("90")));
        assertThrows(IllegalArgumentException.class, () -> PotencyCalculator.weight(cs,
                List.of(BigDecimal.ZERO)));
    }
}
