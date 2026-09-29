package com.example.starter.exposure.exposure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PacingRules} 纯规则单元测试：24 小时预算校验、已开始小时判定、下一可用小时。
 */
class PacingRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    private int[] budgets(int... values) {
        int[] hours = new int[PacingRules.HOURS_PER_DAY];
        System.arraycopy(values, 0, hours, 0, values.length);
        return hours;
    }

    @Test
    @DisplayName("预算合法：非负且合计等于日总额，包括全天为 0")
    void validateBudgets_valid() {
        assertNull(PacingRules.validateBudgets(0, budgets()));
        assertNull(PacingRules.validateBudgets(24, budgets(
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)));
        assertNull(PacingRules.validateBudgets(100, budgets(100)));
    }

    @Test
    @DisplayName("预算非法：合计不等于日总额时返回含实际值/要求值/差额的原因")
    void validateBudgets_sumMismatch_reportsActualRequiredDifference() {
        String reason = PacingRules.validateBudgets(10, budgets(4, 3));
        assertTrue(reason.contains("actual=7"), reason);
        assertTrue(reason.contains("required=10"), reason);
        assertTrue(reason.contains("difference=3"), reason);
    }

    @Test
    @DisplayName("预算非法：负数小时预算被拒绝")
    void validateBudgets_negativeRejected() {
        int[] hours = budgets();
        hours[5] = -1;
        String reason = PacingRules.validateBudgets(-1, hours);
        assertTrue(reason.contains("hour=5") && reason.contains("non-negative"), reason);
    }

    @Test
    @DisplayName("预算非法：长度不为 24 被拒绝")
    void validateBudgets_wrongLengthRejected() {
        assertTrue(PacingRules.validateBudgets(0, new int[23]).contains("exactly 24"));
        assertTrue(PacingRules.validateBudgets(0, null).contains("exactly 24"));
    }

    @Test
    @DisplayName("已开始小时判定：小时起点 <= 当前时刻（含边界，UTC）")
    void isStartedHour_boundarySemantics() {
        long hour10Start = PacingRules.hourStartMillis(DAY, 10);
        // 恰好小时起点：已开始
        assertTrue(PacingRules.isStartedHour(DAY, 10, hour10Start));
        // 小时中：已开始
        assertTrue(PacingRules.isStartedHour(DAY, 10, hour10Start + 3_599_999L));
        // 前一毫秒：未开始
        assertFalse(PacingRules.isStartedHour(DAY, 10, hour10Start - 1));
        // 历史日整天已开始；未来日的小时未开始
        assertTrue(PacingRules.isStartedHour(DAY.minusDays(1), 23, hour10Start));
        assertFalse(PacingRules.isStartedHour(DAY.plusDays(1), 0, hour10Start));
    }

    @Test
    @DisplayName("下一可用小时：跳过零预算与已满小时，返回严格晚于当前小时的最早小时")
    void nextAvailableHour_skipsZeroAndFullHours() {
        int[] hours = budgets(0, 5, 0, 3); // h0=0 h1=5 h2=0 h3=3
        // h1 已满（used=5），h3 有余量
        IntUnaryOperator used = h -> h == 1 ? 5 : 0;
        assertEquals(3, PacingRules.nextAvailableHour(hours, used, 0));
        // 当前为 h3 之后当日无可用
        assertEquals(-1, PacingRules.nextAvailableHour(hours, used, 3));
        // 零预算日：无可用
        assertEquals(-1, PacingRules.nextAvailableHour(budgets(), h -> 0, 0));
    }

    @Test
    @DisplayName("小时起点：左闭右开，h+1 起点等于 h 起点 + 3600000 毫秒")
    void hourStartMillis_adjacentHoursOneHourApart() {
        long h0 = PacingRules.hourStartMillis(DAY, 0);
        long h23 = PacingRules.hourStartMillis(DAY, 23);
        long nextDay = PacingRules.hourStartMillis(DAY.plusDays(1), 0);
        assertEquals(3_600_000L, PacingRules.hourStartMillis(DAY, 1) - h0);
        assertEquals(3_600_000L, nextDay - h23);
    }
}
