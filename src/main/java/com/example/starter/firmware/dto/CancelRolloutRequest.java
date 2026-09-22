package com.example.starter.firmware.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消发布单请求。
 *
 * @param requestId 全局唯一请求 ID，用于幂等去重
 */
public record CancelRolloutRequest(@NotBlank String requestId) {
}
