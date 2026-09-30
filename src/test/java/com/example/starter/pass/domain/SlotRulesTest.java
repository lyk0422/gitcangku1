package com.example.starter.pass.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时隙校核规则的纯单元测试：窗口缺口、仰角边界、整段容量与实际字节边界。
 * 时间均为 UTC 半开区间。
 */
class SlotRulesTest {

    private static final Instant T1200 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T1205 = Instant.parse("2026-10-01T12:05:00Z");
    private static final Instant T1210 = Instant.parse("2026-10-01T12:10:00Z");
    private static final Instant T1159 = Instant.parse("2026-10-01T11:59:00Z");
    private static final Instant T1215 = Instant.parse("2026-10-01T12:15:00Z");

    @Test
    void windowFullyContainedPassesIncludingTouchingBoundaries() {
        assertTrue(SlotRules.checkWindow(T1200, T1205, T1200, T1210).isEmpty());
        // 终点恰好相接（半开区间）不算缺口
        assertTrue(SlotRules.checkWindow(T1205, T1210, T1200, T1210).isEmpty());
        // 整段相同也合法
        assertTrue(SlotRules.checkWindow(T1200, T1210, T1200, T1210).isEmpty());
    }

    @Test
    void windowOutsideVisibilityIsGap() {
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1159, T1205, T1200, T1210).orElseThrow());
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1205, T1215, T1200, T1210).orElseThrow());
    }

    @Test
    void emptyOrReversedWindowIsGap() {
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1200, T1200, T1200, T1210).orElseThrow());
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1210, T1200, T1200, T1210).orElseThrow());
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(null, T1210, T1200, T1210).orElseThrow());
    }

    @Test
    void elevationBoundaryEqualIsAccepted() {
        assertTrue(SlotRules.checkElevation(new BigDecimal("10.00"), new BigDecimal("10.00")).isEmpty());
        assertTrue(SlotRules.checkElevation(new BigDecimal("10.01"), new BigDecimal("10.00")).isEmpty());
        assertEquals(RejectReason.ELEVATION_TOO_LOW,
                SlotRules.checkElevation(new BigDecimal("9.99"), new BigDecimal("10.00")).orElseThrow());
    }

    @Test
    void capacityBoundaryForWholeMinutes() {
        // 60000 字节/分钟 × 10 分钟 = 600000，恰好容纳
        assertTrue(SlotRules.capacityFits(600_000L, 60_000L, 600L));
        assertFalse(SlotRules.capacityFits(600_001L, 60_000L, 600L));
    }

    @Test
    void capacityBoundaryForFractionalMinutesUsesExactArithmetic() {
        // 90 秒：60000 × 90 / 60 = 90000，恰好容纳
        assertTrue(SlotRules.capacityFits(90_000L, 60_000L, 90L));
        assertFalse(SlotRules.capacityFits(90_001L, 60_000L, 90L));
        // 61 秒：60000 × 61 / 60 = 61000
        assertTrue(SlotRules.capacityFits(61_000L, 60_000L, 61L));
        assertFalse(SlotRules.capacityFits(61_001L, 60_000L, 61L));
    }

    @Test
    void capacityRejectsNonPositiveInputs() {
        assertFalse(SlotRules.capacityFits(0L, 60_000L, 600L));
        assertFalse(SlotRules.capacityFits(600_000L, 0L, 600L));
        assertFalse(SlotRules.capacityFits(600_000L, 60_000L, 0L));
    }

    @Test
    void validateReturnsFirstViolationInOrder() {
        // 窗口缺口优先于仰角、容量
        Optional<RejectReason> gap = SlotRules.validate(T1159, T1215, T1200, T1210,
                new BigDecimal("5"), new BigDecimal("10"), 1L, 60_000L);
        assertEquals(RejectReason.WINDOW_GAP, gap.orElseThrow());

        // 窗口合法、仰角不足
        Optional<RejectReason> elevation = SlotRules.validate(T1200, T1205, T1200, T1210,
                new BigDecimal("5.00"), new BigDecimal("10.00"), 1L, 60_000L);
        assertEquals(RejectReason.ELEVATION_TOO_LOW, elevation.orElseThrow());

        // 窗口与仰角合法、超容量
        Optional<RejectReason> capacity = SlotRules.validate(T1200, T1205, T1200, T1210,
                new BigDecimal("10.00"), new BigDecimal("10.00"), 1_000_000L, 60_000L);
        assertEquals(RejectReason.CAPACITY_EXCEEDED, capacity.orElseThrow());

        // 全部通过
        assertTrue(SlotRules.validate(T1200, T1210, T1200, T1210,
                new BigDecimal("10.00"), new BigDecimal("10.00"), 600_000L, 60_000L).isEmpty());
    }

    @Test
    void capacityUsesBigIntegerSoLargeValuesDoNotOverflow() {
        // plannedBytes * 60 超过 long 范围时也必须给出正确结论而不是溢出误判
        long hugePlanned = Long.MAX_VALUE;
        assertFalse(SlotRules.capacityFits(hugePlanned, 60_000L, 600L));
        // 极大容量恰好满足：bytesPerMinute * seconds == plannedBytes * 60
        long planned = Long.MAX_VALUE / 60;
        long perMinute = planned;
        long seconds = 60L;
        assertTrue(SlotRules.capacityFits(planned, perMinute, seconds));
    }

    @Test
    void emptyPredictionWindowIsAlsoGap() {
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1200, T1205, T1205, T1200).orElseThrow());
        assertEquals(RejectReason.WINDOW_GAP,
                SlotRules.checkWindow(T1200, T1205, null, T1210).orElseThrow());
    }

    @Test
    void actualBytesMustBeWithinZeroAndPlanned() {
        assertTrue(SlotRules.checkActualBytes(0L, 600_000L).isEmpty());
        assertTrue(SlotRules.checkActualBytes(600_000L, 600_000L).isEmpty());
        assertEquals(RejectReason.ACTUAL_BYTES_INVALID,
                SlotRules.checkActualBytes(600_001L, 600_000L).orElseThrow());
        assertEquals(RejectReason.ACTUAL_BYTES_INVALID,
                SlotRules.checkActualBytes(-1L, 600_000L).orElseThrow());
    }
}
