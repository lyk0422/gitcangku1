package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.domain.MeterUnit;

/**
 * 计量单位换算精度测试：BigDecimal 精确计算，四舍五入到最近整数分钟。
 */
class MeterUnitTest {

    @Test
    void hoursToMinutes_exactAndRounded() {
        // 精确换算
        assertEquals(150, MeterUnit.HOURS.toMinutes(new BigDecimal("2.5")));
        assertEquals(60, MeterUnit.HOURS.toMinutes(new BigDecimal("1.0")));
        assertEquals(0, MeterUnit.HOURS.toMinutes(BigDecimal.ZERO));
        // 四舍五入到最近整数分钟（HALF_UP）
        assertEquals(101, MeterUnit.HOURS.toMinutes(new BigDecimal("1.68")));   // 100.8 → 101
        assertEquals(100, MeterUnit.HOURS.toMinutes(new BigDecimal("1.66")));   // 99.6 → 100
        assertEquals(101, MeterUnit.HOURS.toMinutes(new BigDecimal("1.675")));  // 100.5 → 101
        assertEquals(1, MeterUnit.HOURS.toMinutes(new BigDecimal("0.01")));     // 0.6 → 1
    }

    @Test
    void minutesToMinutes_roundsToNearestInteger() {
        assertEquals(100, MeterUnit.MINUTES.toMinutes(new BigDecimal("100")));
        assertEquals(100, MeterUnit.MINUTES.toMinutes(new BigDecimal("100.49")));
        assertEquals(101, MeterUnit.MINUTES.toMinutes(new BigDecimal("100.5")));
    }

    @Test
    void fromMinutes_storageScaleKeepsPrecision() {
        assertEquals(new BigDecimal("150"), MeterUnit.MINUTES.fromMinutes(150));
        assertEquals(new BigDecimal("2.5"), MeterUnit.HOURS.fromMinutes(150));
        // 存储保留 6 位小数，避免存储即丢失精度
        assertEquals(new BigDecimal("1.666667"), MeterUnit.HOURS.fromMinutes(100));
    }

    @Test
    void displayFromMinutes_twoDecimalsHalfUp() {
        assertEquals(new BigDecimal("150.00"), MeterUnit.MINUTES.displayFromMinutes(150));
        assertEquals(new BigDecimal("2.50"), MeterUnit.HOURS.displayFromMinutes(150));
        assertEquals(new BigDecimal("1.67"), MeterUnit.HOURS.displayFromMinutes(100));
        assertEquals(new BigDecimal("0.02"), MeterUnit.HOURS.displayFromMinutes(1));
        assertEquals(new BigDecimal("0.00"), MeterUnit.HOURS.displayFromMinutes(0));
    }

    @Test
    void parse_acceptsOnlyKnownTags() {
        assertEquals(MeterUnit.MINUTES, MeterUnit.parse("MINUTES"));
        assertEquals(MeterUnit.HOURS, MeterUnit.parse("HOURS"));
        assertNull(MeterUnit.parse("DAYS"));
        assertNull(MeterUnit.parse("hours"));
        assertNull(MeterUnit.parse(null));
    }

    @Test
    void hasAtMostTwoDecimals() {
        assertTrue(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("2.5")));
        assertTrue(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("2.55")));
        assertTrue(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("2.50")));
        assertTrue(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("3")));
        assertFalse(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("2.555")));
        assertFalse(MeterUnit.hasAtMostTwoDecimals(new BigDecimal("0.001")));
    }
}
