package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预测气象条目（UTC，左闭右开 [validFrom, validTo)）。
 *
 * @param zoneId          预测区域标识（须已存在）
 * @param validFrom       预测时段起点（含），epoch 毫秒（UTC）
 * @param validTo         预测时段终点（不含），epoch 毫秒（UTC），必须大于起点
 * @param visibilityMeters 预测能见度，单位米，必须 >= 0
 * @param cloudBaseMeters  预测云底，单位米，必须 >= 0
 */
public record ForecastEntryDto(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Long validFrom,
        @NotNull Long validTo,
        @NotNull Integer visibilityMeters,
        @NotNull Integer cloudBaseMeters) {
}
