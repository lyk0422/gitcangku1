package com.example.starter.exposure.pacing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动匀速投放纯逻辑单元测试：UTC 小时归属、预算校验、已开始小时下调裁决、下一可用小时扫描。
 */
class PacingTest {

    private static List<Integer> budgets(int... values) {
        assertEquals(24, values.length, "测试预算必须恰好 24 项");
        List<Integer> list = new ArrayList<>();
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    @Test
    @DisplayName("UTC 小时与日期归属：整点左闭、区间左闭右开")
    void utcHourAndDate_halfOpenIntervals() {
        // 整点时刻属于该小时（左闭）
        assertEquals(0, Pacing.utcHour(Instant.parse("2026-09-22T00:00:00Z")));
        assertEquals(10, Pacing.utcHour(Instant.parse("2026-09-22T10:00:00Z")));
        // 小时结束前一刻仍属本小时（右开）
        assertEquals(10, Pacing.utcHour(Instant.parse("2026-09-22T10:59:59.999Z")));
        // 毫秒精度保留
        assertEquals(10, Pacing.utcHour(Instant.parse("2026-09-22T10:00:00.001Z")));

        assertEquals(LocalDate.of(2026, 9, 22),
                Pacing.utcDate(Instant.parse("2026-09-22T23:59:59.999Z")));
        assertEquals(LocalDate.of(2026, 9, 23),
                Pacing.utcDate(Instant.parse("2026-09-23T00:00:00Z")));
    }

    @Test
    @DisplayName("预算校验：24 项非负整数且合计等于日总额，允许全日为 0")
    void validateBudgets_validIncludingAllZero() {
        Pacing.validateBudgets(0, Collections.nCopies(24, 0));
        List<Integer> uneven = new ArrayList<>(Collections.nCopies(24, 0));
        uneven.set(0, 5);
        uneven.set(23, 7);
        Pacing.validateBudgets(12, uneven);
    }

    @Test
    @DisplayName("预算校验失败分支：负数、项数不为 24、合计不等于日总额")
    void validateBudgets_invalid() {
        assertThrows(IllegalArgumentException.class,
                () -> Pacing.validateBudgets(-1, Collections.nCopies(24, 0)));
        List<Integer> negative = new ArrayList<>(Collections.nCopies(24, 0));
        negative.set(3, -1);
        assertThrows(IllegalArgumentException.class, () -> Pacing.validateBudgets(-1, negative));
        assertThrows(IllegalArgumentException.class,
                () -> Pacing.validateBudgets(10, Collections.nCopies(23, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> Pacing.validateBudgets(10, null));
        List<Integer> sumMismatch = new ArrayList<>(Collections.nCopies(24, 1));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Pacing.validateBudgets(10, sumMismatch));
        // 错误信息明确实际合计与要求值
        assertTrue(ex.getMessage().contains("24") && ex.getMessage().contains("10"));
    }

    @Test
    @DisplayName("已开始小时判定：当前小时及之前不可下调，未来小时可下调")
    void findReducedStartedHour() {
        List<Integer> oldBudgets = Collections.nCopies(24, 10);
        Instant now = Instant.parse("2026-09-22T10:15:00Z");

        // 当前小时下调：违规
        List<Integer> reduceCurrent = new ArrayList<>(oldBudgets);
        reduceCurrent.set(10, 9);
        assertEquals(Optional.of(10), Pacing.findReducedStartedHour(oldBudgets, reduceCurrent, now));

        // 之前小时下调：违规并返回最早的一个
        List<Integer> reducePast = new ArrayList<>(oldBudgets);
        reducePast.set(9, 8);
        reducePast.set(0, 5);
        assertEquals(Optional.of(0), Pacing.findReducedStartedHour(oldBudgets, reducePast, now));

        // 已开始小时持平、未来小时下调：合规
        List<Integer> reduceFuture = new ArrayList<>(oldBudgets);
        reduceFuture.set(11, 0);
        reduceFuture.set(23, 1);
        // 合计可变化，纯逻辑只裁决“已开始不可下调”
        assertTrue(Pacing.findReducedStartedHour(oldBudgets, reduceFuture, now).isEmpty());

        // 已开始小时上调：合规
        List<Integer> increaseStarted = new ArrayList<>(oldBudgets);
        increaseStarted.set(10, 100);
        assertFalse(Pacing.findReducedStartedHour(oldBudgets, increaseStarted, now).isPresent());
    }

    @Test
    @DisplayName("下一可用小时：当日后续优先，其次次日；返回整点时刻")
    void nextAvailableHourStart_sameDayThenNextDay() {
        Instant now = Instant.parse("2026-09-22T10:30:00Z");

        // 当前小时即使有预算也不算（已开始），下一可用为 12 时
        List<Integer> budgets = new ArrayList<>(Collections.nCopies(24, 0));
        budgets.set(10, 100);
        budgets.set(12, 5);
        assertEquals(Instant.parse("2026-09-22T12:00:00Z"),
                Pacing.nextAvailableHourStart(budgets, now).orElseThrow());

        // 当日后续全无预算：扫描次日首个正预算小时
        List<Integer> onlyTomorrow = new ArrayList<>(Collections.nCopies(24, 0));
        onlyTomorrow.set(3, 2);
        assertEquals(Instant.parse("2026-09-23T03:00:00Z"),
                Pacing.nextAvailableHourStart(onlyTomorrow, now).orElseThrow());

        // 23:30 时后续只有次日
        Instant late = Instant.parse("2026-09-22T23:30:00Z");
        List<Integer> nextMorning = new ArrayList<>(Collections.nCopies(24, 0));
        nextMorning.set(0, 1);
        assertEquals(Instant.parse("2026-09-23T00:00:00Z"),
                Pacing.nextAvailableHourStart(nextMorning, late).orElseThrow());
    }

    @Test
    @DisplayName("全日预算为 0：不存在下一可用小时")
    void nextAvailableHourStart_allZeroReturnsEmpty() {
        assertTrue(Pacing.nextAvailableHourStart(
                Collections.nCopies(24, 0), Instant.parse("2026-09-22T10:00:00Z")).isEmpty());
    }
}
