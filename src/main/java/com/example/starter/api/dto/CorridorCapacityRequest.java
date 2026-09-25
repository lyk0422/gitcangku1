package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 走廊容量调整请求。容量仅可上调（新容量必须严格大于当前容量），立即生效，
 * 不影响已存在的生效预约。
 *
 * @param corridorKey 走廊唯一标识
 * @param newCapacity 新容量上限，取值 1～50 且严格大于当前容量
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorCapacityRequest(
        @NotBlank @Size(max = 64) String corridorKey,
        @NotNull @Min(1) @Max(50) Integer newCapacity,
        @NotBlank @Size(max = 64) String requestId) {
}
