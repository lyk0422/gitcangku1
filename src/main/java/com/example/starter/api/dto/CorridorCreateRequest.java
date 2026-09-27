package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建应急走廊预留请求。区域为非退化轴对齐闭矩形（单位米）；
 * 时段与高度范围均为左闭右开；时间统一使用 UTC epoch 毫秒。
 *
 * @param corridorId    走廊唯一标识
 * @param xMin          区域左边界（含），单位米
 * @param yMin          区域下边界（含），单位米
 * @param xMax          区域右边界（含），单位米
 * @param yMax          区域上边界（含），单位米
 * @param startAt       生效起点（含），epoch 毫秒（UTC）
 * @param endAt         生效终点（不含），epoch 毫秒（UTC）
 * @param altitudeLower 高度下限（含），单位米
 * @param altitudeUpper 高度上限（不含），单位米
 * @param priority      走廊优先级（>=0）；同优先级有效走廊时空不得重叠
 * @param dispatcherId  授权调度员标识
 * @param reason        预留原因（可选，缺省为 EMERGENCY_CORRIDOR_PREEMPTION），让渡记录固化同一原因
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorCreateRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull @Min(0) Long startAt,
        @NotNull @Min(0) Long endAt,
        @NotNull @Min(0) @Max(100000) Integer altitudeLower,
        @NotNull @Min(0) @Max(100000) Integer altitudeUpper,
        @NotNull @Min(0) Integer priority,
        @NotBlank @Size(max = 64) String dispatcherId,
        @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String requestId) {

    /** 规范化：缺省原因按 EMERGENCY_CORRIDOR_PREEMPTION 处理。 */
    public CorridorCreateRequest {
        if (reason == null || reason.isBlank()) {
            reason = "EMERGENCY_CORRIDOR_PREEMPTION";
        }
    }
}
