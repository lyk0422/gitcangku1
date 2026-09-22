package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 航线点：二维平面整数坐标，单位米，闭区间 [-100000, 100000]。仅用于本题模拟。
 */
public record PointDto(
        @NotNull @Min(-100000) @Max(100000) Integer x,
        @NotNull @Min(-100000) @Max(100000) Integer y) {
}
