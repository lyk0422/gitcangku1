package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建立空域应急走廊请求（授权调度员）。
 * 时间窗 [startUtc, endUtc) 与高度窗 [altMin, altMax) 均为 UTC、左闭右开。
 *
 * @param corridorId 走廊唯一标识
 * @param priority   走廊优先级（非负）；同优先级走廊不得时空高度重叠
 * @param xMin       矩形左边界（含），米
 * @param yMin       矩形下边界（含），米
 * @param xMax       矩形右边界（含），米
 * @param yMax       矩形上边界（含），米
 * @param startUtc   生效起点 epoch 毫秒（UTC，含）
 * @param endUtc     生效终点 epoch 毫秒（UTC，不含）
 * @param altMin     最低高度（含），米
 * @param altMax     最高高度（不含），米
 * @param reason     建立走廊与让渡原因（同一文案固化进让渡历史）
 * @param createdBy  授权调度员标识
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorCreateRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(0) Integer priority,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotBlank @Size(max = 255) String reason,
        @NotBlank @Size(max = 64) String createdBy,
        @NotBlank @Size(max = 64) String requestId) {
}
