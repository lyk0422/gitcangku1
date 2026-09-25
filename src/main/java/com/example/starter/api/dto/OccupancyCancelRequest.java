package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消高度层占用请求。取消立即释放容量，记录历史保留。
 *
 * @param occupancyId 占用记录标识
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyCancelRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 64) String requestId) {
}
