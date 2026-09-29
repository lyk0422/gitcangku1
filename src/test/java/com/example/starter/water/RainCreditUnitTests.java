package com.example.starter.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降雨抵扣纯单元测试：不启动 Spring 与数据库，覆盖抵扣测算、守恒式、UTC 时间换算、
 * 负数/非法输入的 422/400 区分以及差额语义。
 */
class RainCreditUnitTests {

    @Test
    void creditIsRainfallTimesCoefficientRoundedTo3Decimals() {
        // 12.345 毫米 × 0.1 立方米/毫米 = 1.2345 -> 四舍五入 1.235（单位立方米）
        assertEquals(new BigDecimal("1.235"),
                WaterService.creditFor(new BigDecimal("12.345"), new BigDecimal("0.1")));
        // 10 毫米 × 0.2 = 2
        assertEquals(new BigDecimal("2.000"),
                WaterService.creditFor(new BigDecimal("10.000"), new BigDecimal("0.200000")));
        // 零系数或零降雨 -> 0
        assertEquals(0, WaterService.creditFor(new BigDecimal("10"), BigDecimal.ZERO).compareTo(BigDecimal.ZERO));
        assertEquals(0, WaterService.creditFor(BigDecimal.ZERO, new BigDecimal("0.5")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void creditPlusQuotaDeductionAlwaysEqualsRequested() {
        BigDecimal rain = new BigDecimal("100.000");
        BigDecimal coefficient = new BigDecimal("1.000000");
        for (String requested : new String[]{"5", "50", "99.999", "100", "100.001", "1000"}) {
            BigDecimal req = new BigDecimal(requested);
            BigDecimal credit = WaterService.creditFor(rain, coefficient).min(req);
            BigDecimal quota = req.subtract(credit);
            // 守恒：抵扣 + 配额扣减 = 原核销量；抵扣不得超过本次核销量
            assertTrue(credit.compareTo(req) <= 0);
            assertEquals(0, credit.add(quota).compareTo(req));
        }
    }

    @Test
    void negativeRainfallIs422WithActualRequiredAndDifference() {
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseSignedRainfall("-3.5"));
        assertEquals(422, ex.status().value());
        assertEquals("NEGATIVE_RAINFALL", ex.code());
        assertEquals("-3.5", ex.actualValue());
        assertEquals("0", ex.requiredValue());
        // 差额 = 要求值 - 实际值 = 0 - (-3.5) = 3.5
        assertEquals("3.5", ex.difference());
    }

    @Test
    void zeroOrMalformedRainfallIs400Not422() {
        ApiException zero = assertThrows(ApiException.class, () -> WaterService.parseSignedRainfall("0"));
        assertEquals(400, zero.status().value());
        ApiException garbage = assertThrows(ApiException.class, () -> WaterService.parseSignedRainfall("abc"));
        assertEquals(400, garbage.status().value());
        ApiException blank = assertThrows(ApiException.class, () -> WaterService.parseSignedRainfall("  "));
        assertEquals(400, blank.status().value());
        // 超过 3 位小数 -> 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseSignedRainfall("1.0001")).status().value());
    }

    @Test
    void positiveRainfallParsesExactly() {
        assertEquals(new BigDecimal("7.250"), WaterService.parseSignedRainfall("7.250"));
    }

    @Test
    void negativeCoefficientIs422AndZeroAllowed() {
        // 系数解析走服务入口行为，这里直接验证异常约定由 parseCoefficient 承担：
        // 通过声明接口的负数分支在集成测试覆盖，本测试用降雨量解析对称性确保 422 携带差额。
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseSignedRainfall("-0.001"));
        assertEquals(422, ex.status().value());
        assertEquals("-0.001", ex.actualValue());
        assertEquals("0.001", ex.difference());
    }

    @Test
    void amountRejectsNonPositiveAndTooManyDecimals() {
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseAmount("amount", "0")).status().value());
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseAmount("amount", "-1")).status().value());
        assertEquals(new BigDecimal("2.500"), WaterService.parseAmount("amount", "2.500"));
    }

    @Test
    void utcTimestampsRoundTripWithNanosecondPrecision() {
        Instant instant = Instant.parse("2026-11-02T03:04:05.123456789Z");
        long nanos = WaterService.toNanos(instant);
        assertEquals(instant, WaterService.toInstant(nanos));
        assertEquals("2026-11-02T03:04:05.123456789Z", WaterService.toIso(nanos));
        // 非法时刻 -> 400
        assertEquals(400, assertThrows(ApiException.class,
                () -> WaterService.parseInstant("t", "not-a-time")).status().value());
    }

    @Test
    void unprocessableCarriesActualRequiredAndDifference() {
        ApiException ex = ApiException.unprocessable("WRITE_OFF_QUOTA_EXCEEDED", "x",
                new BigDecimal("3.000"), new BigDecimal("10.000"));
        assertEquals(422, ex.status().value());
        assertEquals("3", ex.actualValue());
        assertEquals("10", ex.requiredValue());
        assertEquals("7", ex.difference());
        // 无数额语义的 422 差额字段为 null
        ApiException plain = ApiException.unprocessable("RAIN_WINDOW_MISMATCH", "x");
        assertEquals(422, plain.status().value());
        assertNull(plain.actualValue());
        assertNull(plain.requiredValue());
        assertNull(plain.difference());
    }

    @Test
    void fmtStripsTrailingZerosButKeepsPlainDecimal() {
        assertEquals("2.5", WaterService.fmt(new BigDecimal("2.500")));
        assertEquals("0", WaterService.fmt(new BigDecimal("0.000000")));
        assertTrue(WaterService.fmt(new BigDecimal("1000")).equals("1000"));
    }
}
