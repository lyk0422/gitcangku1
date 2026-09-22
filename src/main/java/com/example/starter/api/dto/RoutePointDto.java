package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 航点（二维整数坐标，单位米）。
 *
 * @param x X 轴坐标（米）
 * @param y Y 轴坐标（米）
 */
public record RoutePointDto(
        @NotNull @Min(-100000) @Max(100000) Integer x,
        @NotNull @Min(-100000) @Max(100000) Integer y) {
}
