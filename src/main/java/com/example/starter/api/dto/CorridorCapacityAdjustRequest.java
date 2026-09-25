package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 走廊容量调整请求。仅可上调不可下调，立即生效，不影响已存在的生效预约。
 *
 * @param corridorId  走廊唯一标识
 * @param capacity    新容量上限（1~50，必须大于当前容量）
 * @param corridorKey 走廊写操作幂等键，全局唯一
 */
public record CorridorCapacityAdjustRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull @Min(1) @Max(50) Integer capacity,
        @NotBlank @Size(max = 64) String corridorKey) {
}
