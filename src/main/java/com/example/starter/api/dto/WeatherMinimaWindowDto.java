package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 区域气象最低标准时段（UTC，左闭右开 [effectiveFrom, effectiveTo)）。
 *
 * @param effectiveFrom      时段起点（含），epoch 毫秒（UTC）
 * @param effectiveTo        时段终点（不含），epoch 毫秒（UTC），必须大于起点
 * @param minVisibilityMeters 能见度最低标准，单位米，必须 >= 0
 * @param minCloudBaseMeters  云底最低标准，单位米，必须 >= 0
 */
public record WeatherMinimaWindowDto(
        @NotNull Long effectiveFrom,
        @NotNull Long effectiveTo,
        @NotNull Integer minVisibilityMeters,
        @NotNull Integer minCloudBaseMeters) {
}
