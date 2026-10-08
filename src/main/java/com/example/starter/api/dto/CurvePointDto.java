package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 曲线控制点/端点（二维整数坐标，单位米，范围 [-100000,100000]）。
 *
 * @param x X 轴坐标（米）
 * @param y Y 轴坐标（米）
 */
public record CurvePointDto(
        @NotNull @Min(-100000) @Max(100000) Integer x,
        @NotNull @Min(-100000) @Max(100000) Integer y) {
}
