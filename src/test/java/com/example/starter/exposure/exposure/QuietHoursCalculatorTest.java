package com.example.starter.exposure.exposure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静默时段纯时间计算单元测试：跨零点区间、负偏移 floor 换算、边界分钟与静默结束时刻。
 * 不启动 Spring、不依赖数据库与真实等待。
 */
class QuietHoursCalculatorTest {

    static final long T_2026_09_22_10_00_UTC = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli();

    @Nested
    @DisplayName("localMinute：UTC 时刻按偏移换算本地分钟")
    class LocalMinute {

        @Test
        @DisplayName("零偏移：10:00 UTC -> 本地 600 分钟")
        void zeroOffset() {
            assertEquals(600, QuietHoursCalculator.localMinute(T_2026_09_22_10_00_UTC, 0));
        }

        @Test
        @DisplayName("正偏移 +480（UTC+8）：10:00 UTC -> 本地 18:00 = 1080")
        void positiveOffset() {
            assertEquals(1080, QuietHoursCalculator.localMinute(T_2026_09_22_10_00_UTC, 480));
        }

        @Test
        @DisplayName("负偏移 -720（UTC-12）：10:00 UTC -> 前一日本地 22:00 = 1320")
        void negativeOffsetUsesFloor() {
            assertEquals(1320, QuietHoursCalculator.localMinute(T_2026_09_22_10_00_UTC, -720));
        }

        @Test
        @DisplayName("最大正偏移 +840（UTC+14）：10:00 UTC -> 次日本地 00:00 = 0")
        void maxPositiveOffsetWrapsToMidnight() {
            assertEquals(0, QuietHoursCalculator.localMinute(T_2026_09_22_10_00_UTC, 840));
        }

        @Test
        @DisplayName("分钟级偏移 +90：10:00 UTC -> 本地 11:30 = 690")
        void nonHourOffset() {
            assertEquals(690, QuietHoursCalculator.localMinute(T_2026_09_22_10_00_UTC, 90));
        }
    }

    @Nested
    @DisplayName("isWithinQuietHours：区间含起排他止")
    class WithinQuietHours {

        @Test
        @DisplayName("同日区间 08:00-18:00：含起点、含结束前一分钟、不含结束点")
        void sameDayWindowBoundaries() {
            assertTrue(QuietHoursCalculator.isWithinQuietHours(480, 480, 1080));
            assertTrue(QuietHoursCalculator.isWithinQuietHours(1079, 480, 1080));
            assertFalse(QuietHoursCalculator.isWithinQuietHours(1080, 480, 1080));
            assertFalse(QuietHoursCalculator.isWithinQuietHours(479, 480, 1080));
            assertFalse(QuietHoursCalculator.isWithinQuietHours(1200, 480, 1080));
        }

        @Test
        @DisplayName("跨零点 22:00-06:00：22:00 至午夜、午夜后至 06:00 前在内，06:00 起在外")
        void crossMidnightWindow() {
            assertTrue(QuietHoursCalculator.isWithinQuietHours(1320, 1320, 360));
            assertTrue(QuietHoursCalculator.isWithinQuietHours(1439, 1320, 360));
            assertTrue(QuietHoursCalculator.isWithinQuietHours(0, 1320, 360));
            assertTrue(QuietHoursCalculator.isWithinQuietHours(359, 1320, 360));
            assertFalse(QuietHoursCalculator.isWithinQuietHours(360, 1320, 360));
            assertFalse(QuietHoursCalculator.isWithinQuietHours(1200, 1320, 360));
        }
    }

    @Nested
    @DisplayName("nextQuietEndUtcMillis：计算静默结束 UTC 时刻")
    class NextQuietEnd {

        @Test
        @DisplayName("零偏移跨零点：23:00 UTC 落在 22:00-06:00，结束为次日 06:00 UTC")
        void crossMidnightZeroOffset() {
            long now = Instant.parse("2026-09-22T23:00:00Z").toEpochMilli();
            long end = QuietHoursCalculator.nextQuietEndUtcMillis(now, 0, 1320, 360);
            assertEquals(Instant.parse("2026-09-23T06:00:00Z").toEpochMilli(), end);
        }

        @Test
        @DisplayName("零偏移同日区间：10:00 UTC 落在 09:00-17:00，结束为当日 17:00 UTC")
        void sameDayZeroOffset() {
            long end = QuietHoursCalculator.nextQuietEndUtcMillis(
                    T_2026_09_22_10_00_UTC, 0, 540, 1020);
            assertEquals(Instant.parse("2026-09-22T17:00:00Z").toEpochMilli(), end);
        }

        @Test
        @DisplayName("UTC+8 跨零点：UTC 15:00（本地 23:00）落在 22:00-06:00，结束换算回 UTC 22:00")
        void crossMidnightWithPositiveOffset() {
            long now = Instant.parse("2026-09-22T15:00:00Z").toEpochMilli();
            long end = QuietHoursCalculator.nextQuietEndUtcMillis(now, 480, 1320, 360);
            assertEquals(Instant.parse("2026-09-22T22:00:00Z").toEpochMilli(), end);
        }

        @Test
        @DisplayName("UTC-5 同日区间：UTC 14:00（本地 09:00）落在 09:00-17:00，结束为 UTC 22:00")
        void sameDayWithNegativeOffset() {
            long now = Instant.parse("2026-09-22T14:00:00Z").toEpochMilli();
            long end = QuietHoursCalculator.nextQuietEndUtcMillis(now, -300, 540, 1020);
            assertEquals(Instant.parse("2026-09-22T22:00:00Z").toEpochMilli(), end);
        }

        @Test
        @DisplayName("结束时刻严格晚于当前时刻")
        void endIsAlwaysInFuture() {
            long end = QuietHoursCalculator.nextQuietEndUtcMillis(
                    T_2026_09_22_10_00_UTC, 0, 540, 1020);
            assertTrue(end > T_2026_09_22_10_00_UTC);
        }
    }
}
