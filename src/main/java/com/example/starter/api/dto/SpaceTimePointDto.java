package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 时空段采样点：{@code atMillis}（epoch 毫秒，UTC）时刻预计位于 (x, y)（米）。
 *
 * @param atMillis 预计到达该点的 epoch 毫秒（UTC）
 * @param x        X 坐标，单位米
 * @param y        Y 坐标，单位米
 */
public record SpaceTimePointDto(
        @NotNull Long atMillis,
        @NotNull @Min(-100000) @Max(100000) Integer x,
        @NotNull @Min(-100000) @Max(100000) Integer y) {
}
