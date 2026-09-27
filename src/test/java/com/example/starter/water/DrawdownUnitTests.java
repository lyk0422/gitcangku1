package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下泄相关纯函数单元测试：逐小时均分为 2 位小数且总和严格守恒（尾差优先给最早小时）、
 * UTC 整点解析、上限/核销金额解析。不依赖 Spring 与数据库。
 */
class DrawdownUnitTests {

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void spreadEvenlyKeepsExactSumWithTwoDecimals() {
        List<BigDecimal> thirds = WaterService.spreadEvenly(new BigDecimal("10.00"), 3);
        assertEquals(List.of(new BigDecimal("3.34"), new BigDecimal("3.33"), new BigDecimal("3.33")), thirds);
        assertEquals(new BigDecimal("10.00"), sum(thirds));

        List<BigDecimal> singleCent = WaterService.spreadEvenly(new BigDecimal("0.01"), 3);
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.00"), new BigDecimal("0.00")), singleCent);
        assertEquals(new BigDecimal("0.01"), sum(singleCent));

        List<BigDecimal> odd = WaterService.spreadEvenly(new BigDecimal("7.01"), 4);
        assertEquals(List.of(new BigDecimal("1.76"), new BigDecimal("1.75"), new BigDecimal("1.75"),
                new BigDecimal("1.75")), odd);
        assertEquals(new BigDecimal("7.01"), sum(odd));

        List<BigDecimal> exact = WaterService.spreadEvenly(new BigDecimal("4"), 2);
        assertEquals(List.of(new BigDecimal("2.00"), new BigDecimal("2.00")), exact);
    }

    @Test
    void spreadEvenlyRejectsNonPositiveHours() {
        assertThrows(IllegalArgumentException.class,
                () -> WaterService.spreadEvenly(new BigDecimal("1"), 0));
    }

    @Test
    void parseHourStartRequiresUtcHourAlignment() {
        long aligned = WaterService.parseHourStart("hourStartUtc", "2026-10-10T01:00:00Z");
        assertTrue(aligned > 0);
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseHourStart("hourStartUtc", "2026-10-10T01:30:00Z"));
        assertEquals(400, ex.status().value());
        assertThrows(ApiException.class,
                () -> WaterService.parseHourStart("hourStartUtc", "not-a-time"));
    }

    @Test
    void capVolumeAllowsZeroAndTwoDecimalsOnly() {
        assertEquals(new BigDecimal("0"), WaterService.parseCapVolume("0"));
        assertEquals(new BigDecimal("1.20"), WaterService.parseCapVolume("1.20"));
        assertThrows(ApiException.class, () -> WaterService.parseCapVolume("-0.01"));
        assertThrows(ApiException.class, () -> WaterService.parseCapVolume("1.001"));
        assertThrows(ApiException.class, () -> WaterService.parseCapVolume("abc"));
        assertThrows(ApiException.class, () -> WaterService.parseCapVolume(" "));
    }

    @Test
    void writeoffVolumeMustBePositiveWithTwoDecimals() {
        assertEquals(new BigDecimal("0.01"), WaterService.parseWriteoffVolume("volume", "0.01"));
        ApiException zero = assertThrows(ApiException.class,
                () -> WaterService.parseWriteoffVolume("volume", "0"));
        assertEquals(400, zero.status().value());
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseWriteoffVolume("volume", "-1")).status().value());
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseWriteoffVolume("volume", "0.001")).status().value());
    }
}
