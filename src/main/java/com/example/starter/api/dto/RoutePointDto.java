package com.example.starter.api.dto;

import com.example.starter.domain.Point;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 航点（二维平面坐标，单位米，最多 3 位小数）。
 *
 * @param x X 轴坐标（米），范围 [-100000,100000]
 * @param y Y 轴坐标（米），范围 [-100000,100000]
 */
public record RoutePointDto(
        @NotNull @DecimalMin("-100000") @DecimalMax("100000")
        @Digits(integer = 6, fraction = 3) BigDecimal x,
        @NotNull @DecimalMin("-100000") @DecimalMax("100000")
        @Digits(integer = 6, fraction = 3) BigDecimal y) {

    public RoutePointDto {
        x = Point.normalize(x);
        y = Point.normalize(y);
    }

    /** 整数坐标便捷构造（单位米）。 */
    public RoutePointDto(int x, int y) {
        this(BigDecimal.valueOf(x), BigDecimal.valueOf(y));
    }
}