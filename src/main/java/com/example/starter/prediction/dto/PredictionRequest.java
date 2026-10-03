package com.example.starter.prediction.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建或修订过境预测的请求。
 *
 * @param satelliteId 卫星标识
 * @param windowStartUtc 可见窗口起点，UTC
 * @param windowEndUtc 可见窗口终点，UTC
 * @param minElevationDeg 窗口内最低仰角，单位度
 * @param plannedDataMb 计划数据量，单位 MB
 */
public record PredictionRequest(
        @NotBlank String satelliteId,
        @NotNull Instant windowStartUtc,
        @NotNull Instant windowEndUtc,
        @NotNull @DecimalMin("0.0") BigDecimal minElevationDeg,
        @Positive long plannedDataMb) {
}