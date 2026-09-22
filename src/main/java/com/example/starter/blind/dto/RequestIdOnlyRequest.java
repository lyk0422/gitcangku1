package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带幂等 requestId 的写操作请求（退组、关闭、批准揭盲）。
 */
public record RequestIdOnlyRequest(@NotBlank String requestId) {
}
