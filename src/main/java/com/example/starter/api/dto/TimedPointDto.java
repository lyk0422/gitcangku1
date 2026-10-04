package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 定时航线航点：平面坐标（单位米，最多 3 位小数）与 UTC 整秒到达时刻。
 *
 * @param x           X 轴坐标（米），范围 [-100000,100000]，最多 3 位小数
 * @param y           Y 轴坐标（米），范围 [-100000,100000]，最多 3 位小数
 * @param arrivalTime 到达时刻（UTC，ISO-8601 格式，必须为整秒）
 */
public record TimedPointDto(
        @NotNull @DecimalMin("-100000") @DecimalMax("100000") BigDecimal x,
        @NotNull @DecimalMin("-100000") @DecimalMax("100000") BigDecimal y,
        @NotNull Instant arrivalTime) {
}