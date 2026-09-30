package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排班/中断纯逻辑单元测试：UTC 左闭右开交叉判定、顺延分钟数与中断原因校验、纳秒时间戳往返。
 * 不依赖 Spring 与数据库。
 */
class OutageScheduleUnitTests {

    @Test
    void overlapsUsesHalfOpenUtcIntervals() {
        // 典型交叉
        assertTrue(WaterService.overlaps(10, 20, 15, 25));
        assertTrue(WaterService.overlaps(10, 20, 10, 20));
        assertTrue(WaterService.overlaps(10, 20, 5, 15));
        // 包含
        assertTrue(WaterService.overlaps(10, 20, 12, 18));
        // 相邻端点不算重叠：[10,20) 与 [20,30)
        assertFalse(WaterService.overlaps(10, 20, 20, 30));
        assertFalse(WaterService.overlaps(20, 30, 10, 20));
        // 完全分离
        assertFalse(WaterService.overlaps(10, 20, 30, 40));
        // 题干场景：中断 [10:00,11:00) 与 10:30 排班 [10:30,10:45) 交叉，与 11:00 开始的排班不交叉
        long t10 = WaterService.toNanos(Instant.parse("2026-10-01T10:00:00Z"));
        long t11 = WaterService.toNanos(Instant.parse("2026-10-01T11:00:00Z"));
        long t1030 = WaterService.toNanos(Instant.parse("2026-10-01T10:30:00Z"));
        long t1045 = WaterService.toNanos(Instant.parse("2026-10-01T10:45:00Z"));
        assertTrue(WaterService.overlaps(t10, t11, t1030, t1045));
        assertFalse(WaterService.overlaps(t10, t11, t11,
                WaterService.toNanos(Instant.parse("2026-10-01T11:30:00Z"))));
        // 顺延后 [11:30,11:45) 不再与中断交叉
        long t1130 = WaterService.toNanos(Instant.parse("2026-10-01T11:30:00Z"));
        long t1145 = WaterService.toNanos(Instant.parse("2026-10-01T11:45:00Z"));
        assertFalse(WaterService.overlaps(t10, t11, t1130, t1145));
    }

    @Test
    void postponeMinutesMustBePositiveIntInRange() {
        assertEquals(1, WaterService.parsePostponeMinutes(1));
        assertEquals(60, WaterService.parsePostponeMinutes(60));
        assertEquals(24 * 60 * 365, WaterService.parsePostponeMinutes(24 * 60 * 365));
        for (Integer bad : new Integer[]{null, 0, -1, -60, 24 * 60 * 365 + 1}) {
            ApiException ex = assertThrows(ApiException.class, () -> WaterService.parsePostponeMinutes(bad));
            assertEquals(400, ex.status().value());
            assertEquals("INVALID_ARGUMENT", ex.code());
        }
    }

    @Test
    void reasonIsRequiredTrimmedAndBounded() throws Exception {
        // 无 Spring 容器时这些静态校验不依赖实例；通过最小构造走实例方法
        WaterService service = new WaterService(null, null, null, Clock.systemUTC());
        assertEquals("灌渠C施工中断", service.normalizeReason("  灌渠C施工中断  "));
        assertEquals("canal outage #3", service.normalizeReason("canal outage #3"));
        for (String bad : new String[]{null, "", "   "}) {
            ApiException ex = assertThrows(ApiException.class, () -> service.normalizeReason(bad));
            assertEquals(400, ex.status().value());
        }
        ApiException tooLong = assertThrows(ApiException.class,
                () -> service.normalizeReason("x".repeat(513)));
        assertEquals(400, tooLong.status().value());
    }

    @Test
    void nanosRoundTripPreservesInstant() {
        Instant instant = Instant.parse("2026-10-01T10:30:45.123456789Z");
        long nanos = WaterService.toNanos(instant);
        assertEquals(instant, WaterService.toInstant(nanos));
        assertEquals("2026-10-01T10:30:45.123456789Z", WaterService.toIso(nanos));
        assertEquals(nanos, WaterService.parseInstant("t", "2026-10-01T10:30:45.123456789Z"));
    }

    @Test
    void parseInstantRejectsBadValue() {
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseInstant("startUtc", "not-a-time"));
        assertEquals(400, ex.status().value());
    }

    @Test
    void parseAmountKeepsThreeDecimalsMax() {
        assertEquals(new java.math.BigDecimal("0.250"), WaterService.parseAmount("v", "0.250"));
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseAmount("v", "1.0001"));
        assertEquals(400, ex.status().value());
    }
}
