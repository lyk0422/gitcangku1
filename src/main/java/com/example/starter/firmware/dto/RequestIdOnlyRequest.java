package com.example.starter.firmware.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带幂等请求号的写操作请求（如取消发布单）。
 *
 * @param requestId 全局唯一请求号，用于幂等去重
 */
public record RequestIdOnlyRequest(@NotBlank String requestId) {
}
