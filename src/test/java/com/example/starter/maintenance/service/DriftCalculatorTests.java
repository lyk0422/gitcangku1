package com.example.starter.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.api.dto.DriftAnchorRequest;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.service.DriftCalculator.Plan;
import com.example.starter.maintenance.service.DriftCalculator.PlannedReading;

/**
 * {@link DriftCalculator} 纯计算单元测试：线性插值、0.001 小时精度、
 * 锚点严格递增、单调边界、冻结点与区间外相邻冲突。
 */
class DriftCalculatorTests {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Reading reading(String id, long minuteOffset, long cumulativeMinutes, int revisionNo) {
        return new Reading("eq", id, T0.plusSeconds(minuteOffset * 60), cumulativeMinutes, revisionNo);
    }

    private Map<String, Reading> mapOf(Reading... readings) {
        Map<String, Reading> map = new LinkedHashMap<>();
        for (Reading reading : readings) {
            map.put(reading.readingId(), reading);
        }
        return map;
    }

    private DriftAnchorRequest anchor(String id, int revision, String hours) {
        return new DriftAnchorRequest(id, revision, new BigDecimal(hours));
    }

    private PlannedReading byId(Plan plan, String id) {
        return plan.readings().stream()
                .filter(p -> p.reading().readingId().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    void linearInterpolation_midpointAndRounding() {
        // 锚点 r1（0min，60 分钟=1.000 小时）与 r4（600min，120 分钟=2.000 小时）
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 300, 90, 1);
        Reading r3 = reading("r3", 301, 91, 1);
        Reading r4 = reading("r4", 600, 120, 1);
        Map<String, Reading> byId = mapOf(r1, r2, r3, r4);

        Plan plan = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r4", 1, "2.000")),
                byId, List.of(r1, r2, r3, r4), Set.of());

        assertTrue(plan.valid(), "计划应可激活");
        assertEquals("1.000", byId(plan, "r1").newHours().toPlainString());
        // 300/600 处线性插值 = 1.500
        assertEquals("1.500", byId(plan, "r2").newHours().toPlainString());
        assertEquals(Integer.valueOf(1), byId(plan, "r2").segmentIndex());
        // 301/600 = 1.501666... 四舍五入到 0.001 = 1.502
        assertEquals("1.502", byId(plan, "r3").newHours().toPlainString());
        assertEquals("2.000", byId(plan, "r4").newHours().toPlainString());
        assertNull(byId(plan, "r1").segmentIndex());
        assertTrue(byId(plan, "r1").anchor());
        assertFalse(byId(plan, "r2").anchor());
        // 新版本号 = 旧版本号 + 1
        assertEquals(1, byId(plan, "r2").oldRevisionNo());
        assertEquals(2, byId(plan, "r2").newRevisionNo());
        // 旧值由分钟精确换算：90 分钟 = 1.500000 小时
        assertEquals("1.500000", byId(plan, "r2").oldHours().toPlainString());
    }

    @Test
    void anchorsReordered_equivalentAfterNormalization() {
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 60, 90, 1);
        Map<String, Reading> byId = mapOf(r1, r2);
        List<Reading> interval = List.of(r1, r2);

        Plan ordered = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r2", 1, "2.000")),
                byId, interval, Set.of());
        Plan reversed = DriftCalculator.compute(
                List.of(anchor("r2", 1, "2.000"), anchor("r1", 1, "1.000")),
                byId, interval, Set.of());

        assertTrue(ordered.valid());
        assertTrue(reversed.valid());
        // 规范化后锚点顺序一致、首锚点为 r1
        assertEquals("r1", ordered.anchors().get(0).reading().readingId());
        assertEquals("r1", reversed.anchors().get(0).reading().readingId());
        assertEquals(ordered.readings().get(0).newHours(), reversed.readings().get(0).newHours());
    }

    @Test
    void anchorsMustBeStrictlyIncreasing() {
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 60, 90, 1);
        Map<String, Reading> byId = mapOf(r1, r2);

        Plan equal = DriftCalculator.compute(
                List.of(anchor("r1", 1, "2.000"), anchor("r2", 1, "2.000")),
                byId, List.of(r1, r2), Set.of());
        assertEquals("CORRECTION_ANCHOR_NOT_STRICT_INCREASING", equal.violation());

        Plan decreasing = DriftCalculator.compute(
                List.of(anchor("r1", 1, "3.000"), anchor("r2", 1, "2.000")),
                byId, List.of(r1, r2), Set.of());
        assertEquals("CORRECTION_ANCHOR_NOT_STRICT_INCREASING", decreasing.violation());
    }

    @Test
    void anchorRevisionChanged_rejected() {
        Reading r1 = reading("r1", 0, 60, 3);
        Reading r2 = reading("r2", 60, 90, 1);
        Map<String, Reading> byId = mapOf(r1, r2);
        Plan plan = DriftCalculator.compute(
                List.of(anchor("r1", 2, "1.000"), anchor("r2", 1, "2.000")),
                byId, List.of(r1, r2), Set.of());
        assertEquals("ANCHOR_REVISION_CONFLICT", plan.violation());
    }

    @Test
    void anchorDuplicatedAndMissing_rejected() {
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 60, 90, 1);
        Map<String, Reading> byId = mapOf(r1, r2);
        Plan duplicated = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r1", 1, "2.000")),
                byId, List.of(r1), Set.of());
        assertEquals("CORRECTION_ANCHOR_DUPLICATED", duplicated.violation());

        Plan missing = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r9", 1, "2.000")),
                byId, List.of(r1), Set.of());
        assertEquals("CORRECTION_ANCHOR_READING_NOT_FOUND", missing.violation());
    }

    @Test
    void precisionBeyondOneThousandthHour_rejected() {
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 60, 90, 1);
        Map<String, Reading> byId = mapOf(r1, r2);
        Plan plan = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.0001"), anchor("r2", 1, "2.000")),
                byId, List.of(r1, r2), Set.of());
        assertEquals("CORRECTION_ANCHOR_PRECISION", plan.violation());
    }

    @Test
    void frozenReadingInsideSet_wholePlanRejectedAndFlagged() {
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 300, 90, 1);
        Reading r3 = reading("r3", 600, 120, 1);
        Map<String, Reading> byId = mapOf(r1, r2, r3);
        // 中间读数 r2 被已完成保养冻结
        Plan plan = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r3", 1, "2.000")),
                byId, List.of(r1, r2, r3), Set.of("r2"));
        assertEquals("CORRECTION_FROZEN_READING", plan.violation());
        assertFalse(plan.valid());
        // 不能只修其余读数：预览计划仍标记冻结点
        assertTrue(byId(plan, "r2").frozen());
        assertFalse(byId(plan, "r1").frozen());
    }

    @Test
    void interpolatedValuesAreMonotonicForStrictAnchors() {
        // 严格递增锚点产生的整条修正序列必须单调不减
        Reading r1 = reading("r1", 0, 0, 1);
        Reading r2 = reading("r2", 1, 1000, 1);
        Reading r3 = reading("r3", 2, 1000, 1);
        Reading r4 = reading("r4", 600, 1000, 1);
        Map<String, Reading> byId = mapOf(r1, r2, r3, r4);
        Plan plan = DriftCalculator.compute(
                List.of(anchor("r1", 1, "1.000"), anchor("r4", 1, "1.002")),
                byId, List.of(r1, r2, r3, r4), Set.of());
        // 1.000 -> 1.002 over 600 min; early points round to 1.000 (equal, not less) => monotonic
        assertTrue(plan.valid(), plan.violation());
        BigDecimalSeq previous = null;
        for (PlannedReading p : plan.readings()) {
            if (previous != null) {
                assertTrue(p.newHours().compareTo(previous.value()) >= 0,
                        "修正序列须单调不减：" + previous.value() + " -> " + p.newHours());
            }
            previous = new BigDecimalSeq(p.newHours());
        }
    }

    private record BigDecimalSeq(BigDecimal value) {
    }

    @Test
    void boundaryConflictWithOutsideNeighbors() {
        // 区间 r2..r3，左邻 r1 工时高于修正首值、右邻 r4 工时低于修正尾值，均须拒绝
        Reading r1 = reading("r1", 0, 300, 1);   // 5.000000 小时
        Reading r2 = reading("r2", 60, 300, 1);
        Reading r3 = reading("r3", 120, 300, 1);
        Reading r4 = reading("r4", 180, 60, 1);   // 1.000000 小时
        Map<String, Reading> byId = mapOf(r1, r2, r3, r4);

        Plan plan = DriftCalculator.compute(
                List.of(anchor("r2", 1, "2.000"), anchor("r3", 1, "3.000")),
                byId, List.of(r2, r3), Set.of());
        assertTrue(plan.valid());
        // 左邻冲突
        assertEquals("CORRECTION_BOUNDARY_CONFLICT",
                DriftCalculator.boundaryViolation(plan, r1, null));
        // 右邻冲突（尾值 3.000 > 右邻 1.000）
        assertEquals("CORRECTION_BOUNDARY_CONFLICT",
                DriftCalculator.boundaryViolation(plan, null, r4));
        // 无区间外邻居：合法
        assertNull(DriftCalculator.boundaryViolation(plan, null, null));
    }

    @Test
    void outsideIntervalReadingsKeepOriginalValues() {
        // 区间内读数被修正；区间外读数保持原值（不进入计划）
        Reading r1 = reading("r1", 0, 60, 1);
        Reading r2 = reading("r2", 60, 90, 1);
        Reading r3 = reading("r3", 120, 120, 1);
        Map<String, Reading> byId = mapOf(r1, r2, r3);
        Plan plan = DriftCalculator.compute(
                List.of(anchor("r2", 1, "2.000")),
                byId, List.of(r2), Set.of());
        // 单锚点不满足 2~20 的请求约束在控制器层校验，计算器允许但此处用两锚点
        plan = DriftCalculator.compute(
                List.of(anchor("r2", 1, "2.000"), anchor("r3", 1, "3.000")),
                byId, List.of(r2, r3), Set.of());
        assertTrue(plan.valid());
        assertEquals(2, plan.readings().size());
        assertTrue(plan.readings().stream().noneMatch(p -> p.reading().readingId().equals("r1")));
    }
}
