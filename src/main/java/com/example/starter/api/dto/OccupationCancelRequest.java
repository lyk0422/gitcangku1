package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消高度层占用请求。取消立即释放容量，占用记录作为历史保留。
 *
 * @param occupationId 要取消的占用记录标识
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 */
public record OccupationCancelRequest(
        @NotBlank @Size(max = 64) String occupationId,
        @NotBlank @Size(max = 64) String requestId) {
}
