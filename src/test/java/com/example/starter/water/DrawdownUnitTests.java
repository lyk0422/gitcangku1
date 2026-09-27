package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 下泄相关纯函数单元测试：UTC 纳秒换算、整点小时解析、非负两位小数上限解析与水量格式化。
 * 不启动 Spring 容器、不访问数据库。
 */
class DrawdownUnitTests {

    @Test
    void nanosRoundTripPreservesUtcInstants() {
        Instant instant = Instant.parse("2026-12-01T01:00:00Z");
        long nanos = WaterService.toNanos(instant);
        assertEquals(instant, WaterService.toInstant(nanos));
        assertEquals("2026-12-01T01:00:00Z", WaterService.toIso(nanos));
        // 带亚秒精度的时刻也保持纳秒精度与 UTC 语义
        Instant precise = Instant.parse("2026-12-01T01:00:00.123456789Z");
        assertEquals(precise, WaterService.toInstant(WaterService.toNanos(precise)));
    }

    @Test
    void parseHourUtcRejectsNonIntegralHoursAndBadInput() {
        long h0 = WaterService.parseHourUtc("h", "2026-12-01T00:00:00Z");
        assertEquals(0L, Math.floorMod(h0, 3_600_000_000_000L));
        assertThrows(ApiException.class,
                () -> WaterService.parseHourUtc("h", "2026-12-01T00:00:30Z"));
        assertThrows(ApiException.class,
                () -> WaterService.parseHourUtc("h", "2026-12-01T00:30:00Z"));
        ApiException blank = assertThrows(ApiException.class, () -> WaterService.parseHourUtc("h", " "));
        assertEquals(400, blank.status().value());
        assertEquals("INVALID_ARGUMENT", blank.code());
    }

    @Test
    void capParsingAllowsZeroAndTwoDecimalsOnly() {
        assertEquals(0, WaterService.parseCap("0").compareTo(new BigDecimal("0")));
        assertEquals(0, WaterService.parseCap("10.00").compareTo(new BigDecimal("10.00")));
        assertEquals(0, WaterService.parseCap("10.50").compareTo(new BigDecimal("10.5")));
        assertThrows(ApiException.class, () -> WaterService.parseCap("-0.01"));
        assertThrows(ApiException.class, () -> WaterService.parseCap("10.001"));
        assertThrows(ApiException.class, () -> WaterService.parseCap("abc"));
        assertThrows(ApiException.class, () -> WaterService.parseCap(null));
    }

    @Test
    void amountParsingRejectsZeroAndMoreThanThreeDecimals() {
        assertEquals(new BigDecimal("0.001"), WaterService.parseAmount("a", "0.001"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("a", "0"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("a", "-1"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("a", "1.0001"));
    }

    @Test
    void fmtStripsTrailingZerosAndKeepsZeroStable() {
        assertEquals("10", WaterService.fmt(new BigDecimal("10.00")));
        assertEquals("0", WaterService.fmt(BigDecimal.ZERO));
        assertEquals("0.5", WaterService.fmt(new BigDecimal("0.500")));
        // null 语义不参与格式化：调用方对缺省字段直接输出 null，不做转换
    }
}
