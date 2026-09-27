package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建应急走廊请求。区域为非退化轴对齐闭矩形（边界接触算相交），
 * 时段与高度范围均为左闭右开区间，时间统一为 UTC epoch 毫秒。
 *
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 * @param corridorId 走廊唯一标识
 * @param xMin       区域左边界（含），单位米
 * @param yMin       区域下边界（含），单位米
 * @param xMax       区域右边界（含），单位米
 * @param yMax       区域上边界（含），单位米
 * @param startUtc   生效起点（含），epoch 毫秒（UTC）
 * @param endUtc     生效终点（不含），epoch 毫秒（UTC）
 * @param altMin     高度下限（含），单位米
 * @param altMax     高度上限（不含），单位米
 * @param priority   走廊优先级；同优先级 ACTIVE 走廊时空不得重叠
 * @param operator   授权调度员标识
 */
public record CorridorCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull @Min(0) Long startUtc,
        @NotNull @Min(0) Long endUtc,
        @NotNull @Min(0) @Max(100000) Integer altMin,
        @NotNull @Min(0) @Max(100000) Integer altMax,
        @NotNull @Min(0) @Max(9) Integer priority,
        @NotBlank @Size(max = 64) String operator) {
}
