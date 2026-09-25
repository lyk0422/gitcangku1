package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.CompensationProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境补偿纯函数单测：线性公式、六位小数 HALF_UP 精度、温湿度适用区间含端点。
 */
class CompensationCalcTest {

    private CompensationProfile profile(String kT, String kH, String tMin, String tMax,
                                        String hMin, String hMax) {
        return new CompensationProfile(1L, "MODEL-X", 1,
                new BigDecimal(kT), new BigDecimal(kH),
                new BigDecimal(tMin), new BigDecimal(tMax),
                new BigDecimal(hMin), new BigDecimal(hMax),
                true, Instant.now());
    }

    @Test
    void linearFormulaAddsBothTerms() {
        // raw 10 + 0.01×25(=0.25) + 0.001×50(=0.05) = 10.300000
        CompensationProfile p = profile("0.01", "0.001", "0", "50", "0", "100");
        BigDecimal result = Compensation.compensate(
                new BigDecimal("10"), p, new BigDecimal("25"), new BigDecimal("50"));
        assertEquals(new BigDecimal("10.300000"), result);
    }

    @Test
    void resultRoundsHalfUpToSixDecimals() {
        // kT 0.000001 × 温度 0.5 = 0.0000005，第 7 位为 5 → HALF_UP 进位为 0.000001
        CompensationProfile p = profile("0.000001", "0", "0", "50", "0", "100");
        BigDecimal result = Compensation.compensate(
                new BigDecimal("1"), p, new BigDecimal("0.5"), new BigDecimal("40"));
        assertEquals(new BigDecimal("1.000001"), result);
    }

    @Test
    void resultTruncatesAtSeventhDecimalWhenBelowHalf() {
        // kT 0.123456 × 0.123456 = 0.015241383936；10 + 该值 = 10.015241383936，第 7 位为 3 → 10.015241
        CompensationProfile p = profile("0.123456", "0", "0", "50", "0", "100");
        BigDecimal result = Compensation.compensate(
                new BigDecimal("10"), p, new BigDecimal("0.123456"), new BigDecimal("40"));
        assertEquals(new BigDecimal("10.015241"), result);
    }

    @Test
    void rangeEndpointsAreInclusive() {
        CompensationProfile p = profile("0.01", "0.001", "15", "35", "30", "80");
        assertTrue(Compensation.withinRange(p, new BigDecimal("15"), new BigDecimal("30")));
        assertTrue(Compensation.withinRange(p, new BigDecimal("35"), new BigDecimal("80")));
        assertTrue(Compensation.withinRange(p, new BigDecimal("25"), new BigDecimal("55")));
    }

    @Test
    void outsideRangeDetectedOnEveryBoundary() {
        CompensationProfile p = profile("0.01", "0.001", "15", "35", "30", "80");
        assertFalse(Compensation.withinRange(p, new BigDecimal("14.999999"), new BigDecimal("55")));
        assertFalse(Compensation.withinRange(p, new BigDecimal("35.000001"), new BigDecimal("55")));
        assertFalse(Compensation.withinRange(p, new BigDecimal("25"), new BigDecimal("29.999999")));
        assertFalse(Compensation.withinRange(p, new BigDecimal("25"), new BigDecimal("80.000001")));
    }

    @Test
    void compensatedSpecCheckIncludesEndpoints() {
        BigDecimal lower = new BigDecimal("0");
        BigDecimal upper = new BigDecimal("10.000000");
        assertTrue(Compensation.withinSpec(new BigDecimal("0"), lower, upper));
        assertTrue(Compensation.withinSpec(new BigDecimal("10.000000"), lower, upper));
        assertFalse(Compensation.withinSpec(new BigDecimal("10.000001"), lower, upper));
        assertFalse(Compensation.withinSpec(new BigDecimal("-0.000001"), lower, upper));
    }
}
