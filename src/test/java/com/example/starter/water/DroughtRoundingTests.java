package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterService.DroughtTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旱情比例削减取整的纯单元测试：3 位小数 HALF_UP、同级差额由申请标识升序首笔承担、
 * 各级削减总量守恒（目标之和等于原总量按比例取整值）。
 */
class DroughtRoundingTests {

    private AllocationRow allocation(String key, String priority, String baseline) {
        return new AllocationRow(1L, key, 1L, "user-" + key, priority, new BigDecimal(baseline),
                new BigDecimal(baseline), new BigDecimal(baseline), "actor", "APPROVED", 1L, 1L);
    }

    private Map<String, Integer> pct(int e, int n, int d) {
        return Map.of("ESSENTIAL", e, "NORMAL", n, "DEFERRABLE", d);
    }

    private BigDecimal sumTargets(List<DroughtTarget> targets) {
        return targets.stream().map(DroughtTarget::targetHeld).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumReduced(List<DroughtTarget> targets) {
        return targets.stream().map(DroughtTarget::reducedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void halfUpRoundingWithConservationDifferenceOnFirstByKey() {
        // 3 笔 NORMAL 各 1.000，削减 30%：逐笔 0.700，和 2.100；总量 3.000*0.7=2.100，无差额
        List<AllocationRow> rows = new ArrayList<>(List.of(
                allocation("ak-a", "NORMAL", "1.000"),
                allocation("ak-b", "NORMAL", "1.000"),
                allocation("ak-c", "NORMAL", "1.000")));
        List<DroughtTarget> targets = WaterService.planTargets(rows, pct(0, 30, 30));
        assertEquals(3, targets.size());
        for (DroughtTarget t : targets) {
            assertEquals(0, t.targetHeld().compareTo(new BigDecimal("0.700")));
            assertEquals(0, t.reducedAmount().compareTo(new BigDecimal("0.300")));
        }
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("2.100")));
        assertEquals(0, sumReduced(targets).compareTo(new BigDecimal("0.900")));

        // 3 笔 NORMAL 各 1.000，削减 35%：逐笔 0.650（0.65 HALF_UP），和 1.950；
        // 总量 3*0.65=1.950，无差额。改为各 1.001：逐笔 0.651（1.001*0.65=0.65065 HALF_UP=0.651），
        // 和 1.953；总量 3.003*0.65=1.95195 HALF_UP=1.952；差额 -0.001 由首笔承担 -> 0.650
        rows = new ArrayList<>(List.of(
                allocation("ak-a", "NORMAL", "1.001"),
                allocation("ak-b", "NORMAL", "1.001"),
                allocation("ak-c", "NORMAL", "1.001")));
        targets = WaterService.planTargets(rows, pct(0, 35, 35));
        assertEquals(0, targets.get(0).targetHeld().compareTo(new BigDecimal("0.650")),
                "首笔（申请标识升序）承担 -0.001 差额");
        assertEquals(0, targets.get(1).targetHeld().compareTo(new BigDecimal("0.651")));
        assertEquals(0, targets.get(2).targetHeld().compareTo(new BigDecimal("0.651")));
        // 守恒：目标之和 = 原总量乘比例 HALF_UP
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("1.952")));
        // 削减总量 = 原总量 - 目标之和 = 3.003 - 1.952 = 1.051
        assertEquals(0, sumReduced(targets).compareTo(new BigDecimal("1.051")));
        assertEquals(0, new BigDecimal("3.003").subtract(sumTargets(targets))
                .compareTo(sumReduced(targets)));
    }

    @Test
    void firstByAllocationKeyBearsDifferenceRegardlessOfInputOrder() {
        // 即使入参乱序，NORMAL 组仍按申请标识升序，差额由 ak-1 承担
        List<AllocationRow> rows = new ArrayList<>(List.of(
                allocation("ak-3", "NORMAL", "1.001"),
                allocation("ak-1", "NORMAL", "1.001"),
                allocation("ak-2", "NORMAL", "1.001")));
        List<DroughtTarget> targets = WaterService.planTargets(rows, pct(0, 35, 35));
        DroughtTarget first = targets.stream()
                .filter(t -> t.row().allocationKey().equals("ak-1")).findFirst().orElseThrow();
        assertEquals(0, first.targetHeld().compareTo(new BigDecimal("0.650")));
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("1.952")));
    }

    @Test
    void eachPriorityGroupConservesIndependently() {
        // ESSENTIAL 2 笔各 1.000 削减 10% -> 各 0.900，和 1.800
        // NORMAL 3 笔各 1.001 削减 35% -> 0.650/0.651/0.651，和 1.952
        // DEFERRABLE 1 笔 2.000 削减 100% -> 0
        List<AllocationRow> rows = new ArrayList<>(List.of(
                allocation("ak-e1", "ESSENTIAL", "1.000"),
                allocation("ak-e2", "ESSENTIAL", "1.000"),
                allocation("ak-n1", "NORMAL", "1.001"),
                allocation("ak-n2", "NORMAL", "1.001"),
                allocation("ak-n3", "NORMAL", "1.001"),
                allocation("ak-d1", "DEFERRABLE", "2.000")));
        List<DroughtTarget> targets = WaterService.planTargets(rows, pct(10, 35, 100));

        BigDecimal essentialTargets = groupSum(targets, "ESSENTIAL");
        BigDecimal normalTargets = groupSum(targets, "NORMAL");
        BigDecimal deferrableTargets = groupSum(targets, "DEFERRABLE");
        assertEquals(0, essentialTargets.compareTo(new BigDecimal("1.800")));
        assertEquals(0, normalTargets.compareTo(new BigDecimal("1.952")));
        assertEquals(0, deferrableTargets.compareTo(new BigDecimal("0.000")));
        // 总守恒：原总量 7.003 - 目标和 3.752 = 削减 3.251
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("3.752")));
        assertEquals(0, sumReduced(targets).compareTo(new BigDecimal("3.251")));
        assertEquals(0, new BigDecimal("7.003").subtract(sumTargets(targets))
                .compareTo(sumReduced(targets)));
        for (DroughtTarget t : targets) {
            assertTrue(t.targetHeld().signum() >= 0);
            assertTrue(t.targetHeld().compareTo(t.originalHeld()) <= 0);
            // 目标值最多 3 位小数
            assertEquals(0, t.targetHeld().compareTo(t.targetHeld().setScale(3)));
        }
    }

    @Test
    void noneLevelPctZeroRestoresBaselineExactly() {
        List<AllocationRow> rows = new ArrayList<>(List.of(
                allocation("ak-a", "NORMAL", "1.001"),
                allocation("ak-b", "DEFERRABLE", "2.345")));
        List<DroughtTarget> targets = WaterService.planTargets(rows, pct(0, 0, 0));
        assertEquals(0, targets.get(0).targetHeld().compareTo(new BigDecimal("1.001")));
        assertEquals(0, targets.get(1).targetHeld().compareTo(new BigDecimal("2.345")));
        assertEquals(0, sumReduced(targets).compareTo(BigDecimal.ZERO));
    }

    @Test
    void oddPercentagesRoundEachGroupToThousandth() {
        // 3 笔各 1.000，NORMAL 削减 33%：逐笔 0.670（0.67 HALF_UP），和 2.010；
        // 总量 3*0.67=2.010 无差额；削减 0.990
        List<AllocationRow> rows = new ArrayList<>(List.of(
                allocation("ak-a", "NORMAL", "1.000"),
                allocation("ak-b", "NORMAL", "1.000"),
                allocation("ak-c", "NORMAL", "1.000")));
        List<DroughtTarget> targets = WaterService.planTargets(rows, pct(0, 33, 33));
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("2.010")));
        assertEquals(0, sumReduced(targets).compareTo(new BigDecimal("0.990")));

        // 33% 作用于 1.001：逐笔 0.671（1.001*0.67=0.67067 HALF_UP），和 2.013；
        // 总量 3.003*0.67=2.01201 HALF_UP=2.012；差额 -0.001 首笔承担
        rows = new ArrayList<>(List.of(
                allocation("ak-a", "NORMAL", "1.001"),
                allocation("ak-b", "NORMAL", "1.001"),
                allocation("ak-c", "NORMAL", "1.001")));
        targets = WaterService.planTargets(rows, pct(0, 33, 33));
        assertEquals(0, targets.get(0).targetHeld().compareTo(new BigDecimal("0.670")));
        assertEquals(0, sumTargets(targets).compareTo(new BigDecimal("2.012")));
        assertEquals(0, sumReduced(targets).compareTo(new BigDecimal("0.991")));
    }

    private BigDecimal groupSum(List<DroughtTarget> targets, String priority) {
        return targets.stream().filter(t -> t.row().priority().equals(priority))
                .map(DroughtTarget::targetHeld).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
