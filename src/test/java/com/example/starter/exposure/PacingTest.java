package com.example.starter.exposure.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Pacing} 纯单元测试：预算校验、下一可用小时与 UTC 时间换算，不依赖 Spring/数据库。
 */
class PacingTest {

    private List<Integer> budgets(int... values) {
        return Arrays.stream(values).boxed().toList();
    }

    @Test
    @DisplayName("24 个非负预算且合计等于日总额：校验通过并返回实际合计")
    void validateBudgets_validSum() {
        List<Integer> budgets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            budgets.add(h == 10 ? 240 : 0);
        }
        assertEquals(240, Pacing.validateBudgets(budgets, 240));
    }

    @Test
    @DisplayName("预算合计不等于日总额：异常消息给出实际值、要求值与差额")
    void validateBudgets_sumMismatch_reportsActualRequiredDifference() {
        List<Integer> budgets = new ArrayList<>(Pacing.zeroBudgets());
        budgets.set(0, 10);
        budgets.set(1, 5);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Pacing.validateBudgets(budgets, 20));
        assertTrue(ex.getMessage().contains("15"));
        assertTrue(ex.getMessage().contains("20"));
        assertTrue(ex.getMessage().contains("-5"));
    }

    @Test
    @DisplayName("负预算或长度不为 24：拒绝")
    void validateBudgets_negativeOrWrongLength() {
        List<Integer> negative = new ArrayList<>(Pacing.zeroBudgets());
        negative.set(3, -1);
        assertThrows(IllegalArgumentException.class, () -> Pacing.validateBudgets(negative, -1 + 0));
        List<Integer> tooShort = List.of(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> Pacing.validateBudgets(tooShort, 6));
        assertThrows(IllegalArgumentException.class, () -> Pacing.validateBudgets(null, 0));
    }

    @Test
    @DisplayName("下一可用小时：从给定小时（含）起找第一个正预算，全天为零返回 -1")
    void nextPositiveHour() {
        int[] budget = new int[24];
        budget[12] = 3;
        assertEquals(12, Pacing.nextPositiveHour(budget, 0));
        assertEquals(12, Pacing.nextPositiveHour(budget, 12));
        assertEquals(-1, Pacing.nextPositiveHour(budget, 13));
        assertEquals(-1, Pacing.nextPositiveHour(new int[24], 0));
    }

    @Test
    @DisplayName("UTC 换算：小时起点毫秒、UTC 日期与小时序号，左闭右开边界正确")
    void utcConversions() {
        LocalDate day = LocalDate.of(2026, 9, 22);
        assertEquals(java.time.Instant.parse("2026-09-22T10:00:00Z").toEpochMilli(),
                Pacing.hourStartEpochMillis(day, 10));
        long atTen = java.time.Instant.parse("2026-09-22T10:59:59.999Z").toEpochMilli();
        assertEquals(day, Pacing.utcDateOf(atTen));
        assertEquals(10, Pacing.utcHourOf(atTen));
        long nextHour = java.time.Instant.parse("2026-09-22T11:00:00Z").toEpochMilli();
        assertEquals(11, Pacing.utcHourOf(nextHour));
    }
}
