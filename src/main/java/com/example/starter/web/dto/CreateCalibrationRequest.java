package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 校准登记请求：天线编号、UTC 校准时刻、方位/俯仰偏差（度）。
 */
public record CreateCalibrationRequest(
        @NotBlank String antennaCode,
        @NotNull Instant calibratedAt,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal azimuthDeviation,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal elevationDeviation) {
}