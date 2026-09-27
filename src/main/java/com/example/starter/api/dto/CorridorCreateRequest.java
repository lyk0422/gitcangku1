package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建立应急走廊请求。时间窗与高度范围均为左闭右开，时间为 epoch 毫秒（UTC）。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       覆盖矩形左边界（含），米
 * @param yMin       覆盖矩形下边界（含），米
 * @param xMax       覆盖矩形右边界（含），米
 * @param yMax       覆盖矩形上边界（含），米
 * @param startMs    有效期起点（含），epoch 毫秒 UTC
 * @param endMs      有效期终点（不含），epoch 毫秒 UTC，必须大于 startMs
 * @param altMin     高度下界（含），米
 * @param altMax     高度上界（不含），米，必须大于 altMin
 * @param priority   走廊优先级，整数越大优先级越高；同优先级时空重叠被拒绝
 * @param reason     建立原因（同时作为让渡原因固化），不可为空
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorCreateRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotNull Integer priority,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 64) String requestId) {
}
