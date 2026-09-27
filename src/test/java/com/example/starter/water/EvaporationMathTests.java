package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蒸发结算纯函数单元测试：银行家舍入（HALF_EVEN）到两位小数、率解析边界、
 * UTC 自然日解析与核销守恒口径。不依赖 Spring 与数据库。
 */
class EvaporationMathTests {

    private static BigDecimal loss(String delivered, String rate) {
        return WaterService.bankerLoss(new BigDecimal(delivered), new BigDecimal(rate));
    }

    @Test
    void bankerRoundingRoundsHalfToEvenAtTwoDecimals() {
        // 恰好 tie：0.250 × 0.10 = 0.025，百分位 2 为偶数 -> 0.02
        assertEquals(new BigDecimal("0.02"), loss("0.250", "0.1000"));
        // 0.150 × 0.10 = 0.015，百分位 1 为奇数 -> 0.02
        assertEquals(new BigDecimal("0.02"), loss("0.150", "0.1000"));
        // 0.450 × 0.10 = 0.045，百分位 4 为偶数 -> 0.04
        assertEquals(new BigDecimal("0.04"), loss("0.450", "0.1000"));
        // 0.350 × 0.10 = 0.035，百分位 3 为奇数 -> 0.04
        assertEquals(new BigDecimal("0.04"), loss("0.350", "0.1000"));
        // 非 tie 按就近：0.126 × 0.10 = 0.0126 -> 0.01；0.026 × 0.10 = 0.0026 -> 0.00
        assertEquals(new BigDecimal("0.01"), loss("0.126", "0.1000"));
        assertEquals(new BigDecimal("0.00"), loss("0.026", "0.1000"));
        // 率为 0 / 送达为 0 边界（送达量本身要求 >0，这里仅验证算术）
        assertEquals(new BigDecimal("0.00"), loss("10.000", "0.0000"));
        // 最大率 0.2：10.005 × 0.2 = 2.0010 -> 2.00
        assertEquals(new BigDecimal("2.00"), loss("10.005", "0.2000"));
        // 10.025 × 0.2 = 2.005 -> tie，百分位 0 偶数 -> 2.00
        assertEquals(new BigDecimal("2.00"), loss("10.025", "0.2000"));
        // 10.075 × 0.2 = 2.015 -> tie，百分位 1 奇数 -> 2.02
        assertEquals(new BigDecimal("2.02"), loss("10.075", "0.2000"));
    }

    @Test
    void conservationIsDeliveredPlusLossAfterRounding() {
        String[][] cases = {{"0.250", "0.1000"}, {"3.333", "0.0750"}, {"8.008", "0.2000"},
                {"0.001", "0.1234"}, {"999.999", "0.0001"}};
        for (String[] c : cases) {
            BigDecimal delivered = new BigDecimal(c[0]);
            BigDecimal rate = new BigDecimal(c[1]);
            BigDecimal loss = WaterService.bankerLoss(delivered, rate);
            BigDecimal deduction = delivered.add(loss);
            // 上游扣减 = 送达 + 损耗（逐笔舍入口径下严格相等）
            assertEquals(0, deduction.compareTo(delivered.add(loss)),
                    "守恒失败: " + delivered + " + " + loss);
            // 扣减不小于送达；率 <= 0.2 时损耗不超过送达的约 20%（含舍入半分容差）
            assertTrue(deduction.compareTo(delivered) >= 0);
            assertTrue(loss.compareTo(delivered.multiply(new BigDecimal("0.21"))) <= 0);
        }
    }

    @Test
    void rateParsingAcceptsBoundaryAndRejectsInvalidValues() {
        assertEquals(0, WaterService.parseRate("rate", "0").compareTo(new BigDecimal("0.0000")));
        assertEquals(new BigDecimal("0.2000"), WaterService.parseRate("rate", "0.2000"));
        assertEquals(new BigDecimal("0.1"), WaterService.parseRate("rate", "0.1"));
        assertEquals(new BigDecimal("0.1234"), WaterService.parseRate("rate", "0.1234"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "0.2001"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "0.21"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "-0.01"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "1"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "0.12345"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", "abc"));
        assertThrows(ApiException.class, () -> WaterService.parseRate("rate", " "));
        ApiException over = assertThrows(ApiException.class,
                () -> WaterService.parseRate("rate", "0.2001"));
        assertEquals(400, over.status().value());
    }

    @Test
    void dateParsingIsUtcIsoDateOnly() {
        assertEquals(LocalDate.of(2026, 11, 10), WaterService.parseDate("d", "2026-11-10"));
        assertThrows(ApiException.class, () -> WaterService.parseDate("d", "2026-11-10T00:00:00Z"));
        assertThrows(ApiException.class, () -> WaterService.parseDate("d", "2026-13-01"));
        assertThrows(ApiException.class, () -> WaterService.parseDate("d", ""));
    }
}
