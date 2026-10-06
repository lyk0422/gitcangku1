package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入渗核算引擎纯函数单测：恢复封顶、同小时字典序、缺口定位、整小时向下取整。
 * 时间全部由入参给定，不依赖系统时钟。
 */
class InfiltrationEngineTests {

    private static long t(String iso) {
        return WaterService.toNanos(Instant.parse(iso));
    }

    private static InfiltrationEngine.Event ev(String key, String iso, String deduct) {
        return new InfiltrationEngine.Event(key, t(iso), new BigDecimal(deduct));
    }

    private static void assertBalance(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "余额应为 " + expected + "，实际 " + actual);
    }

    @Test
    void recoveryIsCappedAtCapacity() {
        // 间隔 3 小时可恢复 6，但余额封顶 C=10
        InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(10, 2,
                t("2026-10-20T09:00:00Z"), List.of(ev("a", "2026-10-20T12:00:00Z", "8")));
        assertNull(outcome.deficit());
        assertBalance("2", outcome.steps().get(0).balanceAfter());
        assertBalance("2", outcome.finalBalance(10));
    }

    @Test
    void sameHourDeductsInAllocationKeyOrder() {
        InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(10, 2,
                t("2026-10-20T09:00:00Z"),
                List.of(ev("b", "2026-10-20T10:00:00Z", "6"), ev("a", "2026-10-20T10:00:00Z", "5")));
        // 同小时按申请键字典序：先 a(5) 后 b(6)，同小时无恢复，总量 11 > 10 → b 处缺口
        assertEquals("a", outcome.steps().get(0).allocationKey());
        assertBalance("5", outcome.steps().get(0).balanceAfter());
        assertEquals("b", outcome.steps().get(1).allocationKey());
        assertNotNull(outcome.deficit());
        assertEquals("b", outcome.deficit().allocationKey());
        assertBalance("-1", outcome.deficit().balanceAfter());
    }

    @Test
    void syntheticScenarioDeficitAtLaterHour() {
        // 题干合成：C=10、R=2，10 点 8 与 1，11 点 4 → 11 点扣前余额 3，缺口 1
        InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(10, 2,
                t("2026-10-20T09:00:00Z"),
                List.of(ev("a1", "2026-10-20T10:00:00Z", "8"),
                        ev("a2", "2026-10-20T11:00:00Z", "4"),
                        ev("a3", "2026-10-20T10:00:00Z", "1")));
        assertNotNull(outcome.deficit());
        assertEquals("a2", outcome.deficit().allocationKey());
        assertBalance("-1", outcome.deficit().balanceAfter());
        assertEquals(3, outcome.steps().size());
        // 10 点两字事件按字典序：a1(8) 后 a3(1)，余额 2 → 1
        assertBalance("2", outcome.steps().get(0).balanceAfter());
        assertBalance("1", outcome.steps().get(1).balanceAfter());
    }

    @Test
    void partialHourRecoveryIsFloored() {
        // 启用 09:30，10:00 事件间隔不足 1 小时 → 不恢复；10:00→11:00 恢复 2
        InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(10, 2,
                t("2026-10-20T09:30:00Z"),
                List.of(ev("a", "2026-10-20T10:00:00Z", "9"), ev("b", "2026-10-20T11:00:00Z", "3")));
        assertNull(outcome.deficit());
        assertBalance("1", outcome.steps().get(0).balanceAfter());
        assertBalance("0", outcome.steps().get(1).balanceAfter());
    }

    @Test
    void emptyEventsKeepFullCapacity() {
        InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(10, 2,
                t("2026-10-20T09:00:00Z"), List.of());
        assertNull(outcome.deficit());
        assertTrue(outcome.steps().isEmpty());
        assertBalance("10", outcome.finalBalance(10));
    }
}
