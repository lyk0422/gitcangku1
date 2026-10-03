package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条预测气象。时段为 UTC 左闭右开区间；负值在业务校验时返回 422。
 *
 * @param zoneId     预测所属区域标识（须为已存在的区域）
 * @param startUtc   预测时段起点（含），epoch 毫秒（UTC）
 * @param endUtc     预测时段终点（不含），epoch 毫秒（UTC），必须大于起点
 * @param visibilityM 预测能见度（米），必须 >= 0，否则 422
 * @param cloudBaseM  预测云底高度（米），必须 >= 0，否则 422
 */
public record WeatherForecastEntryDto(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotNull Integer visibilityM,
        @NotNull Integer cloudBaseM) {
}