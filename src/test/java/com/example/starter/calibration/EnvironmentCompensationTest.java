package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CoefficientVersion;
import com.example.starter.calibration.service.EnvironmentCompensation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境补偿纯领域逻辑单元测试：补偿公式、最终值 HALF_UP 四位小数、证书温湿度范围端点。
 */
class EnvironmentCompensationTest {

    private final Instant now = Instant.parse("2026-06-01T00:00:00Z");

    private CoefficientVersion version(String refTemp, String refHum, String tempCoeff, String humCoeff) {
        return new CoefficientVersion(1L, "INS-1", now, null,
                new BigDecimal(refTemp), new BigDecimal(refHum),
                new BigDecimal(tempCoeff), new BigDecimal(humCoeff), now);
    }

    private Certificate certificate(String tempMin, String tempMax, String humMin, String humMax) {
        return new Certificate(1L, "INS-1", now, now.plusSeconds(3600),
                BigDecimal.ONE, BigDecimal.ZERO,
                new BigDecimal(tempMin), new BigDecimal(tempMax),
                new BigDecimal(humMin), new BigDecimal(humMax),
                false, null, now);
    }

    @Test
    void compensationIsTemperatureDeltaTimesCoeffPlusHumidityDeltaTimesCoeff() {
        // (25-20)×0.01 + (55-50)×0.001 = 0.05 + 0.005 = 0.055
        BigDecimal compensation = EnvironmentCompensation.compensation(
                new BigDecimal("25"), new BigDecimal("55"),
                version("20", "50", "0.01", "0.001"));
        assertEquals(0, new BigDecimal("0.055").compareTo(compensation));

        // 负温差/负湿度差同样精确
        BigDecimal below = EnvironmentCompensation.compensation(
                new BigDecimal("19.5"), new BigDecimal("49.2"),
                version("20", "50", "0.01", "0.001"));
        assertEquals(0, new BigDecimal("-0.0058").compareTo(below));
    }

    @Test
    void finalValueIsHalfUpFourDecimals() {
        // 10.00005 第五位为 5 → HALF_UP 进位为 10.0001
        BigDecimal up = EnvironmentCompensation.finalValue(
                new BigDecimal("10"), new BigDecimal("0.00005"));
        assertEquals("10.0001", up.toPlainString());

        // 10.00004 → 舍为 10.0000
        BigDecimal down = EnvironmentCompensation.finalValue(
                new BigDecimal("10"), new BigDecimal("0.00004"));
        assertEquals("10.0000", down.toPlainString());

        // 10.055 → 补齐四位 10.0550
        BigDecimal padded = EnvironmentCompensation.finalValue(
                new BigDecimal("10"), new BigDecimal("0.055"));
        assertEquals("10.0550", padded.toPlainString());
    }

    @Test
    void certificateRangeIncludesAllEndpoints() {
        Certificate cert = certificate("20", "25", "40", "60");
        assertTrue(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("25"), new BigDecimal("60")));
        assertTrue(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("20"), new BigDecimal("40")));
        assertFalse(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("25.1"), new BigDecimal("50")));
        assertFalse(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("19.9"), new BigDecimal("50")));
        assertFalse(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("25"), new BigDecimal("60.1")));
        assertFalse(EnvironmentCompensation.withinCertificateRange(cert,
                new BigDecimal("25"), new BigDecimal("39.9")));
    }

    @Test
    void certificateWithoutRangeNeverWithinRange() {
        Certificate undefined = new Certificate(1L, "INS-1", now, now.plusSeconds(3600),
                BigDecimal.ONE, BigDecimal.ZERO,
                null, null, null, null, false, null, now);
        assertFalse(EnvironmentCompensation.withinCertificateRange(undefined,
                new BigDecimal("22"), new BigDecimal("50")));
    }
}
