package com.example.starter.pass.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 提交/修订卫星过境预测的请求。
 *
 * @param satelliteCode    卫星业务编码
 * @param visibleStart     UTC 可见窗口起点（含）
 * @param visibleEnd       UTC 可见窗口终点（不含）
 * @param minElevationDeg  过境最低仰角，单位：度
 * @param plannedBytes     计划数据量，单位：字节
 */
public record PredictionUpsertRequest(
        @NotBlank String satelliteCode,
        @NotNull Instant visibleStart,
        @NotNull Instant visibleEnd,
        @NotNull @DecimalMin(value = "0.0") BigDecimal minElevationDeg,
        @Positive long plannedBytes
) {
}
