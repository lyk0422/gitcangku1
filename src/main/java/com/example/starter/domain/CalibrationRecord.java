package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准记录行：偏差单位为度，时刻均为 UTC；validUntil 仅在校准通过时有值。
 */
public record CalibrationRecord(
        long id,
        long antennaId,
        Instant calibratedAt,
        BigDecimal azimuthDeviation,
        BigDecimal elevationDeviation,
        boolean passed,
        Instant validUntil,
        CalibrationStatus status,
        Instant voidedAt,
        String voidReason,
        Instant createdAt) {
}