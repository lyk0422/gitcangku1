package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.model.EnvRecord;
import com.example.starter.calibration.service.CompensationEngine;

/**
 * 环境补偿数值精度单测：线性公式 C = k0 + kt×温度 + kh×湿度，结果 HALF_UP 到 6 位小数；
 * 补偿后测量值 = 基础值 + 补偿值。
 */
class CompensationEngineTest {

    private CompensationCoefficient coeff(String k0, String kt, String kh) {
        return new CompensationCoefficient(1L, "M-X", 1,
                new BigDecimal(k0), new BigDecimal(kt), new BigDecimal(kh),
                new BigDecimal("-20"), new BigDecimal("60"),
                new BigDecimal("0"), new BigDecimal("100"), Instant.now());
    }

    @Test
    void compensationRoundsHalfUpToSixDecimals() {
        // 0.0000005 + 0.1×0.000015(=0.0000015) = 0.0000020 → HALF_UP 6 位 = 0.000002
        BigDecimal c = CompensationEngine.compensation(
                coeff("0.0000005", "0.1", "0"),
                new EnvRecord("M-X", new BigDecimal("0.000015"), new BigDecimal("50")));
        assertEquals(new BigDecimal("0.000002"), c);

        // 1 + 2×(-0.0000005) + 3×0 = 0.9999990 → 0.999999
        BigDecimal c2 = CompensationEngine.compensation(
                coeff("1", "2", "3"),
                new EnvRecord("M-X", new BigDecimal("-0.0000005"), new BigDecimal("0")));
        assertEquals(new BigDecimal("0.999999"), c2);
    }

    @Test
    void compensatedValueAddsSixDigitCompensationExactly() {
        BigDecimal base = new BigDecimal("3.1234575");
        BigDecimal compensation = new BigDecimal("0.000002");
        assertEquals(new BigDecimal("3.1234595"),
                CompensationEngine.compensated(base, compensation));
    }

    @Test
    void rangeCheckIncludesEndpoints() {
        CompensationCoefficient c = coeff("0", "1", "1");
        EnvRecord edgeLow = new EnvRecord("M-X", new BigDecimal("-20"), new BigDecimal("0"));
        EnvRecord edgeHigh = new EnvRecord("M-X", new BigDecimal("60"), new BigDecimal("100"));
        EnvRecord outside = new EnvRecord("M-X", new BigDecimal("60.0001"), new BigDecimal("50"));
        assertEquals(true, c.covers(edgeLow.temperatureC(), edgeLow.humidityPct()));
        assertEquals(true, c.covers(edgeHigh.temperatureC(), edgeHigh.humidityPct()));
        assertEquals(false, c.covers(outside.temperatureC(), outside.humidityPct()));
    }
}
