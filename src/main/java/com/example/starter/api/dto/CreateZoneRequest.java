package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建禁飞区请求：zoneId 唯一，矩形为非退化轴对齐闭矩形（minX&lt;maxX 且 minY&lt;maxY）。
 */
public record CreateZoneRequest(
        @NotBlank String requestId,
        @NotBlank String zoneId,
        @NotNull @Min(-100000) @Max(100000) Integer minX,
        @NotNull @Min(-100000) @Max(100000) Integer minY,
        @NotNull @Min(-100000) @Max(100000) Integer maxX,
        @NotNull @Min(-100000) @Max(100000) Integer maxY) {
}
