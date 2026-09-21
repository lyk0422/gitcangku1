package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量响应。computedValue 为未舍入判定值，displayValue 为 HALF_UP 4 位展示值。
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        BigDecimal displayValue,
        boolean passed,
        MeasurementStatus status,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt) {

    public static MeasurementResponse from(Measurement measurement) {
        return new MeasurementResponse(
                measurement.id(),
                measurement.measurementKey(),
                measurement.instrumentId(),
                measurement.measuredAt(),
                measurement.rawReading(),
                measurement.lowerLimit(),
                measurement.upperLimit(),
                measurement.submittedBy(),
                measurement.certificateId(),
                measurement.computedValue(),
                measurement.displayValue(),
                measurement.passed(),
                measurement.status(),
                measurement.releasedBy(),
                measurement.releasedAt(),
                measurement.createdAt());
    }
}
