package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 失效闭包中受影响的测量校准记录。
 *
 * @param measurementKey 测量键
 * @param standardId     记录绑定的标准器版本业务键
 * @param measuredAt     测量时刻（UTC）
 * @param status         记录状态：PENDING / RELEASED / BLOCKED / REVIEW_REQUIRED
 */
public record AffectedMeasurementDto(
        String measurementKey,
        String standardId,
        Instant measuredAt,
        String status) {
}
