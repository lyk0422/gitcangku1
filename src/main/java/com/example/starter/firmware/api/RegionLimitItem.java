package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 区域上限配置项：该区域同时进行中（已下发未完成）任务数上限，取值1~1000。
 */
public record RegionLimitItem(
        @NotBlank @Size(max = 64) String region,
        @Min(1) @Max(1000) int maxInFlight) {
}
