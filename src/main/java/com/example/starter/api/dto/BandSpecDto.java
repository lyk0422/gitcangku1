package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 高度带配置项（左闭右开，单位米）。
 *
 * @param bandLower 高度带下限（含），米
 * @param bandUpper 高度带上限（不含），米，bandLower &lt; bandUpper
 * @param capacity  同时容量，1~50；修改时只能上调
 */
public record BandSpecDto(
        @NotNull Integer bandLower,
        @NotNull Integer bandUpper,
        @NotNull @Min(1) @Max(50) Integer capacity) {
}
