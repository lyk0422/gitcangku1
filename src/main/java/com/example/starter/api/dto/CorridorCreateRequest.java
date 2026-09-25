package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建走廊请求。走廊声明非退化轴对齐闭矩形与同时容量上限。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       左边界（含），单位米
 * @param yMin       下边界（含），单位米
 * @param xMax       右边界（含），单位米
 * @param yMax       上边界（含），单位米
 * @param capacity   同时容量上限，取值 1～50
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorCreateRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull @Min(1) @Max(50) Integer capacity,
        @NotBlank @Size(max = 64) String requestId) {
}
