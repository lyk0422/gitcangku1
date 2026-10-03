package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预测气象条目（UTC 时段，左闭右开，与标准窗口对齐）。
 *
 * @param regionId       气象区域标识
 * @param windowStartUtc 时段起点（epoch 毫秒，UTC，左闭）
 * @param windowEndUtc   时段终点（epoch 毫秒，UTC，右开）
 * @param visibilityM    预测能见度（米，必须非负）
 * @param cloudBaseM     预测云底（米，必须非负）
 */
public record ForecastEntryDto(
        @NotBlank @Size(max = 64) String regionId,
        @NotNull Long windowStartUtc,
        @NotNull Long windowEndUtc,
        @NotNull Integer visibilityM,
        @NotNull Integer cloudBaseM) {
}