package com.example.starter.water;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蒸发结算纯口径单元测试：UTC 日解析、率校验、银行家舍入与舍入后守恒判定，以及可控时钟。
 * 不涉及数据库；数据库侧的约束、事务与并发在 {@link SettlementApiTests} 用真实 H2 验证。
 */
class EvaporationMathTests {

    @Test
    void bankerRoundingAndConservation() {
        // 常规：送达 2.000、率 10% -> 损耗 0.20、上游扣减 2.20，守恒成立
        WaterService.SettlementMath normal =
                WaterService.computeSettlementMath(new BigDecimal("2.000"), new BigDecimal("0.1000"));
        assertEquals(0, normal.loss().compareTo(new BigDecimal("0.20")));
        assertEquals(0, normal.deduction().compareTo(new BigDecimal("2.20")));
        assertFalse(normal.conservationBroken());

        // 银行家舍入：0.005 保留两位时前一位为偶数 0 -> 舍为 0.00；0.015 -> 入到偶数 0.02
        assertEquals(0, new BigDecimal("0.005").setScale(2, java.math.RoundingMode.HALF_EVEN)
                .compareTo(new BigDecimal("0.00")));
        assertEquals(0, new BigDecimal("0.015").setScale(2, java.math.RoundingMode.HALF_EVEN)
                .compareTo(new BigDecimal("0.02")));

        // 送达 0.125、率 4%：计入送达 round2(0.125)=0.12，损耗精确 0.005 -> 银行家舍入为 0.00，
        // 扣减精确 0.13 -> 0.13；独立舍入后 0.13 != 0.12 + 0.00，守恒不成立（必须 422 拒绝）
        WaterService.SettlementMath broken =
                WaterService.computeSettlementMath(new BigDecimal("0.125"), new BigDecimal("0.0400"));
        assertEquals(0, broken.settledDelivered().compareTo(new BigDecimal("0.12")));
        assertEquals(0, broken.loss().compareTo(new BigDecimal("0.00")));
        assertEquals(0, broken.deduction().compareTo(new BigDecimal("0.13")));
        assertTrue(broken.conservationBroken());

        // 率为 0：无损耗；送达 3.333 -> 计入送达 3.33、扣减 3.33，守恒成立
        WaterService.SettlementMath zeroRate =
                WaterService.computeSettlementMath(new BigDecimal("3.333"), new BigDecimal("0.0000"));
        assertEquals(0, zeroRate.settledDelivered().compareTo(new BigDecimal("3.33")));
        assertEquals(0, zeroRate.loss().compareTo(new BigDecimal("0.00")));
        assertEquals(0, zeroRate.deduction().compareTo(new BigDecimal("3.33")));
        assertFalse(zeroRate.conservationBroken());

        // 率 20% 上界：送达 1 -> 损耗 0.20、扣减 1.20
        WaterService.SettlementMath maxRate =
                WaterService.computeSettlementMath(new BigDecimal("1.000"), new BigDecimal("0.2000"));
        assertEquals(0, maxRate.loss().compareTo(new BigDecimal("0.20")));
        assertEquals(0, maxRate.deduction().compareTo(new BigDecimal("1.20")));
    }

    @Test
    void rateValidation() {
        assertEquals(0, WaterService.parseRate("0").compareTo(new BigDecimal("0.0000")));
        assertEquals(0, WaterService.parseRate("0.1").compareTo(new BigDecimal("0.1000")));
        assertEquals(0, WaterService.parseRate("0.2000").compareTo(new BigDecimal("0.2000")));
        assertThrows(ApiException.class, () -> WaterService.parseRate("0.2001"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("1"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("-0.01"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("abc"));
        assertThrows(ApiException.class, () -> WaterService.parseRate(""));
    }

    @Test
    void utcDayParsing() {
        long dayStart = WaterService.parseDay("dayUtc", "2026-10-16");
        assertEquals("2026-10-16T00:00:00Z", WaterService.toIso(dayStart));
        assertThrows(ApiException.class, () -> WaterService.parseDay("dayUtc", "2026-10-16T00:00:00Z"));
        assertThrows(ApiException.class, () -> WaterService.parseDay("dayUtc", ""));
    }

    @Test
    void fixedClockDrivesServiceNanos() {
        PlatformTransactionManager noop = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return null;
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        Instant fixed = Instant.parse("2026-10-16T08:30:15.123456789Z");
        WaterService service = new WaterService(null, noop, new ObjectMapper(),
                Clock.fixed(fixed, ZoneOffset.UTC));
        assertEquals("2026-10-16T08:30:15.123456789Z", WaterService.toIso(service.nowNanos()));
    }
}
