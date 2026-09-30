package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储温度偏差判定规则纯单元测试：30 分钟边界（恰好合法、超出缺测）与限值边界（等于限值不越界）。
 */
class StorageRulesTest {

    private static final Instant T0 = Instant.parse("2026-06-01T10:00:00Z");

    @Test
    void gapExactlyThirtyMinutes_isNotMissing() {
        Instant at30 = Instant.parse("2026-06-01T10:30:00Z");
        assertFalse(StorageRules.isMissingGap(T0, at30), "恰好 30 分钟不算缺测");
        assertEquals(1800L, StorageRules.gapSeconds(T0, at30));
    }

    @Test
    void gapOneSecondOverThirtyMinutes_isMissing() {
        Instant at30plus1 = Instant.parse("2026-06-01T10:30:01Z");
        assertTrue(StorageRules.isMissingGap(T0, at30plus1), "30 分 1 秒必须判为缺测");
    }

    @Test
    void gapThirtyOneMinutes_isMissing() {
        Instant at31 = Instant.parse("2026-06-01T10:31:00Z");
        assertTrue(StorageRules.isMissingGap(T0, at31), "题干场景：10:00 与 10:31 间隔 31 分钟为缺测");
        assertEquals(1860L, StorageRules.gapSeconds(T0, at31));
    }

    @Test
    void gapShorterThanThirtyMinutes_isNotMissing() {
        assertFalse(StorageRules.isMissingGap(T0, Instant.parse("2026-06-01T10:29:59Z")));
        assertFalse(StorageRules.isMissingGap(T0, Instant.parse("2026-06-01T10:00:01Z")));
    }

    @Test
    void missingGapWithoutPreviousReading_isFalse() {
        assertFalse(StorageRules.isMissingGap(null, T0));
        assertNull(StorageRules.gapSeconds(null, T0));
    }

    @Test
    void temperatureExactlyAtLimits_isNotOutOfLimit() {
        BigDecimal upper = new BigDecimal("8.00");
        BigDecimal lower = new BigDecimal("2.00");
        assertFalse(StorageRules.isOutOfLimit(new BigDecimal("8.00"), upper, lower),
                "恰好等于上限 8℃ 不越界");
        assertFalse(StorageRules.isOutOfLimit(new BigDecimal("2.00"), upper, lower),
                "恰好等于下限 2℃ 不越界");
        assertFalse(StorageRules.isOutOfLimit(new BigDecimal("7.00"), upper, lower));
    }

    @Test
    void temperatureJustBeyondLimits_isOutOfLimit() {
        BigDecimal upper = new BigDecimal("8.00");
        BigDecimal lower = new BigDecimal("2.00");
        assertTrue(StorageRules.isOutOfLimit(new BigDecimal("8.01"), upper, lower));
        assertTrue(StorageRules.isOutOfLimit(new BigDecimal("1.99"), upper, lower));
        // 题干读数 7℃、上限 8℃：不越界（该场景偏差来自缺测而非越界）
        assertFalse(StorageRules.isOutOfLimit(new BigDecimal("7.00"), upper, lower));
    }
}
