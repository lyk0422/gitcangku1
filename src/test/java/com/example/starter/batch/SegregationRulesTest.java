package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 托盘隔离纯规则单元测试：请求内重复、最终布局类别数、隔板要求值、分隔位冲突及实际/要求/差额消息。
 */
class SegregationRulesTest {

    private SegregationRules.Placed p(String batch, String category, int slot) {
        return new SegregationRules.Placed(batch, category, slot);
    }

    @Test
    void requestDuplication_detectsRepeatedBatchAndSlot() {
        assertEquals("批次在装载请求内重复: b2",
                SegregationRules.findRequestDuplication(List.of(p("b1", "A", 1), p("b2", "A", 2),
                        p("b2", "A", 3))));
        assertEquals("分隔位在装载请求内重复: 2",
                SegregationRules.findRequestDuplication(List.of(p("b1", "A", 1), p("b2", "A", 2),
                        p("b3", "B", 2))));
        assertNull(SegregationRules.findRequestDuplication(List.of(p("b1", "A", 1), p("b2", "B", 2))));
    }

    @Test
    void requiredDividers_isDistinctCategoriesMinusOne() {
        assertEquals(0, SegregationRules.requiredDividerCount(List.of(p("b1", "A", 1))));
        assertEquals(0, SegregationRules.requiredDividerCount(
                List.of(p("b1", "A", 1), p("b2", "A", 2), p("b3", "A", 3))));
        assertEquals(1, SegregationRules.requiredDividerCount(
                List.of(p("b1", "A", 1), p("b2", "B", 2))));
        assertEquals(2, SegregationRules.requiredDividerCount(
                List.of(p("b1", "A", 1), p("b2", "B", 2), p("b3", "C", 3))));
        assertEquals(0, SegregationRules.requiredDividerCount(List.of()));
    }

    @Test
    void evaluate_sameCategoryNeedsNoDividerEvenWithZeroDividers() {
        assertNull(SegregationRules.evaluate(0,
                List.of(p("b1", "A", 1), p("b2", "A", 2))));
    }

    @Test
    void evaluate_multiCategoryWithOneDividerAndDistinctSlotsPasses() {
        assertNull(SegregationRules.evaluate(1,
                List.of(p("b1", "A", 1), p("b2", "B", 2))));
    }

    @Test
    void evaluate_missingDividers_reportsActualRequiredAndGap() {
        String reason = SegregationRules.evaluate(0,
                List.of(p("b1", "A", 1), p("b2", "B", 2), p("b3", "C", 3)));
        assertTrue(reason.contains("实际 0"));
        assertTrue(reason.contains("要求 2"));
        assertTrue(reason.contains("差额 -2"));
        // 恰好满足边界：1 块隔板对 2 个类别
        assertNull(SegregationRules.evaluate(1,
                List.of(p("b1", "A", 1), p("b2", "B", 2))));
        // 差一块
        String oneShort = SegregationRules.evaluate(1,
                List.of(p("b1", "A", 1), p("b2", "B", 2), p("b3", "C", 3)));
        assertTrue(oneShort.contains("差额 -1"));
    }

    @Test
    void evaluate_sharedSlot_failsEvenWithEnoughDividers() {
        String reason = SegregationRules.evaluate(9,
                List.of(p("b1", "A", 1), p("b2", "B", 1)));
        assertTrue(reason.contains("分隔位"));
        assertFalse(SegregationRules.slotsDistinct(
                List.of(p("b1", "A", 7), p("b2", "B", 7))));
        assertTrue(SegregationRules.slotsDistinct(
                List.of(p("b1", "A", 7), p("b2", "B", 8))));
    }

    @Test
    void evaluate_extraDividersAreAllowed() {
        assertNull(SegregationRules.evaluate(5, List.of(p("b1", "A", 1), p("b2", "B", 2))));
    }
}
