package com.example.starter.calibration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建校准记录请求。
 *
 * @param antennaCode 天线编号
 * @param calibratedAt 校准时刻，UTC（ISO-8601，如 2026-10-05T08:00:00Z）
 * @param azimuthDeviationDeg 方位偏差，单位度
 * @param elevationDeviationDeg 俯仰偏差，单位度
 */
public record CreateCalibrationRequest(
        @NotBlank String antennaCode,
        @NotNull Instant calibratedAt,
        @NotNull BigDecimal azimuthDeviationDeg,
        @NotNull BigDecimal elevationDeviationDeg) {
}