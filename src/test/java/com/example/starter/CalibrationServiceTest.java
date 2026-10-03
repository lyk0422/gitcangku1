package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.service.CalibrationService;
import com.example.starter.web.dto.CreateCalibrationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * 校准判定规则测试：0.2 度阈值边界、失败不延长有效期。
 */
@SpringBootTest
class CalibrationServiceTest {

    @Autowired
    private CalibrationService calibrationService;

    private CalibrationRecord calibrate(String antenna, String azimuth, String elevation) {
        return calibrationService.create(new CreateCalibrationRequest(
                antenna, Instant.parse("2026-03-01T08:00:00Z"),
                new BigDecimal(azimuth), new BigDecimal(elevation)));
    }

    @Test
    void boundaryDeviationsPass() {
        CalibrationRecord record = calibrate("ANT-CAL-B1", "0.2", "-0.2");
        assertTrue(record.passed());
        assertEquals(Instant.parse("2026-03-02T08:00:00Z"), record.validUntil());
    }

    @Test
    void deviationAboveThresholdFailsAndHasNoValidity() {
        CalibrationRecord record = calibrate("ANT-CAL-B2", "0.2001", "0.0");
        assertFalse(record.passed());
        assertNull(record.validUntil(), "校准失败不延长有效期");
    }

    @Test
    void elevationAboveThresholdFails() {
        CalibrationRecord record = calibrate("ANT-CAL-B3", "0.1", "-0.21");
        assertFalse(record.passed());
        assertNull(record.validUntil());
    }

    @Test
    void unknownCalibrationRaisesNotFound() {
        ApiException ex = assertThrows(ApiException.class, () -> calibrationService.get(999999L));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("CALIBRATION_NOT_FOUND", ex.reason());
    }
}