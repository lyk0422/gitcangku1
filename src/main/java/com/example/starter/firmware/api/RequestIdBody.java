package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带 requestId 的写请求（取消发布单、设备拉取）。
 */
public record RequestIdBody(@NotBlank @Size(max = 64) String requestId) {
}
